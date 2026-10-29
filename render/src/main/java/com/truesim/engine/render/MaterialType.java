package com.truesim.engine.render;

import java.util.Objects;

/**
 * A kind of material, beyond the {@link #STANDARD standard} one: how its materials' data becomes a surface to light,
 * as a Slang {@code IMaterial} of the game's own, shaded through a compute entry point calling engine-render's
 * {@code visibility.resolve} {@code shadeTiles}. Lighting stays the engine's; the type's materials still use
 * {@link Material}'s 96 bytes, which its shader may read its own way.
 *
 * <pre>{@code
 * // water.slang, compiled by the game's truesim.slang build:
 * import visibility.resolve;
 * struct Water : IMaterial { static Surface surface(Material m, SurfaceInput in) { ... } };
 * [shader("compute")] [numthreads(8, 8, 1)]
 * void waterMain(uint3 group : SV_GroupID, uint3 thread : SV_GroupThreadID) { shadeTiles<Water>(NoRays(), group, thread); }
 *
 * int water = materials.register(new MaterialType("water", "shaders/water.spv", "waterMain"));
 * materials.add(new Material().type(water).baseColor(0.1f, 0.3f, 0.4f));
 * }</pre>
 *
 * <p>For now {@link VisibilityBufferShading} shades by type; forward shading draws every type as the standard one.
 *
 * @param name         what it is called, in errors and the pipeline cache
 * @param shader       the compiled shader's resource path, as {@link com.truesim.engine.vulkan.ShaderModule#load} takes
 * @param entry        its entry point, inline ray shadows counting as lit ({@code NoRays()})
 * @param tracedShader the shader of its entry point tracing inline ray shadows, on devices with ray tracing: one of
 *                     its own, as it imports {@code raytracing} (ray queries); null for none, the type then shaded
 *                     without them where they are on
 * @param tracedEntry  that entry point, calling {@code shadeTiles} with {@code QueryRays()}; null with no shader
 */
public record MaterialType(String name, String shader, String entry, String tracedShader, String tracedEntry) {

    /** Type 0, {@link Material}'s metallic-roughness: every material's unless given another. */
    public static final MaterialType STANDARD = new MaterialType("standard", "shaders/visibility/shade.spv",
        "shadeMain", "shaders/visibility/shadeTraced.spv", "shadeTracedMain");

    public MaterialType {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(shader, "shader");
        Objects.requireNonNull(entry, "entry");
        if ((tracedShader == null) != (tracedEntry == null)) {
            throw new IllegalArgumentException("A traced shader and its entry point, or neither: " + name);
        }
    }

    /** A type shaded without inline ray shadows. */
    public MaterialType(String name, String shader, String entry) {
        this(name, shader, entry, null, null);
    }
}
