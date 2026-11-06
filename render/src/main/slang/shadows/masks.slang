// The Shadows owner's traced masks of one view (engine-render's TracedMasks): traceMain traces every traced light's
// rays from each pixel of the view's depth (a thread per pixel and light), writing its mask: hard shadows one ray to
// the light, soft ones several to points of its disc (directional: a cone of its angular radius) or sphere, their
// mean the visibility the denoiser then filters. A ray whose nearest triangle is of a type with a geometry hook waits
// in a queue for that type's keep() (rays.slang); continueMain picks the queued rays up after the test: one that met
// a hole goes on beyond it, one that met a kept point is blocked, and the pixel's remaining rays follow.
__exported import shadows.rays;

[[vk::push_constant]]
ConstantBuffer<ShadowMasksPush> push;

uint2 regionSize(ShadowTrace* t)
{
    return uint2(t.size & 0xffff, t.size >> 16);
}

float3 positionAt(ShadowTrace* t, int2 pixel, float depth)
{
    float2 ndc = (float2(pixel) + 0.5) / float2(regionSize(t)) * 2 - 1;
    float4 clip = mul(t.inverseViewProjection, float4(ndc, depth, 1));
    return clip.xyz / clip.w;
}

// The neighbour along step (one way or the other) nearest in depth: across an edge, the other side.
float3 neighbour(ShadowTrace* t, int2 pixel, int2 step, float depth, float3 position)
{
    int2 last = int2(regionSize(t)) - 1;
    int2 a = clamp(pixel + step, 0, last);
    int2 b = clamp(pixel - step, 0, last);
    float da = textures[t.depth].Load(int3(a, 0)).r;
    float db = textures[t.depth].Load(int3(b, 0)).r;
    bool first = abs(da - depth) <= abs(db - depth);
    float3 other = first ? positionAt(t, a, da) : positionAt(t, b, db);
    return first ? other - position : position - other;
}

/** The surface at a pixel: camera-relative position and normal (towards the camera), rebuilt from the depth. */
struct Point
{
    float3 position;
    float3 normal;
    bool sky;
};

Point pointAt(ShadowTrace* t, int2 pixel)
{
    Point p;
    float depth = textures[t.depth].Load(int3(pixel, 0)).r;
    p.sky = !(depth > 0);
    p.position = 0;
    p.normal = float3(0, 0, 1);
    if (!p.sky)
    {
        p.position = positionAt(t, pixel, depth);
        float3 n = normalize(cross(neighbour(t, pixel, int2(1, 0), depth, p.position),
            neighbour(t, pixel, int2(0, 1), depth, p.position)));
        p.normal = dot(n, p.position) > 0 ? -n : n;
    }
    return p;
}

uint hash(uint x)
{
    x ^= x >> 16;
    x *= 0x7feb352du;
    x ^= x >> 15;
    x *= 0x846ca68bu;
    x ^= x >> 16;
    return x;
}

/** Two numbers in [0, 1) for ray `ray` of slot `slot` at `pixel` this frame: R2 over the rays, turned per pixel. */
float2 sample2(uint2 pixel, uint slot, uint ray, uint frame)
{
    uint h = hash(pixel.x * 1973u + pixel.y * 9277u + slot * 26699u + frame * 0x9e3779b9u);
    float2 shift = float2(h & 0xffff, h >> 16) / 65536.0;
    float n = float(ray + 1);
    return frac(shift + n * float2(0.7548776662, 0.5698402910));
}

/** Two unit vectors perpendicular to unit `n` and each other. */
void basis(float3 n, out float3 a, out float3 b)
{
    a = normalize(abs(n.y) < 0.99 ? cross(n, float3(0, 1, 0)) : cross(n, float3(1, 0, 0)));
    b = cross(n, a);
}

/** Ray `ray` of slot `s` from `p`: its direction (unit) and length. */
float3 rayTowards(ShadowTrace* t, TraceSlot s, Light light, uint slot, uint ray, uint2 pixel, Point p,
    out float tMax)
{
    bool soft = (s.flags & TRACE_SOFT) != 0 && s.softness > 0;
    float2 u = sample2(pixel, slot, ray, t.frame);
    if (light.type == DIRECTIONAL)
    {
        float3 toLight = -light.direction;
        tMax = s.distance > 0 ? s.distance : 1.0e30;
        if (!soft)
        {
            return toLight;
        }
        // Uniform over the cap of the sun's disc
        float cosMax = cos(s.softness);
        float cosTheta = 1 - u.x * (1 - cosMax);
        float sinTheta = sqrt(max(0, 1 - cosTheta * cosTheta));
        float phi = 6.2831853 * u.y;
        float3 a;
        float3 b;
        basis(toLight, a, b);
        return normalize(toLight * cosTheta + (a * cos(phi) + b * sin(phi)) * sinTheta);
    }
    float3 target = light.position;
    if (soft)
    {
        // A point of the sphere's disc facing the surface, uniform over its area
        float3 n = normalize(light.position - p.position);
        float3 a;
        float3 b;
        basis(n, a, b);
        float r = s.softness * sqrt(u.x);
        float phi = 6.2831853 * u.y;
        target += (a * cos(phi) + b * sin(phi)) * r;
    }
    float3 d = target - p.position;
    float distance = length(d);
    tMax = distance * (1 - 1.0e-4);
    return d / max(distance, 1.0e-6);
}

void writeMask(ShadowTrace* t, TraceSlot s, uint2 pixel, float visibility)
{
    if ((s.flags & TRACE_SOFT) != 0)
    {
        images[s.output][pixel] = float4(0, 0, 0, visibility);
        images[s.outputB][pixel] = float4(0);
    }
    else
    {
        images[s.output][pixel] = float4(visibility, 0, 0, 0);
    }
}

/**
 * Traces slot `slot`'s rays at `pixel` from ray `first` on (that one from tMin, past a hole), `lit` of the rays before
 * found clear; writes the mask once all are traced, or queues the ray meeting a hooked type and stops.
 */
void trace(ShadowTrace* t, uint slot, uint2 pixel, Point p, uint first, uint lit, float tMin)
{
    TraceSlot s = ((TraceSlot*)t.traceSlots)[slot];
    Light light = ((Light*)t.lights)[s.light];
    light.position = lessCamera(light.position, light.positionLow, t.cameraHigh, t.cameraLow);
    if (light.type != DIRECTIONAL && first == 0)
    {
        float3 apart = light.position - p.position;
        if (dot(apart, apart) > light.range * light.range)
        {
            writeMask(t, s, pixel, 1); // beyond its reach: it lights nothing here, no ray needed
            return;
        }
    }
    float offset = t.offset * length(p.position) + 1.0e-3;
    RayScene* scene = (RayScene*)t.scene;
    for (uint ray = first; ray < s.rays; ray++)
    {
        float tMax;
        float3 direction = rayTowards(t, s, light, slot, ray, pixel, p, tMax);
        if (dot(p.normal, direction) <= 0)
        {
            continue; // turned away: its own shadow
        }
        float3 from = p.position + p.normal * offset + t.toTopLevel;
        ShadowRay hit;
        uint way = traceShadow(scene, from, direction, ray == first ? tMin : 0, tMax, hit);
        if (way == CLEAR)
        {
            lit++;
        }
        else if (way == PENDING && push.mode == 0)
        {
            hit.pixel = pixel.x | pixel.y << 16;
            hit.state = slot | ray << 8 | lit << 16;
            if (enqueue(push.queueOut, t.capacity, hit))
            {
                return; // goes on once the type's keep() said
            }
        }
    }
    writeMask(t, s, pixel, float(lit) / float(max(s.rays, 1u)));
}

[shader("compute")]
[numthreads(8, 8, 1)]
void traceMain(uint3 id : SV_DispatchThreadID)
{
    ShadowTrace* t = push.trace;
    uint2 size = regionSize(t);
    if (id.x >= size.x || id.y >= size.y || id.z >= t.slots)
    {
        return;
    }
    Point p = pointAt(t, int2(id.xy));
    if (id.z == 0 && t.normal != 0)
    {
        float3x3 axes = viewAxes(t.viewProjection);
        uintImages[t.normal][id.xy] = uint2(p.sky ? 0 : packNormal(toView(axes, p.normal)), 0);
    }
    if (p.sky)
    {
        writeMask(t, ((TraceSlot*)t.traceSlots)[id.z], id.xy, 1);
        return;
    }
    trace(t, id.z, id.xy, p, 0, 0, 0);
}

[shader("compute")]
[numthreads(64, 1, 1)]
void continueMain(uint3 id : SV_DispatchThreadID)
{
    ShadowTrace* t = push.trace;
    uint* queue = push.queueIn;
    if (id.x >= min(queue[0], t.capacity))
    {
        return;
    }
    ShadowRay ray = ((ShadowRay*)(queue + QUEUE_HEADER))[id.x];
    uint2 pixel = uint2(ray.pixel & 0xffff, ray.pixel >> 16);
    uint slot = ray.state & 0xff;
    uint index = (ray.state >> 8) & 0xff;
    uint lit = ray.state >> 16;
    Point p = pointAt(t, int2(pixel));
    if ((ray.kept & 0xff) == HOLE)
    {
        trace(t, slot, pixel, p, index, lit, ray.t * (1 + 1.0e-4) + 1.0e-4); // on beyond the hole
    }
    else
    {
        trace(t, slot, pixel, p, index + 1, lit, 0); // blocked: the next rays
    }
}

// ---------------------------------------------------------------- the path tracer's light rays (LightRays)

/**
 * Light rays of the path tracer's next-event estimation that waited for a keep() test (the push's trace is their
 * record, queueIn the rays tested): through a hole, traced on beyond it, their light added to their pixel's sum where
 * nothing else blocks it; at another hooked type's triangle, queued again (queueOut) unless this is the last round
 * (mode 1: blocked). A kept point blocks.
 */
[shader("compute")]
[numthreads(64, 1, 1)]
void lightRaysMain(uint3 id : SV_DispatchThreadID)
{
    ShadowTrace* t = push.trace;
    uint* queue = push.queueIn;
    if (id.x >= min(queue[0], t.capacity))
    {
        return;
    }
    LightRay ray = ((LightRay*)(queue + QUEUE_HEADER))[id.x];
    if ((ray.hit.kept & 0xff) != HOLE)
    {
        return; // a kept point: blocked
    }
    ShadowRay hit;
    uint way = traceShadow((RayScene*)t.scene, ray.origin, ray.direction, ray.hit.t * (1 + 1.0e-4) + 1.0e-4,
        ray.tMax, hit);
    if (way == CLEAR)
    {
        uint* sums = (uint*)t + t.sums;
        uint index = 3 * ((ray.pixel >> 16) * (t.size & 0xffff) + (ray.pixel & 0xffff));
        addFloat(sums + index, ray.contribution.x);
        addFloat(sums + index + 1, ray.contribution.y);
        addFloat(sums + index + 2, ray.contribution.z);
    }
    else if (way == PENDING && push.mode == 0)
    {
        ray.hit = hit;
        enqueueLight(push.queueOut, t.capacity, ray);
    }
}

/** Adds each pixel's light found by light rays (their sums) to its radiance in storage image `mode`. */
[shader("compute")]
[numthreads(8, 8, 1)]
void lightMergeMain(uint3 id : SV_DispatchThreadID)
{
    ShadowTrace* t = push.trace;
    uint2 size = regionSize(t);
    if (id.x >= size.x || id.y >= size.y)
    {
        return;
    }
    uint* sums = (uint*)t + t.sums;
    uint index = 3 * (id.y * size.x + id.x);
    float3 light = float3(asfloat(sums[index]), asfloat(sums[index + 1]), asfloat(sums[index + 2]));
    if (any(light != 0))
    {
        float4 was = images[push.mode][id.xy];
        images[push.mode][id.xy] = float4(was.rgb + light, was.a);
    }
}
