// The diffuse transmission feature, as glTF's KHR_materials_diffuse_transmission: part of the light a surface would
// scatter diffusely passes through it instead, scattered on the other side and tinted, as through leaves, paper and
// lampshades: lit from behind, such a surface glows.
module diffuseTransmission;

__exported import materials;

[MaterialFeature(FEATURE_DIFFUSE_TRANSMISSION)]
public struct DiffuseTransmissionParameters
{
    [Range(0, 1)] public float diffuseTransmissionFactor = 0;
    [Texture(LINEAR)] public int diffuseTransmissionTexture = -1;      // its alpha times the factor
    [Color] public float3 diffuseTransmissionColorFactor = float3(1, 1, 1); // linear
    [Texture(SRGB)] public int diffuseTransmissionColorTexture = -1;   // its rgb times the color
};

/** Sets how much of surface `s`'s diffuse light passes through, and its tint, from `p` at this point. */
public void applyDiffuseTransmission(inout Surface s, DiffuseTransmissionParameters p, SurfaceInput in)
{
    float factor = p.diffuseTransmissionFactor;
    if (p.diffuseTransmissionTexture >= 0)
    {
        factor *= sampleTexture(p.diffuseTransmissionTexture, in).a;
    }
    float3 color = p.diffuseTransmissionColorFactor;
    if (p.diffuseTransmissionColorTexture >= 0)
    {
        color *= sampleTexture(p.diffuseTransmissionColorTexture, in).rgb;
    }
    s.diffuseTransmission = saturate(factor);
    s.diffuseTransmissionColor = color;
}
