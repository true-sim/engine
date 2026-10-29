// The visibility buffer's shading: one thread per pixel reads which triangle the pixel shows, rebuilds it (its three
// vertices through the same vertex stage as drawing), finds where the pixel's ray meets it, interpolates there, and
// shades once with the same surface shading as forward. Texture gradients come from the rays of the neighboring
// pixels (ray differentials), as the hardware's would from the triangle's screen-space derivatives. The entry points
// (shade.slang, and shadeTraced.slang with inline ray shadows) choose how shadow rays are traced.
//
// By material type: classify.slang sorts the screen's 8x8 tiles into a list per type present in them (a tile of
// several types goes into each), and each type's pass shades its tiles' pixels of its type, with its IMaterial.
// Pixels showing nothing count as type 0, the standard type, which writes the clear color there. A game's type is
// a compute entry point of its own, of 8x8 threads (as shade.slang's):
//
//     import visibility.resolve;
//     struct Water : IMaterial { static Surface surface(Material m, SurfaceInput in) { ... } };
//     void waterMain(uint3 group : SV_GroupID, uint3 thread : SV_GroupThreadID)
//     {
//         shadeTiles<Water>(NoRays(), group, thread);
//     }
module resolve;

__exported import bindless;
__exported import meshlets.common;

public struct ShadePush
{
    View* view;
    MeshEntry* meshes;
    Instance* instances;
    uint2* positions;
    uint* attributes;
    uint* meshlets;
    uint* meshletVertices;
    uint* meshletTriangles;
    Material* materials;
    LightListHeader* lights;   // the frame's light list; null: the sun
    uint ids;                  // storage slot of the IDs (uint2)
    uint color;                // storage slot of the color
    uint size;                 // width in the low 16 bits, height in the high
    uint types;                // how many material types there are: higher type ids shade as type 0
    float3 clear;              // the color behind everything
    uint type;                 // the material type this pass shades
    uint* classicIndices;      // the classic LOD path's indices, as the pool's triangles lie (null: no such path)
    uint* tiles;               // classification: the first type's tile list; shading: this pass's type's
};

/** Tiles per row before the next row of groups: an indirect dispatch's groups are (min(count, this), rows, 1). */
static const uint TILE_ROW = 1024;

[[vk::push_constant]]
public ConstantBuffer<ShadePush> push;

/** Byte i of the pool's meshlet triangles. */
uint triangleByte(uint i)
{
    return (push.meshletTriangles[i >> 2] >> ((i & 3) * 8)) & 0xff;
}

uint width()
{
    return push.size & 0xffff;
}

uint height()
{
    return push.size >> 16;
}

/** The direction (from the camera, at the origin) through pixel coordinates `p` (pixel centers at + 0.5). */
float3 ray(float4x4 toWorld, float2 p)
{
    float2 ndc = p / float2(width(), height()) * 2 - 1;
    float4 world = mul(toWorld, float4(ndc, 1, 1));
    return world.xyz / world.w;
}

/** Barycentric coordinates of where `direction` meets triangle a, b, c (normal n, |n|^2 = nn), and the point. */
float3 barycentrics(float3 direction, float3 a, float3 b, float3 c, float3 n, float nn, out float3 hit)
{
    hit = direction * (dot(n, a) / dot(n, direction));
    float wb = dot(cross(c - a, hit - a), -n) / nn;
    float wc = dot(cross(b - a, hit - a), n) / nn;
    return float3(1 - wb - wc, wb, wc);
}

/** The material type of the pixel showing `ids`: 0 for nothing, or for a type of no pass. */
public uint pixelType(uint2 ids)
{
    if (ids.x == 0)
    {
        return 0;
    }
    Instance instance = push.instances[ids.x - 1];
    uint submesh = (ids.y & CLASSIC_TRIANGLE) != 0 ? (ids.y >> 24) & 0x7f : meshletSubmesh(push.meshlets, ids.y >> 7);
    uint type = materialType(push.materials[instance.materialBase + submesh]);
    return type < push.types ? type : 0;
}

/** Each type's tile list: (groups x, y, z, count), then the tiles, x | y << 16; tiles x * tiles y of room. */
uint* tileList(uint type)
{
    uint tiles = ((width() + 7) / 8) * ((height() + 7) / 8);
    return push.tiles + type * (4 + tiles);
}

groupshared uint present[8]; // the types the tile shows, a bit each

/** Puts tile `group` into the list of each type it shows: one group of 8x8 threads per tile. */
public void classifyTile(uint3 group, uint3 thread)
{
    uint index = thread.y * 8 + thread.x;
    if (index < 8)
    {
        present[index] = 0;
    }
    GroupMemoryBarrierWithGroupSync();
    uint2 pixel = group.xy * 8 + thread.xy;
    if (pixel.x < width() && pixel.y < height())
    {
        uint type = pixelType(uintImages[push.ids][pixel]);
        InterlockedOr(present[type >> 5], 1u << (type & 31));
    }
    GroupMemoryBarrierWithGroupSync();
    if (index < 8)
    {
        uint bits = present[index];
        while (bits != 0)
        {
            uint* list = tileList(index * 32 + firstbitlow(bits));
            bits &= bits - 1;
            uint slot;
            InterlockedAdd(list[3], 1, slot);
            InterlockedMax(list[0], min(slot + 1, TILE_ROW));
            InterlockedMax(list[1], slot / TILE_ROW + 1);
            list[2] = 1;
            list[4 + slot] = group.x | group.y << 16;
        }
    }
}

/**
 * Shades the pixels of material type M (push.type) in this pass's tiles: one group of 8x8 threads per tile, the groups
 * as the tile list's indirect dispatch has them. Shadow rays are traced by `rays`.
 */
public void shadeTiles<M : IMaterial, R : IShadowRays>(R rays, uint3 group, uint3 thread)
{
    uint index = group.y * TILE_ROW + group.x;
    if (index >= push.tiles[3])
    {
        return;
    }
    uint tile = push.tiles[4 + index];
    uint3 id = uint3((tile & 0xffff) * 8 + thread.x, (tile >> 16) * 8 + thread.y, 0);
    if (id.x < width() && id.y < height() && pixelType(uintImages[push.ids][id.xy]) == push.type)
    {
        shadePixel<M, R>(rays, id);
    }
}

/** Shades pixel `id` with material type M, shadow rays traced by `rays`. */
public void shadePixel<M : IMaterial, R : IShadowRays>(R rays, uint3 id)
{
    uint2 ids = uintImages[push.ids][id.xy];
    if (ids.x == 0)
    {
        images[push.color][id.xy] = float4(push.clear, 1);
        return;
    }
    View view = push.view[0];
    Instance instance = relative(push.instances[ids.x - 1], view);
    MeshEntry mesh = push.meshes[instance.mesh];
    VertexOut v[3];
    if ((ids.y & CLASSIC_TRIANGLE) != 0)
    {
        // A classic draw's: the submesh and the triangle in the mesh, whose indices lie where its meshlet bytes do.
        uint t = ids.y & 0xffffff;
        uint submesh = (ids.y >> 24) & 0x7f;
        uint first = mesh.indexBase + t * 3;
        for (uint k = 0; k < 3; k++)
        {
            uint vertex = mesh.vertexBase + push.classicIndices[first + k];
            v[k] = transformVertex(view, instance, mesh, vertex, push.positions, push.attributes, t >> 7, submesh);
        }
    }
    else
    {
        uint m = ids.y >> 7;
        uint t = ids.y & 0x7f;
        uint w = m * 8;
        uint first = mesh.indexBase + push.meshlets[w + 1] + t * 3;
        uint vertexBase = mesh.meshletVertexBase + push.meshlets[w];
        uint submesh = meshletSubmesh(push.meshlets, m);
        for (uint k = 0; k < 3; k++)
        {
            uint vertex = mesh.vertexBase + push.meshletVertices[vertexBase + triangleByte(first + k)];
            v[k] = transformVertex(view, instance, mesh, vertex, push.positions, push.attributes, m, submesh);
        }
    }
    float4x4 toWorld = view.inverseViewProjection;
    float3 a = v[0].world;
    float3 b = v[1].world;
    float3 c = v[2].world;
    float3 n = cross(b - a, c - a);
    float nn = max(dot(n, n), 1.0e-20);
    float2 center = float2(id.xy) + 0.5;
    float3 hit;
    float3 hitX;
    float3 hitY;
    float3 bary = barycentrics(ray(toWorld, center), a, b, c, n, nn, hit);
    float3 baryX = barycentrics(ray(toWorld, center + float2(1, 0)), a, b, c, n, nn, hitX);
    float3 baryY = barycentrics(ray(toWorld, center + float2(0, 1)), a, b, c, n, nn, hitY);
    VertexOut s = v[0];
    s.world = hit;
    s.normal = bary.x * v[0].normal + bary.y * v[1].normal + bary.z * v[2].normal;
    s.tangent = float4(bary.x * v[0].tangent.xyz + bary.y * v[1].tangent.xyz + bary.z * v[2].tangent.xyz,
        v[0].tangent.w);
    s.uv = bary.x * v[0].uv + bary.y * v[1].uv + bary.z * v[2].uv;
    s.color = bary.x * v[0].color + bary.y * v[1].color + bary.z * v[2].color;
    float2 uvX = baryX.x * v[0].uv + baryX.y * v[1].uv + baryX.z * v[2].uv;
    float2 uvY = baryY.x * v[0].uv + baryY.y * v[1].uv + baryY.z * v[2].uv;
    images[push.color][id.xy] = shadeMaterial<M, R>(rays, view, s, push.materials, uvX - s.uv, uvY - s.uv,
        push.lights, id.xy);
}
