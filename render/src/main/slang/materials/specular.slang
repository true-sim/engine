// The specular feature, as glTF's KHR_materials_specular: a dielectric's reflectance scaled by a strength (also its
// reflectance at grazing angles) and tinted by a color, each with a texture. Applied after the IOR feature.
module specular;

__exported import materials;

[MaterialFeature(FEATURE_SPECULAR)]
public struct SpecularParameters
{
    [Range(0, 1)] public float specularFactor = 1;
    [Texture(LINEAR)] public int specularTexture = -1;          // its alpha times the factor
    [Color] public float3 specularColorFactor = float3(1, 1, 1); // linear
    [Texture(SRGB)] public int specularColorTexture = -1;        // its rgb times the color
};

/** Scales and tints surface `s`'s dielectric reflectance by `p` at this point. */
public void applySpecular(inout Surface s, SpecularParameters p, SurfaceInput in)
{
    float strength = p.specularFactor;
    if (p.specularTexture >= 0)
    {
        strength *= sampleTexture(p.specularTexture, in).a;
    }
    float3 color = p.specularColorFactor;
    if (p.specularColorTexture >= 0)
    {
        color *= sampleTexture(p.specularColorTexture, in).rgb;
    }
    s.f0 = min(s.f0 * color, float3(1)) * strength;
    s.f90 = strength;
}
