package com.truesim.engine.render.shadows;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.graph.Buffer;
import com.truesim.engine.graph.BufferAccess;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Request;
import com.truesim.engine.render.RayTracing;
import com.truesim.engine.render.RenderView;
import com.truesim.engine.render.SceneData;
import java.util.Objects;

/**
 * The frame's top-level acceleration structure, rebuilt from {@code view}'s scene's instances relative to its camera
 * (that view's shading traces into it; other views move their rays by the difference): a shared request, so every
 * ray-traced technique of the frame (masks, inline shadows) uses one build. Its result is the structure, which passes
 * tracing rays read ({@link BufferAccess#ACCELERATION_STRUCTURE_READ}), so the graph orders them after the build.
 */
public record TopLevel(RayTracing rayTracing, RenderView view) implements Request<Buffer> {

    public TopLevel {
        Objects.requireNonNull(rayTracing, "rayTracing");
        Objects.requireNonNull(view, "view");
    }

    @Override
    @AllowAllocation("building the graph, when it changes")
    public Buffer build(GraphBuilder g) {
        SceneData.Buffers scene = g.request(new SceneData(view.renderer()));
        GraphBuilder.PassBuilder pass = g.pass("top level").read(scene.instances(), BufferAccess.STORAGE_READ);
        Buffer structure = rayTracing.declareTopLevelBuild(g, pass);
        pass.record(ctx -> rayTracing.buildTopLevel(ctx.unsafe().commands(), view, true));
        return structure;
    }
}
