// One level of a depth pyramid: each texel is the farthest depth (reverse depth: the smallest value) of the texels it
// covers in the level below: 2 x 2 of them, and 3 along an odd edge, as levels halve rounding down (Vulkan's mip
// sizes), so every texel below is covered. Level 0 reads the depth image, sampled; the others read the level below as
// a storage image.
import bindless;

struct PyramidPush
{
    uint source;        // sampled slot of the depth image, or storage slot of the level below
    uint target;        // storage slot of this level
    uint2 sourceSize;
    uint2 targetSize;
    uint fromDepth;     // 1: the source is the depth image
};

[[vk::push_constant]]
ConstantBuffer<PyramidPush> push;

float source(int2 texel)
{
    texel = min(texel, int2(push.sourceSize) - 1);
    return push.fromDepth != 0 ? textures[push.source].Load(int3(texel, 0)).r : images[push.source][texel].r;
}

[shader("compute")]
[numthreads(8, 8, 1)]
void pyramidMain(uint3 id : SV_DispatchThreadID)
{
    if (any(id.xy >= push.targetSize))
    {
        return;
    }
    int2 base = int2(id.xy) * 2;
    // The last texel of a level whose source is odd-sized takes the source's last row or column too.
    int2 extent = int2(2 + ((push.sourceSize.x & 1) != 0 && id.x == push.targetSize.x - 1 ? 1 : 0),
        2 + ((push.sourceSize.y & 1) != 0 && id.y == push.targetSize.y - 1 ? 1 : 0));
    float farthest = 1;
    for (int y = 0; y < extent.y; y++)
    {
        for (int x = 0; x < extent.x; x++)
        {
            farthest = min(farthest, source(base + int2(x, y)));
        }
    }
    images[push.target][id.xy] = float4(farthest, 0, 0, 0);
}
