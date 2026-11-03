// Interpolation between fixed steps (application-api.md D91, InstanceMotion): a thread per motion slot blends an
// instance's pose at the world's last two steps by the frame's blend factor and writes it into the instance table,
// after the scene upload and before anything reads the instances (culling, drawing, ray tracing). Each slot has a pose
// in each of two buffers, the last step's in `current`, the step before's in `previous` (which only a move's first
// step, or a step after steps not uploaded, writes: D122 measured this against both poses per step). Two layouts
// (InstanceMotion.Layout):
// - PRECISE: positions in two parts as instances keep them (Pose, 64 bytes);
// - PACKED: positions as floats from the records' origin, rotations as four snorm16, scales as one float where uniform,
//   else three halves, each pose with its own flag (PackedPose, 32 bytes).
// Positions blend as the start's plus the blended move added to the low part (relative() subtracts the parts apart,
// so a low part larger than usual loses nothing that matters); rotations by normalized linear interpolation, the short
// way; scales linearly. Slots without an instance (0) are holes.
import meshlets.common;

/** PRECISE: one instance's pose at one step: 64 bytes. */
[HostLayout]
struct Pose
{
    float4 rotation;
    float3 position;       // world position, high part
    uint instance;         // the instance id plus one; 0: a hole
    float3 positionLow;
    float pad0;
    float3 scale;
    float pad1;
};

/** PACKED: one instance's pose at one step: 32 bytes. */
[HostLayout]
struct PackedPose
{
    float3 position;       // from the records' origin
    uint instance;         // the instance id plus one (0: a hole); POSE_UNIFORM: the scale is one float
    uint2 rotation;        // four snorm16: x and y, z and w
    uint2 scale;           // uniform: the float in x; else three halves
};

static const uint POSE_UNIFORM = 0x80000000u;
static const uint LAYOUT_PRECISE = 0;

struct BlendPush
{
    uint* current;         // the last step's poses
    uint* previous;        // the step before's
    Instance* instances;
    float3 originHigh;     // the packed layout's origin, in two parts
    uint count;            // slots, holes included
    float3 originLow;
    float blend;           // 0: the step before, 1: the last step
    uint layout;
};

[[vk::push_constant]]
ConstantBuffer<BlendPush> push;

float4 unpackRotation(uint2 q)
{
    float4 r = float4(float(int(q.x << 16) >> 16), float(int(q.x) >> 16), float(int(q.y << 16) >> 16),
        float(int(q.y) >> 16)) / 32767.0;
    return normalize(r);
}

/** A half's bits as a float, by hand: f16tof32 needs the Float16 capability, which devices may not have. */
float halfToFloat(uint h)
{
    uint sign = (h & 0x8000u) << 16;
    uint exponent = (h >> 10) & 0x1fu;
    uint mantissa = h & 0x3ffu;
    if (exponent == 0)
    {
        return asfloat(sign) + (sign != 0 ? -1.0 : 1.0) * float(mantissa) * 5.9604645e-8; // subnormal: m * 2^-24
    }
    if (exponent == 31)
    {
        return asfloat(sign | 0x7f800000u | (mantissa << 13));
    }
    return asfloat(sign | ((exponent + 112u) << 23) | (mantissa << 13));
}

float3 unpackScale(uint2 s, uint instance)
{
    if ((instance & POSE_UNIFORM) != 0)
    {
        return float3(asfloat(s.x));
    }
    return float3(halfToFloat(s.x & 0xffffu), halfToFloat(s.x >> 16), halfToFloat(s.y & 0xffffu));
}

float4 nlerp(float4 from, float4 to, float a)
{
    if (dot(from, to) < 0)
    {
        to = -to;
    }
    return normalize(lerp(from, to, a));
}

[shader("compute")]
[numthreads(64, 1, 1)]
void blendMain(uint3 id : SV_DispatchThreadID)
{
    uint i = id.x;
    if (i >= push.count)
    {
        return;
    }
    float a = push.blend;
    Instance* instances = push.instances;
    if (push.layout == LAYOUT_PRECISE)
    {
        Pose* current = (Pose*) push.current;
        Pose* previous = (Pose*) push.previous;
        uint instance = current[i].instance;
        if (instance == 0)
        {
            return;
        }
        uint t = instance - 1;
        float3 high = previous[i].position;
        float3 low = previous[i].positionLow;
        // precise: the high parts cancel exactly only if nothing reorders the sums (as relative() does)
        precise float3 highMove = current[i].position - high;
        precise float3 lowMove = current[i].positionLow - low;
        precise float3 move = highMove + lowMove;
        instances[t].rotation = nlerp(previous[i].rotation, current[i].rotation, a);
        instances[t].position = high;
        instances[t].positionLow = low + a * move;
        instances[t].scale = lerp(previous[i].scale, current[i].scale, a);
    }
    else
    {
        PackedPose* current = (PackedPose*) push.current;
        PackedPose* previous = (PackedPose*) push.previous;
        uint instance = current[i].instance;
        if (instance == 0)
        {
            return;
        }
        uint before = previous[i].instance; // each pose says its own scale's form
        uint t = (instance & ~POSE_UNIFORM) - 1;
        float3 from = previous[i].position;
        instances[t].rotation = nlerp(unpackRotation(previous[i].rotation), unpackRotation(current[i].rotation), a);
        instances[t].position = push.originHigh;
        instances[t].positionLow = push.originLow + from + a * (current[i].position - from);
        instances[t].scale = lerp(unpackScale(previous[i].scale, before), unpackScale(current[i].scale, instance), a);
    }
}
