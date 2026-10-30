package com.truesim.engine.vulkan;

import com.truesim.annotations.AllowAllocation;

/**
 * Push constants written field by field in the order a shader's push struct declares them, aligned as Slang lays them
 * out for Vulkan (std430): 4-byte scalars on 4 bytes, 8-byte ones and pointers on 8, two-component vectors on 8,
 * three- and four-component ones on 16. Made once (a pass keeps one), {@link #clear cleared} and filled each time it
 * records, so recording allocates nothing.
 *
 * <pre>{@code
 * // struct Push { float4* particles; uint count; float dt; float3 gravity; };
 * Push push = new Push();                                  // once
 * push.clear().address(ctx.address(particles)).u32(count).f32(dt).vec3(0, -9.81f, 0);
 * ctx.dispatch(pipeline, push, (count + 63) / 64);         // or pipeline.push(commands, push)
 * }</pre>
 *
 * <p>A struct inside the push struct aligns to its largest member: {@link #align} before its first field. Holds
 * {@value #MAX_BYTES} bytes, the most push constants every device takes. Use it from one thread at a time.
 */
@AllowAllocation("made once")
public final class Push extends PushConstants {

    private int size;

    /** Empties it for the next fields. Allocates nothing. */
    public Push clear() {
        size = 0;
        return this;
    }

    /** A 32-bit unsigned (or signed) integer: {@code uint}, {@code int}. */
    public Push u32(int value) {
        putInt(at(4, 4), value);
        return this;
    }

    /** A 32-bit float. */
    public Push f32(float value) {
        putFloat(at(4, 4), value);
        return this;
    }

    /** A 64-bit integer: {@code uint64_t}. */
    public Push u64(long value) {
        putLong(at(8, 8), value);
        return this;
    }

    /** A pointer, by device address: {@code T*}. */
    public Push address(long deviceAddress) {
        return u64(deviceAddress);
    }

    /** A {@code float2}. */
    public Push vec2(float x, float y) {
        long o = at(8, 8);
        putFloat(o, x);
        putFloat(o + 4, y);
        return this;
    }

    /** A {@code float3}: 12 bytes on 16; a 4-byte field may follow in its last 4. */
    public Push vec3(float x, float y, float z) {
        long o = at(16, 12);
        putFloat(o, x);
        putFloat(o + 4, y);
        putFloat(o + 8, z);
        return this;
    }

    /** A {@code float4}. */
    public Push vec4(float x, float y, float z, float w) {
        long o = at(16, 16);
        putFloat(o, x);
        putFloat(o + 4, y);
        putFloat(o + 8, z);
        putFloat(o + 12, w);
        return this;
    }

    /** A {@code uint2}. */
    public Push uvec2(int x, int y) {
        long o = at(8, 8);
        putInt(o, x);
        putInt(o + 4, y);
        return this;
    }

    /** A {@code float4x4}, column after column (as shaders compiled column-major read it). */
    public Push mat4(float[] columnMajor) {
        if (columnMajor.length != 16) {
            throw new IllegalArgumentException("A 4 x 4 matrix has 16 floats: " + columnMajor.length);
        }
        long o = at(16, 64);
        for (int i = 0; i < 16; i++) {
            putFloat(o + 4L * i, columnMajor[i]);
        }
        return this;
    }

    /** Moves to the next multiple of {@code alignment}: where a nested struct of that alignment starts. */
    public Push align(int alignment) {
        if (alignment < 1 || Integer.bitCount(alignment) != 1) {
            throw new IllegalArgumentException("Alignments are powers of two: " + alignment);
        }
        at(alignment, 0);
        return this;
    }

    /** The bytes written, the last field's end. */
    @Override
    public int size() {
        return size;
    }

    /** Where the next field of {@code bytes}, aligned to {@code alignment}, goes; it is taken. */
    private long at(int alignment, int bytes) {
        int start = (size + alignment - 1) & -alignment;
        if (start + bytes > MAX_BYTES) {
            throw new IllegalStateException("Push constants take at most " + MAX_BYTES + " bytes: " + (start + bytes));
        }
        size = start + bytes;
        return start;
    }
}
