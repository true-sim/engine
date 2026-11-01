package com.truesim.engine.render;

import com.truesim.common.graph.Id;
import com.truesim.engine.graph.Anchor;
import com.truesim.engine.graph.Key;
import com.truesim.engine.graph.Order;
import com.truesim.engine.graph.Technique;
import java.util.List;

/**
 * How blended materials ({@link Material#alphaBlend}) are drawn over a view's opaque scene: a technique that modifies
 * {@link Keys#SCENE_COLOR} at {@link Anchor#TRANSPARENT}, after the opaque scene's changes and before post effects. One
 * per view, under one id, so {@code port.replace(...)} swaps one for another and two in one view fail the build:
 * {@link WeightedBlendedTransparency} (the default a preset adds), {@link OpaqueTransparency}; further ones (sorted
 * meshlets, per-pixel lists) are more of them. A view without one draws no blended surfaces.
 *
 * <pre>{@code
 * view.port().add(new VisibilityBufferShading(geometry, view, color, depth))
 *     .add(new WeightedBlendedTransparency(geometry, view));
 * view.port().replace(new OpaqueTransparency(geometry, view));       // cheaper: blended drawn as opaque
 * }</pre>
 */
public interface Transparency extends Technique {

    /** The id every transparency technique has: one per view. */
    Id<Transparency> ID = Id.of("truesim.transparency", Transparency.class);

    /** Nothing: transparency modifies the scene's color. */
    @Override
    default List<Key<?>> provides() {
        return List.of();
    }

    @Override
    default Id<? extends Technique> id() {
        return ID;
    }

    /** Over the opaque scene: at {@link Anchor#TRANSPARENT}. */
    @Override
    default Order order() {
        return Order.at(Anchor.TRANSPARENT);
    }

    /** Whether {@code materials} has blended ones, as a build version: the passes come and go with them. */
    static long buildVersion(Materials materials) {
        return materials != null && materials.hasBlended() ? 1 : 0;
    }
}
