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
 * <p>Every pass shading color draws a type with its own entry points: the visibility buffer's shading, and forward
 * shading on every geometry path (indexed, mesh shaders, classic LOD), where each shading bin (a type and its
 * features) draws with pipelines of its type's forward entry points, its blended surfaces too. Depth, shadow and ID
 * passes draw every type alike, but types with geometry hooks ({@link #geometry}): those draw there with pipelines of
 * their own, so a cutout's holes are alike in color, depth and shadows. Ray hits are shaded by type as well: the
 * reference path tracer sorts its hits by shading bin and shades each bin with its type's hit entry point.
 *
 * @param name            what it is called: in material data, errors and the pipeline cache
 * @param parameters      its parameters' record
 * @param fromValues      builds its parameters from data by field name (the record's {@code fromValues})
 * @param allowedFeatures the {@code Material.FEATURE_*} bits its materials may show, and from bit
 *                        {@link Material#GEOMETRY_SHIFT} its geometry hooks ({@code Material.GEOMETRY_*}): its
 *                        parameters' attribute, as the build reads it
 * @param shader          the shader of its entry point, a resource path as
 *                        {@link com.truesim.engine.vulkan.ShaderModule#load} takes
 * @param entry           its entry point
 * @param forwardShader   the shader of its forward fragment entry points from full vertices ({@code <name>FragmentMain},
 *                        {@code <name>MaskedMain}, {@code <name>BlendedMain}): the indexed path, classic LOD and mesh
 *                        shaders without barycentrics
 * @param forwardLeanShader the shader of its forward fragment entry points on the lean mesh path
 *                        ({@code <name>LeanMain}, {@code <name>LeanMaskedMain}, {@code <name>LeanBlendedMain})
 * @param geometryShader  the shader of its depth and ID fragment entry points ({@code <name>DepthMain},
 *                        {@code <name>MaskedDepthMain}, {@code <name>IdsMain}, {@code <name>MaskedIdsMain},
 *                        {@code <name>ClassicIdsMain}, {@code <name>MaskedClassicIdsMain}), drawn where it has geometry
 *                        hooks
 * @param hitShader       the shader of its shading at ray hits ({@code <name>HitMain}, ray queries): the paths whose
 *                        hits show its materials, each shading bin by a dispatch of its own
 *                        ({@link ReferencePathTracer}); null: its hits are not shaded (devices without ray tracing
 *                        never load it)
 * @param hitEntry        that entry point; null with no shader
 */
public record MaterialType(String name, Class<? extends MaterialParameters> parameters,
    Function<MaterialValues, ? extends MaterialParameters> fromValues, int allowedFeatures, String shader,
    String entry, String forwardShader, String forwardLeanShader,
    String geometryShader, String hitShader, String hitEntry) {

    public MaterialType {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(parameters, "parameters");
        Objects.requireNonNull(fromValues, "fromValues");
        Objects.requireNonNull(shader, "shader");
        Objects.requireNonNull(entry, "entry");
        Objects.requireNonNull(forwardShader, "forwardShader");
        Objects.requireNonNull(forwardLeanShader, "forwardLeanShader");
        Objects.requireNonNull(geometryShader, "geometryShader");
        if ((hitShader == null) != (hitEntry == null)) {
            throw new IllegalArgumentException("A hit shader and its entry point, or neither: " + name);
        }
        if ((allowedFeatures & ~(Material.FEATURES | Material.GEOMETRY_HOOKS)) != 0) {
            throw new IllegalArgumentException(name + " allows features there are none of: " + allowedFeatures);
        }
    }

    /**
     * Its geometry hooks ({@code Material.GEOMETRY_*}, e.g. {@link Material#GEOMETRY_KEEP}: its {@code keep()} makes
     * holes): parts of the type deciding where its surfaces are, so they run in every pass drawing it, with depth,
     * shadow and ID pipelines of its own. 0: none; it shares those passes' pipelines with every other such type.
     */
    public int geometry() {
        return allowedFeatures & Material.GEOMETRY_HOOKS;
    }
}
