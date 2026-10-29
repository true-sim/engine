package com.truesim.engine.render;

import java.util.Objects;
import java.util.function.Function;

/**
 * A kind of material: how its parameters become a surface to light, a Slang {@code IMaterial}. Lighting, and the
 * features, stay the engine's. Generated from a {@code [MaterialType("name")]} struct with its entry points (its
 * class's {@code TYPE}, e.g. {@link com.truesim.engine.render.materials.MetallicRoughnessMaterial#TYPE}), then
 * {@link Materials#register registered}. Type 0 of every table is metallic-roughness, as glTF.
 *
 *
 * <pre>{@code
 * // water.slang, built with truesim.slang:
 * [MaterialParameters(FEATURE_CLEARCOAT)]
 * public struct WaterParameters { [Color] public float3 deep = float3(0.02, 0.1, 0.15); };
 * [MaterialType("water")]
 * public struct Water : IMaterial
 * {
 *     public typealias Parameters = WaterParameters;
 *     public static Surface surface(WaterParameters p, MaterialRef m, SurfaceInput in) { ... }
 * };
 *
 * materials.register(Water.TYPE);
 * int lake = materials.add(new Material(WaterParameters.DEFAULTS));
 * }</pre>
 *
 * <p>For now {@link VisibilityBufferShading} shades by type; forward shading draws other types as metallic-roughness's
 * defaults.
 *
 * @param name            what it is called: in material data, errors and the pipeline cache
 * @param parameters      its parameters' record
 * @param fromValues      builds its parameters from data by field name (the record's {@code fromValues})
 * @param allowedFeatures the {@code Material.FEATURE_*} bits its materials may show
 * @param shader          the shader of its entry point, a resource path as
 *                        {@link com.truesim.engine.vulkan.ShaderModule#load} takes
 * @param entry           its entry point, inline ray shadows counting as lit
 * @param tracedShader    the shader of its entry point tracing inline ray shadows (ray queries), or null for none:
 *                        then it is shaded without them where they are on
 * @param tracedEntry     that entry point; null with no shader
 */
public record MaterialType(String name, Class<? extends MaterialParameters> parameters,
    Function<MaterialValues, ? extends MaterialParameters> fromValues, int allowedFeatures, String shader,
    String entry, String tracedShader, String tracedEntry) {

    public MaterialType {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(parameters, "parameters");
        Objects.requireNonNull(fromValues, "fromValues");
        Objects.requireNonNull(shader, "shader");
        Objects.requireNonNull(entry, "entry");
        if ((tracedShader == null) != (tracedEntry == null)) {
            throw new IllegalArgumentException("A traced shader and its entry point, or neither: " + name);
        }
        if ((allowedFeatures & ~Material.FEATURES) != 0) {
            throw new IllegalArgumentException(name + " allows features there are none of: " + allowedFeatures);
        }
    }
}
