// Draws the meshlets the cull pass chose, as indexed triangles: the vertex shader pulls and decodes each vertex from
// the geometry pool by pointer (the draw's vertex offset makes the index a pool vertex), the instance comes as the draw's
// first instance and the meshlet by draw index.
import meshlets.common;

struct DrawPush
{
    View* view;
    MeshEntry* meshes;
    Instance* instances;
    uint2* positions;   // 8 bytes per vertex
    uint* attributes;   // the meshes' attribute bytes, as words
    uint* drawMeshlets;
};

[[vk::push_constant]]
ConstantBuffer<DrawPush> push;

struct VertexOut
{
    float4 position : SV_Position;
    float3 normal : NORMAL;
    nointerpolation uint meshlet : MESHLET;
};

[shader("vertex")]
VertexOut vertexMain(uint vertex : SV_VulkanVertexID, uint instanceIndex : SV_VulkanInstanceID,
    uint draw : SV_DrawIndex)
{
    Instance instance = push.instances[instanceIndex];
    MeshEntry mesh = push.meshes[instance.mesh];
    float3 world = toWorld(instance, decodePosition(push.positions[vertex], mesh));
    VertexOut out;
    out.position = mul(push.view[0].viewProjection, float4(world, 1.0));
    out.normal = float3(0, 1, 0);
    if ((mesh.attributeFormat & 1) != 0) // NORMAL is the first attribute when present
    {
        uint word = push.attributes[(mesh.attributeBase + (vertex - mesh.vertexBase) * mesh.attributeStride) / 4];
        out.normal = rotate(instance.rotation, decodeOctahedral(word));
    }
    out.meshlet = push.drawMeshlets[draw];
    return out;
}

/** A color per meshlet, to see the clusters. */
float3 meshletColor(uint meshlet)
{
    uint h = meshlet * 2654435761u;
    return float3((h >> 8) & 0xff, (h >> 16) & 0xff, (h >> 24) & 0xff) / 255.0 * 0.8 + 0.2;
}

[shader("fragment")]
float4 fragmentMain(VertexOut in) : SV_Target
{
    uint mode = push.view[0].mode;
    if (mode == 2)
    {
        return float4(1, 1, 1, 1);
    }
    float3 base = mode == 1 ? meshletColor(in.meshlet) : float3(0.8, 0.8, 0.8);
    float light = 0.25 + 0.75 * saturate(dot(normalize(in.normal), normalize(float3(0.4, 0.8, 0.3))));
    return float4(base * light, 1);
}
