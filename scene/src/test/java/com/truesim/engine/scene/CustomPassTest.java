package com.truesim.engine.scene;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_UNDEFINED;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_FRAGMENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_VERTEX_BIT;
import static org.lwjgl.vulkan.VK10.vkCmdCopyImageToBuffer;

import com.truesim.engine.graph.ImageDesc;
import com.truesim.engine.graph.Access;
import com.truesim.engine.graph.Clear;
import com.truesim.engine.graph.Buffer;
import com.truesim.engine.graph.BufferAccess;
import com.truesim.engine.graph.ExternalBuffer;
import com.truesim.engine.graph.ExternalImage;
import com.truesim.engine.graph.FrameGraph;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Image;
import com.truesim.engine.graph.Key;
import com.truesim.engine.graph.PassContext;
import com.truesim.engine.graph.Technique;
import com.truesim.engine.render.GeometryPool;
import com.truesim.engine.render.GpuScene;
import com.truesim.engine.vulkan.GpuBuffer;
import com.truesim.engine.vulkan.GpuImage;
import com.truesim.engine.vulkan.Pipeline;
import com.truesim.engine.vulkan.Push;
import com.truesim.engine.vulkan.ShaderModule;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.engine.vulkan.VulkanException;
import com.truesim.engine.vulkan.VulkanInstance;
import com.truesim.scene.assets.mesh.MeshData;
import com.truesim.scene.assets.mesh.VertexFormat;
import com.truesim.scene.meshbuild.MeshBuilder;
import com.truesim.engine.vulkan.Format;
import java.lang.foreign.ValueLayout;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkBufferImageCopy;

/**
 * A game's own passes, as one would write them: shaders of its own importing the engine's modules (the instance layout
 * of engine-render's {@code meshlets.common}), the engine's instances read through the frame graph, and the pass
 * helpers: push constants, a dispatch, a fill, a copy and a draw, no Vulkan calls of its own.
 */
class CustomPassTest {

    private static final int SIZE = 8;
    private static final Key<Image> OUT = Key.image("test.tinted");

    /** Reads the instances' positions with a compute pass, then draws the target tinted by one of them. */
    private record Tint(GpuScene scene, ExternalBuffer instances, ExternalBuffer readback, Pipeline positions,
        Pipeline tint, Push push) implements Technique {

        @Override
        public List<Key<?>> provides() {
            return List.of(OUT);
        }

        @Override
        public void build(GraphBuilder g) {
            int count = scene.capacity();
            Buffer stored = g.importBuffer(instances);
            Buffer out = g.importBuffer(readback);
            Buffer found = g.createBuffer("positions", 16L * count);
            Image target = g.create("tinted", ImageDesc.of(Format.R8G8B8A8_UNORM, SIZE, SIZE));
            g.pass("clear positions").write(found, BufferAccess.TRANSFER_DST).record(ctx -> ctx.fill(found, 0));
            g.pass("find positions").read(stored, BufferAccess.STORAGE_READ).write(found, BufferAccess.STORAGE_WRITE)
                .record(ctx -> ctx.dispatch(positions, push.clear().address(ctx.address(stored))
                    .address(ctx.address(found)).u32(count), (count + 63) / 64));
            g.pass("read back").read(found, BufferAccess.TRANSFER_SRC).write(out, BufferAccess.TRANSFER_DST)
                .record(ctx -> ctx.copy(found, out, 16L * count));
            g.pass("tint").read(found, BufferAccess.DRAW_READ).attach(target, Clear.color(0, 0, 0, 1))
                .record(ctx -> ctx.draw(tint, push.clear().address(ctx.address(found)), 3, 1));
            g.provide(OUT, target);
        }
    }

    private static MeshData square() {
        return new MeshBuilder(VertexFormat.POSITIONS_ONLY, 4)
            .positions(new float[] {-1, 0, -1, 1, 0, -1, 1, 0, 1, -1, 0, 1})
            .submesh(new int[] {0, 2, 1, 0, 3, 2}).buildFlat();
    }

    @Test
    void aGamesPassesReadTheEngineThroughTheGraph() {
        VulkanInstance instance;
        try {
            instance = VulkanInstance.builder().applicationName("tests").validation(true)
                .synchronizationValidation(true).surfaces(false).build();
        } catch (VulkanException | UnsatisfiedLinkError e) {
            assumeThat(e).as("no Vulkan available").isNull();
            throw e;
        }
        try (instance) {
            assumeThat(instance.gpus()).as("no Vulkan 1.3 GPU").isNotEmpty();
            int usage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
            try (VulkanDevice device = VulkanDevice.createHeadless(instance.bestGpu());
                 FrameGraph graph = new FrameGraph(device, 2);
                 GeometryPool pool = new GeometryPool(device, GeometryPool.Capacity.of(64, 4, 2), graph.frameSlots());
                 GpuScene scene = new GpuScene(device, pool, 4, graph.frameSlots());
                 GpuBuffer readback = GpuBuffer.hostVisible(device, 16L * 4, VK_BUFFER_USAGE_TRANSFER_DST_BIT);
                 GpuBuffer pixels = GpuBuffer.hostVisible(device, 4L * SIZE * SIZE, VK_BUFFER_USAGE_TRANSFER_DST_BIT);
                 GpuImage target = new GpuImage(device, SIZE, SIZE, VK_FORMAT_R8G8B8A8_UNORM, usage,
                     VK_IMAGE_ASPECT_COLOR_BIT);
                 ShaderModule positionsShader = ShaderModule.load(device, "shaders/custom/positions.spv");
                 ShaderModule tintShader = ShaderModule.load(device, "shaders/custom/tint.spv");
                 Pipeline positions = Pipeline.compute(device, positionsShader, "positionsMain", 20);
                 Pipeline tint = Pipeline.graphics(device).stage(VK_SHADER_STAGE_VERTEX_BIT, tintShader, "coverMain")
                     .stage(VK_SHADER_STAGE_FRAGMENT_BIT, tintShader, "tintMain").color(VK_FORMAT_R8G8B8A8_UNORM)
                     .pushConstants(8).doubleSided().build()) {
                int mesh = pool.add(square());
                scene.set(1, mesh, 0, 1, true, 2.0, 4.0, 6.0, 0, 0, 0, 1, 1, 1, 1);
                ExternalImage output = new ExternalImage("target", SIZE, SIZE, Format.R8G8B8A8_UNORM, usage,
                    VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL).set(target.handle(), target.view());
                graph.add(new Tint(scene, new ExternalBuffer("instances", scene.instanceBuffer()),
                    new ExternalBuffer("readback", readback), positions, tint, new Push())).output(OUT, output);
                graph.beginFrame();
                graph.submit(0, 0);
                device.waitIdle();
                float[] found = readback.memory().toArray(ValueLayout.JAVA_FLOAT);
                assertThat(found[4]).as("instance 1, x").isEqualTo(2);
                assertThat(found[6]).as("instance 1, z").isEqualTo(6);
                assertThat(found[7]).as("instance 1, live").isEqualTo(1);
                assertThat(found[3]).as("instance 0, a hole").isZero();
                device.submitAndWait(device.graphics(), commands -> {
                    try (MemoryStack stack = stackPush()) {
                        VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
                        region.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
                        region.imageExtent().set(SIZE, SIZE, 1);
                        vkCmdCopyImageToBuffer(commands, target.handle(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                            pixels.handle(), region);
                    }
                });
                int pixel = pixels.memory().getAtIndex(ValueLayout.JAVA_INT, SIZE * SIZE / 2 + SIZE / 2);
                assertThat(pixel & 0xff).as("red: x / 10").isCloseTo(51, org.assertj.core.data.Offset.offset(1));
                assertThat(pixel >>> 16 & 0xff).as("blue: z / 10").isCloseTo(153,
                    org.assertj.core.data.Offset.offset(1));
                assertThat(graph.passes()).containsExactly("clear positions", "find positions", "read back", "tint");
            }
        }
    }
}
