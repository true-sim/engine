// The instances changed since the last upload, scattered into the GPU scene's instance buffer: one thread per entry
// copies its 64-byte record to its id. Each id is in an upload at most once, so the order of the threads is free.

struct Record
{
    uint4 words[4];
};

struct ScatterPush
{
    uint* ids;             // the id of each entry
    Record* records;       // the instances, as the shaders' Instance lays them out
    Record* instances;     // the GPU scene's instance buffer
    uint count;
};

[[vk::push_constant]]
ConstantBuffer<ScatterPush> push;

[shader("compute")]
[numthreads(64, 1, 1)]
void scatterMain(uint3 id : SV_DispatchThreadID)
{
    if (id.x >= push.count)
    {
        return;
    }
    push.instances[push.ids[id.x]] = push.records[id.x];
}
