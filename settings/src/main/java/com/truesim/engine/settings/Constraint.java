package com.truesim.engine.settings;

import com.truesim.annotations.AllowAllocation;
import java.util.Objects;
import java.util.Set;

/**
 * What the hardware or the game allows a setting right now, over what was asked for: {@link EngineSettings#get}
 * returns the preferred value ({@link EngineSettings#preferred}) passed through every constraint on it, in the order
 * they were added, while the preferred value stays as set and saved. A constraint removed gives the preferred value
 * back.
 *
 * <pre>{@code
 * settings.constrain(Presenter.HDR, Constraint.lock(false, "the display has no HDR"));
 * settings.constrain(ShadowSettings.SIZE, Constraint.range(256, 2048, "GPU memory"));
 * settings.constrain(Shadows.MODE, Constraint.choices(Set.of(CASCADES), CASCADES, "no ray tracing on this GPU"));
 * }</pre>
 *
 * @param <T> the setting's value type
 */
@FunctionalInterface
public interface Constraint<T> {

    /** The value allowed for {@code preferred}: {@code preferred} itself where it is allowed. */
    T apply(T preferred);

    /** Why, for options screens: "the display has no HDR". */
    default String reason() {
        return "";
    }

    /** Always {@code value}, whatever is preferred. */
    @AllowAllocation("made rarely")
    static <T> Constraint<T> lock(T value, String reason) {
        Objects.requireNonNull(value, "value");
        return of(preferred -> value, reason);
    }

    /** The preferred value moved into {@code min} to {@code max}. */
    @AllowAllocation("made rarely")
    static <T extends Comparable<T>> Constraint<T> range(T min, T max, String reason) {
        if (min.compareTo(max) > 0) {
            throw new IllegalArgumentException("An empty range: " + min + " to " + max);
        }
        return of(preferred -> preferred.compareTo(min) < 0 ? min : preferred.compareTo(max) > 0 ? max : preferred,
            reason);
    }

    /** The preferred value where it is one of {@code allowed}, else {@code fallback}. */
    @AllowAllocation("made rarely")
    static <T> Constraint<T> choices(Set<T> allowed, T fallback, String reason) {
        Set<T> copy = Set.copyOf(allowed);
        if (!copy.contains(fallback)) {
            throw new IllegalArgumentException("The fallback " + fallback + " is not one of " + copy);
        }
        return of(preferred -> copy.contains(preferred) ? preferred : fallback, reason);
    }

    /** {@code rule}, with {@code reason}. */
    @AllowAllocation("made rarely")
    static <T> Constraint<T> of(Constraint<T> rule, String reason) {
        Objects.requireNonNull(rule, "rule");
        Objects.requireNonNull(reason, "reason");
        return new Constraint<>() {
            @Override
            public T apply(T preferred) {
                return rule.apply(preferred);
            }

            @Override
            public String reason() {
                return reason;
            }

            @Override
            public String toString() {
                return reason;
            }
        };
    }
}
