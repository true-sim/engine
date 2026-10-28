// Shadow-mapped sun shadows, the classic way: per pixel, the position from the scene's depth, into the sun's
// orthographic view, compared with the shadow map's depth (reverse: nearest the sun is largest) over 3 x 3 texels
// (percentage-closer filtering), with a bias against acne.
import bindless;
import meshlets.common;

struct MappedPush
{
    float4x4 clipToLight;   // the camera's clip space to the sun's
    uint depth;             // sampled slot of the scene's depth
    uint mask;              // storage slot of the mask: 1 lit, 0 in shadow
    uint map;               // sampled slot of the shadow map
    uint width;
    uint height;
    uint mapSize;
    float bias;             // in the sun's depth units
};

[[vk::push_constant]]
ConstantBuffer<MappedPush> push;

[shader("compute")]
[numthreads(8, 8, 1)]
void mappedMain(uint3 id : SV_DispatchThreadID)
{
    if (id.x >= push.width || id.y >= push.height)
    {
        return;
    }
    float depth = textures[push.depth].Load(int3(id.xy, 0)).r;
    float lit = 1;
    if (depth > 0)
    {
        float2 ndc = (float2(id.xy) + 0.5) / float2(push.width, push.height) * 2 - 1;
        float4 light = mul(push.clipToLight, float4(ndc, depth, 1));
        float3 q = light.xyz / light.w;
        float2 uv = q.xy * 0.5 + 0.5;
        if (all(uv >= 0) && all(uv <= 1) && q.z >= 0 && q.z <= 1) // outside the map: lit
        {
            int2 texel = int2(floor(uv * push.mapSize));
            int last = int(push.mapSize) - 1;
            float sum = 0;
            for (int y = -1; y <= 1; y++)
            {
                for (int x = -1; x <= 1; x++)
                {
                    float nearest = textures[push.map].Load(int3(clamp(texel + int2(x, y), 0, last), 0)).r;
                    sum += q.z + push.bias >= nearest ? 1 : 0;
                }
            }
            lit = sum / 9;
        }
    }
    images[push.mask][id.xy] = float4(lit, 0, 0, 0);
}
