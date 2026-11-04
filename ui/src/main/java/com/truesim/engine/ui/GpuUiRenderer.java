package com.truesim.engine.ui;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.memPutInt;
import static org.lwjgl.system.MemoryUtil.memPutLong;
import static org.lwjgl.vulkan.VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16B16A16_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_SRGB;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_UNDEFINED;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_SAMPLED_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_FRAGMENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_VERTEX_BIT;
import static org.lwjgl.vulkan.VK10.nvkCmdCopyBufferToImage;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_NONE;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_SHADER_SAMPLED_READ_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_TRANSFER_WRITE_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_TRANSFER_BIT;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.graph.Id;
import com.truesim.engine.graph.Clear;
import com.truesim.engine.graph.FrameGraph;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Image;
import com.truesim.engine.graph.ImageDesc;
import com.truesim.engine.graph.Key;
import com.truesim.engine.graph.PassContext;
import com.truesim.engine.graph.Technique;
import com.truesim.engine.graph.ViewPort;
import com.truesim.engine.render.Camera;
import com.truesim.engine.render.Keys;
import com.truesim.engine.settings.EngineSettings;
import com.truesim.engine.settings.Setting;
import com.truesim.engine.ui.layout.UiPush;
import com.truesim.engine.vulkan.Format;
import com.truesim.engine.vulkan.FrameBuffer;
import com.truesim.engine.vulkan.FrameSlots;
import com.truesim.engine.vulkan.FrameUploads;
import com.truesim.engine.vulkan.GpuBuffer;
import com.truesim.engine.vulkan.GpuImage;
import com.truesim.engine.vulkan.Images;
import com.truesim.engine.vulkan.Pipeline;
import com.truesim.engine.vulkan.Retiring;
import com.truesim.engine.vulkan.ShaderModule;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.ui.core.draw.DirtyRanges;
import com.truesim.ui.core.draw.DrawList;
import com.truesim.ui.core.draw.Primitive;
import com.truesim.ui.core.draw.UiRenderer;
import com.truesim.ui.core.draw.UiTexture;
import com.truesim.ui.core.draw.UiTextures;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkCommandBuffer;

/**
 * Draws a UI's draw lists (ui-core's {@link UiRenderer}) as a frame graph technique providing a view's {@link Keys#UI}
 * image: premultiplied alpha, linear, 1.0 meaning paper white, at the view's output size, cleared transparent each
 * frame, which the view's {@code DisplayTransform} composites after the tone curve (design UI13).
 *
 * <p><b>Data.</b> Each layer's primitives, clip table and transform table live in persistent buffers (one copy per
 * frame in flight, {@link FrameBuffer}); {@link #submit} copies only what changed since the last submit (each of the
 * draw list's dirty ranges of primitives and of transform entries, its clips when they changed), so a frame where
 * nothing changed uploads nothing ({@link #uploadedBytes()}), and an animated opacity, scale or offset uploads its
 * 32-byte transform entry, not the primitives it draws. UI
 * textures get a bindless slot each, named by a table the shader reads by texture id; a texture's dirty region is
 * uploaded when it changes, sampled as {@code RGBA8_UNORM} (MTSDF atlases) or {@code RGBA8_SRGB} (images).
 *
 * <p><b>Drawing.</b> Vertex pulling: six vertices per primitive, no vertex buffers; signed distances for boxes, shadows
 * (Evan Wallace's blurred rounded rectangle), lines and MTSDF glyphs, clipped in the shader by rounded clip
 * rectangles, blended premultiplied. {@link Batching#ADAPTIVE} (the default) draws a layer with one draw of the uber
 * shader, which branches on the kind, but for long runs of one kind (a text flood), drawn with that kind's own
 * shader; {@link Batching#UBER} and {@link Batching#PER_KIND} are the two options design UI15 compares, kept for the
 * benchmark ({@code UiBenchmark}): at 4K on an RX 9070 XT the uber path drew a HUD of 500 elements and 5,000 glyphs in
 * one draw, 0.10 ms, against 1,075 draws and 0.165 ms per kind, while per kind drew 100,000 glyphs about 3% faster;
 * adaptive matched the uber path on the HUD and came within 1% of per kind on the glyphs.
 *
 * <pre>{@code
 * GpuUiRenderer ui = new GpuUiRenderer(graph);
 * view.add(ui);                                              // provides the view's Keys.UI
 * presenter.outputTransform(new DisplayTransform());          // composites it
 * // each frame, after the UI recorded its lists, before presenter.render():
 * ui.submit(layers, layerCount, textures, width, height);
 * }</pre>
 *
 * <p><b>Depth tested primitives</b> (ui-core's {@code Primitive.FLAG_DEPTH}: a gizmo's handles, {@code
 * Gizmo.depthTest}) are faded per pixel where the view's scene depth ({@link Keys#SCENE_DEPTH}, read in the same frame
 * after everything that writes it, where the view has one) is nearer than they are, their colour times their occluded
 * opacity there: exact, with no frame of delay, also where an occluder moved this frame. The depth is turned into view
 * depths by the projection of {@link #depthCamera}, the camera the view's scene is drawn through (the {@code UiPlugin}
 * sets it from its scene bridge's); without one, or without a scene depth, they draw unfaded. Its render region maps
 * onto the UI's image by its share of the view (dynamic resolution included); {@link #DEPTH_BIAS} keeps a handle lying
 * on a surface visible.
 *
 * <p><b>Several views of one UI.</b> A UI shown in several windows (docking's OS windows each show a region of it)
 * uploads once: {@link #share()} makes a renderer for another view that draws this one's buffers and textures (one
 * device, one upload, several presenter targets), at its own {@link #origin} (the UI pixel at its image's top left)
 * and region size ({@link #submit}'s width and height, its layers ignored: this renderer's are drawn).
 *
 * <p>One renderer per view (a technique belongs to one view). Use it on the thread that records frames. Submitting
 * allocates nothing once its buffers have grown, but for textures added or written (rare: an atlas page filled).
 */
public final class GpuUiRenderer implements UiRenderer, Technique, AutoCloseable {

    /** The id of a view's UI renderer. */
    public static final Id<GpuUiRenderer> ID = Id.of("truesim.ui", GpuUiRenderer.class);

    /** How layers are drawn: the batchings design UI15 compares, and the adaptive one chosen by benchmark. */
    public enum Batching {
        /** One draw per layer, one fragment shader branching on the kind (design UI15 a). */
        UBER,
        /** A draw per run of primitives of one kind, in list order, each kind its own fragment shader (UI15 b). */
        PER_KIND,
        /**
         * Runs of at least {@link #LONG_RUN} primitives of one kind (a text flood) drawn with their kind's shader,
         * everything between them with the uber shader: few draws for mixed lists, the lean shader for long runs.
         */
        ADAPTIVE
    }

    /**
     * How much farther than the scene (relative to its depth) a depth tested primitive must be to be faded: 1e-4 is
     * 1 mm at 10 m, which keeps a handle lying on a surface from flickering.
     */
    public static final Setting<Float> DEPTH_BIAS = Setting.decimal("ui.gizmo.depthBias", 1e-4f, 0, 0.1f,
        "how much farther than the scene (relative) a gizmo's part must be to be faded as hidden");

    /** The shortest run of one kind {@link Batching#ADAPTIVE} draws with its kind's own shader. */
    public static final int LONG_RUN = 256;

    /** The shader entry points of the kinds, by kind (Primitive's numbers); EMPTY has none. */
    private static final String[] KIND_ENTRIES = {null, "boxMain", "shadowMain", "glyphMain", "imageMain",
        "nineSliceMain", "lineMain", "triangleMain"};
    private static final int BUFFER_USAGE = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
        | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;
    private static final int CLIP_BYTES = DrawList.CLIP_FLOATS * 4;
    private static final int TRANSFORM_BYTES = DrawList.TRANSFORM_FLOATS * 4;

    private final VulkanDevice device;
    private final FrameSlots slots;
    private final FrameUploads uploads;
    private final Retiring retiring;
    private final Pipeline uber;
    private final Pipeline[] kinds;
    private final GpuUiRenderer source; // the renderer whose uploads this one draws, or null: its own
    private float originX;
    private float originY;
    private volatile Batching batching = Batching.ADAPTIVE;
    private volatile Camera depthCamera;

    // Per layer: the list last submitted, its buffers, and what was drawn of it.
    private DrawList[] lists = new DrawList[0];
    private FrameBuffer[] primitives = new FrameBuffer[0];
    private FrameBuffer[] clips = new FrameBuffer[0];
    private FrameBuffer[] transforms = new FrameBuffer[0];
    private int[] counts = new int[0];
    // The draws planned for each layer's list (first, count, pipeline: 0 the uber one, else a kind's), for the list's
    // version and the batching they were planned for.
    private int[][] draws = new int[0][];
    private int[] drawCounts = new int[0];
    private long[] plannedVersion = new long[0];
    private Batching[] plannedFor = new Batching[0];
    // The arrays of each list wrapped as segments, again only when a list grows its arrays.
    private int[][] wrappedWords = new int[0][];
    private MemorySegment[] wordSegments = new MemorySegment[0];
    private float[][] wrappedClips = new float[0][];
    private MemorySegment[] clipSegments = new MemorySegment[0];
    private float[][] wrappedTransforms = new float[0][];
    private MemorySegment[] transformSegments = new MemorySegment[0];
    private int layerCount;
    private int width;
    private int height;

    // Textures: an image and a sampled slot per id, the table naming the slots.
    private UiTextures textures;
    private GpuImage[] images = new GpuImage[1];
    private int[] slotOf = new int[1];
    // UI textures drawn from a frame graph texture (a camera view's image), by id: no pixels uploaded, sampled in place
    private com.truesim.engine.graph.Texture[] bound = new com.truesim.engine.graph.Texture[1];
    private FrameBuffer table;
    private final Arena stagingArena;
    private MemorySegment staging;

    private long uploaded;
    private long totalUploaded;
    private boolean closed;

    /** A renderer for views of {@code graph}. */
    public GpuUiRenderer(FrameGraph graph) {
        this(graph.device(), graph.frameSlots());
    }

    /** A renderer on {@code device} for frames of {@code slots}. */
    @AllowAllocation("made once")
    public GpuUiRenderer(VulkanDevice device, FrameSlots slots) {
        this.device = Objects.requireNonNull(device, "device");
        this.slots = Objects.requireNonNull(slots, "slots");
        this.uploads = new FrameUploads(device, slots);
        this.retiring = new Retiring(slots);
        this.source = null;
        this.kinds = new Pipeline[Primitive.KINDS];
        this.stagingArena = Arena.ofShared();
        try (ShaderModule shaders = ShaderModule.load(device, "shaders/ui/draw.spv")) {
            uber = pipeline(shaders, "fragmentMain");
            for (int kind = 1; kind < Primitive.KINDS; kind++) { // kinds with a shader
                kinds[kind] = pipeline(shaders, KIND_ENTRIES[kind]);
            }
        }
        table = FrameBuffer.create(device, 64L * 4, BUFFER_USAGE, slots);
        staging = stagingArena.allocate(64L * 64 * 4, 16);
    }

    /** A renderer drawing {@code source}'s uploads: {@link #share()}. */
    @AllowAllocation("made once a window")
    private GpuUiRenderer(GpuUiRenderer source) {
        this.source = source;
        this.device = source.device;
        this.slots = source.slots;
        this.uploads = source.uploads;
        this.retiring = source.retiring;
        this.uber = source.uber;
        this.kinds = source.kinds;
        this.stagingArena = source.stagingArena;
        this.table = source.table;
        this.staging = source.staging;
    }

    /**
     * A renderer for another view that draws what this one uploads (its layers and textures, submitted to this one
     * each frame first): for a UI shown in several windows. Close it before this one.
     */
    @AllowAllocation("made once a window")
    public GpuUiRenderer share() {
        if (closed) {
            throw new IllegalStateException("The UI renderer is closed");
        }
        return new GpuUiRenderer(source != null ? source : this);
    }

    /** The renderer whose uploads it draws ({@link #share()}), or null if it uploads its own. */
    public GpuUiRenderer source() {
        return source;
    }

    /** Draws the UI region whose top left is UI pixel {@code x, y} (0, 0 by default); returns this. */
    public GpuUiRenderer origin(float x, float y) {
        originX = x;
        originY = y;
        return this;
    }

    /** The UI pixel at its image's top left. */
    public float originX() {
        return originX;
    }

    /** The UI pixel at its image's top left. */
    public float originY() {
        return originY;
    }

    /**
     * The camera the view's scene depth is drawn through (null: none), whose projection turns that depth into the view
     * depths depth tested primitives are compared with; read as each frame records. Returns this.
     */
    public GpuUiRenderer depthCamera(Camera camera) {
        depthCamera = camera;
        return this;
    }

    /** The camera the view's scene depth is drawn through, or null. */
    public Camera depthCamera() {
        return depthCamera;
    }

    private Pipeline pipeline(ShaderModule shaders, String fragment) {
        return Pipeline.graphics(device).stage(VK_SHADER_STAGE_VERTEX_BIT, shaders, "vertexMain")
            .stage(VK_SHADER_STAGE_FRAGMENT_BIT, shaders, fragment).color(VK_FORMAT_R16G16B16A16_SFLOAT)
            .blend(0, Pipeline.Blend.PREMULTIPLIED).pushConstants(UiPush.BYTES).doubleSided().build();
    }

    /** Draws layers by {@code mode} from the next frame on. */
    public GpuUiRenderer batching(Batching mode) {
        this.batching = Objects.requireNonNull(mode, "mode");
        return this;
    }

    /** How layers are drawn. */
    public Batching batching() {
        return batching;
    }

    // ------------------------------------------------------------------------------------------------- submitting

    /**
     * Takes this frame's UI: uploads what changed of each list (its dirty primitives and clips, then
     * {@link DrawList#clean() cleans} it) and of each texture, and draws the lists, back to front, in the frames
     * recorded after. Call it every frame the UI is shown, after the UI recorded, before the frame records.
     */
    @Override
    public void submit(DrawList[] layers, int layerCount, UiTextures uiTextures, int uiWidth, int uiHeight) {
        if (closed) {
            throw new IllegalStateException("The UI renderer is closed");
        }
        if (layerCount < 0 || layerCount > layers.length) {
            throw new IllegalArgumentException("layers: " + layerCount);
        }
        if (source != null) { // its source uploads: only the region drawn is its own
            this.width = uiWidth;
            this.height = uiHeight;
            uploaded = 0;
            return;
        }
        retiring.release();
        if (layerCount > lists.length) {
            growLayers(layerCount);
        }
        long bytes = 0;
        for (int i = 0; i < layerCount; i++) {
            bytes += upload(i, Objects.requireNonNull(layers[i], "layer"));
        }
        for (int i = layerCount; i < lists.length; i++) {
            lists[i] = null; // taken again in full if it comes back
        }
        if (uiTextures != null) {
            bytes += uploadTextures(uiTextures);
        }
        this.layerCount = layerCount;
        this.width = uiWidth;
        this.height = uiHeight;
        uploaded = bytes;
        totalUploaded += bytes;
    }

    /** Uploads what changed of layer {@code i}, now {@code list}: its bytes. */
    private long upload(int i, DrawList list) {
        int size = list.size();
        boolean fresh = lists[i] != list;
        lists[i] = list;
        long primitiveBytes = (long) Math.max(1, size) * Primitive.BYTES;
        if (primitives[i] == null || primitives[i].size() < primitiveBytes) {
            primitives[i] = grow(primitives[i], primitiveBytes);
            fresh = true; // a new buffer: all of it
        }
        long clipBytes = (long) list.clipCount() * CLIP_BYTES;
        boolean clipsFresh = fresh;
        if (clips[i] == null || clips[i].size() < clipBytes) {
            clips[i] = grow(clips[i], clipBytes);
            clipsFresh = true;
        }
        long transformBytes = (long) list.transformCount() * TRANSFORM_BYTES;
        boolean transformsFresh = fresh;
        if (transforms[i] == null || transforms[i].size() < transformBytes) {
            transforms[i] = grow(transforms[i], transformBytes);
            transformsFresh = true;
        }
        long bytes = 0;
        if (fresh) {
            bytes += put(primitives[i], words(i, list.words()), 0, size, Primitive.BYTES);
        } else {
            DirtyRanges dirty = list.dirtyRanges(); // changes far apart upload apart
            for (int r = 0; r < dirty.count(); r++) {
                bytes += put(primitives[i], words(i, list.words()), dirty.from(r), Math.min(dirty.to(r), size),
                    Primitive.BYTES);
            }
        }
        if (clipsFresh || list.clipsDirty()) {
            clips[i].put(0, clips(i, list.clips()), 0, clipBytes);
            bytes += clipBytes;
        }
        int transformCount = list.transformCount();
        if (transformsFresh) {
            bytes += put(transforms[i], transforms(i, list.transforms()), 0, transformCount, TRANSFORM_BYTES);
        } else {
            DirtyRanges dirty = list.transformRanges(); // an animation: its entry alone
            for (int r = 0; r < dirty.count(); r++) {
                bytes += put(transforms[i], transforms(i, list.transforms()), dirty.from(r),
                    Math.min(dirty.to(r), transformCount), TRANSFORM_BYTES);
            }
        }
        counts[i] = size;
        Batching mode = batching;
        if (fresh || plannedVersion[i] != list.version() || plannedFor[i] != mode) {
            plan(i, list, mode);
            plannedVersion[i] = list.version();
            plannedFor[i] = mode;
        }
        list.clean();
        return bytes;
    }

    /** Copies records {@code [from, to)} of {@code record} bytes each from {@code source} into {@code buffer}: the bytes. */
    private static long put(FrameBuffer buffer, MemorySegment source, int from, int to, int record) {
        if (to <= from) {
            return 0;
        }
        long offset = (long) from * record;
        long length = (long) (to - from) * record;
        buffer.put(offset, source, offset, length);
        return length;
    }

    /**
     * Plans layer {@code i}'s draws by {@code mode}: the whole list with the uber shader, or its runs of one kind (empty
     * primitives join the run they fall in), short runs together with the uber shader where adaptive.
     */
    private void plan(int i, DrawList list, Batching mode) {
        drawCounts[i] = 0;
        int size = list.size();
        if (size == 0) {
            return;
        }
        if (mode == Batching.UBER) {
            emit(i, 0, size, 0);
            return;
        }
        int runStart = 0;
        int runKind = -1;
        int span = -1; // the first primitive of the short runs waiting to be drawn together, or -1
        for (int p = 0; p <= size; p++) {
            int kind = p == size ? -2 : Primitive.kind(list.header(p));
            if (kind == runKind || kind == Primitive.EMPTY && runKind >= 0) {
                continue;
            }
            if (runKind >= 0) {
                int length = p - runStart;
                if (mode == Batching.PER_KIND || length >= LONG_RUN) {
                    if (span >= 0) {
                        emit(i, span, runStart - span, 0);
                        span = -1;
                    }
                    if (runKind != Primitive.EMPTY) {
                        emit(i, runStart, length, runKind);
                    }
                } else if (span < 0) {
                    span = runStart;
                }
            }
            runStart = p;
            runKind = kind;
        }
        if (span >= 0) {
            emit(i, span, size - span, 0);
        }
    }

    private void emit(int i, int first, int count, int pipeline) {
        int n = drawCounts[i];
        if ((n + 1) * 3 > draws[i].length) {
            draws[i] = grow(draws[i]);
        }
        draws[i][n * 3] = first;
        draws[i][n * 3 + 1] = count;
        draws[i][n * 3 + 2] = pipeline;
        drawCounts[i] = n + 1;
    }

    @AllowAllocation("more draws than ever before, rarely")
    private static int[] grow(int[] array) {
        return Arrays.copyOf(array, Math.max(48, array.length * 2));
    }

    @AllowAllocation("a layer's buffer grown, rarely")
    private FrameBuffer grow(FrameBuffer old, long bytes) {
        long size = old == null ? Math.max(bytes, 64L * Primitive.BYTES) : Math.max(bytes, old.size() * 2);
        if (old != null) {
            retiring.add(old);
        }
        return FrameBuffer.create(device, size, BUFFER_USAGE, slots);
    }

    @AllowAllocation("a list's words wrapped again after it grew them, rarely")
    private MemorySegment words(int i, int[] array) {
        if (wrappedWords[i] != array) {
            wrappedWords[i] = array;
            wordSegments[i] = MemorySegment.ofArray(array);
        }
        return wordSegments[i];
    }

    @AllowAllocation("a list's clips wrapped again after it grew them, rarely")
    private MemorySegment clips(int i, float[] array) {
        if (wrappedClips[i] != array) {
            wrappedClips[i] = array;
            clipSegments[i] = MemorySegment.ofArray(array);
        }
        return clipSegments[i];
    }

    @AllowAllocation("a list's transforms wrapped again after it grew them, rarely")
    private MemorySegment transforms(int i, float[] array) {
        if (wrappedTransforms[i] != array) {
            wrappedTransforms[i] = array;
            transformSegments[i] = MemorySegment.ofArray(array);
        }
        return transformSegments[i];
    }

    @AllowAllocation("more layers than ever before")
    private void growLayers(int count) {
        lists = Arrays.copyOf(lists, count);
        primitives = Arrays.copyOf(primitives, count);
        clips = Arrays.copyOf(clips, count);
        transforms = Arrays.copyOf(transforms, count);
        counts = Arrays.copyOf(counts, count);
        plannedVersion = Arrays.copyOf(plannedVersion, count);
        plannedFor = Arrays.copyOf(plannedFor, count);
        drawCounts = Arrays.copyOf(drawCounts, count);
        wrappedWords = Arrays.copyOf(wrappedWords, count);
        wordSegments = Arrays.copyOf(wordSegments, count);
        wrappedClips = Arrays.copyOf(wrappedClips, count);
        clipSegments = Arrays.copyOf(clipSegments, count);
        wrappedTransforms = Arrays.copyOf(wrappedTransforms, count);
        transformSegments = Arrays.copyOf(transformSegments, count);
        int before = draws.length;
        draws = Arrays.copyOf(draws, count);
        for (int i = before; i < count; i++) {
            draws[i] = new int[48];
        }
    }

    // --------------------------------------------------------------------------------------------------- textures

    /** Gives new textures an image and a slot, and uploads the dirty regions: the bytes uploaded. */
    private long uploadTextures(UiTextures uiTextures) {
        if (uiTextures != textures) {
            forgetTextures();
            textures = uiTextures;
        }
        int count = uiTextures.count();
        if (count > images.length) {
            growTextures(count);
        }
        long bytes = 0;
        for (int id = 1; id < count; id++) {
            UiTexture texture = uiTextures.get(id);
            if (id < bound.length && bound[id] != null) {
                texture.clean(); // its pixels are the graph texture's: never uploaded
                continue;
            }
            boolean fresh = images[id] == null;
            if (fresh) {
                create(id, texture);
            }
            if (fresh || texture.dirty()) {
                bytes += uploadRegion(id, texture, fresh);
                texture.clean();
            }
        }
        return bytes;
    }

    @AllowAllocation("a texture added, rarely")
    private void create(int id, UiTexture texture) {
        int format = texture.format() == UiTexture.Format.RGBA8_SRGB ? VK_FORMAT_R8G8B8A8_SRGB
            : VK_FORMAT_R8G8B8A8_UNORM;
        GpuImage image = new GpuImage(device, texture.width(), texture.height(), format,
            VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT, VK_IMAGE_ASPECT_COLOR_BIT);
        images[id] = image;
        slotOf[id] = device.bindless().addSampled(image.view());
        tableSlot(id, slotOf[id]);
    }

    /** Names bindless slot {@code slot} for texture {@code id} in the table the shader reads. */
    @AllowAllocation("the table grown, rarely")
    private void tableSlot(int id, int slot) {
        if (table.size() < (id + 1) * 4L) {
            FrameBuffer bigger = FrameBuffer.create(device, Math.max((id + 1) * 4L, table.size() * 2), BUFFER_USAGE,
                slots);
            bigger.copyFrom(table);
            retiring.add(table);
            table = bigger;
        }
        table.putInt(id * 4L, slot);
    }

    /**
     * Uploads {@code texture}'s dirty region (all of it when {@code fresh}) into its image, in the next frame's first
     * commands: the bytes.
     */
    @AllowAllocation("a texture written, rarely: an atlas page filled")
    private long uploadRegion(int id, UiTexture texture, boolean fresh) {
        int x = fresh ? 0 : texture.dirtyX();
        int y = fresh ? 0 : texture.dirtyY();
        int w = fresh ? texture.width() : texture.dirtyWidth();
        int h = fresh ? texture.height() : texture.dirtyHeight();
        long bytes = (long) w * h * 4;
        if (staging.byteSize() < bytes) {
            staging = stagingArena.allocate(Math.max(bytes, staging.byteSize() * 2), 16);
        }
        byte[] pixels = texture.pixels();
        int stride = texture.width() * 4;
        for (int row = 0; row < h; row++) {
            MemorySegment.copy(pixels, (y + row) * stride + x * 4, staging, java.lang.foreign.ValueLayout.JAVA_BYTE,
                (long) row * w * 4, w * 4);
        }
        GpuImage image = images[id];
        uploads.record(staging.asSlice(0, bytes),
            (commands, buffer, offset) -> copy(commands, buffer, offset, image, x, y, w, h, fresh));
        return bytes;
    }

    private static void copy(VkCommandBuffer commands, GpuBuffer buffer, long offset, GpuImage image, int x, int y,
        int w, int h, boolean fresh) {
        Images.transition(commands, image.handle(), VK_IMAGE_ASPECT_COLOR_BIT,
            fresh ? VK_IMAGE_LAYOUT_UNDEFINED : VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
            VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT,
            fresh ? VK_ACCESS_2_NONE : VK_ACCESS_2_SHADER_SAMPLED_READ_BIT, VK_PIPELINE_STAGE_2_TRANSFER_BIT,
            VK_ACCESS_2_TRANSFER_WRITE_BIT);
        try (MemoryStack stack = stackPush()) {
            long region = stack.ncalloc(VkBufferImageCopy.ALIGNOF, 1, VkBufferImageCopy.SIZEOF);
            memPutLong(region + VkBufferImageCopy.BUFFEROFFSET, offset);
            long layers = region + VkBufferImageCopy.IMAGESUBRESOURCE;
            memPutInt(layers + org.lwjgl.vulkan.VkImageSubresourceLayers.ASPECTMASK, VK_IMAGE_ASPECT_COLOR_BIT);
            memPutInt(layers + org.lwjgl.vulkan.VkImageSubresourceLayers.LAYERCOUNT, 1);
            memPutInt(region + VkBufferImageCopy.IMAGEOFFSET, x);
            memPutInt(region + VkBufferImageCopy.IMAGEOFFSET + 4, y);
            memPutInt(region + VkBufferImageCopy.IMAGEEXTENT, w);
            memPutInt(region + VkBufferImageCopy.IMAGEEXTENT + 4, h);
            memPutInt(region + VkBufferImageCopy.IMAGEEXTENT + 8, 1);
            nvkCmdCopyBufferToImage(commands, buffer.handle(), image.handle(), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1,
                region);
        }
        Images.transition(commands, image.handle(), VK_IMAGE_ASPECT_COLOR_BIT, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
            VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_PIPELINE_STAGE_2_TRANSFER_BIT, VK_ACCESS_2_TRANSFER_WRITE_BIT,
            VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, VK_ACCESS_2_SHADER_SAMPLED_READ_BIT);
    }

    /**
     * Draws UI texture {@code id} from {@code texture} (a frame graph texture a view renders into: a camera view)
     * instead of uploading its pixels: the texture's bindless slot is the id's, and the UI's pass reads the texture
     * after the view's copy into it in the same frame, so it shows that frame's image. Null unbinds it. The graph
     * rebuilds when its views or outputs change; bind before the frame that first draws it.
     */
    @AllowAllocation("a texture bound, rarely")
    public GpuUiRenderer bind(int id, com.truesim.engine.graph.Texture texture) {
        if (source != null) {
            source.bind(id, texture);
            return this;
        }
        if (id < 1) {
            throw new IllegalArgumentException("no texture " + id);
        }
        if (id >= bound.length) {
            bound = Arrays.copyOf(bound, Math.max(id + 1, bound.length * 2));
        }
        if (id >= images.length) {
            growTextures(id + 1);
        }
        if (images[id] != null) { // uploaded before: let its image go
            GpuImage image = images[id];
            int slot = slotOf[id];
            retiring.add(() -> device.bindless().removeSampled(slot));
            retiring.add(image);
            images[id] = null;
        }
        bound[id] = texture;
        if (texture != null) {
            tableSlot(id, texture.slot());
        }
        return this;
    }

    /** The graph texture UI texture {@code id} is drawn from, or null. */
    public com.truesim.engine.graph.Texture bound(int id) {
        GpuUiRenderer data = source != null ? source : this;
        return id > 0 && id < data.bound.length ? data.bound[id] : null;
    }

    @AllowAllocation("more textures than ever before")
    private void growTextures(int count) {
        int length = Math.max(count, images.length * 2);
        images = Arrays.copyOf(images, length);
        slotOf = Arrays.copyOf(slotOf, length);
    }

    /** Lets go of every texture's image and slot, once no frame in flight samples them. */
    @AllowAllocation("textures replaced, rarely")
    private void forgetTextures() {
        for (int id = 1; id < images.length; id++) {
            GpuImage image = images[id];
            if (image != null) {
                int slot = slotOf[id];
                retiring.add(() -> device.bindless().removeSampled(slot));
                retiring.add(image);
                images[id] = null;
            }
        }
    }

    // ------------------------------------------------------------------------------------------------------ frames

    @Override
    public Id<? extends Technique> id() {
        return ID;
    }

    @Override
    public List<Key<?>> provides() {
        return List.of(Keys.UI);
    }

    @Override
    @AllowAllocation("building the graph, when it changes")
    public void build(GraphBuilder g) {
        ViewPort view = g.view();
        int imageWidth = view == null ? Math.max(1, width) : view.outputWidth();
        int imageHeight = view == null ? Math.max(1, height) : view.outputHeight();
        Image image = g.unscaled(g.create("ui", ImageDesc.of(Format.R16G16B16A16_SFLOAT, imageWidth, imageHeight)));
        UiPush push = new UiPush();
        GraphBuilder.PassBuilder pass = g.pass("ui").attach(image, Clear.color(0, 0, 0, 0));
        Image depth = view == null ? null : g.optional(Keys.SCENE_DEPTH); // as this frame's scene left it
        if (depth != null) {
            pass.read(depth, com.truesim.engine.graph.Access.SAMPLED_FRAGMENT);
        }
        EngineSettings settings = g.settings();
        GpuUiRenderer data = source != null ? source : this;
        for (com.truesim.engine.graph.Texture t : data.bound) {
            if (t != null) {
                pass.read(g.texture(t), com.truesim.engine.graph.Access.SAMPLED_FRAGMENT); // after its view's copy
            }
        }
        pass.record(ctx -> {
            depth(ctx, push, depth, settings, imageWidth, imageHeight);
            draw(ctx, push, imageWidth, imageHeight);
        });
        g.provide(Keys.UI, image);
    }

    /**
     * Sets {@code push}'s scene depth: {@code depth}'s slot, its render region's share of the UI's image, the
     * projection turning it into view depths; none without a depth or a camera.
     */
    private void depth(PassContext ctx, UiPush push, Image depth, EngineSettings settings, int imageWidth,
        int imageHeight) {
        Camera camera = depthCamera;
        if (depth == null || camera == null) {
            push.depth(0);
            return;
        }
        int regionWidth = ctx.regionWidth(depth);
        int regionHeight = ctx.regionHeight(depth);
        float range = 0;
        if (camera.orthographic()) {
            // reverse depth 1 - distance / range: the depth row of the view-projection is back / range
            float[] m = camera.viewProjection();
            range = (float) (1 / Math.sqrt(m[2] * m[2] + m[6] * m[6] + m[10] * m[10]));
        }
        push.depth(ctx.sampled(depth) + 1).depthScaleX((float) regionWidth / imageWidth)
            .depthScaleY((float) regionHeight / imageHeight).depthMaxX(Math.max(0, regionWidth - 1))
            .depthMaxY(Math.max(0, regionHeight - 1)).depthNear((float) camera.near()).depthRange(range)
            .depthBias(settings.get(DEPTH_BIAS));
    }

    /** Draws the layers submitted last, back to front. */
    private void draw(PassContext ctx, UiPush push, int imageWidth, int imageHeight) {
        if (width <= 0 || height <= 0) {
            return;
        }
        GpuUiRenderer data = source != null ? source : this;
        push.textures(data.table.deviceAddress()).scaleX((float) width / imageWidth)
            .scaleY((float) height / imageHeight).toClipX(2f / width).toClipY(2f / height)
            .originX(originX).originY(originY);
        for (int i = 0; i < data.layerCount; i++) {
            if (data.counts[i] == 0) {
                continue;
            }
            push.primitives(data.primitives[i].deviceAddress()).clips(data.clips[i].deviceAddress())
                .transforms(data.transforms[i].deviceAddress());
            int[] planned = data.draws[i];
            for (int d = 0; d < data.drawCounts[i]; d++) {
                int pipeline = planned[d * 3 + 2];
                ctx.draw(pipeline == 0 ? uber : kinds[pipeline], push.first(planned[d * 3]), 6 * planned[d * 3 + 1],
                    1);
            }
        }
    }

    /**
     * Records its layers into an open rendering of {@code width x height} pixels with {@code pipeline} (a world panel's
     * shaders) and {@code push} (its world matrix set), one draw per layer: for {@link WorldPanelRenderer}.
     */
    void drawLayers(PassContext ctx, Pipeline pipeline, UiPush push, int uiWidth, int uiHeight) {
        GpuUiRenderer data = source != null ? source : this;
        push.textures(data.table.deviceAddress()).scaleX(1).scaleY(1).toClipX(2f / uiWidth).toClipY(2f / uiHeight)
            .originX(0).originY(0);
        for (int i = 0; i < data.layerCount; i++) {
            if (data.counts[i] == 0) {
                continue;
            }
            push.primitives(data.primitives[i].deviceAddress()).clips(data.clips[i].deviceAddress())
                .transforms(data.transforms[i].deviceAddress()).first(0);
            ctx.draw(pipeline, push, 6 * data.counts[i], 1);
        }
    }

    /**
     * Records its layers into an open rendering of an image of {@code width x height} pixels outside a frame graph's
     * passes (a world panel's texture, at the frame's start): the uber shader, one draw per layer.
     */
    void drawLayers(VkCommandBuffer commands, UiPush push, int imageWidth, int imageHeight) {
        GpuUiRenderer data = source != null ? source : this;
        if (width <= 0 || height <= 0) {
            return;
        }
        push.textures(data.table.deviceAddress()).scaleX((float) width / imageWidth)
            .scaleY((float) height / imageHeight).toClipX(2f / width).toClipY(2f / height).originX(0).originY(0);
        uber.bind(commands);
        for (int i = 0; i < data.layerCount; i++) {
            if (data.counts[i] == 0) {
                continue;
            }
            push.primitives(data.primitives[i].deviceAddress()).clips(data.clips[i].deviceAddress())
                .transforms(data.transforms[i].deviceAddress()).first(0);
            uber.push(commands, push);
            com.truesim.engine.vulkan.Commands.draw(commands, 6 * data.counts[i], 1, 0, 0);
        }
    }

    /** The device it draws on. */
    VulkanDevice device() {
        return device;
    }

    // ------------------------------------------------------------------------------------------------- statistics

    /**
     * The bytes the last {@link #submit} uploaded: primitives, clips, transform entries and texture regions. 0 when
     * nothing changed.
     */
    public long uploadedBytes() {
        return uploaded;
    }

    /** The bytes uploaded since this renderer was made. */
    public long totalUploadedBytes() {
        return totalUploaded;
    }

    /** The draws the next frame records: one per layer by the uber shader, more per kind or adaptively. */
    public int draws() {
        GpuUiRenderer data = source != null ? source : this;
        int sum = 0;
        for (int i = 0; i < data.layerCount; i++) {
            sum += data.drawCounts[i];
        }
        return sum;
    }

    /** The bindless sampled slot of UI texture {@code id}, or -1 if it has none (yet). */
    public int slot(int id) {
        if (source != null) {
            return source.slot(id);
        }
        if (id > 0 && id < bound.length && bound[id] != null) {
            return bound[id].slot();
        }
        return id > 0 && id < images.length && images[id] != null ? slotOf[id] : -1;
    }

    /** Frees its buffers, images and pipelines: call once the GPU is done with its frames. */
    @Override
    @AllowAllocation("closing")
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (source != null) {
            return; // its source's buffers and pipelines
        }
        forgetTextures();
        retiring.close();
        uploads.close();
        for (int i = 0; i < primitives.length; i++) {
            if (primitives[i] != null) {
                primitives[i].close();
            }
            if (clips[i] != null) {
                clips[i].close();
            }
            if (transforms[i] != null) {
                transforms[i].close();
            }
        }
        table.close();
        uber.close();
        for (Pipeline kind : kinds) {
            if (kind != null) {
                kind.close();
            }
        }
        stagingArena.close();
    }

    @Override
    public String toString() {
        return "UI renderer (" + layerCount + " layers)";
    }
}
