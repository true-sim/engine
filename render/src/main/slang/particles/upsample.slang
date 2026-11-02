// Half-resolution particles over the scene (effects.md §2.5, per eye): emitters drawn into a target of half the view's
// size, tested against the farthest of each 2 x 2 of the scene's depth, then laid over the full-resolution color by
// this pass, a triangle covering the view: each pixel takes the half-resolution texel among the four nearest whose
// depth is nearest its own (a depth-aware upsample), so particles stay behind the edges of nearer surfaces instead of
// bleeding over them, blended premultiplied.
import bindless;

struct ParticleUpsamplePush
{
    uint color;                    // the half-resolution particles, premultiplied: sampled slot
    uint halfDepth;                // the farthest depth of each 2 x 2 of the scene's: sampled slot
    uint depth;                    // the scene's depth: sampled slot
    uint pad;
    uint2 halfSize;                // texels of the half-resolution images
    uint2 pad1;
};

[[vk::push_constant]]
ConstantBuffer<ParticleUpsamplePush> push;

struct UpsampleVertex
{
    float4 position : SV_Position;
};

[shader("vertex")]
UpsampleVertex upsampleVertexMain(uint vertex : SV_VertexID)
{
    // One triangle over the whole view
    UpsampleVertex o;
    float2 uv = float2((vertex << 1) & 2, vertex & 2);
    o.position = float4(uv * 2 - 1, 0, 1);
    return o;
}

[shader("fragment")]
float4 upsampleMain(UpsampleVertex v) : SV_Target
{
    int2 pixel = int2(v.position.xy);
    float depth = textures[push.depth].Load(int3(pixel, 0)).r;
    // The four half texels about this pixel: the one of nearest depth (reverse depth, compared as distances' inverses)
    int2 base = (pixel - 1) >> 1;
    int2 last = int2(push.halfSize) - 1;
    float best = 1e30;
    int2 chosen = clamp(pixel >> 1, int2(0, 0), last);
    for (int y = 0; y < 2; y++)
    {
        for (int x = 0; x < 2; x++)
        {
            int2 t = clamp(base + int2(x, y), int2(0, 0), last);
            float d = textures[push.halfDepth].Load(int3(t, 0)).r;
            float apart = abs(d - depth) / max(max(d, depth), 1e-7);
            if (apart < best)
            {
                best = apart;
                chosen = t;
            }
        }
    }
    return textures[push.color].Load(int3(chosen, 0));
}
