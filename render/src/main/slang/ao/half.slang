// Ambient occlusion at half resolution (engine-render's AmbientOcclusion, each kind's resolution): the pixel each texel
// of the half resolution traces (one of its 2 x 2, ao.slang's halfOffset), its depth, normal and motion (halved, in
// the half resolution's texels), for the denoiser at that resolution and the composite's upsampling.
import bindless;

struct AoHalfPush
{
    uint depth;               // sampled slot: the scene's depth
    uint normal;              // storage slot: the surface normals (packed)
    uint motion;              // storage slot: the motion vectors (with AO_HALF_MOTION)
    uint halfDepth;           // storage slots of the half resolution's
    uint halfNormal;
    uint halfMotion;
    uint size;                // the render region: width, height in the high 16 bits
    uint frame;               // the noise's frame: which pixel of each 2 x 2
    uint flags;               // AO_HALF_*
};

[[vk::push_constant]]
ConstantBuffer<AoHalfPush> push;

static const uint AO_HALF_MOTION = 1;

[shader("compute")]
[numthreads(8, 8, 1)]
void downsampleMain(uint3 id : SV_DispatchThreadID)
{
    uint2 size = uint2(push.size & 0xffff, push.size >> 16);
    if (any(id.xy * 2 >= size))
    {
        return;
    }
    uint2 p = min(id.xy * 2 + uint2(push.frame & 1, (push.frame >> 1) & 1), size - 1);
    images[push.halfDepth][id.xy] = float4(textures[push.depth].Load(int3(p, 0)).r, 0, 0, 0);
    uintImages[push.halfNormal][id.xy] = uint2(uintImages[push.normal][p].x, 0);
    if ((push.flags & AO_HALF_MOTION) != 0)
    {
        images[push.halfMotion][id.xy] = float4(images[push.motion][p].xy * 0.5, 0, 0);
    }
}
