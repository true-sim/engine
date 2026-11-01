// One level of the scene behind transmissive surfaces (engine-render's SceneBehind): level 0 copies the opaque scene's
// color, each level above averages the 2 x 2 texels it covers in the level below (3 along an odd edge, as levels halve
// rounding down), so rougher surfaces sample a blurrier scene. Reads and writes storage images.
import bindless;

struct BehindPush
{
    uint source;        // storage slot: the scene's color, or the level below
    uint target;        // storage slot of this level
    uint2 sourceSize;
    uint2 targetSize;
    uint copy;          // 1: level 0, a copy of the source
};

[[vk::push_constant]]
ConstantBuffer<BehindPush> push;

[shader("compute")]
[numthreads(8, 8, 1)]
void behindMain(uint3 id : SV_DispatchThreadID)
{
    if (any(id.xy >= push.targetSize))
    {
        return;
    }
    if (push.copy != 0)
    {
        images[push.target][id.xy] = float4(images[push.source][id.xy].rgb, 1);
        return;
    }
    int2 base = int2(id.xy) * 2;
    int2 extent = int2(2 + ((push.sourceSize.x & 1) != 0 && id.x == push.targetSize.x - 1 ? 1 : 0),
        2 + ((push.sourceSize.y & 1) != 0 && id.y == push.targetSize.y - 1 ? 1 : 0));
    float3 sum = 0;
    for (int y = 0; y < extent.y; y++)
    {
        for (int x = 0; x < extent.x; x++)
        {
            sum += images[push.source][min(base + int2(x, y), int2(push.sourceSize) - 1)].rgb;
        }
    }
    images[push.target][id.xy] = float4(sum / float(extent.x * extent.y), 1);
}
