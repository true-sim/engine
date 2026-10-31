// For tests: a material type glowing in a color a buffer holds, the buffer named by its parameters: written by the
// host, or by a pass of the frame graph.
module tinted;

import materials;

/** What the buffer holds. */
public struct Tint
{
    public float4 color;
};

[MaterialParameters(0)]
public struct TintedParameters
{
    public Tint* tint; // none: black
    [Range(0, 16)] public float strength = 1;
};

[MaterialType("tinted")]
public struct Tinted : IMaterial
{
    public typealias Parameters = TintedParameters;

    public static Surface surface(TintedParameters p, MaterialRef m, SurfaceInput in)
    {
        Surface s = Surface.defaults(normalize(in.normal));
        s.albedo = 0;
        s.metallic = 1; // no diffuse, no specular: only the glow
        s.roughness = 1;
        s.emissive = (uint64_t)p.tint != 0 ? p.tint.color.rgb * p.strength : float3(0);
        return s;
    }
};
