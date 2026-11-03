// The mesh shader path's lean draws, for shaded draws (each shading bin's, by its type's generated fragment stages) where fragment shaders have barycentrics: vertices carry what
// interpolates (camera-relative position, normal, tangent, texture coordinates), and fragments find what is flat or
// rare from the triangle (its material, instance and meshlet from the visible meshlet; vertex colors, where the mesh
// has them, from its three vertices). A mesh shader's outputs reach fragment shaders through memory on some GPUs: with
// the full VertexOut (some 20 values a vertex) the path took twice as long as the indexed one on an RX 9070 XT, while
// rebuilding whole vertices per pixel instead (tried too) cost more where triangles cover many pixels.
// The push block, the vertices and lean() are forward.slang's, shared with the fragment stages generated per material
// type.
import meshlets.forward;

/** A typed draw's: the meshlets of one shading bin of the raster bin, through the split list (split.slang). */
[shader("mesh")]
[outputtopology("triangle")]
[numthreads(64, 1, 1)]
void meshLeanTypedMain(uint3 group : SV_GroupID, uint thread : SV_GroupIndex, out vertices LeanVertex vertices[64],
    out indices uint3 triangles[124], out primitives LeanPrimitive primitives[124])
{
    View view = push.view[0];
    uint* triangleBytes = push.scene[0].meshletTriangles; // loaded once, read per triangle
    // Past the list's end a workgroup outputs nothing, through the one SetMeshOutputCounts below all the same: with a
    // second call on an early exit, the driver of an AMD Radeon(TM) Graphics drew nothing at all (pitfalls.md).
    ListedMeshlet found = listedTypedMeshlet(view, group.y * 65535 + group.x);
    bool listed = found.listed;
    uint slot = found.slot;
    VisibleMeshlet v = listed ? push.visible[slot] : (VisibleMeshlet) 0;
    Instance instance = relative(push.scene[0].instances[v.instance], view);
    MeshEntry mesh = push.scene[0].meshes[instance.mesh];
    uint w = v.meshlet * 8;
    uint vertexOffset = push.scene[0].meshlets[w];
    uint triangleOffset = push.scene[0].meshlets[w + 1];
    uint counts = listed ? push.scene[0].meshlets[w + 2] : 0;
    uint vertexCount = counts & 0xff;
    uint triangleCount = (counts >> 8) & 0xff;
    SetMeshOutputCounts(vertexCount, triangleCount);
    if (thread < vertexCount)
    {
        uint vertex = mesh.vertexBase
            + push.scene[0].meshletVertices[mesh.meshletVertexBase + vertexOffset + thread];
        VertexOut out = transformVertex(view, instance, mesh, vertex, push.scene[0].positions,
            push.scene[0].attributes, v.meshlet,
            counts >> 16);
        vertices[thread].position = out.position;
        vertices[thread].world = out.world;
        vertices[thread].normal = out.normal;
        vertices[thread].tangent = out.tangent;
        vertices[thread].uv = out.uv;
        vertices[thread].uv1 = out.uv1;
    }
    for (uint t = thread; t < triangleCount; t += 64)
    {
        uint b = mesh.indexBase + triangleOffset + t * 3;
        triangles[t] = wound(uint3(triangleByte(triangleBytes, b), triangleByte(triangleBytes, b + 1), triangleByte(triangleBytes, b + 2)), mirrored(instance));
        primitives[t].id = slot << 7 | t;
    }
}
