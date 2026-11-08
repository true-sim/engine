package com.truesim.engine.render;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.render.layout.HooksConstants;
import com.truesim.engine.render.layout.MaterialsConstants;
import com.truesim.engine.render.materials.MetallicRoughnessParameters;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A material: its type's parameters (a generated record), the features it shows with theirs, and how it rasterizes.
 * Immutable; {@link Materials} copies it into the table.
 *
 * <pre>{@code
 * Material rock = new Material(MetallicRoughnessParameters.DEFAULTS.withRoughness(0.9f).withBaseColorTexture(albedo));
 * Material leaves = new Material(leafParameters).alphaMask(0.5f).doubleSided(true);
 * Material paint = new Material(carPaint).feature(ClearcoatParameters.DEFAULTS.withRoughness(0.05f));
 * int id = materials.add(rock);
 * }</pre>
 *
 * <p>Its type is that of its parameters' record: the one type registered for it, unless named. How it rasterizes:
 * opaque by default; {@link #alphaMask alpha-masked} (texels below the cutoff are not drawn, in every pass: shadows
 * too, by the parameters' {@code [MaskAlpha]} and {@code [MaskTexture]}); {@link #doubleSided double-sided} (back faces
 * drawn and lit as the front). The renderer draws each kind in a raster bin of its own.
 *
 * <p>Features are parts of the lighting any type allowing them gets: a material shows one where it has its
 * parameters. Materials without a feature never pay for it: the visibility buffer shades each combination of type and
 * features in use with a variant of its own, made when the combination first appears.
 *
 * @param type        its type, or null for the one registered for its parameters' record
 * @param parameters  its type's parameters
 * @param features    the features it shows, one per bit
 * @param doubleSided whether back faces show too, lit as the front
 * @param alphaCutoff below 0: opaque; else the alpha below which nothing is drawn
 * @param blended     whether it is drawn over what is behind it, by its alpha ({@link Transparency})
 * @param refusedHooks the shading hooks it refuses by name (hooks.slang: snow on an indoor floor, fog on a sky dome), of
 *                     any hook point; none run at its points, whatever overrides switch on
 * @param hookCoat    whether surface hooks may put a coat of their own on it (a water layer: a clear coat)
 * @param displacement the farthest its type's {@code displace()} moves a vertex ({@link #GEOMETRY_DISPLACE}), in its
 *                    mesh's units: bounds grow by it ({@link #displacement(float)}); NaN where not declared, which a
 *                    displacing type's material must not be
 */
@AllowAllocation("materials are made when they change")
public record Material(MaterialType type, MaterialParameters parameters, List<MaterialFeature> features,
    boolean doubleSided, float alphaCutoff, boolean blended, List<String> refusedHooks, boolean hookCoat,
    float displacement) {

    /** A clear coat: a glossy dielectric layer over the base, as car paint or varnish. */
    public static final int FEATURE_CLEARCOAT = MaterialsConstants.FEATURE_CLEARCOAT;
    /** Texture transforms: textures naming one ({@link TextureSampler#transform}) sample moved, turned, scaled UVs. */
    public static final int FEATURE_TEXTURE_TRANSFORM = MaterialsConstants.FEATURE_TEXTURE_TRANSFORM;
    /** Specular: a dielectric's reflectance, its strength and color, as glTF's {@code KHR_materials_specular}. */
    public static final int FEATURE_SPECULAR = MaterialsConstants.FEATURE_SPECULAR;
    /** IOR: the index of refraction a dielectric's reflectance follows, as glTF's {@code KHR_materials_ior}. */
    public static final int FEATURE_IOR = MaterialsConstants.FEATURE_IOR;
    /** Sheen: back-scattering at grazing angles, as cloth, as glTF's {@code KHR_materials_sheen}. */
    public static final int FEATURE_SHEEN = MaterialsConstants.FEATURE_SHEEN;
    /**
     * Transmission: light passing through, as glass, showing the scene behind, as glTF's
     * {@code KHR_materials_transmission}. Its materials are drawn with the blended ones ({@link Transparency}).
     */
    public static final int FEATURE_TRANSMISSION = MaterialsConstants.FEATURE_TRANSMISSION;
    /** Volume: a transmissive surface's inside, refracting and tinting, as glTF's {@code KHR_materials_volume}. */
    public static final int FEATURE_VOLUME = MaterialsConstants.FEATURE_VOLUME;
    /** Anisotropy: highlights stretched along a direction, as glTF's {@code KHR_materials_anisotropy}. */
    public static final int FEATURE_ANISOTROPY = MaterialsConstants.FEATURE_ANISOTROPY;
    /** Iridescence: a thin film's interference colors, as glTF's {@code KHR_materials_iridescence}. */
    public static final int FEATURE_IRIDESCENCE = MaterialsConstants.FEATURE_IRIDESCENCE;
    /** Dispersion: refraction spreading colors, with a volume, as glTF's {@code KHR_materials_dispersion}. */
    public static final int FEATURE_DISPERSION = MaterialsConstants.FEATURE_DISPERSION;
    /**
     * Diffuse transmission: light scattered through thin surfaces, as glTF's
     * {@code KHR_materials_diffuse_transmission}.
     */
    public static final int FEATURE_DIFFUSE_TRANSMISSION = MaterialsConstants.FEATURE_DIFFUSE_TRANSMISSION;
    /** Unlit: the surface shows its base color as it is, as glTF's {@code KHR_materials_unlit}. */
    public static final int FEATURE_UNLIT = MaterialsConstants.FEATURE_UNLIT;
    /**
     * The features every type allows, whatever its parameters say: how textures are read, and showing the type's
     * albedo unlit.
     */
    public static final int EVERY_TYPES_FEATURES = FEATURE_TEXTURE_TRANSFORM | FEATURE_UNLIT;
    /** How many features there are, as materials.slang has them. */
    static final int FEATURE_BITS = MaterialsConstants.FEATURE_BITS;
    /** Every feature bit. */
    static final int FEATURES = (1 << FEATURE_BITS) - 1;
    /**
     * A geometry hook of a material type ({@link MaterialType#geometry}): its {@code keep()} says which points of its
     * surfaces exist, in every pass (color, depth, shadows, IDs), as materials.slang's {@code GEOMETRY_KEEP}.
     */
    public static final int GEOMETRY_KEEP = MaterialsConstants.GEOMETRY_KEEP;
    /** Where geometry hooks start in a type's {@link MaterialType#allowedFeatures}. */
    public static final int GEOMETRY_SHIFT = MaterialsConstants.GEOMETRY_SHIFT;
    /**
     * A geometry hook of a material type: its {@code displace()} moves its vertices, in every pass reading its geometry
     * ({@link Displacement#RASTER}) or once a frame into the instance's copy of its mesh ({@link Displacement#COMPUTE}),
     * as materials.slang's {@code GEOMETRY_DISPLACE}. Its materials declare how far ({@link #displacement(float)}).
     */
    public static final int GEOMETRY_DISPLACE = MaterialsConstants.GEOMETRY_DISPLACE;
    /** Every geometry hook bit there is. */
    static final int GEOMETRY_HOOKS = GEOMETRY_KEEP | GEOMETRY_DISPLACE;

    /** Metallic-roughness's defaults: light gray, rough, opaque. Material 0 of every table. */
    public static final Material DEFAULT = new Material(MetallicRoughnessParameters.DEFAULTS);

    public Material {
        Objects.requireNonNull(parameters, "parameters");
        features = List.copyOf(features);
        refusedHooks = List.copyOf(new java.util.LinkedHashSet<>(refusedHooks));
        int seen = 0;
        for (MaterialFeature feature : features) {
            if (Integer.bitCount(feature.feature()) != 1 || (feature.feature() & ~FEATURES) != 0) {
                throw new IllegalArgumentException("No feature " + feature.feature() + ": " + feature);
            }
            if ((seen & feature.feature()) != 0) {
                throw new IllegalArgumentException("Feature " + feature.feature() + " twice");
            }
            seen |= feature.feature();
        }
        if (type != null && !type.parameters().isInstance(parameters)) {
            throw new IllegalArgumentException(type.name() + " takes " + type.parameters().getSimpleName() + ", not "
                + parameters.getClass().getSimpleName());
        }
        if (!(alphaCutoff < 0 || alphaCutoff <= 1)) {
            throw new IllegalArgumentException("A cutoff of 0 to 1: " + alphaCutoff);
        }
        if (blended && alphaCutoff >= 0) {
            throw new IllegalArgumentException("Masked or blended, not both");
        }
        if (!Float.isNaN(displacement) && !(displacement >= 0 && displacement < Float.POSITIVE_INFINITY)) {
            throw new IllegalArgumentException("A displacement of 0 or more: " + displacement);
        }
    }

    /** Its displacement not declared: as the canonical constructor with {@code displacement} NaN. */
    public Material(MaterialType type, MaterialParameters parameters, List<MaterialFeature> features,
        boolean doubleSided, float alphaCutoff, boolean blended, List<String> refusedHooks, boolean hookCoat) {
        this(type, parameters, features, doubleSided, alphaCutoff, blended, refusedHooks, hookCoat, Float.NaN);
    }

    /** No hook refused, no coat of theirs: as {@link #Material(MaterialType, MaterialParameters, List, boolean, float,
     * boolean, List, boolean)}. */
    public Material(MaterialType type, MaterialParameters parameters, List<MaterialFeature> features,
        boolean doubleSided, float alphaCutoff, boolean blended) {
        this(type, parameters, features, doubleSided, alphaCutoff, blended, List.of(), false);
    }

    /** Opaque or masked: as {@link #Material(MaterialType, MaterialParameters, List, boolean, float, boolean)}. */
    public Material(MaterialType type, MaterialParameters parameters, List<MaterialFeature> features,
        boolean doubleSided, float alphaCutoff) {
        this(type, parameters, features, doubleSided, alphaCutoff, false);
    }

    /** {@code parameters}, of the type registered for their record; opaque, one-sided, no features. */
    public Material(MaterialParameters parameters) {
        this(null, parameters, List.of(), false, -1, false);
    }

    /** {@code parameters} of type {@code type}: for records more than one type takes. */
    public Material(MaterialType type, MaterialParameters parameters) {
        this(Objects.requireNonNull(type, "type"), parameters, List.of(), false, -1, false);
    }

    /** This with {@code parameters} instead. */
    public Material parameters(MaterialParameters parameters) {
        return new Material(type, parameters, features, doubleSided, alphaCutoff, blended, refusedHooks, hookCoat, displacement);
    }

    /** This showing {@code feature}, instead of any parameters it had for it. */
    public Material feature(MaterialFeature feature) {
        List<MaterialFeature> with = new ArrayList<>(features.size() + 1);
        for (MaterialFeature f : features) {
            if (f.feature() != feature.feature()) {
                with.add(f);
            }
        }
        with.add(feature);
        with.sort((a, b) -> Integer.compare(a.feature(), b.feature()));
        return new Material(type, parameters, with, doubleSided, alphaCutoff, blended, refusedHooks, hookCoat, displacement);
    }

    /** This without feature {@code feature} (a {@code FEATURE_*} bit). */
    public Material withoutFeature(int feature) {
        List<MaterialFeature> without = new ArrayList<>(features);
        without.removeIf(f -> f.feature() == feature);
        return new Material(type, parameters, without, doubleSided, alphaCutoff, blended, refusedHooks, hookCoat, displacement);
    }

    /** Whether back faces show too, lit as the front: leaves, cloth, thin walls. */
    public Material doubleSided(boolean twoSided) {
        return new Material(type, parameters, features, twoSided, alphaCutoff, blended, refusedHooks, hookCoat, displacement);
    }

    /**
     * Alpha-masked: where the parameters' mask alpha (times their mask texture's, times the vertex color's) is below
     * {@code cutoff}, nothing is drawn, the rest is opaque, as glTF's {@code MASK}: foliage, fences. In every pass.
     */
    public Material alphaMask(float cutoff) {
        if (!(cutoff >= 0 && cutoff <= 1)) {
            throw new IllegalArgumentException("A cutoff of 0 to 1: " + cutoff);
        }
        return new Material(type, parameters, features, doubleSided, cutoff, false, refusedHooks, hookCoat, displacement);
    }

    /** Opaque again: alpha is neither compared nor blended. */
    public Material opaque() {
        return new Material(type, parameters, features, doubleSided, -1, false, refusedHooks, hookCoat, displacement);
    }

    /**
     * Alpha-blended, as glTF's {@code BLEND}: drawn over what is behind it by its alpha (the type's, times the vertex
     * color's), after the opaque surfaces, as {@link Transparency#MODE} says; glass, smoke, fades.
     */
    public Material alphaBlend() {
        return new Material(type, parameters, features, doubleSided, -1, true, refusedHooks, hookCoat, displacement);
    }

    public boolean masked() {
        return alphaCutoff >= 0;
    }

    /** Whether it shows feature {@code feature} (a {@code FEATURE_*} bit). */
    public boolean shows(int feature) {
        return (featureMask() & feature) != 0;
    }

    /**
     * Whether it is drawn after the opaque surfaces, with the blended ones ({@link Transparency}): alpha-blended, or
     * transmissive (it shows the scene behind it).
     */
    public boolean drawnBlended() {
        return blended || shows(FEATURE_TRANSMISSION);
    }

    /**
     * This refusing the shading hooks {@code hooks} (by name, as their {@code [ShadingHook("name")]}; of any point:
     * surface, indirect, media) besides those it refuses already: they never run at its points, whatever an override
     * switches on. Names the shaders were built without are ignored.
     */
    public Material refuseHooks(String... hooks) {
        List<String> more = new ArrayList<>(refusedHooks);
        more.addAll(List.of(hooks));
        return new Material(type, parameters, features, doubleSided, alphaCutoff, blended, more, hookCoat, displacement);
    }

    /** This refusing no shading hook. */
    public Material acceptHooks() {
        return new Material(type, parameters, features, doubleSided, alphaCutoff, blended, List.of(), hookCoat, displacement);
    }

    /** Whether it refuses shading hook {@code hook}. */
    public boolean refuses(String hook) {
        return refusedHooks.contains(hook);
    }

    /** This letting surface hooks put a coat of their own on it (a water layer), or not. */
    public Material hookCoat(boolean on) {
        return new Material(type, parameters, features, doubleSided, alphaCutoff, blended, refusedHooks, on,
            displacement);
    }

    /**
     * This declaring that its type's {@code displace()} ({@link #GEOMETRY_DISPLACE}) moves a vertex at most
     * {@code distance} (in its mesh's units, as the vertices): culling grows its meshlets' bounds by it (and its
     * instances' by the table's largest), shadow maps the casters' reach; a displacement past it may be culled away. A
     * displacing type's materials must declare it; other types' ignore it.
     */
    public Material displacement(float distance) {
        return new Material(type, parameters, features, doubleSided, alphaCutoff, blended, refusedHooks, hookCoat,
            distance);
    }

    /** The features it shows, {@code FEATURE_*} bits. */
    public int featureMask() {
        int mask = 0;
        for (MaterialFeature feature : features) {
            mask |= feature.feature();
        }
        return mask;
    }

    /**
     * The header word shaders read, for type id {@code typeId}: type in bits 0-7, double-sided bit 8, masked bit 9,
     * blended bit 10, refuses hooks bit 11 (their words after its feature table), hook coat bit 12 (hooks.slang),
     * displaces bit 13 (its type has {@link #GEOMETRY_DISPLACE}: {@code displaces}), cutoff 16-31.
     */
    int header(int typeId, boolean displaces) {
        int header = typeId | (doubleSided ? 1 << 8 : 0) | (blended ? 1 << 10 : 0)
            | (displaces ? MaterialsConstants.MATERIAL_DISPLACES : 0)
            | (refusedHooks.isEmpty() ? 0 : HooksConstants.MATERIAL_REFUSES_HOOKS)
            | (hookCoat ? HooksConstants.MATERIAL_HOOK_COAT : 0);
        if (alphaCutoff >= 0) {
            header |= 1 << 9 | Math.round(alphaCutoff * 65_535) << 16;
        }
        return header;
    }
}
