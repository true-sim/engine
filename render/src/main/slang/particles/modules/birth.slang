// The built-in initializers of a particle's attributes (E24): its lifetime, velocity, size, color, rotation and
// flipbook start, and what a child takes of its parent. As scene-effects' CpuSimulation (Init).
module birth;

import particles.particle;

/** How long it lives, in seconds. */
[ParticleModule("init.lifetime")]
[BuiltInTwin]
public struct LifetimeInit
{
    public float lifetime = 1;

    public void init(inout ParticleInit s, inout Particle p)
    {
        p.lifetime = lifetime;
    }
};

/** Speed along the shape's direction, in metres per second. */
[ParticleModule("init.speed")]
[BuiltInTwin]
public struct SpeedInit
{
    public float speed = 1;

    public void init(inout ParticleInit s, inout Particle p)
    {
        p.velocity += s.direction * speed;
    }
};

/** A velocity added, in metres per second. */
[ParticleModule("init.velocity")]
[BuiltInTwin]
public struct VelocityInit
{
    public float x = 0;
    public float y = 0;
    public float z = 0;

    public void init(inout ParticleInit s, inout Particle p)
    {
        p.velocity += float3(x, y, z);
    }
};

/** Its size, in metres across: the size from birth that sizes over life scale. */
[ParticleModule("init.size")]
[BuiltInTwin]
public struct SizeInit
{
    public float size = 0.1;

    public void init(inout ParticleInit s, inout Particle p)
    {
        s.size0 = size;
        p.size = size;
    }
};

/** Its color: the color from birth that colors over life scale. */
[ParticleModule("init.color")]
[BuiltInTwin]
public struct ColorInit
{
    public ModuleColor color = float4(1, 1, 1, 1);

    public void init(inout ParticleInit s, inout Particle p)
    {
        s.color0 = color;
        p.color = color;
    }
};

/**
 * What a particle an event spawns takes of its parent: its color and size (at 0.5 or more), a share of its velocity.
 * Read where the event spawns it (simulate.slang's spawnChild, by OP_INIT_INHERIT); nothing at birth.
 */
[ParticleModule("init.inherit")]
[BuiltInTwin]
public struct InheritInit
{
    public float color = 0;
    public float size = 0;
    public float velocity = 0;

    public void init(inout ParticleInit s, inout Particle p)
    {
    }
};

/** Its rotation, in radians: billboards turn by it. */
[ParticleModule("init.rotation")]
[BuiltInTwin]
public struct RotationInit
{
    public float angle = 0;

    public void init(inout ParticleInit s, inout Particle p)
    {
        p.rotation = angle;
    }
};

/** Where its flipbook starts, a fraction of its frames. */
[ParticleModule("init.frame")]
[BuiltInTwin]
public struct FrameInit
{
    public float frame = 0;

    public void init(inout ParticleInit s, inout Particle p)
    {
        p.custom.x = frame;
    }
};
