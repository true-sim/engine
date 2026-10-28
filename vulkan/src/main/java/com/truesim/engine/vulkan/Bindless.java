package com.truesim.engine.vulkan;

import static org.lwjgl.system.MemoryStack.stackPush;
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
import static org.lwjgl.vulkan.VK10.nvkCmdBindDescriptorSets;
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

/**
 * The device's one descriptor set, bindless: every sampled and storage image a shader may use sits in a slot of a large
 * array, and shaders take slot numbers in their push constants, as they take buffers by device address. With the
 * device's one pipeline layout (this set and {@link #PUSH_BYTES} of push constants for every stage), every pipeline is
 * compatible with every other: the set is bound once per command buffer, never per draw.
 *
 * <ul>
 *   <li>binding 0: sampled images, {@link #SAMPLED_SLOTS} of them, in the shader-read-only layout;</li>
 *   <li>binding 1: samplers, see {@link #LINEAR_CLAMP} and its neighbors;</li>
 *   <li>binding 2: storage images, {@link #STORAGE_SLOTS} of them, in the general layout.</li>
 * </ul>
 *
 * <p>Slots may be written while command buffers using the set are pending (update after bind), as long as those
 * command buffers do not use the slot: free a slot only once the frames that used it have finished.
 */
@AllowAllocation("set up once")
public final class Bindless implements AutoCloseable {

    /** Push constants every pipeline may use, in bytes: the guaranteed minimum. */
    public static final int PUSH_BYTES = 128;
    public static final int SAMPLED_SLOTS = 16_384;
    public static final int STORAGE_SLOTS = 4096;
    /** Sampler slots: linear filtering, clamped to the edge. */
    public static final int LINEAR_CLAMP = 0;
    /** Nearest texel, clamped to the edge. */
    public static final int NEAREST_CLAMP = 1;
    /** Linear filtering, repeating. */
    public static final int LINEAR_REPEAT = 2;

    private final VulkanDevice device;
    private final long[] samplers = new long[3];
    private final long setLayout;
    private final long pipelineLayout;
    private final long pool;
    private final long set;
    private final Slots sampled = new Slots(SAMPLED_SLOTS);
    private final Slots storage = new Slots(STORAGE_SLOTS);
    private boolean closed;

    Bindless(VulkanDevice device) {
        this.device = device;
        try (MemoryStack stack = stackPush()) {
            samplers[LINEAR_CLAMP] = sampler(stack, VK_FILTER_LINEAR, VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE);
            samplers[NEAREST_CLAMP] = sampler(stack, VK_FILTER_NEAREST, VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE);
            samplers[LINEAR_REPEAT] = sampler(stack, VK_FILTER_LINEAR, VK_SAMPLER_ADDRESS_MODE_REPEAT);
            VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(3, stack);
            bindings.get(0).binding(0).descriptorType(VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE).descriptorCount(SAMPLED_SLOTS)
                .stageFlags(VK_SHADER_STAGE_ALL);
            bindings.get(1).binding(1).descriptorType(VK_DESCRIPTOR_TYPE_SAMPLER).descriptorCount(samplers.length)
                .stageFlags(VK_SHADER_STAGE_ALL).pImmutableSamplers(stack.longs(samplers));
            bindings.get(2).binding(2).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(STORAGE_SLOTS)
                .stageFlags(VK_SHADER_STAGE_ALL);
            int slotFlags = VK_DESCRIPTOR_BINDING_PARTIALLY_BOUND_BIT | VK_DESCRIPTOR_BINDING_UPDATE_AFTER_BIND_BIT
                | VK_DESCRIPTOR_BINDING_UPDATE_UNUSED_WHILE_PENDING_BIT;
            VkDescriptorSetLayoutBindingFlagsCreateInfo flags = VkDescriptorSetLayoutBindingFlagsCreateInfo
                .calloc(stack)
                .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_BINDING_FLAGS_CREATE_INFO)
                .pBindingFlags(stack.ints(slotFlags, 0, slotFlags));
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
            VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(3, stack);
            sizes.get(0).type(VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE).descriptorCount(SAMPLED_SLOTS);
            sizes.get(1).type(VK_DESCRIPTOR_TYPE_SAMPLER).descriptorCount(samplers.length);
            sizes.get(2).type(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(STORAGE_SLOTS);
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

    private long sampler(MemoryStack stack, int filter, int address) {
        LongBuffer handle = stack.mallocLong(1);
        Vulkan.check(vkCreateSampler(device.handle(), VkSamplerCreateInfo.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO)
            .magFilter(filter)
            .minFilter(filter)
            .mipmapMode(filter == VK_FILTER_LINEAR ? VK_SAMPLER_MIPMAP_MODE_LINEAR : VK_SAMPLER_MIPMAP_MODE_NEAREST)
            .addressModeU(address)
            .addressModeV(address)
            .addressModeW(address)
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
    public int addSampled(long view) {
        int slot = sampled.take("sampled");
        write(0, slot, view, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE);
        return slot;
    }

    /** Puts {@code view} (in the general layout) in a free storage slot and returns the slot. */
    public int addStorage(long view) {
        int slot = storage.take("storage");
        write(2, slot, view, VK_IMAGE_LAYOUT_GENERAL, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE);
        return slot;
    }

    /** Frees a sampled slot; no pending command buffer may still use it. */
    public void removeSampled(int slot) {
        sampled.free(slot);
    }

    /** Frees a storage slot; no pending command buffer may still use it. */
    public void removeStorage(int slot) {
        storage.free(slot);
    }

    /** How many sampled and storage slots are taken. */
    public int slotsInUse() {
        return sampled.used + storage.used;
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
        try (MemoryStack stack = stackPush()) {
            long sets = stack.nmalloc(8, 8);
            memPutLong(sets, set);
            nvkCmdBindDescriptorSets(commands, VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout, 0, 1, sets, 0, 0);
            nvkCmdBindDescriptorSets(commands, VK_PIPELINE_BIND_POINT_GRAPHICS, pipelineLayout, 0, 1, sets, 0, 0);
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

        void free(int slot) {
            if (slot < 0 || slot >= capacity || !taken.get(slot)) {
                throw new IllegalArgumentException("Slot " + slot + " is not taken");
            }
            taken.clear(slot);
            used--;
        }
    }
}
