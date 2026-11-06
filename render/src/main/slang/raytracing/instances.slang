// The top level's instance records, from the meshlet renderer's instances: only those rays trace, listed by the CPU
// (live, casting shadows, their mesh with a bottom level; never the ids the GPU writes, mesh particles, which move
// without a rebuild of the top level). One thread per listed instance writes a VkAccelerationStructureInstanceKHR
// (64 bytes): its 3 x 4 transform (rotation and scale, then position, row-major), its id as custom index, mask 0xff,
// two-sided, opaque unless one of its materials is masked (then its triangles are candidates rays alpha-test), and
// the bottom level the list names. Nothing is left in as an inactive or mask-0 instance: drivers' builders must not
// see what has nothing to trace (docs/pitfalls.md), and a refit keeps the very list its build had.
import meshlets.common;
import materials;
import raytracing;

/** A listed instance: its id and its mesh's bottom level as the CPU read it when it listed it (never 0). */
struct TracedInstance
{
    uint id;
    uint pad;
    uint64_t bottom;
};

struct InstancesPush
{
    Instance* instances;
    BottomLevel* bottom;  // per pool mesh: its submeshes (which materials alpha-test)
    Material* materials;  // the scene's
    uint* records;        // 16 words per listed instance
    TracedInstance* traced; // the instances listed, in order
    uint count;           // how many
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
    uint j = id.x;
    if (j >= push.count)
    {
        return;
    }
    TracedInstance listed = push.traced[j];
    uint i = listed.id;
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
    uint w = j * 16;
    // Row r: (x[r], y[r], z[r], position[r]).
    for (int r = 0; r < 3; r++)
    {
        push.records[w + r * 4] = asuint(x[r]);
        push.records[w + r * 4 + 1] = asuint(y[r]);
        push.records[w + r * 4 + 2] = asuint(z[r]);
        push.records[w + r * 4 + 3] = asuint(instance.position[r]);
    }
    BottomLevel level = push.bottom[instance.mesh];
    push.records[w + 12] = (i & 0xffffffu) | (0xffu << 24);
    bool masked = false;
    for (uint s = 0; s < level.submeshes; s++)
    {
        Material m = push.materials[instance.materialBase + s];
        masked = masked || materialMasked(m) || materialGeometry(m) != 0; // a hook's holes are tested at hits too
    }
    // Triangle facing cull disable, and force opaque or (masked, or a type with a geometry hook) force no opaque.
    push.records[w + 13] = (masked ? 0x9u : 0x5u) << 24;
    push.records[w + 14] = uint(listed.bottom);
    push.records[w + 15] = uint(listed.bottom >> 32);
}
