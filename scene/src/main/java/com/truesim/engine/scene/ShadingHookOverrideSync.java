package com.truesim.engine.scene;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.render.ShadingHookOverrides;
import com.truesim.engine.render.ShadingHooks;
import com.truesim.scene.core.ComponentStore;
import com.truesim.scene.core.NodeHandle;
import com.truesim.scene.core.SceneGraph;
import java.util.Arrays;
import java.util.Objects;

/**
 * The scene's shading hook overrides ({@link ShadingHookOverride}) handed to the renderer's per-instance
 * {@link ShadingHookOverrides}: an entry per overriding node, holding per hook the switch and values of the nearest
 * overriding node at or above it that sets the hook; every renderable below it (down to the next overriding node) drawn
 * with that entry, the others with none. Follows components set, replaced and removed, nodes moved between parents,
 * renderables coming and going. A hook the shaders were built without is left out.
 *
 * <pre>{@code
 * ShadingHookOverrideSync overrides = new ShadingHookOverrideSync(scene, sceneSync, gpuScene.hookOverrides());
 * // each frame, after sceneSync.update(), before the frame records:
 * overrides.update();
 * int entry = overrides.entryOf(emitterNode); // a library's own drawing of a node: its entry, or 0
 * }</pre>
 *
 * <p>Each update walks the subtrees of the overriding nodes (their cost, not the scene's) and hands over only what
 * changed. Allocates nothing per frame unless overrides or their nodes change; one thread.
 */
public final class ShadingHookOverrideSync {

    private final SceneGraph scene;
    private final SceneSync sync;
    private final ShadingHookOverrides overrides;
    private final ComponentStore<ShadingHookOverride> store;
    private final ShadingHooks hooks;
    private int generation;

    // This update's overriding nodes, by index: node, component, hook index, nearest overriding ancestor (or -1)
    private long[] nodes = new long[16];
    private ShadingHookOverride[] components = new ShadingHookOverride[16];
    private int[] hookOf = new int[16];
    private int[] above = new int[16];
    private int[] kept = new int[16]; // its kept record
    private int count;
    private final NodeMap current = new NodeMap();

    // Kept across updates per overriding node: its entry, the component handed over per hook, last seen
    private final NodeMap keptOf = new NodeMap();
    private long[] keptNode = new long[16];
    private int[] keptEntry = new int[16];
    private ShadingHookOverride[][] keptApplied = new ShadingHookOverride[16][];
    private int[] keptSeen = new int[16];
    private int keptCount;

    // The instances given an entry: last update's and this one's, and when each was last given one
    private int[] given = new int[64];
    private int givenCount;
    private int[] giving = new int[64];
    private int givingCount;
    private int[] givenAt = new int[64];
    private long[] walk = new long[16];

    /** {@code graph}'s overrides handed to {@code overrides} for the renderables {@code sync} draws. */
    public ShadingHookOverrideSync(SceneGraph graph, SceneSync sync, ShadingHookOverrides overrides) {
        this.scene = Objects.requireNonNull(graph, "graph");
        this.sync = Objects.requireNonNull(sync, "sync");
        this.overrides = Objects.requireNonNull(overrides, "overrides");
        this.store = graph.components().store(ShadingHookOverride.TYPE);
        this.hooks = overrides.hooks();
    }

    /** Hands over what changed since the last update. */
    public void update() {
        generation++;
        gather();
        for (int i = 0; i < count; i++) {
            above[i] = nearestAbove(nodes[i]);
        }
        for (int i = 0; i < count; i++) {
            entryValues(i);
        }
        givingCount = 0;
        for (int i = 0; i < count; i++) {
            give(i);
        }
        for (int g = 0; g < givenCount; g++) {
            int instance = given[g];
            if (givenAt[instance] != generation) {
                overrides.assign(instance, 0); // below no overriding node any more
            }
        }
        int[] swap = given;
        given = giving;
        giving = swap;
        givenCount = givingCount;
        dropUnseen();
    }

    /**
     * The override entry of {@code node}: its nearest overriding node's at or above it (as of the last update), 0 for
     * none. What a library drawing a node itself (a particle emitter) gives its points. Allocates nothing.
     */
    public int entryOf(long node) {
        for (long n = node; n != NodeHandle.NONE && scene.contains(n); n = scene.parent(n)) {
            int k = keptOf.get(n);
            if (k >= 0 && keptSeen[k] == generation) {
                return keptEntry[k];
            }
        }
        return 0;
    }

    /** This update's overriding nodes, their kept records made where new. */
    private void gather() {
        count = 0;
        current.clear();
        for (int i = 0; i < store.size(); i++) {
            ShadingHookOverride c = store.value(i);
            int hook = hooks.index(c.hook());
            long node = store.node(i);
            if (hook < 0 || !scene.contains(node) || current.get(node) >= 0) {
                continue;
            }
            if (count == nodes.length) {
                growNodes();
            }
            nodes[count] = node;
            components[count] = c;
            hookOf[count] = hook;
            int k = keptOf.get(node);
            if (k < 0) {
                k = keep(node);
            }
            keptSeen[k] = generation;
            kept[count] = k;
            current.put(node, count++);
        }
    }

    /** The index of the nearest overriding node strictly above {@code node}, or -1. */
    private int nearestAbove(long node) {
        for (long n = scene.parent(node); n != NodeHandle.NONE; n = scene.parent(n)) {
            int i = current.get(n);
            if (i >= 0) {
                return i;
            }
        }
        return -1;
    }

    /** Hands over the values of node {@code i}'s entry that changed: per hook, its nearest setter's. */
    private void entryValues(int i) {
        int k = kept[i];
        ShadingHookOverride[] applied = keptApplied[k];
        for (int hook = 0; hook < applied.length; hook++) {
            ShadingHookOverride chosen = null;
            for (int j = i; j >= 0; j = above[j]) {
                if (hookOf[j] == hook) {
                    chosen = components[j];
                    break;
                }
            }
            if (chosen != applied[hook]) {
                hand(keptEntry[k], hook, chosen);
                applied[hook] = chosen;
            }
        }
    }

    @AllowAllocation("overrides change rarely")
    private void hand(int entry, int hook, ShadingHookOverride chosen) {
        String name = hooks.hooks().get(hook);
        if (chosen == null) {
            overrides.clear(entry, name);
            overrides.turn(entry, name, null);
        } else {
            overrides.set(entry, name, new ShadingHookSync.Values(chosen.values(), ""));
            overrides.turn(entry, name, chosen.on());
        }
    }

    /** Gives node {@code i}'s entry to the renderables of its subtree, down to the next overriding nodes. */
    private void give(int i) {
        long root = nodes[i];
        int entry = keptEntry[kept[i]];
        give(sync.slotOf(root), entry);
        long node = scene.firstChild(root);
        while (node != NodeHandle.NONE) {
            boolean own = current.get(node) >= 0; // an overriding node gives its own subtree
            if (!own) {
                give(sync.slotOf(node), entry);
            }
            long child = own ? NodeHandle.NONE : scene.firstChild(node);
            if (child != NodeHandle.NONE) {
                node = child;
                continue;
            }
            while (node != root && scene.nextSibling(node) == NodeHandle.NONE) {
                node = scene.parent(node);
            }
            node = node == root ? NodeHandle.NONE : scene.nextSibling(node);
        }
    }

    private void give(int instance, int entry) {
        if (instance < 0) {
            return;
        }
        if (instance >= givenAt.length) {
            growGivenAt(instance + 1);
        }
        if (givenAt[instance] == generation) {
            return; // given by an overriding node seen before (one under another is skipped by its walk)
        }
        givenAt[instance] = generation;
        if (givingCount == giving.length) {
            giving = Arrays.copyOf(giving, 2 * giving.length);
            given = Arrays.copyOf(given, Math.max(given.length, giving.length));
        }
        giving[givingCount++] = instance;
        if (overrides.entryOf(instance) != entry) {
            overrides.assign(instance, entry);
        }
    }

    /** Removes the entries of nodes no longer overriding, once nothing is given them. */
    private void dropUnseen() {
        boolean dropped = false;
        for (int k = 0; k < keptCount; k++) {
            if (keptNode[k] != NodeHandle.NONE && keptSeen[k] != generation) {
                overrides.remove(keptEntry[k]);
                keptNode[k] = NodeHandle.NONE;
                keptApplied[k] = null;
                dropped = true;
            }
        }
        if (dropped) {
            keptOf.clear();
            for (int k = 0; k < keptCount; k++) {
                if (keptNode[k] != NodeHandle.NONE) {
                    keptOf.put(keptNode[k], k);
                }
            }
        }
    }

    @AllowAllocation("overriding nodes change rarely")
    private int keep(long node) {
        int k = 0;
        while (k < keptCount && keptNode[k] != NodeHandle.NONE) {
            k++;
        }
        if (k == keptCount) {
            if (keptCount == keptNode.length) {
                int more = 2 * keptCount;
                keptNode = Arrays.copyOf(keptNode, more);
                keptEntry = Arrays.copyOf(keptEntry, more);
                keptApplied = Arrays.copyOf(keptApplied, more);
                keptSeen = Arrays.copyOf(keptSeen, more);
            }
            keptCount++;
        }
        keptNode[k] = node;
        keptEntry[k] = overrides.add();
        keptApplied[k] = new ShadingHookOverride[hooks.count()];
        keptOf.put(node, k);
        return k;
    }

    @AllowAllocation("overriding nodes change rarely")
    private void growNodes() {
        int more = 2 * nodes.length;
        nodes = Arrays.copyOf(nodes, more);
        components = Arrays.copyOf(components, more);
        hookOf = Arrays.copyOf(hookOf, more);
        above = Arrays.copyOf(above, more);
        kept = Arrays.copyOf(kept, more);
    }

    @AllowAllocation("growing with the scene's instances")
    private void growGivenAt(int at) {
        givenAt = Arrays.copyOf(givenAt, Math.max(at, 2 * givenAt.length));
    }

    /** Nodes to small ints, open addressing: allocates nothing once grown. */
    private static final class NodeMap {
        private long[] keys = new long[32];
        private int[] values = new int[32];
        private int size;

        NodeMap() {
            Arrays.fill(keys, NodeHandle.NONE);
        }

        int get(long node) {
            int mask = keys.length - 1;
            for (int at = slot(node, mask); ; at = (at + 1) & mask) {
                if (keys[at] == node) {
                    return values[at];
                }
                if (keys[at] == NodeHandle.NONE) {
                    return -1;
                }
            }
        }

        void put(long node, int value) {
            if (2 * (size + 1) > keys.length) {
                grow();
            }
            int mask = keys.length - 1;
            int at = slot(node, mask);
            while (keys[at] != NodeHandle.NONE && keys[at] != node) {
                at = (at + 1) & mask;
            }
            if (keys[at] == NodeHandle.NONE) {
                size++;
            }
            keys[at] = node;
            values[at] = value;
        }

        void clear() {
            if (size > 0) {
                Arrays.fill(keys, NodeHandle.NONE);
                size = 0;
            }
        }

        @AllowAllocation("growing with the overriding nodes")
        private void grow() {
            long[] oldKeys = keys;
            int[] oldValues = values;
            keys = new long[2 * oldKeys.length];
            values = new int[keys.length];
            Arrays.fill(keys, NodeHandle.NONE);
            size = 0;
            for (int i = 0; i < oldKeys.length; i++) {
                if (oldKeys[i] != NodeHandle.NONE) {
                    put(oldKeys[i], oldValues[i]);
                }
            }
        }

        private static int slot(long node, int mask) {
            long h = node * 0x9E3779B97F4A7C15L;
            return (int) (h ^ (h >>> 32)) & mask;
        }
    }
}
