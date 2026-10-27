package com.truesim.engine.vulkan;

import org.lwjgl.vulkan.VkPhysicalDeviceVulkan14Features;

/**
 * The Vulkan 1.4 features a device has enabled, for renderer paths that use them where available. On a 1.3 device
 * every one is false ({@link #NONE}). Every 1.4 feature the GPU supports is enabled; these are the ones the engine
 * looks at.
 *
 * @param pushDescriptor descriptors pushed straight into the command buffer, without descriptor sets
 * @param dynamicRenderingLocalRead reading attachments written earlier in the same render pass (deferred, tiled)
 * @param maintenance5 among others, {@code VkPipelineCreateFlags2} and binding index buffers by size
 * @param maintenance6 among others, binding descriptors for several stages at once
 * @param indexTypeUint8 8-bit indices
 * @param hostImageCopy copying between host memory and images on the CPU, without a staging buffer
 * @param vertexAttributeInstanceRateDivisor instanced attributes that advance every n instances
 * @param shaderFloatControls2 per-instruction float behaviour in shaders
 * @param shaderExpectAssume {@code expect} and {@code assume} hints in shaders
 * @param shaderSubgroupRotate subgroup rotate operations in shaders
 * @param pipelineRobustness robustness chosen per pipeline instead of per device
 * @param globalPriorityQuery queue global priorities can be queried
 */
public record Vulkan14Features(
    boolean pushDescriptor,
    boolean dynamicRenderingLocalRead,
    boolean maintenance5,
    boolean maintenance6,
    boolean indexTypeUint8,
    boolean hostImageCopy,
    boolean vertexAttributeInstanceRateDivisor,
    boolean shaderFloatControls2,
    boolean shaderExpectAssume,
    boolean shaderSubgroupRotate,
    boolean pipelineRobustness,
    boolean globalPriorityQuery) {

    /** A device without Vulkan 1.4. */
    public static final Vulkan14Features NONE =
        new Vulkan14Features(false, false, false, false, false, false, false, false, false, false, false, false);

    static Vulkan14Features of(VkPhysicalDeviceVulkan14Features features) {
        return new Vulkan14Features(features.pushDescriptor(), features.dynamicRenderingLocalRead(),
            features.maintenance5(), features.maintenance6(), features.indexTypeUint8(), features.hostImageCopy(),
            features.vertexAttributeInstanceRateDivisor(), features.shaderFloatControls2(), features.shaderExpectAssume(),
            features.shaderSubgroupRotate(), features.pipelineRobustness(), features.globalPriorityQuery());
    }
}
