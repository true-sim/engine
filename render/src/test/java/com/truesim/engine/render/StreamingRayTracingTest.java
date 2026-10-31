package com.truesim.engine.render;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_D32_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_UNDEFINED;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT;

import com.truesim.engine.graph.Access;
import com.truesim.engine.graph.Buffer;
import com.truesim.engine.graph.BufferAccess;
import com.truesim.engine.graph.FrameGraph;
import com.truesim.engine.graph.ExternalImage;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Image;
import com.truesim.engine.graph.ImageDesc;
import com.truesim.engine.graph.Key;
import com.truesim.engine.graph.Technique;
import com.truesim.engine.render.shadows.TopLevel;
import com.truesim.engine.vulkan.Format;
import com.truesim.engine.vulkan.GpuImage;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.engine.vulkan.VulkanException;
import com.truesim.engine.vulkan.VulkanInstance;
import com.truesim.scene.assets.mesh.MeshData;
import com.truesim.scene.assets.mesh.VertexFormat;
import java.lang.foreign.MemorySegment;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Bottom levels with frames in flight: built in frames, never waited for; traced once built; compacted, removed and
 * freed while frames run; a mesh's id reused; meshes and textures prepared on another thread. Validated,
 * synchronization included, a top level built every frame.
 */
class StreamingRayTracingTest {

    private static final Key<Image> TRACED = Key.image("test.traced");

    /** Builds the top level every frame, as a technique tracing rays into an image does. */
    private record Tracing(RayTracing rayTracing, MeshletRenderer renderer) implements Technique {
        @Override
        public List<Key<?>> provides() {
            return List.of(TRACED);
        }

        @Override
        public void build(GraphBuilder g) {
            Buffer top = g.request(new TopLevel(rayTracing, renderer));
            Image image = g.create("traced", ImageDesc.of(Format.R8G8B8A8_UNORM, 4, 4));
            g.pass("trace").read(top, BufferAccess.ACCELERATION_STRUCTURE_READ).write(image, Access.TRANSFER_DST)
                .record(ctx -> ctx.clear(image.whole(), 0, 0, 0, 1));
            g.provide(TRACED, image);
        }
    }

    @Test
    void bottomLevelsBuildInFramesAndLeaveThemWhenRemoved() throws Exception {
        VulkanInstance instance;
        try {
            instance = VulkanInstance.builder().applicationName("tests").validation(true)
                .synchronizationValidation(true).surfaces(false).build();
        } catch (VulkanException | UnsatisfiedLinkError e) {
            assumeThat(e).as("no Vulkan available").isNull();
            return;
        }
        try (instance; VulkanDevice device = VulkanDevice.createHeadless(instance.bestGpu())) {
            assumeThat(device.rayTracing()).as("no ray tracing").isTrue();
            FrameGraph graph = new FrameGraph(device, 2);
            GeometryPool pool = new GeometryPool(device, GeometryPool.Capacity.of(1 << 14, 256, 8), graph.frameSlots());
            GpuScene scene = new GpuScene(device, pool, 16, graph.frameSlots());
            MeshletRenderer renderer = new MeshletRenderer(device, scene, VK_FORMAT_R8G8B8A8_UNORM,
                VK_FORMAT_D32_SFLOAT, graph.frameSlots());
            RayTracing rayTracing = new RayTracing(device, pool, 16);
            int usage = VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
            GpuImage target = new GpuImage(device, 4, 4, VK_FORMAT_R8G8B8A8_UNORM, usage, VK_IMAGE_ASPECT_COLOR_BIT);
            ExternalImage output = new ExternalImage("target", 4, 4, Format.R8G8B8A8_UNORM, usage,
                VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL).set(target.handle(), target.view());
            try {
                MeshData bumps = MeshletRendererTest.grid(16, 0.1f, true, VertexFormat.POSITIONS_ONLY);
                graph.add(new Tracing(rayTracing, renderer)).output(TRACED, output);
                int mesh = pool.add(bumps);
                scene.set(0, mesh, 0, 0, -3, 0, 0, 0, 1, 1, 1, 1);
                rayTracing.add(mesh, bumps, bumps.levels().size() - 1);
                assertThat(rayTracing.hasBottomLevel(mesh)).as("not built yet: the call returns at once").isFalse();
                assertThat(rayTracing.pendingBottomLevels()).isEqualTo(1);
                long builds = rayTracing.topLevelBuilds();
                for (int frame = 0; frame < 12 && rayTracing.pendingBottomLevels() > 0; frame++) {
                    graph.beginFrame();
                    graph.submit(0, 0);
                }
                assertThat(rayTracing.pendingBottomLevels()).as("built and compacted while frames ran").isZero();
                assertThat(rayTracing.hasBottomLevel(mesh)).isTrue();
                assertThat(rayTracing.bottomLevelBytes()).isPositive();
                assertThat(rayTracing.topLevelBuilds()).as("rebuilt as the level came").isGreaterThan(builds);
                // Removed while frames run: gone from the top level at once, freed once no frame traces it.
                scene.remove(0);
                rayTracing.remove(mesh);
                pool.remove(mesh);
                assertThat(rayTracing.hasBottomLevel(mesh)).isFalse();
                assertThat(rayTracing.bottomLevelBytes()).isZero();
                // The id again, at once (its data prepared on a loader thread), and removed on its way: nothing leaks,
                // nothing is traced.
                GeometryPool.Prepared prepared = java.util.concurrent.CompletableFuture
                    .supplyAsync(() -> pool.prepare(bumps)).get();
                int again = pool.add(prepared);
                rayTracing.add(again, bumps, 0);
                graph.beginFrame();
                graph.submit(0, 0);
                rayTracing.remove(again);
                for (int frame = 0; frame < 8; frame++) {
                    graph.beginFrame();
                    graph.submit(0, 0);
                }
                assertThat(rayTracing.pendingBottomLevels()).as("the removed one dropped on its way").isZero();
                assertThat(rayTracing.hasBottomLevel(again)).isFalse();
                // And once more, kept: traced.
                rayTracing.add(again, bumps, bumps.levels().size() - 1);
                scene.set(0, again, 0, 0, -3, 0, 0, 0, 1, 1, 1, 1);
                for (int frame = 0; frame < 12 && rayTracing.pendingBottomLevels() > 0; frame++) {
                    graph.beginFrame();
                    graph.submit(0, 0);
                }
                assertThat(rayTracing.hasBottomLevel(again)).isTrue();
                // A texture prepared on a loader thread, added while frames run: uploaded and mipmapped in a frame.
                try (Textures textures = new Textures(device, graph.frameSlots())) {
                    MemorySegment texels = java.lang.foreign.Arena.ofAuto().allocate(4L * 512 * 512);
                    Textures.Prepared texture = java.util.concurrent.CompletableFuture
                        .supplyAsync(() -> textures.prepareColor(512, 512, texels)).get();
                    int slot = textures.add(texture);
                    for (int frame = 0; frame < 4; frame++) {
                        graph.beginFrame();
                        graph.submit(0, 0);
                    }
                    assertThat(textures.contains(slot)).isTrue();
                    textures.remove(slot);
                    device.waitIdle();
                }
                device.waitIdle();
                assertThat(instance.validationErrors()).as("validation errors").isZero();
            } finally {
                device.waitIdle();
                graph.close();
                target.close();
                rayTracing.close();
                renderer.close();
                scene.close();
                pool.close();
            }
        }
    }
}
