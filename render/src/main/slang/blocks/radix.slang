// The radix sort's shared parts (blocks/sort.slang, and any shader ranking keys by 4-bit digits in a group): its sizes,
// and wave helpers that count each digit's keys in a wave with ballots, for waves of 4 to 128 lanes.
module radix;

/** Keys a sort's groups take each: SORT_KEYS per thread. */
public static const uint SORT_GROUP = 1024;
/** Threads of a sort's groups. */
public static const uint SORT_THREADS = 256;
/** Keys each thread of a sort's groups takes. */
public static const uint SORT_KEYS = 4;

/** The set bits of a ballot's mask. */
public uint countBits(uint4 mask)
{
    return countbits(mask.x) + countbits(mask.y) + countbits(mask.z) + countbits(mask.w);
}

/** The lanes of the wave below this one, as a ballot's mask. */
public uint4 lanesBelow()
{
    uint lane = WaveGetLaneIndex();
    uint4 below;
    [unroll]
    for (uint i = 0; i < 4; i++)
    {
        uint low = 32 * i;
        below[i] = lane >= low + 32 ? 0xffffffffu : lane > low ? (1u << (lane - low)) - 1 : 0;
    }
    return below;
}

/**
 * A row of a wave's sort keys, one a lane, as ballots: the lanes holding a key, and those whose key's 4-bit digit has
 * each bit set. Any lane can tell from them which lanes hold a digit, and so how many; waves of up to 128 lanes.
 */
public struct DigitBallots
{
    public uint4 held;
    public uint4 bits[4];

    /** The lanes holding a key of digit `digit`. */
    public uint4 lanes(uint digit)
    {
        uint4 m = held;
        [unroll]
        for (uint b = 0; b < 4; b++)
        {
            m &= ((digit >> b) & 1) != 0 ? bits[b] : ~bits[b];
        }
        return m;
    }
};

/** The row of this lane's key of digit `digit` (when `held`): every lane of the wave calls it together. */
public DigitBallots digitBallots(bool held, uint digit)
{
    DigitBallots r;
    r.held = WaveActiveBallot(held);
    [unroll]
    for (uint b = 0; b < 4; b++)
    {
        r.bits[b] = WaveActiveBallot(((digit >> b) & 1) != 0);
    }
    return r;
}

/**
 * Keys of each digit a wave's rows held so far: lane l keeps digits l, l + n, l + 2n and l + 3n of a wave of n lanes,
 * so waves of 4 lanes or more keep all 16.
 */
public struct DigitCounts
{
    public uint kept[4];

    /** The count of digit `digit`, from the lane keeping it: every lane of the wave calls it together. */
    public uint of(uint digit)
    {
        uint n = WaveGetLaneCount();
        uint count = 0;
        [unroll]
        for (uint i = 0; i < 4; i++)
        {
            if (i * n < 16) // the same for the whole wave
            {
                uint value = WaveReadLaneAt(kept[i], digit % n);
                count = digit / n == i ? value : count;
            }
        }
        return count;
    }

    /** Counts the keys of row `row`. */
    [mutating]
    public void add(DigitBallots row)
    {
        uint n = WaveGetLaneCount();
        uint lane = WaveGetLaneIndex();
        [unroll]
        for (uint i = 0; i < 4; i++)
        {
            uint digit = lane + n * i;
            if (digit < 16)
            {
                kept[i] += countBits(row.lanes(digit));
            }
        }
    }
};

/** No keys counted yet. */
public DigitCounts noDigits()
{
    DigitCounts c;
    [unroll]
    for (uint i = 0; i < 4; i++)
    {
        c.kept[i] = 0;
    }
    return c;
}
