package com.truesim.engine.render.shadows;

import com.truesim.annotations.AllowAllocation;

/**
 * Square tiles of power-of-two sizes in a square atlas, by the buddy scheme over a quadtree: a tile is a node; a node
 * too big for a request splits into four, and four free siblings merge again. Tiles never move: a light keeps its
 * tile while its size stays. Allocates nothing once made.
 */
@AllowAllocation("made rarely")
final class AtlasAllocator {

    private static final byte ABSENT = 0; // inside a free or used ancestor
    private static final byte FREE = 1;
    private static final byte SPLIT = 2;
    private static final byte USED = 3;

    private final int size;
    private final int levels; // level 0: the whole atlas; level k: size >> k
    private final int[] offset; // where each level's nodes start
    private final byte[] state;
    private final boolean[] listed;
    private final int[][] free; // per level, a stack of nodes that may be free
    private final int[] freeCount;
    private long used; // texels in tiles

    /** An atlas of {@code size} texels per side (a power of two), of tiles of {@code minTile} texels or more. */
    AtlasAllocator(int size, int minTile) {
        if (Integer.bitCount(size) != 1 || Integer.bitCount(minTile) != 1 || minTile > size) {
            throw new IllegalArgumentException("An atlas of " + size + " in tiles of " + minTile);
        }
        this.size = size;
        levels = Integer.numberOfTrailingZeros(size / minTile) + 1;
        offset = new int[levels + 1];
        for (int k = 0; k < levels; k++) {
            offset[k + 1] = offset[k] + (1 << (2 * k));
        }
        state = new byte[offset[levels]];
        listed = new boolean[offset[levels]];
        free = new int[levels][];
        freeCount = new int[levels];
        for (int k = 0; k < levels; k++) {
            free[k] = new int[Math.min(1 << (2 * k), 1 << 16)];
        }
        clear();
    }

    /** Every tile freed. */
    void clear() {
        java.util.Arrays.fill(state, ABSENT);
        java.util.Arrays.fill(listed, false);
        java.util.Arrays.fill(freeCount, 0);
        state[0] = FREE;
        push(0, 0);
        used = 0;
    }

    int size() {
        return size;
    }

    /** Texels in tiles. */
    long used() {
        return used;
    }

    /** The level of a tile of {@code tile} texels per side, or -1 if there is none of that size. */
    int level(int tile) {
        int k = Integer.numberOfTrailingZeros(size) - Integer.numberOfTrailingZeros(tile);
        return Integer.bitCount(tile) == 1 && k >= 0 && k < levels ? k : -1;
    }

    /** A tile of {@code tile} texels per side: its node, or -1 if none is free. */
    int allocate(int tile) {
        int level = level(tile);
        if (level < 0) {
            return -1;
        }
        int node = take(level);
        if (node >= 0) {
            state[node] = USED;
            used += (long) tile * tile;
        }
        return node;
    }

    /** A free node of {@code level}, split from a bigger one if needed, or -1. */
    private int take(int level) {
        while (freeCount[level] > 0) {
            int node = free[level][--freeCount[level]];
            listed[node] = false;
            if (state[node] == FREE) {
                return node;
            }
        }
        if (level == 0) {
            return -1;
        }
        int parent = take(level - 1);
        if (parent < 0) {
            return -1;
        }
        state[parent] = SPLIT;
        int first = child(parent, level - 1);
        for (int c = 1; c < 4; c++) {
            state[first + c] = FREE;
            push(level, first + c);
        }
        state[first] = FREE;
        return first;
    }

    /** Frees tile {@code node}, merging free siblings. */
    void release(int node) {
        if (state[node] != USED) {
            throw new IllegalStateException("Tile " + node + " is not in use");
        }
        int level = levelOf(node);
        int tile = size >> level;
        used -= (long) tile * tile;
        state[node] = FREE;
        while (level > 0) {
            int local = node - offset[level];
            int first = offset[level] + (local & ~3);
            for (int c = 0; c < 4; c++) {
                if (state[first + c] != FREE) {
                    push(level, node);
                    return;
                }
            }
            for (int c = 0; c < 4; c++) {
                state[first + c] = ABSENT;
            }
            level--;
            node = offset[level] + (local >> 2);
            state[node] = FREE;
        }
        push(0, node);
    }

    private void push(int level, int node) {
        if (listed[node]) {
            return;
        }
        if (freeCount[level] == free[level].length) {
            free[level] = java.util.Arrays.copyOf(free[level], free[level].length * 2);
        }
        free[level][freeCount[level]++] = node;
        listed[node] = true;
    }

    private int child(int node, int level) {
        return offset[level + 1] + 4 * (node - offset[level]);
    }

    private int levelOf(int node) {
        int k = 0;
        while (node >= offset[k + 1]) {
            k++;
        }
        return k;
    }

    /** Tile {@code node}'s texels per side. */
    int tileSize(int node) {
        return size >> levelOf(node);
    }

    /** Tile {@code node}'s left edge, in texels. */
    int x(int node) {
        int level = levelOf(node);
        return compact(node - offset[level]) * (size >> level);
    }

    /** Tile {@code node}'s top edge, in texels. */
    int y(int node) {
        int level = levelOf(node);
        return compact((node - offset[level]) >> 1) * (size >> level);
    }

    /** The even bits of a Morton code. */
    private static int compact(int v) {
        v &= 0x55555555;
        v = (v | (v >> 1)) & 0x33333333;
        v = (v | (v >> 2)) & 0x0f0f0f0f;
        v = (v | (v >> 4)) & 0x00ff00ff;
        v = (v | (v >> 8)) & 0x0000ffff;
        return v;
    }
}
