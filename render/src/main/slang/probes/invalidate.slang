// What the probe volume is told changed (engine-render's ProbeVolume, design §8.3: immediate invalidation of known
// changes, D3): each frame, before its probes update, every instance's record and every light's is compared with the
// frame before's (a copy kept here, persistent), and the probes near what changed restart their histories this frame:
//
// - instancesMain, a thread per instance: moved, turned, scaled, shown, hidden or given another mesh (D91's records,
//   as drawing has them), or skinned anew (its copy's bit in the skinned set): the probes within its bounding sphere,
//   where it was and where it is, grown by the probes' spacing.
// - lightsMain, a thread per light: switched, moved, recolored or reshaped: the probes within its reach (times a
//   margin, for the surfaces it lights beyond it), where it was and where it is; a directional light: every probe.
import indirect;
import meshlets.common;

struct InvalidatePush
{
    ProbeVolumeData* volume;
    Instance* instances;      // the scene's, this frame
    Instance* previous;       // the copy of the frame before's, rewritten here
    MeshEntry* meshes;
    Light* lights;            // the scene's lights, this frame
    Light* previousLights;    // the copy of the frame before's, rewritten here
    uint* status;             // status[0]: set when every probe restarts
    uint* skinned;            // a bit per mesh: skinned this frame
    float3 origin;            // the volume's origin, world position, high part
    uint instanceCount;
    float3 originLow;         // and the rest
    uint lightCount;
    float margin;             // a light's reach is grown by this factor
    float cutoff;             // where lights without a range end
    uint previousLightCount;  // the frame before's lights
};

[[vk::push_constant]]
ConstantBuffer<InvalidatePush> push;

static const uint PROBE_INVALIDATED = 4; // update.slang's

/** Marks the probes of every cascade within `radius` of `center` (relative to the volume's origin). */
void invalidate(float3 center, float radius)
{
    ProbeVolumeData* volume = push.volume;
    uint cascades = volume[0].cascades;
    int3 probes = int3(volume[0].probes);
    ProbeState* states = volume[0].states;
    for (uint c = 0; c < cascades; c++)
    {
        float4 cascade = volume[0].cascade[c];
        float spacing = cascade.w;
        float grown = radius + spacing; // the probes around it whose cells it touches
        int3 low = max(int3(floor((center - grown - cascade.xyz) / spacing)), int3(0));
        int3 high = min(int3(ceil((center + grown - cascade.xyz) / spacing)), probes - 1);
        if (any(high < low))
        {
            continue;
        }
        int3 size = high - low + 1;
        if (size.x * size.y * size.z > probes.x * probes.y * probes.z / 2)
        {
            low = 0; // most of the cascade: all of it
            high = probes - 1;
        }
        int3 base = volume[0].base[c].xyz;
        for (int y = low.y; y <= high.y; y++)
        {
            for (int z = low.z; z <= high.z; z++)
            {
                for (int x = low.x; x <= high.x; x++)
                {
                    float3 p = cascade.xyz + float3(x, y, z) * spacing;
                    float3 d = max(abs(p - center) - radius, 0.0);
                    if (dot(d, d) > spacing * spacing * 3)
                    {
                        continue;
                    }
                    int3 slot = wrapProbe(base + int3(x, y, z), probes);
                    uint index = uint(slot.x + probes.x * (slot.z + probes.z * (slot.y + probes.y * int(c))));
                    InterlockedOr(states[index].flags, PROBE_INVALIDATED);
                }
            }
        }
    }
}

/** Instance `i`'s bounding sphere relative to the volume's origin; radius 0 where it is not drawn. */
float4 sphereOf(Instance instance)
{
    if ((instance.mesh & INSTANCE_LIVE) == 0)
    {
        return 0;
    }
    float4 sphere = push.meshes[instance.mesh & INSTANCE_MESH].sphere;
    float3 at = lessCamera(instance.position, instance.positionLow, push.origin, push.originLow);
    float3 center = at + rotate(instance.rotation, sphere.xyz * instance.scale);
    return float4(center, sphere.w * maxScale(instance));
}

bool same(Instance a, Instance b)
{
    return all(a.rotation == b.rotation) && all(a.position == b.position) && a.mesh == b.mesh
        && all(a.scale == b.scale) && all(a.positionLow == b.positionLow) && a.materialBase == b.materialBase
        && a.layers == b.layers;
}

[shader("compute")]
[numthreads(64, 1, 1)]
void instancesMain(uint3 id : SV_DispatchThreadID)
{
    uint i = id.x;
    if (i >= push.instanceCount)
    {
        return;
    }
    Instance now = push.instances[i];
    Instance before = push.previous[i];
    uint mesh = now.mesh & INSTANCE_MESH;
    bool skinned = (now.mesh & INSTANCE_LIVE) != 0 && (push.skinned[mesh >> 5] & (1u << (mesh & 31))) != 0;
    if (same(now, before) && !skinned)
    {
        return;
    }
    float4 a = sphereOf(before);
    float4 b = sphereOf(now);
    if (a.w > 0)
    {
        invalidate(a.xyz, a.w);
    }
    if (b.w > 0 && any(a != b))
    {
        invalidate(b.xyz, b.w);
    }
    push.previous[i] = now;
}

bool sameLight(Light a, Light b)
{
    return all(a.position == b.position) && a.range == b.range && all(a.color == b.color) && a.type == b.type
        && all(a.direction == b.direction) && a.cosOuter == b.cosOuter && a.cosInner == b.cosInner
        && all(a.positionLow == b.positionLow);
}

/** What a light (a world position) reaches, relative to the volume: center and radius; radius < 0: everywhere. */
float4 reachOf(Light l)
{
    if (l.type == DIRECTIONAL)
    {
        return float4(0, 0, 0, -1);
    }
    if (all(l.color <= 0))
    {
        return 0;
    }
    float3 at = lessCamera(l.position, l.positionLow, push.origin, push.originLow);
    return float4(at, lightReach(l, push.cutoff) * push.margin);
}

[shader("compute")]
[numthreads(64, 1, 1)]
void lightsMain(uint3 id : SV_DispatchThreadID)
{
    uint i = id.x;
    uint before = push.previousLightCount;
    uint count = push.lightCount;
    if (i >= max(count, before))
    {
        return;
    }
    Light now = push.lights[i];
    Light was = push.previousLights[i];
    bool had = i < before;
    bool has = i < count;
    if (had && has && sameLight(now, was))
    {
        return;
    }
    float4 reaches[2] = { had ? reachOf(was) : float4(0), has ? reachOf(now) : float4(0) };
    for (uint k = 0; k < 2; k++)
    {
        float4 r = reaches[k];
        if (r.w < 0)
        {
            InterlockedOr(push.status[0], 1u); // a directional light: every probe
        }
        else if (r.w > 0)
        {
            invalidate(r.xyz, r.w);
        }
    }
    if (has)
    {
        push.previousLights[i] = now;
    }
}
