package com.truesim.engine.graph;

import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_UNDEFINED;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_SAMPLED_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_STORAGE_BIT;
import static org.lwjgl.vulkan.VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.graph.GraphCompiler.BufferSlot;
import com.truesim.engine.graph.GraphCompiler.HistoryPlan;
import com.truesim.engine.graph.GraphCompiler.Slot;
import com.truesim.engine.vulkan.Bindless;
import com.truesim.engine.vulkan.Format;
import com.truesim.engine.vulkan.GpuBuffer;
import com.truesim.engine.vulkan.GpuImage;
import com.truesim.engine.vulkan.Queue;
import com.truesim.engine.vulkan.VulkanDevice;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The frame graph's images and buffers, kept across builds: pooled images and buffers for a compiled graph's slots
 * (reused while one fits), history pairs by name, and what was let go of, destroyed once the frames in flight are done
 * with it. The only part of the graph that allocates on the device.
 */
@AllowAllocation("building the graph, when it changes")
final class ResourcePool {

    private final VulkanDevice device;
    private final Bindless bindless;
    private final Queue graphicsQueue;
    private final Queue computeQueue;
    private final int framesInFlight;
    private final List<Physical> pool = new ArrayList<>();
    private final List<Physical> retired = new ArrayList<>();
    private final List<PhysicalBuffer> bufferPool = new ArrayList<>();
    private final List<PhysicalBuffer> retiredBuffers = new ArrayList<>();
    private final Map<String, HistoryPair> histories = new HashMap<>();

    ResourcePool(VulkanDevice device, int framesInFlight) {
        this.device = device;
        this.bindless = device.bindless();
        this.graphicsQueue = device.graphics();
        this.computeQueue = device.compute();
        this.framesInFlight = framesInFlight;
    }

    /**
     * The history pairs of {@code plans}, by name: a pair kept from the last build where its slot fits ({@code kept[h]}
     * true: its contents carry over), else a new one; pairs no plan names any more retire at {@code frame}.
     */
    HistoryPair[] histories(List<HistoryPlan> plans, boolean[] kept, long frame) {
        HistoryPair[] pairs = new HistoryPair[plans.size()];
        Map<String, HistoryPair> old = new HashMap<>(histories);
        histories.clear();
        for (int h = 0; h < plans.size(); h++) {
            HistoryPlan plan = plans.get(h);
            HistoryPair pair = old.remove(plan.name());
            kept[h] = pair != null && pair.fits(plan.slot());
            if (!kept[h]) {
                if (pair != null) {
                    retire(pair, frame);
                }
                pair = new HistoryPair(plan.slot());
            }
            histories.put(plan.name(), pair);
            pairs[h] = pair;
        }
        for (HistoryPair unused : old.values()) {
            retire(unused, frame);
        }
        return pairs;
    }

    /** Whether something waits to be destroyed. */
    boolean hasRetired() {
        return !retired.isEmpty() || !retiredBuffers.isEmpty();
    }

    int pooledImages() {
        return pool.size();
    }

    int pooledBuffers() {
        return bufferPool.size();
    }

    /** Destroys {@code pair}'s images once the frames in flight are done with them. */
    void retire(HistoryPair pair, long frame) {
        pair.current.retireAt = frame + framesInFlight;
        pair.previous.retireAt = frame + framesInFlight;
        retired.add(pair.current);
        retired.add(pair.previous);
    }

    /** The two images of a history, whose roles swap every frame. */
    @AllowAllocation("building the graph, when it changes")
    final class HistoryPair {
        final Physical current;
        final Physical previous;
        long firstFrame;
        int endCurrent = VK_IMAGE_LAYOUT_UNDEFINED; // the layout the last graph left the current role in

        HistoryPair(Slot slot) {
            current = new Physical(slot);
            previous = new Physical(slot);
        }

        boolean fits(Slot slot) {
            return current.desc.equals(slot.desc) && current.usage == slot.usage && current.shared == slot.shared;
        }

        void close() {
            current.close();
            previous.close();
        }
    }

    /** Takes pooled images for the slots, creating the missing ones; pooled images left over retire. */
    @AllowAllocation("building the graph, when it changes")
    Physical[] claim(List<Slot> slots, long frame) {
        Physical[] claimed = new Physical[slots.size()];
        List<Physical> free = new ArrayList<>(pool);
        for (int s = 0; s < slots.size(); s++) {
            Slot slot = slots.get(s);
            for (Physical candidate : free) {
                if (candidate.desc.equals(slot.desc) && candidate.usage == slot.usage && candidate.shared == slot.shared) {
                    claimed[s] = candidate;
                    free.remove(candidate);
                    break;
                }
            }
            if (claimed[s] == null) {
                claimed[s] = new Physical(slot);
                pool.add(claimed[s]);
            }
        }
        for (Physical unused : free) {
            pool.remove(unused);
            unused.retireAt = frame + framesInFlight;
            retired.add(unused);
        }
        return claimed;
    }

    /**
     * Takes pooled buffers for the slots, the smallest that fits and is at most twice the size, creating the missing
     * ones; pooled buffers left over retire.
     */
    @AllowAllocation("building the graph, when it changes")
    PhysicalBuffer[] claimBuffers(List<BufferSlot> slots, long frame) {
        PhysicalBuffer[] claimed = new PhysicalBuffer[slots.size()];
        List<PhysicalBuffer> free = new ArrayList<>(bufferPool);
        for (int s = 0; s < slots.size(); s++) {
            BufferSlot slot = slots.get(s);
            for (PhysicalBuffer candidate : free) {
                if (candidate.size >= slot.size && candidate.size / 2 <= slot.size && candidate.usage == slot.usage
                    && candidate.shared == slot.shared
                    && (claimed[s] == null || candidate.size < claimed[s].size)) {
                    claimed[s] = candidate;
                }
            }
            if (claimed[s] == null) {
                claimed[s] = new PhysicalBuffer(slot);
                bufferPool.add(claimed[s]);
            } else {
                free.remove(claimed[s]);
            }
        }
        for (PhysicalBuffer unused : free) {
            bufferPool.remove(unused);
            unused.retireAt = frame + framesInFlight;
            retiredBuffers.add(unused);
        }
        return claimed;
    }

    /** Destroys retired images and buffers whose frames have finished by {@code frame} (or all, when closing). */
    void release(boolean all, long frame) {
        for (int i = retiredBuffers.size() - 1; i >= 0; i--) {
            PhysicalBuffer physical = retiredBuffers.get(i);
            if (all || physical.retireAt <= frame) {
                physical.buffer.close();
                retiredBuffers.remove(i);
            }
        }
        for (int i = retired.size() - 1; i >= 0; i--) {
            Physical physical = retired.get(i);
            if (all || physical.retireAt <= frame) {
                physical.close();
                retired.remove(i);
            }
        }
    }

    /** Destroys everything: the GPU is done with it all. */
    void close() {
        for (Physical physical : pool) {
            physical.close();
        }
        pool.clear();
        for (PhysicalBuffer physical : bufferPool) {
            physical.buffer.close();
        }
        bufferPool.clear();
        for (HistoryPair pair : histories.values()) {
            pair.close();
        }
        histories.clear();
        release(true, 0);
    }

    /** A pooled buffer, reachable by device address. */
    @AllowAllocation("building the graph, when it changes")
    final class PhysicalBuffer {
        final GpuBuffer buffer;
        final long size;
        final int usage;
        final boolean shared;
        long retireAt;

        PhysicalBuffer(BufferSlot slot) {
            size = slot.size;
            usage = slot.usage;
            shared = slot.shared;
            int all = usage | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;
            buffer = shared ? GpuBuffer.deviceLocal(device, size, all, graphicsQueue.family(), computeQueue.family())
                : GpuBuffer.deviceLocal(device, size, all);
        }
    }

    /** A pooled image with its bindless slots. */
    @AllowAllocation("building the graph, when it changes")
    final class Physical {
        GpuImage image; // these three swap between a history's two images
        final ImageDesc desc;
        final Format format;
        final int usage;
        final boolean shared;
        final int mips;
        int sampled;
        int[] storage; // a slot per mip level
        int[] sampledMips; // a sampled slot per mip level, for reading one level while writing another
        long retireAt;

        Physical(Slot slot) {
            desc = slot.desc;
            format = desc.format();
            usage = slot.usage;
            shared = slot.shared;
            mips = desc.mips();
            int[] families = shared ? new int[] {graphicsQueue.family(), computeQueue.family()} : new int[0];
            image = GpuImage.create(device, format, desc.width(), desc.height(), desc.depth(), desc.layers(),
                desc.cubeMap(), desc.samples(), mips, usage, families);
            sampled = (usage & VK_IMAGE_USAGE_SAMPLED_BIT) != 0 ? bindless.addSampled(image.view()) : -1;
            storage = new int[(usage & VK_IMAGE_USAGE_STORAGE_BIT) != 0 ? mips : 0];
            for (int mip = 0; mip < storage.length; mip++) {
                storage[mip] = bindless.addStorage(image.view(mip));
            }
            sampledMips = new int[sampled >= 0 && mips > 1 ? mips : 0];
            for (int mip = 0; mip < sampledMips.length; mip++) {
                sampledMips[mip] = bindless.addSampled(image.view(mip));
            }
        }

        /** Exchanges images (and their slots) with {@code other}: a history's roles, frame to frame. */
        void swap(Physical other) {
            GpuImage i = image;
            image = other.image;
            other.image = i;
            int s = sampled;
            sampled = other.sampled;
            other.sampled = s;
            int[] st = storage;
            storage = other.storage;
            other.storage = st;
            int[] sm = sampledMips;
            sampledMips = other.sampledMips;
            other.sampledMips = sm;
        }

        void close() {
            if (sampled >= 0) {
                bindless.removeSampled(sampled);
            }
            for (int slot : storage) {
                bindless.removeStorage(slot);
            }
            for (int slot : sampledMips) {
                bindless.removeSampled(slot);
            }
            image.close();
        }
    }

}
