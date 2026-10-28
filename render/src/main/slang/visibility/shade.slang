// The visibility buffer's shading, without ray tracing: inline ray shadows count as lit.
import visibility.resolve;

[shader("compute")]
[numthreads(8, 8, 1)]
void shadeMain(uint3 id : SV_DispatchThreadID)
{
    shadePixel(NoRays(), id);
}
