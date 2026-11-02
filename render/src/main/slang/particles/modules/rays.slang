// Ray collision (E10, E24): the particle's move traced into the frame's top level, where the device traces rays; only
// in the kernel's rays variants (its capability, RAY_QUERY).
module rays;

import particles.particle;
import raytracing;
import particles.modules.collide;

/** The distance along the ray to the nearest triangle of `scene`'s top level within `far`, or -1 (opaque, all). */
public float nearest(RayScene* scene, float3 origin, float3 direction, float far)
{
    RayDesc ray;
    ray.Origin = origin;
    ray.Direction = direction;
    ray.TMin = 0;
    ray.TMax = far;
    RayQuery<RAY_FLAG_FORCE_OPAQUE | RAY_FLAG_SKIP_PROCEDURAL_PRIMITIVES> query;
    query.TraceRayInline(scenes[scene.slot], RAY_FLAG_NONE, 0xff, ray);
    while (query.Proceed())
    {
    }
    return query.CommittedStatus() == COMMITTED_TRIANGLE_HIT ? query.CommittedRayT() : -1;
}

/**
 * Whether the move from `a` to `b` (the radius further) crosses a triangle of the frame's top level: the contact the
 * radius before it, the normal from two more rays beside the first (or against the move where they miss).
 */
public ParticleHit hitRays(ParticleFrame* frame, ParticleEmitter e, float r, float3 a, float3 b)
{
    uint64_t address = frame.rays;
    float3 d = b - a;
    float length = length(d);
    if (address == 0 || length < 1e-6)
    {
        return noHit();
    }
    RayScene* scene = (RayScene*) address;
    precise float3 high = e.originHigh - frame.raysOriginHigh;
    precise float3 low = e.originLow - frame.raysOriginLow;
    float3 from = (high + low) + a;
    d /= length;
    float t = nearest(scene, from, d, length + r);
    if (t < 0)
    {
        return noHit();
    }
    float3 side = normalize(cross(d, abs(d.y) < 0.9 ? float3(0, 1, 0) : float3(1, 0, 0)));
    float3 up = cross(side, d);
    float apart = max(0.01, r);
    float t1 = nearest(scene, from + side * apart, d, length + r + 4 * apart);
    float t2 = nearest(scene, from + up * apart, d, length + r + 4 * apart);
    float3 n = -d;
    if (t1 >= 0 && t2 >= 0)
    {
        float3 p = d * t;
        float3 c = cross(side * apart + d * t1 - p, up * apart + d * t2 - p);
        n = dot(c, c) > 1e-12 ? normalize(c) : -d;
        n = dot(n, d) > 0 ? -n : n;
    }
    return hitAt(a + d * max(0.0, t - r), n);
}

/** The ray tracing's top level of the frame. */
[ParticleModule("collide.rays")]
[ModuleCapability("RAY_QUERY")]
public struct RaysCollision
{
    public float fallback = 0;
    public float friction = 0;
    public float radius = 0;
    public float response = 0;
    public float restitution = 0.5;

    public ParticleHit collide(inout ParticleStep s, Particle p)
    {
        return respond(hitRays(s.frame, s.emitter, radiusOf(radius, p), s.from, s.to), response, friction, restitution);
    }
};
