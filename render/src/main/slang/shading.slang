// The shading model every way of drawing shares: what a surface is at a point (Surface) and how it is lit. Physically
// based: Lambert diffuse and a GGX specular lobe with Smith visibility and Schlick Fresnel (a dielectric's reflectance
// head-on and at grazing angles from its IOR and specular features), an optional sheen (Charlie distribution), an
// optional clear coat, and transmission (light passing through, from the scene behind: the diffuse part given up for
// it); an anisotropic specular lobe, a thin film's iridescence, and diffuse transmission (light through thin surfaces,
// as leaves). What a surface is comes from its material (materials.slang): the forward fragment shader and the visibility
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
    public float3 anisotropyDirection; // FEATURE_ANISOTROPY: unit, along the surface: where the highlight stretches
    public float anisotropy;       // FEATURE_ANISOTROPY: its strength (0: isotropic)
    public float3 iridescenceFresnel; // FEATURE_IRIDESCENCE: the thin film's reflectance, as thinFilm() gives it
    public float iridescence;      // FEATURE_IRIDESCENCE: how much of the reflectance is the film's (0: none)
    public float iridescenceIor;   // FEATURE_IRIDESCENCE: the film's index of refraction
    public float iridescenceThickness; // FEATURE_IRIDESCENCE: the film's thickness, nanometres
    public float dispersion;       // FEATURE_DISPERSION: how far the IOR spreads by wavelength (0: none)
    public float3 diffuseTransmissionColor; // FEATURE_DIFFUSE_TRANSMISSION: the light through it, tinted
    public float diffuseTransmission; // FEATURE_DIFFUSE_TRANSMISSION: how much of the diffuse part goes through
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
        s.anisotropyDirection = float3(1, 0, 0);
        s.anisotropy = 0;
        s.iridescenceFresnel = 0;
        s.iridescence = 0;
        s.iridescenceIor = 1.3;
        s.iridescenceThickness = 400;
        s.dispersion = 0;
        s.diffuseTransmissionColor = 1;
        s.diffuseTransmission = 0;
        s.clearcoatNormal = normal;
        s.clearcoat = 0;
        s.clearcoatRoughness = 1;
        return s;
    }
};

/**
 * Texture `slot` at `uv`, with its screen gradients `dx`, `dy`: the low 16 bits are its slot, bits 16-20 its sampler past
 * MATERIAL (engine-render's TextureSampler: its wraps and filter; 0 trilinear, anisotropic and repeating). Materials
 * sample through materials.slang's sampleTexture(slot, in), which also follows the texture's UV set and transform
 * (bits 21-25).
 */
public float4 sampleTexture(int slot, float2 uv, float2 dx, float2 dy)
{
    return textures[NonUniformResourceIndex(slot & 0xffff)]
        .SampleGrad(samplers[NonUniformResourceIndex(MATERIAL + ((uint(slot) >> 16) & 0x1f))], uv, dx, dy);
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
        float3 fresnel = surfaceFresnel(s, vh);
        float3 specular = (s.anisotropy > 0 ? anisotropicLobe(s, toViewer, toLight, h, nl)
            : lobe(s.normal, toViewer, h, nl, s.roughness)) * fresnel;
        float3 diffuse = (1 - fresnel) * (1 - s.metallic) * (1 - s.transmission) * (1 - s.diffuseTransmission)
            * s.albedo / PI;
        color = (diffuse + specular) * irradiance * nl;
    }
    else if (s.diffuseTransmission > 0)
    {
        // Lit from behind: what the surface lets through diffusely, as a Lambertian surface facing the other way, in
        // its transmission color (KHR_materials_diffuse_transmission: in place of the base color, not times it).
        color = s.diffuseTransmission * s.diffuseTransmissionColor * (1 - s.metallic) * (1 - s.transmission) / PI
            * irradiance * -nl;
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
 * What surface `s` reflects at the cosine `vh` between the viewer and the half vector: Schlick's Fresnel from its
 * reflectance head-on and at grazing angles (a metal's its albedo), and where it has a thin film, the film's
 * (iridescenceFresnel) by its strength.
 */
public float3 surfaceFresnel(Surface s, float vh)
{
    float3 f0 = lerp(s.f0, s.albedo, s.metallic);
    float f90 = lerp(s.f90, 1.0, s.metallic);
    float3 fresnel = f0 + (f90 - f0) * pow(1 - vh, 5);
    return s.iridescence > 0 ? lerp(fresnel, s.iridescenceFresnel, s.iridescence) : fresnel;
}

/**
 * The anisotropic GGX lobe (KHR_materials_anisotropy, as the glTF sample viewer): the roughness along the anisotropy's
 * direction raised towards 1 by its strength squared, across it kept; times the matching Smith visibility.
 */
float anisotropicLobe(Surface s, float3 toViewer, float3 toLight, float3 h, float nl)
{
    float3 t = s.anisotropyDirection;
    float3 b = cross(s.normal, t);
    float ab = max(s.roughness * s.roughness, 1.0e-3);
    float at = lerp(ab, 1.0, s.anisotropy * s.anisotropy);
    float nh = saturate(dot(s.normal, h));
    float nv = max(dot(s.normal, toViewer), 1.0e-4);
    float a2 = at * ab;
    float3 f = float3(ab * dot(t, h), at * dot(b, h), a2 * nh);
    float w2 = a2 / max(dot(f, f), 1.0e-12);
    float distribution = a2 * w2 * w2 / PI;
    float gv = nl * length(float3(at * dot(t, toViewer), ab * dot(b, toViewer), nv));
    float gl = nv * length(float3(at * dot(t, toLight), ab * dot(b, toLight), nl));
    return distribution * 0.5 / max(gv + gl, 1.0e-5);
}

/**
 * A thin film's reflectance (Belcour and Barla 2017, as the glTF sample viewer's KHR_materials_iridescence): a film of
 * index `filmIor` and `thickness` nanometres in air over a base of reflectance `baseF0`, seen at the cosine `cosine`;
 * the interference of its first reflections, integrated over the eye's sensitivity, in linear sRGB.
 */
public float3 thinFilm(float filmIor, float thickness, float cosine, float3 baseF0)
{
    float ior = lerp(1.0, filmIor, smoothstep(0.0, 0.03, thickness));
    float sin2 = (1 / (ior * ior)) * (1 - cosine * cosine);
    float cos2Sq = 1 - sin2;
    if (cos2Sq < 0)
    {
        return 1; // total internal reflection
    }
    float cos2 = sqrt(cos2Sq);
    float r0 = (ior - 1) / (ior + 1);
    float r12 = r0 * r0 + (1 - r0 * r0) * pow(1 - cosine, 5);
    float t121 = 1 - r12;
    float phi12 = ior < 1 ? PI : 0;
    float phi21 = PI - phi12;
    float3 f = clamp(baseF0, 0.0, 0.9999);
    float3 baseIor = (1 + sqrt(f)) / (1 - sqrt(f));
    float3 r1 = (baseIor - ior) / (baseIor + ior);
    r1 *= r1;
    float3 r23 = r1 + (1 - r1) * pow(1 - cos2, 5);
    float3 phi23 = float3(baseIor.x < ior ? PI : 0, baseIor.y < ior ? PI : 0, baseIor.z < ior ? PI : 0);
    float opd = 2 * ior * thickness * cos2;
    float3 phi = phi21 + phi23;
    float3 r123 = clamp(r12 * r23, 1.0e-5, 0.9999);
    float3 rs = t121 * t121 * r23 / (1 - r123);
    float3 color = r12 + rs;
    float3 cm = rs - t121;
    for (int m = 1; m <= 2; m++)
    {
        cm *= sqrt(r123);
        color += cm * 2 * filmSensitivity(m * opd, m * phi);
    }
    return max(color, 0.0);
}

/** The eye's sensitivity to a path difference `opd` (nanometres) shifted in phase by `shift`, in linear sRGB. */
float3 filmSensitivity(float opd, float3 shift)
{
    float phase = 2 * PI * opd * 1.0e-9;
    float3 value = float3(5.4856e-13, 4.4201e-13, 5.2481e-13);
    float3 position = float3(1.6810e+06, 1.7953e+06, 2.2084e+06);
    float3 variance = float3(4.3278e+09, 9.3046e+09, 6.6121e+09);
    float3 xyz = value * sqrt(2 * PI * variance) * cos(position * phase + shift) * exp(-phase * phase * variance);
    xyz.x += 9.7470e-14 * sqrt(2 * PI * 4.5282e+09) * cos(2.2399e+06 * phase + shift.x)
        * exp(-4.5282e+09 * phase * phase);
    xyz /= 1.0685e-7;
    return float3(3.2404542 * xyz.x - 1.5371385 * xyz.y - 0.4985314 * xyz.z,
        -0.9692660 * xyz.x + 1.8760108 * xyz.y + 0.0415560 * xyz.z,
        0.0556434 * xyz.x - 0.2040259 * xyz.y + 1.0572252 * xyz.z);
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
    // The sky from behind too, through a surface transmitting diffusely: in its transmission color.
    float3 diffuse = (1 - s.diffuseTransmission) * s.albedo + s.diffuseTransmission * s.diffuseTransmissionColor;
    float3 base = (1 - s.metallic) * (1 - s.transmission) * diffuse / PI * irradiance * s.occlusion
        + surfaceFresnel(s, 1.0) * irradiance / PI * 0.5 * s.occlusion;
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
