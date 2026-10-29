package com.truesim.engine.render;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.graph.Buffer;
import com.truesim.engine.graph.BufferAccess;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Request;
import java.util.Arrays;
import java.util.Objects;

/**
 * Culling of a {@link MeshletRenderer}'s views, shared by every technique drawing them: one pass culls all the views
 * the techniques of the graph {@link Views#add add} (the camera, a shadow map's light, ...) with one instance pass and
 * one cull pass ({@link MeshletRenderer#cullViews}), however many there are. Techniques then draw their view with
 * {@link MeshletRenderer#drawView} in passes reading {@link Views#culled()}, so they come after it.
 *
 * <pre>{@code
 * MeshletCulling.Views culling = g.request(new MeshletCulling(renderer)); // the same for every technique
 * culling.add(1, false, () -> renderer.setView(1, light, 1, MeshletRenderer.MODE_WHITE));
 * g.pass("shadow map").read(culling.culled(), BufferAccess.INDIRECT).write(map, Access.DEPTH_ATTACHMENT)
 *     .record(ctx -> renderer.drawView(ctx.commands(), 1, 0, ctx.view(map), size, size, 0, 0, 0));
 * }</pre>
 */
public record MeshletCulling(MeshletRenderer renderer) implements Request<MeshletCulling.Views> {

    public MeshletCulling {
        Objects.requireNonNull(renderer, "renderer");
    }

    @Override
    @AllowAllocation("building the graph, when it changes")
    public Views build(GraphBuilder g) {
        Views views = new Views(renderer, g.createBuffer("culled meshlets", 16));
        g.pass("meshlet culling")
            .write(views.culled, BufferAccess.STORAGE_WRITE)
            .record(ctx -> views.cull(ctx.commands()));
        return views;
    }

    /** The views culled together, gathered while the graph builds. */
    @AllowAllocation("building the graph, when it changes")
    public static final class Views {
        private final MeshletRenderer renderer;
        private final Buffer culled;
        private int[] views = new int[0];
        private int early = -1;
        private Runnable[] before = new Runnable[0];

        Views(MeshletRenderer renderer, Buffer culled) {
            this.renderer = renderer;
            this.culled = culled;
        }

        /**
         * Culls view {@code index} too, in the early phase of occlusion culling with {@code early} (the camera of a
         * {@link MeshletGeometry}'s camera), after running {@code before} (null: nothing), e.g. to set the view when
         * recording.
         */
        public Views add(int index, boolean early, Runnable before) {
            Objects.checkIndex(index, renderer.limits().views());
            for (int view : views) {
                if (view == index) {
                    throw new IllegalStateException("View " + index + " is culled already");
                }
            }
            views = Arrays.copyOf(views, views.length + 1);
            views[views.length - 1] = index;
            if (early) {
                if (this.early >= 0) {
                    throw new IllegalStateException("View " + this.early + " culls early already: one view may");
                }
                this.early = index;
            }
            if (before != null) {
                this.before = Arrays.copyOf(this.before, this.before.length + 1);
                this.before[this.before.length - 1] = before;
            }
            return this;
        }

        /** What draw passes read (as {@link BufferAccess#INDIRECT}), so they run after the culling. */
        public Buffer culled() {
            return culled;
        }

        void cull(org.lwjgl.vulkan.VkCommandBuffer commands) {
            for (Runnable runnable : before) {
                runnable.run();
            }
            renderer.cullViews(commands, views, views.length, early);
        }
    }
}
