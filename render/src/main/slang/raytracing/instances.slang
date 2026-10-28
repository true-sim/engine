// The top level's instance records, from the meshlet renderer's instances: one thread per instance writes a
// VkAccelerationStructureInstanceKHR (64 bytes): its 3 x 4 transform (rotation and scale, then position, row-major),
// its index as custom index, mask 0xff (0: left out, when its mesh has no bottom level), opaque and two-sided, and its
// mesh's bottom level.
import meshlets.common;

struct InstancesPush
{
    Instance* instances;
    uint64_t* bottom;     // per pool mesh: its bottom level's address, or 0
    uint* records;        // 16 words per instance
    uint count;
};

[[vk::push_constant]]
ConstantBuffer<InstancesPush> push;

[shader("compute")]
[numthreads(64, 1, 1)]
void instancesMain(uint3 id : SV_DispatchThreadID)
{
    uint i = id.x;
    if (i >= push.count)
    {
        return;
    }
    Instance instance = push.instances[i];
    float3 x = rotate(instance.rotation, float3(instance.scale.x, 0, 0));
    float3 y = rotate(instance.rotation, float3(0, instance.scale.y, 0));
    float3 z = rotate(instance.rotation, float3(0, 0, instance.scale.z));
    uint w = i * 16;
    // Row r: (x[r], y[r], z[r], position[r]).
    for (int r = 0; r < 3; r++)
    {
        push.records[w + r * 4] = asuint(x[r]);
        push.records[w + r * 4 + 1] = asuint(y[r]);
        push.records[w + r * 4 + 2] = asuint(z[r]);
        push.records[w + r * 4 + 3] = asuint(instance.position[r]);
    }
    uint64_t bottom = push.bottom[instance.mesh];
    uint mask = bottom != 0 ? 0xffu : 0u;
    push.records[w + 12] = (i & 0xffffffu) | (mask << 24);
    push.records[w + 13] = 0x5u << 24;       // triangle facing cull disable | force opaque
    push.records[w + 14] = uint(bottom);
    push.records[w + 15] = uint(bottom >> 32);
}
