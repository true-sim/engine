package com.truesim.engine.vulkan;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.KHRSurface.vkGetPhysicalDeviceSurfaceSupportKHR;
import static org.lwjgl.vulkan.VK10.VK_MEMORY_HEAP_DEVICE_LOCAL_BIT;
import static org.lwjgl.vulkan.VK10.VK_TRUE;
import static org.lwjgl.vulkan.VK10.vkEnumerateDeviceExtensionProperties;
import static org.lwjgl.vulkan.VK10.vkGetPhysicalDeviceMemoryProperties;
import static org.lwjgl.vulkan.VK10.vkGetPhysicalDeviceProperties;
import static org.lwjgl.vulkan.VK10.vkGetPhysicalDeviceQueueFamilyProperties;

import com.truesim.annotations.AllowAllocation;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkExtensionProperties;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.vulkan.VkQueueFamilyProperties;

/**
 * A GPU that supports Vulkan 1.3, as listed by {@link VulkanInstance#gpus()}: its name, type, versions, memory, queue
 * families and extensions. Create a {@link VulkanDevice} on it to use it; several GPUs can be used at once.
 */
@AllowAllocation("listed once")
public final class Gpu {

    private final VulkanInstance instance;
    private final VkPhysicalDevice handle;
    private final String name;
    private final GpuType type;
    private final int vendorId;
    private final int deviceId;
    private final int apiVersion;
    private final int driverVersion;
    private final long deviceLocalMemory;
    private final List<QueueFamily> queueFamilies;
    private final Set<String> extensions;

    Gpu(VulkanInstance instance, VkPhysicalDevice handle) {
        this.instance = instance;
        this.handle = handle;
        try (MemoryStack stack = stackPush()) {
            VkPhysicalDeviceProperties properties = VkPhysicalDeviceProperties.malloc(stack);
            vkGetPhysicalDeviceProperties(handle, properties);
            this.name = properties.deviceNameString();
            this.type = GpuType.of(properties.deviceType());
            this.vendorId = properties.vendorID();
            this.deviceId = properties.deviceID();
            this.apiVersion = properties.apiVersion();
            this.driverVersion = properties.driverVersion();

            VkPhysicalDeviceMemoryProperties memory = VkPhysicalDeviceMemoryProperties.malloc(stack);
            vkGetPhysicalDeviceMemoryProperties(handle, memory);
            long local = 0;
            for (int i = 0; i < memory.memoryHeapCount(); i++) {
                if ((memory.memoryHeaps(i).flags() & VK_MEMORY_HEAP_DEVICE_LOCAL_BIT) != 0) {
                    local += memory.memoryHeaps(i).size();
                }
            }
            this.deviceLocalMemory = local;

            IntBuffer count = stack.mallocInt(1);
            vkGetPhysicalDeviceQueueFamilyProperties(handle, count, null);
            VkQueueFamilyProperties.Buffer families = VkQueueFamilyProperties.malloc(count.get(0), stack);
            vkGetPhysicalDeviceQueueFamilyProperties(handle, count, families);
            List<QueueFamily> list = new ArrayList<>();
            for (int i = 0; i < families.capacity(); i++) {
                list.add(QueueFamily.of(i, families.get(i).queueCount(), families.get(i).queueFlags()));
            }
            this.queueFamilies = List.copyOf(list);

            Vulkan.checkList(vkEnumerateDeviceExtensionProperties(handle, (String) null, count, null),
                "Could not list the device's extensions");
            VkExtensionProperties.Buffer properties2 = VkExtensionProperties.malloc(count.get(0), stack);
            Vulkan.checkList(vkEnumerateDeviceExtensionProperties(handle, (String) null, count, properties2),
                "Could not list the device's extensions");
            Set<String> names = new HashSet<>();
            properties2.forEach(property -> names.add(property.extensionNameString()));
            this.extensions = Set.copyOf(names);
        }
    }

    public VulkanInstance instance() {
        return instance;
    }

    public VkPhysicalDevice handle() {
        return handle;
    }

    public String name() {
        return name;
    }

    public GpuType type() {
        return type;
    }

    /** PCI vendor id, e.g. 0x1002 for AMD, 0x10de for NVIDIA, 0x8086 for Intel. */
    public int vendorId() {
        return vendorId;
    }

    public int deviceId() {
        return deviceId;
    }

    /** The highest Vulkan version the GPU supports. */
    public int apiVersion() {
        return apiVersion;
    }

    public int driverVersion() {
        return driverVersion;
    }

    /** Bytes of memory local to the GPU (VRAM for discrete GPUs). */
    public long deviceLocalMemory() {
        return deviceLocalMemory;
    }

    public List<QueueFamily> queueFamilies() {
        return queueFamilies;
    }

    public Set<String> extensions() {
        return extensions;
    }

    public boolean supports(String extension) {
        return extensions.contains(extension);
    }

    /** Whether queues of {@code family} can present to {@code surface}. */
    public boolean canPresent(Surface surface, int family) {
        try (MemoryStack stack = stackPush()) {
            IntBuffer supported = stack.mallocInt(1);
            Vulkan.check(vkGetPhysicalDeviceSurfaceSupportKHR(handle, family, surface.handle(), supported),
                "Could not ask whether the surface can present");
            return supported.get(0) == VK_TRUE;
        }
    }

    @Override
    public String toString() {
        return name + " (" + type + ", Vulkan " + Vulkan.version(apiVersion) + ", "
            + deviceLocalMemory / (1024 * 1024) + " MiB)";
    }
}
