package com.truesim.engine.render.particles;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.graph.Id;
import com.truesim.engine.graph.Access;
import com.truesim.engine.graph.Anchor;
import com.truesim.engine.graph.Buffer;
import com.truesim.engine.graph.BufferAccess;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Image;
import com.truesim.engine.graph.Key;
import com.truesim.engine.graph.Order;
import com.truesim.engine.graph.Technique;
import com.truesim.engine.render.Keys;
import com.truesim.engine.render.RenderView;
import com.truesim.engine.render.SceneView;
import com.truesim.engine.render.Transparency;
import com.truesim.engine.render.layout.ParticleConstants;
import com.truesim.engine.render.layout.ParticleDepthPush;
import com.truesim.engine.vulkan.Pipeline;
import java.util.List;
import java.util.Objects;

/**
 * The collision view of particles' depth collision ({@code collide.depth}, effects.md §2.7): added to one view (the
 * main camera; the left eye in VR), it takes that view's opaque depth each frame, after the opaque scene, on a grid of
 * at most {@code DEPTH_GRID} samples a side, with the view-projection it was drawn with; the next frame's simulation
 * collides particles with it, reprojected. Right on screen only: off screen and behind what the view sees, particles
 * pass. Without one, depth collision does nothing.
 *
 * <pre>{@code
 * mainPort.add(new ParticleCollisionDepth(particles, mainView));
 * }</pre>
 */
public final class ParticleCollisionDepth implements Technique {

    public static final Id<ParticleCollisionDepth> ID = Id.of("truesim.particleCollisionDepth",
        ParticleCollisionDepth.class);

    private final Particles particles;
    private final SceneView view;

    public ParticleCollisionDepth(Particles particles, SceneView view) {
        this.particles = Objects.requireNonNull(particles, "particles");
        this.view = Objects.requireNonNull(view, "view");
    }

    @Override
    public Id<ParticleCollisionDepth> id() {
        return ID;
    }

    @Override
    public List<Key<?>> provides() {
        return List.of();
    }

    /** The scene's depth, which it only reads: a modifier is built where the depth is used, and ordered after it. */
    @Override
    public List<Key<?>> modifies() {
        return List.of(Keys.SCENE_DEPTH);
    }

    /** After the opaque scene, before the blended materials (which write no depth). */
    @Override
    public Order order() {
        return Order.at(Anchor.TRANSPARENT).before(Transparency.ID);
    }

    @Override
    @AllowAllocation("building the graph, when it changes")
    public void build(GraphBuilder g) {
        Image depth = g.modify(Keys.SCENE_DEPTH);
        Buffer target = g.importBuffer(particles.depthImport());
        Pipeline capture = g.computeProgram("shaders/particles/depth.spv", "captureMain", ParticleDepthPush.BYTES);
        RenderView renderView = view.view();
        Particles p = particles;
        ParticleDepthPush push = new ParticleDepthPush();
        g.pass("particle collision depth").read(depth, Access.SAMPLED_COMPUTE)
            .write(target, BufferAccess.STORAGE_WRITE).sideEffects()
            .record(ctx -> {
                int regionWidth = ctx.regionWidth(depth);
                int regionHeight = ctx.regionHeight(depth);
                int grid = ParticleConstants.DEPTH_GRID;
                int width = regionWidth >= regionHeight ? Math.min(grid, regionWidth)
                    : Math.max(1, Math.min(grid, regionHeight) * regionWidth / regionHeight);
                int height = regionHeight > regionWidth ? Math.min(grid, regionHeight)
                    : Math.max(1, width * regionHeight / regionWidth);
                ctx.dispatch(capture, push.view(renderView.address()).depth(ctx.address(target))
                    .scene(ctx.sampled(depth)).width(width).height(height).regionWidth(regionWidth)
                    .regionHeight(regionHeight), (width + 7) / 8, (height + 7) / 8, 1);
                p.depthCaptured();
            });
        g.provide(Keys.SCENE_DEPTH, depth);
    }
}
