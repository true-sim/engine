package com.truesim.engine.vulkan;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.util.vma.Vma.VMA_ALLOCATOR_CREATE_BUFFER_DEVICE_ADDRESS_BIT;
import static org.lwjgl.util.vma.Vma.vmaCreateAllocator;
import static org.lwjgl.util.vma.Vma.vmaDestroyAllocator;
import static org.lwjgl.vulkan.EXTMeshShader.VK_EXT_MESH_SHADER_EXTENSION_NAME;
import static org.lwjgl.vulkan.EXTMeshShader.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_MESH_SHADER_FEATURES_EXT;
import static org.lwjgl.vulkan.KHRSwapchain.VK_KHR_SWAPCHAIN_EXTENSION_NAME;
import static org.lwjgl.vulkan.VK10.VK_COMMAND_BUFFER_LEVEL_PRIMARY;
import static org.lwjgl.vulkan.VK10.VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
import static org.lwjgl.vulkan.VK10.VK_COMMAND_POOL_CREATE_TRANSIENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.vkAllocateCommandBuffers;
import static org.lwjgl.vulkan.VK10.vkBeginCommandBuffer;
import static org.lwjgl.vulkan.VK10.vkCreateCommandPool;
import static org.lwjgl.vulkan.VK10.vkCreateDevice;
import static org.lwjgl.vulkan.VK10.vkCreateFence;
import static org.lwjgl.vulkan.VK10.vkDestroyCommandPool;
import static org.lwjgl.vulkan.VK10.vkDestroyDevice;
import static org.lwjgl.vulkan.VK10.vkDestroyFence;
import static org.lwjgl.vulkan.VK10.vkDeviceWaitIdle;
import static org.lwjgl.vulkan.VK10.vkEndCommandBuffer;
import static org.lwjgl.vulkan.VK10.vkGetDeviceQueue;
import static org.lwjgl.vulkan.VK10.vkWaitForFences;
import static org.lwjgl.vulkan.VK11.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2;
import static org.lwjgl.vulkan.VK11.vkGetPhysicalDeviceFeatures2;
import static org.lwjgl.vulkan.VK12.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_1_FEATURES;
import static org.lwjgl.vulkan.VK12.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES;
import static org.lwjgl.vulkan.VK13.VK_STRUCTURE_TYPE_COMMAND_BUFFER_SUBMIT_INFO;
import static org.lwjgl.vulkan.VK13.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_3_FEATURES;
import static org.lwjgl.vulkan.VK13.VK_STRUCTURE_TYPE_SUBMIT_INFO_2;
import static org.lwjgl.vulkan.VK14.VK_API_VERSION_1_4;
import static org.lwjgl.vulkan.VK14.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_4_FEATURES;

import com.truesim.annotations.AllowAllocation;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.vma.VmaAllocatorCreateInfo;
import org.lwjgl.util.vma.VmaVulkanFunctions;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferAllocateInfo;
import org.lwjgl.vulkan.VkCommandBufferBeginInfo;
import org.lwjgl.vulkan.VkCommandBufferSubmitInfo;
import org.lwjgl.vulkan.VkCommandPoolCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkDeviceCreateInfo;
import org.lwjgl.vulkan.VkDeviceQueueCreateInfo;
import org.lwjgl.vulkan.VkFenceCreateInfo;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures2;
import org.lwjgl.vulkan.VkPhysicalDeviceMeshShaderFeaturesEXT;
import org.lwjgl.vulkan.VkPhysicalDeviceVulkan11Features;
import org.lwjgl.vulkan.VkPhysicalDeviceVulkan12Features;
import org.lwjgl.vulkan.VkPhysicalDeviceVulkan13Features;
import org.lwjgl.vulkan.VkPhysicalDeviceVulkan14Features;
import org.lwjgl.vulkan.VkSubmitInfo2;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A logical device on one {@link Gpu}: its queues, a memory allocator (VMA), and the features a modern renderer
 * builds on, which every device has: dynamic rendering, synchronization2, timeline semaphores, buffer device addresses,
 * descriptor indexing and scalar block layouts. Several devices (one per GPU, or more) can be used at once.
 *
 * <p>Queues: {@link #graphics()} does everything; {@link #compute()} and {@link #transfer()} are dedicated queues for
 * async compute and uploads when the GPU has them, otherwise the graphics queue.
 */
@AllowAllocation("set up once")
public final class VulkanDevice implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(VulkanDevice.class);

    private final Gpu gpu;
    private final VkDevice handle;
    private final Queue graphics;
    private final Queue compute;
    private final Queue transfer;
    private final long allocator;
    private final int apiVersion;
    private final Vulkan14Features features14;
    private final boolean meshShaders;
    private final boolean swapchains;
    private boolean closed;

    private VulkanDevice(Gpu gpu, boolean swapchains) {
        this.gpu = gpu;
        this.swapchains = swapchains;
        QueueFamily[] chosen = chooseQueueFamilies(gpu.name(), gpu.queueFamilies());
        QueueFamily graphicsFamily = chosen[0];
        QueueFamily computeFamily = chosen[1];
        QueueFamily transferFamily = chosen[2];
        if (swapchains && !gpu.supports(VK_KHR_SWAPCHAIN_EXTENSION_NAME)) {
            throw new VulkanException(gpu.name() + " cannot present: " + VK_KHR_SWAPCHAIN_EXTENSION_NAME + " missing");
        }
        this.apiVersion = Vulkan.min(Vulkan.withoutPatch(gpu.apiVersion()), gpu.instance().apiVersion());
        try (MemoryStack stack = stackPush()) {
            requireFeatures(stack);
            // Every 1.4 feature the GPU supports is enabled: the queried struct goes into the create chain as it is.
            VkPhysicalDeviceVulkan14Features supported14 = null;
            if (Integer.compareUnsigned(apiVersion, VK_API_VERSION_1_4) >= 0) {
                supported14 = VkPhysicalDeviceVulkan14Features.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_4_FEATURES);
                vkGetPhysicalDeviceFeatures2(gpu.handle(), VkPhysicalDeviceFeatures2.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2).pNext(supported14.address()));
            }
            this.features14 = supported14 == null ? Vulkan14Features.NONE : Vulkan14Features.of(supported14);
            // Mesh shaders where the GPU has them: task and mesh stages, for the meshlet renderer's faster path.
            VkPhysicalDeviceMeshShaderFeaturesEXT meshShading = null;
            if (gpu.supports(VK_EXT_MESH_SHADER_EXTENSION_NAME)) {
                VkPhysicalDeviceMeshShaderFeaturesEXT query = VkPhysicalDeviceMeshShaderFeaturesEXT.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_MESH_SHADER_FEATURES_EXT);
                vkGetPhysicalDeviceFeatures2(gpu.handle(), VkPhysicalDeviceFeatures2.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2).pNext(query.address()));
                if (query.taskShader() && query.meshShader()) {
                    meshShading = VkPhysicalDeviceMeshShaderFeaturesEXT.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_MESH_SHADER_FEATURES_EXT)
                        .taskShader(true)
                        .meshShader(true);
                }
            }
            this.meshShaders = meshShading != null;
            Map<Integer, QueueFamily> distinct = new LinkedHashMap<>();
            for (QueueFamily family : List.of(graphicsFamily, computeFamily, transferFamily)) {
                distinct.put(family.index(), family);
            }
            VkDeviceQueueCreateInfo.Buffer queues = VkDeviceQueueCreateInfo.calloc(distinct.size(), stack);
            for (QueueFamily family : distinct.values()) {
                queues.get().sType(VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO).queueFamilyIndex(family.index())
                    .pQueuePriorities(stack.floats(1));
            }
            VkPhysicalDeviceVulkan13Features features13 = VkPhysicalDeviceVulkan13Features.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_3_FEATURES)
                .dynamicRendering(true)
                .synchronization2(true)
                .maintenance4(true);
            if (supported14 != null) {
                features13.pNext(supported14.address());
            }
            if (meshShading != null) {
                meshShading.pNext(features13.pNext());
                features13.pNext(meshShading.address());
            }
            VkPhysicalDeviceVulkan12Features features12 = VkPhysicalDeviceVulkan12Features.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES)
                .pNext(features13.address())
                .timelineSemaphore(true)
                .bufferDeviceAddress(true)
                .descriptorIndexing(true)
                .runtimeDescriptorArray(true)
                .descriptorBindingPartiallyBound(true)
                .scalarBlockLayout(true)
                .shaderInt8(true)
                .storageBuffer8BitAccess(true)
                .drawIndirectCount(true);
            VkPhysicalDeviceVulkan11Features features11 = VkPhysicalDeviceVulkan11Features.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_1_FEATURES)
                .pNext(features12.address())
                .storageBuffer16BitAccess(true)
                .shaderDrawParameters(true);
            VkPhysicalDeviceFeatures2 features = VkPhysicalDeviceFeatures2.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2)
                .pNext(features11.address());
            features.features().shaderInt64(true).shaderInt16(true).multiDrawIndirect(true)
                .drawIndirectFirstInstance(true);
            VkDeviceCreateInfo info = VkDeviceCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO)
                .pNext(features.address())
                .pQueueCreateInfos(queues.flip())
                .ppEnabledExtensionNames(extensions(stack, swapchains, meshShaders));
            PointerBuffer device = stack.mallocPointer(1);
            Vulkan.check(vkCreateDevice(gpu.handle(), info, null, device), "Could not create a device on " + gpu.name());
            this.handle = new VkDevice(device.get(0), gpu.handle(), info, apiVersion);
            Map<Integer, Queue> byFamily = new LinkedHashMap<>();
            for (int family : distinct.keySet()) {
                PointerBuffer queue = stack.mallocPointer(1);
                vkGetDeviceQueue(handle, family, 0, queue);
                byFamily.put(family, new Queue(new org.lwjgl.vulkan.VkQueue(queue.get(0), handle), family));
            }
            this.graphics = byFamily.get(graphicsFamily.index());
            this.compute = byFamily.get(computeFamily.index());
            this.transfer = byFamily.get(transferFamily.index());

            VmaAllocatorCreateInfo allocatorInfo = VmaAllocatorCreateInfo.calloc(stack)
                .flags(VMA_ALLOCATOR_CREATE_BUFFER_DEVICE_ADDRESS_BIT)
                .physicalDevice(gpu.handle())
                .device(handle)
                .instance(gpu.instance().handle())
                .vulkanApiVersion(VulkanInstance.MIN_API_VERSION) // all VMA needs
                .pVulkanFunctions(VmaVulkanFunctions.calloc(stack).set(gpu.instance().handle(), handle));
            PointerBuffer pointer = stack.mallocPointer(1);
            Vulkan.check(vmaCreateAllocator(allocatorInfo, pointer), "Could not create the memory allocator");
            this.allocator = pointer.get(0);
        }
        LOG.info("Device on {}: Vulkan {}, queues graphics {}, compute {}, transfer {}", gpu, Vulkan.version(apiVersion),
            graphics.family(), compute.family(), transfer.family());
    }

    /**
     * The families for the graphics, compute and transfer queues, in that order: graphics can also compute; compute
     * and transfer are dedicated families when the GPU has them, otherwise the graphics and compute ones.
     */
    static QueueFamily[] chooseQueueFamilies(String gpu, List<QueueFamily> families) {
        QueueFamily graphics = null;
        QueueFamily compute = null;
        QueueFamily transfer = null;
        for (QueueFamily family : families) {
            if (graphics == null && family.graphics() && family.compute()) {
                graphics = family;
            }
            if (compute == null && family.compute() && !family.graphics()) {
                compute = family;
            }
            if (transfer == null && family.transfer() && !family.graphics() && !family.compute()) {
                transfer = family;
            }
        }
        if (graphics == null) {
            throw new VulkanException(gpu + " has no graphics queue");
        }
        compute = compute == null ? graphics : compute;
        return new QueueFamily[] {graphics, compute, transfer == null ? compute : transfer};
    }

    private static PointerBuffer extensions(MemoryStack stack, boolean swapchains, boolean meshShaders) {
        PointerBuffer names = stack.mallocPointer(2);
        if (swapchains) {
            names.put(stack.UTF8(VK_KHR_SWAPCHAIN_EXTENSION_NAME));
        }
        if (meshShaders) {
            names.put(stack.UTF8(VK_EXT_MESH_SHADER_EXTENSION_NAME));
        }
        return names.position() == 0 ? null : names.flip();
    }

    /** A device that can present to windows. */
    public static VulkanDevice create(Gpu gpu) {
        return new VulkanDevice(gpu, true);
    }

    /** A device for offscreen rendering and compute only, without swapchains. */
    public static VulkanDevice createHeadless(Gpu gpu) {
        return new VulkanDevice(gpu, false);
    }

    /** Throws unless the GPU has every feature the engine relies on. */
    private void requireFeatures(MemoryStack stack) {
        VkPhysicalDeviceVulkan13Features features13 = VkPhysicalDeviceVulkan13Features.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_3_FEATURES);
        VkPhysicalDeviceVulkan12Features features12 = VkPhysicalDeviceVulkan12Features.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES).pNext(features13.address());
        VkPhysicalDeviceVulkan11Features features11 = VkPhysicalDeviceVulkan11Features.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_1_FEATURES).pNext(features12.address());
        VkPhysicalDeviceFeatures2 features = VkPhysicalDeviceFeatures2.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2).pNext(features11.address());
        vkGetPhysicalDeviceFeatures2(gpu.handle(), features);
        List<String> missing = new ArrayList<>();
        // Shaders read data through 64-bit pointers, as 8, 16 and 32-bit values (meshlet indices, quantized vertices).
        check(missing, features.features().shaderInt64(), "shaderInt64");
        check(missing, features.features().shaderInt16(), "shaderInt16");
        check(missing, features11.storageBuffer16BitAccess(), "storageBuffer16BitAccess");
        check(missing, features11.shaderDrawParameters(), "shaderDrawParameters"); // vertex and draw indices
        check(missing, features12.shaderInt8(), "shaderInt8");
        check(missing, features12.storageBuffer8BitAccess(), "storageBuffer8BitAccess");
        // GPU-driven drawing: many indirect draws, their count and first instance written by the GPU.
        check(missing, features.features().multiDrawIndirect(), "multiDrawIndirect");
        check(missing, features.features().drawIndirectFirstInstance(), "drawIndirectFirstInstance");
        check(missing, features12.drawIndirectCount(), "drawIndirectCount");
        check(missing, features13.dynamicRendering(), "dynamicRendering");
        check(missing, features13.synchronization2(), "synchronization2");
        check(missing, features13.maintenance4(), "maintenance4");
        check(missing, features12.timelineSemaphore(), "timelineSemaphore");
        check(missing, features12.bufferDeviceAddress(), "bufferDeviceAddress");
        check(missing, features12.descriptorIndexing(), "descriptorIndexing");
        check(missing, features12.runtimeDescriptorArray(), "runtimeDescriptorArray");
        check(missing, features12.descriptorBindingPartiallyBound(), "descriptorBindingPartiallyBound");
        check(missing, features12.scalarBlockLayout(), "scalarBlockLayout");
        if (!missing.isEmpty()) {
            throw new VulkanException(gpu.name() + " lacks required features: " + missing);
        }
    }

    private static void check(List<String> missing, boolean supported, String feature) {
        if (!supported) {
            missing.add(feature);
        }
    }

    public Gpu gpu() {
        return gpu;
    }

    public VkDevice handle() {
        return handle;
    }

    /** The Vulkan version used on this device: 1.3, or 1.4 when both the GPU and the instance have it. */
    public int apiVersion() {
        return apiVersion;
    }

    /** The Vulkan 1.4 features enabled on this device; {@link Vulkan14Features#NONE} on a 1.3 device. */
    /** Whether task and mesh shaders (VK_EXT_mesh_shader) are enabled: the GPU has them. */
    public boolean meshShaders() {
        return meshShaders;
    }

    public Vulkan14Features features14() {
        return features14;
    }

    /** The VMA allocator, for buffers and images. */
    public long allocator() {
        return allocator;
    }

    /** Whether swapchains can be created on this device. */
    public boolean canPresent() {
        return swapchains;
    }

    public Queue graphics() {
        return graphics;
    }

    /** A dedicated compute queue, or the graphics queue. */
    public Queue compute() {
        return compute;
    }

    /** A dedicated transfer queue, or the compute queue. */
    public Queue transfer() {
        return transfer;
    }

    /** Waits until the device has finished all work, e.g. before destroying resources. */
    public void waitIdle() {
        Vulkan.check(vkDeviceWaitIdle(handle), "Could not wait for the device");
    }

    /**
     * Records commands with {@code commands} and runs them on {@code queue}, waiting until they completed: for setup
     * work such as uploads, not for frames.
     */
    public void submitAndWait(Queue queue, Consumer<VkCommandBuffer> commands) {
        try (MemoryStack stack = stackPush()) {
            LongBuffer pool = stack.mallocLong(1);
            Vulkan.check(vkCreateCommandPool(handle, VkCommandPoolCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO)
                .flags(VK_COMMAND_POOL_CREATE_TRANSIENT_BIT)
                .queueFamilyIndex(queue.family()), null, pool), "Could not create a command pool");
            LongBuffer fence = stack.callocLong(1); // 0 until created: destroying 0 does nothing
            try {
                PointerBuffer buffer = stack.mallocPointer(1);
                Vulkan.check(vkAllocateCommandBuffers(handle, VkCommandBufferAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO)
                    .commandPool(pool.get(0))
                    .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                    .commandBufferCount(1), buffer), "Could not allocate a command buffer");
                VkCommandBuffer commandBuffer = new VkCommandBuffer(buffer.get(0), handle);
                Vulkan.check(vkBeginCommandBuffer(commandBuffer, VkCommandBufferBeginInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)
                    .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)), "Could not begin recording");
                commands.accept(commandBuffer);
                Vulkan.check(vkEndCommandBuffer(commandBuffer), "Could not finish recording");
                Vulkan.check(vkCreateFence(handle, VkFenceCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_FENCE_CREATE_INFO), null, fence), "Could not create a fence");
                VkSubmitInfo2.Buffer submit = VkSubmitInfo2.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_SUBMIT_INFO_2)
                    .pCommandBufferInfos(VkCommandBufferSubmitInfo.calloc(1, stack)
                        .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_SUBMIT_INFO)
                        .commandBuffer(commandBuffer));
                queue.submit(submit, fence.get(0));
                Vulkan.check(vkWaitForFences(handle, fence, true, Long.MAX_VALUE), "Could not wait for the commands");
            } finally {
                vkDestroyFence(handle, fence.get(0), null);
                vkDestroyCommandPool(handle, pool.get(0), null);
            }
        }
    }

    /** Waits for the device and destroys it; close resources created on it first. */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        waitIdle();
        vmaDestroyAllocator(allocator);
        vkDestroyDevice(handle, null);
    }
}
