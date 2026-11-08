// The probe volume's emitters (engine-render's ProbeVolume; pathtracing/emitters.slang samples them), listed each frame
// in two passes:
//
// - powersMain, a thread per instance id: the instance's power, 0 unless it is live, has a bottom level (rays can meet
//   it) and one of its submeshes' materials gives off light: metallic-roughness's emissive factor (an emissive texture
//   only scales it down), the brightest submesh's luminance times the cross section of the instance's bounding sphere.
//   Other material types are not listed: their emission is found by the probes' rays alone, as before (nothing is
//   counted twice: an emitter not listed is not sampled).
// - listMain, one group: the instances with power, in id order (a scan per 1024 ids), their records with running sums
//   of their powers and each id's slot; more than the list holds and none is listed this frame (nothing sampled: what
//   the rays meet is taken whole).
import pathtracing.emitters;
import raytracing;
import meshlets.common;
import materials.metallicRoughness;

struct EmitterPush
{
    EmitterList* list;
    uint64_t rays;             // RayScene*: the instances, meshes, bottom levels and materials
    View* view;                // the anchor's view: the spheres relative to its camera
    uint instances;            // instance ids to look at
    uint pad;
    float* powers;             // per instance id its power: the list's, which listMain writes into its header
    uint* slots;
};

[[vk::push_constant]]
ConstantBuffer<EmitterPush> push;

static const uint GROUP = 1024;

/** The bounding sphere of instance `id` relative to the view's camera, and what its materials give off. */
float4 emitterSphere(RayScene* rays, uint id, out float emission)
{
    emission = 0;
    Instance raw = ((Instance*)rays.instances)[id];
    if ((raw.mesh & INSTANCE_LIVE) == 0)
    {
        return 0;
    }
    uint meshIndex = raw.mesh & INSTANCE_MESH;
    BottomLevel* levels = (BottomLevel*)rays.bottom;
    if (levels[meshIndex].address == 0)
    {
        return 0;
    }
    uint submeshes = levels[meshIndex].submeshes;
    Material* table = (Material*)rays.materials;
    for (uint s = 0; s < submeshes; s++)
    {
        MaterialRef ref = { table, raw.materialBase + s };
        Material m = ref.slot();
        if (materialType(m) != 0)
        {
            continue;
        }
        MetallicRoughnessParameters p = materialParameters<MetallicRoughnessParameters>(ref);
        emission = max(emission, dot(max(p.emissive, 0.0), float3(0.2126, 0.7152, 0.0722)));
    }
    if (!(emission > 0))
    {
        emission = 0;
        return 0;
    }
    Instance instance = relative(raw, push.view[0]);
    float4 sphere = rays.meshes[meshIndex].sphere;
    float3 scale = abs(instance.scale);
    float3 center = instance.position + rotate(instance.rotation, sphere.xyz * instance.scale);
    return float4(center, sphere.w * max(scale.x, max(scale.y, scale.z)));
}

[shader("compute")]
[numthreads(64, 1, 1)]
void powersMain(uint3 id : SV_DispatchThreadID)
{
    uint i = id.x;
    if (i >= push.instances)
    {
        return;
    }
    float emission;
    float4 sphere = emitterSphere((RayScene*)push.rays, i, emission);
    float* powers = push.powers;
    powers[i] = emission > 0 && sphere.w > 0 ? emission * 3.14159265 * sphere.w * sphere.w : 0;
}

groupshared uint counts[GROUP];
groupshared float sums[GROUP];
groupshared uint listed;
groupshared float summed;

[shader("compute")]
[numthreads(1024, 1, 1)]
void listMain(uint3 thread : SV_GroupThreadID)
{
    uint t = thread.x;
    EmitterList* list = push.list;
    uint capacity = list[0].capacity;
    float* powers = push.powers;
    uint* slots = push.slots;
    EmitterRecord* records = list[0].records;
    RayScene* rays = (RayScene*)push.rays;
    if (t == 0)
    {
        listed = 0;
        summed = 0;
    }
    GroupMemoryBarrierWithGroupSync();
    for (uint start = 0; start < push.instances; start += GROUP)
    {
        uint i = start + t;
        float power = i < push.instances ? powers[i] : 0;
        bool glows = power > 0;
        counts[t] = glows ? 1 : 0;
        sums[t] = glows ? power : 0;
        GroupMemoryBarrierWithGroupSync();
        // Inclusive scans of the flags and the powers (Hillis and Steele): the same order every run
        for (uint step = 1; step < GROUP; step <<= 1)
        {
            uint c = counts[t];
            float s = sums[t];
            if (t >= step)
            {
                c += counts[t - step];
                s += sums[t - step];
            }
            GroupMemoryBarrierWithGroupSync();
            counts[t] = c;
            sums[t] = s;
            GroupMemoryBarrierWithGroupSync();
        }
        uint before = listed;
        float sumBefore = summed;
        if (i < push.instances)
        {
            uint slot = before + counts[t] - 1;
            if (glows && slot < capacity)
            {
                float emission;
                EmitterRecord r;
                r.sphere = emitterSphere(rays, i, emission);
                r.instance = i;
                r.power = power;
                r.sum = sumBefore + sums[t];
                r.pad = 0;
                records[slot] = r;
                slots[i] = slot + 1;
            }
            else
            {
                slots[i] = 0;
            }
        }
        GroupMemoryBarrierWithGroupSync();
        if (t == 0)
        {
            listed = before + counts[GROUP - 1];
            summed = sumBefore + sums[GROUP - 1];
        }
        GroupMemoryBarrierWithGroupSync();
    }
    if (t == 0)
    {
        list[0].powers = powers;
        list[0].slots = slots;
        list[0].found = listed;
        list[0].count = listed <= capacity ? listed : 0;
        list[0].total = listed <= capacity ? summed : 0;
    }
}
