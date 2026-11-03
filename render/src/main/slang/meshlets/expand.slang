// The indexed path's expansion, per raster bin: one workgroup per visible meshlet of the bin writes its triangles
// into the frame's index buffer, at the range the cull pass reserved in the bin's part. Each index is the meshlet's visible slot and its local vertex
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
    uint bin;                // the raster bin expanded
    uint late;               // 1: the late phase's meshlets
    uint* typed;             // expandTypedMain: the view's lists split by shading bin (split.slang)
    uint categories;         // expandTypedMain: the shading bins they have room for
};

[[vk::push_constant]]
ConstantBuffer<ExpandPush> push;

/** Byte i of the pool's meshlet triangles. */
uint triangleByte(uint i)
{
    return (push.meshletTriangles[i >> 2] >> ((i & 3) * 8)) & 0xff;
}

/** Writes the triangles of visible meshlet `slot` of `view` at the range the cull pass (or the split) gave it. */
void expandMeshlet(View view, uint slot, uint thread)
{
    VisibleMeshlet v = push.visible[slot];
    if (v.instance == 0xffffffff)
    {
        return; // over the index capacity: not written
    }
    Instance instance = push.instances[v.instance];
    MeshEntry mesh = push.meshes[instance.mesh & INSTANCE_MESH];
    bool flip = mirrored(instance);
    uint w = v.meshlet * 8;
    uint count = ((push.meshlets[w + 2] >> 8) & 0xff) * 3;
    uint first = mesh.indexBase + push.meshlets[w + 1];
    for (uint i = thread; i < count; i += 64)
    {
        uint corner = i - i % 3 + woundCorner(i % 3, flip); // a mirrored instance's triangles wound the other way
        push.indices[binFirstIndex(view, push.bin) + v.firstIndex + i] = (slot << 8) | triangleByte(first + corner);
    }
}

[shader("compute")]
[numthreads(64, 1, 1)]
void expandMain(uint3 group : SV_GroupID, uint thread : SV_GroupIndex)
{
    View view = push.view[0];
    uint index = group.y * 65535 + group.x;
    if (index >= min(push.visibleCount[visibleCountWord(push.bin, push.late)], binDrawCapacity(view, push.bin)))
    {
        return;
    }
    expandMeshlet(view, binFirstSlot(view, push.bin) + index, thread);
}

/**
 * Like expandMain, for a view whose lists split.slang split by shading bin: in the split list's order, so each shading
 * bin's indices, and the meshlets' within it, are written one after another as the split laid them out.
 */
[shader("compute")]
[numthreads(64, 1, 1)]
void expandTypedMain(uint3 group : SV_GroupID, uint thread : SV_GroupIndex)
{
    View view = push.view[0];
    uint index = group.y * 65535 + group.x;
    uint last = splitEntry(push.bin, push.categories - 1, push.categories);
    if (index >= push.typed[last + SPLIT_BASE] + push.typed[last + SPLIT_COUNT]) // the bin's meshlets split
    {
        return;
    }
    expandMeshlet(view, push.typed[splitList(push.categories) + binFirstSlot(view, push.bin) + index], thread);
}
