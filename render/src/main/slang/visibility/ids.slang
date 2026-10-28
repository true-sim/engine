// The visibility buffer's fragment stage, for both geometry paths: what a pixel shows, as IDs, no shading. x: the
// instance + 1 (0: nothing); y: the pool meshlet << 7 | the triangle in it (meshlets hold at most 124 triangles).
// The indexed path's primitive IDs count over the whole draw, so its meshlets' first triangle comes along.
import meshlets.common;

[shader("fragment")]
uint2 idsMain(VertexOut in, uint primitive : SV_PrimitiveID) : SV_Target
{
    return uint2(in.instance + 1, (in.meshlet << 7) | (primitive - in.firstTriangle));
}
