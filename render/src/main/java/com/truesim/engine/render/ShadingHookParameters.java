package com.truesim.engine.render;

import java.lang.foreign.MemorySegment;

/**
 * A shading hook's parameters: a record generated from a {@code [ShadingHookParameters]} Slang struct (any size), e.g.
 * {@link com.truesim.engine.render.materials.ProbeVolumeParameters}. The struct is the source of truth; the record
 * writes itself as slangc lays the struct out, and the hook's dispatch loads it field by field ({@link ShadingHooks}).
 */
public interface ShadingHookParameters {

    /** Its size in a view's hook data. */
    int bytes();

    /** Writes it to {@code segment} at {@code offset}, as the shaders read it. */
    void write(MemorySegment segment, long offset);

    /** As {@link MaterialParameters#textures}: every {@code [Texture]} field's value. Generated; none by default. */
    default void textures(java.util.function.IntConsumer each) {
    }

    /** As {@link MaterialParameters#buffers}: every buffer field, past {@code base}. Generated; none by default. */
    default void buffers(long base, MaterialBuffers each) {
    }
}
