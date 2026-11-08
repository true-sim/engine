// grass.slang's parameters, apart: the build reads material records before it writes the shading hooks' dispatch,
// so a record's module must not import `indirect` (the hooks' indices) as the type's may.
module grassParameters;

import materials;

[MaterialParameters(GEOMETRY_DISPLACE)]
public struct GrassParameters
{
    [Color] public float3 root = float3(0.05, 0.18, 0.03);
    [Color] public float3 tip = float3(0.35, 0.6, 0.12);
    public float rest = 0.45;     // the largest bend (radians, at the tip) a blade has in calm air; each its own share
    public float lean = 0.55;     // the steady bend (radians, at the tip) at wind strength 1: drag, so strength squared
    public float gust = 0.6;      // what the strongest gust adds (radians at strength 1, also by strength squared)
    public float stiffness = 1;   // bends are divided by it (each blade's own within 25 % of it)
    public float flutter = 0.2;   // the tips' twist (radians) at wind strength 1, 2 to 3.5 times a second
};
