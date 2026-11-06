// Shadow rays of the Shadows owner's traced masks (engine-render's TracedMasks): per pixel of a view's depth and per
// traced light, rays towards the light (one at its center, or for soft shadows several towards points of its disc or
// sphere), each stopped by opaque triangles and by masked ones where their alpha is not clipped. A triangle of a
// material type with a geometry hook (keep()) cannot be tested here, by a shader that knows no type: the nearest one
// on the ray is queued (a ShadowRay), its type's keep entry point (material-types/entryShadowKeep) says whether the
// point is kept, and the ray goes on from there (masks.slang's continueMain) through holes, rounds as the frame allows.
module rays;

__exported import raytracing;
__exported import meshlets.common;
__exported import surfaces;

/** A ray waiting for a keep() test: 32 bytes. */
[HostLayout]
public struct ShadowRay
{
    public uint pixel;             // x | y << 16
    public uint state;             // the traced light's slot (bits 0-7), the ray (8-15), rays found clear (16-31)
    public uint instance;          // the hit: instance, triangle, barycentrics, distance
    public uint primitive;
    public float2 bary;
    public float t;
    public uint kept;              // 0 not tested, KEPT, HOLE; the hit's geometry type number << 8
};

public static const uint KEPT = 1;
public static const uint HOLE = 2;

/** A queue of shadow rays: a count, its indirect dispatch (64 a group), then the rays. */
public static const uint QUEUE_HEADER = 4; // words

/** One traced light of a view, this frame: 32 bytes. */
[HostLayout]
public struct TraceSlot
{
    public uint light;             // the light's index
    public uint output;            // storage slot: the mask (hard), or the denoiser's input A (soft: visibility in a)
    public uint flags;             // TRACE_SOFT
    public uint rays;              // per pixel
    public float softness;         // angular radius (directional) or radius
    public float distance;         // where rays end (directional; 0: unbounded)
    public uint outputB;           // soft: the denoiser's input B (zero)
    public uint pad;
};

public static const uint TRACE_SOFT = 1;

/** What every pass of a view's traced masks reads: written each frame. */
[HostLayout]
public struct ShadowTrace
{
    public float4x4 inverseViewProjection; // clip space back to positions relative to the camera
    public float4x4 viewProjection;
    public float3 cameraHigh;              // the camera's world position
    public uint depth;                     // sampled slot of the view's depth
    public float3 cameraLow;
    public uint normal;                    // storage slot of the normals written for the denoiser (0: none)
    public float3 toTopLevel;              // what moves a camera-relative position into the top level's
    public uint size;                      // the render region: width | height << 16
    public float offset;                   // rays start this far off the surface per unit of distance from the camera
    public uint frame;                     // turns the soft rays' samples
    public uint capacity;                  // shadow rays a queue holds
    public uint slots;                     // traced lights
    public uint64_t scene;                 // RayScene*
    public uint64_t lights;                // Light*
    public uint64_t traceSlots;            // TraceSlot*
    public uint64_t sceneTable;            // SceneTable*: for the keep entry points
    public uint64_t view;                  // View*: the camera's, for the keep entry points
    public uint queueB;                    // light rays (LightRays): words from this record to the second queue
    public uint sums;                      // and to the light found per pixel, rgb as floats' bits
};

/** The way a ray met nothing, something, or a triangle whose keep() decides. */
public static const uint CLEAR = 0;
public static const uint BLOCKED = 1;
public static const uint PENDING = 2;

/**
 * Traces from `origin` along `direction` between tMin and tMax in `scene`'s top level: BLOCKED by an opaque triangle
 * or a masked one where its alpha is not clipped; else PENDING with the nearest triangle of a type with a geometry
 * hook into `hit` (its keep() decides); else CLEAR.
 */
public uint traceShadow(RayScene* scene, float3 origin, float3 direction, float tMin, float tMax, out ShadowRay hit)
{
    hit.pixel = 0;
    hit.state = 0;
    hit.instance = 0;
    hit.primitive = 0;
    hit.bary = 0;
    hit.t = tMax;
    hit.kept = 0;
    RayDesc ray;
    ray.Origin = origin;
    ray.Direction = direction;
    ray.TMin = tMin;
    ray.TMax = tMax;
    RayQuery<RAY_FLAG_ACCEPT_FIRST_HIT_AND_END_SEARCH | RAY_FLAG_SKIP_PROCEDURAL_PRIMITIVES> query;
    query.TraceRayInline(scenes[scene.slot], RAY_FLAG_NONE, 0xff, ray);
    bool pending = false;
    Material* materials = (Material*)scene.materials;
    while (query.Proceed())
    {
        if (query.CandidateType() != CANDIDATE_NON_OPAQUE_TRIANGLE)
        {
            continue;
        }
        uint id = query.CandidateInstanceID();
        uint primitive = query.CandidatePrimitiveIndex();
        float2 bary = query.CandidateTriangleBarycentrics();
        uint geometry = materialGeometry(materials[triangleMaterial(scene, id, primitive)]);
        if (geometry != 0)
        {
            float t = query.CandidateTriangleRayT();
            if (t < hit.t)
            {
                hit.instance = id;
                hit.primitive = primitive;
                hit.bary = bary;
                hit.t = t;
                hit.kept = geometry << 8;
                pending = true;
            }
        }
        else if (!clipped(scene, id, primitive, bary))
        {
            query.CommitNonOpaqueTriangleHit();
        }
    }
    if (query.CommittedStatus() == COMMITTED_TRIANGLE_HIT)
    {
        return BLOCKED;
    }
    return pending ? PENDING : CLEAR;
}

/** Appends `ray` to the queue at `queue`, holding `capacity` rays; false when it is full. */
public bool enqueue(uint* queue, uint capacity, ShadowRay ray)
{
    uint index;
    InterlockedAdd(queue[0], 1, index);
    if (index >= capacity)
    {
        return false;
    }
    if ((index & 63) == 0)
    {
        InterlockedAdd(queue[1], 1); // its indirect dispatch: a group more
    }
    ((ShadowRay*)(queue + QUEUE_HEADER))[index] = ray;
    return true;
}

/**
 * Whether the point of `ray`'s hit exists on its material's surface: type M's keep(), and the alpha of a masked
 * material, at the finest texels: a keep entry point's test, for rays queued as PENDING.
 */
public bool shadowKept<M : IMaterial>(ShadowTrace* trace, ShadowRay ray)
{
    RayScene* rays = (RayScene*)trace.scene;
    SceneTable* table = (SceneTable*)trace.sceneTable;
    View view = ((View*)trace.view)[0];
    if (clipped(rays, ray.instance, ray.primitive, ray.bary))
    {
        return false;
    }
    TriangleVertices tv = triangleVertices(rays, ray.instance, ray.primitive);
    Instance instance = relative(table.instances[ray.instance], view);
    MeshEntry mesh = table.meshes[instance.mesh];
    uint meshlet = tv.meshletWord / 8;
    VertexOut v[3];
    for (uint k = 0; k < 3; k++)
    {
        v[k] = transformVertex(view, instance, mesh, tv.vertices[k], table.positions, table.attributes, meshlet,
            tv.submesh);
    }
    float3 w = float3(1 - ray.bary.x - ray.bary.y, ray.bary.x, ray.bary.y);
    VertexOut s = v[0];
    s.world = w.x * v[0].world + w.y * v[1].world + w.z * v[2].world;
    s.normal = w.x * v[0].normal + w.y * v[1].normal + w.z * v[2].normal;
    s.tangent = float4(w.x * v[0].tangent.xyz + w.y * v[1].tangent.xyz + w.z * v[2].tangent.xyz, v[0].tangent.w);
    s.uv = w.x * v[0].uv + w.y * v[1].uv + w.z * v[2].uv;
    s.uv1 = w.x * v[0].uv1 + w.y * v[1].uv1 + w.z * v[2].uv1;
    s.color = w.x * v[0].color + w.y * v[1].color + w.z * v[2].color;
    return kept<M>(view, s, table.materials, float4(0), float4(0));
}

/**
 * The push of the traced masks' passes (masks.slang) and of the keep entry points: the frame's trace, the queue read
 * and the queue written; `mode` is masks.slang's last round flag, or the keep entry point's type number among those
 * with geometry hooks.
 */
public struct ShadowMasksPush
{
    public ShadowTrace* trace;
    public uint* queueIn;
    public uint* queueOut;
    public uint mode;
    public uint pad;
};

/** Tests ray `index` of the queue if its hit is of type M (the push's mode: the type's number): KEPT or HOLE. */
public void shadowKeep<M : IMaterial>(ShadowMasksPush push, uint index)
{
    uint* queue = push.queueIn;
    if (index >= min(queue[0], push.trace.capacity))
    {
        return;
    }
    // The queue's records: shadow rays (8 words), or light rays (LightRay, push.pad words) beginning with one
    uint stride = push.pad != 0 ? push.pad : 8;
    ShadowRay* at = (ShadowRay*)(queue + QUEUE_HEADER + index * stride);
    ShadowRay ray = at[0];
    if ((ray.kept >> 8) != push.mode)
    {
        return;
    }
    at[0].kept = (ray.kept & ~0xffu) | (shadowKept<M>(push.trace, ray) ? KEPT : HOLE);
}

/**
 * A light ray of the path tracer's next-event estimation waiting for a keep() test (its hit), the light it brings to
 * pixel `pixel` (weighted already) if nothing else blocks it: 80 bytes.
 */
[HostLayout]
public struct LightRay
{
    public ShadowRay hit;
    public float3 origin;          // in the top level's space
    public float tMax;
    public float3 direction;
    public uint pixel;             // x | y << 16
    public float3 contribution;
    public uint pad;
};

/** Words of a LightRay. */
public static const uint LIGHT_RAY_WORDS = 20;

/** Appends `ray` to the light-ray queue at `queue`, holding `capacity`; false when it is full. */
public bool enqueueLight(uint* queue, uint capacity, LightRay ray)
{
    uint index;
    InterlockedAdd(queue[0], 1, index);
    if (index >= capacity)
    {
        return false;
    }
    if ((index & 63) == 0)
    {
        InterlockedAdd(queue[1], 1);
    }
    ((LightRay*)(queue + QUEUE_HEADER))[index] = ray;
    return true;
}

/** Adds `value` to the float whose bits word `word` holds (a compare-and-swap loop: floats without atomics). */
public void addFloat(uint* word, float value)
{
    uint seen = word[0];
    while (true)
    {
        uint was;
        InterlockedCompareExchange(word[0], seen, asuint(asfloat(seen) + value), was);
        if (was == seen)
        {
            return;
        }
        seen = was;
    }
}
