// The classic LOD path's tables (engine-render's ClassicLodGeometry): per mesh its detail levels, coarsest first, and
// per level its parts, each a contiguous range of one submesh's indices in the path's index buffer. Selection writes
// a draw per part of the level each instance needs; the vertex stage finds the draw's instance and part by its draw
// slot (the first instance).
module parts;

__exported import meshlets.common;

/** A mesh drawn the classic way: its levels in the level table, coarsest first (none: not drawn so). 8 bytes. */
public struct ClassicMesh
{
    public uint levelBase;
    public uint levelCount;
};

/** One detail level: its parts and its error, in the mesh's units (0: full detail). 16 bytes. */
public struct ClassicLevel
{
    public uint firstPart;
    public uint partCount;
    public float error;
    public uint pad;
};

/**
 * Part of a level: its indices, its first pool meshlet (for meshlet colors) and its tag: CLASSIC_TRIANGLE, the submesh
 * << 24 and its first triangle in the mesh, what the visibility buffer stores of its triangles. 16 bytes.
 */
public struct ClassicPart
{
    public uint firstIndex;
    public uint indexCount;
    public uint meshlet;
    public uint tag;
};

/** What draw slot s draws: an instance and its part's meshlet and tag. 16 bytes. */
public struct ClassicDraw
{
    public uint instance;
    public uint meshlet;
    public uint tag;
    public uint pad;
};
