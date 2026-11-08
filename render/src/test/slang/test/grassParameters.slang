// grass.slang's parameters, apart: the build reads material records before it writes the shading hooks' dispatch,
// so a record's module must not import `indirect` (the hooks' indices) as the type's may.
module grassParameters;

import materials;

[MaterialParameters(GEOMETRY_DISPLACE)]
public struct GrassParameters
{
    [Color] public float3 root = float3(0.05, 0.18, 0.03);
    [Color] public float3 tip = float3(0.35, 0.6, 0.12);
    public float bend = 0.3;      // how far a tip moves at full wind (mesh units)
    public float stiffness = 0.6; // of the bend, what a calm leaves
};
