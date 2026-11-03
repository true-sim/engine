package com.truesim.engine.render;

import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
import static org.lwjgl.vulkan.VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.graph.Buffer;
import com.truesim.engine.graph.BufferAccess;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Key;
import com.truesim.engine.graph.PassContext;
import com.truesim.engine.graph.Readback;
import com.truesim.engine.graph.Technique;
import com.truesim.engine.render.layout.SegmentsPush;
import com.truesim.engine.render.shadows.TopLevel;
import com.truesim.engine.vulkan.FrameBuffer;
import com.truesim.engine.vulkan.Pipeline;
import com.truesim.engine.vulkan.VulkanDevice;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.List;
import java.util.Objects;

/**
 * Segments traced through a scene's top level, in batches, for whatever asks what lies between two points without
 * needing the answer at once (audio occlusion, §2.11 of scene's audio.md): a technique whose compute pass traces the
 * batch {@link #submit submitted} last with ray queries and reads the crossings back, a frame or more later, for
 * {@link #collect}. A graph builds only what its outputs need, so it hangs on a result the frame always makes: it
 * {@link #modifies() modifies} {@link Keys#SCENE_COLOR} (or the key given), leaving it as it is; add it to one view
 * that runs every frame (the main one), as any other technique of that view.
 *
 * <p>Per segment the nearest {@link #MAX_CROSSINGS} triangles crossed, in order of distance: each one's instance id,
 * whether the ray met its front face, and its material id (of the scene's table, its submesh's); with
 * {@link #frontsOnly(boolean)}, front faces only. Crossings within a millimetre of each other count as one (a ray
 * through an edge meets both triangles). Instances left out of the top level (casting no shadows, mesh particles) are
 * not crossed; masked materials are crossed whole, their alpha not tested.
 *
 * <p>Thread-safe: one thread (a game's audio sync) may submit and collect while the render thread records and receives.
 * Allocates nothing per batch. On devices with {@link VulkanDevice#rayTracing() ray tracing} only, as
 * {@link RayTracing} is.
 */
public final class SegmentTracing implements Technique, AutoCloseable {

    /** The crossings kept per segment, the nearest. */
    public static final int MAX_CROSSINGS = 8;
    /** Words per segment in {@link #collect}'s results: the crossings kept, then two words per crossing. */
    public static final int WORDS = 1 + 2 * MAX_CROSSINGS;
    private static final int SEGMENT_BYTES = 6 * 4;
    private static final int FRONT = 0x8000_0000;
    // Batches in flight: recorded and not yet read back, and read back and not yet collected.
    private static final int RING = 8;

    private final RayTracing rayTracing;
    private final MeshletRenderer renderer;
    private final Key<?> anchor;
    private final int capacity;
    private final FrameBuffer segments; // the batch traced, per frame slot
    private final MemorySegment staging; // the batch as floats relative to the origin, put into segments at once
    private final MemorySegment results; // RING batches read back, of capacity segments each
    private final Readback receiver = this::receive;
    private final Object lock = new Object();
    // The batch waiting to be recorded (at most one): its ends as world positions, its ticket.
    private final double[] pending;
    private int pendingCount;
    private long pendingTicket;
    private long tickets;
    // Per frame recorded (by frame % RING): the frame, the batch it traced and how many segments.
    private final long[] recordedFrame = new long[RING];
    private final long[] recordedTicket = new long[RING];
    private final int[] recordedCount = new int[RING];
    // Per batch read back (by ticket % RING): its ticket and segments.
    private final long[] resultTicket = new long[RING];
    private final int[] resultCount = new int[RING];
    private long traced;
    private volatile boolean frontsOnly;
    private boolean closed;

    /**
     * Batches of up to {@code capacity} segments traced through {@code renderer}'s scene's top level, built by
     * {@code rayTracing} relative to its {@link RayTracing#origin origin}, in the frames that make the scene's color.
     */
    public SegmentTracing(VulkanDevice device, RayTracing rayTracing, MeshletRenderer renderer, int capacity) {
        this(device, rayTracing, renderer, capacity, Keys.SCENE_COLOR);
    }

    /** As {@link #SegmentTracing(VulkanDevice, RayTracing, MeshletRenderer, int)}, in frames making {@code anchor}. */
    @AllowAllocation("set up once")
    public SegmentTracing(VulkanDevice device, RayTracing rayTracing, MeshletRenderer renderer, int capacity,
        Key<?> anchor) {
        this.rayTracing = Objects.requireNonNull(rayTracing, "rayTracing");
        this.renderer = Objects.requireNonNull(renderer, "renderer");
        this.anchor = Objects.requireNonNull(anchor, "anchor");
        if (capacity < 1) {
            throw new IllegalArgumentException("A batch holds a segment at least: " + capacity);
        }
        this.capacity = capacity;
        pending = new double[6 * capacity];
        segments = FrameBuffer.create(device, (long) SEGMENT_BYTES * capacity,
            VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT,
            renderer.scene().frameSlots());
        staging = Arena.ofAuto().allocate((long) SEGMENT_BYTES * capacity, 16);
        results = Arena.ofAuto().allocate((long) RING * WORDS * 4 * capacity, 16);
    }

    /** The most segments a batch holds. */
    public int capacity() {
        return capacity;
    }

    /**
     * Whether only front faces count as crossings, from the next frame recorded: with meshes whose faces wind
     * counter-clockwise seen from outside (glTF's), the crossings into a mesh, each closed mesh crossed once, as
     * physics counts a convex body. False at first: every triangle crossed.
     */
    public SegmentTracing frontsOnly(boolean only) {
        frontsOnly = only;
        return this;
    }

    /** Whether only front faces count as crossings ({@link #frontsOnly(boolean)}). */
    public boolean frontsOnly() {
        return frontsOnly;
    }

    /** Segments traced so far, of every batch recorded. */
    public long traced() {
        synchronized (lock) {
            return traced;
        }
    }

    /**
     * Hands over {@code count} segments for the next frame recorded to trace: 6 values each in {@code ends}, the world
     * positions of its start and end. Returns the batch's ticket for {@link #collect}, or 0 while the batch submitted
     * before has not been recorded yet (nothing taken: submit again later).
     */
    public long submit(double[] ends, int count) {
        if (count < 0 || count > capacity || ends.length < 6 * count) {
            throw new IllegalArgumentException("A batch of " + count + " segments, of " + capacity + " at most");
        }
        synchronized (lock) {
            if (pendingTicket != 0) {
                return 0;
            }
            System.arraycopy(ends, 0, pending, 0, 6 * count);
            pendingCount = count;
            pendingTicket = ++tickets;
            return pendingTicket;
        }
    }

    /**
     * Copies batch {@code ticket}'s crossings into {@code out} ({@link #WORDS} per segment, from 0) once they are back:
     * returns how many segments the batch had, or -1 while they are not (or no longer: a batch is kept until a few
     * later ones came back). Decode a crossing's words with {@link #instance}, {@link #front} and the material word as
     * it is.
     */
    public int collect(long ticket, int[] out) {
        synchronized (lock) {
            int slot = (int) (ticket % RING);
            if (ticket <= 0 || resultTicket[slot] != ticket) {
                return -1;
            }
            int count = resultCount[slot];
            long base = (long) slot * WORDS * capacity;
            for (int i = 0; i < count * WORDS; i++) {
                out[i] = results.getAtIndex(JAVA_INT, base + i);
            }
            return count;
        }
    }

    /** The instance id in a crossing's first word. */
    public static int instance(int word) {
        return word & 0xff_ffff;
    }

    /** Whether the ray met the front face of the triangle crossed, from a crossing's first word. */
    public static boolean front(int word) {
        return (word & FRONT) != 0;
    }

    @Override
    public List<Key<?>> provides() {
        return List.of();
    }

    @Override
    @AllowAllocation("a list, as the graph builds")
    public List<Key<?>> modifies() {
        return List.of(anchor);
    }

    @Override
    @AllowAllocation("building the graph, when it changes")
    public void build(GraphBuilder g) {
        g.modify(anchor); // only to be built with it: left as it is
        Pipeline pipeline = g.computeProgram("shaders/raytracing/segments.spv", "segmentsMain", SegmentsPush.BYTES);
        Buffer topLevel = g.request(new TopLevel(rayTracing, renderer));
        Buffer crossings = g.createBuffer("segment crossings", (long) WORDS * 4 * capacity);
        SegmentsPush push = new SegmentsPush();
        g.pass("segments").read(topLevel, BufferAccess.ACCELERATION_STRUCTURE_READ)
            .write(crossings, BufferAccess.STORAGE_WRITE)
            .record(ctx -> trace(ctx, pipeline, crossings, push));
        g.readback("segment crossings", crossings, 0, (long) WORDS * 4 * capacity, receiver);
    }

    /** Traces the batch waiting, if any, in the frame recording. */
    private void trace(PassContext ctx, Pipeline pipeline, Buffer crossings, SegmentsPush push) {
        int count;
        synchronized (lock) {
            int slot = (int) (ctx.frame() % RING);
            recordedFrame[slot] = ctx.frame();
            recordedTicket[slot] = pendingTicket;
            recordedCount[slot] = pendingCount;
            count = pendingTicket == 0 ? 0 : pendingCount;
            // Relative to the top level's origin, as floats: differences of world positions, small near it.
            for (int i = 0; i < count; i++) {
                for (int axis = 0; axis < 3; axis++) {
                    double origin = rayTracing.origin(axis);
                    staging.setAtIndex(JAVA_FLOAT, 6L * i + axis, (float) (pending[6 * i + axis] - origin));
                    staging.setAtIndex(JAVA_FLOAT, 6L * i + 3 + axis, (float) (pending[6 * i + 3 + axis] - origin));
                }
            }
            pendingTicket = 0;
            traced += count;
        }
        if (count == 0) {
            return;
        }
        segments.put(0, staging, 0, (long) SEGMENT_BYTES * count);
        ctx.dispatch(pipeline, push.scene(rayTracing.sceneAddress(renderer.scene())).segments(segments.deviceAddress())
            .results(ctx.address(crossings)).count(count).fronts(frontsOnly ? 1 : 0), (count + 63) / 64);
    }

    /** Keeps what frame {@code frame} read back, if it traced a batch. */
    private void receive(MemorySegment data, long frame) {
        synchronized (lock) {
            int slot = (int) (frame % RING);
            long ticket = recordedTicket[slot];
            if (recordedFrame[slot] != frame || ticket == 0) {
                return;
            }
            int count = recordedCount[slot];
            int into = (int) (ticket % RING);
            MemorySegment.copy(data, 0, results, (long) into * WORDS * 4 * capacity, (long) WORDS * 4 * count);
            resultTicket[into] = ticket;
            resultCount[into] = count;
        }
    }

    /** Frees the segments' buffer; the device must be done with it. */
    @Override
    public void close() {
        if (!closed) {
            closed = true;
            segments.close();
        }
    }
}
