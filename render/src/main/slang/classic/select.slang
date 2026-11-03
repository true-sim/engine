// The classic LOD path's selection, one view: one thread per instance tests the mesh's bounds against the frustum,
// picks the coarsest level whose error, seen from the mesh's nearest point, is within the allowed pixels (the meshlet
// path's rule for discrete LODs), and appends a VkDrawIndexedIndirectCommand per part of it to the list of the part's
// raster bin (by its material: opaque, double-sided, masked, blended), a mirrored instance's to the bin's mirrored list
// (drawn with clockwise front faces: the pool's indices wind the other way on screen). Each list is cut in parts, one
// per category: the part's shading bin (its material's type and features) in lists drawn by type, else 0; each part
// is drawn by one indirect draw of its own pipeline. The draw list's first words count each part's draws; draws past
// a part's capacity are not written, and its draw's count is capped.
import classic.parts;

struct SelectPush
{
    View* view;
    MeshEntry* meshes;
    Instance* instances;
    ClassicMesh* classicMeshes;
    ClassicLevel* levels;
    ClassicPart* parts;
    uint* draws;           // the parts' counts (per list, the bins' then their mirrored ones, its categories), then
                           // from word lists * categories each part's commands (maxDraws of 5 words each)
    ClassicDraw* records;  // per draw slot: (list * categories + category) * maxDraws + the draw in the part
    uint instanceCount;
    uint maxDraws;         // per part
    Material* materials;   // for each part's raster bin and shading bin
    uint categories;       // parts per list
    uint byType;           // a bit per list (bin, then mirrored bin past BINS): its parts are shading bins
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
        Material material = push.materials[instance.materialBase + ((part.tag >> 24) & 0x7f)];
        uint list = rasterBin(material) + (mirrored(instance) ? BINS : 0);
        uint category = (push.byType >> list & 1) != 0 ? material.bin : 0;
        if (category >= push.categories)
        {
            continue; // a shading bin made since the graph built: drawn from the next build, with its pipelines
        }
        uint at = list * push.categories + category;
        uint local;
        InterlockedAdd(push.draws[at], 1, local);
        if (local >= push.maxDraws)
        {
            continue; // over the part's capacity: counted, not drawn
        }
        uint slot = at * push.maxDraws + local;
        uint c = 2 * BINS * push.categories + slot * 5;
        push.draws[c] = part.indexCount;
        push.draws[c + 1] = 1;
        push.draws[c + 2] = part.firstIndex;
        push.draws[c + 3] = mesh.vertexBase; // the vertex offset: indices count from the mesh's first vertex
        push.draws[c + 4] = slot;            // the first instance: the vertex stage's way to the draw's record
        ClassicDraw draw = { id.x, part.meshlet, part.tag, 0 };
        push.records[slot] = draw;
    }
}
