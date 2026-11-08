// singleHit.slang's switch over two types only (0 metallic-roughness, 1 glow): what ReferencePathTracerTest compares
// with the binned dispatches on every build. The switch over every type the hit shading benchmark registers
// (singleHit.slang) is one function of five inlined shadeHit<M>: Mesa's lavapipe (CI's software GPU) took 25 minutes
// to compile it, so its comparison runs with the benchmark (TRUESIM_BENCHMARK).
import pathtracing.paths;
import test.glow;

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
    default:
        shadeHit<MetallicRoughnessMaterial>(index, features);
        break;
    }
}
