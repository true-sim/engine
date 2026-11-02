// The built-in forces (E24): updaters summing what moves a particle over a step (accelerations and drag, integrated
// exactly together; forces that depend on where it is, from where it was at the step's start), then orbits and the
// speed limit after the motion. As scene-effects' CpuSimulation (Forces), where it has them.
module forces;

import particles.particle;
import bindless;

/** The lattice value of value noise at `c` for `seed`, in [-1, 1) (scene-effects' CurlNoise.lattice). */
public float lattice(int3 c, uint seed)
{
    return float(combine(combine(combine(seed, uint(c.x)), uint(c.y)), uint(c.z)) >> 8) * (2.0 / 16777216.0) - 1;
}

/** Value noise for `seed` at `x` and its gradient: (value, d/dx, d/dy, d/dz) (CurlNoise.noise). */
public float4 noise(float3 x, uint seed)
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
public float3 curlNoise(float3 x, float frequency, float drift, uint octaves, uint seed)
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
public float3 frameAxis(ParticleEmitter e, float3 v)
{
    float3 a = (e.flags & EMITTER_LOCAL) != 0 ? v : turn(e.rotation, v);
    float length2 = dot(a, a);
    return length2 > 1e-12 ? a * rsqrt(length2) : float3(0, 1, 0);
}

/** A vector of the emitter's frame in its particles' space. */
public float3 frameVector(ParticleEmitter e, float3 v)
{
    return (e.flags & EMITTER_LOCAL) != 0 ? v : turn(e.rotation, v);
}

/** How much of an attractor's strength reaches `distance`: falling to 0 at `radius` (0: all of it). */
public float falloff(float distance, float radius)
{
    return radius > 0 ? max(0.0, 1 - distance / radius) : 1.0;
}

/** Gravity along world down, scaled. */
[ParticleModule("force.gravity")]
[BuiltInTwin]
public struct GravityForce
{
    public float scale = 1;

    public void force(inout ParticleStep s, Particle p)
    {
        s.a += s.emitter.down * (GRAVITY * scale);
    }
};

/** Linear drag: the velocity decays by the coefficient per second. */
[ParticleModule("force.drag")]
[BuiltInTwin]
public struct DragForce
{
    public float coefficient = 1;

    public void force(inout ParticleStep s, Particle p)
    {
        s.k += coefficient;
    }
};

/** A drag towards the world's wind (scaled), exact, in gusts: its strength varies by noise over time. */
[ParticleModule("force.wind")]
[BuiltInTwin]
public struct WindForce
{
    public float coupling = 1;
    public float gusts = 0;
    public float scale = 1;

    public void force(inout ParticleStep s, Particle p)
    {
        float gust = noise(float3(s.emitter.time * GUST_RATE, 0, 0), combine(s.emitter.seed, s.salt + 8)).x;
        s.k += coupling;
        s.a += s.emitter.wind * (coupling * scale * (1 + gusts * gust));
    }
};

/** Curl noise: a swirling, divergence-free push, drifting at its speed. */
[ParticleModule("force.curl")]
[BuiltInTwin]
public struct CurlForce
{
    public float frequency = 1;
    public float octaves = 1;
    public float speed = 0;
    public float strength = 1;

    public void force(inout ParticleStep s, Particle p)
    {
        uint n = uint(clamp(round(octaves), 1.0, 4.0));
        s.a += strength * curlNoise(s.start, frequency, s.emitter.time * speed, n, combine(s.emitter.seed, s.salt + 8));
    }
};

/** A whirl about an axis through the emitter (of its frame): around it by the strength, towards it by the pull. */
[ParticleModule("force.vortex")]
[BuiltInTwin]
public struct VortexForce
{
    public float axisX = 0;
    public float axisY = 1;
    public float axisZ = 0;
    public float pull = 0;
    public float strength = 1;

    public void force(inout ParticleStep s, Particle p)
    {
        float3 axis = frameAxis(s.emitter, float3(axisX, axisY, axisZ));
        float3 d = s.start - s.center;
        float3 across = d - axis * dot(d, axis);
        float r = length(across);
        if (r > 1e-6)
        {
            s.a += (strength * cross(axis, across) - pull * across) / r;
        }
    }
};

/** An attractor at a point of the emitter's frame, falling off to its radius; particles within the kill radius die. */
[ParticleModule("force.point")]
[BuiltInTwin]
public struct PointForce
{
    public float killRadius = 0;
    public float radius = 0;
    public float strength = 1;
    public float x = 0;
    public float y = 0;
    public float z = 0;

    public void force(inout ParticleStep s, Particle p)
    {
        float3 to = s.center + frameVector(s.emitter, float3(x, y, z)) - s.start;
        float distance = length(to);
        s.kill = s.kill || distance < killRadius;
        if (distance > 1e-6)
        {
            s.a += to / distance * (strength * falloff(distance, radius));
        }
    }
};

/** An attractor along a line of the emitter's frame (through x, y, z along the axis), falling off to its radius. */
[ParticleModule("force.line")]
[BuiltInTwin]
public struct LineForce
{
    public float axisX = 0;
    public float axisY = 1;
    public float axisZ = 0;
    public float radius = 0;
    public float strength = 1;
    public float x = 0;
    public float y = 0;
    public float z = 0;

    public void force(inout ParticleStep s, Particle p)
    {
        float3 from = s.start - s.center - frameVector(s.emitter, float3(x, y, z));
        float3 along = frameAxis(s.emitter, float3(axisX, axisY, axisZ));
        float3 off = from - along * dot(from, along);
        float apart = length(off);
        if (apart > 1e-6)
        {
            s.a -= off / apart * (strength * falloff(apart, radius));
        }
    }
};

/** A vector field (a 3D texture) over sizeX by sizeY by sizeZ about the emitter, in its frame; tiled at 1. */
[ParticleModule("force.field")]
public struct FieldForce
{
    public ModuleTexture field;
    public float sizeX = 1;
    public float sizeY = 1;
    public float sizeZ = 1;
    public float strength = 1;
    public float tile = 0;

    public void force(inout ParticleStep s, Particle p)
    {
        if (field == NO_TEXTURE)
        {
            return;
        }
        ParticleEmitter e = s.emitter;
        float3 local = s.start - s.center;
        local = (e.flags & EMITTER_LOCAL) != 0 ? local : turn(float4(-e.rotation.xyz, e.rotation.w), local);
        float3 uvw = local / float3(sizeX, sizeY, sizeZ) + 0.5;
        uint sampler = tile > 0.5 ? LINEAR_REPEAT : LINEAR_CLAMP;
        float3 v = volumes[NonUniformResourceIndex(field)].SampleLevel(samplers[sampler], uvw, 0).xyz;
        s.a += frameVector(e, v) * strength;
    }
};

/** A turn about an axis through the emitter (of its frame) at its speed in radians per second, exact. */
[ParticleModule("force.orbit")]
[BuiltInTwin]
public struct OrbitForce
{
    public float axisX = 0;
    public float axisY = 1;
    public float axisZ = 0;
    public float speed = 1;

    public void after(inout ParticleStep s, inout Particle p)
    {
        float3 axis = frameAxis(s.emitter, float3(axisX, axisY, axisZ));
        float angle = speed * s.h;
        float c = cos(angle);
        float sine = sin(angle);
        float3 q = p.position - s.center;
        p.position = s.center + q * c + cross(axis, q) * sine + axis * (dot(axis, q) * (1 - c));
        float3 w = p.velocity;
        p.velocity = w * c + cross(axis, w) * sine + axis * (dot(axis, w) * (1 - c));
    }
};

/** The most speed a particle keeps, in metres per second. */
[ParticleModule("limit.speed")]
[BuiltInTwin]
public struct SpeedLimit
{
    public float speed = 1;

    public void after(inout ParticleStep s, inout Particle p)
    {
        float v = length(p.velocity);
        if (v > speed)
        {
            p.velocity *= max(0.0, speed) / v;
        }
    }
};
