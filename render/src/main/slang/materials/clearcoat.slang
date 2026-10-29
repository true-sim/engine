// The clear coat feature, as glTF's KHR_materials_clearcoat: a glossy dielectric layer over any type's surface
// (car paint, varnish), lit by shading.slang's reflected() and ambient().
module clearcoat;

__exported import materials;

[MaterialFeature(FEATURE_CLEARCOAT)]
public struct ClearcoatParameters
{
    [Range(0, 1)] public float strength = 1;
    [Range(0, 1)] public float roughness = 0;           // perceptual
    [Texture(LINEAR)] public int strengthTexture = -1;  // its red times the strength
    [Texture(LINEAR)] public int roughnessTexture = -1; // its green times the roughness: often the same texture
    [Texture(LINEAR)] public int normalTexture = -1;    // tangent space; none: the coat follows the geometry
    public float normalScale = 1;
};

/** Lays coat `c` over surface `s`. */
public void applyClearcoat(inout Surface s, ClearcoatParameters c, SurfaceInput in)
{
    float strength = c.strength;
    if (c.strengthTexture >= 0)
    {
        strength *= sampleTexture(c.strengthTexture, in.uv, in.dx, in.dy).r;
    }
    float roughness = c.roughness;
    if (c.roughnessTexture >= 0)
    {
        roughness *= sampleTexture(c.roughnessTexture, in.uv, in.dx, in.dy).g;
    }
    s.clearcoat = saturate(strength);
    s.clearcoatRoughness = clamp(roughness, 0.03, 1.0);
    s.clearcoatNormal = mapNormal(normalize(in.normal), in.tangent, c.normalTexture, c.normalScale, in.uv, in.dx,
        in.dy);
}
