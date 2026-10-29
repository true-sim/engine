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
    private final ViewOptions options;
    private final int index;
    private final FrameBuffer view;
    private final GpuBuffer visible;
    private final GpuBuffer expandCommand;
    private final GpuBuffer drawCommand;
    private final GpuBuffer drawCount; // the early (or only) phase's, then the late one's
    private final GpuBuffer visibility; // per instance, 1 if visible the last time the late phase looked; or null
    private final VulkanDevice device;
    private GpuBuffer frameIndices; // the indexed path's, made when it first draws
    private final double[] position = new double[3];
    int phase;
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
        visible = GpuBuffer.deviceLocal(device, 16L * options.draws(), storage);
        expandCommand = GpuBuffer.deviceLocal(device, 16, commandUsage);
        drawCommand = GpuBuffer.deviceLocal(device, 24, commandUsage);
        drawCount = GpuBuffer.hostVisible(device, 8, commandUsage);
        int instances = renderer.scene().capacity();
        visibility = options.occlusion()
            ? GpuBuffer.deviceLocal(device, 4L * instances, storage | VK_BUFFER_USAGE_TRANSFER_DST_BIT) : null;
        if (visibility != null) {
            device.submitAndWait(device.graphics(), commands -> vkCmdFillBuffer(commands, visibility.handle(), 0,
                visibility.size(), 0)); // nothing visible yet: the first late phase finds out
        }
        view.putInt(172, options.draws());
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
     * How many meshlets the view found visible in the last finished frame (on the indexed path, more than the draw
     * limit means some were dropped). Read it once the frame's commands have completed.
     */
    public int visibleMeshlets() {
        return drawCount.memory().get(U32, 0) + drawCount.memory().get(U32, 4);
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

    /** The indexed path's index buffer, made the first time it is needed (rarely: the path changed). */
    @AllowAllocation("the indexed path drawing the view for the first time")
    GpuBuffer frameIndices() {
        if (frameIndices == null) {
            frameIndices = GpuBuffer.deviceLocal(device, 12L * options.triangles(),
                VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT
                    | VK_BUFFER_USAGE_INDEX_BUFFER_BIT);
        }
        return frameIndices;
    }

    boolean hasHistory() {
        return visibility != null;
    }

    private void checkOpen() {
        if (closed) {
            throw new IllegalStateException("The view is closed");
        }
    }

    /** Frees the view's buffers: the renderer closes its views as it closes. */
    @Override
    public void close() {
        if (!closed) {
            closed = true;
            view.close();
            visible.close();
            expandCommand.close();
            drawCommand.close();
            drawCount.close();
            if (visibility != null) {
                visibility.close();
            }
            if (frameIndices != null) {
                frameIndices.close();
            }
        }
    }
}
