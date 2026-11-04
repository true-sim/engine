// Indirect light: what a view's surfaces receive from all around rather than from its lights. Shading reads it from one
// table per view (engine-render's Keys.INDIRECT_LIGHT), whose sections its providers fill: the distant environment
// (EnvironmentLighting: prefiltered cubes and spherical harmonics), later probe volumes, reflection probes and
// screen-space techniques, each a section and a flag. Without a table, the constant sky of shading.slang's ambient().
module indirect;

import bindless;
import shading;

/** The most cascades a probe volume has. */
public static const uint PROBE_CASCADES = 6;
/** Texels of a probe's irradiance (and sky visibility) along each side, its border of one texel included. */
public static const uint PROBE_IRRADIANCE_TEXELS = 8;
/** Texels of a probe's distance moments along each side, its border included. */
public static const uint PROBE_DEPTH_TEXELS = 16;

/** A probe is traced and sampled (classification: not inside geometry). */
public static const uint PROBE_ACTIVE = 1;
/** A probe holds light: updated at least once since its slot took it. */
public static const uint PROBE_VALID = 2;

/**
 * In a volume's flags: a point beyond the last cascade takes the probes at its border nearest to it (their light as
 * they see it, the bound of Chebyshev weighing them) rather than the environment's (radiance cascades, whose one
 * cascade ends where the scene may go on).
 */
public static const uint PROBE_VOLUME_CLAMP = 1;

/** One probe's state: 48 bytes. */
[HostLayout]
public struct ProbeState
{
    public float3 offset;          // relocation: from its grid position, world units
    public uint flags;             // PROBE_*
    public int3 coordinates;       // the world grid coordinate its slot holds (scrolling: a new one resets it)
    public uint frames;            // updates its history holds (the adaptive hysteresis' count)
    public float drift;            // the updates' mean relative difference from the history, averaged over a few
    public uint pad0;              // (adaptive hysteresis: a change too small for one update to show); its square
    public uint pad1;
    public uint pad2;
};

/**
 * A probe volume as its frame wrote it (engine-render's ProbeVolume, design S2: DDGI, Majercik et al. 2019 and
 * 2021): cascades of probes on grids around an anchor camera, each twice as far apart as the one before, scrolling
 * with it (a probe's slot is its world grid coordinate modulo the grid). Positions relative to the volume's origin, a
 * world position (ProbeVolume's), with world axes. Read a field at a time.
 */
[HostLayout]
public struct ProbeVolumeData
{
    public uint cascades;
    public uint irradiance;        // sampled slot: the irradiance atlas (rgb: irradiance / pi; a: sky visibility)
    public uint depth;             // sampled slot: the distance moments atlas (mean distance, mean squared)
    public uint flags;             // PROBE_VOLUME_*
    public uint3 probes;           // probes along each axis of a cascade
    public float normalBias;       // how far a shaded point is moved along its normal, of the probes' spacing
    public float viewBias;         // and towards the viewer
    public float blend;            // of a cascade's probes from its edge: where it blends into the next one
    public float maxDistance;      // the longest distance moments hold
    public uint pad;
    public ProbeState* states;     // per probe: its relocation, its state
    public float4 cascade[6];      // the cascade's lowest probe relative to the origin (xyz), its spacing (w)
    public int4 base[6];           // that probe's world grid coordinate (xyz): slots are coordinates modulo probes
};


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
    public uint reserved;          // for the sections to come: reflection probes, screen space
    public ProbeVolumeData* volume; // INDIRECT_PROBES: the probe volume lighting the view's surfaces
    public float3 volumeOffset;    // INDIRECT_PROBES: the view's camera less the volume's origin (world axes)
    public uint pad1;
};

/** The table holds a distant environment. */
public static const uint INDIRECT_ENVIRONMENT = 1;
/** The table holds a probe volume: its diffuse light replaces the environment's harmonics where it reaches. */
public static const uint INDIRECT_PROBES = 2;


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

/** The octahedral coordinate of unit `d` in [0, 1]. */
public float2 octahedralUnit(float3 d)
{
    d /= abs(d.x) + abs(d.y) + abs(d.z);
    float2 p = d.xy;
    if (d.z < 0)
    {
        p = (1 - abs(d.yx)) * float2(d.x >= 0 ? 1 : -1, d.y >= 0 ? 1 : -1);
    }
    return p * 0.5 + 0.5;
}

/** Where a direction's texel of probe tile `column`, `row` lies in an atlas of tiles `tile` texels wide, normalized. */
float2 atlasCoordinate(float3 d, uint column, uint row, uint tile, float2 atlas)
{
    float interior = float(tile - 2);
    float2 texel = float2(column, row) * float(tile) + 1 + octahedralUnit(d) * interior;
    return texel / atlas;
}

/** `a` modulo `n`, never negative. */
public int3 wrapProbe(int3 a, int3 n)
{
    return ((a % n) + n) % n;
}

/**
 * The probe volume's light at `p` (relative to its origin) on a surface facing `n`, seen from `toViewer`: the
 * irradiance / pi of the probes around the point, weighed by trilinear weights, how much each faces the surface and
 * whether it can see the point (the bound of Chebyshev on its distance moments), blended between cascades near their
 * edges; and in `sky` the fraction of the environment visible around `skyDirection` (the probes' rays that met
 * nothing), for specular occlusion. `found`: false outside every cascade.
 */
public float3 probeLight(ProbeVolumeData* volume, float3 p, float3 n, float3 toViewer, float3 skyDirection,
    out float sky, out bool found)
{
    sky = 1;
    found = false;
    uint cascades = volume[0].cascades;
    int3 probes = int3(volume[0].probes);
    float normalBias = volume[0].normalBias;
    float viewBias = volume[0].viewBias;
    float blend = volume[0].blend;
    uint irradianceSlot = volume[0].irradiance;
    uint depthSlot = volume[0].depth;
    ProbeState* states = volume[0].states;
    float2 tiles = float2(probes.x * probes.z, probes.y * int(cascades));
    float2 irradianceAtlas = tiles * float(PROBE_IRRADIANCE_TEXELS);
    float2 depthAtlas = tiles * float(PROBE_DEPTH_TEXELS);
    float3 sum = 0;
    float skySum = 0;
    float remaining = 1; // of the point's light not yet taken from a finer cascade
    for (uint c = 0; c < cascades && remaining > 1.0e-4; c++)
    {
        float4 cascade = volume[0].cascade[c];
        float spacing = cascade.w;
        float3 biased = p + (n * normalBias + toViewer * viewBias) * spacing;
        float3 local = (biased - cascade.xyz) / spacing;
        float3 last = float3(probes - 1);
        if (any(local < 0) || any(local > last))
        {
            if (c + 1 < cascades || (volume[0].flags & PROBE_VOLUME_CLAMP) == 0)
            {
                continue;
            }
            local = clamp(local, float3(0), last);
        }
        // Near its edge a cascade hands over to the next coarser one
        float3 edge = min(local, last - local);
        float share = c + 1 < cascades
            ? saturate(min(edge.x, min(edge.y, edge.z)) / max(blend * min(last.x, min(last.y, last.z)), 1.0e-4))
            : 1.0;
        int3 base = volume[0].base[c].xyz;
        int3 lower = clamp(int3(floor(local)), int3(0), probes - 2);
        float3 alpha = saturate(local - float3(lower));
        float3 irradiance = 0;
        float visible = 0;
        float weights = 0;
        for (uint k = 0; k < 8; k++)
        {
            int3 o = int3(k & 1, (k >> 1) & 1, k >> 2);
            int3 i = lower + o;
            int3 slot = wrapProbe(base + i, probes);
            uint index = uint(slot.x + probes.x * (slot.z + probes.z * (slot.y + probes.y * int(c))));
            uint flags = states[index].flags;
            if ((flags & (PROBE_ACTIVE | PROBE_VALID)) != (PROBE_ACTIVE | PROBE_VALID))
            {
                continue;
            }
            float3 probe = cascade.xyz + float3(i) * spacing + states[index].offset;
            float3 toProbe = probe - biased;
            float distance = max(length(toProbe), 1.0e-5);
            float3 direction = toProbe / distance;
            float3 trilinear = lerp(1 - alpha, alpha, float3(o));
            float w = (dot(direction, n) + 1) * 0.5;
            w = w * w + 0.2;
            uint column = uint(slot.x + probes.x * slot.z);
            uint row = uint(slot.y + probes.y * int(c));
            float2 moments = textures[depthSlot].SampleLevel(samplers[LINEAR_CLAMP],
                atlasCoordinate(-direction, column, row, PROBE_DEPTH_TEXELS, depthAtlas), 0).rg;
            if (distance > moments.x)
            {
                float variance = abs(moments.x * moments.x - moments.y);
                float d = distance - moments.x;
                float chebyshev = variance / (variance + d * d);
                w *= chebyshev * chebyshev * chebyshev;
            }
            w = max(w, 1.0e-6);
            if (w < 0.2)
            {
                w *= w * w / 0.04; // crushed: what a probe barely sees fades out quickly
            }
            w *= trilinear.x * trilinear.y * trilinear.z;
            float3 value = textures[irradianceSlot].SampleLevel(samplers[LINEAR_CLAMP],
                atlasCoordinate(n, column, row, PROBE_IRRADIANCE_TEXELS, irradianceAtlas), 0).rgb;
            float skyValue = textures[irradianceSlot].SampleLevel(samplers[LINEAR_CLAMP],
                atlasCoordinate(skyDirection, column, row, PROBE_IRRADIANCE_TEXELS, irradianceAtlas), 0).a;
            irradiance += w * value;
            visible += w * skyValue;
            weights += w;
        }
        if (weights <= 0)
        {
            continue; // no probe of this cascade sees the point: the next one's
        }
        float take = remaining * share;
        sum += take * irradiance / weights;
        skySum += take * visible / weights;
        remaining -= take;
        found = true;
    }
    if (!found)
    {
        return 0;
    }
    float taken = 1 - remaining;
    sky = skySum / taken;
    return sum / taken;
}

/**
 * The diffuse light (irradiance / pi) from all around arriving at a surface facing `n` at `position` (relative to the
 * view's camera), seen from `toViewer`: the probe volume's where the table holds one and it reaches the point, else
 * the environment's harmonics.
 */
public float3 diffuseLightAt(IndirectLight* t, float3 n, float3 position, float3 toViewer)
{
    if ((t[0].flags & INDIRECT_PROBES) != 0)
    {
        float sky;
        bool found;
        float3 e = probeLight(t[0].volume, position + t[0].volumeOffset, n, toViewer, n, sky, found);
        if (found)
        {
            return e;
        }
    }
    return diffuseLight(t, n);
}

/**
 * How much of the environment a surface at `position` (relative to the view's camera) facing `n` sees around
 * direction `r`: the probe volume's sky visibility where the table holds one (specular occlusion at the probes'
 * scale), else 1.
 */
public float environmentVisibility(IndirectLight* t, float3 r, float3 position, float3 n, float3 toViewer)
{
    if ((t[0].flags & INDIRECT_PROBES) == 0)
    {
        return 1;
    }
    float sky;
    bool found;
    probeLight(t[0].volume, position + t[0].volumeOffset, n, toViewer, r, sky, found);
    return found ? sky : 1.0;
}

/**
 * The environment reflected along `r` by a GGX lobe of `roughness` at `position` on a surface facing `n`: dimmed by
 * the probe volume's sky visibility around `r` where the table holds one.
 */
public float3 specularLightAt(IndirectLight* t, float3 r, float roughness, float3 position, float3 n,
    float3 toViewer)
{
    return specularLight(t, r, roughness) * environmentVisibility(t, r, position, n, toViewer);
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
public float3 indirectLight(IndirectLight* t, Surface s, float3 toViewer, float3 position)
{
    float3 reflectance;
    return indirectLightWeighed(t, s, toViewer, position, reflectance);
}

/**
 * indirectLight, also giving what the environment's reflection along the mirror direction (reflect(-toViewer,
 * s.normal), at s.roughness: specularLight) is weighed by in the result, its `reflectance`: the lookup table's scale
 * and bias, the occlusion, and what a sheen and a clear coat over it let through. Screen-space reflections replace
 * that term by it (surfaces.slang). 0 where the reflection bends from the mirror direction (anisotropy).
 */
public float3 indirectLightWeighed(IndirectLight* t, Surface s, float3 toViewer, float3 position,
    out float3 reflectance)
{
    float3 diffuseWeight;
    return indirectLightWeights(t, s, toViewer, position, reflectance, diffuseWeight);
}

/**
 * indirectLightWeighed, also giving what the diffuse light from all around the normal (diffuseLight(t, s.normal)) is
 * weighed by in the result, its `diffuseWeight`: the diffuse color less what the specular takes (with its multiple
 * scattering), the occlusion, what a sheen and a clear coat over it let through. Global illumination replaces that
 * term by it (surfaces.slang). The part a surface transmitting diffusely takes from behind (diffuseLight(t, -n)) is
 * left out: it is not of the light around the normal.
 */
public float3 indirectLightWeights(IndirectLight* t, Surface s, float3 toViewer, float3 position,
    out float3 reflectance, out float3 diffuseWeight)
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
    // Where a probe volume lights the view, its sky visibility dims the environment's reflections (specular
    // occlusion at the probes' scale) and its irradiance replaces the environment's harmonics: one look-up for both
    float sky = 1;
    float3 around = 0;
    bool found = false;
    if ((t[0].flags & INDIRECT_PROBES) != 0)
    {
        around = probeLight(t[0].volume, position + t[0].volumeOffset, n, toViewer, r, sky, found);
    }
    if (!found)
    {
        around = diffuseLight(t, n);
        sky = 1;
    }
    float3 specular = specularLight(t, r, s.roughness) * sky * fssEss;
    float3 diffuseColor = (1 - s.metallic) * (1 - s.transmission) * s.albedo;
    diffuseWeight = (fmsEms + diffuseColor * (1 - fssEss - fmsEms)) * (1 - s.diffuseTransmission) * s.occlusion;
    float3 diffuse = (fmsEms + diffuseColor * (1 - fssEss - fmsEms)) * around;
    if (s.diffuseTransmission > 0)
    {
        // Light through the surface, from behind it, in its transmission color
        float3 through = (1 - s.metallic) * (1 - s.transmission) * s.diffuseTransmissionColor
            * diffuseLightAt(t, -n, position, toViewer) * (1 - fssEss - fmsEms);
        diffuse = lerp(diffuse, through, s.diffuseTransmission);
    }
    float3 color = (diffuse + specular) * s.occlusion;
    reflectance = s.anisotropy > 0 ? float3(0) : fssEss * s.occlusion;
    if (any(s.sheenColor > 0))
    {
        float3 sheenLut = brdfTable(t, nv, s.sheenRoughness);
        float albedo = min(sheenLut.z, 1.0);
        float base = 1 - max(s.sheenColor.r, max(s.sheenColor.g, s.sheenColor.b)) * albedo;
        reflectance *= base;
        diffuseWeight *= base;
        color = color * base
            + s.sheenColor * sheenLight(t, reflect(-toViewer, n), s.sheenRoughness) * sky * albedo * s.occlusion;
    }
    if (s.clearcoat > 0)
    {
        float cv = saturate(dot(s.clearcoatNormal, toViewer));
        float fresnel = coatFresnel(cv);
        float3 coatLut = brdfTable(t, cv, s.clearcoatRoughness);
        float3 coatDirection = reflect(-toViewer, s.clearcoatNormal);
        float3 coat = specularLight(t, coatDirection, s.clearcoatRoughness)
            * environmentVisibility(t, coatDirection, position, s.clearcoatNormal, toViewer)
            * (0.04 * coatLut.x + coatLut.y) * s.occlusion;
        color = color * (1 - s.clearcoat * fresnel) + s.clearcoat * coat;
        reflectance *= 1 - s.clearcoat * fresnel;
        diffuseWeight *= 1 - s.clearcoat * fresnel;
    }
    return color;
}
