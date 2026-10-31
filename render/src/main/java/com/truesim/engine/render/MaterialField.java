package com.truesim.engine.render;

import java.util.Objects;

/**
 * One field of a generated material record, by its offset in the record's block: for {@link Materials#update}, which
 * writes a value in place, allocating nothing (a value changing every frame). Generated records have a constant per
 * field, nested ones by path: {@code CarPaintParameters.BASE_ROUGHNESS}.
 */
public final class MaterialField {

    /** What the field holds. */
    public enum Kind {
        /** A float. */
        F32,
        /** A 32-bit integer, or a texture slot. */
        I32,
        /** A float2 to float4 (or a color). */
        FLOATS,
        /** A buffer's address ({@code T*} in Slang), set as a buffer. */
        BUFFER
    }

    private final Class<?> owner;
    private final String path;
    private final int offset;
    private final Kind kind;
    private final int count;
    private final float low;
    private final float high;
    private final boolean texture;

    private MaterialField(Class<?> owner, String path, int offset, Kind kind, int count, float low, float high) {
        this(owner, path, offset, kind, count, low, high, false);
    }

    private MaterialField(Class<?> owner, String path, int offset, Kind kind, int count, float low, float high,
        boolean texture) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.path = Objects.requireNonNull(path, "path");
        this.offset = offset;
        this.kind = kind;
        this.count = count;
        this.low = low;
        this.high = high;
        this.texture = texture;
    }

    public static MaterialField f32(Class<?> owner, String path, int offset) {
        return new MaterialField(owner, path, offset, Kind.F32, 1, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY);
    }

    public static MaterialField f32(Class<?> owner, String path, int offset, float low, float high) {
        return new MaterialField(owner, path, offset, Kind.F32, 1, low, high);
    }

    public static MaterialField i32(Class<?> owner, String path, int offset) {
        return new MaterialField(owner, path, offset, Kind.I32, 1, 0, 0);
    }

    /** A buffer field ({@code T*} in Slang): the address of a buffer, 0 for none. */
    public static MaterialField buffer(Class<?> owner, String path, int offset) {
        return new MaterialField(owner, path, offset, Kind.BUFFER, 1, 0, 0);
    }

    /** A {@code [Texture]} field: an {@link Kind#I32} holding a texture, or -1 for none. */
    public static MaterialField texture(Class<?> owner, String path, int offset) {
        return new MaterialField(owner, path, offset, Kind.I32, 1, 0, 0, true);
    }

    public static MaterialField floats(Class<?> owner, String path, int offset, int count) {
        return new MaterialField(owner, path, offset, Kind.FLOATS, count, 0, 0);
    }

    /** The record it is a field of. */
    public Class<?> owner() {
        return owner;
    }

    /** Its name, nested fields by path: {@code base.roughness}. */
    public String path() {
        return path;
    }

    /** Its byte offset in the record's block. */
    public int offset() {
        return offset;
    }

    public Kind kind() {
        return kind;
    }

    /** How many floats a {@link Kind#FLOATS} field has. */
    public int count() {
        return count;
    }

    /** Whether it holds a texture ({@code [Texture]}): {@link Materials} counts the materials naming each. */
    public boolean texture() {
        return texture;
    }

    /** Whether {@code value} is in the field's range ({@code [Range]}); every value of fields without one. */
    public boolean accepts(float value) {
        return value >= low && value <= high;
    }

    @Override
    public String toString() {
        return owner.getSimpleName() + "." + path;
    }
}
