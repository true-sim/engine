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
    uint* visibility;      // per instance: 1 if it was visible the last time the late phase looked
    Occlusion occlusion;
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
    float3 center = toWorld(instance, mesh.sphere.xyz);
    float radius = mesh.sphere.w * maxScale(instance);
    uint phase = push.occlusion.phase;
    bool inView = inFrustum(view, center, radius);
    if (phase == PHASE_EARLY && (!inView || push.visibility[id.x] == 0))
    {
        return; // the early phase draws what was visible last frame
    }
    if (phase == PHASE_LATE)
    {
        // Every instance in view is tested against the pyramid of what the early phase drew; the late phase draws
        // those now visible that the early phase did not.
        bool drawnEarly = push.visibility[id.x] != 0;
        bool visible = inView && unoccluded(view, push.occlusion, center, radius);
        push.visibility[id.x] = visible ? 1 : 0;
        if (!visible || drawnEarly)
        {
            return;
        }
    }
    else if (!inView)
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
