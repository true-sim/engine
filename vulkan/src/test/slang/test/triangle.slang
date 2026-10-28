// One triangle whose vertices the vertex shader pulls through a pointer, filled with a color: graphics pipelines,
// vertex pulling and dynamic rendering.

struct TrianglePush
{
    float4 color;
    float2* positions;
};

[[vk::push_constant]]
ConstantBuffer<TrianglePush> push;

struct VertexOut
{
    float4 position : SV_Position;
};

[shader("vertex")]
VertexOut vertexMain(uint vertex : SV_VertexID)
{
    VertexOut out;
    out.position = float4(push.positions[vertex], 0.5, 1.0);
    return out;
}

[shader("fragment")]
float4 fragmentMain() : SV_Target
{
    return push.color;
}
