// The reference path tracer's passes but the shading (pathtracing/paths.slang): camera rays, tracing, the bins of the
// hits (count, lay out, scatter: as split.slang), and the resolve of the accumulated samples.
import pathtracing.paths;

/** Camera rays, one per pixel of the region, jittered within it; the accumulation cleared at the first sample. */
[shader("compute")]
[numthreads(8, 8, 1)]
void generateMain(uint3 id : SV_DispatchThreadID)
{
    uint width = push.size & 0xffff;
    uint height = push.size >> 16;
    if (id.x >= width || id.y >= height)
    {
        return;
    }
    if (push.sample == 0)
    {
        images[push.accumulation][id.xy] = float4(0, 0, 0, 1);
    }
    View view = push.view[0];
    uint rng = seed(id.xy, push.sample);
    float2 at = float2(id.xy) + float2(random01(rng), random01(rng));
    float2 ndc = at / float2(width, height) * 2 - 1;
    float4 near = mul(view.inverseViewProjection, float4(ndc, 1, 1));
    PathState path;
    path.origin = view.eye;
    path.direction = normalize(near.xyz / near.w - view.eye);
    path.pixel = id.x | id.y << 16;
    path.flags = 0;
    path.throughput = 1;
    path.rng = rng;
    path.instance = 0;
    path.primitive = 0;
    path.bary = 0;
    path.t = 0;
    path.bin = NO_BIN;
    path.cone = 0;
    path.pad = 0;
    push.paths[id.y * width + id.x] = path;
}

/** The nearest hit of each path: its shading bin counted, or the environment for a miss (the path ends). */
[shader("compute")]
[numthreads(64, 1, 1)]
void traceMain(uint3 group : SV_GroupID, uint3 thread : SV_GroupThreadID)
{
    uint index = pathIndex(group, thread);
    if (index >= push.counts[pushBounce()])
    {
        return;
    }
    PathState path = push.paths[index];
    RayScene* rays = (RayScene*)push.rays;
    float end = (push.limits & PATH_PROBES) != 0 ? probeRayEnd(path) : 1.0e30;
    RayHit hit;
    hit.hit = false;
    if (end > 0)
    {
        hit = nearest(rays, path.origin + push.offset, path.direction, 0, end);
    }
    if (!hit.hit)
    {
        if ((push.limits & PATH_PROBES) != 0)
        {
            recordProbeMiss(path);
            push.paths[index].bin = NO_BIN;
            return;
        }
        accumulate(path.pixel, path.throughput * environment(path.direction));
        push.paths[index].bin = NO_BIN;
        return;
    }
    uint bin = materialShadingBin(push.scene[0].materials[triangleMaterial(rays, hit.instance, hit.primitive)]);
    bin = bin < pushBinCount() ? bin : 0; // a bin made since the graph built: as metallic-roughness till it rebuilds
    path.instance = hit.instance;
    path.primitive = hit.primitive;
    path.bary = hit.bary;
    path.t = hit.t;
    path.bin = bin;
    push.paths[index] = path;
    InterlockedAdd(push.bins[4 * bin + 3], 1);
}

/** Each bin's first slot in the list and its dispatch: one thread, in bin order. */
[shader("compute")]
[numthreads(1, 1, 1)]
void layoutMain()
{
    uint offset = 0;
    for (uint bin = 0; bin < pushBinCount(); bin++)
    {
        uint count = push.bins[4 * bin + 3];
        uint groups = (count + 63) / 64;
        push.bins[4 * bin] = min(groups, PATH_ROW);
        push.bins[4 * bin + 1] = (groups + PATH_ROW - 1) / PATH_ROW;
        push.bins[4 * bin + 2] = 1;
        push.bins[4 * pushBinCount() + bin] = offset;
        push.bins[5 * pushBinCount() + bin] = 0;
        offset += count;
    }
}

/** Each hit's path into its bin's part of the list. */
[shader("compute")]
[numthreads(64, 1, 1)]
void scatterMain(uint3 group : SV_GroupID, uint3 thread : SV_GroupThreadID)
{
    uint index = pathIndex(group, thread);
    if (index >= push.counts[pushBounce()])
    {
        return;
    }
    uint bin = push.paths[index].bin;
    if (bin == NO_BIN)
    {
        return;
    }
    uint slot;
    InterlockedAdd(push.bins[5 * pushBinCount() + bin], 1, slot);
    push.bins[push.list + binOffset(bin) + slot] = index;
}

/** The image: the accumulated radiance over the samples taken (push.sample, this frame's included). */
[shader("compute")]
[numthreads(8, 8, 1)]
void resolveMain(uint3 id : SV_DispatchThreadID)
{
    uint width = push.size & 0xffff;
    uint height = push.size >> 16;
    if (id.x >= width || id.y >= height)
    {
        return;
    }
    float3 sum = images[push.accumulation][id.xy].rgb;
    images[push.list][id.xy] = float4(sum / float(max(push.sample, 1u)), 1);
}
