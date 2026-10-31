package com.truesim.engine.render;

import com.truesim.common.math.Vec2;
import com.truesim.common.math.Vec3;
import com.truesim.common.math.Vec4;
import com.truesim.common.math.color.Color;

/**
 * Material data by field name, as generated records read it ({@code fromValues}): e.g. a scene's material asset. Each
 * method gives the value of field {@code name}, or {@code fallback} where the data has none.
 */
public interface MaterialValues {

    float f32(String name, float fallback);

    int i32(String name, int fallback);

    Vec2 vec2(String name, Vec2 fallback);

    Vec3 vec3(String name, Vec3 fallback);

    Vec4 vec4(String name, Vec4 fallback);

    Color color(String name, Color fallback);

    /** The texture slot of field {@code name}, a texture in color space sRGB or linear; {@code fallback} for none. */
    int texture(String name, boolean srgb, int fallback);

    /** The values of nested record field {@code name}. */
    MaterialValues nested(String name);

    /** The buffer of field {@code name} ({@code T*} in Slang); {@code fallback} for none, as by default. */
    default com.truesim.engine.vulkan.DeviceBuffer buffer(String name, com.truesim.engine.vulkan.DeviceBuffer fallback) {
        return fallback;
    }

    /** No values: every field its fallback. */
    MaterialValues NONE = new MaterialValues() {
        @Override
        public float f32(String name, float fallback) {
            return fallback;
        }

        @Override
        public int i32(String name, int fallback) {
            return fallback;
        }

        @Override
        public Vec2 vec2(String name, Vec2 fallback) {
            return fallback;
        }

        @Override
        public Vec3 vec3(String name, Vec3 fallback) {
            return fallback;
        }

        @Override
        public Vec4 vec4(String name, Vec4 fallback) {
            return fallback;
        }

        @Override
        public Color color(String name, Color fallback) {
            return fallback;
        }

        @Override
        public int texture(String name, boolean srgb, int fallback) {
            return fallback;
        }

        @Override
        public MaterialValues nested(String name) {
            return this;
        }
    };
}
