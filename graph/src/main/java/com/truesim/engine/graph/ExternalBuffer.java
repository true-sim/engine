package com.truesim.engine.graph;

import com.truesim.engine.vulkan.GpuBuffer;
import java.util.Objects;

/**
 * A buffer the graph does not own, such as a game's simulation data, a {@code RecordBuffer}'s records or the engine's
 * instances: passes use it as any buffer ({@link GraphBuilder#importBuffer}), and the graph places the barriers between
 * them from what they declare. Its contents outlive the frame: a pass writing it is always kept, and the frame's first
 * use waits for whatever came before the frame (uploads, the frames before). Async passes use it too where the queues
 * are of one family, or where it is shared by both ({@code GpuBuffer.concurrent()}): the graph transfers no ownership.
 * The buffer may change between frames ({@link #set}); a new size rebuilds the graph. Every {@code ExternalBuffer} of
 * one GPU buffer is the same buffer of a build, so set them alike.
 */
public final class ExternalBuffer {

    final String name;
    long handle;
    long address;
    long size;
    boolean concurrent; // usable by the graphics and async compute queues at once
    int version;

    /** {@code buffer}, by name {@code name} in the graph's passes and errors. */
    public ExternalBuffer(String name, GpuBuffer buffer) {
        this.name = Objects.requireNonNull(name, "name");
        set(buffer);
    }

    /** The buffer for the frames recorded from now on. Allocates nothing. */
    public ExternalBuffer set(GpuBuffer buffer) {
        if (buffer.size() != size) {
            size = buffer.size();
            version++;
        }
        handle = buffer.handle();
        concurrent = buffer.concurrent();
        address = buffer.hasDeviceAddress() ? buffer.deviceAddress() : 0; // e.g. a readback: copied into, not read
        return this;
    }

    public String name() {
        return name;
    }

    public long size() {
        return size;
    }
}
