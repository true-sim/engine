package com.truesim.engine.render;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.graph.Buffer;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Image;
import com.truesim.engine.graph.Key;
import java.util.List;
import java.util.Objects;

/**
 * Blended materials drawn as opaque, over the view's opaque scene: double-sided materials both ways, one-sided ones
 * their front faces, written into the scene's color and depth. The cheapest way to show them, for low settings or
 * debugging; they hide what is behind them. Without blended materials it adds no passes.
 *
 * <pre>{@code
 * view.port().replace(new OpaqueTransparency(geometry, view));
 * }</pre>
 */
public final class OpaqueTransparency implements Transparency {

    private final SceneGeometry geometry;
    private final SceneView view;

    /** {@code geometry}'s blended surfaces as {@code view} sees them, as opaque; added to {@code view}'s port. */
    public OpaqueTransparency(SceneGeometry geometry, SceneView view) {
        this.geometry = Objects.requireNonNull(geometry, "geometry");
        this.view = Objects.requireNonNull(view, "view");
    }

    @Override
    public List<Key<?>> modifies() {
        return List.of(Keys.SCENE_COLOR, Keys.SCENE_DEPTH);
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
        Image depth = g.modify(Keys.SCENE_DEPTH);
        Materials materials = geometry.renderer().scene().materials();
        if (materials == null || !materials.hasBlended()) {
            return;
        }
        Buffer lights = g.optional(Keys.LIGHT_LIST);
        ShadowInputs.Set shadows = lights == null ? null : g.request(new ShadowInputs(g.view()));
        BlendedTargets targets = new BlendedTargets(BlendedTargets.Kind.OPAQUE, color, null, null, depth, lights,
            shadows, g.view());
        boolean load = false;
        for (GeometryProvider provider : geometry.providers()) {
            provider.drawBlended(g, view.view(), targets, load);
            load = true;
        }
    }
}
