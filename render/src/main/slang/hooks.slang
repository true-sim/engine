// Shading hooks: what a library adds to every way of shading without the core knowing it (README, "Writing a shading
// hook"). A hook is a struct marked [ShadingHook("name")] conforming to the interface of each hook point it has, and
// naming its parameters, `typealias Parameters = X;`, X a [ShadingHookParameters] record (any size; a Java record is
// generated for it, as for material records), which it gets by pointer, never null:
//
//   ISurfaceHook   static void surface(ShadingPoint p, X* params, inout Surface s)
//       changes the surface before it is lit (wetness, snow, dust): in world terms, the same for every view; at
//       rasterized pixels, the visibility buffer's, ray hits and probe hits alike; never its alpha. Materials and
//       instances may opt out (MATERIAL_NO_SURFACE_HOOKS, INSTANCE_NO_SURFACE_HOOKS); a coat of its own (a water
//       layer: s.clearcoat) only where the material allows it (p.flags & SHADING_COAT_ALLOWED).
//   IIndirectHook  static void indirect(ShadingPoint p, X* params, AroundQuery q, inout AroundLight light)
//       the light from all around the point, only what the query wants (AROUND_*): diffuse light (an irradiance
//       volume, probes), specular radiance along q.direction at q.roughness, how much of the environment the point
//       sees around q.direction (specular occlusion), and the same for the coat's direction and the back side;
//   IMediaHook     static void media(ShadingPoint p, X* params, inout ViewRay ray)
//       what lies between the camera and the point: fog, aerial perspective, volumetric media; the sky's points are at
//       infinity (p.path == SHADING_SKY).
//
// Hooks may import hooks, bindless, shading and lights (and their own modules); nothing of the engine's shading paths.
// The build (truesim.slang) merges the hooks of a project and its libraries into the dispatch indirect.slang includes
// (shading-hooks/dispatch.slang, from this directory's shading-hooks templates), in their order ([ShadingHookAfter],
// [ShadingHookBefore]). Each hook runs under a bool specialization constant of its own (id 1024 + its index: compiled
// out where a pipeline is made without it) and its bit of the view's hook block (ShadingHookBlock: on or off from
// frame to frame without new pipelines). Its parameters are a record in that block, which the engine writes each frame
// (engine-render's ShadingHooks).
module hooks;

import bindless;
__exported import shading;

/** Marks a struct as a shading hook named `name` (truesim.slang's ShadingHooks). */
[__AttributeUsage(_AttributeTargets.Struct)]
public struct ShadingHookAttribute
{
    string name;
};

/** Marks a struct as a shading hook's parameters: a record, generated as Java as material records are. */
[__AttributeUsage(_AttributeTargets.Struct)]
public struct ShadingHookParametersAttribute {};

/** Orders a shading hook after the hook `name` (where there is one). */
[__AttributeUsage(_AttributeTargets.Struct)]
public struct ShadingHookAfterAttribute
{
    string name;
};

/** Orders a shading hook before the hook `name` (where there is one). */
[__AttributeUsage(_AttributeTargets.Struct)]
public struct ShadingHookBeforeAttribute
{
    string name;
};

// ShadingPoint.path: the way the point is shaded.
/** A rasterized pixel, forward or by the visibility buffer. */
public static const uint SHADING_RASTER = 0;
/** A pixel of a screen-space pass composing over the shaded scene (ambient occlusion, reflections). */
public static const uint SHADING_COMPOSITE = 1;
/** A ray's hit (the reference path tracer). */
public static const uint SHADING_RAY_HIT = 2;
/** A probe ray's hit (global illumination's probes and cascades). */
public static const uint SHADING_PROBE_HIT = 3;
/** The sky behind everything: the point is at infinity along -toViewer. */
public static const uint SHADING_SKY = 4;

// ShadingPoint.flags.
/** The material lets surface hooks put a coat of their own on it (s.clearcoat: a water layer). */
public static const uint SHADING_COAT_ALLOWED = 1;

// Opting out of surface hooks, and into their coats (engine-render's Material.surfaceHooks, Material.hookCoat; the
// instance's flag from its renderable).
/** A material's header bit: surface hooks leave it alone. */
public static const uint MATERIAL_NO_SURFACE_HOOKS = 1u << 11;
/** A material's header bit: surface hooks may put a coat of their own on it (SHADING_COAT_ALLOWED). */
public static const uint MATERIAL_HOOK_COAT = 1u << 12;
/** An instance's mesh word bit (meshlets/common.slang's INSTANCE_*): surface hooks leave it alone. */
public static const uint INSTANCE_NO_SURFACE_HOOKS = 1u << 26;

/** A ShadingPoint.pixel off the screen (ray and probe hits). */
public static const uint2 NO_PIXEL = uint2(0xffffffffu, 0xffffffffu);

/** The point being shaded, as a hook sees it. Positions and directions camera-relative, world axes. */
public struct ShadingPoint
{
    public float3 position;        // the point, from the view's camera
    public float3 normal;          // unit: the side shaded (the material's normal, after normal maps)
    public float3 geometricNormal; // unit: the triangle's, on the same side
    public float3 toViewer;        // unit: towards the camera (or the ray's origin)
    public float3 camera;          // the camera's world position, high part: world = camera + cameraLow + position
    public float3 cameraLow;       // and the rest
    public uint2 pixel;            // the pixel shaded, or NO_PIXEL
    public uint path;              // SHADING_*: how it is shaded
    public uint flags;             // SHADING_COAT_ALLOWED

    /** The point's world position (single precision: far from the origin, prefer `camera`, `cameraLow` apart). */
    public float3 world()
    {
        return camera + (cameraLow + position);
    }
};

// AroundQuery.wants: what an indirect hook is asked for.
/** Diffuse light around the normal (AroundLight.diffuse, coverage). */
public static const uint AROUND_DIFFUSE = 1;
/** Specular radiance along the query's direction at its roughness (AroundLight.specular, specularCoverage). */
public static const uint AROUND_SPECULAR = 2;
/** How much of the environment the point sees around the direction (AroundLight.visibility). */
public static const uint AROUND_VISIBILITY = 4;
/** Visibility around the coat's direction too (AroundLight.coatVisibility). */
public static const uint AROUND_COAT = 8;
/** Diffuse light around the back side, -normal (AroundLight.back, backCoverage): diffuse transmission. */
public static const uint AROUND_BACK = 16;

/** What a point asks its indirect hooks, once per point: only what it uses (`wants`, AROUND_*). */
public struct AroundQuery
{
    public float3 direction;       // unit: the mirror direction (or any the point needs the environment along)
    public float roughness;        // the lobe's along it (perceptual)
    public float3 coatDirection;   // AROUND_COAT: the coat's mirror direction
    public uint wants;             // AROUND_*

    public bool wanted(uint what)
    {
        return (wants & what) != 0;
    }
};

/**
 * The light from all around a point, as indirect hooks give it, in their order: the diffuse light (irradiance / pi)
 * they found and how much of it they cover (the environment's spherical harmonics light the rest: 0 none, 1 all); the
 * specular radiance along the query's direction and how much they cover (the environment's reflection, dimmed by
 * `visibility`, gives the rest); how much of the environment the point sees around the direction and the coat's; the
 * back side's diffuse light and coverage.
 */
public struct AroundLight
{
    public float3 diffuse;
    public float coverage;
    public float3 specular;
    public float specularCoverage;
    public float visibility;
    public float coatVisibility;
    public float3 back;
    public float backCoverage;

    /** Nothing found: the environment's alone. */
    public static AroundLight none()
    {
        AroundLight a;
        a.diffuse = 0;
        a.coverage = 0;
        a.specular = 0;
        a.specularCoverage = 0;
        a.visibility = 1;
        a.coatVisibility = 1;
        a.back = 0;
        a.backCoverage = 0;
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

    /** As add, for the specular radiance along the query's direction. */
    [mutating]
    public void addSpecular(float3 found, float share)
    {
        float take = (1 - specularCoverage) * share;
        specular += take * found;
        specularCoverage += take;
    }

    /** As add, for the back side's diffuse light. */
    [mutating]
    public void addBack(float3 found, float share)
    {
        float take = (1 - backCoverage) * share;
        back += take * found;
        backCoverage += take;
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

/** The surface hook point. */
public interface ISurfaceHook
{
    associatedtype Parameters;
    static void surface(ShadingPoint p, Parameters* params, inout Surface s);
};

/** The indirect hook point. */
public interface IIndirectHook
{
    associatedtype Parameters;
    static void indirect(ShadingPoint p, Parameters* params, AroundQuery q, inout AroundLight light);
};

/** The media hook point. */
public interface IMediaHook
{
    associatedtype Parameters;
    static void media(ShadingPoint p, Parameters* params, inout ViewRay ray);
};

/**
 * A view's hook block (engine-render's ShadingHooks, ShadingHookBlock in Java): this header, a uint of active bits
 * per 32 hooks (a hook runs where its bit is set), a uint per hook with the byte offset of its parameters from the
 * block's start, then the records, every hook's (its defaults where the view does not run it). Written by the CPU
 * each frame, a copy per frame slot; read a field at a time (pitfalls.md: no loads of whole structs through pointers).
 */
[HostLayout]
public struct ShadingHookBlock
{
    public float3 camera;          // the view's camera's world position, high part
    public uint hooks;             // how many hooks the block has room for: the listing's
    public float3 cameraLow;       // and the rest
    public uint pad;
};

/** The bytes of a block's header, before its active bits. */
public static const uint SHADING_HOOK_HEADER = 32;

/** Whether hook `index` runs in the view of `block` this frame. */
public bool hookActive(ShadingHookBlock* block, uint index)
{
    uint* words = (uint*) ((uint64_t) block + SHADING_HOOK_HEADER);
    return (words[index / 32] & (1u << (index % 32))) != 0;
}

/** The address of hook `index`'s parameters in `block`, of a listing of `count` hooks. */
public uint64_t hookRecord(ShadingHookBlock* block, uint index, uint count)
{
    uint* offsets = (uint*) ((uint64_t) block + SHADING_HOOK_HEADER + 4 * ((count + 31) / 32));
    return (uint64_t) block + offsets[index];
}

/** The point `position` etc. of the view of `block`, shaded by `path` (SHADING_*), as hooks see it. */
public ShadingPoint shadingPoint(ShadingHookBlock* block, float3 position, float3 normal, float3 geometricNormal,
    float3 toViewer, uint2 pixel, uint path, uint flags)
{
    ShadingPoint p;
    p.position = position;
    p.normal = normal;
    p.geometricNormal = geometricNormal;
    p.toViewer = toViewer;
    p.camera = block[0].camera;
    p.cameraLow = block[0].cameraLow;
    p.pixel = pixel;
    p.path = path;
    p.flags = flags;
    return p;
}
