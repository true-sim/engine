// The world light grid's build (lights/world.slang, engine-render's WorldLights): the lights the GPU wrote copied
// after the header's data, the directional lights noted (and LISTS' counts cleared), then LISTS a thread per light,
// adding it to every cell its reach meets (a cell's slots in any order), or RESERVOIRS a thread per reservoir (one
// light resampled from candidates drawn uniformly, by what it could give the cell's box).
import lights.world;

struct WorldBuildPush
{
    WorldLightGrid* grid;
    uint* written;             // the lights the GPU writes: a count, three words, the lights; without, a word of 0
    uint writtenCapacity;
    uint frame;                // seeds the reservoirs: new candidates each frame
    uint threads;              // cells (LISTS) or reservoirs (RESERVOIRS)
    uint keep;                 // reservoirs: frames a cell is built after hits last used it
};

[[vk::push_constant]]
ConstantBuffer<WorldBuildPush> push;

uint hash(uint x)
{
    x ^= x >> 16;
    x *= 0x7feb352du;
    x ^= x >> 15;
    x *= 0x846ca68bu;
    x ^= x >> 16;
    return x;
}

/** Light `i` as the build reads it: the scene's, or straight from what the GPU wrote. */
Light source(WorldLightGrid* g, uint count, uint i)
{
    if (i < count)
    {
        return g[0].lights[i];
    }
    return ((Light*)(push.written + 4))[i - count];
}

[shader("compute")]
[numthreads(64, 1, 1)]
void copyMain(uint3 id : SV_DispatchThreadID)
{
    WorldLightGrid* g = push.grid;
    uint extra = min(push.written[0], push.writtenCapacity);
    Light* from = (Light*)(push.written + 4);
    if (id.x < extra)
    {
        g[0].extra[id.x] = from[id.x];
    }
    if (g[0].layout == WORLD_LISTS)
    {
        uint n = g[0].cells;
        uint slots = g[0].slots;
        for (uint cell = id.x; cell < n * n * n; cell += push.threads)
        {
            g[0].data[cell * (1 + slots)] = 0;
        }
    }
    if (id.x == 0)
    {
        g[0].extraCount = extra;
        uint count = g[0].lightCount;
        uint found = 0;
        uint4 directional = 0;
        for (uint i = 0; i < count && found < WORLD_DIRECTIONAL; i++)
        {
            if (g[0].lights[i].type == DIRECTIONAL)
            {
                directional[found++] = i;
            }
        }
        g[0].directional = directional;
        g[0].directionalCount = found;
    }
}

/** Reservoirs: the cells hits used in the last frames (push.keep), listed (a thread per cell). */
[shader("compute")]
[numthreads(64, 1, 1)]
void usedMain(uint3 group : SV_GroupID, uint3 thread : SV_GroupThreadID)
{
    uint cell = (group.y * 1024 + group.x) * 64 + thread.x;
    WorldLightGrid* g = push.grid;
    uint n = g[0].cells;
    if (cell >= n * n * n)
    {
        return;
    }
    uint used = g[0].cellState[2 * cell];
    if (used != 0 && used + push.keep >= push.frame)
    {
        uint slot;
        InterlockedAdd(g[0].built[0], 1, slot);
        g[0].built[4 + slot] = cell;
    }
}

/** Reservoirs: the build's dispatch, rows of 1024 groups, for the cells listed times their reservoirs. */
[shader("compute")]
[numthreads(1, 1, 1)]
void argsMain()
{
    WorldLightGrid* g = push.grid;
    uint groups = (g[0].built[0] * g[0].slots + 63) / 64;
    g[0].built[1] = min(groups, 1024u);
    g[0].built[2] = (groups + 1023) / 1024;
    g[0].built[3] = 1;
}

[shader("compute")]
[numthreads(64, 1, 1)]
void buildMain(uint3 group : SV_GroupID, uint3 thread : SV_GroupThreadID)
{
    uint3 id = uint3((group.y * 1024 + group.x) * 64 + thread.x, 0, 0); // rows of 1024 groups
    if (id.x >= push.threads)
    {
        return;
    }
    WorldLightGrid* g = push.grid;
    uint layout = g[0].layout;
    uint n = g[0].cells;
    uint slots = g[0].slots;
    float size = g[0].cellSize;
    float cutoff = g[0].cutoff;
    float3 origin = g[0].origin;
    float3 originLow = g[0].originLow;
    uint count = g[0].lightCount;
    uint total = count + min(push.written[0], push.writtenCapacity);
    uint cell = id.x / slots; // lists: unused
    uint3 c = uint3(cell % n, (cell / n) % n, cell / (n * n));
    float3 boxMin = float3(c) * size;
    float3 boxMax = boxMin + size;
    if (layout == WORLD_LISTS)
    {
        // Light id.x into every cell its reach meets
        uint i = id.x;
        if (i >= total)
        {
            return;
        }
        Light l = source(g, count, i);
        if (l.type == DIRECTIONAL)
        {
            return;
        }
        l.position = lessCamera(l.position, l.positionLow, origin, originLow);
        float reach = lightReach(l, cutoff);
        int3 low = max(int3(floor((l.position - reach) / size)), 0);
        int3 high = min(int3(floor((l.position + reach) / size)), int(n) - 1);
        for (int z = low.z; z <= high.z; z++)
        {
            for (int y = low.y; y <= high.y; y++)
            {
                for (int x = low.x; x <= high.x; x++)
                {
                    float3 from = float3(x, y, z) * size;
                    if (boxTarget(l, from, from + size, cutoff) <= 0)
                    {
                        continue;
                    }
                    uint* list = g[0].data + ((uint(z) * n + uint(y)) * n + uint(x)) * (1 + slots);
                    uint slot;
                    InterlockedAdd(list[0], 1, slot);
                    if (slot < slots)
                    {
                        list[1 + slot] = i;
                    }
                    else
                    {
                        InterlockedMax(g[0].overflow, slot + 1);
                    }
                }
            }
        }
        return;
    }
    // A reservoir of a listed cell: candidates drawn uniformly among every light, one kept by its weight (resampled
    // importance sampling)
    if (id.x >= g[0].built[0] * slots)
    {
        return;
    }
    cell = g[0].built[4 + id.x / slots];
    c = uint3(cell % n, (cell / n) % n, cell / (n * n));
    boxMin = float3(c) * size;
    boxMax = boxMin + size;
    if (id.x % slots == 0)
    {
        g[0].cellState[2 * cell + 1] = push.frame;
    }
    WorldReservoir* reservoirs = (WorldReservoir*) g[0].data;
    uint candidates = g[0].candidates;
    uint state = hash(id.x * 9781u + push.frame * 6271u + 1u);
    float sum = 0;
    uint chosen = 0xffffffffu;
    float chosenTarget = 0;
    for (uint k = 0; k < candidates && total > 0; k++)
    {
        state = hash(state);
        uint i = min(uint(float(state >> 8) * (1.0 / 16777216.0) * float(total)), total - 1);
        Light l = source(g, count, i);
        if (l.type == DIRECTIONAL)
        {
            continue; // every point sums them itself
        }
        l.position = lessCamera(l.position, l.positionLow, origin, originLow);
        float target = boxTarget(l, boxMin, boxMax, cutoff);
        float w = target * float(total); // over the candidate's density, 1 / total
        sum += w;
        state = hash(state);
        if (w > 0 && float(state >> 8) * (1.0 / 16777216.0) * sum < w)
        {
            chosen = i;
            chosenTarget = target;
        }
    }
    WorldReservoir r;
    r.light = chosenTarget > 0 ? chosen : 0xffffffffu;
    r.weight = chosenTarget > 0 ? sum / (float(candidates) * chosenTarget) : 0;
    reservoirs[cell * slots + id.x % slots] = r;
}
