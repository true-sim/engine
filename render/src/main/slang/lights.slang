// Lights and how they reach a surface: the scene's lights (engine-render's Lights), each with its shadows, and the
// light list a binning technique made for the frame (Keys.LIGHT_LIST), read through one interface whatever its
// layout. Every way of shading imports this, so lights look the same in all.
//
// A light casts shadows through consecutive entries of the shadow table, each of a kind (a shadow map with its filter,
// a screen-space mask, an inline ray query) evaluated by an IShadow: lights of one frame may each use another
// technique.
// A spot light uses one entry; a directional light one per cascade, chosen by view depth (the first whose split is
// beyond the surface); a point light six, cube faces +x, -x, +y, -y, +z, -z, chosen by the direction from the light.
module lights;

import bindless;
import shading;

public static const uint POINT = 0;
public static const uint SPOT = 1;
public static const uint DIRECTIONAL = 2;

/**
 * One light: 80 bytes. At a world position in two floats per axis, as instances are; a light list hands it out
 * relative to the camera it shades for (relativeTo()).
 */
public struct Light
{
    public float3 position;        // world position, high part; relative to the shading camera once handed out
    public float range;            // where its light ends (smoothly); unused for directional lights
    public float3 color;           // linear, intensity included: irradiance at 1 unit (directional: at any distance)
    public uint type;
    public float3 direction;       // where it shines: spot and directional lights
    public float cosOuter;         // spot: no light beyond this angle's cosine
    public float cosInner;         // spot: full light within this
    public int shadowFirst;        // its first entry in the shading view's shadow table, or -1: set as the view's
    public uint shadowCount;       // light list hands it out (handOut), from that table's ranges
    public uint pad;
    public float3 positionLow;     // world position, the rest
    public uint pad1;
};

/** The world position (high, low) less the camera's: what a float near the camera holds. */
public float3 lessCamera(float3 high, float3 low, float3 cameraHigh, float3 cameraLow)
{
    precise float3 h = high - cameraHigh;
    precise float3 l = low - cameraLow;
    return h + l;
}

/**
 * One shadow table entry: 128 bytes. What `map` and `params` mean depends on the kind. Each entry has a world position
 * of its own (a map's: its light camera's; a ray's: its top level's), so it serves any view: a surface is moved from
 * the shading camera's positions into the entry's before the lookup.
 */
public struct Shadow
{
    public float4x4 toShadow;      // maps: positions relative to the entry's to the map's clip space (reverse depth)
    public uint kind;              // SHADOW_MAP, SHADOW_MASK, SHADOW_RAY
    public uint filter;            // maps: PCF, ...
    public uint map;               // maps: the depth map's sampled slot; masks: the mask's; rays: the low half of the
                                   // scene's RayScene address (raytracing.slang)
    public uint size;              // maps: texels across; rays: the high half of that address
    public float bias;             // maps: in the map's depth units
    public float split;            // cascades: the view depth this cascade reaches
    public float2 params;          // maps: (normal offset, 0); masks: (channel, 0); rays: (offset per unit of distance, 0)
    public float3 position;        // the entry's world position, high part
    public uint pad;
    public float3 positionLow;     // and the rest
    public uint pad1;
};

/** Shadow kinds. */
public static const uint SHADOW_MAP = 0;
public static const uint SHADOW_MASK = 1;
public static const uint SHADOW_RAY = 2;
/** No shadow: an entry no technique of the view set reads as lit. */
public static const uint SHADOW_NONE = 3;

/** Shadow map filters: what PCSS, VSM, ESM and others will add to. */
public static const uint PCF = 0;

/**
 * The frame's light list, as a binning technique made it: 80 bytes, then the layout's own data. Shading reads it
 * through lightList(), which opens the layout's ILightList for a surface.
 */
public struct LightListHeader
{
    public uint layout;            // CLUSTERS, ...
    public uint lightCount;
    public uint overflow;          // the most lights a bin wanted, beyond what it holds
    public uint shadowCapacity;    // the shadow table's entries: each light's range of them lies past them
    public Light* lights;
    public Shadow* shadows;
    public uint* data0;            // the layout's lists (clusters: a count per cluster)
    public uint* data1;            // (clusters: maxPerCluster slots per cluster)
    public uint4 ints;             // the layout's numbers (clusters: across, down, deep, tile size in pixels)
    public float4 floats;          // (clusters: near, slices per unit of log depth, maxPerCluster as bits, 0)
    public float3 camera;          // the world position of the camera it shades for, high part
    public uint pad1;
    public float3 cameraLow;       // and the rest; 112 bytes in all
    public uint pad2;

    /** `light` as this list's shading sees it: its position relative to the camera. */
    public Light relativeTo(Light light)
    {
        Light r = light;
        r.position = lessCamera(light.position, light.positionLow, camera, cameraLow);
        r.positionLow = float3(0);
        return r;
    }

    /**
     * Light `index` as this list's shading sees it: relative to the camera, casting shadows through the entries this
     * view's shadow table names for it (its ranges, past the entries), whatever another view's say.
     */
    public Light handOut(uint index)
    {
        Light r = relativeTo(lights[index]);
        int2 range = ((int2*)((uint64_t)shadows + 128 * uint64_t(shadowCapacity)))[index];
        r.shadowFirst = range.x;
        r.shadowCount = uint(range.y);
        return r;
    }

    /** What moves a position relative to the camera into shadow entry `s`'s. */
    public float3 toEntry(Shadow s)
    {
        return lessCamera(camera, cameraLow, s.position, s.positionLow);
    }
};

/**
 * `h[0]`, read field by field. Loaded as one struct, the driver of an AMD Radeon(TM) Graphics read the pointers after
 * the first as their low half twice (pitfalls.md); one load per field reads them right.
 */
public LightListHeader loadHeader(LightListHeader* h)
{
    LightListHeader r;
    r.layout = h[0].layout;
    r.lightCount = h[0].lightCount;
    r.overflow = h[0].overflow;
    r.shadowCapacity = h[0].shadowCapacity;
    r.lights = h[0].lights;
    r.shadows = h[0].shadows;
    r.data0 = h[0].data0;
    r.data1 = h[0].data1;
    r.ints = h[0].ints;
    r.floats = h[0].floats;
    r.camera = h[0].camera;
    r.pad1 = 0;
    r.cameraLow = h[0].cameraLow;
    r.pad2 = 0;
    return r;
}

/** Light list layouts. */
public static const uint CLUSTERS = 1;

/** The lights reaching one surface, from a light list: how a layout is read. A new layout implements this. */
public interface ILightList
{
    /** How many lights may reach the surface. */
    uint count();

    /** The i-th of them, i < count(). */
    Light light(uint i);
}

/**
 * Clusters: the view's frustum in tiles of pixels across and down and exponential depth slices, each with its own
 * light slots.
 */
public struct ClusterLightList : ILightList
{
    LightListHeader header;
    uint cluster;
    uint lights;

    /** The cluster of the surface at `pixel`, `depth` from the viewer along the view direction. */
    public __init(LightListHeader h, uint2 pixel, float depth)
    {
        header = h;
        uint4 dims = h.ints;
        float near = h.floats.x;
        uint slice = uint(clamp(log(max(depth, near) / near) * h.floats.y, 0.0, float(dims.z - 1)));
        uint2 tile = min(pixel / dims.w, dims.xy - 1);
        cluster = (slice * dims.y + tile.y) * dims.x + tile.x;
        lights = min(h.data0[cluster], asuint(h.floats.z));
    }

    public uint count()
    {
        return lights;
    }

    public Light light(uint i)
    {
        return header.handOut(header.data1[cluster * asuint(header.floats.z) + i]);
    }
}

/** Where a surface asks for its shadow: what every kind of shadow may need. */
public struct ShadowQuery
{
    public float3 world;           // position relative to the entry's (Shadow.position)
    public float3 normal;          // the surface's
    public float3 toLight;         // unit
    public float distance;         // to the light (directional: infinite)
    public uint2 pixel;
    public float depth;            // from the viewer, along the view direction
    public float fromViewer;       // distance from the viewer
};

/** Traces shadow rays, or not: pipelines without ray queries use NoRays, so only traced variants need the feature. */
public interface IShadowRays
{
    /** Whether the way from origin along direction is clear up to tMax: 1, or 0 when something is in the way. */
    float clear(uint64_t scene, float3 origin, float3 direction, float tMax);
}

/** No ray tracing: inline ray shadows count as lit. */
public struct NoRays : IShadowRays
{
    public float clear(uint64_t scene, float3 origin, float3 direction, float tMax)
    {
        return 1;
    }
}

/** How a kind of shadow (or a shadow map filter) is evaluated: a new technique implements this. */
public interface IShadow
{
    /** How much of the light reaches the surface: 0 (in shadow) to 1. */
    float visibility(Shadow s, ShadowQuery q);
}

/**
 * A depth map, 3 x 3 percentage-closer filtering; outside the map, lit. Looked up from the surface moved along its
 * normal by the entry's normal offset (about a texel), against acne where the light grazes.
 */
public struct PcfShadow : IShadow
{
    public float visibility(Shadow s, ShadowQuery q)
    {
        float3 normal = dot(q.normal, q.toLight) < 0 ? -q.normal : q.normal;
        float4 clip = mul(s.toShadow, float4(q.world + normal * s.params.x, 1));
        float3 p = clip.xyz / clip.w;
        float2 uv = p.xy * 0.5 + 0.5;
        if (any(uv < 0) || any(uv > 1) || p.z < 0 || p.z > 1)
        {
            return 1;
        }
        int2 texel = int2(floor(uv * s.size));
        uint map = NonUniformResourceIndex(s.map); // neighbours may read other cascades and lights
        // The 3 x 3 texels around, in four gathers of 2 x 2 rather than nine loads: each taken at a texel corner (in
        // texels, c: its 2 x 2 is c - 1 and c each way, as bilinear filtering would read there), edges clamped by the
        // sampler. Gathered as (x: left bottom, y: right bottom, z: right top, w: left top), top the lower row.
        float depth = p.z + s.bias;
        float texelSize = 1.0 / s.size;
        float4 a = textures[map].GatherRed(samplers[NEAREST_CLAMP], float2(texel) * texelSize);
        float4 b = textures[map].GatherRed(samplers[NEAREST_CLAMP], float2(texel + int2(2, 0)) * texelSize);
        float4 c = textures[map].GatherRed(samplers[NEAREST_CLAMP], float2(texel + int2(0, 2)) * texelSize);
        float4 d = textures[map].GatherRed(samplers[NEAREST_CLAMP], float2(texel + int2(2, 2)) * texelSize);
        float4 litA = step(a, depth);          // texels (-1, -1) to (0, 0): all four
        float2 litB = step(float2(b.w, b.x), depth); // (1, -1), (1, 0)
        float2 litC = step(float2(c.w, c.z), depth); // (-1, 1), (0, 1)
        float litD = step(d.w, depth);         // (1, 1)
        return (dot(litA, 1) + litB.x + litB.y + litC.x + litC.y + litD) / 9;
    }
}

/** A screen-space mask, one channel per light: what an earlier pass found per pixel. */
public struct MaskShadow : IShadow
{
    public float visibility(Shadow s, ShadowQuery q)
    {
        return textures[NonUniformResourceIndex(s.map)].Load(int3(q.pixel, 0))[uint(s.params.x)];
    }
}

/** An inline ray query towards the light, from just off the surface. */
public struct RayShadow<R : IShadowRays> : IShadow
{
    R rays;

    public __init(R rays)
    {
        this.rays = rays;
    }

    public float visibility(Shadow s, ShadowQuery q)
    {
        float offset = s.params.x * q.fromViewer + 1.0e-3;
        float3 normal = dot(q.normal, q.toLight) < 0 ? -q.normal : q.normal;
        return rays.clear((uint64_t(s.size) << 32) | s.map, q.world + normal * offset, q.toLight, q.distance);
    }
}

/** Shadow entry `s` evaluated by its kind's IShadow. */
public float shadowVisibility<R : IShadowRays>(R rays, Shadow s, ShadowQuery q)
{
    switch (s.kind)
    {
    case SHADOW_MAP:
        return s.filter == PCF ? PcfShadow().visibility(s, q) : 1;
    case SHADOW_MASK:
        return MaskShadow().visibility(s, q);
    case SHADOW_RAY:
        return RayShadow<R>(rays).visibility(s, q);
    default:
        return 1;
    }
}

/** Interleaved gradient noise (Jimenez 2014): a value in [0, 1) per pixel that neighbours spread evenly. */
float dither(uint2 pixel)
{
    return frac(52.9829189 * frac(0.06711056 * float(pixel.x) + 0.00583715 * float(pixel.y)));
}

/**
 * The light's shadow map for a surface at `world`, `depth` from the viewer, at `pixel`, or -1. Across the last tenth of
 * a cascade's depths, pixels take the next cascade more and more (dithered by pixel): no seam where the texel size
 * changes, for one lookup.
 */
int shadowFor(Light light, Shadow* shadows, float3 world, float depth, uint2 pixel)
{
    if (light.shadowFirst < 0 || light.shadowCount == 0)
    {
        return -1;
    }
    if (light.type == DIRECTIONAL && light.shadowCount > 1) // cascades; a single entry covers every depth
    {
        float start = 0;
        for (uint i = 0; i < light.shadowCount; i++)
        {
            float split = shadows[light.shadowFirst + i].split;
            if (depth <= split)
            {
                float band = (split - start) * 0.1;
                if (i + 1 < light.shadowCount && depth > split - band && dither(pixel) < (depth - split + band) / band)
                {
                    return light.shadowFirst + int(i) + 1; // the next one covers these depths too
                }
                return light.shadowFirst + int(i);
            }
            start = split;
        }
        return -1; // past the last cascade: lit
    }
    if (light.type == POINT && light.shadowCount >= 6)
    {
        float3 d = world - light.position;
        float3 a = abs(d);
        uint face = a.x >= a.y && a.x >= a.z ? (d.x >= 0 ? 0 : 1)
            : a.y >= a.z ? (d.y >= 0 ? 2 : 3) : (d.z >= 0 ? 4 : 5);
        return light.shadowFirst + int(face);
    }
    return light.shadowFirst;
}

/**
 * What light `light` adds to surface `s` at `world`, seen from `toViewer`, at `pixel`, `depth` from the viewer; shadow
 * rays traced by `rays`.
 */
public float3 lightSurface<R : IShadowRays>(R rays, LightListHeader h, Light light, Surface s, float3 world,
    float3 toViewer, uint2 pixel, float depth)
{
    Shadow* shadows = h.shadows;
    float3 toLight;
    float3 irradiance = light.color;
    float distance = 1.0e30;
    if (light.type == DIRECTIONAL)
    {
        toLight = -light.direction;
    }
    else
    {
        float3 d = light.position - world;
        float distance2 = max(dot(d, d), 1.0e-4);
        toLight = d * rsqrt(distance2);
        distance = sqrt(distance2);
        // Inverse square, ending smoothly at the range (as in Karis 2013).
        float ratio = distance2 / (light.range * light.range);
        float window = saturate(1 - ratio * ratio);
        irradiance *= window * window / distance2;
        if (light.type == SPOT)
        {
            irradiance *= smoothstep(light.cosOuter, light.cosInner, dot(-toLight, light.direction));
        }
    }
    if (all(irradiance <= 0))
    {
        return 0;
    }
    int shadow = shadowFor(light, shadows, world, depth, pixel);
    if (shadow >= 0)
    {
        Shadow entry = shadows[shadow];
        ShadowQuery q = { world + h.toEntry(entry), s.normal, toLight, distance, pixel, depth, length(world) };
        irradiance *= shadowVisibility(rays, entry, q);
    }
    return reflected(s, toViewer, toLight, irradiance);
}

/** What every light of `list` adds to surface `s` at `world`, seen from `toViewer`, `depth` from the viewer. */
public float3 lightAll<L : ILightList, R : IShadowRays>(L list, R rays, LightListHeader h, Surface s, float3 world,
    float3 toViewer, uint2 pixel, float depth)
{
    float3 sum = 0;
    for (uint i = 0; i < list.count(); i++)
    {
        sum += lightSurface(rays, h, list.light(i), s, world, toViewer, pixel, depth);
    }
    return sum;
}

/**
 * What the lights of light list `h` add to the surface at `pixel` and `depth` (from the viewer, along the view
 * direction): the layout's ILightList opened there, shadow rays traced by `rays`. Without a light list (null),
 * nothing.
 */
public float3 lightList<R : IShadowRays>(R rays, LightListHeader* h, Surface s, float3 world, float3 toViewer,
    uint2 pixel, float depth)
{
    if (h == nullptr)
    {
        return 0;
    }
    LightListHeader header = loadHeader(h);
    switch (header.layout)
    {
    case CLUSTERS:
        return lightAll(ClusterLightList(header, pixel, depth), rays, header, s, world, toViewer, pixel, depth);
    default:
        return 0;
    }
}
