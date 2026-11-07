// A test's indirect hook, as a library's would be (ShadingHooksTest): the diffuse light from all around every shaded
// point is its data's (values.rgb, covering all of it), and the environment's reflections are seen by values.w.
module testGlow;

import hooks;

[ShadingHook("test.glow")]
public struct TestGlow
{
    public static void indirect(ShadingPoint p, float3 direction, inout AroundLight light)
    {
        light.add(p.data.values.rgb, 1);
        light.visibility *= p.data.values.w;
    }
};
