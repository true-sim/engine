// Writes each index's square through a buffer device address: push constants, pointers and compute.

struct SquaresPush
{
    uint* values;
    uint count;
};

[[vk::push_constant]]
ConstantBuffer<SquaresPush> push;

[shader("compute")]
[numthreads(64, 1, 1)]
void squaresMain(uint3 id : SV_DispatchThreadID)
{
    if (id.x < push.count)
    {
        push.values[id.x] = id.x * id.x;
    }
}
