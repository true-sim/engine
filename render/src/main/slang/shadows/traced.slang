// Ray-traced shadows of one light, as a screen-space mask: per pixel, the position from the scene's depth, then one ray
// query towards the light. Exact:
// no resolution, no bias beyond a small offset off the surface, as far as the scene goes. The offset goes along the
// surface's normal, rebuilt from the depth of the neighbours: the rays test full-detail geometry, while the pixel
// may show a coarser level a little inside it.
import bindless;
import raytracing;
import meshlets.common;
import lights;

struct TracedPush
{
    float4x4 inverseViewProjection;  // clip space back to camera-relative positions
    uint depth;                      // sampled slot of the scene's depth
    uint mask;                       // storage slot of the mask: 1 lit, 0 in shadow
    uint scene;                      // the top level's slot
    uint width;
    uint height;
    float offset;                    // off the surface, per unit of distance from the camera
    Light* lights;
    uint light;                      // which of them
};

[[vk::push_constant]]
ConstantBuffer<TracedPush> push;

float3 positionAt(int2 pixel, float depth)
{
    float2 ndc = (float2(pixel) + 0.5) / float2(push.width, push.height) * 2 - 1;
    float4 clip = mul(push.inverseViewProjection, float4(ndc, depth, 1));
    return clip.xyz / clip.w;
}

// The neighbour along step (one way or the other) nearest in depth: across an edge, the other side.
float3 neighbour(int2 pixel, int2 step, float depth, float3 position)
{
    int2 last = int2(push.width, push.height) - 1;
    int2 a = clamp(pixel + step, 0, last);
    int2 b = clamp(pixel - step, 0, last);
    float da = textures[push.depth].Load(int3(a, 0)).r;
    float db = textures[push.depth].Load(int3(b, 0)).r;
    bool first = abs(da - depth) <= abs(db - depth);
    float3 other = first ? positionAt(a, da) : positionAt(b, db);
    return first ? other - position : position - other;
}

[shader("compute")]
[numthreads(8, 8, 1)]
[require(spvRayQueryKHR)]
void tracedMain(uint3 id : SV_DispatchThreadID)
{
    if (id.x >= push.width || id.y >= push.height)
    {
        return;
    }
    int2 pixel = int2(id.xy);
    float depth = textures[push.depth].Load(int3(pixel, 0)).r;
    float lit = 1;
    if (depth > 0) // 0: nothing drawn, the sky
    {
        float3 position = positionAt(pixel, depth);
        float3 normal = normalize(cross(neighbour(pixel, int2(1, 0), depth, position),
            neighbour(pixel, int2(0, 1), depth, position)));
        normal = dot(normal, position) > 0 ? -normal : normal; // towards the camera
        Light light = push.lights[push.light];
        float3 toLight = -light.direction;
        float distance = 1.0e30;
        if (light.type != DIRECTIONAL)
        {
            float3 d = light.position - position;
            distance = length(d);
            toLight = d / max(distance, 1.0e-6);
        }
        if (dot(normal, toLight) <= 0)
        {
            lit = 0; // turned away from the light: its own shadow, no ray needed
        }
        else
        {
            float offset = push.offset * length(position) + 1.0e-3;
            lit = occluded(push.scene, position + normal * offset, toLight, 0, distance) ? 0 : 1;
        }
    }
    images[push.mask][id.xy] = float4(lit, 0, 0, 0);
}
