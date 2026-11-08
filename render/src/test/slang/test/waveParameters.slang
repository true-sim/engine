// wave.slang's parameters, apart: the build reads material records before it writes the shading hooks' dispatch,
// so a record's module must not import `indirect` (the hooks' indices) as the type's may.
module waveParameters;

import materials;

[MaterialParameters(GEOMETRY_DISPLACE)]
public struct WaveParameters
{
    [Color] public float3 color = float3(0, 1, 0);
    public float3 offset = float3(0, 0, 0);
    public float3 swing = float3(0, 0, 0);
};
