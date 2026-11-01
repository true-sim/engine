// The anisotropy feature, as glTF's KHR_materials_anisotropy: highlights stretched along a direction on the surface,
// as on brushed metal and hair. The direction is the tangent's turned by anisotropyRotation, and where there is a
// texture its red and green (a direction in tangent space), its blue scaling the strength.
module anisotropy;

__exported import materials;

[MaterialFeature(FEATURE_ANISOTROPY)]
public struct AnisotropyParameters
{
    [Range(0, 1)] public float anisotropyStrength = 0;
    [Range(-7, 7)] public float anisotropyRotation = 0;    // radians, counterclockwise from the tangent
    [Texture(LINEAR)] public int anisotropyTexture = -1;   // red, green: a direction; blue: times the strength
};

/** Sets surface `s`'s anisotropy from `p` at this point, its direction along the surface's tangent frame. */
public void applyAnisotropy(inout Surface s, AnisotropyParameters p, SurfaceInput in)
{
    float2 direction = float2(1, 0);
    float strength = p.anisotropyStrength;
    if (p.anisotropyTexture >= 0)
    {
        float3 texel = sampleTexture(p.anisotropyTexture, in).rgb;
        direction = normalize(texel.rg * 2 - 1 + float2(1.0e-6, 0));
        strength *= texel.b;
    }
    float c = cos(p.anisotropyRotation);
    float sn = sin(p.anisotropyRotation);
    direction = float2(c * direction.x - sn * direction.y, sn * direction.x + c * direction.y);
    float3 n = s.normal;
    float3 t;
    float3 b;
    if (dot(in.tangent.xyz, in.tangent.xyz) > 0)
    {
        t = normalize(in.tangent.xyz - n * dot(n, in.tangent.xyz));
        b = cross(n, t) * (in.tangent.w < 0 ? -1.0 : 1.0);
    }
    else
    {
        // No tangents: any frame along the surface (the mesh gives the direction no meaning)
        t = normalize(cross(abs(n.y) < 0.99 ? float3(0, 1, 0) : float3(1, 0, 0), n));
        b = cross(n, t);
    }
    s.anisotropyDirection = normalize(direction.x * t + direction.y * b);
    s.anisotropy = saturate(strength);
}
