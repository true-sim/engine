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
import com.truesim.engine.render.Keys;
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
 * <p><b>Data.</b> Each layer's primitives and clip table live in persistent buffers (one copy per frame in flight,
 * {@link FrameBuffer}); {@link #submit} copies only what changed since the last submit (the draw list's dirty range,
 * its clips when they changed), so a frame where nothing changed uploads nothing ({@link #uploadedBytes()}). UI
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

    /** The shortest run of one kind {@link Batching#ADAPTIVE} draws with its kind's own shader. */
    public static final int LONG_RUN = 256;

    /** The shader entry points of the kinds, by kind (Primitive's numbers); EMPTY has none. */
    private static final String[] KIND_ENTRIES = {null, "boxMain", "shadowMain", "glyphMain", "imageMain",
        "nineSliceMain", "lineMain"};
    private static final int BUFFER_USAGE = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
        | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;
    private static final int CLIP_BYTES = DrawList.CLIP_FLOATS * 4;

    private final VulkanDevice device;
    private final FrameSlots slots;
    private final FrameUploads uploads;
    private final Retiring retiring;
    private final Pipeline uber;
    private final Pipeline[] kinds = new Pipeline[Primitive.KINDS];
    private volatile Batching batching = Batching.ADAPTIVE;

    // Per layer: the list last submitted, its buffers, and what was drawn of it.
    private DrawList[] lists = new DrawList[0];
    private FrameBuffer[] primitives = new FrameBuffer[0];
    private FrameBuffer[] clips = new FrameBuffer[0];
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
    private int layerCount;
    private int width;
    private int height;

    // Textures: an image and a sampled slot per id, the table naming the slots.
    private UiTextures textures;
    private GpuImage[] images = new GpuImage[1];
    private int[] slotOf = new int[1];
    private FrameBuffer table;
    private final Arena stagingArena = Arena.ofShared();
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
        try (ShaderModule shaders = ShaderModule.load(device, "shaders/ui/draw.spv")) {
            uber = pipeline(shaders, "fragmentMain");
            for (int kind = 1; kind < Primitive.KINDS; kind++) {
                kinds[kind] = pipeline(shaders, KIND_ENTRIES[kind]);
            }
        }
        table = FrameBuffer.create(device, 64L * 4, BUFFER_USAGE, slots);
        staging = stagingArena.allocate(64L * 64 * 4, 16);
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
        long bytes = 0;
        int from = fresh ? 0 : Math.min(list.dirtyFrom(), size);
        int to = fresh ? size : Math.min(list.dirtyTo(), size);
        if (to > from) {
            long offset = (long) from * Primitive.BYTES;
            long length = (long) (to - from) * Primitive.BYTES;
            primitives[i].put(offset, words(i, list.words()), offset, length);
            bytes += length;
        }
        if (clipsFresh || list.clipsDirty()) {
            clips[i].put(0, clips(i, list.clips()), 0, clipBytes);
            bytes += clipBytes;
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

    @AllowAllocation("more layers than ever before")
    private void growLayers(int count) {
        lists = Arrays.copyOf(lists, count);
        primitives = Arrays.copyOf(primitives, count);
        clips = Arrays.copyOf(clips, count);
        counts = Arrays.copyOf(counts, count);
        plannedVersion = Arrays.copyOf(plannedVersion, count);
        plannedFor = Arrays.copyOf(plannedFor, count);
        drawCounts = Arrays.copyOf(drawCounts, count);
        wrappedWords = Arrays.copyOf(wrappedWords, count);
        wordSegments = Arrays.copyOf(wordSegments, count);
        wrappedClips = Arrays.copyOf(wrappedClips, count);
        clipSegments = Arrays.copyOf(clipSegments, count);
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
        if (table.size() < (id + 1) * 4L) {
            FrameBuffer bigger = FrameBuffer.create(device, Math.max((id + 1) * 4L, table.size() * 2), BUFFER_USAGE,
                slots);
            bigger.copyFrom(table);
            retiring.add(table);
            table = bigger;
        }
        table.putInt(id * 4L, slotOf[id]);
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
        g.pass("ui").attach(image, Clear.color(0, 0, 0, 0)).record(ctx -> draw(ctx, push, imageWidth, imageHeight));
        g.provide(Keys.UI, image);
    }

    /** Draws the layers submitted last, back to front. */
    private void draw(PassContext ctx, UiPush push, int imageWidth, int imageHeight) {
        if (width <= 0 || height <= 0) {
            return;
        }
        push.textures(table.deviceAddress()).scaleX((float) width / imageWidth).scaleY((float) height / imageHeight)
            .toClipX(2f / width).toClipY(2f / height);
        for (int i = 0; i < layerCount; i++) {
            if (counts[i] == 0) {
                continue;
            }
            push.primitives(primitives[i].deviceAddress()).clips(clips[i].deviceAddress());
            int[] planned = draws[i];
            for (int d = 0; d < drawCounts[i]; d++) {
                int pipeline = planned[d * 3 + 2];
                ctx.draw(pipeline == 0 ? uber : kinds[pipeline], push.first(planned[d * 3]), 6 * planned[d * 3 + 1],
                    1);
            }
        }
    }

    // ------------------------------------------------------------------------------------------------- statistics

    /** The bytes the last {@link #submit} uploaded: primitives, clips and texture regions. 0 when nothing changed. */
    public long uploadedBytes() {
        return uploaded;
    }

    /** The bytes uploaded since this renderer was made. */
    public long totalUploadedBytes() {
        return totalUploaded;
    }

    /** The draws the next frame records: one per layer by the uber shader, more per kind or adaptively. */
    public int draws() {
        int sum = 0;
        for (int i = 0; i < layerCount; i++) {
            sum += drawCounts[i];
        }
        return sum;
    }

    /** The bindless sampled slot of UI texture {@code id}, or -1 if it has none (yet). */
    public int slot(int id) {
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
