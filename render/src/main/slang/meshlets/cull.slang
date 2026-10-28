// Culls meshlets on the GPU and writes an indexed indirect draw for each visible one: one workgroup per instance,
// its threads over the instance's meshlets. A meshlet is drawn when its detail level is the right one for its distance
// (scene's cluster LOD rule), it is in the frustum and it faces the camera (normal cone).
import meshlets.common;

struct CullPush
{
    View* view;
    MeshEntry* meshes;
    Instance* instances;
    uint* meshlets;      // 8 words per meshlet: scene's 32-byte records
    float* clusterLod;   // 10 floats per meshlet: own group's sphere and error, parent's
    uint* draws;         // 5 words per draw: VkDrawIndexedIndirectCommand
    uint* drawMeshlets;  // the meshlet of each draw
    uint* drawCount;
};

[[vk::push_constant]]
ConstantBuffer<CullPush> push;

[shader("compute")]
[numthreads(64, 1, 1)]
void cullMain(uint3 group : SV_GroupID, uint3 thread : SV_GroupThreadID)
{
    View view = push.view[0];
    uint instanceIndex = group.x;
    if (instanceIndex >= view.instanceCount)
    {
        return;
    }
    Instance instance = push.instances[instanceIndex];
    MeshEntry mesh = push.meshes[instance.mesh];
    float scale = maxScale(instance);
    if (!inFrustum(view, toWorld(instance, mesh.sphere.xyz), mesh.sphere.w * scale))
    {
        return; // the whole instance is outside: every thread agrees
    }
    for (uint local = thread.x; local < mesh.meshletCount; local += 64)
    {
        uint m = mesh.meshletBase + local;
        // Detail: this meshlet's group is fine enough, its parent's is not.
        uint l = m * 10;
        float3 own = toWorld(instance, float3(push.clusterLod[l], push.clusterLod[l + 1], push.clusterLod[l + 2]));
        float3 parent = toWorld(instance,
            float3(push.clusterLod[l + 5], push.clusterLod[l + 6], push.clusterLod[l + 7]));
        float ownError = projectedError(view, own, push.clusterLod[l + 3] * scale, push.clusterLod[l + 4] * scale);
        float parentError = projectedError(view, parent, push.clusterLod[l + 8] * scale,
            push.clusterLod[l + 9] * scale);
        if (!(ownError <= view.errorPixels && parentError > view.errorPixels))
        {
            continue;
        }
        // Frustum, with the meshlet's own sphere.
        uint w = m * 8;
        float3 center = toWorld(instance, asfloat(uint3(push.meshlets[w + 3], push.meshlets[w + 4],
            push.meshlets[w + 5])));
        float radius = asfloat(push.meshlets[w + 6]) * scale;
        if (!inFrustum(view, center, radius))
        {
            continue;
        }
        // Backfaces: the whole meshlet faces away when the camera lies inside its normal cone's back side.
        float4 cone = decodeSnorm8(push.meshlets[w + 7]);
        if (cone.w < 1)
        {
            float3 axis = rotate(instance.rotation, cone.xyz);
            if (dot(center, axis) >= cone.w * length(center) + radius)
            {
                continue;
            }
        }
        uint slot;
        InterlockedAdd(push.drawCount[0], 1, slot);
        if (slot < view.maxDraws)
        {
            uint counts = push.meshlets[w + 2];
            uint d = slot * 5;
            push.draws[d] = ((counts >> 8) & 0xff) * 3;            // indexCount
            push.draws[d + 1] = 1;                                 // instanceCount
            push.draws[d + 2] = mesh.indexBase + push.meshlets[w + 1]; // firstIndex
            push.draws[d + 3] = mesh.vertexBase;                   // vertexOffset
            push.draws[d + 4] = instanceIndex;                     // firstInstance
            push.drawMeshlets[slot] = m;
        }
    }
}
