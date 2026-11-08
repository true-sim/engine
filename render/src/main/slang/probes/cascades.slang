// Radiance cascades in world space (engine-render's RadianceCascades, design D9; Sannikov 2023): grids of probes
// around an anchor camera, each cascade's probes twice as far apart as the one before's and with four times as many
// directions, each direction a ray along an interval four times as long as the one before's and starting where it
// ended. No history: every frame finds every probe's light anew, and merges the cascades from the last (whose rays go
// on to the environment) to the first, each direction of a probe that met nothing within its interval taking what the
// next cascade's probes around it found beyond, in its four finer directions. Every cascade's merged light becomes a
// cascade of a probe volume, as probes/volume.slang's probeLight samples it (irradiance / pi with the sky's visibility,
// and distance moments), so every shading path reads it as it reads the ray-traced probe volume: the finest cascade
// where it reaches, the next coarser ones beyond, the environment past the last. A coarser cascade's intervals start
// away from its probes; what lies nearer, each probe finds by rays of its own along the first cascade's directions,
// ending where its intervals start. Where RadianceCascades' reach goes beyond the last cascade, a level of probes past
// it has no intervals: its probes' own rays go on to the environment (and no cascade merges from it). A frame's passes:
//
// - generateMain, a dispatch per cascade, a thread per probe and direction: the interval's ray as a path of the
//   reference path tracer's wavefront (pathtracing/paths.slang's probe mode with PATH_INTERVALS), hits shaded by their
//   material type, the further bounces from the cascades of the frame before; and from a coarser cascade's probes, the
//   rays to where its intervals start (PATH_NEAR). Slots that scrolled to another probe stop being sampled until they
//   hold its light.
// - mergeMain, a dispatch per cascade, last to first, a group per probe: each direction's light, or where its ray met
//   nothing within its interval, the next cascade's merged light around the probe in the four directions refining it,
//   weighed trilinearly over the eight probes around it not inside geometry whose intervals continue this one: a ray
//   from where this interval ends to where theirs start meets nothing (CASCADES_VISIBILITY; no light from the far side
//   of a wall), and of their finer directions those whose start a surface separates from the coarser one's are left
//   out (each cascade marks them as it merges); written in place. Then the probe as shading samples it: its merged
//   light gathered into the first cascade's directions (each standing for the 4^c of this cascade's within its
//   octahedral texel; a coarser cascade's own ray along it where that met a surface before the intervals start), into
//   irradiance and sky visibility per octahedral texel (cosine and solid angle weighted), the distances into mean and
//   mean square, and its state (inside geometry: not sampled).
import probes.volume;
import surfaces;
import bindless;
import raytracing;

/** A ray as the path tracer's wavefront takes it: pathtracing/paths.slang's PathState, the same 80 bytes. */
struct ProbePath
{
    float3 origin;
    uint pixel;
    float3 direction;
    uint flags;
    float3 throughput;
    uint rng;
    uint instance;
    uint primitive;
    float2 bary;
    float t;
    uint bin;
    float cone;
    uint pad;
};

/** One cascade, as RadianceCascades writes it: 80 bytes of four-component rows (the same in every layout). */
struct CascadeLevel
{
    float4 position;   // xyz: probe (0, 0, 0) relative to the volume's origin; w: the spacing
    int4 base;         // xyz: that probe's world grid coordinate; w: directions along each side of a probe's tile
    uint4 probes;      // xyz: probes along each axis; w: the cascade's first probe in the list of every cascade's
    float4 interval;   // x: how far from its probe a ray starts; y: how long it is (0: on to the environment)
    uint4 layout;      // x: the cascade's first row in the ray image; y, z: the column and row its probes' own
                       // rays to where its intervals start begin at there (a tile of the first cascade's size each);
                       // w: 1 for the reach beyond the last cascade, whose probes' own rays go on to the environment
                       // and which has no intervals
};

struct CascadePush
{
    ProbeVolumeData* volume;  // the cascades as a probe volume: what shading samples
    CascadeLevel* levels;
    ProbePath* rays;          // generateMain: the paths appended
    uint* counts;             // generateMain: counts[0] the paths
    uint* inside;             // per probe of every cascade: 1 where most of its rays met back faces
    uint64_t scene;           // RayScene*: rays between probes
    float3 toAnchor;          // the volume's origin less the anchor camera (rays start relative to its camera)
    uint rayImage;            // storage slot: per probe and direction, the light found (rgb) and the distance (a)
    float3 offset;            // the anchor camera less the top level's origin
    uint irradiance;          // storage slots of the volume's atlases
    uint depth;
    uint cascade;             // this dispatch's
    uint cascades;
    uint flags;               // CASCADES_*
};

[[vk::push_constant]]
ConstantBuffer<CascadePush> push;

/** Merges take only probes of the next cascade a probe sees. */
static const uint CASCADES_VISIBILITY = 1;
/** First-cascade probes inside geometry are not sampled. */
static const uint CASCADES_CLASSIFY = 2;

/** pathtracing/paths.slang's flags of an interval's ray (PATH_INTERVALS). */
static const uint PATH_CASCADE_SHIFT = 16;
static const uint PATH_BOUNDED = 1u << 24;
static const uint PATH_NEAR = 1u << 25;
static const uint NO_BIN = 0xffffffffu;
static const float MISSED = 10000; // a ray's distance where it met nothing

/** The most directions of a first-cascade probe (8 x 8): of every probe as shading samples it. */
static const uint MAX_FIRST = 64;

uint seed(uint2 pixel)
{
    uint s = (pixel.x * 73856093u) ^ (pixel.y * 19349663u);
    s = s * 747796405u + 2891336453u;
    return s;
}

/** Probe `index` of a grid of `n` (x fastest, then z, then y: as a probe volume's slots). */
int3 probeOf(uint index, uint3 n)
{
    return int3(int(index % n.x), int(index / (n.x * n.z)), int((index / n.x) % n.z));
}

uint indexOf(int3 p, uint3 n)
{
    return uint(p.x) + n.x * (uint(p.z) + n.z * uint(p.y));
}

/** The direction of texel `uv` of an octahedral tile `side` texels a side: its center. */
float3 tileDirection(uint2 uv, uint side)
{
    return fromOctahedral((float2(uv) + 0.5) / float(side) * 2 - 1);
}

/** The solid angle of the octahedral texel around unit `d`, up to a constant: (|x| + |y| + |z|)^3. */
float solidAngle(float3 d)
{
    float l1 = abs(d.x) + abs(d.y) + abs(d.z);
    return l1 * l1 * l1;
}

/** The ray image's texel of cascade `c`'s probe `p`, direction `uv`. */
uint2 rayTexel(uint c, int3 p, uint2 uv)
{
    uint side = uint(push.levels[c].base.w);
    uint3 n = push.levels[c].probes.xyz;
    uint row = push.levels[c].layout.x;
    return uint2((uint(p.x) + n.x * uint(p.z)) * side + uv.x, row + uint(p.y) * side + uv.y);
}

/** The ray image's texel of coarser cascade `c`'s probe `p`'s own ray to where its intervals start, direction `uv`. */
uint2 nearTexel(uint c, int3 p, uint2 uv)
{
    uint side = uint(push.levels[0].base.w);
    uint3 n = push.levels[c].probes.xyz;
    uint4 layout = push.levels[c].layout;
    return uint2(layout.y + (uint(p.x) + n.x * uint(p.z)) * side + uv.x, layout.z + uint(p.y) * side + uv.y);
}

[shader("compute")]
[numthreads(64, 1, 1)]
void generateMain(uint3 id : SV_DispatchThreadID)
{
    uint c = push.cascade;
    uint side = uint(push.levels[c].base.w);
    uint first = uint(push.levels[0].base.w);
    uint3 n = push.levels[c].probes.xyz;
    uint k = id.x;
    uint index = id.y + id.z * 65535;
    // A coarser cascade's probe's own rays, along the first cascade's directions, after its intervals'; the reach's
    // probes' alone
    bool far = push.levels[c].layout.w != 0;
    uint intervals = far ? 0 : side * side;
    uint near = c > 0 ? first * first : 0;
    if (k >= intervals + near || index >= n.x * n.y * n.z)
    {
        return;
    }
    int3 p = probeOf(index, n);
    float4 level = push.levels[c].position;
    float3 position = level.xyz + float3(p) * level.w;
    float start = push.levels[c].interval.x;
    float length = push.levels[c].interval.y;
    if (k == 0)
    {
        // A slot that scrolled to another probe holds another's light: not sampled (the hits' further bounces read
        // the frame before's) until this frame's merge
        int3 coordinate = push.levels[c].base.xyz + p;
        int3 slot = wrapProbe(coordinate, int3(n));
        ProbeState* states = push.volume[0].states;
        uint s;
        probeTile(push.volume, c, slot, s);
        if (any(states[s].coordinates != coordinate))
        {
            states[s].coordinates = coordinate;
            states[s].offset = 0;
            states[s].flags = 0;
            states[s].frames = 0;
        }
    }
    uint2 texel;
    float3 d;
    uint flags;
    float3 origin;
    if (k < intervals)
    {
        uint2 uv = uint2(k % side, k / side);
        d = tileDirection(uv, side);
        texel = rayTexel(c, p, uv);
        origin = position + d * start;
        flags = (c << PATH_CASCADE_SHIFT) | (length > 0 ? PATH_BOUNDED : 0);
    }
    else
    {
        uint2 uv = uint2((k - intervals) % first, (k - intervals) / first);
        d = tileDirection(uv, first);
        texel = nearTexel(c, p, uv);
        origin = position;
        flags = (c << PATH_CASCADE_SHIFT) | (far ? 0 : PATH_BOUNDED | PATH_NEAR);
    }
    uint slot;
    InterlockedAdd(push.counts[0], 1, slot);
    ProbePath path;
    path.origin = origin + push.toAnchor;
    path.direction = d;
    path.pixel = texel.x | texel.y << 16;
    path.flags = flags;
    path.throughput = 1;
    path.rng = seed(texel); // the same each frame: no history to average a changing one
    path.instance = 0;
    path.primitive = 0;
    path.bary = 0;
    path.t = 0;
    path.bin = NO_BIN;
    path.cone = 0;
    path.pad = 0; // the distance travelled before this ray (through surfaces that let it pass)
    push.rays[slot] = path;
}

groupshared float weights[8];      // per probe of the next cascade around this one: trilinear, not inside geometry
groupshared uint insideRays;
groupshared uint insideNear;
groupshared float4 chunkLight[64]; // a chunk of directions: light and sky visibility, solid angle weighted
groupshared float3 chunkDepth[64]; // and the distance, its square, the solid angle
groupshared float4 merged[MAX_FIRST];  // per direction of the first cascade's: the probe's light, sky visibility
groupshared float2 moments[MAX_FIRST]; // and the mean distance, mean squared
groupshared float4 gathered[MAX_FIRST]; // and its direction (xyz), the solid angle of its octahedral texel (w)
groupshared float4 newIrradiance[36];
groupshared float2 newDepth[196];

/** The interior texel a tile's texel (border included, 0 to n + 1) shows: itself, or across the seam (DDGI). */
uint2 borderSource(uint2 t, uint n)
{
    uint last = n + 1;
    bool left = t.x == 0;
    bool right = t.x == last;
    bool top = t.y == 0;
    bool bottom = t.y == last;
    if ((left || right) && (top || bottom))
    {
        return uint2(left ? n : 1, top ? n : 1);
    }
    if (top || bottom)
    {
        return uint2(last - t.x, top ? 1 : n);
    }
    if (left || right)
    {
        return uint2(left ? 1 : n, last - t.y);
    }
    return t;
}

/** The octahedral direction of interior texel `t` of a tile of `n` interior texels a side. */
float3 texelDirection(uint2 t, uint n)
{
    float2 p = (float2(t) + 0.5) / float(n) * 2 - 1;
    return fromOctahedral(p);
}

[shader("compute")]
[numthreads(64, 1, 1)]
void mergeMain(uint3 group : SV_GroupID, uint3 thread : SV_GroupThreadID)
{
    uint c = push.cascade;
    uint3 n = push.levels[c].probes.xyz;
    uint index = group.x + group.y * 65535;
    if (index >= n.x * n.y * n.z)
    {
        return; // the same for the whole group
    }
    uint t = thread.x;
    uint side = uint(push.levels[c].base.w);
    bool far = push.levels[c].layout.w != 0; // the reach: no intervals, its probes' own rays alone
    uint directions = far ? 0 : side * side;
    int3 p = probeOf(index, n);
    float4 level = push.levels[c].position;
    float3 position = level.xyz + float3(p) * level.w;
    bool last = c + 1 >= push.cascades;
    // The next cascade's probes around this one: trilinear weights, those inside geometry left out
    int3 lower = 0;
    float3 toNext = 0;
    if (!last)
    {
        float4 next = push.levels[c + 1].position;
        int3 m = int3(push.levels[c + 1].probes.xyz);
        float3 local = (position - next.xyz) / next.w;
        lower = clamp(int3(floor(local)), int3(0), m - 2);
        toNext = next.xyz + float3(lower) * next.w - position;
        if (t < 8)
        {
            int3 o = int3(t & 1, (t >> 1) & 1, t >> 2);
            float3 alpha = saturate(local - float3(lower));
            float3 trilinear = lerp(1 - alpha, alpha, float3(o));
            float around = trilinear.x * trilinear.y * trilinear.z;
            if (push.inside[push.levels[c + 1].probes.w + indexOf(lower + o, uint3(m))] != 0)
            {
                around = 0;
            }
            weights[t] = around;
        }
    }
    if (t == 0)
    {
        insideRays = 0;
        insideNear = 0;
    }
    GroupMemoryBarrierWithGroupSync();
    float w[8] = { 0, 0, 0, 0, 0, 0, 0, 0 };
    for (uint j = 0; j < 8 && !last; j++)
    {
        w[j] = weights[j];
    }
    float spacingNext = last ? 0.0 : push.levels[c + 1].position.w;
    float start = push.levels[c].interval.x;
    float end = start + push.levels[c].interval.y;
    bool connect = (push.flags & CASCADES_VISIBILITY) != 0;
    RayScene* scene = (RayScene*)push.scene;
    float maxDistance = push.volume[0].maxDistance * level.w;
    // The directions in the order of the first cascade's they lie within (fine x fine of this one's each), so that a
    // chunk of 64 holds whole ones or a part of one
    uint first = uint(push.levels[0].base.w);
    uint fine = side / first;
    uint within = fine * fine;
    float4 binLight = 0;
    float3 binDepth = 0;
    for (uint chunk = 0; chunk < directions; chunk += 64)
    {
        uint k = chunk + t;
        float4 shade = 0;
        float3 depth = 0;
        if (k < directions)
        {
            uint bin = k / within;
            uint inner = k % within;
            uint2 uv = uint2((bin % first) * fine + inner % fine, (bin / first) * fine + inner / fine);
            uint2 texel = rayTexel(c, p, uv);
            float4 ray = images[push.rayImage][texel];
            float4 light;
            if (ray.a < 0)
            {
                // A back face: the interval starts behind a surface, what it found is not light along the direction
                // (its texel marked so for the cascade before's merge, which leaves it out)
                InterlockedAdd(insideRays, 1u);
                light = float4(0, 0, 0, -1);
            }
            else if (ray.a < MISSED * 0.5)
            {
                light = float4(ray.rgb, 0);
            }
            else if (last)
            {
                light = float4(ray.rgb, 1); // the environment
            }
            else
            {
                // Beyond the interval: the next cascade's light around the probe, in the four directions refining
                // this one, from the probes whose intervals continue this one's (nothing between where it ends and
                // where theirs start: no light from beyond a wall the ray would have met); none: the probes around
                // as they are
                float3 d = tileDirection(uv, side);
                float3 from = position + d * end + push.toAnchor + push.offset;
                float4 sum = 0;
                float total = 0;
                for (uint j = 0; j < 8; j++)
                {
                    if (w[j] <= 0)
                    {
                        continue;
                    }
                    int3 o = int3(j & 1, (j >> 1) & 1, j >> 2);
                    int3 q = lower + o;
                    float4 children = 0;
                    float valid = 0;
                    for (uint i = 0; i < 4; i++)
                    {
                        float4 child = images[push.rayImage][rayTexel(c + 1, q, uv * 2 + uint2(i & 1, i >> 1))];
                        if (child.a >= 0)
                        {
                            children += child;
                            valid += 1;
                        }
                    }
                    if (valid <= 0)
                    {
                        continue; // its intervals start behind a surface here
                    }
                    children /= valid;
                    if (connect)
                    {
                        float3 between = toNext + float3(o) * spacingNext;
                        float distance = length(between);
                        if (distance > 1.0e-4 && occluded(scene, from, between / distance, 0, distance))
                        {
                            continue;
                        }
                    }
                    sum += w[j] * children;
                    total += w[j];
                }
                light = total > 0 ? sum / total : float4(0);
            }
            // As shading sees it from the probe: a back face no light, nor sky
            shade = light.a < 0 ? float4(0) : light;
            float reach = ray.a >= MISSED * 0.5 ? maxDistance : min(start + abs(ray.a), maxDistance);
            if (c > 0 && connect && light.a >= 0)
            {
                // The cascade before continues its directions here along the one this refines: where this interval
                // starts out of reach of where that one's would (a surface between), it is no continuation of it
                // (left out by that merge, as a back face is)
                float3 d = tileDirection(uv, side);
                float3 coarse = tileDirection(uv / 2, side / 2);
                float3 between = (d - coarse) * start;
                float distance = length(between);
                if (distance > 1.0e-4 && occluded(scene, position + coarse * start + push.toAnchor + push.offset,
                    between / distance, 0, distance))
                {
                    light = float4(0, 0, 0, -1);
                }
            }
            if (c > 0)
            {
                images[push.rayImage][texel] = light;
            }
            float weight = solidAngle(tileDirection(uv, side));
            shade *= weight;
            depth = float3(reach, reach * reach, 1) * weight;
        }
        chunkLight[t] = shade;
        chunkDepth[t] = depth;
        GroupMemoryBarrierWithGroupSync();
        // Each of the first cascade's directions gathers this one's within it
        uint begin = max(t * within, chunk);
        uint finish = min((t + 1) * within, chunk + 64);
        for (uint e = begin; e < finish && t < first * first; e++)
        {
            binLight += chunkLight[e - chunk];
            binDepth += chunkDepth[e - chunk];
        }
        GroupMemoryBarrierWithGroupSync();
    }
    bool inside = insideRays * 4 > directions;
    if (t < first * first)
    {
        float4 light = binDepth.z > 0 ? binLight / binDepth.z : float4(0);
        float2 depth = binDepth.z > 0 ? binDepth.xy / binDepth.z : float2(maxDistance, maxDistance * maxDistance);
        if (c > 0)
        {
            // Nearer than its intervals start: the probe's own ray along the direction (a surface there hides what
            // the intervals found beyond it)
            float4 near = images[push.rayImage][nearTexel(c, p, uint2(t % first, t / first))];
            if (near.a < 0)
            {
                InterlockedAdd(insideNear, 1u);
                light = 0;
                float distance = min(abs(near.a), maxDistance);
                depth = float2(distance, distance * distance);
            }
            else if (near.a < MISSED * 0.5)
            {
                light = float4(near.rgb, 0);
                float distance = min(near.a, maxDistance);
                depth = float2(distance, distance * distance);
            }
            else if (far)
            {
                light = float4(near.rgb, 1); // the environment
            }
        }
        merged[t] = light;
        moments[t] = depth;
        float3 d = tileDirection(uint2(t % first, t / first), first);
        gathered[t] = float4(d, solidAngle(d));
    }
    GroupMemoryBarrierWithGroupSync();
    if (c > 0)
    {
        if (t == 0)
        {
            push.inside[push.levels[c].probes.w + index] = inside ? 1 : 0;
        }
        // As shading samples it, inside geometry where most of its own rays met back faces
        inside = insideNear * 4 > first * first;
    }
    // The probe's irradiance / pi and sky visibility per texel, its distance moments
    uint count = first * first;
    if (t < 36)
    {
        uint2 texel = uint2(t % 6, t / 6);
        float3 normal = texelDirection(texel, 6);
        float4 sum = 0;
        float total = 0;
        for (uint k = 0; k < count; k++)
        {
            float4 d = gathered[k];
            float weight = max(dot(normal, d.xyz), 0.0) * d.w;
            sum += weight * merged[k];
            total += weight;
        }
        newIrradiance[t] = total > 0 ? sum / total : 0;
    }
    // A kernel about as wide as the directions are apart (DDGI's cos^50 for 128 rays), by squaring
    for (uint e = t; e < 196; e += 64)
    {
        uint2 texel = uint2(e % 14, e / 14);
        float3 normal = texelDirection(texel, 14);
        float2 sum = 0;
        float total = 0;
        for (uint k = 0; k < count; k++)
        {
            float4 d = gathered[k];
            float c = max(dot(normal, d.xyz), 0.0);
            float c2 = c * c;
            float c16 = c2 * c2;
            c16 *= c16;
            c16 *= c16;
            float weight = c16 * c16 * c16 * c2 * d.w; // c^50
            sum += weight * moments[k];
            total += weight;
        }
        newDepth[e] = total > 0 ? sum / total : float2(maxDistance, maxDistance * maxDistance);
    }
    GroupMemoryBarrierWithGroupSync();
    int3 coordinate = push.levels[c].base.xyz + p;
    int3 slot = wrapProbe(coordinate, int3(n));
    uint s;
    uint2 tile = probeTile(push.volume, c, slot, s);
    uint2 irradianceTile = tile * PROBE_IRRADIANCE_TEXELS;
    uint2 depthTile = tile * PROBE_DEPTH_TEXELS;
    {
        uint2 texel = uint2(t % 8, t / 8);
        uint2 source = borderSource(texel, 6) - 1;
        images[push.irradiance][irradianceTile + texel] = newIrradiance[source.y * 6 + source.x];
    }
    for (uint e = t; e < 256; e += 64)
    {
        uint2 texel = uint2(e % 16, e / 16);
        uint2 source = borderSource(texel, 14) - 1;
        images[push.depth][depthTile + texel] = float4(newDepth[source.y * 14 + source.x], 0, 0);
    }
    if (t == 0)
    {
        ProbeState* states = push.volume[0].states;
        bool sampled = !inside || (push.flags & CASCADES_CLASSIFY) == 0;
        states[s].coordinates = coordinate;
        states[s].offset = 0;
        states[s].flags = PROBE_VALID | (sampled ? PROBE_ACTIVE : 0);
        states[s].frames = 1;
    }
}
