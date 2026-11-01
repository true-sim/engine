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
    uint* chunkCommand;    // the dispatch (x, y, z), then the chunks wanted and [4] those written
};

[[vk::push_constant]]
ConstantBuffer<CullPush> push;

[shader("compute")]
[numthreads(64, 1, 1)]
void cullMain(uint3 group : SV_GroupID, uint3 thread : SV_GroupThreadID)
{
    uint chunkIndex = group.y * 65535 + group.x; // rows of 65,535 workgroups
    if (chunkIndex >= push.chunkCommand[4])
    {
        return; // the last row's workgroups past the chunks written
    }
    Chunk chunk = push.chunks[chunkIndex];
    uint v = chunk.view;
    View view = push.views[v];
    Instance instance = relative(push.instances[chunk.instance], view);
    MeshEntry mesh = push.meshes[instance.mesh];
    uint local = chunk.firstMeshlet + thread.x;
    // Uniform control flow from here: the lists are appended to a wave at a time, one atomic per wave and bin, so no
    // lane leaves before the wave operations.
    bool keep = local < mesh.meshletCount;
    uint m = 0;
    uint bin = 0;
    if (keep)
    {
        m = mesh.meshletBase + local;
        Material material = push.materials[instance.materialBase + meshletSubmesh(push.meshlets, m)];
        bin = rasterBin(material);
        keep = meshletVisible(view, instance, m, push.meshlets, push.clusterLod, materialDoubleSided(material));
    }
    bool late = chunk.phase == PHASE_LATE;
    if (keep && late)
    {
        // A newly visible instance: only its meshlets the pyramid does not hide.
        uint w = m * 8;
        float3 center = toWorld(instance, asfloat(uint3(push.meshlets[w + 3], push.meshlets[w + 4],
            push.meshlets[w + 5])));
        keep = unoccluded(view, push.occlusion, center, asfloat(push.meshlets[w + 6]) * maxScale(instance));
    }
    uint indices = keep ? ((push.meshlets[m * 8 + 2] >> 8) & 0xff) * 3 : 0;
    // The lists' pointers one load each: copied as one struct, the driver of an AMD Radeon(TM) Graphics read every
    // pointer but the first as its low half twice (pitfalls.md).
    ViewLists lists;
    lists.visible = push.views[v].lists.visible;
    lists.expandCommand = push.views[v].lists.expandCommand;
    lists.drawCommand = push.views[v].lists.drawCommand;
    lists.visibleCount = push.views[v].lists.visibleCount;
    for (uint b = 0; b < BINS; b++)
    {
        bool mine = keep && bin == b;
        uint count = WaveActiveCountBits(mine);
        if (count == 0)
        {
            continue; // the same for the whole wave
        }
        // List slots for the wave's meshlets of the bin, in lane order.
        uint base = 0;
        if (WaveIsFirstLane())
        {
            InterlockedAdd(lists.visibleCount[visibleCountWord(b, late ? 1 : 0)], count, base);
        }
        uint index = WaveReadLaneFirst(base) + WavePrefixCountBits(mine);
        // Over the bin's capacity: dropped, and counted. Indices are reserved only for those placed: a range reserved
        // and never written would leave a hole later meshlets' draws run through.
        bool placed = mine && index < binDrawCapacity(view, b);
        uint wanted = placed ? indices : 0;
        uint total = WaveActiveSum(wanted);
        uint firstBase = 0;
        if (total > 0 && WaveIsFirstLane())
        {
            InterlockedAdd(lists.drawCommand[6 * b + 5], total, firstBase); // indices reserved so far in the bin
            InterlockedMax(lists.visibleCount[2 * BINS + b], firstBase + total); // the most any phase wanted, for the CPU
        }
        uint first = WaveReadLaneFirst(firstBase) + WavePrefixSum(wanted);
        // Meshlets past the index capacity come last (first grows with the lane and the wave): no gaps.
        bool fits = placed && first + indices <= binIndexCapacity(view, b);
        if (placed)
        {
            lists.visible[binFirstSlot(view, b) + index] = VisibleMeshlet(fits ? chunk.instance : 0xffffffff, m, first,
                0); // first: in the bin
        }
        uint drawn = WaveActiveMax(fits ? first + indices : 0);
        uint listed = WaveActiveMax(placed ? index + 1 : 0);
        if (WaveIsFirstLane())
        {
            if (drawn > 0)
            {
                InterlockedMax(lists.drawCommand[6 * b], drawn);
            }
            if (listed > 0)
            {
                InterlockedMax(lists.expandCommand[4 * b], min(listed, 65535));
                InterlockedMax(lists.expandCommand[4 * b + 1], (listed - 1) / 65535 + 1);
            }
        }
    }
}
