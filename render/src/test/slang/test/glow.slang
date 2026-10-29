// For tests: a material type of a game's own, glowing in a color of its parameters whatever the light, shaded by type
// in the visibility buffer; its entry points and Java come generated.
module glow;

import materials;

[MaterialParameters(0)]
public struct GlowParameters
{
    [Color] public float3 color = float3(1, 0, 1);
    [Range(0, 16)] public float strength = 1;
};

[MaterialType("glow")]
public struct Glow : IMaterial
{
    public typealias Parameters = GlowParameters;

    public static Surface surface(GlowParameters p, MaterialRef m, SurfaceInput in)
    {
        Surface s;
        s.albedo = 0;
        s.metallic = 1; // no diffuse, no specular: only the glow
        s.normal = normalize(in.normal);
        s.roughness = 1;
        s.emissive = p.color * p.strength;
        s.occlusion = 1;
        return s;
    }
};
