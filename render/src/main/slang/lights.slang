// Lights and how they reach a surface: the scene's lights (engine-render's Lights), each with its shadows, and the
// light list a binning technique made for the frame (Keys.LIGHT_LIST), read through one interface whatever its
// layout. Every way of shading imports this, so lights look the same in all.
//
// A light casts shadows through consecutive entries of the shadow table, each of a kind (a shadow map with its filter,
// a screen-space mask, which ray-traced shadows write) evaluated by an IShadow: lights of one frame may each use
// another technique.
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
    public float range;            // where its light ends (smoothly); infinite: where it falls below the list's cutoff;
                                   // unused for directional lights
    public float3 color;           // linear, intensity included: irradiance at 1 unit (directional: at any distance)
    public uint type;
    public float3 direction;       // where it shines: spot and directional lights
    public float cosOuter;         // spot: no light beyond this angle's cosine
    public float cosInner;         // spot: full light within this
    public int shadowFirst;        // its first entry in the shading view's shadow table, or -1: set as the view's
    public uint shadowCount;       // light list hands it out (handOut), from that table's ranges
    public float radius;           // its size, for soft shadows and the reference: a directional light's angular
                                   // radius in radians, a point or spot light's radius (its ShadowSpec's softness)
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
    public uint kind;              // SHADOW_MAP, SHADOW_MASK
    public uint filter;            // maps: PCF, ...
    public uint map;               // maps: the depth map's sampled slot; masks: the mask's
    public uint size;              // maps: texels across
    public float bias;             // maps: in the map's depth units
    public float split;            // cascades: the view depth this cascade reaches
    public float2 params;          // maps: (normal offset, near); masks: (channel, 0)
                                   // a map's near > 0: a perspective map (reverse depth, near / view depth), its normal
                                   // offset and bias per unit of distance from the light
    public float3 position;        // the entry's world position, high part
    public uint tile;              // maps in an atlas: the tile's corner in texels, x | y << 16
    public float3 positionLow;     // and the rest
    public uint atlas;             // maps in an atlas: its texels per side (the tile's are `size`); 0: a map of its own
};

/** Shadow kinds. */
public static const uint SHADOW_MAP = 0;
public static const uint SHADOW_MASK = 1;
/** No shadow: an entry no technique of the view set reads as lit. */
public static const uint SHADOW_NONE = 3;
/**
 * Above the kind's low 8 bits: the entry tints what it lights by its place among its light's entries (debugging: a
 * directional light's cascades, shadows.cascades.debug).
 */
public static const uint SHADOW_TINTED = 0x100;

/**
 * Shadow map filters, in the low 8 bits of an entry's filter word; above them the kernel's half-width in sixteenths of
 * a texel (12 bits: PCF's, PCSS's least), then PCSS's widest (12 bits). Both weigh every texel of the kernel by a
 * tent's integral over it, so the light changes smoothly with the position and no texel's edge shows as a step.
 */
public static const uint PCF = 0;
/** Percentage-closer soft shadows: the kernel as wide as the light's penumbra there, from the blockers' depth. */
public static const uint PCSS = 1;

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
    public float3 cameraLow;       // and the rest
    public uint pad2;
    public Light* extra;           // the lights the GPU wrote this frame (particle lights), after the lights: copied
                                   // here by the binning; light lightCount + i is extra[i]
    public uint extraCount;        // how many (written by the binning)
    public float cutoff;           // the irradiance where lights without a range end (ClusteredLights.CUTOFF); 128
                                   // bytes in all

    /**
     * `light` as this list's shading sees it: its position relative to the camera, and a light without a range
     * (infinite) ending where it falls below the cutoff (lightReach).
     */
    public Light relativeTo(Light light)
    {
        Light r = light;
        r.position = lessCamera(light.position, light.positionLow, camera, cameraLow);
        r.positionLow = float3(0);
        r.range = lightReach(light, cutoff);
        return r;
    }

    /**
     * Light `index` as this list's shading sees it: relative to the camera, casting shadows through the entries this
     * view's shadow table names for it (its ranges, past the entries), whatever another view's say.
     */
    public Light handOut(uint index)
    {
        if (index >= lightCount) // a light the GPU wrote: casting no shadows
        {
            Light g = relativeTo(extra[index - lightCount]);
            g.shadowFirst = -1;
            g.shadowCount = 0;
            return g;
        }
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
    r.extra = h[0].extra;
    r.extraCount = h[0].extraCount;
    r.cutoff = h[0].cutoff;
    return r;
}

/**
 * Where light `light` ends: its range, or, for one without (infinite: glTF's KHR_lights_punctual without a range,
 * falling with the square of the distance for ever), where its brightest channel's irradiance falls to `cutoff`.
 * There the range's window (lightIrradiance) ends it smoothly: within half of it, the light is 12 % below the inverse
 * square at most, within a third 2.5 %.
 */
public float lightReach(Light light, float cutoff)
{
    return isinf(light.range) ? sqrt(max(light.color.r, max(light.color.g, light.color.b)) / cutoff) : light.range;
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
    public float lightRadius;      // the light's size (Light.radius): angular radius (directional) or radius
};

/** How a kind of shadow (or a shadow map filter) is evaluated: a new technique implements this. */
public interface IShadow
{
    /** How much of the light reaches the surface: 0 (in shadow) to 1. */
    float visibility(Shadow s, ShadowQuery q);
}

/** The integral from minus infinity to `x` of a tent of half-width `r` about 0 (and area 1). */
float tentCdf(float x, float r)
{
    float t = clamp(x / r, -1, 1);
    return t <= 0 ? 0.5 * (1 + t) * (1 + t) : 1 - 0.5 * (1 - t) * (1 - t);
}

/** A map's texels: a tile of `size` texels per side at `corner` of a depth map (or atlas) `1 / texelSize` wide. */
struct MapTexels
{
    uint map;
    int2 corner;
    int size;
    float texelSize;

    /**
     * Texels c - 1 and c each way (in the tile; kept within it, as an atlas's neighbours are other lights' maps), as
     * (x: (c.x - 1, c.y), y: (c.x, c.y), z: (c.x, c.y - 1), w: (c.x - 1, c.y - 1)): a gather at their shared corner.
     */
    float4 gather(int2 c)
    {
        int2 k = clamp(c, 1, size - 1) + corner;
        return textures[NonUniformResourceIndex(map)].GatherRed(samplers[NEAREST_CLAMP], float2(k) * texelSize);
    }
}

/**
 * The share of the light reaching `p` (texels, in the tile: texel i spans i to i + 1) through the texels within `r`
 * texels: each texel compared at the receiver's depth over it (`depth` at `p`, `slope` more per texel each way) and
 * weighted by the integral over it of a tent of half-width `r` about `p`, separable. The weights change smoothly with
 * `p` and `r`, so neither an edge's texels nor the kernel's width show as steps, as equal weights over whole texels
 * do.
 */
float tentFiltered(MapTexels m, float2 p, float r, float depth, float2 slope)
{
    int2 lo = int2(floor(p - r));
    int2 hi = int2(floor(p + r));
    float sum = 0;
    for (int y = lo.y; y <= hi.y; y += 2)
    {
        float wy0 = tentCdf(y + 1 - p.y, r) - tentCdf(y - p.y, r);
        float wy1 = tentCdf(y + 2 - p.y, r) - tentCdf(y + 1 - p.y, r);
        float dy0 = depth + slope.y * (y + 0.5 - p.y);
        float dy1 = dy0 + slope.y;
        for (int x = lo.x; x <= hi.x; x += 2)
        {
            float wx0 = tentCdf(x + 1 - p.x, r) - tentCdf(x - p.x, r);
            float wx1 = tentCdf(x + 2 - p.x, r) - tentCdf(x + 1 - p.x, r);
            float dx0 = slope.x * (x + 0.5 - p.x);
            float dx1 = dx0 + slope.x;
            float4 g = m.gather(int2(x + 1, y + 1));
            // Reverse depth: lit where the receiver is at least as near the light as the texel's caster.
            sum += wy0 * (wx0 * step(g.w, dy0 + dx0) + wx1 * step(g.z, dy0 + dx1))
                + wy1 * (wx0 * step(g.x, dy1 + dx0) + wx1 * step(g.y, dy1 + dx1));
        }
    }
    return sum;
}

/**
 * A depth map filtered by PCF or PCSS (the entry's filter word); outside the map, lit. Looked up from the surface moved
 * along its normal by the entry's normal offset (about a texel), against acne where the light grazes; a kernel's
 * texels are compared at the depth of the surface's plane over each (receiver plane depth bias, Isidoro 2006), so a
 * wide kernel does not shadow a sloped surface by itself. A query without a normal (a point in a volume: fog) takes
 * neither.
 *
 * <p>PCF: a tent of the filter word's half-width (texels). PCSS (Fernando 2005): blockers searched over the widest
 * penumbra (16 gathers, 64 texels, spread evenly); none there: lit; otherwise their mean depth (weighed by a tent over
 * the search, so it changes smoothly with the position) gives the half-width of
 * the penumbra a light of radius `lightRadius` casts there (directional: an angular radius, the penumbra growing with
 * the distance from the blockers; spot and point: a radius, seen from the blockers), the tent that wide, from the
 * filter word's least to its widest. Blockers on all 64: in the umbra, no filtering. A point in a volume (no normal)
 * takes the least kernel without a search.
 */
public struct PcfShadow : IShadow
{
    public float visibility(Shadow s, ShadowQuery q)
    {
        float3 normal = dot(q.normal, q.toLight) < 0 ? -q.normal : q.normal;
        bool surface = dot(normal, normal) > 0;
        bool perspective = s.params.y > 0;
        // A perspective map's texels grow with the distance from the light: its offset and bias with them.
        float scale = perspective ? length(q.world) : 1;
        float3 world = q.world + normal * (s.params.x * scale);
        float4 clip = mul(s.toShadow, float4(world, 1));
        float3 p = clip.xyz / clip.w;
        float2 uv = p.xy * 0.5 + 0.5;
        if (any(uv < 0) || any(uv > 1) || p.z < 0 || p.z > 1)
        {
            return 1;
        }
        // Reverse depth: a perspective map holds near / view depth, compared at the view depth less the bias.
        float depth = perspective ? s.params.y / max(s.params.y / max(p.z, 1.0e-20) - s.bias * scale, s.params.y)
            : p.z + s.bias;
        uint atlas = s.atlas != 0 ? s.atlas : s.size;
        MapTexels m = { s.map, int2(s.tile & 0xffff, s.tile >> 16), int(s.size), 1.0 / atlas };
        float size = float(s.size);
        float2 at = uv * size;
        // A texel's size in the world at the surface: an orthographic map's the same everywhere, a perspective one's
        // growing with the distance along its axis (near / depth).
        float rowScale = length(float3(s.toShadow[0][0], s.toShadow[0][1], s.toShadow[0][2]));
        float along = perspective ? s.params.y / max(p.z, 1.0e-20) : 1;
        float texel = 2 * along / max(rowScale * size, 1.0e-20);
        // The receiver's plane: its depth's change per texel each way, from two points on it a texel off.
        float2 slope = 0;
        if (surface)
        {
            float3 t1 = normalize(cross(normal, abs(normal.y) < 0.99 ? float3(0, 1, 0) : float3(1, 0, 0)));
            float3 t2 = cross(normal, t1);
            float4 c1 = mul(s.toShadow, float4(world + t1 * texel, 1));
            float4 c2 = mul(s.toShadow, float4(world + t2 * texel, 1));
            float4 c3 = mul(s.toShadow, float4(world + q.toLight * texel, 1));
            float3 d1 = c1.xyz / c1.w - p;
            float3 d2 = c2.xyz / c2.w - p;
            float2 u1 = d1.xy * 0.5 * size;
            float2 u2 = d2.xy * 0.5 * size;
            float det = u1.x * u2.y - u2.x * u1.y;
            if (abs(det) > 1.0e-6)
            {
                slope = float2(d1.z * u2.y - d2.z * u1.y, u1.x * d2.z - u2.x * d1.z) / det;
                // At most four times a texel's step towards the light in depth (a surface 76 degrees from facing
                // it): more sloped ones the normal offset keeps lit.
                float most = 4 * abs(c3.z / c3.w - p.z);
                float l = length(slope);
                slope *= l > most ? most / l : 1;
            }
        }
        uint kind = s.filter & 0xff;
        float least = max(float((s.filter >> 8) & 0xfff) / 16, 0.5);
        float r = least;
        // A point in a volume takes the narrowest kernel: what samples it (fog froxels, jittered over frames) is
        // coarser than a penumbra, and a search per froxel cost five times the froxels' light (docs/pitfalls.md).
        if (kind == PCSS && q.lightRadius > 0 && surface)
        {
            float widest = max(float(s.filter >> 20) / 16, least);
            // 4 x 4 gathers, their corners spread evenly over the widest penumbra about the surface.
            float spacing = max(2, (2 * widest - 2) / 3);
            float blockers = 0;
            float weights = 0;
            float found = 0;
            float reach = 1.5 * spacing + 1.5; // past the search's outermost texels' centres
            for (int j = 0; j < 4; j++)
            {
                for (int i = 0; i < 4; i++)
                {
                    int2 c = int2(round(at + (float2(i, j) - 1.5) * spacing));
                    float4 g = m.gather(c);
                    float2 o = float2(c) - at; // the texels' centres: o -+ 0.5 each way
                    float4 receiver = depth + float4(dot(slope, o + float2(-0.5, 0.5)),
                        dot(slope, o + float2(0.5, 0.5)), dot(slope, o + float2(0.5, -0.5)),
                        dot(slope, o + float2(-0.5, -0.5)));
                    float4 blocks = float4(g > receiver);
                    // Weighed by a tent over the search (the texels' centres from the surface): the mean changes
                    // smoothly as texels come into reach, not a step a texel (stripes along penumbrae otherwise)
                    float4 ox = o.x + float4(-0.5, 0.5, 0.5, -0.5);
                    float4 oy = o.y + float4(0.5, 0.5, -0.5, -0.5);
                    float4 w = saturate(1 - abs(ox) / reach) * saturate(1 - abs(oy) / reach) * blocks;
                    blockers += dot(w, g);
                    weights += dot(w, 1);
                    found += dot(blocks, 1);
                }
            }
            if (found == 0)
            {
                return 1; // nothing between it and the light within the widest penumbra
            }
            if (found == 64)
            {
                return 0; // blockers all over the widest penumbra: in the umbra
            }
            float blocker = blockers / max(weights, 1.0e-6);
            float spread;
            if (perspective)
            {
                // Distances along the map's axis (near / depth): seen from the blockers, the light's radius spreads
                // over the gap to the surface by their ratio.
                float toReceiver = s.params.y / max(p.z, 1.0e-20);
                float toBlocker = s.params.y / max(blocker, 1.0e-20);
                spread = q.lightRadius * max(toReceiver - toBlocker, 0) / max(toBlocker, 1.0e-6);
            }
            else
            {
                // Depth to world: the map's depth row takes a world unit to its length in depth.
                float depthScale = length(float3(s.toShadow[2][0], s.toShadow[2][1], s.toShadow[2][2]));
                spread = max(blocker - p.z, 0) / max(depthScale, 1.0e-20) * tan(q.lightRadius);
            }
            // A tent as spread as the light's disc seen edge-on (its variance: R^2 / 6 against r^2 / 4).
            r = clamp(1.2247449 * spread / texel, least, widest);
        }
        return tentFiltered(m, at, r, depth, slope);
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

/** Shadow entry `s` evaluated by its kind's IShadow. */
public float shadowVisibility(Shadow s, ShadowQuery q)
{
    switch (s.kind & 0xff)
    {
    case SHADOW_MAP:
        return (s.filter & 0xff) <= PCSS ? PcfShadow().visibility(s, q) : 1;
    case SHADOW_MASK:
        return MaskShadow().visibility(s, q);
    default:
        return 1;
    }
}

/** Interleaved gradient noise (Jimenez 2014): a value in [0, 1) per pixel that neighbours spread evenly. */
float dither(uint2 pixel)
{
    return frac(52.9829189 * frac(0.06711056 * float(pixel.x) + 0.00583715 * float(pixel.y)));
}

/** A tinted entry's colour by its place among its light's: red, green, blue, yellow, cyan, magenta, white, gray. */
float3 entryTint(uint i)
{
    static const float3 tints[8] = { float3(1, 0.2, 0.2), float3(0.2, 1, 0.2), float3(0.25, 0.4, 1), float3(1, 1, 0.2),
        float3(0.2, 1, 1), float3(1, 0.2, 1), float3(1, 1, 1), float3(0.5, 0.5, 0.5) };
    return tints[min(i, 7u)];
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
 * What light `light` adds to surface `s` at `world`, seen from `toViewer`, at `pixel`, `depth` from the viewer.
 */
public float3 lightSurface(LightListHeader h, Light light, Surface s, float3 world,
    float3 toViewer, uint2 pixel, float depth)
{
    float3 toLight;
    float3 irradiance = lightIrradiance(h, light, world, s.normal, pixel, depth, toLight);
    return all(irradiance <= 0) ? 0 : reflected(s, toViewer, toLight, irradiance);
}

/**
 * The irradiance light `light` brings to a point at `world` (`normal`: its surface's, for shadow offsets), at
 * `pixel`, `depth` from the viewer, after its shadow; the direction to it into `toLight`. What every way of lighting a
 * point uses, surfaces and particles alike.
 */
public float3 lightIrradiance(LightListHeader h, Light light, float3 world, float3 normal,
    uint2 pixel, float depth, out float3 toLight)
{
    Shadow* shadows = h.shadows;
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
        ShadowQuery q = { world + h.toEntry(entry), normal, toLight, distance, pixel, depth, length(world),
            light.radius };
        float visibility = shadowVisibility(entry, q);
        // Tinted (debugging): a fifth of the light through the shadow too, so the entry shows where it shadows
        irradiance *= (entry.kind & SHADOW_TINTED) != 0
            ? entryTint(uint(shadow - light.shadowFirst)) * lerp(0.2, 1.0, visibility) : visibility;
    }
    return irradiance;
}

/** What every light of `list` adds to surface `s` at `world`, seen from `toViewer`, `depth` from the viewer. */
public float3 lightAll<L : ILightList>(L list, LightListHeader h, Surface s, float3 world,
    float3 toViewer, uint2 pixel, float depth)
{
    float3 sum = 0;
    for (uint i = 0; i < list.count(); i++)
    {
        sum += lightSurface(h, list.light(i), s, world, toViewer, pixel, depth);
    }
    return sum;
}

/**
 * What the lights of light list `h` add to the surface at `pixel` and `depth` (from the viewer, along the view
 * direction): the layout's ILightList opened there. Without a light list (absent, `given` false), nothing.
 */
public float3 lightList(LightListHeader* h, Surface s, float3 world, float3 toViewer,
    uint2 pixel, float depth)
{
    if (!given(h))
    {
        return 0;
    }
    LightListHeader header = loadHeader(h);
    switch (header.layout)
    {
    case CLUSTERS:
        return lightAll(ClusterLightList(header, pixel, depth), header, s, world, toViewer, pixel, depth);
    default:
        return 0;
    }
}
