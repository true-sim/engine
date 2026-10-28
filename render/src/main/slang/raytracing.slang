// The bindless set's acceleration structures (binding 3, on devices with ray tracing): top levels to trace rays
// into with ray queries, by slot.
module raytracing;

import lights;

[[vk::binding(3, 0)]]
public RaytracingAccelerationStructure scenes[];

/** Whether anything opaque lies on the ray from origin along direction, between tMin and tMax. */
[require(spvRayQueryKHR)]
public bool occluded(uint scene, float3 origin, float3 direction, float tMin, float tMax)
{
    RayDesc ray;
    ray.Origin = origin;
    ray.Direction = direction;
    ray.TMin = tMin;
    ray.TMax = tMax;
    RayQuery<RAY_FLAG_ACCEPT_FIRST_HIT_AND_END_SEARCH | RAY_FLAG_SKIP_PROCEDURAL_PRIMITIVES> query;
    query.TraceRayInline(scenes[scene], RAY_FLAG_NONE, 0xff, ray);
    query.Proceed();
    return query.CommittedStatus() == COMMITTED_TRIANGLE_HIT;
}

/** Shadow rays by ray queries into the frame's scenes: for lights.slang's inline ray shadows. */
public struct QueryRays : IShadowRays
{
    [require(spvRayQueryKHR)]
    public float clear(uint scene, float3 origin, float3 direction, float tMax)
    {
        return occluded(scene, origin, direction, 0, tMax) ? 0 : 1;
    }
}
