// The selection outline (engine-render's SelectionOutline), in two compute passes over a view's render region:
//
// - maskMain: each pixel's instance, from the visibility buffer's IDs (instance id + 1 in x, 0 for nothing, classic
//   LOD's tagged by the top bit), looked up in the highlight flags (two bits per instance: 1 hovered, 2 selected),
//   written as a mask (the flags over 3, R8).
// - outlineMain: OUTLINE draws, on each pixel not highlighted itself, the colour of the nearest highlighted pixel
//   within the width (selected over hovered), fading over its last pixel so the edge is smooth; TINT blends the colour
//   over the highlighted pixels themselves. The scene's colour is written in place.
//
// Only what the view shows is outlined: a highlighted object behind another shows its visible part's outline. A
// forward view's IDs hold the highlighted instances alone (drawn by themselves), so their depth is tested against the
// scene's.
import bindless;

struct OutlinePush
{
    uint* flags;       // the highlight flags: 16 instances a word, two bits each
    uint ids;          // storage slot: the IDs (uint2)
    uint mask;         // storage slot: the mask (R8)
    uint color;        // storage slot: the scene's colour
    uint size;         // the render region: width, height in the high 16 bits
    uint instances;    // how many instances the flags hold
    uint width;        // the outline's width in pixels
    uint mode;         // 0: outline, 1: tint
    uint idDepth;      // depthTest: sampled slot of the IDs' own depth (an ID draw of the highlighted alone)
    uint sceneDepth;   // depthTest: sampled slot of the scene's depth
    uint depthTest;    // 1: the IDs hold the highlighted alone: masked only where they are what the scene shows
    float selectedR;   // the selected colour, linear, and its opacity
    float selectedG;
    float selectedB;
    float selectedA;
    float hoveredR;    // the hovered colour and its opacity
    float hoveredG;
    float hoveredB;
    float hoveredA;
};

[[vk::push_constant]]
ConstantBuffer<OutlinePush> push;

uint2 regionSize()
{
    return uint2(push.size & 0xffff, push.size >> 16);
}

/** The highlight of pixel {@code p}: 0, 1 hovered, 2 selected, 3 both. */
uint highlight(uint2 p)
{
    return uint(images[push.mask][p].r * 3 + 0.5);
}

float4 colourOf(uint flags)
{
    return (flags & 2) != 0 ? float4(push.selectedR, push.selectedG, push.selectedB, push.selectedA)
        : float4(push.hoveredR, push.hoveredG, push.hoveredB, push.hoveredA);
}

[shader("compute")]
[numthreads(8, 8, 1)]
void maskMain(uint3 id : SV_DispatchThreadID)
{
    if (any(id.xy >= regionSize()))
    {
        return;
    }
    uint shown = uintImages[push.ids][id.xy].x & 0x7fffffff;
    uint flags = 0;
    if (shown != 0 && push.depthTest != 0)
    {
        // reverse depth: nearer is larger; the same surface drawn twice lands on the same depth
        float own = textures[push.idDepth].Load(int3(id.xy, 0)).r;
        float scene = textures[push.sceneDepth].Load(int3(id.xy, 0)).r;
        if (own < scene - max(1.0e-6, scene * 1.0e-4))
        {
            shown = 0; // hidden behind what the scene shows
        }
    }
    if (shown != 0 && shown - 1 < push.instances)
    {
        uint i = shown - 1;
        flags = (push.flags[i >> 4] >> ((i & 15) * 2)) & 3;
    }
    images[push.mask][id.xy] = float4(float(flags) / 3, 0, 0, 0);
}

[shader("compute")]
[numthreads(8, 8, 1)]
void outlineMain(uint3 id : SV_DispatchThreadID)
{
    uint2 size = regionSize();
    if (any(id.xy >= size))
    {
        return;
    }
    uint own = highlight(id.xy);
    if (push.mode == 1)
    {
        if (own != 0)
        {
            float4 c = colourOf(own);
            float4 scene = images[push.color][id.xy];
            images[push.color][id.xy] = float4(lerp(scene.rgb, c.rgb, c.a), scene.a);
        }
        return;
    }
    if (own != 0)
    {
        return; // the outline is drawn around highlighted pixels, not over them
    }
    int r = int(push.width);
    float reach = (r + 0.5) * (r + 0.5);
    float nearest = reach;
    uint found = 0;
    int2 last = int2(size) - 1;
    for (int dy = -r; dy <= r; dy++)
    {
        for (int dx = -r; dx <= r; dx++)
        {
            float d2 = float(dx * dx + dy * dy);
            if (d2 >= reach)
            {
                continue;
            }
            uint n = highlight(uint2(clamp(int2(id.xy) + int2(dx, dy), int2(0, 0), last)));
            if (n != 0)
            {
                found |= n;
                nearest = min(nearest, d2);
            }
        }
    }
    if (found == 0)
    {
        return;
    }
    float4 c = colourOf(found);
    float a = c.a * saturate(float(r) + 1 - sqrt(nearest)); // the last pixel fades: a smooth edge
    float4 scene = images[push.color][id.xy];
    images[push.color][id.xy] = float4(lerp(scene.rgb, c.rgb, a), scene.a);
}
