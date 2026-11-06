// Classic LOD's blended draws of a view sorted farthest first, for SortedTransparency: within each blended part of its
// draw list (a shading bin, unmirrored or mirrored), each part still drawn by one indirect draw of its own pipeline,
// so the order across parts is the parts' (see SortedTransparency). classicSortArgsMain finds where each part's draws
// start among all of them and sizes the radix sort; classicSortKeysMain writes each draw's key (its part above 24 bits
// of its instance's squared distance, inverted: farthest first) and its slot; the radix sort is blocks/sort.slang's,
// 8 passes; classicSortPlaceMain copies each draw's command into its part's range of the sorted commands, in order.
import meshlets.common;

struct ClassicSortPush
{
    View* view;
    SceneTable* scene;        // the instances and meshes
    uint* draws;              // the draw list: per part its count, then from word 2 * BINS * categories the commands
    uint* records;            // per draw slot: instance, meshlet, tag, 0
    uint2* keys;              // the keys and slots: the radix sort's first buffer, where its result ends
    uint* args;               // the sort's dispatch and key count, then from word 4 each blended part's first key
    uint* sorted;             // per blended part, maxDraws commands of 5 words
    uint categories;          // parts per list
    uint maxDraws;            // draws per part
    uint group;               // keys per group of the radix sort
};

[[vk::push_constant]]
ConstantBuffer<ClassicSortPush> push;

/** The draw list's part of blended part `p`: the blended bin's list (p < categories), then its mirrored one. */
uint listPart(uint p)
{
    uint mirrored = p / push.categories;
    return (BIN_BLENDED + mirrored * BINS) * push.categories + p % push.categories;
}

[shader("compute")]
[numthreads(1, 1, 1)]
void classicSortArgsMain()
{
    uint total = 0;
    for (uint p = 0; p < 2 * push.categories; p++)
    {
        push.args[4 + p] = total;
        total += min(push.draws[listPart(p)], push.maxDraws);
    }
    uint groups = (total + push.group - 1) / push.group;
    push.args[0] = min(groups, 65535u);
    push.args[1] = (groups + 65534) / 65535;
    push.args[2] = 1;
    push.args[3] = total;
}

[shader("compute")]
[numthreads(64, 1, 1)]
void classicSortKeysMain(uint3 id : SV_DispatchThreadID, uint3 group : SV_GroupID, uint lane : SV_GroupIndex)
{
    uint t = (group.y * 65535 + group.x) * 64 + lane;
    uint p = t / push.maxDraws;
    uint local = t % push.maxDraws;
    if (p >= 2 * push.categories || local >= min(push.draws[listPart(p)], push.maxDraws))
    {
        return;
    }
    View view = push.view[0];
    uint slot = listPart(p) * push.maxDraws + local;
    Instance instance = relative(push.scene[0].instances[push.records[slot * 4]], view);
    MeshEntry mesh = push.scene[0].meshes[instance.mesh];
    float3 center = toWorld(instance, mesh.sphere.xyz) - view.eye;
    uint depth = (0x7fffffffu - asuint(dot(center, center))) >> 7; // farthest first
    push.keys[push.args[4 + p] + local] = uint2(p << 24 | depth, slot);
}

[shader("compute")]
[numthreads(64, 1, 1)]
void classicSortPlaceMain(uint3 group : SV_GroupID, uint lane : SV_GroupIndex)
{
    uint i = (group.y * 65535 + group.x) * 64 + lane;
    if (i >= push.args[3])
    {
        return;
    }
    uint2 item = push.keys[i];
    uint p = item.x >> 24;
    uint local = i - push.args[4 + p];
    uint from = 2 * BINS * push.categories + item.y * 5;
    uint to = (p * push.maxDraws + local) * 5;
    for (uint w = 0; w < 5; w++)
    {
        push.sorted[to + w] = push.draws[from + w];
    }
}
