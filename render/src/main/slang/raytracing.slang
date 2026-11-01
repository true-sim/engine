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

/** A mesh's bottom level: its address, and the detail level it was built from. */
[HostLayout]
public struct BottomLevel
{
    public uint64_t address;           // 0: none
    public uint firstMeshlet;          // the level's first meshlet, of the mesh's
    public uint meshletCount;
    public uint submeshes;             // how many submeshes its meshlets name: the instance's materials to look at
    public uint firstIndex;            // the mesh's triangle byte of its triangle 0
};

/** Byte `i` of the pool's meshlet triangles. */
uint triangleByte(RayScene* scene, uint i)
{
    return (scene.meshletTriangles[i >> 2] >> ((i & 3) * 8)) & 0xff;
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
    // The meshlet holding the triangle: the level's meshlets' triangles follow one another.
    uint t = level.firstIndex + primitive * 3;
    uint low = level.firstMeshlet;
    uint high = level.firstMeshlet + level.meshletCount - 1;
    while (low < high)
    {
        uint middle = (low + high + 1) / 2;
        if (scene.meshlets[(mesh.meshletBase + middle) * 8 + 1] <= t)
        {
            low = middle;
        }
        else
        {
            high = middle - 1;
        }
    }
    uint w = (mesh.meshletBase + low) * 8;
    Material m = ((Material*)scene.materials)[instance.materialBase + (scene.meshlets[w + 2] >> 16)];
    if (!materialMasked(m))
    {
        return false;
    }
    float3 weights = float3(1 - bary.x - bary.y, bary.x, bary.y);
    uint first = mesh.indexBase + t;
    uint vertexBase = mesh.meshletVertexBase + scene.meshlets[w];
    float2 uv = 0;
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
        if ((mesh.attributeFormat & 16) != 0) // COLOR: 4 x unorm8
        {
            uint word = attributeWord(mesh, scene.attributes, vertex, 4);
            color += weights[k] * float4(word & 0xff, (word >> 8) & 0xff, (word >> 16) & 0xff, word >> 24) / 255.0;
        }
    }
    return alphaClipped(m, float4(uv, uv), float4(0), float4(0), color);
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
