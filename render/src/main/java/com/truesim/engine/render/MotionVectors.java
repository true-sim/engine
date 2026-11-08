package com.truesim.engine.render;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.graph.Access;
import com.truesim.engine.graph.Buffer;
import com.truesim.engine.graph.BufferAccess;
import com.truesim.engine.graph.BufferHistory;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Image;
import com.truesim.engine.graph.ImageDesc;
import com.truesim.engine.graph.Key;
import com.truesim.engine.graph.Request;
import com.truesim.engine.graph.Technique;
import com.truesim.engine.render.layout.MotionPush;
import com.truesim.engine.vulkan.Format;
import com.truesim.engine.vulkan.Pipeline;
import java.util.List;
import java.util.Objects;

/**
 * A view's motion vectors ({@link Keys#MOTION}): for each pixel, how far the surface it shows moved on screen since the
 * frame before, for temporal techniques (reprojecting history: denoisers, temporal reuse, antialiasing) in any view,
 * each eye of a headset its own. Exact for what moves rigidly or skinned: the pixel's triangle is found in visibility
 * buffer IDs of the view's camera, and the same point of it is placed as the frame before drew it. The IDs are a
 * {@link SceneDraw} of a render view: the view's own where it is shaded through the visibility buffer (the same
 * request, so drawn once; the same {@link SceneGeometry} object), or, for forward shading (which culls its view
 * itself), a second render view set to the same camera, drawn for them alone (as the
 * {@link com.truesim.engine.render.shadows.Shadows} owner's traced masks draw depth for forward shading).
 *
 * <ul>
 *
 * <ul>
 *   <li>the camera: the frame before's view-projection ({@link RenderView}, relative to this frame's camera, so large
 *   worlds keep their precision) and jitter;
 *   <li>instances: their poses of the frame before, a copy of the instance table kept from frame to frame (a frame's
 *   upload and interpolation done, D91), whatever moved them (interpolation between steps, the CPU, GPU writers);
 *   <li>skinned and morphing copies: their vertices as the frame before drew them, copied aside before each skinning
 *   ({@link Deformation#keepPrevious}).
 * </ul>
 *
 * <p>The motion is in pixels, from the pixel to where its surface was in the frame before's render region: a history
 * rendered then is read at {@code pixel + motion} (two 16-bit floats, {@link #FORMAT}), whatever dynamic resolution
 * made of the two regions' sizes; jitter does not move it. Pixels showing nothing move with
 * the camera's turn (the sky at infinity); an instance new this frame, or drawing another mesh than the frame before,
 * has none of its own; the first frame has none.
 *
 * <p>Costs a pass per view (one thread per pixel, rebuilding its triangle twice), a copy of the instance table per
 * frame (64 bytes an instance, shared by every view), and for forward views an ID draw. Allocates nothing per frame.
 */
public final class MotionVectors implements Technique {

    /** The motion's format: x and y in pixels. */
    public static final Format FORMAT = Format.R16G16_SFLOAT;

    private final SceneGeometry geometry;
    private final SceneView view;
    private final RenderView ids;
    private final MeshletRenderer renderer;
    private final ClassicLodGeometry classic; // null: meshlets
    private final int depthFormat;
    private Boolean occlusion; // or null: as the graph's settings say

    /**
     * {@code view}'s motion vectors, from {@code geometry}'s ID draw of {@code ids} (looking through the view's camera)
     * with depth of {@code depthFormat}: {@code view.view()} and the visibility buffer's depth format share its draw;
     * added to {@code view}'s port.
     */
    public MotionVectors(SceneGeometry geometry, RenderView ids, SceneView view, int depthFormat) {
        this.geometry = Objects.requireNonNull(geometry, "geometry");
        this.view = Objects.requireNonNull(view, "view");
        this.ids = Objects.requireNonNull(ids, "ids");
        MeshletGeometry meshlets = geometry.provider(MeshletGeometry.class);
        this.classic = geometry.provider(ClassicLodGeometry.class);
        if (geometry.providers().size() != 1 || meshlets == null && classic == null) {
            throw new IllegalArgumentException("Motion vectors find one provider's triangles, meshlets or classic LOD");
        }
        this.renderer = meshlets != null ? meshlets.renderer() : classic.renderer();
        this.depthFormat = depthFormat;
    }

    /** Two-phase occlusion culling of its ID draw, as the view's visibility buffer shading has it (to share it). */
    @AllowAllocation("set up rarely")
    public MotionVectors occlusion(boolean on) {
        this.occlusion = on;
        return this;
    }

    @Override
    public List<Key<?>> provides() {
        return List.of(Keys.MOTION);
    }

    /** The instance table's size: the copy kept of it follows it. */
    @Override
    public long buildVersion() {
        return renderer.scene().capacity();
    }

    @Override
    @AllowAllocation("building the graph, when it changes")
    public void build(GraphBuilder g) {
        view.checkBuilding(g, this);
        int width = view.width();
        int height = view.height();
        if (width > 0xffff || height > 0xffff) {
            throw new IllegalStateException("Motion vectors up to 65,535 pixels per side: " + width + " x " + height);
        }
        SceneDraw.Targets ids = g.request(new SceneDraw(geometry, this.ids, null, SurfaceOutput.ids(), depthFormat,
            width, height, (occlusion != null ? occlusion : g.setting(RenderSettings.OCCLUSION))
                && this.ids.options().occlusion()));
        SceneData.Buffers scene = g.request(new SceneData(renderer));
        BufferHistory before = g.request(new InstanceHistory(renderer));
        Deformation deformation = renderer.pool().deformation();
        deformation.keepPrevious(true);
        Pipeline motion = g.computeProgram("shaders/motion/motion.spv", "motionMain", MotionPush.BYTES);
        Image image = g.create("motion", ImageDesc.of(FORMAT, width, height));
        MotionPush push = new MotionPush();
        scene.read(g.pass("motion vectors"), BufferAccess.STORAGE_READ).read(ids.color(), Access.STORAGE_READ)
            .read(before.previous(), BufferAccess.STORAGE_READ).write(image, Access.STORAGE_WRITE)
            .record(ctx -> {
                Buffer previous = ctx.historyValid(before) ? before.previous() : scene.instances();
                int region = ctx.regionWidth(image) | ctx.regionHeight(image) << 16;
                // The region the view rendered the frame before (its images are the view's size)
                int previousSize = view.port().previousRegionWidth() | view.port().previousRegionHeight() << 16;
                ctx.dispatch(motion, push.view(this.ids.address()).scene(renderer.scene().tableAddress())
                    .previous(ctx.address(previous)).deformed(deformation.previousTableAddress())
                    .previousPositions(deformation.previousPositionsAddress())
                    .classicIndices(classic == null ? ctx.absent() : classic.indicesAddress())
                    .ids(ctx.storage(ids.color())).motion(ctx.storage(image))
                    .size(region).previousSize(previousSize).meshCount(deformation.previousTableMeshes()),
                    (ctx.regionWidth(image) + 7) / 8, (ctx.regionHeight(image) + 7) / 8, 1);
            });
        g.provide(Keys.MOTION, image);
    }

    /**
     * The instances as the frame before drew them: a copy of {@code renderer}'s instance table, made each frame once
     * its upload and interpolation are done, kept for the next; shared by every view's motion vectors.
     */
    record InstanceHistory(MeshletRenderer renderer) implements Request<BufferHistory> {

        @Override
        @AllowAllocation("building the graph, when it changes")
        public BufferHistory build(GraphBuilder g) {
            SceneData.Buffers scene = g.request(new SceneData(renderer));
            long bytes = 64L * renderer.scene().capacity();
            BufferHistory history = g.historyBuffer("instances of the frame before", bytes);
            g.pass("instances kept").read(scene.instances(), BufferAccess.TRANSFER_SRC)
                .write(history.current(), BufferAccess.TRANSFER_DST)
                .record(ctx -> ctx.copy(scene.instances(), history.current(),
                    Math.min(bytes, scene.instances().size())));
            return history;
        }
    }
}
