// A test's media hook, as a library's would be (ShadingHooksTest): the light between the camera and every shaded
// point passes through its data's transmittance (values.rgb) and gains its in-scattered light (more.rgb).
module testFog;

import hooks;

[ShadingHook("test.fog")]
public struct TestFog
{
    public static void media(ShadingPoint p, inout ViewRay ray)
    {
        ray.through(p.data.values.rgb, p.data.more.rgb);
    }
};
