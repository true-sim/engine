// Particles sorted back to front for a view or a pair of eyes (effects.md §2.4, E7, E19): the sorted emitters' alive
// particles, each emitter's together, by their distance from the view's center. sortArgsMain gives each sorted emitter
// its first key (an exclusive scan of their alive counts) and sizes the sort's indirect dispatches; sortKeysMain, a
// group per chunk of a sorted emitter's range, writes each particle's key (the emitter's place among the sorted, then
// its distance, farthest first) and its index in the range. Then a radix sort, 4 bits a pass, FidelityFX-style:
// sortCountMain counts each group's digits, sortScanMain scans the counts digit by digit over the groups, and
// sortScatterMain sorts each group's keys by their digit (four 1-bit splits, stable) and writes them where the scan
// says. Stable passes from the lowest digit up leave every emitter's particles in its own run, farthest first; the
// draws read each run by the emitter's first key.
import particle;

struct ParticleSortPush
{
    Particle* particles;           // the half of the pool the simulation wrote this frame
    ParticleEmitter* emitters;     // the frame's list
    uint* state;                   // the emitter instances' state
    uint* list;                    // per sorted emitter: its place in the frame's list
    uint2* chunks;                 // per chunk of a sorted emitter's range: its place among the sorted, the chunk
    uint* args;                    // the dispatch (3 words), the key count, then each sorted emitter's first key
    uint2* source;                 // keys and their particles' indices in their ranges, read by the pass
    uint2* target;                 // written by the pass
    uint* histogram;               // per digit and group: keys, then where they go
    float3 centerHigh;             // the view's (or the pair's) center: high part
    uint sortedCount;              // sorted emitters
    float3 centerLow;              // and the rest
    uint chunkCount;               // chunks of their ranges (sortKeysMain)
    uint shift;                    // the pass's digit: bits from the key's lowest
};

[[vk::push_constant]]
ConstantBuffer<ParticleSortPush> push;

groupshared uint runs[SORT_GROUP];
groupshared uint sums[2][SORT_GROUP];
groupshared uint digits[16];
groupshared uint2 tile[SORT_GROUP];

/** The group of a dispatch in rows of up to 65,535 groups. */
uint groupOf(uint3 group)
{
    return group.x + 65535 * group.y;
}

/** Groups the sort's passes take: a key each per thread. */
uint groups()
{
    uint* args = push.args;
    return (args[3] + SORT_GROUP - 1) / SORT_GROUP;
}

[shader("compute")]
[numthreads(64, 1, 1)]
void sortArgsMain(uint lane : SV_GroupIndex)
{
    // One group: a run of sorted emitters per thread, the runs' sums scanned, then each emitter's first key.
    uint n = push.sortedCount;
    uint run = (n + 63) / 64;
    uint from = min(lane * run, n);
    uint to = min(from + run, n);
    uint* list = push.list;
    ParticleEmitter* emitters = push.emitters;
    uint* state = push.state;
    uint sum = 0;
    for (uint i = from; i < to; i++)
    {
        sum += state[emitters[list[i]].instance * STATE_WORDS + STATE_ALIVE];
    }
    runs[lane] = sum;
    GroupMemoryBarrierWithGroupSync();
    uint* args = push.args;
    if (lane == 0)
    {
        uint total = 0;
        for (uint i = 0; i < 64; i++)
        {
            uint s = runs[i];
            runs[i] = total;
            total += s;
        }
        uint g = (total + SORT_GROUP - 1) / SORT_GROUP;
        args[0] = min(g, 65535u);
        args[1] = (g + 65534) / 65535;
        args[2] = 1;
        args[3] = total;
    }
    GroupMemoryBarrierWithGroupSync();
    uint offset = runs[lane];
    for (uint i = from; i < to; i++)
    {
        args[4 + i] = offset;
        offset += state[emitters[list[i]].instance * STATE_WORDS + STATE_ALIVE];
    }
}

[shader("compute")]
[numthreads(64, 1, 1)]
void sortKeysMain(uint3 group : SV_GroupID, uint lane : SV_GroupIndex)
{
    uint g = groupOf(group);
    if (g >= push.chunkCount)
    {
        return;
    }
    uint2* chunks = push.chunks;
    uint2 chunk = chunks[g];
    uint* list = push.list;
    ParticleEmitter* emitters = push.emitters;
    ParticleEmitter e = emitters[list[chunk.x]];
    uint* state = push.state;
    uint i = chunk.y * CHUNK + lane;
    if (i >= state[e.instance * STATE_WORDS + STATE_ALIVE])
    {
        return;
    }
    Particle* particles = push.particles;
    float3 d = relativeTo(e, particles[e.first + i].position, push.centerHigh, push.centerLow);
    // Farthest first: the squared distance's bits (a positive float orders as its bits do), inverted, top 20 kept
    uint depth = (0x7fffffffu - asuint(dot(d, d))) >> (31 - SORT_DEPTH_BITS);
    uint* args = push.args;
    uint2* target = push.target;
    target[args[4 + chunk.x] + i] = uint2(chunk.x << SORT_DEPTH_BITS | depth, i);
}

[shader("compute")]
[numthreads(SORT_GROUP, 1, 1)]
void sortCountMain(uint3 group : SV_GroupID, uint lane : SV_GroupIndex)
{
    uint g = groupOf(group);
    if (g >= groups())
    {
        return; // the whole group: past the last row's end
    }
    if (lane < 16)
    {
        digits[lane] = 0;
    }
    GroupMemoryBarrierWithGroupSync();
    uint* args = push.args;
    uint i = g * SORT_GROUP + lane;
    if (i < args[3])
    {
        uint2* source = push.source;
        InterlockedAdd(digits[(source[i].x >> push.shift) & 15], 1);
    }
    GroupMemoryBarrierWithGroupSync();
    if (lane < 16)
    {
        uint* histogram = push.histogram;
        histogram[lane * groups() + g] = digits[lane];
    }
}

[shader("compute")]
[numthreads(SORT_GROUP, 1, 1)]
void sortScanMain(uint lane : SV_GroupIndex)
{
    // One group: digit by digit, group by group, where each group's keys of each digit go.
    uint total = 16 * groups();
    uint run = (total + SORT_GROUP - 1) / SORT_GROUP;
    uint from = min(lane * run, total);
    uint to = min(from + run, total);
    uint* histogram = push.histogram;
    uint sum = 0;
    for (uint i = from; i < to; i++)
    {
        sum += histogram[i];
    }
    runs[lane] = sum;
    GroupMemoryBarrierWithGroupSync();
    if (lane == 0)
    {
        uint before = 0;
        for (uint i = 0; i < SORT_GROUP; i++)
        {
            uint s = runs[i];
            runs[i] = before;
            before += s;
        }
    }
    GroupMemoryBarrierWithGroupSync();
    uint offset = runs[lane];
    for (uint i = from; i < to; i++)
    {
        uint count = histogram[i];
        histogram[i] = offset;
        offset += count;
    }
}

/** The sum of `value` over the group's threads up to `lane`, itself included. */
uint inclusiveSum(uint value, uint lane)
{
    uint from = 0;
    sums[0][lane] = value;
    GroupMemoryBarrierWithGroupSync();
    for (uint step = 1; step < SORT_GROUP; step <<= 1)
    {
        uint s = sums[from][lane] + (lane >= step ? sums[from][lane - step] : 0);
        sums[1 - from][lane] = s;
        from = 1 - from;
        GroupMemoryBarrierWithGroupSync();
    }
    return sums[from][lane];
}

[shader("compute")]
[numthreads(SORT_GROUP, 1, 1)]
void sortScatterMain(uint3 group : SV_GroupID, uint lane : SV_GroupIndex)
{
    uint g = groupOf(group);
    if (g >= groups())
    {
        return; // the whole group
    }
    uint* args = push.args;
    uint n = args[3];
    uint i = g * SORT_GROUP + lane;
    uint2* source = push.source;
    // Past the end: the greatest key, after every real one of its digit (they come first, and splits are stable)
    uint2 item = i < n ? source[i] : uint2(0xffffffffu, 0);
    uint shift = push.shift;
    for (uint b = 0; b < 4; b++)
    {
        uint zero = ((item.x >> (shift + b)) & 1) == 0 ? 1 : 0;
        uint zeros = inclusiveSum(zero, lane);
        uint allZeros = sums[0][SORT_GROUP - 1]; // eight steps: the sums end in the first buffer
        uint before = zeros - zero;
        uint at = zero != 0 ? before : allZeros + lane - before;
        tile[at] = item;
        GroupMemoryBarrierWithGroupSync();
        item = tile[lane];
        GroupMemoryBarrierWithGroupSync();
    }
    // The tile is in digit order: each digit's first place in it, then each key's place in the whole
    uint digit = (item.x >> shift) & 15;
    if (lane == 0 || ((tile[lane - 1].x >> shift) & 15) != digit)
    {
        digits[digit] = lane;
    }
    GroupMemoryBarrierWithGroupSync();
    if (lane < min(n - g * SORT_GROUP, SORT_GROUP))
    {
        uint* histogram = push.histogram;
        uint2* target = push.target;
        target[histogram[digit * groups() + g] + lane - digits[digit]] = item;
    }
}
