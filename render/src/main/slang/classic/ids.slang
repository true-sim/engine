// The classic LOD path's visibility buffer stage: x the instance + 1, y the part's tag plus the triangle in the draw,
// so CLASSIC_TRIANGLE | submesh << 24 | the triangle in the mesh, which the resolve rebuilds from the path's indices.
import classic.parts;

struct ClassicIdsPush
{
    View* view;
    SceneTable* scene;     // at byte 8, as the path's draw push block has it
};

[[vk::push_constant]]
ConstantBuffer<ClassicIdsPush> push;

[shader("fragment")]
uint2 classicIdsMain(VertexOut in, uint primitive : SV_PrimitiveID) : SV_Target
{
    return uint2(in.instance + 1, in.firstTriangle + primitive);
}

/** The masked bin's: its masked texels and one-sided back faces show nothing, as in every pass. */
[shader("fragment")]
uint2 maskedClassicIdsMain(VertexOut in, uint primitive : SV_PrimitiveID, bool front : SV_IsFrontFace) : SV_Target
{
    discardMasked(push.scene[0].materials[in.material], in, front);
    return uint2(in.instance + 1, in.firstTriangle + primitive);
}
