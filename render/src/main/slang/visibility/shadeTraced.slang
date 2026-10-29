// The visibility buffer's shading of the standard material type with inline ray shadows (ray queries), on devices
// with ray tracing.
import visibility.resolve;
import raytracing;

[shader("compute")]
[numthreads(8, 8, 1)]
void shadeTracedMain(uint3 group : SV_GroupID, uint3 thread : SV_GroupThreadID)
{
    shadeTiles<StandardMaterial>(QueryRays(), group, thread);
}
