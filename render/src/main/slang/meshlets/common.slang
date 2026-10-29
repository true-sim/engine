// The GPU side of scene's mesh format (scene#12) and of the renderer's per-frame data: structs as the Java side
// writes them (std430-compatible offsets), and the decoders for quantized data. Everything is reached by pointer.
module common;

import bindless;
__exported import materials.metallicRoughness;
__exported import materials.clearcoat;
__exported import lights;

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

/**
 * One drawn object: world position, rotation, scale and mesh. 64 bytes. The position is two floats per axis, a high
 * part and the rest (position + positionLow), about 48 bits: precise anywhere in a large world. Loaded through
 * relative(), which makes it relative to a view's camera; everything after works camera-relative, in floats.
 */
public struct Instance
{
    public float4 rotation;        // quaternion x, y, z, w
    public float3 position;        // world position, high part; after relative(): camera-relative
    public uint mesh;              // the mesh in the low 24 bits, the flags (INSTANCE_*) above; after relative(): the mesh
    public float3 scale;
    public uint materialBase;      // the material of submesh s is materialBase + s
    public float3 positionLow;     // world position, the rest; after relative(): 0
    public uint layers;            // the layers it is on: drawn in views of any of them
};

/** Instance flags, in the mesh word's top 8 bits. */
public static const uint INSTANCE_LIVE = 1u << 24;          // 0: a hole, an id not in use
public static const uint INSTANCE_CASTS_SHADOWS = 1u << 25;
public static const uint INSTANCE_MESH = 0xffffffu;

/** View flags. */
public static const uint VIEW_CASTERS_ONLY = 1;             // shadow views: instances casting shadows only

/** Whether stored instance `instance` (as stored, before relative()) is drawn in `view`: live, on its layers. */
public bool drawnIn(Instance instance, View view)
{
    return (instance.mesh & INSTANCE_LIVE) != 0 && (instance.layers & view.layers) != 0
        && ((view.flags & VIEW_CASTERS_ONLY) == 0 || (instance.mesh & INSTANCE_CASTS_SHADOWS) != 0);
}

/**
 * A view of the frame: a camera, with its world position (two floats per axis, as instances') and the matrices from
 * positions relative to it. A view may look from an eye off that position (a light's shadow map from a camera's
 * position): the cone test and level selection use the eye.
 */
public struct View
{
    public float4x4 viewProjection;
    public float4 planes[6];       // frustum planes (normal, distance), normals inward
    public float projectionScale;  // pixels per unit at distance 1
    public float errorPixels;      // the error allowed on screen, in pixels
    public uint instanceCount;
    public uint maxDraws;
    public uint mode;              // 0 lit, 1 meshlet colors, 2 white
    public uint orthographic;      // 1: projectionScale is pixels per unit at any distance (e.g. a sun's shadow map)
    public uint maxIndices;        // the indexed path's index buffer holds this many (-1: mesh shaders, no buffer)
    public uint binDraws;          // room for this many meshlets in each raster bin but the first (BIN_*)
    public float4x4 inverseViewProjection; // clip space back to camera-relative positions
    public float3 position;        // the camera's world position, high part
    public uint pad1;
    public float3 positionLow;     // and the rest
    public uint layers;            // the layers it draws
    public float3 eye;             // where the view looks from, relative to its position (0 but for shadow views)
    public uint flags;             // VIEW_*
    public ViewLists lists;        // where culling lists what the view draws
    public uint* visibility;       // per instance: 1 if the view saw it the last time its late phase looked; or null
    public uint binIndices;        // the indexed path's room in each raster bin but the first, after maxIndices
    public uint pad3;              // 352 bytes in all
};

/**
 * Raster bins: how meshlets rasterize, by their material. Each has its own lists and draw: the first is ordinary
 * opaque and one-sided (back faces culled, no discard: the fastest), then double-sided opaque (no culling), then
 * alpha-masked (no culling; back faces of one-sided masked materials discarded with the masked texels).
 */
public static const uint BINS = 3;
public static const uint BIN_OPAQUE = 0;
public static const uint BIN_DOUBLE_SIDED = 1;
public static const uint BIN_MASKED = 2;

/** The raster bin of a meshlet of material `m`. */
public uint rasterBin(Material m)
{
    return materialMasked(m) ? BIN_MASKED : materialDoubleSided(m) ? BIN_DOUBLE_SIDED : BIN_OPAQUE;
}

/** The first slot of `bin` in `view`'s visible list. */
public uint binFirstSlot(View view, uint bin)
{
    return bin == 0 ? 0 : view.maxDraws + (bin - 1) * view.binDraws;
}

/** How many meshlets `bin` has room for in `view`'s visible list. */
public uint binDrawCapacity(View view, uint bin)
{
    return bin == 0 ? view.maxDraws : view.binDraws;
}

/** The first index of `bin` in `view`'s index buffer (the indexed path's). */
public uint binFirstIndex(View view, uint bin)
{
    return bin == 0 ? 0 : view.maxIndices + (bin - 1) * view.binIndices;
}

/** How many indices `bin` has room for (-1: mesh shaders, no index buffer). */
public uint binIndexCapacity(View view, uint bin)
{
    return bin == 0 ? view.maxIndices : view.binIndices;
}

/** A view's place in a renderer's table of views. */
public struct ViewRef
{
    public View* view;
};

/**
 * `instance` relative to `view`'s camera: its world position minus the camera's, the high parts and the rest apart,
 * so the difference keeps what floats near the camera can hold.
 */
public Instance relative(Instance instance, View view)
{
    precise float3 high = instance.position - view.position;
    precise float3 low = instance.positionLow - view.positionLow;
    Instance r = instance;
    r.position = high + low;
    r.positionLow = float3(0);
    r.mesh = instance.mesh & INSTANCE_MESH;
    return r;
}

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

/** A float16 in the low 16 bits, by integer arithmetic: no 16-bit float support needed on the device. */
public float decodeHalf(uint h)
{
    uint sign = (h & 0x8000) << 16;
    uint exponent = (h >> 10) & 0x1f;
    uint mantissa = h & 0x3ff;
    if (exponent == 0) // zero and subnormals
    {
        return asfloat(sign) + (sign != 0 ? -1.0 : 1.0) * float(mantissa) * 5.9604645e-8;
    }
    if (exponent == 31) // infinities and NaN
    {
        return asfloat(sign | 0x7f800000 | (mantissa << 13));
    }
    return asfloat(sign | ((exponent + 112) << 23) | (mantissa << 13));
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
    if (view.orthographic != 0)
    {
        return error * view.projectionScale;
    }
    float distance = length(center - view.eye) - radius;
    return distance > 0 ? error * view.projectionScale / distance : 1.0 / 0.0;
}

// ---------------------------------------------------------------- occlusion culling

/** The depth pyramid the late phase of two-phase occlusion culling tests against. */
public struct Occlusion
{
    public uint pyramid;      // sampled slot of the depth pyramid (DepthPyramid: farthest depth, reverse)
    public uint depthWidth;   // the depth image's size: the pyramid's level 0 is half of it, rounded up
    public uint depthHeight;
    public uint mips;
};

public static const uint PHASE_ALL = 0;
public static const uint PHASE_EARLY = 1;
public static const uint PHASE_LATE = 2;

/** A view the instance pass culls, one per row of workgroups: which, and its phase (PHASE_ALL, _EARLY, _LATE). */
public struct CulledView
{
    public uint view;
    public uint phase;
};

/**
 * Whether a sphere (camera-relative) may be seen past what the depth pyramid holds. Its box is projected; at the
 * pyramid level where the box covers at most 2 x 2 texels, it is hidden if its nearest depth is farther than all of
 * them. Texels are found by what they cover (level 0: 2 x 2 depth texels; higher levels: 2 x 2 of the level below,
 * the last of an odd row 3), so the test never hides anything visible. A box reaching behind the camera is visible.
 */
public bool unoccluded(View view, Occlusion hzb, float3 center, float radius)
{
    float2 lo = 1.0e9;
    float2 hi = -1.0e9;
    float nearest = 0; // reverse depth: the nearest point has the largest depth
    for (int i = 0; i < 8; i++)
    {
        float3 corner = center + radius * float3((i & 1) != 0 ? 1 : -1, (i & 2) != 0 ? 1 : -1, (i & 4) != 0 ? 1 : -1);
        float4 clip = mul(view.viewProjection, float4(corner, 1.0));
        if (clip.w <= 1.0e-5)
        {
            return true;
        }
        float3 ndc = clip.xyz / clip.w;
        lo = min(lo, ndc.xy);
        hi = max(hi, ndc.xy);
        nearest = max(nearest, ndc.z);
    }
    // In depth texels, then in level-0 pyramid texels (2 x 2 depth texels each).
    float2 depthSize = float2(hzb.depthWidth, hzb.depthHeight);
    float2 from = saturate(lo * 0.5 + 0.5) * depthSize * 0.5;
    float2 to = saturate(hi * 0.5 + 0.5) * depthSize * 0.5;
    float extent = max(to.x - from.x, to.y - from.y);
    uint level = min(hzb.mips - 1, uint(ceil(log2(max(extent, 1.0)))));
    int2 levelSize = int2(max(uint2((hzb.depthWidth + 1) / 2, (hzb.depthHeight + 1) / 2) >> level, uint2(1, 1)));
    int2 a = min(int2(from / float(1u << level)), levelSize - 1);
    int2 b = min(int2(to / float(1u << level)), levelSize - 1);
    float farthest = 1;
    for (int y = a.y; y <= b.y; y++)
    {
        for (int x = a.x; x <= b.x; x++)
        {
            farthest = min(farthest, textures[hzb.pyramid].Load(int3(x, y, int(level))).r);
        }
    }
    return nearest >= farthest;
}

// ---------------------------------------------------------------- shared by both geometry paths

/** Up to 64 meshlets of one instance for one view, from a local meshlet: one culling workgroup's work. */
public struct Chunk
{
    public uint instance;
    public uint firstMeshlet;  // the local meshlet
    public uint view;
    public uint phase;         // the view's, as the instance pass culled it
};

/** A meshlet the indexed path draws: its instance, pool meshlet and first index in the frame's index buffer. */
public struct VisibleMeshlet
{
    public uint instance;
    public uint meshlet;
    public uint firstIndex;
    public uint pad;
};

/** Where a view's culling puts what it finds: each view has its own lists, so views cull together. */
public struct ViewLists
{
    public VisibleMeshlet* visible;
    public uint* expandCommand;  // per raster bin, 4 words: VkDispatchIndirectCommand (the mesh dispatch too)
    public uint* drawCommand;    // per bin, 6 words: VkDrawIndexedIndirectCommand, its index count growing; word 5
                                 // counts the indices reserved
    public uint* visibleCount;   // per bin the early (or only) phase, then the late one (words 2 * bin + phase); then
                                 // per bin the most indices a phase wanted (words 6 + bin)
};

/**
 * The top bit of a visibility buffer triangle: a classic LOD draw's (ClassicLodGeometry), then the submesh << 24 and the
 * triangle in the mesh; without it, a meshlet's: the pool meshlet << 7 and the triangle in it.
 */
public static const uint CLASSIC_TRIANGLE = 0x80000000u;

/** What the vertex or mesh shader hands the fragment shader: the same for both paths. */
public struct VertexOut
{
    public float4 position : SV_Position;
    public float3 world : WORLD;       // camera-relative
    public float3 normal : NORMAL;
    public float4 tangent : TANGENT;   // w: the bitangent's sign; 0 without tangents
    public float2 uv : UV;
    public float4 color : COLOR;
    public nointerpolation uint meshlet : MESHLET;
    public nointerpolation uint material : MATERIAL;
    public nointerpolation uint instance : INSTANCE;
    public nointerpolation uint firstTriangle : FIRST_TRIANGLE; // the indexed path's: primitive IDs count from it
};

/**
 * Whether meshlet m (a pool meshlet) of an instance is drawn: its detail level is the right one for its distance
 * (own group's error at most the allowed pixels, parent's more), it is in the frustum and does not face away.
 */
public bool meshletVisible(View view, Instance instance, uint m, uint* meshlets, float* clusterLod,
    bool doubleSided)
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
    // Back faces as seen from the eye; orthographic views (shadow maps) keep them.
    float3 fromEye = center - view.eye;
    return doubleSided || view.orthographic != 0 || cone.w >= 1
        || dot(fromEye, rotate(instance.rotation, cone.xyz)) < cone.w * length(fromEye) + radius;
}

/** The submesh of pool meshlet `m`: its material is the instance's material base plus this. */
public uint meshletSubmesh(uint* meshlets, uint m)
{
    return meshlets[m * 8 + 2] >> 16;
}

/** The word of vertex attribute `bit` (scene's VertexAttribute order) of a mesh vertex: attributes lie in order. */
uint attributeWord(MeshEntry mesh, uint* attributes, uint vertex, uint bit)
{
    uint offset = countbits(mesh.attributeFormat & ((1u << bit) - 1)) * 4;
    return attributes[(mesh.attributeBase + (vertex - mesh.vertexBase) * mesh.attributeStride + offset) / 4];
}

/** Pool vertex `vertex` of an instance, transformed, with its attributes: the vertex stage of both paths. */
public VertexOut transformVertex(View view, Instance instance, MeshEntry mesh, uint vertex, uint2* positions,
    uint* attributes, uint meshlet, uint submesh)
{
    float3 world = toWorld(instance, decodePosition(positions[vertex], mesh));
    VertexOut out;
    out.position = mul(view.viewProjection, float4(world, 1.0));
    out.world = world;
    out.normal = rotate(instance.rotation, float3(0, 1, 0));
    out.tangent = 0;
    out.uv = 0;
    out.color = 1;
    uint format = mesh.attributeFormat;
    if ((format & 1) != 0) // NORMAL: octahedral
    {
        out.normal = rotate(instance.rotation, decodeOctahedral(attributeWord(mesh, attributes, vertex, 0)));
    }
    if ((format & 2) != 0) // TANGENT: octahedral, the bitangent's sign in the lowest bit
    {
        uint word = attributeWord(mesh, attributes, vertex, 1);
        out.tangent = float4(rotate(instance.rotation, decodeOctahedral(word)), (word & 1) != 0 ? -1.0 : 1.0);
    }
    if ((format & 4) != 0) // UV0: 2 x float16
    {
        uint word = attributeWord(mesh, attributes, vertex, 2);
        out.uv = float2(decodeHalf(word & 0xffff), decodeHalf(word >> 16));
    }
    if ((format & 16) != 0) // COLOR: 4 x unorm8
    {
        uint word = attributeWord(mesh, attributes, vertex, 4);
        out.color = float4(word & 0xff, (word >> 8) & 0xff, (word >> 16) & 0xff, word >> 24) / 255.0;
    }
    out.meshlet = meshlet;
    out.material = instance.materialBase + submesh;
    out.instance = 0;
    out.firstTriangle = 0;
    return out;
}

/** A color per meshlet, to see the clusters. */
public float3 meshletColor(uint meshlet)
{
    uint h = meshlet * 2654435761u;
    return float3((h >> 8) & 0xff, (h >> 16) & 0xff, (h >> 24) & 0xff) / 255.0 * 0.8 + 0.2;
}

/** Towards the sun, the scene's one light: (0.4, 0.8, 0.3) normalized, as engine-render's Keys.SUN. */
public static const float3 SUN = float3(0.42399915, 0.84799830, 0.31799936);

/** The sun's irradiance and the sky's, linear: a surface facing the sun gets 0.75 of its albedo, any one 0.25. */
public static const float3 SUN_IRRADIANCE = float3(2.35619449);
public static const float3 SKY_IRRADIANCE = float3(0.78539816 / 1.02);

/**
 * The surface color at `pixel`, with texture coordinate gradients `dx`, `dy` (per pixel): the fragment stage of both
 * paths, and of anything shading the same surfaces. Lit by the lights `lights` gives it (the frame's light list) and
 * the sky or, without a light list (null), by the sun and the sky. Shadow rays are traced by `rays` (NoRays: none).
 */
/**
 * Discards what a masked material does not draw at this fragment: its masked texels, and back faces unless it is
 * double-sided (the masked bin draws without culling). Every pass rasterizing the masked bin calls it, shadows too.
 */
public void discardMasked(Material m, VertexOut in, bool front)
{
    if ((!front && !materialDoubleSided(m)) || alphaClipped(m, in.uv, ddx(in.uv), ddy(in.uv), in.color))
    {
        discard;
    }
}

public float4 shadeSurface<R : IShadowRays>(R rays, View view, VertexOut in, Material* materials, float2 dx,
    float2 dy, LightListHeader* lights, uint2 pixel)
{
    Material m = materials[in.material];
    if (materialType(m) != 0)
    {
        // Forward shading draws only metallic-roughness for now: other types as its defaults.
        return shadeMaterial<DefaultSurface, R>(rays, view, in, materials, dx, dy, lights, pixel,
            materialFeatures(m));
    }
    return shadeMaterial<MetallicRoughnessMaterial, R>(rays, view, in, materials, dx, dy, lights, pixel,
        materialFeatures(m));
}

/** Metallic-roughness's defaults, whatever the material's parameters: for types a pass cannot shade yet. */
struct DefaultSurface : IMaterial
{
    typealias Parameters = NoParameters;

    static Surface surface(NoParameters p, MaterialRef m, SurfaceInput in)
    {
        MetallicRoughnessParameters defaults = {};
        return evaluate(defaults, in);
    }
};

/**
 * shadeSurface with the surface from material type M, showing the features of `features` (FEATURE_*; where it is a
 * constant, the code of the others is left out): the lighting is the same for every type.
 */
public float4 shadeMaterial<M : IMaterial, R : IShadowRays>(R rays, View view, VertexOut in, Material* materials,
    float2 dx, float2 dy, LightListHeader* lights, uint2 pixel, uint features)
{
    if (view.mode == 2)
    {
        return float4(1, 1, 1, 1);
    }
    MaterialRef ref = { materials, in.material };
    Material m = ref.slot();
    float3 toViewer = normalize(view.eye - in.world);
    float3 normal = in.normal;
    float4 tangent = in.tangent;
    if (materialDoubleSided(m) && dot(normal, toViewer) < 0)
    {
        normal = -normal; // a back face: lit as the front
        tangent.w = -tangent.w;
    }
    SurfaceInput input;
    input.world = in.world;
    input.toViewer = toViewer;
    input.normal = normal;
    input.tangent = tangent;
    input.uv = in.uv;
    input.dx = dx;
    input.dy = dy;
    input.color = in.color;
    input.features = features;
    Surface s = M.surface(materialParameters<M.Parameters>(ref), ref, input);
    if (view.mode == 1)
    {
        s.albedo = meshletColor(in.meshlet) * in.color.rgb;
    }
    // The features, the engine's: on any type's surface.
    s.clearcoat = 0;
    float3 emissive = s.emissive;
    if ((features & FEATURE_CLEARCOAT) != 0)
    {
        applyClearcoat(s, materialFeature<ClearcoatParameters>(ref, FEATURE_CLEARCOAT), input);
        emissive *= 1 - s.clearcoat * coatFresnel(dot(s.clearcoatNormal, toViewer)); // seen through the coat
    }
    float3 color = ambient(s, SKY_IRRADIANCE) + emissive;
    if (lights == nullptr)
    {
        color += reflected(s, toViewer, SUN, SUN_IRRADIANCE);
    }
    else
    {
        float depth = mul(view.viewProjection, float4(in.world, 1)).w; // along the view direction
        color += lightList(rays, lights, s, in.world, toViewer, pixel, depth);
    }
    return float4(color, 1);
}
