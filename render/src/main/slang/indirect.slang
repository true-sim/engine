// Indirect light: what a view's surfaces receive from all around rather than from its lights. Shading reads it from one
// table per view (engine-render's Keys.INDIRECT_LIGHT), whose sections its providers fill: the distant environment
// (EnvironmentLighting: prefiltered cubes and spherical harmonics), later probe volumes, reflection probes and
// screen-space techniques, each a section and a flag. Without a table, the constant sky of shading.slang's ambient().
module indirect;

import bindless;
import shading;

/** The table. Read a field at a time (pitfalls.md: no loads of whole structs through pointers). */
[HostLayout]
public struct IndirectLight
{
    public uint flags;             // INDIRECT_*: the sections filled
    public uint specular;          // the environment's GGX-prefiltered cube (sampled slot): a level per roughness
    public uint sheen;             // its sheen-prefiltered cube (the Charlie distribution)
    public uint brdf;              // the BRDF lookup table (2D: GGX scale, bias; sheen albedo), by n.v and roughness
    public uint specularMips;
    public uint sheenMips;
    public float intensity;        // the environment's brightness
    public uint pad;
    public float4 rotation;        // a unit quaternion from the world to the environment's frame
    public float sh[27];           // diffuse light (irradiance / pi): 9 spherical harmonics, rgb each
    public uint reserved[5];       // for the sections to come: probe volumes, reflection probes, screen space
};

/** The table holds a distant environment. */
public static const uint INDIRECT_ENVIRONMENT = 1;

float3 rotateBy(float4 q, float3 v)
{
    float3 t = 2.0 * cross(q.xyz, v);
    return v + q.w * t + cross(q.xyz, t);
}

/** Direction `d` in the environment's frame. */
float3 environmentDirection(IndirectLight* t, float3 d)
{
    return rotateBy(t[0].rotation, d);
}

/** The diffuse light (irradiance / pi) from all around arriving at a surface facing `n`. */
public float3 diffuseLight(IndirectLight* t, float3 n)
{
    float3 d = environmentDirection(t, n);
    float y[9] = { 0.282095, 0.488603 * d.y, 0.488603 * d.z, 0.488603 * d.x, 1.092548 * d.x * d.y,
        1.092548 * d.y * d.z, 0.315392 * (3 * d.z * d.z - 1), 1.092548 * d.x * d.z,
        0.546274 * (d.x * d.x - d.y * d.y) };
    float3 sum = 0;
    for (int b = 0; b < 9; b++)
    {
        sum += float3(t[0].sh[3 * b], t[0].sh[3 * b + 1], t[0].sh[3 * b + 2]) * y[b];
    }
    return max(sum, 0.0) * t[0].intensity;
}

/** The environment reflected along `r` by a GGX lobe of `roughness`. */
public float3 specularLight(IndirectLight* t, float3 r, float roughness)
{
    float lod = roughness * float(t[0].specularMips - 1);
    return cubes[t[0].specular].SampleLevel(samplers[LINEAR_CLAMP], environmentDirection(t, r), lod).rgb
        * t[0].intensity;
}

/** The environment reflected along `r` by the sheen's lobe of `roughness`. */
float3 sheenLight(IndirectLight* t, float3 r, float roughness)
{
    float lod = roughness * float(t[0].sheenMips - 1);
    return cubes[t[0].sheen].SampleLevel(samplers[LINEAR_CLAMP], environmentDirection(t, r), lod).rgb
        * t[0].intensity;
}

/** The lookup table at `nv`, `roughness`: GGX's scale and bias, and the sheen's albedo. */
public float3 brdfTable(IndirectLight* t, float nv, float roughness)
{
    return textures[t[0].brdf].SampleLevel(samplers[LINEAR_CLAMP], float2(saturate(nv), saturate(roughness)), 0)
        .rgb;
}

/**
 * Surface `s` lit by the table's indirect light, seen from `toViewer` (unit), the image-based lighting of the glTF
 * sample viewer: diffuse light from the spherical harmonics, the prefiltered environment's reflections scaled by the
 * lookup table (with multiple scattering), the sheen's, a clear coat's; a thin film in the Fresnel, anisotropy bending
 * the reflected normal, diffuse transmission lit from behind. Darkened by the surface's occlusion.
 */
public float3 indirectLight(IndirectLight* t, Surface s, float3 toViewer)
{
    float3 n = s.normal;
    float nv = saturate(dot(n, toViewer));
    float3 reflectedNormal = n;
    if (s.anisotropy > 0)
    {
        // As the glTF sample viewer: the normal bent towards the anisotropy's direction, the more the rougher across it
        float3 b = cross(n, s.anisotropyDirection);
        float3 tangent = cross(b, toViewer);
        float3 bent = cross(tangent, b);
        float bend = 1 - s.anisotropy * (1 - s.roughness);
        reflectedNormal = normalize(lerp(bent, n, bend * bend * bend * bend));
    }
    float3 r = reflect(-toViewer, reflectedNormal);
    float3 lut = brdfTable(t, nv, s.roughness);
    // Fresnel at n.v, rougher surfaces reflecting less at grazing angles (Fdez-Aguera 2019), a thin film's mixed in
    float3 f0 = lerp(s.f0, s.albedo, s.metallic);
    float f90 = lerp(s.f90, 1.0, s.metallic);
    float3 kS = f0 + (max(float3(1 - s.roughness), f0) - f0) * pow(1 - nv, 5);
    if (s.iridescence > 0)
    {
        kS = lerp(kS, s.iridescenceFresnel, s.iridescence);
    }
    float3 fssEss = kS * lut.x + f90 * lut.y;
    float ess = lut.x + lut.y;
    float3 fAvg = f0 + (1 - f0) / 21;
    float3 fmsEms = (1 - ess) * fssEss * fAvg / (1 - fAvg * (1 - ess));
    float3 specular = specularLight(t, r, s.roughness) * fssEss;
    float3 diffuseColor = (1 - s.metallic) * (1 - s.transmission) * s.albedo;
    float3 diffuse = (fmsEms + diffuseColor * (1 - fssEss - fmsEms)) * diffuseLight(t, n);
    if (s.diffuseTransmission > 0)
    {
        // Light through the surface, from behind it, in its transmission color
        float3 through = (1 - s.metallic) * (1 - s.transmission) * s.diffuseTransmissionColor
            * diffuseLight(t, -n) * (1 - fssEss - fmsEms);
        diffuse = lerp(diffuse, through, s.diffuseTransmission);
    }
    float3 color = (diffuse + specular) * s.occlusion;
    if (any(s.sheenColor > 0))
    {
        float3 sheenLut = brdfTable(t, nv, s.sheenRoughness);
        float albedo = sheenLut.z;
        color = color * (1 - max(s.sheenColor.r, max(s.sheenColor.g, s.sheenColor.b)) * albedo)
            + s.sheenColor * sheenLight(t, reflect(-toViewer, n), s.sheenRoughness) * albedo * s.occlusion;
    }
    if (s.clearcoat > 0)
    {
        float cv = saturate(dot(s.clearcoatNormal, toViewer));
        float fresnel = coatFresnel(cv);
        float3 coatLut = brdfTable(t, cv, s.clearcoatRoughness);
        float3 coat = specularLight(t, reflect(-toViewer, s.clearcoatNormal), s.clearcoatRoughness)
            * (0.04 * coatLut.x + coatLut.y) * s.occlusion;
        color = color * (1 - s.clearcoat * fresnel) + s.clearcoat * coat;
    }
    return color;
}
