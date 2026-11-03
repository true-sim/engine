// The bindless set's acceleration structures (binding 3, on devices with ray tracing): top levels to trace rays
// into with ray queries, by slot. Rays reach a scene's top level through its RayScene, which also leads to what
// alpha-tests the triangles of masked materials.
module raytracing;

import lights;
import materials;
import meshlets.common;

[[vk::binding(3, 0)]]
public RaytracingAccelerationStructure scenes[];

/**
 * A scene's top level and what its triangles are made of, as engine-render's RayTracing writes it each frame: rays
 * hitting an instance of a masked material look its triangle up, interpolate its texture coordinates and test the
 * material's alpha as rasterizing does.
 */
[HostLayout]
public struct RayScene
{
    public uint slot;                  // the top level's slot in scenes
    public uint pad;
    // Addresses of host-layout structs as integers: the layout generator reads pointers to them by name only.
    public uint64_t instances;         // Instance*: the scene's, by instance id (the top level's custom index)
    public MeshEntry* meshes;          // the pool's mesh table
    public uint* meshlets;             // the pool's meshlet records, 8 words each
    public uint* meshletVertices;
    public uint* meshletTriangles;     // a byte per local index
    public uint* attributes;
    public uint64_t materials;         // Material*: the scene's material table
    public uint64_t bottom;            // BottomLevel*: per pool mesh
};

/**
 * A mesh's bottom level: its address, and the meshlets it was built from (a detail level's cut, which in a cluster
 * hierarchy need not be one range of them), its triangles theirs in that order.
 */
[HostLayout]
public struct BottomLevel
{
    public uint64_t address;           // 0: none
    public uint firstMeshlet;          // unused
    public uint meshletCount;          // how many meshlets it was built from
    public uint submeshes;             // how many submeshes its meshlets name: the instance's materials to look at
    public uint firstIndex;            // unused
    public uint64_t list;              // uint2 per meshlet: the mesh's meshlet, its first triangle in the level
};

/** Byte `i` of the pool's meshlet triangles. */
uint triangleByte(RayScene* scene, uint i)
{
    return (scene.meshletTriangles[i >> 2] >> ((i & 3) * 8)) & 0xff;
}

/**
 * Where triangle `primitive` of `level` lies in its mesh: its first index (the mesh's triangle byte) returned, the
 * first word of its meshlet's record into `w`; found in the level's list of meshlets by its first triangle.
 */
uint levelTriangle(RayScene* scene, MeshEntry mesh, BottomLevel level, uint primitive, out uint w)
{
    uint2* list = (uint2*)level.list;
    uint low = 0;
    uint high = level.meshletCount - 1;
    while (low < high)
    {
        uint middle = (low + high + 1) / 2;
        if (list[middle].y <= primitive)
        {
            low = middle;
        }
        else
        {
            high = middle - 1;
        }
    }
    uint2 entry = list[low];
    w = (mesh.meshletBase + entry.x) * 8;
    return scene.meshlets[w + 1] + (primitive - entry.y) * 3;
}

/** The material id (of the scene's table) of triangle `primitive` of instance `id`'s bottom level: its submesh's. */
public uint triangleMaterial(RayScene* scene, uint id, uint primitive)
{
    Instance instance = ((Instance*)scene.instances)[id];
    uint meshIndex = instance.mesh & INSTANCE_MESH;
    MeshEntry mesh = scene.meshes[meshIndex];
    BottomLevel level = ((BottomLevel*)scene.bottom)[meshIndex];
    uint w;
    levelTriangle(scene, mesh, level, primitive, w);
    return instance.materialBase + (scene.meshlets[w + 2] >> 16);
}

/**
 * Whether triangle `primitive` of instance `id`'s bottom level, at barycentrics `bary` (of its second and third
 * vertices), is masked out by its material: the same test rasterizing a masked material makes, at the finest texels.
 */
public bool clipped(RayScene* scene, uint id, uint primitive, float2 bary)
{
    Instance instance = ((Instance*)scene.instances)[id];
    uint meshIndex = instance.mesh & INSTANCE_MESH;
    MeshEntry mesh = scene.meshes[meshIndex];
    BottomLevel level = ((BottomLevel*)scene.bottom)[meshIndex];
    uint w;
    uint t = levelTriangle(scene, mesh, level, primitive, w);
    uint id = instance.materialBase + (scene.meshlets[w + 2] >> 16);
    Material m = ((Material*)scene.materials)[id];
    if (!materialMasked(m))
    {
        return false;
    }
    float3 weights = float3(1 - bary.x - bary.y, bary.x, bary.y);
    uint first = mesh.indexBase + t;
    uint vertexBase = mesh.meshletVertexBase + scene.meshlets[w];
    float2 uv = 0;
    float2 uv1 = 0;
    float4 color = 1;
    if ((mesh.attributeFormat & 16) != 0)
    {
        color = 0;
    }
    for (uint k = 0; k < 3; k++)
    {
        uint vertex = mesh.vertexBase + scene.meshletVertices[vertexBase + triangleByte(scene, first + k)];
        if ((mesh.attributeFormat & 4) != 0) // UV0: 2 x float16
        {
            uint word = attributeWord(mesh, scene.attributes, vertex, 2);
            uv += weights[k] * float2(decodeHalf(word & 0xffff), decodeHalf(word >> 16));
        }
        if ((mesh.attributeFormat & 8) != 0) // UV1: 2 x float16, for a mask texture in the second set
        {
            uint word = attributeWord(mesh, scene.attributes, vertex, 3);
            uv1 += weights[k] * float2(decodeHalf(word & 0xffff), decodeHalf(word >> 16));
        }
        if ((mesh.attributeFormat & 16) != 0) // COLOR: 4 x unorm8
        {
            uint word = attributeWord(mesh, scene.attributes, vertex, 4);
            color += weights[k] * float4(word & 0xff, (word >> 8) & 0xff, (word >> 16) & 0xff, word >> 24) / 255.0;
        }
    }
    return alphaClipped((Material*)scene.materials, id, float4(uv, uv1), float4(0), float4(0), color);
}

/**
 * Whether anything lies on the ray from origin along direction, between tMin and tMax, in `scene`'s top level:
 * opaque triangles stop it, those of masked materials where their alpha is not clipped.
 */
public bool occluded(RayScene* scene, float3 origin, float3 direction, float tMin, float tMax)
{
    RayDesc ray;
    ray.Origin = origin;
    ray.Direction = direction;
    ray.TMin = tMin;
    ray.TMax = tMax;
    RayQuery<RAY_FLAG_ACCEPT_FIRST_HIT_AND_END_SEARCH | RAY_FLAG_SKIP_PROCEDURAL_PRIMITIVES> query;
    query.TraceRayInline(scenes[scene.slot], RAY_FLAG_NONE, 0xff, ray);
    // Instances of masked materials come as candidates (the top level forces them non-opaque): each kept unless
    // its texel is clipped.
    while (query.Proceed())
    {
        if (query.CandidateType() == CANDIDATE_NON_OPAQUE_TRIANGLE
            && !clipped(scene, query.CandidateInstanceID(), query.CandidatePrimitiveIndex(),
                query.CandidateTriangleBarycentrics()))
        {
            query.CommitNonOpaqueTriangleHit();
        }
    }
    return query.CommittedStatus() == COMMITTED_TRIANGLE_HIT;
}

/** Shadow rays by ray queries into the frame's scenes: for lights.slang's inline ray shadows. */
public struct QueryRays : IShadowRays
{
    public float clear(uint64_t scene, float3 origin, float3 direction, float tMax)
    {
        return occluded((RayScene*)scene, origin, direction, 0, tMax) ? 0 : 1;
    }
}

/** The nearest triangle a ray meets: which, where along it, and where on it. */
public struct RayHit
{
    public bool hit;
    public uint instance;      // the scene's instance id (the top level's custom index)
    public uint primitive;     // the triangle of its bottom level
    public float2 bary;        // barycentrics of the triangle's second and third vertices
    public float t;            // along the ray, in units of its direction
};

/**
 * The nearest triangle on the ray from origin along direction, between tMin and tMax, in `scene`'s top level: opaque
 * triangles as they come, those of masked materials where their alpha is not clipped (as occluded() stops at them).
 */
public RayHit nearest(RayScene* scene, float3 origin, float3 direction, float tMin, float tMax)
{
    RayDesc ray;
    ray.Origin = origin;
    ray.Direction = direction;
    ray.TMin = tMin;
    ray.TMax = tMax;
    RayQuery<RAY_FLAG_SKIP_PROCEDURAL_PRIMITIVES> query;
    query.TraceRayInline(scenes[scene.slot], RAY_FLAG_NONE, 0xff, ray);
    while (query.Proceed())
    {
        if (query.CandidateType() == CANDIDATE_NON_OPAQUE_TRIANGLE
            && !clipped(scene, query.CandidateInstanceID(), query.CandidatePrimitiveIndex(),
                query.CandidateTriangleBarycentrics()))
        {
            query.CommitNonOpaqueTriangleHit();
        }
    }
    RayHit h;
    h.hit = query.CommittedStatus() == COMMITTED_TRIANGLE_HIT;
    h.instance = h.hit ? query.CommittedInstanceID() : 0;
    h.primitive = h.hit ? query.CommittedPrimitiveIndex() : 0;
    h.bary = h.hit ? query.CommittedTriangleBarycentrics() : float2(0);
    h.t = h.hit ? query.CommittedRayT() : tMax;
    return h;
}

/**
 * Where the vertices of triangle `primitive` of instance `id`'s bottom level lie in the pool: its meshlet record's
 * first word, and its three pool vertices.
 */
public struct TriangleVertices
{
    public uint meshletWord;   // (the pool meshlet * 8): its record's first word
    public uint3 vertices;     // pool vertices
    public uint submesh;
};

/** The pool vertices of triangle `primitive` of instance `id`'s bottom level, as rasterizing would draw it. */
public TriangleVertices triangleVertices(RayScene* scene, uint id, uint primitive)
{
    Instance instance = ((Instance*)scene.instances)[id];
    uint meshIndex = instance.mesh & INSTANCE_MESH;
    MeshEntry mesh = scene.meshes[meshIndex];
    BottomLevel level = ((BottomLevel*)scene.bottom)[meshIndex];
    uint w;
    uint t = levelTriangle(scene, mesh, level, primitive, w);
    uint first = mesh.indexBase + t;
    uint vertexBase = mesh.meshletVertexBase + scene.meshlets[w];
    TriangleVertices v;
    v.meshletWord = w;
    v.submesh = scene.meshlets[w + 2] >> 16;
    for (uint k = 0; k < 3; k++)
    {
        v.vertices[k] = mesh.vertexBase + scene.meshletVertices[vertexBase + triangleByte(scene, first + k)];
    }
    return v;
}
