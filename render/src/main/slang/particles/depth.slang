// The collision view's depth for particles (effects.md §2.7, depth collision): captureMain samples the view's scene
// depth on a grid of at most DEPTH_GRID samples a side over its render region and writes it, after a header of the
// view's view-projection, its inverse and its camera's position, into the collision depth buffer that the next
// frame's simulation reads (the last frame's depth, reprojected by the view-projection it was drawn with).
import particle;
import meshlets.common;
import bindless;

struct ParticleDepthPush
{
    View* view;                    // the collision view
    float* depth;                  // the collision depth: header, then the grid
    uint scene;                    // the scene's depth, sampled slot
    uint width;                    // the grid's samples across
    uint height;                   // and down
    uint regionWidth;              // the render region's pixels across
    uint regionHeight;             // and down
};

[[vk::push_constant]]
ConstantBuffer<ParticleDepthPush> push;

[shader("compute")]
[numthreads(8, 8, 1)]
void captureMain(uint3 id : SV_DispatchThreadID)
{
    float* depth = push.depth;
    if (id.x == 0 && id.y == 0)
    {
        View* view = push.view;
        float4x4 viewProjection = view.viewProjection;
        float4x4 inverse = view.inverseViewProjection;
        for (uint i = 0; i < 16; i++)
        {
            depth[i] = viewProjection[i / 4][i % 4];
            depth[16 + i] = inverse[i / 4][i % 4];
        }
        float3 high = view.position;
        float3 low = view.positionLow;
        depth[32] = high.x;
        depth[33] = high.y;
        depth[34] = high.z;
        depth[35] = low.x;
        depth[36] = low.y;
        depth[37] = low.z;
        depth[38] = float(push.width);
        depth[39] = float(push.height);
    }
    if (id.x >= push.width || id.y >= push.height)
    {
        return;
    }
    int2 pixel = int2((float2(id.xy) + 0.5) / float2(push.width, push.height)
        * float2(push.regionWidth, push.regionHeight));
    depth[DEPTH_HEADER + id.y * push.width + id.x] = textures[push.scene].Load(int3(pixel, 0)).r;
}
