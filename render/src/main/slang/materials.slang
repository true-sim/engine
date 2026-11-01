// Materials: the table every pass reads, and how material types make surfaces (shading.slang lights them).
//
// The table (engine-render's Materials) is a slot of 32 bytes per material, then the materials' parameter blocks. A
// slot says how the material rasterizes (header, mask), where its block lies (its type's parameters, then a section
// per feature it shows, then a table of those sections), which features it shows, and its shading bin. Passes that
// only rasterize (depth, shadows, IDs) read the slot alone, whatever the type.
//
// A material type is an IMaterial with the struct of its parameters; a feature (FEATURE_*) is a part of the lighting
// any type allowing it gets, with its own parameters. Their structs are marked for the build, which generates their
// Java records from them (truesim.slang): the struct is the one source of truth, and slangc's layout the layout.
//
//     [MaterialParameters(FEATURE_CLEARCOAT)]          // the features the type allows
//     struct WaterParameters { [Color] float3 deep = float3(0.02, 0.1, 0.15); [Range(0, 4)] float waves = 1; };
//
//     [MaterialType("water")]                          // the visibility buffer's entry points come generated
//     struct Water : IMaterial
//     {
//         typealias Parameters = WaterParameters;
//         static Surface surface(WaterParameters p, MaterialRef m, SurfaceInput in)
//         {
//             Surface s = Surface.defaults(normalize(in.normal)); // every field set, then the type's own
//             ...
//         }
//     };
module materials;

__exported import bindless;
__exported import shading;

// ---------------------------------------------------------------------------------------------- what the build reads

/** A material type's parameters, allowing the features of the mask `features` (FEATURE_*). */
[__AttributeUsage(_AttributeTargets.Struct)]
public struct MaterialParametersAttribute { int features; };

/** A feature's parameters: the section of FEATURE_* `feature`. */
[__AttributeUsage(_AttributeTargets.Struct)]
public struct MaterialFeatureAttribute { int feature; };

/** A material type called `name` (an IMaterial): the build generates its entry points and its Java MaterialType. */
[__AttributeUsage(_AttributeTargets.Struct)]
public struct MaterialTypeAttribute { string name; };

/** A float or integer field's range, checked in Java. */
[__AttributeUsage(_AttributeTargets.Var)]
public struct RangeAttribute { float low; float high; };

/** A float3 or float4 field that is a color (linear): a Color in Java. */
[__AttributeUsage(_AttributeTargets.Var)]
public struct ColorAttribute {};

/** An int field that is a texture slot (-1: none), of color space SRGB or LINEAR. */
[__AttributeUsage(_AttributeTargets.Var)]
public struct TextureAttribute { int colorSpace; };

/** The field (a float, or a float4's alpha) that alpha masking compares; the slot gets a copy. */
[__AttributeUsage(_AttributeTargets.Var)]
public struct MaskAlphaAttribute {};

/** The texture (its alpha) that alpha masking compares; the slot gets a copy. */
[__AttributeUsage(_AttributeTargets.Var)]
public struct MaskTextureAttribute {};

public static const int LINEAR = 0;
public static const int SRGB = 1;

// ---------------------------------------------------------------------------------------------- the slot

/** One material's slot: 32 bytes. Offsets count from the table's start. */
[HostLayout]
public struct Material
{
    public uint header;            // type bits 0-7 (0: metallic-roughness), double-sided bit 8, masked bit 9,
                                   // blended bit 10, cutoff bits 16-31
    public uint parameters;        // its block: its type's parameters, then its features' sections
    public int maskTexture;        // masking: the texture whose alpha it compares (-1: none)
    public float maskAlpha;        // masking: the alpha factor
    public uint geometry;          // a geometry block (displacement, to come); 0: none
    public uint features;          // the FEATURE_* bits it shows
    public uint featureTable;      // from its block's start: a uint per feature it shows, in bit order, each the
                                   // offset of the feature's section from the block's start
    public uint bin;               // its shading bin: its type and features, as the table numbers them
};

/** Features: parts of the lighting a material may show, each a bit of its features and a section of its block. */
public static const uint FEATURE_CLEARCOAT = 1;
/** Texture transforms (glTF's KHR_texture_transform): textures naming one are sampled at moved, turned, scaled UVs. */
public static const uint FEATURE_TEXTURE_TRANSFORM = 2;
/** Unlit (glTF's KHR_materials_unlit): the surface shows its base color as it is. */
public static const uint FEATURE_UNLIT = 4;
/** Specular (glTF's KHR_materials_specular): a dielectric's reflectance, its strength and color. */
public static const uint FEATURE_SPECULAR = 8;
/** IOR (glTF's KHR_materials_ior): the index of refraction a dielectric's reflectance follows. */
public static const uint FEATURE_IOR = 16;
/** Sheen (glTF's KHR_materials_sheen): back-scattering at grazing angles, as cloth and velvet. */
public static const uint FEATURE_SHEEN = 32;
/**
 * Transmission (glTF's KHR_materials_transmission): light passing through the surface, as glass, from the scene behind
 * it. Its materials are drawn with the blended ones, after the opaque scene, whose color they sample.
 */
public static const uint FEATURE_TRANSMISSION = 64;
/** Volume (glTF's KHR_materials_volume): a transmissive surface's inside, refracting and tinting what passes. */
public static const uint FEATURE_VOLUME = 128;
/** Anisotropy (glTF's KHR_materials_anisotropy): highlights stretched along a direction, as brushed metal. */
public static const uint FEATURE_ANISOTROPY = 256;
/** Iridescence (glTF's KHR_materials_iridescence): a thin film's colors, as soap bubbles and oil. */
public static const uint FEATURE_IRIDESCENCE = 512;
/** Dispersion (glTF's KHR_materials_dispersion): refraction spreading colors, as prisms and gems (with a volume). */
public static const uint FEATURE_DISPERSION = 1024;
/**
 * Diffuse transmission (glTF's KHR_materials_diffuse_transmission): light scattered through thin surfaces, as leaves,
 * paper and lampshades.
 */
public static const uint FEATURE_DIFFUSE_TRANSMISSION = 2048;
/** How many features there are: their bits are the low FEATURE_BITS of a mask. */
public static const uint FEATURE_BITS = 12;

/**
 * A texture reference, as materials' texture fields hold them: slot bits 0-15, sampler 16-20, bit 21 the second UV set,
 * bits 22-25 the transform (1-8, of the material's FEATURE_TEXTURE_TRANSFORM; 0: none). -1: no texture.
 */
public static const uint TEXTURE_UV1 = 1u << 21;
public static const uint TEXTURE_TRANSFORM_SHIFT = 22;

/**
 * The texture transforms a material's textures name (FEATURE_TEXTURE_TRANSFORM), as glTF's KHR_texture_transform:
 * transform n moves UVs by its offset, turns them by its rotation (radians, counterclockwise) and scales them, in
 * glTF's order (scale, then rotate, then translate).
 */
[MaterialFeature(FEATURE_TEXTURE_TRANSFORM)]
public struct TextureTransformParameters
{
    public float4 transform1 = float4(0, 0, 1, 1); // offset x, y; scale x, y
    public float4 transform2 = float4(0, 0, 1, 1);
    public float4 transform3 = float4(0, 0, 1, 1);
    public float4 transform4 = float4(0, 0, 1, 1);
    public float4 transform5 = float4(0, 0, 1, 1);
    public float4 transform6 = float4(0, 0, 1, 1);
    public float4 transform7 = float4(0, 0, 1, 1);
    public float4 transform8 = float4(0, 0, 1, 1);
    public float4 rotations1to4 = float4(0, 0, 0, 0); // radians
    public float4 rotations5to8 = float4(0, 0, 0, 0);
};

/** The type of `m`, bits 0-7 of its header. */
public uint materialType(Material m)
{
    return m.header & 0xff;
}

/** The features `m` shows. */
public uint materialFeatures(Material m)
{
    return m.features;
}

/** Whether `m`'s back faces show, lit as the front. */
public bool materialDoubleSided(Material m)
{
    return (m.header & (1u << 8)) != 0;
}

/** Whether `m` is alpha-blended: drawn over what is behind it, by its alpha, after the opaque surfaces. */
public bool materialBlended(Material m)
{
    return (m.header & (1u << 10)) != 0;
}

/**
 * Whether `m` is drawn after the opaque surfaces, with the blended ones: alpha-blended, or transmissive
 * (FEATURE_TRANSMISSION: it shows the scene behind it).
 */
public bool materialDrawnBlended(Material m)
{
    return materialBlended(m) || (m.features & FEATURE_TRANSMISSION) != 0;
}

/** Whether `m` is alpha-masked: texels whose alpha is below its cutoff are not drawn. */
public bool materialMasked(Material m)
{
    return (m.header & (1u << 9)) != 0;
}

/**
 * Whether `m` masks this point out: alpha (its mask alpha, times its mask texture's, times the vertex color's) below
 * the cutoff, at texture coordinates `uvs` (both sets) with gradients `dx`, `dy`. Opaque materials mask nothing.
 */
public bool alphaClipped(Material m, float4 uvs, float4 dx, float4 dy, float4 color)
{
    if (!materialMasked(m))
    {
        return false;
    }
    float alpha = m.maskAlpha * color.a;
    if (m.maskTexture >= 0)
    {
        // In its UV set (`uvs`: the first, then the second); not moved by a transform, which the slot does not hold.
        bool second = (uint(m.maskTexture) & TEXTURE_UV1) != 0;
        alpha *= sampleTexture(m.maskTexture, second ? uvs.zw : uvs.xy, second ? dx.zw : dx.xy, second ? dy.zw : dy.xy)
            .a;
    }
    return alpha < float(m.header >> 16) / 65535.0;
}

// ---------------------------------------------------------------------------------------------- the blocks

/** Material `id` of the table at `table`: its slot, and through it its blocks. */
public struct MaterialRef
{
    public Material* table;
    public uint id;

    public Material slot()
    {
        return table[id];
    }
};

/** The parameters of `m`'s type, a struct T as the type's IMaterial names it. */
public T materialParameters<T>(MaterialRef m)
{
    return *(T*)((uint64_t)m.table + m.slot().parameters);
}

/** The parameters (struct T) of feature `feature` (one FEATURE_* bit), which `m` shows. */
public T materialFeature<T>(MaterialRef m, uint feature)
{
    return *materialFeatureAt<T>(m, feature);
}

/** Where the parameters (struct T) of feature `feature` (one FEATURE_* bit), which `m` shows, lie. */
public T* materialFeatureAt<T>(MaterialRef m, uint feature)
{
    Material s = m.slot();
    uint64_t block = (uint64_t)m.table + s.parameters;
    uint section = *(uint*)(block + s.featureTable + 4 * countbits(s.features & (feature - 1)));
    return (T*)(block + section);
}

// ---------------------------------------------------------------------------------------------- types

/** What a material gets at one point of a surface: interpolated, the normal facing the viewer (double-sided). */
public struct SurfaceInput
{
    public float3 world;           // camera-relative
    public float3 toViewer;        // unit
    public float3 normal;          // interpolated, not yet normalized: the geometry's
    public float4 tangent;         // w: the bitangent's sign; 0 without tangents
    public float2 uv;
    public float2 dx;              // the texture coordinates' gradients, per pixel
    public float2 dy;
    public float2 uv1;             // the second set (TEXTURE_UV1), and its gradients
    public float2 dx1;
    public float2 dy1;
    public float4 color;           // the vertex color
    public uint features;          // the features shaded (FEATURE_*): a constant where the pass is a variant
    public TextureTransformParameters* transforms; // the material's texture transforms; null: none
};

/**
 * Texture `texture` (a texture reference: slot, sampler, UV set, transform) at this point: in its UV set, moved by its
 * transform, with the gradients transformed alike.
 */
public float4 sampleTexture(int texture, SurfaceInput in)
{
    bool second = (uint(texture) & TEXTURE_UV1) != 0;
    float2 uv = second ? in.uv1 : in.uv;
    float2 dx = second ? in.dx1 : in.dx;
    float2 dy = second ? in.dy1 : in.dy;
    uint t = (uint(texture) >> TEXTURE_TRANSFORM_SHIFT) & 0xf;
    if (t != 0 && in.transforms != nullptr)
    {
        TextureTransformParameters p = *in.transforms;
        float4 transforms[8] = { p.transform1, p.transform2, p.transform3, p.transform4, p.transform5, p.transform6,
            p.transform7, p.transform8 };
        float4 tr = transforms[min(t, 8u) - 1];
        float rotation = t <= 4 ? p.rotations1to4[t - 1] : p.rotations5to8[min(t, 8u) - 5];
        float c = cos(rotation);
        float s = sin(rotation);
        // glTF: uv' = T * R * S * uv, R = [c, s; -s, c] (counterclockwise in texture space, whose v runs down)
        float2x2 m = float2x2(c * tr.z, s * tr.w, -s * tr.z, c * tr.w);
        uv = mul(m, uv) + tr.xy;
        dx = mul(m, dx);
        dy = mul(m, dy);
    }
    return sampleTexture(texture, uv, dx, dy);
}

/** Unit normal `n` bent by tangent-space normal map `texture` (by `scale`) at this point, if there are tangents. */
public float3 mapNormal(float3 n, float4 tangent, int texture, float scale, SurfaceInput in)
{
    if (texture < 0 || dot(tangent.xyz, tangent.xyz) <= 0)
    {
        return n;
    }
    float3 t = normalize(tangent.xyz - n * dot(n, tangent.xyz));
    float3 b = cross(n, t) * (tangent.w < 0 ? -1.0 : 1.0);
    float3 local = sampleTexture(texture, in).xyz * 2 - 1;
    local.xy *= scale;
    return normalize(local.x * t + local.y * b + local.z * n);
}

/**
 * A material type: how its materials' parameters become a surface to light. Lighting, and the features, are the
 * engine's: a type says only what its surface is at a point (the clear coat and other features' fields it leaves).
 */
public interface IMaterial
{
    /** The struct of its parameters, a [MaterialParameters] one. */
    associatedtype Parameters;

    static Surface surface(Parameters p, MaterialRef m, SurfaceInput in);
};

/** The parameters of a type that has none. */
public struct NoParameters {};
