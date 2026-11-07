package com.truesim.engine.render;

import static org.lwjgl.vulkan.EXTMeshShader.VK_SHADER_STAGE_MESH_BIT_EXT;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_FRAGMENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_VERTEX_BIT;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.vulkan.Pipeline;
import com.truesim.engine.vulkan.ShaderModule;
import com.truesim.engine.vulkan.VulkanDevice;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A {@link MeshletRenderer}'s pipelines of material types (their generated entry points): drawing a shading bin into
 * color, or a type with geometry hooks into depth or the visibility buffer's IDs, on any geometry path (indexed, mesh
 * shaders, classic LOD). Each is made once, by what it is made of ({@link Key}), and kept until the renderer closes.
 *
 * <p>A pipeline is made either when it is first wanted ({@link #get}), or ahead, on a thread of its own
 * ({@link #prepare}): when a material that will draw with it is added, so the frame that first draws it finds it made.
 * Whoever comes first makes it; a pipeline wanted while it is being made ahead is waited for, which takes no longer
 * than making it then, so preparing never delays a material's first appearance.
 */
@AllowAllocation("pipelines are made rarely: as material types and raster bins first appear")
final class ShadingPipelines implements AutoCloseable {

    /** Into the renderer's color format and depth. */
    static final int COLOR = 0;
    /** Into depth alone (shadow maps, depth passes). */
    static final int DEPTH = 1;
    /** Into the visibility buffer's IDs ({@link MeshletRenderer#ID_FORMAT}) and depth. */
    static final int IDS = 2;
    /** Into weighted blended transparency's targets, the opaque depth tested, not written. */
    static final int WEIGHTED = 3;
    /**
     * Into the scene's color, blended over it by the fragment's alpha ({@link SortedTransparency}), the opaque depth
     * tested, not written.
     */
    static final int OVER = 4;
    /** Into the renderer's color format, the surface outputs ({@link SurfaceOutputs}) and depth. */
    static final int COLOR_SURFACES = 5;

    /** Culls back faces (counter-clockwise front faces). */
    static final int CULL_BACK = 0;
    /** Culls nothing. */
    static final int DOUBLE_SIDED = 1;
    /** Flag: clockwise front faces (classic LOD's mirrored instances). */
    static final int CLOCKWISE = 2;

    /**
     * What a pipeline is made of: its geometry stage (a vertex or mesh entry point), its fragment stage (or none, for
     * depth from positions alone), the specialization constants of its fragment entry point (a shading bin's
     * features, a type's geometry hooks, the shading hooks its view runs: {@code SHADING_HOOKS}, constant 3), its
     * rasterization ({@link #CULL_BACK}, {@link #DOUBLE_SIDED}, with {@link #CLOCKWISE}), its targets
     * ({@link #COLOR} ...) and its push block's bytes.
     */
    record Key(String geometryShader, String geometryEntry, boolean mesh, String fragmentShader, String fragmentEntry,
        int features, int geometry, int raster, int target, int push, int hooks) {

        Key {
            Objects.requireNonNull(geometryShader, "geometryShader");
            Objects.requireNonNull(geometryEntry, "geometryEntry");
            if ((fragmentShader == null) != (fragmentEntry == null)) {
                throw new IllegalArgumentException("A fragment shader and its entry point, or neither");
            }
        }
    }

    /** A pipeline being made or made: the first to claim it makes it. */
    private static final class Entry {
        final CompletableFuture<Pipeline> made = new CompletableFuture<>();
        final AtomicBoolean claimed = new AtomicBoolean();
    }

    private final VulkanDevice device;
    private final int colorFormat;
    private final int depthFormat;
    private final ConcurrentHashMap<Key, Entry> pipelines = new ConcurrentHashMap<>();
    private final AtomicLong nanos = new AtomicLong();
    private final AtomicLong waitedNanos = new AtomicLong();
    private ExecutorService ahead; // made when first asked to prepare
    private volatile boolean closed;
    private volatile int coldSalt; // benchmarks: a constant no shader reads, so the driver's disk cache has none

    ShadingPipelines(VulkanDevice device, int colorFormat, int depthFormat) {
        this.device = device;
        this.colorFormat = colorFormat;
        this.depthFormat = depthFormat;
    }

    /**
     * The pipeline of {@code key}: made, or made now on this thread, or, being made ahead, waited for. Thread-safe.
     */
    Pipeline get(Key key) {
        Entry entry = pipelines.computeIfAbsent(key, k -> new Entry());
        if (entry.claimed.compareAndSet(false, true)) {
            make(key, entry);
        } else if (!entry.made.isDone()) {
            long start = System.nanoTime();
            entry.made.join();
            waitedNanos.addAndGet(System.nanoTime() - start);
        }
        return entry.made.join();
    }

    /** The pipeline of {@code key} if it is made, else null; never waits. Thread-safe. */
    Pipeline ready(Key key) {
        Entry entry = pipelines.get(key);
        return entry != null && entry.made.isDone() && !entry.made.isCompletedExceptionally() ? entry.made.join()
            : null;
    }

    /** Has the pipeline of {@code key} made ahead, on the preparing thread, unless it is made or being made. */
    synchronized void prepare(Key key) {
        if (closed) {
            return;
        }
        Entry entry = pipelines.computeIfAbsent(key, k -> new Entry());
        if (entry.claimed.get()) {
            return;
        }
        if (ahead == null) {
            ahead = Executors.newSingleThreadExecutor(r -> {
                Thread thread = new Thread(r, "truesim-pipelines");
                thread.setDaemon(true);
                return thread;
            });
        }
        ahead.execute(() -> {
            if (!closed && entry.claimed.compareAndSet(false, true)) {
                make(key, entry);
            }
        });
    }

    private void make(Key key, Entry entry) {
        long start = System.nanoTime();
        try (ShaderModule geometry = ShaderModule.load(device, key.geometryShader());
             ShaderModule fragment = key.fragmentShader() == null ? null
                 : key.fragmentShader().equals(key.geometryShader()) ? null
                 : ShaderModule.load(device, key.fragmentShader())) {
            Pipeline.Graphics graphics = Pipeline.graphics(device)
                .stage(key.mesh() ? VK_SHADER_STAGE_MESH_BIT_EXT : VK_SHADER_STAGE_VERTEX_BIT, geometry,
                    key.geometryEntry())
                .pushConstants(key.push());
            if (key.fragmentShader() != null) {
                graphics.stage(VK_SHADER_STAGE_FRAGMENT_BIT, fragment != null ? fragment : geometry,
                    key.fragmentEntry());
                graphics.constants(key.features(), key.geometry(), coldSalt, key.hooks());
            }
            if ((key.raster() & DOUBLE_SIDED) != 0) {
                graphics.doubleSided();
            }
            if ((key.raster() & CLOCKWISE) != 0) {
                graphics.clockwise();
            }
            Pipeline pipeline = switch (key.target()) {
                case COLOR -> graphics.color(colorFormat).depth(depthFormat).build();
                case COLOR_SURFACES -> graphics.color(colorFormat).color(SurfaceOutputs.NORMAL_FORMAT.vk())
                    .color(SurfaceOutputs.MATERIAL_FORMAT.vk()).color(SurfaceOutputs.REFLECTANCE_FORMAT.vk())
                    .color(SurfaceOutputs.DIFFUSE_FORMAT.vk()).depth(depthFormat).build();
                case DEPTH -> graphics.depth(depthFormat).build();
                case IDS -> graphics.color(MeshletRenderer.ID_FORMAT).depth(depthFormat).build();
                case WEIGHTED -> RasterPipelines.blended(graphics, depthFormat);
                case OVER -> graphics.color(colorFormat).blend(0, Pipeline.Blend.ALPHA).depth(depthFormat)
                    .depthTest(org.lwjgl.vulkan.VK10.VK_COMPARE_OP_GREATER_OR_EQUAL, false).build();
                default -> throw new IllegalArgumentException("No target " + key.target());
            };
            nanos.addAndGet(System.nanoTime() - start);
            entry.made.complete(pipeline);
        } catch (RuntimeException | Error e) {
            entry.made.completeExceptionally(e);
            throw e;
        }
    }

    /**
     * For benchmarks: pipelines made from now on take specialization constant 2, which no shader reads, at
     * {@code salt} (0: none), so a value never used before makes the driver compile them as if never seen.
     */
    void coldForBenchmarks(int salt) {
        coldSalt = salt;
    }

    /** How many pipelines are made or being made. */
    int count() {
        return pipelines.size();
    }

    /** How many pipelines into {@code target} ({@link #COLOR} ...) are made or being made. */
    int count(int target) {
        int count = 0;
        for (Key key : pipelines.keySet()) {
            count += key.target() == target ? 1 : 0;
        }
        return count;
    }

    /** Nanoseconds spent making pipelines, on any thread. */
    long nanos() {
        return nanos.get();
    }

    /** Nanoseconds threads wanting a pipeline waited for it to be made ahead. */
    long waitedNanos() {
        return waitedNanos.get();
    }

    /** Waits until the pipelines being made ahead are made (tests and benchmarks). */
    void awaitPrepared() {
        ExecutorService executor;
        synchronized (this) {
            executor = ahead;
        }
        if (executor != null && !closed) {
            try {
                executor.submit(() -> { }).get(); // after every pipeline asked for before: one thread, in order
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (java.util.concurrent.ExecutionException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    @Override
    public void close() {
        ExecutorService executor;
        synchronized (this) {
            closed = true;
            executor = ahead;
        }
        if (executor != null) {
            executor.shutdown();
            try {
                executor.awaitTermination(1, TimeUnit.MINUTES);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        for (Entry entry : pipelines.values()) {
            if (entry.made.isDone() && !entry.made.isCompletedExceptionally()) {
                entry.made.join().close();
            }
        }
        pipelines.clear();
    }
}
