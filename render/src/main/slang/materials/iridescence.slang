// The iridescence feature, as glTF's KHR_materials_iridescence: a thin film over the surface, whose interference
// colors its reflections by the angle seen and the film's thickness (between the minimum and maximum, by the thickness
// texture's green; the maximum without one), as soap bubbles, oil slicks and beetles.
module iridescence;

__exported import materials;

[MaterialFeature(FEATURE_IRIDESCENCE)]
public struct IridescenceParameters
{
    [Range(0, 1)] public float iridescenceFactor = 0;
    [Texture(LINEAR)] public int iridescenceTexture = -1;          // its red times the factor
    [Range(1, 5)] public float iridescenceIor = 1.3;
    [Range(0, 10000)] public float iridescenceThicknessMinimum = 100; // nanometres
    [Range(0, 10000)] public float iridescenceThicknessMaximum = 400;
    [Texture(LINEAR)] public int iridescenceThicknessTexture = -1; // its green between the minimum and maximum
};

/** Sets surface `s`'s thin film from `p` at this point (shading evaluates its reflectance, seeing the angle). */
public void applyIridescence(inout Surface s, IridescenceParameters p, SurfaceInput in)
{
    float factor = p.iridescenceFactor;
    if (p.iridescenceTexture >= 0)
    {
        factor *= sampleTexture(p.iridescenceTexture, in).r;
    }
    float thickness = p.iridescenceThicknessMaximum;
    if (p.iridescenceThicknessTexture >= 0)
    {
        thickness = lerp(p.iridescenceThicknessMinimum, p.iridescenceThicknessMaximum,
            sampleTexture(p.iridescenceThicknessTexture, in).g);
    }
    s.iridescence = saturate(factor);
    s.iridescenceIor = p.iridescenceIor;
    s.iridescenceThickness = thickness;
}
