// The unlit feature, as glTF's KHR_materials_unlit: the surface shows its base color (the type's albedo, with its
// texture and the vertex color) as it is, unlit: baked lighting, stylized art, sky domes.
module unlit;

__exported import materials;

[MaterialFeature(FEATURE_UNLIT)]
public struct UnlitParameters
{
    [Range(0, 1)] public float amount = 1; // 1: unlit; between: blended with the lit color
};

/** The color of a surface showing feature `u`: its albedo, blended with `lit`, the lit color, by the amount. */
public float3 applyUnlit(float3 lit, Surface s, UnlitParameters u)
{
    return lerp(lit, s.albedo, u.amount);
}
