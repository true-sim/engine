package com.truesim.engine.scene;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_D32_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_UNDEFINED;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT;

import com.truesim.common.jobs.TaskScheduler;
import com.truesim.common.math.Vec3;
import com.truesim.common.state.ChangeClock;
import com.truesim.engine.graph.ExternalImage;
import com.truesim.engine.graph.FrameGraph;
import com.truesim.engine.graph.ViewPort;
import com.truesim.engine.render.Camera;
import com.truesim.engine.render.ClusteredForwardShading;
import com.truesim.engine.render.GeometryPool;
import com.truesim.engine.render.GpuScene;
import com.truesim.engine.render.Keys;
import com.truesim.engine.render.Materials;
import com.truesim.engine.render.MeshletRenderer;
import com.truesim.engine.render.ProbeVolume;
import com.truesim.engine.render.SceneGeometry;
import com.truesim.engine.render.SceneView;
import com.truesim.engine.render.ShadingHookOverrides;
import com.truesim.engine.render.Textures;
import com.truesim.engine.render.ViewOptions;
import com.truesim.engine.render.materials.ProbeVolumeParameters;
import com.truesim.engine.vulkan.Format;
import com.truesim.engine.vulkan.FrameSlots;
import com.truesim.engine.vulkan.GpuImage;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.engine.vulkan.VulkanException;
import com.truesim.engine.vulkan.VulkanInstance;
import com.truesim.scene.assets.AssetCache;
import com.truesim.scene.core.ComponentStore;
import com.truesim.scene.core.SceneGraph;
import com.truesim.scene.render.MaterialData;
import com.truesim.scene.render.Renderable;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Override components followed by the renderer's per-instance overrides: an entry per overriding node, the nearest
 * one at or above a renderable giving its entry, a node overriding below another taking its own, entries following
 * components replaced and removed and nodes moved, renderables leaving their entry when they leave the subtree;
 * {@code entryOf} for a library's own drawing of a node.
 */
class ShadingHookOverrideSyncTest {

    @Test
    void renderablesTakeTheEntryOfTheNearestOverridingNodeAboveThem() throws InterruptedException {
        VulkanInstance instance;
        try {
            instance = VulkanInstance.builder().applicationName("overrides").surfaces(false).build();
        } catch (VulkanException | UnsatisfiedLinkError e) {
            assumeThat(e).as("no Vulkan available").isNull();
            throw e;
        }
        FrameSlots slots = new FrameSlots(2);
        int usage = VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
        try (instance; VulkanDevice device = VulkanDevice.createHeadless(instance.bestGpu());
             GeometryPool pool = new GeometryPool(device, GeometryPool.Capacity.of(1 << 20, 10_000, 4), slots);
             Materials materials = new Materials(device, 16, slots);
             Textures textures = new Textures(device, slots);
             TaskScheduler tasks = new TaskScheduler();
             AssetCache assets = new AssetCache(tasks);
             SceneResources resources = new SceneResources(assets, pool, materials, textures);
             GpuScene gpu = new GpuScene(device, pool, 64, slots);
             MeshletRenderer renderer = new MeshletRenderer(device, gpu, VK_FORMAT_R8G8B8A8_UNORM,
                 VK_FORMAT_D32_SFLOAT, slots);
             GpuImage target = new GpuImage(device, 32, 32, VK_FORMAT_R8G8B8A8_UNORM, usage,
                 VK_IMAGE_ASPECT_COLOR_BIT);
             FrameGraph graph = new FrameGraph(device, slots)) {
            Camera camera = new Camera().set(0, 0, -1, 0, 1, 0, Math.PI / 3, 32, 32, 0.1);
            SceneGeometry geometry = SceneGeometry.meshlets(renderer);
            ViewPort port = graph.addView("main").size(32, 32);
            SceneView view = geometry.createView(port, camera, ViewOptions.of(1 << 12, 1 << 20));
            port.add(new ClusteredForwardShading(geometry, view, VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_D32_SFLOAT))
                .output(Keys.SCENE_COLOR, new ExternalImage("target", 32, 32, Format.R8G8B8A8_UNORM, usage,
                    VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL).set(target.handle(),
                    target.view()));
            SceneGraph scene = new SceneGraph(new ChangeClock());
            ComponentStore<Renderable> renderables = scene.components().store(Renderable.TYPE);
            // building / room / {chair, lamp}; building / wall; outside
            long building = scene.create();
            long room = scene.create(building);
            long chair = scene.create(room);
            long lamp = scene.create(room);
            long wall = scene.create(building);
            long outside = scene.create();
            long emitter = scene.create(lamp); // no renderable: a library's own drawing
            for (long node : new long[] {building, room, chair, lamp, wall, outside}) {
                scene.setTranslation(node, 0, 0, -5);
                renderables.set(node, Renderable.of(SceneBenchmark.ROCK, List.of(SceneBenchmark.GRANITE)));
            }
            SceneSync sync = new SceneSync(resources, scene, gpu);
            ShadingHookOverrides overrides = gpu.hookOverrides();
            ShadingHookOverrideSync hooks = new ShadingHookOverrideSync(scene, sync, overrides);
            Runnable frame = () -> {
                int slot = graph.beginFrame();
                slots.begin(slot);
                tasks.drainCompleted();
                scene.update();
                sync.update();
                hooks.update();
                view.set(1, MeshletRenderer.MODE_LIT);
                graph.submit(0, 0);
                slots.end();
            };
            long deadline = System.nanoTime() + 120_000_000_000L;
            while (sync.instanceCount() < 6) {
                frame.run();
                assertThat(System.nanoTime()).as("meshes loading").isLessThan(deadline);
                Thread.sleep(5);
            }
            frame.run();
            for (long node : new long[] {building, room, chair, lamp, wall, outside}) {
                assertThat(overrides.entryOf(sync.slotOf(node))).as("no overrides: no entry").isZero();
            }
            MaterialData.Values far = MaterialData.builder("x").set("offset", 1f, 2f, 3f).build().values();
            scene.components().set(building, ShadingHookOverride.TYPE, new ShadingHookOverride(ProbeVolume.HOOK, far));
            frame.run();
            int outer = overrides.entryOf(sync.slotOf(building));
            assertThat(outer).isPositive();
            assertThat(overrides.record(outer, ProbeVolume.HOOK))
                .isEqualTo(ProbeVolumeParameters.DEFAULTS.withOffset(new Vec3(1, 2, 3)));
            for (long node : new long[] {room, chair, lamp, wall}) {
                assertThat(overrides.entryOf(sync.slotOf(node))).as("below the building").isEqualTo(outer);
            }
            assertThat(overrides.entryOf(sync.slotOf(outside))).as("outside").isZero();
            assertThat(hooks.entryOf(emitter)).as("a node drawn by a library").isEqualTo(outer);
            // The room overrides the same hook again: its subtree takes an entry of its own
            MaterialData.Values near = MaterialData.builder("x").set("offset", 7f, 8f, 9f).build().values();
            scene.components().set(room, ShadingHookOverride.TYPE, new ShadingHookOverride(ProbeVolume.HOOK, near));
            frame.run();
            int inner = overrides.entryOf(sync.slotOf(room));
            assertThat(inner).isPositive().isNotEqualTo(outer);
            assertThat(overrides.record(inner, ProbeVolume.HOOK))
                .isEqualTo(ProbeVolumeParameters.DEFAULTS.withOffset(new Vec3(7, 8, 9)));
            assertThat(overrides.entryOf(sync.slotOf(chair))).isEqualTo(inner);
            assertThat(overrides.entryOf(sync.slotOf(lamp))).isEqualTo(inner);
            assertThat(hooks.entryOf(emitter)).isEqualTo(inner);
            assertThat(overrides.entryOf(sync.slotOf(wall))).as("the building's still").isEqualTo(outer);
            // The chair moved outside: no entry; the room's override removed: the building's again
            scene.setParent(chair, outside);
            scene.components().remove(room, ShadingHookOverride.TYPE);
            frame.run();
            assertThat(overrides.entryOf(sync.slotOf(chair))).as("moved outside").isZero();
            assertThat(overrides.entryOf(sync.slotOf(lamp))).as("the room's gone").isEqualTo(outer);
            assertThat(overrides.entries()).as("the room's entry removed").isEqualTo(outer);
            // A component replaced: the same entry, new values
            scene.components().set(building, ShadingHookOverride.TYPE, new ShadingHookOverride(ProbeVolume.HOOK,
                near));
            frame.run();
            assertThat(overrides.entryOf(sync.slotOf(wall))).isEqualTo(outer);
            assertThat(overrides.record(outer, ProbeVolume.HOOK))
                .isEqualTo(ProbeVolumeParameters.DEFAULTS.withOffset(new Vec3(7, 8, 9)));
            assertThat(overrides.switched(outer, ProbeVolume.HOOK)).as("as the view runs it").isNull();
            // The wall switches the hook off for itself
            scene.components().set(wall, ShadingHookOverride.TYPE, ShadingHookOverride.off(ProbeVolume.HOOK));
            frame.run();
            int dark = overrides.entryOf(sync.slotOf(wall));
            assertThat(dark).isPositive().isNotEqualTo(outer);
            assertThat(overrides.switched(dark, ProbeVolume.HOOK)).isFalse();
            assertThat(overrides.switched(outer, ProbeVolume.HOOK)).isNull();
            scene.components().remove(wall, ShadingHookOverride.TYPE);
            // A hook the shaders lack is left out; every override removed: no entries
            scene.components().set(outside, ShadingHookOverride.TYPE, new ShadingHookOverride("no.such.hook", far));
            scene.components().remove(building, ShadingHookOverride.TYPE);
            frame.run();
            for (long node : new long[] {building, room, chair, lamp, wall, outside}) {
                assertThat(overrides.entryOf(sync.slotOf(node))).as("none left").isZero();
            }
            assertThat(overrides.entries()).isZero();
            assertThat(hooks.entryOf(emitter)).isZero();
            device.waitIdle();
            sync.close();
        }
    }
}
