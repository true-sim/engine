package com.truesim.engine.scene.gi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.assertj.core.api.Assumptions.assumeThat;

import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.engine.vulkan.VulkanException;
import com.truesim.engine.vulkan.VulkanInstance;
import org.junit.jupiter.api.Test;

/**
 * The GI test scenes are what they claim, validated (small enough for CI's lavapipe): every scene and state builds
 * and path traces to finite light; the leak scenes' dark room is black (closed: no light through cracks, so any light
 * a technique puts there is a leak); the toggled light and the shut door leave their rooms dark; the error of an
 * image against itself is none and against a darker one its bias.
 */
class GiScenesTest {

    @Test
    void everySceneTracesAndTheDarkRoomsAreDark() {
        VulkanInstance instance;
        try {
            instance = VulkanInstance.builder().applicationName("tests").validation(true).surfaces(false).build();
        } catch (VulkanException | UnsatisfiedLinkError e) {
            assumeThat(e).as("no Vulkan available").isNull();
            return;
        }
        try (instance; VulkanDevice device = VulkanDevice.createHeadless(instance.bestGpu())) {
            assumeThat(device.rayTracing()).as("no ray tracing").isTrue();
            int w = 40;
            int h = 24;
            try (GiHarness harness = new GiHarness(device, w, h)) {
                GiScenes.forEach(null, scene -> {
                    float[] image = harness.reference(scene[0], Integer.parseInt(scene[1]), 16, 8);
                    double mean = 0;
                    for (int p = 0; p < w * h; p++) {
                        double l = GiError.luminance(image, p);
                        assertThat(Double.isFinite(l)).as("%s %s: finite", scene[0], scene[1]).isTrue();
                        mean += l;
                    }
                    mean /= w * h;
                    if (scene[0].startsWith("leak") || scene[0].equals("toggle") && scene[1].equals("1")
                        || scene[0].equals("door") && scene[1].equals("0")) {
                        assertThat(mean).as("%s %s: dark", scene[0], scene[1]).isLessThan(1.0e-6);
                    } else {
                        assertThat(mean).as("%s %s: lit", scene[0], scene[1]).isGreaterThan(1.0e-3);
                    }
                });
                float[] cornell = harness.reference("cornell", 0, 16, 8);
                assertThat(GiError.of(cornell, cornell, w, h).mean()).isZero();
                float[] darker = cornell.clone();
                for (int i = 0; i < darker.length; i++) {
                    darker[i] *= 0.5f;
                }
                GiError error = GiError.of(darker, cornell, w, h);
                assertThat(error.bias()).isCloseTo(-0.5, within(1.0e-6));
                assertThat(error.mean()).isCloseTo(0.5, within(1.0e-6));
            }
            assertThat(instance.validationErrors()).as("validation errors").isZero();
        }
    }
}
