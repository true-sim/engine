package com.truesim.engine.ui;

import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_COMPARE_OP_GREATER_OR_EQUAL;
import static org.lwjgl.vulkan.VK10.VK_COMPARE_OP_LESS;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_FRAGMENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_VERTEX_BIT;
import static org.lwjgl.vulkan.VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.graph.Id;
import com.truesim.engine.graph.Anchor;
import com.truesim.engine.graph.FrameGraph;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Image;
import com.truesim.engine.graph.Key;
import com.truesim.engine.graph.Load;
import com.truesim.engine.graph.Order;
import com.truesim.engine.graph.PassContext;
import com.truesim.engine.graph.Store;
import com.truesim.engine.graph.Technique;
import com.truesim.engine.render.Camera;
import com.truesim.engine.render.Keys;
import com.truesim.engine.ui.layout.GizmoPush;
import com.truesim.engine.vulkan.FrameBuffer;
import com.truesim.engine.vulkan.Pipeline;
import com.truesim.engine.vulkan.Retiring;
import com.truesim.engine.vulkan.ShaderModule;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.ui.gizmo.Gizmo;
import com.truesim.ui.gizmo.GizmoMesh;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Draws {@link Gizmo}s as true 3D geometry in a 3D view (design 8, XR): each gizmo's handles as ui-gizmo builds them
 * ({@link Gizmo#mesh}: shafts, cones, cubes, tube rings, plane squares and balls in world units, sized by angular size
 * from the head), drawn after the opaque scene, depth tested by the hardware against the scene's depth of the frame
 * being drawn: where the scene is in front at the {@code gizmo-occluded} opacity, where it is behind at full opacity
 * (two draws with complementary depth tests, the depth read only). Made with a camera per eye, in a view whose scene
 * colour and depth have a layer per eye, it draws every eye in one multiview pass, each through its own matrix; with
 * one camera, a flat view. Positions are relative to each gizmo's origin and the matrices are made in doubles relative
 * to each camera, so nothing jitters far from the world's origin.
 *
 * <pre>{@code
 * GizmoRenderer gizmos = new GizmoRenderer(graph, leftEye, rightEye);
 * stereoView.add(gizmos);
 * gizmos.add(gizmo.head(hx, hy, hz));    // an XR gizmo: sized by angular size from the head
 * // each frame, after the UI updated, before the frame records:
 * gizmos.submit();
 * }</pre>
 *
 * <p>Where the UI's own overlay draws gizmos ({@code Gizmo.paint}: projected handles in a screen layer), a gizmo drawn
 * here should be made with no view (an XR gizmo) so it is drawn once. Allocates nothing per frame once its buffers grew
 * to the largest gizmo.
 */
public final class GizmoRenderer implements Technique, AutoCloseable {

    /** The id of a view's 3D gizmo renderer. */
    public static final Id<GizmoRenderer> ID = Id.of("truesim.ui.gizmos3d", GizmoRenderer.class);
    /** Floats per view in the views buffer: the matrix (16), the eye relative to the gizmo's origin (3), a spare. */
    public static final int VIEW_FLOATS = 20;

    private static final int USAGE = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;
    private static final ValueLayout.OfFloat F32 = ValueLayout.JAVA_FLOAT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    /** One gizmo: its mesh and buffers. */
    private static final class Entry {
        final Gizmo gizmo;
        final GizmoMesh mesh = new GizmoMesh();
        final FrameBuffer views;
        FrameBuffer vertices;
        int count;

        Entry(Gizmo gizmo, FrameBuffer views) {
            this.gizmo = gizmo;
            this.views = views;
        }
    }

    private final FrameGraph graph;
    private final VulkanDevice device;
    private final Camera[] cameras;
    private final List<Entry> entries = new ArrayList<>();
    private final Retiring retiring;
    private final Arena arena = Arena.ofShared();
    private final MemorySegment viewBytes;
    private final double[] m = new double[16];
    private Pipeline visible;
    private Pipeline hidden;
    private int builtColor;
    private int builtDepth;
    private int builtViews;
    private boolean closed;

    /**
     * A renderer of gizmos in a view of {@code graph} with a layer per camera of {@code eyes} (one: a flat view; a
     * headset's two eyes, left first: a stereo view whose scene colour and depth have two layers).
     */
    @AllowAllocation("made once")
    public GizmoRenderer(FrameGraph graph, Camera... eyes) {
        this.graph = Objects.requireNonNull(graph, "graph");
        this.device = graph.device();
        if (eyes.length < 1 || eyes.length > GraphBuilder.MAX_VIEWS) {
            throw new IllegalArgumentException("A view has 1 to " + GraphBuilder.MAX_VIEWS + " eyes: " + eyes.length);
        }
        cameras = eyes.clone();
        for (Camera eye : cameras) {
            Objects.requireNonNull(eye, "eye");
        }
        viewBytes = arena.allocate(4L * VIEW_FLOATS * cameras.length, 16);
        retiring = new Retiring(graph.frameSlots());
    }

    /** Draws {@code gizmo}; returns this. */
    @AllowAllocation("gizmos are added when an editor opens")
    public GizmoRenderer add(Gizmo gizmo) {
        Objects.requireNonNull(gizmo, "gizmo");
        if (find(gizmo) != null) {
            throw new IllegalArgumentException("The gizmo is drawn already");
        }
        entries.add(new Entry(gizmo, FrameBuffer.create(device, 4L * VIEW_FLOATS * cameras.length, USAGE,
            graph.frameSlots())));
        return this;
    }

    /** Stops drawing {@code gizmo}; its buffers are freed once no frame in flight uses them. */
    @AllowAllocation("gizmos are removed rarely")
    public void remove(Gizmo gizmo) {
        Entry e = find(gizmo);
        if (e != null) {
            entries.remove(e);
            retire(e);
        }
    }

    private Entry find(Gizmo gizmo) {
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).gizmo == gizmo) {
                return entries.get(i);
            }
        }
        return null;
    }

    @AllowAllocation("freeing")
    private void retire(Entry e) {
        if (e.vertices != null) {
            retiring.add(e.vertices);
        }
        retiring.add(e.views);
        e.mesh.close();
    }

    /** How many gizmos it draws. */
    public int count() {
        return entries.size();
    }

    /** The vertices gizmo {@code gizmo} drew in the last submitted frame (0: nothing shown). */
    public int vertices(Gizmo gizmo) {
        Entry e = find(gizmo);
        return e == null ? 0 : e.count;
    }

    /**
     * Takes each gizmo's handles of this frame (call after the UI updated, before the frame records): builds their
     * geometry and uploads it.
     */
    public void submit() {
        retiring.release();
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            e.count = e.gizmo.mesh(e.mesh) ? e.mesh.vertexCount() : 0;
            if (e.count == 0) {
                continue;
            }
            if (e.vertices == null || e.vertices.size() < e.mesh.bytes()) {
                grow(e);
            }
            e.vertices.put(0, e.mesh.vertices(), 0, e.mesh.bytes());
        }
    }

    @AllowAllocation("grows to the largest gizmo, then never")
    private void grow(Entry e) {
        if (e.vertices != null) {
            retiring.add(e.vertices);
        }
        e.vertices = FrameBuffer.create(device, Math.max(4096, Long.highestOneBit(e.mesh.bytes()) * 2), USAGE,
            graph.frameSlots());
    }

    // ------------------------------------------------------------------------------------------------------ the pass

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
        return List.of(Keys.SCENE_COLOR);
    }

    @Override
    public List<Key<?>> reads() {
        return List.of(Keys.SCENE_DEPTH);
    }

    @Override
    public Order order() {
        return Order.at(Anchor.TRANSPARENT).after(WorldPanelRenderer.ID); // handles over the panels they move
    }

    @Override
    @AllowAllocation("building the graph, when it changes")
    public void build(GraphBuilder g) {
        Image color = g.modify(Keys.SCENE_COLOR);
        Image depth = g.read(Keys.SCENE_DEPTH);
        int views = color.desc().layers();
        if (views != cameras.length) {
            throw new IllegalStateException("The view's scene colour has " + views + " layers for " + cameras.length
                + " cameras: a layer per eye");
        }
        pipelines(color.format().vk(), depth.format().vk(), views);
        GizmoPush push = new GizmoPush();
        g.pass("gizmos").attach(color, Load.LOAD, Store.STORE).attachDepthReadOnly(depth).region()
            .record(ctx -> draw(ctx, push));
    }

    @AllowAllocation("building the graph, when its formats change")
    private void pipelines(int colorFormat, int depthFormat, int views) {
        if (visible != null && builtColor == colorFormat && builtDepth == depthFormat && builtViews == views) {
            return;
        }
        if (visible != null) {
            retiring.add(visible);
            retiring.add(hidden);
        }
        try (ShaderModule shaders = ShaderModule.load(device, "shaders/ui/gizmo.spv")) {
            // reverse depth: the scene is in front where its depth is greater
            visible = pipeline(shaders, colorFormat, depthFormat, views, VK_COMPARE_OP_GREATER_OR_EQUAL);
            hidden = pipeline(shaders, colorFormat, depthFormat, views, VK_COMPARE_OP_LESS);
        }
        builtColor = colorFormat;
        builtDepth = depthFormat;
        builtViews = views;
    }

    private Pipeline pipeline(ShaderModule shaders, int colorFormat, int depthFormat, int views, int compare) {
        return Pipeline.graphics(device).stage(VK_SHADER_STAGE_VERTEX_BIT, shaders, "gizmoVertexMain")
            .stage(VK_SHADER_STAGE_FRAGMENT_BIT, shaders, "gizmoFragmentMain").color(colorFormat).depth(depthFormat)
            .depthTest(compare, false).blend(0, Pipeline.Blend.PREMULTIPLIED).pushConstants(GizmoPush.BYTES)
            .viewMask(views > 1 ? (1 << views) - 1 : 0).build(); // back faces culled: each part covers a pixel once
    }

    private void draw(PassContext ctx, GizmoPush push) {
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            if (e.count == 0) {
                continue;
            }
            for (int v = 0; v < cameras.length; v++) {
                view(cameras[v], e.mesh, v);
            }
            e.views.put(0, viewBytes, 0, 4L * VIEW_FLOATS * cameras.length);
            push.vertices(e.vertices.deviceAddress()).views(e.views.deviceAddress())
                .vertexFloats(GizmoMesh.VERTEX_BYTES / 4).viewFloats(VIEW_FLOATS);
            push.opacity(e.mesh.occludedOpacity());
            ctx.draw(hidden, push, e.count, 1);
            push.opacity(1);
            ctx.draw(visible, push, e.count, 1);
        }
    }

    /**
     * Writes view {@code v}'s entry: the matrix from positions relative to {@code mesh}'s origin to clip space (in
     * doubles relative to {@code camera}, through its view-projection), and the eye relative to the origin.
     */
    private void view(Camera camera, GizmoMesh mesh, int v) {
        double tx = mesh.originX() - camera.position(0);
        double ty = mesh.originY() - camera.position(1);
        double tz = mesh.originZ() - camera.position(2);
        float[] vp = camera.viewProjection();
        for (int row = 0; row < 4; row++) {
            m[row] = vp[row];
            m[4 + row] = vp[4 + row];
            m[8 + row] = vp[8 + row];
            m[12 + row] = vp[row] * tx + vp[4 + row] * ty + vp[8 + row] * tz + vp[12 + row];
        }
        long base = 4L * VIEW_FLOATS * v;
        for (int k = 0; k < 16; k++) {
            viewBytes.set(F32, base + 4L * k, (float) m[k]);
        }
        viewBytes.set(F32, base + 64, (float) (camera.position(0) + camera.eye(0) - mesh.originX()));
        viewBytes.set(F32, base + 68, (float) (camera.position(1) + camera.eye(1) - mesh.originY()));
        viewBytes.set(F32, base + 72, (float) (camera.position(2) + camera.eye(2) - mesh.originZ()));
        viewBytes.set(F32, base + 76, 0);
    }

    /** Frees its buffers and pipelines: call once the GPU is done with the frames. */
    @Override
    @AllowAllocation("closing")
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        for (Entry e : entries) {
            if (e.vertices != null) {
                e.vertices.close();
            }
            e.views.close();
            e.mesh.close();
        }
        entries.clear();
        if (visible != null) {
            visible.close();
            hidden.close();
        }
        retiring.close();
        arena.close();
    }
}
