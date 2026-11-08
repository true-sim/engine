package com.truesim.engine.render;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.render.layout.HooksConstants;
import com.truesim.engine.render.layout.ShadingHookBlockLayout;
import com.truesim.engine.vulkan.FrameSlots;
import com.truesim.engine.vulkan.VulkanDevice;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * A scene's hook block for its geometry ({@link GpuScene#geometryHooks}): one block of the shading hooks' layout
 * (hooks.slang's {@code ShadingHookBlock}) for every view of the scene, which material types' {@code displace()} read
 * (materials.slang's {@code DisplacePoint.hook}): wind, a snow depth field. One block, so every view (the camera, every
 * shadow map and tile, the visibility buffer's IDs, motion vectors, the compute pass displacing copies) displaces
 * alike; and kept as the frame before had it too ({@code SceneTable.previousHooks}), which motion vectors evaluate the
 * frame before's displacement with.
 *
 * <p>Each hook's record and bit, composed once a frame as the scene's instances upload: what code set this frame or
 * before ({@link #set}, {@link #turn}: a library's technique writing its wind each frame), else what the scene holds
 * ({@link ShadingHookValues}: a component's values, on or off), else the record's defaults, off; then the scene's
 * per-instance override entries ({@link GpuScene#hookOverrides}), as views' blocks carry them. Host-visible, a copy per
 * frame slot, written in one piece. Set from the thread changing the scene, between frames; allocates nothing then.
 */
public final class GeometryHooks implements AutoCloseable {

    private static final ValueLayout.OfInt U32 = ValueLayout.JAVA_INT_UNALIGNED;
    private static final ShadingHookSource[] NO_SOURCES = new ShadingHookSource[0];

    private final GpuScene scene;
    private final ShadingHooks hooks;
    private final ShadingHookValues values;
    private final ShadingHookBlock current;
    private final ShadingHookBlock before;
    private final ShadingHookParameters[] records; // set by code, by hook index; null: the scene's or the defaults
    private final Boolean[] turned;                // switched by code; null: on where set, else as the scene's values
    private long published;                        // frames composed
    private boolean dirty;                         // set or turned since the last composition
    private long seenValues = -1;                  // the scene values' changes composed last
    private boolean changedLast;                   // the last publish changed the frame's block

    @AllowAllocation("made with its scene")
    GeometryHooks(VulkanDevice device, GpuScene scene, FrameSlots slots) {
        this.scene = scene;
        this.values = ShadingHookValues.of(device);
        this.hooks = values.hooks();
        current = new ShadingHookBlock(device, hooks, slots);
        before = new ShadingHookBlock(device, hooks, slots);
        records = new ShadingHookParameters[hooks.count()];
        turned = new Boolean[hooks.count()];
    }

    /** The listing its records are laid out by. */
    public ShadingHooks listing() {
        return hooks;
    }

    /**
     * Hook {@code hook}'s record from the next frame on, until set again ({@code record} null: the scene's values or the
     * defaults again); a hook set is on unless {@link #turn turned} off.
     *
     * @throws IllegalStateException where the shaders were built without the hook
     * @throws IllegalArgumentException where {@code record} is not of the hook's parameters' record class
     */
    public synchronized GeometryHooks set(String hook, ShadingHookParameters record) {
        int index = hooks.require(hook);
        if (record != null && record.getClass() != hooks.parameters(index)) {
            throw new IllegalArgumentException(hook + " takes " + hooks.parameters(index).getSimpleName() + ", not "
                + record.getClass().getSimpleName());
        }
        records[index] = record;
        dirty = true;
        return this;
    }

    /** Switches hook {@code hook} on or off from the next frame on; null: on where set, else as the scene's values. */
    public synchronized GeometryHooks turn(String hook, Boolean on) {
        turned[hooks.require(hook)] = on;
        dirty = true;
        return this;
    }

    /** How many frames it published: those changing it, and the one after each. */
    public synchronized long frames() {
        return published;
    }

    /**
     * Composes the frame's block (and keeps the one before as the frame before's), into the copies of the frame slot
     * recording: as the scene's instances upload, once a frame; in frames where nothing changed (after the frame
     * following a change), nothing is written. Allocates nothing, unless the override section grows.
     */
    synchronized void publish() {
        ShadingHookOverrides overrides = scene.hookOverridesIfMade();
        long seen = values.changes();
        // Unchanged since the frame before (nothing set, no scene value, no override entries): the blocks stand, and
        // the frame before's equals this frame's once a frame passed (a block's writes reach every slot's copy)
        boolean changes = published == 0 || dirty || seen != seenValues || overrides != null;
        if (!changes && !changedLast) {
            return;
        }
        long bytes = ShadingHookOverrides.blockBytes(hooks, overrides);
        current.ensure(bytes, 2);
        before.ensure(Math.max(bytes, current.size()), 2);
        // The frame before's: what was composed last (as it was, its instances' entries then included)
        MemorySegment last = current.staging();
        MemorySegment kept = before.staging();
        if (published == 0) {
            compose(last, overrides, current.address());
        }
        MemorySegment.copy(last, 0, kept, 0, Math.min(last.byteSize(), kept.byteSize()));
        if (overrides == null) { // its instance pointer at a word of its own copy, as composed blocks have it
            kept.set(ValueLayout.JAVA_LONG_UNALIGNED, ShadingHookBlockLayout.INSTANCE_ENTRIES,
                before.address() + ShadingHookBlockLayout.INSTANCES);
        }
        before.publishAll();
        if (changes) {
            compose(last, overrides, current.address());
            current.publishAll();
        }
        dirty = false;
        seenValues = seen;
        changedLast = changes;
        published++;
    }

    /** The block's bits and records into {@code block}, then the override section (its copy at {@code address}). */
    private void compose(MemorySegment block, ShadingHookOverrides overrides, long address) {
        int n = hooks.count();
        long words = HooksConstants.SHADING_HOOK_HEADER;
        for (int w = 0; w < (n + 31) / 32; w++) {
            block.set(U32, words + 4L * w, 0);
        }
        for (int i = 0; i < n; i++) {
            ShadingHookParameters record = records[i];
            ShadingHookParameters authored = values.authored(i);
            boolean on;
            if (record != null) {
                record.write(block, hooks.recordOffset(i));
                on = true;
            } else if (authored != null) {
                authored.write(block, hooks.recordOffset(i));
                on = values.on(i);
            } else {
                hooks.defaults(i).write(block, hooks.recordOffset(i));
                on = false;
            }
            if (turned[i] != null) {
                on = turned[i];
            }
            if (on) {
                long at = ShadingHooks.activeWord(i);
                block.set(U32, at, block.get(U32, at) | 1 << (i % 32));
            }
        }
        ShadingHookOverrides.compose(hooks, overrides, null, block, address, NO_SOURCES);
    }

    /** The frame's block, for the frame slot recording. */
    public long address() {
        return current.address();
    }

    /** The frame before's block (this frame's on the first), for the frame slot recording. */
    public long previousAddress() {
        return before.address();
    }

    /** Its listing's record bytes in the frame's block, at hook {@code hook}'s offset: for tests. */
    int recordInt(String hook, int offset) {
        return current.getInt(hooks.recordOffset(hooks.require(hook)) + offset);
    }

    @Override
    public void close() {
        current.close();
        before.close();
    }
}
