// The classic LOD path's selection, one view: one thread per instance tests the mesh's bounds against the frustum,
// picks the coarsest level whose error, seen from the mesh's nearest point, is within the allowed pixels (the meshlet
// path's rule for discrete LODs), and appends a VkDrawIndexedIndirectCommand per part of it to the list of the part's
// raster bin (by its material: opaque, double-sided, masked). Words 0 to 2 of the draw list count each bin's; draws
// past a bin's capacity are not written, and its draw's count is capped.
import classic.parts;

struct SelectPush
{
    View* view;
    MeshEntry* meshes;
    Instance* instances;
    ClassicMesh* classicMeshes;
    ClassicLevel* levels;
    ClassicPart* parts;
    uint* draws;           // the bins' counts, then from word 4 each bin's commands (maxDraws of 5 words each)
    ClassicDraw* records;  // per draw slot: bin * maxDraws + the draw in the bin
    uint instanceCount;
    uint maxDraws;         // per bin
    Material* materials;   // for each part's raster bin
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
    Instance stored = push.instances[id.x];
    if (!drawnIn(stored, view))
    {
        return;
    }
    Instance instance = relative(stored, view);
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
    for (uint p = 0; p < level.partCount; p++)
    {
        ClassicPart part = push.parts[level.firstPart + p];
        uint bin = rasterBin(push.materials[instance.materialBase + ((part.tag >> 24) & 0x7f)]);
        uint local;
        InterlockedAdd(push.draws[bin], 1, local);
        if (local >= push.maxDraws)
        {
            continue; // over the bin's capacity: counted, not drawn
        }
        uint slot = bin * push.maxDraws + local;
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
