// Lights for points off the screen (engine-render's WorldLights): a grid of cells in world space around an anchor
// camera, built once a frame from the scene's lights and those the GPU wrote (particle lights), which ray hits read
// wherever they are, as a view's light clusters cannot serve them. Two layouts, each a correct estimator of the light
// at a point:
// - LISTS: each cell lists the lights whose reach meets its box (fixed slots, overflow reported); a point sums every
//   light of its cell, each with a shadow ray: exact, its cost growing with the lights a cell holds.
// - RESERVOIRS (ReGIR, Boksansky, Jukarainen and Wyman 2021): each cell holds reservoirs, each one light chosen by
//   resampled importance sampling from candidates drawn uniformly among all lights, by what the light could give the
//   cell (its brightness over the nearest distance from the cell's box: never 0 where it reaches); a point resamples
//   its cell's reservoirs by what each gives it and traces one shadow ray: unbiased, constant cost, noisy. Only the
//   cells hits used the frame before are built; a hit in another (a camera moved there) sums every light, exactly,
//   and marks its cell for the next frame.
// Directional lights reach every point: both layouts leave them to the point, which sums them directly.
module world;

__exported import lights;
import bindless;

public static const uint WORLD_LISTS = 1;
public static const uint WORLD_RESERVOIRS = 2;
public static const uint WORLD_DIRECTIONAL = 4; // directional lights the header names

/** The grid: 112 bytes (128 kept), then the cells' data, then the lights the GPU wrote. Read a field at a time. */
[HostLayout]
public struct WorldLightGrid
{
    public uint layout;            // WORLD_LISTS, WORLD_RESERVOIRS
    public uint cells;             // per axis
    public uint slots;             // per cell: light slots (lists) or reservoirs
    public uint candidates;        // reservoirs: lights drawn for each
    public float3 origin;          // the grid's lowest corner, world position, high part
    public float cellSize;
    public float3 originLow;       // and the rest
    public uint lightCount;        // the scene's lights
    public Light* lights;
    public Light* extra;           // the lights the GPU wrote, copied here
    public uint* data;             // lists: per cell a count, then its slots; reservoirs: per slot a light, a weight
    public uint extraCount;        // written by the build
    public uint directionalCount;
    public uint4 directional;      // the scene's directional lights (up to WORLD_DIRECTIONAL)
    public float cutoff;           // where lights without a range end
    public uint overflow;          // lists: the most lights a cell wanted
    public uint zero;              // 0: what the build reads as the GPU's light count without such lights
    public uint frame;             // reservoirs: this frame's number, from 1
    public uint* cellState;        // reservoirs: per cell the frame a hit last used it, the frame it was built (kept
                                   // from frame to frame): cells are built only where hits used them the frame before
    public uint* built;            // reservoirs: the cells built this frame (a count, then the cells)
};

/** Light `i` of the grid's lights (the scene's, then the GPU's), at world positions. */
public Light gridLight(WorldLightGrid* g, uint i)
{
    uint count = g[0].lightCount;
    if (i < count)
    {
        return g[0].lights[i];
    }
    return g[0].extra[i - count];
}

/** The cell of a point at `p` relative to the grid's origin, or -1 outside it. */
public int gridCell(WorldLightGrid* g, float3 p)
{
    uint n = g[0].cells;
    int3 c = int3(floor(p / g[0].cellSize));
    if (any(c < 0) || any(c >= int(n)))
    {
        return -1;
    }
    return int((uint(c.z) * n + uint(c.y)) * n + uint(c.x));
}

/**
 * What `light` (relative to the same point as `boxMin`) could give a point of the box from `boxMin` to `boxMax`: its
 * brightest channel's irradiance at the box's nearest point, its range's window there; 0 where it cannot reach the box.
 */
public float boxTarget(Light light, float3 boxMin, float3 boxMax, float cutoff)
{
    float3 d = max(max(boxMin - light.position, light.position - boxMax), 0);
    float distance2 = dot(d, d);
    float range = lightReach(light, cutoff);
    if (distance2 >= range * range)
    {
        return 0;
    }
    float3 extent = boxMax - boxMin;
    float ratio = distance2 / (range * range);
    float window = saturate(1 - ratio * ratio);
    // Inside or next to the box: as bright as a quarter of a cell away, finite and still positive
    float near = max(distance2, 0.0625 * dot(extent, extent) / 3);
    return max(light.color.r, max(light.color.g, light.color.b)) * window * window / near;
}

/** Light `i` (of the scene's then the GPU's) relative to world position (`high`, `low`). */
public Light gridLightRelative(WorldLightGrid* g, uint i, float3 high, float3 low)
{
    Light l = gridLight(g, i);
    l.position = lessCamera(l.position, l.positionLow, high, low);
    l.positionLow = 0;
    l.range = lightReach(l, g[0].cutoff);
    return l;
}

/** One reservoir of RESERVOIRS: the light chosen (~0: none) and its weight W. */
public struct WorldReservoir
{
    public uint light;
    public float weight;
};
