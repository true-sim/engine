// Weighted blended order-independent transparency's resolve (McGuire and Bavoil 2013): the blended surfaces' weighted
// average color, over the opaque scene by how much of it they let through, into a new image.
import bindless;

struct TransparencyPush
{
    uint opaque;         // storage slot: the opaque scene's color
    uint color;          // storage slot: the result, the blended surfaces over it
    uint accumulated;    // storage slot: color times alpha, and alpha, weighted and added up
    uint revealage;      // storage slot: the product of every blended surface's 1 - alpha
    uint2 size;          // the region resolved
};

[[vk::push_constant]]
ConstantBuffer<TransparencyPush> push;

[shader("compute")]
[numthreads(8, 8, 1)]
void transparencyMain(uint3 id : SV_DispatchThreadID)
{
    if (any(id.xy >= push.size))
    {
        return;
    }
    float4 behind = images[push.opaque][id.xy];
    float revealage = images[push.revealage][id.xy].r;
    if (revealage >= 1)
    {
        images[push.color][id.xy] = behind; // nothing blended here
        return;
    }
    float4 accumulated = images[push.accumulated][id.xy];
    float3 average = accumulated.rgb / max(accumulated.a, 1.0e-5);
    images[push.color][id.xy] = float4(average * (1 - revealage) + behind.rgb * revealage, behind.a);
}
