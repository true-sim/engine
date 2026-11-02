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
#ifdef PARTICLE_MODULES
import particles.particle; // included by a game's kernel variant (effects step 20), from its own folder
#else
import particle;
#endif
import bindless;
import meshlets.common;
#ifdef PARTICLE_RAYS
import raytracing;
#endif

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

#ifdef PARTICLE_MODULES
/**
 * Runs plugin module `op` (an opcode from OP_PLUGIN on) of program `m` on particle `p`: its fields evaluated, in
 * alphabetical order of their names, then the game's ParticleModules dispatch (effects step 20).
 */
void plugin<K : ICurveKeys>(Emitting m, K keys, uint op, inout Particle p, float h, bool init)
{
    uint code = m.program[op] & 0xff;
    uint salt = m.program[op + 1];
    uint fields = min(((m.program[op] >> 8) - 2) / BINDING_WORDS, PLUGIN_FIELDS);
    ParticleModuleInput in;
    for (uint f = 0; f < PLUGIN_FIELDS; f++)
    {
        in.values[f] = f < fields ? value(m, keys, op, salt, f, p) : 0;
    }
    in.h = h;
    in.time = m.emitter.time;
    in.salt = salt;
    in.local = (m.emitter.flags & EMITTER_LOCAL) != 0;
    in.down = m.emitter.down;
    in.wind = m.emitter.wind;
    if (init)
    {
        ParticleModules.init(code - OP_PLUGIN, p, in);
    }
    else
    {
        ParticleModules.update(code - OP_PLUGIN, p, in);
    }
}
#endif

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

/** Byte `i` of the pool's meshlet triangles. */
uint triangleByte(uint* triangles, uint i)
{
    return (triangles[i >> 2] >> ((i & 3) * 8)) & 0xff;
}

/** Pool vertex `v`'s normal, or `fallback` where the mesh has none. */
float3 vertexNormal(MeshEntry mesh, uint* attributes, uint v, float3 fallback)
{
    return (mesh.attributeFormat & 1) != 0 ? decodeOctahedral(attributeWord(mesh, attributes, v, 0)) : fallback;
}

/** Pool vertex `v`'s first texture coordinates (the mesh has them). */
float2 vertexUv(MeshEntry mesh, uint* attributes, uint v)
{
    uint word = attributeWord(mesh, attributes, v, 2);
    return float2(decodeHalf(word & 0xffff), decodeHalf(word >> 16));
}

/**
 * A point of source `at`'s mesh, as `mode` says (MESH_*), drawn from `salt` on, in the emitter's frame: on a triangle
 * its alias table by area picks (uniform within it), on a vertex, or in the tetrahedron from the box's center to a
 * triangle its table by volume picks; with the surface's normal (outwards from the center in the volume) and its
 * texture coordinates where the mesh has them. False without a mesh.
 */
bool meshPoint(uint at, uint mode, uint seed, uint salt, out float3 position, out float3 normal, out float2 uv,
    out bool textured)
{
    ParticleFrame* frame = push.frame;
    ParticleSource* sources = frame.sources;
    position = 0;
    normal = float3(0, 1, 0);
    uv = 0;
    textured = false;
    uint meshIndex = sources[at].mesh;
    if (meshIndex == NO_MESH)
    {
        return false;
    }
    MeshEntry* meshes = (MeshEntry*) frame.meshes;
    MeshEntry mesh = meshes[meshIndex];
    uint2* positions = frame.positions;
    uint* attributes = frame.attributes;
    if (mode == MESH_VERTICES)
    {
        uint vertices = sources[at].vertices;
        uint v = mesh.vertexBase + min(uint(random(seed, salt + 8) * float(vertices)), vertices - 1);
        position = decodePosition(positions[v], mesh);
        normal = vertexNormal(mesh, attributes, v, normal);
        textured = (mesh.attributeFormat & 4) != 0;
        uv = textured ? vertexUv(mesh, attributes, v) : 0;
    }
    else
    {
        uint n = sources[at].triangles;
        uint* table = (uint*) (sources[at].table + (mode == MESH_VOLUME ? 12 * uint64_t(n) : 0));
        uint i = min(uint(random(seed, salt + 8) * float(n)), n - 1);
        uint chosen = random(seed, salt + 9) < asfloat(table[3 * i + 1]) ? i : table[3 * i + 2];
        uint packed = table[3 * chosen];
        uint* meshlets = frame.meshlets;
        uint* meshletVertices = frame.meshletVertices;
        uint* triangles = frame.meshletTriangles;
        uint w = (mesh.meshletBase + (packed >> 8)) * 8;
        uint first = mesh.meshletVertexBase + meshlets[w];
        uint corner = mesh.indexBase + meshlets[w + 1] + 3 * (packed & 0xff);
        uint3 v = uint3(meshletVertices[first + triangleByte(triangles, corner)],
            meshletVertices[first + triangleByte(triangles, corner + 1)],
            meshletVertices[first + triangleByte(triangles, corner + 2)]) + mesh.vertexBase;
        float3 a = decodePosition(positions[v.x], mesh);
        float3 b = decodePosition(positions[v.y], mesh);
        float3 c = decodePosition(positions[v.z], mesh);
        if (mode == MESH_VOLUME)
        {
            // Uniform in the tetrahedron (center, a, b, c): a point of the unit cube folded into it (Rocchini and
            // Cignoni 2000)
            float3 center = mesh.boxMin + 0.5 * mesh.boxExtent;
            float s = random(seed, salt + 10);
            float t = random(seed, salt + 11);
            float u = random(seed, salt + 12);
            if (s + t > 1)
            {
                s = 1 - s;
                t = 1 - t;
            }
            if (t + u > 1)
            {
                float k = u;
                u = 1 - s - t;
                t = 1 - k;
            }
            else if (s + t + u > 1)
            {
                float k = u;
                u = s + t + u - 1;
                s = 1 - t - k;
            }
            position = (1 - s - t - u) * center + s * a + t * b + u * c;
            float3 away = position - center;
            normal = dot(away, away) > 1e-12 ? normalize(away) : normal;
        }
        else
        {
            float r = sqrt(random(seed, salt + 10));
            float q = random(seed, salt + 11);
            float3 bary = float3(1 - r, r * (1 - q), r * q);
            position = bary.x * a + bary.y * b + bary.z * c;
            float3 face = cross(b - a, c - a);
            face = dot(face, face) > 1e-20 ? normalize(face) : normal;
            if ((mesh.attributeFormat & 1) != 0)
            {
                normal = bary.x * vertexNormal(mesh, attributes, v.x, face)
                    + bary.y * vertexNormal(mesh, attributes, v.y, face)
                    + bary.z * vertexNormal(mesh, attributes, v.z, face);
            }
            else
            {
                normal = face;
            }
            textured = (mesh.attributeFormat & 4) != 0;
            if (textured)
            {
                uv = bary.x * vertexUv(mesh, attributes, v.x) + bary.y * vertexUv(mesh, attributes, v.y)
                    + bary.z * vertexUv(mesh, attributes, v.z);
            }
        }
    }
    // Into the emitter's frame; normals by the cofactors, so a scaled mesh's stay perpendicular
    float4 r0 = sources[at].toEmitter[0];
    float4 r1 = sources[at].toEmitter[1];
    float4 r2 = sources[at].toEmitter[2];
    position = float3(dot(r0.xyz, position) + r0.w, dot(r1.xyz, position) + r1.w, dot(r2.xyz, position) + r2.w);
    float3x3 cofactor = float3x3(cross(r1.xyz, r2.xyz), cross(r2.xyz, r0.xyz), cross(r0.xyz, r1.xyz));
    float3 turned = mul(transpose(cofactor), normal);
    normal = dot(turned, turned) > 1e-20 ? normalize(turned) : float3(0, 1, 0);
    return true;
}

/**
 * A point within `radius` of the chain of source `at`'s joints, uniform by length along it (and by area across it),
 * drawn from `salt` on, with the direction away from the chain. False without joints.
 */
bool bonePoint(uint at, float radius, uint seed, uint salt, out float3 position, out float3 direction)
{
    ParticleFrame* frame = push.frame;
    ParticleSource* sources = frame.sources;
    uint n = min(sources[at].joints, MAX_JOINTS);
    position = 0;
    direction = randomDirection(seed, salt + 10);
    if (n == 0)
    {
        return false;
    }
    float total = 0;
    for (uint i = 0; i + 1 < n; i++)
    {
        total += length(sources[at].chain[i + 1].xyz - sources[at].chain[i].xyz);
    }
    float along = random(seed, salt + 8) * total;
    float3 a = sources[at].chain[0].xyz;
    float3 b = a;
    float t = 0;
    for (uint i = 0; i + 1 < n; i++)
    {
        a = sources[at].chain[i].xyz;
        b = sources[at].chain[i + 1].xyz;
        float l = length(b - a);
        t = l > 0 ? saturate(along / l) : 0.0;
        if (along <= l)
        {
            break;
        }
        along -= l;
    }
    position = a + (b - a) * t;
    float3 axis = b - a;
    if (dot(axis, axis) > 1e-12)
    {
        axis = normalize(axis);
        float3 across = direction - axis * dot(direction, axis);
        direction = dot(across, across) > 1e-12 ? normalize(across) : direction;
    }
    position += direction * (radius * sqrt(random(seed, salt + 9)));
    return true;
}

/** Runs the initializers on newborn `p` (CpuSimulation.init), its direction scratch kept across them. */
void initialize<K : ICurveKeys>(Emitting m, K keys, inout Particle p, inout float4 color0, inout float size0)
{
    uint header = m.program[0];
    uint count = header & 0xff;
    uint op = 1;
    float3 direction = 0;
    float2 uv = 0;         // the mesh shape's texture coordinates, for masks
    bool textured = false;
    bool masked = false;   // a mask left it out: born dead
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
        case OP_INHERIT:
            break; // read when an event spawns the particle (inheritFrom)
        case OP_MESH: // mode, upward
            p.position = 0;
            direction = float3(0, 1, 0);
            textured = false;
            // Nothing to emit from (no mesh named, or not loaded yet): born dead, not in a heap at the emitter
            masked = masked || m.emitter.source == NO_SOURCE || !meshPoint(m.emitter.source,
                uint(value(m, keys, op, salt, 0, p) + 0.5), seed, salt, p.position, direction, uv, textured);
            // Weighted by how far the surface faces up: kept with the chance 1 - upward + upward max(0, n.y)
            float upward = value(m, keys, op, salt, 1, p);
            masked = masked || !(random(seed, salt + 13) < 1 - upward + upward * max(0.0, direction.y));
            break;
        case OP_ROTATION:
            p.rotation = value(m, keys, op, salt, 0, p);
            break;
        case OP_FRAME:
            p.custom.x = value(m, keys, op, salt, 0, p);
            break;
        case OP_BONES:
            p.position = 0;
            direction = randomDirection(seed, salt + 10);
            textured = false;
            masked = masked || m.emitter.source == NO_SOURCE || !bonePoint(m.emitter.source,
                value(m, keys, op, salt, 0, p), seed, salt, p.position, direction);
            break;
        case OP_MASK: // channel, mask, size x, size z, threshold
            uint maskSlot = m.program[op + 2 + BINDING_WORDS];
            if (maskSlot != NO_TEXTURE)
            {
                float2 at = textured ? uv : float2(p.position.x / value(m, keys, op, salt, 2, p),
                    p.position.z / value(m, keys, op, salt, 3, p)) + 0.5;
                float4 texel = textures[NonUniformResourceIndex(maskSlot)].SampleLevel(samplers[LINEAR_CLAMP], at, 0);
                uint channel = uint(value(m, keys, op, salt, 0, p) + 0.5);
                float density = channel < 4 ? texel[channel] : dot(texel.rgb, float3(0.2126, 0.7152, 0.0722));
                float threshold = value(m, keys, op, salt, 4, p);
                masked = masked || (threshold > 0 ? density < threshold : !(random(seed, salt + 12) < density));
            }
            break;
        case OP_COLOR:
            color0 = colorOf(m, keys, op, salt, p);
            p.color = color0;
            break;
        default:
#ifdef PARTICLE_MODULES
            plugin(m, keys, op, p, 0, true);
#endif
            break;
        }
        op = skip(m, op);
    }
    if (masked)
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
        float rate = value(m, keys, at, salt, 0, p);
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

/** The lattice value of value noise at `c` for `seed`, in [-1, 1) (scene-effects' CurlNoise.lattice). */
float lattice(int3 c, uint seed)
{
    return float(combine(combine(combine(seed, uint(c.x)), uint(c.y)), uint(c.z)) >> 8) * (2.0 / 16777216.0) - 1;
}

/** Value noise for `seed` at `x` and its gradient: (value, d/dx, d/dy, d/dz) (CurlNoise.noise). */
float4 noise(float3 x, uint seed)
{
    float3 f = floor(x);
    int3 i = int3(f);
    float3 t = x - f;
    float3 u = t * t * t * (t * (t * 6 - 15) + 10);
    float3 du = 30 * t * t * (t - 1) * (t - 1);
    float a = lattice(i, seed);
    float b = lattice(i + int3(1, 0, 0), seed);
    float c = lattice(i + int3(0, 1, 0), seed);
    float d = lattice(i + int3(1, 1, 0), seed);
    float e = lattice(i + int3(0, 0, 1), seed);
    float g1 = lattice(i + int3(1, 0, 1), seed);
    float g = lattice(i + int3(0, 1, 1), seed);
    float h = lattice(i + int3(1, 1, 1), seed);
    float k1 = b - a;
    float k2 = c - a;
    float k3 = e - a;
    float k4 = a - b - c + d;
    float k5 = a - c - e + g;
    float k6 = a - b - e + g1;
    float k7 = -a + b + c - d + e - g1 - g + h;
    return float4(a + k1 * u.x + k2 * u.y + k3 * u.z + k4 * u.x * u.y + k5 * u.y * u.z + k6 * u.z * u.x
            + k7 * u.x * u.y * u.z,
        du.x * (k1 + k4 * u.y + k6 * u.z + k7 * u.y * u.z), du.y * (k2 + k5 * u.z + k4 * u.x + k7 * u.z * u.x),
        du.z * (k3 + k6 * u.x + k5 * u.y + k7 * u.x * u.y));
}

/** Curl noise at `x` (CurlNoise.curl): the curl of three channels' potential, over octaves. */
float3 curlNoise(float3 x, float frequency, float drift, uint octaves, uint seed)
{
    float3 curl = 0;
    float scale = frequency;
    float weight = 1;
    for (uint o = 0; o < octaves; o++)
    {
        float3 s = x * scale + drift;
        float4 n0 = noise(s, seed);
        float4 n1 = noise(s, seed + 1);
        float4 n2 = noise(s, seed + 2);
        curl += weight * float3(n2.z - n1.w, n0.w - n2.y, n1.y - n0.z);
        scale *= 2;
        weight *= 0.5;
    }
    return curl;
}

/** The unit axis (x, y, z) of the emitter's frame in its particles' space: turned for world-space ones; +Y for none. */
float3 frameAxis(ParticleEmitter e, float3 v)
{
    float3 a = (e.flags & EMITTER_LOCAL) != 0 ? v : turn(e.rotation, v);
    float length2 = dot(a, a);
    return length2 > 1e-12 ? a * rsqrt(length2) : float3(0, 1, 0);
}

/** A vector of the emitter's frame in its particles' space. */
float3 frameVector(ParticleEmitter e, float3 v)
{
    return (e.flags & EMITTER_LOCAL) != 0 ? v : turn(e.rotation, v);
}

/** Field `f`, `f + 1` and `f + 2` of the op at `op` as a vector. */
float3 vectorOf<K : ICurveKeys>(Emitting m, K keys, uint op, uint salt, uint f, Particle p)
{
    return float3(value(m, keys, op, salt, f, p), value(m, keys, op, salt, f + 1, p),
        value(m, keys, op, salt, f + 2, p));
}

/** Where a particle hit, in world axes about its emitter's origin: the contact (outside by its radius), the normal. */
struct Hit
{
    bool hit;
    float3 contact;
    float3 normal;
};

Hit noHit()
{
    Hit h;
    h.hit = false;
    h.contact = 0;
    h.normal = float3(0, 1, 0);
    return h;
}

/** A hit at `contact` with normal `normal`. */
Hit hitAt(float3 contact, float3 normal)
{
    Hit h;
    h.hit = true;
    h.contact = contact;
    h.normal = normal;
    return h;
}

/** Whether `q` from a collider's center `c` is within `reach` of it: pushed out along it (+Y from the very center). */
Hit outOf(float3 c, float3 q, float reach)
{
    float d = length(q);
    if (!(d < reach))
    {
        return noHit();
    }
    float3 n = d > 1e-6 ? q / d : float3(0, 1, 0);
    return hitAt(c + n * reach, n);
}

/** Whether a particle of radius `r` moving from `a` to `b` ends touching collider `k` (centered at `c`). */
Hit hitCollider(ParticleCollider k, float3 c, float r, float3 a, float3 b)
{
    float3 q = b - c;
    if (k.kind == COLLIDER_PLANE)
    {
        float3 n = turn(k.rotation, float3(0, 1, 0));
        float before = dot(a - c, n) - r;
        float after = dot(q, n) - r;
        return after < 0 && before >= -r ? hitAt(b - after * n, n) : noHit();
    }
    if (k.kind == COLLIDER_SPHERE)
    {
        return outOf(c, q, k.size.x + r);
    }
    if (k.kind == COLLIDER_CAPSULE)
    {
        float3 axis = turn(k.rotation, float3(0, 1, 0));
        float3 e = axis * clamp(dot(q, axis), -k.size.y, k.size.y);
        return outOf(c + e, q - e, k.size.x + r);
    }
    // a box: out through the face it is least deep behind
    float4 back = float4(-k.rotation.xyz, k.rotation.w);
    float3 l = turn(back, q);
    float3 extent = k.size + r;
    float3 depth = extent - abs(l);
    if (!(depth.x > 0 && depth.y > 0 && depth.z > 0))
    {
        return noHit();
    }
    float3 face = 0;
    if (depth.x <= depth.y && depth.x <= depth.z)
    {
        face.x = l.x < 0 ? -1 : 1;
        l.x = face.x * extent.x;
    }
    else if (depth.y <= depth.z)
    {
        face.y = l.y < 0 ? -1 : 1;
        l.y = face.y * extent.y;
    }
    else
    {
        face.z = l.z < 0 ? -1 : 1;
        l.z = face.z * extent.z;
    }
    return hitAt(c + turn(k.rotation, l), turn(k.rotation, face));
}

/** The first of the frame's colliders in `layers` that the move from `a` to `b` ends touching. */
Hit hitColliders(ParticleEmitter e, uint layers, float r, float3 a, float3 b)
{
    ParticleFrame* frame = push.frame;
    ParticleCollider* colliders = frame.colliders;
    uint n = frame.colliderCount;
    for (uint i = 0; i < n; i++)
    {
        ParticleCollider k = colliders[i];
        if ((k.layers & layers) == 0)
        {
            continue;
        }
        precise float3 high = k.positionHigh - e.originHigh;
        precise float3 low = k.positionLow - e.originLow;
        Hit h = hitCollider(k, high + low, r, a, b);
        if (h.hit)
        {
            return h;
        }
    }
    return noHit();
}

/** The height field at grid point (`x`, `z`), clamped to the grid. */
float heightAt(float* heights, uint n, int x, int z)
{
    return heights[uint(clamp(z, 0, int(n) - 1)) * n + uint(clamp(x, 0, int(n) - 1))];
}

/** Whether the move ends below the height field's ground (with the radius): contact on it, normal from its slope. */
Hit hitHeight(ParticleEmitter e, float r, float3 b)
{
    ParticleFrame* frame = push.frame;
    float* heights = frame.heights;
    uint n = frame.heightResolution;
    if (n < 2)
    {
        return noHit();
    }
    float size = frame.heightSize;
    precise float3 high = e.originHigh - frame.heightCenterHigh;
    precise float3 low = e.originLow - frame.heightCenterLow;
    float3 at = (high + low) + b;
    float spacing = size / float(n - 1);
    float2 grid = (at.xz + 0.5 * size) / spacing;
    if (any(grid < 0) || any(grid > float(n - 1)))
    {
        return noHit();
    }
    int2 cell = min(int2(floor(grid)), int2(int(n) - 2, int(n) - 2));
    float2 t = grid - float2(cell);
    float h00 = heightAt(heights, n, cell.x, cell.y);
    float h10 = heightAt(heights, n, cell.x + 1, cell.y);
    float h01 = heightAt(heights, n, cell.x, cell.y + 1);
    float h11 = heightAt(heights, n, cell.x + 1, cell.y + 1);
    float ground = lerp(lerp(h00, h10, t.x), lerp(h01, h11, t.x), t.y);
    precise float groundY = (ground - e.originHigh.y) - e.originLow.y;
    if (!(groundY > b.y - r))
    {
        return noHit();
    }
    float dx = (lerp(h10, h11, t.y) - lerp(h00, h01, t.y)) / spacing;
    float dz = (lerp(h01, h11, t.x) - lerp(h00, h10, t.x)) / spacing;
    return hitAt(float3(b.x, groundY + r, b.z), normalize(float3(-dx, 1, -dz)));
}

/** The collision view's raw depth (reverse, 0 at infinity) at grid sample (`x`, `y`). */
float depthAt(float* depth, uint width, uint height, int x, int y)
{
    return depth[DEPTH_HEADER + uint(clamp(y, 0, int(height) - 1)) * width + uint(clamp(x, 0, int(width) - 1))];
}

/** Grid sample (`x`, `y`) of the depth as a point relative to the view's camera. */
float3 depthPoint(float4x4 inverse, float* depth, uint width, uint height, int x, int y)
{
    float2 ndc = (float2(x, y) + 0.5) / float2(width, height) * 2 - 1;
    float4 p = mul(inverse, float4(ndc, depthAt(depth, width, height, x, y), 1));
    return p.xyz / p.w;
}

/**
 * Whether the move from `a` to `b` crosses the collision view's last depth: in front of it at the start, behind it
 * (by less than `thickness`) at the end; the contact on that surface, the normal from its neighbours.
 */
Hit hitDepth(ParticleEmitter e, float r, float thickness, float3 a, float3 b)
{
    ParticleFrame* frame = push.frame;
    if (frame.depthReady == 0)
    {
        return noHit();
    }
    float* depth = frame.depth;
    float4x4 viewProjection;
    float4x4 inverse;
    for (uint i = 0; i < 16; i++)
    {
        viewProjection[i / 4][i % 4] = depth[i];
        inverse[i / 4][i % 4] = depth[16 + i];
    }
    float3 cameraHigh = float3(depth[32], depth[33], depth[34]);
    float3 cameraLow = float3(depth[35], depth[36], depth[37]);
    uint width = uint(depth[38]);
    uint height = uint(depth[39]);
    precise float3 high = e.originHigh - cameraHigh;
    precise float3 low = e.originLow - cameraLow;
    float3 toOrigin = high + low;
    float4 clip = mul(viewProjection, float4(toOrigin + b, 1));
    if (clip.w <= 0)
    {
        return noHit();
    }
    float2 uv = clip.xy / clip.w * 0.5 + 0.5;
    if (any(uv < 0) || any(uv >= 1))
    {
        return noHit();
    }
    int2 cell = int2(uv * float2(width, height));
    float raw = depthAt(depth, width, height, cell.x, cell.y);
    if (raw <= 0)
    {
        return noHit(); // the sky
    }
    float near = mul(viewProjection, float4(0, 0, 0, 1)).z;
    float scene = near / raw;
    float before = mul(viewProjection, float4(toOrigin + a, 1)).w;
    if (!(clip.w + r > scene && clip.w < scene + thickness && before - r <= scene))
    {
        return noHit();
    }
    float3 surface = depthPoint(inverse, depth, width, height, cell.x, cell.y);
    float3 across = depthPoint(inverse, depth, width, height, cell.x + 1, cell.y) - surface;
    float3 up = depthPoint(inverse, depth, width, height, cell.x, cell.y + 1) - surface;
    float3 n = cross(across, up);
    n = dot(n, n) > 1e-12 ? normalize(n) : normalize(-surface);
    n = dot(n, surface) > 0 ? -n : n; // towards the camera
    return hitAt(surface - toOrigin + n * r, n);
}

#ifdef PARTICLE_RAYS
/** The distance along the ray to the nearest triangle of `scene`'s top level within `far`, or -1 (opaque, all). */
float nearest(RayScene* scene, float3 origin, float3 direction, float far)
{
    RayDesc ray;
    ray.Origin = origin;
    ray.Direction = direction;
    ray.TMin = 0;
    ray.TMax = far;
    RayQuery<RAY_FLAG_FORCE_OPAQUE | RAY_FLAG_SKIP_PROCEDURAL_PRIMITIVES> query;
    query.TraceRayInline(scenes[scene.slot], RAY_FLAG_NONE, 0xff, ray);
    while (query.Proceed())
    {
    }
    return query.CommittedStatus() == COMMITTED_TRIANGLE_HIT ? query.CommittedRayT() : -1;
}

/**
 * Whether the move from `a` to `b` (the radius further) crosses a triangle of the frame's top level: the contact the
 * radius before it, the normal from two more rays beside the first (or against the move where they miss).
 */
Hit hitRays(ParticleEmitter e, float r, float3 a, float3 b)
{
    ParticleFrame* frame = push.frame;
    uint64_t address = frame.rays;
    float3 d = b - a;
    float length = length(d);
    if (address == 0 || length < 1e-6)
    {
        return noHit();
    }
    RayScene* scene = (RayScene*) address;
    precise float3 high = e.originHigh - frame.raysOriginHigh;
    precise float3 low = e.originLow - frame.raysOriginLow;
    float3 from = (high + low) + a;
    d /= length;
    float t = nearest(scene, from, d, length + r);
    if (t < 0)
    {
        return noHit();
    }
    float3 side = normalize(cross(d, abs(d.y) < 0.9 ? float3(0, 1, 0) : float3(1, 0, 0)));
    float3 up = cross(side, d);
    float apart = max(0.01, r);
    float t1 = nearest(scene, from + side * apart, d, length + r + 4 * apart);
    float t2 = nearest(scene, from + up * apart, d, length + r + 4 * apart);
    float3 n = -d;
    if (t1 >= 0 && t2 >= 0)
    {
        float3 p = d * t;
        float3 c = cross(side * apart + d * t1 - p, up * apart + d * t2 - p);
        n = dot(c, c) > 1e-12 ? normalize(c) : -d;
        n = dot(n, d) > 0 ? -n : n;
    }
    return hitAt(a + d * max(0.0, t - r), n);
}
#else
Hit hitRays(ParticleEmitter e, float r, float3 a, float3 b)
{
    return noHit(); // the base variant: programs have no ray collision here
}
#endif

/** How much of an attractor's strength reaches `distance`: falling to 0 at `radius` (0: all of it). */
float falloff(float distance, float radius)
{
    return radius > 0 ? max(0.0, 1 - distance / radius) : 1.0;
}

/**
 * Moves `p` on by `h` seconds under the updaters (CpuSimulation.step): gravity, linear drag and wind integrated
 * exactly, the forces that depend on the position (curl noise, vortices, attractors, vector fields) from where it is
 * at the step's start, then orbits (exact turns) and speed limits, then collision (the first method of each chain the
 * GPU has; stuck particles only age), then the attributes over life from those at birth. Fields are by their index in
 * alphabetical order of their names.
 */
void step<K : ICurveKeys>(Emitting m, K keys, uint first, inout Particle p, float4 color0, float size0, float h)
{
    uint count = (m.program[0] >> 8) & 0xff;
    ParticleEmitter e = m.emitter;
    p.flags &= ~PARTICLE_COLLIDED;
    bool stuck = (p.flags & PARTICLE_STUCK) != 0; // frozen where it hit: it only ages
    float3 a = 0;
    float k = 0;
    uint op = first;
    bool over = false;
    bool kill = false;
    bool after = false;
    bool plugins = false;
    // Where the emitter is in its particles' space: its frame's origin, or its offset from theirs
    float3 center = (e.flags & EMITTER_LOCAL) != 0 ? 0 : e.position;
    float3 x = p.position;
    for (uint i = 0; i < count; i++)
    {
        uint code = m.program[op] & 0xff;
        uint salt = m.program[op + 1];
        switch (code)
        {
        case OP_GRAVITY:
            a += e.down * (GRAVITY * value(m, keys, op, salt, 0, p));
            break;
        case OP_DRAG:
            k += value(m, keys, op, salt, 0, p);
            break;
        case OP_WIND: // coupling, gusts, scale: a drag towards the wind, exact
            float coupling = value(m, keys, op, salt, 0, p);
            float gust = noise(float3(e.time * GUST_RATE, 0, 0), combine(e.seed, salt + 8)).x;
            k += coupling;
            a += e.wind * (coupling * value(m, keys, op, salt, 2, p) * (1 + value(m, keys, op, salt, 1, p) * gust));
            break;
        case OP_CURL: // frequency, octaves, speed, strength
            uint octaves = uint(clamp(round(value(m, keys, op, salt, 1, p)), 1.0, 4.0));
            a += value(m, keys, op, salt, 3, p) * curlNoise(x, value(m, keys, op, salt, 0, p),
                e.time * value(m, keys, op, salt, 2, p), octaves, combine(e.seed, salt + 8));
            break;
        case OP_VORTEX: // axis x, y, z, pull, strength
            float3 axis = frameAxis(e, vectorOf(m, keys, op, salt, 0, p));
            float3 d = x - center;
            float3 across = d - axis * dot(d, axis);
            float r = length(across);
            if (r > 1e-6)
            {
                a += (value(m, keys, op, salt, 4, p) * cross(axis, across) - value(m, keys, op, salt, 3, p) * across)
                    / r;
            }
            break;
        case OP_POINT_FORCE: // kill radius, radius, strength, x, y, z
            float3 to = center + frameVector(e, vectorOf(m, keys, op, salt, 3, p)) - x;
            float distance = length(to);
            kill = kill || distance < value(m, keys, op, salt, 0, p);
            if (distance > 1e-6)
            {
                a += to / distance * (value(m, keys, op, salt, 2, p)
                    * falloff(distance, value(m, keys, op, salt, 1, p)));
            }
            break;
        case OP_LINE: // axis x, y, z, radius, strength, x, y, z
            float3 from = x - center - frameVector(e, vectorOf(m, keys, op, salt, 5, p));
            float3 along = frameAxis(e, vectorOf(m, keys, op, salt, 0, p));
            float3 off = from - along * dot(from, along);
            float apart = length(off);
            if (apart > 1e-6)
            {
                a -= off / apart * (value(m, keys, op, salt, 4, p) * falloff(apart, value(m, keys, op, salt, 3, p)));
            }
            break;
        case OP_FIELD: // field, size x, y, z, strength, tile: in the emitter's frame, about it
            uint slot = m.program[op + 2];
            if (slot != NO_TEXTURE)
            {
                float3 local = x - center;
                local = (e.flags & EMITTER_LOCAL) != 0 ? local : turn(float4(-e.rotation.xyz, e.rotation.w), local);
                float3 uvw = local / vectorOf(m, keys, op, salt, 1, p) + 0.5;
                uint sampler = value(m, keys, op, salt, 5, p) > 0.5 ? LINEAR_REPEAT : LINEAR_CLAMP;
                float3 field = volumes[NonUniformResourceIndex(slot)].SampleLevel(samplers[sampler], uvw, 0).xyz;
                a += frameVector(e, field) * value(m, keys, op, salt, 4, p);
            }
            break;
        case OP_ORBIT:
        case OP_SPEED_LIMIT:
        case OP_COLLIDERS:
        case OP_HEIGHT:
        case OP_DEPTH:
        case OP_RAYS:
            after = true;
            break;
        case OP_OVER_SIZE:
        case OP_OVER_COLOR:
            over = true;
            break;
        default:
            plugins = true; // the game's own modules, after the motion
            break;
        }
        op = skip(m, op);
    }
    if (stuck)
    {
        a = 0;
        k = 0;
        after = false;
        p.velocity = 0;
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
    if ((m.emitter.flags & EMITTER_MESH) != 0)
    {
        p.rotation += sqrt(dot(v, v)) * h / max(0.5 * p.size.x, 1e-3); // mesh particles roll as far as they move
    }
    for (uint i = 0, op = first; after && i < count; i++)
    {
        uint code = m.program[op] & 0xff;
        uint salt = m.program[op + 1];
        if (code == OP_ORBIT) // axis x, y, z, speed: a turn about the axis, exact
        {
            float3 axis = frameAxis(e, vectorOf(m, keys, op, salt, 0, p));
            float angle = value(m, keys, op, salt, 3, p) * h;
            float c = cos(angle);
            float s = sin(angle);
            float3 q = p.position - center;
            p.position = center + q * c + cross(axis, q) * s + axis * (dot(axis, q) * (1 - c));
            float3 w = p.velocity;
            p.velocity = w * c + cross(axis, w) * s + axis * (dot(axis, w) * (1 - c));
        }
        else if (code == OP_SPEED_LIMIT)
        {
            float limit = value(m, keys, op, salt, 0, p);
            float speed = length(p.velocity);
            if (speed > limit)
            {
                p.velocity *= max(0.0, limit) / speed;
            }
        }
        op = skip(m, op);
    }
    // Collision: the move from the step's start, in world axes about the origin (local-space particles turned out
    // and back), against each method the program has; the first hit responds.
    bool local = (e.flags & EMITTER_LOCAL) != 0;
    float3 start = local ? turn(e.rotation, x) : x;
    float4 back = float4(-e.rotation.xyz, e.rotation.w);
    for (uint i = 0, op = first; after && i < count; i++)
    {
        uint code = m.program[op] & 0xff;
        uint salt = m.program[op + 1];
        if (code == OP_COLLIDERS || code == OP_HEIGHT || code == OP_DEPTH || code == OP_RAYS)
        {
            uint shift = code == OP_COLLIDERS ? 1 : 0; // colliders have their layers before the radius
            float radius = value(m, keys, op, salt, 2 + shift, p);
            radius = radius > 0 ? radius : 0.5 * p.size.x;
            float3 end = local ? turn(e.rotation, p.position) : p.position;
            Hit hit;
            if (code == OP_COLLIDERS)
            {
                hit = hitColliders(e, uint(value(m, keys, op, salt, 2, p) + 0.5), radius, start, end);
            }
            else if (code == OP_HEIGHT)
            {
                hit = hitHeight(e, radius, end);
            }
            else if (code == OP_RAYS)
            {
                hit = hitRays(e, radius, start, end);
            }
            else
            {
                hit = hitDepth(e, radius, value(m, keys, op, salt, 5, p), start, end);
            }
            if (hit.hit)
            {
                uint response = uint(round(value(m, keys, op, salt, 3 + shift, p)));
                p.flags |= PARTICLE_COLLIDED;
                if (response == RESPONSE_KILL)
                {
                    kill = true;
                    break;
                }
                float3 velocity = local ? turn(e.rotation, p.velocity) : p.velocity;
                float into = dot(velocity, hit.normal);
                if (response == RESPONSE_STICK || response == RESPONSE_SNAP)
                {
                    p.flags |= PARTICLE_STUCK;
                    velocity = 0;
                }
                else if (into < 0)
                {
                    float keep = 1 - value(m, keys, op, salt, 1, p);
                    float bounce = response == RESPONSE_BOUNCE ? -value(m, keys, op, salt, 4 + shift, p) : 0;
                    velocity = (velocity - into * hit.normal) * keep + bounce * into * hit.normal;
                }
                p.position = local ? turn(back, hit.contact) : hit.contact;
                p.velocity = local ? turn(back, velocity) : velocity;
                break;
            }
        }
        op = skip(m, op);
    }
    if (kill)
    {
        p.lifetime = p.age; // within an attractor's kill radius: dead from now, gone at the next count
    }
#ifdef PARTICLE_MODULES
    if (plugins)
    {
        op = first;
        for (uint i = 0; i < count; i++)
        {
            if ((m.program[op] & 0xff) >= OP_PLUGIN)
            {
                plugin(m, keys, op, p, h, false);
            }
            op = skip(m, op);
        }
    }
#endif
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
        if ((m.program[op] & 0xff) == OP_INHERIT)
        {
            uint salt = m.program[op + 1];
            color = value(m, keys, op, salt, 0, p) >= 0.5;
            size = value(m, keys, op, salt, 1, p) >= 0.5;
            inherit = value(m, keys, op, salt, 2, p);
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
