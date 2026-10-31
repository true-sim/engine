package com.truesim.engine.vulkan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.VK_ATTACHMENT_LOAD_OP_CLEAR;
import static org.lwjgl.vulkan.VK10.VK_ATTACHMENT_STORE_OP_STORE;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_UNDEFINED;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_FRAGMENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_VERTEX_BIT;
import static org.lwjgl.vulkan.VK10.vkCmdCopyImageToBuffer;
import static org.lwjgl.vulkan.VK10.vkCmdDispatch;
import static org.lwjgl.vulkan.VK10.vkCmdDraw;
import static org.lwjgl.vulkan.VK10.vkCmdSetScissor;
import static org.lwjgl.vulkan.VK10.vkCmdSetViewport;
import static org.lwjgl.vulkan.VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_NONE;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_TRANSFER_READ_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_TOP_OF_PIPE_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_TRANSFER_BIT;
import static org.lwjgl.vulkan.VK13.VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO;
import static org.lwjgl.vulkan.VK13.VK_STRUCTURE_TYPE_RENDERING_INFO;
import static org.lwjgl.vulkan.VK13.vkCmdBeginRendering;
import static org.lwjgl.vulkan.VK13.vkCmdEndRendering;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkRect2D;
import org.lwjgl.vulkan.VkRenderingAttachmentInfo;
import org.lwjgl.vulkan.VkRenderingInfo;
import org.lwjgl.vulkan.VkViewport;

/** Slang shaders, compute and graphics pipelines, device addresses and uploads, checked on the GPU. */
class RenderingTest {

    private static final int SIZE = 64;

    /** A headless instance with validation (required on CI), or the test is skipped without a Vulkan 1.3 GPU. */
    private static VulkanInstance instance() {
        VulkanInstance instance;
        try {
            instance = VulkanInstance.builder().applicationName("tests").validation(true).surfaces(false).build();
        } catch (VulkanException | UnsatisfiedLinkError e) {
            assumeThat(e).as("no Vulkan available").isNull();
            throw e;
        }
        if ("true".equals(System.getenv("TRUESIM_REQUIRE_VALIDATION")) && !instance.validationEnabled()) {
            instance.close();
            throw new AssertionError("Validation is required (TRUESIM_REQUIRE_VALIDATION) but the layer is not active");
        }
        assumeThat(instance.gpus()).as("no Vulkan 1.3 GPU").isNotEmpty();
        return instance;
    }

    @Test
    void computeShadersWriteThroughDeviceAddresses() {
        try (VulkanInstance instance = instance(); VulkanDevice device = VulkanDevice.createHeadless(instance.bestGpu());
             ShaderModule shaders = ShaderModule.load(device, "shaders/test/squares.spv");
             Pipeline squares = Pipeline.compute(device, shaders, "squaresMain", 16);
             GpuBuffer values = GpuBuffer.hostVisible(device, 1000 * 4,
                 VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT)) {
            assertThat(squares.pushBytes()).isEqualTo(16);
            assertThat(squares.handle()).isNotZero();
            assertThat(squares.layout()).isNotZero();
            device.submitAndWait(device.compute(), commands -> {
                try (MemoryStack stack = stackPush()) {
                    long push = stack.nmalloc(8, 16);
                    MemorySegment data = MemorySegment.ofAddress(push).reinterpret(16);
                    data.set(ValueLayout.JAVA_LONG, 0, values.deviceAddress());
                    data.set(ValueLayout.JAVA_INT, 8, 1000);
                    squares.bind(commands);
                    squares.push(commands, push, 12);
                    vkCmdDispatch(commands, (1000 + 63) / 64, 1, 1);
                }
            });
            for (int i = 0; i < 1000; i++) {
                assertThat(values.memory().getAtIndex(ValueLayout.JAVA_INT, i)).isEqualTo(i * i);
            }
            assertThatIllegalArgumentException().isThrownBy(() -> squares.push(null, 0, 20));
        }
        assertThat(instance()).satisfies(VulkanInstance::close); // instances come and go
    }

    @Test
    void graphicsPipelinesTakeTheirStateAsAsked() {
        // Every state a graphics pipeline takes, made under validation: blending, multisampling, two views at once,
        // another topology, depth tested otherwise and biased, clockwise fronts, and a stencil test.
        try (VulkanInstance instance = instance(); VulkanDevice device = VulkanDevice.createHeadless(instance.bestGpu());
             ShaderModule shaders = ShaderModule.load(device, "shaders/test/triangle.spv")) {
            int depthStencil = depthStencilFormat(device); // D32S8 or D24S8: a device has one of them at least
            for (Pipeline.Blend blend : Pipeline.Blend.values()) {
                Pipeline.graphics(device).stage(VK_SHADER_STAGE_VERTEX_BIT, shaders, "vertexMain")
                    .stage(VK_SHADER_STAGE_FRAGMENT_BIT, shaders, "fragmentMain").color(VK_FORMAT_R8G8B8A8_UNORM)
                    .blend(0, blend).pushConstants(24).build().close();
            }
            Pipeline.graphics(device).stage(VK_SHADER_STAGE_VERTEX_BIT, shaders, "vertexMain")
                .stage(VK_SHADER_STAGE_FRAGMENT_BIT, shaders, "fragmentMain").color(VK_FORMAT_R8G8B8A8_UNORM)
                .depth(depthStencil)
                .samples(org.lwjgl.vulkan.VK10.VK_SAMPLE_COUNT_4_BIT)
                .viewMask(0b11)
                .topology(org.lwjgl.vulkan.VK10.VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP)
                .depthTest(org.lwjgl.vulkan.VK10.VK_COMPARE_OP_LESS, false)
                .depthBias(1.25f, 1.75f)
                .clockwise()
                .stencil(depthStencil, org.lwjgl.vulkan.VK10.VK_COMPARE_OP_EQUAL,
                    org.lwjgl.vulkan.VK10.VK_STENCIL_OP_REPLACE, 1)
                .pushConstants(24).build().close();
            assertThat(instance.validationErrors()).as("validation errors").isZero();
        }
    }

    /** A combined depth and stencil format the device can attach. */
    private static int depthStencilFormat(VulkanDevice device) {
        try (MemoryStack stack = stackPush()) {
            for (int format : new int[] {org.lwjgl.vulkan.VK10.VK_FORMAT_D32_SFLOAT_S8_UINT,
                org.lwjgl.vulkan.VK10.VK_FORMAT_D24_UNORM_S8_UINT}) {
                org.lwjgl.vulkan.VkFormatProperties properties = org.lwjgl.vulkan.VkFormatProperties.calloc(stack);
                org.lwjgl.vulkan.VK10.vkGetPhysicalDeviceFormatProperties(device.gpu().handle(), format, properties);
                if ((properties.optimalTilingFeatures()
                    & org.lwjgl.vulkan.VK10.VK_FORMAT_FEATURE_DEPTH_STENCIL_ATTACHMENT_BIT) != 0) {
                    return format;
                }
            }
        }
        throw new IllegalStateException("No depth and stencil format");
    }

    @Test
    void graphicsPipelinesPullVerticesAndRenderOffscreen() {
        try (VulkanInstance instance = instance(); VulkanDevice device = VulkanDevice.createHeadless(instance.bestGpu());
             ShaderModule shaders = ShaderModule.load(device, "shaders/test/triangle.spv");
             Pipeline triangle = Pipeline.graphics(device)
                 .stage(VK_SHADER_STAGE_VERTEX_BIT, shaders, "vertexMain")
                 .stage(VK_SHADER_STAGE_FRAGMENT_BIT, shaders, "fragmentMain")
                 .color(VK_FORMAT_R8G8B8A8_UNORM)
                 .pushConstants(24)
                 .doubleSided()
                 .build();
             GpuImage target = new GpuImage(device, SIZE, SIZE, VK_FORMAT_R8G8B8A8_UNORM,
                 VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT, VK_IMAGE_ASPECT_COLOR_BIT);
             GpuBuffer pixels = GpuBuffer.hostVisible(device, SIZE * SIZE * 4, VK_BUFFER_USAGE_TRANSFER_DST_BIT)) {
            // A triangle from the top left corner: (-1, -1), (0, -1), (-1, 3) in clip space, i.e. (0, 0), (32, 0) and
            // (0, 128) in pixels; it covers the pixels left of the line from (32, 0) to (0, 128).
            MemorySegment corners = Arena.ofAuto().allocate(6 * 4);
            float[] xy = {-1, -1, 0, -1, -1, 3};
            for (int i = 0; i < 6; i++) {
                corners.setAtIndex(ValueLayout.JAVA_FLOAT, i, xy[i]);
            }
            try (GpuBuffer positions = GpuBuffer.upload(device, corners, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
                | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT)) {
                assertThat(positions.isHostVisible()).isFalse();
                device.submitAndWait(device.graphics(), commands -> {
                    Images.transition(commands, target.handle(), VK_IMAGE_ASPECT_COLOR_BIT, VK_IMAGE_LAYOUT_UNDEFINED,
                        VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, VK_PIPELINE_STAGE_2_TOP_OF_PIPE_BIT,
                        VK_ACCESS_2_NONE, VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT,
                        VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT);
                    try (MemoryStack stack = stackPush()) {
                        VkRenderingAttachmentInfo.Buffer color = VkRenderingAttachmentInfo.calloc(1, stack)
                            .sType(VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO)
                            .imageView(target.view())
                            .imageLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
                            .loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR)
                            .storeOp(VK_ATTACHMENT_STORE_OP_STORE);
                        color.clearValue().color().float32(0, 0).float32(1, 0).float32(2, 1).float32(3, 1);
                        VkRenderingInfo rendering = VkRenderingInfo.calloc(stack)
                            .sType(VK_STRUCTURE_TYPE_RENDERING_INFO)
                            .layerCount(1)
                            .pColorAttachments(color);
                        rendering.renderArea().extent().set(SIZE, SIZE);
                        vkCmdBeginRendering(commands, rendering);
                        triangle.bind(commands);
                        vkCmdSetViewport(commands, 0, VkViewport.calloc(1, stack).width(SIZE).height(SIZE)
                            .maxDepth(1));
                        vkCmdSetScissor(commands, 0, VkRect2D.calloc(1, stack).extent(e -> e.set(SIZE, SIZE)));
                        long push = stack.nmalloc(16, 24);
                        MemorySegment data = MemorySegment.ofAddress(push).reinterpret(24);
                        data.set(ValueLayout.JAVA_FLOAT, 0, 1);       // red
                        data.set(ValueLayout.JAVA_FLOAT, 4, 0.5f);    // half green
                        data.set(ValueLayout.JAVA_FLOAT, 8, 0);
                        data.set(ValueLayout.JAVA_FLOAT, 12, 1);
                        data.set(ValueLayout.JAVA_LONG, 16, positions.deviceAddress());
                        triangle.push(commands, push, 24);
                        vkCmdDraw(commands, 3, 1, 0, 0);
                        vkCmdEndRendering(commands);
                    }
                    Images.transition(commands, target.handle(), VK_IMAGE_ASPECT_COLOR_BIT,
                        VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                        VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT, VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT,
                        VK_PIPELINE_STAGE_2_TRANSFER_BIT, VK_ACCESS_2_TRANSFER_READ_BIT);
                    try (MemoryStack stack = stackPush()) {
                        VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
                        region.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
                        region.imageExtent().set(SIZE, SIZE, 1);
                        vkCmdCopyImageToBuffer(commands, target.handle(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                            pixels.handle(), region);
                    }
                });
            }
            MemorySegment rgba = pixels.memory();
            int checked = 0;
            for (int y = 0; y < SIZE; y++) {
                for (int x = 0; x < SIZE; x++) {
                    // The pixel center's distance from the hypotenuse, in pixels; positive inside.
                    double cx = x + 0.5;
                    double cy = y + 0.5;
                    double inside = (1 - cx / 32 - cy / 128) / Math.hypot(1 / 32.0, 1 / 128.0);
                    if (Math.abs(inside) < 1) {
                        continue; // on the edge: the rasterizer's rules decide
                    }
                    int pixel = rgba.get(ValueLayout.JAVA_INT, (y * SIZE + x) * 4L);
                    // Bytes r, g, b, a: the triangle is orange (half green rounds to 0x80), the rest the blue clear.
                    assertThat(pixel).as("pixel %d, %d", x, y).isEqualTo(inside > 0 ? 0xff0080ff : 0xffff0000);
                    checked++;
                }
            }
            assertThat(checked).isGreaterThan(SIZE * SIZE * 9 / 10);
            assertThatIllegalStateException().isThrownBy(pixels::deviceAddress);
        }
    }

    @Test
    void misuseIsChecked() {
        try (VulkanInstance instance = instance(); VulkanDevice device = VulkanDevice.createHeadless(instance.bestGpu());
             ShaderModule shaders = ShaderModule.load(device, "shaders/test/squares.spv")) {
            assertThat(shaders.name()).isEqualTo("shaders/test/squares.spv");
            assertThat(shaders.handle()).isNotZero();
            assertThatIllegalArgumentException().isThrownBy(() -> ShaderModule.load(device, "shaders/none.spv"));
            assertThatIllegalArgumentException().isThrownBy(() -> ShaderModule.of(device, new byte[6], "odd"));
            assertThatIllegalArgumentException().isThrownBy(() -> ShaderModule.of(device, new byte[0], "empty"));
            assertThatIllegalArgumentException().isThrownBy(() -> Pipeline.compute(device, shaders, "squaresMain", 6));
            assertThatIllegalArgumentException().isThrownBy(() -> Pipeline.compute(device, shaders, "squaresMain",
                132));
            assertThatIllegalStateException().isThrownBy(() -> Pipeline.graphics(device).build());
            try (ShaderModule empty = ShaderModule.load(device, "shaders/test/empty.spv");
                 Pipeline none = Pipeline.compute(device, empty, "emptyMain", 0)) {
                assertThat(none.pushBytes()).isZero();
                none.close(); // twice is fine
            }
        }
    }
}
