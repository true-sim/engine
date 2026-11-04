// GPU picking from the visibility buffer (engine-ui's GpuPicking, design UI5): the instance under each pointer's pixel,
// read from the view's IDs (instance id + 1 in x, 0 for nothing, classic LOD's tagged by the top bit), written as the
// instance id (or 0xffffffff for nothing) into a list the CPU reads back a frame or more later.
import bindless;

struct PickPush
{
    uint ids;          // storage slot of the visibility buffer's IDs (uint2)
    uint count;        // pointers this frame
    uint width;        // the render region in the IDs, where pixels are clamped
    uint height;
    uint* pixels;      // per pointer: x, y in the IDs' pixels
    uint* picked;      // per pointer: the instance id, or 0xffffffff
};

[[vk::push_constant]]
ConstantBuffer<PickPush> push;

[shader("compute")]
[numthreads(64, 1, 1)]
void pickMain(uint3 id : SV_DispatchThreadID)
{
    uint i = id.x;
    if (i >= push.count)
    {
        return;
    }
    uint x = min(push.pixels[i * 2], push.width - 1);
    uint y = min(push.pixels[i * 2 + 1], push.height - 1);
    uint shown = uintImages[push.ids][uint2(x, y)].x & 0x7fffffff;
    push.picked[i] = shown == 0 ? 0xffffffff : shown - 1;
}
