// Shading hooks: what a library adds to every way of shading without the core knowing it (README, "Writing a shading
// hook"). A hook is a struct marked [ShadingHook("name")] with a static function per hook point it has, and may name
// its parameters, `typealias Parameters = X;`, X a [ShadingHookParameters] record (any size; a Java record is generated
// for it, as for material records):
//
//   static void indirect(ShadingPoint p, X params, float3 direction, inout AroundLight light)
//       the light from all around the point: diffuse light where the hook has some (an irradiance volume, probes),
//       and how much of the environment the point sees around `direction` (specular occlusion);
//   static void media(ShadingPoint p, X params, inout ViewRay ray)
//       what lies between the camera and the point: fog, aerial perspective, volumetric media.
//
// (without parameters, the same without `params`). The build (truesim.slang) merges the hooks of a project and its
// libraries into the dispatch indirect.slang includes (shading-hooks/dispatch.slang, from this directory's
// shading-hooks templates); each hook runs under its bit of the specialization constants SHADING_HOOKS_<n> (as many
// as the hooks need), which a view's pipelines are made with: a hook the view does not use is compiled out. Its
// parameters for the view are a record its technique writes each frame, whose address the view's indirect-light
// table holds after its fields, one per hook (engine-render's ShadingHooks.add); the dispatch loads them field by
// field.
module hooks;

import bindless;

/** Marks a struct as a shading hook named `name` (truesim.slang's ShadingHooks). */
[__AttributeUsage(_AttributeTargets.Struct)]
public struct ShadingHookAttribute
{
    string name;
};

/** Marks a struct as a shading hook's parameters: a record, generated as Java as material records are. */
[__AttributeUsage(_AttributeTargets.Struct)]
public struct ShadingHookParametersAttribute {};

/** The point being shaded, as a hook sees it. Positions and directions camera-relative, world axes. */
public struct ShadingPoint
{
    public float3 position;    // the point, from the view's camera
    public float3 normal;      // unit: the side lit
    public float3 toViewer;    // unit
};

/**
 * The light from all around a point, as indirect hooks give it, in their order: the diffuse light (irradiance / pi)
 * they found, how much of it they cover (the environment's spherical harmonics light the rest: 0 none, 1 all), and how
 * much of the environment the point sees around the direction asked (it multiplies the environment's reflections).
 */
public struct AroundLight
{
    public float3 diffuse;
    public float coverage;
    public float visibility;

    /** Nothing found: the environment's alone. */
    public static AroundLight none()
    {
        AroundLight a;
        a.diffuse = 0;
        a.coverage = 0;
        a.visibility = 1;
        return a;
    }

    /**
     * Adds `found` (irradiance / pi) covering `share` of what is left uncovered: a hook's diffuse light where an
     * earlier one has not given all of it.
     */
    [mutating]
    public void add(float3 found, float share)
    {
        float take = (1 - coverage) * share;
        diffuse += take * found;
        coverage += take;
    }
};

/**
 * The light leaving a shaded point towards the camera, as media hooks change it on its way: the radiance arriving at
 * the camera, and the transmittance of the media between (what any later change of the point's own light, such as
 * screen-space reflections replacing its environment reflection, is multiplied by). A medium of transmittance T
 * adding in-scattered light L makes radiance * T + L, transmittance * T.
 */
public struct ViewRay
{
    public float3 radiance;
    public float3 transmittance;

    /** Through medium of transmittance `t`, gaining in-scattered light `light`. */
    [mutating]
    public void through(float3 t, float3 light)
    {
        radiance = radiance * t + light;
        transmittance *= t;
    }
};
