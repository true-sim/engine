// For ForwardShadingBenchmark: the correct single forward shader as the build would generate it (#67 follow-up), one
// fragment stage for every material type, whose surface is a switch over the material's type calling each registered
// type's own surface() (here the engine's and the tests' types), its features read from the material (no
// specialization). The types' numbers in the table are specialization constants, set when the pipeline is made.
import meshlets.forward;
import materials.metallicRoughness;
import test.glow;
import test.tinted;
import test.carPaint;

[vk::constant_id(1)]
const uint GLOW_TYPE = 0xff;
[vk::constant_id(2)]
const uint TINTED_TYPE = 0xff;
[vk::constant_id(3)]
const uint CAR_PAINT_TYPE = 0xff;

/** Every registered type's surface, by the material's type: what the generated switch is. */
struct AnySurface : IMaterial
{
    typealias Parameters = NoParameters;

    static Surface surface(NoParameters p, MaterialRef m, SurfaceInput in)
    {
        uint type = materialType(m.slot());
        if (type == GLOW_TYPE)
        {
            return Glow.surface(materialParameters<GlowParameters>(m), m, in);
        }
        if (type == TINTED_TYPE)
        {
            return Tinted.surface(materialParameters<TintedParameters>(m), m, in);
        }
        if (type == CAR_PAINT_TYPE)
        {
            return CarPaint.surface(materialParameters<CarPaintParameters>(m), m, in);
        }
        return MetallicRoughnessMaterial.surface(materialParameters<MetallicRoughnessParameters>(m), m, in);
    }
};

float4 shadeAny(VertexOut s, uint2 pixel)
{
    float4 uvs = float4(s.uv, s.uv1);
    return shadeMaterial<AnySurface, NoRays>(NoRays(), push.view[0], s, push.scene[0].materials, ddx(uvs), ddy(uvs),
        push.lights, pixel, materialFeatures(push.scene[0].materials[s.material]), behind(s.instance), push.indirect);
}

[shader("fragment")]
float4 singleFragmentMain(VertexOut in) : SV_Target
{
    return shadeAny(in, uint2(in.position.xy));
}

[shader("fragment")]
float4 singleMaskedMain(VertexOut in, bool front : SV_IsFrontFace) : SV_Target
{
    discardMasked(push.scene[0].materials[in.material], in, front);
    return shadeAny(in, uint2(in.position.xy));
}

[shader("fragment")]
BlendedOut singleBlendedMain(VertexOut in, bool front : SV_IsFrontFace)
{
    discardMasked(push.scene[0].materials[in.material], in, front);
    return weighBlended(shadeAny(in, uint2(in.position.xy)), length(in.world));
}

[shader("fragment")]
float4 singleLeanMain(LeanVertex in, uint primitive : SV_PrimitiveID, float3 bary : SV_Barycentrics) : SV_Target
{
    return shadeAny(lean(in, primitive, bary), uint2(in.position.xy));
}

[shader("fragment")]
float4 singleLeanMaskedMain(LeanVertex in, uint primitive : SV_PrimitiveID, float3 bary : SV_Barycentrics,
    bool front : SV_IsFrontFace) : SV_Target
{
    VertexOut s = lean(in, primitive, bary);
    discardMasked(push.scene[0].materials[s.material], s, front);
    return shadeAny(s, uint2(in.position.xy));
}

[shader("fragment")]
BlendedOut singleLeanBlendedMain(LeanVertex in, uint primitive : SV_PrimitiveID, float3 bary : SV_Barycentrics,
    bool front : SV_IsFrontFace)
{
    VertexOut s = lean(in, primitive, bary);
    discardMasked(push.scene[0].materials[s.material], s, front);
    return weighBlended(shadeAny(s, uint2(in.position.xy)), length(s.world));
}
