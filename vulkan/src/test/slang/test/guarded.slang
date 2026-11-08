// A load through a pointer that may be null, guarded by a push constant: run with the guard off and the pointer 0.

struct GuardedPush
{
    uint* values;
    uint* maybe; // 0 unless mode is 1
    uint mode;
    uint count;
};

[[vk::push_constant]]
ConstantBuffer<GuardedPush> push;

[shader("compute")]
[numthreads(64, 1, 1)]
void guardedMain(uint3 id : SV_DispatchThreadID)
{
    if (id.x >= push.count)
    {
        return;
    }
    uint v = push.mode == 1 ? push.maybe[0] : 7;
    push.values[id.x] = v + id.x;
}

[shader("compute")]
[numthreads(64, 1, 1)]
void branchedMain(uint3 id : SV_DispatchThreadID)
{
    uint v = 7;
    if (push.mode == 1)
    {
        v = push.maybe[id.x & 3];
    }
    if (id.x < push.count)
    {
        push.values[id.x] = v + id.x;
    }
}

[shader("compute")]
[numthreads(64, 1, 1)]
void uniformMain(uint3 id : SV_DispatchThreadID)
{
    uint v = 7;
    if (push.mode == 1)
    {
        v = push.maybe[0];
    }
    if (id.x < push.count)
    {
        push.values[id.x] = v + id.x;
    }
}
