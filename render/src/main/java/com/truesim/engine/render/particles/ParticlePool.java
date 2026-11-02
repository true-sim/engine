package com.truesim.engine.render.particles;

import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
import static org.lwjgl.vulkan.VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.graph.ExternalBuffer;
import com.truesim.engine.render.RangeAllocator;
import com.truesim.engine.render.layout.ParticleConstants;
import com.truesim.engine.render.layout.ParticleLayout;
import com.truesim.engine.vulkan.GpuBuffer;
import com.truesim.engine.vulkan.VulkanDevice;

/**
 * The shared particle buffers (effects.md §2.2): two copies of every slot, device-local, and two of every side unit
 * (16 bytes of attributes some emitters keep per particle, such as those from birth that attributes over life
 * multiply). Ranges are handed out per emitter instance in chunks of {@value ParticleConstants#CHUNK} slots, first fit
 * and deterministic ({@link RangeAllocator}). Slot {@code s} of half {@code h} is particle {@code h * slots() + s}.
 * The pool does not grow: its budget is set for the content, and an emitter finding no room is reported, not drawn.
 */
public final class ParticlePool implements AutoCloseable {

    /** Bytes a slot costs: two particles and two side units of 16 bytes. */
    public static final int SLOT_BYTES = 2 * ParticleLayout.BYTES + 2 * 2 * 16;

    private static final int USAGE = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT
        | VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT;

    private final int slots;
    private final int sideUnits;
    private final RangeAllocator chunks;
    private final RangeAllocator sides;
    private final GpuBuffer particles;
    private final GpuBuffer side;
    private final ExternalBuffer particlesImport;
    private final ExternalBuffer sideImport;

    /** A pool of {@code budget} bytes: {@code budget / SLOT_BYTES} slots, at least one chunk. */
    @AllowAllocation("made once")
    public ParticlePool(VulkanDevice device, long budget) {
        long chunkCount = Math.max(1, budget / SLOT_BYTES / ParticleConstants.CHUNK);
        if (chunkCount * ParticleConstants.CHUNK > Integer.MAX_VALUE / 2) {
            throw new IllegalArgumentException("A particle budget of at most 2^30 slots: " + budget + " bytes");
        }
        slots = (int) chunkCount * ParticleConstants.CHUNK;
        sideUnits = 2 * slots;
        chunks = new RangeAllocator(chunkCount);
        sides = new RangeAllocator(sideUnits);
        particles = GpuBuffer.deviceLocal(device, 2L * slots * ParticleLayout.BYTES, USAGE, Particles.queues(device));
        try {
            side = GpuBuffer.deviceLocal(device, 2L * sideUnits * 16, USAGE, Particles.queues(device));
        } catch (RuntimeException e) {
            particles.close();
            throw e;
        }
        particlesImport = new ExternalBuffer("particles", particles);
        sideImport = new ExternalBuffer("particle side arrays", side);
    }

    /** Slots in each half. */
    public int slots() {
        return slots;
    }

    /** Side units of 16 bytes in each half. */
    public int sideUnits() {
        return sideUnits;
    }

    /** Slots not in any range. */
    public long freeSlots() {
        return chunks.free() * ParticleConstants.CHUNK;
    }

    /** The particle buffer: both halves. */
    public GpuBuffer buffer() {
        return particles;
    }

    /** The side buffer: both halves. */
    public GpuBuffer sideBuffer() {
        return side;
    }

    ExternalBuffer graphBuffer() {
        return particlesImport;
    }

    ExternalBuffer sideGraphBuffer() {
        return sideImport;
    }

    /** The first slot of a range of {@code capacity} slots (whole chunks), or -1 without room. */
    int allocate(int capacity) {
        long first = chunks.allocate(chunksOf(capacity), 1);
        return first < 0 ? -1 : (int) first * ParticleConstants.CHUNK;
    }

    void release(int first, int capacity) {
        chunks.release(first / ParticleConstants.CHUNK, chunksOf(capacity));
    }

    /** The first unit of a side range of {@code units}, or -1 without room. */
    int allocateSide(int units) {
        long first = sides.allocate(units, 1);
        return (int) first;
    }

    void releaseSide(int first, int units) {
        sides.release(first, units);
    }

    static int chunksOf(int capacity) {
        return (capacity + ParticleConstants.CHUNK - 1) / ParticleConstants.CHUNK;
    }

    @Override
    public void close() {
        particles.close();
        side.close();
    }
}
