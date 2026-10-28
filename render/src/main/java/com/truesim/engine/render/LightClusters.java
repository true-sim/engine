package com.truesim.engine.render;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.memPutAddress;
import static org.lwjgl.system.MemoryUtil.memPutFloat;
import static org.lwjgl.system.MemoryUtil.memPutInt;
import static org.lwjgl.system.MemoryUtil.memPutLong;
import static org.lwjgl.vulkan.VK10.nvkCmdUpdateBuffer;
import static org.lwjgl.vulkan.VK10.vkCmdDispatch;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_SHADER_STORAGE_READ_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_TRANSFER_WRITE_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_TRANSFER_BIT;
import static org.lwjgl.vulkan.VK13.VK_STRUCTURE_TYPE_DEPENDENCY_INFO;
import static org.lwjgl.vulkan.VK13.VK_STRUCTURE_TYPE_MEMORY_BARRIER_2;
import static org.lwjgl.vulkan.VK13.nvkCmdPipelineBarrier2;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.math.Maths;
import com.truesim.engine.graph.Buffer;
import com.truesim.engine.graph.BufferAccess;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.PassContext;
import com.truesim.engine.graph.Request;
import com.truesim.engine.vulkan.Pipeline;
import com.truesim.engine.vulkan.ShaderModule;
import java.util.Objects;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkMemoryBarrier2;

/**
 * The {@link Lights} binned per cluster of a camera's frustum, for shading many lights: tiles of {@code tileSize}
 * pixels across and down, {@code slices} depth slices from {@code near} to {@code far} (exponential: each one longer by
 * the same ratio), up to {@code maxPerCluster} lights each. A shared request: every technique shading the camera's
 * view (forward, visibility buffer) gets the same clusters, made once per frame on the async compute queue. The camera
 * is view 0 of {@code renderer}, read when the frame runs.
 *
 * <pre>{@code
 * LightClusters.Grid clusters = g.request(new LightClusters(lights, renderer, width, height));
 * g.pass("shade").read(clusters.header(), BufferAccess.DRAW_READ).read(clusters.indices(), BufferAccess.DRAW_READ)
 *     ... // shaders take ctx.address(clusters.header()): a ClusterHeader of lights.slang
 * }</pre>
 */
public record LightClusters(Lights lights, MeshletRenderer renderer, int width, int height, int tileSize, int slices,
    float near, float far, int maxPerCluster) implements Request<LightClusters.Grid> {

    private static final int PUSH_BYTES = 32;
    static final int HEADER_BYTES = 64;

    public LightClusters {
        Objects.requireNonNull(lights, "lights");
        Objects.requireNonNull(renderer, "renderer");
        if (width < 1 || height < 1 || tileSize < 1 || slices < 1 || !(near > 0) || !(far > near)
            || maxPerCluster < 1) {
            throw new IllegalArgumentException("Clusters need a size, tiles, slices, 0 < near < far and room for "
                + "lights: " + width + " x " + height + ", " + tileSize + ", " + slices + ", " + near + ", " + far
                + ", " + maxPerCluster);
        }
    }

    /** Tiles of 64 pixels, 24 slices from 0.1 to 1,000 units, up to 128 lights per cluster. */
    public LightClusters(Lights lights, MeshletRenderer renderer, int width, int height) {
        this(lights, renderer, width, height, 64, 24, 0.1f, 1000, 128);
    }

    /** The clusters: the header (then a count per cluster) and the light slots. */
    public record Grid(Buffer header, Buffer indices) {

        /** Declares that {@code pass} reads the clusters with {@code access}. */
        public GraphBuilder.PassBuilder readBy(GraphBuilder.PassBuilder pass, BufferAccess access) {
            return pass.read(header, access).read(indices, access);
        }
    }

    public int tilesAcross() {
        return (width + tileSize - 1) / tileSize;
    }

    public int tilesDown() {
        return (height + tileSize - 1) / tileSize;
    }

    public int clusters() {
        return tilesAcross() * tilesDown() * slices;
    }

    @Override
    @AllowAllocation("building the graph, when it changes")
    public Grid build(GraphBuilder g) {
        Pipeline pipeline = g.cached(LightClusters.class, device -> {
            try (ShaderModule shader = ShaderModule.load(device, "shaders/lights/cluster.spv")) {
                return Pipeline.compute(device, shader, "clusterMain", PUSH_BYTES);
            }
        });
        Grid grid = new Grid(g.createBuffer("light clusters", HEADER_BYTES + 4L * clusters()),
            g.createBuffer("cluster lights", 4L * clusters() * maxPerCluster));
        g.pass("light clusters")
            .write(grid.header, BufferAccess.TRANSFER_DST)
            .write(grid.header, BufferAccess.STORAGE_WRITE)
            .write(grid.indices, BufferAccess.STORAGE_WRITE)
            .async()
            .record(ctx -> cluster(ctx, pipeline, grid));
        return grid;
    }

    private void cluster(PassContext ctx, Pipeline pipeline, Grid grid) {
        long header = ctx.address(grid.header);
        try (MemoryStack stack = stackPush()) {
            long data = stack.ncalloc(8, 1, HEADER_BYTES);
            memPutInt(data, tilesAcross());
            memPutInt(data + 4, tilesDown());
            memPutInt(data + 8, slices);
            memPutInt(data + 12, tileSize);
            memPutFloat(data + 16, near);
            memPutFloat(data + 20, (float) (slices / Maths.log(far / near)));
            memPutInt(data + 24, maxPerCluster);
            memPutInt(data + 28, 0); // overflow: the most lights a cluster wanted
            memPutLong(data + 32, lights.lightsAddress());
            memPutLong(data + 40, lights.shadowsAddress());
            memPutLong(data + 48, ctx.address(grid.indices));
            memPutLong(data + 56, header + HEADER_BYTES);
            nvkCmdUpdateBuffer(ctx.commands(), ctx.buffer(grid.header), 0, HEADER_BYTES, data);
            long barrier = stack.ncalloc(VkMemoryBarrier2.ALIGNOF, 1, VkMemoryBarrier2.SIZEOF);
            memPutInt(barrier + VkMemoryBarrier2.STYPE, VK_STRUCTURE_TYPE_MEMORY_BARRIER_2);
            memPutLong(barrier + VkMemoryBarrier2.SRCSTAGEMASK, VK_PIPELINE_STAGE_2_TRANSFER_BIT);
            memPutLong(barrier + VkMemoryBarrier2.SRCACCESSMASK, VK_ACCESS_2_TRANSFER_WRITE_BIT);
            memPutLong(barrier + VkMemoryBarrier2.DSTSTAGEMASK, VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT);
            memPutLong(barrier + VkMemoryBarrier2.DSTACCESSMASK, VK_ACCESS_2_SHADER_STORAGE_READ_BIT
                | VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT);
            long dependency = stack.ncalloc(VkDependencyInfo.ALIGNOF, 1, VkDependencyInfo.SIZEOF);
            memPutInt(dependency + VkDependencyInfo.STYPE, VK_STRUCTURE_TYPE_DEPENDENCY_INFO);
            memPutInt(dependency + VkDependencyInfo.MEMORYBARRIERCOUNT, 1);
            memPutAddress(dependency + VkDependencyInfo.PMEMORYBARRIERS, barrier);
            nvkCmdPipelineBarrier2(ctx.commands(), dependency);
            long push = stack.nmalloc(8, PUSH_BYTES);
            memPutLong(push, renderer.viewBuffer().deviceAddress());
            memPutLong(push + 8, header);
            memPutInt(push + 16, width);
            memPutInt(push + 20, height);
            memPutFloat(push + 24, far);
            memPutInt(push + 28, lights.count());
            pipeline.bind(ctx.commands());
            pipeline.push(ctx.commands(), push, PUSH_BYTES);
            vkCmdDispatch(ctx.commands(), (clusters() + 63) / 64, 1, 1);
        }
    }
}
