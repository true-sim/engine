// Metallic-roughness, as glTF: a base color, metalness, roughness, a tangent-space normal map, occlusion and
// emission, each a factor times an optional texture. Type 0, every material's unless it names another. Other types
// may nest its parameters and call evaluate(), or its pieces, to build on it.
module metallicRoughness;

__exported import materials;

[MaterialParameters(FEATURE_CLEARCOAT)]
public struct MetallicRoughnessParameters
{
    [Color] [MaskAlpha] public float4 baseColor = float4(0.8, 0.8, 0.8, 1); // linear; alpha: what masking compares
    [Texture(SRGB)] [MaskTexture] public int baseColorTexture = -1;
    [Range(0, 1)] public float roughness = 0.7;                            // perceptual; times the ORM's green
    [Range(0, 1)] public float metallic = 0;                               // times the ORM's blue
    [Texture(LINEAR)] public int ormTexture = -1;                          // occlusion, roughness, metalness in rgb
    [Range(0, 1)] public float occlusion = 1;                              // how strongly the ORM's red darkens
    [Texture(LINEAR)] public int normalTexture = -1;                       // tangent space, xyz in rgb
    public float normalScale = 1;                                          // how strongly it bends the normal
    [Color] public float3 emissive = float3(0, 0, 0);                      // linear
    [Texture(SRGB)] public int emissiveTexture = -1;
};

/** The base color: factor, times the texture's, times the vertex color. */
public float4 baseColor(MetallicRoughnessParameters p, SurfaceInput in)
{
    float4 base = p.baseColor * in.color;
    if (p.baseColorTexture >= 0)
    {
        base *= sampleTexture(p.baseColorTexture, in);
    }
    return base;
}

/** Occlusion (1: none), perceptual roughness and metalness: factors times the ORM texture's. */
public float3 orm(MetallicRoughnessParameters p, SurfaceInput in)
{
    float3 result = float3(1, p.roughness, p.metallic);
    if (p.ormTexture >= 0)
    {
        float3 texel = sampleTexture(p.ormTexture, in).rgb;
        result = float3(lerp(1.0, texel.r, p.occlusion), result.y * texel.g, result.z * texel.b);
    }
    return result;
}

/** The unit normal, bent by the normal map. */
public float3 normal(MetallicRoughnessParameters p, SurfaceInput in)
{
    return mapNormal(normalize(in.normal), in.tangent, p.normalTexture, p.normalScale, in);
}

/** The light it gives off: factor times the texture's. */
public float3 emission(MetallicRoughnessParameters p, SurfaceInput in)
{
    float3 emissive = p.emissive;
    if (p.emissiveTexture >= 0)
    {
        emissive *= sampleTexture(p.emissiveTexture, in).rgb;
    }
    return emissive;
}

/** The surface of `p` at this point. */
public Surface evaluate(MetallicRoughnessParameters p, SurfaceInput in)
{
    float3 o = orm(p, in);
    Surface s = Surface.defaults(normal(p, in));
    float4 base = baseColor(p, in);
    s.albedo = base.rgb;
    s.alpha = base.a;
    s.metallic = saturate(o.z);
    s.roughness = clamp(o.y, 0.03, 1.0); // a perfect mirror's highlight would be infinitely small
    s.emissive = emission(p, in);
    s.occlusion = o.x;
    return s;
}

[MaterialType("metallicRoughness")]
public struct MetallicRoughnessMaterial : IMaterial
{
    public typealias Parameters = MetallicRoughnessParameters;

    public static Surface surface(MetallicRoughnessParameters p, MaterialRef m, SurfaceInput in)
    {
        return evaluate(p, in);
    }
};
