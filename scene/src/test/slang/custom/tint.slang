// A game's own draw: a triangle covering the target, colored by instance 1's position.

struct TintPush
{
    float4* positions;
};

[[vk::push_constant]]
ConstantBuffer<TintPush> push;

struct Covering
{
    float4 position : SV_Position;
};

[shader("vertex")]
Covering coverMain(uint vertex : SV_VertexID)
{
    float2 corner = float2((vertex << 1) & 2, vertex & 2);
    Covering out;
    out.position = float4(corner * 2 - 1, 0, 1);
    return out;
}

[shader("fragment")]
float4 tintMain(Covering in) : SV_Target
{
    float4 p = push.positions[1];
    return float4(p.xyz / 10, 1);
}
