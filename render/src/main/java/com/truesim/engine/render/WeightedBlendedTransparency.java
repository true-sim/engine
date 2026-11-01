package com.truesim.engine.render;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.graph.Access;
import com.truesim.engine.graph.Buffer;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Image;
import com.truesim.engine.graph.ImageDesc;
import com.truesim.engine.graph.Key;
import com.truesim.engine.graph.ViewPort;
import com.truesim.engine.render.layout.TransparencyPush;
import com.truesim.engine.vulkan.Format;
import com.truesim.engine.vulkan.Pipeline;
import java.util.List;
import java.util.Objects;

/**
 * Weighted blended order-independent transparency (McGuire and Bavoil 2013): the view's blended surfaces drawn after
 * the opaque scene into accumulated color and revealage, testing the opaque depth without writing it, then resolved
 * over the scene's color in place. One pass, no sorting; nearer and more opaque surfaces count more. An approximation
 * where surfaces of very different opacity overlap a lot. Without blended materials it adds no passes.
 *
 * <pre>{@code
 * view.port().add(new WeightedBlendedTransparency(geometry, view));
 * }</pre>
 */
public final class WeightedBlendedTransparency implements Transparency {

    /** The accumulation target's format: weighted color and alpha, added up. */
    public static final int ACCUMULATED_FORMAT = org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16B16A16_SFLOAT;
    /** The revealage target's format: what the blended surfaces let through. */
    public static final int REVEALAGE_FORMAT = org.lwjgl.vulkan.VK10.VK_FORMAT_R16_SFLOAT;

    private final SceneGeometry geometry;
    private final SceneView view;

    /** {@code geometry}'s blended surfaces as {@code view} sees them; added to {@code view}'s port. */
    public WeightedBlendedTransparency(SceneGeometry geometry, SceneView view) {
        this.geometry = Objects.requireNonNull(geometry, "geometry");
        this.view = Objects.requireNonNull(view, "view");
    }

    @Override
    public List<Key<?>> modifies() {
        return List.of(Keys.SCENE_COLOR);
    }

    @Override
    public long buildVersion() {
        return Transparency.buildVersion(geometry.renderer().scene().materials());
    }

    @Override
    @AllowAllocation("building the graph, when it changes")
    public void build(GraphBuilder g) {
        view.checkBuilding(g, this);
        Image color = g.modify(Keys.SCENE_COLOR);
        Materials materials = geometry.renderer().scene().materials();
        if (materials == null || !materials.hasBlended()) {
            return; // nothing blended: the color as it is
        }
        Image depth = g.require(Keys.SCENE_DEPTH);
        Image behind = Transparency.behind(g, materials, color);
        Buffer lights = g.optional(Keys.LIGHT_LIST);
        ShadowInputs.Set shadows = lights == null ? null : g.request(new ShadowInputs(g.view()));
        ViewPort region = g.view();
        String name = view.view().index() == 0 ? "scene" : "view " + view.view().index();
        Image accumulated = g.create(name + " blended color", ImageDesc.of(Format.of(ACCUMULATED_FORMAT),
            color.width(), color.height()));
        Image revealage = g.create(name + " blended revealage", ImageDesc.of(Format.of(REVEALAGE_FORMAT),
            color.width(), color.height()));
        BlendedTargets targets = new BlendedTargets(BlendedTargets.Kind.WEIGHTED, null, accumulated, revealage, depth,
            lights, shadows, region, behind, IndirectInputs.of(g));
        boolean load = false;
        for (GeometryProvider provider : geometry.providers()) {
            provider.drawBlended(g, view.view(), targets, load);
            load = true;
        }
        Pipeline resolve = g.computeProgram("shaders/blocks/transparency.spv", "transparencyMain",
            TransparencyPush.BYTES);
        TransparencyPush push = new TransparencyPush();
        g.pass(name + " transparency resolve")
            .read(accumulated, Access.STORAGE_READ)
            .read(revealage, Access.STORAGE_READ)
            .write(color, Access.STORAGE_WRITE)
            .record(ctx -> {
                int width = region != null ? ctx.regionWidth(color) : color.width();
                int height = region != null ? ctx.regionHeight(color) : color.height();
                ctx.dispatch(resolve, push.color(ctx.storage(color)).accumulated(ctx.storage(accumulated))
                    .revealage(ctx.storage(revealage)).size(width, height), (width + 7) / 8, (height + 7) / 8, 1);
            });
    }
}
