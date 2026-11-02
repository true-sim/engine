// For tests: particle.slang's hash on fixed vectors. Each input is three words: the function (0 pcg, 1 combine,
// 2 random as float bits) and its arguments; each output one word.
import particles.particle;

struct ParticleHashPush
{
    uint* inputs;
    uint* outputs;
    uint count;
};

[[vk::push_constant]]
ConstantBuffer<ParticleHashPush> push;

[shader("compute")]
[numthreads(64, 1, 1)]
void hashMain(uint3 id : SV_DispatchThreadID)
{
    if (id.x >= push.count)
    {
        return;
    }
    uint* inputs = push.inputs;
    uint* outputs = push.outputs;
    uint kind = inputs[3 * id.x];
    uint a = inputs[3 * id.x + 1];
    uint b = inputs[3 * id.x + 2];
    outputs[id.x] = kind == 0 ? pcg(a) : kind == 1 ? combine(a, b) : asuint(random(a, b));
}
