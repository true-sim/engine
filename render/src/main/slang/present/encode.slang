// A window's output transform: the view's linear scene color, exposed and tone mapped for the window's display, then
// encoded for it. SDR: tone mapped into 0 to 1, linear (the copy into the window's sRGB image encodes it). HDR10: the
// tone curve applied relative to the display's peak brightness, in nits from paper white (SDR white), moved to Rec.
// 2020 primaries and encoded by the PQ curve. scRGB: the same curve, in Rec. 709 primaries, 1.0 being 80 nits.
// The result is the size the view shows at; scene color rendered smaller (a render scale, dynamic resolution's region)
// is scaled up, filtered, never reading past the region.
import bindless;

public struct EncodePush
{
    uint source;        // sampled slot of the view's linear scene color
    uint target;        // storage slot of the result, 16-bit floats
    uint width;         // the result's size
    uint height;
    uint sourceWidth;   // the render region in the scene color, from its top left corner
    uint sourceHeight;
    uint encoding;      // ENCODE_SDR, ENCODE_HDR10, ENCODE_SCRGB
    uint curve;         // CURVE_NONE, CURVE_REINHARD, CURVE_ACES
    float exposure;     // scene color is multiplied by this first
    float paperWhite;   // nits of SDR white (scene color 1.0) on an HDR display
    float peak;         // the HDR display's peak brightness, in nits
};

public static const uint ENCODE_SDR = 0;
public static const uint ENCODE_HDR10 = 1;
public static const uint ENCODE_SCRGB = 2;
public static const uint CURVE_NONE = 0;
public static const uint CURVE_REINHARD = 1;
public static const uint CURVE_ACES = 2;

[[vk::push_constant]]
ConstantBuffer<EncodePush> push;

/** `c` (0 and up) tone mapped into 0 to 1 by the push's curve; none clamps. */
float3 toneMap(float3 c)
{
    switch (push.curve)
    {
    case CURVE_REINHARD:
        return c / (1 + c);
    case CURVE_ACES:
        // Narkowicz's fit of the ACES film curve, its input scaled so 1 maps near 0.8.
        float3 x = c * 0.6;
        return saturate((x * (2.51 * x + 0.03)) / (x * (2.43 * x + 0.59) + 0.14));
    default:
        return saturate(c);
    }
}

/** Absolute brightness in nits, encoded by SMPTE ST 2084 (PQ) into 0 to 1. */
float3 pq(float3 nits)
{
    float m1 = 0.1593017578125;
    float m2 = 78.84375;
    float c1 = 0.8359375;
    float c2 = 18.8515625;
    float c3 = 18.6875;
    float3 y = pow(saturate(nits / 10000), m1);
    return pow((c1 + c2 * y) / (1 + c3 * y), m2);
}

/** Linear Rec. 709 to Rec. 2020 primaries. */
float3 rec2020(float3 c)
{
    return float3(
        dot(float3(0.6274040, 0.3292820, 0.0433136), c),
        dot(float3(0.0690970, 0.9195400, 0.0113612), c),
        dot(float3(0.0163916, 0.0880132, 0.8955950), c));
}

[shader("compute")]
[numthreads(8, 8, 1)]
void encodeMain(uint3 id : SV_DispatchThreadID)
{
    if (id.x >= push.width || id.y >= push.height)
    {
        return;
    }
    float3 color;
    if (push.sourceWidth == push.width && push.sourceHeight == push.height)
    {
        color = textures[push.source].Load(int3(id.xy, 0)).rgb;
    }
    else
    {
        // The pixel's center in the region, kept half a texel inside it, so filtering reads none beyond.
        float2 region = float2(push.sourceWidth, push.sourceHeight);
        float2 p = clamp((float2(id.xy) + 0.5) * region / float2(push.width, push.height), 0.5, region - 0.5);
        uint sceneWidth;
        uint sceneHeight;
        textures[push.source].GetDimensions(sceneWidth, sceneHeight);
        color = textures[push.source].SampleLevel(samplers[LINEAR_CLAMP], p / float2(sceneWidth, sceneHeight), 0).rgb;
    }
    color = max(color, 0) * push.exposure;
    float3 encoded;
    if (push.encoding == ENCODE_SDR)
    {
        encoded = toneMap(color);
    }
    else
    {
        // The curve relative to the display's peak: paper white stays near itself, brighter colors roll off to the peak.
        float range = push.peak / push.paperWhite;
        float3 nits = toneMap(color / range) * push.peak;
        encoded = push.encoding == ENCODE_HDR10 ? pq(rec2020(nits)) : nits / 80;
    }
    images[push.target][id.xy] = float4(encoded, 1);
}
