// Interpolation between fixed steps (application-api.md D91, InstanceMotion): a thread per motion record blends an
// instance's pose at the world's last two steps by the frame's blend factor and writes it into the instance table,
// after the scene upload and before anything reads the instances (culling, drawing, ray tracing). Positions blend as
// the start's two parts plus the blended move added to the low part (relative() subtracts the parts apart, so a low
// part larger than usual loses nothing that matters); rotations by normalized linear interpolation, the short way;
// scales linearly. Records without an instance (0) are holes.
import meshlets.common;

/** One instance's pose at the world's last two steps: 112 bytes. */
[HostLayout]
struct Motion
{
    float4 rotationFrom;   // quaternion x, y, z, w at the step before
    float4 rotationTo;     // at the last step
    float3 position;       // world position at the step before, high part
    uint instance;         // the instance id plus one; 0: a hole
    float3 positionLow;    // the rest of it
    float pad0;
    float3 move;           // the position at the last step minus that at the step before
    float pad1;
    float3 scaleFrom;
    float pad2;
    float3 scaleTo;
    float pad3;
};

struct BlendPush
{
    Motion* motions;
    Instance* instances;
    uint count;            // records, holes included
    float blend;           // 0: the step before, 1: the last step
};

[[vk::push_constant]]
ConstantBuffer<BlendPush> push;

[shader("compute")]
[numthreads(64, 1, 1)]
void blendMain(uint3 id : SV_DispatchThreadID)
{
    if (id.x >= push.count)
    {
        return;
    }
    Motion* motions = push.motions;
    uint instance = motions[id.x].instance;
    if (instance == 0)
    {
        return;
    }
    float a = push.blend;
    float4 from = motions[id.x].rotationFrom;
    float4 to = motions[id.x].rotationTo;
    if (dot(from, to) < 0)
    {
        to = -to;
    }
    Instance* instances = push.instances;
    uint i = instance - 1;
    instances[i].rotation = normalize(lerp(from, to, a));
    instances[i].position = motions[id.x].position;
    instances[i].positionLow = motions[id.x].positionLow + a * motions[id.x].move;
    instances[i].scale = lerp(motions[id.x].scaleFrom, motions[id.x].scaleTo, a);
}
