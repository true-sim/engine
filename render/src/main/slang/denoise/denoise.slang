// The engine's denoiser (engine-render's Denoiser, design §9, D6): a signal of two images per pixel, A (rgb a vector,
// such as a bent normal; a the value guiding the filter, such as the occlusion) and B (rgb a color or distance; a its
// variance, written here), filtered over a view's render region, in two families benchmarked against each other:
//
// - SVGF (Schied et al. 2017, 2018): temporal accumulation (temporalMain) then an edge-stopping a-trous wavelet filter
//   (atrousMain, steps 1, 2, 4, ...) guided by the depth's plane, the normal and the value's variance.
// - ReBLUR's ideas (Zhdan 2021): a blur before (blurMain), the temporal accumulation, and a blur after whose radius
//   shrinks as the history grows, the history holding the blurred result (recurrent).
//
// The temporal accumulation is the same for both: the history is found where motion vectors say the pixel's surface
// was (bilinearly, each tap kept only where it held the same surface: depth as expected and the normal alike), its
// weight 1 / frames with frames capped (the static scene's history length), and a fast history (a few frames, of the
// value's 3 x 3 means) clamps the slow one within a few of its deviations (ReBLUR's history fix): a change nothing
// announced shows within the fast history's frames, a still view keeps the long history's low noise. No pass waits
// for a later frame: the image is never delayed.
import meshlets.common;

struct DenoisePush
{
    View* view;
    uint depth;          // sampled slot: the scene's depth
    uint normal;         // storage slot: the surface normal output (view space, packed)
    uint motion;         // storage slot: the motion vectors (with DENOISE_MOTION)
    uint inputA;         // storage slots of the signal read
    uint inputB;
    uint historyA;       // temporal: storage slots of the frame before's accumulation
    uint historyB;
    uint moments;        // temporal: the frame before's moments (fast mean, fast mean squared, slow mean squared,
                         // frames); blurMain with DENOISE_SHRINK: this frame's
    uint geometry;       // temporal: the frame before's geometry (linear depth bits, packed world normal)
    uint outputA;        // storage slots written
    uint outputB;
    uint outputMoments;
    uint outputGeometry;
    uint size;           // the render region: width, height in the high 16 bits
    uint previousSize;   // the frame before's render region
    uint maxFrames;      // the slow history's frames at most
    uint fastFrames;     // the fast history's
    uint step;           // atrousMain: the step between taps, in pixels
    uint frame;          // blurMain: the frame, turning the taps
    uint flags;          // DENOISE_*
    float clampSigma;    // temporal: the slow history kept within the fast one's mean, this many deviations
    float sigmaValue;    // atrousMain: the value's edge-stopping, in deviations (0: no value guidance)
    float radius;        // blurMain: the blur's radius in world units at the pixel
};

[[vk::push_constant]]
ConstantBuffer<DenoisePush> push;

static const uint DENOISE_HISTORY = 1; // the frame before's accumulation is valid
static const uint DENOISE_MOTION = 2;  // motion vectors given; else the camera's reprojection
static const uint DENOISE_SHRINK = 4;  // blurMain: the radius shrinks as one over the root of the frames

uint2 unpackSize(uint s)
{
    return uint2(s & 0xffff, s >> 16);
}

float sceneDepth(int2 p)
{
    return textures[push.depth].Load(int3(p, 0)).r;
}

float3 unproject(float2 s, float z, float2 size)
{
    float2 ndc = s / size * 2 - 1;
    float4 h = mul(push.view[0].inverseViewProjection, float4(ndc, z, 1));
    return h.xyz / h.w;
}

/** A pixel's surface: camera-relative position, world normal, view distance; distance 0 where it shows nothing. */
struct Geometry
{
    float3 position;
    float3 normal;
    float distance;
};

Geometry geometryAt(int2 p, float2 size, float3x3 axes)
{
    Geometry g;
    g.distance = 0;
    g.position = 0;
    g.normal = 0;
    float z = sceneDepth(p);
    if (z <= 0)
    {
        return g;
    }
    g.position = unproject(float2(p) + 0.5, z, size);
    g.normal = fromView(axes, unpackNormal(uintImages[push.normal][p].x));
    g.distance = dot(push.view[0].viewProjection[3], float4(g.position, 1));
    return g;
}

/** Whether `q`'s surface lies on `g`'s plane (within a part of its distance) and faces alike. */
float sameSurface(Geometry g, Geometry q, float tolerance)
{
    if (q.distance <= 0)
    {
        return 0;
    }
    float plane = abs(dot(g.normal, q.position - g.position));
    float wPlane = exp(-plane / max(tolerance * g.distance, 1.0e-4));
    float facing = saturate(dot(g.normal, q.normal));
    float f2 = facing * facing;
    float f4 = f2 * f2;
    float f8 = f4 * f4;
    return wPlane * f8 * f8 * f8 * f8; // facing^32
}

/** The temporal accumulation: history reprojected, accumulated, clamped by the fast history. */
[shader("compute")]
[numthreads(8, 8, 1)]
void temporalMain(uint3 id : SV_DispatchThreadID)
{
    float2 size = float2(unpackSize(push.size));
    if (any(float2(id.xy) >= size))
    {
        return;
    }
    int2 p = int2(id.xy);
    float3x3 axes = viewAxes(push.view[0].viewProjection);
    Geometry g = geometryAt(p, size, axes);
    if (g.distance <= 0)
    {
        images[push.outputA][p] = float4(0, 0, -1, 1);
        images[push.outputB][p] = 0;
        images[push.outputMoments][p] = 0;
        uintImages[push.outputGeometry][p] = uint2(0, 0);
        return;
    }
    float4 a = images[push.inputA][p];
    float4 b = images[push.inputB][p];
    // The value's mean and spread over the 3 x 3 pixels of the same surface
    float sum = 0;
    float sum2 = 0;
    float count = 0;
    for (int k = 0; k < 9; k++)
    {
        int2 q = p + int2(k % 3, k / 3) - 1;
        if (any(q < 0) || any(float2(q) >= size))
        {
            continue;
        }
        Geometry n = geometryAt(q, size, axes);
        if (sameSurface(g, n, 0.02) < 0.5)
        {
            continue;
        }
        float value = images[push.inputA][q].a;
        sum += value;
        sum2 += value * value;
        count += 1;
    }
    float mean = sum / max(count, 1.0);
    float spatialVariance = max(sum2 / max(count, 1.0) - mean * mean, 0.0);
    // Where the surface was the frame before
    float2 previous;
    if ((push.flags & DENOISE_MOTION) != 0)
    {
        previous = float2(p) + 0.5 + images[push.motion][p].xy;
    }
    else
    {
        float4 clip = mul(push.view[0].previousViewProjection, float4(g.position, 1));
        float2 previousRegion = float2(unpackSize(push.previousSize));
        previous = (clip.xy / clip.w * 0.5 + 0.5) * previousRegion;
    }
    float expected = dot(push.view[0].previousViewProjection[3], float4(g.position, 1));
    float4 historyA = 0;
    float4 historyB = 0;
    float4 moments = 0;
    float weights = 0;
    if ((push.flags & DENOISE_HISTORY) != 0)
    {
        float2 previousRegion = float2(unpackSize(push.previousSize));
        float2 at = previous - 0.5;
        int2 base = int2(floor(at));
        float2 f = at - float2(base);
        for (int k = 0; k < 4; k++)
        {
            int2 o = int2(k & 1, k >> 1);
            int2 q = base + o;
            if (any(q < 0) || any(float2(q) >= previousRegion))
            {
                continue;
            }
            uint2 kept = uintImages[push.geometry][q].xy;
            float before = asfloat(kept.x);
            if (before <= 0)
            {
                continue;
            }
            float3 normalBefore = unpackNormal(kept.y);
            // The same surface: its distance as this frame's position seen from the frame before's camera (moving
            // surfaces: as their motion took them, within a tenth), facing alike
            float tolerance = (push.flags & DENOISE_MOTION) != 0 ? 0.1 : 0.03;
            if (abs(before - expected) > tolerance * expected || dot(normalBefore, g.normal) < 0.8)
            {
                continue;
            }
            float w = (o.x == 1 ? f.x : 1 - f.x) * (o.y == 1 ? f.y : 1 - f.y);
            historyA += w * images[push.historyA][q];
            historyB += w * images[push.historyB][q];
            moments += w * images[push.moments][q];
            weights += w;
        }
    }
    float frames = 1;
    float4 outA = a;
    float4 outB = float4(b.rgb, 0);
    float fast = mean;
    float fast2 = mean * mean;
    float slow2 = a.a * a.a;
    if (weights > 0.01)
    {
        historyA /= weights;
        historyB /= weights;
        moments /= weights;
        frames = min(floor(moments.w + 0.5) + 1, float(push.maxFrames));
        float fastFrames = min(frames, float(push.fastFrames));
        float fastWeight = 1 / fastFrames;
        fast = lerp(moments.x, mean, fastWeight);
        fast2 = lerp(moments.y, mean * mean, fastWeight);
        float w = 1 / frames;
        outA = lerp(historyA, a, w);
        outB.rgb = lerp(historyB.rgb, b.rgb, w);
        slow2 = lerp(moments.z, a.a * a.a, w);
        // The history fix: the long history must agree with the fast one within its noise (the neighbors' spread
        // over the pixels and frames the fast one averages); where it does not, something changed that nothing
        // announced: the history restarts from the fast one's frames.
        float noise = sqrt(spatialVariance / max(count, 1.0) / fastFrames);
        if (abs(outA.a - fast) > push.clampSigma * noise + 0.02)
        {
            outA = lerp(historyA, a, fastWeight);
            outA.a = fast;
            outB.rgb = lerp(historyB.rgb, b.rgb, fastWeight);
            slow2 = fast2;
            frames = fastFrames;
        }
    }
    // The value's variance: over the frames once there are a few, else over the neighbors
    float temporalVariance = max(slow2 - outA.a * outA.a, 0.0);
    outB.a = frames >= 4 ? temporalVariance : spatialVariance;
    images[push.outputA][p] = outA;
    images[push.outputB][p] = outB;
    images[push.outputMoments][p] = float4(fast, fast2, slow2, frames);
    uintImages[push.outputGeometry][p] = uint2(asuint(g.distance), packNormal(g.normal));
}

/** One step of the a-trous wavelet filter (5 x 5 taps `step` apart), edge-stopping, guided by the value's variance. */
[shader("compute")]
[numthreads(8, 8, 1)]
void atrousMain(uint3 id : SV_DispatchThreadID)
{
    float2 size = float2(unpackSize(push.size));
    if (any(float2(id.xy) >= size))
    {
        return;
    }
    int2 p = int2(id.xy);
    float3x3 axes = viewAxes(push.view[0].viewProjection);
    Geometry g = geometryAt(p, size, axes);
    float4 a = images[push.inputA][p];
    float4 b = images[push.inputB][p];
    if (g.distance <= 0)
    {
        images[push.outputA][p] = a;
        images[push.outputB][p] = b;
        return;
    }
    // The variance's 3 x 3 Gaussian (SVGF), for a steadier guide
    float variance = 0;
    float varianceWeights = 0;
    for (int k = 0; k < 9; k++)
    {
        int2 o = int2(k % 3, k / 3) - 1;
        int2 q = clamp(p + o, int2(0), int2(size) - 1);
        float w = (o.x == 0 ? 2.0 : 1.0) * (o.y == 0 ? 2.0 : 1.0);
        variance += w * images[push.inputB][q].a;
        varianceWeights += w;
    }
    float deviation = sqrt(max(variance / varianceWeights, 0.0));
    float kernel[3] = { 3.0 / 8.0, 1.0 / 4.0, 1.0 / 16.0 };
    float4 sumA = 0;
    float3 sumB = 0;
    float sumVariance = 0;
    float weights = 0;
    int step = int(max(push.step, 1u));
    for (int y = -2; y <= 2; y++)
    {
        for (int x = -2; x <= 2; x++)
        {
            int2 q = p + int2(x, y) * step;
            if (any(q < 0) || any(float2(q) >= size))
            {
                continue;
            }
            Geometry n = (x == 0 && y == 0) ? g : geometryAt(q, size, axes);
            float w = kernel[abs(x)] * kernel[abs(y)] * ((x == 0 && y == 0) ? 1.0 : sameSurface(g, n, 0.01));
            if (w <= 0)
            {
                continue;
            }
            float4 qa = images[push.inputA][q];
            float4 qb = images[push.inputB][q];
            if (push.sigmaValue > 0 && (x != 0 || y != 0))
            {
                w *= exp(-abs(qa.a - a.a) / (push.sigmaValue * deviation + 1.0e-3));
            }
            sumA += w * qa;
            sumB += w * qb.rgb;
            sumVariance += w * w * qb.a;
            weights += w;
        }
    }
    images[push.outputA][p] = sumA / weights;
    images[push.outputB][p] = float4(sumB / weights, sumVariance / (weights * weights));
}

/**
 * A blur over a disk of taps on the same surface (ReBLUR's pre- and post-blur): `radius` world units wide at the
 * pixel, turned by a per-pixel angle; with DENOISE_SHRINK narrower as one over the root of the history's frames.
 */
[shader("compute")]
[numthreads(8, 8, 1)]
void blurMain(uint3 id : SV_DispatchThreadID)
{
    float2 size = float2(unpackSize(push.size));
    if (any(float2(id.xy) >= size))
    {
        return;
    }
    int2 p = int2(id.xy);
    float3x3 axes = viewAxes(push.view[0].viewProjection);
    Geometry g = geometryAt(p, size, axes);
    float4 a = images[push.inputA][p];
    float4 b = images[push.inputB][p];
    if (g.distance <= 0)
    {
        images[push.outputA][p] = a;
        images[push.outputB][p] = b;
        return;
    }
    float3 row = push.view[0].viewProjection[1].xyz;
    float pixelsPerUnit = 0.5 * size.y * length(row - dot(row, axes[2]) * axes[2]);
    float radius = push.radius * pixelsPerUnit / g.distance;
    if ((push.flags & DENOISE_SHRINK) != 0)
    {
        radius /= sqrt(max(images[push.moments][p].w, 1.0));
    }
    radius = clamp(radius, 1.0, 32.0);
    float angle = 6.2831853 * frac(52.9829189 * frac(dot(float2(p) + 5.588238 * float(push.frame & 63),
        float2(0.06711056, 0.00583715))));
    float4 sumA = a;
    float3 sumB = b.rgb;
    float sumVariance = b.a;
    float weights = 1;
    static const uint TAPS = 12;
    for (uint k = 0; k < TAPS; k++)
    {
        float rho = radius * sqrt((float(k) + 0.5) / float(TAPS));
        float t = angle + float(k) * 2.39996323;
        int2 q = int2(round(float2(p) + 0.5 + rho * float2(cos(t), sin(t)) - 0.5));
        if (any(q < 0) || any(float2(q) >= size) || all(q == p))
        {
            continue;
        }
        Geometry n = geometryAt(q, size, axes);
        float w = sameSurface(g, n, 0.01);
        if (w <= 0)
        {
            continue;
        }
        float4 qb = images[push.inputB][q];
        sumA += w * images[push.inputA][q];
        sumB += w * qb.rgb;
        sumVariance += w * w * qb.a;
        weights += w;
    }
    images[push.outputA][p] = sumA / weights;
    images[push.outputB][p] = float4(sumB / weights, sumVariance / (weights * weights));
}
