package com.truesim.engine.render;

import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
import static org.lwjgl.vulkan.VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.render.layout.ShadingHookBlockLayout;
import com.truesim.engine.vulkan.FrameBuffer;
import com.truesim.engine.vulkan.FrameSlots;
import com.truesim.engine.vulkan.Retiring;
import com.truesim.engine.vulkan.VulkanDevice;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

/**
 * A render view's shading hooks' block (hooks.slang's ShadingHookBlock): the camera's world position, a bit per hook
 * the view runs this frame, the records' offsets, then every hook's parameters ({@link ShadingHooks#recordOffset}), its
 * defaults where the view runs it not, and the override section ({@link ShadingHookOverrides}). Host-visible, a copy per
 * frame slot ({@link FrameBuffer}); the view writes the camera as it moves, its graph's {@link ViewShadingHooks} the
 * rest each frame, composed in plain memory and handed over in one {@link FrameBuffer#put} (pitfalls.md: a frame
 * buffer written field by field). It grows with the override section ({@link #ensure}): a bigger buffer, the old one
 * freed once no frame in flight reads it; shaders take its address each frame.
 */
final class ShadingHookBlock implements AutoCloseable {

    private final VulkanDevice device;
    private final ShadingHooks hooks;
    private final FrameSlots slots;
    private final Retiring outgrown;
    private FrameBuffer buffer;
    private MemorySegment staging; // the header's per-frame fields, bits, offsets, records and overrides as composed

    @AllowAllocation("made with its view")
    ShadingHookBlock(VulkanDevice device, ShadingHooks hooks, FrameSlots slots) {
        this.device = device;
        this.hooks = hooks;
        this.slots = slots;
        outgrown = new Retiring(slots);
        long bytes = hooks.blockBytes();
        buffer = create(bytes);
        staging = Arena.ofAuto().allocate(bytes, 16);
        MemorySegment defaults = hooks.defaultBlock();
        staging.copyFrom(defaults);
        buffer.put(0, defaults, 0, bytes);
        // A valid word holding 0 for the instances' entries until the first frame points it at its own copy's
        buffer.putLong(ShadingHookBlockLayout.INSTANCE_ENTRIES,
            buffer.deviceAddress() + ShadingHookBlockLayout.INSTANCES);
    }

    private FrameBuffer create(long bytes) {
        return FrameBuffer.create(device, bytes,
            VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT, slots);
    }

    /** The listing it is laid out by. */
    ShadingHooks hooks() {
        return hooks;
    }

    /** Sets the camera's world position along {@code axis}, as two floats. Allocates nothing. */
    synchronized void camera(int axis, float high, float low) {
        buffer.putFloat(ShadingHookBlockLayout.CAMERA + 4L * axis, high);
        buffer.putFloat(ShadingHookBlockLayout.CAMERA_LOW + 4L * axis, low);
    }

    /**
     * Room for {@code bytes}: where it has less, a buffer of at least {@code growth} times its size (a growth room,
     * {@link ShadingHookInputs#GROWTH}), the camera and the staged block carried over. Allocates only when it grows.
     */
    synchronized void ensure(long bytes, float growth) {
        if (bytes <= buffer.size()) {
            return;
        }
        grow(Math.max(bytes, (long) Math.ceil(buffer.size() * (double) growth)));
    }

    @AllowAllocation("growing with the override section, rarely")
    private void grow(long bytes) {
        long size = (bytes + 15) / 16 * 16;
        FrameBuffer old = buffer;
        FrameBuffer grown = create(size);
        grown.copyFrom(old, 0, 0, old.size());
        MemorySegment more = Arena.ofAuto().allocate(size, 16);
        more.copyFrom(staging);
        buffer = grown;
        staging = more;
        outgrown.add(old);
    }

    /** Its bytes now. */
    synchronized long size() {
        return buffer.size();
    }

    /**
     * The block in plain memory, to compose the frame's header fields, bits, records and overrides in
     * ({@link #publish}): as last published.
     */
    synchronized MemorySegment staging() {
        return staging;
    }

    /**
     * Hands the staged block from its per-frame header fields on (the override section's, the instances') to the
     * frame's copy, in one write; the camera stays as the view wrote it. Allocates nothing.
     */
    synchronized void publish() {
        long from = ShadingHookBlockLayout.OVERRIDE_ENTRIES;
        buffer.put(from, staging, from, buffer.size() - from);
        outgrown.release();
    }

    /** Hands the whole staged block, its header too, to the frame's copy. Allocates nothing. */
    synchronized void publishAll() {
        buffer.put(0, staging, 0, buffer.size());
        outgrown.release();
    }

    /** The address of the copy of the frame recording (of the next frame between frames). */
    synchronized long address() {
        return buffer.deviceAddress();
    }

    /** The CPU's latest of the bytes at {@code offset}, e.g. for tests. */
    synchronized int getInt(long offset) {
        return buffer.getInt(offset);
    }

    @Override
    public synchronized void close() {
        outgrown.close();
        buffer.close();
    }
}
