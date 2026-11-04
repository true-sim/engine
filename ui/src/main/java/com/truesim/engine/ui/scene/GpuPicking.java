package com.truesim.engine.ui.scene;

import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
import static org.lwjgl.vulkan.VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.graph.Id;
import com.truesim.engine.graph.Access;
import com.truesim.engine.graph.Anchor;
import com.truesim.engine.graph.Buffer;
import com.truesim.engine.graph.BufferAccess;
import com.truesim.engine.graph.FrameGraph;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Image;
import com.truesim.engine.graph.Key;
import com.truesim.engine.graph.Order;
import com.truesim.engine.graph.Technique;
import com.truesim.engine.render.Keys;
import com.truesim.engine.ui.layout.PickPush;
import com.truesim.engine.vulkan.FrameBuffer;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Arrays;
import java.util.List;

/**
 * GPU picking from the visibility buffer (design UI5), the opt-in alternative to the CPU path for what the CPU cannot
 * pick exactly: skinned, morphed, displaced and alpha-tested surfaces, whose drawn shape only the GPU knows. Each frame
 * the pixels under up to {@link #capacity} pointers ({@link #pixel}) are read from the view's
 * {@link Keys#VISIBILITY_IDS} by one tiny compute dispatch and read back; {@link #instance} answers with the newest
 * finished frame's: <b>a frame or more of delay</b> (the frames in flight), never a wait. The answer is a
 * {@code GpuScene} instance id; the game maps it to its node (its sync keeps that map).
 *
 * <p>Needs {@code VisibilityBufferShading} on the view (its IDs cost nothing extra there); a forward-shaded view would
 * need an ID pass of its own, about the cost of a depth pre-pass, which is why the CPU path stays the default (see the
 * engine README's UI section for the measured costs).
 */
public final class GpuPicking implements Technique {

    /** The id of a view's GPU picking. */
    public static final Id<GpuPicking> ID = Id.of("truesim.ui.gpuPicking", GpuPicking.class);
    /** What {@link #instance} answers for a pixel showing nothing, or before the first answer. */
    public static final int NOTHING = -1;

    private static final int RING = 8;
    private final FrameBuffer pixels;
    private final int capacity;
    private final MemorySegment staging;
    private int count;
    private final int[] asked; // x, y per pointer, as set for the next frame
    // per frame recorded (by frame % RING): its pointers' pixels and count
    private final int[][] recordedPixels;
    private final int[] recordedCount = new int[RING];
    private final long[] recordedFrame = new long[RING];
    // the newest answer
    private final int[] picked;
    private final int[] pickedPixels;
    private int pickedCount;
    private long answered = -1;
    private final com.truesim.engine.graph.Readback receiver = this::receive;

    /** Picking for up to {@code pointers} pointers of a view of {@code graph}. */
    @AllowAllocation("one per view")
    public GpuPicking(FrameGraph graph, int pointers) {
        if (pointers < 1) {
            throw new IllegalArgumentException("pointers " + pointers);
        }
        capacity = pointers;
        pixels = FrameBuffer.create(graph.device(), pointers * 8L,
            VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT, graph.frameSlots());
        asked = new int[pointers * 2];
        staging = MemorySegment.ofArray(asked);
        recordedPixels = new int[RING][pointers * 2];
        picked = new int[pointers];
        pickedPixels = new int[pointers * 2];
        Arrays.fill(picked, NOTHING);
    }

    /** How many pointers it reads. */
    public int capacity() {
        return capacity;
    }

    /** Reads the pixel {@code x, y} (the view's image pixels) for pointer {@code i} from the next frame on. */
    public void pixel(int i, int x, int y) {
        asked[i * 2] = Math.max(0, x);
        asked[i * 2 + 1] = Math.max(0, y);
        count = Math.max(count, i + 1);
    }

    /** Reads {@code n} pointers from the next frame on (those past it are dropped). */
    public void count(int n) {
        count = Math.min(Math.max(0, n), capacity);
    }

    /** The instance under pointer {@code i} in the newest finished frame, or {@link #NOTHING}. */
    public synchronized int instance(int i) {
        return i < pickedCount ? picked[i] : NOTHING;
    }

    /** Pixel x pointer {@code i}'s answer was read at. */
    public synchronized int answeredX(int i) {
        return pickedPixels[i * 2];
    }

    /** Pixel y pointer {@code i}'s answer was read at. */
    public synchronized int answeredY(int i) {
        return pickedPixels[i * 2 + 1];
    }

    /** The frame the newest answer is of, or -1. */
    public synchronized long frame() {
        return answered;
    }

    @Override
    public Id<? extends Technique> id() {
        return ID;
    }

    @Override
    public List<Key<?>> provides() {
        return List.of();
    }

    @Override
    public List<Key<?>> modifies() {
        return List.of(Keys.SCENE_COLOR); // only to be built with the view: left as it is
    }

    @Override
    public Order order() {
        return Order.at(Anchor.OPAQUE);
    }

    @Override
    @AllowAllocation("building the graph, when it changes")
    public void build(GraphBuilder g) {
        g.modify(Keys.SCENE_COLOR);
        Image ids = g.require(Keys.VISIBILITY_IDS);
        com.truesim.engine.vulkan.Pipeline pipeline = g.computeProgram("shaders/ui/pick.spv", "pickMain",
            PickPush.BYTES);
        Buffer out = g.createBuffer("picked instances", capacity * 4L);
        PickPush push = new PickPush();
        g.pass("gpu picking").read(ids, Access.STORAGE_READ).write(out, BufferAccess.STORAGE_WRITE)
            .record(ctx -> {
                int n = recordPointers(ctx.frame());
                if (n > 0) {
                    ctx.dispatch(pipeline, push.ids(ctx.storage(ids)).count(n).width(ctx.regionWidth(ids))
                        .height(ctx.regionHeight(ids)).pixels(pixels.deviceAddress()).picked(ctx.address(out)),
                        (n + 63) / 64);
                }
            });
        g.readback("gpu picking result", out, 0, capacity * 4L, receiver);
    }

    /** Keeps the pointers frame {@code frame} reads (for its answer) and uploads them: how many. */
    private synchronized int recordPointers(long frame) {
        int n = count;
        int r = (int) (frame % RING);
        System.arraycopy(asked, 0, recordedPixels[r], 0, n * 2);
        recordedCount[r] = n;
        recordedFrame[r] = frame;
        if (n > 0) {
            pixels.put(0, staging, 0, n * 8L);
        }
        return n;
    }

    private synchronized void receive(MemorySegment data, long frame) {
        int r = (int) (frame % RING);
        if (recordedFrame[r] != frame) {
            return;
        }
        int n = recordedCount[r];
        for (int i = 0; i < n; i++) {
            picked[i] = data.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, i);
            pickedPixels[i * 2] = recordedPixels[r][i * 2];
            pickedPixels[i * 2 + 1] = recordedPixels[r][i * 2 + 1];
        }
        pickedCount = n;
        answered = frame;
    }

    /** Frees its buffer: call once the GPU is done with the frames. */
    public void close() {
        pixels.close();
    }
}
