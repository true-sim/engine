package com.truesim.engine.render.shadows;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.graph.Buffer;
import com.truesim.engine.graph.BufferAccess;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Request;
import com.truesim.engine.render.RayTracing;
import com.truesim.engine.render.RenderView;
import java.util.Objects;

/**
 * The frame's top-level acceleration structure, rebuilt from {@code view}'s scene's instances relative to its camera
 * (that view's shading traces into it; other views move their rays by the difference): a shared request, so every
 * ray-traced technique of the frame (masks, inline shadows) uses one build. Its result is a small buffer that passes
 * tracing rays read, so they run after the build.
 */
public record TopLevel(RayTracing rayTracing, RenderView view) implements Request<Buffer> {

    public TopLevel {
        Objects.requireNonNull(rayTracing, "rayTracing");
        Objects.requireNonNull(view, "view");
    }

    @Override
    @AllowAllocation("building the graph, when it changes")
    public Buffer build(GraphBuilder g) {
        Buffer built = g.createBuffer("top level", 16);
        g.pass("top level").write(built, BufferAccess.STORAGE_WRITE)
            .record(ctx -> rayTracing.buildTopLevel(ctx.unsafe().commands(), view));
        return built;
    }
}
