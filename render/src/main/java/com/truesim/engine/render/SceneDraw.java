package com.truesim.engine.render;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.graph.Buffer;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Image;
import com.truesim.engine.graph.ImageDesc;
import com.truesim.engine.graph.Request;
import com.truesim.engine.graph.ViewPort;
import com.truesim.engine.render.blocks.DepthPyramid;
import com.truesim.engine.vulkan.Format;
import java.util.Objects;

/**
 * The scene's geometry drawn from one view, a shared request: every provider of {@code geometry} draws into the same
 * depth and {@code output} targets, {@code width} by {@code height}. With {@code occlusion}, in two phases around a
 * {@link DepthPyramid} of what all of them drew first. Techniques ask for the draws they need (a shading technique the
 * camera's, a shadow technique the light's); equal requests share one.
 *
 * @param geometry what is drawn
 * @param view the view drawn (made by the geometry's renderer)
 * @param camera set on that view when the frame runs (e.g. a light's); null: the view as set
 * @param output what is written besides depth
 * @param depthFormat the depth format
 * @param width the targets' size
 * @param height the targets' size
 * @param occlusion two-phase occlusion culling (a view made with occlusion)
 * @param port the graph's view whose render region it draws in (dynamic resolution); null: the view it is built for
 *     when it is that view's own draw (no camera of its own, targets of the view's size), else all of its targets (a
 *     light's draw)
 */
public record SceneDraw(SceneGeometry geometry, RenderView view, Camera camera, SurfaceOutput output, int depthFormat,
    int width, int height, boolean occlusion, ViewPort port) implements Request<SceneDraw.Targets> {

    /** A draw in the region of the view it is built for if it is that view's own, else into all of its targets. */
    public SceneDraw(SceneGeometry geometry, RenderView view, Camera camera, SurfaceOutput output, int depthFormat,
        int width, int height, boolean occlusion) {
        this(geometry, view, camera, output, depthFormat, width, height, occlusion, null);
    }

    public SceneDraw {
        Objects.requireNonNull(geometry, "geometry");
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(view, "view");
        if (width < 1 || height < 1) {
            throw new IllegalArgumentException("Targets have a size: " + width + " x " + height);
        }
        if (occlusion && !view.options().occlusion()) {
            throw new IllegalArgumentException("View " + view.index() + " was made without occlusion culling");
        }
    }

    /**
     * What the draw wrote: {@code depth}, {@code color} (shaded color or IDs; null for depth only), and the light list,
     * shadow inputs and indirect light shading used ({@link Keys#LIGHT_LIST}, {@link ShadowInputs},
     * {@link IndirectInputs}; null without them, or without shading), within the render region of {@code region} (null:
     * all of them).
     */
    public record Targets(Image depth, Image color, Buffer lights, ShadowInputs.Set shadows,
        IndirectInputs.Set indirect, ViewPort region) {

        public Targets(Image depth, Image color, Buffer lights, ShadowInputs.Set shadows) {
            this(depth, color, lights, shadows, null, null);
        }

        /** Declares that {@code pass} reads the indirect light, where shading has some. */
        public GraphBuilder.PassBuilder readIndirect(GraphBuilder.PassBuilder pass, com.truesim.engine.graph.Access access,
            com.truesim.engine.graph.BufferAccess read) {
            return indirect == null ? pass : indirect.readBy(pass, access, read);
        }

        /** {@code pass}, drawing (or culling) within the render region of {@link #region} where there is one. */
        public GraphBuilder.PassBuilder inRegion(GraphBuilder.PassBuilder pass) {
            return region == null ? pass : pass.region(region);
        }
    }

    @Override
    @AllowAllocation("building the graph, when it changes")
    public Targets build(GraphBuilder g) {
        String name = view.index() == 0 ? "scene" : "view " + view.index();
        Image depth = g.create(name + " depth", ImageDesc.of(Format.of(depthFormat), width, height));
        Image color = switch (output.kind()) {
            case DEPTH -> null;
            case SHADED -> g.create(name + " color", ImageDesc.of(Format.of(output.colorFormat()), width, height));
            case IDS -> g.create(name + " ids", ImageDesc.of(Format.of(output.colorFormat()), width, height));
        };
        Buffer lights = output.kind() == SurfaceOutput.Kind.SHADED ? g.optional(Keys.LIGHT_LIST) : null;
        ShadowInputs.Set shadows = lights == null ? null : g.request(new ShadowInputs(g.view()));
        // The view's own draw renders within its render region; a light's, into all of its targets.
        ViewPort built = g.view();
        ViewPort region = port != null ? port : camera == null && built != null && built.width() == width
            && built.height() == height ? built : null;
        IndirectInputs.Set indirect = output.kind() == SurfaceOutput.Kind.SHADED ? IndirectInputs.of(g) : null;
        Targets targets = new Targets(depth, color, lights, shadows, indirect, region);
        boolean load = false;
        for (GeometryProvider provider : geometry.providers()) {
            provider.drawFirst(g, this, targets, load);
            load = true;
        }
        if (occlusion) {
            Image pyramid = g.request(new DepthPyramid(depth, region));
            for (GeometryProvider provider : geometry.providers()) {
                provider.drawLate(g, this, targets, pyramid);
            }
        }
        return targets;
    }
}
