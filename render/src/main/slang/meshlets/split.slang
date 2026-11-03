// Forward shading by material type (#67): after culling, each raster bin's list of a view is split by the meshlets'
// shading bins (Material.bin: a type and features, as Materials numbers them), so each (raster bin, shading bin) draws
// with one indirect draw of a pipeline of its own type and features, and no shader branches on the type. Three
// dispatches, each a row of workgroups per raster bin: one counts the meshlets (and the indexed path's indices) of each
// shading bin, one lays the shading bins' parts out one after another and writes their draws, one scatters each
// meshlet's slot into its part of the split list (and on the indexed path, gives it its index range in its part, which
// the expansion then writes). Counts are added a wave and a shading bin at a time.
import meshlets.common;

struct SplitPush
{
    View* view;
    SceneTable* scene;        // the instances, meshlets and materials
    VisibleMeshlet* visible;
    uint* visibleCount;
    uint* typed;              // the view's split lists: the header (forward.slang's SPLIT_*), then the slot list
    uint categories;          // shading bins the header has room for: materials of a later bin are not drawn
    uint late;                // 1: the late phase's lists
    uint indexed;             // 1: the indexed path, whose meshlets get index ranges by shading bin
    uint stride;              // threads per raster bin: the dispatch's x times 64
    uint bins;                // the raster bins split, a bit each: the others' lists stay as culled
};

[[vk::push_constant]]
ConstantBuffer<SplitPush> push;

/** Meshlets listed in raster bin `bin` this phase (as the expansion and the mesh draws read them). */
uint listed(View view, uint bin)
{
    return min(push.visibleCount[visibleCountWord(bin, push.late)], binDrawCapacity(view, bin));
}

/**
 * Adds 1 for each lane with `active` to word `word` of its shading bin `category`'s header entry (from `entry0`, the
 * raster bin's first) and, with `indexed`, its `indices` to word `word + 2`, one atomic per word and shading bin present
 * in the wave; returns the lane's places in what was there. Uniform control flow: every lane of the wave calls it.
 */
uint2 waveAdd(uint entry0, uint word, bool active, uint category, uint indices, bool indexed)
{
    uint2 place = 0;
    bool pending = active;
    for (;;)
    {
        uint c = WaveActiveMin(pending ? category : 0xffffffffu);
        if (c == 0xffffffffu)
        {
            break; // the same for the whole wave
        }
        bool mine = pending && category == c;
        uint mineIndices = mine ? indices : 0;
        uint count = WaveActiveCountBits(mine);
        uint before = WavePrefixCountBits(mine);
        uint sum = indexed ? WaveActiveSum(mineIndices) : 0;
        uint beforeIndices = indexed ? WavePrefixSum(mineIndices) : 0;
        uint2 first = 0;
        if (WaveIsFirstLane())
        {
            uint at = entry0 + c * SPLIT_WORDS + word;
            InterlockedAdd(push.typed[at], count, first.x);
            if (indexed)
            {
                InterlockedAdd(push.typed[at + 2], sum, first.y);
            }
        }
        first = WaveReadLaneFirst(first);
        if (mine)
        {
            place = first + uint2(before, beforeIndices);
            pending = false;
        }
    }
    return place;
}

/** Indices of pool meshlet `m`'s triangles. */
uint meshletIndices(uint m)
{
    return ((push.scene[0].meshlets[m * 8 + 2] >> 8) & 0xff) * 3;
}

/** Counts each shading bin's meshlets (and indices) in raster bin group.y, and notes each meshlet's shading bin. */
[shader("compute")]
[numthreads(64, 1, 1)]
void splitCountMain(uint3 group : SV_GroupID, uint thread : SV_GroupIndex)
{
    View view = push.view[0];
    uint bin = group.y;
    if ((push.bins >> bin & 1) == 0)
    {
        return; // uniform: the whole group's bin
    }
    uint count = listed(view, bin);
    uint first = binFirstSlot(view, bin);
    uint entry0 = splitEntry(bin, 0, push.categories);
    for (uint base = group.x * 64; base < count; base += push.stride) // uniform: base is the group's
    {
        uint k = base + thread;
        bool active = k < count;
        uint category = 0;
        uint indices = 0;
        if (active)
        {
            VisibleMeshlet v = push.visible[first + k];
            active = v.instance != 0xffffffffu; // the indexed path's past its index room: not drawn
            if (active)
            {
                Instance instance = push.scene[0].instances[v.instance];
                category = push.scene[0].materials[instance.materialBase
                    + meshletSubmesh(push.scene[0].meshlets, v.meshlet)].bin;
                // A bin made since the graph built has no pipeline yet: left out until the next build makes it
                // (which the new bin asks for), rather than drawn as another type.
                active = category < push.categories;
                indices = active ? meshletIndices(v.meshlet) : 0;
                push.visible[first + k].pad = active ? category : 0xffffffffu; // for the scatter
            }
        }
        waveAdd(entry0, SPLIT_COUNT, active, category, indices, push.indexed != 0); // and SPLIT_INDICES
    }
}

/** Lays raster bin group.y's shading bins out in its parts of the split list and index buffer; writes their draws. */
[shader("compute")]
[numthreads(1, 1, 1)]
void splitLayoutMain(uint3 group : SV_GroupID)
{
    View view = push.view[0];
    uint bin = group.y;
    if ((push.bins >> bin & 1) == 0)
    {
        return;
    }
    uint slots = 0;
    uint indices = 0;
    uint firstIndex = push.indexed != 0 ? binFirstIndex(view, bin) : 0;
    for (uint c = 0; c < push.categories; c++)
    {
        uint e = splitEntry(bin, c, push.categories);
        uint n = push.typed[e + SPLIT_COUNT];
        uint ni = push.typed[e + SPLIT_INDICES];
        push.typed[e + SPLIT_BASE] = slots;
        push.typed[e + SPLIT_INDEX_BASE] = indices;
        push.typed[e + SPLIT_MESH_COMMAND] = min(n, 65535);
        push.typed[e + SPLIT_MESH_COMMAND + 1] = n == 0 ? 1 : (n - 1) / 65535 + 1;
        push.typed[e + SPLIT_MESH_COMMAND + 2] = 1;
        push.typed[e + SPLIT_DRAW_COMMAND] = ni;         // indexCount
        push.typed[e + SPLIT_DRAW_COMMAND + 1] = 1;      // instanceCount
        push.typed[e + SPLIT_DRAW_COMMAND + 2] = firstIndex + indices;
        push.typed[e + SPLIT_DRAW_COMMAND + 3] = 0;      // vertexOffset
        push.typed[e + SPLIT_DRAW_COMMAND + 4] = 0;      // firstInstance
        slots += n;
        indices += ni;
    }
}

/** Scatters raster bin group.y's meshlets into their shading bins' parts; on the indexed path, their index ranges. */
[shader("compute")]
[numthreads(64, 1, 1)]
void splitScatterMain(uint3 group : SV_GroupID, uint thread : SV_GroupIndex)
{
    View view = push.view[0];
    uint bin = group.y;
    if ((push.bins >> bin & 1) == 0)
    {
        return; // uniform: the whole group's bin
    }
    uint count = listed(view, bin);
    uint first = binFirstSlot(view, bin);
    uint entry0 = splitEntry(bin, 0, push.categories);
    uint list = splitList(push.categories) + first;
    for (uint base = group.x * 64; base < count; base += push.stride) // uniform: base is the group's
    {
        uint k = base + thread;
        bool active = k < count;
        uint category = 0;
        uint indices = 0;
        VisibleMeshlet v = (VisibleMeshlet) 0;
        if (active)
        {
            v = push.visible[first + k];
            active = v.instance != 0xffffffffu && v.pad != 0xffffffffu;
            category = active ? v.pad : 0;
            indices = active ? meshletIndices(v.meshlet) : 0;
        }
        // SPLIT_CURSOR and SPLIT_INDEX_CURSOR: the meshlet's place in its shading bin's part, and its indices'
        uint2 place = waveAdd(entry0, SPLIT_CURSOR, active, category, indices, push.indexed != 0);
        if (active)
        {
            uint entry = entry0 + category * SPLIT_WORDS;
            push.typed[list + push.typed[entry + SPLIT_BASE] + place.x] = first + k;
            if (push.indexed != 0)
            {
                push.visible[first + k].firstIndex = push.typed[entry + SPLIT_INDEX_BASE] + place.y; // in the bin
            }
        }
    }
}
