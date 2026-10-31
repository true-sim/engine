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
    private static final int SHADOW_RAY = LightsConstants.SHADOW_RAY;
    private static final int SHADOW_NONE = LightsConstants.SHADOW_NONE;
    private static final int PCF = LightsConstants.PCF;

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
     * Entry {@code entry}, a shadow map filtered by 3 x 3 PCF: the depth map in sampled slot {@code map},
     * {@code size} texels across, drawn from {@code camera} (its view-projection and world position: the map serves
     * every view, and stays valid while the light camera stays), compared with {@code bias}; a cascade covers view
     * depths up to {@code split} (others: any). Allocates nothing.
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
        long o = shadow(entry, SHADOW_MAP, PCF, map);
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
     * Entry {@code entry}, a screen-space mask: channel {@code channel} of the image in sampled slot {@code mask}, the
     * size of the shaded image, 1 lit and 0 in shadow per pixel. Allocates nothing.
     */
    public ShadowTable shadowMask(int entry, int mask, int channel) {
        long o = shadow(entry, SHADOW_MASK, 0, mask);
        shadows.putFloat(o + 88, channel);
        return this;
    }

    /**
     * Entry {@code entry}, an inline ray query into the top level whose {@code RayScene} lies at address {@code scene}
     * ({@link RayTracing#sceneAddress}: its slot, and what alpha-tests masked triangles), built
     * relative to world position ({@code x}, {@code y}, {@code z}), from {@code offset} per unit of distance off the
     * surface (the drawn surface may be a detail level within a pixel of the traced one). Shading that cannot trace
     * counts it as lit. Allocates nothing.
     */
    public ShadowTable shadowRay(int entry, long scene, float offset, double x, double y, double z) {
        long o = shadow(entry, SHADOW_RAY, 0, (int) scene);
        shadows.putInt(o + 76, (int) (scene >>> 32)); // the address's high half, where maps keep their size
        shadows.putFloat(o + 88, offset);
        Lights.putPosition(shadows, o + 96, 16, x, y, z);
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
