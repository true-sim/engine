// The probe volume as a shading hook (hooks.slang): where a view's global illumination is a probe volume
// (engine-render's ProbeVolumeLighting, ProbeVolume, RadianceCascades), its diffuse light replaces the environment's
// spherical harmonics and its sky visibility dims the environment's reflections, where the volume reaches. Shading
// knows it only through the generated dispatch. It moves out of the engine with global illumination.
module hook;

import bindless;
import hooks;
import probes.volume;

/** The probe volume hook's parameters for a view. */
[ShadingHookParameters]
[HostLayout]
public struct ProbeVolumeParameters
{
    public uint64_t volume;        // the ProbeVolumeData, as its frame wrote it
    public float3 offset;          // the view's camera less the volume's origin (world axes)
};

/** The probe volume's light at a shaded point. */
[ShadingHook("probeVolume")]
public struct ProbeVolumeHook
{
    public typealias Parameters = ProbeVolumeParameters;

    public static void indirect(ShadingPoint p, ProbeVolumeParameters probes, float3 direction,
        inout AroundLight light)
    {
        float sky;
        bool found;
        float3 e = probeLight((ProbeVolumeData*) probes.volume, p.position + probes.offset, p.normal, p.toViewer,
            direction, sky, found);
        if (found)
        {
            light.add(e, 1);
            light.visibility *= sky;
        }
    }
};
