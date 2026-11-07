// A test's indirect hook, as a library's would be (ShadingHooksTest): the diffuse light from all around every shaded
// point is its parameters' (covering all of it), and the environment's reflections are seen by their visibility.
module testGlow;

import hooks;

/** Nested, as a record may be: the light. */
[ShadingHookParameters]
public struct TestGlowLight
{
    public float3 diffuse;
};

[ShadingHookParameters]
public struct TestGlowParameters
{
    public TestGlowLight light;
    public float visibility = 1;
};

[ShadingHook("test.glow")]
public struct TestGlow : IIndirectHook
{
    public typealias Parameters = TestGlowParameters;

    public static void indirect(ShadingPoint p, TestGlowParameters* glow, AroundQuery q, inout AroundLight light)
    {
        light.add(glow[0].light.diffuse, 1);
        light.addBack(glow[0].light.diffuse, 1);
        light.visibility *= glow[0].visibility;
        light.coatVisibility *= glow[0].visibility;
    }
};
