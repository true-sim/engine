package com.truesim.engine.render;

import static org.lwjgl.vulkan.VK10.VK_FORMAT_D32_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_UNDEFINED;

import com.truesim.engine.graph.ExternalImage;
import com.truesim.engine.graph.FrameGraph;
import com.truesim.engine.graph.GpuTimings;
import com.truesim.engine.graph.Technique;
import com.truesim.engine.render.shadows.CascadedShadowMaps;
import com.truesim.engine.render.shadows.RayTracedShadows;
import com.truesim.engine.render.shadows.ShadowSettings;
import com.truesim.engine.render.shadows.Shadows;
import com.truesim.engine.vulkan.Format;
import com.truesim.engine.vulkan.GpuImage;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.engine.vulkan.VulkanInstance;
import com.truesim.scene.assets.mesh.MeshData;
import com.truesim.scene.core.ShadowSpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * The {@link Shadows} owner's methods, timed (TRUESIM_BENCHMARK=true): 30,000 rocks on the terrain, the visibility
 * buffer, the sun's shadows by each method at 1920 x 1080 and 3840 x 2160, the camera still and turning (a still
 * camera keeps its cascades' tiles); the techniques they replace beside them; then 200 point lights with AUTO maps as
 * the camera flies over them (cost, tiles drawn, atlas use, graph builds), and specs switched every frame. GPU time per
 * frame (every pass and the frame start) and the shadows' passes alone, medians over frames, ranges over rounds taken
 * in turns after a warm-up round.
 */
@EnabledIfEnvironmentVariable(named = "TRUESIM_BENCHMARK", matches = "true")
class ShadowsBenchmark {

    private static final int ROUNDS = 3;
    private static final int FRAMES = 60;

    private record Config(String name, Consumer<Setup> setup) {
    }

    /** What a configuration sets up in a graph. */
    private record Setup(FrameGraph graph, SceneView main, SceneGeometry geometry, Lights lights, RayTracing rays,
        List<Technique> added, Shadows[] owner) {
    }

    private record Sample(double frame, double shadows, int drawn, long texels, int builds) {
    }

    @Test
    void shadowMethods() {
        try (VulkanInstance instance = VulkanInstance.builder().applicationName("benchmark").surfaces(false).build();
             VulkanDevice device = VulkanDevice.createHeadless(instance.bestGpu());
             GeometryPool pool = new GeometryPool(device, GeometryPool.Capacity.of(1 << 21, 100_000, 4));
             GpuScene scene = new GpuScene(device, pool, 100_000);
             MeshletRenderer renderer = MeshletBenchmark.withView(new MeshletRenderer(device, scene,
                 VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_D32_SFLOAT), 80_000_000);
             Lights lights = new Lights(device, 256, 1024);
             RayTracing rayTracing = device.rayTracing() ? new RayTracing(device, pool, 100_000) : null) {
            MeshData rockMesh = MeshletBenchmark.rock(4);
            MeshData terrainMesh = MeshletBenchmark.terrain(1024);
            int rock = pool.add(rockMesh);
            int ground = pool.add(terrainMesh);
            if (rayTracing != null) {
                rayTracing.add(rock, rockMesh, rockMesh.levels().size() - 1);
                rayTracing.add(ground, terrainMesh, terrainMesh.levels().size() - 1);
            }
            double groundY = -12;
            MeshletBenchmark.rocksOnTerrain(renderer, rock, ground, 30_000, 600, groundY);
            RenderView view = MeshletBenchmark.mainView(renderer);
            SceneGeometry geometry = SceneGeometry.meshlets(renderer);
            System.out.printf(Locale.ROOT, "Shadows on %s (%s), 30,000 rocks on the terrain, the visibility buffer:%n",
                device.gpu().name(), rayTracing != null ? "ray tracing" : "no ray tracing");
            for (int[] size : new int[][] {{1920, 1080}, {3840, 2160}}) {
                List<Config> configs = sunConfigs(rayTracing != null);
                for (boolean moving : new boolean[] {false, true}) {
                    run(device, renderer, view, geometry, lights, rayTracing, size[0], size[1], moving, configs,
                        String.format(Locale.ROOT, "%d x %d, the camera %s", size[0], size[1],
                            moving ? "turning" : "still"), false);
                }
            }
            lights.directional(0, 0, -1, 0, 0, 0, 0);
            List<Config> many = new ArrayList<>();
            many.add(new Config("200 point lights, unshadowed", s -> points(s, null)));
            many.add(new Config("200 point lights, AUTO maps", s -> points(s, ShadowSpec.maps(ShadowSpec.AUTO))));
            many.add(new Config("200 point lights, 256 maps", s -> points(s, ShadowSpec.maps(256))));
            many.add(new Config("200 point lights, AUTO maps, specs switched every frame",
                s -> points(s, ShadowSpec.maps(ShadowSpec.AUTO))));
            run(device, renderer, view, geometry, lights, rayTracing, 1920, 1080, true, many,
                "1920 x 1080, flying over 200 point lights", true);
        }
    }

    private static List<Config> sunConfigs(boolean rays) {
        List<Config> configs = new ArrayList<>();
        configs.add(new Config("unshadowed", s -> sun(s, null)));
        configs.add(new Config("maps: one 4096 to 300", s -> sun(s, ShadowSpec.maps(4096).distance(300))));
        configs.add(new Config("cascades: 3 of 2048 to 300", s -> sun(s, ShadowSpec.cascades(3, 2048, 300))));
        configs.add(new Config("cascades: AUTO to 300", s -> sun(s, ShadowSpec.cascades(ShadowSpec.AUTO,
            ShadowSpec.AUTO, 300))));
        configs.add(new Config("(before) CascadedShadowMaps 3 of 2048 to 300", s -> {
            s.lights().directional(0, -Keys.SUN_X, -Keys.SUN_Y, -Keys.SUN_Z, 3, 3, 3).setCount(1);
            s.lights().shadow(0, ShadowSpec.NONE);
            CascadedShadowMaps cascades = new CascadedShadowMaps(s.geometry(), s.lights(), 0, 0, s.main(), 3, 2048,
                ViewOptions.shadow(1 << 18, 1 << 24), 300);
            s.added().add(cascades);
        }));
        if (rays) {
            configs.add(new Config("ray traced", s -> sun(s, ShadowSpec.rayTraced())));
            configs.add(new Config("(before) RayTracedShadows", s -> {
                s.lights().directional(0, -Keys.SUN_X, -Keys.SUN_Y, -Keys.SUN_Z, 3, 3, 3).setCount(1);
                s.lights().shadow(0, ShadowSpec.NONE);
                SceneDraw ids = new SceneDraw(s.geometry(), s.main().view(), null, SurfaceOutput.ids(),
                    VK_FORMAT_D32_SFLOAT, s.main().width(), s.main().height(), true); // the visibility buffer's
                s.added().add(new RayTracedShadows(s.rays(), s.lights(), 0, 0, ids, s.main().camera()));
            }));
            configs.add(new Config("soft ray traced (sun 0.0047), SVGF", s -> sun(s,
                ShadowSpec.softRayTraced(0.0047))));
            configs.add(new Config("soft ray traced (sun 0.0047), REBLUR", s -> {
                s.graph().settings().set(ShadowSettings.SOFT_DENOISER, Denoiser.Mode.REBLUR);
                sun(s, ShadowSpec.softRayTraced(0.0047));
            }));
            configs.add(new Config("soft ray traced (sun 0.0047), 4 rays, SVGF", s -> {
                s.graph().settings().set(ShadowSettings.SOFT_RAYS, 4);
                sun(s, ShadowSpec.softRayTraced(0.0047));
            }));
        }
        return configs;
    }

    private static void sun(Setup s, ShadowSpec spec) {
        s.lights().directional(0, -Keys.SUN_X, -Keys.SUN_Y, -Keys.SUN_Z, 3, 3, 3).setCount(1);
        s.lights().shadow(0, spec == null ? ShadowSpec.NONE : spec);
        s.owner()[0] = new Shadows(s.geometry(), s.lights()).rayTracing(s.rays());
        s.added().add(s.owner()[0].view(s.main()));
    }

    private static void points(Setup s, ShadowSpec spec) {
        MeshletBenchmark.scatterLights(s.lights(), 200, -12, 40);
        s.lights().setCount(200);
        for (int i = 0; i < 200; i++) {
            s.lights().shadow(i, spec == null ? ShadowSpec.NONE : spec);
        }
        s.owner()[0] = new Shadows(s.geometry(), s.lights()).rayTracing(s.rays());
        s.added().add(s.owner()[0].view(s.main()));
    }

    private static void run(VulkanDevice device, MeshletRenderer renderer, RenderView view, SceneGeometry geometry,
        Lights lights, RayTracing rayTracing, int width, int height, boolean moving, List<Config> configs,
        String title, boolean switching) {
        Sample[][] samples = new Sample[configs.size()][ROUNDS];
        try (GpuImage shaded = new GpuImage(device, width, height, VK_FORMAT_R8G8B8A8_UNORM,
            org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT, org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT)) {
            ExternalImage target = new ExternalImage("shaded", width, height, Format.R8G8B8A8_UNORM,
                org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT, VK_IMAGE_LAYOUT_UNDEFINED,
                org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL).set(shaded.handle(), 0);
            for (int round = -1; round < ROUNDS; round++) {
                for (int c = 0; c < configs.size(); c++) {
                    Sample sample = measure(device, renderer, view, geometry, lights, rayTracing, width, height,
                        moving, configs.get(c), target, switching && configs.get(c).name().contains("switched"));
                    if (round >= 0) {
                        samples[c][round] = sample;
                    }
                }
            }
        }
        System.out.println(title + ":");
        System.out.println("| Shadows | Frame (GPU) | Shadow passes | Tiles drawn a frame | Atlas texels | Builds |");
        System.out.println("|---|---|---|---|---|---|");
        for (int c = 0; c < configs.size(); c++) {
            Sample[] all = samples[c];
            System.out.printf(Locale.ROOT, "| %s | %s | %s | %d | %,d | %d |%n", configs.get(c).name(),
                range(all, true), range(all, false), all[0].drawn(), all[0].texels(), all[0].builds());
        }
    }

    private static Sample measure(VulkanDevice device, MeshletRenderer renderer, RenderView view,
        SceneGeometry geometry, Lights lights, RayTracing rayTracing, int width, int height, boolean moving,
        Config config, ExternalImage target, boolean switching) {
        Camera camera = new Camera();
        place(camera, 0, width, height);
        view.set(camera, 1, MeshletRenderer.MODE_LIT);
        try (FrameGraph graph = new FrameGraph(device, 2)) {
            SceneView main = new SceneView(graph.addView("main").size(width, height), view, camera);
            List<Technique> added = new ArrayList<>();
            added.add(new VisibilityBufferShading(geometry, main, VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_D32_SFLOAT));
            added.add(new ClusteredLights(lights, main));
            Shadows[] owner = {null};
            config.setup().accept(new Setup(graph, main, geometry, lights, rayTracing, added, owner));
            for (Technique technique : added) {
                main.port().add(technique);
            }
            main.port().output(Keys.SCENE_COLOR, target);
            CascadedShadowMaps legacy = null;
            for (Technique technique : added) {
                if (technique instanceof CascadedShadowMaps cascades) {
                    legacy = cascades;
                }
            }
            ShadowSpec[] specs = {ShadowSpec.maps(ShadowSpec.AUTO), ShadowSpec.maps(128), ShadowSpec.maps(512)};
            int frame = 0;
            for (int i = 0; i < 30; i++) {
                frame = step(device, graph, camera, width, height, moving, legacy, lights, switching, specs, frame);
            }
            int builds = graph.builds();
            graph.timings(true);
            double[] frames = new double[FRAMES];
            double[] shadow = new double[FRAMES];
            int drawn = 0;
            for (int i = -4; i < FRAMES; i++) {
                frame = step(device, graph, camera, width, height, moving, legacy, lights, switching, specs, frame);
                GpuTimings timings = graph.timings();
                if (i < 0 || timings == null) {
                    continue;
                }
                double total = timings.frameStartMillis();
                for (int t = 0; t < timings.count(); t++) {
                    double ms = timings.millis(t);
                    if (Double.isNaN(ms)) {
                        continue;
                    }
                    total += ms;
                    String pass = timings.pass(t);
                    if (pass.contains("shadow") || pass.contains("traced") || pass.contains("cascade")
                        || pass.contains("view ") || pass.contains("top level") || pass.contains("bottom level")) {
                        shadow[i] += ms;
                    }
                }
                frames[i] = total;
                drawn = Math.max(drawn, owner[0] == null ? 0 : owner[0].stats().drawn());
            }
            graph.timings(false);
            device.waitIdle();
            int built = graph.builds() - builds;
            long texels = owner[0] == null ? 0 : owner[0].stats().texels();
            if (owner[0] != null) {
                owner[0].close();
            }
            if (legacy != null) {
                legacy.close();
            }
            return new Sample(median(frames), median(shadow), drawn, texels, built);
        }
    }

    /** A frame: the camera placed (turning, or still), the legacy cascades fitted, specs switched where asked. */
    private static int step(VulkanDevice device, FrameGraph graph, Camera camera, int width, int height,
        boolean moving, CascadedShadowMaps legacy, Lights lights, boolean switching, ShadowSpec[] specs, int frame) {
        if (moving) {
            place(camera, frame, width, height);
        }
        if (legacy != null) {
            legacy.update();
        }
        if (switching) {
            for (int i = 0; i < 200; i += 7) {
                lights.shadow(i, specs[(frame + i) % specs.length]);
            }
        }
        device.submitAndWait(device.graphics(), graph::record);
        return frame + 1;
    }

    /** The camera over the rocks, turning a little each frame and moving along. */
    private static void place(Camera camera, int frame, int width, int height) {
        double a = 0.004 * frame;
        camera.position(0, 0, -0.5 * frame);
        camera.set(Math.sin(a), -0.35, -Math.cos(a), 0, 1, 0, Math.PI / 3, width, height, 0.1);
    }

    private static double median(double[] values) {
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }

    private static String range(Sample[] samples, boolean frame) {
        double min = Double.MAX_VALUE;
        double max = 0;
        for (Sample s : samples) {
            double v = frame ? s.frame() : s.shadows();
            min = Math.min(min, v);
            max = Math.max(max, v);
        }
        return String.format(Locale.ROOT, "%.2f-%.2f ms", min, max);
    }
}
