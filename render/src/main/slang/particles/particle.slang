// Particles (scene-effects, effects.md): the pool's records, the emitter and spawn records the CPU writes each frame,
// the module programs and curve keys an effect is encoded into, and what the simulation, CPU emitters and the draws
// share: the hash (bit for bit scene-effects' Hash), curve evaluation and positions in the world. Everything is reached
// by pointer; Particles (engine-render) writes these layouts from their generated Java.
module particle;

import bindless;

/**
 * A particle: 80 bytes (the design's fields; its "64 bytes" miscounts them). Positions are offsets from its emitter's
 * origin (world space) or in the emitter's frame (local space). The pool holds two copies of every range: each frame
 * reads one and writes the survivors, in order, then the newborn into the other.
 */
[HostLayout]
public struct Particle
{
    public float3 position;
    public float age;              // seconds since birth
    public float3 velocity;
    public float lifetime;         // age >= lifetime, or the DEAD flag: dead
    public float4 color;           // linear, premultiplied by the draws
    public float2 size;            // metres across
    public float rotation;
    public uint seed;              // hash of (effect seed, emitter, spawn index): every random value it draws
    public uint flags;             // PARTICLE_*
    public uint spawnIndex;        // its order of birth in its emitter (low 32 bits)
    public float2 custom;          // flipbook frame, ribbon id, user data
};

/**
 * An emitter instance in this frame's list: 144 bytes, written by the CPU each frame. Its particles live in slots
 * [first, first + capacity) of each half of the pool.
 */
[HostLayout]
public struct ParticleEmitter
{
    public uint first;             // its range's first slot (a multiple of CHUNK)
    public uint capacity;          // slots in its range
    public uint program;           // its module program: word offset in the program buffer
    public uint curves;            // its effect's curve block: float offset in the curve buffer
    public uint curveFloats;       // the block's length
    public uint flags;             // EMITTER_*
    public uint side;              // its side range's first 16-byte unit in each half; NO_SIDE: none
    public uint seed;              // the emitter seed: Hash.combine(effect seed, emitter)
    public float3 originHigh;      // where positions are offsets from (world space), or the emitter (local space)
    public float dt;               // this frame's step, clamped
    public float3 originLow;       // the rest of the origin
    public float frame;            // seconds its effect moved on this frame, unclamped: births spread over them
    public float4 rotation;        // the emitter's rotation now, a quaternion
    public float3 position;        // where the emitter is now, from the origin; a CPU emitter's box's least corner
    public uint parameters;        // its effect's user parameters: float offset in the frame's parameter blocks
    public float3 previous;        // where it was at the last update, from the origin; a CPU emitter's box's greatest
    public uint chunks;            // its first entry in the frame's chunk list
    public float3 down;            // world down in its particles' frame, for gravity
    public uint records;           // its first spawn record; a CPU emitter's first uploaded particle
    public uint recordCount;       // how many spawn records it has
    public uint demand;            // particles they ask for; a CPU emitter's uploaded particles
    public uint instance;          // its row of the state buffer (STATE_WORDS words), stable while it lives
    public uint sideUnits;         // 16-byte side units per particle
};

/** A spawn record: 64 bytes, as scene-effects' SpawnRecords, written by the CPU each frame. */
[HostLayout]
public struct ParticleSpawn
{
    public uint emitter;           // its emitter's place in the frame's list
    public uint count;             // particles it spawns
    public uint before;            // particles earlier records of its emitter spawn this frame
    public uint firstIndex;        // the first particle's spawn index; the others follow on
    public float age;              // how old the first is at the end of the frame
    public float interval;         // how much younger each next one is
    public uint flags;             // SPAWN_*
    public uint pad;
    public float3 burstPosition;   // a burst call's position, from the origin (SPAWN_BURST)
    public float pad1;
    public float3 burstNormal;     // and its normal: the emitter's +Y turned onto it
    public float pad2;
};

/**
 * How an emitter's particles are drawn: one of its renderers (scene-effects' Renderer), encoded with its effect's
 * programs. 112 bytes.
 */
[HostLayout]
public struct ParticleRenderer
{
    public uint kind;              // RENDER_*
    public uint facing;            // FACING_*: how billboards turn
    public uint flags;             // DRAW_*
    public float alphaToAdd;       // 0 alpha blended, 1 additive, premultiplied either way
    public float stretch;          // velocity-facing: extra length per metre per second, in sizes
    public float soft;             // metres over which it fades into the opaque scene; 0: a hard edge
    public uint columns;           // a flipbook's frames across its texture
    public uint rows;              // and down
    public float frames;           // frames it plays
    public float rate;             // frames per second of age; 0: the frames over its life
    public uint ribbons;           // ribbons: how many, particle i in ribbon (spawn index % ribbons)
    public uint stride;            // ribbons: every stride-th particle a point
    public float lodDistance;      // ribbons: the stride doubles every time the distance doubles past this; 0: never
    public float widthTail;        // ribbons, trails and beams: the width at the tail, in sizes
    public float widthHead;        // and at the head
    public float tile;             // ribbons and trails: particles (or points) per texture repeat; 0: stretched over
    public uint points;            // trails: past positions kept per particle
    public uint segments;          // beams: segments from end to end
    public float noise;            // beams: how far the noise moves them sideways, in metres
    public float width;            // beams: metres across
    public float length;           // beams: metres along the emitter's +Y without an end given
    public float4 color;           // beams: linear color and alpha
    public uint pad0;
    public uint pad1;
    public uint pad2;
};

/** A draw of one renderer of one emitter instance in a view: 16 bytes, written by the CPU each frame. */
[HostLayout]
public struct ParticleDrawRecord
{
    public uint emitter;           // the emitter's place in the frame's list
    public uint renderer;          // its renderer's word in the program buffer
    public uint texture;           // its texture's sampled slot; NO_TEXTURE: a round sprite
    public uint pad;
};

/** Slots per chunk: the simulation's groups each take one chunk of one emitter. */
public static const uint CHUNK = 64;
/** Floats of an effect's curve keys the update keeps in group-shared memory; longer blocks are read in place. */
public static const uint CURVE_CACHE = 1024;
/** A side range that is not there. */
public static const uint NO_SIDE = 0xffffffffu;

/** Particle flags. */
public static const uint PARTICLE_DEAD = 1;               // leaves at the next count (born dead, or killed)
public static const uint PARTICLE_PREWARM = 2;            // born before its effect started

/** Emitter flags. */
public static const uint EMITTER_NEW = 1;                 // its first frame: no particles yet
public static const uint EMITTER_LOCAL = 2;               // its particles live in its frame
public static const uint EMITTER_CPU = 4;                 // simulated on the CPU: its particles uploaded each frame

/** Spawn record flags. */
public static const uint SPAWN_PREWARM = 1;               // scene-effects' SpawnRecords.PREWARM
public static const uint SPAWN_BURST = 2;                 // at a burst call's position and normal

/** The state buffer: STATE_WORDS words per emitter instance, written by the simulation. */
public static const uint STATE_WORDS = 16;
public static const uint STATE_ALIVE = 0;                 // particles after the frame
public static const uint STATE_SURVIVORS = 1;             // of the frame before
public static const uint STATE_SPAWNED = 2;               // born this frame
public static const uint STATE_SPAWN_OFFSET = 3;          // its first newborn among the frame's
public static const uint STATE_OVERFLOW = 4;              // particles dropped for want of room, since it started
public static const uint STATE_BOUNDS = 8;                // the particles' box: min x, y, z, then max (ordered ints)

/** The statistics buffer: the frame's totals, then ALIVE and OVERFLOW per emitter instance. */
public static const uint STATISTICS_HEADER = 8;
public static const uint STATISTICS_ALIVE = 0;
public static const uint STATISTICS_SPAWNED = 1;
public static const uint STATISTICS_OVERFLOW = 2;         // dropped this frame
public static const uint STATISTICS_EMITTERS = 3;

/** Renderer kinds: scene-effects' Renderer.Kind, in order. */
public static const uint RENDER_BILLBOARD = 0;
public static const uint RENDER_MESH = 1;
public static const uint RENDER_RIBBON = 2;
public static const uint RENDER_TRAIL = 3;
public static const uint RENDER_BEAM = 4;
/** Billboard facings: scene-effects' Renderer.Facing, in order. */
public static const uint FACING_POSITION = 0;
public static const uint FACING_VIEW_PLANE = 1;
public static const uint FACING_VELOCITY = 2;
public static const uint FACING_AXIS = 3;
public static const uint FACING_FREE = 4;
/** Draw flags. */
public static const uint DRAW_VOLUME = 1;                 // the soft fade by the view ray's path through a sphere
public static const uint DRAW_BLEND_FRAMES = 2;           // a flipbook's frames blended
public static const uint DRAW_LOOP = 4;                   // a flipbook played by age loops
/** Words of a renderer record. */
public static const uint RENDERER_WORDS = 28;
/** A draw without a texture. */
public static const uint NO_TEXTURE = 0xffffffffu;

/** Module operations, as ModulePrograms encodes them: initializers, then updaters. */
public static const uint OP_POINT = 0;
public static const uint OP_SPHERE = 1;
public static const uint OP_BOX = 2;
public static const uint OP_CONE = 3;
public static const uint OP_DISC = 4;
public static const uint OP_LIFETIME = 5;
public static const uint OP_SPEED = 6;
public static const uint OP_VELOCITY = 7;
public static const uint OP_SIZE = 8;
public static const uint OP_COLOR = 9;
public static const uint OP_GRAVITY = 10;
public static const uint OP_DRAG = 11;
public static const uint OP_OVER_SIZE = 12;
public static const uint OP_OVER_COLOR = 13;

/** Binding kinds: scene-effects' Binding.Kind and ColorBinding.Kind, in order. */
public static const uint BIND_CONSTANT = 0;
public static const uint BIND_PARAMETER = 1;
public static const uint BIND_AGE = 2;
public static const uint BIND_PARAMETER_CURVE = 3;
public static const uint BIND_SPEED = 4;
public static const uint BIND_RANDOM = 5;
public static const uint COLOR_CONSTANT = 0;
public static const uint COLOR_PARAMETER = 1;
public static const uint COLOR_AGE = 2;
public static const uint COLOR_RANDOM = 3;

/** Words of an encoded float binding, and of a color binding. */
public static const uint BINDING_WORDS = 4;
public static const uint COLOR_WORDS = 5;
/** Program header bits: it has attributes over life, kept from birth in its side range. */
public static const uint PROGRAM_OVER = 1;
/** Program header bits: it keeps a trail of past positions (their count in the header's top 8 bits). */
public static const uint PROGRAM_TRAIL = 2;
/** Curve header bits (above the key count and the stride): keys evenly spaced, indexed directly. */
public static const uint CURVE_EVEN = 1;
/** Interpolations: scene-effects' Interpolation, in order. */
public static const uint KEY_STEP = 0;
public static const uint KEY_LINEAR = 1;
public static const uint KEY_CUBIC = 2;

/** Gravity's acceleration, scene-effects' Forces.G. */
public static const float GRAVITY = 9.81f;
public static const float TAU = 6.2831855f;

// ---------------------------------------------------------------------------------------------- the hash

/** The PCG hash of `value` (scene-effects' Hash.pcg). */
public uint pcg(uint value)
{
    uint state = value * 747796405u + 0xAC564B05u;
    uint word = ((state >> ((state >> 28) + 4u)) ^ state) * 277803737u;
    return (word >> 22) ^ word;
}

/** `seed` and `salt` hashed together (Hash.combine). */
public uint combine(uint seed, uint salt)
{
    return pcg(seed + pcg(salt));
}

/** A random float in [0, 1) for `seed` and `salt` (Hash.random): the top 24 bits, exact in a float. */
public float random(uint seed, uint salt)
{
    return float(combine(seed, salt) >> 8) * (1.0 / 16777216.0);
}

// ---------------------------------------------------------------------------------------------- curves

/**
 * Where curve keys are read from: an effect's block in the curve buffer, or its copy in group-shared memory. A curve at
 * `c` (relative to the block) is a header word (key count, stride << 8, CURVE_* << 16), the times, the values and the
 * slopes (stride floats per key each), and the interpolations (a word per key).
 */
public interface ICurveKeys
{
    public float at(uint i);
}

/** Keys read in place, from the curve buffer. */
public struct CurveBuffer : ICurveKeys
{
    public float* block;

    public float at(uint i)
    {
        return block[i];
    }
};

/** The key segment of `t` in the curve at `c`: -1 before its first key, n - 1 at or after its last (or NaN). */
public int segment<K : ICurveKeys>(K keys, uint c, uint n, uint flags, float t)
{
    float first = keys.at(c + 1);
    if (t < first)
    {
        return -1;
    }
    if (!(t < keys.at(c + n)))
    {
        return int(n) - 1;
    }
    uint low;
    if ((flags & CURVE_EVEN) != 0)
    {
        float h = keys.at(c + 2) - first;
        low = min(uint(max((t - first) / h, 0.0)), n - 2);
        if (keys.at(c + 1 + low) > t)
        {
            low--;
        }
        else if (keys.at(c + 2 + low) <= t)
        {
            low++;
        }
    }
    else if (n <= 4)
    {
        low = 0;
        while (low + 2 < n && keys.at(c + 2 + low) <= t)
        {
            low++;
        }
    }
    else
    {
        low = 0;
        uint high = n - 1;
        while (low < high)
        {
            uint middle = (low + high + 1) >> 1;
            if (keys.at(c + 1 + middle) <= t)
            {
                low = middle;
            }
            else
            {
                high = middle - 1;
            }
        }
    }
    return int(low);
}

/** Component `k` of the curve or gradient at `c` in segment `i` (not its last key), at `t`. */
float interpolate<K : ICurveKeys>(K keys, uint c, uint n, uint stride, uint i, uint k, float t)
{
    uint values = c + 1 + n;
    uint slopes = values + n * stride;
    uint kinds = slopes + n * stride;
    float v0 = keys.at(values + i * stride + k);
    float v1 = keys.at(values + (i + 1) * stride + k);
    float t0 = keys.at(c + 1 + i);
    float h = keys.at(c + 2 + i) - t0;
    float s = (t - t0) / h;
    uint kind = asuint(keys.at(kinds + i));
    if (kind == KEY_STEP)
    {
        return v0;
    }
    if (kind == KEY_LINEAR)
    {
        return v0 + (v1 - v0) * s;
    }
    float s2 = s * s;
    float s3 = s2 * s;
    return (2 * s3 - 3 * s2 + 1) * v0 + (s3 - 2 * s2 + s) * h * keys.at(slopes + i * stride + k)
        + (-2 * s3 + 3 * s2) * v1 + (s3 - s2) * h * keys.at(slopes + (i + 1) * stride + k);
}

/** The float curve at `c` at time `t` (FloatCurve.evaluate). */
public float curve<K : ICurveKeys>(K keys, uint c, float t)
{
    uint header = asuint(keys.at(c));
    uint n = header & 0xff;
    int i = segment(keys, c, n, header >> 16, t);
    if (i < 0)
    {
        return keys.at(c + 1 + n);
    }
    if (i == int(n) - 1)
    {
        return keys.at(c + 1 + n + uint(i));
    }
    return interpolate(keys, c, n, 1, uint(i), 0, t);
}

/** The gradient at `c` at time `t` (ColorGradient.evaluate). */
public float4 gradient<K : ICurveKeys>(K keys, uint c, float t)
{
    uint header = asuint(keys.at(c));
    uint n = header & 0xff;
    int i = segment(keys, c, n, header >> 16, t);
    uint values = c + 1 + n;
    if (i < 0 || i == int(n) - 1)
    {
        uint at = values + 4 * uint(max(i, 0));
        return float4(keys.at(at), keys.at(at + 1), keys.at(at + 2), keys.at(at + 3));
    }
    return float4(interpolate(keys, c, n, 4, uint(i), 0, t), interpolate(keys, c, n, 4, uint(i), 1, t),
        interpolate(keys, c, n, 4, uint(i), 2, t), interpolate(keys, c, n, 4, uint(i), 3, t));
}

// ---------------------------------------------------------------------------------------------- positions

/** `v` turned by the unit quaternion `q`. */
public float3 turn(float4 q, float3 v)
{
    float3 c = 2 * cross(q.xyz, v);
    return v + q.w * c + cross(q.xyz, c);
}

/** An int ordering as `f` does, for atomic minimums and maximums of floats. */
public int ordered(float f)
{
    int i = asint(f);
    return i >= 0 ? i : i ^ 0x7fffffff;
}

/** The float of an ordered int. */
public float unordered(int i)
{
    return asfloat(i >= 0 ? i : i ^ 0x7fffffff);
}

/** The emitter instance's box from the state: min x, y, z, max x, y, z; min above max while it has no particles. */
public void stateBounds(uint* state, uint instance, out float3 low, out float3 high)
{
    uint at = instance * STATE_WORDS + STATE_BOUNDS;
    low = float3(unordered(int(state[at] ^ 0x80000000u)), unordered(int(state[at + 1] ^ 0x80000000u)),
        unordered(int(state[at + 2] ^ 0x80000000u)));
    high = float3(unordered(int(state[at + 3] ^ 0x80000000u)), unordered(int(state[at + 4] ^ 0x80000000u)),
        unordered(int(state[at + 5] ^ 0x80000000u)));
}

/**
 * A position of emitter `e`'s particles (offsets from its origin, or in its frame for local space) relative to a
 * camera at `high` + `low`: the origin's high parts and the rest apart, so floats near the camera stay precise.
 */
public float3 relativeTo(ParticleEmitter e, float3 p, float3 high, float3 low)
{
    precise float3 h = e.originHigh - high;
    precise float3 l = e.originLow - low;
    float3 offset = (e.flags & EMITTER_LOCAL) != 0 ? turn(e.rotation, p) : p;
    return (h + l) + offset;
}
