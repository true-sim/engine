// The mesh shader path's lean draws, for shaded draws where fragment shaders have barycentrics: vertices carry what
// interpolates (camera-relative position, normal, tangent, texture coordinates), and fragments find what is flat or
// rare from the triangle (its material, instance and meshlet from the visible meshlet; vertex colors, where the mesh
// has them, from its three vertices). A mesh shader's outputs reach fragment shaders through memory on some GPUs: with
// the full VertexOut (some 20 values a vertex) the path took twice as long as the indexed one on an RX 9070 XT, while
// rebuilding whole vertices per pixel instead (tried too) cost more where triangles cover many pixels.
import meshlets.common;

/** As mesh.slang's MeshPush: the renderer sets the one block for both. */
struct LeanPush
{
    View* view;
    MeshEntry* meshes;
    Instance* instances;
    VisibleMeshlet* visible;
    uint* visibleCount;
    uint* meshlets;
    uint* meshletVertices;
    uint* meshletTriangles;
    uint2* positions;
    uint* attributes;
    Material* materials;
    LightListHeader* lights;
    uint bin;
    uint late;
    uint background;
    uint backgroundRegion;
};

[[vk::push_constant]]
ConstantBuffer<LeanPush> push;

/** The scene behind, as the push block names it, for a fragment of instance `instance`. */
Background behind(uint instance)
{
    Background b = { push.background, push.backgroundRegion,
        push.background != 0 ? maxScale(push.instances[instance]) : 1.0 };
    return b;
}

/** Byte i of the pool's meshlet triangles. */
uint triangleByte(uint i)
{
    return (push.meshletTriangles[i >> 2] >> ((i & 3) * 8)) & 0xff;
}

/** A triangle, as its primitive ID: its visible meshlet's slot, then its number in the meshlet (7 bits). */
struct LeanPrimitive
{
    uint id : SV_PrimitiveID;
};

struct LeanVertex
{
    float4 position : SV_Position;
    float3 world : WORLD;
    float3 normal : NORMAL;
    float4 tangent : TANGENT;
    float2 uv : UV;
    float2 uv1 : UV1;
};

[shader("mesh")]
[outputtopology("triangle")]
[numthreads(64, 1, 1)]
void meshLeanMain(uint3 group : SV_GroupID, uint thread : SV_GroupIndex, out vertices LeanVertex vertices[64],
    out indices uint3 triangles[124], out primitives LeanPrimitive primitives[124])
{
    View view = push.view[0];
    uint index = group.y * 65535 + group.x;
    // Past the list's end a workgroup outputs nothing, through the one SetMeshOutputCounts below all the same: with a
    // second call on an early exit, the driver of an AMD Radeon(TM) Graphics drew nothing at all (pitfalls.md).
    bool listed = index < min(push.visibleCount[visibleCountWord(push.bin, push.late)], binDrawCapacity(view, push.bin));
    uint slot = binFirstSlot(view, push.bin) + index;
    VisibleMeshlet v = listed ? push.visible[slot] : (VisibleMeshlet) 0;
    Instance instance = relative(push.instances[v.instance], view);
    MeshEntry mesh = push.meshes[instance.mesh];
    uint w = v.meshlet * 8;
    uint vertexOffset = push.meshlets[w];
    uint triangleOffset = push.meshlets[w + 1];
    uint counts = listed ? push.meshlets[w + 2] : 0;
    uint vertexCount = counts & 0xff;
    uint triangleCount = (counts >> 8) & 0xff;
    SetMeshOutputCounts(vertexCount, triangleCount);
    if (thread < vertexCount)
    {
        uint vertex = mesh.vertexBase + push.meshletVertices[mesh.meshletVertexBase + vertexOffset + thread];
        VertexOut out = transformVertex(view, instance, mesh, vertex, push.positions, push.attributes, v.meshlet,
            counts >> 16);
        vertices[thread].position = out.position;
        vertices[thread].world = out.world;
        vertices[thread].normal = out.normal;
        vertices[thread].tangent = out.tangent;
        vertices[thread].uv = out.uv;
        vertices[thread].uv1 = out.uv1;
    }
    for (uint t = thread; t < triangleCount; t += 64)
    {
        uint b = mesh.indexBase + triangleOffset + t * 3;
        triangles[t] = wound(uint3(triangleByte(b), triangleByte(b + 1), triangleByte(b + 2)), mirrored(instance));
        primitives[t].id = slot << 7 | t;
    }
}

/** The fragment's surface: the interpolated values, and the flat or rare ones from triangle `primitive`. */
VertexOut lean(LeanVertex in, uint primitive, float3 bary)
{
    VisibleMeshlet visible = push.visible[primitive >> 7];
    Instance instance = push.instances[visible.instance];
    MeshEntry mesh = push.meshes[instance.mesh & INSTANCE_MESH]; // the mesh word holds flags too
    uint m = visible.meshlet;
    VertexOut s;
    s.position = in.position;
    s.world = in.world;
    s.normal = in.normal;
    s.tangent = in.tangent;
    s.uv = in.uv;
    s.uv1 = in.uv1;
    s.color = 1;
    if ((mesh.attributeFormat & 16) != 0) // COLOR: from the triangle's vertices
    {
        uint w = m * 8;
        uint first = mesh.indexBase + push.meshlets[w + 1] + (primitive & 0x7f) * 3;
        uint vertexBase = mesh.meshletVertexBase + push.meshlets[w];
        s.color = 0;
        for (uint k = 0; k < 3; k++)
        {
            uint vertex = mesh.vertexBase + push.meshletVertices[vertexBase
                + triangleByte(first + woundCorner(k, mirrored(instance)))]; // barycentrics follow the drawn corners
            uint word = attributeWord(mesh, push.attributes, vertex, 4);
            s.color += bary[k] * float4(word & 0xff, (word >> 8) & 0xff, (word >> 16) & 0xff, word >> 24) / 255.0;
        }
    }
    s.meshlet = m;
    s.material = instance.materialBase + (push.meshlets[m * 8 + 2] >> 16);
    s.instance = visible.instance;
    s.firstTriangle = 0;
    return s;
}

[shader("fragment")]
float4 leanMain(LeanVertex in, uint primitive : SV_PrimitiveID, float3 bary : SV_Barycentrics) : SV_Target
{
    VertexOut s = lean(in, primitive, bary);
    float4 uvs = float4(s.uv, s.uv1);
    return shadeSurface(NoRays(), push.view[0], s, push.materials, ddx(uvs), ddy(uvs), push.lights,
        uint2(in.position.xy), behind(s.instance));
}

/**
 * The blended bin's, into the transparency pass's targets: one-sided materials' back faces discarded, and masked
 * texels (a masked material that is transmissive draws here).
 */
[shader("fragment")]
BlendedOut leanBlendedMain(LeanVertex in, uint primitive : SV_PrimitiveID, float3 bary : SV_Barycentrics,
    bool front : SV_IsFrontFace)
{
    VertexOut s = lean(in, primitive, bary);
    discardMasked(push.materials[s.material], s, front); // masked transmissive materials draw here too
    float4 uvs = float4(s.uv, s.uv1);
    return weighBlended(shadeSurface(NoRays(), push.view[0], s, push.materials, ddx(uvs), ddy(uvs), push.lights,
        uint2(in.position.xy), behind(s.instance)), length(s.world));
}

/** The masked bin's: back faces of one-sided masked materials discarded with the masked texels. */
[shader("fragment")]
float4 leanMaskedMain(LeanVertex in, uint primitive : SV_PrimitiveID, float3 bary : SV_Barycentrics,
    bool front : SV_IsFrontFace) : SV_Target
{
    VertexOut s = lean(in, primitive, bary);
    discardMasked(push.materials[s.material], s, front);
    float4 uvs = float4(s.uv, s.uv1);
    return shadeSurface(NoRays(), push.view[0], s, push.materials, ddx(uvs), ddy(uvs), push.lights,
        uint2(in.position.xy), behind(s.instance));
}
