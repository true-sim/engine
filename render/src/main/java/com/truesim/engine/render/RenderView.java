package com.truesim.engine.render;

import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_INDEX_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.graph.Buffer;
import com.truesim.engine.graph.BufferAccess;
import com.truesim.engine.graph.ExternalBuffer;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.render.layout.CommonConstants;
import com.truesim.engine.render.layout.ViewLayout;
import com.truesim.engine.vulkan.Commands;
import com.truesim.engine.vulkan.FrameBuffer;
import com.truesim.engine.vulkan.FrameSlots;
import com.truesim.engine.vulkan.GpuBuffer;
import com.truesim.engine.vulkan.Retiring;
import com.truesim.engine.vulkan.VulkanDevice;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.util.Objects;

/**
 * One view of a {@link MeshletRenderer}'s scene: a camera, a shadow map's light, a mirror. Each has its camera
 * ({@link #set}), the instances it draws ({@link #filter}), its lists of what culling found, sized by its
 * {@link ViewOptions}, and with occlusion culling a history of its own, so any number of views may cull occluded
 * instances. Views are culled together, however many: one instance pass and one cull pass for all. Made by
 * {@link MeshletRenderer#createView}, rarely; setting one allocates nothing.
 */
@AllowAllocation("created rarely")
public final class RenderView implements AutoCloseable {

    /**
     * The shaders' View ({@link ViewLayout}): matrices and planes, the error and mode, the camera's world position (two
     * floats per axis), its filter, the lists' and the occlusion history's addresses, then the frame before's matrix and
     * jitter, this frame's jitter and output size. Views lie one after another in the renderer's view constants array.
     */
    static final int BYTES = ViewLayout.BYTES;
    private static final ValueLayout.OfInt U32 = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    private final MeshletRenderer renderer;
    private ViewOptions options;
    private final int index;
    private final long base; // where its View starts in the renderer's view constants array
    private GpuBuffer visible;
    private final GpuBuffer expandCommand;
    private final GpuBuffer drawCommand;
    private final GpuBuffer drawCount; // the early (or only) phase's, then the late one's
    private GpuBuffer visibility; // per instance, 1 if visible the last time the late phase looked; or null
    private boolean visibilityGrown; // replaced by a bigger one, cleared at the next cull
    private final VulkanDevice device;
    private GpuBuffer frameIndices; // the indexed path's, made when it first draws
    // Its lists split by shading bin (split.slang), for forward shading by type: made when a graph first splits it,
    // with room for splitRoom shading bins; split while splitCategories > 0 (set by the graph's build).
    private GpuBuffer split;
    private ExternalBuffer splitImport;
    private int splitRoom;
    private volatile int splitCategories;
    private volatile int splitBins;
    private volatile int splitGeometryBins;
    // Its blended bin sorted far to near after the split (meshlets/sort.slang), for SortedTransparency: in runs of
    // sortSlots slots of sortCategories shading bins, while sortSlots > 0 (set by the graph's build); the keys, counts
    // and runs in one buffer, made when a graph first sorts the view.
    private GpuBuffer sort;
    private ExternalBuffer sortImport;
    private volatile int sortSlots;
    private volatile int sortCategories;
    private volatile int[] sortDrawn = new int[0];
    // The lists as imported buffers of the frame graph, which the culling and drawing passes declare.
    private final ExternalBuffer visibleImport;
    private final ExternalBuffer expandImport;
    private final ExternalBuffer drawImport;
    private final ExternalBuffer countImport;
    private final ExternalBuffer visibilityImport; // or null, as the history
    // The indexed path's index buffer as an import, once a graph declared it, always naming a live buffer: the index
    // buffer, or after it was dropped a tiny placeholder. And whether the last build declared it (the indexed path).
    private ExternalBuffer indicesImport;
    private GpuBuffer noIndices;
    private boolean indicesDeclared;
    private final double[] position = new double[3];
    private final float[] viewProjection = new float[16]; // as last set, to be the next frame's previous one
    private boolean wasSet;
    // The frame before's camera, taken at a frame's first write: writes again in the frame (a pass refreshing it after
    // another, a draw setting it twice) keep it, rather than taking the frame's own as the frame before's.
    private final FrameSlots slots;
    private final float[] before = new float[16];
    private final double[] beforePosition = new double[3];
    private float beforeJitterX;
    private float beforeJitterY;
    private boolean beforeSet;
    private long writtenFrame = -1;
    // Whether the frame before's view-projection or jitter written last differs from the current one: the next frame
    // writes again, though the camera stays, so the frame before's is this one's (motion vectors: none when still).
    private boolean previousStale;
    private Camera camera; // as last set: read again when the frame culls, if it moved since
    private float errorPixels;
    private int mode;
    private float detail = 1; // rendered pixels per pixel the camera was set for (a render scale)
    private float projectionScale; // the camera's, as last written
    private float jitterX;
    private float jitterY;
    final RenderView[] alone = {this}; // to cull the view by itself; never written
    boolean marked; // while a cull checks its views
    private boolean closed;

    RenderView(VulkanDevice device, MeshletRenderer renderer, ViewOptions options, int index, FrameSlots slots) {
        this.device = device;
        this.renderer = renderer;
        this.options = Objects.requireNonNull(options, "options");
        this.index = index;
        this.slots = slots;
        int storage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;
        int commandUsage = storage | VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT;
        this.base = (long) index * BYTES;
        visible = GpuBuffer.deviceLocal(device, 16L * options.allDraws(), storage); // the bins, one after another
        expandCommand = GpuBuffer.deviceLocal(device, 16L * MeshletRenderer.BINS, commandUsage);
        drawCommand = GpuBuffer.deviceLocal(device, 24L * MeshletRenderer.BINS, commandUsage);
        // Per bin the early and late counts, then per bin the most indices a phase wanted.
        drawCount = GpuBuffer.hostVisible(device, 12L * MeshletRenderer.BINS, commandUsage); // common.slang's
        // Read before the first cull (overflowed(), wanted...()): new memory holds anything, a freed buffer's on
        // lavapipe; nothing wanted yet.
        drawCount.memory().fill((byte) 0);
        int instances = renderer.scene().capacity();
        visibility = options.occlusion()
            ? GpuBuffer.deviceLocal(device, 4L * instances, storage | VK_BUFFER_USAGE_TRANSFER_DST_BIT) : null;
        visibilityGrown = visibility != null; // cleared by the first cull, no wait: nothing visible yet
        visibleImport = new ExternalBuffer("view " + index + " visible", visible);
        expandImport = new ExternalBuffer("view " + index + " expand command", expandCommand);
        drawImport = new ExternalBuffer("view " + index + " draw command", drawCommand);
        countImport = new ExternalBuffer("view " + index + " draw count", drawCount);
        visibilityImport = visibility == null ? null : new ExternalBuffer("view " + index + " visibility", visibility);
        FrameBuffer v = renderer.viewConstants();
        v.put(base, MemorySegment.ofArray(new byte[BYTES]), 0, BYTES); // an earlier view's entry, gone
        putInt(ViewLayout.MAX_DRAWS, options.draws());
        putInt(ViewLayout.BIN_DRAWS, options.binDraws());
        filter(options.layers(), options.castersOnly());
        putLong(ViewLayout.LISTS_VISIBLE, visible.deviceAddress());
        putLong(ViewLayout.LISTS_EXPAND_COMMAND, expandCommand.deviceAddress());
        putLong(ViewLayout.LISTS_DRAW_COMMAND, drawCommand.deviceAddress());
        putLong(ViewLayout.LISTS_VISIBLE_COUNT, drawCount.deviceAddress());
        putLong(ViewLayout.VISIBILITY, visibility == null ? 0 : visibility.deviceAddress());
    }

    /**
     * Sets the view's camera: its matrices and world position (instances are drawn relative to it), the error allowed
     * on screen in pixels (detail is chosen per meshlet so that simplification moves no surface by more) and the
     * shading {@code mode} ({@link MeshletRenderer#MODE_LIT}, ...). The camera is read now, and again when a frame
     * culls the view if it changed since: setting it once is enough for a camera that moves (a headset's eyes, located
     * just before the frame records). Allocates nothing.
     */
    public synchronized RenderView set(Camera camera, float errorPixels, int mode) {
        checkOpen();
        this.camera = camera;
        this.errorPixels = errorPixels;
        this.mode = mode;
        return write(camera);
    }

    /**
     * Looks through {@code camera} with {@code errorPixels} of error allowed, keeping the view's mode: how a draw through
     * a camera of its own (a light's) binds it each frame. Allocates nothing.
     */
    public synchronized RenderView camera(Camera camera, float errorPixels) {
        return set(camera, errorPixels, mode);
    }

    /**
     * Reads the camera last {@link #set} again where it changed since it was read (moved, turned, resized): when a frame
     * culls the view, so the frame draws from where the camera is then. Allocates nothing.
     */
    synchronized void refresh() {
        refresh(false);
    }

    /**
     * {@link #refresh()}, where {@code first} is the frame's first cull of the view (its early phase, or its only one):
     * there a camera that stopped since the frame before also writes its matrices again, so the frame before's are
     * this frame's (with one frame slot no frames are counted: only the first cull tells a new frame).
     */
    synchronized void refresh(boolean first) {
        if (camera == null || !changed(camera) && !(previousStale && (slots.isSingle() ? first
            : frameOfWrite() != writtenFrame))) {
            return; // refreshed already this frame, maybe by another pass on another thread
        }
        write(camera);
    }

    /**
     * Renders at {@code scale} times the pixels the camera was set for (a view's render scale, dynamic resolution's
     * changing every frame): detail is chosen for the error allowed in the pixels actually drawn, so a view rendered at
     * half the size draws coarser levels. 1 by default; the scene's draws of a view set it each frame. Allocates nothing.
     */
    public RenderView detail(float scale) {
        checkOpen();
        if (!(scale > 0)) {
            throw new IllegalArgumentException("A render scale is positive: " + scale);
        }
        if (scale != detail) {
            detail = scale;
            putFloat(ViewLayout.PROJECTION_SCALE, projectionScale * detail);
        }
        return this;
    }

    /** The render scale detail is chosen for ({@link #detail(float)}). */
    public float detail() {
        return detail;
    }

    /** Whether {@code from}'s matrices or position differ from those the view holds. */
    private boolean changed(Camera from) {
        float[] matrix = from.viewProjection();
        for (int i = 0; i < 16; i++) {
            if (matrix[i] != viewProjection[i]) {
                return true;
            }
        }
        return from.position(0) != position[0] || from.position(1) != position[1] || from.position(2) != position[2];
    }

    /**
     * The frame a write is for: the one recording, or between frames the next. With one frame slot, which counts no
     * frames, each write is a frame's.
     */
    private long frameOfWrite() {
        return slots.current() >= 0 ? slots.frames() : slots.frames() + 1;
    }

    /**
     * Writes {@code camera} as this frame's, the frame before's as held at the frame's first write. Synchronized with
     * the camera too: passes of several views may refresh on several threads, and cameras keep scratch arrays.
     */
    private synchronized RenderView write(Camera camera) {
        synchronized (camera) {
            return writeLocked(camera);
        }
    }

    private RenderView writeLocked(Camera camera) {
        long frame = frameOfWrite();
        if (slots.isSingle() || frame != writtenFrame) { // what the view holds is the frame before's
            writtenFrame = frame;
            System.arraycopy(viewProjection, 0, before, 0, 16);
            System.arraycopy(position, 0, beforePosition, 0, 3);
            beforeJitterX = getFloat(ViewLayout.JITTER);
            beforeJitterY = getFloat(ViewLayout.JITTER + 4);
            beforeSet = wasSet;
        }
        float[] matrix = camera.viewProjection();
        // The frame before's matrix, from positions relative to this frame's camera: moved by how far the camera did
        // (column-major: the last column takes the move), so motion vectors need no positions of the frame before.
        // The first time, this frame's.
        double dx = beforeSet ? camera.position(0) - beforePosition[0] : 0;
        double dy = beforeSet ? camera.position(1) - beforePosition[1] : 0;
        double dz = beforeSet ? camera.position(2) - beforePosition[2] : 0;
        float[] previous = beforeSet ? before : matrix;
        boolean stale = dx != 0 || dy != 0 || dz != 0 || beforeJitterX != jitterX || beforeJitterY != jitterY;
        for (int i = 0; i < 16 && !stale; i++) {
            stale = previous[i] != matrix[i];
        }
        previousStale = stale;
        for (int row = 0; row < 4; row++) {
            for (int column = 0; column < 3; column++) {
                putFloat(ViewLayout.PREVIOUS_VIEW_PROJECTION + 4L * (4 * column + row), previous[4 * column + row]);
            }
            putFloat(ViewLayout.PREVIOUS_VIEW_PROJECTION + 4L * (12 + row), (float) (previous[row] * dx
                + previous[4 + row] * dy + previous[8 + row] * dz + previous[12 + row]));
        }
        System.arraycopy(matrix, 0, viewProjection, 0, 16);
        for (int i = 0; i < 16; i++) {
            putFloat(ViewLayout.VIEW_PROJECTION + 4L * i, matrix[i]);
        }
        float[] planes = camera.planes();
        for (int i = 0; i < 24; i++) {
            putFloat(ViewLayout.PLANES + 4L * i, planes[i]);
        }
        projectionScale = camera.projectionScale();
        putFloat(ViewLayout.PROJECTION_SCALE, projectionScale * detail);
        putFloat(ViewLayout.ERROR_PIXELS, errorPixels);
        putInt(ViewLayout.MODE, mode);
        putInt(ViewLayout.ORTHOGRAPHIC, camera.orthographic() ? 1 : 0);
        float[] inverse = camera.inverseViewProjection();
        for (int i = 0; i < 16; i++) {
            putFloat(ViewLayout.INVERSE_VIEW_PROJECTION + 4L * i, inverse[i]);
        }
        // The camera's world position, two floats per axis as instances keep theirs, and the eye off it.
        for (int axis = 0; axis < 3; axis++) {
            double p = camera.position(axis);
            position[axis] = p;
            float high = (float) p;
            putFloat(ViewLayout.POSITION + 4L * axis, high);
            putFloat(ViewLayout.POSITION_LOW + 4L * axis, (float) (p - high));
            putFloat(ViewLayout.EYE + 4L * axis, (float) camera.eye(axis));
        }
        // The jitter of the frame before; this frame's stays until set again.
        putFloat(ViewLayout.PREVIOUS_JITTER, beforeJitterX);
        putFloat(ViewLayout.PREVIOUS_JITTER + 4, beforeJitterY);
        putFloat(ViewLayout.JITTER, jitterX);
        putFloat(ViewLayout.JITTER + 4, jitterY);
        wasSet = true;
        return this;
    }

    /**
     * The subpixel offset, in pixels, the next {@link #set} camera's projection is moved by (temporal antialiasing),
     * which shaders read beside the frame before's. Allocates nothing.
     */
    public RenderView jitter(float x, float y) {
        checkOpen();
        jitterX = x;
        jitterY = y;
        return this;
    }

    /** The pixels the view renders to, for shaders that need its size. Allocates nothing. */
    public RenderView output(int width, int height) {
        checkOpen();
        if (width < 0 || height < 0) {
            throw new IllegalArgumentException("An output of " + width + " x " + height);
        }
        putInt(ViewLayout.OUTPUT_SIZE, width);
        putInt(ViewLayout.OUTPUT_SIZE + 4, height);
        return this;
    }

    /**
     * Grows the visibility to the scene's ids, as the scene grew: a bigger buffer, cleared at the next cull (nothing
     * visible yet, as when the view was made), the old one retired.
     */
    void followInstances() {
        long bytes = 4L * renderer.scene().capacity();
        if (visibility == null || visibility.size() >= bytes) {
            return;
        }
        renderer.retire(visibility);
        visibility = GpuBuffer.deviceLocal(device, bytes,
            VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT
                | VK_BUFFER_USAGE_TRANSFER_DST_BIT);
        visibilityImport.set(visibility);
        putLong(ViewLayout.VISIBILITY, visibility.deviceAddress());
        visibilityGrown = true;
    }

    /** Clears a visibility grown since the last cull, with the cull's resets (whose barrier orders it). */
    void clearGrownVisibility(org.lwjgl.vulkan.VkCommandBuffer commands) {
        if (visibilityGrown) {
            visibilityGrown = false;
            Commands.fillBuffer(commands, visibility.handle(), 0, visibility.size(), 0);
        }
    }

    void putInt(long offset, int value) {
        renderer.viewConstants().putInt(base + offset, value);
    }

    void putLong(long offset, long value) {
        renderer.viewConstants().putLong(base + offset, value);
    }

    void putFloat(long offset, float value) {
        renderer.viewConstants().putFloat(base + offset, value);
    }

    float getFloat(long offset) {
        return renderer.viewConstants().getFloat(base + offset);
    }

    /**
     * Which instances the view draws: those on any layer of {@code layers}, and with {@code castersOnly} only those
     * casting shadows (a shadow map's view). From the options at first; kept until set again. Allocates nothing.
     */
    public RenderView filter(int layers, boolean castersOnly) {
        checkOpen();
        putInt(ViewLayout.LAYERS, layers);
        this.castersOnly = castersOnly;
        putInt(ViewLayout.FLAGS, flags());
        return this;
    }

    /**
     * Draws only the instances highlighted in the flags at device address {@code flags} (two bits per instance id,
     * sixteen ids a word, {@code count} ids: a {@link SelectionHighlights}' upload), or every instance (address 0):
     * the selection outline's own ID draw for forward views, whose cost then follows the highlighted. The address names
     * a frame slot's copy: set it in each frame it is read. Allocates nothing.
     */
    public RenderView highlightedOnly(long flags, int count) {
        return highlights(flags, count, 2);
    }

    /**
     * Draws only the instances not highlighted in the flags at device address {@code flags} (as
     * {@link #highlightedOnly}: ids past {@code count} are not highlighted), or every instance (address 0): a shadow
     * tile's static casters, the moving ones flagged. Set in each frame it is read. Allocates nothing.
     */
    public RenderView unhighlightedOnly(long flags, int count) {
        return highlights(flags, count, 8);
    }

    /**
     * Culls only the {@code count} instances whose ids lie at device address {@code ids} (32-bit each), or every instance
     * (address 0): a view drawing few known instances (a shadow tile's moving casters) then costs as they do, not as
     * the scene's every instance. The address names a frame slot's copy: set it in each frame it is read. Allocates
     * nothing.
     */
    public RenderView instances(long ids, int count) {
        checkOpen();
        putLong(ViewLayout.IDS, ids);
        putInt(ViewLayout.ID_COUNT, ids == 0 ? 0 : count);
        return this;
    }

    private RenderView highlights(long flags, int count, int mode) {
        checkOpen();
        putLong(ViewLayout.HIGHLIGHTS, flags);
        putInt(ViewLayout.HIGHLIGHT_COUNT, flags == 0 ? 0 : count);
        highlightMode = flags != 0 ? mode : 0;
        putInt(ViewLayout.FLAGS, flags());
        return this;
    }

    private int flags() {
        return (castersOnly ? 1 : 0) | highlightMode | (bothSides ? 4 : 0);
    }

    /**
     * Keeps meshlets facing away from the eye (shadow casters drawn two-sided, as rays meet every face); off by default:
     * a camera's view culls them. Allocates nothing.
     */
    public RenderView bothSides(boolean on) {
        checkOpen();
        if (on != bothSides) {
            bothSides = on;
            putInt(ViewLayout.FLAGS, flags());
        }
        return this;
    }

    private boolean bothSides;

    private boolean castersOnly;
    private int highlightMode; // 2: highlighted only, 8: unhighlighted only

    /**
     * How many meshlets the view found visible in the last finished frame, every raster bin and both phases of
     * occlusion culling together. Read it once the frame's commands have completed.
     */
    public int visibleMeshlets() {
        int sum = 0;
        for (int word = 0; word < 2 * MeshletRenderer.BINS; word++) {
            sum += drawCount.memory().get(U32, 4L * word);
        }
        return sum;
    }

    /**
     * Like {@link #visibleMeshlets()}, of raster bin {@code bin} alone (0 opaque, 1 double-sided, 2 masked, 3 blended).
     */
    public int visibleMeshlets(int bin) {
        Objects.checkIndex(bin, MeshletRenderer.BINS);
        return drawCount.memory().get(U32, 8L * bin) + drawCount.memory().get(U32, 8L * bin + 4);
    }

    /**
     * The most triangles one phase of the last finished frame wanted to draw in the opaque bin: on the indexed path,
     * more than {@link ViewOptions#triangles()} were dropped.
     */
    public long wantedTriangles() {
        return wantedTriangles(0);
    }

    /** Like {@link #wantedTriangles()}, of raster bin {@code bin}. */
    public long wantedTriangles(int bin) {
        Objects.checkIndex(bin, MeshletRenderer.BINS);
        return Integer.toUnsignedLong(drawCount.memory().get(U32, 8L * MeshletRenderer.BINS + 4L * bin)) / 3;
    }

    /**
     * Whether the last finished frame wanted more than the view's lists hold: more meshlets in a phase of a raster bin
     * than it holds ({@link ViewOptions#draws()}, {@link ViewOptions#binDraws()}), or on the indexed path more
     * triangles. What did not fit was not drawn; {@link #resize} the view. False until the view has culled.
     */
    public boolean overflowed() {
        for (int bin = 0; bin < MeshletRenderer.BINS; bin++) {
            int most = Math.max(drawCount.memory().get(U32, 8L * bin), drawCount.memory().get(U32, 8L * bin + 4));
            int draws = bin == 0 ? options.draws() : options.binDraws();
            int triangles = bin == 0 ? options.triangles() : options.binTriangles();
            if (most > draws || frameIndices != null && wantedTriangles(bin) > triangles) {
                return true;
            }
        }
        return false;
    }

    /**
     * Gives the view lists for {@code draws} meshlets and {@code triangles} triangles (as {@link ViewOptions}), the
     * other raster bins a quarter of that, e.g. once it {@link #overflowed}: the old lists go once no frame in flight
     * uses them. Allocates (rarely).
     */
    public RenderView resize(int draws, int triangles) {
        return resize(draws, triangles, Math.max(1, draws / 4), Math.max(1, triangles / 4));
    }

    /** Like {@link #resize(int, int)}, with room for {@code binDraws} and {@code binTriangles} in the other bins. */
    @AllowAllocation("resizing, rarely")
    public RenderView resize(int draws, int triangles, int binDraws, int binTriangles) {
        checkOpen();
        ViewOptions resized = new ViewOptions(draws, triangles, options.occlusion(), options.layers(),
            options.castersOnly(), binDraws, binTriangles);
        if (resized.allDraws() != options.allDraws()) {
            renderer.retire(visible);
            visible = GpuBuffer.deviceLocal(device, 16L * resized.allDraws(),
                VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT);
            visibleImport.set(visible); // a new size: the graph rebuilds
            putLong(ViewLayout.LISTS_VISIBLE, visible.deviceAddress());
        }
        options = resized;
        if (split != null && split.size() != splitBytes(splitRoom)) {
            renderer.retire(split);
            split = newSplit(splitRoom);
            splitImport.set(split);
        }
        if (sort != null) {
            sortBuffer();
        }
        putInt(ViewLayout.MAX_DRAWS, draws);
        putInt(ViewLayout.BIN_DRAWS, binDraws);
        boolean indexed = frameIndices != null;
        if (indexed && frameIndices.size() != 12L * resized.allTriangles()) {
            dropIndices();
            frameIndices();
        }
        return this;
    }

    /** The GPU memory the view's lists, commands and history take, in bytes. */
    public long bytes() {
        return (long) BYTES * renderer.viewConstants().slots().count() + visible.size() + expandCommand.size() + drawCommand.size()
            + drawCount.size() + (visibility == null ? 0 : visibility.size())
            + (frameIndices == null ? 0 : frameIndices.size()) + (split == null ? 0 : split.size())
            + (sort == null ? 0 : sort.size());
    }

    /** The world position along {@code axis} of the view's camera, as last {@link #set}. */
    public double position(int axis) {
        return position[Objects.checkIndex(axis, 3)];
    }

    public ViewOptions options() {
        return options;
    }

    public MeshletRenderer renderer() {
        return renderer;
    }

    /** Its place in the renderer's view constants array: shaders find its View there by this index. */
    public int index() {
        return index;
    }

    /** The shaders' View, for passes drawing it. */
    public long address() {
        return renderer.viewConstants().deviceAddress() + base;
    }

    GpuBuffer visible() {
        return visible;
    }

    GpuBuffer expandCommand() {
        return expandCommand;
    }

    GpuBuffer drawCommand() {
        return drawCommand;
    }

    GpuBuffer drawCount() {
        return drawCount;
    }

    /**
     * Has culling split the view's lists of every raster bin by shading bin from now on (drawn shaded by type), with
     * room for {@code categories} shading bins (a table's {@link Materials#binCount}).
     */
    @AllowAllocation("building the graph, when it changes")
    void splitByType(int categories) {
        splitByType(categories, (1 << MeshletRenderer.BINS) - 1, 0);
    }

    /**
     * Has culling split the view's lists of the raster bins of {@code bins} (a bit each) from now on, with room for
     * {@code categories}: by shading bin, or for those of {@code geometryBins} by type with geometry hooks
     * ({@link Materials#geometryTypeCount}; 0: the others). Its split lists are made, or remade larger, now; the other
     * bins' lists stay as culled, for draws shared by every type. Set while a graph builds, before the view is culled
     * ({@link MeshletCulling.Views#add}); a second call in a build adds bins and room.
     */
    @AllowAllocation("building the graph, when it changes")
    synchronized void splitByType(int categories, int bins, int geometryBins) {
        if ((splitBins & geometryBins & ~splitGeometryBins) != 0 || (splitGeometryBins & bins & ~geometryBins) != 0) {
            throw new IllegalStateException("View " + index + "'s raster bins split both by shading bin and by type");
        }
        if (categories < 1 || categories > Materials.MAX_BINS) {
            throw new IllegalArgumentException("1 to " + Materials.MAX_BINS + " shading bins: " + categories);
        }
        if (categories > splitRoom) {
            int room = Math.min(Materials.MAX_BINS, Math.max(categories, 2 * splitRoom));
            if (split != null) {
                renderer.retire(split);
            }
            split = newSplit(room);
            splitRoom = room;
            if (splitImport == null) {
                splitImport = new ExternalBuffer("view " + index + " split lists", split);
            } else {
                splitImport.set(split);
            }
        }
        splitCategories = splitRoom;
        splitBins |= bins;
        splitGeometryBins |= geometryBins;
    }

    /** Culling no longer splits the view's lists (its split lists stay, for a later graph), nor sorts them. */
    synchronized void noSplit() {
        splitCategories = 0;
        splitBins = 0;
        splitGeometryBins = 0;
        sortSlots = 0;
        sortCategories = 0;
    }

    /**
     * Has culling sort the view's blended bin farthest first after splitting it ({@link SortedTransparency}): in runs
     * of one drawn shading bin, each drawn by a (run slot, drawn shading bin) entry of the split lists, {@code slots}
     * run slots (at least as many as drawn shading bins). {@code drawn} numbers the table's shading bins among those
     * drawn (-1: not drawn). Set while a graph builds; the sort's buffer is made now, the split lists' room grown.
     */
    @AllowAllocation("building the graph, when it changes")
    synchronized void sortBlended(int slots, int[] drawn) {
        int categories = 0;
        for (int d : drawn) {
            categories = Math.max(categories, d + 1);
        }
        if (categories < 1 || categories > 254 || drawn.length > Materials.MAX_BINS
            || (long) slots * categories > Materials.MAX_BINS || slots < categories) {
            throw new IllegalArgumentException("Sorted blended surfaces of " + categories + " shading bins in " + slots
                + " run slots: 1 to 254 bins, at least as many slots, at most " + Materials.MAX_BINS + " entries");
        }
        splitByType(slots * categories, 1 << MeshletRenderer.BIN_BLENDED, 0);
        sortSlots = slots;
        sortCategories = categories;
        sortDrawn = drawn.clone();
        sortBuffer();
    }

    /** Per shading bin of the table, its number among those the sort draws, or -1; as {@link #sortBlended} set. */
    int[] sortDrawn() {
        return sortDrawn;
    }

    /** Run slots of the blended bin's sort; 0: not sorted. */
    int sortSlots() {
        return sortSlots;
    }

    /** Shading bins of the blended bin's sort: the run slots' entries are slot * this + shading bin. */
    int sortCategories() {
        return sortCategories;
    }

    /** The sort's buffer ({@link #sortOffset}). */
    GpuBuffer sort() {
        return sort;
    }

    /** Keys a sort of the view holds: the blended bin's room. */
    int sortCapacity() {
        return options.binDraws();
    }

    /**
     * Where part {@code part} of the sort's buffer starts: 0 the keys, 1 the other keys, 2 the radix counts, 3 the
     * arguments (32 words), 4 the blocks' counts, 5 the runs (3 words per slot, up to {@link Materials#MAX_BINS}), 6 the
     * drawn shading bins' numbers (a word per shading bin of the table), 7 the end.
     */
    long sortOffset(int part) {
        long keys = sortCapacity();
        long groups = (keys + com.truesim.engine.render.layout.ParticleConstants.SORT_GROUP - 1)
            / com.truesim.engine.render.layout.ParticleConstants.SORT_GROUP;
        long blocks = (keys + 1023) / 1024;
        long at = 0;
        for (int i = 0; i < part; i++) { // each part 256-byte aligned; allocates nothing
            long size = switch (i) {
                case 0, 1 -> 8 * keys;
                case 2 -> 64 * groups;
                case 3 -> 128;
                case 4 -> 8 * blocks;
                case 5 -> 12L * Materials.MAX_BINS;
                default -> 4L * Materials.MAX_BINS;
            };
            at += (size + 255) & ~255L;
        }
        return at;
    }

    /** The sort's buffer, made or remade for the lists' size. */
    @AllowAllocation("building the graph, or resizing")
    private void sortBuffer() {
        if (sort != null && sort.size() == sortOffset(7)) {
            return;
        }
        if (sort != null) {
            renderer.retire(sort);
        }
        sort = GpuBuffer.deviceLocal(device, sortOffset(7), VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
            | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT | VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT
            | VK_BUFFER_USAGE_TRANSFER_DST_BIT);
        if (sortImport == null) {
            sortImport = new ExternalBuffer("view " + index + " sort", sort);
        } else {
            sortImport.set(sort);
        }
    }

    /** Declares that {@code pass} (the culling) sorts the view's lists where it does: the sort's buffer. */
    void declareSorting(GraphBuilder g, GraphBuilder.PassBuilder pass) {
        if (sortSlots > 0) {
            Buffer buffer = g.importBuffer(sortImport);
            pass.write(buffer, BufferAccess.TRANSFER_DST).write(buffer, BufferAccess.STORAGE_WRITE)
                .read(buffer, BufferAccess.INDIRECT);
        }
    }

    /** The raster bins (a bit each) whose lists culling splits by type with geometry hooks, not by shading bin. */
    int splitGeometryBins() {
        return splitGeometryBins;
    }

    /** The raster bins (a bit each) whose lists culling splits; 0: none. */
    int splitBins() {
        return splitBins;
    }

    /** The shading bins the view's split lists have room for while culling splits them; 0: it does not. */
    int splitCategories() {
        return splitCategories;
    }

    /** The split lists: a header entry per raster bin and shading bin (common.slang's SPLIT_*), then the slots. */
    GpuBuffer split() {
        return split;
    }

    /** Bytes of split lists with room for {@code room} shading bins: the header, the slot list and a spare word. */
    private long splitBytes(int room) {
        return 4L * ((long) MeshletRenderer.BINS * room * CommonConstants.SPLIT_WORDS + options.allDraws() + 4);
    }

    private GpuBuffer newSplit(int room) {
        return GpuBuffer.deviceLocal(device, splitBytes(room), VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
            | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT | VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT
            | VK_BUFFER_USAGE_TRANSFER_DST_BIT);
    }

    /** Lets the indexed path's index buffer go, once no frame in flight reads it: the path is mesh shaders now. */
    synchronized void dropIndices() {
        if (frameIndices != null) {
            renderer.retire(frameIndices);
            frameIndices = null;
            if (indicesImport != null) {
                indicesImport.set(noIndices); // graphs name no freed buffer; a new size rebuilds them
            }
        }
    }

    /** Whether the view holds an index buffer: it was drawn on the indexed path. */
    boolean hasIndices() {
        return frameIndices != null;
    }

    /**
     * The indexed path's index buffer, made the first time it is needed (rarely: the path changed), by whichever of
     * the passes culling and drawing the view records first.
     */
    @AllowAllocation("the indexed path drawing the view for the first time")
    synchronized GpuBuffer frameIndices() {
        if (frameIndices == null) {
            frameIndices = GpuBuffer.deviceLocal(device, 12L * options.allTriangles(),
                VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT
                    | VK_BUFFER_USAGE_INDEX_BUFFER_BIT);
            if (indicesImport != null) {
                indicesImport.set(frameIndices);
            }
        }
        return frameIndices;
    }

    /**
     * Declares that {@code pass} culls the view: resets its lists and counts with transfers, then fills them (and its
     * history) with compute; on the indexed path, its index buffer too (made now if it was not).
     */
    void declareCulling(GraphBuilder g, GraphBuilder.PassBuilder pass) {
        Buffer expand = g.importBuffer(expandImport);
        Buffer draw = g.importBuffer(drawImport);
        Buffer count = g.importBuffer(countImport);
        pass.write(g.importBuffer(visibleImport), BufferAccess.STORAGE_WRITE)
            .write(expand, BufferAccess.TRANSFER_DST).write(expand, BufferAccess.STORAGE_WRITE)
            .write(draw, BufferAccess.TRANSFER_DST).write(draw, BufferAccess.STORAGE_WRITE)
            .write(count, BufferAccess.TRANSFER_DST).write(count, BufferAccess.STORAGE_WRITE);
        if (visibilityImport != null) {
            pass.write(g.importBuffer(visibilityImport), BufferAccess.STORAGE_WRITE);
        }
        if (splitCategories > 0) { // reset, then counted, laid out and scattered by the split's dispatches
            Buffer lists = g.importBuffer(splitImport);
            pass.write(lists, BufferAccess.TRANSFER_DST).write(lists, BufferAccess.STORAGE_WRITE);
        }
        indicesDeclared = renderer.path() == MeshletRenderer.Path.INDEXED;
        if (indicesDeclared) { // the expansion writes the index buffer: made now, while the graph builds
            GpuBuffer indices = frameIndices();
            if (indicesImport == null) {
                noIndices = GpuBuffer.deviceLocal(device, 4, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
                indicesImport = new ExternalBuffer("view " + index + " indices", indices);
            }
            pass.write(g.importBuffer(indicesImport), BufferAccess.STORAGE_WRITE);
        }
    }

    /** Declares that {@code pass} draws what culling listed for the view: its indirect commands, list and counts. */
    void declareDrawing(GraphBuilder g, GraphBuilder.PassBuilder pass) {
        pass.read(g.importBuffer(expandImport), BufferAccess.INDIRECT)
            .read(g.importBuffer(drawImport), BufferAccess.INDIRECT)
            .read(g.importBuffer(visibleImport), BufferAccess.DRAW_READ)
            .read(g.importBuffer(countImport), BufferAccess.DRAW_READ);
        if (splitCategories > 0) {
            Buffer lists = g.importBuffer(splitImport);
            pass.read(lists, BufferAccess.INDIRECT).read(lists, BufferAccess.DRAW_READ);
        }
        if (indicesDeclared) {
            pass.read(g.importBuffer(indicesImport), BufferAccess.INDEX);
        }
    }

    /** Whether the graph orders the view's index buffer: the last build declared it, and the view has one. */
    boolean indicesOrdered() {
        return indicesDeclared && frameIndices != null;
    }

    boolean hasHistory() {
        return visibility != null;
    }

    void checkOpen() {
        if (closed) {
            throw new IllegalStateException("The view is closed");
        }
    }

    /**
     * Removes the view from its renderer: drawing or culling it throws from now on, its index goes to the next view
     * made, and its buffers are freed once no frame in flight reads them. Rebuild graphs that drew it. The renderer
     * closes its views as it closes.
     */
    @Override
    public void close() {
        if (!closed) {
            closed = true;
            renderer.removeView(this);
        }
    }

    /** Frees the view's buffers through {@code retiring}: once no frame in flight reads them, or now once it closed. */
    @AllowAllocation("closing a view")
    void freeBuffers(Retiring retiring) {
        closed = true;
        for (AutoCloseable buffer : new AutoCloseable[] {visible, expandCommand, drawCommand, drawCount,
            visibility, frameIndices, noIndices, split, sort}) {
            if (buffer == null) {
                continue;
            }
            retiring.add(buffer);
        }
        frameIndices = null;
    }
}
