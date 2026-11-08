// Displacement into copies (Displacement.COMPUTE; engine-render's Deformation): once a frame, after morph targets and
// skinning, every displaced copy's vertices are moved by its material type's displace(), so every pass draws the copy
// as it is and ray tracing's bottom level (refitted after) sees it. Batched over every displaced copy: a table of the
// copies (DisplacedCopy) and lists of chunks (a copy and its first vertex or meshlet, 64 a workgroup), so the work is a
// few dispatches a frame however many copies there are:
//   ownersMain   once per source mesh: each vertex's submesh (the lowest of the meshlets using it), so a thread per
//                vertex finds its material;
//   restMain     the copies not skinned: the source's vertices copied in, quantized into the copy's box (grown by its
//                displacement), the kept vertices of the frame before saved first (motion vectors); its mesh entry;
//   <type>DisplaceMain (material-types/entryDisplaceCompute): a thread per vertex of the type's materials, in place;
//   spheresMain  each meshlet's sphere around its displaced vertices, cone culling off, detail spheres grown.
module displace;

__exported import meshlets.common;

/** A displaced copy, as Deformation writes the frame's table. 64 bytes. */
[HostLayout]
public struct DisplacedCopy
{
    public float3 boxMin;          // the box its positions are quantized in, grown by its displacement
    public uint target;            // its pool mesh
    public float3 boxExtent;
    public uint source;            // the mesh it copies
    public uint instance;          // the instance it is displaced for
    public uint skinned;           // 1: the skinning posed it this frame (in place); 0: copied from its source here
    public uint owners;            // its source's first word in the owners buffer
    public uint previous;          // its first kept vertex (motion vectors); 0xffffffff: none kept this frame
    public float growth;           // how far its detail spheres grow
    public uint vertices;
    public uint meshlets;
    public uint pad;
};

public struct DisplacePush
{
    public MeshEntry* meshes;
    public uint2* positions;
    public uint* attributes;
    public uint* meshlets;
    public uint* meshletVertices;
    public float* clusterLod;
    public Instance* instances;
    public Material* materials;
    public ShadingHookBlock* hooks;   // the scene's hook block (GpuScene.geometryHooks), this frame's
    public uint* owners;              // per source vertex its submesh
    public uint2* previous;           // the copies' kept vertices (Deformation.keepPrevious)
    public DisplacedCopy* copies;
    public uint2* chunks;             // (copy, first vertex or meshlet)
    public uint chunkCount;
    public uint type;                 // the table's id of the type a <type>DisplaceMain displaces
};

[[vk::push_constant]]
public ConstantBuffer<DisplacePush> push;

/** Octahedral 2 x snorm16 of a unit vector, the first value's low bit `low` (as skin.slang's). */
public uint encodeOctahedral(float3 n, uint low)
{
    n /= max(abs(n.x) + abs(n.y) + abs(n.z), 1e-20);
    float2 e = n.xy;
    if (n.z < 0)
    {
        e = (1.0 - abs(n.yx)) * float2(n.x >= 0 ? 1 : -1, n.y >= 0 ? 1 : -1);
    }
    int2 q = int2(round(clamp(e, -1.0, 1.0) * 32767.0));
    return ((uint(q.x) & 0xfffe) | low) | (uint(q.y) << 16);
}

/** The word index of attribute `bit` of vertex `v` (counted from the mesh's first) of `mesh`. */
public uint attributeIndex(MeshEntry mesh, uint v, uint bit)
{
    uint offset = countbits(mesh.attributeFormat & ((1u << bit) - 1)) * 4;
    return (mesh.attributeBase + v * mesh.attributeStride + offset) / 4;
}

/** A position quantized into a box: 3 x unorm16. */
public uint2 quantize(float3 p, float3 boxMin, float3 boxExtent)
{
    uint3 q = uint3(saturate((p - boxMin) / max(boxExtent, 1e-20)) * 65535.0 + 0.5);
    return uint2(q.x | (q.y << 16), q.z);
}

/** The chunk of workgroup `group` (rows of 65,535) and the item (vertex or meshlet) of its thread `thread`, or false past the end. */
public bool chunkItem(uint group, uint thread, out DisplacedCopy copy, out uint item, uint itemsOf)
{
    item = 0;
    copy = (DisplacedCopy) 0;
    if (group >= push.chunkCount)
    {
        return false;
    }
    uint2 chunk = push.chunks[group];
    copy = push.copies[chunk.x];
    item = chunk.y + thread;
    return item < (itemsOf == 0 ? copy.vertices : copy.meshlets);
}

/**
 * Displaces vertex `v` of displaced copy `copy` with type M (table id push.type), where its submesh's material is of that
 * type and displaces: the copy's position (posed in place this frame, or its source's) and normal moved by
 * M's displace(), as the vertex stages of RASTER move them.
 */
public void displaceCopyVertex<M : IMaterial>(DisplacedCopy copy, uint v)
{
    Instance stored = push.instances[copy.instance];
    uint submesh = push.owners[copy.owners + v];
    MaterialRef ref = { push.materials, stored.materialBase + submesh };
    Material m = ref.slot();
    if (materialType(m) != push.type || !materialDisplaces(m))
    {
        return;
    }
    MeshEntry target = push.meshes[copy.target];
    uint vertex = target.vertexBase + v;
    // Posed in place by the skinning, or straight from the source (restMain left it)
    MeshEntry source = push.meshes[copy.source];
    LocalVertex local = copy.skinned != 0 ? localVertex(target, vertex, push.positions, push.attributes)
        : localVertex(source, source.vertexBase + v, push.positions, push.attributes);
    LocalVertex moved = displacedVertex<M>(local, stored, copy.instance, ref, push.hooks);
    push.positions[vertex] = quantize(moved.position, copy.boxMin, copy.boxExtent);
    uint format = target.attributeFormat;
    if ((format & 1) != 0)
    {
        push.attributes[attributeIndex(target, v, 0)] = encodeOctahedral(normalize(moved.normal), 0);
    }
    if ((format & 2) != 0 && dot(moved.tangent.xyz, moved.tangent.xyz) > 0)
    {
        uint at = attributeIndex(target, v, 1);
        push.attributes[at] = encodeOctahedral(normalize(moved.tangent.xyz), moved.tangent.w < 0 ? 1u : 0u);
    }
}
