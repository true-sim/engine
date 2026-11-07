// The probe volume's update (engine-render's ProbeVolume, design S2; DDGI: Majercik, Guertin, Nowrouzezahrai and
// McGuire 2019; Majercik, Mueller, Keller, Nowrouzezahrai and McGuire 2021), a frame's passes:
//
// - prepareMain, a thread per probe slot: a slot whose grid coordinate scrolled to another probe (the cascade moved
//   with the anchor) starts anew; a probe the invalidation marked (a light or an instance changed near it, invalidate
//   .slang) or every probe when the environment changed restarts its history; the probes to update this frame are
//   marked (all of them, or a share in turns plus those restarted).
// - generateMain, a thread per ray: each probe updated sends its rays (spherical Fibonacci directions turned by a
//   rotation new each frame, and a fixed set never turned, for relocation and classification; inactive probes send
//   only those) as paths of the reference path tracer's wavefront (pathtracing/paths.slang, its probe mode), whose
//   hits are shaded by their material type with the same BRDF, lights and environment, the volume's own light of the
//   frame before giving the further bounces.
// - blendMain, a group per probe: the rays' light into the probe's irradiance (cosine-weighted, as irradiance / pi)
//   and sky visibility per octahedral texel, the distances into mean and mean square per texel (a power of the cosine),
//   the history weighed by one over its frames (capped at maxFrames: the still scene's lag), and restarted where this
//   update disagrees with it beyond its noise (adaptive hysteresis: changes nothing announced); borders copied for
//   bilinear filtering across the octahedron's seams; the probe relocated off nearby surfaces and classified (inside
//   geometry: inactive).
import probes.volume;
import surfaces;
import bindless;

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

static const uint NO_BIN = 0xffffffffu;

/** paths.slang's seed for texel `pixel` and frame `sample`, PCG stepped once. */
uint seed(uint2 pixel, uint sample)
{
    uint s = (pixel.x * 73856093u) ^ (pixel.y * 19349663u) ^ (sample * 83492791u);
    s = s * 747796405u + 2891336453u;
    return s;
}

struct ProbePush
{
    ProbeVolumeData* volume;
    ProbePath* rays;          // generateMain: the paths appended
    uint* counts;             // generateMain: counts[0] the paths
    uint* status;             // status[0]: invalidate.slang's PROBES_RESET_ALL
    float3 toAnchor;          // the volume's origin less the anchor camera (rays start relative to its camera)
    uint rayCount;            // rays per probe updated
    uint fixedRays;           // of them, never turned: relocation and classification
    uint rayImage;            // storage slot: per probe and ray, the light found (rgb) and the distance (a)
    uint irradiance;          // storage slots of the atlases
    uint depth;
    uint frame;
    uint period;              // probes are updated every period frames, in turns (1: every frame)
    uint maxFrames;           // the history's frames at most
    uint flags;               // PROBES_*
    float sensitivity;        // adaptive hysteresis: deviations of the update's noise a texel may differ by
    float drift;              // and how far the updates' mean relative difference from the history may drift
    uint fresh;               // a probe whose history holds fewer updates updates every frame, budget or not
    uint variances;           // storage slot: per irradiance texel, the mean variance of its updates (their noise)
    float4 rotation;          // this frame's rotation of the rays (a unit quaternion)
};

[[vk::push_constant]]
ConstantBuffer<ProbePush> push;

static const uint PROBES_ADAPTIVE = 1;   // restart a history where the update disagrees with it
static const uint PROBES_RESET_ALL = 2;  // every history restarts (the environment changed, the volume was made)
static const uint PROBES_RELOCATE = 4;   // move probes off nearby surfaces
static const uint PROBES_CLASSIFY = 8;   // turn off probes inside geometry

/** Probe flags beside volume.slang's: marked by invalidate.slang, and to update this frame. */
static const uint PROBE_INVALIDATED = 4;
static const uint PROBE_UPDATE = 8;
/** Its history restarted this frame: a repeated update (render.gi.response.iterations) refines it again. */
static const uint PROBE_RESTARTED = 16;

/** Probes per row of the ray image. */
static const uint RAY_ROW = 32;
static const uint MAX_RAYS = 256;
static const float MISSED = 10000; // a ray's distance where it met nothing

/** The slot of probe `index` (as ordered in the states): its cascade and its place in the cascade's grid. */
int3 slotOf(uint index, int3 probes, out uint cascade)
{
    uint perCascade = uint(probes.x * probes.y * probes.z);
    cascade = index / perCascade;
    uint i = index % perCascade;
    int x = int(i % uint(probes.x));
    uint rest = i / uint(probes.x);
    int z = int(rest % uint(probes.z));
    int y = int(rest / uint(probes.z));
    return int3(x, y, z);
}

/** The world grid coordinate slot `slot` of cascade `cascade` holds now: the one of the cascade's grid it wraps to. */
int3 coordinateOf(int3 slot, uint cascade, int3 probes)
{
    int3 base = push.volume[0].base[cascade].xyz;
    return base + wrapProbe(slot - base, probes);
}

/** Probe `index`'s position relative to the volume's origin, its relocation not added. */
float3 gridPosition(uint index, int3 probes, out uint cascade, out float spacing)
{
    int3 slot = slotOf(index, probes, cascade);
    int3 coordinate = coordinateOf(slot, cascade, probes);
    float4 c = push.volume[0].cascade[cascade];
    spacing = c.w;
    return c.xyz + float3(coordinate - push.volume[0].base[cascade].xyz) * spacing;
}

uint probeCount()
{
    uint3 p = push.volume[0].probes;
    return p.x * p.y * p.z * push.volume[0].cascades;
}

[shader("compute")]
[numthreads(64, 1, 1)]
void prepareMain(uint3 id : SV_DispatchThreadID)
{
    uint index = id.x;
    if (index >= probeCount())
    {
        return;
    }
    int3 probes = int3(push.volume[0].probes);
    ProbeState* states = push.volume[0].states;
    uint cascade;
    int3 slot = slotOf(index, probes, cascade);
    int3 coordinate = coordinateOf(slot, cascade, probes);
    uint flags = states[index].flags;
    uint frames = states[index].frames;
    bool restart = (push.flags & PROBES_RESET_ALL) != 0 || push.status[0] != 0
        || (flags & PROBE_INVALIDATED) != 0;
    if (any(states[index].coordinates != coordinate) || (flags & PROBE_VALID) == 0)
    {
        // A probe new to its slot: nothing it holds is its own
        states[index].coordinates = coordinate;
        states[index].offset = 0;
        flags = PROBE_ACTIVE;
        restart = true;
    }
    if (restart)
    {
        frames = 0;
        states[index].drift = 0;
        states[index].pad0 = 0;
    }
    // A restarted probe (a change told or found) updates every frame until its history holds a few updates: the budget
    // spreads still probes' updates, not a change's response
    bool update = restart || frames < push.fresh || (index + push.frame) % max(push.period, 1u) == 0;
    flags &= ~(PROBE_INVALIDATED | PROBE_UPDATE | PROBE_RESTARTED);
    states[index].flags = flags | (update ? PROBE_UPDATE : 0);
    states[index].frames = frames;
}

/**
 * A repeated update within the frame: the probes whose history restarted update again, from scratch, their rays'
 * hits now reading this frame's first update of the volume: one more bounce of the new light per repeat, so a change
 * spreads through high albedo rooms in fewer frames (no image waits for it).
 */
[shader("compute")]
[numthreads(64, 1, 1)]
void prepareAgainMain(uint3 id : SV_DispatchThreadID)
{
    uint index = id.x;
    if (index >= probeCount())
    {
        return;
    }
    ProbeState* states = push.volume[0].states;
    uint flags = states[index].flags;
    if ((flags & PROBE_RESTARTED) != 0 && (flags & PROBE_ACTIVE) != 0)
    {
        states[index].flags = flags | PROBE_UPDATE;
        states[index].frames = 0;
    }
    else
    {
        states[index].flags = flags & ~PROBE_UPDATE;
    }
}

/** Point `i` of `n` on the sphere, spherical Fibonacci (as DDGI's rays). */
float3 fibonacci(float i, float n)
{
    const float golden = 1.61803398875;
    float phi = 6.28318531 * frac(i * (golden - 1));
    float cosTheta = 1 - (2 * i + 1) / n;
    float sinTheta = sqrt(saturate(1 - cosTheta * cosTheta));
    return float3(cos(phi) * sinTheta, sin(phi) * sinTheta, cosTheta);
}

float3 rotateQ(float4 q, float3 v)
{
    float3 t = 2.0 * cross(q.xyz, v);
    return v + q.w * t + cross(q.xyz, t);
}

/** Ray `k` of a probe this frame: the fixed set first, then the turned ones. */
float3 rayDirection(uint k)
{
    uint fixed = push.fixedRays;
    if (k < fixed)
    {
        return fibonacci(float(k), float(fixed));
    }
    return rotateQ(push.rotation, fibonacci(float(k - fixed), float(push.rayCount - fixed)));
}

/** The ray image's texel of probe `index`'s ray `k`. */
uint2 rayTexel(uint index, uint k)
{
    return uint2(k + push.rayCount * (index % RAY_ROW), index / RAY_ROW);
}

[shader("compute")]
[numthreads(64, 1, 1)]
void generateMain(uint3 id : SV_DispatchThreadID)
{
    uint k = id.x;
    uint index = id.y + id.z * 65535;
    if (k >= push.rayCount || index >= probeCount())
    {
        return;
    }
    ProbeState* states = push.volume[0].states;
    uint flags = states[index].flags;
    if ((flags & PROBE_UPDATE) == 0 || ((flags & PROBE_ACTIVE) == 0 && k >= push.fixedRays))
    {
        return;
    }
    int3 probes = int3(push.volume[0].probes);
    uint cascade;
    float spacing;
    float3 position = gridPosition(index, probes, cascade, spacing) + states[index].offset;
    uint slot;
    InterlockedAdd(push.counts[0], 1, slot);
    uint2 texel = rayTexel(index, k);
    ProbePath path;
    path.origin = position + push.toAnchor;
    path.direction = rayDirection(k);
    path.pixel = texel.x | texel.y << 16;
    path.flags = 0;
    path.throughput = 1;
    path.rng = seed(texel, push.frame);
    path.instance = 0;
    path.primitive = 0;
    path.bary = 0;
    path.t = 0;
    path.bin = NO_BIN;
    path.cone = 0;
    path.pad = 0; // the distance travelled before this ray (through surfaces that let it pass)
    push.rays[slot] = path;
}

groupshared float4 rayLight[MAX_RAYS];  // rgb the light, a the distance (negative: a back face)
groupshared float3 rayWay[MAX_RAYS];
groupshared float4 newIrradiance[36];
groupshared float2 newDepth[196];
groupshared uint changed;
groupshared float2 relative[36];
groupshared float noises[36];          // per irradiance texel: an update's variance, as the adaptive test took it
groupshared float updated[36];         // per irradiance texel: this update's variance (of its luminance)
groupshared uint stillActive;

/** The octahedral direction of interior texel `t` of a tile of `n` interior texels a side. */
float3 texelDirection(uint2 t, uint n)
{
    float2 p = (float2(t) + 0.5) / float(n) * 2 - 1;
    return fromOctahedral(p);
}

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
        return uint2(left ? n : 1, top ? n : 1); // corners: the diagonally opposite interior corner
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

float luminance3(float3 c)
{
    return dot(c, float3(0.2126, 0.7152, 0.0722));
}

[shader("compute")]
[numthreads(256, 1, 1)]
void blendMain(uint3 group : SV_GroupID, uint3 thread : SV_GroupThreadID)
{
    uint index = group.x + group.y * 65535;
    if (index >= probeCount())
    {
        return;
    }
    ProbeState* states = push.volume[0].states;
    uint flags = states[index].flags;
    if ((flags & PROBE_UPDATE) == 0)
    {
        return; // the same for the whole group
    }
    uint t = thread.x;
    int3 probes = int3(push.volume[0].probes);
    uint cascade;
    int3 slot = slotOf(index, probes, cascade);
    float spacing = push.volume[0].cascade[cascade].w;
    bool active = (flags & PROBE_ACTIVE) != 0;
    uint traced = active ? push.rayCount : push.fixedRays;
    for (uint k = t; k < traced; k += 256)
    {
        rayLight[k] = images[push.rayImage][rayTexel(index, k)];
        rayWay[k] = rayDirection(k);
    }
    if (t == 0)
    {
        changed = 0;
        stillActive = active ? 1 : 0;
    }
    GroupMemoryBarrierWithGroupSync();
    uint column = uint(slot.x + probes.x * slot.z);
    uint row = uint(slot.y + probes.y * int(cascade));
    uint2 irradianceTile = uint2(column, row) * PROBE_IRRADIANCE_TEXELS;
    uint2 depthTile = uint2(column, row) * PROBE_DEPTH_TEXELS;
    uint frames = states[index].frames;
    float maxDistance = push.volume[0].maxDistance * spacing;
    // Relocation and classification from the fixed rays (one thread: at most a few dozen rays)
    if (t == 0 && (push.flags & (PROBES_RELOCATE | PROBES_CLASSIFY)) != 0)
    {
        uint backfaces = 0;
        float closestBack = 1.0e30;
        float3 closestBackWay = 0;
        float closestFront = 1.0e30;
        float3 closestFrontWay = 0;
        float farthestFront = 0;
        float3 farthestFrontWay = 0;
        for (uint k = 0; k < push.fixedRays; k++)
        {
            float d = rayLight[k].a;
            if (d < 0)
            {
                backfaces++;
                if (-d * 5 < closestBack)
                {
                    closestBack = -d * 5; // stored shortened to a fifth
                    closestBackWay = rayWay[k];
                }
            }
            else
            {
                if (d < closestFront)
                {
                    closestFront = d;
                    closestFrontWay = rayWay[k];
                }
                if (d > farthestFront)
                {
                    farthestFront = d;
                    farthestFrontWay = rayWay[k];
                }
            }
        }
        float3 offset = states[index].offset;
        float3 before = offset;
        float inside = float(backfaces) / float(max(push.fixedRays, 1u));
        float least = 0.25 * spacing; // how near a surface a probe may sit
        if ((push.flags & PROBES_RELOCATE) != 0)
        {
            float3 moved = offset;
            if (inside > 0.25)
            {
                moved += closestBackWay * (closestBack + least * 0.5); // out through the nearest back face
            }
            else if (closestFront < least)
            {
                if (dot(closestFrontWay, farthestFrontWay) <= 0)
                {
                    moved += farthestFrontWay * min(farthestFront, least); // away, not through the farthest
                }
            }
            else if (dot(offset, offset) > 0)
            {
                // Clear of surfaces: back towards the grid's point, as far as the nearest surface allows
                float back = min(closestFront - least, length(offset));
                moved -= normalize(offset) * back;
            }
            if (all(abs(moved) <= 0.45 * spacing))
            {
                offset = moved;
            }
            states[index].offset = offset;
        }
        if ((push.flags & PROBES_CLASSIFY) != 0)
        {
            stillActive = inside > 0.25 ? 0 : 1;
        }
        else
        {
            stillActive = 1;
        }
        if (length(offset - before) > 0.05 * spacing)
        {
            changed = 1; // moved: what it saw from there is no longer its light
        }
    }
    GroupMemoryBarrierWithGroupSync();
    bool blend = active && traced > push.fixedRays;
    // The update per irradiance texel: the cosine-weighted mean of the turned rays' light (irradiance / pi) and of
    // their misses (the sky's visibility), with its noise for the adaptive hysteresis
    if (blend && t < 36)
    {
        uint2 texel = uint2(t % 6, t / 6);
        float3 n = texelDirection(texel, 6);
        float3 sum = 0;
        float sky = 0;
        float weights = 0;
        float weights2 = 0;
        float lum = 0;
        float lum2 = 0;
        for (uint k = push.fixedRays; k < traced; k++)
        {
            float4 l = rayLight[k];
            if (l.a < 0)
            {
                continue; // a back face: no light of the probe's side
            }
            float w = max(dot(n, rayWay[k]), 0.0);
            if (w <= 0)
            {
                continue;
            }
            float y = luminance3(l.rgb);
            sum += w * l.rgb;
            sky += w * (l.a >= MISSED * 0.5 ? 1.0 : 0.0);
            weights += w;
            weights2 += w * w;
            lum += w * y;
            lum2 += w * y * y;
        }
        float4 update = weights > 0 ? float4(sum / weights, sky / weights) : float4(0, 0, 0, 0);
        float4 history = images[push.irradiance][irradianceTile + 1 + texel];
        float now = luminance3(update.rgb);
        float was = luminance3(history.rgb);
        relative[t] = weights > 0 && frames > 0 ? float2(now, was) : float2(0, 0);
        noises[t] = 0;
        float mean = weights > 0 ? lum / weights : 0;
        float variance = weights > 0 ? max(lum2 / weights - mean * mean, 0.0) * weights2 / (weights * weights) : 0;
        updated[t] = variance;
        if (frames > 0 && weights > 0 && (push.flags & PROBES_ADAPTIVE) != 0)
        {
            float before = luminance3(history.rgb);
            // An update's noise is also what the history's updates showed (the mean of their variances): one update's
            // rays tell it badly where a rare ray brings much (a small bright light met in some updates only), and an
            // update that missed it seemed far beyond its noise and restarted the history, again and again
            variance = max(variance, images[push.variances][irradianceTile + 1 + texel].r);
            noises[t] = variance;
            float difference = abs(mean - before);
            if (difference > push.sensitivity * sqrt(variance) + 0.05 * max(mean, before) + 1.0e-4
                || abs(update.a - history.a) > 0.25)
            {
                InterlockedOr(changed, 1u);
            }
        }
        newIrradiance[t] = update;
    }
    GroupMemoryBarrierWithGroupSync();
    // A drift: the updates differing from the history the same way, frame after frame, by less than one update's
    // noise shows (a small change nothing announced)
    if (t == 0 && blend && frames > 0 && (push.flags & PROBES_ADAPTIVE) != 0)
    {
        float2 sum = 0;
        float variances = 0;
        for (uint k = 0; k < 36; k++)
        {
            sum += relative[k];
            variances += noises[k];
        }
        // The relative difference of the texels' sums (unbiased where the updates' noise is), averaged over a few
        // updates with its square: a drift beyond the average's own noise (sensitivity deviations, at least the floor)
        float d = (sum.x - sum.y) / max(sum.y, 1.0e-4);
        float drift = lerp(states[index].drift, d, 0.25);
        float square = lerp(asfloat(states[index].pad0), d * d, 0.25);
        // Its noise: what the last few updates showed, and at least what the texels' noise gives one (as if they
        // erred apart), the history's included: a rare bright ray, which a few updates may not have shown
        float least = variances / max(sum.y * sum.y, 1.0e-8);
        float noise = sqrt(max(square - drift * drift, least) * 0.25 / 1.75);
        if (frames >= 8 && abs(drift) > push.sensitivity * noise + push.drift)
        {
            changed = 1;
            drift = 0;
            square = 0;
        }
        states[index].drift = drift;
        states[index].pad0 = asuint(square);
    }
    GroupMemoryBarrierWithGroupSync();
    if (changed != 0)
    {
        frames = 0;
    }
    uint taken = min(frames + 1, max(push.maxFrames, 1u));
    float w = 1.0 / float(taken);
    // Distances per depth texel: the mean and mean square of the rays near its direction (a power of the cosine)
    if (blend && t < 196)
    {
        uint2 texel = uint2(t % 14, t / 14);
        float3 n = texelDirection(texel, 14);
        float2 sum = 0;
        float weights = 0;
        for (uint k = push.fixedRays; k < traced; k++)
        {
            float d = rayLight[k].a;
            float distance = min(d < 0 ? -d : d, maxDistance);
            float c = max(dot(n, rayWay[k]), 0.0);
            float c2 = c * c;
            float c4 = c2 * c2;
            float c16 = c4 * c4 * c4 * c4;
            float weight = c16 * c16 * c16 * c2; // cos^50
            sum += weight * float2(distance, distance * distance);
            weights += weight;
        }
        float2 update = weights > 0 ? sum / weights : float2(maxDistance, maxDistance * maxDistance);
        float2 history = images[push.depth][depthTile + 1 + texel].rg;
        newDepth[t] = frames == 0 ? update : lerp(history, update, w);
    }
    if (blend && t < 36)
    {
        uint2 texel = uint2(t % 6, t / 6);
        float4 history = images[push.irradiance][irradianceTile + 1 + texel];
        newIrradiance[t] = frames == 0 ? newIrradiance[t] : lerp(history, newIrradiance[t], w);
        // The updates' mean variance, weighed as the history is
        float before = images[push.variances][irradianceTile + 1 + texel].r;
        images[push.variances][irradianceTile + 1 + texel] = float4(frames == 0 ? updated[t] : lerp(before, updated[t],
            w), 0, 0, 0);
    }
    GroupMemoryBarrierWithGroupSync();
    if (blend)
    {
        // The tiles, borders copied across the octahedron's seams
        if (t < 64)
        {
            uint2 texel = uint2(t % 8, t / 8);
            uint2 source = borderSource(texel, 6) - 1;
            images[push.irradiance][irradianceTile + texel] = newIrradiance[source.y * 6 + source.x];
        }
        {
            uint2 texel = uint2(t % 16, t / 16);
            uint2 source = borderSource(texel, 14) - 1;
            images[push.depth][depthTile + texel] = float4(newDepth[source.y * 14 + source.x], 0, 0);
        }
    }
    if (t == 0)
    {
        uint kept = flags & ~(PROBE_UPDATE | PROBE_ACTIVE | PROBE_VALID);
        bool nowActive = stillActive != 0;
        // A probe turned off keeps what it held; one turned on starts anew next frame
        uint valid = blend || (flags & PROBE_VALID) != 0 ? PROBE_VALID : 0;
        if (nowActive && !active)
        {
            valid = 0;
        }
        states[index].flags = kept | (nowActive ? PROBE_ACTIVE : 0) | valid
            | (blend && frames == 0 ? PROBE_RESTARTED : 0);
        states[index].frames = blend ? taken : frames;
    }
}
