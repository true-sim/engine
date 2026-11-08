// lift.slang's parameters, apart: the build reads material records before the shading hooks' dispatch is written.
module liftParameters;

import materials;

[MaterialParameters(GEOMETRY_DISPLACE)]
public struct LiftParameters
{
    [Color] public float3 glow = float3(1, 0, 0);
    public float3 offset = float3(0, 0, 0); // every vertex moved by it, in mesh units
};
