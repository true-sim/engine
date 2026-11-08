// The type-agnostic passes of displacement into copies (deformation/displace.slang): ownersMain, restMain and
// spheresMain, each over the frame's chunk list of every displaced copy.
import deformation.displace;

/** Each vertex of a source mesh's meshlets takes the lowest submesh using it: a thread per meshlet (chunks of them). */
[shader("compute")]
[numthreads(64, 1, 1)]
void ownersMain(uint3 group : SV_GroupID, uint3 thread : SV_GroupThreadID)
{
    DisplacedCopy copy;
    uint local;
    if (!chunkItem(group.y * 65535 + group.x, thread.x, copy, local, 1))
    {
        return;
    }
    MeshEntry source = push.meshes[copy.source];
    uint w = (source.meshletBase + local) * 8;
    uint first = source.meshletVertexBase + push.meshlets[w];
    uint counts = push.meshlets[w + 2];
    uint submesh = counts >> 16;
    for (uint i = 0; i < (counts & 0xff); i++)
    {
        InterlockedMin(push.owners[copy.owners + push.meshletVertices[first + i]], submesh);
    }
}

/**
 * The copies not skinned: their source's vertices (position, normal, tangent) copied in, quantized into the copy's box,
 * what the frame before drew kept first where motion vectors keep it; the first thread of a copy writes its mesh entry.
 */
[shader("compute")]
[numthreads(64, 1, 1)]
void restMain(uint3 group : SV_GroupID, uint3 thread : SV_GroupThreadID)
{
    DisplacedCopy copy;
    uint v;
    bool inside = chunkItem(group.y * 65535 + group.x, thread.x, copy, v, 0);
    if (!inside)
    {
        return;
    }
    MeshEntry source = push.meshes[copy.source];
    if (v == 0)
    {
        push.meshes[copy.target].boxMin = copy.boxMin;
        push.meshes[copy.target].boxExtent = copy.boxExtent;
        push.meshes[copy.target].sphere = float4(copy.boxMin + 0.5 * copy.boxExtent, 0.5 * length(copy.boxExtent));
        push.meshes[copy.target].lodSphere = float4(source.lodSphere.xyz, source.lodSphere.w + copy.growth);
    }
    uint targetVertexBase = push.meshes[copy.target].vertexBase;
    if (copy.previous != 0xffffffffu)
    {
        push.previous[copy.previous + v] = push.positions[targetVertexBase + v];
    }
    if (copy.skinned != 0)
    {
        return; // posed in place by the skinning
    }
    float3 p = decodePosition(push.positions[source.vertexBase + v], source);
    push.positions[targetVertexBase + v] = quantize(p, copy.boxMin, copy.boxExtent);
    MeshEntry target = source;
    target.attributeBase = push.meshes[copy.target].attributeBase;
    uint format = source.attributeFormat;
    if ((format & 1) != 0)
    {
        push.attributes[attributeIndex(target, v, 0)] = push.attributes[attributeIndex(source, v, 0)];
    }
    if ((format & 2) != 0)
    {
        push.attributes[attributeIndex(target, v, 1)] = push.attributes[attributeIndex(source, v, 1)];
    }
}

/** Each meshlet's sphere around its displaced vertices, cone culling off, the detail spheres grown. */
[shader("compute")]
[numthreads(64, 1, 1)]
void spheresMain(uint3 group : SV_GroupID, uint3 thread : SV_GroupThreadID)
{
    DisplacedCopy copy;
    uint m;
    if (!chunkItem(group.y * 65535 + group.x, thread.x, copy, m, 1))
    {
        return;
    }
    MeshEntry target = push.meshes[copy.target];
    uint sourceMeshletBase = push.meshes[copy.source].meshletBase;
    uint w = (target.meshletBase + m) * 8;
    uint first = target.meshletVertexBase + push.meshlets[w];
    uint count = push.meshlets[w + 2] & 0xff;
    float3 low = 1e30;
    float3 high = -1e30;
    for (uint i = 0; i < count; i++)
    {
        float3 p = decodePosition(push.positions[target.vertexBase + push.meshletVertices[first + i]], target);
        low = min(low, p);
        high = max(high, p);
    }
    float3 center = 0.5 * (low + high);
    float radius = 0;
    for (uint i = 0; i < count; i++)
    {
        float3 p = decodePosition(push.positions[target.vertexBase + push.meshletVertices[first + i]], target);
        radius = max(radius, length(p - center));
    }
    push.meshlets[w + 3] = asuint(center.x);
    push.meshlets[w + 4] = asuint(center.y);
    push.meshlets[w + 5] = asuint(center.z);
    push.meshlets[w + 6] = asuint(radius);
    push.meshlets[w + 7] = (push.meshlets[w + 7] & 0x00ffffff) | (127u << 24); // cutoff 1: no cone culling
    uint to = (target.meshletBase + m) * 10;
    uint from = (sourceMeshletBase + m) * 10;
    push.clusterLod[to + 3] = push.clusterLod[from + 3] + copy.growth;
    push.clusterLod[to + 8] = push.clusterLod[from + 8] + copy.growth;
}
