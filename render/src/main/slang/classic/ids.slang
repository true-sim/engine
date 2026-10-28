// The classic LOD path's visibility buffer stage: x the instance + 1, y the part's tag plus the triangle in the draw,
// so CLASSIC_TRIANGLE | submesh << 24 | the triangle in the mesh, which the resolve rebuilds from the path's indices.
import classic.parts;

[shader("fragment")]
uint2 classicIdsMain(VertexOut in, uint primitive : SV_PrimitiveID) : SV_Target
{
    return uint2(in.instance + 1, in.firstTriangle + primitive);
}
