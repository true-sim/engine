package com.truesim.engine.render;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_D32_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_UNDEFINED;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
import static org.lwjgl.vulkan.VK10.vkCmdCopyImageToBuffer;

import com.truesim.engine.graph.ExternalImage;
import com.truesim.engine.graph.FrameGraph;
import com.truesim.engine.graph.GpuTimings;
import com.truesim.engine.render.shadows.Shadows;
import com.truesim.engine.vulkan.Format;
import com.truesim.engine.vulkan.FrameSlots;
import com.truesim.engine.vulkan.GpuBuffer;
import com.truesim.engine.vulkan.GpuImage;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.engine.vulkan.VulkanInstance;
import com.truesim.scene.assets.mesh.MeshData;
import com.truesim.scene.core.ShadowSpec;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkBufferImageCopy;

/**
 * A video of {@link ManyLights}' scene, as PNG frames (TRUESIM_SHADOWS_VIDEO=folder; no encoder in the engine): the
 * camera flying over 30,000 rocks on the terrain lit by 200 point lights, 300 rocks circling through the lights and a
 * tenth of the lights circling, at 1920 x 1080 on a fixed 60 fps timeline (the scene stepped by 1/60 s a frame, not by
 * the clock), 10 s per shadow method, each in a folder of its own on the same path: {@code maps} (AUTO), {@code rays}
 * (hard), {@code soft} (soft, sphere radius 0.3). Next to the frames, {@code frames.csv}: each frame's GPU time (every
 * pass and the frame start), its shadow passes, tiles drawn and waiting, atlas texels used and bytes allocated; then
 * the same path once more without capture, frames in flight, the scene stepped by the clock ({@code realtime.csv}), as
 * capturing (a readback a frame) slows frames. The summaries print as a table.
 */
@EnabledIfEnvironmentVariable(named = "TRUESIM_SHADOWS_VIDEO", matches = ".+")
class ShadowsVideo {

    private static final int WIDTH = 1920;
    private static final int HEIGHT = 1080;
    private static final int FPS = 60;
    private static final double SECONDS = 10;
    private static final int ROCKS = 300;
    private static final double LIGHTS_MOVING = 0.1;

    /** A frame's measures. */
    private record Measure(double frame, double shadows, int drawn, int waiting, long texels, long allocated) {
    }

    @Test
    void video() throws Exception {
        Path root = Files.createDirectories(Path.of(System.getenv("TRUESIM_SHADOWS_VIDEO")));
        String only = System.getenv("TRUESIM_SHADOWS_VIDEO_ONLY"); // a method's folder name, or all
        FrameSlots slots = new FrameSlots(2);
        int usage = VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
        try (VulkanInstance instance = VulkanInstance.builder().applicationName("video").surfaces(false).build();
             VulkanDevice device = VulkanDevice.createHeadless(instance.bestGpu());
             GeometryPool pool = new GeometryPool(device, GeometryPool.Capacity.of(1 << 21, 100_000, 4), slots);
             GpuScene scene = new GpuScene(device, pool, 100_000, slots);
             MeshletRenderer renderer = MeshletBenchmark.withView(new MeshletRenderer(device, scene,
                 VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_D32_SFLOAT, slots), 80_000_000);
             Lights lights = new Lights(device, 256, 1024, slots);
             RayTracing rayTracing = device.rayTracing() ? new RayTracing(device, pool, 100_000) : null;
             GpuImage target = new GpuImage(device, WIDTH, HEIGHT, VK_FORMAT_R8G8B8A8_UNORM, usage,
                 VK_IMAGE_ASPECT_COLOR_BIT);
             GpuBuffer pixels = GpuBuffer.hostVisible(device, (long) WIDTH * HEIGHT * 4,
                 VK_BUFFER_USAGE_TRANSFER_DST_BIT)) {
            MeshData rockMesh = MeshletBenchmark.rock(4);
            MeshData terrainMesh = MeshletBenchmark.terrain(1024);
            int rock = pool.add(rockMesh);
            int ground = pool.add(terrainMesh);
            if (rayTracing != null) {
                rayTracing.add(rock, rockMesh, rockMesh.levels().size() - 1);
                rayTracing.add(ground, terrainMesh, terrainMesh.levels().size() - 1);
            }
            RenderView view = MeshletBenchmark.mainView(renderer);
            SceneGeometry geometry = SceneGeometry.meshlets(renderer);
            ManyLights field = new ManyLights(40);
            List<Object[]> methods = new ArrayList<>();
            methods.add(new Object[] {"maps", ShadowSpec.maps(ShadowSpec.AUTO)});
            if (rayTracing != null) {
                methods.add(new Object[] {"rays", ShadowSpec.rayTraced()});
                methods.add(new Object[] {"soft", ShadowSpec.softRayTraced(0.3)});
            }
            StringBuilder table = new StringBuilder(String.format(Locale.ROOT,
                "Shadows video on %s: 200 point lights, %d rocks and %d%% of the lights moving, %d x %d%n"
                    + "| Method | Run | Frames | Frame median / p95 / p99 / max (GPU) | Shadows median / p95 / p99 / max"
                    + " | Tiles drawn a frame (mean / max) | Atlas texels | Builds | Frames allocating |%n"
                    + "|---|---|---|---|---|---|---|---|---|%n",
                device.gpu().name(), ROCKS, Math.round(100 * LIGHTS_MOVING), WIDTH, HEIGHT));
            ExecutorService writers = Executors.newFixedThreadPool(Math.max(2,
                Runtime.getRuntime().availableProcessors() - 2));
            try {
                for (Object[] method : methods) {
                    String name = (String) method[0];
                    if (only != null && !only.equals(name)) {
                        continue;
                    }
                    ShadowSpec spec = (ShadowSpec) method[1];
                    for (boolean capture : new boolean[] {true, false}) {
                        MeshletBenchmark.rocksOnTerrain(renderer, rock, ground, 30_000, 600, ManyLights.GROUND_Y);
                        field.place(lights, 1, spec);
                        lights.directional(0, -Keys.SUN_X, -Keys.SUN_Y, -Keys.SUN_Z, 0.04f, 0.045f, 0.07f);
                        lights.shadow(0, ShadowSpec.NONE); // moonlight: the rocks' shapes, no shadows
                        Path folder = Files.createDirectories(root.resolve(name));
                        String row = run(device, slots, renderer, view, geometry, lights, rayTracing, scene, rock,
                            field, target, pixels, capture ? folder : null, folder, writers);
                        table.append(String.format(Locale.ROOT, "| %s | %s %s%n", name,
                            capture ? "fixed 60 fps, captured" : "clock-paced, no capture", row));
                    }
                }
            } finally {
                writers.shutdown();
                writers.awaitTermination(10, TimeUnit.MINUTES);
            }
            System.out.print(table);
            Files.writeString(root.resolve("summary.md"), table.toString(), StandardCharsets.UTF_8);
        }
    }

    /**
     * One run of the path: captured on the fixed timeline into {@code frames} (frames.csv and PNGs), or paced by the
     * clock without capture (realtime.csv in {@code folder}). Returns the summary row after the method's name.
     */
    private static String run(VulkanDevice device, FrameSlots slots, MeshletRenderer renderer, RenderView view,
        SceneGeometry geometry, Lights lights, RayTracing rayTracing, GpuScene scene, int rock, ManyLights field,
        GpuImage target, GpuBuffer pixels, Path frames, Path folder, ExecutorService writers) throws Exception {
        boolean capture = frames != null;
        Camera camera = new Camera();
        place(camera, 0);
        view.set(camera, 1, MeshletRenderer.MODE_LIT);
        com.sun.management.ThreadMXBean threads =
            (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
        ExternalImage external = new ExternalImage("target", WIDTH, HEIGHT, Format.R8G8B8A8_UNORM,
            VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT, VK_IMAGE_LAYOUT_UNDEFINED,
            VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL).set(target.handle(), 0);
        try (FrameGraph graph = new FrameGraph(device, slots)) {
            SceneView main = new SceneView(graph.addView("main").size(WIDTH, HEIGHT), view, camera);
            Shadows shadows = new Shadows(geometry, lights).rayTracing(rayTracing);
            main.port().add(new VisibilityBufferShading(geometry, main, VK_FORMAT_R8G8B8A8_UNORM,
                VK_FORMAT_D32_SFLOAT)).add(new ClusteredLights(lights, main)).add(shadows.view(main))
                .output(Keys.SCENE_COLOR, external);
            graph.timings(true);
            long[] counted = {0};
            // Warm up at the path's start: the graph built, the atlas drawn, the denoisers' histories filled
            for (int i = 0; i < 60; i++) {
                field.move(scene, rock, ROCKS, lights, 1, LIGHTS_MOVING, 0);
                frame(graph, slots, counted);
            }
            device.waitIdle();
            int builds = graph.builds();
            int planned = (int) Math.round(SECONDS * FPS);
            int capacity = capture ? planned : planned * 20;
            double[] gpu = new double[capacity];
            double[] shadow = new double[capacity];
            Arrays.fill(gpu, Double.NaN);
            Arrays.fill(shadow, Double.NaN);
            long[] graphFrame = new long[capacity];
            int[] drawn = new int[capacity];
            int[] waiting = new int[capacity];
            long[] texels = new long[capacity];
            long[] allocated = new long[capacity];
            double[] at = new double[capacity];
            List<Future<?>> written = new ArrayList<>();
            long start = System.nanoTime();
            int count = 0;
            for (int f = 0; f < capacity; f++) {
                double seconds = capture ? f / (double) FPS : (System.nanoTime() - start) / 1e9;
                if (seconds >= SECONDS) {
                    break;
                }
                long before = threads.getCurrentThreadAllocatedBytes();
                place(camera, seconds);
                field.move(scene, rock, ROCKS, lights, 1, LIGHTS_MOVING, seconds);
                graphFrame[f] = frame(graph, slots, counted);
                allocated[f] = threads.getCurrentThreadAllocatedBytes() - before;
                drawn[f] = shadows.stats().drawn();
                waiting[f] = shadows.stats().waiting();
                texels[f] = shadows.stats().texels();
                at[f] = seconds;
                count = f + 1;
                collect(graph.timings(), graphFrame, f, gpu, shadow);
                if (capture) {
                    device.waitIdle();
                    int[] rgba = readback(device, target, pixels);
                    Path file = frames.resolve(String.format(Locale.ROOT, "frame-%05d.png", f));
                    written.add(writers.submit(() -> write(rgba, file)));
                }
            }
            for (int i = 0; i < 3; i++) {
                frame(graph, slots, counted); // the last frames' timings come in as later frames begin
                collect(graph.timings(), graphFrame, count, gpu, shadow);
            }
            device.waitIdle();
            int built = graph.builds() - builds;
            shadows.close();
            for (Future<?> w : written) {
                w.get();
            }
            try (Writer csv = Files.newBufferedWriter(folder.resolve(capture ? "frames.csv" : "realtime.csv"),
                StandardCharsets.UTF_8)) {
                csv.write("frame,seconds,gpu_ms,shadow_ms,tiles_drawn,tiles_waiting,atlas_texels,allocated_bytes\n");
                for (int f = 0; f < count; f++) {
                    csv.write(String.format(Locale.ROOT, "%d,%.4f,%.4f,%.4f,%d,%d,%d,%d%n", f, at[f], gpu[f],
                        shadow[f], drawn[f], waiting[f], texels[f], allocated[f]));
                }
            }
            int allocating = 0;
            double drawnSum = 0;
            int drawnMax = 0;
            long texelsMax = 0;
            for (int f = 0; f < count; f++) {
                allocating += allocated[f] > 0 ? 1 : 0;
                drawnSum += drawn[f];
                drawnMax = Math.max(drawnMax, drawn[f]);
                texelsMax = Math.max(texelsMax, texels[f]);
            }
            return String.format(Locale.ROOT, "| %d | %s | %s | %.1f / %d | %,d | %d | %d |", count,
                stats(gpu, count), stats(shadow, count), drawnSum / count, drawnMax, texelsMax, built, allocating);
        }
    }

    /** A frame, frames in flight: begun, its slot opened, submitted. Returns its number (the graph's, from 1). */
    private static long frame(FrameGraph graph, FrameSlots slots, long[] counted) {
        int slot = graph.beginFrame();
        slots.begin(slot);
        graph.submit(0, 0);
        slots.end();
        return ++counted[0];
    }

    /** The finished frame's timings, into the entry of the frame of that number. */
    private static void collect(GpuTimings timings, long[] graphFrame, int upTo, double[] gpu, double[] shadow) {
        if (timings == null) {
            return;
        }
        long number = timings.frame();
        for (int f = upTo; f >= 0 && f >= upTo - 4; f--) {
            if (f < graphFrame.length && graphFrame[f] == number && Double.isNaN(gpu[f])) {
                double start = timings.frameStartMillis();
                double total = Double.isNaN(start) ? 0 : start;
                double shadows = 0;
                for (int t = 0; t < timings.count(); t++) {
                    double ms = timings.millis(t);
                    if (Double.isNaN(ms)) {
                        continue;
                    }
                    total += ms;
                    String pass = timings.pass(t);
                    if (pass.contains("shadow") || pass.contains("traced") || pass.contains("top level")) {
                        shadows += ms;
                    }
                }
                gpu[f] = total;
                shadow[f] = shadows;
                return;
            }
        }
    }

    /** Median, 95th and 99th percentiles and the most of the measured. */
    private static String stats(double[] values, int count) {
        double[] known = new double[count];
        int n = 0;
        for (int i = 0; i < count; i++) {
            if (!Double.isNaN(values[i])) {
                known[n++] = values[i];
            }
        }
        double[] sorted = Arrays.copyOf(known, n);
        Arrays.sort(sorted);
        if (n == 0) {
            return "-";
        }
        return String.format(Locale.ROOT, "%.2f / %.2f / %.2f / %.2f ms", sorted[n / 2],
            sorted[Math.min(n - 1, (int) (0.95 * n))], sorted[Math.min(n - 1, (int) (0.99 * n))], sorted[n - 1]);
    }

    /**
     * The camera's path at {@code seconds}: 12 units over the field's middle, from its near edge 140 units along it
     * in 10 s, looking ahead and down, swaying left and right.
     */
    static void place(Camera camera, double seconds) {
        double z = -2 - 14 * seconds;
        double x = 20 * Math.sin(seconds * 0.45);
        double yaw = 0.25 * Math.sin(seconds * 0.45 + 0.8);
        camera.position(x, 0, z);
        camera.set(Math.sin(yaw), -0.42, -Math.cos(yaw), 0, 1, 0, Math.PI / 3, WIDTH, HEIGHT, 0.1);
    }

    private static int[] readback(VulkanDevice device, GpuImage target, GpuBuffer pixels) {
        device.submitAndWait(device.graphics(), commands -> {
            try (MemoryStack stack = stackPush()) {
                VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
                region.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
                region.imageExtent().set(WIDTH, HEIGHT, 1);
                vkCmdCopyImageToBuffer(commands, target.handle(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    pixels.handle(), region);
            }
        });
        int[] rgba = new int[WIDTH * HEIGHT];
        java.lang.foreign.MemorySegment.copy(pixels.memory(), ValueLayout.JAVA_INT, 0, rgba, 0, rgba.length);
        return rgba;
    }

    private static void write(int[] rgba, Path file) {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        int[] row = new int[WIDTH];
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                int p = rgba[y * WIDTH + x];
                row[x] = (p & 0xff) << 16 | ((p >> 8) & 0xff) << 8 | ((p >> 16) & 0xff);
            }
            image.setRGB(0, y, WIDTH, 1, row, 0, WIDTH);
        }
        try {
            ImageIO.write(image, "png", file.toFile());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
