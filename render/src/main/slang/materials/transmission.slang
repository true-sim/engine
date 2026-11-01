// The transmission feature, as glTF's KHR_materials_transmission: how much of the light the surface would scatter
// diffusely passes through it instead, as through glass, showing the scene behind (refracted and tinted by the volume
// feature, blurred by the roughness). Shading samples that scene from a copy of the opaque scene's color.
module transmission;

__exported import materials;

[MaterialFeature(FEATURE_TRANSMISSION)]
public struct TransmissionParameters
{
    [Range(0, 1)] public float transmissionFactor = 0;
    [Texture(LINEAR)] public int transmissionTexture = -1; // its red times the factor
};

/** Sets how much of surface `s` lets light through, by `p` at this point. */
public void applyTransmission(inout Surface s, TransmissionParameters p, SurfaceInput in)
{
    float transmission = p.transmissionFactor;
    if (p.transmissionTexture >= 0)
    {
        transmission *= sampleTexture(p.transmissionTexture, in).r;
    }
    s.transmission = saturate(transmission);
}
