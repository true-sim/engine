// Motion vectors (engine-render's MotionVectors): for each pixel of a view, where the surface it shows was on screen
// the frame before, from the visibility buffer's IDs: the triangle rebuilt at this frame's pose (as the visibility
// buffer's shading rebuilds it, met by the pixel's ray for its barycentrics), and the same point of it at the frame
// before's: the instance's pose of the frame before (a copy of the instances kept from it, so interpolated instances,
// D91, and instances written anew alike), a skinned or displaced copy's vertices as they were posed then, a displacing
// type's vertices displaced as the frame before displaced them (the scene's hook block of the frame before,
// SceneTable.previousHooks), seen through the frame before's view-projection (RenderView: relative to this frame's
// camera). Pixels showing nothing move with the camera's turn alone (directions at infinity). The motion is in pixels
// of the render region, from here to there (the frame before's position, in its render region's pixels, less this
// one), the projections' jitter taken out of both: a history rendered the frame before is read at the pixel plus the
// motion, whatever the regions' sizes.
//
// Pixels of types displacing in the vertex stages (GEOMETRY_DISPLACE, RASTER) are left by motion.slang's pass, which
// lists their 8x8 tiles per type with geometry hooks; each displacing type's pass (material-types/entryDisplaceMotion)
// then writes its pixels of those tiles, displacing as it draws.
module vectors;

__exported import meshlets.common;

/** A skinned copy's vertices as the frame before drew them: per pool mesh, 32 bytes (Deformation's previous table). */
public struct DeformedBefore
{
    public float3 boxMin;     // the box they were quantized in
    public uint offset;       // their first, of the previous positions (uint2 each)
    public float3 boxExtent;
    public uint valid;        // 1: skinned this frame, so moved from those; 0: as drawn now
};

public struct MotionPush
{
    public View* view;
    public SceneTable* scene;
    public Instance* previous;          // the instances as the frame before drew them (this frame's on the first)
    public DeformedBefore* deformed;    // per pool mesh; or null: no deformed copies kept
    public uint2* previousPositions;    // the skinned copies' previous vertices, quantized
    public uint* classicIndices;        // the classic LOD path's indices (absent: no such path)
    public uint* tiles;                 // per type with geometry hooks a tile list (4 words, then tiles x | y << 16)
    public uint ids;                    // storage slot of the IDs (uint2)
    public uint motion;                 // storage slot of the motion (two floats)
    public uint size;                   // the region: width, height in the high 16 bits
    public uint meshCount;              // pool meshes the deformed table covers
    public uint previousSize;           // the frame before's region (dynamic resolution): where its pixels lay
    public uint lists;                  // tile lists (types with geometry hooks); 0: none
    public uint stride;                 // words from one tile list to the next
    public uint list;                   // a displacing type's pass: its list (its geometry number - 1)
};

[[vk::push_constant]]
public ConstantBuffer<MotionPush> push;

uint triangleByte(uint i)
{
    return (push.scene[0].meshletTriangles[i >> 2] >> ((i & 3) * 8)) & 0xff;
}

/** Barycentrics of where `direction` (from the camera) meets triangle a, b, c. */
float3 barycentrics(float3 direction, float3 a, float3 b, float3 c)
{
    float3 n = cross(b - a, c - a);
    float nn = max(dot(n, n), 1.0e-20);
    float3 hit = direction * (dot(n, a) / dot(n, direction));
    float wb = dot(cross(c - a, hit - a), -n) / nn;
    float wc = dot(cross(b - a, hit - a), n) / nn;
    return float3(1 - wb - wc, wb, wc);
}

/** Pool vertex `vertex` of `mesh` (number `meshIndex`) in its mesh's space as the frame before drew it. */
float3 previousLocal(MeshEntry mesh, uint meshIndex, uint vertex)
{
    if (given(push.deformed) && meshIndex < push.meshCount)
    {
        DeformedBefore* d = push.deformed + meshIndex;
        if (d[0].valid != 0)
        {
            uint2 q = push.previousPositions[d[0].offset + vertex - mesh.vertexBase];
            float3 unorm = float3(q.x & 0xffff, q.x >> 16, q.y & 0xffff) / 65535.0;
            return d[0].boxMin + unorm * d[0].boxExtent;
        }
    }
    return decodePosition(push.scene[0].positions[vertex], mesh);
}

/** Pixel position (in the region, jitter taken out) of camera-relative clip-space point `clip`. */
float2 toPixel(float4 clip, float2 size, float2 jitter)
{
    return (clip.xy / clip.w * 0.5 + 0.5) * size - jitter;
}

/** The region's width and height. */
public uint2 regionSize()
{
    return uint2(push.size & 0xffff, push.size >> 16);
}

/** The material of the pixel showing `ids` (not nothing), and its instance as stored. */
public Material pixelMaterial(uint2 ids, out Instance stored)
{
    stored = push.scene[0].instances[ids.x - 1];
    uint submesh = (ids.y & CLASSIC_TRIANGLE) != 0 ? (ids.y >> 24) & 0x7f
        : meshletSubmesh(push.scene[0].meshlets, ids.y >> 7);
    return push.scene[0].materials[stored.materialBase + submesh];
}

/**
 * Writes the motion of pixel `id`: with `displaced`, its triangle's vertices moved by type M's displace() now (the
 * scene's hook block of this frame, this pose) and then (the frame before's block, its pose and vertices), as the
 * vertex stages displaced them in each frame.
 */
public void writeMotion<M : IMaterial>(uint2 id, bool displaced)
{
    uint width = push.size & 0xffff;
    uint height = push.size >> 16;
    View view = push.view[0];
    float2 size = float2(width, height);
    float2 center = float2(id.xy) + 0.5;
    float2 ndc = center / size * 2 - 1;
    float4 near = mul(view.inverseViewProjection, float4(ndc, 1, 1));
    float3 direction = near.xyz / near.w;
    float2 here = center - view.jitter;
    uint2 ids = uintImages[push.ids][id.xy];
    float4 before;
    if (ids.x == 0)
    {
        before = mul(view.previousViewProjection, float4(direction, 0)); // at infinity: the camera's turn only
    }
    else
    {
        Instance stored = push.scene[0].instances[ids.x - 1];
        Instance instance = relative(stored, view);
        Instance storedBefore = push.previous[ids.x - 1];
        // An instance that was not there, or another mesh, the frame before: as drawn now.
        bool had = (storedBefore.mesh & INSTANCE_LIVE) != 0
            && (storedBefore.mesh & INSTANCE_MESH) == (stored.mesh & INSTANCE_MESH);
        Instance then = had ? storedBefore : stored;
        Instance previous = relative(then, view);
        uint meshIndex = instance.mesh;
        MeshEntry mesh = push.scene[0].meshes[meshIndex];
        uint3 vertices;
        uint submesh;
        if ((ids.y & CLASSIC_TRIANGLE) != 0)
        {
            uint first = mesh.indexBase + (ids.y & 0xffffff) * 3;
            for (uint k = 0; k < 3; k++)
            {
                vertices[k] = mesh.vertexBase + push.classicIndices[first + k];
            }
            submesh = (ids.y >> 24) & 0x7f;
        }
        else
        {
            uint w = (ids.y >> 7) * 8;
            uint first = mesh.indexBase + push.scene[0].meshlets[w + 1] + (ids.y & 0x7f) * 3;
            uint vertexBase = mesh.meshletVertexBase + push.scene[0].meshlets[w];
            for (uint k = 0; k < 3; k++)
            {
                vertices[k] = mesh.vertexBase + push.scene[0].meshletVertices[vertexBase + triangleByte(first + k)];
            }
            submesh = meshletSubmesh(push.scene[0].meshlets, ids.y >> 7);
        }
        MaterialRef ref = { push.scene[0].materials, stored.materialBase + submesh };
        float3 now[3];
        float3 earlier[3];
        for (uint k = 0; k < 3; k++)
        {
            if (displaced)
            {
                LocalVertex v = localVertex(mesh, vertices[k], push.scene[0].positions, push.scene[0].attributes);
                LocalVertex w = v;
                w.position = previousLocal(mesh, meshIndex, vertices[k]);
                now[k] = toWorld(instance, displacedVertex<M>(v, stored, ids.x - 1, ref,
                    push.scene[0].hooks).position);
                earlier[k] = toWorld(previous, displacedVertex<M>(w, then, ids.x - 1, ref,
                    push.scene[0].previousHooks).position);
            }
            else
            {
                now[k] = toWorld(instance, decodePosition(push.scene[0].positions[vertices[k]], mesh));
                earlier[k] = toWorld(previous, previousLocal(mesh, meshIndex, vertices[k]));
            }
        }
        float3 bary = barycentrics(direction, now[0], now[1], now[2]);
        float3 point = bary.x * earlier[0] + bary.y * earlier[1] + bary.z * earlier[2];
        before = mul(view.previousViewProjection, float4(point, 1));
    }
    float2 previousSize = float2(push.previousSize & 0xffff, push.previousSize >> 16);
    float2 there = before.w > 1.0e-6 ? toPixel(before, previousSize, view.previousJitter)
        : here * previousSize / size;
    images[push.motion][id.xy] = float4(there - here, 0, 0);
}

/** Tile list `list` (a type with geometry hooks' number - 1): (groups x, y, z, count), then the tiles. */
public uint* tileList(uint list)
{
    return push.tiles + list * push.stride;
}

/** Tiles per row before the next row of groups: an indirect dispatch's groups are (min(count, this), rows, 1). */
public static const uint TILE_ROW = 1024;

/**
 * A displacing type M's pass: writes the motion of its pixels (of its geometry number, push.list + 1, displaced where
 * drawn) in the tiles listed for it, one group of 8x8 threads per tile.
 */
public void typedMotion<M : IMaterial>(uint3 group, uint3 thread)
{
    uint* list = tileList(push.list);
    uint index = group.y * TILE_ROW + group.x;
    if (index >= list[3])
    {
        return;
    }
    uint tile = list[4 + index];
    uint2 id = uint2((tile & 0xffff) * 8 + thread.x, (tile >> 16) * 8 + thread.y);
    uint2 size = regionSize();
    if (id.x >= size.x || id.y >= size.y)
    {
        return;
    }
    uint2 ids = uintImages[push.ids][id];
    if (ids.x == 0)
    {
        return;
    }
    Instance stored;
    Material m = pixelMaterial(ids, stored);
    if (displacedInStages(stored, m) && materialGeometry(m) == push.list + 1)
    {
        writeMotion<M>(id, true);
    }
}
