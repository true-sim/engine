// For tests: a game's own particle modules (effects step 20) in a kernel variant of its own. "game.gust" pushes
// particles along +X by its strength in metres per second squared; "game.tint" turns newborn blue. The simulation's
// entry points ([shader("compute")] countMain, updateMain, spawnMain, ...) come from the include.
#define PARTICLE_MODULES
import particles.particle;

/** Pushes particles along +X: value 0, its strength. */
struct Gust : IParticleModule
{
    static void init(inout Particle p, ParticleModuleInput in)
    {
    }

    static void update(inout Particle p, ParticleModuleInput in)
    {
        p.velocity.x += in.values[0] * in.h;
        p.position.x += 0.5 * in.values[0] * in.h * in.h;
    }
};

/** Newborn blue, by the share given: value 0. */
struct Tint : IParticleModule
{
    static void init(inout Particle p, ParticleModuleInput in)
    {
        p.color = float4(0, 0, in.values[0], 1);
    }

    static void update(inout Particle p, ParticleModuleInput in)
    {
    }
};

/** The game's modules by their index: as ParticleModules lists their types. */
struct ParticleModules
{
    static void init(uint i, inout Particle p, ParticleModuleInput in)
    {
        if (i == 1)
        {
            Tint.init(p, in);
        }
    }

    static void update(uint i, inout Particle p, ParticleModuleInput in)
    {
        if (i == 0)
        {
            Gust.update(p, in);
        }
    }
};

#include "particles/simulate.slang"
