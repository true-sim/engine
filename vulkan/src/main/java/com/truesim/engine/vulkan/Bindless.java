package com.truesim.engine.vulkan;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET_ACCELERATION_STRUCTURE_KHR;
import static org.lwjgl.vulkan.VK10.VK_BORDER_COLOR_FLOAT_TRANSPARENT_BLACK;
import static org.lwjgl.vulkan.VK10.VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE;
import static org.lwjgl.vulkan.VK10.VK_DESCRIPTOR_TYPE_SAMPLER;
import static org.lwjgl.vulkan.VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
import static org.lwjgl.vulkan.VK10.VK_FILTER_LINEAR;
import static org.lwjgl.vulkan.VK10.VK_FILTER_NEAREST;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_GENERAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_BIND_POINT_COMPUTE;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_BIND_POINT_GRAPHICS;
import static org.lwjgl.vulkan.VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
import static org.lwjgl.vulkan.VK10.VK_SAMPLER_ADDRESS_MODE_REPEAT;
import static org.lwjgl.vulkan.VK10.VK_SAMPLER_MIPMAP_MODE_LINEAR;
import static org.lwjgl.vulkan.VK10.VK_SAMPLER_MIPMAP_MODE_NEAREST;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_ALL;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
import static org.lwjgl.vulkan.VK10.vkAllocateDescriptorSets;
import static org.lwjgl.system.MemoryUtil.memPutLong;
import static org.lwjgl.vulkan.VK10.vkCreateDescriptorPool;
import static org.lwjgl.vulkan.VK10.vkCreateDescriptorSetLayout;
import static org.lwjgl.vulkan.VK10.vkCreatePipelineLayout;
import static org.lwjgl.vulkan.VK10.vkCreateSampler;
import static org.lwjgl.vulkan.VK10.vkDestroyDescriptorPool;
import static org.lwjgl.vulkan.VK10.vkDestroyDescriptorSetLayout;
import static org.lwjgl.vulkan.VK10.vkDestroyPipelineLayout;
import static org.lwjgl.vulkan.VK10.vkDestroySampler;
import static org.lwjgl.vulkan.VK10.vkUpdateDescriptorSets;
import static org.lwjgl.vulkan.VK12.VK_DESCRIPTOR_BINDING_PARTIALLY_BOUND_BIT;
import static org.lwjgl.vulkan.VK12.VK_DESCRIPTOR_BINDING_UPDATE_AFTER_BIND_BIT;
import static org.lwjgl.vulkan.VK12.VK_DESCRIPTOR_BINDING_UPDATE_UNUSED_WHILE_PENDING_BIT;
import static org.lwjgl.vulkan.VK12.VK_DESCRIPTOR_POOL_CREATE_UPDATE_AFTER_BIND_BIT;
import static org.lwjgl.vulkan.VK12.VK_DESCRIPTOR_SET_LAYOUT_CREATE_UPDATE_AFTER_BIND_POOL_BIT;
import static org.lwjgl.vulkan.VK12.VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_BINDING_FLAGS_CREATE_INFO;

import com.truesim.annotations.AllowAllocation;
import java.nio.LongBuffer;
import java.util.BitSet;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBindingFlagsCreateInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;
import org.lwjgl.vulkan.VkSamplerCreateInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;
import org.lwjgl.vulkan.VkWriteDescriptorSetAccelerationStructureKHR;

/**
 * The device's one descriptor set, bindless: every sampled and storage image a shader may use sits in a slot of a large
 * array, and shaders take slot numbers in their push constants, as they take buffers by device address. With the
 * device's one pipeline layout (this set and {@link #PUSH_BYTES} of push constants for every stage), every pipeline is
 * compatible with every other: the set is bound once per command buffer, never per draw.
 *
 * <ul>
 *   <li>binding 0: sampled images, {@link #SAMPLED_SLOTS} of them, in the shader-read-only layout;</li>
 *   <li>binding 1: samplers, see {@link #LINEAR_CLAMP} and its neighbors;</li>
 *   <li>binding 2: storage images, {@link #STORAGE_SLOTS} of them, in the general layout;</li>
 *   <li>binding 3, on devices with ray tracing: acceleration structures, {@link #STRUCTURE_SLOTS} of them.</li>
 * </ul>
 *
 * <p>Slots may be written while command buffers using the set are pending (update after bind), as long as those
 * command buffers do not use the slot: free a slot only once the frames that used it have finished.
 *
 * <p>Adding and removing slots may happen on any thread: they lock, as descriptor updates must not overlap.
 */
@AllowAllocation("set up once")
public final class Bindless implements AutoCloseable {

    /** Push constants every pipeline may use, in bytes: the guaranteed minimum. */
    public static final int PUSH_BYTES = 128;
    public static final int SAMPLED_SLOTS = 16_384;
    public static final int STORAGE_SLOTS = 4096;
    /** Acceleration structure slots (binding 3), on devices with {@link VulkanDevice#rayTracing()}. */
    public static final int STRUCTURE_SLOTS = 64;
    /** Sampler slots: linear filtering, clamped to the edge. */
    public static final int LINEAR_CLAMP = 0;
    /** Nearest texel, clamped to the edge. */
    public static final int NEAREST_CLAMP = 1;
    /** Linear filtering, repeating. */
    public static final int LINEAR_REPEAT = 2;
    /** Trilinear and anisotropic (as far as the device goes, up to 16), repeating: for material textures. */
    public static final int MATERIAL = 3;

    private final VulkanDevice device;
    private final long[] samplers = new long[4];
    // Set once while constructing; 0 until made, so a constructor that fails frees what it made (destroying 0 is a
    // no-op).
    private long setLayout;
    private long pipelineLayout;
    private long pool;
    private long set;
    private final Slots sampled = new Slots(SAMPLED_SLOTS);
    private final Slots storage = new Slots(STORAGE_SLOTS);
    private final Slots structures = new Slots(STRUCTURE_SLOTS);
    private boolean closed;

    Bindless(VulkanDevice device) {
        this.device = device;
        try {
            create();
        } catch (RuntimeException e) {
            close();
            throw e;
        }
    }

    private void create() {
        try (MemoryStack stack = stackPush()) {
            samplers[LINEAR_CLAMP] = sampler(stack, VK_FILTER_LINEAR, VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE, false);
            samplers[NEAREST_CLAMP] = sampler(stack, VK_FILTER_NEAREST, VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE, false);
            samplers[LINEAR_REPEAT] = sampler(stack, VK_FILTER_LINEAR, VK_SAMPLER_ADDRESS_MODE_REPEAT, false);
            samplers[MATERIAL] = sampler(stack, VK_FILTER_LINEAR, VK_SAMPLER_ADDRESS_MODE_REPEAT, device.anisotropic());
            boolean traced = device.rayTracing();
            int count = traced ? 4 : 3;
            VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(count, stack);
            bindings.get(0).binding(0).descriptorType(VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE).descriptorCount(SAMPLED_SLOTS)
                .stageFlags(VK_SHADER_STAGE_ALL);
            bindings.get(1).binding(1).descriptorType(VK_DESCRIPTOR_TYPE_SAMPLER).descriptorCount(samplers.length)
                .stageFlags(VK_SHADER_STAGE_ALL).pImmutableSamplers(stack.longs(samplers));
            bindings.get(2).binding(2).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(STORAGE_SLOTS)
                .stageFlags(VK_SHADER_STAGE_ALL);
            if (traced) {
                bindings.get(3).binding(3).descriptorType(VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR)
                    .descriptorCount(STRUCTURE_SLOTS).stageFlags(VK_SHADER_STAGE_ALL);
            }
            int slotFlags = VK_DESCRIPTOR_BINDING_PARTIALLY_BOUND_BIT | VK_DESCRIPTOR_BINDING_UPDATE_AFTER_BIND_BIT
                | VK_DESCRIPTOR_BINDING_UPDATE_UNUSED_WHILE_PENDING_BIT;
            VkDescriptorSetLayoutBindingFlagsCreateInfo flags = VkDescriptorSetLayoutBindingFlagsCreateInfo
                .calloc(stack)
                .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_BINDING_FLAGS_CREATE_INFO)
                .pBindingFlags(traced ? stack.ints(slotFlags, 0, slotFlags, slotFlags)
                    : stack.ints(slotFlags, 0, slotFlags));
            LongBuffer handle = stack.mallocLong(1);
            Vulkan.check(vkCreateDescriptorSetLayout(device.handle(), VkDescriptorSetLayoutCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO)
                .pNext(flags.address())
                .flags(VK_DESCRIPTOR_SET_LAYOUT_CREATE_UPDATE_AFTER_BIND_POOL_BIT)
                .pBindings(bindings), null, handle), "Could not create the bindless set layout");
            setLayout = handle.get(0);
            Vulkan.check(vkCreatePipelineLayout(device.handle(), VkPipelineLayoutCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO)
                .pSetLayouts(stack.longs(setLayout))
                .pPushConstantRanges(VkPushConstantRange.calloc(1, stack).stageFlags(VK_SHADER_STAGE_ALL)
                    .size(PUSH_BYTES)), null, handle), "Could not create the pipeline layout");
            pipelineLayout = handle.get(0);
            VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(count, stack);
            sizes.get(0).type(VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE).descriptorCount(SAMPLED_SLOTS);
            sizes.get(1).type(VK_DESCRIPTOR_TYPE_SAMPLER).descriptorCount(samplers.length);
            sizes.get(2).type(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(STORAGE_SLOTS);
            if (traced) {
                sizes.get(3).type(VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR).descriptorCount(STRUCTURE_SLOTS);
            }
            Vulkan.check(vkCreateDescriptorPool(device.handle(), VkDescriptorPoolCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO)
                .flags(VK_DESCRIPTOR_POOL_CREATE_UPDATE_AFTER_BIND_BIT)
                .maxSets(1)
                .pPoolSizes(sizes), null, handle), "Could not create the bindless descriptor pool");
            pool = handle.get(0);
            Vulkan.check(vkAllocateDescriptorSets(device.handle(), VkDescriptorSetAllocateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO)
                .descriptorPool(pool)
                .pSetLayouts(stack.longs(setLayout)), handle), "Could not allocate the bindless set");
            set = handle.get(0);
        }
    }

    private long sampler(MemoryStack stack, int filter, int address, boolean anisotropic) {
        LongBuffer handle = stack.mallocLong(1);
        Vulkan.check(vkCreateSampler(device.handle(), VkSamplerCreateInfo.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO)
            .magFilter(filter)
            .minFilter(filter)
            .mipmapMode(filter == VK_FILTER_LINEAR ? VK_SAMPLER_MIPMAP_MODE_LINEAR : VK_SAMPLER_MIPMAP_MODE_NEAREST)
            .addressModeU(address)
            .addressModeV(address)
            .addressModeW(address)
            .anisotropyEnable(anisotropic)
            .maxAnisotropy(device.maxAnisotropy())
            .maxLod(1000)
            .borderColor(VK_BORDER_COLOR_FLOAT_TRANSPARENT_BLACK), null, handle), "Could not create a sampler");
        return handle.get(0);
    }

    /** The pipeline layout every {@link Pipeline} uses: this set and {@link #PUSH_BYTES} of push constants. */
    public long pipelineLayout() {
        return pipelineLayout;
    }

    public long set() {
        return set;
    }

    /** Puts {@code view} (in the shader-read-only layout) in a free sampled slot and returns the slot. */
    public synchronized int addSampled(long view) {
        int slot = sampled.take("sampled");
        write(0, slot, view, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE);
        return slot;
    }

    /** Puts {@code view} (in the general layout) in a free storage slot and returns the slot. */
    public synchronized int addStorage(long view) {
        int slot = storage.take("storage");
        write(2, slot, view, VK_IMAGE_LAYOUT_GENERAL, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE);
        return slot;
    }

    /**
     * Puts acceleration structure {@code structure} (a top-level one, to trace rays into) in a free slot of binding 3
     * and returns the slot; on devices with {@link VulkanDevice#rayTracing()} only.
     */
    public synchronized int addAccelerationStructure(long structure) {
        if (!device.rayTracing()) {
            throw new IllegalStateException("The device has no ray tracing");
        }
        int slot = structures.take("acceleration structure");
        try (MemoryStack stack = stackPush()) {
            VkWriteDescriptorSetAccelerationStructureKHR info = VkWriteDescriptorSetAccelerationStructureKHR.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET_ACCELERATION_STRUCTURE_KHR)
                .pAccelerationStructures(stack.longs(structure));
            VkWriteDescriptorSet.Buffer write = VkWriteDescriptorSet.calloc(1, stack)
                .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                .pNext(info.address())
                .dstSet(set)
                .dstBinding(3)
                .dstArrayElement(slot)
                .descriptorType(VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR)
                .descriptorCount(1);
            vkUpdateDescriptorSets(device.handle(), write, null);
        }
        return slot;
    }

    /**
     * Frees an acceleration structure slot at once, for the lowest-first reuse of the next add: only when no pending
     * command buffer may still use it: the device idles, or its owner closes (a top-level structure lives as long as
     * its owner).
     */
    public synchronized void removeAccelerationStructure(int slot) {
        structures.free(slot);
    }

    /**
     * Frees a sampled slot at once, for the lowest-first reuse of the next add: only when no pending command buffer may
     * still use it (the device idles, say). Else {@link #removeSampled(int, Retiring)}.
     */
    public synchronized void removeSampled(int slot) {
        sampled.free(slot);
    }

    /**
     * Frees a storage slot at once, for the lowest-first reuse of the next add: only when no pending command buffer may
     * still use it (the device idles, say). Else {@link #removeStorage(int, Retiring)}.
     */
    public synchronized void removeStorage(int slot) {
        storage.free(slot);
    }

    /**
     * Frees a sampled slot once no frame in flight of {@code retiring}'s slots can sample it: as descriptors update
     * after bind, a slot reused earlier would be rewritten under a frame still reading it.
     */
    @AllowAllocation("removing, rarely")
    public synchronized void removeSampled(int slot, Retiring retiring) {
        sampled.check(slot);
        retiring.add(() -> removeSampled(slot)); // locks when it runs
    }

    /** Frees a storage slot once no frame in flight of {@code retiring}'s slots can use it. */
    @AllowAllocation("removing, rarely")
    public synchronized void removeStorage(int slot, Retiring retiring) {
        storage.check(slot);
        retiring.add(() -> removeStorage(slot));
    }

    /** How many sampled and storage slots are taken. */
    public int slotsInUse() {
        return sampled.used + storage.used + structures.used;
    }

    private void write(int binding, int slot, long view, int layout, int type) {
        try (MemoryStack stack = stackPush()) {
            VkWriteDescriptorSet.Buffer write = VkWriteDescriptorSet.calloc(1, stack)
                .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                .dstSet(set)
                .dstBinding(binding)
                .dstArrayElement(slot)
                .descriptorType(type)
                .descriptorCount(1)
                .pImageInfo(VkDescriptorImageInfo.calloc(1, stack).imageView(view).imageLayout(layout));
            vkUpdateDescriptorSets(device.handle(), write, null);
        }
    }

    /** Binds the set for compute and graphics pipelines. Allocates nothing. */
    public void bind(VkCommandBuffer commands) {
        bind(commands, true);
    }

    /**
     * Binds the set for compute pipelines and, with {@code graphics}, graphics ones (not on compute-only queues).
     * Allocates nothing.
     */
    public void bind(VkCommandBuffer commands, boolean graphics) {
        try (MemoryStack stack = stackPush()) {
            long sets = stack.nmalloc(8, 8);
            memPutLong(sets, set);
            Commands.bindDescriptorSets(commands, VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout, 0, 1, sets);
            if (graphics) {
                Commands.bindDescriptorSets(commands, VK_PIPELINE_BIND_POINT_GRAPHICS, pipelineLayout, 0, 1, sets);
            }
        }
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            vkDestroyDescriptorPool(device.handle(), pool, null);
            vkDestroyPipelineLayout(device.handle(), pipelineLayout, null);
            vkDestroyDescriptorSetLayout(device.handle(), setLayout, null);
            for (long sampler : samplers) {
                vkDestroySampler(device.handle(), sampler, null);
            }
        }
    }

    /** Free slots of one binding, lowest first, so slot numbers stay small and deterministic. */
    @AllowAllocation("set up once")
    private static final class Slots {
        private final BitSet taken;
        private final int capacity;
        int used;

        Slots(int capacity) {
            this.capacity = capacity;
            this.taken = new BitSet(capacity);
        }

        int take(String kind) {
            int slot = taken.nextClearBit(0);
            if (slot >= capacity) {
                throw new IllegalStateException("All " + capacity + " " + kind + " slots are taken");
            }
            taken.set(slot);
            used++;
            return slot;
        }

        void check(int slot) {
            if (slot < 0 || slot >= capacity || !taken.get(slot)) {
                throw new IllegalArgumentException("Slot " + slot + " is not taken");
            }
        }

        void free(int slot) {
            check(slot);
            taken.clear(slot);
            used--;
        }
    }
}
