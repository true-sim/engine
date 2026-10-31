package com.truesim.engine.vulkan;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.util.vma.Vma.VMA_ALLOCATOR_CREATE_BUFFER_DEVICE_ADDRESS_BIT;
import static org.lwjgl.util.vma.Vma.vmaCreateAllocator;
import static org.lwjgl.util.vma.Vma.vmaDestroyAllocator;
import static org.lwjgl.vulkan.EXTDeviceFault.VK_EXT_DEVICE_FAULT_EXTENSION_NAME;
import static org.lwjgl.vulkan.EXTDeviceFault.VK_DEVICE_FAULT_ADDRESS_TYPE_EXECUTE_INVALID_EXT;
import static org.lwjgl.vulkan.EXTDeviceFault.VK_DEVICE_FAULT_ADDRESS_TYPE_INSTRUCTION_POINTER_FAULT_EXT;
import static org.lwjgl.vulkan.EXTDeviceFault.VK_DEVICE_FAULT_ADDRESS_TYPE_INSTRUCTION_POINTER_INVALID_EXT;
import static org.lwjgl.vulkan.EXTDeviceFault.VK_DEVICE_FAULT_ADDRESS_TYPE_INSTRUCTION_POINTER_UNKNOWN_EXT;
import static org.lwjgl.vulkan.EXTDeviceFault.VK_DEVICE_FAULT_ADDRESS_TYPE_READ_INVALID_EXT;
import static org.lwjgl.vulkan.EXTDeviceFault.VK_DEVICE_FAULT_ADDRESS_TYPE_WRITE_INVALID_EXT;
import static org.lwjgl.vulkan.EXTDeviceFault.vkGetDeviceFaultInfoEXT;
import static org.lwjgl.vulkan.EXTMeshShader.VK_EXT_MESH_SHADER_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRFragmentShaderBarycentric.VK_KHR_FRAGMENT_SHADER_BARYCENTRIC_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRFragmentShaderBarycentric.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FRAGMENT_SHADER_BARYCENTRIC_FEATURES_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_KHR_ACCELERATION_STRUCTURE_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_ACCELERATION_STRUCTURE_FEATURES_KHR;
import static org.lwjgl.vulkan.KHRDeferredHostOperations.VK_KHR_DEFERRED_HOST_OPERATIONS_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRRayQuery.VK_KHR_RAY_QUERY_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRRayQuery.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_RAY_QUERY_FEATURES_KHR;
import static org.lwjgl.vulkan.EXTMeshShader.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_MESH_SHADER_FEATURES_EXT;
import static org.lwjgl.vulkan.EXTHdrMetadata.VK_EXT_HDR_METADATA_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRSwapchain.VK_KHR_SWAPCHAIN_EXTENSION_NAME;
import static org.lwjgl.vulkan.VK10.VK_ERROR_DEVICE_LOST;
import static org.lwjgl.vulkan.VK10.VK_INCOMPLETE;
import static org.lwjgl.vulkan.VK10.VK_SUCCESS;
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
import static org.lwjgl.vulkan.VK10.vkGetPhysicalDeviceFeatures;
import static org.lwjgl.vulkan.VK10.vkGetPhysicalDeviceProperties;
import static org.lwjgl.vulkan.VK10.vkCreateFence;
import static org.lwjgl.vulkan.VK10.vkDestroyCommandPool;
import static org.lwjgl.vulkan.VK10.vkDestroyDevice;
import static org.lwjgl.vulkan.VK10.vkDestroyFence;
import static org.lwjgl.vulkan.VK10.vkDeviceWaitIdle;
import static org.lwjgl.vulkan.VK10.vkEndCommandBuffer;
import static org.lwjgl.vulkan.VK10.vkGetDeviceQueue;
import static org.lwjgl.vulkan.VK10.vkResetCommandPool;
import static org.lwjgl.vulkan.VK10.vkResetFences;
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
import java.util.IdentityHashMap;
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
import org.lwjgl.vulkan.VkDeviceFaultAddressInfoEXT;
import org.lwjgl.vulkan.VkDeviceFaultCountsEXT;
import org.lwjgl.vulkan.VkDeviceFaultInfoEXT;
import org.lwjgl.vulkan.VkDeviceFaultVendorInfoEXT;
import org.lwjgl.vulkan.VkDeviceQueueCreateInfo;
import org.lwjgl.vulkan.VkFenceCreateInfo;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures;
import org.lwjgl.vulkan.VkPhysicalDeviceFaultFeaturesEXT;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures2;
import org.lwjgl.vulkan.VkPhysicalDeviceFragmentShaderBarycentricFeaturesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceAccelerationStructureFeaturesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceRayQueryFeaturesKHR;
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
    private final Bindless bindless;
    private final PipelineCache pipelineCache;
    private final DirectCalls direct;
    private final int apiVersion;
    private final Vulkan14Features features14;
    private final boolean meshShaders;
    private final boolean barycentrics;
    private final boolean rayTracing;
    private final boolean anisotropic;
    private final boolean cubeArrays;
    private final boolean primitiveIds;
    private final float maxAnisotropy;
    private final boolean swapchains;
    /** Whether the driver can say what went wrong when the device is lost (VK_EXT_device_fault). */
    private final boolean faults;
    private final boolean hdrMetadata;
    // submitAndWait's command pools and fences, per queue: idle ones, and all ever made (destroyed on close).
    private final Map<Queue, java.util.ArrayDeque<OneOff>> oneOffs = new java.util.IdentityHashMap<>();
    private final java.util.List<OneOff> allOneOffs = new java.util.ArrayList<>();
    /** Whether a call checked by {@link #check} found the device lost: only then may the driver be asked why. */
    private volatile boolean lost;
    /** With validation on, the buffers and images made on the device and not closed, with where each was made. */
    private final Map<Object, Throwable> children;
    private volatile boolean closed;

    private VulkanDevice(Gpu gpu, boolean swapchains, java.util.Collection<String> extra) {
        for (String name : extra) {
            if (!gpu.supports(name)) {
                throw new VulkanException(gpu.name() + " lacks the device extension " + name);
            }
        }
        this.gpu = gpu;
        this.swapchains = swapchains;
        this.children = gpu.instance().validationEnabled() ? new IdentityHashMap<>() : null;
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
            // Anisotropic filtering where the GPU has it (nearly all do), for textures seen at an angle.
            VkPhysicalDeviceFeatures core = VkPhysicalDeviceFeatures.calloc(stack);
            vkGetPhysicalDeviceFeatures(gpu.handle(), core);
            VkPhysicalDeviceProperties properties = VkPhysicalDeviceProperties.calloc(stack);
            vkGetPhysicalDeviceProperties(gpu.handle(), properties);
            this.anisotropic = core.samplerAnisotropy();
            this.cubeArrays = core.imageCubeArray();
            this.primitiveIds = core.geometryShader();
            this.maxAnisotropy = Math.min(16, properties.limits().maxSamplerAnisotropy());
            // Every 1.4 feature the GPU supports is enabled: the queried struct goes into the create chain as it is.
            VkPhysicalDeviceVulkan14Features supported14 = null;
            if (Integer.compareUnsigned(apiVersion, VK_API_VERSION_1_4) >= 0) {
                supported14 = VkPhysicalDeviceVulkan14Features.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_4_FEATURES);
                vkGetPhysicalDeviceFeatures2(gpu.handle(), VkPhysicalDeviceFeatures2.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2).pNext(supported14.address()));
            }
            this.features14 = supported14 == null ? Vulkan14Features.NONE : Vulkan14Features.of(supported14);
            // Mesh shaders where the GPU has them, for the meshlet renderer's faster path; task shaders too, if there.
            VkPhysicalDeviceMeshShaderFeaturesEXT meshShading = null;
            if (gpu.supports(VK_EXT_MESH_SHADER_EXTENSION_NAME)) {
                VkPhysicalDeviceMeshShaderFeaturesEXT query = VkPhysicalDeviceMeshShaderFeaturesEXT.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_MESH_SHADER_FEATURES_EXT);
                vkGetPhysicalDeviceFeatures2(gpu.handle(), VkPhysicalDeviceFeatures2.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2).pNext(query.address()));
                if (query.meshShader()) {
                    meshShading = VkPhysicalDeviceMeshShaderFeaturesEXT.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_MESH_SHADER_FEATURES_EXT)
                        .taskShader(query.taskShader())
                        .meshShader(true);
                }
            }
            this.meshShaders = meshShading != null;
            // Barycentric coordinates in fragment shaders where the GPU has them: the mesh shader path passes its
            // fragments positions and triangles alone, and they rebuild the rest (cheaper than interpolated outputs).
            VkPhysicalDeviceFragmentShaderBarycentricFeaturesKHR barycentric = null;
            if (gpu.supports(VK_KHR_FRAGMENT_SHADER_BARYCENTRIC_EXTENSION_NAME)) {
                VkPhysicalDeviceFragmentShaderBarycentricFeaturesKHR query =
                    VkPhysicalDeviceFragmentShaderBarycentricFeaturesKHR.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FRAGMENT_SHADER_BARYCENTRIC_FEATURES_KHR);
                vkGetPhysicalDeviceFeatures2(gpu.handle(), VkPhysicalDeviceFeatures2.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2).pNext(query.address()));
                if (query.fragmentShaderBarycentric()) {
                    barycentric = VkPhysicalDeviceFragmentShaderBarycentricFeaturesKHR.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FRAGMENT_SHADER_BARYCENTRIC_FEATURES_KHR)
                        .fragmentShaderBarycentric(true);
                }
            }
            this.barycentrics = barycentric != null;
            // Hardware ray tracing where the GPU has it: acceleration structures, traced by ray queries from any shader.
            VkPhysicalDeviceAccelerationStructureFeaturesKHR structures = null;
            VkPhysicalDeviceRayQueryFeaturesKHR queries = null;
            if (gpu.supports(VK_KHR_ACCELERATION_STRUCTURE_EXTENSION_NAME) && gpu.supports(VK_KHR_RAY_QUERY_EXTENSION_NAME)
                && gpu.supports(VK_KHR_DEFERRED_HOST_OPERATIONS_EXTENSION_NAME)) {
                VkPhysicalDeviceAccelerationStructureFeaturesKHR structureQuery =
                    VkPhysicalDeviceAccelerationStructureFeaturesKHR.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_ACCELERATION_STRUCTURE_FEATURES_KHR);
                VkPhysicalDeviceRayQueryFeaturesKHR rayQuery = VkPhysicalDeviceRayQueryFeaturesKHR.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_RAY_QUERY_FEATURES_KHR)
                    .pNext(structureQuery.address());
                vkGetPhysicalDeviceFeatures2(gpu.handle(), VkPhysicalDeviceFeatures2.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2).pNext(rayQuery.address()));
                if (structureQuery.accelerationStructure() && rayQuery.rayQuery()
                    && structureQuery.descriptorBindingAccelerationStructureUpdateAfterBind()) {
                    structures = VkPhysicalDeviceAccelerationStructureFeaturesKHR.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_ACCELERATION_STRUCTURE_FEATURES_KHR)
                        .accelerationStructure(true)
                        .descriptorBindingAccelerationStructureUpdateAfterBind(true);
                    queries = VkPhysicalDeviceRayQueryFeaturesKHR.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_RAY_QUERY_FEATURES_KHR)
                        .pNext(structures.address())
                        .rayQuery(true);
                }
            }
            this.rayTracing = queries != null;
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
                .maintenance4(true)
                .shaderDemoteToHelperInvocation(true); // Slang's discard: masked materials
            if (supported14 != null) {
                features13.pNext(supported14.address());
            }
            if (meshShading != null) {
                meshShading.pNext(features13.pNext());
                features13.pNext(meshShading.address());
            }
            if (barycentric != null) {
                barycentric.pNext(features13.pNext());
                features13.pNext(barycentric.address());
            }
            if (queries != null) {
                structures.pNext(features13.pNext());
                features13.pNext(queries.address());
            }
            this.hdrMetadata = swapchains && gpu.supports(VK_EXT_HDR_METADATA_EXTENSION_NAME);
            this.faults = gpu.supports(VK_EXT_DEVICE_FAULT_EXTENSION_NAME);
            if (faults) {
                features13.pNext(VkPhysicalDeviceFaultFeaturesEXT.calloc(stack).sType$Default().deviceFault(true)
                    .pNext(features13.pNext()).address());
            }
            VkPhysicalDeviceVulkan12Features features12 = VkPhysicalDeviceVulkan12Features.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES)
                .pNext(features13.address())
                .timelineSemaphore(true)
                .bufferDeviceAddress(true)
                .descriptorIndexing(true)
                .runtimeDescriptorArray(true)
                .shaderSampledImageArrayNonUniformIndexing(true)
                .descriptorBindingPartiallyBound(true)
                .descriptorBindingSampledImageUpdateAfterBind(true)
                .descriptorBindingStorageImageUpdateAfterBind(true)
                .descriptorBindingUpdateUnusedWhilePending(true)
                .scalarBlockLayout(true)
                .shaderInt8(true)
                .storageBuffer8BitAccess(true)
                .drawIndirectCount(true)
                .hostQueryReset(true); // timings: the graph resets its queries from the host
            VkPhysicalDeviceVulkan11Features features11 = VkPhysicalDeviceVulkan11Features.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_1_FEATURES)
                .pNext(features12.address())
                .storageBuffer16BitAccess(true)
                .shaderDrawParameters(true)
                .multiview(true); // one pass drawing several views (a headset's eyes): Vulkan 1.1 requires it
            VkPhysicalDeviceFeatures2 features = VkPhysicalDeviceFeatures2.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2)
                .pNext(features11.address());
            features.features().shaderInt64(true).shaderInt16(true).multiDrawIndirect(true)
                .drawIndirectFirstInstance(true).shaderStorageImageReadWithoutFormat(true)
                .shaderStorageImageWriteWithoutFormat(true).samplerAnisotropy(anisotropic).imageCubeArray(cubeArrays)
                .geometryShader(primitiveIds);
            VkDeviceCreateInfo info = VkDeviceCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO)
                .pNext(features.address())
                .pQueueCreateInfos(queues.flip())
                .ppEnabledExtensionNames(extensions(stack, swapchains, meshShaders, barycentrics, rayTracing, faults,
                    hdrMetadata, extra));
            PointerBuffer device = stack.mallocPointer(1);
            Vulkan.check(vkCreateDevice(gpu.handle(), info, null, device), "Could not create a device on " + gpu.name());
            this.handle = new VkDevice(device.get(0), gpu.handle(), info, apiVersion);
            Map<Integer, Queue> byFamily = new LinkedHashMap<>();
            for (int family : distinct.keySet()) {
                PointerBuffer queue = stack.mallocPointer(1);
                vkGetDeviceQueue(handle, family, 0, queue);
                byFamily.put(family, new Queue(new org.lwjgl.vulkan.VkQueue(queue.get(0), handle), family, this));
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
            int created = vmaCreateAllocator(allocatorInfo, pointer);
            if (created != VK_SUCCESS) {
                vkDestroyDevice(handle, null); // nothing made on a device that failed to set up leaks
                Vulkan.check(created, "Could not create the memory allocator");
            }
            this.allocator = pointer.get(0);
        }
        Bindless made = null;
        try {
            made = new Bindless(this);
            this.bindless = made;
            this.pipelineCache = new PipelineCache(this);
            this.direct = new DirectCalls(handle);
        } catch (RuntimeException | Error e) {
            if (made != null) {
                made.close(); // made before what failed
            }
            vmaDestroyAllocator(allocator);
            vkDestroyDevice(handle, null);
            throw e;
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

    private static PointerBuffer extensions(MemoryStack stack, boolean swapchains, boolean meshShaders,
        boolean barycentrics, boolean rayTracing, boolean faults, boolean hdrMetadata,
        java.util.Collection<String> extra) {
        PointerBuffer names = stack.mallocPointer(8 + extra.size());
        List<String> engine = new ArrayList<>();
        if (hdrMetadata) {
            names.put(stack.UTF8(VK_EXT_HDR_METADATA_EXTENSION_NAME));
            engine.add(VK_EXT_HDR_METADATA_EXTENSION_NAME);
        }
        if (faults) {
            names.put(stack.UTF8(VK_EXT_DEVICE_FAULT_EXTENSION_NAME));
            engine.add(VK_EXT_DEVICE_FAULT_EXTENSION_NAME);
        }
        if (swapchains) {
            names.put(stack.UTF8(VK_KHR_SWAPCHAIN_EXTENSION_NAME));
            engine.add(VK_KHR_SWAPCHAIN_EXTENSION_NAME);
        }
        if (meshShaders) {
            names.put(stack.UTF8(VK_EXT_MESH_SHADER_EXTENSION_NAME));
            engine.add(VK_EXT_MESH_SHADER_EXTENSION_NAME);
        }
        if (barycentrics) {
            names.put(stack.UTF8(VK_KHR_FRAGMENT_SHADER_BARYCENTRIC_EXTENSION_NAME));
            engine.add(VK_KHR_FRAGMENT_SHADER_BARYCENTRIC_EXTENSION_NAME);
        }
        if (rayTracing) {
            names.put(stack.UTF8(VK_KHR_ACCELERATION_STRUCTURE_EXTENSION_NAME));
            engine.add(VK_KHR_ACCELERATION_STRUCTURE_EXTENSION_NAME);
            names.put(stack.UTF8(VK_KHR_RAY_QUERY_EXTENSION_NAME));
            engine.add(VK_KHR_RAY_QUERY_EXTENSION_NAME);
            names.put(stack.UTF8(VK_KHR_DEFERRED_HOST_OPERATIONS_EXTENSION_NAME));
            engine.add(VK_KHR_DEFERRED_HOST_OPERATIONS_EXTENSION_NAME);
        }
        for (String name : extra) {
            if (!engine.contains(name)) {
                names.put(stack.UTF8(name));
            }
        }
        return names.position() == 0 ? null : names.flip();
    }

    /** A device that can present to windows. */
    public static VulkanDevice create(Gpu gpu) {
        return new VulkanDevice(gpu, true, List.of());
    }

    /**
     * A device that can present to windows, with {@code extensions} enabled besides the engine's, e.g. those an
     * OpenXR runtime asks for: fails if the GPU lacks one.
     */
    public static VulkanDevice create(Gpu gpu, java.util.Collection<String> extensions) {
        return new VulkanDevice(gpu, true, extensions);
    }

    /** A device for offscreen rendering and compute only, without swapchains. */
    public static VulkanDevice createHeadless(Gpu gpu) {
        return new VulkanDevice(gpu, false, List.of());
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
        check(missing, features11.multiview(), "multiview");
        check(missing, features.features().shaderInt16(), "shaderInt16");
        check(missing, features11.storageBuffer16BitAccess(), "storageBuffer16BitAccess");
        check(missing, features11.shaderDrawParameters(), "shaderDrawParameters"); // vertex and draw indices
        check(missing, features12.shaderInt8(), "shaderInt8");
        check(missing, features12.storageBuffer8BitAccess(), "storageBuffer8BitAccess");
        // GPU-driven drawing: many indirect draws, their count and first instance written by the GPU.
        check(missing, features.features().multiDrawIndirect(), "multiDrawIndirect");
        check(missing, features.features().drawIndirectFirstInstance(), "drawIndirectFirstInstance");
        check(missing, features12.drawIndirectCount(), "drawIndirectCount");
        check(missing, features12.hostQueryReset(), "hostQueryReset");
        check(missing, features13.dynamicRendering(), "dynamicRendering");
        check(missing, features13.synchronization2(), "synchronization2");
        check(missing, features13.maintenance4(), "maintenance4");
        check(missing, features13.shaderDemoteToHelperInvocation(), "shaderDemoteToHelperInvocation");
        check(missing, features12.timelineSemaphore(), "timelineSemaphore");
        check(missing, features12.bufferDeviceAddress(), "bufferDeviceAddress");
        check(missing, features12.descriptorIndexing(), "descriptorIndexing");
        check(missing, features12.runtimeDescriptorArray(), "runtimeDescriptorArray");
        // Slots that differ within a wave (per pixel materials, per light shadow maps).
        check(missing, features12.shaderSampledImageArrayNonUniformIndexing(),
            "shaderSampledImageArrayNonUniformIndexing");
        check(missing, features12.descriptorBindingPartiallyBound(), "descriptorBindingPartiallyBound");
        check(missing, features12.scalarBlockLayout(), "scalarBlockLayout");
        // The bindless set: image slots written while frames using other slots are in flight; storage images of any
        // format, so one array holds them all.
        check(missing, features12.descriptorBindingSampledImageUpdateAfterBind(),
            "descriptorBindingSampledImageUpdateAfterBind");
        check(missing, features12.descriptorBindingStorageImageUpdateAfterBind(),
            "descriptorBindingStorageImageUpdateAfterBind");
        check(missing, features12.descriptorBindingUpdateUnusedWhilePending(),
            "descriptorBindingUpdateUnusedWhilePending");
        check(missing, features.features().shaderStorageImageReadWithoutFormat(), "shaderStorageImageReadWithoutFormat");
        check(missing, features.features().shaderStorageImageWriteWithoutFormat(),
            "shaderStorageImageWriteWithoutFormat");
        if (!missing.isEmpty()) {
            throw new VulkanException(gpu.name() + " lacks required features: " + missing);
        }
    }

    private static void check(List<String> missing, boolean supported, String feature) {
        if (!supported) {
            missing.add(feature);
        }
    }

    /** The cache every pipeline is made through; {@link PipelineCache#persist} keeps it across runs. */
    public PipelineCache pipelineCache() {
        return pipelineCache;
    }

    public Gpu gpu() {
        return gpu;
    }

    public VkDevice handle() {
        return handle;
    }

    /**
     * Throws like {@link Vulkan#check} for {@code result}, what a call on this device returned: a lost device's
     * exception also says what the driver says went wrong ({@link #faultReport()}).
     */
    public void check(int result, String what) {
        if (result == VK_ERROR_DEVICE_LOST) {
            lost = true;
            String report = faultReport();
            throw new DeviceLostException(report == null ? what : what + ": " + report);
        }
        Vulkan.check(result, what);
    }

    /**
     * What the driver says went wrong, once a call checked by {@link #check} found the device lost (VK_EXT_device_fault;
     * the driver may only be asked then): the fault, the addresses it involved, each with the buffer it falls in (with
     * validation on, which notes where each was made), and the vendor's codes. Null before, or when the driver cannot
     * say.
     */
    @AllowAllocation("failures")
    public String faultReport() {
        if (!faults || !lost) {
            return null;
        }
        try (MemoryStack stack = stackPush()) {
            VkDeviceFaultCountsEXT counts = VkDeviceFaultCountsEXT.calloc(stack).sType$Default();
            if (vkGetDeviceFaultInfoEXT(handle, counts, null) != VK_SUCCESS) {
                return null;
            }
            int addressCount = counts.addressInfoCount();
            int vendorCount = counts.vendorInfoCount();
            VkDeviceFaultAddressInfoEXT.Buffer addresses = VkDeviceFaultAddressInfoEXT.calloc(Math.max(1, addressCount),
                stack);
            VkDeviceFaultVendorInfoEXT.Buffer vendors = VkDeviceFaultVendorInfoEXT.calloc(Math.max(1, vendorCount), stack);
            counts.vendorBinarySize(0); // the vendor's binary dump: not read
            VkDeviceFaultInfoEXT info = VkDeviceFaultInfoEXT.calloc(stack).sType$Default().pAddressInfos(addresses)
                .pVendorInfos(vendors);
            int result = vkGetDeviceFaultInfoEXT(handle, counts, info);
            if (result != VK_SUCCESS && result != VK_INCOMPLETE) {
                return null;
            }
            StringBuilder report = new StringBuilder(info.descriptionString());
            for (int i = 0; i < Math.min(addressCount, counts.addressInfoCount()); i++) {
                var address = addresses.get(i);
                report.append("; ").append(addressReport(address.addressType(), address.reportedAddress(),
                    address.addressPrecision()));
            }
            for (int i = 0; i < Math.min(vendorCount, counts.vendorInfoCount()); i++) {
                var vendor = vendors.get(i);
                report.append(String.format("; %s (code 0x%x, data 0x%x)", vendor.descriptionString(),
                    vendor.vendorFaultCode(), vendor.vendorFaultData()));
            }
            return report.toString();
        }
    }

    /** Whether the driver can say what went wrong when the device is lost (VK_EXT_device_fault). */
    boolean reportsFaults() {
        return faults;
    }

    /**
     * One address of a fault report: what was done there ({@code VK_DEVICE_FAULT_ADDRESS_TYPE_*}), the address, and the
     * buffers it may fall in. The address is known to {@code precision}, a power of two: it lies in the aligned range of
     * that size.
     */
    @AllowAllocation("failures")
    String addressReport(int type, long address, long precision) {
        long size = Math.max(1, precision);
        long low = address & -size;
        return String.format("%s at 0x%x (to 0x%x): %s", faultKind(type), address, size, bufferAt(low, low + size));
    }

    private static String faultKind(int type) {
        return switch (type) {
            case VK_DEVICE_FAULT_ADDRESS_TYPE_READ_INVALID_EXT -> "invalid read";
            case VK_DEVICE_FAULT_ADDRESS_TYPE_WRITE_INVALID_EXT -> "invalid write";
            case VK_DEVICE_FAULT_ADDRESS_TYPE_EXECUTE_INVALID_EXT -> "invalid execution";
            case VK_DEVICE_FAULT_ADDRESS_TYPE_INSTRUCTION_POINTER_UNKNOWN_EXT -> "instruction pointer (unknown)";
            case VK_DEVICE_FAULT_ADDRESS_TYPE_INSTRUCTION_POINTER_INVALID_EXT -> "instruction pointer (invalid)";
            case VK_DEVICE_FAULT_ADDRESS_TYPE_INSTRUCTION_POINTER_FAULT_EXT -> "instruction pointer (fault)";
            default -> "address type " + type;
        };
    }

    /** The open buffers overlapping addresses [from, to), with where each was made; validation only. */
    private String bufferAt(long from, long to) {
        if (children == null) {
            return "buffers unknown without validation";
        }
        StringBuilder found = new StringBuilder();
        synchronized (children) {
            children.forEach((child, made) -> {
                if (child instanceof GpuBuffer buffer && buffer.hasDeviceAddress()) {
                    long start = buffer.deviceAddress();
                    if (start < to && from < start + buffer.size()) {
                        found.append(found.isEmpty() ? "" : ", ").append(String.format("%s at 0x%x, made at %s",
                            buffer, start, madeAt(made)));
                    }
                }
            });
        }
        return found.isEmpty() ? "in no buffer of the device" : found.toString();
    }

    /** The first caller outside this package in {@code made}'s stack: who asked for the buffer. */
    private static String madeAt(Throwable made) {
        for (StackTraceElement frame : made.getStackTrace()) {
            if (!frame.getClassName().startsWith(VulkanDevice.class.getPackageName() + ".")) {
                return frame.toString();
            }
        }
        return "?";
    }

    /** The Vulkan version used on this device: 1.3, or 1.4 when both the GPU and the instance have it. */
    public int apiVersion() {
        return apiVersion;
    }

    /** The Vulkan 1.4 features enabled on this device; {@link Vulkan14Features#NONE} on a 1.3 device. */
    /** Whether HDR swapchains can tell displays about their content (VK_EXT_hdr_metadata): a presenting GPU has it. */
    public boolean hdrMetadata() {
        return hdrMetadata;
    }

    /** Whether task and mesh shaders (VK_EXT_mesh_shader) are enabled: the GPU has them. */
    public boolean meshShaders() {
        return meshShaders;
    }

    /** Whether fragment shaders read barycentric coordinates (VK_KHR_fragment_shader_barycentric): the GPU has them. */
    public boolean barycentrics() {
        return barycentrics;
    }

    /**
     * Whether hardware ray tracing is on: acceleration structures (VK_KHR_acceleration_structure), traced with ray
     * queries from any shader (VK_KHR_ray_query). The bindless set then has a binding for acceleration structures.
     */
    public boolean rayTracing() {
        return rayTracing;
    }

    /**
     * Whether fragment shaders may read which primitive they shade (SV_PrimitiveID): the geometryShader feature, which
     * desktop GPUs have. The visibility buffer needs it.
     */
    public boolean primitiveIds() {
        return primitiveIds;
    }

    /** Whether images may hold several cube maps (imageCubeArray): nearly every GPU can. */
    public boolean cubeArrays() {
        return cubeArrays;
    }

    /** Whether samplers may filter anisotropically (samplerAnisotropy): nearly every GPU can. */
    public boolean anisotropic() {
        return anisotropic;
    }

    /** The most anisotropic filtering samplers use, where {@link #anisotropic()}: the GPU's limit, at most 16. */
    public float maxAnisotropy() {
        return maxAnisotropy;
    }

    public Vulkan14Features features14() {
        return features14;
    }

    /** The VMA allocator, for buffers and images; throws once the device is closed, where VMA would crash. */
    public long allocator() {
        if (closed) {
            throw new IllegalStateException("The device is closed: close what was made on it first");
        }
        return allocator;
    }

    /** Notes {@code child}, made on this device, so closing the device reports it if still open (validation only). */
    void track(Object child) {
        if (children != null) {
            synchronized (children) {
                children.put(child, new Throwable("made here"));
            }
        }
    }

    /** Notes that {@code child} was closed. */
    void untrack(Object child) {
        if (children != null) {
            synchronized (children) {
                children.remove(child);
            }
        }
    }

    /** The buffers and images made on this device and not closed yet; -1 without validation, which tracks them. */
    public int openChildren() {
        if (children == null) {
            return -1;
        }
        synchronized (children) {
            return children.size();
        }
    }

    /** The device's bindless descriptor set and the pipeline layout all pipelines share. */
    /** The Vulkan calls of every frame made allocation free, past LWJGL. */
    public DirectCalls direct() {
        return direct;
    }

    public Bindless bindless() {
        return bindless;
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

    /**
     * Waits until the device has finished all work, e.g. before destroying resources. Holds every queue meanwhile, as
     * Vulkan requires: submits from other threads wait.
     */
    public void waitIdle() {
        synchronized (graphics) { // in one order, as no queue lock is taken in another
            synchronized (compute) {
                synchronized (transfer) {
                    check(vkDeviceWaitIdle(handle), "Could not wait for the device");
                }
            }
        }
    }

    /**
     * Records commands with {@code commands} and runs them on {@code queue}, waiting until they completed: for setup
     * work such as uploads, not for frames. Each queue keeps a command pool and a fence for it, reset each time: waiting
     * for a fence just made took 8 ms on an RX 9070 XT, a reused one 0.06 ms. Each call takes an idle one of the queue's
     * (made the first time more are needed at once: calls from several threads, or one from inside {@code commands}),
     * so threads wait for their own work only, and gives it back.
     */
    public void submitAndWait(Queue queue, Consumer<VkCommandBuffer> commands) {
        OneOff oneOff = takeOneOff(queue);
        try {
            oneOff.run(queue, commands);
        } finally {
            synchronized (oneOffs) {
                oneOffs.get(queue).push(oneOff);
            }
        }
    }

    /** An idle command pool, buffer and fence of {@code queue}'s for {@link #submitAndWait}, made if none is idle. */
    private OneOff takeOneOff(Queue queue) {
        synchronized (oneOffs) {
            OneOff idle = oneOffs.computeIfAbsent(queue, q -> new java.util.ArrayDeque<>()).poll();
            if (idle != null) {
                return idle;
            }
            OneOff made = new OneOff(queue.family());
            allOneOffs.add(made);
            return made;
        }
    }

    /** A queue's reusable means for {@link #submitAndWait}: a command pool with one buffer, and a fence. */
    private final class OneOff {
        final long pool;
        final VkCommandBuffer commands;
        final long fence;

        OneOff(int family) {
            try (MemoryStack stack = stackPush()) {
                LongBuffer made = stack.mallocLong(1);
                Vulkan.check(vkCreateCommandPool(handle, VkCommandPoolCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO)
                    .flags(VK_COMMAND_POOL_CREATE_TRANSIENT_BIT)
                    .queueFamilyIndex(family), null, made), "Could not create a command pool");
                pool = made.get(0);
                PointerBuffer buffer = stack.mallocPointer(1);
                Vulkan.check(vkAllocateCommandBuffers(handle, VkCommandBufferAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO)
                    .commandPool(pool)
                    .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                    .commandBufferCount(1), buffer), "Could not allocate a command buffer");
                commands = new VkCommandBuffer(buffer.get(0), handle);
                Vulkan.check(vkCreateFence(handle, VkFenceCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_FENCE_CREATE_INFO), null, made), "Could not create a fence");
                fence = made.get(0);
            }
        }

        void run(Queue queue, Consumer<VkCommandBuffer> record) {
            try (MemoryStack stack = stackPush()) {
                Vulkan.check(vkResetCommandPool(handle, pool, 0), "Could not reset a command pool");
                Vulkan.check(vkBeginCommandBuffer(commands, VkCommandBufferBeginInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)
                    .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)), "Could not begin recording");
                record.accept(commands);
                Vulkan.check(vkEndCommandBuffer(commands), "Could not finish recording");
                LongBuffer fences = stack.longs(fence);
                Vulkan.check(vkResetFences(handle, fences), "Could not reset a fence");
                queue.submit(VkSubmitInfo2.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_SUBMIT_INFO_2)
                    .pCommandBufferInfos(VkCommandBufferSubmitInfo.calloc(1, stack)
                        .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_SUBMIT_INFO)
                        .commandBuffer(commands)), fence);
                check(vkWaitForFences(handle, fences, true, Long.MAX_VALUE), "Could not wait for the commands");
            }
        }

        void destroy() {
            vkDestroyFence(handle, fence, null);
            vkDestroyCommandPool(handle, pool, null);
        }
    }

    /** Waits for the device and destroys it; close resources created on it first. */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        waitIdle();
        if (children != null) {
            synchronized (children) {
                if (!children.isEmpty()) {
                    LOG.warn("{} buffers and images were not closed before their device", children.size());
                    children.forEach((child, made) -> LOG.warn("Not closed: {}", child, made));
                }
            }
        }
        synchronized (oneOffs) {
            for (OneOff oneOff : allOneOffs) {
                oneOff.destroy();
            }
            allOneOffs.clear();
            oneOffs.clear();
        }
        pipelineCache.close();
        bindless.close();
        closed = true;
        vmaDestroyAllocator(allocator);
        vkDestroyDevice(handle, null);
    }
}
