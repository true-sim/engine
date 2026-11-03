// A view's blended meshlets sorted by their distance from its eye, after the split (split.slang) and before the
// indexed path's expansion (expand.slang), which then writes their indices in the sorted order, for SortedTransparency:
// farthest first, in runs of one shading bin each, each run a (run slot, drawn shading bin) entry of the split lists'
// header, so the draws (an indirect draw per run slot and drawn shading bin, its type's pipeline) draw the meshlets back
// to front across shading bins; past the run slots, the meshlets are ordered by shading bin first (two more radix
// passes), each shading bin's back to front.
//
// sortArgsMain sizes the dispatches; sortKeysMain writes a key per listed meshlet (its shading bin above 24 bits of its
// squared distance, inverted: farthest first; 0xffffffff for one not drawn, which sorts last) and its slot. The radix
// sort is particles/sort.slang's (count, scan, scatter, 4 bits a pass): 6 passes over the distance, then, where the
// runs are more than the slots, 2 over the shading bin. sortBlocksMain counts the runs (a
// new one where the shading bin changes) and indices of each block of SORT_BLOCK keys, sortScanMain scans the blocks,
// sortPlaceMain writes each meshlet's slot into the split list in the sorted order, its first index (the indexed path)
// and each run's start; sortEntriesMain writes the header entries of the run slots.
import meshlets.common;

struct BlendedSortPush
{
    View* view;
    SceneTable* scene;        // the instances and meshlets
    VisibleMeshlet* visible;
    uint* visibleCount;
    uint* typed;              // the view's split lists (split.slang)
    uint2* keys;              // the keys and slots: the radix sort's first buffer, where its result ends
    uint* args;               // SORT_ARGS_* below
    uint* blocks;             // per block of SORT_BLOCK keys: its runs and indices, then where they start
    uint* runs;               // per run slot: its first key, its first index, its shading bin
    uint* drawnBins;          // per shading bin of the table, its place among those drawn (categories); past: none
    uint bin;                 // the raster bin sorted
    uint late;                // 1: the late phase's lists
    uint categories;          // shading bins drawn (blended ones), numbered by drawnBins; the others are not drawn
    uint tableBins;           // shading bins drawnBins numbers: meshlets of later ones are not drawn
    uint room;                // shading bins the split lists have room for
    uint slots;               // run slots drawn
    uint indexed;             // 1: the indexed path, whose meshlets get index ranges in the sorted order
    uint group;               // keys per group of the radix sort (particle.slang's SORT_GROUP)
    uint final;               // sortScanMain: 0, the first count (whether the runs fit the slots); 1, the last
};

[[vk::push_constant]]
ConstantBuffer<BlendedSortPush> push;

/** Keys a block of sortBlocksMain and sortPlaceMain takes: 4 a thread. */
static const uint SORT_BLOCK = 1024;
static const uint BLOCK_THREADS = 256;
/** The words of args: the radix sort's dispatch (and key count at 3), as particles/sort.slang reads them. */
static const uint ARGS_SORT = 0;
static const uint ARGS_KEYS = 4;      // a dispatch of 64-thread groups over the keys
static const uint ARGS_DRAWN = 7;     // keys of meshlets drawn
static const uint ARGS_BY_BIN = 8;    // the radix sort's dispatch over the shading bin's bits: none, or ARGS_SORT's
static const uint ARGS_BLOCKS = 12;   // a dispatch of a group per block
static const uint ARGS_RUNS = 15;     // runs in all
static const uint ARGS_INDICES = 16;  // indices in all
/** The key of a meshlet not drawn: after every drawn one, whichever passes run. */
static const uint NOT_DRAWN = 0xffffffffu;

groupshared uint waveSums[BLOCK_THREADS / 4];

/** A dispatch of `groups` groups in rows of up to 65,535 at word `at` of args. */
void dispatchArgs(uint at, uint groups)
{
    push.args[at] = min(groups, 65535u);
    push.args[at + 1] = (groups + 65534) / 65535;
    push.args[at + 2] = 1;
}

[shader("compute")]
[numthreads(1, 1, 1)]
void sortArgsMain()
{
    View view = push.view[0];
    uint n = min(push.visibleCount[visibleCountWord(push.bin, push.late)], binDrawCapacity(view, push.bin));
    uint groups = (n + push.group - 1) / push.group;
    dispatchArgs(ARGS_SORT, groups);
    push.args[ARGS_SORT + 3] = n;
    dispatchArgs(ARGS_KEYS, (n + 63) / 64);
    push.args[ARGS_DRAWN] = 0;
    dispatchArgs(ARGS_BY_BIN, 0); // none, unless sortScanMain finds more runs than slots
    push.args[ARGS_BY_BIN + 3] = n;
    dispatchArgs(ARGS_BLOCKS, (n + SORT_BLOCK - 1) / SORT_BLOCK);
    push.args[ARGS_RUNS] = 0;
    push.args[ARGS_INDICES] = 0;
}

[shader("compute")]
[numthreads(64, 1, 1)]
void sortKeysMain(uint3 group : SV_GroupID, uint lane : SV_GroupIndex)
{
    uint k = (group.y * 65535 + group.x) * 64 + lane;
    if (k >= push.args[ARGS_SORT + 3])
    {
        return;
    }
    View view = push.view[0];
    uint slot = binFirstSlot(view, push.bin) + k;
    VisibleMeshlet v = push.visible[slot];
    uint bin = v.pad; // the split's: its shading bin, or 0xffffffff where it is not drawn
    uint category = bin < push.tableBins ? push.drawnBins[bin] : 0xffffffffu;
    uint key = NOT_DRAWN;
    if (v.instance != 0xffffffffu && category < push.categories)
    {
        Instance instance = relative(push.scene[0].instances[v.instance], view);
        uint* meshlets = push.scene[0].meshlets;
        uint w = v.meshlet * 8;
        float3 center = toWorld(instance, asfloat(uint3(meshlets[w + 3], meshlets[w + 4], meshlets[w + 5])))
            - view.eye;
        // A positive float orders as its bits: the squared distance's top 24 bits, inverted for farthest first
        uint d = asuint(dot(center, center));
        uint depth = (0x7fffffffu - d) >> 7;
        key = category << 24 | min(depth, 0xfffffeu); // below NOT_DRAWN's, which sorts last
        InterlockedAdd(push.args[ARGS_DRAWN], 1);
    }
    push.keys[k] = uint2(key, slot);
}

/** What key `i` of `n` adds: 1 run where it starts one (its shading bin changes), and its indices on the indexed path. */
uint2 counted(uint i, uint n, out bool drawn, out bool starts)
{
    uint key = i < n ? push.keys[i].x : NOT_DRAWN;
    drawn = key >> 24 != 0xff;
    starts = drawn && (i == 0 || push.keys[i - 1].x >> 24 != key >> 24);
    uint indices = 0;
    if (drawn && push.indexed != 0)
    {
        uint m = push.visible[push.keys[i].y].meshlet;
        indices = ((push.scene[0].meshlets[m * 8 + 2] >> 8) & 0xff) * 3;
    }
    return uint2(starts ? 1 : 0, indices);
}

/** The sum of `value` over the group's threads before `lane` (waves of consecutive threads). Every thread calls it. */
uint exclusiveSum(uint value, uint lane)
{
    uint size = WaveGetLaneCount();
    uint before = WavePrefixSum(value);
    if (WaveGetLaneIndex() == size - 1)
    {
        waveSums[lane / size] = before + value;
    }
    GroupMemoryBarrierWithGroupSync();
    if (lane == 0)
    {
        uint sum = 0;
        for (uint w = 0; w < BLOCK_THREADS / size; w++)
        {
            uint s = waveSums[w];
            waveSums[w] = sum;
            sum += s;
        }
    }
    GroupMemoryBarrierWithGroupSync();
    uint result = waveSums[lane / size] + before;
    GroupMemoryBarrierWithGroupSync(); // free for the next call
    return result;
}

[shader("compute")]
[numthreads(BLOCK_THREADS, 1, 1)]
void sortBlocksMain(uint3 group : SV_GroupID, uint lane : SV_GroupIndex)
{
    uint b = group.y * 65535 + group.x;
    uint n = push.args[ARGS_SORT + 3];
    uint2 sum = 0;
    for (uint j = 0; j < 4; j++)
    {
        bool drawn;
        bool starts;
        sum += counted(b * SORT_BLOCK + lane * 4 + j, n, drawn, starts);
    }
    uint runs = exclusiveSum(sum.x, lane);
    uint indices = exclusiveSum(sum.y, lane);
    if (lane == BLOCK_THREADS - 1)
    {
        push.blocks[2 * b] = runs + sum.x;
        push.blocks[2 * b + 1] = indices + sum.y;
    }
}

[shader("compute")]
[numthreads(BLOCK_THREADS, 1, 1)]
void sortScanMain(uint lane : SV_GroupIndex)
{
    // One group: the blocks' counts scanned, a run of blocks a thread
    uint n = push.args[ARGS_SORT + 3];
    uint count = (n + SORT_BLOCK - 1) / SORT_BLOCK;
    uint run = (count + BLOCK_THREADS - 1) / BLOCK_THREADS;
    uint from = min(lane * run, count);
    uint to = min(from + run, count);
    uint2 sum = 0;
    for (uint i = from; i < to; i++)
    {
        sum += uint2(push.blocks[2 * i], push.blocks[2 * i + 1]);
    }
    uint2 offset = uint2(exclusiveSum(sum.x, lane), exclusiveSum(sum.y, lane));
    for (uint i = from; i < to; i++)
    {
        uint2 here = uint2(push.blocks[2 * i], push.blocks[2 * i + 1]);
        push.blocks[2 * i] = offset.x;
        push.blocks[2 * i + 1] = offset.y;
        offset += here;
    }
    if (lane == BLOCK_THREADS - 1)
    {
        push.args[ARGS_RUNS] = offset.x;
        push.args[ARGS_INDICES] = offset.y;
        if (push.final == 0 && offset.x > push.slots)
        {
            // More runs than slots: ordered by shading bin first, each one's back to front, a run each
            push.args[ARGS_BY_BIN] = push.args[ARGS_SORT];
            push.args[ARGS_BY_BIN + 1] = push.args[ARGS_SORT + 1];
        }
    }
}

[shader("compute")]
[numthreads(BLOCK_THREADS, 1, 1)]
void sortPlaceMain(uint3 group : SV_GroupID, uint lane : SV_GroupIndex)
{
    uint b = group.y * 65535 + group.x;
    uint n = push.args[ARGS_SORT + 3];
    bool drawn[4];
    bool starts[4];
    uint2 counts[4];
    uint2 sum = 0;
    for (uint j = 0; j < 4; j++)
    {
        counts[j] = counted(b * SORT_BLOCK + lane * 4 + j, n, drawn[j], starts[j]);
        sum += counts[j];
    }
    uint2 at = uint2(exclusiveSum(sum.x, lane), exclusiveSum(sum.y, lane))
        + uint2(push.blocks[2 * b], push.blocks[2 * b + 1]);
    View view = push.view[0];
    uint list = splitList(push.room) + binFirstSlot(view, push.bin);
    for (uint j = 0; j < 4; j++)
    {
        uint i = b * SORT_BLOCK + lane * 4 + j;
        if (drawn[j])
        {
            uint2 item = push.keys[i];
            push.typed[list + i] = item.y;
            if (push.indexed != 0)
            {
                push.visible[item.y].firstIndex = at.y; // in the bin, as the split's
            }
            uint run = at.x; // runs started before it: its own is the next, where it starts one
            if (starts[j] && run < push.slots)
            {
                push.runs[3 * run] = i;
                push.runs[3 * run + 1] = at.y;
                push.runs[3 * run + 2] = item.x >> 24;
            }
        }
        at += counts[j];
    }
}

[shader("compute")]
[numthreads(64, 1, 1)]
void sortEntriesMain(uint lane : SV_GroupIndex)
{
    // Every header entry of the bin: (run slot, shading bin) entries hold their run, the rest nothing (after the
    // drawn meshlets, so the expansion's end, the last entry's, is theirs)
    View view = push.view[0];
    uint runs = min(push.args[ARGS_RUNS], push.slots);
    uint drawn = push.args[ARGS_DRAWN];
    uint indices = push.args[ARGS_INDICES];
    uint firstIndex = push.indexed != 0 ? binFirstIndex(view, push.bin) : 0;
    for (uint v = lane; v < push.room; v += 64)
    {
        uint r = v / push.categories;
        uint c = v % push.categories;
        uint start = drawn;
        uint end = drawn;
        uint indexStart = indices;
        uint indexEnd = indices;
        if (r < runs && push.runs[3 * r + 2] == c)
        {
            start = push.runs[3 * r];
            indexStart = push.runs[3 * r + 1];
            end = r + 1 < runs ? push.runs[3 * r + 3] : drawn;
            indexEnd = r + 1 < runs ? push.runs[3 * r + 4] : indices;
        }
        uint e = splitEntry(push.bin, v, push.room);
        uint count = end - start;
        push.typed[e + SPLIT_COUNT] = count;
        push.typed[e + SPLIT_CURSOR] = count;
        push.typed[e + SPLIT_INDICES] = indexEnd - indexStart;
        push.typed[e + SPLIT_INDEX_CURSOR] = indexEnd - indexStart;
        push.typed[e + SPLIT_BASE] = start;
        push.typed[e + SPLIT_INDEX_BASE] = indexStart;
        push.typed[e + SPLIT_MESH_COMMAND] = min(count, 65535);
        push.typed[e + SPLIT_MESH_COMMAND + 1] = count == 0 ? 1 : (count - 1) / 65535 + 1;
        push.typed[e + SPLIT_MESH_COMMAND + 2] = 1;
        push.typed[e + SPLIT_DRAW_COMMAND] = indexEnd - indexStart;
        push.typed[e + SPLIT_DRAW_COMMAND + 1] = 1;
        push.typed[e + SPLIT_DRAW_COMMAND + 2] = firstIndex + indexStart;
        push.typed[e + SPLIT_DRAW_COMMAND + 3] = 0;
        push.typed[e + SPLIT_DRAW_COMMAND + 4] = 0;
    }
}
