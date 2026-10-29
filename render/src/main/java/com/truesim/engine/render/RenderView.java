package com.truesim.engine.render;

import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_INDEX_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.vkCmdFillBuffer;
import static org.lwjgl.vulkan.VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.vulkan.FrameBuffer;
import com.truesim.engine.vulkan.FrameSlots;
import com.truesim.engine.vulkan.GpuBuffer;
import com.truesim.engine.vulkan.Retiring;
import com.truesim.engine.vulkan.VulkanDevice;
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
     * The shaders' View, 352 bytes: matrices and planes, the error and mode, the camera's world position (two floats
     * per axis), its filter, then the lists' and the occlusion history's addresses.
     */
    static final int BYTES = 352;
    private static final ValueLayout.OfInt U32 = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    private final MeshletRenderer renderer;
    private ViewOptions options;
    private final int index;
    private final FrameBuffer view;
    private GpuBuffer visible;
    private final GpuBuffer expandCommand;
    private final GpuBuffer drawCommand;
    private final GpuBuffer drawCount; // the early (or only) phase's, then the late one's
    private final GpuBuffer visibility; // per instance, 1 if visible the last time the late phase looked; or null
    private final VulkanDevice device;
    private GpuBuffer frameIndices; // the indexed path's, made when it first draws
    private final double[] position = new double[3];
    final RenderView[] alone = {this}; // to cull the view by itself; never written
    boolean marked; // while a cull checks its views
    private boolean closed;

    RenderView(VulkanDevice device, MeshletRenderer renderer, ViewOptions options, int index, FrameSlots slots) {
        this.device = device;
        this.renderer = renderer;
        this.options = Objects.requireNonNull(options, "options");
        this.index = index;
        int storage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;
        int commandUsage = storage | VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT;
        view = FrameBuffer.create(device, BYTES, storage, slots);
        visible = GpuBuffer.deviceLocal(device, 16L * options.allDraws(), storage); // the bins, one after another
        expandCommand = GpuBuffer.deviceLocal(device, 16L * MeshletRenderer.BINS, commandUsage);
        drawCommand = GpuBuffer.deviceLocal(device, 24L * MeshletRenderer.BINS, commandUsage);
        // Per bin the early and late counts, then per bin the most indices a phase wanted.
        drawCount = GpuBuffer.hostVisible(device, 48, commandUsage);
        int instances = renderer.scene().capacity();
        visibility = options.occlusion()
            ? GpuBuffer.deviceLocal(device, 4L * instances, storage | VK_BUFFER_USAGE_TRANSFER_DST_BIT) : null;
        if (visibility != null) {
            device.submitAndWait(device.graphics(), commands -> vkCmdFillBuffer(commands, visibility.handle(), 0,
                visibility.size(), 0)); // nothing visible yet: the first late phase finds out
        }
        view.putInt(172, options.draws());
        view.putInt(188, options.binDraws());
        filter(options.layers(), options.castersOnly());
        view.putLong(304, visible.deviceAddress());
        view.putLong(312, expandCommand.deviceAddress());
        view.putLong(320, drawCommand.deviceAddress());
        view.putLong(328, drawCount.deviceAddress());
        view.putLong(336, visibility == null ? 0 : visibility.deviceAddress());
    }

    /**
     * Sets the view's camera: its matrices and world position (instances are drawn relative to it), the error allowed
     * on screen in pixels (detail is chosen per meshlet so that simplification moves no surface by more) and the
     * shading {@code mode} ({@link MeshletRenderer#MODE_LIT}, ...). Allocates nothing.
     */
    public RenderView set(Camera camera, float errorPixels, int mode) {
        checkOpen();
        FrameBuffer m = view;
        float[] matrix = camera.viewProjection();
        for (int i = 0; i < 16; i++) {
            m.putFloat(4L * i, matrix[i]);
        }
        float[] planes = camera.planes();
        for (int i = 0; i < 24; i++) {
            m.putFloat(64 + 4L * i, planes[i]);
        }
        m.putFloat(160, camera.projectionScale());
        m.putFloat(164, errorPixels);
        m.putInt(176, mode);
        m.putInt(180, camera.orthographic() ? 1 : 0);
        float[] inverse = camera.inverseViewProjection();
        for (int i = 0; i < 16; i++) {
            m.putFloat(192 + 4L * i, inverse[i]);
        }
        // The camera's world position, two floats per axis as instances keep theirs, and the eye off it.
        for (int axis = 0; axis < 3; axis++) {
            double p = camera.position(axis);
            position[axis] = p;
            float high = (float) p;
            m.putFloat(256 + 4L * axis, high);
            m.putFloat(272 + 4L * axis, (float) (p - high));
            m.putFloat(288 + 4L * axis, (float) camera.eye(axis));
        }
        return this;
    }

    /**
     * Which instances the view draws: those on any layer of {@code layers}, and with {@code castersOnly} only those
     * casting shadows (a shadow map's view). From the options at first; kept until set again. Allocates nothing.
     */
    public RenderView filter(int layers, boolean castersOnly) {
        checkOpen();
        view.putInt(284, layers);
        view.putInt(300, castersOnly ? 1 : 0);
        return this;
    }

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

    /** Like {@link #visibleMeshlets()}, of raster bin {@code bin} alone (0 opaque, 1 double-sided, 2 masked). */
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
        return Integer.toUnsignedLong(drawCount.memory().get(U32, 24 + 4L * bin)) / 3;
    }

    /**
     * Whether the last finished frame wanted more than the view's lists hold: more meshlets in a phase of a raster bin
     * than it holds ({@link ViewOptions#draws()}, {@link ViewOptions#binDraws()}), or on the indexed path more
     * triangles. What did not fit was not drawn; {@link #resize} the view.
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
            view.putLong(304, visible.deviceAddress());
        }
        view.putInt(172, draws);
        view.putInt(188, binDraws);
        boolean indexed = frameIndices != null;
        options = resized;
        if (indexed && frameIndices.size() != 12L * resized.allTriangles()) {
            dropIndices();
            frameIndices();
        }
        return this;
    }

    /** The GPU memory the view's lists, commands and history take, in bytes. */
    public long bytes() {
        return view.size() * view.slots().count() + visible.size() + expandCommand.size() + drawCommand.size()
            + drawCount.size() + (visibility == null ? 0 : visibility.size())
            + (frameIndices == null ? 0 : frameIndices.size());
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

    /** Its place in the renderer's table of views. */
    public int index() {
        return index;
    }

    /** The shaders' View, for passes drawing it. */
    long address() {
        return view.deviceAddress();
    }

    FrameBuffer viewBuffer() {
        return view;
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

    /** Lets the indexed path's index buffer go, once no frame in flight reads it: the path is mesh shaders now. */
    synchronized void dropIndices() {
        if (frameIndices != null) {
            renderer.retire(frameIndices);
            frameIndices = null;
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
        }
        return frameIndices;
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
        for (AutoCloseable buffer : new AutoCloseable[] {view, visible, expandCommand, drawCommand, drawCount,
            visibility, frameIndices}) {
            if (buffer == null) {
                continue;
            }
            retiring.add(buffer);
        }
        frameIndices = null;
    }
}
