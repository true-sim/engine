// The indexed path's draw: all visible meshlets' triangles in one indexed draw. Each index is a visible meshlet's slot
// and its local vertex (slot << 8 | local); the vertex shader finds the instance, the meshlet and the pool vertex from
// it. The fragment shader serves the mesh shader path too.
import meshlets.common;

struct DrawPush
{
    View* view;               // the shaded fields at the same bytes in every raster block: draw.slang's fragment
    SceneTable* scene;        // shader serves them all (common.slang's SceneTable: the frame's scene buffers)
    LightListHeader* lights;  // at 16: the light list, or null: the sun
    IndirectLight* indirect;  // at 24: the view's indirect-light table (indirect.slang), or null: the constant sky
    VisibleMeshlet* visible;
    uint64_t unused;
    uint unusedBin;
    uint unusedLate;
    uint background;          // at 56: the scene behind, for transmission (common.slang's Background); 0: none
    uint backgroundRegion;
};

[[vk::push_constant]]
ConstantBuffer<DrawPush> push;

/** The scene behind, as the push block names it, for a fragment of instance `instance`. */
Background behind(uint instance)
{
    Background b = { push.background, push.backgroundRegion,
        push.background != 0 ? maxScale(push.scene[0].instances[instance]) : 1.0 };
    return b;
}

[shader("vertex")]
VertexOut vertexMain(uint index : SV_VulkanVertexID)
{
    VisibleMeshlet v = push.visible[index >> 8];
    Instance instance = relative(push.scene[0].instances[v.instance], push.view[0]);
    MeshEntry mesh = push.scene[0].meshes[instance.mesh];
    uint vertexOffset = push.scene[0].meshlets[v.meshlet * 8];
    uint vertex = mesh.vertexBase
        + push.scene[0].meshletVertices[mesh.meshletVertexBase + vertexOffset + (index & 0xff)];
    VertexOut out = transformVertex(push.view[0], instance, mesh, vertex, push.scene[0].positions,
        push.scene[0].attributes, v.meshlet,
        meshletSubmesh(push.scene[0].meshlets, v.meshlet));
    out.instance = v.instance;
    out.firstTriangle = v.firstIndex / 3;
    return out;
}

[shader("fragment")]
float4 fragmentMain(VertexOut in) : SV_Target
{
    float4 uvs = float4(in.uv, in.uv1);
    return shadeSurface(NoRays(), push.view[0], in, push.scene[0].materials, ddx(uvs), ddy(uvs), push.lights,
        uint2(in.position.xy), behind(in.instance), push.indirect);
}

/**
 * The blended bin's, into the transparency pass's targets: one-sided materials' back faces discarded, and masked
 * texels (a masked material that is transmissive draws here).
 */
[shader("fragment")]
BlendedOut blendedMain(VertexOut in, bool front : SV_IsFrontFace)
{
    discardMasked(push.scene[0].materials[in.material], in, front); // masked transmissive materials draw here too
    float4 uvs = float4(in.uv, in.uv1);
    return weighBlended(shadeSurface(NoRays(), push.view[0], in, push.scene[0].materials, ddx(uvs), ddy(uvs),
        push.lights,
        uint2(in.position.xy), behind(in.instance), push.indirect), length(in.world));
}

/** The masked bin's: drawn without culling, so one-sided materials' back faces go here with the masked texels. */
[shader("fragment")]
float4 maskedMain(VertexOut in, bool front : SV_IsFrontFace) : SV_Target
{
    discardMasked(push.scene[0].materials[in.material], in, front);
    float4 uvs = float4(in.uv, in.uv1);
    return shadeSurface(NoRays(), push.view[0], in, push.scene[0].materials, ddx(uvs), ddy(uvs), push.lights,
        uint2(in.position.xy), behind(in.instance), push.indirect);
}

/** The masked bin's depth only (shadow maps, depth prepasses): the same texels masked as the color pass's. */
[shader("fragment")]
void maskedDepthMain(VertexOut in, bool front : SV_IsFrontFace)
{
    discardMasked(push.scene[0].materials[in.material], in, front);
}
