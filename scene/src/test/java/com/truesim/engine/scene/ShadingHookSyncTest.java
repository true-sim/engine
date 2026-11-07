package com.truesim.engine.scene;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import com.truesim.common.math.Vec3;
import com.truesim.common.state.ChangeClock;
import com.truesim.engine.render.ProbeVolume;
import com.truesim.engine.render.ShadingHookValues;
import com.truesim.engine.render.ShadingHooks;
import com.truesim.engine.render.materials.ProbeVolumeParameters;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.engine.vulkan.VulkanException;
import com.truesim.engine.vulkan.VulkanInstance;
import com.truesim.scene.core.SceneGraph;
import com.truesim.scene.render.MaterialData;
import org.junit.jupiter.api.Test;

/**
 * Shading hook components followed by the renderer's hook values: set by field name, the first node's counting, off
 * while hidden or turned off, replaced, dropped with the last node holding them.
 */
class ShadingHookSyncTest {

    @Test
    void componentsAreTheHooksValues() {
        VulkanInstance instance;
        try {
            instance = VulkanInstance.builder().applicationName("hooks").surfaces(false).build();
        } catch (VulkanException | UnsatisfiedLinkError e) {
            assumeThat(e).as("no Vulkan available").isNull();
            throw e;
        }
        try (instance; VulkanDevice device = VulkanDevice.createHeadless(instance.bestGpu())) {
            ShadingHookValues values = ShadingHookValues.of(device);
            int hook = ShadingHooks.of(device).require(ProbeVolume.HOOK);
            SceneGraph scene = new SceneGraph(new ChangeClock());
            long first = scene.create();
            long second = scene.create();
            scene.update();
            ShadingHookSync sync = new ShadingHookSync(scene, values);
            sync.update();
            assertThat(values.authored(hook)).as("nothing held").isNull();
            scene.components().set(first, ShadingHookComponent.TYPE, new ShadingHookComponent(ProbeVolume.HOOK,
                MaterialData.builder("x").set("offset", 1f, 2f, 3f).build().values(), true));
            scene.components().set(second, ShadingHookComponent.TYPE, new ShadingHookComponent(ProbeVolume.HOOK,
                MaterialData.builder("x").set("offset", 9f, 9f, 9f).build().values(), true));
            sync.update();
            assertThat(values.authored(hook)).isEqualTo(ProbeVolumeParameters.DEFAULTS.withOffset(new Vec3(1, 2, 3)));
            assertThat(values.on(hook)).isTrue();
            long version = values.version();
            scene.setLayers(first, 0);
            scene.update();
            sync.update();
            assertThat(values.on(hook)).as("hidden: off").isFalse();
            assertThat(values.version()).as("on and off rebuild nothing").isEqualTo(version);
            scene.setLayers(first, 1);
            scene.update();
            scene.components().set(first, ShadingHookComponent.TYPE, new ShadingHookComponent(ProbeVolume.HOOK,
                MaterialData.builder("x").set("offset", 4f, 5f, 6f).build().values(), false));
            sync.update();
            assertThat(values.authored(hook)).isEqualTo(ProbeVolumeParameters.DEFAULTS.withOffset(new Vec3(4, 5, 6)));
            assertThat(values.on(hook)).as("turned off").isFalse();
            scene.remove(first);
            scene.update();
            sync.update();
            assertThat(values.authored(hook)).as("the second node's now")
                .isEqualTo(ProbeVolumeParameters.DEFAULTS.withOffset(new Vec3(9, 9, 9)));
            scene.remove(second);
            scene.update();
            sync.update();
            assertThat(values.authored(hook)).as("none left").isNull();
            assertThat(values.version()).isGreaterThan(version);
        }
    }
}
