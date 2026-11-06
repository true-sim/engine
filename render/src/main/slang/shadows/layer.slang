// A shadow atlas tile drawn over its layer of still casters (Shadows, caster layers): a triangle covering the tile's
// viewport writes the layer's depth at each texel, then the moving casters are drawn over it with the depth test. The
// pass's depth attachment is the atlas; the layer atlas, as large, is read at the same texel.
import bindless;

struct ShadowLayerPush
{
    uint layer;                    // the layer atlas: sampled slot
    uint3 pad;
};

[[vk::push_constant]]
ConstantBuffer<ShadowLayerPush> push;

struct LayerVertex
{
    float4 position : SV_Position;
};

[shader("vertex")]
LayerVertex layerVertexMain(uint vertex : SV_VertexID)
{
    // One triangle over the whole viewport: the tile
    LayerVertex o;
    float2 uv = float2((vertex << 1) & 2, vertex & 2);
    o.position = float4(uv * 2 - 1, 0, 1);
    return o;
}

[shader("fragment")]
float layerMain(LayerVertex v) : SV_Depth
{
    return textures[push.layer].Load(int3(int2(v.position.xy), 0)).r;
}
