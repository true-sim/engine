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
 * The frame's builds and refits of the deformed copies' bottom levels ({@link RayTracing#addDeformed}): a pass after
 * the deformation pass, which wrote the copies' positions, and before the top level's build ({@link TopLevel}, which
 * requests it). A shared request. Its result is a token buffer it writes: the top level's pass reads it, which orders
 * the two; the levels themselves are outside the graph, so the pass places its own barriers around them.
 */
public record BottomLevelUpdates(RayTracing rayTracing, MeshletRenderer renderer) implements Request<Buffer> {

    public BottomLevelUpdates {
        Objects.requireNonNull(rayTracing, "rayTracing");
        Objects.requireNonNull(renderer, "renderer");
    }

    @Override
    @AllowAllocation("building the graph, when it changes")
    public Buffer build(GraphBuilder g) {
        SceneData.Buffers scene = g.request(new SceneData(renderer));
        Buffer done = g.createBuffer("bottom levels refitted", 16);
        g.pass("bottom level refits").read(scene.positions(), BufferAccess.STORAGE_READ)
            .write(done, BufferAccess.STORAGE_WRITE)
            .record(ctx -> rayTracing.updateBottomLevels(ctx.unsafe().commands()));
        return done;
    }
}
