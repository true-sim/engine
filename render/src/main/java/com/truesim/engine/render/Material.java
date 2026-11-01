package com.truesim.engine.render;

import com.truesim.annotations.AllowAllocation;
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
 */
@AllowAllocation("materials are made when they change")
public record Material(MaterialType type, MaterialParameters parameters, List<MaterialFeature> features,
    boolean doubleSided, float alphaCutoff) {

    /** A clear coat: a glossy dielectric layer over the base, as car paint or varnish. */
    public static final int FEATURE_CLEARCOAT = MaterialsConstants.FEATURE_CLEARCOAT;
    /** Texture transforms: textures naming one ({@link TextureSampler#transform}) sample moved, turned, scaled UVs. */
    public static final int FEATURE_TEXTURE_TRANSFORM = MaterialsConstants.FEATURE_TEXTURE_TRANSFORM;
    /** The features every type allows, whatever its parameters say: they act on how textures are read. */
    public static final int EVERY_TYPES_FEATURES = FEATURE_TEXTURE_TRANSFORM;
    /** How many features there are, as materials.slang has them. */
    static final int FEATURE_BITS = MaterialsConstants.FEATURE_BITS;
    /** Every feature bit. */
    static final int FEATURES = (1 << FEATURE_BITS) - 1;

    /** Metallic-roughness's defaults: light gray, rough, opaque. Material 0 of every table. */
    public static final Material DEFAULT = new Material(MetallicRoughnessParameters.DEFAULTS);

    public Material {
        Objects.requireNonNull(parameters, "parameters");
        features = List.copyOf(features);
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
    }

    /** {@code parameters}, of the type registered for their record; opaque, one-sided, no features. */
    public Material(MaterialParameters parameters) {
        this(null, parameters, List.of(), false, -1);
    }

    /** {@code parameters} of type {@code type}: for records more than one type takes. */
    public Material(MaterialType type, MaterialParameters parameters) {
        this(Objects.requireNonNull(type, "type"), parameters, List.of(), false, -1);
    }

    /** This with {@code parameters} instead. */
    public Material parameters(MaterialParameters parameters) {
        return new Material(type, parameters, features, doubleSided, alphaCutoff);
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
        return new Material(type, parameters, with, doubleSided, alphaCutoff);
    }

    /** This without feature {@code feature} (a {@code FEATURE_*} bit). */
    public Material withoutFeature(int feature) {
        List<MaterialFeature> without = new ArrayList<>(features);
        without.removeIf(f -> f.feature() == feature);
        return new Material(type, parameters, without, doubleSided, alphaCutoff);
    }

    /** Whether back faces show too, lit as the front: leaves, cloth, thin walls. */
    public Material doubleSided(boolean twoSided) {
        return new Material(type, parameters, features, twoSided, alphaCutoff);
    }

    /**
     * Alpha-masked: where the parameters' mask alpha (times their mask texture's, times the vertex color's) is below
     * {@code cutoff}, nothing is drawn, the rest is opaque, as glTF's {@code MASK}: foliage, fences. In every pass.
     */
    public Material alphaMask(float cutoff) {
        if (!(cutoff >= 0 && cutoff <= 1)) {
            throw new IllegalArgumentException("A cutoff of 0 to 1: " + cutoff);
        }
        return new Material(type, parameters, features, doubleSided, cutoff);
    }

    /** Opaque again: alpha is not compared. */
    public Material opaque() {
        return new Material(type, parameters, features, doubleSided, -1);
    }

    public boolean masked() {
        return alphaCutoff >= 0;
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
     * cutoff 16-31.
     */
    int header(int typeId) {
        int header = typeId | (doubleSided ? 1 << 8 : 0);
        if (alphaCutoff >= 0) {
            header |= 1 << 9 | Math.round(alphaCutoff * 65_535) << 16;
        }
        return header;
    }
}
