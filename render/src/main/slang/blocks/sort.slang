// A radix sort of pairs of a 32-bit key and a value, stable, 4 bits a pass, FidelityFX-style, its sizes read from the
// GPU (an indirect sort: any pass before writes the count): word 3 of `args` is the key count, words 0 to 2 the
// dispatch over its groups of SORT_GROUP keys (SORT_KEYS a thread; rows of up to 65,535 groups). sortCountMain counts
// each group's digits, sortScanMain (one group) scans the counts digit by digit over the groups, and sortScatterMain
// ranks each key among the keys of its digit before it in its group (wave ballots of the digit's bits, then the waves'
// counts scanned) and writes it straight where the scan says, which keeps the group's order. Passes from the lowest
// digit up, ping-ponging between two buffers, sort the keys; RadixSort adds them to a frame graph.
import blocks.radix;

struct SortPush
{
    uint* args;                    // the dispatch (3 words), then the key count
    uint2* source;                 // the keys and their values, read by the pass
    uint2* target;                 // written by the pass
    uint* histogram;               // per digit and group: keys, then where they go (16 words a group)
    uint shift;                    // the pass's digit: bits from the key's lowest
};

[[vk::push_constant]]
ConstantBuffer<SortPush> push;

/** The fewest lanes a wave has: waves of 4 to 128 lanes rank keys alike. */
static const uint LEAST_WAVE = 4;

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
