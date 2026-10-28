// The fallback path's draw: indexed triangles of the meshlets the cull pass chose. The vertex shader pulls each vertex
// from the pool (the draw's vertex offset makes the index a pool vertex), the instance comes as the draw's first
// instance and the meshlet by draw index. The fragment shader serves the mesh shader path too.
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

[shader("vertex")]
VertexOut vertexMain(uint vertex : SV_VulkanVertexID, uint instanceIndex : SV_VulkanInstanceID,
    uint draw : SV_DrawIndex)
{
    Instance instance = push.instances[instanceIndex];
    return transformVertex(push.view[0], instance, push.meshes[instance.mesh], vertex, push.positions,
        push.attributes, push.drawMeshlets[draw]);
}

[shader("fragment")]
float4 fragmentMain(VertexOut in) : SV_Target
{
    return shadeSurface(push.view[0], in);
}
