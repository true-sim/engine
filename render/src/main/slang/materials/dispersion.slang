// The dispersion feature, as glTF's KHR_materials_dispersion: the index of refraction differs by wavelength, so light
// refracted through a volume spreads into colors, as through prisms and gems. 20 / Abbe number: 0 none, 0.33 a crown
// glass, 1 a dense flint. Only transmission through a volume (FEATURE_TRANSMISSION, FEATURE_VOLUME) shows it.
module dispersion;

__exported import materials;

[MaterialFeature(FEATURE_DISPERSION)]
public struct DispersionParameters
{
    [Range(0, 10)] public float dispersion = 0;
};

/** Sets surface `s`'s dispersion from `p`. */
public void applyDispersion(inout Surface s, DispersionParameters p)
{
    s.dispersion = max(p.dispersion, 0.0);
}
