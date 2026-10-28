// The indexed path's draw: all visible meshlets' triangles in one indexed draw. Each index is a visible meshlet's slot
// and its local vertex (slot << 8 | local); the vertex shader finds the instance, the meshlet and the pool vertex from
// it. The fragment shader serves the mesh shader path too.
import meshlets.common;

struct DrawPush
{
    View* view;
    MeshEntry* meshes;
    Instance* instances;
    uint2* positions;         // 8 bytes per vertex
    uint* attributes;         // the meshes' attribute bytes, as words
    VisibleMeshlet* visible;
    uint* meshlets;
    uint* meshletVertices;
};

[[vk::push_constant]]
ConstantBuffer<DrawPush> push;

[shader("vertex")]
VertexOut vertexMain(uint index : SV_VulkanVertexID)
{
    VisibleMeshlet v = push.visible[index >> 8];
    Instance instance = push.instances[v.instance];
    MeshEntry mesh = push.meshes[instance.mesh];
    uint vertexOffset = push.meshlets[v.meshlet * 8];
    uint vertex = mesh.vertexBase + push.meshletVertices[mesh.meshletVertexBase + vertexOffset + (index & 0xff)];
    return transformVertex(push.view[0], instance, mesh, vertex, push.positions, push.attributes, v.meshlet);
}

[shader("fragment")]
float4 fragmentMain(VertexOut in) : SV_Target
{
    return shadeSurface(push.view[0], in);
}
