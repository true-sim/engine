package com.truesim.engine.render;

import static com.truesim.testing.AllocationAssertions.assertAllocationFree;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_D32_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM;

import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_UNDEFINED;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT;

import com.truesim.engine.graph.Access;
import com.truesim.engine.graph.ExternalImage;
import com.truesim.engine.graph.FrameGraph;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Image;
import com.truesim.engine.graph.ImageDesc;
import com.truesim.engine.graph.Key;
import com.truesim.engine.graph.Technique;
import com.truesim.engine.vulkan.Format;
import com.truesim.engine.vulkan.GpuImage;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.engine.vulkan.VulkanException;
import com.truesim.engine.vulkan.VulkanInstance;
import com.truesim.scene.assets.mesh.MeshData;
import com.truesim.scene.assets.mesh.VertexFormat;
import com.truesim.scene.meshbuild.MeshBuilder;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Segments traced through a top level in batches, validated: every triangle crossed, nearest first, with its instance,
 * facing and material; results a frame or more later, matched to their batch; nothing allocated per batch.
 */
class SegmentTracingTest {

    private static final Key<Image> TARGET = Key.image("test.target");

    /** The frame's one output, a cleared image: what the tracing hangs on. */
    private record Target() implements Technique {
        @Override
        public List<Key<?>> provides() {
            return List.of(TARGET);
        }

        @Override
        public void build(GraphBuilder g) {
            Image image = g.create("target", ImageDesc.of(Format.R8G8B8A8_UNORM, 4, 4));
            g.pass("clear").write(image, Access.TRANSFER_DST).record(ctx -> ctx.clear(image.whole(), 0, 0, 0, 1));
            g.provide(TARGET, image);
        }
    }

    /** A cube from 0 to 2 along each axis, faces outwards (counter-clockwise seen from outside). */
    static MeshData cube() {
        float[] positions = new float[24];
        for (int v = 0; v < 8; v++) {
            positions[3 * v] = (v & 1) * 2;
            positions[3 * v + 1] = (v >> 1 & 1) * 2;
            positions[3 * v + 2] = (v >> 2 & 1) * 2;
        }
        int[] faces = {0, 2, 3, 0, 3, 1, 4, 5, 7, 4, 7, 6, 0, 1, 5, 0, 5, 4, 2, 6, 7, 2, 7, 3, 0, 4, 6, 0, 6, 2, 1, 3, 7,
            1, 7, 5};
        return new MeshBuilder(VertexFormat.of(), 8).positions(positions).submesh(faces).buildFlat();
    }

    /** Frames until batch {@code ticket} is back, into {@code out}: its segment count. */
    private static int await(FrameGraph graph, SegmentTracing tracing, long ticket, int[] out) {
        for (int frame = 0; frame < 16; frame++) {
            int count = tracing.collect(ticket, out);
            if (count >= 0) {
                return count;
            }
            graph.beginFrame();
            graph.submit(0, 0);
        }
        throw new AssertionError("batch " + ticket + " never came back; traced " + tracing.traced());
    }

    @Test
    void segmentsCrossEveryTriangleNearestFirst() {
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
            GeometryPool pool = new GeometryPool(device, GeometryPool.Capacity.of(16, 1, 1), graph.frameSlots());
            GpuScene scene = new GpuScene(device, pool, 16, graph.frameSlots());
            MeshletRenderer renderer = new MeshletRenderer(device, scene, VK_FORMAT_R8G8B8A8_UNORM,
                VK_FORMAT_D32_SFLOAT, graph.frameSlots());
            RayTracing rayTracing = new RayTracing(device, pool, 16);
            SegmentTracing tracing = new SegmentTracing(device, rayTracing, renderer, 64, TARGET);
            int usage = VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
            GpuImage target = new GpuImage(device, 4, 4, VK_FORMAT_R8G8B8A8_UNORM, usage, VK_IMAGE_ASPECT_COLOR_BIT);
            ExternalImage output = new ExternalImage("target", 4, 4, Format.R8G8B8A8_UNORM, usage,
                VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL).set(target.handle(), target.view());
            try {
                try (SegmentTracing other = new SegmentTracing(device, rayTracing, renderer, 1)) {
                    assertThat(other.modifies())
                        .as("on the scene's color by default").containsExactly(Keys.SCENE_COLOR);
                }
                assertThatIllegalArgumentException().isThrownBy(
                    () -> new SegmentTracing(device, rayTracing, renderer, 0));
                MeshData cube = cube();
                int mesh = pool.add(cube);
                rayTracing.add(mesh, cube, 0);
                // Two walls 0.2 m thick across x: at 5 (materials from 3) and at 8 (from 7); a third casting no shadow.
                scene.set(0, mesh, 3, 5.0, -5, -5, 0, 0, 0, 1, 0.1f, 5, 5);
                scene.set(1, mesh, 7, 8.0, -5, -5, 0, 0, 0, 1, 0.1f, 5, 5);
                scene.set(2, mesh, 9, 1, false, 2.0, -5, -5, 0, 0, 0, 1, 0.1f, 5, 5);
                graph.add(new Target()).add(tracing).output(TARGET, output);
                rayTracing.origin(1000, 0, 0); // segments are moved into the top level's frame
                for (int frame = 0; frame < 12 && rayTracing.pendingBottomLevels() > 0; frame++) {
                    graph.beginFrame();
                    graph.submit(0, 0);
                }
                assertThat(tracing.capacity()).isEqualTo(64);
                double[] ends = {
                    0, 0.3, 0.2, 10, 0.3, 0.2, // through both walls
                    10, 0.3, 0.2, 0, 0.3, 0.2, // the other way
                    0, 0, 0, 3, 0, 0,          // short of them
                    6, 0, 0, 6, 0, 0,          // too short to trace
                    5.1, 0, 0, 6, 0, 0,        // from inside the first wall
                };
                assertThatIllegalArgumentException().isThrownBy(() -> tracing.submit(ends, 65));
                long ticket = tracing.submit(ends, 5);
                assertThat(ticket).isPositive();
                assertThat(tracing.submit(ends, 1)).as("one batch waits for a frame at most").isZero();
                int[] out = new int[SegmentTracing.WORDS * 64];
                assertThat(tracing.collect(ticket, out)).as("not traced yet").isEqualTo(-1);
                assertThat(await(graph, tracing, ticket, out)).isEqualTo(5);
                assertThat(tracing.traced()).isEqualTo(5);
                int w = SegmentTracing.WORDS;
                assertThat(out[0]).as("four faces crossed").isEqualTo(4);
                int[] instances = new int[4];
                int[] materials = new int[4];
                boolean[] fronts = new boolean[4];
                for (int k = 0; k < 4; k++) {
                    instances[k] = SegmentTracing.instance(out[1 + 2 * k]);
                    fronts[k] = SegmentTracing.front(out[1 + 2 * k]);
                    materials[k] = out[2 + 2 * k];
                }
                assertThat(instances).containsExactly(0, 0, 1, 1);
                assertThat(materials).containsExactly(3, 3, 7, 7);
                // Going into a mesh whose faces wind counter-clockwise seen from outside (glTF's outward faces) meets
                // their fronts, coming out their backs.
                assertThat(fronts).containsExactly(true, false, true, false);
                assertThat(out[w]).isEqualTo(4);
                assertThat(SegmentTracing.instance(out[w + 1])).as("the other way: nearest first").isEqualTo(1);
                assertThat(SegmentTracing.front(out[w + 1])).isTrue();
                assertThat(out[2 * w]).as("short of the walls").isZero();
                assertThat(out[3 * w]).as("too short").isZero();
                assertThat(out[4 * w]).as("from inside: out through its far face").isEqualTo(1);
                assertThat(SegmentTracing.front(out[4 * w + 1])).isFalse();
                assertThat(tracing.collect(ticket + 1, out)).isEqualTo(-1);
                assertThat(tracing.collect(0, out)).isEqualTo(-1);
                // Front faces only: each wall once, going in, either way; none coming out of one.
                assertThat(tracing.frontsOnly(true).frontsOnly()).isTrue();
                long entering = tracing.submit(ends, 5);
                assertThat(await(graph, tracing, entering, out)).isEqualTo(5);
                assertThat(out[0]).isEqualTo(2);
                assertThat(SegmentTracing.instance(out[3])).isEqualTo(1);
                assertThat(out[w]).isEqualTo(2);
                assertThat(SegmentTracing.instance(out[w + 1])).isEqualTo(1);
                assertThat(out[4 * w]).as("from inside").isZero();
                tracing.frontsOnly(false);
                // More walls than crossings kept: the nearest eight.
                for (int i = 3; i < 12; i++) {
                    scene.set(i, mesh, 20 + i, 10.0 + i, -5, -5, 0, 0, 0, 1, 0.1f, 5, 5);
                }
                long many = tracing.submit(new double[] {0, 0, 0, 30, 0, 0}, 1);
                assertThat(await(graph, tracing, many, out)).isEqualTo(1);
                assertThat(out[0]).isEqualTo(SegmentTracing.MAX_CROSSINGS);
                assertThat(out[2 + 2 * 7]).as("the eighth: the far face of the wall at 14").isEqualTo(24);
                // Batch after batch, a frame apart, allocating nothing.
                double[] one = {0, 0.3, 0.2, 10, 0.3, 0.2};
                long[] last = {0};
                assertAllocationFree(() -> {
                    long t = tracing.submit(one, 1);
                    last[0] = t != 0 ? t : last[0];
                    tracing.collect(last[0], out);
                    graph.beginFrame();
                    graph.submit(0, 0);
                }, 8000, 300, 8); // warmed past C2: receive and trace run once a frame
                device.waitIdle();
                assertThat(instance.validationErrors()).as("validation errors").isZero();
            } finally {
                device.waitIdle();
                graph.close();
                target.close();
                tracing.close();
                tracing.close();
                rayTracing.close();
                renderer.close();
                scene.close();
                pool.close();
            }
        }
    }
}
