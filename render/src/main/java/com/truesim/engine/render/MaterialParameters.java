package com.truesim.engine.render;

import java.lang.foreign.MemorySegment;

/**
 * A material type's parameters: a record generated from a {@code [MaterialParameters]} Slang struct
 * (engine-render's {@code materials.slang}), e.g. {@link com.truesim.engine.render.materials.MetallicRoughnessParameters}.
 * The struct is the source of truth; the record writes itself as slangc lays the struct out.
 */
public interface MaterialParameters {

    /** Its size in a material's block. */
    int bytes();

    /** Writes it to {@code segment} at {@code offset}, as the shaders read it. */
    void write(MemorySegment segment, long offset);

    /** The alpha masking compares (its {@code [MaskAlpha]} field): copied to the material's slot. */
    float maskAlpha();

    /** The texture whose alpha masking compares (its {@code [MaskTexture]} field), or -1. */
    int maskTexture();

    /** The {@code Material.FEATURE_*} bits its type allows, from {@code [MaterialParameters(features)]}. */
    int allowedFeatures();

    /**
     * Gives {@code each} the value of every {@code [Texture]} field, nested records' too, -1 for none: for
     * {@link Materials} to count the materials naming each texture. Generated; none by default.
     */
    default void textures(java.util.function.IntConsumer each) {
    }
}
