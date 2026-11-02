// The particle simulation (effects.md §2.4), once a frame over every GPU emitter instance of the frame's list, in 64-slot
// chunks of one emitter each. countMain counts each chunk's survivors (age + dt < lifetime, not flagged dead);
// scanMain gives each chunk its survivors' first slot, an exclusive scan per emitter; updateMain moves each survivor on
// by the emitter's step under its updaters and writes it, in order, into the other half of the pool (the survivor copy:
// spawn order kept across chunks); spawnArgsMain clamps each emitter's spawn demand to its room (overflow counted) and
// sizes the spawn dispatch; spawnMain makes the newborn, a thread each, from the spawn records: initializers, placed
// along the emitter's motion by birth time, moved on by their age; argsMain keeps each emitter's count for the next
// frame and the statistics. A module program is one emitter's initializers and updaters as words (ModulePrograms);
// their curve keys sit in a block per effect, which the update keeps in group-shared memory per chunk. The same
// arithmetic as scene-effects' CpuSimulation, the reference the tests compare against. Mesh shapes read the geometry
// pool (a skinned mesh's posed copy, after the deformation pass) through the frame's emission sources and alias
// tables; bone shapes the joints the CPU wrote there; masks sample a texture where the shapes put a particle.
import particles.particle;
import bindless;
import meshlets.common;

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
    ParticleFrame* frame;          // colliders, the height field, the collision depth, the CPU emitters' uploads
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
groupshared uint2 smallKeys[EVENT_SMALL];         // the small event sort's keys
groupshared uint waveDigits[16 * SORT_THREADS / 4]; // its waves' counts of each digit (waves of 4 lanes or more)
groupshared uint keyBits[2];                      // the bits any of its keys has, and those all have
groupshared uint frameBuckets[LIGHT_BUCKETS];     // the light selection's frame histogram

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

/** The float binding at word `at` of the program for particle `p`, with its salt (CpuSimulation.value). */
float value<K : ICurveKeys>(Emitting m, K keys, uint at, uint salt, Particle p)
{
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
        return (low + (asfloat(m.program[at + 3]) - low) * random(p.seed, salt)) * scale;
    }
}

/** The color binding at word `at` of the program for particle `p` (CpuSimulation.color). */
float4 colorOf<K : ICurveKeys>(Emitting m, K keys, uint at, uint salt, Particle p)
{
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

// The modules' dispatch (E24): their opcodes and a function per stage switching over them, generated by the build
// from the [ParticleModule] structs of particles/modules and of the game whose kernel variant this is.
#include "particle-modules/dispatch.slang"

/** The word of the op after the one at `op`: an op word is its opcode | its words << 8 | its stages << 16. */
uint skip(Emitting m, uint op)
{
    return op + ((m.program[op] >> 8) & 0xff);
}

/** Runs the initializers on newborn `p` (CpuSimulation.init), what they share kept across them. */
void initialize<K : ICurveKeys>(Emitting m, K keys, inout Particle p, inout float4 color0, inout float size0)
{
    ParticleInit s;
    s.emitter = m.emitter;
    s.frame = push.frame;
    s.salt = 0;
    s.direction = 0;
    s.uv = 0;
    s.textured = false;
    s.masked = false;
    s.color0 = color0;
    s.size0 = size0;
    uint count = m.program[0] & 0xff;
    uint op = 1;
    for (uint i = 0; i < count; i++)
    {
        initModule(m, keys, op, m.program[op] & 0xff, s, p);
        op = skip(m, op);
    }
    color0 = s.color0;
    size0 = s.size0;
    if (s.masked)
    {
        p.lifetime = 0; // dead at birth
    }
}

/** The word of the program's event list (its count, then EVENT_WORDS per event), after its updaters. */
uint eventList(Emitting m, uint first)
{
    uint op = first;
    for (uint i = 0; i < ((m.program[0] >> 8) & 0xff); i++)
    {
        op = skip(m, op);
    }
    return op;
}

/**
 * Sends particle `p`'s events of phase `phase` (0: birth and death, as it was; 1: periodic and collision, after its
 * step, its age crossing a multiple of the interval from `before`), from slot `local` of emitter `index`'s range in
 * the half read: each with its chance and count, appended to the frame's events with its sort key (CpuSimulation.send).
 */
void send<K : ICurveKeys>(Emitting m, K keys, uint list, uint index, uint local, Particle p, uint phase, bool birth,
    bool death, bool collision, float before, uint emitAt)
{
    ParticleFrame* frame = push.frame;
    uint count = m.program[list];
    for (uint k = 0; k < min(count, MAX_EVENTS); k++)
    {
        uint at = list + 1 + k * EVENT_WORDS;
        uint kind = m.program[at] & 0xff;
        float interval = asfloat(m.program[at + 7]);
        bool sent = kind == EVENT_BIRTH ? birth : kind == EVENT_DEATH ? death : kind == EVENT_COLLISION ? collision
            : kind == EVENT_PERIODIC && phase == 1 && floor(p.age / interval) > floor(before / interval);
        uint salt = m.program[at + 1];
        bool emits = kind == EVENT_EMIT && phase == 1 && emitAt != NO_SIDE;
        if (!(sent || emits) || !(random(p.seed, salt + 15) < asfloat(m.program[at + 6])))
        {
            continue;
        }
        float rate = value(m, keys, at + 2, salt, p);
        uint children;
        if (emits)
        {
            // Per second, carried in fixed point per particle (CpuSimulation.send): what its step owes, spread over it
            uint slot = 0;
            for (uint j = 0; j < k; j++)
            {
                slot += (m.program[list + 1 + j * EVENT_WORDS] & 0xff) == EVENT_EMIT ? 1 : 0;
            }
            uint4* sides = push.sideSource;
            uint4* written = sides + push.sideDelta;
            uint owed = written[emitAt][slot] + uint(max(0.0, rate * (p.age - before) * float(1u << EMIT_BITS)));
            children = min(owed >> EMIT_BITS, MAX_CHILDREN);
            written[emitAt][slot] = owed - (children << EMIT_BITS);
        }
        else
        {
            children = uint(clamp(round(rate), 0.0, float(MAX_CHILDREN)));
        }
        uint* targets = frame.targets;
        uint target = targets[m.emitter.targets + (m.program[at] >> 8)];
        if (children == 0 || target == NO_EMITTER)
        {
            continue;
        }
        uint* args = frame.eventArgs;
        uint slot;
        InterlockedAdd(args[4], 1, slot);
        if (slot >= EVENT_CAPACITY)
        {
            continue; // counted: sent, not kept
        }
        ParticleEmitter e = m.emitter;
        bool space = (e.flags & EMITTER_LOCAL) != 0; // local space: turned into world axes
        ParticleEventRecord r;
        r.target = target;
        r.count = children;
        r.seed = p.seed;
        r.salt = salt | (emits ? EVENT_SPREAD : 0);
        r.parentIndex = p.spawnIndex;
        r.step = emits ? p.age - before : 0;
        r.position = space ? turn(e.rotation, p.position) : p.position;
        r.parent = index;
        r.velocity = space ? turn(e.rotation, p.velocity) : p.velocity;
        r.size = p.size.x;
        r.color = p.color;
        ParticleEventRecord* events = (ParticleEventRecord*) frame.events;
        events[slot] = r;
        uint2* eventKeys = frame.eventKeys;
        eventKeys[slot] = uint2(local << 4 | phase << 3 | k, slot);
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
 * Moves `p` on by `h` seconds under the updaters (CpuSimulation.step), by stage: the forces summed (gravity, linear
 * drag and wind integrated exactly; those that depend on the position from where it is at the step's start), then
 * after the motion (orbits, speed limits), collision (the first method of each chain the GPU has; the first hit
 * responds; stuck particles only age), the game's own updaters, then the attributes over life from those at birth.
 */
void step<K : ICurveKeys>(Emitting m, K keys, uint first, inout Particle p, float4 color0, float size0, float h)
{
    uint count = (m.program[0] >> 8) & 0xff;
    ParticleEmitter e = m.emitter;
    p.flags &= ~PARTICLE_COLLIDED;
    bool stuck = (p.flags & PARTICLE_STUCK) != 0; // frozen where it hit: it only ages
    bool local = (e.flags & EMITTER_LOCAL) != 0;
    ParticleStep s;
    s.emitter = e;
    s.frame = push.frame;
    s.salt = 0;
    s.h = h;
    // Where the emitter is in its particles' space: its frame's origin, or its offset from theirs
    s.center = local ? 0 : e.position;
    s.start = p.position;
    s.a = 0;
    s.k = 0;
    s.kill = false;
    s.from = 0;
    s.to = 0;
    s.color = color0;
    s.size = size0;
    s.colored = false;
    s.sized = false;
    uint stages = 0; // of all its updaters
    uint op = first;
    for (uint i = 0; i < count; i++)
    {
        uint word = m.program[op];
        stages |= word >> 16;
        forceModule(m, keys, op, word & 0xff, s, p);
        op = skip(m, op);
    }
    if (stuck)
    {
        s.a = 0;
        s.k = 0;
        stages &= ~(STAGE_AFTER | STAGE_COLLIDE);
        p.velocity = 0;
    }
    float3 v = p.velocity;
    if (s.k == 0)
    {
        p.position += v * h + 0.5 * s.a * h * h;
        p.velocity = v + s.a * h;
    }
    else
    {
        float decay = exp(-s.k * h);
        float fade = (1 - decay) / s.k;
        float3 terminal = s.a / s.k;
        p.position += terminal * h + (v - terminal) * fade;
        p.velocity = terminal + (v - terminal) * decay;
    }
    p.age += h;
    if ((e.flags & EMITTER_MESH) != 0)
    {
        p.rotation += sqrt(dot(v, v)) * h / max(0.5 * p.size.x, 1e-3); // mesh particles roll as far as they move
    }
    if ((stages & STAGE_AFTER) != 0)
    {
        op = first;
        for (uint i = 0; i < count; i++)
        {
            uint word = m.program[op];
            if (((word >> 16) & STAGE_AFTER) != 0)
            {
                afterModule(m, keys, op, word & 0xff, s, p);
            }
            op = skip(m, op);
        }
    }
    if ((stages & STAGE_COLLIDE) != 0)
    {
        // The move from the step's start, in world axes about the origin (local-space particles turned out and
        // back), against each method the program has; the first hit responds.
        s.from = local ? turn(e.rotation, s.start) : s.start;
        s.to = local ? turn(e.rotation, p.position) : p.position;
        float4 back = float4(-e.rotation.xyz, e.rotation.w);
        op = first;
        for (uint i = 0; i < count; i++)
        {
            uint word = m.program[op];
            if (((word >> 16) & STAGE_COLLIDE) != 0)
            {
                ParticleHit hit = collideModule(m, keys, op, word & 0xff, s, p);
                if (hit.hit)
                {
                    p.flags |= PARTICLE_COLLIDED;
                    if (hit.response == RESPONSE_KILL)
                    {
                        s.kill = true;
                        break;
                    }
                    float3 velocity = local ? turn(e.rotation, p.velocity) : p.velocity;
                    float into = dot(velocity, hit.normal);
                    if (hit.response == RESPONSE_STICK || hit.response == RESPONSE_SNAP)
                    {
                        p.flags |= PARTICLE_STUCK;
                        velocity = 0;
                    }
                    else if (into < 0)
                    {
                        float keep = 1 - hit.friction;
                        float bounce = hit.response == RESPONSE_BOUNCE ? -hit.restitution : 0;
                        velocity = (velocity - into * hit.normal) * keep + bounce * into * hit.normal;
                    }
                    p.position = local ? turn(back, hit.contact) : hit.contact;
                    p.velocity = local ? turn(back, velocity) : velocity;
                    break;
                }
            }
            op = skip(m, op);
        }
    }
    if (s.kill)
    {
        p.lifetime = p.age; // within an attractor's kill radius, or killed by a hit: dead from now
    }
    if ((stages & STAGE_UPDATE) != 0)
    {
        op = first;
        for (uint i = 0; i < count; i++)
        {
            uint word = m.program[op];
            if (((word >> 16) & STAGE_UPDATE) != 0)
            {
                updateModule(m, keys, op, word & 0xff, s, p);
            }
            op = skip(m, op);
        }
    }
    if ((stages & STAGE_OVER) == 0)
    {
        return;
    }
    op = first;
    for (uint i = 0; i < count; i++)
    {
        uint word = m.program[op];
        if (((word >> 16) & STAGE_OVER) != 0)
        {
            overModule(m, keys, op, word & 0xff, s, p);
        }
        op = skip(m, op);
    }
    if (s.sized)
    {
        p.size = s.size;
    }
    if (s.colored)
    {
        p.color = s.color;
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
            state[at + STATE_EVENT_INDEX] = 0;
        }
        state[at + STATE_EVENT_CHILDREN] = 0;
        if (index == 0)
        {
            ParticleFrame* frame = push.frame;
            uint* args = frame.eventArgs;
            args[3] = 0;
            args[4] = 0;
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
    Particle p = {};
    bool keep = false;
    uint header0 = m.program[0];
    bool sends = ((header0 >> 16) & PROGRAM_EVENTS) != 0;
    bool held = (e.flags & EMITTER_HELD) != 0; // a reduced update rate: copied as it is this frame
    uint list = sends ? eventList(m, updaters(m)) : 0;
    bool born = false;
    if (local < aliveOf(e))
    {
        Particle* source = push.source;
        p = source[e.first + local];
        keep = survives(p, e.dt);
        if ((e.flags & EMITTER_LOCAL) == 0)
        {
            p.position += e.shift; // the origin rebased: the same place in the world
        }
        born = (p.flags & PARTICLE_NEWBORN) != 0;
        if (!held) // held still, it sends its birth events when it next moves
        {
            p.flags &= ~PARTICLE_NEWBORN;
        }
        if (sends && !keep && (p.flags & PARTICLE_DEAD) == 0)
        {
            CurveBuffer dying;
            dying.block = block;
            send(m, dying, list, chunks[chunk], local, p, 0, born, true, false, p.age, NO_SIDE);
        }
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
        float before = p.age;
        CurveBuffer events;
        events.block = block;
        uint header1 = m.program[0];
        // Its emit accumulators, in the side range it was just copied to
        uint emitAt = ((header1 >> 16) & PROGRAM_EMIT) != 0 ? e.side + (to - e.first) * e.sideUnits
            + (((header1 >> 16) & PROGRAM_OVER) != 0 ? 2 : 0) : NO_SIDE;
        if (sends && born && !held)
        {
            send(m, events, list, chunks[chunk], local, p, 0, true, false, false, before, NO_SIDE);
        }
        if (cached && !held)
        {
            CurveCache keys;
            step(m, keys, first, p, color0, size0, e.dt);
        }
        else if (!held)
        {
            CurveBuffer keys;
            keys.block = block;
            step(m, keys, first, p, color0, size0, e.dt);
        }
        if (sends && !held)
        {
            send(m, events, list, chunks[chunk], local, p, 1, false, false, (p.flags & PARTICLE_COLLIDED) != 0, before,
                emitAt);
        }
        Particle* source0 = push.source;
        Particle* target = source0 + push.targetDelta;
        target[to] = p;
        bound(p);
        uint header = m.program[0];
        if (((header >> 16) & PROGRAM_TRAIL) != 0 && !held)
        {
            // The trail's ring: one more past position, over its oldest
            uint4* sideSource0 = push.sideSource;
            uint4* trail = sideSource0 + push.sideDelta + e.side + (to - e.first) * e.sideUnits + trailUnit(header);
            uint points = header >> 24;
            if (any(e.shift != 0))
            {
                for (uint i = 1; i <= points; i++)
                {
                    trail[i] = uint4(asuint(asfloat(trail[i].xyz) + e.shift), 0);
                }
            }
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
        uint demand = (e.flags & EMITTER_CPU) != 0 ? 0 : e.demand + state[at + STATE_EVENT_CHILDREN];
        uint spawned = min(demand, e.capacity - survivors);
        state[at + STATE_SPAWNED] = spawned;
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
        p.velocity = turn(q, p.velocity) + e.inherited;
        p.position = at + turn(q, p.position);
        return;
    }
    p.position = at + turn(q, p.position);
    p.velocity = turn(q, p.velocity);
}

/** The side unit of its range a particle's trail starts at: after its attributes from birth and its accumulators. */
uint trailUnit(uint header)
{
    return (((header >> 16) & PROGRAM_OVER) != 0 ? 2 : 0) + (((header >> 16) & PROGRAM_EMIT) != 0 ? 1 : 0);
}

/** Writes newborn `p` (with `color0` and `size0` from birth) into slot `slot` of emitter `e`'s range, and its box. */
void store(ParticleEmitter e, uint at, uint slot, Particle p, float4 color0, float size0, uint header)
{
    Particle* source0 = push.source;
    Particle* target = source0 + push.targetDelta;
    target[e.first + slot] = p;
    uint* state = push.state;
    if (e.side != NO_SIDE)
    {
        uint4* sideSource0 = push.sideSource;
        uint4* sideTarget = sideSource0 + push.sideDelta;
        uint s = e.side + slot * e.sideUnits;
        if (((header >> 16) & PROGRAM_OVER) != 0)
        {
            sideTarget[s] = asuint(color0);
            sideTarget[s + 1] = uint4(asuint(size0), 0, 0, 0);
        }
        if (((header >> 16) & PROGRAM_EMIT) != 0)
        {
            sideTarget[s + (((header >> 16) & PROGRAM_OVER) != 0 ? 2 : 0)] = 0; // nothing owed yet
        }
        if (((header >> 16) & PROGRAM_TRAIL) != 0)
        {
            // A trail starts where its particle is: no streak back to the origin
            uint4* trail = sideTarget + s + trailUnit(header);
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

/**
 * Newborn `k` of emitter `e`, child `j` of the frame's events into it (CpuSimulation.spawnEvents): its event the last
 * sorted one whose children start at or before it; seeded from its parent, initialized, placed at its parent (its
 * shapes about it), taking what its init.inherit says; born at the frame's end.
 */
void spawnChild(ParticleEmitter e, uint at, uint k, uint j)
{
    ParticleFrame* frame = push.frame;
    uint* state = push.state;
    uint* prefix = frame.childPrefix;
    uint* args = frame.eventArgs;
    uint first = state[at + STATE_EVENT_FIRST];
    uint global = prefix[first] + j;
    uint low = first;
    uint high = args[3] - 1;
    while (low < high)
    {
        uint middle = (low + high + 1) >> 1;
        if (prefix[middle] <= global)
        {
            low = middle;
        }
        else
        {
            high = middle - 1;
        }
    }
    uint2* eventKeys = frame.eventKeys;
    ParticleEventRecord* events = (ParticleEventRecord*) frame.events;
    ParticleEventRecord r = events[eventKeys[low].y];
    uint c = global - prefix[low];
    // An emit event's children: spread back over their parent's step along its path, the youngest last
    bool emitted = (r.salt & EVENT_SPREAD) != 0;
    float spread = emitted ? r.step * float(r.count - 1 - c) / float(r.count) : 0;
    r.salt &= ~EVENT_SPREAD;
    Particle p;
    p.position = 0;
    p.velocity = 0;
    p.age = 0;
    p.lifetime = 1;
    p.color = 1;
    p.size = 0.1;
    p.rotation = 0;
    p.spawnIndex = state[at + STATE_EVENT_INDEX] + j;
    // An emitting parent's children each by their spawn index: its events come every frame, alike
    p.seed = combine(combine(r.seed, r.salt), emitted ? p.spawnIndex : c);
    p.flags = PARTICLE_NEWBORN;
    p.custom = float2(0, asfloat(r.parentIndex)); // a ribbon per parent
    float4 color0 = 1;
    float size0 = 0.1;
    Emitting m = emitting(e);
    float* curves = push.curves;
    CurveBuffer keys;
    keys.block = curves + e.curves;
    initialize(m, keys, p, color0, size0);
    if (p.lifetime <= 0)
    {
        p.flags |= PARTICLE_DEAD;
    }
    // What it takes of its parent: init.inherit's color, size, velocity
    float inherit = 0;
    bool color = false;
    bool size = false;
    uint op = 1;
    for (uint i = 0; i < (m.program[0] & 0xff); i++)
    {
        if ((m.program[op] & 0xff) == OP_INIT_INHERIT) // its fields: color, size, velocity
        {
            uint salt = m.program[op + 1];
            color = value(m, keys, op + 2, salt, p) >= 0.5;
            size = value(m, keys, op + 2 + BINDING_WORDS, salt + 1, p) >= 0.5;
            inherit = value(m, keys, op + 2 + 2 * BINDING_WORDS, salt + 2, p);
        }
        op = skip(m, op);
    }
    ParticleEmitter* emitters = push.emitters;
    ParticleEmitter parent = emitters[r.parent];
    precise float3 high3 = parent.originHigh - e.originHigh;
    precise float3 low3 = parent.originLow - e.originLow;
    float3 from = (high3 + low3) + r.position - r.velocity * spread;
    float4 back = float4(-e.rotation.xyz, e.rotation.w);
    if ((e.flags & EMITTER_LOCAL) != 0)
    {
        p.position += turn(back, from);
        p.velocity += turn(back, r.velocity * inherit);
    }
    else
    {
        p.position = from + turn(e.rotation, p.position);
        p.velocity = turn(e.rotation, p.velocity) + r.velocity * inherit;
    }
    if (color)
    {
        p.color *= r.color;
        color0 = p.color;
    }
    if (size)
    {
        p.size *= r.size;
        size0 = p.size.x;
    }
    p.age = spread;
    store(e, at, state[at + STATE_SURVIVORS] + k, p, color0, size0, m.program[0]);
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
    if (k >= e.demand)
    {
        spawnChild(e, at, k, k - e.demand);
        return;
    }
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
    p.flags = ((record.flags & SPAWN_PREWARM) != 0 ? PARTICLE_PREWARM : 0) | PARTICLE_NEWBORN;
    p.custom = float2(0, asfloat(p.spawnIndex)); // its ribbon of several: spawn index % ribbons
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
    store(e, at, state[at + STATE_SURVIVORS] + k, p, color0, size0, m.program[0]);
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
    uint spawned = state[at + STATE_SPAWNED];
    uint alive = state[at + STATE_SURVIVORS] + spawned;
    state[at + STATE_ALIVE] = alive;
    uint fromRecords = (emitters[id.x].flags & EMITTER_CPU) != 0 ? 0 : min(emitters[id.x].demand, spawned);
    state[at + STATE_EVENT_INDEX] += spawned - fromRecords;
    uint* statistics = push.statistics;
    statistics[STATISTICS_HEADER + 2 * instance] = alive;
    statistics[STATISTICS_HEADER + 2 * instance + 1] = state[at + STATE_OVERFLOW];
}

[shader("compute")]
[numthreads(1, 1, 1)]
void eventsArgsMain()
{
    // The events kept (sent, within the frame's limit), the radix sort's dispatch over them, or the small sort's (one
    // group) when they fit it; the statistics
    ParticleFrame* frame = push.frame;
    uint* args = frame.eventArgs;
    uint sent = args[4];
    uint kept = min(sent, frame.eventLimit);
    bool small = kept <= frame.smallEvents;
    uint groups = small ? 0 : (kept + SORT_GROUP - 1) / SORT_GROUP;
    args[0] = min(groups, 65535u);
    args[1] = (groups + 65534) / 65535;
    args[2] = 1;
    args[3] = kept;
    args[5] = small && kept > 0 ? 1 : 0;
    args[6] = 1;
    args[7] = 1;
    uint* statistics = push.statistics;
    statistics[STATISTICS_EVENTS] = sent;
    statistics[STATISTICS_EVENTS_DROPPED] = sent - kept;
}

/** The event key of the event at `index`: its target and its parent's emitter, for the second, stable sort. */
uint targetKey(ParticleEventRecord* events, uint index)
{
    return events[index].target << 12 | events[index].parent;
}

[shader("compute")]
[numthreads(SORT_THREADS, 1, 1)]
void eventsRekeyMain(uint3 group : SV_GroupID, uint lane : SV_GroupIndex)
{
    // Sorted by parent, phase and event; now keyed by target and parent emitter for the second, stable sort
    ParticleFrame* frame = push.frame;
    uint* args = frame.eventArgs;
    uint2* eventKeys = frame.eventKeys;
    ParticleEventRecord* events = (ParticleEventRecord*) frame.events;
    for (uint k = 0; k < SORT_KEYS; k++)
    {
        uint i = chunkOf(group) * SORT_GROUP + k * SORT_THREADS + lane;
        if (i < args[3])
        {
            uint index = eventKeys[i].y;
            eventKeys[i] = uint2(targetKey(events, index), index);
        }
    }
}

/**
 * The sum of `value` over the group's threads before `lane` (SORT_THREADS of them, in waves of consecutive threads):
 * each wave's prefix sums, then the waves' sums scanned (sort.slang's). Every thread calls it together.
 */
uint exclusiveSum(uint value, uint lane)
{
    uint n = WaveGetLaneCount();
    uint before = WavePrefixSum(value);
    if (WaveGetLaneIndex() == n - 1)
    {
        shared[lane / n] = before + value; // SORT_THREADS / 4 waves at most: CHUNK
    }
    GroupMemoryBarrierWithGroupSync();
    if (lane == 0)
    {
        uint sum = 0;
        for (uint w = 0; w < SORT_THREADS / n; w++)
        {
            uint s = shared[w];
            shared[w] = sum;
            sum += s;
        }
    }
    GroupMemoryBarrierWithGroupSync();
    uint result = shared[lane / n] + before;
    GroupMemoryBarrierWithGroupSync(); // the waves' sums free for the next call
    return result;
}

/** Keys of the small sort each thread takes. */
static const uint SMALL_KEYS = EVENT_SMALL / SORT_THREADS;

/**
 * Sorts the first `n` keys of smallKeys by their first word, stably: the radix sort's passes of 4 bits, each key
 * ranked as sortScatterMain ranks it, moved within group memory. A digit every key has alike leaves the order as it
 * is, so its pass is left out. Every thread calls it together.
 */
void sortSmall(uint n, uint lane)
{
    uint size = WaveGetLaneCount();
    uint wave = lane / size;
    uint waves = SORT_THREADS / size;
    uint first = wave * SMALL_KEYS * size + WaveGetLaneIndex();
    uint4 below = lanesBelow();
    if (lane == 0)
    {
        keyBits[0] = 0;
        keyBits[1] = 0xffffffffu;
    }
    GroupMemoryBarrierWithGroupSync();
    uint any = 0;
    uint all = 0xffffffffu;
    for (uint i = lane; i < n; i += SORT_THREADS)
    {
        any |= smallKeys[i].x;
        all &= smallKeys[i].x;
    }
    InterlockedOr(keyBits[0], WaveActiveBitOr(any));
    InterlockedAnd(keyBits[1], WaveActiveBitAnd(all));
    GroupMemoryBarrierWithGroupSync();
    uint differing = keyBits[0] ^ keyBits[1];
    for (uint shift = 0; shift < 32; shift += 4)
    {
        if (((differing >> shift) & 15) == 0)
        {
            continue; // the same for the whole group
        }
        DigitCounts counts = noDigits();
        uint2 items[SMALL_KEYS];
        uint ranks[SMALL_KEYS];
        for (uint k = 0; k < SMALL_KEYS; k++)
        {
            uint i = first + k * size;
            bool held = i < n;
            items[k] = held ? smallKeys[i] : uint2(0, 0);
            uint digit = (items[k].x >> shift) & 15;
            DigitBallots row = digitBallots(held, digit);
            ranks[k] = counts.of(digit) + countBits(row.lanes(digit) & below);
            counts.add(row);
        }
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
        for (uint k = 0; k < SMALL_KEYS; k++)
        {
            if (first + k * size < n)
            {
                smallKeys[waveDigits[((items[k].x >> shift) & 15) * waves + wave] + ranks[k]] = items[k];
            }
        }
        GroupMemoryBarrierWithGroupSync();
    }
}

[shader("compute")]
[numthreads(SORT_THREADS, 1, 1)]
void eventsSmallMain(uint lane : SV_GroupIndex)
{
    // One group, when the frame keeps at most EVENT_SMALL events: the radix sort by parent, phase and event, the rekey
    // and the second sort, in group memory; stable sorts of the same keys, so the same order
    ParticleFrame* frame = push.frame;
    uint* args = frame.eventArgs;
    uint n = min(args[3], EVENT_SMALL);
    uint2* eventKeys = frame.eventKeys;
    ParticleEventRecord* events = (ParticleEventRecord*) frame.events;
    for (uint i = lane; i < n; i += SORT_THREADS)
    {
        smallKeys[i] = eventKeys[i];
    }
    GroupMemoryBarrierWithGroupSync();
    sortSmall(n, lane);
    for (uint i = lane; i < n; i += SORT_THREADS)
    {
        uint index = smallKeys[i].y;
        smallKeys[i] = uint2(targetKey(events, index), index);
    }
    GroupMemoryBarrierWithGroupSync();
    sortSmall(n, lane);
    for (uint i = lane; i < n; i += SORT_THREADS)
    {
        eventKeys[i] = smallKeys[i];
    }
}

[shader("compute")]
[numthreads(64, 1, 1)]
void eventsRangesMain(uint lane : SV_GroupIndex)
{
    // One group: each sorted event's first child among all (an exclusive scan of their counts), each target's first
    // event and its children
    ParticleFrame* frame = push.frame;
    uint* args = frame.eventArgs;
    uint n = args[3];
    uint run = (n + 63) / 64;
    uint from = min(lane * run, n);
    uint to = min(from + run, n);
    uint2* eventKeys = frame.eventKeys;
    ParticleEventRecord* events = (ParticleEventRecord*) frame.events;
    uint sum = 0;
    for (uint i = from; i < to; i++)
    {
        sum += events[eventKeys[i].y].count;
    }
    shared[lane] = sum;
    GroupMemoryBarrierWithGroupSync();
    if (lane == 0)
    {
        uint total = 0;
        for (uint i = 0; i < 64; i++)
        {
            uint s = shared[i];
            shared[i] = total;
            total += s;
        }
    }
    GroupMemoryBarrierWithGroupSync();
    uint offset = shared[lane];
    uint* prefix = frame.childPrefix;
    uint* state = push.state;
    ParticleEmitter* emitters = push.emitters;
    for (uint i = from; i < to; i++)
    {
        ParticleEventRecord r = events[eventKeys[i].y];
        prefix[i] = offset;
        offset += r.count;
        uint at = emitters[r.target].instance * STATE_WORDS;
        if (i == 0 || events[eventKeys[i - 1].y].target != r.target)
        {
            state[at + STATE_EVENT_FIRST] = i;
        }
        InterlockedAdd(state[at + STATE_EVENT_CHILDREN], r.count);
    }
}

/** The rotation turning +Y onto unit `d`. */
float4 fromUp(float3 d)
{
    float w = 1 + d.y;
    return w < 1e-6 ? float4(1, 0, 0, 0) : float4(d.z, 0, -d.x, w) / sqrt(w * w + d.z * d.z + d.x * d.x);
}

[shader("compute")]
[numthreads(64, 1, 1)]
void instancesMain(uint3 id : SV_DispatchThreadID, uint3 group : SV_GroupID)
{
    // Mesh particles (E15): a row of groups per emitter with a mesh renderer, an instance per slot of its range in the
    // scene's range the GPU writes: its live particles as drawn (from the half just written), the other slots holes.
    ParticleFrame* frame = push.frame;
    uint* list = frame.meshEmitters;
    ParticleEmitter* emitters = push.emitters;
    ParticleEmitter e = emitters[list[group.y]];
    uint k = id.x;
    if (k >= e.capacity)
    {
        return;
    }
    Instance r;
    Instance before = (Instance) 0; // a frame before: moved back by its velocity, turned back by its roll
    r.rotation = float4(0, 0, 0, 1);
    r.position = 0;
    r.mesh = 0; // a hole
    r.scale = 0;
    r.materialBase = 0;
    r.positionLow = 0;
    r.layers = 0;
    uint* state = push.state;
    if (e.mesh != 0 && k < state[e.instance * STATE_WORDS + STATE_ALIVE])
    {
        Particle* source0 = push.source;
        Particle* written = source0 + push.targetDelta;
        Particle p = written[e.first + k];
        if ((p.flags & PARTICLE_DEAD) == 0 && p.age < p.lifetime)
        {
            uint* words = push.programs;
            ParticleRenderer renderer = ((ParticleRenderer*) (words + e.meshRenderer))[0];
            bool local = (e.flags & EMITTER_LOCAL) != 0;
            if (renderer.orient == ORIENT_VELOCITY)
            {
                float3 v = local ? turn(e.rotation, p.velocity) : p.velocity;
                float speed = length(v);
                r.rotation = speed > 1e-6 ? fromUp(v / speed) : float4(0, 0, 0, 1);
            }
            else if (renderer.orient == ORIENT_EMITTER)
            {
                r.rotation = e.rotation;
            }
            else // tumbling about an axis of its own, by how far it rolled
            {
                float3 axis = randomDirection(p.seed, 0x7000);
                float angle = TAU * random(p.seed, 0x7002) + p.rotation;
                r.rotation = float4(axis * sin(0.5 * angle), cos(0.5 * angle));
                // as the update rolls it: by its speed over half its size
                float back = angle - length(p.velocity) * e.dt / max(0.5 * p.size.x, 1e-4);
                before.rotation = float4(axis * sin(0.5 * back), cos(0.5 * back));
            }
            r.position = e.originHigh;
            r.positionLow = e.originLow + (local ? turn(e.rotation, p.position) : p.position);
            r.mesh = e.mesh;
            r.scale = p.size.x * renderer.scale;
            r.materialBase = e.material;
            r.layers = renderer.layers;
        }
    }
    Instance* instances = (Instance*) frame.instances;
    instances[e.meshFirst + k] = r;
    float4 turned = before.rotation;
    before = r;
    if (r.mesh != 0)
    {
        Particle* source1 = push.source;
        Particle p1 = (source1 + push.targetDelta)[e.first + k];
        bool turns = ((ParticleRenderer*) (push.programs + e.meshRenderer))[0].orient == ORIENT_TUMBLE;
        before.rotation = turns ? turned : r.rotation;
        before.positionLow -= ((e.flags & EMITTER_LOCAL) != 0 ? turn(e.rotation, p1.velocity) : p1.velocity) * e.dt;
    }
    Instance* previous = (Instance*) frame.previousInstances;
    previous[e.meshFirst - frame.meshBase + k] = before;
}

// ---------------------------------------------------------------------------------------------- particle lights

/**
 * Whether particle `k` of light emitter row `row` lights (alive, in the share lit, brighter than nothing): its light's
 * color into `color`, its radius into `reach` (the emitter's, or that per metre of the particle's size), its bucket of
 * reach into `bucket`. Candidates rank by their brightness (the color's brightest channel) times their radius, so a
 * light reaching far outranks one as bright reaching near for the frame's budget; within an emitter of one radius that
 * is their brightness.
 */
bool lightCandidate(uint row, uint k, out Particle p, out float3 color, out float reach, out uint bucket)
{
    ParticleFrame* frame = push.frame;
    uint* entries = frame.lightEmitters;
    uint* entry = entries + row * LIGHT_ENTRY_WORDS;
    ParticleEmitter* emitters = push.emitters;
    ParticleEmitter e = emitters[entry[0]];
    uint* state = push.state;
    color = 0;
    reach = 0;
    bucket = 0;
    p = (Particle) 0;
    if (k >= state[e.instance * STATE_WORDS + STATE_ALIVE])
    {
        return false;
    }
    Particle* source0 = push.source;
    Particle* written = source0 + push.targetDelta;
    p = written[e.first + k];
    if ((p.flags & PARTICLE_DEAD) != 0 || p.age >= p.lifetime || !(random(p.seed, 0x7100) < asfloat(entry[2])))
    {
        return false;
    }
    color = p.color.rgb * p.color.a * asfloat(entry[4]);
    float perSize = asfloat(entry[5]);
    reach = perSize > 0 ? asfloat(entry[3]) * p.size.x * perSize : asfloat(entry[3]);
    float brightness = max(color.r, max(color.g, color.b));
    if (!(brightness > 0) || !(reach > 0))
    {
        return false;
    }
    bucket = uint(clamp(log2(brightness * reach) * 4 + 24, 0.0, float(LIGHT_BUCKETS - 1)));
    return true;
}

[shader("compute")]
[numthreads(64, 1, 1)]
void lightsCountMain(uint3 id : SV_DispatchThreadID, uint3 group : SV_GroupID)
{
    // A row of groups per light emitter: its candidates counted by reach
    Particle p;
    float3 color;
    float reach;
    uint bucket;
    if (lightCandidate(group.y, id.x, p, color, reach, bucket))
    {
        ParticleFrame* frame = push.frame;
        uint* state = frame.lightState;
        InterlockedAdd(state[group.y * LIGHT_STATE_WORDS + bucket], 1);
    }
}

/** The bucket from which the brightest `budget` of `counts` start, into `at`, and how many of it they take. */
void threshold(uint* counts, uint budget, out uint at, out uint taken)
{
    uint sum = 0;
    at = 0;
    taken = counts[0];
    for (int q = int(LIGHT_BUCKETS) - 1; q >= 0; q--)
    {
        uint n = counts[q];
        if (sum + n >= budget)
        {
            at = uint(q);
            taken = budget - sum;
            return;
        }
        sum += n;
    }
}

[shader("compute")]
[numthreads(256, 1, 1)]
void lightsThresholdMain(uint lane : SV_GroupIndex)
{
    // One group: each emitter keeps its farthest reaching within its budget (a thread per emitter, the rows in turn),
    // what they keep summed into the frame's histogram in group memory; of it, the frame keeps its farthest reaching
    // within its budget
    ParticleFrame* frame = push.frame;
    uint* state = frame.lightState;
    uint* entries = frame.lightEmitters;
    uint* all = state + MAX_LIGHT_EMITTERS * LIGHT_STATE_WORDS;
    if (lane < LIGHT_BUCKETS)
    {
        frameBuckets[lane] = 0;
    }
    GroupMemoryBarrierWithGroupSync();
    for (uint row = lane; row < frame.lightEmitterCount; row += 256)
    {
        uint* s = state + row * LIGHT_STATE_WORDS;
        uint at;
        uint taken;
        threshold(s, entries[row * LIGHT_ENTRY_WORDS + 1], at, taken);
        s[LIGHT_BUCKETS] = at;
        s[LIGHT_BUCKETS + 1] = taken;
        for (uint q = at; q < LIGHT_BUCKETS; q++)
        {
            uint n = q == at ? taken : s[q];
            if (n > 0)
            {
                InterlockedAdd(frameBuckets[q], n);
            }
        }
    }
    GroupMemoryBarrierWithGroupSync();
    if (lane < LIGHT_BUCKETS)
    {
        all[lane] = frameBuckets[lane];
    }
    if (lane == 0)
    {
        // threshold(), over the group's histogram
        uint sum = 0;
        uint at = 0;
        uint taken = frameBuckets[0];
        for (int q = int(LIGHT_BUCKETS) - 1; q >= 0; q--)
        {
            uint n = frameBuckets[q];
            if (sum + n >= frame.lightBudget)
            {
                at = uint(q);
                taken = frame.lightBudget - sum;
                break;
            }
            sum += n;
        }
        all[LIGHT_BUCKETS] = at;
        all[LIGHT_BUCKETS + 1] = taken;
    }
}

/** Whether a candidate of bucket `bucket` stays within selection `s`: above its threshold, or among the first at it. */
bool kept(uint* s, uint bucket)
{
    if (bucket != s[LIGHT_BUCKETS])
    {
        return bucket > s[LIGHT_BUCKETS];
    }
    uint before;
    InterlockedAdd(s[LIGHT_BUCKETS + 2], 1, before);
    return before < s[LIGHT_BUCKETS + 1];
}

[shader("compute")]
[numthreads(64, 1, 1)]
void lightsWriteMain(uint3 id : SV_DispatchThreadID, uint3 group : SV_GroupID)
{
    // A row of groups per light emitter: the candidates its budget and the frame's keep become point lights
    Particle p;
    float3 color;
    float reach;
    uint bucket;
    if (!lightCandidate(group.y, id.x, p, color, reach, bucket))
    {
        return;
    }
    ParticleFrame* frame = push.frame;
    uint* state = frame.lightState;
    if (!kept(state + group.y * LIGHT_STATE_WORDS, bucket)
        || !kept(state + MAX_LIGHT_EMITTERS * LIGHT_STATE_WORDS, bucket))
    {
        return;
    }
    uint* lights = frame.gpuLights;
    uint slot;
    InterlockedAdd(lights[0], 1, slot);
    if (slot >= frame.lightBudget)
    {
        return;
    }
    uint* entries = frame.lightEmitters;
    ParticleEmitter* emitters = push.emitters;
    ParticleEmitter e = emitters[entries[group.y * LIGHT_ENTRY_WORDS]];
    Light l;
    l.position = e.originHigh;
    l.positionLow = e.originLow + ((e.flags & EMITTER_LOCAL) != 0 ? turn(e.rotation, p.position) : p.position);
    l.range = reach;
    l.color = color;
    l.type = POINT;
    l.direction = float3(0, -1, 0);
    l.cosOuter = 0;
    l.cosInner = 0;
    l.shadowFirst = -1;
    l.shadowCount = 0;
    l.pad = 0;
    l.pad1 = 0;
    Light* written = (Light*) (lights + 4);
    written[slot] = l;
}

[shader("compute")]
[numthreads(64, 1, 1)]
void uploadMain(uint3 id : SV_DispatchThreadID, uint3 group : SV_GroupID)
{
    // A row of groups per CPU emitter: its uploaded particles into its range of the half the frame writes.
    ParticleFrame* frame = push.frame;
    uint* cpu = frame.cpu;
    ParticleEmitter* emitters = push.emitters;
    ParticleEmitter e = emitters[cpu[group.y]];
    if (id.x >= e.demand)
    {
        return;
    }
    Particle* uploads = frame.uploads;
    Particle* source0 = push.source;
    Particle* target = source0 + push.targetDelta;
    target[e.first + id.x] = uploads[e.records + id.x];
}
