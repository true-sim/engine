// For tests and the benchmark (DisplacementTest, DisplacementBenchmark): grass blades bent by the scene's wind (the
// test.wind hook's record): each vertex moves along the wind by its height on the blade squared (its texture's v, 0 at
// the root, 1 at the tip) times the blade's `bend`, the gusts a wave travelling along the wind in world space, so
// neighbours sway together; normals tilt with the bend. The material declares the bend as its largest displacement.
module grass;

import materials;
import test.hooks.testWind;
__exported import test.grassParameters;

/** test.wind's index in the shading hooks' dispatch, resolved when a shader links (hooks.properties). */
extern static const uint RUN_HOOK_TEST_WIND_INDEX;

[MaterialType("grass")]
public struct Grass : IMaterial
{
    public typealias Parameters = GrassParameters;

    public static Surface surface(GrassParameters p, MaterialRef m, SurfaceInput in)
    {
        Surface s = Surface.defaults(normalize(in.normal));
        s.albedo = lerp(p.root, p.tip, saturate(in.uv.y));
        s.roughness = 0.8;
        return s;
    }

    public override static Displaced displace(GrassParameters p, MaterialRef m, DisplacePoint in)
    {
        Displaced d = Displaced.of(in);
        if (!in.hookOn(RUN_HOOK_TEST_WIND_INDEX))
        {
            return d;
        }
        TestWindParameters* wind = in.hook<TestWindParameters>(RUN_HOOK_TEST_WIND_INDEX);
        float3 direction = wind[0].direction;
        float strength = wind[0].strength;
        float time = wind[0].time;
        float frequency = wind[0].frequency;
        float wavelength = wind[0].wavelength;
        // The gust at the blade's root, a wave along the wind in world space (anchored at the instance's origin)
        float3 world = in.origin + in.originLow;
        float along = dot(world.xz, normalize(direction.xz + 1e-6));
        float gust = 0.5 + 0.5 * sin(6.2831853 * (along / wavelength - time * frequency));
        float amount = strength * (p.stiffness + (1 - p.stiffness) * gust);
        float h = saturate(in.uv.y);
        float3 push = in.toMesh(direction) * (p.bend * amount * h * h);
        d.position += push;
        // The bend's slope tilts the normal: d(push)/dh = 2 h along the wind, the blade's up axis is mesh y
        d.normal = normalize(in.normal - in.toMesh(direction) * (2 * h * p.bend * amount) * in.normal.y);
        return d;
    }
};
