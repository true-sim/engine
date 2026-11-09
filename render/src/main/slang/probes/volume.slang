// A probe volume as shading samples it (engine-render's ProbeVolume, design S2: DDGI, Majercik et al. 2019 and 2021;
// RadianceCascades writes each of its cascades the same way): its data, and the light it gives a point. Shading reaches
// it through the shading hook of probes/hook.slang, never by name: the core's shading knows no probe format.
module volume;

import bindless;

/** The most cascades a probe volume has: six, and radiance cascades' reach beyond theirs. */
public static const uint PROBE_CASCADES = 7;
/** Texels of a probe's irradiance (and sky visibility) along each side, its border of one texel included. */
public static const uint PROBE_IRRADIANCE_TEXELS = 8;
/** Texels of a probe's distance moments along each side, its border included. */
public static const uint PROBE_DEPTH_TEXELS = 16;

/** A probe is traced and sampled (classification: not inside geometry). */
public static const uint PROBE_ACTIVE = 1;
/** A probe holds light: updated at least once since its slot took it. */
public static const uint PROBE_VALID = 2;

/**
 * In a volume's flags: each cascade lights the points within a box around the anchor camera (`anchor`, `inner`) that
 * its probes enclose wherever the camera is, and blends into the next over a band at that box's edges, the last into
 * the environment: a point's light follows the camera continuously, never the steps its grids snap by (radiance
 * cascades). Otherwise a cascade blends from its grid's edges and the last lights every point within its grid
 * and past the box it encloses wherever the camera is fades into the environment over `fade` of that box.
 */
public static const uint PROBE_VOLUME_ANCHORED = 1;

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
    public float fade;             // not anchored: past the box its probes enclose wherever the camera is (`anchor`),
                                   // how far the last cascade's light carries, fading into the environment, of that
                                   // box's half extents (0: none, the environment from its grid's edges)
    public ProbeState* states;     // per probe: its relocation, its state
    public float4 cascade[7];      // the cascade's lowest probe relative to the origin (xyz), its spacing (w)
    public int4 base[7];           // that probe's world grid coordinate (xyz): slots are coordinates modulo its probes
    public uint4 grid[7];          // the cascade's probes along each axis (xyz); its first tile in the atlases (w:
                                   // column << 16 | row). A slot's tile is that plus (x + probes.x * z, y); its
                                   // state is its tile's, tiles.x a row
    public uint2 tiles;            // the atlases' size in tiles
    public uint2 pad1;
    public float4 anchor;          // the anchor camera relative to the origin (xyz)
    public float4 inner[7];        // PROBE_VOLUME_ANCHORED: the cascade's half extents around the anchor that its
                                   // probes enclose wherever it is (xyz); the band at their edges where it blends (w)
};

/** The atlases' tile of cascade `c`'s probe in slot `slot` (column, row), and in `state` its state's index. */
public uint2 probeTile(ProbeVolumeData* volume, uint c, int3 slot, out uint state)
{
    uint4 grid = volume[0].grid[c];
    uint2 tile = uint2((grid.w >> 16) + uint(slot.x) + grid.x * uint(slot.z), (grid.w & 0xffff) + uint(slot.y));
    state = tile.x + volume[0].tiles.x * tile.y;
    return tile;
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
 * nothing), for specular occlusion. `coverage`: the share of the point's light the volume gives, the environment the
 * rest (0 outside every cascade and past the last one's fade; below 1 where the last cascade blends into the
 * environment: with PROBE_VOLUME_ANCHORED at its box's edges, otherwise past that box by `fade`).
 */
public float3 probeLight(ProbeVolumeData* volume, float3 p, float3 n, float3 toViewer, float3 skyDirection,
    out float sky, out float coverage)
{
    sky = 1;
    coverage = 0;
    uint cascades = volume[0].cascades;
    bool anchored = (volume[0].flags & PROBE_VOLUME_ANCHORED) != 0;
    float3 anchor = volume[0].anchor.xyz;
    float normalBias = volume[0].normalBias;
    float viewBias = volume[0].viewBias;
    float blend = volume[0].blend;
    uint irradianceSlot = volume[0].irradiance;
    uint depthSlot = volume[0].depth;
    ProbeState* states = volume[0].states;
    float2 tiles = float2(volume[0].tiles);
    float2 irradianceAtlas = tiles * float(PROBE_IRRADIANCE_TEXELS);
    float2 depthAtlas = tiles * float(PROBE_DEPTH_TEXELS);
    float3 sum = 0;
    float skySum = 0;
    float remaining = 1; // of the point's light not yet taken from a finer cascade
    bool beyond = false; // where the last cascade fades into the environment (not anchored)
    for (uint c = 0; c < cascades && remaining > 1.0e-4; c++)
    {
        float4 cascade = volume[0].cascade[c];
        int3 probes = int3(volume[0].grid[c].xyz);
        float spacing = cascade.w;
        float3 biased = p + (n * normalBias + toViewer * viewBias) * spacing;
        float3 local = (biased - cascade.xyz) / spacing;
        float3 last = float3(probes - 1);
        float share;
        if (anchored)
        {
            // Within the box its probes enclose wherever the camera is, blending at its edges: continuous in the
            // camera's position, the last cascade fading into the environment
            float4 inner = volume[0].inner[c];
            float3 room = inner.xyz - abs(biased - anchor);
            float edge = min(room.x, min(room.y, room.z));
            if (edge <= 0)
            {
                continue;
            }
            share = saturate(edge / max(inner.w, 1.0e-6));
            local = clamp(local, float3(0), last); // within by construction: rounding at its faces alone
        }
        else if (c + 1 == cascades && volume[0].fade > 0)
        {
            // The last cascade fades into the environment past the box its probes enclose wherever the camera is,
            // over `fade` of that box's half extents: continuous in the camera's position (its grid snaps by its
            // spacing), its edge's light carried past the grid, no step where the grid ends
            float3 extent = max(last * 0.5 - 1, 0.5) * spacing;
            float3 past = max(abs(biased - anchor) - extent, 0) / extent;
            share = 1 - max(past.x, max(past.y, past.z)) / volume[0].fade;
            if (share <= 0)
            {
                continue;
            }
            share = share * share * (3 - 2 * share);
            if (any(local < 0) || any(local > last))
            {
                local = clamp(local, float3(0), last);
                biased = cascade.xyz + local * spacing; // the probes see the grid's edge, not the point past it
            }
            beyond = share < 1;
        }
        else if (any(local < 0) || any(local > last))
        {
            continue;
        }
        else
        {
            // Near its edge a cascade hands over to the next coarser one
            float3 edge = min(local, last - local);
            share = c + 1 < cascades
                ? saturate(min(edge.x, min(edge.y, edge.z)) / max(blend * min(last.x, min(last.y, last.z)), 1.0e-4))
                : 1.0;
        }
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
            uint index;
            uint2 tile = probeTile(volume, c, slot, index);
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
            float2 moments = textures[depthSlot].SampleLevel(samplers[LINEAR_CLAMP],
                atlasCoordinate(-direction, tile.x, tile.y, PROBE_DEPTH_TEXELS, depthAtlas), 0).rg;
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
                atlasCoordinate(n, tile.x, tile.y, PROBE_IRRADIANCE_TEXELS, irradianceAtlas), 0).rgb;
            float skyValue = textures[irradianceSlot].SampleLevel(samplers[LINEAR_CLAMP],
                atlasCoordinate(skyDirection, tile.x, tile.y, PROBE_IRRADIANCE_TEXELS, irradianceAtlas), 0).a;
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
    }
    float taken = 1 - remaining;
    if (taken <= 0)
    {
        return 0;
    }
    coverage = anchored || beyond ? taken : 1;
    sky = skySum / taken;
    return sum / taken;
}
