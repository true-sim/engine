// Surface outputs: what the opaque shading pass tells later passes about each pixel's surface, written only where a
// technique asks for them (engine-render's SurfaceOutputs): its normal in view space (octahedral, two 16-bit signed
// values in one word), its perceptual roughness, its specular F0, and what the environment's reflection along its
// mirror direction is multiplied by in its color (the reflectance), so screen-space reflections replace that term
// exactly. View space: x along the image's x (right), y along its y (down, as Vulkan's), z away from the viewer.
module surfaces;

/** A pixel's surface as shading saw it. Normal and positions camera-relative (world axes). */
public struct SurfaceOut
{
    public float3 normal;      // unit
    public float roughness;    // perceptual
    public float3 f0;          // specular reflectance at normal incidence, metals' albedo included
    public float3 reflectance; // what the environment's reflection along reflect(-toViewer, normal) is weighed by
};

/** No surface: a pixel nothing covers, or one the reflections must leave alone (reflectance 0). */
public SurfaceOut noSurface()
{
    SurfaceOut s;
    s.normal = float3(0, 0, 1);
    s.roughness = 1;
    s.f0 = 0;
    s.reflectance = 0;
    return s;
}

/** Unit `n` folded onto the octahedron, in -1 to 1 each. */
public float2 octahedral(float3 n)
{
    n /= abs(n.x) + abs(n.y) + abs(n.z);
    float2 p = n.xy;
    if (n.z < 0)
    {
        p = (1 - abs(n.yx)) * float2(n.x >= 0 ? 1 : -1, n.y >= 0 ? 1 : -1);
    }
    return p;
}

/** The unit vector of octahedral `p`. */
public float3 fromOctahedral(float2 p)
{
    float3 n = float3(p, 1 - abs(p.x) - abs(p.y));
    float t = saturate(-n.z);
    n.x += n.x >= 0 ? -t : t;
    n.y += n.y >= 0 ? -t : t;
    return normalize(n);
}

/** Unit `n` as two 16-bit signed normalized values in one word, x in the low half. */
public uint packNormal(float3 n)
{
    int2 q = int2(round(clamp(octahedral(n), -1.0, 1.0) * 32767.0));
    return (uint(q.x) & 0xffff) | (uint(q.y) << 16);
}

/** The unit vector packNormal packed. */
public float3 unpackNormal(uint word)
{
    int2 q = int2(int(word << 16) >> 16, int(word) >> 16);
    return fromOctahedral(max(float2(q) / 32767.0, -1.0));
}

/**
 * The camera's axes in camera-relative world space, as rows (x: the image's right, y: its down, z: forward), from a
 * perspective view-projection: its third clip row is forward (w grows with distance), the first two the image's axes
 * once the off-center terms along forward are taken out (asymmetric eyes, jitter).
 */
public float3x3 viewAxes(float4x4 viewProjection)
{
    float3 forward = normalize(viewProjection[3].xyz);
    float3 x = viewProjection[0].xyz;
    float3 y = viewProjection[1].xyz;
    x = normalize(x - dot(x, forward) * forward);
    y = normalize(y - dot(y, forward) * forward);
    return float3x3(x, y, forward);
}

/** Camera-relative world direction `n` in view space (`axes`: viewAxes). */
public float3 toView(float3x3 axes, float3 n)
{
    return mul(axes, n);
}

/** View-space direction `n` back in camera-relative world space. */
public float3 fromView(float3x3 axes, float3 n)
{
    return mul(n, axes);
}
