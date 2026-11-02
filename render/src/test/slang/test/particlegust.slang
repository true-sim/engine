// For tests: a game's own particle modules (effects.md E24), which the build collects with the built-in ones into
// kernel variants of the test's (particle-modules/TestBuiltInModules.slang and its rays variant) and lists in
// TestBuiltInModules. "game.gust" pushes particles along +X by its strength in metres per second squared, on the CPU
// too (its Java twin, GustTwin); "game.tint" turns newborn blue.
module particlegust;

import particles.particle;

/** Pushes particles along +X by its strength, in m/s^2. */
[ParticleModule("game.gust")]
[ModuleTwin("com.truesim.engine.render.particles.GustTwin")]
public struct Gust
{
    public float strength = 1;

    public void update(inout ParticleStep s, inout Particle p)
    {
        p.velocity.x += strength * s.h;
        p.position.x += 0.5 * strength * s.h * s.h;
    }
};

/** Newborn blue, by the share given. */
[ParticleModule("game.tint")]
public struct Tint
{
    public float blue = 1;

    public void init(inout ParticleInit s, inout Particle p)
    {
        p.color = float4(0, 0, blue, 1);
    }
};
