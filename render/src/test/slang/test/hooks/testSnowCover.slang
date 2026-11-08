// A test's surface hook for pictures (the hook screenshots), checked by ShadingHooksTest: snow lying on upward-facing
// surfaces. Where a surface faces up and a world-space noise is high enough for the coverage, its albedo turns to the
// snow's color, its roughness to the snow's, and it stops being metal; at coverage 0 nothing changes.
module testSnowCover;

import hooks;

[ShadingHookParameters]
public struct TestSnowCoverParameters
{
    public float3 color = float3(0.9, 0.92, 0.95);
    public float coverage = 0.5;  // 0: none, 1: every surface not facing down
    public float roughness = 0.85;
    public float scale = 1.2;     // noise cells per metre
};

float coverHash(float3 c)
{
    uint3 u = uint3(int3(c) + 32768);
    uint h = u.x * 73856093u ^ u.y * 19349663u ^ u.z * 83492791u;
    h ^= h >> 13;
    h *= 0x5bd1e995u;
    h ^= h >> 15;
    return float(h & 0xffffu) / 65535.0;
}

/** Value noise in 0 to 1, smooth across cells. */
float coverNoise(float3 x)
{
    float3 c = floor(x);
    float3 f = x - c;
    f = f * f * (3 - 2 * f);
    float a = lerp(lerp(coverHash(c), coverHash(c + float3(1, 0, 0)), f.x),
        lerp(coverHash(c + float3(0, 1, 0)), coverHash(c + float3(1, 1, 0)), f.x), f.y);
    float b = lerp(lerp(coverHash(c + float3(0, 0, 1)), coverHash(c + float3(1, 0, 1)), f.x),
        lerp(coverHash(c + float3(0, 1, 1)), coverHash(c + float3(1, 1, 1)), f.x), f.y);
    return lerp(a, b, f.z);
}

/** The width of the snow's edge in noise amount. */
static const float EDGE = 0.06;

[ShadingHook("test.snowCover")]
[ShadingHookBefore("test.tint")] // a tint (the pictures: a strong green) colors the snow too
public struct TestSnowCover : ISurfaceHook
{
    public typealias Parameters = TestSnowCoverParameters;

    public static void surface(ShadingPoint p, TestSnowCoverParameters* snow, inout Surface s)
    {
        float3 w = p.world() * snow[0].scale;
        float noise = 0.65 * coverNoise(w) + 0.35 * coverNoise(w * 2.9 + 17.0);
        // Value noise gathers around 0.5: stretched so that the coverage is about the share of level ground covered
        float amount = saturate((noise - 0.5) * 2.2 + 0.5) * (0.4 + 0.6 * saturate(s.normal.y));
        // The edge's band, 0.06 wide, slides from just above the highest amount (1) at coverage 0 to just below the
        // lowest (0) at coverage 1: no snow at all at 0, however high the noise, and all of it at 1. A band centred
        // on 1 - coverage let amounts within 0.03 of 1 (level ground, high noise) take snow at coverage 0.
        float low = lerp(1.0, -EDGE, saturate(snow[0].coverage));
        float lying = smoothstep(low, low + EDGE, amount) * saturate(s.normal.y * 6);
        s.albedo = lerp(s.albedo, snow[0].color, lying);
        s.roughness = lerp(s.roughness, snow[0].roughness, lying);
        s.metallic = lerp(s.metallic, 0, lying);
    }
};
