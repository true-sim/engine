// For tests: a game's material type in a project of its own, building on engine-render's metallic-roughness.
module tinted;

import materials.metallicRoughness;

[MaterialParameters(FEATURE_CLEARCOAT)]
public struct TintedParameters
{
    public MetallicRoughnessParameters base;
    [Color] public float3 tint = float3(1, 1, 1);
};

[MaterialType("tinted")]
public struct Tinted : IMaterial
{
    public typealias Parameters = TintedParameters;

    public static Surface surface(TintedParameters p, MaterialRef m, SurfaceInput in)
    {
        Surface s = evaluate(p.base, in);
        s.albedo *= p.tint;
        return s;
    }
};
