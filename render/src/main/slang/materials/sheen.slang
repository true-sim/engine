// The sheen feature, as glTF's KHR_materials_sheen: light scattered back at grazing angles by fibers, as on cloth and
// velvet, lit by shading.slang's reflected() and ambient().
module sheen;

__exported import materials;

[MaterialFeature(FEATURE_SHEEN)]
public struct SheenParameters
{
    [Color] public float3 sheenColorFactor = float3(0, 0, 0); // linear; 0: none
    [Texture(SRGB)] public int sheenColorTexture = -1;         // its rgb times the color
    [Range(0, 1)] public float sheenRoughnessFactor = 0;       // perceptual
    [Texture(LINEAR)] public int sheenRoughnessTexture = -1;   // its alpha times the roughness
};

/** Lays sheen `p` over surface `s` at this point. */
public void applySheen(inout Surface s, SheenParameters p, SurfaceInput in)
{
    float3 color = p.sheenColorFactor;
    if (p.sheenColorTexture >= 0)
    {
        color *= sampleTexture(p.sheenColorTexture, in).rgb;
    }
    float roughness = p.sheenRoughnessFactor;
    if (p.sheenRoughnessTexture >= 0)
    {
        roughness *= sampleTexture(p.sheenRoughnessTexture, in).a;
    }
    s.sheenColor = color;
    s.sheenRoughness = clamp(roughness, 0.07, 1.0);
}
