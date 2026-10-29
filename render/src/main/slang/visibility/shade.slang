// The visibility buffer's shading of the standard material type, without ray tracing: inline ray shadows count as
// lit.
import visibility.resolve;

[shader("compute")]
[numthreads(8, 8, 1)]
void shadeMain(uint3 group : SV_GroupID, uint3 thread : SV_GroupThreadID)
{
    shadeTiles<StandardMaterial>(NoRays(), group, thread);
}
