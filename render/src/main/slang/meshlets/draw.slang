// The indexed path's draw: all visible meshlets' triangles in one indexed draw. Each index is a visible meshlet's slot
// and its local vertex (slot << 8 | local); the vertex shader finds the instance, the meshlet and the pool vertex from
// it. Shaded fragments are the material types' generated stages (forward.slang's typed*), on the mesh shader path too.
// The push block (forward.slang's ForwardPush) is forward.slang's.
import meshlets.forward;

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
    out.firstTriangle = drawFirstTriangle(v); // primitive IDs count from it
    return out;
}

/** The masked bin's depth only (shadow maps, depth prepasses): the same texels masked as the color pass's. */
[shader("fragment")]
void maskedDepthMain(VertexOut in, bool front : SV_IsFrontFace)
{
    discardMasked(push.scene[0].materials, in, front);
}

/**
 * The masked bin's shadow casters (shadow atlas tiles): the masked texels discarded, back faces kept whatever the
 * material, as shadow rays meet every face.
 */
[shader("fragment")]
void maskedShadowMain(VertexOut in)
{
    discardMasked(push.scene[0].materials, in, true);
}
