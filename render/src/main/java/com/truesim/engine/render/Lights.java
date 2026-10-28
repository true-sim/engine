package com.truesim.engine.render;

import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
import static org.lwjgl.vulkan.VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.math.Maths;
import com.truesim.engine.vulkan.GpuBuffer;
import com.truesim.engine.vulkan.VulkanDevice;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.util.Objects;

/**
 * The scene's lights, as shaders read them (engine-render's {@code lights.slang}): point, spot and directional lights,
 * camera-relative like instances, and the shadow maps they cast through. {@link ClusteredLights} bins them per cluster
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
 * <p>Lights are read when the frame's commands run: change them only once the frames using them have finished.
 * Allocates nothing once created.
 */
@AllowAllocation("created rarely")
public final class Lights implements AutoCloseable {

    static final int LIGHT_BYTES = 64;
    static final int SHADOW_BYTES = 96;
    /** Shadow kinds and map filters, as lights.slang numbers them. */
    private static final int SHADOW_MAP = 0;
    private static final int SHADOW_MASK = 1;
    private static final int SHADOW_RAY = 2;
    private static final int PCF = 0;
    private static final int POINT = 0;
    private static final int SPOT = 1;
    private static final int DIRECTIONAL = 2;
    private static final ValueLayout.OfInt U32 = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfFloat F32 = ValueLayout.JAVA_FLOAT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    private final GpuBuffer lights;
    private final GpuBuffer shadows;
    private final int capacity;
    private final int shadowCapacity;
    private int count;

    /** Room for {@code capacity} lights and {@code shadowCapacity} shadow maps. */
    public Lights(VulkanDevice device, int capacity, int shadowCapacity) {
        if (capacity < 1 || shadowCapacity < 1) {
            throw new IllegalArgumentException("Room for lights and shadows: " + capacity + ", " + shadowCapacity);
        }
        this.capacity = capacity;
        this.shadowCapacity = shadowCapacity;
        int usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;
        lights = GpuBuffer.hostVisible(device, (long) LIGHT_BYTES * capacity, usage);
        shadows = GpuBuffer.hostVisible(device, (long) SHADOW_BYTES * shadowCapacity, usage);
    }

    /**
     * Light {@code index} shines in all directions from ({@code x}, {@code y}, {@code z}), up to {@code range}:
     * irradiance ({@code r}, {@code g}, {@code b}) at 1 unit, falling with the square of the distance. No shadows.
     */
    public Lights point(int index, float x, float y, float z, float range, float r, float g, float b) {
        return set(index, POINT, x, y, z, range, r, g, b, 0, 0, 0, -1, -1);
    }

    /**
     * Like {@link #point}, but only along ({@code dx}, {@code dy}, {@code dz}): full light within {@code inner}
     * radians of it, fading to none at {@code outer}.
     */
    public Lights spot(int index, float x, float y, float z, float range, float r, float g, float b, float dx, float dy,
        float dz, double inner, double outer) {
        return set(index, SPOT, x, y, z, range, r, g, b, dx, dy, dz, (float) Maths.cos(inner),
            (float) Maths.cos(outer));
    }

    /** Light {@code index} shines along ({@code dx}, {@code dy}, {@code dz}) everywhere, e.g. the sun. */
    public Lights directional(int index, float dx, float dy, float dz, float r, float g, float b) {
        return set(index, DIRECTIONAL, 0, 0, 0, 0, r, g, b, dx, dy, dz, -1, -1);
    }

    private Lights set(int index, int type, float x, float y, float z, float range, float r, float g, float b,
        float dx, float dy, float dz, float cosInner, float cosOuter) {
        MemorySegment m = light(index);
        float length = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        float scale = length > 0 ? 1 / length : 0;
        m.set(F32, 0, x);
        m.set(F32, 4, y);
        m.set(F32, 8, z);
        m.set(F32, 12, range);
        m.set(F32, 16, r);
        m.set(F32, 20, g);
        m.set(F32, 24, b);
        m.set(U32, 28, type);
        m.set(F32, 32, dx * scale);
        m.set(F32, 36, dy * scale);
        m.set(F32, 40, dz * scale);
        m.set(F32, 44, cosOuter);
        m.set(F32, 48, cosInner);
        m.set(U32, 52, -1); // no shadows until shadows() says so
        m.set(U32, 56, 0);
        return this;
    }

    /** Light {@code index} casts shadows through shadow table entries {@code first} to {@code first + count - 1}. */
    public Lights shadows(int index, int first, int count) {
        if (first < 0 || count < 1 || first + count > shadowCapacity) {
            throw new IllegalArgumentException("Shadow entries " + first + " to " + (first + count - 1) + " of "
                + shadowCapacity);
        }
        MemorySegment m = light(index);
        m.set(U32, 52, first);
        m.set(U32, 56, count);
        return this;
    }

    /**
     * Shadow table entry {@code entry}, a shadow map filtered by 3 x 3 PCF: the depth map in sampled slot {@code map},
     * {@code size} texels across, drawn with {@code toShadow} (camera-relative positions to its clip space,
     * column-major, reverse depth, e.g. the light camera's {@link Camera#viewProjection()}), compared with
     * {@code bias}; a cascade covers view depths up to {@code split} (others: any). Allocates nothing.
     */
    public Lights shadowMap(int entry, float[] toShadow, int map, int size, float bias, float split) {
        return shadowMap(entry, toShadow, map, size, bias, split, 0);
    }

    /**
     * Like {@link #shadowMap(int, float[], int, int, float, float)}, looked up from the surface moved {@code normalOffset}
     * units along its normal (about a texel's size in the world): against acne where the light grazes, beyond what
     * the depth bias does. Allocates nothing.
     */
    public Lights shadowMap(int entry, float[] toShadow, int map, int size, float bias, float split,
        float normalOffset) {
        MemorySegment m = shadow(entry, SHADOW_MAP, PCF, map);
        m.set(F32, 88, normalOffset);
        for (int i = 0; i < 16; i++) {
            m.set(F32, 4L * i, toShadow[i]);
        }
        m.set(U32, 76, size);
        m.set(F32, 80, bias);
        m.set(F32, 84, split);
        return this;
    }

    /**
     * Shadow table entry {@code entry}, a screen-space mask: channel {@code channel} of the image in sampled slot
     * {@code mask}, the size of the shaded image, 1 lit and 0 in shadow per pixel. Allocates nothing.
     */
    public Lights shadowMask(int entry, int mask, int channel) {
        MemorySegment m = shadow(entry, SHADOW_MASK, 0, mask);
        m.set(F32, 88, channel);
        return this;
    }

    /**
     * Shadow table entry {@code entry}, an inline ray query into the top level in acceleration structure slot
     * {@code scene}, from {@code offset} per unit of distance off the surface (the drawn surface may be a detail level
     * within a pixel of the traced one). Shading that cannot trace counts it as lit. Allocates nothing.
     */
    public Lights shadowRay(int entry, int scene, float offset) {
        MemorySegment m = shadow(entry, SHADOW_RAY, 0, scene);
        m.set(F32, 88, offset);
        return this;
    }

    private MemorySegment shadow(int entry, int kind, int filter, int map) {
        Objects.checkIndex(entry, shadowCapacity);
        MemorySegment m = shadows.memory().asSlice((long) SHADOW_BYTES * entry, SHADOW_BYTES);
        m.set(U32, 64, kind);
        m.set(U32, 68, filter);
        m.set(U32, 72, map);
        m.set(F32, 84, Float.MAX_VALUE); // covers every depth, unless a cascade's split says otherwise
        m.set(F32, 88, 0);
        m.set(F32, 92, 0);
        return m;
    }

    private MemorySegment light(int index) {
        Objects.checkIndex(index, capacity);
        return lights.memory().asSlice((long) LIGHT_BYTES * index, LIGHT_BYTES);
    }

    /** Light {@code index}'s direction (where it shines; unit length) into {@code out}. Allocates nothing. */
    public void direction(int index, double[] out) {
        MemorySegment m = light(index);
        out[0] = m.get(F32, 32);
        out[1] = m.get(F32, 36);
        out[2] = m.get(F32, 40);
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
