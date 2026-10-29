// A game's own compute shader reading the engine's instances: their layout from engine-render's module.
import meshlets.common;

struct PositionsPush
{
    Instance* instances;   // GpuScene.instanceAddress()
    float4* positions;     // out: each instance's world position, w 1 if it is live
    uint count;
};

[[vk::push_constant]]
ConstantBuffer<PositionsPush> push;

[shader("compute")]
[numthreads(64, 1, 1)]
void positionsMain(uint3 id : SV_DispatchThreadID)
{
    if (id.x >= push.count)
    {
        return;
    }
    Instance instance = push.instances[id.x];
    bool live = (instance.mesh & INSTANCE_LIVE) != 0;
    push.positions[id.x] = float4(instance.position + instance.positionLow, live ? 1.0 : 0.0);
}
