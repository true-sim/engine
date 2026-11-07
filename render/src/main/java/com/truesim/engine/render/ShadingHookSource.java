package com.truesim.engine.render;

import com.truesim.engine.graph.PassContext;
import java.lang.foreign.MemorySegment;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Where a shading hook's parameters for a view come from, frame by frame ({@link ShadingHooks#add}): a technique's
 * own values, a scene component's, or values only the frame knows (a graph buffer's address). It writes the hook's
 * record ({@link ShadingHookParameters}, as slangc lays it out) when the frame records, so it must not allocate.
 */
public interface ShadingHookSource {

    /** The bytes it writes: the hook's parameters' record's. */
    int bytes();

    /** Writes the parameters as they are for the frame recording in {@code ctx}, at {@code offset} of {@code into}. */
    void write(PassContext ctx, MemorySegment into, long offset);

    /**
     * The parameters {@code current} gives (read each frame: a technique's or a component's record, replaced when
     * its values change), as a source of {@code bytes} bytes.
     */
    static ShadingHookSource of(int bytes, Supplier<? extends ShadingHookParameters> current) {
        Objects.requireNonNull(current, "current");
        return new ShadingHookSource() {
            @Override
            public int bytes() {
                return bytes;
            }

            @Override
            public void write(PassContext ctx, MemorySegment into, long offset) {
                current.get().write(into, offset);
            }
        };
    }

    /** Fixed parameters {@code parameters}. */
    static ShadingHookSource of(ShadingHookParameters parameters) {
        Objects.requireNonNull(parameters, "parameters");
        return of(parameters.bytes(), () -> parameters);
    }

    /** A hook without parameters. */
    static ShadingHookSource none() {
        return new ShadingHookSource() {
            @Override
            public int bytes() {
                return 0;
            }

            @Override
            public void write(PassContext ctx, MemorySegment into, long offset) {
            }
        };
    }
}
