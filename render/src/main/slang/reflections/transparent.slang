// Screen-space reflections of blended and transmissive surfaces (engine-render's TransparentReflections, the setting
// render.ssr.transparent): each fragment the transparency pass shades traces its own mirror ray, inline, through the
// opaque scene's depth, as ScreenSpaceReflections' HIZ kind traces the opaque pixels' rays (reflect.slang, whose
// march this follows): the hierarchical march over the nearest-depth pyramid (DepthPyramid with nearest), at its
// finest level a copy of the opaque depth (the transparency pass tests the depth itself as its attachment), surfaces
// taken as slabs of the thickness along their normal (here rebuilt from the depth: the surface outputs need not
// exist). Where the ray meets the opaque scene, its color there (SceneBehind's copy, blurred by the GGX lobe's cone,
// a level of its mip chain) is what replaces the environment's reflection: the caller adds
// reflectance * trust * (hit - environment), as the opaque composite does. One ray per fragment: no hit image, no
// tent of neighbors' hits, no spatial filter (a fragment cannot see its neighbors' rays).
module transparent;

import meshlets.common;

/** What a fragment traces through: the opaque scene's depth, its pyramid and color, and the tunables. */
public struct ScreenTrace
{
    public float4x4 viewProjection;        // the view's: camera-relative positions to clip space
    public float4x4 inverseViewProjection;
    public uint depth;                     // sampled slot: a copy of the opaque depth (reverse: 1 near, 0 at infinity)
    public uint pyramid;                   // sampled slot: the nearest-depth pyramid of it
    public uint pyramidMips;
    public uint chain;                     // sampled slot: the opaque scene's color with its mip chain (SceneBehind)
    public float2 size;                    // the render region, in pixels (the images' top left)
    public uint maxSteps;
    public float thickness;                // in view depth units (meters)
    public float maxDistance;
    public float cutoff;                   // perceptual roughness from which there are no screen-space reflections
    public float edgeFade;                 // of the region's size, along each edge
};

/** What a ray found: the opaque scene's color where it met it, and how much to trust that (0: nothing). */
public struct ScreenHit
{
    public float3 color;
    public float trust;
};

static const float FAR_AWAY = 1.0e30;

/** The least share of the distance behind a surface along the view ray that counts as into it (reflect.slang's). */
static const float GRAZING_SHARE = 0.25;

float opaqueDepth(ScreenTrace t, int2 p)
{
    return textures[t.depth].Load(int3(clamp(p, int2(0, 0), int2(t.size) - 1), 0)).r;
}

/** Screen position `s` (pixels of the region) with projected depth `z` back into camera-relative space. */
float3 unprojected(ScreenTrace t, float2 s, float z)
{
    float2 ndc = s / t.size * 2 - 1;
    float4 h = mul(t.inverseViewProjection, float4(ndc, z, 1));
    return h.xyz / h.w;
}

/** The view depth (clip w) of screen position `s` with projected depth `z`. */
float viewDepth(ScreenTrace t, float2 s, float z)
{
    float2 ndc = s / t.size * 2 - 1;
    return 1 / max(dot(t.inverseViewProjection[3], float4(ndc, z, 1)), 1.0e-20);
}

/** Camera-relative point `p` on the screen: pixels of the region, and projected depth. */
float3 projected(ScreenTrace t, float3 p)
{
    float4 c = mul(t.viewProjection, float4(p, 1));
    return float3((c.xy / c.w * 0.5 + 0.5) * t.size, c.z / c.w);
}

/** The far end of the ray from `origin` along `r`: the maximum distance, short of the camera's plane. */
float3 farEnd(ScreenTrace t, float3 origin, float3 r)
{
    float4 row = t.viewProjection[3];
    float w0 = dot(row, float4(origin, 1));
    float wr = dot(row.xyz, r);
    float along = t.maxDistance;
    float least = 1.0e-3 * w0;
    if (w0 + wr * along < least)
    {
        along = (least - w0) / wr; // towards the camera: stop before its plane
    }
    return origin + r * along;
}

/** The parameter where the screen segment s0 + (s1 - s0) t leaves the region (s0 inside). */
float leavesRegion(float2 s0, float2 s1, float2 size)
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
float behindDepth(ScreenTrace t, float2 s, float z)
{
    int2 p = int2(clamp(floor(s), float2(0, 0), t.size - 1));
    float d = opaqueDepth(t, p);
    if (d <= 0)
    {
        return -FAR_AWAY; // the sky: never in front of the ray
    }
    return viewDepth(t, s, z) - viewDepth(t, s, d);
}

/** reflect.slang's: within the thickness fully, fading out to twice it. */
float slabTrust(ScreenTrace t, float into)
{
    return 1 - smoothstep(t.thickness, 2 * t.thickness, into);
}

/**
 * The visible surface's |n.v| at pixel `q` (camera-relative view direction `v`), its normal rebuilt from the depth:
 * towards the neighbor along each axis whose depth is nearer its own (not across an outline), a neighbor off the
 * region or showing the sky left out; 1 where none is left (a lone pixel), as seen face on.
 */
float facing(ScreenTrace t, int2 q, float3 at, float3 v)
{
    float3 sides[2];
    int2 steps[2] = { int2(1, 0), int2(0, 1) };
    float here = viewDepth(t, float2(q) + 0.5, opaqueDepth(t, q));
    for (int axis = 0; axis < 2; axis++)
    {
        float best = FAR_AWAY;
        float3 side = 0;
        for (int sign = -1; sign <= 1; sign += 2)
        {
            int2 n = q + sign * steps[axis];
            if (any(n < 0) || any(n >= int2(t.size)))
            {
                continue; // off the region: not a neighbor (clamped, it would be the pixel itself)
            }
            float d = opaqueDepth(t, n);
            if (d <= 0)
            {
                continue; // the sky
            }
            float gap = abs(viewDepth(t, float2(n) + 0.5, d) - here);
            if (gap < best)
            {
                best = gap;
                side = (unprojected(t, float2(n) + 0.5, d) - at) * float(sign);
            }
        }
        if (best >= FAR_AWAY)
        {
            return 1;
        }
        sides[axis] = side;
    }
    float3 normal = cross(sides[0], sides[1]);
    float length2 = dot(normal, normal);
    return length2 > 1.0e-20 ? abs(dot(normal, v)) * rsqrt(length2) : 1;
}

/**
 * How deep into the surface pixel `q` shows a point `gap` behind its depth lies, surfaces taken as slabs of the
 * thickness along their normal: the distance behind along the view ray times |n.v|, at least GRAZING_SHARE of it
 * (reflect.slang's intoSurface, the normal rebuilt from the depth).
 */
float intoSlab(ScreenTrace t, int2 q, float gap)
{
    float3 at = unprojected(t, float2(q) + 0.5, opaqueDepth(t, q));
    float3 v = normalize(at);
    float3x3 axes = viewAxes(t.viewProjection);
    float along = gap / max(dot(v, axes[2]), 1.0e-3);
    return along * max(facing(t, q, at, v), GRAZING_SHARE);
}

/** The nearest depth (reverse: the largest) over cell `cell` of level `mip` (0: the depth itself). */
float nearestOver(ScreenTrace t, int mip, int2 cell)
{
    if (mip == 0)
    {
        return opaqueDepth(t, cell);
    }
    // The pyramid's level mip - 1: half the region rounded up, then halving down (DepthPyramid.levelSize)
    int2 level = max(int2(1, 1), int2((uint2(t.size) + 1) / 2) >> (mip - 1));
    return textures[t.pyramid].Load(int3(clamp(cell, int2(0, 0), level - 1), mip - 1)).r;
}

/** Where a ray went behind the depth: its parameter, the pixel it went behind, how far into that surface. */
struct Met
{
    bool found;
    float t;
    int2 pixel;
    float gap;
};

/** reflect.slang's marchHierarchical, over this trace's depth and pyramid. */
Met marchPyramid(ScreenTrace tr, float3 s0, float3 s1, float tEnd)
{
    Met c;
    c.found = false;
    c.t = 0;
    c.pixel = 0;
    c.gap = 0;
    float t;
    int2 last = 0;
    float3 o = float3(s0.xy, 1 - s0.z);
    float3 d = float3(s1.xy - s0.xy, s0.z - s1.z);
    d.xy = float2(abs(d.x) < 1.0e-6 ? 1.0e-6 : d.x, abs(d.y) < 1.0e-6 ? 1.0e-6 : d.y);
    float3 inverse = 1 / d;
    float2 floorOffset = float2(d.x < 0 ? 0 : 1, d.y < 0 ? 0 : 1);
    float2 nudge = 0.005 * float2(d.x < 0 ? -1 : 1, d.y < 0 ? -1 : 1);
    // Start at the next pixel
    float2 plane = floor(o.xy) + floorOffset + nudge;
    float2 first = (plane - o.xy) * inverse.xy;
    t = min(first.x, first.y);
    float3 position = o + d * t;
    int maxMip = int(tr.pyramidMips);
    int mip = 0;
    uint i = 0;
    while (i < tr.maxSteps && mip >= 0 && t <= tEnd)
    {
        float cellSize = exp2(float(mip));
        float2 cell = floor(position.xy / cellSize);
        float surface = 1 - nearestOver(tr, mip, int2(cell));
        if (mip == 0)
        {
            last = int2(cell);
        }
        float3 boundary = float3((cell + floorOffset) * cellSize + nudge, surface);
        float3 ts = (boundary - o) * inverse;
        ts.z = d.z > 0 ? ts.z : FAR_AWAY; // a ray coming nearer meets no cell's nearest depth from in front
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
    float3 s = s0 + (s1 - s0) * t;
    c.pixel = clamp(last, int2(0, 0), int2(tr.size) - 1);
    float2 inside = clamp(s.xy, float2(c.pixel), float2(c.pixel) + 0.999);
    c.gap = intoSlab(tr, c.pixel, behindDepth(tr, inside, s.z));
    c.found = c.gap > -2 * tr.thickness && slabTrust(tr, c.gap) > 0;
    c.t = t;
    return c;
}

/** The cone angle (half) of a GGX lobe of perceptual `roughness`, as a Phong lobe's (reflect.slang's). */
float lobeAngle(float roughness)
{
    float a = roughness * roughness;
    float power = 2 / max(a * a, 1.0e-6) - 2;
    return acos(pow(0.244, 1 / (power + 1)));
}

/** The opaque scene's color around `s` (pixels of the region), from the chain's level `lod`, kept in the region. */
float3 opaqueColor(ScreenTrace t, float2 s, float lod)
{
    uint width;
    uint height;
    uint levels;
    textures[t.chain].GetDimensions(0, width, height, levels);
    float2 image = float2(width, height);
    lod = min(lod, float(levels - 1));
    float coarse = ceil(lod);
    float2 levelRegion = max(floor(t.size / exp2(coarse)), 1);
    float2 levelImage = max(floor(image / exp2(coarse)), 1);
    float2 uv = clamp(s / image, 0.5 / levelImage, (levelRegion - 0.5) / levelImage);
    return textures[t.chain].SampleLevel(samplers[LINEAR_CLAMP], uv, lod).rgb;
}

/**
 * The opaque scene where the mirror ray from camera-relative `position` along `r` (unit) meets it, for a surface of
 * perceptual `roughness`: its color, blurred by the lobe's cone there, and the trust, fading at the region's edges,
 * the distance's last fifth, from three quarters of the cutoff in roughness and with how far behind the depth the ray
 * went; trust 0 where it leaves the region, goes past the distance or finds nothing.
 */
public ScreenHit traceScreen(ScreenTrace t, float3 position, float3 r, float roughness)
{
    ScreenHit hit;
    hit.color = 0;
    hit.trust = 0;
    if (roughness >= t.cutoff)
    {
        return hit;
    }
    float3 s0 = projected(t, position);
    if (any(s0.xy < 0) || any(s0.xy >= t.size))
    {
        return hit;
    }
    float3 s1 = projected(t, farEnd(t, position, r));
    float tEnd = leavesRegion(s0.xy, s1.xy, t.size);
    Met c = marchPyramid(t, s0, s1, tEnd);
    if (!c.found)
    {
        return hit;
    }
    int2 q = c.pixel;
    float depthAt = opaqueDepth(t, q);
    float3 at = unprojected(t, float2(q) + 0.5, depthAt);
    float distance = length(at - position);
    float2 uv = (float2(q) + 0.5) / t.size;
    float2 e = min(uv, 1 - uv);
    float edges = saturate(min(e.x, e.y) / max(t.edgeFade, 1.0e-4));
    hit.trust = slabTrust(t, c.gap) * edges * (1 - smoothstep(0.8, 1.0, distance / t.maxDistance))
        * (1 - smoothstep(0.75 * t.cutoff, t.cutoff, roughness));
    // The cone's footprint where it meets the scene, in pixels there: the chain's level whose texels are that wide
    float3x3 axes = viewAxes(t.viewProjection);
    float pixelsPerUnit = 0.5 * t.size.y * length(t.viewProjection[1].xyz
        - dot(t.viewProjection[1].xyz, axes[2]) * axes[2]);
    float hitDepth = viewDepth(t, float2(q) + 0.5, depthAt);
    float footprint = 2 * distance * tan(lobeAngle(roughness)) * pixelsPerUnit / max(hitDepth, 1.0e-4);
    hit.color = opaqueColor(t, float2(q) + 0.5, log2(max(footprint, 1)));
    return hit;
}
