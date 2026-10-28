// The meshlet pass of both paths: one workgroup per chunk, a thread per meshlet. Each visible meshlet is appended to
// the frame's visible list; the mesh shader path launches a mesh workgroup per entry, the indexed path reserves an index
// range for its triangles that the expansion pass writes, and the whole frame draws with one indexed draw. Commands
// are built in place by atomics: the expansion (or mesh) dispatch's X and Y (rows of 65,535 meshlets) and the draw's
// index count.
import meshlets.common;

struct CullPush
{
    View* view;
    MeshEntry* meshes;
    Instance* instances;
    Chunk* chunks;
    uint* meshlets;        // 8 words per meshlet: scene's 32-byte records
    float* clusterLod;     // 10 floats per meshlet
    VisibleMeshlet* visible;
    uint* expandCommand;   // VkDispatchIndirectCommand
    uint* drawCommand;     // VkDrawIndexedIndirectCommand: its index count grows here
    uint* visibleCount;
    uint maxIndices;
    Occlusion occlusion;
};

[[vk::push_constant]]
ConstantBuffer<CullPush> push;

[shader("compute")]
[numthreads(64, 1, 1)]
void cullMain(uint3 group : SV_GroupID, uint3 thread : SV_GroupThreadID)
{
    View view = push.view[0];
    Chunk chunk = push.chunks[group.x];
    Instance instance = push.instances[chunk.instance];
    MeshEntry mesh = push.meshes[instance.mesh];
    uint local = chunk.firstMeshlet + thread.x;
    if (local >= mesh.meshletCount)
    {
        return;
    }
    uint m = mesh.meshletBase + local;
    if (!meshletVisible(view, instance, m, push.meshlets, push.clusterLod))
    {
        return;
    }
    if (push.occlusion.phase == PHASE_LATE)
    {
        // A newly visible instance: only its meshlets the pyramid does not hide.
        uint w = m * 8;
        float3 center = toWorld(instance, asfloat(uint3(push.meshlets[w + 3], push.meshlets[w + 4],
            push.meshlets[w + 5])));
        if (!unoccluded(view, push.occlusion, center, asfloat(push.meshlets[w + 6]) * maxScale(instance)))
        {
            return;
        }
    }
    uint slot;
    InterlockedAdd(push.visibleCount[0], 1, slot);
    uint indices = ((push.meshlets[m * 8 + 2] >> 8) & 0xff) * 3;
    uint first;
    InterlockedAdd(push.drawCommand[5], indices, first); // word 5: indices reserved so far
    if (slot >= view.maxDraws)
    {
        return; // over the frame's capacity: dropped, and counted
    }
    bool fits = first + indices <= push.maxIndices; // meshlets past the index capacity come last: no gaps
    push.visible[slot] = VisibleMeshlet(fits ? chunk.instance : 0xffffffff, m, first, 0);
    if (fits)
    {
        InterlockedMax(push.drawCommand[0], first + indices);
    }
    InterlockedMax(push.expandCommand[0], min(slot + 1, 65535));
    InterlockedMax(push.expandCommand[1], slot / 65535 + 1);
}
