package com.truesim.engine.render;

import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
import static org.lwjgl.vulkan.VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.math.Maths;
import com.truesim.engine.render.layout.LightsConstants;
import com.truesim.engine.graph.ViewPort;
import com.truesim.engine.vulkan.FrameBuffer;
import com.truesim.engine.vulkan.FrameSlots;
import com.truesim.engine.vulkan.Retiring;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.scene.core.ShadowSpec;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The scene's lights, as shaders read them (engine-render's {@code lights.slang}): point, spot and directional lights
 * at world positions (two floats per axis, as instances keep theirs: a light list hands them to shading relative to its
 * camera, so one set of lights serves every view), and the shadow tables they cast through. {@link ClusteredLights} bins
 * them per cluster of the view for shading.
 *
 * <p>Shadows: a light names a range of consecutive entries of the shadow table, each of a kind evaluated its own way
 * ({@code lights.slang}'s {@code IShadow}), so lights may each use another technique: a
 * {@link ShadowTable#shadowMap shadow map} with its filter, a screen-space {@link ShadowTable#shadowMask mask}, or an
 * inline {@link ShadowTable#shadowRay ray query}. A spot light uses one entry; a directional light one per cascade
 * (nearest first, each covering view depths up to its split); a point light six, the cube faces +x, -x, +y, -y, +z, -z.
 * Each graph view has a table of its own ({@link #shadowTable}), numbered alike: shadow techniques fill the entries of
 * their lights in the table of the view they build in, so each view's cascades fit its own camera.
 *
 * <pre>{@code
 * int sun = lights.add();                                                // the lowest free id; grows when full
 * lights.directional(sun, -0.4f, -0.8f, -0.3f, 3, 3, 3);                // its shadow technique sets entries
 * int lamp = lights.add();
 * lights.point(lamp, x, y, z, 10, 20, 15, 10);                           // range 10, warm
 * lights.remove(lamp);                                                   // off, its id free for the next add
 * }</pre>
 *
 * <p>Or by index, managing the count: {@code lights.point(1, ...)} and {@link #setCount}. Ids are stable: a removed
 * light leaves an unlit hole until its id is taken again, so techniques may keep a light's id.
 *
 * <p>Lights are read when the frame's commands run. Given a frame graph's {@link FrameSlots}, they keep a copy per frame
 * in flight and may change every frame; otherwise change them only once the frames using them have finished.
 * Allocates nothing once created.
 */
@AllowAllocation("created rarely")
public final class Lights implements AutoCloseable {

    static final int LIGHT_BYTES = 80;
    /** Light types, as lights.slang numbers them. */
    private static final int POINT = LightsConstants.POINT;
    private static final int SPOT = LightsConstants.SPOT;
    private static final int DIRECTIONAL = LightsConstants.DIRECTIONAL;

    private FrameBuffer lights; // replaced by a bigger one as lights are added past it
    private final Map<ViewPort, ShadowTable> tables = new HashMap<>(); // by view; null: the frame's
    private final VulkanDevice device;
    private final int usage;
    private final FrameSlots slots;
    private final int[] families;
    private final Retiring retiring; // the tables of views gone, until no frame in flight reads them
    private int capacity;
    private final int shadowCapacity;
    private int count;
    private boolean[] taken; // ids add() handed out and remove() did not take back
    private ShadowSpec[] specs; // how each casts shadows (for the Shadows owner); NONE until set
    private long shadowVersion; // changes with every spec set
    private long[] versions; // per light: changes with every write of it (what it is, where, its spec)
    private byte[] mobility; // per light: its mobility hint (GpuScene's numbers), CPU only
    // Lights the GPU writes each frame (particle lights, effects.md §2.5): a count, three words, then the lights; the
    // keys whose producers write them, built before the lights are binned
    private com.truesim.engine.vulkan.GpuBuffer written;
    private com.truesim.engine.graph.ExternalBuffer writtenImport;
    private int writtenCapacity;
    private final java.util.List<com.truesim.engine.graph.Key<?>> writers = new java.util.ArrayList<>();

    /**
     * Room for {@code capacity} lights and {@code shadowCapacity} shadow maps, one copy of them: for frames the caller
     * waits for before changing lights.
     */
    public Lights(VulkanDevice device, int capacity, int shadowCapacity) {
        this(device, capacity, shadowCapacity, FrameSlots.single());
    }

    /**
     * Room for {@code capacity} lights and {@code shadowCapacity} shadow maps, a copy per frame of {@code slots} (a
     * frame graph's), so lights and shadow entries may change every frame, also while the frame records.
     */
    public Lights(VulkanDevice device, int capacity, int shadowCapacity, FrameSlots slots) {
        if (capacity < 1 || shadowCapacity < 1) {
            throw new IllegalArgumentException("Room for lights and shadows: " + capacity + ", " + shadowCapacity);
        }
        this.capacity = capacity;
        this.shadowCapacity = shadowCapacity;
        this.taken = new boolean[capacity];
        this.specs = new ShadowSpec[capacity];
        this.versions = new long[capacity];
        this.mobility = new byte[capacity];
        java.util.Arrays.fill(specs, ShadowSpec.NONE);
        int usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;
        // Read by the graphics queue and by async compute (light clusters, ray-traced shadows): shared by both.
        int graphics = device.graphics().family();
        int compute = device.compute().family();
        lights = FrameBuffer.create(device, (long) LIGHT_BYTES * capacity, usage, slots, graphics, compute);
        this.device = device;
        this.usage = usage;
        this.slots = slots;
        this.families = new int[] {graphics, compute};
        this.retiring = new Retiring(slots);
        tables.put(null, new ShadowTable(device, shadowCapacity, capacity, usage, slots, families));
    }

    /**
     * {@code view}'s shadow table (null: the frame's), made the first time it is asked for: the entries its shadow
     * techniques write and its light list points its shading at. Every view's has the same {@code shadowCapacity}
     * entries, numbered alike. A view's goes with the view (removed, or its graph closed), freed once no frame in flight
     * reads it; the frame's closes with the lights.
     */
    @AllowAllocation("a view's first, when the graph builds")
    public ShadowTable shadowTable(ViewPort view) {
        ShadowTable table = tables.get(view);
        if (table == null) {
            table = new ShadowTable(device, shadowCapacity, capacity, usage, slots, families); // grows with the lights
            tables.put(view, table);
            if (view != null) {
                view.own(() -> retire(view));
            }
        }
        return table;
    }

    /** Lets {@code view}'s table go: freed once no frame in flight reads it (at once, the lights closed). */
    private void retire(ViewPort view) {
        ShadowTable table = tables.remove(view);
        if (table != null) {
            retiring.add(table);
        }
    }

    /** Frees the tables of views gone that no frame in flight reads any more: from each frame's light list. */
    void releaseRetired() {
        retiring.release();
    }

    /** The shadow tables held: in use, and of views gone that frames in flight may still read. */
    int shadowTableCount() {
        return tables.size() + retiring.count();
    }

    /**
     * A light's range for one without (glTF's KHR_lights_punctual without a range): its light falls with the square of
     * the distance until it is below the light list's cutoff ({@link ClusteredLights#CUTOFF}), where it ends smoothly.
     */
    public static final float NO_RANGE = Float.POSITIVE_INFINITY;

    /**
     * Light {@code index} shines in all directions from world position ({@code x}, {@code y}, {@code z}), up to
     * {@code range} ({@link #NO_RANGE}: as far as it is bright enough):
     * irradiance ({@code r}, {@code g}, {@code b}) at 1 unit, falling with the square of the distance. No shadows.
     */
    public Lights point(int index, double x, double y, double z, float range, float r, float g, float b) {
        return set(index, POINT, x, y, z, range, r, g, b, 0, 0, 0, -1, -1);
    }

    /**
     * Like {@link #point}, but only along ({@code dx}, {@code dy}, {@code dz}): full light within {@code inner}
     * radians of it, fading to none at {@code outer}.
     */
    public Lights spot(int index, double x, double y, double z, float range, float r, float g, float b, float dx,
        float dy, float dz, double inner, double outer) {
        return set(index, SPOT, x, y, z, range, r, g, b, dx, dy, dz, (float) Maths.cos(inner),
            (float) Maths.cos(outer));
    }

    /** Light {@code index} shines along ({@code dx}, {@code dy}, {@code dz}) everywhere, e.g. the sun. */
    public Lights directional(int index, float dx, float dy, float dz, float r, float g, float b) {
        return set(index, DIRECTIONAL, 0, 0, 0, 0, r, g, b, dx, dy, dz, -1, -1);
    }

    private Lights set(int index, int type, double x, double y, double z, float range, float r, float g, float b,
        float dx, float dy, float dz, float cosInner, float cosOuter) {
        long o = light(index);
        versions[index]++;
        float length = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        float scale = length > 0 ? 1 / length : 0;
        putPosition(lights, o, 64, x, y, z);
        lights.putFloat(o + 12, range);
        lights.putFloat(o + 16, r);
        lights.putFloat(o + 20, g);
        lights.putFloat(o + 24, b);
        lights.putInt(o + 28, type);
        lights.putFloat(o + 32, dx * scale);
        lights.putFloat(o + 36, dy * scale);
        lights.putFloat(o + 40, dz * scale);
        lights.putFloat(o + 44, cosOuter);
        lights.putFloat(o + 48, cosInner);
        lights.putInt(o + 52, -1); // unused: each view's shadow table says which entries a light's shadows are
        lights.putInt(o + 56, 0);
        return this;
    }

    /**
     * Writes world position ({@code x}, {@code y}, {@code z}) at {@code o} of {@code buffer}: the high parts there, the
     * rest {@code lowOffset} bytes on, as shaders split them.
     */
    static void putPosition(FrameBuffer buffer, long o, long lowOffset, double x, double y, double z) {
        float hx = (float) x;
        float hy = (float) y;
        float hz = (float) z;
        buffer.putFloat(o, hx);
        buffer.putFloat(o + 4, hy);
        buffer.putFloat(o + 8, hz);
        buffer.putFloat(o + lowOffset, (float) (x - hx));
        buffer.putFloat(o + lowOffset + 4, (float) (y - hy));
        buffer.putFloat(o + lowOffset + 8, (float) (z - hz));
    }


    /** Light {@code index}'s offset. */
    private long light(int index) {
        Objects.checkIndex(index, capacity);
        return (long) LIGHT_BYTES * index;
    }

    /** Light {@code index}'s world position along {@code axis} (0 x, 1 y, 2 z), as set. */
    public double position(int index, int axis) {
        long o = light(index) + 4L * Objects.checkIndex(axis, 3);
        return (double) lights.getFloat(o) + lights.getFloat(o + 64);
    }

    /** Light {@code index}'s kind: 0 point, 1 spot, 2 directional ({@code lights.slang}'s numbers). */
    public int type(int index) {
        return lights.getInt(light(index) + 28);
    }

    /** Light {@code index}'s range ({@link #NO_RANGE}: none; directional lights: 0), as set. */
    public float range(int index) {
        return lights.getFloat(light(index) + 12);
    }

    /** The cosine of spot light {@code index}'s outer cone, as set (others: -1). */
    public float cosOuter(int index) {
        return lights.getFloat(light(index) + 44);
    }

    /** Light {@code index}'s brightest channel: its irradiance at 1 unit (directional: anywhere); 0: it is off. */
    public float brightness(int index) {
        long o = light(index);
        return Math.max(lights.getFloat(o + 16), Math.max(lights.getFloat(o + 20), lights.getFloat(o + 24)));
    }

    /** Light {@code index}'s direction (where it shines; unit length) into {@code out}. Allocates nothing. */
    public void direction(int index, double[] out) {
        long o = light(index);
        out[0] = lights.getFloat(o + 32);
        out[1] = lights.getFloat(o + 36);
        out[2] = lights.getFloat(o + 40);
    }

    /**
     * A light's id, the lowest free one, its light off until set ({@link #point}, {@link #spot}, {@link #directional}):
     * the count follows the highest id taken. Room grows (twice) when every id is taken.
     */
    @AllowAllocation("growing, rarely")
    public int add() {
        int id = 0;
        while (id < capacity && taken[id]) {
            id++;
        }
        if (id == capacity) {
            grow(2 * capacity);
        }
        taken[id] = true;
        off(id);
        count = Math.max(count, id + 1);
        return id;
    }

    /** Turns light {@code id} ({@link #add}) off and frees its id for the next add; the count drops past holes. */
    public void remove(int id) {
        Objects.checkIndex(id, capacity);
        if (!taken[id]) {
            throw new IllegalArgumentException("No light " + id + " was added");
        }
        taken[id] = false;
        mobility[id] = GpuScene.UNKNOWN;
        off(id);
        shadow(id, ShadowSpec.NONE);
        while (count > 0 && !taken[count - 1]) {
            count--;
        }
    }

    /** Light {@code id} lights nothing: a point light of no range and no color, casting no shadows. */
    private void off(int id) {
        set(id, POINT, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, -1, -1);
    }

    /** Room for {@code more} lights: the lights and each view's shadow table copied into bigger ones. */
    private void grow(int more) {
        FrameBuffer old = lights;
        lights = FrameBuffer.create(device, (long) LIGHT_BYTES * more, usage, slots, families);
        lights.copyFrom(old);
        retiring.add(old); // frames in flight read it
        for (ShadowTable table : tables.values()) {
            table.growLights(more, retiring);
        }
        taken = java.util.Arrays.copyOf(taken, more);
        specs = java.util.Arrays.copyOf(specs, more);
        versions = java.util.Arrays.copyOf(versions, more);
        mobility = java.util.Arrays.copyOf(mobility, more);
        java.util.Arrays.fill(specs, capacity, more, ShadowSpec.NONE);
        capacity = more;
    }

    /**
     * How light {@code index} casts shadows ({@link ShadowSpec}, scene's per-light shadow data): read each frame by the
     * {@link com.truesim.engine.render.shadows.Shadows} owner, so a change takes effect from the next frame without a
     * rebuild. Its softness becomes the light's size ({@code lights.slang}'s {@code radius}), which soft shadows and
     * the reference path tracer sample. {@link ShadowSpec#NONE} until set, and again once removed. Allocates nothing.
     */
    public Lights shadow(int index, ShadowSpec spec) {
        long o = light(index);
        Objects.requireNonNull(spec, "spec");
        if (specs[index] != spec) {
            specs[index] = spec;
            shadowVersion++;
            versions[index]++;
        }
        lights.putFloat(o + 60, (float) spec.softness());
        return this;
    }

    /** How light {@code index} casts shadows, as last set. */
    public ShadowSpec shadow(int index) {
        light(index);
        return specs[index];
    }

    /**
     * A number that changes whenever light {@code index} is written ({@link #point}, {@link #spot},
     * {@link #directional}, {@link #shadow} with another spec, {@link #remove}), whether or not its values changed:
     * while it stands, nothing about the light changed. Allocates nothing.
     */
    public long version(int index) {
        light(index);
        return versions[index];
    }

    /**
     * Says whether light {@code index} is expected to move ({@link com.truesim.scene.core.Mobility}, its scene node's;
     * null: not known): a hint for what caches its shadows ({@link com.truesim.engine.render.shadows.Shadows} skips a
     * static light's own change tests while its {@link #version} stands), never a promise: a static light written
     * anew is followed all the same. Lasts until set again or the light is removed. Allocates nothing.
     */
    public Lights mobility(int index, com.truesim.scene.core.Mobility hint) {
        light(index);
        mobility[index] = hint == null ? GpuScene.UNKNOWN
            : hint == com.truesim.scene.core.Mobility.STATIC ? GpuScene.STILL : GpuScene.MOVING;
        return this;
    }

    /** Light {@code index}'s mobility hint, as {@link GpuScene#mobilityHint} numbers it. */
    public byte mobilityHint(int index) {
        light(index);
        return mobility[index];
    }

    /** A number that changes whenever a light's {@link #shadow shadow spec} does. */
    public long shadowVersion() {
        return shadowVersion;
    }

    /** How many lights there is room for now: it grows as lights are added ({@link #add}). */
    public int capacity() {
        return capacity;
    }

    /** How many lights, from 0, shine. */
    public void setCount(int count) {
        if (count < 0 || count > capacity) {
            throw new IllegalArgumentException("0 to " + capacity + " lights: " + count);
        }
        this.count = count;
    }

    public int count() {
        return count;
    }

    /**
     * Makes room for {@code capacity} lights the GPU writes each frame (particle lights; 0: none): a device-local buffer
     * of a count, three words and the lights ({@code lights.slang}'s {@code Light}, at world positions), which the
     * binning ({@link ClusteredLights}) takes after these lights, counting them on the GPU; they cast no shadows. The
     * writer resets the count each frame. Set up before the graph builds.
     */
    @AllowAllocation("set up rarely")
    public Lights gpuLights(int capacity) {
        if (capacity < 0) {
            throw new IllegalArgumentException("Not a light count: " + capacity);
        }
        if (written != null) {
            retiring.add(written);
            written = null;
            writtenImport = null;
        }
        writtenCapacity = capacity;
        if (capacity > 0) {
            written = com.truesim.engine.vulkan.GpuBuffer.deviceLocal(device, 16L + (long) LIGHT_BYTES * capacity,
                usage | org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT
                    | org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT, families);
            writtenImport = new com.truesim.engine.graph.ExternalBuffer("gpu lights", written);
        }
        return this;
    }

    /** The most lights the GPU writes, 0 for none. */
    public int gpuCapacity() {
        return writtenCapacity;
    }

    /** The buffer of lights the GPU writes, as a frame graph imports it, or null. */
    public com.truesim.engine.graph.ExternalBuffer gpuLightsBuffer() {
        return writtenImport;
    }

    /**
     * Notes that the technique providing {@code key} writes the lights the GPU writes: the binning builds it first, so
     * its writes come before the binning reads them in the frame.
     */
    @AllowAllocation("set up once")
    public void writtenBy(com.truesim.engine.graph.Key<?> key) {
        if (!writers.contains(key)) {
            writers.add(Objects.requireNonNull(key, "key"));
        }
    }

    /** Stops noting {@code key}. */
    public void notWrittenBy(com.truesim.engine.graph.Key<?> key) {
        writers.remove(key);
    }

    /** The keys whose producers write the lights the GPU writes. */
    java.util.List<com.truesim.engine.graph.Key<?>> writers() {
        return writers;
    }

    /** The lights' device address, for passes reading them (e.g. a light's shadow technique). */
    public long lightsAddress() {
        return lights.deviceAddress();
    }

    @Override
    public void close() {
        lights.close();
        if (written != null) {
            written.close();
        }
        for (ShadowTable table : tables.values()) {
            table.close();
        }
        tables.clear();
        retiring.close(); // frames are done: the retired tables go now
    }
}
