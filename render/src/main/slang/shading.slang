// Materials and the shading model every way of drawing shares: the forward fragment shader and the visibility
// buffer's shading pass evaluate a surface the same way, the only difference being where its texture gradients come
// from (the hardware's, or reconstructed). Physically based, metallic-roughness as in glTF: Lambert diffuse and a
// GGX specular lobe with Smith visibility and Schlick Fresnel.
module shading;

import bindless;

/**
 * One material of engine-render's Materials: 96 bytes. Textures are sampled slots, or -1 for none. The features a
 * material shows (FEATURE_*) are in its header, set from its data (a clear coat: a coat above 0).
 */
public struct Material
{
    public float4 baseColor;       // linear RGBA, times the base color texture
    public float3 emissive;        // linear, times the emissive texture
    public float roughness;        // perceptual, times the texture's green
    public float metallic;         // times the texture's blue
    public float normalScale;      // how strongly the normal map bends the normal
    public float occlusion;        // how strongly the texture's red darkens ambient light
    public uint header;            // type bits 0-7 (0: standard), double-sided bit 8, masked bit 9, features bits
                                   // 10-15, cutoff bits 16-31
    public int baseColorTexture;   // sRGB
    public int normalTexture;      // tangent space, xyz in rgb
    public int ormTexture;         // occlusion, roughness, metalness in rgb
    public int emissiveTexture;    // sRGB
    public float clearcoat;        // the coat's strength, 0 to 1, times its texture's red (KHR_materials_clearcoat)
    public float clearcoatRoughness; // perceptual, times its texture's green
    public int clearcoatTexture;   // linear, red
    public int clearcoatRoughnessTexture; // linear, green
    public int clearcoatNormalTexture; // tangent space, as normalTexture; none: the coat follows the geometry
    public float clearcoatNormalScale;
    public uint reserved0;
    public uint reserved1;
};

/**
 * Features: optional parts of the standard shading a material may show, each a bit of the header (from bit 10). The
 * visibility buffer shades each combination with a variant of its own, compiled with only its features' code.
 */
public static const uint FEATURE_CLEARCOAT = 1;
/** How many feature bits there are: combinations of them are 1 << FEATURE_BITS. */
public static const uint FEATURE_BITS = 1;

/** The features `m` shows. */
public uint materialFeatures(Material m)
{
    return (m.header >> 10) & ((1u << FEATURE_BITS) - 1);
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
 * Whether `m` masks this point out: alpha (factor, times the base color texture's, times the vertex color's) below
 * the cutoff. Opaque materials mask nothing.
 */
public bool alphaClipped(Material m, float2 uv, float2 dx, float2 dy, float4 color)
{
    if (!materialMasked(m))
    {
        return false;
    }
    float alpha = m.baseColor.a * color.a;
    if (m.baseColorTexture >= 0)
    {
        alpha *= sampleTexture(m.baseColorTexture, uv, dx, dy).a;
    }
    return alpha < float(m.header >> 16) / 65535.0;
}

/** A surface ready to light: what the material says at one point. */
public struct Surface
{
    public float3 albedo;
    public float metallic;
    public float3 normal;          // unit, camera-relative world space
    public float roughness;
    public float3 emissive;
    public float occlusion;
    public float3 clearcoatNormal; // FEATURE_CLEARCOAT: the coat's normal, unit
    public float clearcoat;        // FEATURE_CLEARCOAT: its strength (0: none, every other surface)
    public float clearcoatRoughness;
};

float4 sampleTexture(int slot, float2 uv, float2 dx, float2 dy)
{
    return textures[NonUniformResourceIndex(slot)].SampleGrad(samplers[MATERIAL], uv, dx, dy);
}

/** Unit normal `n` bent by tangent-space normal map `slot` (by `scale`), if there are tangents. */
float3 mapNormal(float3 n, float4 tangent, int slot, float scale, float2 uv, float2 dx, float2 dy)
{
    if (slot < 0 || dot(tangent.xyz, tangent.xyz) <= 0)
    {
        return n;
    }
    float3 t = normalize(tangent.xyz - n * dot(n, tangent.xyz));
    float3 b = cross(n, t) * (tangent.w < 0 ? -1.0 : 1.0);
    float3 local = sampleTexture(slot, uv, dx, dy).xyz * 2 - 1;
    local.xy *= scale;
    return normalize(local.x * t + local.y * b + local.z * n);
}

/**
 * The surface of material `m` at texture coordinates `uv` (with their screen gradients `dx`, `dy`), interpolated
 * normal and tangent (w: the bitangent's sign) and vertex color, with the features it shows.
 */
public Surface evaluate(Material m, float3 normal, float4 tangent, float2 uv, float2 dx, float2 dy, float4 color)
{
    return evaluate(m, normal, tangent, uv, dx, dy, color, materialFeatures(m));
}

/**
 * evaluate() with the features of `features` only: a constant, the others' code is left out (a material shows only
 * features it has; one it has and `features` lacks is not shown).
 */
public Surface evaluate(Material m, float3 normal, float4 tangent, float2 uv, float2 dx, float2 dy, float4 color,
    uint features)
{
    float4 base = m.baseColor * color;
    if (m.baseColorTexture >= 0)
    {
        base *= sampleTexture(m.baseColorTexture, uv, dx, dy);
    }
    float roughness = m.roughness;
    float metallic = m.metallic;
    float occlusion = 1;
    if (m.ormTexture >= 0)
    {
        float3 orm = sampleTexture(m.ormTexture, uv, dx, dy).rgb;
        occlusion = lerp(1.0, orm.r, m.occlusion);
        roughness *= orm.g;
        metallic *= orm.b;
    }
    float3 geometric = normalize(normal);
    float3 n = mapNormal(geometric, tangent, m.normalTexture, m.normalScale, uv, dx, dy);
    float3 emissive = m.emissive;
    if (m.emissiveTexture >= 0)
    {
        emissive *= sampleTexture(m.emissiveTexture, uv, dx, dy).rgb;
    }
    Surface s;
    s.albedo = base.rgb;
    s.metallic = saturate(metallic);
    s.normal = n;
    s.roughness = clamp(roughness, 0.03, 1.0); // a perfect mirror's highlight would be infinitely small
    s.emissive = emissive;
    s.occlusion = occlusion;
    s.clearcoatNormal = geometric;
    s.clearcoat = 0;
    s.clearcoatRoughness = 1;
    if ((features & materialFeatures(m) & FEATURE_CLEARCOAT) != 0)
    {
        float coat = m.clearcoat;
        if (m.clearcoatTexture >= 0)
        {
            coat *= sampleTexture(m.clearcoatTexture, uv, dx, dy).r;
        }
        float coatRoughness = m.clearcoatRoughness;
        if (m.clearcoatRoughnessTexture >= 0)
        {
            coatRoughness *= sampleTexture(m.clearcoatRoughnessTexture, uv, dx, dy).g;
        }
        s.clearcoat = saturate(coat);
        s.clearcoatRoughness = clamp(coatRoughness, 0.03, 1.0);
        s.clearcoatNormal = mapNormal(geometric, tangent, m.clearcoatNormalTexture, m.clearcoatNormalScale, uv, dx,
            dy);
    }
    return s;
}

/** What a material gets at one point of a surface: interpolated, normal facing the viewer (double-sided). */
public struct SurfaceInput
{
    public float3 world;           // camera-relative
    public float3 toViewer;        // unit
    public float3 normal;          // interpolated, not yet normalized
    public float4 tangent;         // w: the bitangent's sign; 0 without tangents
    public float2 uv;
    public float2 dx;              // the texture coordinates' gradients, per pixel
    public float2 dy;
    public float4 color;           // the vertex color
    public uint features;          // the features shaded (FEATURE_*): a constant where the pass is a variant
};

/**
 * A material type: how its materials' data becomes a surface to light. Lighting is the engine's; a type says only
 * what the surface is at a point, from its Material record (whose fields it may read its own way). Type 0 is
 * StandardMaterial. A game's type is a struct of its own, shaded through visibility.resolve's shadeTiles. A type
 * may show the features (FEATURE_*) in in.features by setting their fields of Surface; the others it leaves.
 */
public interface IMaterial
{
    static Surface surface(Material m, SurfaceInput in);
};

/** Type 0: metallic-roughness as in glTF, with the features in.features, evaluate(). */
public struct StandardMaterial : IMaterial
{
    public static Surface surface(Material m, SurfaceInput in)
    {
        return evaluate(m, in.normal, in.tangent, in.uv, in.dx, in.dy, in.color, in.features);
    }
};

/** The type of `m`, bits 0-7 of its header. */
public uint materialType(Material m)
{
    return m.header & 0xff;
}

static const float PI = 3.14159265;

/**
 * The light a surface reflects towards the viewer (`toViewer`, unit) from a light in direction `toLight` (unit)
 * giving `irradiance` (on a surface facing it): diffuse and specular, times the cosine.
 */
public float3 reflected(Surface s, float3 toViewer, float3 toLight, float3 irradiance)
{
    float3 h = normalize(toViewer + toLight);
    float vh = saturate(dot(toViewer, h));
    float3 color = 0;
    float nl = dot(s.normal, toLight);
    if (nl > 0)
    {
        float3 f0 = lerp(float3(0.04), s.albedo, s.metallic);
        float3 fresnel = f0 + (1 - f0) * pow(1 - vh, 5);
        float3 specular = lobe(s.normal, toViewer, h, nl, s.roughness) * fresnel;
        float3 diffuse = (1 - fresnel) * (1 - s.metallic) * s.albedo / PI;
        color = (diffuse + specular) * irradiance * nl;
    }
    if (s.clearcoat > 0)
    {
        // A dielectric layer over the base (KHR_materials_clearcoat): what it reflects, the base under it the rest.
        float fresnel = coatFresnel(vh);
        color *= 1 - s.clearcoat * fresnel;
        float cl = dot(s.clearcoatNormal, toLight);
        if (cl > 0)
        {
            color += s.clearcoat * fresnel * lobe(s.clearcoatNormal, toViewer, h, cl, s.clearcoatRoughness)
                * irradiance * cl;
        }
    }
    return color;
}

/** The clear coat's Fresnel, a dielectric's (index 1.5: 4 % head-on), by the cosine `c` towards the viewer. */
public float coatFresnel(float c)
{
    return 0.04 + 0.96 * pow(1 - saturate(c), 5);
}

/** GGX distribution times Smith visibility, for normal `n`, half vector `h` and cosine `nl` towards the light. */
float lobe(float3 n, float3 toViewer, float3 h, float nl, float roughness)
{
    float nv = max(dot(n, toViewer), 1.0e-4);
    float nh = saturate(dot(n, h));
    float a = roughness * roughness;
    float a2 = a * a;
    float d = nh * nh * (a2 - 1) + 1;
    float distribution = a2 / (PI * d * d);
    // Smith-GGX correlated visibility (height-correlated, Heitz 2014), including the 1 / (4 nl nv).
    float gv = nl * sqrt(nv * nv * (1 - a2) + a2);
    float gl = nv * sqrt(nl * nl * (1 - a2) + a2);
    return distribution * 0.5 / max(gv + gl, 1.0e-5);
}

/** Light from all around, `irradiance` from the sky: diffuse only, darkened by the surface's occlusion. */
public float3 ambient(Surface s, float3 irradiance)
{
    float3 base = (1 - s.metallic) * s.albedo / PI * irradiance * s.occlusion
        + lerp(float3(0.04), s.albedo, s.metallic) * irradiance / PI * 0.5 * s.occlusion;
    if (s.clearcoat > 0)
    {
        float coat = s.clearcoat * 0.04;
        base = base * (1 - coat) + coat * irradiance / PI * 0.5 * s.occlusion;
    }
    return base;
}
