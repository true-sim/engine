// For the benchmark of hit shading (design D5): every hit shaded by one shader, a switch over the material's type
// calling each type's own shading (the same shadeHit<M> as the types' generated hit entry points), its features read
// from the material at run time: the shader the build would generate over the types the benchmark registers, in their
// order (0 metallic-roughness, then glow, tinted, car paint, dissolve). Against hits binned by shading bin, a dispatch
// per bin (ReferencePathTracer's own way).
import pathtracing.paths;
import test.glow;
import test.tinted;
import test.carPaint;
import test.dissolve;

[shader("compute")]
[numthreads(64, 1, 1)]
void singleHitMain(uint3 group : SV_GroupID, uint3 thread : SV_GroupThreadID)
{
    uint index = pathIndex(group, thread);
    if (index >= push.counts[pushBounce()])
    {
        return;
    }
    PathState path = push.paths[index];
    if (path.bin == NO_BIN)
    {
        return;
    }
    Material m = push.scene[0].materials[triangleMaterial((RayScene*)push.rays, path.instance, path.primitive)];
    uint features = materialFeatures(m);
    switch (materialType(m))
    {
    case 1:
        shadeHit<Glow>(index, features);
        break;
    case 2:
        shadeHit<Tinted>(index, features);
        break;
    case 3:
        shadeHit<CarPaint>(index, features);
        break;
    case 4:
        shadeHit<Dissolve>(index, features);
        break;
    default:
        shadeHit<MetallicRoughnessMaterial>(index, features);
        break;
    }
}
