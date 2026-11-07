// The probe volume as a shading hook (hooks.slang): where a view's global illumination is a probe volume
// (engine-render's ProbeVolumeLighting, ProbeVolume, RadianceCascades), its diffuse light replaces the environment's
// spherical harmonics and its sky visibility dims the environment's reflections, where the volume reaches. Shading
// knows it only through the generated dispatch. It moves out of the engine with global illumination.
module hook;

import hooks;
import probes.volume;

/**
 * The probe volume's light at a shaded point. Its data: `address` the ProbeVolumeData, `values.xyz` the view's camera
 * less the volume's origin (world axes).
 */
[ShadingHook("probeVolume")]
public struct ProbeVolumeHook
{
    public static void indirect(ShadingPoint p, float3 direction, inout AroundLight light)
    {
        float sky;
        bool found;
        float3 e = probeLight((ProbeVolumeData*) p.data.address, p.position + p.data.values.xyz, p.normal,
            p.toViewer, direction, sky, found);
        if (found)
        {
            light.add(e, 1);
            light.visibility *= sky;
        }
    }
};
