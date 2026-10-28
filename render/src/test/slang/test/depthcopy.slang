// For tests: a depth image's values into the red channel of a color image.
import bindless;

struct CopyPush
{
    uint depth;
    uint color;
};

[[vk::push_constant]]
ConstantBuffer<CopyPush> push;

[shader("compute")]
[numthreads(8, 8, 1)]
void copyMain(uint3 id : SV_DispatchThreadID)
{
    images[push.color][id.xy] = float4(textures[push.depth].Load(int3(id.xy, 0)).r, 0, 0, 1);
}
