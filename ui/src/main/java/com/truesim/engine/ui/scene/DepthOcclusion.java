package com.truesim.engine.ui.scene;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.graph.Id;
import com.truesim.engine.graph.Access;
import com.truesim.engine.graph.Anchor;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Image;
import com.truesim.engine.graph.Key;
import com.truesim.engine.graph.Order;
import com.truesim.engine.graph.Subresource;
import com.truesim.engine.graph.Technique;
import com.truesim.engine.render.Camera;
import com.truesim.engine.render.Keys;
import com.truesim.engine.render.blocks.DepthPyramid;
import com.truesim.engine.settings.Setting;
import com.truesim.ui.gizmo.GizmoOcclusion;
import com.truesim.ui.scene.SceneView;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.List;
import java.util.Objects;

/**
 * Gizmo occlusion from the view's depth buffer (design UI26: hidden parts of a gizmo are faded, never hidden): a
 * technique of the main view reads back one level of the scene depth's farthest-depth pyramid ({@link DepthPyramid},
 * shared with occlusion culling where it builds the same) with the camera it was drawn through, and {@link #occluded}
 * projects a gizmo's point through that camera and compares the point's depth with the farthest surface over its
 * block: hidden when it lies behind all of it. Per surface rather than by boxes ({@code RayOcclusion}), conservative at
 * silhouettes (a block holding sky or a far surface lets the point show).
 *
 * <p><b>Latency.</b> The answer comes from the newest finished frame's depth: one frame or more old (the frames in
 * flight), never waited for. While the camera or the scene moves, a part's fade follows a frame or two late; a moving
 * camera is accounted for (the point is projected through the camera that drew that depth), a moving occluder is not.
 * Before the first readback nothing is hidden. The exact, same-frame alternative would test each gizmo fragment against
 * the depth on the GPU, which needs the UI's primitives to carry a depth (a draw-list change), not built.
 *
 * <p>Costs: the pyramid (shared), a copy of one level ({@link #LEVEL}: level 1 of a 1080p view is 480 x 270, 518 KB) and
 * projecting a few hundred points per gizmo recording. Allocates nothing per frame.
 *
 * <pre>{@code
 * DepthOcclusion occlusion = new DepthOcclusion(camera);
 * mainView.add(occlusion);
 * gizmo.occlusion(occlusion);
 * }</pre>
 */
public final class DepthOcclusion implements GizmoOcclusion, Technique {

    /** The id of a view's gizmo depth occlusion. */
    public static final Id<DepthOcclusion> ID = Id.of("truesim.ui.gizmoDepthOcclusion", DepthOcclusion.class);
    /** The pyramid level read back: 0 is half the view's size, each further one half again. */
    public static final Setting<Integer> LEVEL = Setting.integer("ui.gizmo.depthLevel", 1, 0, 6,
        "the depth pyramid level gizmo occlusion reads back: 0 is half the view's size, each one more half again");

    private static final ValueLayout.OfFloat FLOAT = ValueLayout.JAVA_FLOAT_UNALIGNED;
    private static final int RING = 8;

    private final Camera camera;
    private final int level;
    private final double depthBias;
    // the camera of each frame recorded (by frame % RING): its view-projection and position
    private final float[][] recordedMatrix = new float[RING][16];
    private final double[][] recordedPosition = new double[RING][3];
    private final long[] recordedFrame = new long[RING];
    private final int[] recordedWidth = new int[RING];
    private final int[] recordedHeight = new int[RING];
    // the newest depth received and the camera it was drawn through (written on the render thread, read by the UI)
    private float[] depths = new float[0];
    private int width;
    private int height;
    private int regionWidth;
    private int regionHeight;
    private final float[] matrix = new float[16];
    private final double[] position = new double[3];
    private long received = -1;
    private final com.truesim.engine.graph.Readback receiver = this::receive;

    /** Occlusion of the view drawn through {@code viewCamera}, reading back level {@code pyramidLevel}. */
    @AllowAllocation("one per view")
    public DepthOcclusion(Camera viewCamera, int pyramidLevel) {
        this.camera = Objects.requireNonNull(viewCamera, "viewCamera");
        if (pyramidLevel < 0) {
            throw new IllegalArgumentException("level " + pyramidLevel);
        }
        this.level = pyramidLevel;
        this.depthBias = 1e-6;
    }

    /** Occlusion at level {@link #LEVEL}'s default. */
    public DepthOcclusion(Camera viewCamera) {
        this(viewCamera, LEVEL.defaultValue());
    }

    /** The frame whose depth answers now, or -1 before the first. */
    public long frame() {
        return received;
    }

    // ---------------------------------------------------------------- the technique

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
    public List<Key<?>> reads() {
        return List.of(Keys.SCENE_DEPTH);
    }

    @Override
    public Order order() {
        return Order.at(Anchor.TRANSPARENT);
    }

    @Override
    @AllowAllocation("building the graph, when it changes")
    public void build(GraphBuilder g) {
        g.modify(Keys.SCENE_COLOR);
        Image depth = g.read(Keys.SCENE_DEPTH);
        Image pyramid = g.request(new DepthPyramid(depth, g.view()));
        int mip = Math.min(level, pyramid.mips() - 1);
        Subresource part = pyramid.mip(mip);
        int levelWidth = Math.max(1, pyramid.width() >> mip);
        int levelHeight = Math.max(1, pyramid.height() >> mip);
        g.pass("gizmo occlusion camera").read(part, Access.TRANSFER_SRC).sideEffects()
            .record(ctx -> snapshot(ctx.frame(), DepthPyramid.levelSize(ctx.regionWidth(depth), mip),
                DepthPyramid.levelSize(ctx.regionHeight(depth), mip), levelWidth, levelHeight));
        g.readback("gizmo occlusion depth", part, receiver);
    }

    /** Keeps the camera frame {@code frame} draws through, and the region of the level read back. */
    private synchronized void snapshot(long frame, int region, int regionH, int levelWidth, int levelHeight) {
        int r = (int) (frame % RING);
        System.arraycopy(camera.viewProjection(), 0, recordedMatrix[r], 0, 16);
        for (int a = 0; a < 3; a++) {
            recordedPosition[r][a] = camera.position(a);
        }
        recordedFrame[r] = frame;
        recordedWidth[r] = Math.min(region, levelWidth);
        recordedHeight[r] = Math.min(regionH, levelHeight);
        width = levelWidth;
        height = levelHeight;
    }

    /** The depth of frame {@code frame}, finished: kept with the camera that drew it. */
    private synchronized void receive(MemorySegment data, long frame) {
        int r = (int) (frame % RING);
        if (recordedFrame[r] != frame) {
            return; // no camera kept for it (the ring wrapped)
        }
        int n = (int) Math.min(data.byteSize() / 4, (long) width * height);
        if (depths.length < n) {
            depths = new float[n]; // the view grew: rare
        }
        MemorySegment.copy(data, FLOAT, 0, depths, 0, n);
        System.arraycopy(recordedMatrix[r], 0, matrix, 0, 16);
        System.arraycopy(recordedPosition[r], 0, position, 0, 3);
        regionWidth = recordedWidth[r];
        regionHeight = recordedHeight[r];
        received = frame;
    }

    // ---------------------------------------------------------------- the answer

    @Override
    public synchronized boolean occluded(SceneView view, double x, double y, double z) {
        if (received < 0 || regionWidth <= 0) {
            return false;
        }
        double rx = x - position[0];
        double ry = y - position[1];
        double rz = z - position[2];
        float[] m = matrix;
        double cx = m[0] * rx + m[4] * ry + m[8] * rz + m[12];
        double cy = m[1] * rx + m[5] * ry + m[9] * rz + m[13];
        double cz = m[2] * rx + m[6] * ry + m[10] * rz + m[14];
        double cw = m[3] * rx + m[7] * ry + m[11] * rz + m[15];
        if (cw <= 0) {
            return false; // behind the camera: not drawn anyway
        }
        double nx = cx / cw;
        double ny = cy / cw;
        if (nx < -1 || nx > 1 || ny < -1 || ny > 1) {
            return false; // off the depth that was drawn
        }
        int bx = Math.min(regionWidth - 1, (int) ((nx + 1) / 2 * regionWidth));
        int by = Math.min(regionHeight - 1, (int) ((ny + 1) / 2 * regionHeight));
        float farthest = depths[by * width + bx];
        double depth = cz / cw; // reverse depth: larger is nearer
        return depth < farthest - depthBias;
    }
}
