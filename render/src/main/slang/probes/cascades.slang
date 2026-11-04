// Radiance cascades in world space (engine-render's RadianceCascades, design D9; Sannikov 2023): grids of probes
// around an anchor camera, each cascade's probes twice as far apart as the one before's and with four times as many
// directions, each direction a ray along an interval four times as long as the one before's and starting where it
// ended. No history: every frame finds every probe's light anew, and merges the cascades from the last (whose rays go
// on to the environment) to the first, each direction of a probe that met nothing within its interval taking what the
// next cascade's probes around it found beyond, in its four finer directions. The first cascade's merged light becomes
// a probe volume of one cascade, as indirect.slang's probeLight samples it (irradiance / pi with the sky's visibility,
// and distance moments), so every shading path reads it as it reads the ray-traced probe volume. A frame's passes:
//
// - generateMain, a dispatch per cascade, a thread per probe and direction: the interval's ray as a path of the
//   reference path tracer's wavefront (pathtracing/paths.slang's probe mode with PATH_INTERVALS), hits shaded by their
//   material type, the further bounces from the first cascade's probes of the frame before; the first cascade's slots
//   that scrolled to another probe stop being sampled until they hold its light.
// - mergeMain, a dispatch per cascade, last to first, a group per probe: each direction's light, or where its ray met
//   nothing within its interval, the next cascade's merged light around the probe in the four directions refining it,
//   weighed trilinearly over the eight probes around it not inside geometry whose intervals continue this one: a ray
//   from where this interval ends to where theirs start meets nothing (CASCADES_VISIBILITY; no light from the far side
//   of a wall), and of their finer directions those whose start a surface separates from the coarser one's are left
//   out (each cascade marks them as it merges); written in place. For the first cascade, the merged light into the
//   probe's irradiance and sky visibility per octahedral texel (cosine and solid angle weighted), its rays' distances
//   into mean and mean square, and its state (inside geometry: not sampled).
import indirect;
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
    uint4 layout;      // x: the cascade's first row in the ray image
};

struct CascadePush
{
    ProbeVolumeData* volume;  // the first cascade as a probe volume of one cascade: what shading samples
    CascadeLevel* levels;
    ProbePath* rays;          // generateMain: the paths appended
    uint* counts;             // generateMain: counts[0] the paths
    uint* inside;             // per probe of every cascade: 1 where most of its rays met back faces
    uint64_t scene;           // RayScene*: rays between probes
    float3 toAnchor;          // the volume's origin less the anchor camera (rays start relative to its camera)
    uint rayImage;            // storage slot: per probe and direction, the light found (rgb) and the distance (a)
    float3 offset;            // the anchor camera less the top level's origin
    uint irradiance;          // storage slots of the first cascade's atlases
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
static const uint NO_BIN = 0xffffffffu;
static const float MISSED = 10000; // a ray's distance where it met nothing

/** The most directions of a first-cascade probe (8 x 8). */
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

[shader("compute")]
[numthreads(64, 1, 1)]
void generateMain(uint3 id : SV_DispatchThreadID)
{
    uint c = push.cascade;
    uint side = uint(push.levels[c].base.w);
    uint3 n = push.levels[c].probes.xyz;
    uint k = id.x;
    uint index = id.y + id.z * 65535;
    if (k >= side * side || index >= n.x * n.y * n.z)
    {
        return;
    }
    int3 p = probeOf(index, n);
    float4 level = push.levels[c].position;
    float3 position = level.xyz + float3(p) * level.w;
    uint2 uv = uint2(k % side, k / side);
    float3 d = tileDirection(uv, side);
    float start = push.levels[c].interval.x;
    float length = push.levels[c].interval.y;
    if (c == 0 && k == 0)
    {
        // A slot that scrolled to another probe holds another's light: not sampled (the hits' further bounces read
        // the frame before's) until this frame's merge
        int3 probes = int3(n);
        int3 coordinate = push.levels[0].base.xyz + p;
        int3 slot = wrapProbe(coordinate, probes);
        ProbeState* states = push.volume[0].states;
        uint s = indexOf(slot, n);
        if (any(states[s].coordinates != coordinate))
        {
            states[s].coordinates = coordinate;
            states[s].offset = 0;
            states[s].flags = 0;
            states[s].frames = 0;
        }
    }
    uint slot;
    InterlockedAdd(push.counts[0], 1, slot);
    uint2 texel = rayTexel(c, p, uv);
    ProbePath path;
    path.origin = position + d * start + push.toAnchor;
    path.direction = d;
    path.pixel = texel.x | texel.y << 16;
    path.flags = (c << PATH_CASCADE_SHIFT) | (length > 0 ? PATH_BOUNDED : 0);
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
groupshared float4 merged[MAX_FIRST];
groupshared float distances[MAX_FIRST];
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
    uint directions = side * side;
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
    }
    GroupMemoryBarrierWithGroupSync();
    float w[8] = { 0, 0, 0, 0, 0, 0, 0, 0 };
    for (uint j = 0; j < 8 && !last; j++)
    {
        w[j] = weights[j];
    }
    float spacingNext = last ? 0.0 : push.levels[c + 1].position.w;
    float end = push.levels[c].interval.x + push.levels[c].interval.y;
    bool connect = (push.flags & CASCADES_VISIBILITY) != 0;
    RayScene* scene = (RayScene*)push.scene;
    float maxDistance = push.volume[0].maxDistance * level.w;
    for (uint k = t; k < directions; k += 64)
    {
        uint2 uv = uint2(k % side, k / side);
        uint2 texel = rayTexel(c, p, uv);
        float4 ray = images[push.rayImage][texel];
        float4 light;
        if (ray.a < 0)
        {
            // A back face: the interval starts behind a surface, what it found is not light along the direction (its
            // texel marked so for the cascade before's merge, which leaves it out)
            InterlockedAdd(insideRays, 1u);
            light = c == 0 ? float4(0) : float4(0, 0, 0, -1);
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
            // Beyond the interval: the next cascade's light around the probe, in the four directions refining this one,
            // from the probes whose intervals continue this one's (nothing between where it ends and where theirs
            // start: no light from beyond a wall the ray would have met); none: the probes around as they are
            float3 d = tileDirection(uv, side);
            float3 from = position + d * end + push.toAnchor + push.offset;
            int3 m = int3(push.levels[c + 1].probes.xyz);
            float4 sum = 0;
            float total = 0;
            float4 all = 0;
            float allTotal = 0;
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
                all += w[j] * children;
                allTotal += w[j];
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
        if (c > 0 && connect && light.a >= 0)
        {
            // The cascade before continues its directions here along the one this refines: where this interval
            // starts out of reach of where that one's would (a surface between), it is no continuation of it (left out
            // by that merge, as a back face is)
            float3 d = tileDirection(uv, side);
            float3 coarse = tileDirection(uv / 2, side / 2);
            float start = push.levels[c].interval.x;
            float3 between = (d - coarse) * start;
            float distance = length(between);
            if (distance > 1.0e-4 && occluded(scene, position + coarse * start + push.toAnchor + push.offset,
                between / distance, 0, distance))
            {
                light = float4(0, 0, 0, -1);
            }
        }
        if (c == 0)
        {
            merged[k] = light;
            distances[k] = ray.a >= MISSED * 0.5 ? maxDistance : min(abs(ray.a), maxDistance);
        }
        else
        {
            images[push.rayImage][texel] = light;
        }
    }
    GroupMemoryBarrierWithGroupSync();
    bool inside = insideRays * 4 > directions;
    if (c > 0)
    {
        if (t == 0)
        {
            push.inside[push.levels[c].probes.w + index] = inside ? 1 : 0;
        }
        return;
    }
    // The first cascade: the probe's irradiance / pi and sky visibility per texel, its distance moments
    if (t < 36)
    {
        uint2 texel = uint2(t % 6, t / 6);
        float3 normal = texelDirection(texel, 6);
        float4 sum = 0;
        float total = 0;
        for (uint k = 0; k < directions; k++)
        {
            float3 d = tileDirection(uint2(k % side, k / side), side);
            float weight = max(dot(normal, d), 0.0) * solidAngle(d);
            sum += weight * merged[k];
            total += weight;
        }
        newIrradiance[t] = total > 0 ? sum / total : 0;
    }
    // A kernel about as wide as the directions are apart (DDGI's cos^50 for 128 rays)
    float power = 50.0;
    for (uint e = t; e < 196; e += 64)
    {
        uint2 texel = uint2(e % 14, e / 14);
        float3 normal = texelDirection(texel, 14);
        float2 sum = 0;
        float total = 0;
        for (uint k = 0; k < directions; k++)
        {
            float3 d = tileDirection(uint2(k % side, k / side), side);
            float weight = pow(max(dot(normal, d), 0.0), power) * solidAngle(d);
            float distance = distances[k];
            sum += weight * float2(distance, distance * distance);
            total += weight;
        }
        newDepth[e] = total > 0 ? sum / total : float2(maxDistance, maxDistance * maxDistance);
    }
    GroupMemoryBarrierWithGroupSync();
    int3 probes = int3(n);
    int3 coordinate = push.levels[0].base.xyz + p;
    int3 slot = wrapProbe(coordinate, probes);
    uint column = uint(slot.x + probes.x * slot.z);
    uint row = uint(slot.y);
    uint2 irradianceTile = uint2(column, row) * PROBE_IRRADIANCE_TEXELS;
    uint2 depthTile = uint2(column, row) * PROBE_DEPTH_TEXELS;
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
        uint s = indexOf(slot, n);
        bool sampled = !inside || (push.flags & CASCADES_CLASSIFY) == 0;
        states[s].coordinates = coordinate;
        states[s].offset = 0;
        states[s].flags = PROBE_VALID | (sampled ? PROBE_ACTIVE : 0);
        states[s].frames = 1;
    }
}
