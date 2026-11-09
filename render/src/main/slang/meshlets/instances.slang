// The instance pass of both geometry paths, for any number of views at once (a row of workgroups per view): one thread
// per instance and view tests the instance's bounds against the view's frustum, and each visible instance appends a
// chunk per 64 of the meshlets its distance may need, marked with the view. All views share the chunk list, so one
// cull pass serves them all.
//
// A cull runs in rounds (CullPasses): each round's instance pass fills the chunk list and its cull pass empties it into
// the views' lists. A workgroup takes room for all its instances' chunks or for none: one finding the list full marks
// itself in the retry bits, and the next round (dispatched indirectly, over no workgroups when none is marked) runs the
// marked workgroups again. They decide as in round 0, but for the late phase, which reads the visibility round 0 wrote.
// What does not fit in the last round is dropped and counted (instances and chunks), for the CPU to report and grow
// the room by; so is the part of a workgroup wanting more than the whole list that does not fit.
//
// A round's command (8 words): 0-2 the cull pass's indirect dispatch over the chunks written, 3 the chunks wanted, 4
// the end of those written, 5-7 the next round's indirect dispatch of this pass. The statistics, never reset by the
// GPU: 0 the most chunks a cull wanted (its round 0's), 1 instances dropped, 2 the most rounds a cull used, 3 chunks
// dropped.
import meshlets.common;

struct InstancePush
{
    View* views;           // the renderer's views of the frame, by index: its view constants array
    MeshEntry* meshes;
    Instance* instances;
    Chunk* chunks;
    uint* chunkCommand;    // this round's command
    Level* levels;
    uint maxChunks;
    Occlusion occlusion;
    CulledView* rows;      // the views of the rows, and their phases
    uint firstRow;         // the row of this dispatch's first (dispatches have at most 65,535 rows)
    float displacement;    // the material table's largest displacement (mesh units): INSTANCE_DISPLACED grow by it
    uint* stats;           // the statistics (above)
    uint* retry;           // a bit per workgroup of the cull (row * groupsPerRow + group): run it again
    uint round;            // 0: every workgroup, a row of them per view; later rounds the marked ones, rows flattened
    uint lastRound;        // 1: what does not fit is dropped
    uint groupsPerRow;     // workgroups per row: (instanceCount + 63) / 64
    uint rowCount;         // the rows of the cull
};

[[vk::push_constant]]
ConstantBuffer<InstancePush> push;

/** A late phase's mark for an instance newly visible (drawn by the late phase): not 0, as any visible one's. */
static const uint NEWLY_VISIBLE = 2;

groupshared uint groupTotal;
groupshared uint groupFirst;

/**
 * How many chunks the instance at `index` of `row` wants (0: none), its id in `instanceId`. In a retry round the late
 * phase takes those round 0 found newly visible.
 */
uint wantedChunks(uint index, CulledView row, bool retry, out uint instanceId)
{
    View view = push.views[row.view];
    // A view listing its instances culls those alone (the rest of the row's threads want none)
    instanceId = index;
    if (given(view.ids))
    {
        if (index >= view.idCount)
        {
            return 0;
        }
        instanceId = view.ids[index];
    }
    if (instanceId >= view.instanceCount)
    {
        return 0;
    }
    Instance stored = push.instances[instanceId];
    if (!drawnIn(stored, instanceId, view))
    {
        return 0; // a hole, on other layers, or casting no shadow in a shadow view
    }
    Instance instance = relative(stored, view);
    MeshEntry mesh = push.meshes[instance.mesh];
    uint phase = row.phase;
    if (retry && phase == PHASE_LATE)
    {
        if (view.visibility[instanceId] != NEWLY_VISIBLE)
        {
            return 0;
        }
    }
    else
    {
        float3 center = toWorld(instance, mesh.sphere.xyz);
        // Displaced in the vertex stages (RASTER): grown by the most any material moves a vertex (meshlets by their own)
        float radius = (mesh.sphere.w + ((stored.mesh & INSTANCE_DISPLACED) != 0 ? push.displacement : 0))
            * maxScale(instance);
        bool inView = inFrustum(view, center, radius);
        if (phase == PHASE_EARLY && (!inView || view.visibility[instanceId] == 0))
        {
            return 0; // the early phase draws what was visible last frame
        }
        if (phase == PHASE_LATE)
        {
            // Every instance in view is tested against the pyramid of what the early phase drew; the late phase draws
            // those now visible that the early phase did not.
            bool drawnEarly = view.visibility[instanceId] != 0;
            bool visible = inView && unoccluded(view, push.occlusion, center, radius);
            view.visibility[instanceId] = visible ? (drawnEarly ? 1 : NEWLY_VISIBLE) : 0;
            if (!visible || drawnEarly)
            {
                return 0;
            }
        }
        else if (!inView)
        {
            return 0;
        }
    }
    // Only the meshlets the LOD rule can pick at this distance: none past the first level whose error, seen from the
    // instance's nearest point, is fine enough (meshlets are stored coarsest first).
    uint end = mesh.meshletCount;
    float scale = maxScale(instance);
    // Orthographic views: errors do not shrink with distance (as if at distance 1).
    float distance = view.orthographic != 0 ? 1.0
        : length(toWorld(instance, mesh.lodSphere.xyz) - view.eye) - mesh.lodSphere.w * scale;
    if (distance > 0)
    {
        for (uint l = 0; l < mesh.levelCount; l++)
        {
            Level level = push.levels[mesh.levelBase + l];
            if (level.error * scale * view.projectionScale / distance <= view.errorPixels)
            {
                end = level.end;
                break;
            }
        }
    }
    return (end + 63) / 64;
}

[shader("compute")]
[numthreads(64, 1, 1)]
void instancesMain(uint3 groupId : SV_GroupID, uint3 thread : SV_GroupThreadID)
{
    // The workgroup's row and place in it; whole workgroups leave together, before the barriers below.
    uint rowIndex;
    uint group;
    bool retry = push.round > 0;
    if (!retry)
    {
        rowIndex = push.firstRow + groupId.y;
        group = groupId.x;
    }
    else
    {
        uint linear = groupId.y * 65535 + groupId.x; // rows of 65,535 workgroups
        if (linear >= push.rowCount * push.groupsPerRow)
        {
            return;
        }
        rowIndex = linear / push.groupsPerRow;
        group = linear % push.groupsPerRow;
    }
    uint bit = rowIndex * push.groupsPerRow + group;
    if (retry && (push.retry[bit >> 5] & (1u << (bit & 31))) == 0)
    {
        return; // placed in an earlier round, or wanting nothing
    }
    CulledView row = push.rows[rowIndex];
    uint instanceId;
    uint chunks = wantedChunks(group * 64 + thread.x, row, retry, instanceId);
    // The workgroup's chunks, each thread's after those of the threads before it; then room for all of them or none.
    if (thread.x == 0)
    {
        groupTotal = 0;
    }
    GroupMemoryBarrierWithGroupSync();
    uint local = 0;
    if (chunks > 0)
    {
        InterlockedAdd(groupTotal, chunks, local);
    }
    GroupMemoryBarrierWithGroupSync();
    uint total = groupTotal;
    if (total == 0)
    {
        return; // the same for the whole workgroup
    }
    if (thread.x == 0)
    {
        uint taken;
        InterlockedAdd(push.chunkCommand[3], total, taken);
        groupFirst = taken;
        if (!retry)
        {
            InterlockedMax(push.stats[0], taken + total); // the cull's chunks, for the CPU to grow the room to
        }
    }
    GroupMemoryBarrierWithGroupSync();
    uint first = groupFirst;
    bool alone = total > push.maxChunks; // more than the whole room: what fits, the rest dropped
    if (first + total <= push.maxChunks || alone)
    {
        uint at = first + local;
        for (uint i = 0; i < chunks && at + i < push.maxChunks; i++)
        {
            push.chunks[at + i] = Chunk(instanceId, i * 64, row.view, row.phase);
        }
        if (chunks > 0 && at < push.maxChunks)
        {
            // The cull dispatch: rows of 65,535 workgroups, as many as the chunks written; and how many those are,
            // for the workgroups of the last row past them.
            uint written = min(at + chunks, push.maxChunks);
            InterlockedMax(push.chunkCommand[0], min(written, 65535));
            InterlockedMax(push.chunkCommand[1], (written + 65534) / 65535);
            InterlockedMax(push.chunkCommand[4], written);
        }
        if (chunks > 0 && at + chunks > push.maxChunks)
        {
            InterlockedAdd(push.stats[1], 1);
            InterlockedAdd(push.stats[3], at + chunks - max(at, push.maxChunks));
        }
        if (thread.x == 0 && retry)
        {
            InterlockedAnd(push.retry[bit >> 5], ~(1u << (bit & 31)));
            InterlockedMax(push.stats[2], push.round + 1);
        }
    }
    else if (push.lastRound != 0)
    {
        if (chunks > 0) // no room left in the last round: dropped, and counted
        {
            InterlockedAdd(push.stats[1], 1);
            InterlockedAdd(push.stats[3], chunks);
        }
        if (thread.x == 0)
        {
            InterlockedMax(push.stats[2], push.round + 1);
        }
    }
    else if (thread.x == 0)
    {
        // Again in the next round: marked, and that round's dispatch made to cover every workgroup of the cull.
        if (!retry)
        {
            InterlockedOr(push.retry[bit >> 5], 1u << (bit & 31));
        }
        uint groups = push.rowCount * push.groupsPerRow;
        InterlockedMax(push.chunkCommand[5], min(groups, 65535));
        InterlockedMax(push.chunkCommand[6], (groups + 65534) / 65535);
    }
}
