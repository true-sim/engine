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
import bindless;
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
        default:
            over = true;
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
        if ((e.flags & EMITTER_LOCAL) == 0)
        {
            p.position += e.shift; // the origin rebased: the same place in the world
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
        p.velocity = turn(q, p.velocity) + e.inherited;
        p.position = at + turn(q, p.position);
        return;
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
