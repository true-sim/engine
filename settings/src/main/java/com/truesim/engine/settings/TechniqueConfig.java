package com.truesim.engine.settings;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The tunables of one kind of technique ({@link TechniqueKind}), as a record implementing this: each component is a
 * setting named by its dotted path, the slot's name, the kind's id and the component's name
 * ({@code render.ao.bitmask.radius}), defaulting to the kind's defaults. Components are {@code boolean}, {@code int},
 * {@code float}, {@code String} or an enum; {@link Tunable} gives each its description and range.
 *
 * <pre>{@code
 * public record Bitmask(
 *     @Tunable(value = "how far occluders count, in meters", min = 0.01, max = 100) float radius,
 *     @Tunable(value = "samples along each side of a slice", min = 1, max = 64) int steps,
 *     @Tunable("one bounce of the scene's color") boolean bounce) implements TechniqueConfig {
 * }
 * }</pre>
 *
 * <p>Open: games and mods write their own records for their own kinds, as the engine does for its.
 */
public interface TechniqueConfig {

    /**
     * A component's description, range (numbers) and former setting names: what an options screen or a console shows,
     * what values a player may set, and the names older settings files saved it under (read where the new name holds
     * nothing).
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.RECORD_COMPONENT)
    @interface Tunable {

        /** What it does, for options screens and consoles. */
        String value();

        /** The smallest value allowed (numbers). */
        double min() default -Double.MAX_VALUE;

        /** The largest value allowed (numbers). */
        double max() default Double.MAX_VALUE;

        /** Full names it was saved under before ({@code render.ao.radius}): read where its own holds nothing. */
        String[] formerly() default {};
    }
}
