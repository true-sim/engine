package com.truesim.engine.render;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.graph.BufferAccess;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Request;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * Culling of a {@link MeshletRenderer}'s views, shared by every technique drawing them: one pass culls all the views
 * the techniques of the graph {@link Views#add add} (the camera, a shadow map's light, ...) with one instance pass and
 * one cull pass ({@link MeshletRenderer#cullViews}), however many there are. A view whose adder does not run in a frame
 * (its graph view skipped: {@link com.truesim.engine.graph.ViewPort#enabled}) is left out of that frame's culling, its
 * lists and occlusion history kept as they are. Techniques then draw their view with
 * {@link MeshletRenderer#drawAttached} in passes declaring it ({@link Views#drawing}), which reads the lists the
 * culling wrote, so the graph orders them after it.
 *
 * <pre>{@code
 * MeshletCulling.Views culling = g.request(new MeshletCulling(renderer)); // the same for every technique
 * culling.add(sunView, false, () -> sunView.set(light, 1, MeshletRenderer.MODE_WHITE));
 * culling.drawing(g.pass("shadow map"), sunView).attachDepth(map, Clear.FAR)
 *     .record(ctx -> renderer.drawAttached(ctx.unsafe().commands(), sunView, false, true, 0));
 * }</pre>
 */
public record MeshletCulling(MeshletRenderer renderer) implements Request<MeshletCulling.Views> {

    public MeshletCulling {
        Objects.requireNonNull(renderer, "renderer");
    }

    @Override
    @AllowAllocation("building the graph, when it changes")
    public Views build(GraphBuilder g) {
        for (RenderView view : renderer.views()) {
            view.noSplit(); // split again only for the draws of this build that shade by type
        }
        SceneData.written(g, renderer); // instances the GPU writes (mesh particles) before they are culled
        SceneData.Buffers scene = g.request(new SceneData(renderer));
        // Open: the views techniques add later in the build declare their lists on it.
        GraphBuilder.PassBuilder pass = scene.read(g.pass("meshlet culling"), BufferAccess.STORAGE_READ).open();
        renderer.declareCulling(g, pass);
        Views views = new Views(renderer, g, pass);
        pass.record(ctx -> views.cull(ctx.unsafe().commands()));
        return views;
    }

    /** The views culled together, gathered while the graph builds. */
    @AllowAllocation("building the graph, when it changes")
    public static final class Views {
        private final MeshletRenderer renderer;
        private final GraphBuilder g;
        private final GraphBuilder.PassBuilder pass;
        private RenderView[] views = new RenderView[0];
        private boolean[] early = new boolean[0];
        private BooleanSupplier[] running = new BooleanSupplier[0]; // whether each one's adder runs this frame
        private RenderView[] culled = new RenderView[0]; // those culled this frame
        private boolean[] culledEarly = new boolean[0];
        private Runnable[] before = new Runnable[0]; // per view, aligned with views: null where none

        Views(MeshletRenderer renderer, GraphBuilder g, GraphBuilder.PassBuilder pass) {
            this.renderer = renderer;
            this.g = g;
            this.pass = pass;
        }

        /**
         * Culls {@code view} too, in the early phase of occlusion culling with {@code early} (a view made with
         * occlusion; each has its own history), after running {@code before} (null: nothing), e.g. to set the view
         * when recording.
         */
        public Views add(RenderView view, boolean early, Runnable before) {
            return add(view, early, before, null);
        }

        /**
         * Like {@link #add(RenderView, boolean, Runnable)}, culled only in frames where {@code active} says so (read
         * when the culling records; null: always), besides its adder running: a view drawn only some frames (a shadow
         * tile that changed), its lists kept as they were otherwise. {@code active} must not allocate.
         */
        public Views add(RenderView view, boolean early, Runnable before, BooleanSupplier active) {
            if (view.renderer() != renderer) {
                throw new IllegalArgumentException("A view of another renderer");
            }
            for (RenderView culled : views) {
                if (culled == view) {
                    throw new IllegalStateException("View " + view.index() + " is culled already");
                }
            }
            view.declareCulling(g, pass);
            views = Arrays.copyOf(views, views.length + 1);
            views[views.length - 1] = view;
            this.early = Arrays.copyOf(this.early, this.early.length + 1);
            this.early[this.early.length - 1] = early;
            running = Arrays.copyOf(running, running.length + 1);
            BooleanSupplier runs = g.running();
            running[running.length - 1] = active == null ? runs : () -> runs.getAsBoolean() && active.getAsBoolean();
            culled = new RenderView[views.length];
            culledEarly = new boolean[views.length];
            this.before = Arrays.copyOf(this.before, this.before.length + 1);
            this.before[this.before.length - 1] = before; // with its view: run only when the view is culled
            return this;
        }

        /**
         * Has the culling sort {@code view}'s blended bin farthest first, in {@code slots} runs of one drawn shading
         * bin each ({@link RenderView#sortBlended}); {@code view} is culled here already.
         */
        public Views sortBlended(RenderView view, int slots, int[] drawn) {
            view.sortBlended(slots, drawn);
            view.declareSorting(g, pass);
            return this;
        }

        /** Declares that {@code drawing} draws {@code view} as culled here (its lists); returns {@code drawing}. */
        public GraphBuilder.PassBuilder drawing(GraphBuilder.PassBuilder drawing, RenderView view) {
            view.declareDrawing(g, drawing);
            return drawing;
        }

        void cull(org.lwjgl.vulkan.VkCommandBuffer commands) {
            for (int i = 0; i < views.length; i++) {
                if (before[i] != null && running[i].getAsBoolean()) {
                    before[i].run(); // a skipped view's camera, detail and history are left as they were
                }
            }
            int count = 0;
            for (int i = 0; i < views.length; i++) {
                if (running[i].getAsBoolean()) {
                    culled[count] = views[i];
                    culledEarly[count++] = early[i];
                }
            }
            if (count > 0) {
                renderer.cullViews(commands, culled, culledEarly, count, true); // declared on its pass
            }
        }
    }
}
