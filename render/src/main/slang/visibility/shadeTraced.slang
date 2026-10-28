// The visibility buffer's shading with inline ray shadows (ray queries), on devices with ray tracing.
import visibility.resolve;
import raytracing;

[shader("compute")]
[numthreads(8, 8, 1)]
void shadeTracedMain(uint3 id : SV_DispatchThreadID)
{
    shadePixel(QueryRays(), id);
}
