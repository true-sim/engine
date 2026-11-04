package com.truesim.engine.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_D32_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16B16A16_SFLOAT;

import com.truesim.common.math.color.Color;
import com.truesim.engine.graph.FrameGraph;
import com.truesim.engine.graph.Technique;
import com.truesim.engine.graph.ViewPort;
import com.truesim.engine.render.Camera;
import com.truesim.engine.render.ClusteredForwardShading;
import com.truesim.engine.render.ClusteredLights;
import com.truesim.engine.render.Environment;
import com.truesim.engine.render.EnvironmentLighting;
import com.truesim.engine.render.Material;
import com.truesim.engine.render.MeshletRenderer;
import com.truesim.engine.render.SceneView;
import com.truesim.engine.render.ScreenSpaceReflections;
import com.truesim.engine.render.SkyBackground;
import com.truesim.engine.render.TextureFormat;
import com.truesim.engine.render.Textures;
import com.truesim.engine.render.ViewOptions;
import com.truesim.engine.render.VisibilityBufferShading;
import com.truesim.engine.render.materials.MetallicRoughnessParameters;
import com.truesim.engine.settings.TechniqueKind;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.engine.vulkan.VulkanInstance;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Screenshots of screen-space reflections in the demo's scene: a mirror-like patch on the terrain among its rocks (some
 * glossy: the gold metal at roughness 0.3, the blue at 0.2), under a sky panorama, at 1920 x 1080, through the display
 * transform, for each technique and off, forward and visibility buffer. Run with {@code TRUESIM_SCREENSHOTS=true
 * TRUESIM_SSR_SHOTS=<directory> ./gradlew :engine-demo:test --tests '*ReflectionsScreenshots'}.
 */
@EnabledIfEnvironmentVariable(named = "TRUESIM_SCREENSHOTS", matches = "true")
class ReflectionsScreenshots {

    private static final int WIDTH = 1920;
    private static final int HEIGHT = 1080;

    /** A sky panorama: a deep blue zenith over a pale horizon, a warm sun glow, a dim brown ground. */
    private static MemorySegment sky(Arena arena, int width, int height) {
        MemorySegment texels = arena.allocate(8L * width * height);
        ValueLayout.OfShort half = ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(java.nio.ByteOrder.LITTLE_ENDIAN);
        for (int y = 0; y < height; y++) {
            double up = 1 - 2 * (y + 0.5) / height; // 1 at the zenith, -1 at the nadir
            for (int x = 0; x < width; x++) {
                double u = (x + 0.5) / width;
                float[] c;
                if (up >= 0) {
                    double t = Math.pow(up, 0.5);
                    c = new float[] {(float) (0.7 - 0.6 * t), (float) (0.85 - 0.6 * t), (float) (1.2 - 0.5 * t)};
                    double glow = Math.exp(-(Math.pow((u - 0.3) * 8, 2) + Math.pow((up - 0.15) * 10, 2)));
                    c[0] += (float) (6 * glow);
                    c[1] += (float) (4.5 * glow);
                    c[2] += (float) (2.5 * glow);
                } else {
                    c = new float[] {0.25f, 0.2f, 0.15f};
                }
                for (int k = 0; k < 3; k++) {
                    texels.set(half, 8L * (y * width + x) + 2L * k, Float.floatToFloat16(c[k]));
                }
                texels.set(half, 8L * (y * width + x) + 6, Float.floatToFloat16(1));
            }
        }
        return texels;
    }

    @Test
    void shots() throws Exception {
        Path directory = Path.of(System.getenv().getOrDefault("TRUESIM_SSR_SHOTS", "build/ssr-shots"));
        Files.createDirectories(directory);
        try (VulkanInstance instance = VulkanInstance.builder().applicationName("screenshots").validation(true)
                .surfaces(false).build();
             VulkanDevice device = VulkanDevice.createHeadless(instance.bestGpu());
             FrameGraph graph = new FrameGraph(device, 2);
             DemoScene world = new DemoScene(device, graph.frameSlots(), VK_FORMAT_R16G16B16A16_SFLOAT,
                 VK_FORMAT_D32_SFLOAT);
             Textures textures = new Textures(device, graph.frameSlots());
             Arena arena = Arena.ofConfined()) {
            Environment environment = Environment.panorama("sky", textures.add(TextureFormat.RGBA16F, 512, 256,
                Textures.GENERATE, sky(arena, 512, 256)), 512);
            // The flattest 10 x 10 m near the middle, the patch just above its highest point
            double bestX = 0;
            double bestZ = 0;
            double bestSpread = Double.MAX_VALUE;
            for (double cx = -40; cx <= 40; cx += 2) {
                for (double cz = -40; cz <= 40; cz += 2) {
                    double low = Double.MAX_VALUE;
                    double high = -Double.MAX_VALUE;
                    for (double dx = -5; dx <= 5; dx += 0.5) {
                        for (double dz = -5; dz <= 5; dz += 0.5) {
                            double h = DemoMeshes.height(cx + dx, cz + dz);
                            low = Math.min(low, h);
                            high = Math.max(high, h);
                        }
                    }
                    if (high - low < bestSpread) {
                        bestSpread = high - low;
                        bestX = cx;
                        bestZ = cz;
                    }
                }
            }
            double top = -Double.MAX_VALUE;
            for (double dx = -5; dx <= 5; dx += 0.25) {
                for (double dz = -5; dz <= 5; dz += 0.25) {
                    top = Math.max(top, DemoMeshes.height(bestX + dx, bestZ + dz));
                }
            }
            int mirror = world.materials().add(new Material(MetallicRoughnessParameters.DEFAULTS
                .withBaseColor(new Color(0.95f, 0.95f, 0.97f, 1)).withMetallic(1).withRoughness(0.03f)));
            float y = (float) (top + 0.03);
            float half = (float) Math.sqrt(0.5);
            // The pane is a unit square facing +Z: turned to face up, 10 x 10 m
            world.gpuScene().set(world.gpuScene().capacity() - 1, world.pane(), mirror, (float) bestX, y,
                (float) bestZ, -half, 0, 0, half, 10, 10, 1);
            // Glossy rocks on and around the patch
            world.extraRock(0, bestX - 1.5, y + 0.6, bestZ - 2.5, 1.2f);
            world.extraRock(1, bestX + 2.5, y + 0.4, bestZ - 1, 0.8f);
            world.extraRock(2, bestX + 0.5, y + 0.9, bestZ - 5.5, 1.6f);
            Camera camera = new Camera();
            ViewPort port = graph.addView("main").size(WIDTH, HEIGHT);
            SceneView view = world.geometry().createView(port, camera, ViewOptions.of(1 << 14, 1 << 22)
                .withOcclusion(true)).set(1, MeshletRenderer.MODE_LIT);
            port.add(new ClusteredForwardShading(world.geometry(), view, VK_FORMAT_R16G16B16A16_SFLOAT,
                VK_FORMAT_D32_SFLOAT)).add(new ClusteredLights(world.lights(), view))
                .add(new EnvironmentLighting(environment, view)).add(new SkyBackground(environment, view))
                .add(new ScreenSpaceReflections(view));
            try (Capture capture = new Capture(device, port)) {
                for (boolean visibility : new boolean[] {false, true}) {
                    if (visibility) {
                        port.replace(new VisibilityBufferShading(world.geometry(), view,
                            VK_FORMAT_R16G16B16A16_SFLOAT, VK_FORMAT_D32_SFLOAT));
                    }
                    for (TechniqueKind<?, ScreenSpaceReflections, Technique> mode : Arrays.asList(null,
                        ScreenSpaceReflections.LINEAR, ScreenSpaceReflections.HIZ)) {
                        ScreenSpaceReflections.SLOT.choose(graph.settings(), mode);
                        for (int frame = 0; frame < 6; frame++) {
                            world.update(1.0);
                            camera.position(bestX + 2, y + 4.5, bestZ + 14).set(-0.12, -0.33, -1, 0, 1, 0,
                                Math.toRadians(60), WIDTH, HEIGHT, 0.1);
                            graph.beginFrame();
                            graph.submit(0, 0);
                        }
                        device.waitIdle();
                        Path file = directory.resolve((visibility ? "visibility-" : "forward-")
                            + (mode == null ? "off" : mode.id()) + ".png");
                        capture.write(file);
                        System.out.println("wrote " + file.toAbsolutePath());
                    }
                }
            }
            port.remove(ScreenSpaceReflections.ID);
            device.waitIdle();
            assertThat(instance.validationErrors()).isZero();
        }
    }
}
