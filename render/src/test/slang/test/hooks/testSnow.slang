// A test's second surface hook, as a library's would be (ShadingHooksTest): snow whitens every surface it reaches by
// its parameters' amount; a material refusing it keeps its color while other surface hooks still change it.
module testSnow;

import hooks;

[ShadingHookParameters]
public struct TestSnowParameters
{
    public float amount = 1;
};

[ShadingHook("test.snow")]
public struct TestSnow : ISurfaceHook
{
    public typealias Parameters = TestSnowParameters;

    public static void surface(ShadingPoint p, TestSnowParameters* snow, inout Surface s)
    {
        s.albedo = lerp(s.albedo, float3(1, 1, 1), snow[0].amount);
    }
};
