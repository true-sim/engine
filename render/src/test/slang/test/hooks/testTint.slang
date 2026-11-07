// A test's surface hook, as a library's would be (ShadingHooksTest): every surface it reaches takes its parameters'
// tint, and a coat of its strength where the material lets hooks coat it. It sets the alpha to 0, which the dispatch
// must undo: surface hooks never change what covers what.
module testTint;

import hooks;

[ShadingHookParameters]
public struct TestTintParameters
{
    public float3 tint = float3(1, 1, 1);
    public float coat;
};

[ShadingHook("test.tint")]
[ShadingHookBefore("test.fog")]
public struct TestTint : ISurfaceHook
{
    public typealias Parameters = TestTintParameters;

    public static void surface(ShadingPoint p, TestTintParameters* tint, inout Surface s)
    {
        s.albedo *= tint[0].tint;
        s.alpha = 0;
        if ((p.flags & SHADING_COAT_ALLOWED) != 0)
        {
            s.clearcoat = tint[0].coat;
            s.clearcoatNormal = s.normal;
            s.clearcoatRoughness = 0;
        }
    }
};
