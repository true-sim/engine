package com.truesim.engine.render.shadows;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.graph.Buffer;
import com.truesim.engine.graph.BufferAccess;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Request;
import com.truesim.engine.render.RayTracing;
import com.truesim.engine.render.RenderView;
import com.truesim.engine.render.SceneData;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * The frame's top-level acceleration structure, rebuilt once a frame for every view: a shared request, so every
 * ray-traced technique of every view (masks, inline shadows) uses one build. It is built from the scene's instances
 * relative to one camera, its anchor's: the first view named as one ({@link Structure#anchor(GraphBuilder, RenderView)})
 * whose requester runs that frame. Other views move their rays by the difference to the anchor's position. Passes
 * tracing rays read {@link Structure#buffer()} ({@link BufferAccess#ACCELERATION_STRUCTURE_READ}), so the graph orders
 * them after the build.
 *
 * <pre>{@code
 * TopLevel.Structure top = g.request(new TopLevel(rayTracing)).anchor(g, view);
 * pass.read(top.buffer(), BufferAccess.ACCELERATION_STRUCTURE_READ)
 *     .record(ctx -> trace(ctx, top.anchor().position(0), ...)); // the structure's origin this frame
 * }</pre>
 */
public record TopLevel(RayTracing rayTracing) implements Request<TopLevel.Structure> {

    public TopLevel {
        Objects.requireNonNull(rayTracing, "rayTracing");
    }

    @Override
    @AllowAllocation("building the graph, when it changes")
    public Structure build(GraphBuilder g) {
        // Open: it reads the scene's instances, through the renderer of the first view named as an anchor.
        GraphBuilder.PassBuilder pass = g.pass("top level").open();
        Structure structure = new Structure(rayTracing.declareTopLevelBuild(g, pass), pass);
        pass.record(ctx -> rayTracing.buildTopLevel(ctx.unsafe().commands(), structure.anchor(), true));
        return structure;
    }

    /** The structure of one build, and the views it may be built relative to. */
    public static final class Structure {
        private final Buffer buffer;
        private GraphBuilder.PassBuilder pass; // the build, until it declares reading the scene's instances
        private RenderView[] anchors = new RenderView[0];
        private BooleanSupplier[] running = new BooleanSupplier[0]; // whether each anchor's requester runs this frame

        Structure(Buffer buffer, GraphBuilder.PassBuilder pass) {
            this.buffer = buffer;
            this.pass = pass;
        }

        /** The structure, for passes tracing rays to read. */
        public Buffer buffer() {
            return buffer;
        }

        /**
         * Names {@code view} as a camera the structure may be built relative to, by the technique building in
         * {@code g}: the first named whose technique runs that frame is the anchor. Returns this.
         */
        @AllowAllocation("building the graph, when it changes")
        public Structure anchor(GraphBuilder g, RenderView view) {
            Objects.requireNonNull(view, "view");
            if (pass != null) {
                pass.read(g.request(new SceneData(view.renderer())).instances(), BufferAccess.STORAGE_READ);
                pass = null;
            }
            anchors = Arrays.copyOf(anchors, anchors.length + 1);
            anchors[anchors.length - 1] = view;
            running = Arrays.copyOf(running, running.length + 1);
            running[running.length - 1] = g.running();
            return this;
        }

        /**
         * The view the structure is built relative to in the frame being recorded: the first anchor whose technique
         * runs (the first anchor if none does). Allocates nothing.
         */
        public RenderView anchor() {
            if (anchors.length == 0) {
                throw new IllegalStateException("No view to build the top level relative to: name one with anchor()");
            }
            for (int i = 0; i < anchors.length; i++) {
                if (running[i].getAsBoolean()) {
                    return anchors[i];
                }
            }
            return anchors[0];
        }
    }
}
