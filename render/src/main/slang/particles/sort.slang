// Particles sorted back to front for a view or a pair of eyes (effects.md §2.4, E7, E19): the sorted emitters' alive
// particles, each emitter's together, by their distance from the view's center. sortArgsMain gives each sorted emitter
// its first key (an exclusive scan of their alive counts) and sizes the sort's indirect dispatches; sortKeysMain, a
// group per chunk of a sorted emitter's range, writes each particle's key (the emitter's place among the sorted, then
// its distance, farthest first) and its index in the range. Then the core's radix sort (blocks/sort.slang, stable,
// 4 bits a pass) leaves every emitter's particles in its own run, farthest first; the draws read each run by the
// emitter's first key.
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
    float3 centerHigh;             // the view's (or the pair's) center: high part
    uint sortedCount;              // sorted emitters
    float3 centerLow;              // and the rest
    uint chunkCount;               // chunks of their ranges (sortKeysMain)
    uint capacity;                 // keys the buffers hold: more are left out (a frame before its graph grew them)
};

[[vk::push_constant]]
ConstantBuffer<ParticleSortPush> push;

groupshared uint runs[SORT_THREADS];

/** The group of a dispatch in rows of up to 65,535 groups. */
uint groupOf(uint3 group)
{
    return group.x + 65535 * group.y;
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
        total = min(total, push.capacity);
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
    Particle p = particles[e.first + i];
    float3 d = relativeTo(e, p.position + p.velocity * e.held, push.centerHigh, push.centerLow);
    // Farthest first: the squared distance's bits (a positive float orders as its bits do), inverted, top 20 kept
    uint depth = (0x7fffffffu - asuint(dot(d, d))) >> (31 - SORT_DEPTH_BITS);
    uint* args = push.args;
    uint2* target = push.target;
    if (args[4 + chunk.x] + i < push.capacity)
    {
        target[args[4 + chunk.x] + i] = uint2(chunk.x << SORT_DEPTH_BITS | depth, i);
    }
}

// ---------------------------------------------------------------------------------------------- ribbons per parent

/**
 * Keys of the particles of emitters drawing a ribbon per parent (particles as emitters, effects step 19), as
 * sortKeysMain makes the view's: the emitter's place among them above the low 20 bits of its parent's spawn index (the
 * ribbon id), and each particle's index in its range. The stable sort leaves each ribbon's particles together, in spawn
 * order. (Ribbons whose parents' spawn indices are 2^20 apart share a run: never at once at the rates of trails.)
 */
[shader("compute")]
[numthreads(64, 1, 1)]
void ribbonKeysMain(uint3 group : SV_GroupID, uint lane : SV_GroupIndex)
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
    uint ribbon = asuint(particles[e.first + i].custom.y) & ((1u << SORT_DEPTH_BITS) - 1);
    uint* args = push.args;
    uint2* target = push.target;
    if (args[4 + chunk.x] + i < push.capacity)
    {
        target[args[4 + chunk.x] + i] = uint2(chunk.x << SORT_DEPTH_BITS | ribbon, i);
    }
}

/** Each sorted key's particle into its emitter's range of the ribbon order, in its run's order. */
[shader("compute")]
[numthreads(SORT_THREADS, 1, 1)]
void ribbonOrderMain(uint3 group : SV_GroupID, uint lane : SV_GroupIndex)
{
    uint* args = push.args;
    uint2* source = push.source;
    uint* list = push.list;
    ParticleEmitter* emitters = push.emitters;
    uint* order = (uint*) push.target;
    for (uint k = 0; k < SORT_KEYS; k++)
    {
        uint s = groupOf(group) * SORT_GROUP + k * SORT_THREADS + lane;
        if (s < args[3])
        {
            uint2 item = source[s];
            uint place = item.x >> SORT_DEPTH_BITS;
            order[emitters[list[place]].first + s - args[4 + place]] = item.y;
        }
    }
}
