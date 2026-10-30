package com.truesim.engine.render;

import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
import static org.lwjgl.vulkan.VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.math.Maths;
import com.truesim.engine.render.layout.LightsConstants;
import com.truesim.engine.vulkan.FrameBuffer;
import com.truesim.engine.vulkan.FrameSlots;
import com.truesim.engine.vulkan.VulkanDevice;
import java.util.Objects;

/**
 * The scene's lights, as shaders read them (engine-render's {@code lights.slang}): point, spot and directional lights
 * at world positions (two floats per axis, as instances keep theirs: a light list hands them to shading relative to its
 * camera, so one set of lights serves every view), and the shadow maps they cast through. {@link ClusteredLights} bins them per cluster
 * of the view for shading.
 *
 * <p>Shadows: a light names a range of consecutive entries of the shadow table, each of a kind evaluated its own way
 * ({@code lights.slang}'s {@code IShadow}), so lights may each use another technique: a {@link #shadowMap shadow map}
 * with its filter, a screen-space {@link #shadowMask mask}, or an inline {@link #shadowRay ray query}. A spot light
 * uses one entry; a directional light one per cascade (nearest first, each covering view depths up to its split); a
 * point light six, the cube faces +x, -x, +y, -y, +z, -z. Shadow techniques fill the entries of their lights.
 *
 * <pre>{@code
 * lights.directional(0, -0.4f, -0.8f, -0.3f, 3, 3, 3);                  // the sun; its shadow technique sets entries
 * lights.point(1, x, y, z, 10, 20, 15, 10);                              // range 10, warm
 * lights.setCount(2);
 * }</pre>
 *
 * <p>Lights are read when the frame's commands run. Given a frame graph's {@link FrameSlots}, they keep a copy per frame
 * in flight and may change every frame; otherwise change them only once the frames using them have finished.
 * Allocates nothing once created.
 */
@AllowAllocation("created rarely")
public final class Lights implements AutoCloseable {

    static final int LIGHT_BYTES = 80;
    static final int SHADOW_BYTES = 128;
    /** Shadow kinds and map filters, as lights.slang numbers them. */
    private static final int SHADOW_MAP = LightsConstants.SHADOW_MAP;
    private static final int SHADOW_MASK = LightsConstants.SHADOW_MASK;
    private static final int SHADOW_RAY = LightsConstants.SHADOW_RAY;
    private static final int PCF = LightsConstants.PCF;
    private static final int POINT = LightsConstants.POINT;
    private static final int SPOT = LightsConstants.SPOT;
    private static final int DIRECTIONAL = LightsConstants.DIRECTIONAL;

    private final FrameBuffer lights;
    private final FrameBuffer shadows;
    private final int capacity;
    private final int shadowCapacity;
    private int count;

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
        int usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;
        // Read by the graphics queue and by async compute (light clusters, ray-traced shadows): shared by both.
        int graphics = device.graphics().family();
        int compute = device.compute().family();
        lights = FrameBuffer.create(device, (long) LIGHT_BYTES * capacity, usage, slots, graphics, compute);
        shadows = FrameBuffer.create(device, (long) SHADOW_BYTES * shadowCapacity, usage, slots, graphics, compute);
    }

    /**
     * Light {@code index} shines in all directions from world position ({@code x}, {@code y}, {@code z}), up to
     * {@code range}:
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
        lights.putInt(o + 52, -1); // no shadows until shadows() says so
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

    /** Light {@code index} casts shadows through shadow table entries {@code first} to {@code first + count - 1}. */
    public Lights shadows(int index, int first, int count) {
        if (first < 0 || count < 1 || first + count > shadowCapacity) {
            throw new IllegalArgumentException("Shadow entries " + first + " to " + (first + count - 1) + " of "
                + shadowCapacity);
        }
        long o = light(index);
        lights.putInt(o + 52, first);
        lights.putInt(o + 56, count);
        return this;
    }

    /**
     * Shadow table entry {@code entry}, a shadow map filtered by 3 x 3 PCF: the depth map in sampled slot {@code map},
     * {@code size} texels across, drawn from {@code camera} (its view-projection and world position: the map serves
     * every view, and stays valid while the light camera stays), compared with {@code bias}; a cascade covers view
     * depths up to {@code split} (others: any). Allocates nothing.
     */
    public Lights shadowMap(int entry, Camera camera, int map, int size, float bias, float split) {
        return shadowMap(entry, camera, map, size, bias, split, 0);
    }

    /**
     * Like {@link #shadowMap(int, Camera, int, int, float, float)}, looked up from the surface moved
     * {@code normalOffset} units along its normal (about a texel's size in the world): against acne where the light
     * grazes, beyond what the depth bias does. Allocates nothing.
     */
    public Lights shadowMap(int entry, Camera camera, int map, int size, float bias, float split, float normalOffset) {
        long o = shadow(entry, SHADOW_MAP, PCF, map);
        shadows.putFloat(o + 88, normalOffset);
        float[] toShadow = camera.viewProjection();
        for (int i = 0; i < 16; i++) {
            shadows.putFloat(o + 4L * i, toShadow[i]);
        }
        shadows.putInt(o + 76, size);
        shadows.putFloat(o + 80, bias);
        shadows.putFloat(o + 84, split);
        putPosition(shadows, o + 96, 16, camera.position(0), camera.position(1), camera.position(2));
        return this;
    }

    /**
     * Shadow table entry {@code entry}, a screen-space mask: channel {@code channel} of the image in sampled slot
     * {@code mask}, the size of the shaded image, 1 lit and 0 in shadow per pixel. Allocates nothing.
     */
    public Lights shadowMask(int entry, int mask, int channel) {
        long o = shadow(entry, SHADOW_MASK, 0, mask);
        shadows.putFloat(o + 88, channel);
        return this;
    }

    /**
     * Shadow table entry {@code entry}, an inline ray query into the top level in acceleration structure slot
     * {@code scene}, built relative to world position ({@code x}, {@code y}, {@code z}), from {@code offset} per unit of
     * distance off the surface (the drawn surface may be a detail level within a pixel of the traced one). Shading
     * that cannot trace counts it as lit. Allocates nothing.
     */
    public Lights shadowRay(int entry, int scene, float offset, double x, double y, double z) {
        long o = shadow(entry, SHADOW_RAY, 0, scene);
        shadows.putFloat(o + 88, offset);
        putPosition(shadows, o + 96, 16, x, y, z);
        return this;
    }

    /** Shadow table entry {@code entry}'s offset, its kind, filter and image set, the rest reset. */
    private long shadow(int entry, int kind, int filter, int map) {
        Objects.checkIndex(entry, shadowCapacity);
        long o = (long) SHADOW_BYTES * entry;
        shadows.putInt(o + 64, kind);
        shadows.putInt(o + 68, filter);
        shadows.putInt(o + 72, map);
        shadows.putFloat(o + 84, Float.MAX_VALUE); // covers every depth, unless a cascade's split says otherwise
        shadows.putFloat(o + 88, 0);
        shadows.putFloat(o + 92, 0);
        return o;
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

    /** Light {@code index}'s direction (where it shines; unit length) into {@code out}. Allocates nothing. */
    public void direction(int index, double[] out) {
        long o = light(index);
        out[0] = lights.getFloat(o + 32);
        out[1] = lights.getFloat(o + 36);
        out[2] = lights.getFloat(o + 40);
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

    /** The lights' device address, for passes reading them (e.g. a light's shadow technique). */
    public long lightsAddress() {
        return lights.deviceAddress();
    }

    long shadowsAddress() {
        return shadows.deviceAddress();
    }

    @Override
    public void close() {
        lights.close();
        shadows.close();
    }
}
