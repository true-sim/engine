// The scene's color with the sun's shadows: shadowed pixels keep the ambient share of their light.
import bindless;

struct CompositePush
{
    uint color;     // sampled slot of the scene's color
    uint mask;      // sampled slot of the shadow mask
    uint target;    // storage slot of the result
    uint width;
    uint height;
    float ambient;  // the light a shadow leaves, 0 to 1
};

[[vk::push_constant]]
ConstantBuffer<CompositePush> push;

[shader("compute")]
[numthreads(8, 8, 1)]
void compositeMain(uint3 id : SV_DispatchThreadID)
{
    if (id.x >= push.width || id.y >= push.height)
    {
        return;
    }
    float4 color = textures[push.color].Load(int3(id.xy, 0));
    float lit = textures[push.mask].Load(int3(id.xy, 0)).r;
    images[push.target][id.xy] = float4(color.rgb * (push.ambient + (1 - push.ambient) * lit), color.a);
}
