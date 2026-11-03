package com.truesim.engine.render;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_D32_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R32G32B32A32_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_UNDEFINED;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
import static org.lwjgl.vulkan.VK10.vkCmdCopyImageToBuffer;

import com.truesim.common.math.color.Color;
import com.truesim.engine.graph.Buffer;
import com.truesim.engine.graph.BufferAccess;
import com.truesim.engine.graph.ExternalImage;
import com.truesim.engine.graph.FrameGraph;
import com.truesim.engine.graph.GpuTimings;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Key;
import com.truesim.engine.graph.Technique;
import com.truesim.engine.render.materials.MetallicRoughnessParameters;
import com.truesim.engine.vulkan.Commands;
import com.truesim.engine.vulkan.Format;
import com.truesim.engine.vulkan.GpuBuffer;
import com.truesim.engine.vulkan.GpuImage;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.engine.vulkan.VulkanInstance;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkBufferImageCopy;

/**
 * Lights for ray hits (design D2): every light, the world grid's lists and its reservoirs, timed and their noise
 * measured on the reference path tracer's hits (TRUESIM_BENCHMARK=true): 3,000 rocks on a terrain at 1920 x 1080, at
 * night, lit by 256 lights the GPU writes (as particle lights) and 64, 1,024 or 4,096 point lights of the scene over
 * 200 x 200 units. Per configuration: the grid's build, the hits' shading (every bounce's shading passes: what the
 * lights cost there), both; and the noise at 16 samples (4 x 4 pixel means, relative), all medians over rounds
 * taken in turns after a warm-up round.
 */
@EnabledIfEnvironmentVariable(named = "TRUESIM_BENCHMARK", matches = "true")
class WorldLightsBenchmark {

    private static final int WIDTH = 1920;
    private static final int HEIGHT = 1080;
    private static final int ROUNDS = 4;
    private static final int[] POINTS = {64, 1024, 4096};
    private static final int GPU_LIGHTS = 256;
    private static final Key<Buffer> WRITTEN = Key.buffer("benchmark.gpuLights");

    /** Writes the GPU lights each frame, as particle lights are: points circling over the terrain. */
    private record Writer(Lights lights, int count) implements Technique {

        @Override
        public List<Key<?>> provides() {
            return List.of(WRITTEN);
        }

        @Override
        public void build(GraphBuilder g) {
            Buffer written = g.importBuffer(lights.gpuLightsBuffer());
            g.pass("gpu lights").write(written, BufferAccess.TRANSFER_DST).record(ctx -> {
                try (MemoryStack stack = stackPush()) {
                    int bytes = 16 + 80 * count;
                    long data = stack.ncalloc(16, 1, bytes);
                    MemoryUtil.memPutInt(data, count);
                    for (int i = 0; i < count; i++) {
                        long o = data + 16 + 80L * i;
                        double a = i * 2 * Math.PI / count;
                        MemoryUtil.memPutFloat(o, (float) (60 * Math.cos(a)));
                        MemoryUtil.memPutFloat(o + 4, 3);
                        MemoryUtil.memPutFloat(o + 8, (float) (-100 + 60 * Math.sin(a)));
                        MemoryUtil.memPutFloat(o + 12, 6);
                        MemoryUtil.memPutFloat(o + 16, 4);
                        MemoryUtil.memPutFloat(o + 20, 2);
                        MemoryUtil.memPutFloat(o + 24, 0.5f);
                        MemoryUtil.memPutInt(o + 52, -1);
                    }
                    for (int at = 0; at < bytes; at += 65536) {
                        Commands.updateBuffer(ctx.unsafe().commands(), ctx.unsafe().buffer(written), at,
                            Math.min(65536, bytes - at), data + at);
                    }
                }
            });
            g.provide(WRITTEN, written);
        }
    }

    private record Config(int points, ReferencePathTracer.Lighting lighting, WorldLights.Mode mode) {
        String name() {
            return lighting == ReferencePathTracer.Lighting.ALL ? "every light" : mode == WorldLights.Mode.LISTS
                ? "grid lists" : "grid reservoirs";
        }
    }

    private record Sample(double build, double shade, double frame, double noise) {
    }

    @Test
    void lightsForRayHits() {
        try (VulkanInstance instance = VulkanInstance.builder().applicationName("benchmark").surfaces(false).build();
             VulkanDevice device = VulkanDevice.createHeadless(instance.bestGpu());
             GeometryPool pool = new GeometryPool(device, GeometryPool.Capacity.of(1 << 21, 1 << 16, 4));
             GpuScene scene = new GpuScene(device, pool, 4096);
             Materials materials = new Materials(device, 16);
             MeshletRenderer renderer = MeshletBenchmark.withView(new MeshletRenderer(device, scene,
                 VK_FORMAT_R32G32B32A32_SFLOAT, VK_FORMAT_D32_SFLOAT), 1 << 24);
             RayTracing rayTracing = new RayTracing(device, pool, 4096);
             Lights lights = new Lights(device, 4096 + 1, 4);
             GpuImage target = new GpuImage(device, WIDTH, HEIGHT, VK_FORMAT_R32G32B32A32_SFLOAT,
                 VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT, VK_IMAGE_ASPECT_COLOR_BIT);
             GpuBuffer pixels = GpuBuffer.hostVisible(device, (long) WIDTH * HEIGHT * 16,
                 VK_BUFFER_USAGE_TRANSFER_DST_BIT)) {
            if (!device.rayTracing()) {
                System.out.println("No ray tracing on " + device.gpu().name());
                return;
            }
            var rockMesh = MeshletBenchmark.rock(4);
            var groundMesh = MeshletBenchmark.terrain(256);
            int rock = pool.add(rockMesh);
            int ground = pool.add(groundMesh);
            rayTracing.add(rock, rockMesh, rockMesh.levels().size() - 1);
            rayTracing.add(ground, groundMesh, groundMesh.levels().size() - 1);
            scene.setMaterials(materials);
            int grey = materials.add(new Material(MetallicRoughnessParameters.DEFAULTS
                .withBaseColor(new Color(0.6f, 0.6f, 0.6f, 1)).withRoughness(0.8f)));
            scene.set(0, ground, grey, 0, -2, -100, 0, 0, 0, 1, 0.8f, 0.8f, 0.8f);
            SplittableRandom random = new SplittableRandom(3);
            for (int i = 1; i <= 3000; i++) {
                float s = (float) random.nextDouble(0.3, 1.5);
                scene.set(i, rock, grey, (float) random.nextDouble(-100, 100), 0, (float) random.nextDouble(-200, 0),
                    0, 0, 0, 1, s, s, s);
            }
            scene.removeFrom(3001);
            Camera camera = new Camera().position(0, 25, 30);
            camera.set(0, -0.35, -1, 0, 1, 0, Math.PI / 3, WIDTH, HEIGHT, 0.1);
            RenderView view = MeshletBenchmark.mainView(renderer);
            view.set(camera, 1, MeshletRenderer.MODE_LIT);
            lights.gpuLights(GPU_LIGHTS);
            lights.writtenBy(WRITTEN);
            ExternalImage output = new ExternalImage("target", WIDTH, HEIGHT, Format.R32G32B32A32_SFLOAT,
                VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT, VK_IMAGE_LAYOUT_UNDEFINED,
                VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL).set(target.handle(), target.view());
            List<Config> configs = new ArrayList<>();
            for (int points : POINTS) {
                configs.add(new Config(points, ReferencePathTracer.Lighting.ALL, WorldLights.Mode.LISTS));
                configs.add(new Config(points, ReferencePathTracer.Lighting.GRID, WorldLights.Mode.LISTS));
                configs.add(new Config(points, ReferencePathTracer.Lighting.GRID, WorldLights.Mode.RESERVOIRS));
            }
            Sample[][] samples = new Sample[configs.size()][ROUNDS];
            for (int round = -1; round < ROUNDS; round++) {
                for (int c = 0; c < configs.size(); c++) {
                    Sample sample = measure(device, renderer, rayTracing, lights, view, camera, output, target, pixels,
                        configs.get(c));
                    if (round >= 0) {
                        samples[c][round] = sample;
                    }
                }
            }
            System.out.printf(Locale.ROOT, "Lights for ray hits on %s, %d x %d, 2 bounces, %d lights the GPU writes:%n",
                device.gpu().name(), WIDTH, HEIGHT, GPU_LIGHTS);
            System.out.println("| Point lights | Lights | Grid build | Hit shading | Both | Noise at 16 samples |");
            System.out.println("|---|---|---|---|---|---|");
            for (int c = 0; c < configs.size(); c++) {
                Sample[] all = samples[c];
                System.out.printf(Locale.ROOT, "| %,d | %s | %s | %s | %s | %s |%n", configs.get(c).points(),
                    configs.get(c).name(), range(all, 0), range(all, 1), range(all, 2), noiseRange(all));
            }
        }
    }

    private static Sample measure(VulkanDevice device, MeshletRenderer renderer, RayTracing rayTracing,
        Lights lights, RenderView view, Camera camera, ExternalImage output, GpuImage target, GpuBuffer pixels,
        Config config) {
        SplittableRandom random = new SplittableRandom(11);
        lights.directional(0, -0.3f, -1, -0.2f, 0.02f, 0.02f, 0.03f); // moonlight
        for (int i = 1; i <= config.points(); i++) {
            lights.point(i, random.nextDouble(-100, 100), random.nextDouble(0.5, 4), random.nextDouble(-200, 0),
                (float) random.nextDouble(4, 12), (float) random.nextDouble(0.5, 4), (float) random.nextDouble(0.5,
                    4), (float) random.nextDouble(0.5, 4));
        }
        lights.setCount(config.points() + 1);
        try (FrameGraph graph = new FrameGraph(device, 2)) {
            SceneView main = new SceneView(graph.addView("main").size(WIDTH, HEIGHT), view, camera);
            ReferencePathTracer reference = new ReferencePathTracer(SceneGeometry.meshlets(renderer), rayTracing, main,
                VK_FORMAT_R32G32B32A32_SFLOAT).worldLights(lights);
            graph.settings().set(ReferencePathTracer.BOUNCES, 2).set(ReferencePathTracer.LIGHTS, config.lighting())
                .set(WorldLights.MODE, config.mode()).set(WorldLights.CELL, 8f).set(WorldLights.CELLS, 64)
                .set(WorldLights.SLOTS, config.mode() == WorldLights.Mode.LISTS ? 64 : 16);
            graph.add(new Writer(lights, GPU_LIGHTS));
            main.port().add(reference).add(new ClusteredLights(lights, main)).output(Keys.SCENE_COLOR, output);
            for (int i = 0; i < 8; i++) {
                device.submitAndWait(device.graphics(), graph::record);
            }
            graph.timings(true);
            int frames = 24;
            double[] build = new double[frames];
            double[] shade = new double[frames];
            double[] frame = new double[frames];
            for (int i = -4; i < frames; i++) {
                device.submitAndWait(device.graphics(), graph::record);
                GpuTimings timings = graph.timings();
                if (i < 0 || timings == null) {
                    continue;
                }
                for (int t = 0; t < timings.count(); t++) {
                    double ms = timings.millis(t);
                    if (Double.isNaN(ms)) {
                        continue;
                    }
                    if (timings.pass(t).equals("world lights")) {
                        build[i] += ms;
                    } else if (timings.pass(t).startsWith("reference shade")) {
                        shade[i] += ms;
                    }
                }
                frame[i] = build[i] + shade[i];
            }
            graph.timings(false);
            // The noise: 16 samples from a restart, against 16 more
            reference.restart();
            float[] half = read(device, graph, target, pixels, 16);
            float[] full = read(device, graph, target, pixels, 16);
            return new Sample(median(build), median(shade), median(frame), noise(half, full));
        }
    }

    private static float[] read(VulkanDevice device, FrameGraph graph, GpuImage target, GpuBuffer pixels, int frames) {
        for (int i = 0; i < frames; i++) {
            device.submitAndWait(device.graphics(), graph::record);
        }
        device.submitAndWait(device.graphics(), commands -> {
            try (MemoryStack stack = stackPush()) {
                VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
                region.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
                region.imageExtent().set(WIDTH, HEIGHT, 1);
                vkCmdCopyImageToBuffer(commands, target.handle(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    pixels.handle(), region);
            }
        });
        return pixels.memory().asSlice(0, (long) WIDTH * HEIGHT * 16).toArray(ValueLayout.JAVA_FLOAT);
    }

    /** Relative noise of 4 x 4 pixel means of the 2n-sample image {@code full} whose first n are {@code half}. */
    private static double noise(float[] half, float[] full) {
        double noise = 0;
        double signal = 0;
        int count = 0;
        for (int by = 0; by + 4 <= HEIGHT; by += 4) {
            for (int bx = 0; bx + 4 <= WIDTH; bx += 4) {
                double a = 0;
                double b = 0;
                for (int y = by; y < by + 4; y++) {
                    for (int x = bx; x < bx + 4; x++) {
                        int p = y * WIDTH + x;
                        double h = ReferencePathTracerTest.luminance(half, p);
                        a += h;
                        b += 2 * ReferencePathTracerTest.luminance(full, p) - h;
                    }
                }
                noise += (a - b) * (a - b) / 4;
                signal += (a + b) / 2;
                count++;
            }
        }
        return Math.sqrt(noise / count) / (signal / count);
    }

    private static double median(double[] values) {
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }

    private static String range(Sample[] samples, int field) {
        double low = Double.MAX_VALUE;
        double high = 0;
        for (Sample s : samples) {
            double v = field == 0 ? s.build() : field == 1 ? s.shade() : s.frame();
            low = Math.min(low, v);
            high = Math.max(high, v);
        }
        return String.format(Locale.ROOT, "%.3f-%.3f ms", low, high);
    }

    private static String noiseRange(Sample[] samples) {
        double low = Double.MAX_VALUE;
        double high = 0;
        for (Sample s : samples) {
            low = Math.min(low, s.noise());
            high = Math.max(high, s.noise());
        }
        return String.format(Locale.ROOT, "%.1f-%.1f %%", 100 * low, 100 * high);
    }
}
