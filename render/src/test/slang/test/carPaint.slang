// For tests: a material type building on metallic-roughness, as a game's would: its parameters nest the engine's and
// add their own, its surface is evaluate()'s with flakes mixed in.
module carPaint;

import materials.metallicRoughness;

[MaterialParameters(FEATURE_CLEARCOAT)]
public struct CarPaintParameters
{
    public MetallicRoughnessParameters base;
    [Color] public float3 flakeColor = float3(1, 1, 1);
    [Range(0, 1)] public float flakes = 0;
};

[MaterialType("carPaint")]
public struct CarPaint : IMaterial
{
    public typealias Parameters = CarPaintParameters;

    public static Surface surface(CarPaintParameters p, MaterialRef m, SurfaceInput in)
    {
        Surface s = evaluate(p.base, in);
        s.albedo = lerp(s.albedo, p.flakeColor, p.flakes);
        return s;
    }
};
