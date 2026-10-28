// The fallback path's meshlet pass: one workgroup per chunk, a thread per meshlet; visible meshlets become indexed
// indirect draws for vkCmdDrawIndexedIndirectCount.
import meshlets.common;

struct CullPush
{
    View* view;
    MeshEntry* meshes;
    Instance* instances;
    Chunk* chunks;
    uint* meshlets;      // 8 words per meshlet: scene's 32-byte records
    float* clusterLod;   // 10 floats per meshlet
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
    uint slot;
    InterlockedAdd(push.drawCount[0], 1, slot);
    if (slot < view.maxDraws)
    {
        uint d = slot * 5;
        push.draws[d] = ((push.meshlets[m * 8 + 2] >> 8) & 0xff) * 3;   // indexCount
        push.draws[d + 1] = 1;                                         // instanceCount
        push.draws[d + 2] = mesh.indexBase + push.meshlets[m * 8 + 1]; // firstIndex
        push.draws[d + 3] = mesh.vertexBase;                           // vertexOffset
        push.draws[d + 4] = chunk.instance;                            // firstInstance
        push.drawMeshlets[slot] = m;
    }
}
