// The mesh shader path: the cull pass lists the visible meshlets, as for the indexed path, and a mesh workgroup per
// listed meshlet emits its vertices and triangles straight from the pool. Culling in a compute pass rather than in task
// shaders keeps every lane busy: a task workgroup per chunk of 64 meshlets launches only a few mesh workgroups when
// instances need few meshlets (30,000 rocks: 0.95 ms against 0.59 ms). Where fragment shaders have barycentrics,
// shaded draws take lean.slang's entries and opaque depth the slim one below; the visibility buffer's IDs (and
// GPUs without them) take meshIdsMain, whose vertices carry everything for draw.slang's fragment shaders. Meshlets
// hold at most 64 vertices and 124 triangles.
// The push block (forward.slang's ForwardPush, as the shaded draws' fragment stages take it) is forward.slang's.
import meshlets.forward;

// The triangle's number in its meshlet, for the visibility buffer's fragment shader.
struct PrimitiveOut
{
    uint id : SV_PrimitiveID;
};

/** The visibility buffer's draws, and every draw where fragment shaders lack barycentrics: vertices carry all. */
[shader("mesh")]
[outputtopology("triangle")]
[numthreads(64, 1, 1)]
void meshIdsMain(uint3 group : SV_GroupID, uint thread : SV_GroupIndex, out vertices VertexOut vertices[64],
    out indices uint3 triangles[124], out primitives PrimitiveOut primitives[124])
{
    View view = push.view[0];
    uint* triangleBytes = push.scene[0].meshletTriangles; // loaded once, read per triangle
    // Past the list's end a workgroup outputs nothing, through the one SetMeshOutputCounts below all the same: with a
    // second call on an early exit, the driver of an AMD Radeon(TM) Graphics drew nothing at all (pitfalls.md).
    ListedMeshlet found = listedMeshlet(view, group.y * 65535 + group.x);
    bool listed = found.listed;
    uint slot = found.slot;
    VisibleMeshlet v = listed ? push.visible[slot] : (VisibleMeshlet) 0;
    uint m = v.meshlet;
    Instance instance = relative(push.scene[0].instances[v.instance], view);
    MeshEntry mesh = push.scene[0].meshes[instance.mesh];
    uint w = m * 8;
    uint vertexOffset = push.scene[0].meshlets[w];
    uint triangleOffset = push.scene[0].meshlets[w + 1];
    uint counts = listed ? push.scene[0].meshlets[w + 2] : 0;
    uint vertexCount = counts & 0xff;
    uint triangleCount = (counts >> 8) & 0xff;
    SetMeshOutputCounts(vertexCount, triangleCount);
    if (thread < vertexCount)
    {
        uint vertex = mesh.vertexBase
            + push.scene[0].meshletVertices[mesh.meshletVertexBase + vertexOffset + thread];
        vertices[thread] = transformVertex(view, instance, mesh, vertex, push.scene[0].positions,
            push.scene[0].attributes, m,
            counts >> 16);
        vertices[thread].instance = v.instance;
    }
    for (uint t = thread; t < triangleCount; t += 64)
    {
        uint b = mesh.indexBase + triangleOffset + t * 3;
        triangles[t] = wound(uint3(triangleByte(triangleBytes, b), triangleByte(triangleBytes, b + 1), triangleByte(triangleBytes, b + 2)), mirrored(instance));
        primitives[t].id = t;
    }
}

/** Like meshIdsMain, in a typed draw: the meshlets of one shading bin of the raster bin, through the split list. */
[shader("mesh")]
[outputtopology("triangle")]
[numthreads(64, 1, 1)]
void meshIdsTypedMain(uint3 group : SV_GroupID, uint thread : SV_GroupIndex, out vertices VertexOut vertices[64],
    out indices uint3 triangles[124], out primitives PrimitiveOut primitives[124])
{
    View view = push.view[0];
    uint* triangleBytes = push.scene[0].meshletTriangles; // loaded once, read per triangle
    // Past the list's end a workgroup outputs nothing, through the one SetMeshOutputCounts below all the same: with a
    // second call on an early exit, the driver of an AMD Radeon(TM) Graphics drew nothing at all (pitfalls.md).
    ListedMeshlet found = listedTypedMeshlet(view, group.y * 65535 + group.x);
    bool listed = found.listed;
    uint slot = found.slot;
    VisibleMeshlet v = listed ? push.visible[slot] : (VisibleMeshlet) 0;
    uint m = v.meshlet;
    Instance instance = relative(push.scene[0].instances[v.instance], view);
    MeshEntry mesh = push.scene[0].meshes[instance.mesh];
    uint w = m * 8;
    uint vertexOffset = push.scene[0].meshlets[w];
    uint triangleOffset = push.scene[0].meshlets[w + 1];
    uint counts = listed ? push.scene[0].meshlets[w + 2] : 0;
    uint vertexCount = counts & 0xff;
    uint triangleCount = (counts >> 8) & 0xff;
    SetMeshOutputCounts(vertexCount, triangleCount);
    if (thread < vertexCount)
    {
        uint vertex = mesh.vertexBase
            + push.scene[0].meshletVertices[mesh.meshletVertexBase + vertexOffset + thread];
        vertices[thread] = transformVertex(view, instance, mesh, vertex, push.scene[0].positions,
            push.scene[0].attributes, m,
            counts >> 16);
        vertices[thread].instance = v.instance;
    }
    for (uint t = thread; t < triangleCount; t += 64)
    {
        uint b = mesh.indexBase + triangleOffset + t * 3;
        triangles[t] = wound(uint3(triangleByte(triangleBytes, b), triangleByte(triangleBytes, b + 1), triangleByte(triangleBytes, b + 2)), mirrored(instance));
        primitives[t].id = t;
    }
}

// The slim path, for the depth of opaque bins (no fragment shader): vertices carry their clip positions alone.

struct SlimVertex
{
    float4 position : SV_Position;
};

[shader("mesh")]
[outputtopology("triangle")]
[numthreads(64, 1, 1)]
void meshMain(uint3 group : SV_GroupID, uint thread : SV_GroupIndex, out vertices SlimVertex vertices[64],
    out indices uint3 triangles[124])
{
    View view = push.view[0];
    uint* triangleBytes = push.scene[0].meshletTriangles; // loaded once, read per triangle
    // Past the list's end a workgroup outputs nothing, through the one SetMeshOutputCounts below all the same: with a
    // second call on an early exit, the driver of an AMD Radeon(TM) Graphics drew nothing at all (pitfalls.md).
    ListedMeshlet found = listedMeshlet(view, group.y * 65535 + group.x);
    bool listed = found.listed;
    uint slot = found.slot;
    VisibleMeshlet v = listed ? push.visible[slot] : (VisibleMeshlet) 0;
    Instance instance = relative(push.scene[0].instances[v.instance], view);
    MeshEntry mesh = push.scene[0].meshes[instance.mesh];
    uint w = v.meshlet * 8;
    uint vertexOffset = push.scene[0].meshlets[w];
    uint triangleOffset = push.scene[0].meshlets[w + 1];
    uint counts = listed ? push.scene[0].meshlets[w + 2] : 0;
    uint vertexCount = counts & 0xff;
    uint triangleCount = (counts >> 8) & 0xff;
    SetMeshOutputCounts(vertexCount, triangleCount);
    if (thread < vertexCount)
    {
        uint vertex = mesh.vertexBase
            + push.scene[0].meshletVertices[mesh.meshletVertexBase + vertexOffset + thread];
        float3 world = toWorld(instance, decodePosition(push.scene[0].positions[vertex], mesh));
        vertices[thread].position = mul(view.viewProjection, float4(world, 1.0));
    }
    for (uint t = thread; t < triangleCount; t += 64)
    {
        uint b = mesh.indexBase + triangleOffset + t * 3;
        triangles[t] = wound(uint3(triangleByte(triangleBytes, b), triangleByte(triangleBytes, b + 1), triangleByte(triangleBytes, b + 2)), mirrored(instance));
    }
}

/** Like meshMain, in a split draw: the meshlets of one category of the raster bin, through the split list. */
[shader("mesh")]
[outputtopology("triangle")]
[numthreads(64, 1, 1)]
void meshTypedMain(uint3 group : SV_GroupID, uint thread : SV_GroupIndex, out vertices SlimVertex vertices[64],
    out indices uint3 triangles[124])
{
    View view = push.view[0];
    uint* triangleBytes = push.scene[0].meshletTriangles; // loaded once, read per triangle
    // Past the list's end a workgroup outputs nothing, through the one SetMeshOutputCounts below all the same: with a
    // second call on an early exit, the driver of an AMD Radeon(TM) Graphics drew nothing at all (pitfalls.md).
    ListedMeshlet found = listedTypedMeshlet(view, group.y * 65535 + group.x);
    bool listed = found.listed;
    uint slot = found.slot;
    VisibleMeshlet v = listed ? push.visible[slot] : (VisibleMeshlet) 0;
    Instance instance = relative(push.scene[0].instances[v.instance], view);
    MeshEntry mesh = push.scene[0].meshes[instance.mesh];
    uint w = v.meshlet * 8;
    uint vertexOffset = push.scene[0].meshlets[w];
    uint triangleOffset = push.scene[0].meshlets[w + 1];
    uint counts = listed ? push.scene[0].meshlets[w + 2] : 0;
    uint vertexCount = counts & 0xff;
    uint triangleCount = (counts >> 8) & 0xff;
    SetMeshOutputCounts(vertexCount, triangleCount);
    if (thread < vertexCount)
    {
        uint vertex = mesh.vertexBase
            + push.scene[0].meshletVertices[mesh.meshletVertexBase + vertexOffset + thread];
        float3 world = toWorld(instance, decodePosition(push.scene[0].positions[vertex], mesh));
        vertices[thread].position = mul(view.viewProjection, float4(world, 1.0));
    }
    for (uint t = thread; t < triangleCount; t += 64)
    {
        uint b = mesh.indexBase + triangleOffset + t * 3;
        triangles[t] = wound(uint3(triangleByte(triangleBytes, b), triangleByte(triangleBytes, b + 1), triangleByte(triangleBytes, b + 2)), mirrored(instance));
    }
}

