// The instance pass of both geometry paths: one thread per instance tests its bounds against the frustum, and each
// visible instance appends a chunk per 64 of the meshlets its distance may need. Words 0-2 of the chunk command are the
// cull pass's indirect dispatch over the chunks; word 3 counts them all, and the command's X is capped at the capacity.
import meshlets.common;

struct InstancePush
{
    View* view;
    MeshEntry* meshes;
    Instance* instances;
    Chunk* chunks;
    uint* chunkCommand;
    Level* levels;
    uint maxChunks;
};

[[vk::push_constant]]
ConstantBuffer<InstancePush> push;

[shader("compute")]
[numthreads(64, 1, 1)]
void instancesMain(uint3 id : SV_DispatchThreadID)
{
    View view = push.view[0];
    if (id.x >= view.instanceCount)
    {
        return;
    }
    Instance instance = push.instances[id.x];
    MeshEntry mesh = push.meshes[instance.mesh];
    if (!inFrustum(view, toWorld(instance, mesh.sphere.xyz), mesh.sphere.w * maxScale(instance)))
    {
        return;
    }
    // Only the meshlets the LOD rule can pick at this distance: none past the first level whose error, seen from the
    // instance's nearest point, is fine enough (meshlets are stored coarsest first).
    uint end = mesh.meshletCount;
    float scale = maxScale(instance);
    float distance = length(toWorld(instance, mesh.lodSphere.xyz)) - mesh.lodSphere.w * scale;
    if (distance > 0)
    {
        for (uint l = 0; l < mesh.levelCount; l++)
        {
            Level level = push.levels[mesh.levelBase + l];
            if (level.error * scale * view.projectionScale / distance <= view.errorPixels)
            {
                end = level.end;
                break;
            }
        }
    }
    uint chunks = (end + 63) / 64;
    uint first;
    InterlockedAdd(push.chunkCommand[3], chunks, first);
    for (uint i = 0; i < chunks && first + i < push.maxChunks; i++)
    {
        push.chunks[first + i] = Chunk(id.x, i * 64);
    }
    InterlockedMax(push.chunkCommand[0], min(first + chunks, push.maxChunks));
}
