// Lights and how they reach a surface: the scene's lights (engine-render's Lights), binned per cluster of the view's
// frustum (LightClusters), each with its shadows. Every way of shading imports this, so lights look the same in all.
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

/** What LightClusters made for the frame: 64 bytes, then a light count per cluster. */
public struct ClusterHeader
{
    public uint3 dims;             // clusters across, down and deep
    public uint tileSize;          // pixels per cluster, across and down
    public float near;             // where the first depth slice starts
    public float sliceScale;       // slices per unit of log depth: slice = log(depth / near) * sliceScale
    public uint maxPerCluster;     // light slots per cluster
    public uint overflow;          // the most lights any cluster wanted
    public Light* lights;
    public Shadow* shadows;
    public uint* indices;          // maxPerCluster slots per cluster
    public uint* counts;           // per cluster
};

/** The cluster of a pixel at view depth `depth`. */
public uint clusterAt(ClusterHeader* h, uint2 pixel, float depth)
{
    ClusterHeader c = h[0];
    uint slice = uint(clamp(log(max(depth, c.near) / c.near) * c.sliceScale, 0.0, float(c.dims.z - 1)));
    uint2 tile = min(pixel / c.tileSize, c.dims.xy - 1);
    return (slice * c.dims.y + tile.y) * c.dims.x + tile.x;
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

/**
 * What every light of the surface's cluster adds: `pixel` and `depth` (from the viewer, along the view direction)
 * place it in the grid. Without clusters (null), nothing.
 */
public float3 lightCluster(ClusterHeader* h, Surface s, float3 world, float3 toViewer, uint2 pixel, float depth)
{
    if (h == nullptr)
    {
        return 0;
    }
    ClusterHeader c = h[0];
    uint cluster = clusterAt(h, pixel, depth);
    uint count = min(c.counts[cluster], c.maxPerCluster);
    float3 sum = 0;
    for (uint i = 0; i < count; i++)
    {
        sum += lightSurface(c.lights[c.indices[cluster * c.maxPerCluster + i]], c.shadows, s, world, toViewer, depth);
    }
    return sum;
}
