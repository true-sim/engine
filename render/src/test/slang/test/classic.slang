// The classic way, for comparison: whole meshes as indexed draws, one per instance, vertices pulled from the pool
// (the draw's vertex offset makes the index a pool vertex, its first instance is the instance). No meshlets.
import meshlets.common;

struct ClassicPush
{
    View* view;
    MeshEntry* meshes;
    Instance* instances;
    uint2* positions;
    uint* attributes;
    Material* materials;
};

[[vk::push_constant]]
ConstantBuffer<ClassicPush> push;

[shader("vertex")]
VertexOut vertexMain(uint vertex : SV_VulkanVertexID, uint instanceIndex : SV_VulkanInstanceID)
{
    Instance instance = push.instances[instanceIndex];
    return transformVertex(push.view[0], instance, push.meshes[instance.mesh], vertex, push.positions,
        push.attributes, 0, 0);
}

[shader("fragment")]
float4 fragmentMain(VertexOut in) : SV_Target
{
    return shadeSurface(push.view[0], in, push.materials, ddx(in.uv), ddy(in.uv), nullptr, uint2(0, 0));
}
