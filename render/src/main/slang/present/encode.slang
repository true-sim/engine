// A window's output transform: the view's linear scene color, exposed and tone mapped for the window's display, then
// encoded for it.
// - SDR in an sRGB image: tone mapped into 0 to 1, linear (the copy into the window's sRGB image encodes it).
// - SDR in a UNORM image (no sRGB format offered): tone mapped, then encoded here by the exact sRGB curve.
// - HDR10: the tone curve applied relative to the display's peak brightness, in nits from paper white (SDR white),
//   moved to Rec. 2020 primaries and encoded by the PQ curve.
// - scRGB: the same curve, in Rec. 709 primaries, 1.0 being 80 nits.
// Before the copy into 8 (SDR) or 10 (HDR10) bits, an optional triangular dither of one step, in the encoded values,
// hides banding in smooth gradients.
// The result is the size the view shows at; scene color rendered smaller (a render scale, dynamic resolution's region)
// is scaled up, filtered, never reading past the region.
// A view's UI (engine-render's Keys.UI: premultiplied, linear, 1.0 paper white) is composited after the tone curve:
// SDR toneMapped * (1 - a) + ui; HDR curveNits * (1 - a) + ui * paperWhite * uiBrightness, clipped at the peak.
// In two stages (DisplayTransform.displayEffects), the first writes the tone-mapped color in display units (SDR 0 to 1,
// HDR 1 at paper white) for techniques to modify, and the second composites the UI over it and encodes.
import bindless;

public struct EncodePush
{
    uint source;        // sampled slot of the view's linear scene color (or, to encode only, its display color)
    uint target;        // storage slot of the result, 16-bit floats
    uint width;         // the result's size
    uint height;
    uint sourceWidth;   // the render region in the scene color, from its top left corner
    uint sourceHeight;
    uint modes;         // bits: encoding (ENCODE_*), curve << 4, look << 8, stage << 12, EXPOSURE_FROM_BUFFER
    float exposure;     // scene color is multiplied by this first, unless EXPOSURE_FROM_BUFFER
    float* exposureAt;  // with EXPOSURE_FROM_BUFFER: the exposure, a technique's (auto exposure)
    float paperWhite;   // nits of SDR white (scene color 1.0) on an HDR display
    float peak;         // the HDR display's peak brightness, in nits
    float whitePoint;   // REINHARD_EXTENDED: the scene color mapped to white
    uint ditherSteps;   // 0: no dither; else the steps of the image the result is copied into (255, 1023)
    uint frame;         // varies the dither from frame to frame
    uint ui;            // sampled slot of the view's UI, or NO_UI
    float uiBrightness; // the UI's white on an HDR display, relative to paper white
};

public static const uint ENCODE_SDR = 0;
public static const uint ENCODE_HDR10 = 1;
public static const uint ENCODE_SCRGB = 2;
public static const uint ENCODE_SDR_UNORM = 3;
public static const uint NO_UI = 0xffffffff;

public static const uint CURVE_NONE = 0;
public static const uint CURVE_REINHARD = 1;
public static const uint CURVE_ACES = 2;
public static const uint CURVE_REINHARD_EXTENDED = 3;
public static const uint CURVE_ACES_FITTED = 4;
public static const uint CURVE_AGX = 5;
public static const uint CURVE_PBR_NEUTRAL = 6;
public static const uint CURVE_GT = 7;
public static const uint CURVE_HABLE = 8;

public static const uint LOOK_BASE = 0;
public static const uint LOOK_PUNCHY = 1;
public static const uint LOOK_GOLDEN = 2;

public static const uint STAGE_FUSED = 0;    // tone map and encode in one pass
public static const uint STAGE_TONE_MAP = 1; // tone map into display units (for display-space effects)
public static const uint STAGE_ENCODE = 2;   // composite the UI over display units and encode

public static const uint EXPOSURE_FROM_BUFFER = 1 << 16;

[[vk::push_constant]]
ConstantBuffer<EncodePush> push;

// ---------------------------------------------------------------- the curves, each as its reference implementation

/** Narkowicz's fit of the ACES film curve (2016), its input scaled by 0.6 as he notes to match ACES: 1 maps to 0.67. */
float3 acesNarkowicz(float3 c)
{
    float3 x = c * 0.6;
    return saturate((x * (2.51 * x + 0.03)) / (x * (2.43 * x + 0.59) + 0.14));
}

/** Stephen Hill's fit of the ACES RRT and sRGB ODT (BakingLab's ACES.hlsl): sRGB to ACES' working space and back. */
float3 acesHill(float3 c)
{
    float3 v = float3(
        dot(float3(0.59719, 0.35458, 0.04823), c),
        dot(float3(0.07600, 0.90834, 0.01566), c),
        dot(float3(0.02840, 0.13383, 0.83777), c));
    float3 a = v * (v + 0.0245786) - 0.000090537;
    float3 b = v * (0.983729 * v + 0.4329510) + 0.238081;
    v = a / b;
    return saturate(float3(
        dot(float3(1.60475, -0.53108, -0.07367), v),
        dot(float3(-0.10208, 1.10813, -0.00605), v),
        dot(float3(-0.00327, -0.07276, 1.07602), v)));
}

/** AgX's sigmoid in log space, the 6th order fit of Sobotka's default contrast (Wrensch's minimal AgX). */
float3 agxContrast(float3 x)
{
    float3 x2 = x * x;
    float3 x4 = x2 * x2;
    return 15.5 * x4 * x2 - 40.14 * x4 * x + 31.96 * x4 - 6.868 * x2 * x + 0.4298 * x2 + 0.1191 * x - 0.00232;
}

/**
 * AgX (Troy Sobotka), as Wrensch's minimal reference: into AgX's inset primaries, log2 between -12.47 and +4.03 stops
 * around middle gray, the sigmoid, the look (Blender's punchy and golden), out of the inset and linearized from the
 * 2.2 power display AgX encodes for.
 */
float3 agx(float3 c)
{
    // GLSL's mat3 is column-major: the columns are the inset primaries.
    float3 v = float3(0.842479062253094, 0.0423282422610123, 0.0423756549057051) * c.x
        + float3(0.0784335999999992, 0.878468636469772, 0.0784336) * c.y
        + float3(0.0792237451477643, 0.0791661274605434, 0.879142973793104) * c.z;
    const float minEv = -12.47393;
    const float maxEv = 4.026069;
    v = clamp(log2(max(v, 1e-10)), minEv, maxEv);
    v = agxContrast((v - minEv) / (maxEv - minEv));
    uint look = (push.modes >> 8) & 15;
    if (look != LOOK_BASE)
    {
        float luma = dot(v, float3(0.2126, 0.7152, 0.0722));
        float3 slope = look == LOOK_GOLDEN ? float3(1.0, 0.9, 0.5) : float3(1, 1, 1);
        float3 power = look == LOOK_GOLDEN ? float3(0.8, 0.8, 0.8) : float3(1.35, 1.35, 1.35);
        float saturation = look == LOOK_GOLDEN ? 0.8 : 1.4;
        v = pow(max(v * slope, 0), power);
        v = luma + saturation * (v - luma);
    }
    v = float3(1.19687900512017, -0.0528968517574562, -0.0529716355144438) * v.x
        + float3(-0.0980208811401368, 1.15190312990417, -0.0980434501171241) * v.y
        + float3(-0.0990297440797205, -0.0989611768448433, 1.15107367264116) * v.z;
    return pow(saturate(v), 2.2);
}

/**
 * Khronos PBR Neutral (KhronosGroup/ToneMapping), with its ceiling `top`: 1 in SDR, as the reference; in HDR the
 * peak over paper white, so colors below the compression start show as they do in SDR and highlights roll off to the
 * peak (the same shoulder, its asymptote raised; value and slope stay continuous at the start).
 */
float3 pbrNeutral(float3 color, float top)
{
    const float startCompression = 0.8 - 0.04;
    const float desaturation = 0.15;
    float x = min(color.r, min(color.g, color.b));
    float offset = x < 0.08 ? x - 6.25 * x * x : 0.04;
    color -= offset;
    float peak = max(color.r, max(color.g, color.b));
    if (peak < startCompression)
    {
        return color;
    }
    float d = top - startCompression;
    float newPeak = top - d * d / (peak + d - startCompression);
    color *= newPeak / peak;
    float g = 1 - 1 / (desaturation * (peak - newPeak) + 1);
    return lerp(color, newPeak, g);
}

float gtSmooth(float x, float e0, float e1)
{
    if (x <= e0) return 0;
    if (x >= e1) return 1;
    float a = (x - e0) / (e1 - e0);
    return a * a * (3 - 2 * a);
}

float gtStep(float x, float e0, float e1)
{
    if (x <= e0) return 0;
    if (x >= e1) return 1;
    return (x - e0) / (e1 - e0);
}

/**
 * Uchimura's Gran Turismo curve (CEDEC 2017), its reference parameters: contrast 1, linear section from 0.22 for 0.4,
 * black 1.33, no pedestal; `P` the brightest output, 1 in SDR and the peak over paper white in HDR (its own HDR form).
 */
float gt(float x, float P)
{
    const float a = 1;
    const float m = 0.22;
    const float l = 0.4;
    const float c = 1.33;
    const float b = 0;
    float l0 = (P - m) * l / a;
    float S0 = m + l0;
    float S1 = m + a * l0;
    float C2 = a * P / (P - S1);
    float Lx = m + a * (x - m);
    float Tx = m * pow(x / m, c) + b;
    float Sx = P - (P - S1) * exp(-(C2 * (x - S0) / P));
    float w0 = 1 - gtSmooth(x, 0, m);
    float w2 = gtStep(x, m + l0, m + l0);
    float w1 = 1 - w0 - w2;
    return Tx * w0 + Lx * w1 + Sx * w2;
}

/** Hable's Uncharted 2 curve, as his reference: A..F of the filmic shoulder, exposure bias 2, white at 11.2. */
float3 hablePartial(float3 x)
{
    const float A = 0.15;
    const float B = 0.50;
    const float C = 0.10;
    const float D = 0.20;
    const float E = 0.02;
    const float F = 0.30;
    return ((x * (A * x + C * B) + D * E) / (x * (A * x + B) + D * F)) - E / F;
}

float3 hable(float3 c)
{
    return saturate(hablePartial(2 * c) / hablePartial(float3(11.2, 11.2, 11.2)));
}

/** `c` (0 and up) tone mapped into 0 to 1 by the push's curve, in SDR; none clamps. */
float3 toneMap(float3 c)
{
    switch ((push.modes >> 4) & 15)
    {
    case CURVE_REINHARD:
        return c / (1 + c);
    case CURVE_ACES:
        return acesNarkowicz(c);
    case CURVE_REINHARD_EXTENDED:
        return saturate(c * (1 + c / (push.whitePoint * push.whitePoint)) / (1 + c));
    case CURVE_ACES_FITTED:
        return acesHill(c);
    case CURVE_AGX:
        return agx(c);
    case CURVE_PBR_NEUTRAL:
        return pbrNeutral(c, 1);
    case CURVE_GT:
        return saturate(float3(gt(c.r, 1), gt(c.g, 1), gt(c.b, 1)));
    case CURVE_HABLE:
        return hable(c);
    default:
        return saturate(c);
    }
}

/**
 * `c` in display units (1 at paper white) on an HDR display whose peak is `range` times paper white: PBR Neutral and
 * GT with their own HDR forms (their curves in paper white's units, up to the peak), the others relative to the peak,
 * as SDR maps to white.
 */
float3 toneMapHdr(float3 c, float range)
{
    switch ((push.modes >> 4) & 15)
    {
    case CURVE_PBR_NEUTRAL:
        return min(pbrNeutral(c, range), range);
    case CURVE_GT:
        return min(float3(gt(c.r, range), gt(c.g, range), gt(c.b, range)), range);
    default:
        return toneMap(c / range) * range;
    }
}

// ---------------------------------------------------------------- encodings

/** The sRGB curve (IEC 61966-2-1), exactly: linear 0 to 1 into encoded 0 to 1. */
float3 srgbEncode(float3 c)
{
    c = saturate(c);
    return select(c <= 0.0031308, 12.92 * c, 1.055 * pow(c, 1 / 2.4) - 0.055);
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

/** PCG's 3D hash (Jarzynski and Olano 2020). */
uint3 pcg3d(uint3 v)
{
    v = v * 1664525u + 1013904223u;
    v.x += v.y * v.z;
    v.y += v.z * v.x;
    v.z += v.x * v.y;
    v ^= v >> 16u;
    v.x += v.y * v.z;
    v.y += v.z * v.x;
    v.z += v.x * v.y;
    return v;
}

/**
 * `encoded` (0 to 1, to be quantized into `ditherSteps`) plus triangular noise of one step each way, the same for the
 * three channels, scaled down where it would cross 0 or 1 so black and white stay exact.
 */
float3 dither(float3 encoded, uint2 pixel)
{
    uint3 h = pcg3d(uint3(pixel, push.frame));
    float noise = (h.x >> 8) * (1.0 / 16777216.0) + (h.y >> 8) * (1.0 / 16777216.0) - 1; // -1 to 1, triangular
    float steps = push.ditherSteps;
    float3 room = min(encoded, 1 - encoded) * steps;
    return encoded + noise * min(room, 1) / steps;
}

/** The view's UI at `pixel` (premultiplied, 1.0 paper white), or transparent without one. */
float4 uiAt(uint2 pixel)
{
    if (push.ui == NO_UI)
    {
        return float4(0, 0, 0, 0);
    }
    float4 ui;
    uint uiWidth;
    uint uiHeight;
    textures[push.ui].GetDimensions(uiWidth, uiHeight);
    if (uiWidth == push.width && uiHeight == push.height)
    {
        ui = textures[push.ui].Load(int3(pixel, 0));
    }
    else
    {
        float2 uv = (float2(pixel) + 0.5) / float2(push.width, push.height);
        ui = textures[push.ui].SampleLevel(samplers[LINEAR_CLAMP], uv, 0);
    }
    return max(ui, 0);
}

[shader("compute")]
[numthreads(8, 8, 1)]
void encodeMain(uint3 id : SV_DispatchThreadID)
{
    if (id.x >= push.width || id.y >= push.height)
    {
        return;
    }
    uint encoding = push.modes & 15;
    uint stage = (push.modes >> 12) & 15;
    bool sdr = encoding == ENCODE_SDR || encoding == ENCODE_SDR_UNORM;
    float range = push.peak / push.paperWhite;
    float3 d; // display units: SDR 0 to 1, HDR paper white 1 up to the peak's share
    if (stage == STAGE_ENCODE)
    {
        d = max(textures[push.source].Load(int3(id.xy, 0)).rgb, 0);
    }
    else
    {
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
            color = textures[push.source].SampleLevel(samplers[LINEAR_CLAMP], p / float2(sceneWidth, sceneHeight), 0)
                .rgb;
        }
        float exposure = (push.modes & EXPOSURE_FROM_BUFFER) != 0 ? push.exposureAt[0] : push.exposure;
        color = max(color, 0) * exposure;
        // HDR: the curve relative to the display's peak: paper white stays near itself, brighter colors roll off.
        d = sdr ? saturate(toneMap(color)) : toneMapHdr(color, range);
        if (stage == STAGE_TONE_MAP)
        {
            images[push.target][id.xy] = float4(d, 1);
            return;
        }
    }
    // The UI after the curve: SDR as it is, HDR its white at paper white (times its brightness), never past the peak.
    float4 ui = uiAt(id.xy);
    float keep = 1 - saturate(ui.a); // how much of the scene shows through the UI
    float3 encoded;
    if (sdr)
    {
        float3 shown = saturate(d * keep + ui.rgb);
        if (encoding == ENCODE_SDR_UNORM)
        {
            encoded = srgbEncode(shown);
            if (push.ditherSteps != 0)
            {
                encoded = dither(encoded, id.xy);
            }
        }
        else if (push.ditherSteps != 0)
        {
            // Dithered as encoded values, handed over linear: the copy into the sRGB image encodes them again. The
            // step of under one 8-bit level goes back through the curve's slope (exact to its second order: under
            // 0.03 of a level), sparing a second power.
            float3 e = srgbEncode(shown);
            float3 slope = select(e <= 0.04045, 1 / 12.92, 2.4 * shown / (e + 0.055));
            encoded = max(shown + (dither(e, id.xy) - e) * slope, 0);
        }
        else
        {
            encoded = shown;
        }
    }
    else
    {
        float3 nits = min((d * keep + ui.rgb * push.uiBrightness) * push.paperWhite, push.peak);
        if (encoding == ENCODE_HDR10)
        {
            encoded = pq(rec2020(nits));
            if (push.ditherSteps != 0)
            {
                encoded = dither(encoded, id.xy);
            }
        }
        else
        {
            encoded = nits / 80;
        }
    }
    images[push.target][id.xy] = float4(encoded, 1);
}
