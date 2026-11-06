package com.truesim.engine.graph;

import com.truesim.common.graph.Id;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Where a technique runs among those modifying the same results: at an {@link Anchor} or a named order point inside
 * one ({@link #SKY}, {@link #SCREEN_SPACE_OCCLUSION}, {@link #SCREEN_SPACE_LIGHTING}), and after or before other
 * techniques by their {@link Technique#id() id}. Techniques with no order run where they were added, at
 * {@link Anchor#FIRST}. A technique named that is not in the graph is ignored, so a plugin may order itself against
 * another that may be absent.
 *
 * <p>Order points let libraries place their techniques by what they do, never by another library's ids: ambient
 * occlusion runs at {@link #SCREEN_SPACE_OCCLUSION}, after the sky and before reflections, whichever libraries
 * provide them. Ids stay for order within one library, or against the core's techniques.
 *
 * <pre>{@code
 * public Order order() {
 *     return Order.at(Order.SCREEN_SPACE_LIGHTING);                   // after the sky and ambient occlusion
 *     // or: Order.at(Anchor.POST).after(Exposure.ID).before(Bloom.ID);
 * }
 * }</pre>
 *
 * @param anchor where it runs
 * @param after the techniques it runs after
 * @param before the techniques it runs before
 */
public record Order(Anchor anchor, List<Id<?>> after, List<Id<?>> before) {

    /**
     * In {@link Anchor#OPAQUE}'s span: the sky (or any background) filling what the opaque scene left uncovered. After
     * the opaque scene's changes, before what is lit from the screen.
     */
    public static final Anchor SKY = new Anchor("sky", Anchor.OPAQUE.rank() + 20);
    /**
     * In {@link Anchor#OPAQUE}'s span: occlusion of the opaque scene's light found on the screen, such as ambient
     * occlusion. After {@link #SKY}, before {@link #SCREEN_SPACE_LIGHTING}, so reflections show the occluded scene.
     */
    public static final Anchor SCREEN_SPACE_OCCLUSION = new Anchor("screen-space occlusion", Anchor.OPAQUE.rank() + 40);
    /**
     * In {@link Anchor#OPAQUE}'s span: light added from what the screen shows, such as screen-space reflections or
     * global illumination. After {@link #SCREEN_SPACE_OCCLUSION}, before {@link Anchor#TRANSPARENT}.
     */
    public static final Anchor SCREEN_SPACE_LIGHTING = new Anchor("screen-space lighting", Anchor.OPAQUE.rank() + 60);

    /** No order: at {@link Anchor#FIRST}, where it was added. */
    public static final Order NONE = new Order(Anchor.FIRST, List.of(), List.of());

    public Order {
        Objects.requireNonNull(anchor, "anchor");
        after = List.copyOf(after);
        before = List.copyOf(before);
    }

    /** At {@code anchor}. */
    public static Order at(Anchor anchor) {
        return new Order(anchor, List.of(), List.of());
    }

    /** This order, also after the techniques {@code ids}. */
    public Order after(Id<?>... ids) {
        return new Order(anchor, joined(after, ids), before);
    }

    /** This order, also before the techniques {@code ids}. */
    public Order before(Id<?>... ids) {
        return new Order(anchor, after, joined(before, ids));
    }

    private static List<Id<?>> joined(List<Id<?>> list, Id<?>[] more) {
        List<Id<?>> all = new ArrayList<>(list);
        for (Id<?> id : more) {
            all.add(Objects.requireNonNull(id, "id"));
        }
        return all;
    }
}
