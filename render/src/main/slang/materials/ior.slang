// The IOR feature, as glTF's KHR_materials_ior: the index of refraction a dielectric's reflectance follows, head-on
// ((ior - 1) / (ior + 1))^2: 4 % at 1.5, the default.
module ior;

__exported import materials;

[MaterialFeature(FEATURE_IOR)]
public struct IorParameters
{
    [Range(1, 5)] public float ior = 1.5;
};

/** Sets surface `s`'s dielectric reflectance from `p`'s index of refraction. */
public void applyIor(inout Surface s, IorParameters p)
{
    float r = (p.ior - 1) / (p.ior + 1);
    s.f0 = r * r;
}
