// Motion vectors' memory of skinned copies (Deformation, for MotionVectors): before a copy is skinned anew, its
// vertices as the frame before drew them are copied aside, a thread per vertex, so motion vectors find where each
// point of it was.
struct KeepPush
{
    uint2* positions;      // the pool's quantized positions
    uint2* previous;       // the copies' kept vertices
    uint from;             // the copy's first vertex in the pool
    uint to;               // and among the kept ones
    uint count;
};

[[vk::push_constant]]
ConstantBuffer<KeepPush> push;

[shader("compute")]
[numthreads(64, 1, 1)]
void keepMain(uint3 id : SV_DispatchThreadID)
{
    if (id.x < push.count)
    {
        push.previous[push.to + id.x] = push.positions[push.from + id.x];
    }
}
