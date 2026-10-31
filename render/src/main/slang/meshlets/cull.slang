// The meshlet pass of both paths, for every view culled together: one workgroup per chunk, a thread per meshlet. Each
// visible meshlet is appended to its view's list of its raster bin (by its material: opaque, double-sided, masked);
// the mesh shader path launches a mesh workgroup per entry, the indexed path reserves an index range in the bin's part
// of the index buffer for the expansion pass to write, and each bin of each view draws with one draw. Commands are
// built in place by atomics, per bin: the expansion (or mesh) dispatch's X and Y (rows of 65,535 meshlets) and the
// draw's index count.
import meshlets.common;

struct CullPush
{
    View* views;           // the renderer's views of the frame, by index: its view constants array
    MeshEntry* meshes;
    Instance* instances;
    Chunk* chunks;
    uint* meshlets;        // 8 words per meshlet: scene's 32-byte records
    float* clusterLod;     // 10 floats per meshlet
    Occlusion occlusion;
    Material* materials;   // for each meshlet's raster bin
};

[[vk::push_constant]]
ConstantBuffer<CullPush> push;

[shader("compute")]
[numthreads(64, 1, 1)]
void cullMain(uint3 group : SV_GroupID, uint3 thread : SV_GroupThreadID)
{
    Chunk chunk = push.chunks[group.x];
    uint v = chunk.view;
    View view = push.views[v];
    Instance instance = relative(push.instances[chunk.instance], view);
    MeshEntry mesh = push.meshes[instance.mesh];
    uint local = chunk.firstMeshlet + thread.x;
    if (local >= mesh.meshletCount)
    {
        return;
    }
    uint m = mesh.meshletBase + local;
    Material material = push.materials[instance.materialBase + meshletSubmesh(push.meshlets, m)];
    uint bin = rasterBin(material);
    if (!meshletVisible(view, instance, m, push.meshlets, push.clusterLod, materialDoubleSided(material)))
    {
        return;
    }
    bool late = chunk.phase == PHASE_LATE;
    if (late)
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
    // The lists' pointers one load each: copied as one struct, the driver of an AMD Radeon(TM) Graphics read every
    // pointer but the first as its low half twice (pitfalls.md).
    ViewLists lists;
    lists.visible = push.views[v].lists.visible;
    lists.expandCommand = push.views[v].lists.expandCommand;
    lists.drawCommand = push.views[v].lists.drawCommand;
    lists.visibleCount = push.views[v].lists.visibleCount;
    uint index;
    InterlockedAdd(lists.visibleCount[2 * bin + (late ? 1 : 0)], 1, index);
    if (index >= binDrawCapacity(view, bin))
    {
        // Over the bin's capacity: dropped, and counted. Checked before reserving indices: a range reserved and never
        // written would leave a hole later meshlets' draws run through.
        return;
    }
    uint indices = ((push.meshlets[m * 8 + 2] >> 8) & 0xff) * 3;
    uint first;
    InterlockedAdd(lists.drawCommand[6 * bin + 5], indices, first); // indices reserved so far in the bin
    InterlockedMax(lists.visibleCount[6 + bin], first + indices); // the most any phase wanted, for the CPU to read
    uint slot = binFirstSlot(view, bin) + index;
    bool fits = first + indices <= binIndexCapacity(view, bin); // meshlets past the index capacity come last: no gaps
    lists.visible[slot] = VisibleMeshlet(fits ? chunk.instance : 0xffffffff, m, first, 0); // first: in the bin
    if (fits)
    {
        InterlockedMax(lists.drawCommand[6 * bin], first + indices);
    }
    InterlockedMax(lists.expandCommand[4 * bin], min(index + 1, 65535));
    InterlockedMax(lists.expandCommand[4 * bin + 1], index / 65535 + 1);
}
