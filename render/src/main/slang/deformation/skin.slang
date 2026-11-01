// Deformation: an instance's copy of a mesh posed by its joints (GeometryPool.addDeformed, Deformation). skinMain, a
// thread per vertex, reads the source mesh's rest position, normal, tangent, joints and weights, blends the joints'
// matrices by the weights (linear blend skinning) and writes the copy's position, quantized into the box the CPU
// bounded the pose by, and its normal and tangent (normals by the blended matrix's cofactors, so scaling keeps them
// perpendicular). Its first thread writes the copy's mesh entry: box, sphere and the detail sphere grown by how far
// the joints move. spheresMain, a thread per meshlet, after: each meshlet's sphere around its posed vertices, cone
// culling off, and the cluster LOD spheres grown alike, so detail selection keeps the rest pose's order.
import meshlets.common;

struct DeformPush
{
    MeshEntry* meshes;
    uint2* positions;
    uint* attributes;
    uint* meshlets;
    uint* meshletVertices;
    float* clusterLod;
    float* matrices;           // the copy's joints, 12 floats each: rows of a 3x4 matrix, in the mesh's space
    uint source;               // the mesh at rest
    uint target;               // its copy
    uint vertices;             // how many vertices it has (skinMain) or meshlets (spheresMain)
    float growth;              // the farthest any joint moves a point of its box
    float3 boxMin;             // the posed mesh's box
    float3 boxExtent;
};

[[vk::push_constant]]
ConstantBuffer<DeformPush> push;

/** Joint `j`'s matrix. */
float3x4 joint(uint j)
{
    float* m = push.matrices + 12 * j;
    return float3x4(m[0], m[1], m[2], m[3], m[4], m[5], m[6], m[7], m[8], m[9], m[10], m[11]);
}

/** Octahedral 2 x snorm16 of a unit vector, the first value's low bit `low`. */
uint encodeOctahedral(float3 n, uint low)
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

/** The word index of attribute `bit` of mesh vertex `v` (counted from the mesh's first). */
uint attributeIndex(MeshEntry mesh, uint v, uint bit)
{
    uint offset = countbits(mesh.attributeFormat & ((1u << bit) - 1)) * 4;
    return (mesh.attributeBase + v * mesh.attributeStride + offset) / 4;
}

[shader("compute")]
[numthreads(64, 1, 1)]
void skinMain(uint3 id : SV_DispatchThreadID)
{
    MeshEntry* meshes = push.meshes;
    MeshEntry source = meshes[push.source];
    uint targetVertexBase = meshes[push.target].vertexBase;
    float3 boxMin = push.boxMin;
    float3 boxExtent = push.boxExtent;
    if (id.x == 0)
    {
        float3 center = boxMin + 0.5 * boxExtent;
        meshes[push.target].boxMin = boxMin;
        meshes[push.target].boxExtent = boxExtent;
        meshes[push.target].sphere = float4(center, 0.5 * length(boxExtent));
        meshes[push.target].lodSphere = float4(source.lodSphere.xyz, source.lodSphere.w + push.growth);
    }
    uint v = id.x;
    if (v >= push.vertices)
    {
        return;
    }
    uint* attributes = push.attributes;
    uint2* positions = push.positions;
    uint format = source.attributeFormat;
    uint joints = attributes[attributeIndex(source, v, 5)];
    float4 weights = float4(attributes[attributeIndex(source, v, 6)] >> uint4(0, 8, 16, 24) & 0xff) / 255.0;
    float3x4 m = weights.x * joint(joints & 0xff) + weights.y * joint(joints >> 8 & 0xff)
        + weights.z * joint(joints >> 16 & 0xff) + weights.w * joint(joints >> 24);
    float3 rest = decodePosition(positions[source.vertexBase + v], source);
    float3 posed = mul(m, float4(rest, 1.0));
    float3 extent = max(boxExtent, 1e-20);
    uint3 q = uint3(saturate((posed - boxMin) / extent) * 65535.0 + 0.5);
    positions[targetVertexBase + v] = uint2(q.x | (q.y << 16), q.z);
    // The copy's attributes lie as the source's, from its own base.
    MeshEntry target = source;
    target.attributeBase = meshes[push.target].attributeBase;
    float3x3 linear = float3x3(m[0].xyz, m[1].xyz, m[2].xyz);
    float3 normal = float3(0, 0, 1);
    if ((format & 1) != 0)
    {
        // The cofactor matrix: the inverse transpose up to scale, which normalizing removes
        float3x3 cofactor = float3x3(cross(linear[1], linear[2]), cross(linear[2], linear[0]),
            cross(linear[0], linear[1]));
        normal = normalize(mul(transpose(cofactor), decodeOctahedral(attributes[attributeIndex(source, v, 0)])));
        attributes[attributeIndex(target, v, 0)] = encodeOctahedral(normal, 0);
    }
    if ((format & 2) != 0)
    {
        uint word = attributes[attributeIndex(source, v, 1)];
        float3 tangent = mul(linear, decodeOctahedral(word));
        if ((format & 1) != 0)
        {
            tangent -= normal * dot(normal, tangent);
        }
        attributes[attributeIndex(target, v, 1)] = encodeOctahedral(normalize(tangent), word & 1);
    }
}

[shader("compute")]
[numthreads(64, 1, 1)]
void spheresMain(uint3 id : SV_DispatchThreadID)
{
    uint m = id.x;
    if (m >= push.vertices)
    {
        return;
    }
    MeshEntry* meshes = push.meshes;
    MeshEntry target = meshes[push.target];
    uint sourceMeshletBase = meshes[push.source].meshletBase;
    uint* meshlets = push.meshlets;
    uint* meshletVertices = push.meshletVertices;
    uint2* positions = push.positions;
    uint w = (target.meshletBase + m) * 8;
    uint first = target.meshletVertexBase + meshlets[w];
    uint count = meshlets[w + 2] & 0xff;
    float3 low = 1e30;
    float3 high = -1e30;
    for (uint i = 0; i < count; i++)
    {
        float3 p = decodePosition(positions[target.vertexBase + meshletVertices[first + i]], target);
        low = min(low, p);
        high = max(high, p);
    }
    float3 center = 0.5 * (low + high);
    float radius = 0;
    for (uint i = 0; i < count; i++)
    {
        float3 p = decodePosition(positions[target.vertexBase + meshletVertices[first + i]], target);
        radius = max(radius, length(p - center));
    }
    meshlets[w + 3] = asuint(center.x);
    meshlets[w + 4] = asuint(center.y);
    meshlets[w + 5] = asuint(center.z);
    meshlets[w + 6] = asuint(radius);
    meshlets[w + 7] = (meshlets[w + 7] & 0x00ffffff) | (127u << 24); // cutoff 1: no cone culling
    float* clusterLod = push.clusterLod;
    uint to = (target.meshletBase + m) * 10;
    uint from = (sourceMeshletBase + m) * 10;
    clusterLod[to + 3] = clusterLod[from + 3] + push.growth;
    clusterLod[to + 8] = clusterLod[from + 8] + push.growth;
}
