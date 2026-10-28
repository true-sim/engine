// One direction of a separable Gaussian blur: each thread writes one texel of the target, averaging the source along
// `step` with Gaussian weights (sigma = radius / 2). Sampling with linear filtering also resamples the source to the
// target's size, so a smaller target is a blurred downsample.
import bindless;

struct BlurPush
{
    uint source;   // sampled slot
    uint target;   // storage slot
    uint2 size;    // the target's
    float2 step;   // one source texel along the blur, in texture coordinates
    int radius;    // taps on each side, in source texels
};

[[vk::push_constant]]
ConstantBuffer<BlurPush> push;

[shader("compute")]
[numthreads(8, 8, 1)]
void blurMain(uint3 id : SV_DispatchThreadID)
{
    if (any(id.xy >= push.size))
    {
        return;
    }
    float2 uv = (float2(id.xy) + 0.5) / float2(push.size);
    float sigma = max(push.radius * 0.5, 0.5);
    float4 sum = 0;
    float weights = 0;
    for (int i = -push.radius; i <= push.radius; i++)
    {
        float w = exp(-0.5 * i * i / (sigma * sigma));
        sum += w * textures[push.source].SampleLevel(samplers[LINEAR_CLAMP], uv + i * push.step, 0);
        weights += w;
    }
    images[push.target][id.xy] = sum / weights;
}
