// The emitters a probe's light samples (engine-render's ProbeVolume, its emitters): the instances whose materials give
// off light, listed each frame by probes/emitters.slang (the instance table in id order, so the list and every sample of
// it are the same from run to run), each by its bounding sphere and its power (the brightest emission of its submeshes
// times the sphere's cross section). A probe draws light samples from it, next-event estimation of emissive surfaces:
// an emitter chosen by its power (EMITTERS_POWER: a search of the list's running sums) or by what it could give the
// probe (EMITTERS_NEARBY: its power times the solid angle its sphere covers from there, every emitter weighed), then a
// direction uniform in the cone of its sphere. Such a ray counts the emission it meets only on the emitter it chose;
// the probe's turned rays find the same emission by chance. Both are weighed by the balance heuristic (Veach 1997)
// over their densities of directions, so emission is counted once: what rays meet, they take by their share.
module emitters;

import bindless;

/** An emitter: an instance giving off light, as the list holds it. 32 bytes. */
[HostLayout]
public struct EmitterRecord
{
    public float4 sphere;      // its bounding sphere: center relative to the anchor view's camera, radius
    public uint instance;      // its instance id
    public float power;        // what it weighs in the choice by power: its emission's luminance times pi r^2
    public float sum;          // the powers of the records before it and its own: the choice by power searches these
    public uint pad;
};

/**
 * The list, as the probe volume's setup writes its first part and probes/emitters.slang the rest each frame. Read a
 * field at a time (pitfalls.md: no loads of whole structs holding pointers).
 */
[HostLayout]
public struct EmitterList
{
    public uint count;         // the emitters listed (0: none, or more found than the list holds: none sampled)
    public uint capacity;      // the most it holds
    public uint found;         // the emissive instances found this frame
    public uint samples;       // light samples per probe update (0: none: rays alone find emission)
    public uint turned;        // the probe rays the samples are weighed against (each uniform over the sphere)
    public uint selection;     // EMITTERS_POWER or EMITTERS_NEARBY
    public float total;        // the listed emitters' powers summed
    public uint instances;     // the instance ids `powers` and `slots` hold
    public EmitterRecord* records;
    public float* powers;      // per instance id: its power this frame (0: it gives off no light)
    public uint* slots;        // per instance id: its record plus one (0: not listed)
};

/** Emitters are chosen by their power alone: a search of the running sums. */
public static const uint EMITTERS_POWER = 0;
/** By their power times the solid angle they cover from the probe: every emitter weighed, for each sample. */
public static const uint EMITTERS_NEARBY = 1;

static const float EMITTER_PI = 3.14159265;

/**
 * The solid angle sphere `sphere` (center relative to `p`'s frame, radius) covers seen from `p`, and the cosine of its
 * cone's half angle (-1: `p` inside it, every direction).
 */
public float coneOf(float4 sphere, float3 p, out float cosMax)
{
    float3 d = sphere.xyz - p;
    float distance2 = dot(d, d);
    float r2 = sphere.w * sphere.w;
    if (distance2 <= r2)
    {
        cosMax = -1;
        return 4 * EMITTER_PI;
    }
    float sin2 = r2 / distance2;
    float c = sqrt(max(1 - sin2, 0.0));
    cosMax = c;
    return 2 * EMITTER_PI * sin2 / (1 + c); // 2 pi (1 - cos), exact for small cones too
}

/** What emitter `r` weighs in the choice of a sample from `p`. */
float weightAt(EmitterList* list, EmitterRecord r, float3 p, uint selection)
{
    if (selection == EMITTERS_NEARBY)
    {
        float cosMax;
        return r.power * coneOf(r.sphere, p, cosMax);
    }
    return r.power;
}

/** The record list `list` holds at `i`, field by field. */
EmitterRecord recordAt(EmitterList* list, uint i)
{
    EmitterRecord* records = list[0].records;
    return records[i];
}

/**
 * A light sample from `p` (relative to the anchor's camera): an emitter chosen by `u.x`, a direction to it from `u.yz`
 * (uniform within the cone of its sphere), and the chosen emitter's instance. False where nothing is listed.
 */
public bool sampleEmitter(EmitterList* list, float3 p, float3 u, out float3 direction, out uint instance)
{
    direction = float3(0, 0, 1);
    instance = 0xffffffffu;
    uint count = list[0].count;
    if (count == 0 || list[0].samples == 0)
    {
        return false;
    }
    uint selection = list[0].selection;
    uint chosen = 0;
    if (selection == EMITTERS_NEARBY)
    {
        // Every emitter weighed from here: the first whose running weight passes u times their sum
        float weights = 0;
        for (uint i = 0; i < count; i++)
        {
            weights += weightAt(list, recordAt(list, i), p, selection);
        }
        if (weights <= 0)
        {
            return false;
        }
        float target = u.x * weights;
        float running = 0;
        chosen = count - 1;
        for (uint i = 0; i < count; i++)
        {
            float w = weightAt(list, recordAt(list, i), p, selection);
            running += w;
            if (w > 0)
            {
                chosen = i; // the last that weighs, should rounding leave the sum short of the target
                if (running > target)
                {
                    break;
                }
            }
        }
    }
    else
    {
        // The first record whose running sum passes u times the total
        float target = u.x * list[0].total;
        EmitterRecord* records = list[0].records;
        uint low = 0;
        uint high = count - 1;
        while (low < high)
        {
            uint middle = (low + high) / 2;
            if (records[middle].sum <= target)
            {
                low = middle + 1;
            }
            else
            {
                high = middle;
            }
        }
        chosen = low;
    }
    EmitterRecord r = recordAt(list, chosen);
    instance = r.instance;
    float cosMax;
    coneOf(r.sphere, p, cosMax);
    float3 axis = r.sphere.xyz - p;
    float length2 = dot(axis, axis);
    axis = length2 > 0 ? axis * rsqrt(length2) : float3(0, 0, 1);
    float cosTheta = 1 - u.y * (1 - cosMax);
    float sinTheta = sqrt(max(1 - cosTheta * cosTheta, 0.0));
    float phi = 2 * EMITTER_PI * u.z;
    // A basis around the axis (Duff et al. 2017)
    float sign = axis.z >= 0 ? 1.0 : -1.0;
    float a = -1.0 / (sign + axis.z);
    float b = axis.x * axis.y * a;
    float3 t = float3(1 + sign * axis.x * axis.x * a, sign * b, -sign * axis.x);
    float3 s = float3(b, sign + axis.y * axis.y * a, -axis.y);
    direction = normalize((t * cos(phi) + s * sin(phi)) * sinTheta + axis * cosTheta);
    return true;
}

/**
 * The density (per solid angle) with which a light sample from `p` takes a direction to instance `instance`, where a
 * ray from `p` met it: the chance of choosing it over its cone's solid angle; 0 where it is not listed.
 */
public float emitterPdf(EmitterList* list, uint instance, float3 p)
{
    uint count = list[0].count;
    if (count == 0 || list[0].samples == 0 || instance >= list[0].instances)
    {
        return 0;
    }
    uint* slots = list[0].slots;
    uint slot = slots[instance];
    if (slot == 0 || slot > count)
    {
        return 0;
    }
    EmitterRecord r = recordAt(list, slot - 1);
    uint selection = list[0].selection;
    float cosMax;
    float solid = coneOf(r.sphere, p, cosMax);
    if (selection == EMITTERS_NEARBY)
    {
        // Chosen by its power times its solid angle: over that solid angle, its power over all emitters' weights
        float weights = 0;
        for (uint i = 0; i < count; i++)
        {
            weights += weightAt(list, recordAt(list, i), p, selection);
        }
        return weights > 0 ? r.power / weights : 0;
    }
    float total = list[0].total;
    return total > 0 ? r.power / (total * solid) : 0;
}

/** The density with which the probe's turned rays take any one direction: `turned` of them, each uniform. */
float rayDensity(EmitterList* list)
{
    return float(list[0].turned) / (4 * EMITTER_PI);
}

/**
 * What emission a probe's turned ray met on instance `instance` from `p` is weighed by (the balance heuristic): its
 * rays' share of the two densities of that direction. 1 where nothing samples it.
 */
public float rayEmissionWeight(EmitterList* list, uint instance, float3 p)
{
    float light = float(list[0].samples) * emitterPdf(list, instance, p);
    if (light <= 0)
    {
        return 1;
    }
    float rays = rayDensity(list);
    return rays / (rays + light);
}

/**
 * What emission a light sample from `p` met on the instance it chose is multiplied by to be one sample's part of the
 * irradiance (with the cosine): the balance heuristic's weight over the sample's density, over the samples taken.
 */
public float sampleEmissionWeight(EmitterList* list, uint instance, float3 p)
{
    float light = float(list[0].samples) * emitterPdf(list, instance, p);
    if (light <= 0)
    {
        return 0;
    }
    return 1 / (light + rayDensity(list));
}
