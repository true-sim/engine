// The particle simulation (effects.md §2.4), once a frame over every GPU emitter instance of the frame's list, in 64-slot
// chunks of one emitter each. countMain counts each chunk's survivors (age + dt < lifetime, not flagged dead);
// scanMain gives each chunk its survivors' first slot, an exclusive scan per emitter; updateMain moves each survivor on
// by the emitter's step under its updaters and writes it, in order, into the other half of the pool (the survivor copy:
// spawn order kept across chunks); spawnArgsMain clamps each emitter's spawn demand to its room (overflow counted) and
// sizes the spawn dispatch; spawnMain makes the newborn, a thread each, from the spawn records: initializers, placed
// along the emitter's motion by birth time, moved on by their age; argsMain keeps each emitter's count for the next
// frame and the statistics. A module program is one emitter's initializers and updaters as words (ModulePrograms);
// their curve keys sit in a block per effect, which the update keeps in group-shared memory per chunk. The same
// arithmetic as scene-effects' CpuSimulation, the reference the tests compare against.
import particle;

struct ParticlePush
{
    Particle* source;              // the half of the pool the frame reads; it writes the other (targetDelta)
    uint4* sideSource;             // side arrays (attributes kept from birth), by halves likewise
    ParticleEmitter* emitters;     // the frame's list
    uint* chunks;                  // per chunk: its emitter's place in the list
    uint2* chunkCounts;            // per chunk: survivors, then the first slot they go to
    uint* state;                   // STATE_WORDS words per emitter instance
    ParticleSpawn* spawns;         // the frame's spawn records
    uint* programs;                // module programs
    float* curves;                 // curve keys
    float* parameters;             // the frame's user parameter blocks
    uint* statistics;              // STATISTICS_HEADER words, then ALIVE and OVERFLOW per instance
    uint* dispatch;                // the spawn dispatch's group counts
    Particle* uploads;             // the frame's CPU emitters' particles (uploadMain)
    uint* cpu;                     // the CPU emitters' places in the list (uploadMain)
    int targetDelta;               // particles from the half read to the half written
    int sideDelta;                 // side units likewise
    uint emitterCount;
    uint chunkCount;
};

[[vk::push_constant]]
ConstantBuffer<ParticlePush> push;

groupshared float cache[CURVE_CACHE];
groupshared uint shared[CHUNK];
groupshared int bounds[6];

/** Curve keys from the group's copy of the emitter's block. */
struct CurveCache : ICurveKeys
{
    float at(uint i)
    {
        return cache[i];
    }
};

/** The chunk of this group: dispatches are rows of up to 65,535 groups. */
uint chunkOf(uint3 group)
{
    return group.x + 65535 * group.y;
}

/** What a particle's modules read: its emitter, its effect's parameters and program. */
struct Emitting
{
    ParticleEmitter emitter;
    float* parameters;
    uint* program;
};

Emitting emitting(ParticleEmitter e)
{
    Emitting m;
    m.emitter = e;
    float* parameters = push.parameters;
    m.parameters = parameters + e.parameters;
    uint* programs = push.programs;
    m.program = programs + e.program;
    return m;
}

/** Float field `f` of the op at `op` for particle `p`: its binding evaluated (CpuSimulation.value). */
float value<K : ICurveKeys>(Emitting m, K keys, uint op, uint salt, uint f, Particle p)
{
    uint at = op + 2 + BINDING_WORDS * f;
    uint head = m.program[at];
    uint kind = head & 0xff;
    float scale = asfloat(m.program[at + 1]);
    uint a = m.program[at + 2];
    switch (kind)
    {
    case BIND_CONSTANT:
        return asfloat(a);
    case BIND_PARAMETER:
        return m.parameters[head >> 8] * scale;
    case BIND_AGE:
        return curve(keys, a, p.age / p.lifetime) * scale;
    case BIND_PARAMETER_CURVE:
        return curve(keys, a, m.parameters[head >> 8]) * scale;
    case BIND_SPEED:
        return curve(keys, a, sqrt(dot(p.velocity, p.velocity))) * scale;
    default:
        float low = asfloat(a);
        return (low + (asfloat(m.program[at + 3]) - low) * random(p.seed, salt + f)) * scale;
    }
}

/** The color of the op at `op` for particle `p` (CpuSimulation.color). */
float4 colorOf<K : ICurveKeys>(Emitting m, K keys, uint op, uint salt, Particle p)
{
    uint at = op + 2;
    uint head = m.program[at];
    switch (head & 0xff)
    {
    case COLOR_CONSTANT:
        return float4(asfloat(m.program[at + 1]), asfloat(m.program[at + 2]), asfloat(m.program[at + 3]),
            asfloat(m.program[at + 4]));
    case COLOR_PARAMETER:
        uint o = head >> 8;
        return float4(m.parameters[o], m.parameters[o + 1], m.parameters[o + 2], m.parameters[o + 3]);
    case COLOR_AGE:
        return gradient(keys, m.program[at + 1], p.age / p.lifetime);
    default:
        return gradient(keys, m.program[at + 1], random(p.seed, salt));
    }
}

/** A unit direction uniform on the sphere, from two draws from `salt` on. */
float3 randomDirection(uint seed, uint salt)
{
    float y = 1 - 2 * random(seed, salt);
    float r = sqrt(max(0.0, 1 - y * y));
    float turn = TAU * random(seed, salt + 1);
    return float3(r * cos(turn), y, r * sin(turn));
}

/** A position on a disc of `radius` in the XZ plane, uniform by area. */
float3 disc(float radius, uint seed, uint salt)
{
    float rho = radius * sqrt(random(seed, salt + 8));
    float turn = TAU * random(seed, salt + 9);
    return float3(rho * cos(turn), 0, rho * sin(turn));
}

/** The op words' start of the program's `index`-th op from `op` on. */
uint skip(Emitting m, uint op)
{
    return op + (m.program[op] >> 8);
}

/** Runs the initializers on newborn `p` (CpuSimulation.init), its direction scratch kept across them. */
void initialize<K : ICurveKeys>(Emitting m, K keys, inout Particle p, inout float4 color0, inout float size0)
{
    uint header = m.program[0];
    uint count = header & 0xff;
    uint op = 1;
    float3 direction = 0;
    for (uint i = 0; i < count; i++)
    {
        uint code = m.program[op] & 0xff;
        uint salt = m.program[op + 1];
        uint seed = p.seed;
        switch (code)
        {
        case OP_POINT:
            p.position = 0;
            direction = randomDirection(seed, salt + 8);
            break;
        case OP_SPHERE:
            float radius = value(m, keys, op, salt, 0, p);
            float thickness = value(m, keys, op, salt, 1, p);
            direction = randomDirection(seed, salt + 8);
            float inner = 1 - min(1.0, max(0.0, thickness));
            float u = random(seed, salt + 10);
            float cube = inner * inner * inner + (1 - inner * inner * inner) * u;
            float r = radius * (cube > 0 ? pow(cube, 1.0 / 3.0) : 0.0);
            p.position = direction * r;
            break;
        case OP_BOX:
            p.position = float3((random(seed, salt + 8) - 0.5) * value(m, keys, op, salt, 0, p),
                (random(seed, salt + 9) - 0.5) * value(m, keys, op, salt, 1, p),
                (random(seed, salt + 10) - 0.5) * value(m, keys, op, salt, 2, p));
            direction = randomDirection(seed, salt + 11);
            break;
        case OP_CONE:
            float angle = value(m, keys, op, salt, 0, p);
            p.position = disc(value(m, keys, op, salt, 1, p), seed, salt);
            float c = 1 - random(seed, salt + 10) * (1 - cos(angle));
            float s = sqrt(max(0.0, 1 - c * c));
            float turn = TAU * random(seed, salt + 11);
            direction = float3(s * cos(turn), c, s * sin(turn));
            break;
        case OP_DISC:
            p.position = disc(value(m, keys, op, salt, 0, p), seed, salt);
            direction = float3(0, 1, 0);
            break;
        case OP_LIFETIME:
            p.lifetime = value(m, keys, op, salt, 0, p);
            break;
        case OP_SPEED:
            p.velocity += direction * value(m, keys, op, salt, 0, p);
            break;
        case OP_VELOCITY:
            p.velocity += float3(value(m, keys, op, salt, 0, p), value(m, keys, op, salt, 1, p),
                value(m, keys, op, salt, 2, p));
            break;
        case OP_SIZE:
            size0 = value(m, keys, op, salt, 0, p);
            p.size = size0;
            break;
        default: // OP_COLOR
            color0 = colorOf(m, keys, op, salt, p);
            p.color = color0;
            break;
        }
        op = skip(m, op);
    }
}

/** The first updater's word. */
uint updaters(Emitting m)
{
    uint header = m.program[0];
    uint op = 1;
    for (uint i = 0; i < (header & 0xff); i++)
    {
        op = skip(m, op);
    }
    return op;
}

/**
 * Moves `p` on by `h` seconds under the updaters (CpuSimulation.step): gravity and linear drag integrated exactly, each
 * evaluated at the particle's state before the step, then the attributes over life from those at birth.
 */
void step<K : ICurveKeys>(Emitting m, K keys, uint first, inout Particle p, float4 color0, float size0, float h)
{
    uint count = (m.program[0] >> 8) & 0xff;
    float3 a = 0;
    float k = 0;
    uint op = first;
    bool over = false;
    for (uint i = 0; i < count; i++)
    {
        uint code = m.program[op] & 0xff;
        uint salt = m.program[op + 1];
        if (code == OP_GRAVITY)
        {
            a += m.emitter.down * (GRAVITY * value(m, keys, op, salt, 0, p));
        }
        else if (code == OP_DRAG)
        {
            k += value(m, keys, op, salt, 0, p);
        }
        else
        {
            over = true;
        }
        op = skip(m, op);
    }
    float3 v = p.velocity;
    if (k == 0)
    {
        p.position += v * h + 0.5 * a * h * h;
        p.velocity = v + a * h;
    }
    else
    {
        float decay = exp(-k * h);
        float fade = (1 - decay) / k;
        float3 terminal = a / k;
        p.position += terminal * h + (v - terminal) * fade;
        p.velocity = terminal + (v - terminal) * decay;
    }
    p.age += h;
    if (!over)
    {
        return;
    }
    float t = p.age / p.lifetime;
    float size = size0;
    float4 color = color0;
    bool sized = false;
    bool colored = false;
    op = first;
    for (uint i = 0; i < count; i++)
    {
        uint code = m.program[op] & 0xff;
        if (code == OP_OVER_SIZE)
        {
            size *= curve(keys, m.program[op + 2], t);
            sized = true;
        }
        else if (code == OP_OVER_COLOR)
        {
            color *= gradient(keys, m.program[op + 2], t);
            colored = true;
        }
        op = skip(m, op);
    }
    if (sized)
    {
        p.size = size;
    }
    if (colored)
    {
        p.color = color;
    }
}

/** Grows the group's box by particle `p`'s. */
void bound(Particle p)
{
    float r = 0.5 * max(p.size.x, p.size.y);
    InterlockedMin(bounds[0], ordered(p.position.x - r));
    InterlockedMin(bounds[1], ordered(p.position.y - r));
    InterlockedMin(bounds[2], ordered(p.position.z - r));
    InterlockedMax(bounds[3], ordered(p.position.x + r));
    InterlockedMax(bounds[4], ordered(p.position.y + r));
    InterlockedMax(bounds[5], ordered(p.position.z + r));
}

/** Merges the group's box into its emitter instance's, by its first thread. */
void mergeBounds(uint instance, uint lane)
{
    GroupMemoryBarrierWithGroupSync();
    if (lane == 0 && bounds[0] != 0x7fffffff)
    {
        uint* state = push.state;
        uint at = instance * STATE_WORDS + STATE_BOUNDS;
        for (uint i = 0; i < 3; i++)
        {
            InterlockedMin(state[at + i], uint(bounds[i]) ^ 0x80000000u);
            InterlockedMax(state[at + 3 + i], uint(bounds[3 + i]) ^ 0x80000000u);
        }
    }
}

void resetBounds(uint lane)
{
    if (lane < 6)
    {
        bounds[lane] = lane < 3 ? 0x7fffffff : int(0x80000000u);
    }
}

/** The particles emitter `e` has from the frame before. */
uint aliveOf(ParticleEmitter e)
{
    uint* state = push.state;
    return (e.flags & EMITTER_NEW) != 0 ? 0 : state[e.instance * STATE_WORDS + STATE_ALIVE];
}

/** Whether particle `p` lives through a step of `dt`. */
bool survives(Particle p, float dt)
{
    return p.age + dt < p.lifetime && (p.flags & PARTICLE_DEAD) == 0;
}

[shader("compute")]
[numthreads(64, 1, 1)]
void countMain(uint3 group : SV_GroupID, uint lane : SV_GroupIndex)
{
    uint chunk = chunkOf(group);
    if (chunk >= push.chunkCount)
    {
        return; // the whole group
    }
    if (lane == 0)
    {
        shared[0] = 0;
    }
    GroupMemoryBarrierWithGroupSync();
    uint* chunks = push.chunks;
    ParticleEmitter* emitters = push.emitters;
    ParticleEmitter e = emitters[chunks[chunk]];
    uint local = (chunk - e.chunks) * CHUNK + lane;
    if (local < aliveOf(e))
    {
        Particle* source = push.source;
        if (survives(source[e.first + local], e.dt))
        {
            InterlockedAdd(shared[0], 1);
        }
    }
    GroupMemoryBarrierWithGroupSync();
    if (lane == 0)
    {
        uint2* counts = push.chunkCounts;
        counts[chunk] = uint2(shared[0], 0);
    }
}

[shader("compute")]
[numthreads(64, 1, 1)]
void scanMain(uint3 group : SV_GroupID, uint lane : SV_GroupIndex)
{
    // A group per emitter: each thread sums a run of its chunks, the runs' sums are scanned, then each thread writes
    // its chunks' first slots.
    uint index = chunkOf(group);
    if (index >= push.emitterCount)
    {
        return;
    }
    ParticleEmitter* emitters = push.emitters;
    ParticleEmitter e = emitters[index];
    uint2* counts = push.chunkCounts;
    uint n = (e.capacity + CHUNK - 1) / CHUNK;
    uint run = (n + CHUNK - 1) / CHUNK;
    uint from = min(lane * run, n);
    uint to = min(from + run, n);
    uint sum = 0;
    for (uint c = from; c < to; c++)
    {
        sum += counts[e.chunks + c].x;
    }
    shared[lane] = sum;
    GroupMemoryBarrierWithGroupSync();
    if (lane == 0)
    {
        uint total = 0;
        for (uint i = 0; i < CHUNK; i++)
        {
            uint s = shared[i];
            shared[i] = total;
            total += s;
        }
        uint* state = push.state;
        uint at = e.instance * STATE_WORDS;
        bool cpu = (e.flags & EMITTER_CPU) != 0;
        state[at + STATE_SURVIVORS] = cpu ? e.demand : total; // a CPU emitter: what it uploads
        if ((e.flags & EMITTER_NEW) != 0)
        {
            state[at + STATE_OVERFLOW] = 0;
        }
        for (uint b = 0; b < 3; b++)
        {
            bool empty = !cpu || e.demand == 0;
            state[at + STATE_BOUNDS + b] = empty ? 0xffffffffu : uint(ordered(e.position[b])) ^ 0x80000000u;
            state[at + STATE_BOUNDS + 3 + b] = empty ? 0 : uint(ordered(e.previous[b])) ^ 0x80000000u;
        }
    }
    GroupMemoryBarrierWithGroupSync();
    uint offset = shared[lane];
    for (uint c = from; c < to; c++)
    {
        uint count = counts[e.chunks + c].x;
        counts[e.chunks + c] = uint2(count, offset);
        offset += count;
    }
}

[shader("compute")]
[numthreads(64, 1, 1)]
void updateMain(uint3 group : SV_GroupID, uint lane : SV_GroupIndex)
{
    uint chunk = chunkOf(group);
    if (chunk >= push.chunkCount)
    {
        return; // the whole group
    }
    uint* chunks = push.chunks;
    ParticleEmitter* emitters = push.emitters;
    ParticleEmitter e = emitters[chunks[chunk]];
    Emitting m = emitting(e);
    // The emitter's curve keys into group-shared memory, if they fit.
    bool cached = e.curveFloats <= CURVE_CACHE;
    float* curves = push.curves;
    float* block = curves + e.curves;
    if (cached)
    {
        for (uint i = lane; i < e.curveFloats; i += CHUNK)
        {
            cache[i] = block[i];
        }
    }
    resetBounds(lane);
    uint local = (chunk - e.chunks) * CHUNK + lane;
    Particle p;
    bool keep = false;
    if (local < aliveOf(e))
    {
        Particle* source = push.source;
        p = source[e.first + local];
        keep = survives(p, e.dt);
    }
    shared[lane] = keep ? 1 : 0;
    GroupMemoryBarrierWithGroupSync();
    uint rank = 0;
    for (uint i = 0; i < lane; i++)
    {
        rank += shared[i];
    }
    if (keep)
    {
        uint2* counts = push.chunkCounts;
        uint to = e.first + counts[chunk].y + rank;
        float4 color0 = 1;
        float size0 = 0.1;
        bool side = e.side != NO_SIDE;
        if (side)
        {
            uint4* sideSource = push.sideSource;
            uint s = e.side + local * e.sideUnits;
            if (((m.program[0] >> 16) & PROGRAM_OVER) != 0)
            {
                color0 = asfloat(sideSource[s]);
                size0 = asfloat(sideSource[s + 1].x);
            }
            uint4* sideTarget = sideSource + push.sideDelta;
            uint t = e.side + (to - e.first) * e.sideUnits;
            for (uint u = 0; u < e.sideUnits; u++)
            {
                sideTarget[t + u] = sideSource[s + u];
            }
        }
        uint first = updaters(m);
        if (cached)
        {
            CurveCache keys;
            step(m, keys, first, p, color0, size0, e.dt);
        }
        else
        {
            CurveBuffer keys;
            keys.block = block;
            step(m, keys, first, p, color0, size0, e.dt);
        }
        Particle* source0 = push.source;
        Particle* target = source0 + push.targetDelta;
        target[to] = p;
        bound(p);
        uint header = m.program[0];
        if (((header >> 16) & PROGRAM_TRAIL) != 0)
        {
            // The trail's ring: one more past position, over its oldest
            uint4* sideSource0 = push.sideSource;
            uint4* trail = sideSource0 + push.sideDelta + e.side + (to - e.first) * e.sideUnits
                + (((header >> 16) & PROGRAM_OVER) != 0 ? 2 : 0);
            uint points = header >> 24;
            uint head = (trail[0].x + 1) % points;
            trail[0] = uint4(head, 0, 0, 0);
            trail[1 + head] = uint4(asuint(p.position), 0);
        }
    }
    mergeBounds(e.instance, lane);
}

[shader("compute")]
[numthreads(64, 1, 1)]
void spawnArgsMain(uint lane : SV_GroupIndex)
{
    // One group: each emitter's newborn are its demand within its room, and their first place among the frame's is an
    // exclusive scan over the list.
    uint n = push.emitterCount;
    uint run = (n + CHUNK - 1) / CHUNK;
    uint from = min(lane * run, n);
    uint to = min(from + run, n);
    ParticleEmitter* emitters = push.emitters;
    uint* state = push.state;
    uint sum = 0;
    uint dropped = 0;
    uint survived = 0;
    for (uint i = from; i < to; i++)
    {
        ParticleEmitter e = emitters[i];
        uint at = e.instance * STATE_WORDS;
        uint survivors = state[at + STATE_SURVIVORS];
        uint spawned = (e.flags & EMITTER_CPU) != 0 ? 0 : min(e.demand, e.capacity - survivors);
        state[at + STATE_SPAWNED] = spawned;
        uint demand = (e.flags & EMITTER_CPU) != 0 ? 0 : e.demand;
        state[at + STATE_OVERFLOW] += demand - spawned;
        dropped += demand - spawned;
        survived += survivors;
        sum += spawned;
    }
    shared[lane] = sum;
    GroupMemoryBarrierWithGroupSync();
    if (lane == 0)
    {
        uint total = 0;
        for (uint i = 0; i < CHUNK; i++)
        {
            uint s = shared[i];
            shared[i] = total;
            total += s;
        }
        uint* dispatch = push.dispatch;
        uint groups = (total + 63) / 64;
        dispatch[0] = min(groups, 65535u);
        dispatch[1] = (groups + 65534) / 65535;
        dispatch[2] = 1;
        dispatch[3] = total;
        uint* statistics = push.statistics;
        statistics[STATISTICS_SPAWNED] = total;
        statistics[STATISTICS_EMITTERS] = n;
        statistics[STATISTICS_ALIVE] = 0;
        statistics[STATISTICS_OVERFLOW] = 0;
    }
    GroupMemoryBarrierWithGroupSync();
    uint offset = shared[lane];
    for (uint i = from; i < to; i++)
    {
        uint at = emitters[i].instance * STATE_WORDS;
        state[at + STATE_SPAWN_OFFSET] = offset;
        offset += state[at + STATE_SPAWNED];
    }
    uint* statistics = push.statistics;
    InterlockedAdd(statistics[STATISTICS_ALIVE], survived + sum);
    InterlockedAdd(statistics[STATISTICS_OVERFLOW], dropped);
}

/**
 * Places newborn `p` in its space (CpuSimulation.place): world-space ones where the emitter was at its birth, along
 * the frame's motion, turned by its rotation, or at a burst call's position facing its normal; local-space ones stay in
 * the emitter's frame.
 */
void place(ParticleEmitter e, ParticleSpawn r, inout Particle p, float age)
{
    float4 q;
    float3 at;
    if ((r.flags & SPAWN_BURST) != 0)
    {
        float3 n = r.burstNormal;
        float w = 1 + n.y;
        q = w < 1e-6 ? float4(1, 0, 0, 0) : float4(n.z, 0, -n.x, w) / sqrt(w * w + n.z * n.z + n.x * n.x);
        at = r.burstPosition;
    }
    else if ((e.flags & EMITTER_LOCAL) != 0)
    {
        return;
    }
    else
    {
        q = e.rotation;
        float along = e.frame > 0 ? clamp(1 - age / e.frame, 0.0, 1.0) : 1.0;
        at = e.previous + (e.position - e.previous) * along;
    }
    p.position = at + turn(q, p.position);
    p.velocity = turn(q, p.velocity);
}

[shader("compute")]
[numthreads(64, 1, 1)]
void spawnMain(uint3 id : SV_DispatchThreadID)
{
    uint t = id.x + 65535 * 64 * id.y;
    uint* dispatch = push.dispatch;
    if (t >= dispatch[3])
    {
        return;
    }
    // The emitter: the last whose first newborn is at or before t.
    ParticleEmitter* emitters = push.emitters;
    uint* state = push.state;
    uint low = 0;
    uint high = push.emitterCount - 1;
    while (low < high)
    {
        uint middle = (low + high + 1) >> 1;
        if (state[emitters[middle].instance * STATE_WORDS + STATE_SPAWN_OFFSET] <= t)
        {
            low = middle;
        }
        else
        {
            high = middle - 1;
        }
    }
    ParticleEmitter e = emitters[low];
    uint at = e.instance * STATE_WORDS;
    uint k = t - state[at + STATE_SPAWN_OFFSET];
    // Its record: the last whose particles start at or before k.
    ParticleSpawn* spawns = push.spawns;
    uint r = e.records;
    while (r + 1 < e.records + e.recordCount && spawns[r + 1].before <= k)
    {
        r++;
    }
    ParticleSpawn record = spawns[r];
    uint j = k - record.before;
    float age = record.age - float(j) * record.interval;
    Particle p;
    p.position = 0;
    p.velocity = 0;
    p.age = 0;
    p.lifetime = 1;
    p.color = 1;
    p.size = 0.1;
    p.rotation = 0;
    p.spawnIndex = record.firstIndex + j;
    p.seed = combine(e.seed, p.spawnIndex);
    p.flags = (record.flags & SPAWN_PREWARM) != 0 ? PARTICLE_PREWARM : 0;
    p.custom = 0;
    float4 color0 = 1;
    float size0 = 0.1;
    Emitting m = emitting(e);
    float* curves = push.curves;
    CurveBuffer keys;
    keys.block = curves + e.curves;
    initialize(m, keys, p, color0, size0);
    if (age >= p.lifetime)
    {
        p.flags |= PARTICLE_DEAD; // dead before the frame ends: takes its slot until the next count
    }
    else
    {
        place(e, record, p, age);
        step(m, keys, updaters(m), p, color0, size0, age);
    }
    uint slot = state[at + STATE_SURVIVORS] + k;
    Particle* source0 = push.source;
    Particle* target = source0 + push.targetDelta;
    target[e.first + slot] = p;
    if (e.side != NO_SIDE)
    {
        uint4* sideSource0 = push.sideSource;
        uint4* sideTarget = sideSource0 + push.sideDelta;
        uint s = e.side + slot * e.sideUnits;
        uint header = m.program[0];
        if (((header >> 16) & PROGRAM_OVER) != 0)
        {
            sideTarget[s] = asuint(color0);
            sideTarget[s + 1] = uint4(asuint(size0), 0, 0, 0);
        }
        if (((header >> 16) & PROGRAM_TRAIL) != 0)
        {
            // A trail starts where its particle is: no streak back to the origin
            uint4* trail = sideTarget + s + (((header >> 16) & PROGRAM_OVER) != 0 ? 2 : 0);
            trail[0] = uint4(0, 0, 0, 0);
            for (uint i = 0; i < (header >> 24); i++)
            {
                trail[1 + i] = uint4(asuint(p.position), 0);
            }
        }
    }
    if ((p.flags & PARTICLE_DEAD) == 0)
    {
        float r2 = 0.5 * max(p.size.x, p.size.y);
        uint b = at + STATE_BOUNDS;
        InterlockedMin(state[b], uint(ordered(p.position.x - r2)) ^ 0x80000000u);
        InterlockedMin(state[b + 1], uint(ordered(p.position.y - r2)) ^ 0x80000000u);
        InterlockedMin(state[b + 2], uint(ordered(p.position.z - r2)) ^ 0x80000000u);
        InterlockedMax(state[b + 3], uint(ordered(p.position.x + r2)) ^ 0x80000000u);
        InterlockedMax(state[b + 4], uint(ordered(p.position.y + r2)) ^ 0x80000000u);
        InterlockedMax(state[b + 5], uint(ordered(p.position.z + r2)) ^ 0x80000000u);
    }
}

[shader("compute")]
[numthreads(64, 1, 1)]
void argsMain(uint3 id : SV_DispatchThreadID)
{
    if (id.x >= push.emitterCount)
    {
        return;
    }
    ParticleEmitter* emitters = push.emitters;
    uint* state = push.state;
    uint instance = emitters[id.x].instance;
    uint at = instance * STATE_WORDS;
    uint alive = state[at + STATE_SURVIVORS] + state[at + STATE_SPAWNED];
    state[at + STATE_ALIVE] = alive;
    uint* statistics = push.statistics;
    statistics[STATISTICS_HEADER + 2 * instance] = alive;
    statistics[STATISTICS_HEADER + 2 * instance + 1] = state[at + STATE_OVERFLOW];
}

[shader("compute")]
[numthreads(64, 1, 1)]
void uploadMain(uint3 id : SV_DispatchThreadID, uint3 group : SV_GroupID)
{
    // A row of groups per CPU emitter: its uploaded particles into its range of the half the frame writes.
    uint* cpu = push.cpu;
    ParticleEmitter* emitters = push.emitters;
    ParticleEmitter e = emitters[cpu[group.y]];
    if (id.x >= e.demand)
    {
        return;
    }
    Particle* uploads = push.uploads;
    Particle* source0 = push.source;
    Particle* target = source0 + push.targetDelta;
    target[e.first + id.x] = uploads[e.records + id.x];
}
