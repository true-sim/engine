// For tests: a material type with a geometry hook (GEOMETRY_KEEP), cut out in a checkerboard of its texture
// coordinates, glowing yellow where it is: its holes must be the same in color, depth, shadows and IDs.
module dissolve;

import materials;

[MaterialParameters(GEOMETRY_KEEP)]
public struct DissolveParameters
{
    [Color] public float3 color = float3(1, 1, 0);
    [Range(0.01, 1)] public float cell = 0.25; // the checkerboard's cells, in texture coordinates
};

[MaterialType("dissolve")]
public struct Dissolve : IMaterial
{
    public typealias Parameters = DissolveParameters;

    public static Surface surface(DissolveParameters p, MaterialRef m, SurfaceInput in)
    {
        Surface s = Surface.defaults(normalize(in.normal));
        s.albedo = 0;
        s.metallic = 1; // no diffuse, no specular: only the glow
        s.roughness = 1;
        s.emissive = p.color;
        return s;
    }

    /** Every other cell of the checkerboard exists. */
    public override static bool keep(DissolveParameters p, MaterialRef m, SurfaceInput in)
    {
        int2 cell = int2(floor(in.uv / p.cell));
        return ((cell.x + cell.y) & 1) == 0;
    }
};
