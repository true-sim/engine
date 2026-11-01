// The volume feature, as glTF's KHR_materials_volume: a transmissive surface encloses a volume of this thickness (in the
// mesh's units), through which light refracts by the surface's index of refraction and is attenuated (Beer's law)
// towards attenuationColor over attenuationDistance. Without the transmission feature it shows nothing.
module volume;

__exported import materials;

[MaterialFeature(FEATURE_VOLUME)]
public struct VolumeParameters
{
    [Range(0, 1000000)] public float thicknessFactor = 0;     // 0: thin-walled
    [Texture(LINEAR)] public int thicknessTexture = -1;      // its green times the factor
    [Range(0, 1000000)] public float attenuationDistance = 0; // 0: no attenuation (glTF's infinity)
    [Color] public float3 attenuationColor = float3(1, 1, 1); // linear
};

/** Sets surface `s`'s volume from `p` at this point. */
public void applyVolume(inout Surface s, VolumeParameters p, SurfaceInput in)
{
    float thickness = p.thicknessFactor;
    if (p.thicknessTexture >= 0)
    {
        thickness *= sampleTexture(p.thicknessTexture, in).g;
    }
    s.thickness = max(thickness, 0.0);
    s.attenuationColor = p.attenuationColor;
    s.attenuationDistance = p.attenuationDistance;
}
