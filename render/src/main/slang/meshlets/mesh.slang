// The mesh shader path: the cull pass lists the visible meshlets, as for the indexed path, and a mesh workgroup per
// listed meshlet emits its vertices and triangles straight from the pool. Culling in a compute pass rather than in task
// shaders keeps every lane busy: a task workgroup per chunk of 64 meshlets launches only a few mesh workgroups when
// instances need few meshlets (30,000 rocks: 0.95 ms against 0.59 ms). The fragment shader is the one in draw.slang.
// Meshlets hold at most 64 vertices and 124 triangles.
import meshlets.common;

struct MeshPush
{
    View* view;
    MeshEntry* meshes;
    Instance* instances;
    VisibleMeshlet* visible;
    uint* visibleCount;
    uint* meshlets;
    uint* meshletVertices;   // mesh vertex indices, per meshlet
    uint* meshletTriangles;  // 3 bytes per triangle, as words
    uint2* positions;
    uint* attributes;
    Material* materials;     // at byte 80: the fragment shader in draw.slang reads it from both paths' blocks
    LightListHeader* lights; // at byte 88, likewise
};

[[vk::push_constant]]
ConstantBuffer<MeshPush> push;

// The triangle's number in its meshlet, for the visibility buffer's fragment shader.
struct PrimitiveOut
{
    uint id : SV_PrimitiveID;
};

/** Byte i of the pool's meshlet triangles. */
uint triangleByte(uint i)
{
    return (push.meshletTriangles[i >> 2] >> ((i & 3) * 8)) & 0xff;
}

[shader("mesh")]
[outputtopology("triangle")]
[numthreads(64, 1, 1)]
void meshMain(uint3 group : SV_GroupID, uint thread : SV_GroupIndex, out vertices VertexOut vertices[64],
    out indices uint3 triangles[124], out primitives PrimitiveOut primitives[124])
{
    View view = push.view[0];
    uint slot = group.y * 65535 + group.x;
    if (slot >= min(push.visibleCount[0], view.maxDraws))
    {
        SetMeshOutputCounts(0, 0);
        return;
    }
    VisibleMeshlet v = push.visible[slot];
    uint m = v.meshlet;
    Instance instance = push.instances[v.instance];
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
        vertices[thread] = transformVertex(view, instance, mesh, vertex, push.positions, push.attributes, m,
            counts >> 16);
        vertices[thread].instance = v.instance;
    }
    for (uint t = thread; t < triangleCount; t += 64)
    {
        uint b = mesh.indexBase + triangleOffset + t * 3;
        triangles[t] = uint3(triangleByte(b), triangleByte(b + 1), triangleByte(b + 2));
        primitives[t].id = t;
    }
}
