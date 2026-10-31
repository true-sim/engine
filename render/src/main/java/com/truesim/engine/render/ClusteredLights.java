package com.truesim.engine.render;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.memPutFloat;
import static org.lwjgl.system.MemoryUtil.memPutInt;
import static org.lwjgl.system.MemoryUtil.memPutLong;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_SHADER_STORAGE_READ_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_TRANSFER_WRITE_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_TRANSFER_BIT;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.math.Maths;
import com.truesim.engine.graph.Buffer;
import com.truesim.engine.graph.BufferAccess;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Key;
import com.truesim.engine.graph.PassContext;
import com.truesim.engine.graph.Technique;
import com.truesim.engine.graph.ViewPort;
import com.truesim.engine.render.layout.ClusterPush;
import com.truesim.engine.render.layout.LightsConstants;
import com.truesim.engine.vulkan.Commands;
import com.truesim.engine.vulkan.Barriers;
import com.truesim.engine.vulkan.Pipeline;
import java.util.List;
import java.util.Objects;
import org.lwjgl.system.MemoryStack;

/**
 * Light binning by clusters: provides {@link Keys#LIGHT_LIST}, the {@link Lights} binned per cluster of a {@link
 * SceneView}'s frustum, for every technique shading the view: added to its port, whose size it bins. Tiles of {@code
 * tileSize} pixels across and down, {@code slices} depth slices from {@code near} to {@code far} (exponential: each
 * longer by the same ratio), up to {@code maxPerCluster} lights each; built once per frame on the async compute queue,
 * by a thread per cluster testing the lights' spheres against its box. {@code graph.replace} swaps it for another
 * binning; shading reads any of them through {@code lights.slang}'s {@code ILightList}.
 *
 * <p>The light list is one buffer: the common header ({@code LightListHeader}), a count per cluster, then the slots.
 * Fixed slots per cluster: no atomics, a deterministic order, and overflow reported in the header (the most lights a
 * cluster wanted).
 *
 * <pre>{@code
 * view.port().add(new ClusteredLights(lights, view));   // the view's shading techniques find it by key
 * }</pre>
 */
public final class ClusteredLights implements Technique {

    static final int HEADER_BYTES = 112;
    private static final int CLUSTERS = LightsConstants.CLUSTERS;

    private final Lights lights;
    private final ViewPort port;
    private final Camera camera;
    private final int tileSize;
    private final int slices;
    private final float near;
    private final float far;
    private final int maxPerCluster;

    /**
     * {@code lights} binned for {@code camera} (read when the frame runs: the camera of the view shaded) on
     * {@code port}'s images (their size read when the graph builds); added to {@code port}.
     */
    public ClusteredLights(Lights lights, ViewPort port, Camera camera, int tileSize, int slices, float near,
        float far, int maxPerCluster) {
        this.lights = Objects.requireNonNull(lights, "lights");
        this.port = Objects.requireNonNull(port, "port");
        this.camera = Objects.requireNonNull(camera, "camera");
        if (tileSize < 1 || slices < 1 || !(near > 0) || !(far > near) || maxPerCluster < 1) {
            throw new IllegalArgumentException("Clusters need tiles, slices, 0 < near < far and room for lights: "
                + tileSize + ", " + slices + ", " + near + ", " + far + ", " + maxPerCluster);
        }
        this.tileSize = tileSize;
        this.slices = slices;
        this.near = near;
        this.far = far;
        this.maxPerCluster = maxPerCluster;
    }

    /** Tiles of 64 pixels, 24 slices from 0.1 to 1,000 units, up to 128 lights per cluster. */
    public ClusteredLights(Lights lights, ViewPort port, Camera camera) {
        this(lights, port, camera, 64, 24, 0.1f, 1000, 128);
    }

    /** {@code lights} binned for {@code view}'s camera on its port: tiles of 64 pixels, 24 slices, ... as above. */
    public ClusteredLights(Lights lights, SceneView view) {
        this(lights, view.port(), view.camera());
    }

    /** Tiles across the view's port, at its size now. */
    public int tilesAcross() {
        return (port.width() + tileSize - 1) / tileSize;
    }

    /** Tiles down the view's port, at its size now. */
    public int tilesDown() {
        return (port.height() + tileSize - 1) / tileSize;
    }

    /** Clusters of the view's port, at its size now. */
    public int clusters() {
        return clusters(tilesAcross(), tilesDown());
    }

    private int clusters(int across, int down) {
        return across * down * slices;
    }

    public int maxPerCluster() {
        return maxPerCluster;
    }

    @Override
    public List<Key<?>> provides() {
        return List.of(Keys.LIGHT_LIST);
    }

    @Override
    @AllowAllocation("building the graph, when it changes")
    public void build(GraphBuilder g) {
        if (g.view() != port) {
            throw new IllegalStateException("ClusteredLights of " + port + " was added to "
                + (g.view() == null ? "the frame" : g.view()) + ": add it to its view");
        }
        int across = tilesAcross();
        int down = tilesDown();
        Pipeline pipeline = g.computeProgram("shaders/lights/cluster.spv", "clusterMain", ClusterPush.BYTES);
        ClusterPush push = new ClusterPush().width(port.width()).height(port.height()).far(far);
        Buffer list = g.createBuffer("light clusters",
            HEADER_BYTES + 4L * clusters(across, down) * (1 + maxPerCluster));
        g.pass("light clusters")
            .write(list, BufferAccess.TRANSFER_DST)
            .write(list, BufferAccess.STORAGE_WRITE)
            .async()
            .record(ctx -> cluster(ctx, pipeline, list, push, across, down));
        g.provide(Keys.LIGHT_LIST, list);
    }

    private void cluster(PassContext ctx, Pipeline pipeline, Buffer list, ClusterPush push, int across, int down) {
        int clusters = clusters(across, down);
        long header = ctx.address(list);
        long counts = header + HEADER_BYTES;
        try (MemoryStack stack = stackPush()) {
            long data = stack.ncalloc(8, 1, HEADER_BYTES);
            memPutInt(data, CLUSTERS);
            memPutInt(data + 4, lights.count());
            memPutInt(data + 8, 0); // overflow: the most lights a cluster wanted
            memPutLong(data + 16, lights.lightsAddress());
            memPutLong(data + 24, lights.shadowTable(port).address()); // its view's entries
            memPutLong(data + 32, counts);
            memPutLong(data + 40, counts + 4L * clusters);
            memPutInt(data + 48, across);
            memPutInt(data + 52, down);
            memPutInt(data + 56, slices);
            memPutInt(data + 60, tileSize);
            memPutFloat(data + 64, near);
            memPutFloat(data + 68, (float) (slices / Maths.log(far / near)));
            memPutInt(data + 72, maxPerCluster);
            // The camera it shades for: lights and shadow entries are made relative to its world position.
            for (int axis = 0; axis < 3; axis++) {
                double p = camera.position(axis);
                float high = (float) p;
                memPutFloat(data + 80 + 4L * axis, high);
                memPutFloat(data + 96 + 4L * axis, (float) (p - high));
            }
            Commands.updateBuffer(ctx.unsafe().commands(), ctx.unsafe().buffer(list), 0, HEADER_BYTES, data);
            Barriers.memory(ctx.unsafe().commands(), VK_PIPELINE_STAGE_2_TRANSFER_BIT, VK_ACCESS_2_TRANSFER_WRITE_BIT,
                VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
                VK_ACCESS_2_SHADER_STORAGE_READ_BIT | VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT);
            ctx.dispatch(pipeline, push.viewProjection(camera.viewProjection()).header(header).lightCount(lights.count()),
                (clusters + 63) / 64);
        }
    }
}
