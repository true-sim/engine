package com.truesim.engine.render;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.render.layout.LightsConstants;
import com.truesim.engine.vulkan.FrameBuffer;
import com.truesim.engine.vulkan.FrameSlots;
import com.truesim.engine.vulkan.VulkanDevice;
import java.util.Objects;

/**
 * One view's shadow table ({@link Lights#shadowTable}): the entries a light's shadows go through, each a shadow map
 * with its filter, a screen-space mask or an inline ray query, and per light which of them it casts through
 * ({@link #light}). Every view's table has the same entry numbers and contents of its own, so each view's cascades,
 * fitted to its camera, or masks, of its pixels, never meet another's, and two views may shadow one light by different
 * techniques. An entry no technique of the view wrote reads as lit, as does a light no technique of the view named.
 *
 * <p>Written when frames record (from the shadow techniques' setups); a copy per frame in flight, as the lights.
 * Allocates nothing once made.
 */
@AllowAllocation("made rarely")
public final class ShadowTable implements AutoCloseable {

    static final int SHADOW_BYTES = 128;
    private static final int SHADOW_MAP = LightsConstants.SHADOW_MAP;
    private static final int SHADOW_MASK = LightsConstants.SHADOW_MASK;
    private static final int SHADOW_NONE = LightsConstants.SHADOW_NONE;
    /** Shadow map filters (lights.slang): percentage-closer filtering over a tent of fixed width. */
    public static final int PCF = LightsConstants.PCF;
    /** Percentage-closer soft shadows: the tent as wide as the light's penumbra. */
    public static final int PCSS = LightsConstants.PCSS;
    private static final int DEFAULT_FILTER = filterWord(PCF, 1.5f, 1.5f);

    private FrameBuffer shadows; // replaced by a bigger one as the lights grow
    private final int capacity;
    private int lights;

    /**
     * {@code capacity} entries, then each of {@code lights} lights' range of them, 8 bytes (first, count; first -1:
     * none), where lights.slang's light list finds it past the entries.
     */
    private final VulkanDevice device;
    private final int usage;
    private final FrameSlots slots;
    private final int[] families;

    ShadowTable(VulkanDevice device, int capacity, int lights, int usage, FrameSlots slots, int... families) {
        this.device = device;
        this.usage = usage;
        this.slots = slots;
        this.families = families;
        this.capacity = capacity;
        this.lights = lights;
        shadows = FrameBuffer.create(device, (long) SHADOW_BYTES * capacity + 8L * lights, usage, slots, families);
        for (int entry = 0; entry < capacity; entry++) {
            shadows.putInt((long) SHADOW_BYTES * entry + 64, SHADOW_NONE); // lit until a technique sets it
        }
        for (int light = 0; light < lights; light++) {
            shadows.putInt(range(light), -1); // no shadows until a technique names them
        }
    }

    /**
     * In this view, light {@code light} casts shadows through entries {@code first} to {@code first + count - 1}: a
     * shadow technique's setup says so each frame, beside writing the entries. Allocates nothing.
     */
    public ShadowTable light(int light, int first, int count) {
        Objects.checkIndex(light, lights);
        if (first < 0 || count < 1 || first + count > capacity) {
            throw new IllegalArgumentException("Shadow entries " + first + " to " + (first + count - 1) + " of "
                + capacity);
        }
        shadows.putInt(range(light), first);
        shadows.putInt(range(light) + 4, count);
        return this;
    }

    /** In this view, light {@code light} casts no shadows (until named again). Allocates nothing. */
    public ShadowTable noShadows(int light) {
        Objects.checkIndex(light, lights);
        shadows.putInt(range(light), -1);
        shadows.putInt(range(light) + 4, 0);
        return this;
    }

    /**
     * Room for {@code lights} lights' ranges, as the lights grew: the table copied into a bigger one (the new lights
     * casting no shadows), the old one retired through {@code retiring}.
     */
    void growLights(int lights, com.truesim.engine.vulkan.Retiring retiring) {
        FrameBuffer old = shadows;
        int had = this.lights;
        shadows = FrameBuffer.create(device, (long) SHADOW_BYTES * capacity + 8L * lights, usage, slots, families);
        shadows.copyFrom(old);
        retiring.add(old);
        this.lights = lights;
        for (int light = had; light < lights; light++) {
            shadows.putInt(range(light), -1);
        }
    }

    /** Where light {@code light}'s range lies: past the entries. */
    private long range(int light) {
        return (long) SHADOW_BYTES * capacity + 8L * light;
    }

    /** How many entries it has. */
    public int capacity() {
        return capacity;
    }

    /**
     * Entry {@code entry}, a shadow map filtered by PCF over 1.5 texels ({@link #filter}): the depth map in sampled
     * slot {@code map}, {@code size} texels across, drawn from {@code camera} (its view-projection and world
     * position: the map serves every view, and stays valid while the light camera stays), compared with {@code bias};
     * a cascade covers view depths up to {@code split} (others: any). Allocates nothing.
     */
    public ShadowTable shadowMap(int entry, Camera camera, int map, int size, float bias, float split) {
        return shadowMap(entry, camera, map, size, bias, split, 0);
    }

    /**
     * Like {@link #shadowMap(int, Camera, int, int, float, float)}, looked up from the surface moved
     * {@code normalOffset} units along its normal (about a texel's size in the world): against acne where the light
     * grazes, beyond what the depth bias does. Allocates nothing.
     */
    public ShadowTable shadowMap(int entry, Camera camera, int map, int size, float bias, float split,
        float normalOffset) {
        long o = shadow(entry, SHADOW_MAP, DEFAULT_FILTER, map);
        shadows.putFloat(o + 88, normalOffset);
        float[] toShadow = camera.viewProjection();
        for (int i = 0; i < 16; i++) {
            shadows.putFloat(o + 4L * i, toShadow[i]);
        }
        shadows.putInt(o + 76, size);
        shadows.putFloat(o + 80, bias);
        shadows.putFloat(o + 84, split);
        Lights.putPosition(shadows, o + 96, 16, camera.position(0), camera.position(1), camera.position(2));
        return this;
    }

    /**
     * Entry {@code entry}, a tile of a shadow atlas filtered as {@link #shadowMap}: {@code size} texels per side at
     * ({@code x}, {@code y}) of the depth atlas in sampled slot {@code atlas}, {@code atlasSize} texels per side, drawn
     * from {@code camera} (its view-projection and world position). An orthographic camera's tile is compared with
     * {@code bias} in its depth units and looked up {@code normalOffset} units along the surface's normal, as
     * {@link #shadowMap(int, Camera, int, int, float, float, float)}; a perspective one's (reverse depth from
     * {@code near}) with {@code bias} and {@code normalOffset} per unit of distance from the camera, as its texels grow
     * with it. A cascade covers view depths up to {@code split}. Allocates nothing.
     */
    public ShadowTable atlasMap(int entry, Camera camera, int atlas, int atlasSize, int x, int y, int size, float bias,
        float split, float normalOffset, float near) {
        shadowMap(entry, camera, atlas, size, bias, split, normalOffset);
        long o = (long) SHADOW_BYTES * entry;
        shadows.putFloat(o + 92, near);
        shadows.putInt(o + 108, x | y << 16);
        shadows.putInt(o + 124, atlasSize);
        return this;
    }

    /**
     * How map entry {@code entry} is filtered: {@code kind} ({@link #PCF} or {@link #PCSS}) over a tent of
     * {@code radius} texels' half-width (PCSS: its narrowest), PCSS's penumbrae at most {@code widest} texels wide
     * (half-width). Set after the entry's map; a map's filter is PCF over 1.5 texels until then. Allocates nothing.
     */
    public ShadowTable filter(int entry, int kind, float radius, float widest) {
        Objects.checkIndex(entry, capacity);
        if (kind != PCF && kind != PCSS) {
            throw new IllegalArgumentException("Not a shadow map filter: " + kind);
        }
        shadows.putInt((long) SHADOW_BYTES * entry + 68, filterWord(kind, radius, widest));
        return this;
    }

    /** A filter word: the kind, the kernel's half-width and PCSS's widest, in sixteenths of a texel (lights.slang). */
    static int filterWord(int kind, float radius, float widest) {
        int least = Math.clamp(Math.round(radius * 16), 1, 0xfff);
        int most = Math.clamp(Math.round(widest * 16), least, 0xfff);
        return kind | least << 8 | most << 20;
    }

    /**
     * Entry {@code entry}, a screen-space mask: channel {@code channel} of the image in sampled slot {@code mask}, the
     * size of the shaded image, 1 lit and 0 in shadow per pixel. Allocates nothing.
     */
    public ShadowTable shadowMask(int entry, int mask, int channel) {
        long o = shadow(entry, SHADOW_MASK, 0, mask);
        shadows.putFloat(o + 88, channel);
        return this;
    }

    /** Entry {@code entry} read as lit again, as no technique had set it. Allocates nothing. */
    public ShadowTable clear(int entry) {
        shadow(entry, SHADOW_NONE, 0, 0);
        return this;
    }

    /** Entry {@code entry}'s offset, its kind, filter and image set, the rest reset. */
    private long shadow(int entry, int kind, int filter, int map) {
        Objects.checkIndex(entry, capacity);
        long o = (long) SHADOW_BYTES * entry;
        shadows.putInt(o + 64, kind);
        shadows.putInt(o + 68, filter);
        shadows.putInt(o + 72, map);
        shadows.putFloat(o + 84, Float.MAX_VALUE); // covers every depth, unless a cascade's split says otherwise
        shadows.putFloat(o + 88, 0);
        shadows.putFloat(o + 92, 0);
        shadows.putInt(o + 108, 0); // not in an atlas
        shadows.putInt(o + 124, 0);
        return o;
    }

    /** The table's device address, of the frame recording: what a light list points its shading at. */
    public long address() {
        return shadows.deviceAddress();
    }

    @Override
    public void close() {
        shadows.close();
    }
}
