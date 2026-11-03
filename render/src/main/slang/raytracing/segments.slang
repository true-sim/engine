// Segments traced through a scene's top level (engine-render's SegmentTracing): one thread per segment walks every
// triangle between its ends with one ray query (all geometry forced non-opaque, nothing committed, so the query
// visits every candidate) and keeps the nearest crossings in order of distance: each crossing's instance id, whether
// the ray met the triangle's front face, and the triangle's material id. Crossings within a millimetre of a kept one
// count as that one (a ray through an edge meets both triangles), as physics' rays step a millimetre past each hit.
import raytracing;

static const uint MAX_CROSSINGS = 8;                 // kept per segment, the nearest
static const uint SEGMENT_WORDS = 1 + 2 * MAX_CROSSINGS;
static const float MERGE = 1e-3;                    // metres: crossings this close are one

struct SegmentsPush
{
    RayScene* scene;      // the top level, and the instances and meshlets that name a triangle's material
    float* segments;      // 6 floats per segment: its ends, relative to the top level's origin
    uint* results;        // SEGMENT_WORDS per segment: the crossings kept, then (instance | front << 31, material) each
    uint count;
    uint fronts;          // 1: only front faces are crossings (going into meshes whose faces wind outwards)
};

[[vk::push_constant]]
ConstantBuffer<SegmentsPush> push;

[shader("compute")]
[numthreads(64, 1, 1)]
void segmentsMain(uint3 id : SV_DispatchThreadID)
{
    uint i = id.x;
    if (i >= push.count)
    {
        return;
    }
    uint s = i * 6;
    float3 from = float3(push.segments[s], push.segments[s + 1], push.segments[s + 2]);
    float3 to = float3(push.segments[s + 3], push.segments[s + 4], push.segments[s + 5]);
    float3 along = to - from;
    float length = sqrt(dot(along, along));
    uint w = i * SEGMENT_WORDS;
    if (length < MERGE)
    {
        push.results[w] = 0;
        return;
    }
    float t[MAX_CROSSINGS] = {};
    uint instance[MAX_CROSSINGS] = {}; // its id, front face in the top bit
    uint primitive[MAX_CROSSINGS] = {};
    uint kept = 0;
    RayDesc ray;
    ray.Origin = from;
    ray.Direction = along / length;
    ray.TMin = 0;
    ray.TMax = length;
    RayQuery<RAY_FLAG_FORCE_NON_OPAQUE | RAY_FLAG_SKIP_PROCEDURAL_PRIMITIVES> query;
    query.TraceRayInline(scenes[push.scene.slot], RAY_FLAG_NONE, 0xff, ray);
    while (query.Proceed())
    {
        if (push.fronts != 0 && !query.CandidateTriangleFrontFace())
        {
            continue;
        }
        float at = query.CandidateTriangleRayT();
        // the place among the kept, nearest first; one within MERGE of a kept crossing keeps the nearer of the two
        uint place = 0;
        while (place < kept && t[place] + MERGE <= at)
        {
            place++;
        }
        uint word = query.CandidateInstanceID() | (query.CandidateTriangleFrontFace() ? 0x80000000u : 0u);
        uint triangle = query.CandidatePrimitiveIndex();
        if (place < kept && abs(t[place] - at) < MERGE)
        {
            if (at < t[place])
            {
                t[place] = at;
                instance[place] = word;
                primitive[place] = triangle;
            }
            continue;
        }
        if (place >= MAX_CROSSINGS)
        {
            continue; // farther than the nearest kept
        }
        uint last = min(kept, MAX_CROSSINGS - 1);
        for (uint k = last; k > place; k--)
        {
            t[k] = t[k - 1];
            instance[k] = instance[k - 1];
            primitive[k] = primitive[k - 1];
        }
        t[place] = at;
        instance[place] = word;
        primitive[place] = triangle;
        kept = min(kept + 1, MAX_CROSSINGS);
    }
    push.results[w] = kept;
    for (uint k = 0; k < kept; k++)
    {
        push.results[w + 1 + 2 * k] = instance[k];
        push.results[w + 2 + 2 * k] = triangleMaterial(push.scene, instance[k] & 0xffffffu, primitive[k]);
    }
}
