package com.truesim.engine.vulkan;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.VK_COLOR_COMPONENT_A_BIT;
import static org.lwjgl.vulkan.VK10.VK_COLOR_COMPONENT_B_BIT;
import static org.lwjgl.vulkan.VK10.VK_COLOR_COMPONENT_G_BIT;
import static org.lwjgl.vulkan.VK10.VK_COLOR_COMPONENT_R_BIT;
import static org.lwjgl.vulkan.VK10.VK_COMPARE_OP_GREATER_OR_EQUAL;
import static org.lwjgl.vulkan.VK10.VK_CULL_MODE_BACK_BIT;
import static org.lwjgl.vulkan.VK10.VK_CULL_MODE_NONE;
import static org.lwjgl.vulkan.VK10.VK_DYNAMIC_STATE_SCISSOR;
import static org.lwjgl.vulkan.VK10.VK_DYNAMIC_STATE_VIEWPORT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_UNDEFINED;
import static org.lwjgl.vulkan.VK10.VK_FRONT_FACE_COUNTER_CLOCKWISE;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_BIND_POINT_COMPUTE;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_BIND_POINT_GRAPHICS;
import static org.lwjgl.vulkan.VK10.VK_POLYGON_MODE_FILL;
import static org.lwjgl.vulkan.VK10.VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
import static org.lwjgl.vulkan.VK10.VK_SAMPLE_COUNT_1_BIT;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_ALL;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_COMPUTE_BIT;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_PIPELINE_DEPTH_STENCIL_STATE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.vkCmdBindPipeline;
import static org.lwjgl.vulkan.VK10.nvkCmdPushConstants;
import static org.lwjgl.vulkan.VK10.vkCreateComputePipelines;
import static org.lwjgl.vulkan.VK10.vkCreateGraphicsPipelines;
import static org.lwjgl.vulkan.VK10.vkCreatePipelineLayout;
import static org.lwjgl.vulkan.VK10.vkDestroyPipeline;
import static org.lwjgl.vulkan.VK10.vkDestroyPipelineLayout;
import static org.lwjgl.vulkan.VK10.VK_NULL_HANDLE;
import static org.lwjgl.vulkan.EXTMeshShader.VK_SHADER_STAGE_MESH_BIT_EXT;
import static org.lwjgl.vulkan.VK13.VK_STRUCTURE_TYPE_PIPELINE_RENDERING_CREATE_INFO;

import com.truesim.annotations.AllowAllocation;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkComputePipelineCreateInfo;
import org.lwjgl.vulkan.VkGraphicsPipelineCreateInfo;
import org.lwjgl.vulkan.VkPipelineColorBlendAttachmentState;
import org.lwjgl.vulkan.VkPipelineColorBlendStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineDepthStencilStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineDynamicStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineInputAssemblyStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineMultisampleStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineRasterizationStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineRenderingCreateInfo;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkPipelineVertexInputStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineViewportStateCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;

/**
 * A compute or graphics pipeline with its layout. Shaders get their data through buffer device addresses passed in
 * push constants ({@link #push}), so pipelines need no descriptor sets: one push constant range for all stages.
 * Graphics pipelines render with dynamic rendering, have dynamic viewport and scissor, pull vertices themselves (no
 * vertex input) and use reverse depth (greater or equal passes; clear depth to 0).
 *
 * <pre>{@code
 * Pipeline draw = Pipeline.graphics(device)
 *     .stage(VK_SHADER_STAGE_VERTEX_BIT, shaders, "vertexMain")
 *     .stage(VK_SHADER_STAGE_FRAGMENT_BIT, shaders, "fragmentMain")
 *     .color(VK_FORMAT_R8G8B8A8_UNORM)
 *     .depth(VK_FORMAT_D32_SFLOAT)
 *     .pushConstants(32)
 *     .build();
 *
 * draw.bind(commands);
 * draw.push(commands, pushData, 32);          // e.g. pointers to the frame's buffers
 * }</pre>
 */
@AllowAllocation("created rarely")
public final class Pipeline implements AutoCloseable {

    private final VulkanDevice device;
    private final long handle;
    private final long layout;
    private final int bindPoint;
    private final int pushBytes;
    private boolean closed;

    private Pipeline(VulkanDevice device, long handle, long layout, int bindPoint, int pushBytes) {
        this.device = device;
        this.handle = handle;
        this.layout = layout;
        this.bindPoint = bindPoint;
        this.pushBytes = pushBytes;
    }

    /** A compute pipeline running {@code entry} of {@code module}, with {@code pushBytes} of push constants. */
    public static Pipeline compute(VulkanDevice device, ShaderModule module, String entry, int pushBytes) {
        try (MemoryStack stack = stackPush()) {
            long layout = createLayout(device, stack, pushBytes);
            VkComputePipelineCreateInfo.Buffer info = VkComputePipelineCreateInfo.calloc(1, stack)
                .sType(VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO)
                .layout(layout);
            info.stage()
                .sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                .stage(VK_SHADER_STAGE_COMPUTE_BIT)
                .module(module.handle())
                .pName(stack.UTF8(entry));
            LongBuffer pipeline = stack.mallocLong(1);
            try {
                Vulkan.check(vkCreateComputePipelines(device.handle(), VK_NULL_HANDLE, info, null, pipeline),
                    "Could not create the compute pipeline " + entry + " of " + module.name());
            } catch (VulkanException e) {
                vkDestroyPipelineLayout(device.handle(), layout, null);
                throw e;
            }
            return new Pipeline(device, pipeline.get(0), layout, VK_PIPELINE_BIND_POINT_COMPUTE, pushBytes);
        }
    }

    /** Starts describing a graphics pipeline. */
    public static Graphics graphics(VulkanDevice device) {
        return new Graphics(device);
    }

    private static long createLayout(VulkanDevice device, MemoryStack stack, int pushBytes) {
        if (pushBytes < 0 || pushBytes % 4 != 0 || pushBytes > 128) {
            throw new IllegalArgumentException("Push constants are 0 to 128 bytes, a multiple of 4: " + pushBytes);
        }
        VkPipelineLayoutCreateInfo info = VkPipelineLayoutCreateInfo.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO);
        if (pushBytes > 0) {
            info.pPushConstantRanges(VkPushConstantRange.calloc(1, stack)
                .stageFlags(VK_SHADER_STAGE_ALL)
                .size(pushBytes));
        }
        LongBuffer layout = stack.mallocLong(1);
        Vulkan.check(vkCreatePipelineLayout(device.handle(), info, null, layout), "Could not create a pipeline layout");
        return layout.get(0);
    }

    public long handle() {
        return handle;
    }

    public long layout() {
        return layout;
    }

    /** How many bytes of push constants the pipeline takes. */
    public int pushBytes() {
        return pushBytes;
    }

    /** Binds the pipeline. Allocates nothing. */
    public void bind(VkCommandBuffer commands) {
        vkCmdBindPipeline(commands, bindPoint, handle);
    }

    /** Sets the push constants from {@code size} bytes at native address {@code data}. Allocates nothing. */
    public void push(VkCommandBuffer commands, long data, int size) {
        if (size > pushBytes) {
            throw new IllegalArgumentException("The pipeline takes " + pushBytes + " bytes of push constants: " + size);
        }
        nvkCmdPushConstants(commands, layout, VK_SHADER_STAGE_ALL, 0, size, data);
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            vkDestroyPipeline(device.handle(), handle, null);
            vkDestroyPipelineLayout(device.handle(), layout, null);
        }
    }

    /** Describes a graphics pipeline; {@link #build()} creates it. */
    public static final class Graphics {
        private record Stage(int stage, ShaderModule module, String entry) {
        }

        private final VulkanDevice device;
        private final List<Stage> stages = new ArrayList<>();
        private final List<Integer> colors = new ArrayList<>();
        private int depth = VK_FORMAT_UNDEFINED;
        private int pushBytes;
        private boolean cullBack = true;

        private Graphics(VulkanDevice device) {
            this.device = device;
        }

        /**
         * Adds a shader stage, e.g. {@code VK_SHADER_STAGE_VERTEX_BIT}, or task and mesh stages for mesh shading.
         */
        public Graphics stage(int stage, ShaderModule module, String entry) {
            stages.add(new Stage(stage, Objects.requireNonNull(module, "module"), Objects.requireNonNull(entry,
                "entry")));
            return this;
        }

        /** Adds a color attachment of {@code format}. */
        public Graphics color(int format) {
            colors.add(format);
            return this;
        }

        /** A depth attachment of {@code format}, tested and written with reverse depth. */
        public Graphics depth(int format) {
            this.depth = format;
            return this;
        }

        public Graphics pushConstants(int bytes) {
            this.pushBytes = bytes;
            return this;
        }

        /** Draws back faces too; by default, counter-clockwise triangles are front faces and back faces are culled. */
        public Graphics doubleSided() {
            this.cullBack = false;
            return this;
        }

        public Pipeline build() {
            if (stages.isEmpty()) {
                throw new IllegalStateException("A graphics pipeline needs shader stages");
            }
            try (MemoryStack stack = stackPush()) {
                long layout = createLayout(device, stack, pushBytes);
                VkPipelineShaderStageCreateInfo.Buffer shaderStages =
                    VkPipelineShaderStageCreateInfo.calloc(stages.size(), stack);
                for (int i = 0; i < stages.size(); i++) {
                    Stage stage = stages.get(i);
                    shaderStages.get(i)
                        .sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                        .stage(stage.stage)
                        .module(stage.module.handle())
                        .pName(stack.UTF8(stage.entry));
                }
                var colorFormats = stack.mallocInt(colors.size());
                VkPipelineColorBlendAttachmentState.Buffer blends =
                    VkPipelineColorBlendAttachmentState.calloc(colors.size(), stack);
                for (int i = 0; i < colors.size(); i++) {
                    colorFormats.put(i, colors.get(i));
                    blends.get(i).colorWriteMask(VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT
                        | VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT);
                }
                VkPipelineRenderingCreateInfo rendering = VkPipelineRenderingCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_RENDERING_CREATE_INFO)
                    .colorAttachmentCount(colors.size())
                    .pColorAttachmentFormats(colorFormats)
                    .depthAttachmentFormat(depth);
                boolean meshShading = false;
                for (Stage stage : stages) {
                    meshShading |= stage.stage == VK_SHADER_STAGE_MESH_BIT_EXT;
                }
                VkGraphicsPipelineCreateInfo.Buffer info = VkGraphicsPipelineCreateInfo.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO)
                    .pNext(rendering.address())
                    .pStages(shaderStages)
                    .pViewportState(VkPipelineViewportStateCreateInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO)
                        .viewportCount(1)
                        .scissorCount(1))
                    .pRasterizationState(VkPipelineRasterizationStateCreateInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO)
                        .polygonMode(VK_POLYGON_MODE_FILL)
                        .cullMode(cullBack ? VK_CULL_MODE_BACK_BIT : VK_CULL_MODE_NONE)
                        .frontFace(VK_FRONT_FACE_COUNTER_CLOCKWISE)
                        .lineWidth(1))
                    .pMultisampleState(VkPipelineMultisampleStateCreateInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO)
                        .rasterizationSamples(VK_SAMPLE_COUNT_1_BIT))
                    .pDepthStencilState(VkPipelineDepthStencilStateCreateInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_PIPELINE_DEPTH_STENCIL_STATE_CREATE_INFO)
                        .depthTestEnable(depth != VK_FORMAT_UNDEFINED)
                        .depthWriteEnable(depth != VK_FORMAT_UNDEFINED)
                        .depthCompareOp(VK_COMPARE_OP_GREATER_OR_EQUAL))
                    .pColorBlendState(VkPipelineColorBlendStateCreateInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO)
                        .pAttachments(blends))
                    .pDynamicState(VkPipelineDynamicStateCreateInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO)
                        .pDynamicStates(stack.ints(VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR)))
                    .layout(layout);
                if (!meshShading) { // mesh shaders make their own primitives
                    info.pVertexInputState(VkPipelineVertexInputStateCreateInfo.calloc(stack)
                            .sType(VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO))
                        .pInputAssemblyState(VkPipelineInputAssemblyStateCreateInfo.calloc(stack)
                            .sType(VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO)
                            .topology(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST));
                }
                LongBuffer pipeline = stack.mallocLong(1);
                try {
                    Vulkan.check(vkCreateGraphicsPipelines(device.handle(), VK_NULL_HANDLE, info, null, pipeline),
                        "Could not create a graphics pipeline from " + stages.getFirst().module.name());
                } catch (VulkanException e) {
                    vkDestroyPipelineLayout(device.handle(), layout, null);
                    throw e;
                }
                return new Pipeline(device, pipeline.get(0), layout, VK_PIPELINE_BIND_POINT_GRAPHICS, pushBytes);
            }
        }
    }
}
