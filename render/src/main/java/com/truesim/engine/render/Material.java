package com.truesim.engine.render;

/**
 * A physically based material, metallic-roughness as in glTF: base color, metalness, roughness, a normal map,
 * occlusion and emission, each a factor times an optional texture (a sampled slot from {@link Textures}). Set it up,
 * then add it to {@link Materials}, which copies it: it may be changed and added again.
 *
 * <pre>{@code
 * Material rock = new Material().baseColor(0.5f, 0.45f, 0.4f).roughness(0.9f)
 *     .baseColorTexture(albedo).normalTexture(normals);
 * int id = materials.add(rock);
 * }</pre>
 */
public final class Material {

    float r = 0.8f;
    float g = 0.8f;
    float b = 0.8f;
    float a = 1;
    float er;
    float eg;
    float eb;
    float roughness = 0.7f;
    float metallic;
    float normalScale = 1;
    float occlusion = 1;
    int baseColorTexture = -1;
    int normalTexture = -1;
    int ormTexture = -1;
    int emissiveTexture = -1;

    /** The base color, linear: diffuse color, or a metal's reflectance. Light gray by default. */
    public Material baseColor(float r, float g, float b) {
        this.r = r;
        this.g = g;
        this.b = b;
        return this;
    }

    /** Perceptual roughness, 0 (mirror) to 1; 0.7 by default. With an ORM texture, times its green. */
    public Material roughness(float roughness) {
        this.roughness = roughness;
        return this;
    }

    /** 0 (dielectric, the default) to 1 (metal). With an ORM texture, times its blue. */
    public Material metallic(float metallic) {
        this.metallic = metallic;
        return this;
    }

    /** Light the surface gives off, linear; none by default. With an emissive texture, times it. */
    public Material emissive(float r, float g, float b) {
        this.er = r;
        this.eg = g;
        this.eb = b;
        return this;
    }

    /** An sRGB base color texture, times the base color. */
    public Material baseColorTexture(int slot) {
        this.baseColorTexture = slot;
        return this;
    }

    /** A tangent-space normal map (xyz in rgb), bent by {@code scale} (1: as it is). Needs tangents on the mesh. */
    public Material normalTexture(int slot, float scale) {
        this.normalTexture = slot;
        this.normalScale = scale;
        return this;
    }

    /** Occlusion, roughness and metalness in red, green and blue; occlusion darkens ambient light by {@code strength}. */
    public Material ormTexture(int slot, float strength) {
        this.ormTexture = slot;
        this.occlusion = strength;
        return this;
    }

    /** An sRGB emissive texture, times the emissive color. */
    public Material emissiveTexture(int slot) {
        this.emissiveTexture = slot;
        return this;
    }
}
