// For tests (DisplacementTest): a material type displacing its vertices (GEOMETRY_DISPLACE), glowing so its surface is
// easy to find: every vertex moved by `offset` (mesh units), plus `swing` times the sine of the scene's wind time
// (test.wind's record, where that hook is on) times its frequency (radians a second).
module wave;

import materials;
import test.hooks.testWind;
__exported import test.waveParameters;

/** test.wind's index in the shading hooks' dispatch, resolved when a shader links (hooks.properties). */
extern static const uint RUN_HOOK_TEST_WIND_INDEX;

[MaterialType("wave")]
public struct Wave : IMaterial
{
    public typealias Parameters = WaveParameters;

    public static Surface surface(WaveParameters p, MaterialRef m, SurfaceInput in)
    {
        Surface s = Surface.defaults(normalize(in.normal));
        s.albedo = 0.5;
        s.roughness = 1;
        s.emissive = p.color;
        return s;
    }

    public override static Displaced displace(WaveParameters p, MaterialRef m, DisplacePoint in)
    {
        Displaced d = Displaced.of(in);
        float3 move = p.offset;
        if (in.hookOn(RUN_HOOK_TEST_WIND_INDEX))
        {
            TestWindParameters* wind = in.hook<TestWindParameters>(RUN_HOOK_TEST_WIND_INDEX);
            move += p.swing * sin(wind[0].time * wind[0].frequency);
        }
        d.position += move;
        return d;
    }
};
