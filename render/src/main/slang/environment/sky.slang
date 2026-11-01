// An environment shown behind the scene (engine-render's SkyBackground): over every pixel nothing covers (reverse
// depth 0, at infinity), the environment's radiance cube along the pixel's direction, turned and scaled as the
// environment is. A compute pass over the render region, in place on the scene's color.
import meshlets.common;

struct SkyPush
{
    View* view;
    uint color;          // storage slot: the scene's color
    uint depth;          // sampled slot: the scene's depth
    uint cube;           // sampled slot: the radiance cube
    uint size;           // the render region: width, height in the high 16 bits
    float4 rotation;     // from the world into the environment's frame
    float intensity;
    float blur;          // the cube's level to show: 0 sharp
};

[[vk::push_constant]]
ConstantBuffer<SkyPush> push;

[shader("compute")]
[numthreads(8, 8, 1)]
void skyMain(uint3 id : SV_DispatchThreadID)
{
    uint2 size = uint2(push.size & 0xffff, push.size >> 16);
    if (any(id.xy >= size))
    {
        return;
    }
    if (textures[push.depth].Load(int3(id.xy, 0)).r > 0)
    {
        return; // a surface: the scene as it is
    }
    // The pixel's direction: its clip position at the far plane, back into camera-relative space.
    float2 ndc = (float2(id.xy) + 0.5) / float2(size) * 2 - 1;
    float4 world = mul(push.view[0].inverseViewProjection, float4(ndc, 0.5, 1));
    float3 d = normalize(world.xyz / world.w);
    float3 t = 2.0 * cross(push.rotation.xyz, d);
    float3 turned = d + push.rotation.w * t + cross(push.rotation.xyz, t);
    float3 c = cubes[push.cube].SampleLevel(samplers[LINEAR_CLAMP], turned, push.blur).rgb * push.intensity;
    images[push.color][id.xy] = float4(c, 1);
}
