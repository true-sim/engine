// The classic LOD path's selection, one view: one thread per instance tests the mesh's bounds against the frustum,
// picks the coarsest level whose error, seen from the mesh's nearest point, is within the allowed pixels (the meshlet
// path's rule for discrete LODs), and appends a VkDrawIndexedIndirectCommand per part of it. Word 0 of the draw list
// counts them; draws past the capacity are not written, and the draw's count is capped.
import classic.parts;

struct SelectPush
{
    View* view;
    MeshEntry* meshes;
    Instance* instances;
    ClassicMesh* classicMeshes;
    ClassicLevel* levels;
    ClassicPart* parts;
    uint* draws;           // the count, then from word 4 the commands, 5 words each
    ClassicDraw* records;  // per draw slot
    uint instanceCount;
    uint maxDraws;
};

[[vk::push_constant]]
ConstantBuffer<SelectPush> push;

[shader("compute")]
[numthreads(64, 1, 1)]
void selectMain(uint3 id : SV_DispatchThreadID)
{
    if (id.x >= push.instanceCount)
    {
        return;
    }
    View view = push.view[0];
    Instance instance = relative(push.instances[id.x], view);
    ClassicMesh classic = push.classicMeshes[instance.mesh];
    if (classic.levelCount == 0)
    {
        return;
    }
    MeshEntry mesh = push.meshes[instance.mesh];
    float scale = maxScale(instance);
    float3 center = toWorld(instance, mesh.sphere.xyz);
    float radius = mesh.sphere.w * scale;
    if (!inFrustum(view, center, radius))
    {
        return;
    }
    ClassicLevel level = push.levels[classic.levelBase + classic.levelCount - 1]; // the finest: error 0
    for (uint l = 0; l + 1 < classic.levelCount; l++)
    {
        ClassicLevel coarser = push.levels[classic.levelBase + l];
        if (projectedError(view, center, radius, coarser.error * scale) <= view.errorPixels)
        {
            level = coarser;
            break;
        }
    }
    uint first;
    InterlockedAdd(push.draws[0], level.partCount, first);
    for (uint p = 0; p < level.partCount && first + p < push.maxDraws; p++)
    {
        ClassicPart part = push.parts[level.firstPart + p];
        uint slot = first + p;
        uint c = 4 + slot * 5;
        push.draws[c] = part.indexCount;
        push.draws[c + 1] = 1;
        push.draws[c + 2] = part.firstIndex;
        push.draws[c + 3] = mesh.vertexBase; // the vertex offset: indices count from the mesh's first vertex
        push.draws[c + 4] = slot;            // the first instance: the vertex stage's way to the draw's record
        ClassicDraw draw = { id.x, part.meshlet, part.tag, 0 };
        push.records[slot] = draw;
    }
}
