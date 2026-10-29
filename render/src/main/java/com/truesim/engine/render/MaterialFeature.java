package com.truesim.engine.render;

import java.lang.foreign.MemorySegment;

/**
 * A feature's parameters: a record generated from a {@code [MaterialFeature(feature)]} Slang struct, e.g.
 * {@link com.truesim.engine.render.materials.ClearcoatParameters}. A material shows the feature where it has them
 * ({@link Material#feature}), whatever its type, if the type allows it.
 */
public interface MaterialFeature {

    /** Its size in a material's block. */
    int bytes();

    /** Writes it to {@code segment} at {@code offset}, as the shaders read it. */
    void write(MemorySegment segment, long offset);

    /** Its {@code Material.FEATURE_*} bit. */
    int feature();
}
