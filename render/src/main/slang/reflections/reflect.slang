// Screen-space reflections (engine-render's ScreenSpaceReflections), in two compute passes over a view's render region:
//
// - traceMain, at the hit image's resolution (render.ssr.resolutionScale): each texel's pixel reflects the ray from the
//   camera about its surface's normal (the surface outputs, surfaces.slang) and follows it through the depth in
//   screen space, where a straight line stays straight (x, y and the projected depth all change linearly along it):
//   LINEAR marches in equal steps and refines the crossing by bisection, passing behind surfaces thicker than the
//   thickness; HIZ climbs and descends the nearest-depth pyramid (DepthPyramid with nearest), skipping cells the ray
//   passes wholly in front of (as AMD FidelityFX SSSR's hierarchical march), and takes the first surface it goes
//   behind, a hit only within the thickness. It writes where the ray met the scene (in the region, 0 to 1), how far
//   it went (of the maximum distance) and how much to trust it (fading at the image's edges and the distance's end; 0:
//   no hit: off the image, past the distance, behind a surface thicker than the thickness, or its back).
// - compositeMain, at full resolution: where a hit is trusted, the scene's color at it, blurred by the footprint of the
//   GGX lobe's cone there (a level of the scene's mip chain, SceneBehind's), replaces the environment's reflection
//   that shading added along the same mirror direction, weighed by the same reflectance (the surface outputs): color +
//   reflectance * trust * (hit - environment). Energy stays as shading had it, the environment taking over smoothly
//   wherever trust falls.
import meshlets.common;

struct ReflectPush
{
    View* view;
    IndirectLight* indirect; // with INDIRECT: the view's indirect-light table, for the environment's reflection
    uint depth;              // sampled slot: the scene's depth (reverse: 1 near, 0 at infinity)
    uint normal;             // storage slots of the surface outputs
    uint material;
    uint reflectance;
    uint color;              // storage slot: the scene's color, written in place by the composite
    uint chain;              // sampled slot: the scene's color with its mip chain
    uint chainMips;
    uint pyramid;            // sampled slot: the nearest-depth pyramid (HIZ)
    uint pyramidMips;
    uint hits;               // storage slot: the hit image
    uint size;               // the render region: width, height in the high 16 bits
    uint hitSize;            // the hit image's region
    uint imageSize;          // the whole color image (the chain's level 0)
    uint maxSteps;
    float thickness;         // in view depth units (meters)
    float maxDistance;
    float cutoff;            // perceptual roughness from which there are no screen-space reflections
    float edgeFade;          // of the region's size, along each edge
    float scale;             // the hit image's resolution, of the region's
};

[[vk::push_constant]]
ConstantBuffer<ReflectPush> push;

/** 1: HIZ, the hierarchical march; 0: LINEAR. */
[vk::constant_id(0)]
const uint HIERARCHICAL = 0;

/** 1: the view has an indirect-light table; 0: the constant sky (no pointer is read: pitfalls.md). */
[vk::constant_id(1)]
const uint INDIRECT = 0;

static const float LARGE = 1.0e30;

uint2 unpackSize(uint s)
{
    return uint2(s & 0xffff, s >> 16);
}

float sceneDepth(int2 p)
{
    return textures[push.depth].Load(int3(p, 0)).r;
}

/** Screen position `s` (pixels of the region) with projected depth `z` back into camera-relative space. */
float3 unproject(float2 s, float z, float2 size)
{
    float2 ndc = s / size * 2 - 1;
    float4 h = mul(push.view[0].inverseViewProjection, float4(ndc, z, 1));
    return h.xyz / h.w;
}

/** The view depth (clip w) of screen position `s` with projected depth `z`. */
float linearDepth(float2 s, float z, float2 size)
{
    float2 ndc = s / size * 2 - 1;
    float4x4 m = push.view[0].inverseViewProjection;
    return 1 / max(dot(m[3], float4(ndc, z, 1)), 1.0e-20);
}

/** Camera-relative point `p` on the screen: pixels of the region, and projected depth. */
float3 project(float3 p, float2 size)
{
    float4 c = mul(push.view[0].viewProjection, float4(p, 1));
    return float3((c.xy / c.w * 0.5 + 0.5) * size, c.z / c.w);
}

/** A pixel's surface: its normal (camera-relative), perceptual roughness and reflectance; reflectance 0: none. */
struct Pixel
{
    float3 position;
    float3 normal;
    float roughness;
    float3 reflectance;
};

Pixel pixelAt(uint2 p, float2 size, float3x3 axes)
{
    Pixel px;
    px.reflectance = 0;
    float depth = sceneDepth(int2(p));
    if (depth <= 0)
    {
        return px; // nothing: the sky
    }
    float3 reflectance = images[push.reflectance][p].rgb;
    float roughness = images[push.material][p].a;
    if (all(reflectance <= 0) || roughness >= push.cutoff)
    {
        return px;
    }
    px.position = unproject(float2(p) + 0.5, depth, size);
    px.normal = fromView(axes, unpackNormal(uintImages[push.normal][p].x));
    px.roughness = roughness;
    px.reflectance = reflectance;
    return px;
}

/** The far end of the ray from `origin` along `r`: the maximum distance, short of the camera's plane. */
float3 rayEnd(float3 origin, float3 r)
{
    float4 row = push.view[0].viewProjection[3];
    float w0 = dot(row, float4(origin, 1));
    float wr = dot(row.xyz, r);
    float along = push.maxDistance;
    float least = 1.0e-3 * w0;
    if (w0 + wr * along < least)
    {
        along = (least - w0) / wr; // towards the camera: stop before its plane
    }
    return origin + r * along;
}

/** The parameter where the screen segment s0 + (s1 - s0) t leaves the region (s0 inside). */
float leaveRegion(float2 s0, float2 s1, float2 size)
{
    float2 d = s1 - s0;
    float t = 1;
    if (d.x > 0) t = min(t, (size.x - s0.x) / d.x);
    if (d.x < 0) t = min(t, -s0.x / d.x);
    if (d.y > 0) t = min(t, (size.y - s0.y) / d.y);
    if (d.y < 0) t = min(t, -s0.y / d.y);
    return max(t, 0.0);
}

/** How far the ray at screen `s`, projected depth `z`, is behind the depth there (view depth; negative: in front). */
float behind(float2 s, float z, float2 size)
{
    int2 p = int2(clamp(floor(s), float2(0, 0), size - 1));
    float d = sceneDepth(p);
    if (d <= 0)
    {
        return -LARGE; // the sky: never in front of the ray
    }
    return linearDepth(s, z, size) - linearDepth(s, d, size);
}

/** Where a ray went behind the depth: its parameter, the pixel it went behind, how far behind it there. */
struct Crossing
{
    bool found;
    float t;
    int2 pixel;
    float gap;    // how deep into the surface (intoSurface)
};

/**
 * How much a crossing `into` a surface (intoSurface) counts as meeting it: fully within the thickness, fading out to
 * twice it, so neighbors whose rays pass behind a surface by about the thickness do not flip between hit and miss.
 */
float thicknessTrust(float into)
{
    return 1 - smoothstep(push.thickness, 2 * push.thickness, into);
}

/** The least share of the distance behind a surface along the view ray that counts as into it (grazing surfaces). */
static const float GRAZING = 0.25;

/**
 * How deep into the surface pixel `q` shows a point `gap` behind its depth (view depth units) lies, surfaces taken as
 * slabs of the thickness along their normal: the distance behind along the view ray times |n.v|, at least GRAZING of
 * it. Seen face on, the gap itself; at a silhouette or under a bulge, where the visible surface is seen at a grazing
 * angle and the object goes on behind it, a fraction of it (pixel by pixel there the depth behind jumps, and a test of
 * the gap alone flipped between hit and miss).
 */
float intoSurface(int2 q, float gap, float2 size)
{
    float3 at = unproject(float2(q) + 0.5, sceneDepth(q), size);
    float3 v = normalize(at);
    float3x3 axes = viewAxes(push.view[0].viewProjection);
    float3 n = fromView(axes, unpackNormal(uintImages[push.normal][q].x));
    float along = gap / max(dot(v, axes[2]), 1.0e-3);
    return along * max(abs(dot(n, v)), GRAZING);
}

/**
 * LINEAR: equal steps of at least a pixel, the first crossing behind refined; the first crossing the surface counts at
 * (thicknessTrust), the ray passing behind surfaces deeper than that.
 */
Crossing marchLinear(float3 s0, float3 s1, float tEnd, float2 size)
{
    Crossing c;
    c.found = false;
    c.t = 0;
    c.pixel = 0;
    c.gap = 0;
    float t;
    float3 d = s1 - s0;
    float pixels = length(d.xy);
    float dt = max(pixels * tEnd / float(push.maxSteps), 1.0) / max(pixels, 1.0e-6);
    float previous = min(1.0 / max(pixels, 1.0e-6), tEnd); // a pixel on: not the surface itself
    t = previous;
    for (uint i = 0; i < push.maxSteps; i++)
    {
        t = min(previous + dt, tEnd);
        float3 s = s0 + d * t;
        float gap = behind(s.xy, s.z, size);
        if (gap > 0)
        {
            // Behind a surface since the last step: the crossing, by bisection
            float lo = previous;
            float hi = t;
            for (int k = 0; k < 6; k++)
            {
                float mid = 0.5 * (lo + hi);
                float3 m = s0 + d * mid;
                if (behind(m.xy, m.z, size) > 0)
                {
                    hi = mid;
                }
                else
                {
                    lo = mid;
                }
            }
            float3 h = s0 + d * hi;
            int2 q = int2(clamp(floor(h.xy), float2(0, 0), size - 1)); // the pixel it went behind
            float into = intoSurface(q, behind(h.xy, h.z, size), size);
            if (thicknessTrust(into) > 0)
            {
                c.found = true;
                c.t = hi;
                c.pixel = q;
                c.gap = into;
                return c;
            }
            // Deeper into it than twice the thickness: the ray passes behind it
        }
        previous = t;
        if (t >= tEnd)
        {
            break;
        }
    }
    return c;
}

/** The nearest depth (reverse: the largest) over cell `cell` of level `mip` (0: the depth itself). */
float nearestDepth(int mip, int2 cell, uint2 size)
{
    if (mip == 0)
    {
        return sceneDepth(clamp(cell, int2(0, 0), int2(size) - 1));
    }
    // The pyramid's level mip - 1: half the region rounded up, then halving down (DepthPyramid.levelSize)
    int2 level = max(int2(1, 1), int2((size + 1) / 2) >> (mip - 1));
    return textures[push.pyramid].Load(int3(clamp(cell, int2(0, 0), level - 1), mip - 1)).r;
}

/**
 * HIZ: the hierarchical march over the nearest-depth pyramid, in pixels and 1 - depth (so farther is larger), as
 * FidelityFX SSSR's: a cell the ray passes wholly in front of is skipped and the next one taken a level coarser; where
 * it reaches the cell's nearest depth, or goes behind it, a level finer; at the depth itself, the first surface the ray
 * went behind, at the pixel (the cell of the depth itself) whose depth it went behind.
 */
Crossing marchHierarchical(float3 s0, float3 s1, float tEnd, float2 size)
{
    Crossing c;
    c.found = false;
    c.t = 0;
    c.pixel = 0;
    c.gap = 0;
    float t;
    int2 last = 0; // the cell of the depth itself last looked at
    float3 o = float3(s0.xy, 1 - s0.z);
    float3 d = float3(s1.xy - s0.xy, s0.z - s1.z);
    d.xy = float2(abs(d.x) < 1.0e-6 ? 1.0e-6 : d.x, abs(d.y) < 1.0e-6 ? 1.0e-6 : d.y);
    float3 inverse = 1 / d;
    float2 floorOffset = float2(d.x < 0 ? 0 : 1, d.y < 0 ? 0 : 1);
    float2 nudge = 0.005 * float2(d.x < 0 ? -1 : 1, d.y < 0 ? -1 : 1);
    // Start at the next pixel: not the surface itself
    float2 plane = floor(o.xy) + floorOffset + nudge;
    float2 first = (plane - o.xy) * inverse.xy;
    t = min(first.x, first.y);
    float3 position = o + d * t;
    int maxMip = int(push.pyramidMips);
    int mip = 0;
    uint i = 0;
    uint2 region = uint2(size);
    while (i < push.maxSteps && mip >= 0 && t <= tEnd)
    {
        float cellSize = exp2(float(mip));
        float2 cell = floor(position.xy / cellSize);
        float surface = 1 - nearestDepth(mip, int2(cell), region);
        if (mip == 0)
        {
            last = int2(cell);
        }
        float3 boundary = float3((cell + floorOffset) * cellSize + nudge, surface);
        float3 ts = (boundary - o) * inverse;
        ts.z = d.z > 0 ? ts.z : LARGE; // a ray coming nearer meets no cell's nearest depth from in front
        float least = min(min(ts.x, ts.y), ts.z);
        bool above = surface > position.z;
        bool skipped = least != ts.z && above;
        t = above ? least : t;
        position = o + d * t;
        mip = skipped ? min(mip + 1, maxMip) : mip - 1;
        i++;
    }
    if (mip >= 0 || t > tEnd)
    {
        return c;
    }
    // How far behind the depth of the pixel it went behind (the ray's point kept in that pixel: at a cell's border it
    // may lie on the neighbor's side by the nudge)
    float3 s = s0 + (s1 - s0) * t;
    c.pixel = clamp(last, int2(0, 0), int2(size) - 1);
    float2 inside = clamp(s.xy, float2(c.pixel), float2(c.pixel) + 0.999);
    c.gap = intoSurface(c.pixel, behind(inside, s.z, size), size);
    c.found = c.gap > -2 * push.thickness && thicknessTrust(c.gap) > 0;
    c.t = t;
    return c;
}

/** Of `extent` along the region's edges: 0 at an edge, 1 from `extent` in. */
float edgeFade(float2 uv)
{
    float2 e = min(uv, 1 - uv);
    return saturate(min(e.x, e.y) / max(push.edgeFade, 1.0e-4));
}

[shader("compute")]
[numthreads(8, 8, 1)]
void traceMain(uint3 id : SV_DispatchThreadID)
{
    uint2 hitSize = unpackSize(push.hitSize);
    if (any(id.xy >= hitSize))
    {
        return;
    }
    float2 size = float2(unpackSize(push.size));
    uint2 p = min(uint2((float2(id.xy) + 0.5) / push.scale), uint2(size) - 1); // the texel's pixel
    float3x3 axes = viewAxes(push.view[0].viewProjection);
    Pixel px = pixelAt(p, size, axes);
    float4 result = 0; // not traced: no reflecting surface here
    if (any(px.reflectance > 0))
    {
        result = float4(0, 0, 1, 0); // traced, no hit
        float3 r = reflect(normalize(px.position), px.normal);
        float3 s0 = project(px.position, size);
        float3 s1 = project(rayEnd(px.position, r), size);
        float tEnd = leaveRegion(s0.xy, s1.xy, size);
        Crossing c = HIERARCHICAL != 0 ? marchHierarchical(s0, s1, tEnd, size) : marchLinear(s0, s1, tEnd, size);
        if (c.found)
        {
            // The scene's color is taken at the pixel the ray went behind. No test of that pixel's normal against the
            // ray: it is the visible surface's, not the one the ray met (behind a silhouette, under a bulge, it faces
            // across or away from the ray though the ray meets the same object), and rejecting by it flipped
            // neighbors between hit and miss along outlines and contacts.
            int2 q = c.pixel;
            float3 at = unproject(float2(q) + 0.5, sceneDepth(q), size);
            float distance = length(at - px.position);
            float2 uv = (float2(q) + 0.5) / size;
            // Trust fades with how far behind the depth the ray went, at the edges and towards the distance's end
            float trust = thicknessTrust(c.gap) * edgeFade(uv)
                * (1 - smoothstep(0.8, 1.0, distance / push.maxDistance));
            result = float4(saturate(uv), saturate(distance / push.maxDistance), trust);
        }
    }
    images[push.hits][id.xy] = result;
}

/** The cone angle (half) of a GGX lobe of perceptual `roughness`, as a Phong lobe's (Uludag, GPU Pro 5). */
float coneAngle(float roughness)
{
    float a = roughness * roughness;
    float power = 2 / max(a * a, 1.0e-6) - 2;
    return acos(pow(0.244, 1 / (power + 1)));
}

/** The scene's color around `s` (pixels of the region), from the chain's level `lod`, kept inside the region. */
float3 sceneAt(float2 s, float lod, float2 size)
{
    float2 image = float2(unpackSize(push.imageSize));
    float coarse = ceil(lod);
    float2 levelRegion = max(floor(size / exp2(coarse)), 1);
    float2 levelImage = max(floor(image / exp2(coarse)), 1);
    float2 uv = clamp(s / image, 0.5 / levelImage, (levelRegion - 0.5) / levelImage);
    return textures[push.chain].SampleLevel(samplers[LINEAR_CLAMP], uv, lod).rgb;
}

/** What the hits around a pixel add up to: their color by weight and trust, their trust and their weights. */
struct Gathered
{
    float3 found;
    float trust;
    float weights;
    float distance; // the hits' distance, by weight and trust
};

/**
 * Adds hit texel `q` with weight `w` to `g`, for pixel `px` at view depth `depth`: none where nothing was traced or it
 * was traced from another surface (off this one's plane by over 2 % of the depth, or facing another way); its color
 * the scene's at the hit, blurred by the cone's footprint there (the chain's level whose texels are that wide).
 */
void gather(inout Gathered g, int2 q, float w, Pixel px, float depth, float2 size, float tangent, float pixelsPerUnit)
{
    uint2 hitSize = unpackSize(push.hitSize);
    q = clamp(q, int2(0, 0), int2(hitSize) - 1);
    uint2 from = min(uint2((float2(q) + 0.5) / push.scale), uint2(size) - 1);
    float4 hit = images[push.hits][q];
    if (all(hit == 0))
    {
        return; // not traced: no reflecting surface there (an object standing on this one), not a miss
    }
    float3 there = unproject(float2(from) + 0.5, sceneDepth(int2(from)), size);
    float3 normal = fromView(viewAxes(push.view[0].viewProjection), unpackNormal(uintImages[push.normal][from].x));
    w *= abs(dot(there - px.position, px.normal)) <= 0.02 * depth && dot(normal, px.normal) > 0.9 ? 1 : 0;
    g.weights += w;
    if (w <= 0 || hit.w <= 0)
    {
        return;
    }
    float2 s = hit.xy * size;
    float distance = hit.z * push.maxDistance;
    float hitDepth = linearDepth(s, sceneDepth(int2(min(floor(s), size - 1))), size);
    float footprint = 2 * distance * tangent * pixelsPerUnit / max(hitDepth, 1.0e-4);
    float lod = clamp(log2(max(footprint, 1)), 0, float(push.chainMips - 1));
    g.found += w * hit.w * sceneAt(s, lod, size);
    g.trust += w * hit.w;
    g.distance += w * hit.w * distance;
}

/** Taps of the spatial filter of rough reflections: a sunflower (Vogel) disk of radius 1. */
static const uint TAPS = 12;

[shader("compute")]
[numthreads(8, 8, 1)]
void compositeMain(uint3 id : SV_DispatchThreadID)
{
    float2 size = float2(unpackSize(push.size));
    if (any(float2(id.xy) >= size))
    {
        return;
    }
    float3x3 axes = viewAxes(push.view[0].viewProjection);
    Pixel px = pixelAt(id.xy, size, axes);
    if (all(px.reflectance <= 0))
    {
        return;
    }
    float depth = linearDepth(float2(id.xy) + 0.5, sceneDepth(int2(id.xy)), size);
    float pixelsPerUnit = 0.5 * size.y * length(push.view[0].viewProjection[1].xyz
        - dot(push.view[0].viewProjection[1].xyz, axes[2]) * axes[2]);
    float tangent = tan(coneAngle(px.roughness));
    // The hits of the 3 x 3 hit texels around the pixel's, by a tent (1 2 1 by 1 2 1): a reconstruction of the hits'
    // coverage, so an outline is antialiased and one ray missing or meeting another pixel than its neighbors' (along
    // outlines and contacts, where the depth behind jumps from pixel to pixel) does not show as a hair or a hole
    float2 at = (float2(id.xy) + 0.5) * push.scale - 0.5;
    int2 base = int2(round(at));
    Gathered g = { float3(0), 0, 0, 0 };
    for (int k = 0; k < 9; k++)
    {
        int2 o = int2(k % 3, k / 3) - 1;
        float w = (o.x == 0 ? 2 : 1) * (o.y == 0 ? 2 : 1);
        gather(g, base + o, w, px, depth, size, tangent, pixelsPerUnit);
    }
    // Rough surfaces: one ray per pixel finds the lobe's middle only, and the sky or nothing around an object leaves
    // its blurred image cut at its outline. The rays of the pixels around cover the lobe: those within the radius its
    // cone has on the reflected image (at the hits' distance beyond the surface; the maximum distance where none hit),
    // their hits and misses (the environment) averaged. A spatial filter of this frame alone: no history.
    float distance = g.trust > 0 ? g.distance / g.trust : push.maxDistance;
    float radius = min(tangent * pixelsPerUnit * distance / (depth + distance), 24.0);
    if (radius >= 1.5)
    {
        for (uint k = 0; k < TAPS; k++)
        {
            float rho = radius * sqrt((float(k) + 0.5) / float(TAPS));
            float angle = float(k) * 2.39996323;
            float2 offset = rho * float2(cos(angle), sin(angle));
            gather(g, int2(round(((float2(id.xy) + 0.5 + offset) * push.scale) - 0.5)), 1, px, depth, size,
                tangent, pixelsPerUnit);
        }
    }
    if (g.trust <= 0 || g.weights <= 0)
    {
        return;
    }
    float3 reflected = g.found / g.trust; // the hits' color, by their trust
    float trust = g.trust / g.weights;
    trust *= 1 - smoothstep(0.75 * push.cutoff, push.cutoff, px.roughness);
    float3 r = reflect(normalize(px.position), px.normal);
    float3 environment = INDIRECT != 0 ? specularLight(push.indirect, r, px.roughness) : SKY_REFLECTED;
    float3 color = images[push.color][id.xy].rgb;
    images[push.color][id.xy] = float4(color + px.reflectance * trust * (reflected - environment), 1);
}
