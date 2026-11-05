// 3D gizmos (design 8, XR: engine-ui's GizmoRenderer): ui-gizmo's GizmoMesh drawn as true geometry in the 3D view,
// a triangle list pulled from a buffer (SV_VertexID, no vertex buffers), each view (eye) through its own matrix
// (SV_ViewID: a multiview pass draws every eye at once). Depth tested by the hardware against the scene's depth of the
// frame, read only: the renderer draws the mesh twice with complementary tests, where the scene is in front at the
// occluded opacity and where it is behind at full opacity, so every pixel shows exactly one of the two looks. Lit by
// a headlight (the eye's direction), never darker than AMBIENT, so the style's colours read as they do on the screen.

struct GizmoPush
{
    float* vertices;   // GizmoMesh: 8 words a vertex: position (3 floats, relative to the gizmo's origin), normal (3),
                       // colour (4 halves: linear, straight alpha)
    float* views;      // per view viewFloats floats: the origin-relative position to clip space (16, column-major),
                       // then the eye relative to the origin (3) and a spare
    float opacity;     // colours times this: 1 where the scene is behind, the occluded opacity where it is in front
    uint vertexFloats; // floats per vertex (GizmoMesh.VERTEX_BYTES / 4): handed over, never assumed
    uint viewFloats;   // floats per view (GizmoRenderer.VIEW_FLOATS)
};

[[vk::push_constant]]
ConstantBuffer<GizmoPush> push;

/** The share of a colour lit even facing away from the eye. */
static const float AMBIENT = 0.65;

struct GizmoOut
{
    float4 position : SV_Position;
    float3 normal : NORMAL;
    float3 toEye : TOEYE;
    nointerpolation uint2 color : COLOR;
};

/** A half's bits as a float, by hand (as ui/draw.slang: no Float16 capability needed). */
float halfToFloat(uint h)
{
    float magnitude = asfloat((h & 0x7fffu) << 13) * 5.192296858534828e33;
    return (h & 0x8000u) != 0 ? -magnitude : magnitude;
}

[shader("vertex")]
GizmoOut gizmoVertexMain(uint vertex : SV_VulkanVertexID, uint view : SV_ViewID)
{
    float* v = push.vertices + vertex * push.vertexFloats;
    float3 p = float3(v[0], v[1], v[2]);
    float* m = push.views + view * push.viewFloats;
    GizmoOut out;
    out.position = float4(m[0] * p.x + m[4] * p.y + m[8] * p.z + m[12], m[1] * p.x + m[5] * p.y + m[9] * p.z + m[13],
        m[2] * p.x + m[6] * p.y + m[10] * p.z + m[14], m[3] * p.x + m[7] * p.y + m[11] * p.z + m[15]);
    out.normal = float3(v[3], v[4], v[5]);
    out.toEye = float3(m[16], m[17], m[18]) - p;
    out.color = uint2(asuint(v[6]), asuint(v[7]));
    return out;
}

[shader("fragment")]
float4 gizmoFragmentMain(GizmoOut in) : SV_Target
{
    float3 rgb = float3(halfToFloat(in.color.x & 0xffff), halfToFloat(in.color.x >> 16),
        halfToFloat(in.color.y & 0xffff));
    float a = saturate(halfToFloat(in.color.y >> 16)) * push.opacity;
    float facing = saturate(dot(normalize(in.normal), normalize(in.toEye))); // front faces only: back ones culled
    float shade = AMBIENT + (1 - AMBIENT) * facing;
    return float4(rgb * shade * a, a); // premultiplied
}
