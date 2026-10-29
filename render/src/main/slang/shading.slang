// Materials and the shading model every way of drawing shares: the forward fragment shader and the visibility
// buffer's shading pass evaluate a surface the same way, the only difference being where its texture gradients come
// from (the hardware's, or reconstructed). Physically based, metallic-roughness as in glTF: Lambert diffuse and a
// GGX specular lobe with Smith visibility and Schlick Fresnel.
module shading;

import bindless;

/** One material of engine-render's Materials: 64 bytes. Textures are sampled slots, or -1 for none. */
public struct Material
{
    public float4 baseColor;       // linear RGBA, times the base color texture
    public float3 emissive;        // linear, times the emissive texture
    public float roughness;        // perceptual, times the texture's green
    public float metallic;         // times the texture's blue
    public float normalScale;      // how strongly the normal map bends the normal
    public float occlusion;        // how strongly the texture's red darkens ambient light
    public uint header;            // type bits 0-7 (0: standard), double-sided bit 8, masked bit 9, cutoff bits 16-31
    public int baseColorTexture;   // sRGB
    public int normalTexture;      // tangent space, xyz in rgb
    public int ormTexture;         // occlusion, roughness, metalness in rgb
    public int emissiveTexture;    // sRGB
};

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
};

float4 sampleTexture(int slot, float2 uv, float2 dx, float2 dy)
{
    return textures[NonUniformResourceIndex(slot)].SampleGrad(samplers[MATERIAL], uv, dx, dy);
}

/**
 * The surface of material `m` at texture coordinates `uv` (with their screen gradients `dx`, `dy`), interpolated
 * normal and tangent (w: the bitangent's sign) and vertex color.
 */
public Surface evaluate(Material m, float3 normal, float4 tangent, float2 uv, float2 dx, float2 dy, float4 color)
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
    float3 n = normalize(normal);
    if (m.normalTexture >= 0 && dot(tangent.xyz, tangent.xyz) > 0)
    {
        float3 t = normalize(tangent.xyz - n * dot(n, tangent.xyz));
        float3 b = cross(n, t) * (tangent.w < 0 ? -1.0 : 1.0);
        float3 local = sampleTexture(m.normalTexture, uv, dx, dy).xyz * 2 - 1;
        local.xy *= m.normalScale;
        n = normalize(local.x * t + local.y * b + local.z * n);
    }
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
};

/**
 * A material type: how its materials' data becomes a surface to light. Lighting is the engine's; a type says only
 * what the surface is at a point, from its Material record (whose fields it may read its own way). Type 0 is
 * StandardMaterial. A game's type is a struct of its own, shaded through visibility.resolve's shadeTiles.
 */
public interface IMaterial
{
    static Surface surface(Material m, SurfaceInput in);
};

/** Type 0: metallic-roughness as in glTF, evaluate(). */
public struct StandardMaterial : IMaterial
{
    public static Surface surface(Material m, SurfaceInput in)
    {
        return evaluate(m, in.normal, in.tangent, in.uv, in.dx, in.dy, in.color);
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
    float nl = dot(s.normal, toLight);
    if (nl <= 0)
    {
        return 0;
    }
    float3 h = normalize(toViewer + toLight);
    float nv = max(dot(s.normal, toViewer), 1.0e-4);
    float nh = saturate(dot(s.normal, h));
    float vh = saturate(dot(toViewer, h));
    float a = s.roughness * s.roughness;
    float a2 = a * a;
    float d = nh * nh * (a2 - 1) + 1;
    float distribution = a2 / (PI * d * d);
    // Smith-GGX correlated visibility (height-correlated, Heitz 2014), including the 1 / (4 nl nv).
    float gv = nl * sqrt(nv * nv * (1 - a2) + a2);
    float gl = nv * sqrt(nl * nl * (1 - a2) + a2);
    float visibility = 0.5 / max(gv + gl, 1.0e-5);
    float3 f0 = lerp(float3(0.04), s.albedo, s.metallic);
    float3 fresnel = f0 + (1 - f0) * pow(1 - vh, 5);
    float3 specular = distribution * visibility * fresnel;
    float3 diffuse = (1 - fresnel) * (1 - s.metallic) * s.albedo / PI;
    return (diffuse + specular) * irradiance * nl;
}

/** Light from all around, `irradiance` from the sky: diffuse only, darkened by the surface's occlusion. */
public float3 ambient(Surface s, float3 irradiance)
{
    return (1 - s.metallic) * s.albedo / PI * irradiance * s.occlusion
        + lerp(float3(0.04), s.albedo, s.metallic) * irradiance / PI * 0.5 * s.occlusion;
}
