// The classic LOD path's vertex stage: indexed draws of whole parts of a level. The vertex index comes with the mesh's
// first vertex added (the draw's vertex offset), and the instance index is the draw slot, which leads to the draw's
// record. Shading is the meshlet paths' fragment stage (meshlets/draw.slang's fragmentMain), whose push block this one
// matches: the materials at byte 80 and the light list at 88.
import classic.parts;

struct ClassicPush
{
    View* view;
    MeshEntry* meshes;
    Instance* instances;
    uint2* positions;
    uint* attributes;
    ClassicDraw* records;
    uint64_t unused[4];
    Material* materials;
    LightListHeader* lights;
};

[[vk::push_constant]]
ConstantBuffer<ClassicPush> push;

[shader("vertex")]
VertexOut classicVertexMain(uint vertex : SV_VulkanVertexID, uint slot : SV_VulkanInstanceID)
{
    ClassicDraw draw = push.records[slot];
    Instance instance = push.instances[draw.instance];
    MeshEntry mesh = push.meshes[instance.mesh];
    VertexOut out = transformVertex(push.view[0], instance, mesh, vertex, push.positions, push.attributes,
        draw.meshlet, (draw.tag >> 24) & 0x7f);
    out.instance = draw.instance;
    out.firstTriangle = draw.tag; // for the visibility buffer: its triangles count on from the part's tag
    return out;
}
