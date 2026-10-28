// Lights and how they reach a surface: the scene's lights (engine-render's Lights), each with its shadows, and the light
// list a binning technique made for the frame (Keys.LIGHT_LIST), read through one interface whatever its layout. Every
// way of shading imports this, so lights look the same in all.
//
// A light may cast shadows from one or more shadow maps, consecutive entries of the shadow table: a spot light one; a
// directional light one per cascade, chosen by view depth (the first whose split is beyond the surface); a point light
// six, cube faces +x, -x, +y, -y, +z, -z, chosen by the direction from the light.
module lights;

import bindless;
import shading;

public static const uint POINT = 0;
public static const uint SPOT = 1;
public static const uint DIRECTIONAL = 2;

/** One light: 64 bytes. Positions are camera-relative, as instances are. */
public struct Light
{
    public float3 position;
    public float range;            // where its light ends (smoothly); unused for directional lights
    public float3 color;           // linear, intensity included: irradiance at 1 unit (directional: at any distance)
    public uint type;
    public float3 direction;       // where it shines: spot and directional lights
    public float cosOuter;         // spot: no light beyond this angle's cosine
    public float cosInner;         // spot: full light within this
    public int shadowFirst;        // its first shadow map in the shadow table, or -1
    public uint shadowCount;
    public uint pad;
};

/** One shadow map: 80 bytes. */
public struct Shadow
{
    public float4x4 toShadow;      // camera-relative positions to the map's clip space (reverse depth)
    public uint map;               // sampled slot of the depth map
    public float bias;             // in the map's depth units
    public float split;            // cascades: the view depth this cascade reaches
    public uint size;              // texels across
};

/**
 * The frame's light list, as a binning technique made it: 80 bytes, then the layout's own data. Shading reads it through
 * lightList(), which opens the layout's ILightList for a surface.
 */
public struct LightListHeader
{
    public uint layout;            // CLUSTERS, ...
    public uint lightCount;
    public uint overflow;          // the most lights a bin wanted, beyond what it holds
    public uint pad;
    public Light* lights;
    public Shadow* shadows;
    public uint* data0;            // the layout's lists (clusters: a count per cluster)
    public uint* data1;            // (clusters: maxPerCluster slots per cluster)
    public uint4 ints;             // the layout's numbers (clusters: across, down, deep, tile size in pixels)
    public float4 floats;          // (clusters: near, slices per unit of log depth, maxPerCluster as bits, 0)
};

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
        return header.lights[header.data1[cluster * asuint(header.floats.z) + i]];
    }
}

/** How much of the light at `world` (camera-relative) reaches it past shadow `s`: 3 x 3 percentage-closer filtering. */
float shadowed(Shadow s, float3 world)
{
    float4 clip = mul(s.toShadow, float4(world, 1));
    float3 q = clip.xyz / clip.w;
    float2 uv = q.xy * 0.5 + 0.5;
    if (any(uv < 0) || any(uv > 1) || q.z < 0 || q.z > 1)
    {
        return 1; // outside the map: lit
    }
    int2 texel = int2(floor(uv * s.size));
    int last = int(s.size) - 1;
    float lit = 0;
    for (int y = -1; y <= 1; y++)
    {
        for (int x = -1; x <= 1; x++)
        {
            float nearest = textures[s.map].Load(int3(clamp(texel + int2(x, y), 0, last), 0)).r;
            lit += q.z + s.bias >= nearest ? 1 : 0;
        }
    }
    return lit / 9;
}

/** The light's shadow map for a surface at `world`, `depth` from the viewer, or -1. */
int shadowFor(Light light, Shadow* shadows, float3 world, float depth)
{
    if (light.shadowFirst < 0 || light.shadowCount == 0)
    {
        return -1;
    }
    if (light.type == DIRECTIONAL)
    {
        for (uint i = 0; i < light.shadowCount; i++)
        {
            if (depth <= shadows[light.shadowFirst + i].split)
            {
                return light.shadowFirst + int(i);
            }
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

/** What light `light` adds to surface `s` at `world`, seen from `toViewer`, `depth` from the viewer. */
public float3 lightSurface(Light light, Shadow* shadows, Surface s, float3 world, float3 toViewer, float depth)
{
    float3 toLight;
    float3 irradiance = light.color;
    if (light.type == DIRECTIONAL)
    {
        toLight = -light.direction;
    }
    else
    {
        float3 d = light.position - world;
        float distance2 = max(dot(d, d), 1.0e-4);
        toLight = d * rsqrt(distance2);
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
    int shadow = shadowFor(light, shadows, world, depth);
    if (shadow >= 0)
    {
        irradiance *= shadowed(shadows[shadow], world);
    }
    return reflected(s, toViewer, toLight, irradiance);
}

/** What every light of `list` adds to surface `s` at `world`, seen from `toViewer`, `depth` from the viewer. */
public float3 lightAll<L : ILightList>(L list, Shadow* shadows, Surface s, float3 world, float3 toViewer, float depth)
{
    float3 sum = 0;
    for (uint i = 0; i < list.count(); i++)
    {
        sum += lightSurface(list.light(i), shadows, s, world, toViewer, depth);
    }
    return sum;
}

/**
 * What the lights of light list `h` add to the surface at `pixel` and `depth` (from the viewer, along the view
 * direction): the layout's ILightList opened there. Without a light list (null), nothing.
 */
public float3 lightList(LightListHeader* h, Surface s, float3 world, float3 toViewer, uint2 pixel, float depth)
{
    if (h == nullptr)
    {
        return 0;
    }
    LightListHeader header = h[0];
    switch (header.layout)
    {
    case CLUSTERS:
        return lightAll(ClusterLightList(header, pixel, depth), header.shadows, s, world, toViewer, depth);
    default:
        return 0;
    }
}
