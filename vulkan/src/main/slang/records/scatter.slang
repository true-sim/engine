// A RecordBuffer's changes scattered into it: one thread per 16 bytes of a changed record, so records of any size
// (a multiple of 16 bytes) copy in parallel. Each record is in an upload at most once, so the order is free.

struct ScatterPush
{
    uint* indices;         // the record index of each change
    uint4* changes;        // the changed records, one after another
    uint4* records;        // the buffer's records
    uint count;            // changes
    uint words;            // 16-byte words per record
    uint first;            // the first word of this dispatch (dispatches have at most 65,535 workgroups)
};

[[vk::push_constant]]
ConstantBuffer<ScatterPush> push;

[shader("compute")]
[numthreads(64, 1, 1)]
void scatterMain(uint3 id : SV_DispatchThreadID)
{
    uint thread = push.first + id.x;
    uint change = thread / push.words;
    if (change >= push.count)
    {
        return;
    }
    uint word = thread - change * push.words;
    push.records[push.indices[change] * push.words + word] = push.changes[thread];
}
