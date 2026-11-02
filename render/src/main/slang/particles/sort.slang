// Particles sorted back to front for a view or a pair of eyes (effects.md §2.4, E7, E19): the sorted emitters' alive
// particles, each emitter's together, by their distance from the view's center. sortArgsMain gives each sorted emitter
// its first key (an exclusive scan of their alive counts) and sizes the sort's indirect dispatches; sortKeysMain, a
// group per chunk of a sorted emitter's range, writes each particle's key (the emitter's place among the sorted, then
// its distance, farthest first) and its index in the range. Then a radix sort, 4 bits a pass, FidelityFX-style, in
// groups of SORT_GROUP keys (SORT_KEYS a thread): sortCountMain counts each group's digits, sortScanMain scans the
// counts digit by digit over the groups, and sortScatterMain ranks each key among the keys of its digit before it in
// its group (wave ballots of the digit's bits, then the waves' counts scanned) and writes it straight where the scan
// says, which keeps the group's order (stable). Stable passes from the lowest digit up leave every emitter's particles
// in its own run, farthest first; the draws read each run by the emitter's first key.
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
    uint capacity;                 // keys the buffers hold: more are left out (a frame before its graph grew them)
};

[[vk::push_constant]]
ConstantBuffer<ParticleSortPush> push;

/** The fewest lanes a wave has: waves of 4 to 128 lanes rank keys alike. */
static const uint LEAST_WAVE = 4;

groupshared uint runs[SORT_THREADS];
groupshared uint waveSums[SORT_THREADS / LEAST_WAVE];
groupshared uint digits[16];
groupshared uint waveDigits[16 * SORT_THREADS / LEAST_WAVE];

/** The group of a dispatch in rows of up to 65,535 groups. */
uint groupOf(uint3 group)
{
    return group.x + 65535 * group.y;
}

/** Groups the sort's passes take: SORT_GROUP keys each. */
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

/**
 * The sum of `value` over the group's threads before `lane` (SORT_THREADS of them, in waves of consecutive threads):
 * each wave's prefix sums, then the waves' sums scanned. Every thread calls it together.
 */
uint exclusiveSum(uint value, uint lane)
{
    uint n = WaveGetLaneCount();
    uint before = WavePrefixSum(value);
    if (WaveGetLaneIndex() == n - 1)
    {
        waveSums[lane / n] = before + value;
    }
    GroupMemoryBarrierWithGroupSync();
    if (lane == 0)
    {
        uint sum = 0;
        for (uint w = 0; w < SORT_THREADS / n; w++)
        {
            uint s = waveSums[w];
            waveSums[w] = sum;
            sum += s;
        }
    }
    GroupMemoryBarrierWithGroupSync();
    uint result = waveSums[lane / n] + before;
    GroupMemoryBarrierWithGroupSync(); // the waves' sums free for the next call
    return result;
}

[shader("compute")]
[numthreads(SORT_THREADS, 1, 1)]
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
    uint n = args[3];
    uint2* source = push.source;
    uint4 below = lanesBelow();
    for (uint k = 0; k < SORT_KEYS; k++)
    {
        // A wave's keys of one digit counted once, by the first lane holding one
        uint i = g * SORT_GROUP + k * SORT_THREADS + lane;
        bool held = i < n;
        uint digit = held ? (source[i].x >> push.shift) & 15 : 0;
        uint4 peers = digitBallots(held, digit).lanes(digit);
        if (held && countBits(peers & below) == 0)
        {
            InterlockedAdd(digits[digit], countBits(peers));
        }
    }
    GroupMemoryBarrierWithGroupSync();
    if (lane < 16)
    {
        uint* histogram = push.histogram;
        histogram[lane * groups() + g] = digits[lane];
    }
}

[shader("compute")]
[numthreads(SORT_THREADS, 1, 1)]
void sortScanMain(uint lane : SV_GroupIndex)
{
    // One group: digit by digit, group by group, where each group's keys of each digit go.
    uint total = 16 * groups();
    uint run = (total + SORT_THREADS - 1) / SORT_THREADS;
    uint from = min(lane * run, total);
    uint to = min(from + run, total);
    uint* histogram = push.histogram;
    uint sum = 0;
    for (uint i = from; i < to; i++)
    {
        sum += histogram[i];
    }
    uint offset = exclusiveSum(sum, lane);
    for (uint i = from; i < to; i++)
    {
        uint count = histogram[i];
        histogram[i] = offset;
        offset += count;
    }
}

[shader("compute")]
[numthreads(SORT_THREADS, 1, 1)]
void sortScatterMain(uint3 group : SV_GroupID, uint lane : SV_GroupIndex)
{
    uint g = groupOf(group);
    if (g >= groups())
    {
        return; // the whole group
    }
    uint* args = push.args;
    uint n = args[3];
    uint2* source = push.source;
    uint shift = push.shift;
    // Each wave takes SORT_KEYS rows of consecutive keys, one a lane, the waves in order: a key's place among the
    // group's keys of its digit is those of the waves before, of its wave's rows before (counted per digit as they
    // go) and of the lanes below
    uint size = WaveGetLaneCount();
    uint wave = lane / size;
    uint waves = SORT_THREADS / size;
    uint first = g * SORT_GROUP + wave * SORT_KEYS * size + WaveGetLaneIndex();
    uint4 below = lanesBelow();
    DigitCounts counts = noDigits();
    uint2 items[SORT_KEYS];
    uint ranks[SORT_KEYS];
    for (uint k = 0; k < SORT_KEYS; k++)
    {
        uint i = first + k * size;
        bool held = i < n;
        items[k] = held ? source[i] : uint2(0, 0);
        uint digit = (items[k].x >> shift) & 15;
        DigitBallots row = digitBallots(held, digit);
        ranks[k] = counts.of(digit) + countBits(row.lanes(digit) & below);
        counts.add(row);
    }
    // The waves' counts, digit by digit and wave by wave, scanned: where each wave's keys of each digit start among
    // the group's keys in digit order
    [unroll]
    for (uint i = 0; i < 4; i++)
    {
        uint digit = WaveGetLaneIndex() + size * i;
        if (digit < 16)
        {
            waveDigits[digit * waves + wave] = counts.kept[i];
        }
    }
    GroupMemoryBarrierWithGroupSync();
    uint total = 16 * waves;
    uint run = (total + SORT_THREADS - 1) / SORT_THREADS;
    uint from = min(lane * run, total);
    uint to = min(from + run, total);
    uint sum = 0;
    for (uint i = from; i < to; i++)
    {
        sum += waveDigits[i];
    }
    uint offset = exclusiveSum(sum, lane);
    for (uint i = from; i < to; i++)
    {
        uint count = waveDigits[i];
        waveDigits[i] = offset;
        offset += count;
    }
    GroupMemoryBarrierWithGroupSync();
    uint* histogram = push.histogram;
    uint2* target = push.target;
    for (uint k = 0; k < SORT_KEYS; k++)
    {
        if (first + k * size < n)
        {
            // Its digit's first place in the whole, then its place among the group's keys of its digit
            uint digit = (items[k].x >> shift) & 15;
            uint at = waveDigits[digit * waves + wave] + ranks[k] - waveDigits[digit * waves];
            target[histogram[digit * groups() + g] + at] = items[k];
        }
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
