// A test's geometry hook (DisplacementTest), as a weather library's wind would be: no function, a record the scene's
// hook block carries for material types' displace() (wave.slang, grass.slang) to read.
module testWind;

import hooks;

[ShadingHookParameters]
public struct TestWindParameters
{
    public float3 direction = float3(1, 0, 0); // where it blows, in the world
    public float strength = 1;
    public float time = 0;                     // seconds: what animates the displacement
    public float frequency = 1;                // gusts per second
    public float wavelength = 8;               // world units between gust fronts
};

[ShadingHook("test.wind")]
public struct TestWind : IGeometryHook
{
    public typealias Parameters = TestWindParameters;
};
