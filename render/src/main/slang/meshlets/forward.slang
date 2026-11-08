// Forward shading's draws, shared by the single shader branching on the material's type (lean.slang, draw.slang) and
// the entry points generated per material type (material-types/entryForward*.slang.template, #67): the push block
// every shaded raster pipeline takes, the lean path's vertices and how fragments find their triangle, and the
// fragment stages generic over a type. A type's entries shade its own surface with only its bin's features (FEATURES,
// a specialization constant), drawn by an indirect draw per (raster bin, shading bin) that split.slang lists, so no
// shader branches on the type.
module forward;

__exported import meshlets.common;

/**
 * The push block of shaded raster pipelines; mesh.slang's MeshPush and draw.slang's DrawPush keep the same bytes for
 * the fields they share, so one fragment shader serves every geometry stage.
 */
public struct ForwardPush
{
    public View* view;
    public SceneTable* scene;        // common.slang's SceneTable: the frame's scene buffers
    public LightListHeader* lights;  // at 16: the light list, or absent: the sun
    public IndirectLight* indirect;  // at 24: the view's indirect-light table (indirect.slang), or absent: the constant sky
    public VisibleMeshlet* visible;
    public uint* visibleCount;
    public uint bin;                 // the raster bin drawn
    public uint late;                // 1: the late phase's meshlets
    public uint background;          // at 56: the scene behind, for transmission (common.slang's Background); 0: none
    public uint backgroundRegion;
    public uint* typed;              // at 64: the view's lists split by shading bin (split.slang); typed draws only
    public uint category;            // the shading bin drawn, in typed draws
    public uint categories;          // shading bins the split lists have room for
};

[[vk::push_constant]]
public ConstantBuffer<ForwardPush> push;

/** The features a type's forward variant shows (FEATURE_*): its shading bin's, set when its pipeline is made. */
[vk::constant_id(0)]
public const uint FEATURES = 0;

/**
 * The geometry hooks the type has (GEOMETRY_*, its parameters' attribute's), set when its pipeline is made: hooks it
 * does not name never run, in any pass, so every pass agrees on its surfaces.
 */
[vk::constant_id(1)]
public const uint GEOMETRY = 0;

// ---------------------------------------------------------------- the split lists (split.slang writes them)

/** A listed meshlet of a mesh workgroup: whether there is one, and its slot in the view's visible list. */
public struct ListedMeshlet
{
    public bool listed;
    public uint slot;
};

/** Mesh workgroup `index`'s meshlet of the drawn raster bin: its slot in the bin's part of the visible list. */
public ListedMeshlet listedMeshlet(View view, uint index)
{
    ListedMeshlet m;
    m.listed = index < min(push.visibleCount[visibleCountWord(push.bin, push.late)], binDrawCapacity(view, push.bin));
    m.slot = binFirstSlot(view, push.bin) + index;
    return m;
}

/** Like listedMeshlet, in a typed draw: workgroup `index` of the drawn shading bin's part, through the split list. */
public ListedMeshlet listedTypedMeshlet(View view, uint index)
{
    uint entry = splitEntry(push.bin, push.category, push.categories);
    ListedMeshlet m;
    m.listed = index < push.typed[entry + SPLIT_COUNT];
    uint at = splitList(push.categories) + binFirstSlot(view, push.bin) + push.typed[entry + SPLIT_BASE]
        + (m.listed ? index : 0);
    m.slot = push.typed[at];
    return m;
}

/**
 * The indexed path's first triangle of visible meshlet `v` in the draw drawing it, which primitive IDs count from: in
 * the raster bin's draw, from the bin's first index; in a typed draw (lists split by shading bin or type,
 * push.categories set), from its part's first, as each part draws from its own range (split.slang).
 */
public uint drawFirstTriangle(VisibleMeshlet v)
{
    uint first = v.firstIndex; // in the bin
    if (push.categories != 0)
    {
        first -= push.typed[splitEntry(push.bin, push.category, push.categories) + SPLIT_INDEX_BASE];
    }
    return first / 3;
}

// ---------------------------------------------------------------- the lean path's fragments

/** The scene behind, as the push block names it, for a fragment of instance `instance`. */
public Background behind(uint instance)
{
    Background b = { push.background, push.backgroundRegion,
        push.background != 0 ? maxScale(push.scene[0].instances[instance]) : 1.0 };
    return b;
}

/** Byte i of the pool's meshlet triangles. */
public uint triangleByte(uint* triangles, uint i)
{
    return (triangles[i >> 2] >> ((i & 3) * 8)) & 0xff;
}

/** A triangle, as its primitive ID: its visible meshlet's slot, then its number in the meshlet (7 bits). */
public struct LeanPrimitive
{
    public uint id : SV_PrimitiveID;
};

/** What the lean path's vertices carry: what interpolates. */
public struct LeanVertex
{
    public float4 position : SV_Position;
    public float3 world : WORLD;
    public float3 normal : NORMAL;
    public float4 tangent : TANGENT;
    public float2 uv : UV;
    public float2 uv1 : UV1;
};

/** The fragment's surface: the interpolated values, and the flat or rare ones from triangle `primitive`. */
public VertexOut lean(LeanVertex in, uint primitive, float3 bary)
{
    VisibleMeshlet visible = push.visible[primitive >> 7];
    uint* triangleBytes = push.scene[0].meshletTriangles; // loaded once, read per triangle
    Instance instance = push.scene[0].instances[visible.instance];
    MeshEntry mesh = push.scene[0].meshes[instance.mesh & INSTANCE_MESH]; // the mesh word holds flags too
    uint m = visible.meshlet;
    VertexOut s;
    s.position = in.position;
    s.world = in.world;
    s.normal = in.normal;
    s.tangent = in.tangent;
    s.uv = in.uv;
    s.uv1 = in.uv1;
    s.color = 1;
    if ((mesh.attributeFormat & 16) != 0) // COLOR: from the triangle's vertices
    {
        uint w = m * 8;
        uint first = mesh.indexBase + push.scene[0].meshlets[w + 1] + (primitive & 0x7f) * 3;
        uint vertexBase = mesh.meshletVertexBase + push.scene[0].meshlets[w];
        s.color = 0;
        for (uint k = 0; k < 3; k++)
        {
            uint vertex = mesh.vertexBase + push.scene[0].meshletVertices[vertexBase
                + triangleByte(triangleBytes, first + woundCorner(k, mirrored(instance)))]; // as the drawn corners
            uint word = attributeWord(mesh, push.scene[0].attributes, vertex, 4);
            s.color += bary[k] * float4(word & 0xff, (word >> 8) & 0xff, (word >> 16) & 0xff, word >> 24) / 255.0;
        }
    }
    s.meshlet = m;
    s.material = instance.materialBase + (push.scene[0].meshlets[m * 8 + 2] >> 16);
    s.instance = visible.instance;
    s.firstTriangle = 0;
    return s;
}

// ---------------------------------------------------------------- a material type's fragment stages

/**
 * Where pixel `pixel` of instance `instance` is shaded, for the view's hooks: its block, a rasterized pixel, the
 * instance's override entry.
 */
public HookSite rasterSite(uint instance, uint2 pixel)
{
    return HookSite.ofInstance(push.view[0].hooks, pixel, SHADING_RASTER, instance);
}

/** Surface `s` at `pixel` shaded with material type M and the variant's FEATURES; its holes (GEOMETRY_KEEP) left out. */
public float4 shadeTyped<M : IMaterial>(VertexOut s, uint2 pixel)
{
    float4 uvs = float4(s.uv, s.uv1);
    discardHoles<M>(s, ddx(uvs), ddy(uvs));
    return shadeMaterial<M>(push.view[0], s, push.scene[0].materials, ddx(uvs), ddy(uvs),
        push.lights, pixel, FEATURES, behind(s.instance), push.indirect, rasterSite(s.instance, pixel));
}

/** A fragment's color and its surface outputs (surfaces.slang), for passes a technique asked them of. */
public struct ShadedSurface
{
    public float4 color : SV_Target0;
    public uint normal : SV_Target1;      // view space, packNormal's
    public float4 material : SV_Target2;  // F0, perceptual roughness
    public float4 reflectance : SV_Target3;
    public float4 diffuse : SV_Target4;   // the diffuse weight
};

/** `color` with its `surface` as the targets take them, the normal in the view's space. */
public ShadedSurface surfaceTargets(float4 color, SurfaceOut surface)
{
    ShadedSurface out;
    out.color = color;
    out.normal = packNormal(toView(viewAxes(push.view[0].viewProjection), surface.normal));
    out.material = float4(saturate(surface.f0), saturate(surface.roughness));
    out.reflectance = float4(saturate(surface.reflectance), 1);
    out.diffuse = float4(saturate(surface.diffuse), 1);
    return out;
}

/** shadeTyped, with the surface outputs. */
public ShadedSurface shadeTypedSurface<M : IMaterial>(VertexOut s, uint2 pixel)
{
    float4 uvs = float4(s.uv, s.uv1);
    discardHoles<M>(s, ddx(uvs), ddy(uvs));
    SurfaceOut surface;
    float4 color = shadeMaterialSurface<M>(push.view[0], s, push.scene[0].materials, ddx(uvs),
        ddy(uvs), push.lights, pixel, FEATURES, behind(s.instance), push.indirect, rasterSite(s.instance, pixel),
        surface);
    return surfaceTargets(color, surface);
}

/** typedFragment with the surface outputs. */
public ShadedSurface typedFragmentSurface<M : IMaterial>(VertexOut in)
{
    return shadeTypedSurface<M>(in, uint2(in.position.xy));
}

/** typedMasked with the surface outputs. */
public ShadedSurface typedMaskedSurface<M : IMaterial>(VertexOut in, bool front)
{
    discardMasked(push.scene[0].materials, in, front);
    return shadeTypedSurface<M>(in, uint2(in.position.xy));
}

/** typedLean with the surface outputs. */
public ShadedSurface typedLeanSurface<M : IMaterial>(LeanVertex in, uint primitive, float3 bary)
{
    return shadeTypedSurface<M>(lean(in, primitive, bary), uint2(in.position.xy));
}

/** typedLeanMasked with the surface outputs. */
public ShadedSurface typedLeanMaskedSurface<M : IMaterial>(LeanVertex in, uint primitive, float3 bary, bool front)
{
    VertexOut s = lean(in, primitive, bary);
    discardMasked(push.scene[0].materials, s, front);
    return shadeTypedSurface<M>(s, uint2(in.position.xy));
}

/** Discards the fragment of `s` where type M's keep() makes a hole, if the type has the hook (GEOMETRY). */
public void discardHoles<M : IMaterial>(VertexOut s, float4 dx, float4 dy)
{
    if ((GEOMETRY & GEOMETRY_KEEP) != 0 && !kept<M>(push.view[0], s, push.scene[0].materials, dx, dy))
    {
        discard;
    }
}

// ---------------------------------------------------------------- a displacing type's vertex and mesh stages

/**
 * Pool vertex `vertex` of stored instance `stored` (id `id`; `instance` it relative to `view`) of `mesh`, meshlet
 * `meshlet` of submesh `submesh`: moved by type M's displace() where its material displaces and the instance is not a
 * copy displaced already (GEOMETRY_DISPLACE, RASTER), then transformed as transformVertex does. The vertex stages of a
 * displacing type's pipelines (material-types/entryDisplace.slang.template), on every path and in every pass.
 */
public VertexOut typedVertex<M : IMaterial>(View view, Instance stored, Instance instance, uint id, MeshEntry mesh,
    uint vertex, uint meshlet, uint submesh)
{
    LocalVertex local = localVertex(mesh, vertex, push.scene[0].positions, push.scene[0].attributes);
    MaterialRef ref = { push.scene[0].materials, instance.materialBase + submesh };
    if ((GEOMETRY & GEOMETRY_DISPLACE) != 0 && displacedInStages(stored, ref.slot()))
    {
        local = displacedVertex<M>(local, stored, id, ref, push.scene[0].hooks);
    }
    VertexOut out = transformLocal(view, instance, local, meshlet, submesh);
    out.instance = id;
    return out;
}

/** typedVertex for vertex `local` of visible meshlet `v` (its meshlet's submesh `submesh`). */
public VertexOut typedMeshletVertex<M : IMaterial>(View view, VisibleMeshlet v, uint local, uint submesh)
{
    Instance stored = push.scene[0].instances[v.instance];
    Instance instance = relative(stored, view);
    MeshEntry mesh = push.scene[0].meshes[instance.mesh];
    uint vertexOffset = push.scene[0].meshlets[v.meshlet * 8];
    uint vertex = mesh.vertexBase
        + push.scene[0].meshletVertices[mesh.meshletVertexBase + vertexOffset + local];
    return typedVertex<M>(view, stored, instance, v.instance, mesh, vertex, v.meshlet, submesh);
}

// ---------------------------------------------------------------- a geometry type's depth and ID stages

/**
 * Depth only (shadow maps, depth passes) of type M's surface: its holes discarded, and with `masked` (the masked
 * bin) the masked texels and one-sided back faces too, as the color pass discards them. Reads the push block's view
 * and scene alone, at the bytes every raster push block has them.
 */
public void typedDepth<M : IMaterial>(VertexOut in, bool front, bool masked)
{
    if (masked)
    {
        discardMasked(push.scene[0].materials, in, front);
    }
    float4 uvs = float4(in.uv, in.uv1);
    discardHoles<M>(in, ddx(uvs), ddy(uvs));
}

/** The visibility buffer's IDs (visibility/ids.slang's) of type M's surface, its holes and masked texels left out. */
public uint2 typedIds<M : IMaterial>(VertexOut in, uint primitive, bool front, bool masked)
{
    typedDepth<M>(in, front, masked);
    return uint2(in.instance + 1, (in.meshlet << 7) | (primitive - in.firstTriangle));
}

/** Classic LOD's IDs (classic/ids.slang's) of type M's surface, its holes and masked texels left out. */
public uint2 typedClassicIds<M : IMaterial>(VertexOut in, uint primitive, bool front, bool masked)
{
    typedDepth<M>(in, front, masked);
    return uint2(in.instance + 1, in.firstTriangle + primitive);
}

/** The opaque bins' fragments, from full vertices (the indexed path, and mesh shaders without barycentrics). */
public float4 typedFragment<M : IMaterial>(VertexOut in)
{
    return shadeTyped<M>(in, uint2(in.position.xy));
}

/** The masked bin's: one-sided materials' back faces discarded with the masked texels. */
public float4 typedMasked<M : IMaterial>(VertexOut in, bool front)
{
    discardMasked(push.scene[0].materials, in, front);
    return shadeTyped<M>(in, uint2(in.position.xy));
}

/** The blended bin's, into the transparency pass's targets. */
public BlendedOut typedBlended<M : IMaterial>(VertexOut in, bool front)
{
    discardMasked(push.scene[0].materials, in, front); // masked transmissive materials draw here too
    return weighBlended(shadeTyped<M>(in, uint2(in.position.xy)), length(in.world));
}

/** The lean path's opaque fragments. */
public float4 typedLean<M : IMaterial>(LeanVertex in, uint primitive, float3 bary)
{
    return shadeTyped<M>(lean(in, primitive, bary), uint2(in.position.xy));
}

/** The lean path's masked fragments. */
public float4 typedLeanMasked<M : IMaterial>(LeanVertex in, uint primitive, float3 bary, bool front)
{
    VertexOut s = lean(in, primitive, bary);
    discardMasked(push.scene[0].materials, s, front);
    return shadeTyped<M>(s, uint2(in.position.xy));
}

/** The lean path's blended fragments. */
public BlendedOut typedLeanBlended<M : IMaterial>(LeanVertex in, uint primitive, float3 bary, bool front)
{
    VertexOut s = lean(in, primitive, bary);
    discardMasked(push.scene[0].materials, s, front);
    return weighBlended(shadeTyped<M>(s, uint2(in.position.xy)), length(s.world));
}
