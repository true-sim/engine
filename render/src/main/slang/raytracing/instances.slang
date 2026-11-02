// The top level's instance records, from the meshlet renderer's instances: one thread per instance writes a
// VkAccelerationStructureInstanceKHR (64 bytes): its 3 x 4 transform (rotation and scale, then position, row-major),
// its index as custom index, mask 0xff (0: left out, when its mesh has no bottom level), two-sided, opaque unless one
// of its materials is masked (then its triangles are candidates rays alpha-test), and its mesh's bottom level. An
// instance left out names an empty bottom level, never none (reference 0): an inactive instance must stay inactive
// through refits of the top level, and an instance whose mesh loses or gains its level would change that.
import meshlets.common;
import materials;
import raytracing;

struct InstancesPush
{
    Instance* instances;
    BottomLevel* bottom;  // per pool mesh: its bottom level, address 0 for none
    Material* materials;  // the scene's
    uint* records;        // 16 words per instance
    uint64_t empty;       // a bottom level holding nothing traceable, for instances left out
    uint count;
    float originX;        // the world position the top level is relative to (RayTracing.origin), high part
    float originY;
    float originZ;
    float originLowX;     // and the rest
    float originLowY;
    float originLowZ;
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
    Instance stored = push.instances[i];
    // Relative to the origin: floats near it hold positions finely, wherever in the world it is.
    precise float3 high = stored.position - float3(push.originX, push.originY, push.originZ);
    precise float3 low = stored.positionLow - float3(push.originLowX, push.originLowY, push.originLowZ);
    Instance instance = stored;
    instance.position = high + low;
    instance.mesh = stored.mesh & INSTANCE_MESH;
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
    BottomLevel level = push.bottom[instance.mesh];
    uint64_t bottom = level.address;
    // Rays trace shadows: holes and instances casting none are left out.
    bool casts = (stored.mesh & INSTANCE_LIVE) != 0 && (stored.mesh & INSTANCE_CASTS_SHADOWS) != 0;
    uint mask = bottom != 0 && casts ? 0xffu : 0u;
    push.records[w + 12] = (i & 0xffffffu) | (mask << 24);
    bool masked = false;
    for (uint s = 0; s < level.submeshes && mask != 0; s++) // holes name mesh 0 and no materials
    {
        masked = masked || materialMasked(push.materials[instance.materialBase + s]);
    }
    // Triangle facing cull disable, and force opaque or (masked) force no opaque.
    push.records[w + 13] = (masked ? 0x9u : 0x5u) << 24;
    uint64_t reference = mask != 0 ? bottom : push.empty;
    push.records[w + 14] = uint(reference);
    push.records[w + 15] = uint(reference >> 32);
}
