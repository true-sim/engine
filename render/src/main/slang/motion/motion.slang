// Motion vectors' pass (motion/vectors.slang, engine-render's MotionVectors): a thread per pixel, a group per 8x8 tile.
// Pixels of a type displacing in the vertex stages (GEOMETRY_DISPLACE, RASTER) are left to that type's own pass: their
// tile goes into the list of the type's geometry number, which its pass (material-types/entryDisplaceMotion) reads.
import motion.vectors;

static const uint MAX_LISTS = 2048; // engine-render's MotionVectors.MAX_GEOMETRY_TYPES
groupshared uint present[MAX_LISTS / 32]; // the lists the tile goes into, a bit each

[shader("compute")]
[numthreads(8, 8, 1)]
void motionMain(uint3 id : SV_DispatchThreadID, uint3 group : SV_GroupID, uint3 thread : SV_GroupThreadID)
{
    uint index = thread.y * 8 + thread.x;
    if (push.lists != 0)
    {
        for (uint w = index; w < (push.lists + 31) / 32; w += 64)
        {
            present[w] = 0;
        }
        GroupMemoryBarrierWithGroupSync();
    }
    uint2 size = regionSize();
    bool inside = id.x < size.x && id.y < size.y;
    bool own = inside; // written here: not a displaced pixel left to its type's pass
    if (inside && push.lists != 0)
    {
        uint2 ids = uintImages[push.ids][id.xy];
        if (ids.x != 0)
        {
            Instance stored;
            Material m = pixelMaterial(ids, stored);
            uint geometry = materialGeometry(m);
            if (displacedInStages(stored, m) && geometry != 0 && geometry <= push.lists)
            {
                own = false;
                InterlockedOr(present[(geometry - 1) >> 5], 1u << ((geometry - 1) & 31));
            }
        }
    }
    if (own)
    {
        writeMotion<MetallicRoughnessMaterial>(id.xy, false);
    }
    if (push.lists != 0)
    {
        GroupMemoryBarrierWithGroupSync();
        for (uint w = index; w < (push.lists + 31) / 32; w += 64)
        {
            uint bits = present[w];
            while (bits != 0)
            {
                uint* list = tileList(w * 32 + firstbitlow(bits));
                bits &= bits - 1;
                uint slot;
                InterlockedAdd(list[3], 1, slot);
                InterlockedMax(list[0], min(slot + 1, TILE_ROW));
                InterlockedMax(list[1], slot / TILE_ROW + 1);
                list[2] = 1;
                list[4 + slot] = group.x | group.y << 16;
            }
        }
    }
}
