// Motion vectors (engine-render's MotionVectors): for each pixel of a view, where the surface it shows was on screen
// the frame before, from the visibility buffer's IDs: the triangle rebuilt at this frame's pose (as the visibility
// buffer's shading rebuilds it, met by the pixel's ray for its barycentrics), and the same point of it at the frame
// before's: the instance's pose of the frame before (a copy of the instances kept from it, so interpolated instances,
// D91, and instances written anew alike), a skinned copy's vertices as they were posed then, seen through the frame
// before's view-projection (RenderView: relative to this frame's camera). Pixels showing nothing move with the camera's
// turn alone (directions at infinity). The motion is in pixels of the render region, from here to there (the frame
// before's position, in its render region's pixels, less this one), the projections' jitter taken out of both: a
// history rendered the frame before is read at the pixel plus the motion, whatever the regions' sizes.
import meshlets.common;

/** A skinned copy's vertices as the frame before drew them: per pool mesh, 32 bytes (Deformation's previous table). */
struct DeformedBefore
{
    float3 boxMin;     // the box they were quantized in
    uint offset;       // their first, of the previous positions (uint2 each)
    float3 boxExtent;
    uint valid;        // 1: skinned this frame, so moved from those; 0: as drawn now
};

struct MotionPush
{
    View* view;
    SceneTable* scene;
    Instance* previous;          // the instances as the frame before drew them (this frame's on the first)
    DeformedBefore* deformed;    // per pool mesh; or absent: no deformed copies kept
    uint2* previousPositions;    // the skinned copies' previous vertices, quantized
    uint* classicIndices;        // the classic LOD path's indices (absent: no such path)
    uint ids;                    // storage slot of the IDs (uint2)
    uint motion;                 // storage slot of the motion (two floats)
    uint size;                   // the region: width, height in the high 16 bits
    uint meshCount;              // pool meshes the deformed table covers
    uint previousSize;           // the frame before's region (dynamic resolution): where its pixels lay
};

[[vk::push_constant]]
ConstantBuffer<MotionPush> push;

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

[shader("compute")]
[numthreads(8, 8, 1)]
void motionMain(uint3 id : SV_DispatchThreadID)
{
    uint width = push.size & 0xffff;
    uint height = push.size >> 16;
    if (id.x >= width || id.y >= height)
    {
        return;
    }
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
        Instance previous = relative(had ? storedBefore : stored, view);
        uint meshIndex = instance.mesh;
        MeshEntry mesh = push.scene[0].meshes[meshIndex];
        uint3 vertices;
        if ((ids.y & CLASSIC_TRIANGLE) != 0)
        {
            uint first = mesh.indexBase + (ids.y & 0xffffff) * 3;
            for (uint k = 0; k < 3; k++)
            {
                vertices[k] = mesh.vertexBase + push.classicIndices[first + k];
            }
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
        }
        float3 now[3];
        float3 then[3];
        for (uint k = 0; k < 3; k++)
        {
            now[k] = toWorld(instance, decodePosition(push.scene[0].positions[vertices[k]], mesh));
            then[k] = toWorld(previous, previousLocal(mesh, meshIndex, vertices[k]));
        }
        float3 bary = barycentrics(direction, now[0], now[1], now[2]);
        float3 point = bary.x * then[0] + bary.y * then[1] + bary.z * then[2];
        before = mul(view.previousViewProjection, float4(point, 1));
    }
    float2 previousSize = float2(push.previousSize & 0xffff, push.previousSize >> 16);
    float2 there = before.w > 1.0e-6 ? toPixel(before, previousSize, view.previousJitter)
        : here * previousSize / size;
    images[push.motion][id.xy] = float4(there - here, 0, 0);
}
