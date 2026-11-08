package com.truesim.engine.scene.gi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;

import com.truesim.engine.graph.FrameGraph;
import com.truesim.engine.render.ProbeVolume;
import com.truesim.engine.render.ProbeVolumeLighting;
import com.truesim.engine.render.RadianceCascades;
import com.truesim.engine.vulkan.GpuBuffer;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.engine.vulkan.VulkanException;
import com.truesim.engine.vulkan.VulkanInstance;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The GI scenes' real-time images are determined by the scene (true-sim/engine#442): every scene and state, in the
 * order GiEvaluation renders them (so each starts from what the scenes before it left), without global illumination,
 * with the probe volume and with radiance cascades, rendered twice on one device, the second time after an unrelated
 * host-visible allocation that stays (3 MiB of 0x7f bytes, which moves every allocation after it): the same images
 * but for exact ties ({@link #TIES}), and no NaN in any. Small volumes and cascades, for CI's lavapipe.
 *
 * <p>It found a view's shadow table keeping the light ranges a removed shadow technique had named: the toggle
 * scene's point light (light 0) cast its shadows through the entries the earlier scenes' sun had, which no technique
 * of the new graph wrote (on lavapipe 2,754 to 2,877 of toggle 0's 3,840 floats differed, in all three images).
 */
class GiDeterminismTest {

    private static final int WIDTH = 40;
    private static final int HEIGHT = 24;
    private static final int FRAMES = 12;
    /**
     * How far an image may differ, of its light (mean absolute difference over mean absolute value): exact ties settle
     * differently from run to run on the RX 9070 XT and at times on lavapipe (the Cornell box: up to 1.0e-3 measured);
     * the stale memory this test found moved 16 to 26 % (docs/pitfalls.md).
     */
    private static final double TIES = 1.0e-2;

    @Test
    void everyGiSceneRendersTheSameAfterAnUnrelatedAllocationAndHoldsNoNaN() {
        VulkanInstance instance;
        try {
            instance = VulkanInstance.builder().applicationName("tests").validation(true).surfaces(false).build();
        } catch (VulkanException | UnsatisfiedLinkError e) {
            assumeThat(e).as("no Vulkan available").isNull();
            return;
        }
        try (instance; VulkanDevice device = VulkanDevice.createHeadless(instance.bestGpu())) {
            assumeThat(device.rayTracing()).as("no ray tracing").isTrue();
            Map<String, float[]> plain = renderAll(device);
            Map<String, float[]> padded;
            try (GpuBuffer pad = GpuBuffer.hostVisible(device, 3L << 20, VK_BUFFER_USAGE_TRANSFER_DST_BIT)) {
                pad.memory().fill((byte) 0x7f);
                padded = renderAll(device);
            }
            // Each image the same, or within TIES of its light: exact ties (a ray or a pixel on the corner where two
            // instances' faces meet: the Cornell box's) settle differently from run to run on some devices, by at
            // most 1.0e-3 of an image's light measured; the stale shadow ranges moved 16 to 26 %
            List<String> different = new ArrayList<>();
            List<String> ties = new ArrayList<>();
            List<String> nans = new ArrayList<>();
            for (Map.Entry<String, float[]> e : plain.entrySet()) {
                String name = e.getKey();
                float[] a = e.getValue();
                float[] b = padded.get(name);
                int differ = 0;
                int nan = 0;
                double off = 0;
                double light = 0;
                for (int i = 0; i < a.length; i++) {
                    differ += Float.floatToRawIntBits(a[i]) != Float.floatToRawIntBits(b[i]) ? 1 : 0;
                    nan += Float.isNaN(a[i]) || Float.isNaN(b[i]) ? 1 : 0;
                    off += Math.abs(a[i] - b[i]);
                    light += Math.abs(a[i]);
                }
                double relative = off / Math.max(light, 1.0e-30);
                String line = String.format(java.util.Locale.ROOT, "%s (%d of %d floats, %.2e of its light)", name,
                    differ, a.length, relative);
                if (relative > TIES) {
                    different.add(line);
                } else if (differ > 0) {
                    ties.add(line);
                }
                if (nan > 0) {
                    nans.add(name + " (" + nan + " floats)");
                }
            }
            System.out.printf("%d images; within the ties' bound: %s; beyond it: %s; holding NaN: %s%n", plain.size(),
                ties, different, nans);
            assertThat(different).as("images differing after an unrelated allocation").isEmpty();
            assertThat(nans).as("images holding NaN").isEmpty();
            assertThat(instance.validationErrors()).as("validation errors").isZero();
        }
    }

    /** Every scene and state's images without GI, with the probe volume and with radiance cascades, by name. */
    private static Map<String, float[]> renderAll(VulkanDevice device) {
        Map<String, float[]> images = new LinkedHashMap<>();
        try (GiHarness harness = new GiHarness(device, WIDTH, HEIGHT)) {
            FrameGraph graph = harness.graph();
            graph.settings().set(ProbeVolume.CASCADES, 1).set(ProbeVolume.PROBES, 8).set(ProbeVolume.PROBES_UP, 4)
                .set(ProbeVolume.RAYS, 64).set(ProbeVolume.FIXED_RAYS, 16)
                .set(RadianceCascades.CASCADES, 2).set(RadianceCascades.PROBES, 8).set(RadianceCascades.PROBES_UP, 4)
                .set(RadianceCascades.DIRECTIONS, 4);
            GiScenes.forEach(null, scene -> {
                String name = scene[0] + "-" + scene[1];
                int state = Integer.parseInt(scene[1]);
                images.put(name + " raster", harness.raster(scene[0], state));
                harness.realtime(scene[0], state);
                ProbeVolumeLighting.SLOT.choose(graph.settings(), ProbeVolumeLighting.PROBES);
                images.put(name + " probes", harness.render(FRAMES));
                ProbeVolumeLighting.SLOT.choose(graph.settings(), ProbeVolumeLighting.CASCADES);
                images.put(name + " cascades", harness.render(FRAMES));
                ProbeVolumeLighting.SLOT.choose(graph.settings(), ProbeVolumeLighting.PROBES);
            });
        }
        return images;
    }
}
