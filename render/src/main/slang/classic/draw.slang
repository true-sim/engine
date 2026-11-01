// The classic LOD path's vertex stage: indexed draws of whole parts of a level. The vertex index comes with the mesh's
// first vertex added (the draw's vertex offset), and the instance index is the draw slot, which leads to the draw's
// record. Shading is the meshlet paths' fragment stage (meshlets/draw.slang's fragmentMain), whose push block this one
// matches: the materials at byte 80 and the light list at 88.
import classic.parts;

struct ClassicPush
{
    View* view;               // the shaded fields at the same bytes in every raster block: draw.slang's fragment
    SceneTable* scene;        // shader serves them all (common.slang's SceneTable: the frame's scene buffers)
    LightListHeader* lights;  // at 16: the light list, or null: the sun
    IndirectLight* indirect;  // at 24: the view's indirect-light table (indirect.slang), or null: the constant sky
    ClassicDraw* records;
    uint64_t unused;
    uint unusedBin;
    uint unusedLate;
    uint background;          // at 56: the scene behind, for transmission (common.slang's Background); 0: none
    uint backgroundRegion;
};

[[vk::push_constant]]
ConstantBuffer<ClassicPush> push;

[shader("vertex")]
VertexOut classicVertexMain(uint vertex : SV_VulkanVertexID, uint slot : SV_VulkanInstanceID)
{
    ClassicDraw draw = push.records[slot];
    Instance instance = relative(push.scene[0].instances[draw.instance], push.view[0]);
    MeshEntry mesh = push.scene[0].meshes[instance.mesh];
    VertexOut out = transformVertex(push.view[0], instance, mesh, vertex, push.scene[0].positions,
        push.scene[0].attributes, draw.meshlet, (draw.tag >> 24) & 0x7f);
    out.instance = draw.instance;
    out.firstTriangle = draw.tag; // for the visibility buffer: its triangles count on from the part's tag
    return out;
}
