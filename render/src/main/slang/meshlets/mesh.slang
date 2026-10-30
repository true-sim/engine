// The mesh shader path: the cull pass lists the visible meshlets, as for the indexed path, and a mesh workgroup per
// listed meshlet emits its vertices and triangles straight from the pool. Culling in a compute pass rather than in task
// shaders keeps every lane busy: a task workgroup per chunk of 64 meshlets launches only a few mesh workgroups when
// instances need few meshlets (30,000 rocks: 0.95 ms against 0.59 ms). Where fragment shaders have barycentrics,
// shaded draws take lean.slang's entries and depth-only draws the slim one below; the visibility buffer's IDs (and
// GPUs without them) take meshIdsMain, whose vertices carry everything for draw.slang's fragment shaders. Meshlets
// hold at most 64 vertices and 124 triangles.
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
    uint bin;                // the raster bin drawn
    uint late;               // 1: the late phase's meshlets
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

/** The visibility buffer's draws, and every draw where fragment shaders lack barycentrics: vertices carry all. */
[shader("mesh")]
[outputtopology("triangle")]
[numthreads(64, 1, 1)]
void meshIdsMain(uint3 group : SV_GroupID, uint thread : SV_GroupIndex, out vertices VertexOut vertices[64],
    out indices uint3 triangles[124], out primitives PrimitiveOut primitives[124])
{
    View view = push.view[0];
    uint index = group.y * 65535 + group.x;
    if (index >= min(push.visibleCount[2 * push.bin + push.late], binDrawCapacity(view, push.bin)))
    {
        SetMeshOutputCounts(0, 0);
        return;
    }
    uint slot = binFirstSlot(view, push.bin) + index;
    VisibleMeshlet v = push.visible[slot];
    uint m = v.meshlet;
    Instance instance = relative(push.instances[v.instance], view);
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

// The slim path, for depth-only draws where fragment shaders have barycentric coordinates: vertices carry their clip
// positions alone and triangles their visible meshlet's slot and number; the masked bin's fragments rebuild their
// texture coordinates from the pool, as the visibility buffer's resolve does. (Shading this way, rebuilding whole
// vertices per pixel, cost more than lean.slang's interpolated values where triangles cover many pixels.)

struct SlimVertex
{
    float4 position : SV_Position;
};

/**
 * A triangle of the slim path, as its primitive ID (a built-in, which fragment shaders read without per-primitive
 * inputs): its visible meshlet's slot in the view's list, then its number in the meshlet (7 bits: at most 124).
 */
struct SlimPrimitive
{
    uint id : SV_PrimitiveID;
};

[shader("mesh")]
[outputtopology("triangle")]
[numthreads(64, 1, 1)]
void meshMain(uint3 group : SV_GroupID, uint thread : SV_GroupIndex, out vertices SlimVertex vertices[64],
    out indices uint3 triangles[124], out primitives SlimPrimitive primitives[124])
{
    View view = push.view[0];
    uint index = group.y * 65535 + group.x;
    if (index >= min(push.visibleCount[2 * push.bin + push.late], binDrawCapacity(view, push.bin)))
    {
        SetMeshOutputCounts(0, 0);
        return;
    }
    uint slot = binFirstSlot(view, push.bin) + index;
    VisibleMeshlet v = push.visible[slot];
    Instance instance = relative(push.instances[v.instance], view);
    MeshEntry mesh = push.meshes[instance.mesh];
    uint w = v.meshlet * 8;
    uint vertexOffset = push.meshlets[w];
    uint triangleOffset = push.meshlets[w + 1];
    uint counts = push.meshlets[w + 2];
    uint vertexCount = counts & 0xff;
    uint triangleCount = (counts >> 8) & 0xff;
    SetMeshOutputCounts(vertexCount, triangleCount);
    if (thread < vertexCount)
    {
        uint vertex = mesh.vertexBase + push.meshletVertices[mesh.meshletVertexBase + vertexOffset + thread];
        float3 world = toWorld(instance, decodePosition(push.positions[vertex], mesh));
        vertices[thread].position = mul(view.viewProjection, float4(world, 1.0));
    }
    for (uint t = thread; t < triangleCount; t += 64)
    {
        uint b = mesh.indexBase + triangleOffset + t * 3;
        triangles[t] = uint3(triangleByte(b), triangleByte(b + 1), triangleByte(b + 2));
        primitives[t].id = slot << 7 | t;
    }
}

/**
 * The surface of triangle `primitive` (slot << 7 | triangle) where barycentric coordinates `bary` say: its vertices
 * rebuilt and interpolated.
 */
VertexOut rebuild(uint primitive, float3 bary, float4 position)
{
    View view = push.view[0];
    VisibleMeshlet visible = push.visible[primitive >> 7];
    Instance instance = relative(push.instances[visible.instance], view);
    MeshEntry mesh = push.meshes[instance.mesh];
    uint m = visible.meshlet;
    uint w = m * 8;
    uint first = mesh.indexBase + push.meshlets[w + 1] + (primitive & 0x7f) * 3;
    uint vertexBase = mesh.meshletVertexBase + push.meshlets[w];
    uint submesh = push.meshlets[w + 2] >> 16;
    VertexOut v[3];
    for (uint k = 0; k < 3; k++)
    {
        uint vertex = mesh.vertexBase + push.meshletVertices[vertexBase + triangleByte(first + k)];
        v[k] = transformVertex(view, instance, mesh, vertex, push.positions, push.attributes, m, submesh);
    }
    VertexOut s = v[0];
    s.position = position;
    s.world = bary.x * v[0].world + bary.y * v[1].world + bary.z * v[2].world;
    s.normal = bary.x * v[0].normal + bary.y * v[1].normal + bary.z * v[2].normal;
    s.tangent = float4(bary.x * v[0].tangent.xyz + bary.y * v[1].tangent.xyz + bary.z * v[2].tangent.xyz,
        v[0].tangent.w);
    s.uv = bary.x * v[0].uv + bary.y * v[1].uv + bary.z * v[2].uv;
    s.color = bary.x * v[0].color + bary.y * v[1].color + bary.z * v[2].color;
    s.instance = visible.instance;
    return s;
}

/** The masked bin's depth only: the same texels masked as the color pass's. */
[shader("fragment")]
void slimMaskedDepthMain(SlimVertex in, uint primitive : SV_PrimitiveID, float3 bary : SV_Barycentrics,
    bool front : SV_IsFrontFace)
{
    VertexOut s = rebuild(primitive, bary, in.position);
    discardMasked(push.materials[s.material], s, front);
}

