package com.truesim.engine.render;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.graph.Buffer;
import com.truesim.engine.graph.BufferAccess;
import com.truesim.engine.graph.ExternalBuffer;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Request;
import com.truesim.engine.render.layout.BlendPush;
import com.truesim.engine.render.layout.DeformPush;
import com.truesim.engine.render.layout.KeepPush;
import com.truesim.engine.vulkan.Pipeline;
import java.util.List;
import java.util.Objects;

/**
 * A renderer's scene data on the GPU as buffers of the graph: its instances and its geometry pool's buffers, imported
 * once per build however many techniques request them. Passes reading them declare it ({@link Buffers#read}), so the
 * graph orders them after whatever writes them: the uploads, or a game's own passes writing instances. The frame graph
 * buffers the scene's materials name ({@link Materials#graphBuffers}) are among them: a pass writing one comes first.
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

    /** The imported buffers: the instances, then the pool's vertices and meshlets, then the materials' buffers. */
    public record Buffers(Buffer instances, Buffer positions, Buffer attributes, Buffer meshlets,
        Buffer meshletVertices, Buffer meshletTriangles, Buffer clusterLod, java.util.List<Buffer> materials) {

        /** Declares that {@code pass} reads every one of them with {@code access}; returns {@code pass}. */
        public GraphBuilder.PassBuilder read(GraphBuilder.PassBuilder pass, BufferAccess access) {
            pass.read(instances, access).read(positions, access).read(attributes, access).read(meshlets, access)
                .read(meshletVertices, access).read(meshletTriangles, access).read(clusterLod, access);
            for (Buffer buffer : materials) {
                pass.read(buffer, access);
            }
            return pass;
        }
    }

    /**
     * Builds the techniques writing {@code renderer}'s instances on the GPU ({@link GpuScene#writtenBy}: mesh
     * particles) now, if the graph has them, so their writes come before what culls the instances: techniques culling
     * them call this before declaring their passes.
     */
    @AllowAllocation("building the graph, when it changes")
    public static void written(GraphBuilder g, MeshletRenderer renderer) {
        for (com.truesim.engine.graph.Key<?> key : List.copyOf(renderer.scene().writers())) {
            g.optional(key);
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
        ExternalBuffer[] geometry = pool.graphBuffers(); // the pool's own: set anew as it grows
        java.util.List<Buffer> named = new java.util.ArrayList<>();
        if (scene.materials() != null) {
            for (ExternalBuffer buffer : scene.materials().graphBuffers()) {
                named.add(g.importBuffer(buffer));
            }
        }
        Buffers buffers = new Buffers(g.importBuffer(scene.graphBuffer()),
            g.importBuffer(geometry[0]), g.importBuffer(geometry[1]), g.importBuffer(geometry[2]),
            g.importBuffer(geometry[3]), g.importBuffer(geometry[4]), g.importBuffer(geometry[5]), List.copyOf(named));
        InstanceMotion motion = scene.motion();
        Buffer motions = g.importBuffer(motion.firstGraph());
        Buffer motionsBefore = g.importBuffer(motion.secondGraph());
        pool.uploadInPasses();
        scene.uploadInPasses();
        motion.uploadInPasses();
        g.pass("scene upload").write(buffers.instances, BufferAccess.STORAGE_WRITE)
            .write(motions, BufferAccess.STORAGE_WRITE).write(motionsBefore, BufferAccess.STORAGE_WRITE)
            .write(buffers.positions, BufferAccess.TRANSFER_DST).write(buffers.attributes, BufferAccess.TRANSFER_DST)
            .write(buffers.meshlets, BufferAccess.TRANSFER_DST).write(buffers.meshletVertices, BufferAccess.TRANSFER_DST)
            .write(buffers.meshletTriangles, BufferAccess.TRANSFER_DST)
            .write(buffers.clusterLod, BufferAccess.TRANSFER_DST)
            .record(ctx -> {
                pool.uploadInPass(ctx.unsafe().commands());
                scene.uploadInPass(ctx.unsafe().commands());
                motion.uploadInPass(ctx.unsafe().commands());
            });
        // Instances moved in fixed steps drawn between their last two poses (D91), before anything reads them
        Pipeline blend = g.computeProgram("shaders/interpolation/blend.spv", "blendMain", BlendPush.BYTES);
        BlendPush blendPush = new BlendPush();
        g.pass("interpolation").read(motions, BufferAccess.STORAGE_READ).read(motionsBefore, BufferAccess.STORAGE_READ)
            .write(buffers.instances, BufferAccess.STORAGE_WRITE)
            .record(ctx -> motion.record(ctx, ctx.address(motions), ctx.address(motionsBefore),
                ctx.address(buffers.instances), blend, blendPush));
        // Deformed copies posed right after: every reader sees them as any mesh
        Pipeline skin = g.computeProgram("shaders/deformation/skin.spv", "skinMain", DeformPush.BYTES);
        Pipeline spheres = g.computeProgram("shaders/deformation/skin.spv", "spheresMain", DeformPush.BYTES);
        DeformPush push = new DeformPush();
        // Skinned copies' vertices as the frame before drew them, kept for motion vectors (MotionVectors)
        Pipeline keep = g.computeProgram("shaders/deformation/keep.spv", "keepMain", KeepPush.BYTES);
        KeepPush keepPush = new KeepPush();
        g.pass("deformation").write(buffers.positions, BufferAccess.STORAGE_WRITE)
            .write(buffers.attributes, BufferAccess.STORAGE_WRITE).write(buffers.meshlets, BufferAccess.STORAGE_WRITE)
            .read(buffers.meshletVertices, BufferAccess.STORAGE_READ)
            .write(buffers.clusterLod, BufferAccess.STORAGE_WRITE)
            .read(buffers.instances, BufferAccess.STORAGE_READ) // displaced copies: their instances' poses
            .record(ctx -> pool.deformation().record(ctx, buffers, skin, spheres, push, keep, keepPush, scene));
        return buffers;
    }
}
