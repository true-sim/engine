// The shading model every way of drawing shares: what a surface is at a point (Surface) and how it is lit. Physically
// based: Lambert diffuse and a GGX specular lobe with Smith visibility and Schlick Fresnel (a dielectric's reflectance
// head-on and at grazing angles from its IOR and specular features), an optional sheen (Charlie distribution), an
// optional clear coat, and transmission (light passing through, from the scene behind: the diffuse part given up for
// it). What a surface is comes from its material (materials.slang): the forward fragment shader and the visibility
// buffer's shading pass evaluate it the same way, the only difference being where texture gradients come from (the
// hardware's, or reconstructed).
module shading;

import bindless;

/** A surface ready to light: what the material says at one point. */
public struct Surface
{
    public float3 albedo;
    public float metallic;
    public float3 normal;          // unit, camera-relative world space
    public float roughness;
    public float3 emissive;
    public float occlusion;
    public float alpha;            // coverage, for blended materials: 1 opaque
    public float3 f0;              // a dielectric's reflectance head-on (FEATURE_IOR, FEATURE_SPECULAR): 0.04 at IOR 1.5
    public float f90;              // and at grazing angles, the specular weight (FEATURE_SPECULAR): 1
    public float3 sheenColor;      // FEATURE_SHEEN: the sheen's color, linear (0: none)
    public float sheenRoughness;   // FEATURE_SHEEN: perceptual
    public float ior;              // the index of refraction (FEATURE_IOR): 1.5
    public float transmission;     // FEATURE_TRANSMISSION: how much of the diffuse part passes through instead (0: none)
    public float thickness;        // FEATURE_VOLUME: the volume's thickness under the surface (0: thin-walled)
    public float3 attenuationColor; // FEATURE_VOLUME: the color white light becomes after attenuationDistance inside
    public float attenuationDistance; // FEATURE_VOLUME: 0: no attenuation
    public float3 clearcoatNormal; // FEATURE_CLEARCOAT: the coat's normal, unit
    public float clearcoat;        // FEATURE_CLEARCOAT: its strength (0: none, every other surface)
    public float clearcoatRoughness;

    /**
     * A surface with every field set: a matte, mid-grey dielectric facing `normal` (unit), with no emission and no clear
     * coat. Material types start from it and set what they have, so a field added later always has a value.
     */
    public static Surface defaults(float3 normal)
    {
        Surface s;
        s.albedo = 0.8;
        s.metallic = 0;
        s.normal = normal;
        s.roughness = 0.7;
        s.emissive = 0;
        s.occlusion = 1;
        s.alpha = 1;
        s.f0 = 0.04;
        s.f90 = 1;
        s.sheenColor = 0;
        s.sheenRoughness = 0;
        s.ior = 1.5;
        s.transmission = 0;
        s.thickness = 0;
        s.attenuationColor = 1;
        s.attenuationDistance = 0;
        s.clearcoatNormal = normal;
        s.clearcoat = 0;
        s.clearcoatRoughness = 1;
        return s;
    }
};

/**
 * Texture `slot` at `uv`, with its screen gradients `dx`, `dy`: the low 16 bits are its slot, bits 16-19 its sampler past
 * MATERIAL (engine-render's TextureSampler: 0 trilinear, anisotropic and repeating). Materials sample through
 * materials.slang's sampleTexture(slot, in), which also follows the texture's UV set and transform (bits 20-24).
 */
public float4 sampleTexture(int slot, float2 uv, float2 dx, float2 dy)
{
    return textures[NonUniformResourceIndex(slot & 0xffff)]
        .SampleGrad(samplers[NonUniformResourceIndex(MATERIAL + ((uint(slot) >> 16) & 0xf))], uv, dx, dy);
}

/** Unit normal `n` bent by tangent-space normal map `slot` (by `scale`), if there are tangents. */
public float3 mapNormal(float3 n, float4 tangent, int slot, float scale, float2 uv, float2 dx, float2 dy)
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
        float3 f0 = lerp(s.f0, s.albedo, s.metallic);
        float f90 = lerp(s.f90, 1.0, s.metallic);
        float3 fresnel = f0 + (f90 - f0) * pow(1 - vh, 5);
        float3 specular = lobe(s.normal, toViewer, h, nl, s.roughness) * fresnel;
        float3 diffuse = (1 - fresnel) * (1 - s.metallic) * (1 - s.transmission) * s.albedo / PI;
        color = (diffuse + specular) * irradiance * nl;
    }
    if (any(s.sheenColor > 0))
    {
        // A sheen over the base (KHR_materials_sheen): its lobe added, the base dimmed by what the sheen takes.
        color *= sheenScaling(s);
        if (nl > 0)
        {
            color += s.sheenColor * sheenLobe(s.normal, toViewer, h, nl, s.sheenRoughness) * irradiance * nl;
        }
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

/**
 * How much of the base a sheen leaves: 1 - its color's largest channel times the sheen's directional albedo, taken as
 * its average (0.157, for a mid roughness) rather than from a table.
 */
public float sheenScaling(Surface s)
{
    return 1 - max(s.sheenColor.r, max(s.sheenColor.g, s.sheenColor.b)) * 0.157;
}

/**
 * The sheen's lobe: the Charlie distribution (Estevez and Kulla 2017) times Neubelt's visibility, for normal `n`, half
 * vector `h` and cosine `nl` towards the light.
 */
float sheenLobe(float3 n, float3 toViewer, float3 h, float nl, float roughness)
{
    float a = max(roughness * roughness, 1.0e-3);
    float nh = saturate(dot(n, h));
    float sin2 = max(1 - nh * nh, 1.0e-4);
    float distribution = (2 + 1 / a) * pow(sin2, 0.5 / a) / (2 * PI);
    float nv = max(dot(n, toViewer), 1.0e-4);
    float visibility = 1 / (4 * (nl + nv - nl * nv));
    return distribution * visibility;
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
    float3 base = (1 - s.metallic) * (1 - s.transmission) * s.albedo / PI * irradiance * s.occlusion
        + lerp(s.f0, s.albedo, s.metallic) * irradiance / PI * 0.5 * s.occlusion;
    if (any(s.sheenColor > 0))
    {
        base = base * sheenScaling(s) + s.sheenColor * 0.157 * irradiance / PI * s.occlusion;
    }
    if (s.clearcoat > 0)
    {
        float coat = s.clearcoat * 0.04;
        base = base * (1 - coat) + coat * irradiance / PI * 0.5 * s.occlusion;
    }
    return base;
}
