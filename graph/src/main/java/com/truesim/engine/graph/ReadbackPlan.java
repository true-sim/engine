package com.truesim.engine.graph;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.vulkan.GpuBuffer;
import com.truesim.engine.vulkan.VulkanDevice;
import java.lang.foreign.MemorySegment;
import java.util.Arrays;

/** A readback of a built graph: per frame slot, host memory the copy lands in and the frame waiting in it, if any. */
final class ReadbackPlan implements AutoCloseable {

    final Readback receiver;
    final long bytes;
    private GpuBuffer[] staging = new GpuBuffer[0];
    private MemorySegment[] views = new MemorySegment[0];
    private long[] pending = new long[0];

    ReadbackPlan(Readback receiver, long bytes) {
        this.receiver = receiver;
        this.bytes = bytes;
    }

    /** Makes the host memory of each of {@code slots} frame slots. */
    @AllowAllocation("building the graph, when it changes")
    void allocate(VulkanDevice device, int slots, int usage) {
        staging = new GpuBuffer[slots];
        views = new MemorySegment[slots];
        pending = new long[slots];
        Arrays.fill(pending, -1);
        for (int s = 0; s < slots; s++) {
            staging[s] = GpuBuffer.hostVisible(device, bytes, usage);
            views[s] = staging[s].memory().asSlice(0, bytes);
        }
    }

    /** The {@code VkBuffer} slot {@code slot}'s copy lands in; frame {@code frame} is now waiting in it. */
    long target(int slot, long frame) {
        pending[slot] = frame;
        return staging[slot].handle();
    }

    /** Hands what frame slot {@code slot} holds to the receiver, if a frame copied into it. Allocates nothing. */
    void deliver(int slot) {
        long frame = pending[slot];
        if (frame >= 0) {
            pending[slot] = -1;
            receiver.receive(views[slot], frame);
        }
    }

    /** Whether a copy waits to be delivered. */
    boolean pending() {
        for (long frame : pending) {
            if (frame >= 0) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void close() {
        for (GpuBuffer buffer : staging) {
            buffer.close();
        }
        staging = new GpuBuffer[0];
    }
}
