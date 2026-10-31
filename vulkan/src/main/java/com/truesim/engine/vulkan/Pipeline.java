package com.truesim.engine.vulkan;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.VK_COLOR_COMPONENT_A_BIT;
import static org.lwjgl.vulkan.VK10.VK_COLOR_COMPONENT_B_BIT;
import static org.lwjgl.vulkan.VK10.VK_COLOR_COMPONENT_G_BIT;
import static org.lwjgl.vulkan.VK10.VK_COLOR_COMPONENT_R_BIT;
import static org.lwjgl.vulkan.VK10.VK_BLEND_FACTOR_ONE;
import static org.lwjgl.vulkan.VK10.VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;
import static org.lwjgl.vulkan.VK10.VK_BLEND_FACTOR_SRC_ALPHA;
import static org.lwjgl.vulkan.VK10.VK_BLEND_OP_ADD;
import static org.lwjgl.vulkan.VK10.VK_COMPARE_OP_GREATER_OR_EQUAL;
import static org.lwjgl.vulkan.VK10.VK_FRONT_FACE_CLOCKWISE;
import static org.lwjgl.vulkan.VK10.VK_STENCIL_OP_KEEP;
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
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.vkCreateComputePipelines;
import static org.lwjgl.vulkan.VK10.vkCreateGraphicsPipelines;
import static org.lwjgl.vulkan.VK10.vkDestroyPipeline;
import static org.lwjgl.vulkan.EXTMeshShader.VK_SHADER_STAGE_MESH_BIT_EXT;
import static org.lwjgl.vulkan.VK13.VK_STRUCTURE_TYPE_PIPELINE_RENDERING_CREATE_INFO;

import com.truesim.annotations.AllowAllocation;
import java.nio.ByteBuffer;
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
import org.lwjgl.vulkan.VkPipelineMultisampleStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineRasterizationStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineRenderingCreateInfo;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkPipelineVertexInputStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineViewportStateCreateInfo;
import org.lwjgl.vulkan.VkSpecializationInfo;
import org.lwjgl.vulkan.VkSpecializationMapEntry;

/**
 * A compute or graphics pipeline. Shaders get buffers through device addresses passed in push constants ({@link #push})
 * and images through slots of the device's {@link Bindless} set; every pipeline shares the device's one layout, so the
 * set is bound once per command buffer.
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
        return compute(device, module, entry, pushBytes, new int[0]);
    }

    /**
     * A compute pipeline running {@code entry} of {@code module}, with {@code pushBytes} of push constants and its
     * specialization constants (32-bit, as {@code [vk::constant_id(i)]}) i = 0, 1, ... set to {@code constants}: one
     * shader, variants the driver compiles with those values known, e.g. a branch on a feature removed.
     */
    public static Pipeline compute(VulkanDevice device, ShaderModule module, String entry, int pushBytes,
        int... constants) {
        try (MemoryStack stack = stackPush()) {
            long layout = layoutFor(device, pushBytes);
            VkComputePipelineCreateInfo.Buffer info = VkComputePipelineCreateInfo.calloc(1, stack)
                .sType(VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO)
                .layout(layout);
            info.stage()
                .sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                .stage(VK_SHADER_STAGE_COMPUTE_BIT)
                .module(module.handle())
                .pName(stack.UTF8(entry));
            if (constants.length > 0) {
                VkSpecializationMapEntry.Buffer entries = VkSpecializationMapEntry.calloc(constants.length, stack);
                ByteBuffer data = stack.malloc(4 * constants.length);
                for (int i = 0; i < constants.length; i++) {
                    entries.get(i).constantID(i).offset(4 * i).size(4);
                    data.putInt(4 * i, constants[i]);
                }
                info.stage().pSpecializationInfo(VkSpecializationInfo.calloc(stack).pMapEntries(entries).pData(data));
            }
            LongBuffer pipeline = stack.mallocLong(1);
            long cache = device.pipelineCache().handle();
            Vulkan.check(vkCreateComputePipelines(device.handle(), cache, info, null, pipeline),
                "Could not create the compute pipeline " + entry + " of " + module.name());
            return new Pipeline(device, pipeline.get(0), layout, VK_PIPELINE_BIND_POINT_COMPUTE, pushBytes);
        }
    }

    /** Starts describing a graphics pipeline. */
    public static Graphics graphics(VulkanDevice device) {
        return new Graphics(device);
    }

    /** The device's shared layout, after checking {@code pushBytes} fits it. */
    private static long layoutFor(VulkanDevice device, int pushBytes) {
        if (pushBytes < 0 || pushBytes % 4 != 0 || pushBytes > Bindless.PUSH_BYTES) {
            throw new IllegalArgumentException("Push constants are 0 to " + Bindless.PUSH_BYTES
                + " bytes, a multiple of 4: " + pushBytes);
        }
        return device.bindless().pipelineLayout();
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
        Commands.bindPipeline(commands, bindPoint, handle);
    }

    /** Sets the push constants to {@code push}'s bytes. Allocates nothing. */
    public void push(VkCommandBuffer commands, PushConstants push) {
        if (push.size() > 0) {
            push(commands, push.address(), push.size());
        }
    }

    /** Sets the push constants from {@code size} bytes at native address {@code data}. Allocates nothing. */
    public void push(VkCommandBuffer commands, long data, int size) {
        if (size > pushBytes) {
            throw new IllegalArgumentException("The pipeline takes " + pushBytes + " bytes of push constants: " + size);
        }
        Commands.pushConstants(commands, layout, VK_SHADER_STAGE_ALL, 0, size, data);
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            vkDestroyPipeline(device.handle(), handle, null);
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
        private final List<Blend> blends = new ArrayList<>(); // per color attachment; NONE past the list
        private int samples = VK_SAMPLE_COUNT_1_BIT;
        private int viewMask;
        private int topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
        private int depthCompare = VK_COMPARE_OP_GREATER_OR_EQUAL; // reverse depth
        private boolean depthWrite = true;
        private boolean depthBias;
        private float depthBiasConstant;
        private float depthBiasSlope;
        private boolean clockwise;
        private int stencil = VK_FORMAT_UNDEFINED;
        private int stencilCompare;
        private int stencilPass = VK_STENCIL_OP_KEEP;
        private int stencilReference;

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

        /** Clockwise triangles are front faces, rather than counter-clockwise ones. */
        public Graphics clockwise() {
            this.clockwise = true;
            return this;
        }

        /** How color attachment {@code attachment}'s writes blend with what it holds: {@link Blend#NONE} by default. */
        public Graphics blend(int attachment, Blend blend) {
            Objects.requireNonNull(blend, "blend");
            while (blends.size() <= attachment) {
                blends.add(Blend.NONE);
            }
            blends.set(attachment, blend);
            return this;
        }

        /** Rasterizes {@code count} samples a pixel (a {@code VK_SAMPLE_COUNT_*_BIT}), into multisampled attachments. */
        public Graphics samples(int count) {
            this.samples = count;
            return this;
        }

        /** Draws once per view of {@code mask} (bit n: view n, multiview): a headset's eyes in one pass. */
        public Graphics viewMask(int mask) {
            this.viewMask = mask;
            return this;
        }

        /** What its vertices make, a {@code VK_PRIMITIVE_TOPOLOGY_*}: triangle lists by default. */
        public Graphics topology(int topology) {
            this.topology = topology;
            return this;
        }

        /**
         * How depth is tested ({@code VK_COMPARE_OP_*}; {@code ALWAYS} for no test) and whether it is written: greater
         * or equal (reverse depth) and written by default, with a depth attachment.
         */
        public Graphics depthTest(int compareOp, boolean write) {
            this.depthCompare = compareOp;
            this.depthWrite = write;
            return this;
        }

        /** Moves its depths by {@code constant} units and {@code slope} times the triangle's slope, e.g. for shadows. */
        public Graphics depthBias(float constant, float slope) {
            this.depthBias = true;
            this.depthBiasConstant = constant;
            this.depthBiasSlope = slope;
            return this;
        }

        /**
         * A stencil attachment of {@code format} (the depth one's, for a combined format), tested with {@code compareOp}
         * against {@code reference}; {@code passOp} where it passes. Both faces alike, all bits.
         */
        public Graphics stencil(int format, int compareOp, int passOp, int reference) {
            this.stencil = format;
            this.stencilCompare = compareOp;
            this.stencilPass = passOp;
            this.stencilReference = reference;
            return this;
        }

        public Pipeline build() {
            if (stages.isEmpty()) {
                throw new IllegalStateException("A graphics pipeline needs shader stages");
            }
            try (MemoryStack stack = stackPush()) {
                long layout = layoutFor(device, pushBytes);
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
                    Blend blend = i < this.blends.size() ? this.blends.get(i) : Blend.NONE;
                    blends.get(i).colorWriteMask(VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT
                        | VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT)
                        .blendEnable(blend != Blend.NONE)
                        .srcColorBlendFactor(blend.source)
                        .dstColorBlendFactor(blend.destination)
                        .colorBlendOp(VK_BLEND_OP_ADD)
                        .srcAlphaBlendFactor(blend == Blend.NONE ? 0 : VK_BLEND_FACTOR_ONE)
                        .dstAlphaBlendFactor(blend.destination)
                        .alphaBlendOp(VK_BLEND_OP_ADD);
                }
                VkPipelineRenderingCreateInfo rendering = VkPipelineRenderingCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_RENDERING_CREATE_INFO)
                    .viewMask(viewMask)
                    .colorAttachmentCount(colors.size())
                    .pColorAttachmentFormats(colorFormats)
                    .depthAttachmentFormat(depth)
                    .stencilAttachmentFormat(stencil);
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
                        .frontFace(clockwise ? VK_FRONT_FACE_CLOCKWISE : VK_FRONT_FACE_COUNTER_CLOCKWISE)
                        .depthBiasEnable(depthBias)
                        .depthBiasConstantFactor(depthBiasConstant)
                        .depthBiasSlopeFactor(depthBiasSlope)
                        .lineWidth(1))
                    .pMultisampleState(VkPipelineMultisampleStateCreateInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO)
                        .rasterizationSamples(samples))
                    .pDepthStencilState(stencilState(VkPipelineDepthStencilStateCreateInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_PIPELINE_DEPTH_STENCIL_STATE_CREATE_INFO)
                        .depthTestEnable(depth != VK_FORMAT_UNDEFINED)
                        .depthWriteEnable(depth != VK_FORMAT_UNDEFINED && depthWrite)
                        .depthCompareOp(depthCompare)))
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
                            .topology(topology));
                }
                LongBuffer pipeline = stack.mallocLong(1);
                long cache = device.pipelineCache().handle();
                Vulkan.check(vkCreateGraphicsPipelines(device.handle(), cache, info, null, pipeline),
                    "Could not create a graphics pipeline from " + stages.getFirst().module.name());
                return new Pipeline(device, pipeline.get(0), layout, VK_PIPELINE_BIND_POINT_GRAPHICS, pushBytes);
            }
        }

        /** {@code state} with the stencil test set, if there is a stencil attachment. */
        private VkPipelineDepthStencilStateCreateInfo stencilState(VkPipelineDepthStencilStateCreateInfo state) {
            if (stencil == VK_FORMAT_UNDEFINED) {
                return state;
            }
            state.stencilTestEnable(true);
            for (org.lwjgl.vulkan.VkStencilOpState face : new org.lwjgl.vulkan.VkStencilOpState[] {state.front(),
                state.back()}) {
                face.failOp(VK_STENCIL_OP_KEEP).passOp(stencilPass).depthFailOp(VK_STENCIL_OP_KEEP)
                    .compareOp(stencilCompare).compareMask(0xff).writeMask(0xff).reference(stencilReference);
            }
            return state;
        }
    }

    /** How a color attachment's writes blend with what it holds ({@link Graphics#blend}). */
    public enum Blend {
        /** Written over: no blending. */
        NONE(0, 0),
        /** Over, by the source's alpha: {@code src * a + dst * (1 - a)}. */
        ALPHA(VK_BLEND_FACTOR_SRC_ALPHA, VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA),
        /** Over, the source's color premultiplied by its alpha: {@code src + dst * (1 - a)}. */
        PREMULTIPLIED(VK_BLEND_FACTOR_ONE, VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA),
        /** Added: {@code src + dst}, e.g. light or particles. */
        ADDITIVE(VK_BLEND_FACTOR_ONE, VK_BLEND_FACTOR_ONE);

        final int source;
        final int destination;

        Blend(int source, int destination) {
            this.source = source;
            this.destination = destination;
        }
    }
}
