package com.truesim.engine.render;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.graph.Id;
import com.truesim.engine.graph.Access;
import com.truesim.engine.graph.Anchor;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Image;
import com.truesim.engine.graph.Key;
import com.truesim.engine.graph.Order;
import com.truesim.engine.graph.Technique;
import com.truesim.engine.graph.ViewPort;
import com.truesim.engine.render.layout.SkyPush;
import com.truesim.engine.vulkan.Pipeline;
import java.util.List;
import java.util.Objects;

/**
 * An {@link Environment} shown behind a view's scene: every pixel no surface covers takes the environment along its
 * direction, turned and scaled as the environment is. It modifies {@link Keys#SCENE_COLOR} at {@link Anchor#OPAQUE},
 * before transparency, so glass shows it; lighting by the environment is {@link EnvironmentLighting}'s, chosen apart
 * (a view may show one environment and be lit by another, or show none: AR passthrough, a product on a plain color).
 * One per view, under one id. Shares the environment's filtering with {@link EnvironmentLighting}.
 *
 * <pre>{@code
 * view.port().add(new SkyBackground(environment, view));
 * }</pre>
 */
public final class SkyBackground implements Technique {

    /** The id of a view's background. */
    public static final Id<SkyBackground> ID = Id.of("truesim.background", SkyBackground.class);

    private final Environment environment;
    private final SceneView view;
    private final EnvironmentMaps.Quality quality;
    private volatile float blur;

    /** {@code environment} behind {@code view}'s scene, filtered at {@link EnvironmentMaps#QUALITY}. */
    public SkyBackground(Environment environment, SceneView view) {
        this(environment, view, EnvironmentMaps.QUALITY);
    }

    /**
     * {@code environment} behind {@code view}'s scene, filtered at {@code quality}: the lighting's, for the views lit
     * by the same environment to share one filtering.
     */
    public SkyBackground(Environment environment, SceneView view, EnvironmentMaps.Quality quality) {
        this.environment = Objects.requireNonNull(environment, "environment");
        this.view = Objects.requireNonNull(view, "view");
        this.quality = Objects.requireNonNull(quality, "quality");
    }

    /** Shown blurred: the radiance cube's level {@code level} (0 sharp, each level half as fine). Read when frames run. */
    public SkyBackground blur(float level) {
        if (!(level >= 0)) {
            throw new IllegalArgumentException("A level of 0 or more: " + level);
        }
        blur = level;
        return this;
    }

    @Override
    public Id<? extends Technique> id() {
        return ID;
    }

    @Override
    public List<Key<?>> provides() {
        return List.of();
    }

    @Override
    public List<Key<?>> modifies() {
        return List.of(Keys.SCENE_COLOR);
    }

    @Override
    public Order order() {
        return Order.at(Anchor.OPAQUE);
    }

    @Override
    @AllowAllocation("building the graph, when it changes")
    public void build(GraphBuilder g) {
        view.checkBuilding(g, this);
        Image color = g.modify(Keys.SCENE_COLOR);
        Image depth = g.require(Keys.SCENE_DEPTH);
        EnvironmentMaps.Maps maps = g.request(new EnvironmentMaps(environment, quality));
        Pipeline sky = g.computeProgram("shaders/environment/sky.spv", "skyMain", SkyPush.BYTES);
        SkyPush push = new SkyPush();
        ViewPort region = g.view();
        GraphBuilder.PassBuilder pass = g.pass("sky").read(depth, Access.SAMPLED_COMPUTE)
            .read(maps.radiance(), Access.SAMPLED_COMPUTE).write(color, Access.STORAGE_WRITE);
        if (region != null) {
            pass.region(region);
        }
        pass.record(ctx -> {
            int width = ctx.regionWidth(color);
            int height = ctx.regionHeight(color);
            ctx.dispatch(sky, push.view(view.view().address()).color(ctx.storage(color)).depth(ctx.sampled(depth))
                .cube(ctx.sampled(maps.radiance())).size(width | height << 16)
                .rotation(environment.toEnvironment(0), environment.toEnvironment(1), environment.toEnvironment(2),
                    environment.toEnvironment(3))
                .intensity(environment.intensity()).blur(blur), (width + 7) / 8, (height + 7) / 8, 1);
        });
    }
}
