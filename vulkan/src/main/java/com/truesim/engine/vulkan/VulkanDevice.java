package com.truesim.engine.vulkan;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.util.vma.Vma.VMA_ALLOCATOR_CREATE_BUFFER_DEVICE_ADDRESS_BIT;
import static org.lwjgl.util.vma.Vma.vmaCreateAllocator;
import static org.lwjgl.util.vma.Vma.vmaDestroyAllocator;
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
import static org.lwjgl.vulkan.VK12.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES;
import static org.lwjgl.vulkan.VK13.VK_STRUCTURE_TYPE_COMMAND_BUFFER_SUBMIT_INFO;
import static org.lwjgl.vulkan.VK13.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_3_FEATURES;
import static org.lwjgl.vulkan.VK13.VK_STRUCTURE_TYPE_SUBMIT_INFO_2;

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
import org.lwjgl.vulkan.VkPhysicalDeviceVulkan12Features;
import org.lwjgl.vulkan.VkPhysicalDeviceVulkan13Features;
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
    private final boolean swapchains;
    private boolean closed;

    private VulkanDevice(Gpu gpu, boolean swapchains) {
        this.gpu = gpu;
        this.swapchains = swapchains;
        List<QueueFamily> families = gpu.queueFamilies();
        QueueFamily graphicsFamily = families.stream().filter(f -> f.graphics() && f.compute()).findFirst()
            .orElseThrow(() -> new VulkanException(gpu.name() + " has no graphics queue"));
        QueueFamily computeFamily = families.stream().filter(f -> f.compute() && !f.graphics()).findFirst()
            .orElse(graphicsFamily);
        QueueFamily transferFamily = families.stream().filter(f -> f.transfer() && !f.graphics() && !f.compute())
            .findFirst().orElse(computeFamily);
        if (swapchains && !gpu.supports(VK_KHR_SWAPCHAIN_EXTENSION_NAME)) {
            throw new VulkanException(gpu.name() + " cannot present: " + VK_KHR_SWAPCHAIN_EXTENSION_NAME + " missing");
        }
        try (MemoryStack stack = stackPush()) {
            requireFeatures(stack);
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
            VkPhysicalDeviceVulkan12Features features12 = VkPhysicalDeviceVulkan12Features.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES)
                .pNext(features13.address())
                .timelineSemaphore(true)
                .bufferDeviceAddress(true)
                .descriptorIndexing(true)
                .runtimeDescriptorArray(true)
                .descriptorBindingPartiallyBound(true)
                .scalarBlockLayout(true);
            VkPhysicalDeviceFeatures2 features = VkPhysicalDeviceFeatures2.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2)
                .pNext(features12.address());
            VkDeviceCreateInfo info = VkDeviceCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO)
                .pNext(features.address())
                .pQueueCreateInfos(queues.flip())
                .ppEnabledExtensionNames(swapchains ? stack.pointers(stack.UTF8(VK_KHR_SWAPCHAIN_EXTENSION_NAME)) : null);
            PointerBuffer device = stack.mallocPointer(1);
            Vulkan.check(vkCreateDevice(gpu.handle(), info, null, device), "Could not create a device on " + gpu.name());
            this.handle = new VkDevice(device.get(0), gpu.handle(), info, VulkanInstance.API_VERSION);
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
                .vulkanApiVersion(VulkanInstance.API_VERSION)
                .pVulkanFunctions(VmaVulkanFunctions.calloc(stack).set(gpu.instance().handle(), handle));
            PointerBuffer pointer = stack.mallocPointer(1);
            Vulkan.check(vmaCreateAllocator(allocatorInfo, pointer), "Could not create the memory allocator");
            this.allocator = pointer.get(0);
        }
        LOG.info("Device on {}: queues graphics {}, compute {}, transfer {}", gpu, graphics.family(), compute.family(),
            transfer.family());
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
        VkPhysicalDeviceFeatures2 features = VkPhysicalDeviceFeatures2.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2).pNext(features12.address());
        vkGetPhysicalDeviceFeatures2(gpu.handle(), features);
        List<String> missing = new ArrayList<>();
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
