// A test's media hook, as a library's would be (ShadingHooksTest): the light between the camera and every shaded
// point passes through its parameters' transmittance and gains their in-scattered light.
module testFog;

import hooks;

[ShadingHookParameters]
public struct TestFogParameters
{
    public float3 transmittance = float3(1, 1, 1);
    public float3 light;
};

[ShadingHook("test.fog")]
public struct TestFog
{
    public typealias Parameters = TestFogParameters;

    public static void media(ShadingPoint p, TestFogParameters fog, inout ViewRay ray)
    {
        ray.through(fog.transmittance, fog.light);
    }
};
