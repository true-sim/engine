package com.truesim.engine.graph;

import com.truesim.common.graph.Id;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Where a technique runs among those modifying the same results: at an {@link Anchor}, and after or before other
 * techniques by their {@link Technique#id() id}. Techniques with no order run where they were added, at
 * {@link Anchor#FIRST}. A technique named that is not in the graph is ignored, so a plugin may order itself against
 * another that may be absent.
 *
 * <pre>{@code
 * public Order order() {
 *     return Order.at(Anchor.POST).after(Exposure.ID).before(Bloom.ID);
 * }
 * }</pre>
 *
 * @param anchor where it runs
 * @param after the techniques it runs after
 * @param before the techniques it runs before
 */
public record Order(Anchor anchor, List<Id<?>> after, List<Id<?>> before) {

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
