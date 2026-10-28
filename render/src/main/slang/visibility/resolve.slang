// The visibility buffer's shading: one thread per pixel reads which triangle the pixel shows, rebuilds it (its three
// vertices through the same vertex stage as drawing), finds where the pixel's ray meets it, interpolates there, and
// shades once with the same surface shading as forward. Texture gradients come from the rays of the neighboring
// pixels (ray differentials), as the hardware's would from the triangle's screen-space derivatives. The entry points
// (shade.slang, and shadeTraced.slang with inline ray shadows) choose how shadow rays are traced.
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
    uint width;
    uint height;
    float3 clear;              // the color behind everything
};

[[vk::push_constant]]
public ConstantBuffer<ShadePush> push;

/** Byte i of the pool's meshlet triangles. */
uint triangleByte(uint i)
{
    return (push.meshletTriangles[i >> 2] >> ((i & 3) * 8)) & 0xff;
}

/** The direction (from the camera, at the origin) through pixel coordinates `p` (pixel centers at + 0.5). */
float3 ray(float4x4 toWorld, float2 p)
{
    float2 ndc = p / float2(push.width, push.height) * 2 - 1;
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

/** Shades pixel `id`, shadow rays traced by `rays`. */
public void shadePixel<R : IShadowRays>(R rays, uint3 id)
{
    if (id.x >= push.width || id.y >= push.height)
    {
        return;
    }
    uint2 ids = uintImages[push.ids][id.xy];
    if (ids.x == 0)
    {
        images[push.color][id.xy] = float4(push.clear, 1);
        return;
    }
    View view = push.view[0];
    Instance instance = push.instances[ids.x - 1];
    MeshEntry mesh = push.meshes[instance.mesh];
    uint m = ids.y >> 7;
    uint t = ids.y & 0x7f;
    uint w = m * 8;
    uint first = mesh.indexBase + push.meshlets[w + 1] + t * 3;
    uint vertexBase = mesh.meshletVertexBase + push.meshlets[w];
    uint submesh = meshletSubmesh(push.meshlets, m);
    VertexOut v[3];
    for (uint k = 0; k < 3; k++)
    {
        uint vertex = mesh.vertexBase + push.meshletVertices[vertexBase + triangleByte(first + k)];
        v[k] = transformVertex(view, instance, mesh, vertex, push.positions, push.attributes, m, submesh);
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
    images[push.color][id.xy] = shadeSurface(rays, view, s, push.materials, uvX - s.uv, uvY - s.uv, push.lights,
        id.xy);
}
