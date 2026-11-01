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
                                   // cutoff bits 16-31
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
/** How many features there are: their bits are the low FEATURE_BITS of a mask. */
public static const uint FEATURE_BITS = 1;

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

/** Whether `m` is alpha-masked: texels whose alpha is below its cutoff are not drawn. */
public bool materialMasked(Material m)
{
    return (m.header & (1u << 9)) != 0;
}

/**
 * Whether `m` masks this point out: alpha (its mask alpha, times its mask texture's, times the vertex color's) below
 * the cutoff. Opaque materials mask nothing.
 */
public bool alphaClipped(Material m, float2 uv, float2 dx, float2 dy, float4 color)
{
    if (!materialMasked(m))
    {
        return false;
    }
    float alpha = m.maskAlpha * color.a;
    if (m.maskTexture >= 0)
    {
        alpha *= sampleTexture(m.maskTexture, uv, dx, dy).a;
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
    Material s = m.slot();
    uint64_t block = (uint64_t)m.table + s.parameters;
    uint section = *(uint*)(block + s.featureTable + 4 * countbits(s.features & (feature - 1)));
    return *(T*)(block + section);
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
    public float4 color;           // the vertex color
    public uint features;          // the features shaded (FEATURE_*): a constant where the pass is a variant
};

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
