// For tests: a material type of a game's own, glowing in its base color whatever the light, shaded by type in the
// visibility buffer.
import visibility.resolve;

struct Glow : IMaterial
{
    static Surface surface(Material m, SurfaceInput in)
    {
        Surface s;
        s.albedo = 0;
        s.metallic = 1; // no diffuse, no specular: only the glow
        s.normal = normalize(in.normal);
        s.roughness = 1;
        s.emissive = m.baseColor.rgb; // the record read its own way
        s.occlusion = 1;
        return s;
    }
};

[shader("compute")]
[numthreads(8, 8, 1)]
void glowMain(uint3 group : SV_GroupID, uint3 thread : SV_GroupThreadID)
{
    shadeTiles<Glow>(NoRays(), group, thread);
}
