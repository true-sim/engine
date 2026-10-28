// Light clustering: the view's frustum cut into tiles of pixels across and slices of depth (exponential: deeper ones
// longer), and each cluster given the lights whose sphere reaches its box. One thread per cluster; the lights are
// read in batches of 64 into shared memory, which every thread of the group tests. Directional lights reach every
// cluster. The header was written before; this fills the counts and the light slots.
import meshlets.common;
import lights;

struct ClusterPush
{
    View* view;                // the camera
    ClusterHeader* header;
    uint width;                // the image's pixels
    uint height;
    float far;                 // where the last slice ends
    uint lightCount;
};

[[vk::push_constant]]
ConstantBuffer<ClusterPush> push;

groupshared float4 spheres[64]; // view-space center and radius; radius < 0: everywhere

/** Row `r` of the view-projection matrix, without its last column (the camera sits at the origin). */
float3 row(float4x4 m, uint r)
{
    return float3(mul(m, float4(1, 0, 0, 0))[r], mul(m, float4(0, 1, 0, 0))[r], mul(m, float4(0, 0, 1, 0))[r]);
}

[shader("compute")]
[numthreads(64, 1, 1)]
void clusterMain(uint3 id : SV_DispatchThreadID, uint local : SV_GroupIndex)
{
    ClusterHeader h = push.header[0];
    uint clusters = h.dims.x * h.dims.y * h.dims.z;
    uint c = id.x;
    float4x4 vp = push.view[0].viewProjection;
    // The camera's axes: right, down and forward, and the projection's scales across and down.
    float3 across = row(vp, 0);
    float3 down = row(vp, 1);
    float3 forward = row(vp, 3);
    float sx = length(across);
    float sy = length(down);
    across /= sx;
    down /= sy;
    // The cluster's box in view space (x right, y down, z forward).
    uint3 cell = uint3(c % h.dims.x, (c / h.dims.x) % h.dims.y, c / (h.dims.x * h.dims.y));
    float2 from = float2(cell.xy * h.tileSize) / float2(push.width, push.height) * 2 - 1;
    float2 to = float2(min((cell.xy + 1) * h.tileSize, uint2(push.width, push.height)))
        / float2(push.width, push.height) * 2 - 1;
    float near = cell.z == 0 ? 0 : h.near * exp(float(cell.z) / h.sliceScale);
    float far = cell.z + 1 == h.dims.z ? push.far : h.near * exp(float(cell.z + 1) / h.sliceScale);
    float3 lo = float3(min(min(from.x * near, from.x * far), min(to.x * near, to.x * far)) / sx,
        min(min(from.y * near, from.y * far), min(to.y * near, to.y * far)) / sy, near);
    float3 hi = float3(max(max(from.x * near, from.x * far), max(to.x * near, to.x * far)) / sx,
        max(max(from.y * near, from.y * far), max(to.y * near, to.y * far)) / sy, far);
    uint count = 0;
    for (uint first = 0; first < push.lightCount; first += 64)
    {
        GroupMemoryBarrierWithGroupSync();
        if (first + local < push.lightCount)
        {
            Light light = h.lights[first + local];
            float3 p = light.position;
            spheres[local] = float4(dot(across, p), dot(down, p), dot(forward, p),
                light.type == DIRECTIONAL ? -1.0 : light.range);
        }
        GroupMemoryBarrierWithGroupSync();
        if (c >= clusters)
        {
            continue;
        }
        uint batch = min(64, push.lightCount - first);
        for (uint i = 0; i < batch; i++)
        {
            float4 sphere = spheres[i];
            float3 nearest = clamp(sphere.xyz, lo, hi);
            float3 d = sphere.xyz - nearest;
            if (sphere.w < 0 || dot(d, d) <= sphere.w * sphere.w)
            {
                if (count < h.maxPerCluster)
                {
                    h.indices[c * h.maxPerCluster + count] = first + i;
                }
                count++;
            }
        }
    }
    if (c < clusters)
    {
        h.counts[c] = min(count, h.maxPerCluster);
        InterlockedMax(push.header[0].overflow, count);
    }
}
