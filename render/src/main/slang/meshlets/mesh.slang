// The mesh shader path: a task workgroup per chunk culls its meshlets (the same test as the fallback) and launches a
// mesh workgroup per visible one, which emits the meshlet's vertices and triangles straight from the pool. The fragment
// shader is the one in draw.slang. Meshlets hold at most 64 vertices and 124 triangles.
import meshlets.common;

struct MeshPush
{
    View* view;
    MeshEntry* meshes;
    Instance* instances;
    Chunk* chunks;
    uint* meshlets;
    float* clusterLod;
    uint* meshletVertices;   // mesh vertex indices, per meshlet
    uint* meshletTriangles;  // 3 bytes per triangle, as words
    uint2* positions;
    uint* attributes;
    uint* drawCount;         // counts visible meshlets, as the fallback does
};

[[vk::push_constant]]
ConstantBuffer<MeshPush> push;

struct Payload
{
    uint instance;
    uint meshlets[64];
};

groupshared Payload payload;
groupshared uint visibleCount;

[shader("amplification")]
[numthreads(64, 1, 1)]
void taskMain(uint3 group : SV_GroupID, uint thread : SV_GroupIndex)
{
    if (thread == 0)
    {
        visibleCount = 0;
    }
    GroupMemoryBarrierWithGroupSync();
    View view = push.view[0];
    Chunk chunk = push.chunks[group.x];
    Instance instance = push.instances[chunk.instance];
    MeshEntry mesh = push.meshes[instance.mesh];
    uint local = chunk.firstMeshlet + thread;
    if (local < mesh.meshletCount)
    {
        uint m = mesh.meshletBase + local;
        if (meshletVisible(view, instance, m, push.meshlets, push.clusterLod))
        {
            uint slot;
            InterlockedAdd(visibleCount, 1, slot);
            payload.meshlets[slot] = m;
        }
    }
    payload.instance = chunk.instance;
    GroupMemoryBarrierWithGroupSync();
    if (thread == 0)
    {
        InterlockedAdd(push.drawCount[0], visibleCount);
    }
    DispatchMesh(visibleCount, 1, 1, payload);
}

/** Byte i of the pool's meshlet triangles. */
uint triangleByte(uint i)
{
    return (push.meshletTriangles[i >> 2] >> ((i & 3) * 8)) & 0xff;
}

[shader("mesh")]
[outputtopology("triangle")]
[numthreads(64, 1, 1)]
void meshMain(uint3 group : SV_GroupID, uint thread : SV_GroupIndex, in payload Payload task,
    out vertices VertexOut vertices[64], out indices uint3 triangles[124])
{
    View view = push.view[0];
    uint m = task.meshlets[group.x];
    Instance instance = push.instances[task.instance];
    MeshEntry mesh = push.meshes[instance.mesh];
    uint w = m * 8;
    uint vertexOffset = push.meshlets[w];
    uint triangleOffset = push.meshlets[w + 1];
    uint counts = push.meshlets[w + 2];
    uint vertexCount = counts & 0xff;
    uint triangleCount = (counts >> 8) & 0xff;
    SetMeshOutputCounts(vertexCount, triangleCount);
    if (thread < vertexCount)
    {
        uint vertex = mesh.vertexBase + push.meshletVertices[mesh.meshletVertexBase + vertexOffset + thread];
        vertices[thread] = transformVertex(view, instance, mesh, vertex, push.positions, push.attributes, m);
    }
    for (uint t = thread; t < triangleCount; t += 64)
    {
        uint b = mesh.indexBase + triangleOffset + t * 3;
        triangles[t] = uint3(triangleByte(b), triangleByte(b + 1), triangleByte(b + 2));
    }
}
