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
public struct ProbeVolumeHook : IIndirectHook
{
    public typealias Parameters = ProbeVolumeParameters;

    public static void indirect(ShadingPoint p, ProbeVolumeParameters* probes, AroundQuery q,
        inout AroundLight light)
    {
        uint64_t address = probes[0].volume;
        if (address == 0)
        {
            return; // no volume this frame (the hook's defaults)
        }
        ProbeVolumeData* volume = (ProbeVolumeData*) address;
        float3 at = p.position + probes[0].offset;
        float sky;
        float coverage; // of the point's light: the environment's the rest
        if (q.wanted(AROUND_DIFFUSE | AROUND_VISIBILITY))
        {
            float3 e = probeLight(volume, at, p.normal, p.toViewer, q.direction, sky, coverage);
            if (coverage > 0)
            {
                if (q.wanted(AROUND_DIFFUSE))
                {
                    light.add(e, coverage);
                }
                light.visibility *= lerp(1, sky, coverage);
            }
        }
        if (q.wanted(AROUND_COAT))
        {
            probeLight(volume, at, p.normal, p.toViewer, q.coatDirection, sky, coverage);
            if (coverage > 0)
            {
                light.coatVisibility *= lerp(1, sky, coverage);
            }
        }
        if (q.wanted(AROUND_BACK))
        {
            float3 e = probeLight(volume, at, -p.normal, p.toViewer, -p.normal, sky, coverage);
            if (coverage > 0)
            {
                light.addBack(e, coverage);
            }
        }
    }
};
