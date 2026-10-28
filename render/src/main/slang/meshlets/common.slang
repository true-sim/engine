// The GPU side of scene's mesh format (scene#12) and of the renderer's per-frame data: structs as the Java side
// writes them (std430-compatible offsets), and the decoders for quantized data. Everything is reached by pointer.
module common;

/** One mesh in the geometry pool: where its data lies in the pool's buffers, and its quantization box. 96 bytes. */
public struct MeshEntry
{
    public float3 boxMin;
    public uint vertexBase;        // first vertex in the pool's positions (and attribute vertices)
    public float3 boxExtent;
    public uint meshletBase;       // first meshlet in the pool's meshlet and cluster LOD records
    public float4 sphere;          // bounds: center, radius
    public uint meshletCount;
    public uint meshletVertexBase; // first entry in the pool's meshlet vertices
    public uint indexBase;         // first index of the fallback index buffer = first byte of meshlet triangles
    public uint attributeBase;     // byte offset of the mesh's attributes
    public uint attributeFormat;   // scene's VertexFormat mask
    public uint attributeStride;
    public uint levelBase;         // its first entry in the pool's level table
    public uint levelCount;
    public float4 lodSphere;       // around every detail group: the instance pass's distance bound
};

/** One detail level: the meshlets up to `end` may be drawn while its `error` is too coarse to stop at. */
public struct Level
{
    public uint end;
    public float error;
};

/** One drawn object: camera-relative position, rotation, scale and mesh. 48 bytes. */
public struct Instance
{
    public float4 rotation;        // quaternion x, y, z, w
    public float3 position;
    public uint mesh;
    public float3 scale;
    public uint pad;
};

/** The frame's view. The camera sits at the origin: instances are camera-relative. */
public struct View
{
    public float4x4 viewProjection;
    public float4 planes[6];       // frustum planes (normal, distance), normals inward
    public float projectionScale;  // pixels per unit at distance 1
    public float errorPixels;      // the error allowed on screen, in pixels
    public uint instanceCount;
    public uint maxDraws;
    public uint mode;              // 0 lit, 1 meshlet colors, 2 white
};

public float3 rotate(float4 q, float3 v)
{
    float3 t = 2.0 * cross(q.xyz, v);
    return v + q.w * t + cross(q.xyz, t);
}

public float3 toWorld(Instance instance, float3 local)
{
    return instance.position + rotate(instance.rotation, local * instance.scale);
}

/** The largest scale factor: spheres and errors grow by it. */
public float maxScale(Instance instance)
{
    return max(instance.scale.x, max(instance.scale.y, instance.scale.z));
}

/** A position from 3 x unorm16 in the mesh's box. */
public float3 decodePosition(uint2 q, MeshEntry mesh)
{
    float3 unorm = float3(q.x & 0xffff, q.x >> 16, q.y & 0xffff) / 65535.0;
    return mesh.boxMin + unorm * mesh.boxExtent;
}

/** A unit vector from octahedral 2 x snorm16 in one word. */
public float3 decodeOctahedral(uint word)
{
    float2 e = max(float2(int(word << 16) >> 16, int(word) >> 16) / 32767.0, -1.0);
    float3 n = float3(e.x, e.y, 1.0 - abs(e.x) - abs(e.y));
    if (n.z < 0)
    {
        n.xy = (1.0 - abs(n.yx)) * float2(n.x >= 0 ? 1 : -1, n.y >= 0 ? 1 : -1);
    }
    return normalize(n);
}

/** 4 x snorm8 in one word. */
public float4 decodeSnorm8(uint word)
{
    int4 b = int4(int(word << 24) >> 24, int(word << 16) >> 24, int(word << 8) >> 24, int(word) >> 24);
    return max(float4(b) / 127.0, -1.0);
}

/** Whether a sphere is at least partly inside the frustum. */
public bool inFrustum(View view, float3 center, float radius)
{
    for (int i = 0; i < 6; i++)
    {
        if (dot(view.planes[i].xyz, center) + view.planes[i].w < -radius)
        {
            return false;
        }
    }
    return true;
}

/**
 * An error of a group with this sphere, in pixels on screen: the error over the distance to the sphere. Infinite
 * inside the sphere, unless the error is 0.
 */
public float projectedError(View view, float3 center, float radius, float error)
{
    if (error == 0)
    {
        return 0;
    }
    float distance = length(center) - radius;
    return distance > 0 ? error * view.projectionScale / distance : 1.0 / 0.0;
}

// ---------------------------------------------------------------- shared by both geometry paths

/** Up to 64 meshlets of one instance, from local meshlet firstMeshlet: one culling workgroup's work. */
public struct Chunk
{
    public uint instance;
    public uint firstMeshlet;
};

/** A meshlet the indexed path draws: its instance, pool meshlet and first index in the frame's index buffer. */
public struct VisibleMeshlet
{
    public uint instance;
    public uint meshlet;
    public uint firstIndex;
    public uint pad;
};

/** What the vertex or mesh shader hands the fragment shader: the same for both paths. */
public struct VertexOut
{
    public float4 position : SV_Position;
    public float3 normal : NORMAL;
    public nointerpolation uint meshlet : MESHLET;
};

/**
 * Whether meshlet m (a pool meshlet) of an instance is drawn: its detail level is the right one for its distance
 * (own group's error at most the allowed pixels, parent's more), it is in the frustum and does not face away.
 */
public bool meshletVisible(View view, Instance instance, uint m, uint* meshlets, float* clusterLod)
{
    float scale = maxScale(instance);
    uint l = m * 10;
    float3 own = toWorld(instance, float3(clusterLod[l], clusterLod[l + 1], clusterLod[l + 2]));
    float3 parent = toWorld(instance, float3(clusterLod[l + 5], clusterLod[l + 6], clusterLod[l + 7]));
    float ownError = projectedError(view, own, clusterLod[l + 3] * scale, clusterLod[l + 4] * scale);
    float parentError = projectedError(view, parent, clusterLod[l + 8] * scale, clusterLod[l + 9] * scale);
    if (!(ownError <= view.errorPixels && parentError > view.errorPixels))
    {
        return false;
    }
    uint w = m * 8;
    float3 center = toWorld(instance, asfloat(uint3(meshlets[w + 3], meshlets[w + 4], meshlets[w + 5])));
    float radius = asfloat(meshlets[w + 6]) * scale;
    if (!inFrustum(view, center, radius))
    {
        return false;
    }
    float4 cone = decodeSnorm8(meshlets[w + 7]);
    return cone.w >= 1 || dot(center, rotate(instance.rotation, cone.xyz)) < cone.w * length(center) + radius;
}

/** Pool vertex `vertex` of an instance, transformed and with its normal: the vertex stage of both paths. */
public VertexOut transformVertex(View view, Instance instance, MeshEntry mesh, uint vertex, uint2* positions,
    uint* attributes, uint meshlet)
{
    float3 world = toWorld(instance, decodePosition(positions[vertex], mesh));
    VertexOut out;
    out.position = mul(view.viewProjection, float4(world, 1.0));
    out.normal = float3(0, 1, 0);
    if ((mesh.attributeFormat & 1) != 0) // NORMAL is the first attribute when present
    {
        uint word = attributes[(mesh.attributeBase + (vertex - mesh.vertexBase) * mesh.attributeStride) / 4];
        out.normal = rotate(instance.rotation, decodeOctahedral(word));
    }
    out.meshlet = meshlet;
    return out;
}

/** A color per meshlet, to see the clusters. */
public float3 meshletColor(uint meshlet)
{
    uint h = meshlet * 2654435761u;
    return float3((h >> 8) & 0xff, (h >> 16) & 0xff, (h >> 24) & 0xff) / 255.0 * 0.8 + 0.2;
}

/** The surface color: the fragment stage of both paths. */
public float4 shadeSurface(View view, VertexOut in)
{
    if (view.mode == 2)
    {
        return float4(1, 1, 1, 1);
    }
    float3 base = view.mode == 1 ? meshletColor(in.meshlet) : float3(0.8, 0.8, 0.8);
    float light = 0.25 + 0.75 * saturate(dot(normalize(in.normal), normalize(float3(0.4, 0.8, 0.3))));
    return float4(base * light, 1);
}
