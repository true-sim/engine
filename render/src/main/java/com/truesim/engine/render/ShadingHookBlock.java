package com.truesim.engine.render;

import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
import static org.lwjgl.vulkan.VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.render.layout.HooksConstants;
import com.truesim.engine.render.layout.ShadingHookBlockLayout;
import com.truesim.engine.vulkan.FrameBuffer;
import com.truesim.engine.vulkan.FrameSlots;
import com.truesim.engine.vulkan.VulkanDevice;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

/**
 * A render view's shading hooks' block (hooks.slang's ShadingHookBlock): the camera's world position, a bit per hook
 * the view runs this frame, the records' offsets, then every hook's parameters ({@link ShadingHooks#recordOffset}), its
 * defaults where the view runs it not. Host-visible, a copy per frame slot ({@link FrameBuffer}); the view writes the
 * camera as it moves, its graph's {@link ViewShadingHooks} the rest each frame, composed in plain memory and handed over
 * in one {@link FrameBuffer#put} (pitfalls.md: a frame buffer written field by field).
 */
final class ShadingHookBlock implements AutoCloseable {

    private final ShadingHooks hooks;
    private final FrameBuffer buffer;
    private final MemorySegment staging; // the bits, offsets and records as composed for the frame

    @AllowAllocation("made with its view")
    ShadingHookBlock(VulkanDevice device, ShadingHooks hooks, FrameSlots slots) {
        this.hooks = hooks;
        long bytes = hooks.blockBytes();
        buffer = FrameBuffer.create(device, bytes,
            VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT, slots);
        staging = Arena.ofAuto().allocate(bytes, 16);
        MemorySegment defaults = hooks.defaultBlock();
        staging.copyFrom(defaults);
        buffer.put(0, defaults, 0, bytes);
    }

    /** The listing it is laid out by. */
    ShadingHooks hooks() {
        return hooks;
    }

    /** Sets the camera's world position along {@code axis}, as two floats. Allocates nothing. */
    void camera(int axis, float high, float low) {
        buffer.putFloat(ShadingHookBlockLayout.CAMERA + 4L * axis, high);
        buffer.putFloat(ShadingHookBlockLayout.CAMERA_LOW + 4L * axis, low);
    }

    /**
     * The block past its header in plain memory, to compose the frame's bits and records in ({@link #publish}): as
     * last published.
     */
    MemorySegment staging() {
        return staging;
    }

    /** Hands the staged bits and records to the frame's copy, in one write. Allocates nothing. */
    void publish() {
        long from = HooksConstants.SHADING_HOOK_HEADER;
        buffer.put(from, staging, from, hooks.blockBytes() - from);
    }

    /** Hands the whole staged block, its header too, to the frame's copy. Allocates nothing. */
    void publishAll() {
        buffer.put(0, staging, 0, hooks.blockBytes());
    }

    /** The address of the copy of the frame recording (of the next frame between frames). */
    long address() {
        return buffer.deviceAddress();
    }

    /** The CPU's latest of the bytes at {@code offset}, e.g. for tests. */
    int getInt(long offset) {
        return buffer.getInt(offset);
    }

    @Override
    public void close() {
        buffer.close();
    }
}
