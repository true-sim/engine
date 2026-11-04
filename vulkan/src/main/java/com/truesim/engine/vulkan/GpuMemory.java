package com.truesim.engine.vulkan;

import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_STORAGE_BIT;

import java.util.concurrent.atomic.AtomicLongArray;

/**
 * The engine's own accounting of GPU memory by what it holds, for the profiler's counters: every {@link GpuBuffer} and
 * {@link GpuImage} adds its allocation's size to a category when made and takes it off when closed. A category comes
 * from the scope the allocation is made in ({@link #enter}: the geometry pool's meshes, the frame graph's transients)
 * or else from the usage (acceleration structure storage, host-visible copy sources as staging, attachments and
 * storage images as render targets, other images as textures, other buffers as buffers). Counting is two atomic adds;
 * reading allocates nothing.
 */
public final class GpuMemory {

    /** What memory holds. */
    public enum Category {
        /** Sampled images: textures, environment maps. */
        TEXTURES,
        /** Attachments and storage images: what the frame renders into. */
        RENDER_TARGETS,
        /** Meshes: vertices, indices, meshlets. */
        MESHES,
        /** Bottom and top level acceleration structures and their scratch. */
        ACCELERATION,
        /** The frame graph's transient images and buffers. */
        TRANSIENTS,
        /** Host-visible buffers copied from: uploads. */
        STAGING,
        /** Every other buffer. */
        BUFFERS;

        private static final Category[] ALL = values();

        /** The category of ordinal {@code i}. */
        public static Category of(int i) {
            return ALL[i];
        }

        /** How many there are. */
        public static int count() {
            return ALL.length;
        }
    }

    private static final AtomicLongArray BYTES = new AtomicLongArray(Category.count());
    private static final AtomicLongArray COUNT = new AtomicLongArray(Category.count());
    private static final ThreadLocal<Category[]> SCOPE = ThreadLocal.withInitial(() -> new Category[1]);

    private GpuMemory() {
    }

    /**
     * Counts what the calling thread allocates as {@code category} until {@link #exit} with the returned previous
     * scope: {@code Category before = GpuMemory.enter(MESHES); try { ... } finally { GpuMemory.exit(before); }}.
     */
    public static Category enter(Category category) {
        Category[] s = SCOPE.get();
        Category before = s[0];
        s[0] = category;
        return before;
    }

    /** Ends the scope {@link #enter} began, back to {@code before}. */
    public static void exit(Category before) {
        SCOPE.get()[0] = before;
    }

    static Category buffer(int usage, boolean hostVisible) {
        Category scoped = SCOPE.get()[0];
        if (scoped != null) {
            return scoped;
        }
        if ((usage & VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR) != 0) {
            return Category.ACCELERATION;
        }
        if (hostVisible && (usage & VK_BUFFER_USAGE_TRANSFER_SRC_BIT) != 0) {
            return Category.STAGING;
        }
        return Category.BUFFERS;
    }

    static Category image(int usage) {
        Category scoped = SCOPE.get()[0];
        if (scoped != null) {
            return scoped;
        }
        int targets = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT
            | VK_IMAGE_USAGE_STORAGE_BIT;
        return (usage & targets) != 0 ? Category.RENDER_TARGETS : Category.TEXTURES;
    }

    static void allocated(Category category, long bytes) {
        BYTES.addAndGet(category.ordinal(), bytes);
        COUNT.incrementAndGet(category.ordinal());
    }

    static void freed(Category category, long bytes) {
        BYTES.addAndGet(category.ordinal(), -bytes);
        COUNT.decrementAndGet(category.ordinal());
    }

    /** Bytes allocated as {@code category} now, all devices together. */
    public static long bytes(Category category) {
        return BYTES.get(category.ordinal());
    }

    /** Allocations counted as {@code category} now. */
    public static long count(Category category) {
        return COUNT.get(category.ordinal());
    }

    /** Bytes allocated now, every category. */
    public static long total() {
        long t = 0;
        for (int i = 0; i < Category.count(); i++) {
            t += BYTES.get(i);
        }
        return t;
    }
}
