// The reference path tracer (engine-render's ReferencePathTracer): paths of light traced through the frame's top level
// by ray queries in compute, a wavefront of them, a pass per step, and shaded at every surface they meet by the
// surface's own material type: hits are sorted by shading bin (a type and features, as the visibility buffer and
// forward shading number them) and each bin is shaded by its type's generated entry point (material-types/
// entryHit.slang.template), so no shader branches on the type and every type shades as itself, at ray hits too.
//
// One sample per pixel per frame, added into an accumulation the resolve divides by the samples taken. Per bounce:
// trace (the nearest hit, or the environment for a miss), bins (count, lay out, scatter, as split.slang), then a
// dispatch per shading bin, which lights the hit (the type's surface, the engine's features, the same BRDF as
// shading.slang: reflected()), adds its emission and every light of the light list with a shadow ray each (or the sun
// without a list), and samples the next direction: the BRDF's lobes mixed by their weights (Lambert by the cosine, the
// GGX lobes by their visible normals, Heitz 2018), transmission through thin walls and into volumes (refracted,
// attenuated by Beer's law over the distance travelled inside). Russian roulette from a bounce on; paths end at the
// bounce limit. The environment is found by the paths that leave the scene (no light sampling of it), as are emissive
// surfaces: unbiased, slower to converge for small bright emitters.
module paths;

__exported import meshlets.common;
__exported import raytracing;
__exported import lights.world;
import shadows.rays;

/** One path between bounces: 80 bytes. Positions relative to the view's camera. */
public struct PathState
{
    public float3 origin;      // where the next ray starts
    public uint pixel;         // x | y << 16
    public float3 direction;   // unit
    public uint flags;         // bounces taken (bits 0-7), PATH_INSIDE
    public float3 throughput;  // what light found along the path is multiplied by
    public uint rng;           // the random state
    public uint instance;      // after tracing: the hit's instance
    public uint primitive;     // and triangle
    public float2 bary;        // and barycentrics
    public float t;            // and distance
    public uint bin;           // its shading bin; NO_BIN: the path ended (a miss)
    public float cone;         // the ray cone's width at the origin (texture filtering, Akenine-Moller et al. 2019)
    public uint pad;
};

/** Inside a volume (FEATURE_VOLUME's thickness): the next hit leaves it. */
public static const uint PATH_INSIDE = 1u << 8;
public static const uint NO_BIN = 0xffffffffu;
static const float PI = 3.14159265;

/**
 * In the limits: the paths are a probe volume's rays (engine-render's ProbeVolume, probes/update.slang), not a
 * camera's: a ray's light is written to its texel of the ray image (the accumulation's slot) with its distance, a
 * hit is shaded once (emission, its lights, and the volume's own indirect light of the frame before for the further
 * bounces, as the push's indirect-light table holds it) and no path continues but through surfaces that let it pass.
 */
public static const uint PATH_PROBES = 1u << 16;
/** A probe ray's distance where it met nothing. */
public static const float PROBE_MISSED = 10000;
/**
 * In the limits, with PATH_PROBES: the probe rays are radiance cascades' intervals (engine-render's RadianceCascades,
 * probes/cascades.slang): a ray's cascade is in bits 16-23 of its flags, and one marked PATH_BOUNDED ends where its
 * cascade's interval does (the first cascade's interval long, push.size as a float, four times longer each next);
 * meeting nothing within it, it records no light and PROBE_MISSED: what lies beyond is the next cascade's to find.
 */
public static const uint PATH_INTERVALS = 1u << 17;
/** A path's cascade: its flags' bits from here (PATH_INTERVALS). */
public static const uint PATH_CASCADE_SHIFT = 16;
/** A probe ray ending at its cascade's interval (PATH_INTERVALS); the last cascade's go on to the environment. */
public static const uint PATH_BOUNDED = 1u << 24;

/** Groups per row of the 2D dispatches over paths: (min(groups, this), rows). */
public static const uint PATH_ROW = 1024;

public struct PathPush
{
    public PathState* paths;           // this bounce's
    public PathState* next;            // the next bounce's, appended to
    public uint* bins;                 // this bounce's bin table (bin words), then the bins' slot list (list)
    public uint* counts;               // paths per bounce: counts[bounce] this one, counts[bounce + 1] the next
    public View* view;
    public SceneTable* scene;
    public uint64_t rays;              // RayScene*: the top level and its triangles
    public LightListHeader* lights;    // the view's light list; null: the sun
    public IndirectLight* indirect;    // the view's indirect-light table (its environment); null: the constant sky
    public WorldLightGrid* world;      // the lights by world grid (lights/world.slang); null: every light of the list
    public float3 offset;              // the camera's position less the top level's origin
    public uint accumulation;          // storage slot: the radiance added up (rgb)
    public uint size;                  // the region: width, height in the high 16 bits (PATH_INTERVALS: see there)
    public uint bounce;
    public uint binCount;              // shading bins: hits of a later one shade as bin 0
    public uint bin;                   // shading: the bin shaded; resolve: the output's storage slot
    public uint sample;                // samples taken before this frame's (0: the accumulation starts anew)
    public uint limits;                // the bounce limit (bits 0-7), Russian roulette's first bounce (bits 8-15)
    public uint list;                  // words from the bin table to the slot list
    public uint lightRays;             // words from counts to the light rays' trace record (shadows/rays.slang's
                                       // ShadowTrace, LightRays): shadow rays meeting a type with a geometry hook wait
                                       // there for its keep(); 0: none (no such type: they never do)
};

[[vk::push_constant]]
public ConstantBuffer<PathPush> push;

/** The features a variant of a shading pass shows (FEATURE_*): set when its pipeline is made. */
[vk::constant_id(0)]
public const uint FEATURES = 0;

// ---------------------------------------------------------------- the bin table, per bounce
//
// bins[4 * b]: shading bin b's dispatch (x, y, z) and its count; then from 4 * binCount, each bin's first slot in the
// list, and from 5 * binCount its cursor.

public uint binCount(uint bin)
{
    return push.bins[4 * bin + 3];
}

public uint binOffset(uint bin)
{
    return push.bins[4 * push.binCount + bin];
}

/** The thread's path index in a 2D dispatch over paths. */
public uint pathIndex(uint3 group, uint3 thread)
{
    return (group.y * PATH_ROW + group.x) * 64 + thread.x;
}

// ---------------------------------------------------------------- random numbers and sampling

/** PCG's output function over a state stepped linearly (O'Neill 2014): a 32-bit random value. */
public uint random(inout uint state)
{
    state = state * 747796405u + 2891336453u;
    uint word = ((state >> ((state >> 28u) + 4u)) ^ state) * 277803737u;
    return (word >> 22u) ^ word;
}

/** A random value in [0, 1). */
public float random01(inout uint state)
{
    return float(random(state) >> 8) * (1.0 / 16777216.0);
}

/** A seed for pixel `pixel` and sample `sample`. */
public uint seed(uint2 pixel, uint sample)
{
    uint s = (pixel.x * 73856093u) ^ (pixel.y * 19349663u) ^ (sample * 83492791u);
    random(s);
    return s;
}

/** A unit tangent and bitangent around unit `n` (Duff et al. 2017). */
public void basis(float3 n, out float3 t, out float3 b)
{
    float sign = n.z >= 0 ? 1.0 : -1.0;
    float a = -1.0 / (sign + n.z);
    float c = n.x * n.y * a;
    t = float3(1 + sign * n.x * n.x * a, sign * c, -sign * n.x);
    b = float3(c, sign + n.y * n.y * a, -n.y);
}

public float3 toWorldBasis(float3 local, float3 n)
{
    float3 t;
    float3 b;
    basis(n, t, b);
    return local.x * t + local.y * b + local.z * n;
}

/** A direction around `n` with density cos / pi. */
public float3 cosineSample(float2 u, float3 n)
{
    float r = sqrt(u.x);
    float phi = 2 * PI * u.y;
    return toWorldBasis(float3(r * cos(phi), r * sin(phi), sqrt(max(0.0, 1 - u.x))), n);
}

/** GGX's distribution of alpha squared `a2` at the cosine `nh`. */
public float ggx(float nh, float a2)
{
    float d = nh * nh * (a2 - 1) + 1;
    return a2 / (PI * d * d);
}

/** Smith's masking of GGX at the cosine `c`, alpha squared `a2`. */
public float smithG1(float c, float a2)
{
    return 2 * c / (c + sqrt(a2 + (1 - a2) * c * c));
}

/**
 * A normal of the GGX distribution of `alpha` around `n`, as seen from `v` (its visible normals, Heitz 2018): the
 * reflection of v about it is distributed as the lobe, less what Smith's masking takes.
 */
public float3 sampleVisibleNormal(float3 v, float3 n, float alpha, float2 u)
{
    float3 t;
    float3 b;
    basis(n, t, b);
    float3 local = float3(dot(v, t), dot(v, b), dot(v, n));
    float3 vh = normalize(float3(alpha * local.x, alpha * local.y, max(local.z, 1.0e-4)));
    float lensq = vh.x * vh.x + vh.y * vh.y;
    float3 t1 = lensq > 0 ? float3(-vh.y, vh.x, 0) * rsqrt(lensq) : float3(1, 0, 0);
    float3 t2 = cross(vh, t1);
    float r = sqrt(u.x);
    float phi = 2 * PI * u.y;
    float p1 = r * cos(phi);
    float p2 = r * sin(phi);
    float s = 0.5 * (1 + vh.z);
    p2 = (1 - s) * sqrt(max(0.0, 1 - p1 * p1)) + s * p2;
    float3 nh = p1 * t1 + p2 * t2 + sqrt(max(0.0, 1 - p1 * p1 - p2 * p2)) * vh;
    float3 m = normalize(float3(alpha * nh.x, alpha * nh.y, max(nh.z, 0.0)));
    return m.x * t + m.y * b + m.z * n;
}

/** The density of a direction `l` reflected about the visible normals of GGX `alpha` around `n`, seen from `v`. */
public float visibleNormalPdf(float3 v, float3 l, float3 n, float alpha)
{
    float nv = dot(n, v);
    float nl = dot(n, l);
    if (nv <= 0 || nl <= 0)
    {
        return 0;
    }
    float3 h = normalize(v + l);
    float a2 = max(alpha * alpha, 1.0e-12);
    return ggx(saturate(dot(n, h)), a2) * smithG1(nv, a2) / (4 * nv);
}

public float luminance(float3 c)
{
    return dot(c, float3(0.2126, 0.7152, 0.0722));
}

/**
 * `p` moved off a surface of geometric normal `n` (unit, the side to leave on), past float rounding (as Wachter and
 * Binder 2019 advise, by a part of the position's magnitude).
 */
public float3 offsetRay(float3 p, float3 n)
{
    float3 scale = max(abs(p), 1.0);
    return p + n * (1.0e-4 * max(scale.x, max(scale.y, scale.z)));
}

// ---------------------------------------------------------------- the environment and the lights

float3 rotateByQuaternion(float4 q, float3 v)
{
    float3 t = 2.0 * cross(q.xyz, v);
    return v + q.w * t + cross(q.xyz, t);
}

/**
 * The light arriving along `-direction` from far away: the view's environment (its cube at roughness 0, which holds
 * it unfiltered), or without one the constant sky, the radiance whose irradiance is SKY_IRRADIANCE from every side.
 */
public float3 environment(float3 direction)
{
    IndirectLight* t = push.indirect;
    if (t == nullptr)
    {
        return SKY_IRRADIANCE / PI;
    }
    if ((t[0].flags & INDIRECT_ENVIRONMENT) == 0)
    {
        return SKY_IRRADIANCE / PI;
    }
    uint cube = t[0].specular;
    float intensity = t[0].intensity;
    float4 rotation = t[0].rotation;
    return cubes[cube].SampleLevel(samplers[LINEAR_CLAMP], rotateByQuaternion(rotation, direction), 0).rgb
        * intensity;
}

/**
 * What the light a shadow ray of next-event estimation tests is weighed by (the path's throughput), and the pixel it
 * goes to: set by the hit's shading before it lights it, so a shadow ray waiting for a keep() test brings its light
 * there later.
 */
static float3 lightWeight;
static uint lightPixel;

/** Whether nothing lies between camera-relative `p` and `p + direction * distance`. */
public bool visible(float3 p, float3 direction, float distance)
{
    return !occluded((RayScene*)push.rays, p + push.offset, direction, 0, distance);
}

/** Light `light` (relative to the camera) at `p`: its irradiance on a surface facing it, the direction to it. */
float3 lightAt(Light light, float3 p, out float3 toLight, out float distance)
{
    float3 irradiance = light.color;
    distance = 1.0e30;
    if (light.type == DIRECTIONAL)
    {
        toLight = -light.direction;
        return irradiance;
    }
    float3 d = light.position - p;
    float distance2 = max(dot(d, d), 1.0e-8);
    toLight = d * rsqrt(distance2);
    distance = sqrt(distance2);
    float ratio = distance2 / (light.range * light.range);
    float window = saturate(1 - ratio * ratio);
    irradiance *= window * window / distance2;
    if (light.type == SPOT)
    {
        irradiance *= smoothstep(light.cosOuter, light.cosInner, dot(-toLight, light.direction));
    }
    return irradiance;
}

/** What one light would add at `p` on surface `s` seen from `v`, unshadowed; the way to it into `toLight`. */
float3 lightUnshadowed(Light light, Surface s, float3 p, float3 v, out float3 toLight, out float distance)
{
    float3 irradiance = lightAt(light, p, toLight, distance);
    if (all(irradiance <= 0))
    {
        return 0;
    }
    return reflected(s, v, toLight, irradiance);
}

/**
 * Whether nothing lies between `p` (geometric normal `ng`) and a light `distance` away along `toLight`. A ray meeting
 * a triangle of a type with a geometry hook (keep()) first waits for its type's test: false now, and `light` (what
 * the light adds) brought to the pixel later if it goes through (LightRays).
 */
bool lightVisible(float3 p, float3 ng, float3 toLight, float distance, bool directional, float3 light)
{
    float3 from = offsetRay(p, dot(ng, toLight) >= 0 ? ng : -ng);
    float tMax = directional ? 1.0e30 : distance * (1 - 1.0e-4);
    if (push.lightRays == 0)
    {
        return visible(from, toLight, tMax);
    }
    ShadowRay hit;
    uint way = traceShadow((RayScene*)push.rays, from + push.offset, toLight, 0, tMax, hit);
    if (way == PENDING)
    {
        ShadowTrace* trace = (ShadowTrace*)(push.counts + push.lightRays);
        LightRay waiting;
        waiting.hit = hit;
        waiting.origin = from + push.offset;
        waiting.tMax = tMax;
        waiting.direction = toLight;
        waiting.pixel = lightPixel;
        waiting.contribution = light * lightWeight;
        waiting.pad = 0;
        enqueueLight((uint*)(trace + 1), trace.capacity, waiting); // queue A: right after the record
        return false;
    }
    return way == CLEAR;
}

/** What one light adds at `p` (geometric normal `ng`) on surface `s` seen from `v`, its shadow traced. */
float3 lightOne(Light light, Surface s, float3 p, float3 ng, float3 v, inout uint rng)
{
    float3 toLight;
    float distance;
    float3 c = lightUnshadowed(light, s, p, v, toLight, distance);
    if (all(c <= 0))
    {
        return 0;
    }
    if (light.radius > 0)
    {
        // A light of a size (its ShadowSpec's softness): the shadow ray towards a point of it, uniform over the sun's
        // disc (a cone of its angular radius) or a sphere's disc facing the point, so its shadows have penumbras.
        float u = random01(rng);
        float w = random01(rng);
        float phi = 6.2831853 * w;
        float3 a = normalize(abs(toLight.y) < 0.99 ? cross(toLight, float3(0, 1, 0))
            : cross(toLight, float3(1, 0, 0)));
        float3 b = cross(toLight, a);
        if (light.type == DIRECTIONAL)
        {
            float cosTheta = 1 - u * (1 - cos(light.radius));
            float sinTheta = sqrt(max(0, 1 - cosTheta * cosTheta));
            toLight = normalize(toLight * cosTheta + (a * cos(phi) + b * sin(phi)) * sinTheta);
        }
        else
        {
            float3 d = light.position + (a * cos(phi) + b * sin(phi)) * (light.radius * sqrt(u)) - p;
            distance = length(d);
            toLight = d / max(distance, 1.0e-6);
        }
    }
    return lightVisible(p, ng, toLight, distance, light.type == DIRECTIONAL, c) ? c : float3(0);
}

/**
 * The light of the world grid's lights at `p` (relative to `view`'s camera): the directional ones summed, then the
 * cell's: LISTS every light it lists, RESERVOIRS one of its reservoirs resampled by what each light gives `p`
 * (unshadowed), its shadow traced, weighed so the estimate is unbiased. Outside the grid, every light.
 */
float3 worldLight(Surface s, float3 p, float3 ng, float3 v, View view, inout uint rng)
{
    WorldLightGrid* g = push.world;
    float3 sum = 0;
    uint directionalCount = g[0].directionalCount;
    uint4 directional = g[0].directional;
    for (uint i = 0; i < directionalCount; i++)
    {
        sum += lightOne(gridLightRelative(g, directional[i], view.position, view.positionLow), s, p, ng, v, rng);
    }
    float3 toGrid = lessCamera(view.position, view.positionLow, g[0].origin, g[0].originLow);
    int cell = gridCell(g, p + toGrid);
    uint total = g[0].lightCount + g[0].extraCount;
    if (cell < 0)
    {
        for (uint i = 0; i < total; i++)
        {
            Light l = gridLightRelative(g, i, view.position, view.positionLow);
            if (l.type != DIRECTIONAL)
            {
                sum += lightOne(l, s, p, ng, v, rng);
            }
        }
        return sum;
    }
    uint slots = g[0].slots;
    if (g[0].layout == WORLD_LISTS)
    {
        uint* list = g[0].data + uint(cell) * (1 + slots);
        uint listed = min(list[0], slots); // past the slots: overflowed (noted in the header)
        for (uint k = 0; k < listed; k++)
        {
            sum += lightOne(gridLightRelative(g, list[1 + k], view.position, view.positionLow), s, p, ng, v, rng);
        }
        return sum;
    }
    // Marked used for the next frame's build; built this frame, or every light
    uint frame = g[0].frame;
    g[0].cellState[2 * uint(cell)] = frame;
    if (g[0].cellState[2 * uint(cell) + 1] != frame)
    {
        for (uint i = 0; i < total; i++)
        {
            Light l = gridLightRelative(g, i, view.position, view.positionLow);
            if (l.type != DIRECTIONAL)
            {
                sum += lightOne(l, s, p, ng, v, rng);
            }
        }
        return sum;
    }
    WorldReservoir* reservoirs = (WorldReservoir*) g[0].data + uint(cell) * slots;
    float weights = 0;
    float chosenTarget = 0;
    float3 chosen = 0;
    float3 chosenWay = 0;
    float chosenDistance = 0;
    for (uint k = 0; k < slots; k++)
    {
        WorldReservoir r = reservoirs[k];
        if (r.light == 0xffffffffu)
        {
            continue;
        }
        float3 toLight;
        float distance;
        float3 c = lightUnshadowed(gridLightRelative(g, r.light, view.position, view.positionLow), s, p, v, toLight,
            distance);
        float target = luminance(max(c, 0.0));
        float w = target * r.weight;
        weights += w;
        if (w > 0 && random01(rng) * weights < w)
        {
            chosenTarget = target;
            chosen = c;
            chosenWay = toLight;
            chosenDistance = distance;
        }
    }
    if (chosenTarget > 0)
    {
        float3 brought = chosen * (weights / (float(slots) * chosenTarget));
        if (lightVisible(p, ng, chosenWay, chosenDistance, false, brought))
        {
            sum += brought;
        }
    }
    return sum;
}

/**
 * The light every light of the view's list (the sun without one) reflects at `p` towards `v`, each with a shadow ray:
 * next-event estimation, exact for these point and directional lights.
 */
public float3 directLight(Surface s, float3 p, float3 ng, float3 v, View view, inout uint rng)
{
    if (push.world != nullptr)
    {
        return worldLight(s, p, ng, v, view, rng);
    }
    LightListHeader* list = push.lights;
    if (list == nullptr)
    {
        Light sun;
        sun.type = DIRECTIONAL;
        sun.direction = -SUN;
        sun.color = SUN_IRRADIANCE;
        sun.position = 0;
        sun.positionLow = 0;
        sun.range = 0;
        sun.cosOuter = 0;
        sun.cosInner = 0;
        sun.shadowFirst = -1;
        sun.shadowCount = 0;
        sun.radius = 0;
        sun.pad1 = 0;
        return lightOne(sun, s, p, ng, v, rng);
    }
    LightListHeader h = loadHeader(list);
    float3 sum = 0;
    for (uint i = 0; i < h.lightCount; i++)
    {
        sum += lightOne(h.relativeTo(h.lights[i]), s, p, ng, v, rng);
    }
    for (uint i = 0; i < h.extraCount; i++)
    {
        sum += lightOne(h.relativeTo(h.extra[i]), s, p, ng, v, rng);
    }
    return sum;
}

/** Adds `radiance` to pixel `pixel`'s accumulation. */
public void accumulate(uint pixel, float3 radiance)
{
    if (any(radiance != 0))
    {
        uint2 p = uint2(pixel & 0xffff, pixel >> 16);
        float4 sum = images[push.accumulation][p];
        images[push.accumulation][p] = float4(sum.rgb + radiance, 1);
    }
}

// ---------------------------------------------------------------- shading a hit by its type

/** The next path from `p` (camera-relative) along `direction`, or none, from shading path `path`. */
void continuePath(PathState path, float3 origin, float3 direction, float3 throughput, uint flags)
{
    uint slot;
    InterlockedAdd(push.counts[push.bounce + 1], 1, slot);
    PathState next = path;
    next.origin = origin;
    next.direction = direction;
    next.throughput = throughput;
    next.flags = flags;
    next.bin = NO_BIN;
    push.next[slot] = next;
}

/** The width of path `path`'s ray cone at its hit: grown by a pixel's angle per unit travelled. */
public float coneWidth(PathState path, View view)
{
    return path.cone + path.t / max(view.projectionScale, 1.0e-6);
}

/**
 * The reflectance of a smooth dielectric boundary for light arriving at the cosine `cosine` from the side of index
 * ratio `eta` (its side's index over the other's): Fresnel's equations, unpolarized; 1 past the critical angle.
 */
public float dielectricFresnel(float cosine, float eta)
{
    float sin2 = eta * eta * (1 - cosine * cosine);
    if (sin2 >= 1)
    {
        return 1;
    }
    float cosT = sqrt(1 - sin2);
    float rs = (eta * cosine - cosT) / (eta * cosine + cosT);
    float rp = (cosine - eta * cosT) / (cosine + eta * cosT);
    return 0.5 * (rs * rs + rp * rp);
}

/** The hit of path `path`: its triangle rebuilt as drawing does, interpolated at its barycentrics. */
public struct HitSurface
{
    public VertexOut vertex;   // interpolated, camera-relative
    public float3 geometric;   // the triangle's unit normal, on its front: counter-clockwise, as drawing culls
    public float4 dx;          // texture coordinate gradients (both sets) for the ray cone's footprint
    public float4 dy;
};

public HitSurface hitSurface(PathState path, View view)
{
    RayScene* rays = (RayScene*)push.rays;
    TriangleVertices tv = triangleVertices(rays, path.instance, path.primitive);
    Instance instance = relative(push.scene[0].instances[path.instance], view);
    MeshEntry mesh = push.scene[0].meshes[instance.mesh];
    uint meshlet = tv.meshletWord / 8;
    VertexOut v[3];
    for (uint k = 0; k < 3; k++)
    {
        v[k] = transformVertex(view, instance, mesh, tv.vertices[k], push.scene[0].positions, push.scene[0].attributes,
            meshlet, tv.submesh);
    }
    float3 w = float3(1 - path.bary.x - path.bary.y, path.bary.x, path.bary.y);
    HitSurface h;
    VertexOut s = v[0];
    s.world = w.x * v[0].world + w.y * v[1].world + w.z * v[2].world;
    s.normal = w.x * v[0].normal + w.y * v[1].normal + w.z * v[2].normal;
    s.tangent = float4(w.x * v[0].tangent.xyz + w.y * v[1].tangent.xyz + w.z * v[2].tangent.xyz, v[0].tangent.w);
    s.uv = w.x * v[0].uv + w.y * v[1].uv + w.z * v[2].uv;
    s.uv1 = w.x * v[0].uv1 + w.y * v[1].uv1 + w.z * v[2].uv1;
    s.color = w.x * v[0].color + w.y * v[1].color + w.z * v[2].color;
    h.vertex = s;
    float3 e1 = v[1].world - v[0].world;
    float3 e2 = v[2].world - v[0].world;
    float3 n = cross(e1, e2);
    float area = length(n);
    float3 ng = area > 0 ? n / area : normalize(s.normal);
    if (mirrored(instance))
    {
        ng = -ng; // drawn with two corners swapped: its faces face as the mesh means them to
    }
    h.geometric = ng;
    // The ray cone's footprint at the hit, over the texture coordinates' density on the triangle (isotropic).
    float width = coneWidth(path, view);
    float cosine = max(abs(dot(ng, path.direction)), 1.0e-3);
    float2 a0 = v[1].uv - v[0].uv;
    float2 b0 = v[2].uv - v[0].uv;
    float2 a1 = v[1].uv1 - v[0].uv1;
    float2 b1 = v[2].uv1 - v[0].uv1;
    float uvArea = abs(a0.x * b0.y - a0.y * b0.x);
    float uv1Area = abs(a1.x * b1.y - a1.y * b1.x);
    float footprint = width / cosine;
    float g0 = area > 0 ? footprint * sqrt(uvArea / area) : 0;
    float g1 = area > 0 ? footprint * sqrt(uv1Area / area) : 0;
    h.dx = float4(g0, 0, g1, 0);
    h.dy = float4(0, g0, 0, g1);
    return h;
}

/**
 * Shades path `index` (whose hit is of material type M, features `features`: FEATURES where a pass is a variant):
 * emission, the lights by next-event estimation, then the next direction sampled from the surface's lobes, or the
 * path ends.
 */
public void shadeHit<M : IMaterial>(uint index, uint features)
{
    PathState path = push.paths[index];
    View view = push.view[0];
    HitSurface hit = hitSurface(path, view);
    VertexOut in = hit.vertex;
    float3 ng = hit.geometric;
    float3 v = -path.direction;
    uint flags = path.flags;
    uint bounce = flags & 0xff;
    bool inside = (flags & PATH_INSIDE) != 0;
    uint maxBounces = push.limits & 0xff;
    uint roulette = (push.limits >> 8) & 0xff;
    uint rng = path.rng;
    float3 throughput = path.throughput;
    float3 p = in.world;
    MaterialRef ref = { push.scene[0].materials, in.material };
    Material m = ref.slot();
    bool front = dot(ng, v) > 0;
    float3 radiance = 0;
    // A one-sided material's back face is not drawn: the path goes on through it (unless leaving a volume).
    path.cone = coneWidth(path, view);
    if (!front && !materialDoubleSided(m) && !inside)
    {
        if (bounce + 1 < maxBounces)
        {
            path.rng = rng;
            continuePath(path, offsetRay(p, -ng), path.direction, throughput, flags + 1);
        }
        return;
    }
    // The surface, as drawing makes it: the normal turned to the viewer where the material is double-sided.
    float3 normal = in.normal;
    float4 tangent = in.tangent;
    if (!front)
    {
        normal = -normal;
        tangent.w = -tangent.w;
    }
    SurfaceInput input;
    input.world = p;
    input.toViewer = v;
    input.normal = normal;
    input.tangent = tangent;
    input.uv = in.uv;
    input.dx = hit.dx.xy;
    input.dy = hit.dy.xy;
    input.uv1 = in.uv1;
    input.dx1 = hit.dx.zw;
    input.dy1 = hit.dy.zw;
    input.color = in.color;
    input.features = features;
    input.transforms = nullptr;
    if ((features & FEATURE_TEXTURE_TRANSFORM) != 0)
    {
        input.transforms = materialFeatureAt<TextureTransformParameters>(ref, FEATURE_TEXTURE_TRANSFORM);
    }
    // A type's geometry hook (GEOMETRY_KEEP) makes holes at hits as in every pass drawing it: the path goes through
    if (materialGeometry(m) != 0 && !M.keep(materialParameters<M.Parameters>(ref), ref, input))
    {
        if (bounce + 1 < maxBounces)
        {
            path.rng = rng;
            continuePath(path, offsetRay(p, front ? -ng : ng), path.direction, throughput, flags + 1);
        }
        return;
    }
    float3 emissive;
    Surface s = typedSurface<M>(ref, input, features, emissive);
    float3 side = front ? ng : -ng; // the geometric normal on the viewer's side
    // The view's surface hooks (wetness, snow), as rasterizing sees them
    surfaceHooksAt(hitSite(push.view[0].hooks, path.instance, SHADING_RAY_HIT), s, p, side, v, m.header);
    // A shading normal (a normal map's) nearly in the plane of the view or facing away from it: moved towards the viewer
    // until it faces it by at least 0.01, so the lobes sampled about it see the viewer (a normal facing away has no
    // directions to reflect into; one barely facing makes samples whose density rounds to nothing)
    float facing = dot(s.normal, v);
    if (facing < 0.01)
    {
        s.normal = normalize(s.normal + v * (0.01 - facing));
    }
    // Blended surfaces cover by their alpha: the rest of the paths go through.
    if (materialBlended(m) && random01(rng) >= s.alpha)
    {
        if (bounce + 1 < maxBounces)
        {
            path.rng = rng;
            continuePath(path, offsetRay(p, -side), path.direction, throughput, flags + 1);
        }
        return;
    }
    float unlit = 0;
    if ((features & FEATURE_UNLIT) != 0)
    {
        unlit = materialFeature<UnlitParameters>(ref, FEATURE_UNLIT).amount;
    }
    // Leaving a volume: attenuated over the way inside, then out through the surface (or reflected back in).
    if (inside && !front)
    {
        float3 attenuation = 1;
        if (s.attenuationDistance > 0)
        {
            attenuation = pow(max(s.attenuationColor, 1.0e-4), path.t / s.attenuationDistance);
        }
        throughput *= attenuation;
        float3 n = front ? s.normal : -s.normal; // pointing back into the volume? out of it: along the ray
        float3 outward = dot(n, path.direction) > 0 ? n : -n;
        float ratio = max(s.ior, 1.0); // from inside to air
        float3 micro = sampleVisibleNormal(-path.direction, -outward, max(s.roughness * s.roughness, 1.0e-4),
            float2(random01(rng), random01(rng)));
        float cosine = abs(dot(path.direction, micro));
        float3 l = refract(path.direction, micro, ratio);
        float fresnel = dielectricFresnel(cosine, ratio);
        if (random01(rng) < fresnel)
        {
            l = reflect(path.direction, micro); // stays inside
            if (bounce + 1 < maxBounces && dot(l, -outward) > 0)
            {
                path.rng = rng;
                continuePath(path, offsetRay(p, -outward), l, throughput, flags + 1);
            }
            return;
        }
        if (bounce + 1 < maxBounces && dot(l, outward) > 0)
        {
            path.rng = rng;
            continuePath(path, offsetRay(p, outward), normalize(l), throughput, (flags + 1) & ~PATH_INSIDE);
        }
        return;
    }
    radiance += throughput * emissive * (1 - unlit) + throughput * s.albedo * unlit;
    throughput *= 1 - unlit;
    // The lights, each by a shadow ray
    lightWeight = throughput; // what a shadow ray waiting for a keep() test brings later is weighed by
    lightPixel = path.pixel;
    radiance += throughput * directLight(s, p, side, v, view, rng);
    path.rng = rng;
    accumulate(path.pixel, radiance);
    if (bounce + 1 >= maxBounces || all(throughput <= 0))
    {
        return;
    }
    // Russian roulette
    if (bounce + 1 >= roulette)
    {
        float q = min(max(throughput.r, max(throughput.g, throughput.b)), 0.95);
        if (random01(rng) >= q)
        {
            return;
        }
        throughput /= q;
    }
    float3 n = s.normal;
    float nv = saturate(dot(n, v));
    // Transmission (glass): what the dielectric lets in rather than reflects, tinted by its albedo
    float through = s.transmission * (1 - s.metallic);
    if (through > 0)
    {
        float3 fresnel = surfaceFresnel(s, nv);
        float chance = through * (1 - luminance(fresnel));
        if (random01(rng) < chance)
        {
            float alpha = max(s.roughness * s.roughness, 1.0e-4);
            float3 micro = sampleVisibleNormal(v, n, alpha, float2(random01(rng), random01(rng)));
            float3 weight = through * (1 - surfaceFresnel(s, saturate(dot(v, micro)))) * s.albedo / chance;
            if (s.thickness > 0)
            {
                // Into the volume: refracted at the microfacet
                float3 l = refract(-v, micro, 1 / max(s.ior, 1.0));
                if (all(l == 0) || dot(l, side) >= 0)
                {
                    return;
                }
                weight *= smithG1(saturate(dot(-n, l)), alpha * alpha);
                path.rng = rng;
                continuePath(path, offsetRay(p, -side), normalize(l), throughput * weight,
                    (flags + 1) | PATH_INSIDE);
            }
            else
            {
                // A thin wall: reflected at the microfacet, then mirrored through the surface (as the glTF sample
                // viewer's thin-walled transmission): straight through when smooth
                float3 r = reflect(-v, micro);
                float nr = dot(n, r);
                if (nr <= 0)
                {
                    return;
                }
                weight *= smithG1(nr, alpha * alpha);
                float3 l = r - 2 * nr * n;
                path.rng = rng;
                continuePath(path, offsetRay(p, -side), normalize(l), throughput * weight, flags + 1);
            }
            return;
        }
        throughput /= 1 - chance;
    }
    // The reflected lobes, mixed by their weights: Lambert (and the sheen) by the cosine, diffuse transmission by the
    // cosine behind, GGX and the clear coat by their visible normals; weighed by reflected(), the BRDF shading uses.
    float3 fresnel = surfaceFresnel(s, nv);
    float specularWeight = max(luminance(fresnel), 0.02);
    float base = (1 - s.metallic) * (1 - s.transmission) * (1 - luminance(fresnel));
    float diffuseWeight = base * (1 - s.diffuseTransmission) * luminance(s.albedo) + luminance(s.sheenColor);
    float backWeight = base * s.diffuseTransmission * luminance(s.diffuseTransmissionColor);
    float coatWeight = s.clearcoat * coatFresnel(dot(s.clearcoatNormal, v));
    float total = specularWeight + diffuseWeight + backWeight + coatWeight;
    float alpha = max(s.roughness * s.roughness, 1.0e-4);
    if (s.anisotropy > 0)
    {
        alpha = sqrt(alpha * lerp(alpha, 1.0, s.anisotropy * s.anisotropy));
    }
    float coatAlpha = max(s.clearcoatRoughness * s.clearcoatRoughness, 1.0e-4);
    float pick = random01(rng) * total;
    float2 u = float2(random01(rng), random01(rng));
    float3 l;
    if (pick < diffuseWeight)
    {
        l = cosineSample(u, n);
    }
    else if (pick < diffuseWeight + backWeight)
    {
        l = cosineSample(u, -n);
    }
    else if (pick < diffuseWeight + backWeight + specularWeight)
    {
        l = reflect(-v, sampleVisibleNormal(v, n, alpha, u));
    }
    else
    {
        l = reflect(-v, sampleVisibleNormal(v, s.clearcoatNormal, coatAlpha, u));
    }
    float nl = dot(n, l);
    float pdf = (diffuseWeight * max(nl, 0.0) / PI + backWeight * max(-nl, 0.0) / PI
        + specularWeight * visibleNormalPdf(v, l, n, alpha)
        + coatWeight * visibleNormalPdf(v, l, s.clearcoatNormal, coatAlpha)) / total;
    if (pdf <= 0)
    {
        return;
    }
    float3 f = reflected(s, v, l, float3(1));
    float3 weight = f / pdf;
    if (all(weight <= 0))
    {
        return;
    }
    path.rng = rng;
    continuePath(path, offsetRay(p, dot(l, side) >= 0 ? side : -side), l, throughput * weight, flags + 1);
}

/** How far probe ray `path` may still go: to its interval's end (PATH_INTERVALS, PATH_BOUNDED), else unbounded. */
public float probeRayEnd(PathState path)
{
    if ((push.limits & PATH_INTERVALS) == 0 || (path.flags & PATH_BOUNDED) == 0)
    {
        return 1.0e30;
    }
    uint cascade = (path.flags >> PATH_CASCADE_SHIFT) & 0xff;
    return asfloat(push.size) * float(1u << (2 * cascade)) - asfloat(path.pad);
}

/** Records probe ray `path` meeting nothing: the environment, or within a bounded interval no light. */
public void recordProbeMiss(PathState path)
{
    bool bounded = (push.limits & PATH_INTERVALS) != 0 && (path.flags & PATH_BOUNDED) != 0;
    recordProbeRay(path.pixel, bounded ? float3(0) : environment(path.direction), PROBE_MISSED);
}

/** Writes a probe ray's light and distance (negative: a back face) into its texel of the ray image. */
public void recordProbeRay(uint pixel, float3 radiance, float distance)
{
    uint2 p = uint2(pixel & 0xffff, pixel >> 16);
    images[push.accumulation][p] = float4(radiance, distance);
}

/**
 * Where a hit on instance `instance` is shaded (SHADING_RAY_HIT, SHADING_PROBE_HIT), for the view's hooks: a probe
 * volume's rays' hits by the hook block of its own table (its own light at its hits), others by the view's.
 */
HookSite hitSite(ShadingHookBlock* block, uint instance, uint path)
{
    HookSite site = HookSite.at(block, NO_PIXEL, path);
    if (anyShadingHooks() && (push.scene[0].instances[instance].mesh & INSTANCE_NO_SURFACE_HOOKS) != 0)
    {
        site.flags |= SITE_NO_SURFACE_HOOKS;
    }
    return site;
}

/**
 * Shades probe ray `index` (PATH_PROBES), whose hit is of material type M: the light the hit sends back along the ray,
 * its distance; a back face of a one-sided material is recorded as one (no light, the distance shortened to a fifth and
 * negative, DDGI's mark of a probe inside geometry); holes and what blended surfaces let pass go on.
 */
public void shadeProbeHit<M : IMaterial>(uint index, uint features)
{
    PathState path = push.paths[index];
    View view = push.view[0];
    HitSurface hit = hitSurface(path, view);
    VertexOut in = hit.vertex;
    float3 ng = hit.geometric;
    float3 v = -path.direction;
    uint flags = path.flags;
    uint bounce = flags & 0xff;
    uint maxBounces = push.limits & 0xff;
    uint rng = path.rng;
    float3 p = in.world;
    float travelled = asfloat(path.pad) + path.t;
    MaterialRef ref = { push.scene[0].materials, in.material };
    Material m = ref.slot();
    bool front = dot(ng, v) > 0;
    path.cone = coneWidth(path, view);
    if (!front && !materialDoubleSided(m))
    {
        recordProbeRay(path.pixel, 0, -0.2 * travelled);
        return;
    }
    float3 normal = in.normal;
    float4 tangent = in.tangent;
    if (!front)
    {
        normal = -normal;
        tangent.w = -tangent.w;
    }
    SurfaceInput input;
    input.world = p;
    input.toViewer = v;
    input.normal = normal;
    input.tangent = tangent;
    input.uv = in.uv;
    input.dx = hit.dx.xy;
    input.dy = hit.dy.xy;
    input.uv1 = in.uv1;
    input.dx1 = hit.dx.zw;
    input.dy1 = hit.dy.zw;
    input.color = in.color;
    input.features = features;
    input.transforms = nullptr;
    if ((features & FEATURE_TEXTURE_TRANSFORM) != 0)
    {
        input.transforms = materialFeatureAt<TextureTransformParameters>(ref, FEATURE_TEXTURE_TRANSFORM);
    }
    bool through = materialGeometry(m) != 0 && !M.keep(materialParameters<M.Parameters>(ref), ref, input);
    float3 emissive;
    Surface s = typedSurface<M>(ref, input, features, emissive);
    HookSite site = hitSite(push.indirect[0].hooks, path.instance, SHADING_PROBE_HIT);
    float3 side = front ? ng : -ng;
    surfaceHooksAt(site, s, p, side, v, m.header); // the view's surface hooks, as rasterizing sees them
    through = through || (materialBlended(m) && random01(rng) >= s.alpha);
    if (through)
    {
        // A hole, or what a blended surface lets pass: the ray goes on, its distance kept
        if (bounce + 1 < maxBounces)
        {
            path.rng = rng;
            path.pad = asuint(travelled);
            continuePath(path, offsetRay(p, front ? -ng : ng), path.direction, path.throughput, flags + 1);
        }
        else
        {
            recordProbeMiss(path);
        }
        return;
    }
    float facing = dot(s.normal, v);
    if (facing < 0.01)
    {
        s.normal = normalize(s.normal + v * (0.01 - facing));
    }
    float unlit = 0;
    if ((features & FEATURE_UNLIT) != 0)
    {
        unlit = materialFeature<UnlitParameters>(ref, FEATURE_UNLIT).amount;
    }
    // Its emission, its lights (shadow rays), and the light from all around it: the volume's of the frame before
    // (further bounces), the environment's reflections as far as the volume's probes see the sky
    float3 reflectance;
    float3 diffuseWeight;
    float3 around = indirectLightWeights(push.indirect, site, s, v, p, side, reflectance, diffuseWeight);
    lightWeight = 1 - unlit;
    lightPixel = path.pixel;
    float3 radiance = emissive * (1 - unlit) + s.albedo * unlit
        + (1 - unlit) * (directLight(s, p, side, v, view, rng) + around);
    recordProbeRay(path.pixel, radiance, travelled);
}

/** Shades this pass's bin's paths with material type M: one thread per path, the groups as the bin's dispatch. */
public void shadeHits<M : IMaterial>(uint3 group, uint3 thread)
{
    uint index = pathIndex(group, thread);
    if (index >= binCount(push.bin))
    {
        return;
    }
    uint path = push.bins[push.list + binOffset(push.bin) + index];
    if ((push.limits & PATH_PROBES) != 0)
    {
        shadeProbeHit<M>(path, FEATURES);
        return;
    }
    shadeHit<M>(path, FEATURES);
}
