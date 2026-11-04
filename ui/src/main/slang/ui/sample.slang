// The eyedropper's read of the screen (ui-core's ScreenSampler): a block of up to 31 x 31 texels of the view's
// scene-referred color (linear, before the display transform: values above 1 kept) around a point, written as float4s
// into a buffer the CPU reads back a frame or more later. The first two float4s are a header naming the request the
// block answers, so the CPU never takes a stale or unwritten block for a new one.
import bindless;

struct SamplePush
{
    float4* target;     // 2 header float4s, then size * size texels, row by row from the top
    uint source;        // sampled slot of the view's scene color
    int centerX;        // the block's centre, in scene color texels
    int centerY;
    uint size;          // texels per side (odd)
    uint regionWidth;   // the render region from the image's top left: texels outside it read as 0
    uint regionHeight;
    float uiX;          // the UI pixel asked for (echoed in the header)
    float uiY;
    uint sequence;      // the request's number (echoed in the header)
};

/** The header's mark: a block written by this shader. */
static const uint MARK = 0x53414d50; // "SAMP"

[[vk::push_constant]]
ConstantBuffer<SamplePush> push;

[shader("compute")]
[numthreads(8, 8, 1)]
void sampleMain(uint3 id : SV_DispatchThreadID)
{
    if (id.x == 0 && id.y == 0)
    {
        push.target[0] = float4(push.uiX, push.uiY, asfloat(push.size), asfloat(push.sequence));
        push.target[1] = float4(asfloat(MARK), asfloat(~push.sequence), 0, 0);
    }
    if (id.x >= push.size || id.y >= push.size)
    {
        return;
    }
    int half = int(push.size / 2);
    int2 at = int2(push.centerX, push.centerY) + int2(id.xy) - int2(half, half);
    float4 c = float4(0, 0, 0, 1);
    if (all(at >= 0) && at.x < int(push.regionWidth) && at.y < int(push.regionHeight))
    {
        c = textures[NonUniformResourceIndex(push.source)].Load(int3(at, 0));
    }
    push.target[2 + id.y * push.size + id.x] = c;
}
