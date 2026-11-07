package com.truesim.engine.render;

import com.truesim.engine.graph.PassContext;
import java.lang.foreign.MemorySegment;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Where a shading hook's parameters for a view come from, frame by frame ({@link ShadingHooks#add}): the single writer
 * of the hook's record in the view's block. Values come in layers: what the scene holds for the hook (its authored
 * record, {@link ShadingHookValues}: set by field name in content, saved with the scene; the record's defaults without
 * any) and what only the frame knows (a graph buffer's address, regions culled to the view, time), which the source
 * merges. It writes when the frame records, possibly once for each block the hook is in (a view's, a probe volume's
 * own), and must not allocate.
 */
public interface ShadingHookSource {

    /** The bytes it writes: the hook's parameters' record's. */
    int bytes();

    /**
     * Whether the hook runs this frame: its bit of the view's block. Off, the view's pipelines keep its code (they are
     * made with it) and skip it. On by default.
     */
    default boolean active() {
        return true;
    }

    /**
     * Writes the parameters as they are for the frame recording in {@code ctx}, at {@code offset} of {@code into}:
     * {@code authored} (what the scene holds for the hook, or its record's defaults) merged with the frame's own.
     */
    void write(PassContext ctx, ShadingHookParameters authored, MemorySegment into, long offset);

    /** The scene's values as they are ({@code authored}), on while {@code active} says so. */
    static ShadingHookSource authored(int bytes, BooleanSupplier active) {
        Objects.requireNonNull(active, "active");
        return new ShadingHookSource() {
            @Override
            public int bytes() {
                return bytes;
            }

            @Override
            public boolean active() {
                return active.getAsBoolean();
            }

            @Override
            public void write(PassContext ctx, ShadingHookParameters authored, MemorySegment into, long offset) {
                authored.write(into, offset);
            }
        };
    }

    /**
     * The parameters {@code current} gives (read each frame: a technique's record, replaced when its values change),
     * as a source of {@code bytes} bytes; the scene's values are not read.
     */
    static ShadingHookSource of(int bytes, Supplier<? extends ShadingHookParameters> current) {
        Objects.requireNonNull(current, "current");
        return new ShadingHookSource() {
            @Override
            public int bytes() {
                return bytes;
            }

            @Override
            public void write(PassContext ctx, ShadingHookParameters authored, MemorySegment into, long offset) {
                current.get().write(into, offset);
            }
        };
    }

    /** Fixed parameters {@code parameters}. */
    static ShadingHookSource of(ShadingHookParameters parameters) {
        Objects.requireNonNull(parameters, "parameters");
        return of(parameters.bytes(), () -> parameters);
    }
}
