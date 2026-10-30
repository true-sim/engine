package com.truesim.engine.render;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.graph.Buffer;
import com.truesim.engine.graph.BufferAccess;
import com.truesim.engine.graph.ExternalBuffer;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Request;
import java.util.Objects;

/**
 * A renderer's scene data on the GPU as buffers of the graph: its instances and its geometry pool's buffers, imported
 * once per build however many techniques request them. Passes reading them declare it ({@link Buffers#read}), so the
 * graph orders them after whatever writes them: the uploads, or a game's own passes writing instances.
 *
 * <pre>{@code
 * SceneData.Buffers scene = g.request(new SceneData(renderer));
 * scene.read(g.pass("my pass"), BufferAccess.STORAGE_READ).record(ctx -> ...);
 * }</pre>
 */
public record SceneData(MeshletRenderer renderer) implements Request<SceneData.Buffers> {

    public SceneData {
        Objects.requireNonNull(renderer, "renderer");
    }

    /** The imported buffers: the instances, then the pool's vertices and meshlets. */
    public record Buffers(Buffer instances, Buffer positions, Buffer attributes, Buffer meshlets,
        Buffer meshletVertices, Buffer meshletTriangles, Buffer clusterLod) {

        /** Declares that {@code pass} reads every one of them with {@code access}; returns {@code pass}. */
        public GraphBuilder.PassBuilder read(GraphBuilder.PassBuilder pass, BufferAccess access) {
            return pass.read(instances, access).read(positions, access).read(attributes, access).read(meshlets, access)
                .read(meshletVertices, access).read(meshletTriangles, access).read(clusterLod, access);
        }
    }

    /**
     * Imports the buffers and adds the pass uploading what changed since the last frame: the instances' scatter and the
     * pool's new meshes. The graph places its barriers from what it and the readers declare, so the frames' starts no
     * longer upload them, for this renderer (frames recorded without a graph upload themselves, as before).
     */
    @Override
    @AllowAllocation("building the graph, when it changes")
    public Buffers build(GraphBuilder g) {
        GeometryPool pool = renderer.pool();
        GpuScene scene = renderer.scene();
        Buffers buffers = new Buffers(g.importBuffer(new ExternalBuffer("instances", scene.instanceBuffer())),
            g.importBuffer(new ExternalBuffer("positions", pool.positions())),
            g.importBuffer(new ExternalBuffer("attributes", pool.attributes())),
            g.importBuffer(new ExternalBuffer("meshlets", pool.meshlets())),
            g.importBuffer(new ExternalBuffer("meshlet vertices", pool.meshletVertices())),
            g.importBuffer(new ExternalBuffer("meshlet triangles", pool.meshletTriangles())),
            g.importBuffer(new ExternalBuffer("cluster LOD", pool.clusterLod())));
        pool.uploadInPasses();
        scene.uploadInPasses();
        g.pass("scene upload").write(buffers.instances, BufferAccess.STORAGE_WRITE)
            .write(buffers.positions, BufferAccess.TRANSFER_DST).write(buffers.attributes, BufferAccess.TRANSFER_DST)
            .write(buffers.meshlets, BufferAccess.TRANSFER_DST).write(buffers.meshletVertices, BufferAccess.TRANSFER_DST)
            .write(buffers.meshletTriangles, BufferAccess.TRANSFER_DST)
            .write(buffers.clusterLod, BufferAccess.TRANSFER_DST)
            .record(ctx -> {
                pool.uploadInPass(ctx.unsafe().commands());
                scene.uploadInPass(ctx.unsafe().commands());
            });
        return buffers;
    }
}
