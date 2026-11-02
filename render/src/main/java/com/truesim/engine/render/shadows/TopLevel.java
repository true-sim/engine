package com.truesim.engine.render.shadows;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.graph.Buffer;
import com.truesim.engine.graph.BufferAccess;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Request;
import com.truesim.engine.render.MeshletRenderer;
import com.truesim.engine.render.RayTracing;
import com.truesim.engine.render.SceneData;
import java.util.Objects;

/**
 * The frame's top-level acceleration structure, rebuilt once a frame from {@code renderer}'s scene's instances relative
 * to the ray tracing's {@link RayTracing#origin origin}: a shared request, so every ray-traced technique of every view
 * (masks, inline shadows) uses one build, and moves its rays by its camera's position less the origin. Its result is the
 * structure, which passes tracing rays read ({@link BufferAccess#ACCELERATION_STRUCTURE_READ}), so the graph orders them
 * after the build. The deformed copies' bottom levels are refitted before it ({@link BottomLevelUpdates}).
 */
public record TopLevel(RayTracing rayTracing, MeshletRenderer renderer) implements Request<Buffer> {

    public TopLevel {
        Objects.requireNonNull(rayTracing, "rayTracing");
        Objects.requireNonNull(renderer, "renderer");
    }

    @Override
    @AllowAllocation("building the graph, when it changes")
    public Buffer build(GraphBuilder g) {
        rayTracing.follow(renderer.scene()); // grown with it, between frames
        SceneData.Buffers scene = g.request(new SceneData(renderer));
        Buffer bottoms = g.request(new BottomLevelUpdates(rayTracing, renderer)); // deformed copies refitted first
        GraphBuilder.PassBuilder pass = g.pass("top level").read(scene.instances(), BufferAccess.STORAGE_READ)
            .read(bottoms, BufferAccess.STORAGE_READ);
        Buffer structure = rayTracing.declareTopLevelBuild(g, pass, renderer.scene());
        pass.record(ctx -> rayTracing.buildTopLevel(ctx.unsafe().commands(), renderer, true));
        return structure;
    }
}
