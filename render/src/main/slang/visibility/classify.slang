// The visibility buffer's tile classification: which material types each 8x8 tile shows (resolve.slang).
import visibility.resolve;

[shader("compute")]
[numthreads(8, 8, 1)]
void classifyMain(uint3 group : SV_GroupID, uint3 thread : SV_GroupThreadID)
{
    classifyTile(group, thread);
}
