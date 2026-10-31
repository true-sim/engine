package com.truesim.engine.vulkan;

/**
 * A buffer shaders reach by address ({@code T*} in Slang), e.g. from a material's parameters: a {@link GpuBuffer}, a
 * {@link FrameBuffer} (the open slot's copy), a {@link RecordBuffer}'s records, or a frame graph's external buffer.
 * The address may change from frame to frame (a frame slot's copy, a buffer grown): read it as frames record.
 */
public interface DeviceBuffer {

    /** The address shaders read it at, for the frame being recorded. */
    long deviceAddress();
}
