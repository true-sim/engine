// A probe volume as shading samples it (engine-render's ProbeVolume, design S2: DDGI, Majercik et al. 2019 and 2021;
// RadianceCascades writes its first cascade the same way): its data, and the light it gives a point. Shading reaches it
// through the shading hook of probes/hook.slang, never by name: the core's shading knows no probe format.
module volume;

import bindless;

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
