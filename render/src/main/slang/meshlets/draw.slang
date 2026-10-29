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
    uint64_t unused[2];
    Material* materials;      // at byte 80 and the light list at 88, as in mesh.slang's push block: the fragment
    LightListHeader* lights;  // shader serves both paths (null: no light list, the sun)
};

[[vk::push_constant]]
ConstantBuffer<DrawPush> push;

[shader("vertex")]
VertexOut vertexMain(uint index : SV_VulkanVertexID)
{
    VisibleMeshlet v = push.visible[index >> 8];
    Instance instance = relative(push.instances[v.instance], push.view[0]);
    MeshEntry mesh = push.meshes[instance.mesh];
    uint vertexOffset = push.meshlets[v.meshlet * 8];
    uint vertex = mesh.vertexBase + push.meshletVertices[mesh.meshletVertexBase + vertexOffset + (index & 0xff)];
    VertexOut out = transformVertex(push.view[0], instance, mesh, vertex, push.positions, push.attributes, v.meshlet,
        meshletSubmesh(push.meshlets, v.meshlet));
    out.instance = v.instance;
    out.firstTriangle = v.firstIndex / 3;
    return out;
}

[shader("fragment")]
float4 fragmentMain(VertexOut in) : SV_Target
{
    return shadeSurface(NoRays(), push.view[0], in, push.materials, ddx(in.uv), ddy(in.uv), push.lights,
        uint2(in.position.xy));
}

/** The masked bin's: drawn without culling, so one-sided materials' back faces go here with the masked texels. */
[shader("fragment")]
float4 maskedMain(VertexOut in, bool front : SV_IsFrontFace) : SV_Target
{
    discardMasked(push.materials[in.material], in, front);
    return shadeSurface(NoRays(), push.view[0], in, push.materials, ddx(in.uv), ddy(in.uv), push.lights,
        uint2(in.position.xy));
}

/** The masked bin's depth only (shadow maps, depth prepasses): the same texels masked as the color pass's. */
[shader("fragment")]
void maskedDepthMain(VertexOut in, bool front : SV_IsFrontFace)
{
    discardMasked(push.materials[in.material], in, front);
}
