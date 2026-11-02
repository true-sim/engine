// Ribbons of several strands (effects.md §2.5): an emitter's particles are in spawn order in its range, but those of
// one ribbon are not neighbours when its ribbons interleave. orderMain, a group per such emitter, sorts its particles
// by the second key (ribbon, spawn index) into the order buffer: a stable counting sort, ribbon by ribbon in spawn
// order, which the ribbon draws follow.
import particle;

struct ParticleRibbonPush
{
    Particle* particles;           // the half of the pool the simulation wrote this frame
    ParticleEmitter* emitters;     // the frame's list
    uint* state;                   // the emitter instances' state
    uint* order;                   // per slot of the pool: the particles of each range in ribbon order
    uint2* list;                   // per emitter sorted: its place in the list and its ribbons
    uint count;
};

[[vk::push_constant]]
ConstantBuffer<ParticleRibbonPush> push;

groupshared uint offsets[CHUNK];
groupshared uint ribbonOf[CHUNK];

[shader("compute")]
[numthreads(64, 1, 1)]
void orderMain(uint3 group : SV_GroupID, uint lane : SV_GroupIndex)
{
    if (group.x >= push.count)
    {
        return;
    }
    uint2* list = push.list;
    uint2 entry = list[group.x];
    ParticleEmitter* emitters = push.emitters;
    ParticleEmitter e = emitters[entry.x];
    uint ribbons = min(entry.y, CHUNK);
    uint* state = push.state;
    uint n = state[e.instance * STATE_WORDS + STATE_ALIVE];
    Particle* particles = push.particles;
    uint* order = push.order;
    offsets[lane] = 0;
    GroupMemoryBarrierWithGroupSync();
    for (uint base = 0; base < n; base += CHUNK)
    {
        if (base + lane < n)
        {
            InterlockedAdd(offsets[asuint(particles[e.first + base + lane].custom.y) % ribbons], 1);
        }
    }
    GroupMemoryBarrierWithGroupSync();
    if (lane == 0)
    {
        uint total = 0;
        for (uint r = 0; r < ribbons; r++)
        {
            uint c = offsets[r];
            offsets[r] = total;
            total += c;
        }
    }
    GroupMemoryBarrierWithGroupSync();
    for (uint base = 0; base < n; base += CHUNK)
    {
        uint i = base + lane;
        uint ribbon = i < n ? asuint(particles[e.first + i].custom.y) % ribbons : 0xffffffffu;
        ribbonOf[lane] = ribbon;
        GroupMemoryBarrierWithGroupSync();
        if (i < n)
        {
            uint rank = 0;
            for (uint j = 0; j < lane; j++)
            {
                rank += ribbonOf[j] == ribbon ? 1 : 0;
            }
            order[e.first + offsets[ribbon] + rank] = i;
        }
        GroupMemoryBarrierWithGroupSync();
        if (lane < ribbons)
        {
            uint added = 0;
            for (uint j = 0; j < CHUNK; j++)
            {
                added += ribbonOf[j] == lane ? 1 : 0;
            }
            offsets[lane] += added;
        }
        GroupMemoryBarrierWithGroupSync();
    }
}
