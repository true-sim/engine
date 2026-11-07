package com.truesim.engine.render;

import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
import static org.lwjgl.vulkan.VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.math.Vec2;
import com.truesim.common.math.Vec3;
import com.truesim.common.math.Vec4;
import com.truesim.common.math.color.Color;
import com.truesim.engine.graph.PassContext;
import com.truesim.engine.render.layout.HooksConstants;
import com.truesim.engine.render.layout.ShadingHookBlockLayout;
import com.truesim.engine.vulkan.DeviceBuffer;
import com.truesim.engine.vulkan.FrameBuffer;
import com.truesim.engine.vulkan.Retiring;
import com.truesim.engine.vulkan.VulkanDevice;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Shading hook switches and values per instance, layered over each view's ({@link GpuScene#hookOverrides}): numbered
 * entries, each holding for some hooks whether they run (off, on, or as the view runs them) and the fields it sets (by
 * field name, as content holds them, or a whole record); each instance of the scene takes one entry or none (0). Where
 * a point of an instance is shaded (rasterized, by the visibility buffer, a ray's or a probe ray's hit, a particle of
 * an emitter given the entry), every hook point runs a hook as the entry switches it and reads the hook's record of the
 * view with the entry's fields in place of its own: fog thinner inside a building, snow off under a roof. Only the sky,
 * which has no instance, keeps the view's. A material refusing a hook ({@link Material#refuseHooks}) wins over an entry
 * switching it on; a hook switched on runs only where the view's pipelines are made with it.
 *
 * <pre>{@code
 * ShadingHookOverrides overrides = scene.hookOverrides();
 * int inside = overrides.add();
 * overrides.set(inside, "game.fog", values);   // values: MaterialValues, the fields set override the view's
 * overrides.turn(inside, "game.snow", false);  // no snow inside; null: as the view again
 * overrides.assign(houseInstance, inside);     // 0: none again
 * }</pre>
 *
 * <p>Each view's hook block carries the entries ({@link ShadingHookBlock}): per entry the hooks it switches off and on
 * and the record offsets of the hooks it sets values of, then their merged records (the view's bytes, the entry's
 * fields over them, then the hook's technique's {@link ShadingHookSource#override}), composed each frame in the view's
 * hook pass at a cost of the entries' records; the block grows to hold them. The instances' entries are a host-visible
 * buffer of a uint per instance id, a copy per frame slot, growing with the scene. engine-scene's {@code
 * ShadingHookOverrideSync} keeps them from scene components.
 *
 * <p>Changes are made between frames, from one thread (the scene's sync); views read a consistent copy of the entries
 * as their frame records. Assigning allocates nothing; entries' values allocate as they change.
 */
public final class ShadingHookOverrides implements AutoCloseable {

    private static final ValueLayout.OfInt U32 = ValueLayout.JAVA_INT_UNALIGNED;
    private static final ValueLayout.OfLong U64 = ValueLayout.JAVA_LONG_UNALIGNED;

    private final VulkanDevice device;
    private final GpuScene scene;
    private final ShadingHooks hooks;
    private final Retiring outgrown;
    private final Runnable follower = this::follow;
    private FrameBuffer instances; // a uint per instance id: its entry; made at the first entry assigned
    private int[] assigned = new int[0]; // the CPU's copy
    private volatile Table table = new Table(new Fields[1][], 0);
    private boolean closed;

    /**
     * An entry's override of one hook: whether it runs (null: as the view runs it), and its values where it sets some
     * (else null): its record (defaults where unset), its bytes, and the (offset, length) byte ranges it sets.
     */
    private record Fields(Boolean on, ShadingHookParameters record, MemorySegment bytes, int[] ranges) {

        boolean values() {
            return record != null;
        }

        /** This with the values of {@code other}, or null where it holds nothing. */
        static Fields of(Boolean on, Fields values) {
            return on == null && (values == null || !values.values()) ? null
                : values == null ? new Fields(on, null, null, null)
                : new Fields(on, values.record, values.bytes, values.ranges);
        }
    }

    /** The entries as views read them: by entry (0 unused, null free), by hook. Immutable once made. */
    private record Table(Fields[][] rows, long recordBytes) {

        int entries() {
            return rows.length - 1;
        }
    }

    @AllowAllocation("made once per scene, at need")
    ShadingHookOverrides(VulkanDevice device, GpuScene scene) {
        this.device = Objects.requireNonNull(device, "device");
        this.scene = Objects.requireNonNull(scene, "scene");
        hooks = ShadingHooks.of(device);
        outgrown = new Retiring(scene.frameSlots());
    }

    /** The listing it is of. */
    public ShadingHooks hooks() {
        return hooks;
    }

    // ---------------------------------------------------------------- entries

    /** A new entry, overriding nothing yet: the lowest free number from 1. */
    @AllowAllocation("entries change rarely")
    public synchronized int add() {
        Fields[][] rows = table.rows();
        int entry = 1;
        while (entry < rows.length && rows[entry] != null) {
            entry++;
        }
        Fields[][] more = Arrays.copyOf(rows, Math.max(rows.length, entry + 1));
        more[entry] = new Fields[hooks.count()];
        table = new Table(more, table.recordBytes());
        return entry;
    }

    /**
     * Removes entry {@code entry}: its number is free for {@link #add} again. Instances still assigned to it read the
     * view's values; assign them before reusing the number.
     */
    @AllowAllocation("entries change rarely")
    public synchronized void remove(int entry) {
        Fields[][] rows = table.rows();
        check(rows, entry);
        Fields[][] less = rows.clone();
        less[entry] = null;
        int length = less.length;
        while (length > 1 && less[length - 1] == null) {
            length--;
        }
        table = new Table(Arrays.copyOf(less, length), recordBytes(less));
    }

    /**
     * Sets entry {@code entry}'s values for hook {@code hook} from {@code values} by field name: the fields they hold
     * override the view's record, the others stay the view's.
     *
     * @throws IllegalStateException where the shaders were built without the hook
     */
    @AllowAllocation("values change rarely")
    public ShadingHookOverrides set(int entry, String hook, MaterialValues values) {
        int index = hooks.require(hook);
        Probe probe = new Probe(Objects.requireNonNull(values, "values"), "");
        ShadingHookParameters record = hooks.fromValues(index, probe);
        return set(entry, index, record, ranges(hooks.parameters(index), record.bytes(), probe.paths));
    }

    /** Sets entry {@code entry}'s values for hook {@code hook} to all of {@code record}. */
    @AllowAllocation("values change rarely")
    public ShadingHookOverrides set(int entry, String hook, ShadingHookParameters record) {
        int index = hooks.require(hook);
        return set(entry, index, record, new int[] {0, record.bytes()});
    }

    @AllowAllocation("values change rarely")
    private synchronized ShadingHookOverrides set(int entry, int index, ShadingHookParameters record, int[] ranges) {
        Objects.requireNonNull(record, "record");
        if (!hooks.parameters(index).isInstance(record)) {
            throw new IllegalArgumentException(hooks.hooks().get(index) + "'s parameters are "
                + hooks.parameters(index).getSimpleName() + ", not " + record.getClass().getSimpleName());
        }
        Fields[][] rows = table.rows();
        check(rows, entry);
        MemorySegment bytes = Arena.ofAuto().allocate(Math.max(1, record.bytes()), 16);
        record.write(bytes, 0);
        Fields old = rows[entry][index];
        return put(entry, index, new Fields(old == null ? null : old.on(), record, bytes, ranges));
    }

    @AllowAllocation("values change rarely")
    private ShadingHookOverrides put(int entry, int index, Fields fields) {
        Fields[][] changed = table.rows().clone();
        changed[entry] = changed[entry].clone();
        changed[entry][index] = fields;
        table = new Table(changed, recordBytes(changed));
        return this;
    }

    /**
     * Switches hook {@code hook} off ({@code false}) or on ({@code true}) at the points of entry {@code entry}'s
     * instances, or back to as the view runs it ({@code null}); its values stay. A material refusing the hook wins.
     */
    @AllowAllocation("switches change rarely")
    public synchronized ShadingHookOverrides turn(int entry, String hook, Boolean on) {
        int index = hooks.require(hook);
        check(table.rows(), entry);
        return put(entry, index, Fields.of(on, table.rows()[entry][index]));
    }

    /** Drops entry {@code entry}'s values for hook {@code hook}: the view's again (its switch stays). */
    @AllowAllocation("values change rarely")
    public synchronized ShadingHookOverrides clear(int entry, String hook) {
        int index = hooks.require(hook);
        Fields[][] rows = table.rows();
        check(rows, entry);
        Fields old = rows[entry][index];
        return old == null || !old.values() ? this : put(entry, index, Fields.of(old.on(), null));
    }

    /** Whether entry {@code entry} sets values of hook {@code hook}. */
    public boolean overrides(int entry, String hook) {
        Fields f = fields(entry, hook);
        return f != null && f.values();
    }

    /** How entry {@code entry} switches hook {@code hook}: off, on, or null (as the view runs it). */
    public Boolean switched(int entry, String hook) {
        Fields f = fields(entry, hook);
        return f == null ? null : f.on();
    }

    private Fields fields(int entry, String hook) {
        Fields[][] rows = table.rows();
        int index = hooks.index(hook);
        return entry > 0 && entry < rows.length && rows[entry] != null && index >= 0 ? rows[entry][index] : null;
    }

    /**
     * Entry {@code entry}'s record for hook {@code hook} as set (the record's defaults in fields it does not set), or
     * null where it does not override the hook: for tools and tests.
     */
    public ShadingHookParameters record(int entry, String hook) {
        Fields f = fields(entry, hook);
        return f != null ? f.record() : null;
    }

    /** How many entry numbers are in use or below one in use (the section's rows). */
    public int entries() {
        return table.entries();
    }

    private static void check(Fields[][] rows, int entry) {
        if (entry <= 0 || entry >= rows.length || rows[entry] == null) {
            throw new IllegalArgumentException("No shading hook override entry " + entry);
        }
    }

    private long recordBytes(Fields[][] rows) {
        long bytes = 0;
        for (Fields[] row : rows) {
            for (int h = 0; row != null && h < row.length; h++) {
                if (row[h] != null && row[h].values()) {
                    bytes += align(hooks.bytes(h));
                }
            }
        }
        return bytes;
    }

    private static long align(long bytes) {
        return (bytes + 15) / 16 * 16;
    }

    // ---------------------------------------------------------------- instances

    /**
     * Gives instance {@code instance} (an id of the scene) entry {@code entry}, 0 for none: from the next frame on, its
     * points are shaded with the entry's values. Allocates nothing, unless it makes the instances' buffer (the first
     * entry given).
     */
    public synchronized void assign(int instance, int entry) {
        if (instance < 0) {
            throw new IndexOutOfBoundsException("Instance " + instance);
        }
        if (entry < 0) {
            throw new IllegalArgumentException("Override entry " + entry);
        }
        if (instances == null) {
            if (entry == 0) {
                return;
            }
            make();
        }
        if (instance >= assigned.length) {
            if (entry == 0) {
                return;
            }
            resize(Math.max(instance + 1, scene.capacity()));
        }
        if (assigned[instance] != entry) {
            assigned[instance] = entry;
            instances.putInt(4L * instance, entry);
        }
    }

    /** Instance {@code instance}'s entry, 0 for none. Allocates nothing. */
    public synchronized int entryOf(int instance) {
        return instance >= 0 && instance < assigned.length ? assigned[instance] : 0;
    }

    /** The scene removed instance {@code instance}: its entry goes with it. Allocates nothing. */
    synchronized void forget(int instance) {
        if (instance < assigned.length && assigned[instance] != 0) {
            assign(instance, 0);
        }
    }

    @AllowAllocation("made once, at need")
    private void make() {
        instances = createBuffer(scene.capacity());
        assigned = new int[scene.capacity()];
        scene.onGrow(follower);
    }

    private FrameBuffer createBuffer(int ids) {
        return FrameBuffer.create(device, 4L * Math.max(1, ids),
            VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT, scene.frameSlots());
    }

    /** Follows the scene's table as it grows. */
    private synchronized void follow() {
        if (instances != null && scene.capacity() > assigned.length) {
            resize(scene.capacity());
        }
    }

    @AllowAllocation("growing with the scene, rarely")
    private void resize(int ids) {
        FrameBuffer grown = createBuffer(ids);
        grown.copyFrom(instances, 0, 0, instances.size());
        outgrown.add(instances);
        instances = grown;
        assigned = Arrays.copyOf(assigned, ids);
    }

    // ---------------------------------------------------------------- a view's block

    /** The bytes a block of the listing needs for the entries as they are: the listing's, and the section. */
    long blockBytes() {
        Table t = table;
        return hooks.blockBytes() + align(4L * t.entries() * rowWords()) + t.recordBytes();
    }

    /** The words of an entry's row: the hooks it switches off, those it switches on, a record offset per hook. */
    private int rowWords() {
        int n = hooks.count();
        return 2 * ((n + 31) / 32) + n;
    }

    /** The bytes a block needs with {@code overrides}, or none (null). */
    static long blockBytes(ShadingHooks hooks, ShadingHookOverrides overrides) {
        return overrides != null ? overrides.blockBytes() : hooks.blockBytes();
    }

    /**
     * Writes the override section of {@code block} (a block's bytes in plain memory, the view's records composed, with
     * room for {@link #blockBytes()}) for the frame recording in {@code ctx}, and its header's fields: the entries'
     * records merged, each then handed to its hook's source ({@code sources}, by hook index, or null), the instances'
     * entries for the frame. {@code overrides} null: no entries, the instances' pointer at a word of the block's own
     * copy ({@code blockAddress}) holding 0. Allocates nothing.
     */
    static void compose(ShadingHooks hooks, ShadingHookOverrides overrides, PassContext ctx, MemorySegment block,
        long blockAddress, ShadingHookSource[] sources) {
        if (overrides == null) {
            block.set(U32, ShadingHookBlockLayout.OVERRIDE_ENTRIES, 0);
            block.set(U32, ShadingHookBlockLayout.OVERRIDE_TABLE, (int) HooksConstants.SHADING_HOOK_HEADER);
            block.set(U64, ShadingHookBlockLayout.INSTANCE_ENTRIES, blockAddress + ShadingHookBlockLayout.INSTANCES);
            block.set(U32, ShadingHookBlockLayout.INSTANCES, 0);
            return;
        }
        overrides.composeSection(ctx, block, blockAddress, sources);
    }

    private void composeSection(PassContext ctx, MemorySegment block, long blockAddress,
        ShadingHookSource[] sources) {
        Table t = table;
        int n = hooks.count();
        int words = (n + 31) / 32;
        int stride = rowWords();
        int entries = t.entries();
        long section = hooks.blockBytes();
        long records = section + align(4L * entries * stride);
        if (block.byteSize() < records + t.recordBytes()) {
            throw new IllegalStateException("A hook block of " + block.byteSize() + " bytes for "
                + (records + t.recordBytes()));
        }
        for (int e = 1; e <= entries; e++) {
            Fields[] row = t.rows()[e];
            long rowAt = section + 4L * (long) (e - 1) * stride;
            for (int w = 0; w < 2 * words; w++) {
                block.set(U32, rowAt + 4L * w, 0);
            }
            for (int h = 0; h < n; h++) {
                Fields o = row != null ? row[h] : null;
                int at = 0;
                if (o != null && o.on() != null) {
                    long word = rowAt + 4L * ((o.on() ? words : 0) + h / 32);
                    block.set(U32, word, block.get(U32, word) | 1 << (h % 32));
                }
                if (o != null && o.values()) {
                    MemorySegment.copy(block, hooks.recordOffset(h), block, records, hooks.bytes(h));
                    int[] ranges = o.ranges();
                    for (int r = 0; r < ranges.length; r += 2) {
                        MemorySegment.copy(o.bytes(), ranges[r], block, records + ranges[r], ranges[r + 1]);
                    }
                    ShadingHookSource source = h < sources.length ? sources[h] : null;
                    if (source != null) {
                        source.override(ctx, e, block, records);
                    }
                    at = (int) records;
                    records += align(hooks.bytes(h));
                }
                block.set(U32, rowAt + 4L * (2 * words + h), at);
            }
        }
        block.set(U32, ShadingHookBlockLayout.OVERRIDE_ENTRIES, entries);
        block.set(U32, ShadingHookBlockLayout.OVERRIDE_TABLE,
            (int) (entries > 0 ? section : HooksConstants.SHADING_HOOK_HEADER));
        synchronized (this) {
            outgrown.release();
            boolean any = instances != null;
            block.set(U64, ShadingHookBlockLayout.INSTANCE_ENTRIES,
                any ? instances.deviceAddress() : blockAddress + ShadingHookBlockLayout.INSTANCES);
            block.set(U32, ShadingHookBlockLayout.INSTANCES, any ? assigned.length : 0);
        }
    }

    @Override
    public synchronized void close() {
        if (!closed) {
            closed = true;
            if (instances != null) {
                scene.stopFollowing(follower);
                instances.close();
            }
            outgrown.close();
        }
    }

    // ---------------------------------------------------------------- the fields values set

    /**
     * The (offset, length) byte ranges of {@code record}'s fields at {@code paths} (generated records' constants,
     * {@link MaterialField#find}), merged where adjacent; a path of no constant (an array) sets the whole record.
     */
    @AllowAllocation("values change rarely")
    private static int[] ranges(Class<?> record, int bytes, List<String> paths) {
        int[] set = new int[2 * paths.size()];
        int n = 0;
        for (String path : paths) {
            MaterialField field = MaterialField.find(record, path);
            if (field == null) {
                return new int[] {0, bytes};
            }
            int length = switch (field.kind()) {
                case F32, I32 -> 4;
                case FLOATS -> 4 * field.count();
                case BUFFER -> 8;
            };
            set[n++] = field.offset();
            set[n++] = length;
        }
        return Arrays.copyOf(set, n);
    }

    /**
     * Values read through, noting the paths they hold a value for: each asked twice with different fallbacks (or once
     * with a fallback of its own identity), a value held where both answers agree.
     */
    @AllowAllocation("values change rarely")
    private static final class Probe implements MaterialValues {
        private final MaterialValues values;
        private final String prefix;
        private final List<String> paths;

        Probe(MaterialValues values, String prefix) {
            this(values, prefix, new ArrayList<>());
        }

        private Probe(MaterialValues values, String prefix, List<String> paths) {
            this.values = values;
            this.prefix = prefix;
            this.paths = paths;
        }

        private void held(String name) {
            paths.add(prefix + name);
        }

        @Override
        public float f32(String name, float fallback) {
            float a = values.f32(name, 0);
            float b = values.f32(name, 1);
            if (Float.floatToRawIntBits(a) == Float.floatToRawIntBits(b)) {
                held(name);
                return a;
            }
            return fallback;
        }

        @Override
        public int i32(String name, int fallback) {
            int a = values.i32(name, 0);
            int b = values.i32(name, 1);
            if (a == b) {
                held(name);
                return a;
            }
            return fallback;
        }

        @Override
        public Vec2 vec2(String name, Vec2 fallback) {
            Vec2 none = new Vec2(0, 0);
            Vec2 v = values.vec2(name, none);
            if (v != none) {
                held(name);
                return v;
            }
            return fallback;
        }

        @Override
        public Vec3 vec3(String name, Vec3 fallback) {
            Vec3 none = new Vec3(0, 0, 0);
            Vec3 v = values.vec3(name, none);
            if (v != none) {
                held(name);
                return v;
            }
            return fallback;
        }

        @Override
        public Vec4 vec4(String name, Vec4 fallback) {
            Vec4 none = new Vec4(0, 0, 0, 0);
            Vec4 v = values.vec4(name, none);
            if (v != none) {
                held(name);
                return v;
            }
            return fallback;
        }

        @Override
        public Color color(String name, Color fallback) {
            Color none = new Color(0, 0, 0, 0);
            Color v = values.color(name, none);
            if (v != none) {
                held(name);
                return v;
            }
            return fallback;
        }

        @Override
        public int texture(String name, boolean srgb, int fallback) {
            int a = values.texture(name, srgb, -2);
            int b = values.texture(name, srgb, -3);
            if (a == b) {
                held(name);
                return a;
            }
            return fallback;
        }

        @Override
        public MaterialValues nested(String name) {
            return new Probe(values.nested(name), prefix + name + ".", paths);
        }

        @Override
        public DeviceBuffer buffer(String name, DeviceBuffer fallback) {
            DeviceBuffer v = values.buffer(name, null);
            if (v != null) {
                held(name);
                return v;
            }
            return fallback;
        }
    }
}
