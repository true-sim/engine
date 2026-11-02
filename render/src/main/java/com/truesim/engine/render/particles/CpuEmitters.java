package com.truesim.engine.render.particles;

import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
import static org.lwjgl.vulkan.VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.render.layout.ParticleLayout;
import com.truesim.engine.vulkan.FrameBuffer;
import com.truesim.engine.vulkan.FrameSlots;
import com.truesim.engine.vulkan.Retiring;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.scene.effects.CpuSimulation;
import com.truesim.scene.effects.ParticleCursor;

/**
 * CPU emitters on the GPU side (E1, effects.md §2.9): each frame the particles of every CPU emitter, as
 * {@link CpuSimulation} left them or as the game wrote them ({@link ParticleWriter}), written as particle records into
 * plain memory and copied into a per-frame mapped buffer once, which the simulation's "particle upload" copies into the
 * emitters' ranges of the pool. From then on drawing, culling and LOD do not know where a particle was simulated. 80
 * bytes per particle per frame: for small counts. Allocates nothing once its buffer has grown to the particles uploaded.
 */
final class CpuEmitters implements AutoCloseable {

    private static final int USAGE = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;

    private final VulkanDevice device;
    private final FrameSlots slots;
    private final Retiring retired;
    private final ParticleCursor cursor = new ParticleCursor();
    private FrameBuffer records;
    private final FrameBuffer list;
    private final Staging recordsStaged = new Staging(256L * ParticleLayout.BYTES);
    private final Staging listStaged;
    private int uploaded;
    private int emitters;
    private int largest;
    // The box of the last emitter written, from its origin.
    float lowX;
    float lowY;
    float lowZ;
    float highX;
    float highY;
    float highZ;

    @AllowAllocation("made once")
    CpuEmitters(VulkanDevice device, FrameSlots slots, int maxEmitters) {
        this.device = device;
        this.slots = slots;
        this.retired = new Retiring(slots);
        list = FrameBuffer.create(device, 4L * maxEmitters, USAGE, slots);
        listStaged = new Staging(4L * maxEmitters);
        try {
            records = FrameBuffer.create(device, 256L * ParticleLayout.BYTES, USAGE, slots);
        } catch (RuntimeException e) {
            list.close();
            throw e;
        }
    }

    /** Starts the frame's uploads. */
    void begin() {
        retired.release();
        uploaded = 0;
        emitters = 0;
        largest = 0;
    }

    /** Hands the frame's particles and list to the GPU: a copy each. */
    void finish() {
        long bytes = (long) ParticleLayout.BYTES * uploaded;
        if (records.size() < bytes) {
            grow(bytes);
        }
        recordsStaged.copyTo(records, bytes);
        listStaged.copyTo(list, 4L * emitters);
    }

    /** CPU emitters uploaded this frame. */
    int emitters() {
        return emitters;
    }

    /** The most particles one of them uploaded. */
    int largest() {
        return largest;
    }

    /** Particles uploaded this frame. */
    int uploaded() {
        return uploaded;
    }

    long recordsAddress() {
        return records.deviceAddress();
    }

    long listAddress() {
        return list.deviceAddress();
    }

    /**
     * Writes CPU emitter {@code emitter} of {@code effect} (the list's {@code place}): the game's records if
     * {@code writer} is not null, else {@code simulation}'s particles (none without one), at most {@code capacity}, as
     * offsets from ({@code ox}, {@code oy}, {@code oz}). Returns its first record; {@link #uploaded()} grows by its
     * count, the box is in the fields.
     */
    int write(int place, long effect, int emitter, int capacity, ParticleWriter writer, CpuSimulation simulation,
        double ox, double oy, double oz) {
        int first = uploaded;
        lowX = Float.POSITIVE_INFINITY;
        lowY = Float.POSITIVE_INFINITY;
        lowZ = Float.POSITIVE_INFINITY;
        highX = Float.NEGATIVE_INFINITY;
        highY = Float.NEGATIVE_INFINITY;
        highZ = Float.NEGATIVE_INFINITY;
        if (writer != null) {
            int n = Math.min(capacity, writer.count);
            room(first + n);
            for (int i = 0; i < n; i++) {
                put(first + i, writer.positions[3 * i] - ox, writer.positions[3 * i + 1] - oy,
                    writer.positions[3 * i + 2] - oz, writer.velocities[3 * i], writer.velocities[3 * i + 1],
                    writer.velocities[3 * i + 2], writer.ages[i], writer.lifetimes[i], writer.colors[4 * i],
                    writer.colors[4 * i + 1], writer.colors[4 * i + 2], writer.colors[4 * i + 3], writer.sizes[i],
                    writer.rotations[i], 0, i);
            }
            uploaded += n;
        } else if (simulation != null) {
            simulation.particles(effect, emitter, cursor);
            int n = Math.min(capacity, cursor.count());
            room(first + n);
            for (int i = 0; i < n && cursor.next(); i++) {
                put(first + i, cursor.x() - ox, cursor.y() - oy, cursor.z() - oz, cursor.vx(), cursor.vy(),
                    cursor.vz(), cursor.age(), cursor.lifetime(), cursor.color(0), cursor.color(1), cursor.color(2),
                    cursor.color(3), cursor.size(), 0, cursor.seed(), (int) cursor.spawnIndex());
            }
            uploaded += n;
        }
        listStaged.putInt(4L * emitters++, place);
        largest = Math.max(largest, uploaded - first);
        return first;
    }

    private void room(int particles) {
        recordsStaged.room((long) ParticleLayout.BYTES * particles);
    }

    @AllowAllocation("the upload growing")
    private void grow(long bytes) {
        retired.add(records);
        records = FrameBuffer.create(device, Math.max(bytes, 2 * records.size()), USAGE, slots);
    }

    private void put(int i, double x, double y, double z, float vx, float vy, float vz, float age, float lifetime,
        float r, float g, float b, float a, float size, float rotation, int seed, int index) {
        long at = (long) ParticleLayout.BYTES * i;
        Staging f = recordsStaged;
        float px = (float) x;
        float py = (float) y;
        float pz = (float) z;
        f.putVector(at + ParticleLayout.POSITION, px, py, pz);
        f.putFloat(at + ParticleLayout.AGE, age);
        f.putVector(at + ParticleLayout.VELOCITY, vx, vy, vz);
        f.putFloat(at + ParticleLayout.LIFETIME, lifetime);
        f.putFloat(at + ParticleLayout.COLOR, r);
        f.putFloat(at + ParticleLayout.COLOR + 4, g);
        f.putFloat(at + ParticleLayout.COLOR + 8, b);
        f.putFloat(at + ParticleLayout.COLOR + 12, a);
        f.putFloat(at + ParticleLayout.SIZE, size);
        f.putFloat(at + ParticleLayout.SIZE + 4, size);
        f.putFloat(at + ParticleLayout.ROTATION, rotation);
        f.putInt(at + ParticleLayout.SEED, seed);
        f.putInt(at + ParticleLayout.FLAGS, 0);
        f.putInt(at + ParticleLayout.SPAWN_INDEX, index);
        f.putFloat(at + ParticleLayout.CUSTOM, 0);
        f.putFloat(at + ParticleLayout.CUSTOM + 4, 0);
        float half = 0.5f * size;
        lowX = Math.min(lowX, px - half);
        lowY = Math.min(lowY, py - half);
        lowZ = Math.min(lowZ, pz - half);
        highX = Math.max(highX, px + half);
        highY = Math.max(highY, py + half);
        highZ = Math.max(highZ, pz + half);
    }

    @Override
    public void close() {
        records.close();
        list.close();
        retired.close();
    }
}
