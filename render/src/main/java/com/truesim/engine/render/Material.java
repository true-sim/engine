package com.truesim.engine.render;

/**
 * A physically based material, metallic-roughness as in glTF: base color, metalness, roughness, a normal map,
 * occlusion and emission, each a factor times an optional texture (a sampled slot from {@link Textures}). Set it up,
 * then add it to {@link Materials}, which copies it: it may be changed and added again.
 *
 * <pre>{@code
 * Material rock = new Material().baseColor(0.5f, 0.45f, 0.4f).roughness(0.9f)
 *     .baseColorTexture(albedo).normalTexture(normals);
 * Material leaves = new Material().baseColorTexture(leafAlbedo).alphaMask(0.5f).doubleSided(true);
 * int id = materials.add(rock);
 * }</pre>
 *
 * <p>How it rasterizes, its header: opaque by default; {@link #alphaMask alpha-masked} (texels below the cutoff are not
 * drawn, in every pass: shadows too); {@link #doubleSided double-sided} (back faces drawn and lit as the front). The
 * renderer draws each kind in a raster bin of its own; ordinary opaque materials keep the fastest one. And its
 * {@link #type type}: {@link MaterialType#STANDARD standard} by default, or one registered with {@link Materials}.
 *
 * <p>Features, optional parts of the shading, follow from the data: a {@link #clearcoat clear coat} shows where its
 * strength is above 0. Materials without a feature never pay for it: the visibility buffer shades each combination of
 * features with a variant of its own.
 */
public final class Material {

    /** A clear coat: a glossy dielectric layer over the base, as car paint or varnish. */
    public static final int FEATURE_CLEARCOAT = 1;
    /** How many feature bits there are: combinations are {@code 1 << FEATURE_BITS}, as shading.slang has them. */
    static final int FEATURE_BITS = 1;

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
    float alphaCutoff = -1; // below 0: opaque
    boolean doubleSided;
    int type;
    float clearcoat;
    float clearcoatRoughness;
    int clearcoatTexture = -1;
    int clearcoatRoughnessTexture = -1;
    int clearcoatNormalTexture = -1;
    float clearcoatNormalScale = 1;

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

    /** The base color's alpha, times the texture's: what {@link #alphaMask} compares. 1 by default. */
    public Material alpha(float alpha) {
        this.a = alpha;
        return this;
    }

    /**
     * Alpha-masked: where the base color's alpha (factor times texture times vertex color) is below {@code cutoff},
     * nothing is drawn, the rest is opaque, as glTF's {@code MASK}: foliage, fences. Masked in every pass, shadow maps
     * included.
     */
    public Material alphaMask(float cutoff) {
        if (!(cutoff >= 0 && cutoff <= 1)) {
            throw new IllegalArgumentException("A cutoff of 0 to 1: " + cutoff);
        }
        this.alphaCutoff = cutoff;
        return this;
    }

    /** Opaque again: alpha is not compared. */
    public Material opaque() {
        this.alphaCutoff = -1;
        return this;
    }

    /** Whether back faces show too, lit as the front: leaves, cloth, thin walls. Not by default. */
    public Material doubleSided(boolean twoSided) {
        this.doubleSided = twoSided;
        return this;
    }

    /**
     * Its {@link MaterialType}: an id {@link Materials#register} returned, 0 (standard, the default) to 255. A type the
     * table has not registered shades as the standard one.
     */
    public Material type(int id) {
        if (id < 0 || id > Materials.MAX_TYPES - 1) {
            throw new IllegalArgumentException("A material type of 0 to " + (Materials.MAX_TYPES - 1) + ": " + id);
        }
        this.type = id;
        return this;
    }

    public int type() {
        return type;
    }

    public boolean masked() {
        return alphaCutoff >= 0;
    }

    public boolean isDoubleSided() {
        return doubleSided;
    }

    /**
     * The header word shaders read: type (0, standard) in bits 0-7, double-sided bit 8, masked bit 9, features 10-15,
     * cutoff 16-31.
     */
    int header() {
        int header = type | (doubleSided ? 1 << 8 : 0) | features() << 10;
        if (alphaCutoff >= 0) {
            header |= 1 << 9 | Math.round(alphaCutoff * 65_535) << 16;
        }
        return header;
    }

    /**
     * A clear coat as glTF's {@code KHR_materials_clearcoat}: a dielectric layer of {@code strength} (0, none, the
     * default, to 1) and perceptual {@code roughness} over the base, reflecting light before it reaches it. Following
     * the geometry's normal, unless given {@link #clearcoatNormalTexture a normal map of its own}.
     */
    public Material clearcoat(float strength, float roughness) {
        this.clearcoat = strength;
        this.clearcoatRoughness = roughness;
        return this;
    }

    /** A linear texture whose red times the clear coat's strength. */
    public Material clearcoatTexture(int slot) {
        this.clearcoatTexture = slot;
        return this;
    }

    /** A linear texture whose green times the clear coat's roughness (often the same as {@link #clearcoatTexture}). */
    public Material clearcoatRoughnessTexture(int slot) {
        this.clearcoatRoughnessTexture = slot;
        return this;
    }

    /** The clear coat's tangent-space normal map, bent by {@code scale}. Needs tangents on the mesh. */
    public Material clearcoatNormalTexture(int slot, float scale) {
        this.clearcoatNormalTexture = slot;
        this.clearcoatNormalScale = scale;
        return this;
    }

    /** The features it shows, {@code FEATURE_*} bits: from its data. */
    public int features() {
        return clearcoat > 0 ? FEATURE_CLEARCOAT : 0;
    }

    /** An sRGB emissive texture, times the emissive color. */
    public Material emissiveTexture(int slot) {
        this.emissiveTexture = slot;
        return this;
    }
}
