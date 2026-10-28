// The indexed path's expansion: one workgroup per visible meshlet writes its triangles into the frame's index buffer,
// at the range the cull pass reserved. Each index is the meshlet's visible slot and its local vertex
// (slot << 8 | local), so the vertex shader finds instance, meshlet and vertex from the index alone.
import meshlets.common;

struct ExpandPush
{
    View* view;
    VisibleMeshlet* visible;
    uint* visibleCount;
    uint* meshlets;
    MeshEntry* meshes;
    Instance* instances;
    uint* meshletTriangles;  // 3 bytes per triangle, as words
    uint* indices;
};

[[vk::push_constant]]
ConstantBuffer<ExpandPush> push;

/** Byte i of the pool's meshlet triangles. */
uint triangleByte(uint i)
{
    return (push.meshletTriangles[i >> 2] >> ((i & 3) * 8)) & 0xff;
}

[shader("compute")]
[numthreads(64, 1, 1)]
void expandMain(uint3 group : SV_GroupID, uint thread : SV_GroupIndex)
{
    uint slot = group.y * 65535 + group.x;
    if (slot >= min(push.visibleCount[0], push.view[0].maxDraws))
    {
        return;
    }
    VisibleMeshlet v = push.visible[slot];
    if (v.instance == 0xffffffff)
    {
        return; // over the index capacity: not written
    }
    MeshEntry mesh = push.meshes[push.instances[v.instance].mesh];
    uint w = v.meshlet * 8;
    uint count = ((push.meshlets[w + 2] >> 8) & 0xff) * 3;
    uint first = mesh.indexBase + push.meshlets[w + 1];
    for (uint i = thread; i < count; i += 64)
    {
        push.indices[v.firstIndex + i] = (slot << 8) | triangleByte(first + i);
    }
}
