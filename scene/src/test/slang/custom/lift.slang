// For engine-scene's tests: a material type displacing its vertices (GEOMETRY_DISPLACE) by a constant offset, glowing,
// so SceneSync's choice of where it displaces (DisplacementMode, the type's mode) can be seen and counted.
module lift;

import materials;
__exported import custom.liftParameters;

[MaterialType("lift")]
public struct Lift : IMaterial
{
    public typealias Parameters = LiftParameters;

    public static Surface surface(LiftParameters p, MaterialRef m, SurfaceInput in)
    {
        Surface s = Surface.defaults(normalize(in.normal));
        s.albedo = 0;
        s.metallic = 1;
        s.emissive = p.glow;
        return s;
    }

    public override static Displaced displace(LiftParameters p, MaterialRef m, DisplacePoint in)
    {
        Displaced d = Displaced.of(in);
        d.position += p.offset;
        return d;
    }
};
