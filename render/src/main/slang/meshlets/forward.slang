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
    public LightListHeader* lights;  // at 16: the light list, or null: the sun
    public IndirectLight* indirect;  // at 24: the view's indirect-light table (indirect.slang), or null: the constant sky
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

/** Surface `s` at `pixel` shaded with material type M and the variant's FEATURES. */
public float4 shadeTyped<M : IMaterial>(VertexOut s, uint2 pixel)
{
    float4 uvs = float4(s.uv, s.uv1);
    return shadeMaterial<M, NoRays>(NoRays(), push.view[0], s, push.scene[0].materials, ddx(uvs), ddy(uvs),
        push.lights, pixel, FEATURES, behind(s.instance), push.indirect);
}

/** The opaque bins' fragments, from full vertices (the indexed path, and mesh shaders without barycentrics). */
public float4 typedFragment<M : IMaterial>(VertexOut in)
{
    return shadeTyped<M>(in, uint2(in.position.xy));
}

/** The masked bin's: one-sided materials' back faces discarded with the masked texels. */
public float4 typedMasked<M : IMaterial>(VertexOut in, bool front)
{
    discardMasked(push.scene[0].materials[in.material], in, front);
    return shadeTyped<M>(in, uint2(in.position.xy));
}

/** The blended bin's, into the transparency pass's targets. */
public BlendedOut typedBlended<M : IMaterial>(VertexOut in, bool front)
{
    discardMasked(push.scene[0].materials[in.material], in, front); // masked transmissive materials draw here too
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
    discardMasked(push.scene[0].materials[s.material], s, front);
    return shadeTyped<M>(s, uint2(in.position.xy));
}

/** The lean path's blended fragments. */
public BlendedOut typedLeanBlended<M : IMaterial>(LeanVertex in, uint primitive, float3 bary, bool front)
{
    VertexOut s = lean(in, primitive, bary);
    discardMasked(push.scene[0].materials[s.material], s, front);
    return weighBlended(shadeTyped<M>(s, uint2(in.position.xy)), length(s.world));
}
