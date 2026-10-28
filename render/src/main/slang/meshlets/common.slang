// The GPU side of scene's mesh format (scene#12) and of the renderer's per-frame data: structs as the Java side
// writes them (std430-compatible offsets), and the decoders for quantized data. Everything is reached by pointer.
module common;

/** One mesh in the geometry pool: where its data lies in the pool's buffers, and its quantization box. 80 bytes. */
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
    public uint pad0;
    public uint pad1;
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
