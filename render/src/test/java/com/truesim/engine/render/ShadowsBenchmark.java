package com.truesim.engine.render;

import static org.lwjgl.vulkan.VK10.VK_FORMAT_D32_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_UNDEFINED;

import com.truesim.engine.graph.ExternalImage;
import com.truesim.engine.graph.FrameGraph;
import com.truesim.engine.graph.GpuTimings;
import com.truesim.engine.graph.Technique;
import com.truesim.engine.render.shadows.ShadowSettings;
import com.truesim.engine.render.shadows.Shadows;
import com.truesim.engine.vulkan.Format;
import com.truesim.engine.vulkan.GpuImage;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.engine.vulkan.VulkanInstance;
import com.truesim.scene.assets.mesh.MeshData;
import com.truesim.scene.core.Mobility;
import com.truesim.scene.core.ShadowMethod;
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
 * camera keeps its cascades' tiles); then 200 point lights with AUTO maps as
 * the camera flies over them (cost, tiles drawn, atlas use, graph builds), and specs switched every frame. GPU time per
 * frame (every pass and the frame start) and the shadows' passes alone, medians over frames, ranges over rounds taken
 * in turns after a warm-up round.
 */
@EnabledIfEnvironmentVariable(named = "TRUESIM_BENCHMARK", matches = "true")
class ShadowsBenchmark {

    private static final int ROUNDS = 3;
    private static final int FRAMES = 60;
    private static final ManyLights MOVING = new ManyLights(40);

    /**
     * A configuration: {@code rocks} rocks and {@code share} of the lights of {@link ManyLights} moving ({@code pausing}:
     * the rocks standing every other {@link #PAUSE} frames, as characters pausing).
     */
    private record Config(String name, Consumer<Setup> setup, int rocks, double share, boolean pausing) {
        Config(String name, Consumer<Setup> setup) {
            this(name, setup, 0, 0, false);
        }

        Config(String name, Consumer<Setup> setup, int rocks, double share) {
            this(name, setup, rocks, share, false);
        }
    }

    /** Frames pausing rocks move, then stand. */
    private static final int PAUSE = 40;

    /** What a configuration sets up in a graph. */
    private record Setup(FrameGraph graph, SceneView main, SceneGeometry geometry, Lights lights, RayTracing rays,
        List<Technique> added, Shadows[] owner) {
    }

    private record Sample(double frame, double shadows, double drawn, double waiting, long texels, int builds,
        double[] frames, double[] shadowFrames, double forced, long waited, long drawnTotal, int maxWait, long late,
        long changed, double culling, double prepare, double movingCasters) {
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
            String part = System.getenv("TRUESIM_SHADOWS_ONLY");
            boolean pointsOnly = part != null && (part.equals("points") || part.startsWith("dynamic"));
            for (int[] size : pointsOnly ? new int[0][] : new int[][] {{1920, 1080}, {3840, 2160}}) {
                List<Config> configs = sunConfigs(rayTracing != null);
                String only = System.getenv("TRUESIM_SHADOWS_ONLY");
                if (only != null) {
                    configs.removeIf(c -> !c.name().contains(only)); // a part of a run
                }
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
            String only = System.getenv("TRUESIM_SHADOWS_ONLY");
            if (only == null || "points".equals(only)) {
                run(device, renderer, view, geometry, lights, rayTracing, 1920, 1080, true, many,
                    "1920 x 1080, flying over 200 point lights", true);
            }
            if (only == null || only.startsWith("dynamic")) {
                ShadowsBenchmark.rockMesh = rock;
                groundMesh = ground;
                ShadowSpec[] methods = rayTracing != null
                    ? new ShadowSpec[] {ShadowSpec.maps(ShadowSpec.AUTO), ShadowSpec.rayTraced(),
                        ShadowSpec.softRayTraced(0.3)}
                    : new ShadowSpec[] {ShadowSpec.maps(ShadowSpec.AUTO)};
                for (ShadowSpec method : methods) {
                    String name = method.method().name().toLowerCase(Locale.ROOT);
                    if (only != null && only.length() > 8 && !name.equals(only.substring(8))) {
                        continue; // TRUESIM_SHADOWS_ONLY="dynamic maps": one method
                    }
                    run(device, renderer, view, geometry, lights, rayTracing, 1920, 1080, true,
                        dynamicConfigs(method),
                        "1920 x 1080, flying over 200 point lights (" + name + "), rocks and lights moving", false);
                }
            }
        }
    }

    /** The rock mesh, for moving rocks. */
    private static int rockMesh = -1;
    private static int groundMesh = -1;

    /** 200 point lights shadowed by {@code method}: still, rocks moving, lights moving, both. */
    private static List<Config> dynamicConfigs(ShadowSpec method) {
        List<Config> configs = new ArrayList<>();
        Consumer<Setup> setup = s -> {
            if (System.getenv("TRUESIM_SHADOWS_DRAWS") != null) {
                s.graph().settings().set(ShadowSettings.ATLAS_DRAWS,
                    Integer.parseInt(System.getenv("TRUESIM_SHADOWS_DRAWS")));
            }
            if (System.getenv("TRUESIM_SHADOWS_UPDATES") != null) { // importance, all, capped
                s.graph().settings().set(ShadowSettings.ATLAS_UPDATES, ShadowSettings.Updates.valueOf(
                    System.getenv("TRUESIM_SHADOWS_UPDATES").toUpperCase(Locale.ROOT)));
            }
            if (System.getenv("TRUESIM_SHADOWS_URGENT") != null) {
                s.graph().settings().set(ShadowSettings.ATLAS_URGENT,
                    Float.parseFloat(System.getenv("TRUESIM_SHADOWS_URGENT")));
            }
            if (System.getenv("TRUESIM_SHADOWS_DETAIL") != null) { // caster detail: finer still casters cost more
                s.graph().settings().set(ShadowSettings.CASTER_DETAIL,
                    Float.parseFloat(System.getenv("TRUESIM_SHADOWS_DETAIL")));
            }
            if (System.getenv("TRUESIM_SHADOWS_LAG") != null) {
                s.graph().settings().set(ShadowSettings.ATLAS_LAG,
                    Integer.parseInt(System.getenv("TRUESIM_SHADOWS_LAG")));
            }
            MOVING.place(s.lights(), 0, method);
            s.owner()[0] = new Shadows(s.geometry(), s.lights()).rayTracing(s.rays());
            s.added().add(s.owner()[0].view(s.main()));
        };
        // TRUESIM_SHADOWS_AB: each case with caster layers on too, in turns
        boolean ab = System.getenv("TRUESIM_SHADOWS_AB") != null && method.method() == ShadowMethod.MAPS;
        // TRUESIM_SHADOWS_MOBILITY: each case with the scene's hints (the moving rocks and lights dynamic, the rest
        // static) used, and ignored (DETECT), in turns; with cases of rocks pausing
        boolean mobility = System.getenv("TRUESIM_SHADOWS_MOBILITY") != null;
        Consumer<Setup> layered = s -> {
            s.graph().settings().set(ShadowSettings.CASTER_LAYERS, true);
            setup.accept(s);
        };
        String[] names = {"all still", "10 rocks moving", "100 rocks moving", "500 rocks moving", "2,000 rocks moving",
            "10% of the lights moving", "50% of the lights moving", "300 rocks and 10% of the lights moving (the video)",
            "500 rocks and 10% of the lights moving", "2,000 rocks and 50% of the lights moving",
            "100 rocks moving and pausing", "500 rocks moving and pausing"};
        int[] rocks = {0, 10, 100, 500, 2000, 0, 0, 300, 500, 2000, 100, 500};
        double[] shares = {0, 0, 0, 0, 0, 0.1, 0.5, 0.1, 0.1, 0.5, 0, 0};
        String cases = System.getenv("TRUESIM_SHADOWS_CASES"); // names, separated by ';': those alone
        for (int i = 0; i < names.length; i++) {
            if (cases != null && !Arrays.asList(cases.split(";")).contains(names[i])) {
                continue;
            }
            boolean pausing = names[i].contains("pausing");
            if (pausing && !mobility) {
                continue; // the hints' cases
            }
            if (mobility) {
                int moving = rocks[i];
                double share = shares[i];
                configs.add(new Config(names[i] + ", hints", s -> {
                    hint(s, moving, share);
                    setup.accept(s);
                }, rocks[i], shares[i], pausing));
                configs.add(new Config(names[i] + ", detected", s -> {
                    hint(s, moving, share);
                    s.graph().settings().set(ShadowSettings.CASTER_MOBILITY, ShadowSettings.MobilityUse.DETECT)
                        .set(ShadowSettings.LIGHT_MOBILITY, ShadowSettings.MobilityUse.DETECT);
                    setup.accept(s);
                }, rocks[i], shares[i], pausing));
                continue;
            }
            configs.add(new Config(names[i], setup, rocks[i], shares[i]));
            if (ab) {
                configs.add(new Config(names[i] + ", caster layers", layered, rocks[i], shares[i]));
            }
        }
        return configs;
    }

    /**
     * The scene's hints as an application marks them: the rocks that move (1 to {@code rocks}) and the lights of
     * {@code share} dynamic, the terrain and every other rock and light static.
     */
    private static void hint(Setup s, int rocks, double share) {
        GpuScene scene = s.geometry().renderer().scene();
        for (int id = 0; id < scene.highWater(); id++) {
            if (scene.contains(id)) {
                scene.setMobility(id, id >= 1 && id <= rocks ? Mobility.DYNAMIC : Mobility.STATIC);
            }
        }
        for (int i = 0; i < ManyLights.COUNT; i++) {
            s.lights().mobility(i, ManyLights.moves(i, share) ? Mobility.DYNAMIC : Mobility.STATIC);
        }
    }

    private static List<Config> sunConfigs(boolean rays) {
        List<Config> configs = new ArrayList<>();
        configs.add(new Config("unshadowed", s -> sun(s, null)));
        configs.add(new Config("maps: one 4096 to 300", s -> sun(s, ShadowSpec.maps(4096).distance(300))));
        configs.add(new Config("cascades: 3 of 2048 to 300", s -> sun(s, ShadowSpec.cascades(3, 2048, 300))));
        configs.add(new Config("cascades: 3 of 2048 to 300, front faces", s -> {
            s.graph().settings().set(ShadowSettings.CASTER_FACES, ShadowSettings.Faces.FRONT);
            sun(s, ShadowSpec.cascades(3, 2048, 300));
        }));
        configs.add(new Config("maps: one 4096 to 300, front faces", s -> {
            s.graph().settings().set(ShadowSettings.CASTER_FACES, ShadowSettings.Faces.FRONT);
            sun(s, ShadowSpec.maps(4096).distance(300));
        }));
        configs.add(new Config("cascades: AUTO to 300", s -> sun(s, ShadowSpec.cascades(ShadowSpec.AUTO,
            ShadowSpec.AUTO, 300))));
        if (rays) {
            configs.add(new Config("ray traced", s -> sun(s, ShadowSpec.rayTraced())));
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
        System.out.println("| Shadows | Frame (GPU) | Frame p99 | Shadow passes | Shadow p99 | Culling"
            + " | Tiles drawn a frame"
            + " | Of them forced | Tiles waiting | Lag drawn mean / changed mean / max (frames) | Atlas texels"
            + " | Builds | Prepare (CPU) | Moving casters | Shadow mean |");
        System.out.println("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|");
        for (int c = 0; c < configs.size(); c++) {
            Sample[] all = samples[c];
            long waited = 0;
            long drawn = 0;
            int maxWait = 0;
            long late = 0;
            long changed = 0;
            double drawnMean = 0;
            double forced = 0;
            double waiting = 0;
            double culling = 0;
            double prepare = 0;
            double movers = 0;
            for (Sample sample : all) {
                culling += sample.culling() / all.length;
                prepare += sample.prepare() / all.length;
                movers += sample.movingCasters() / all.length;
                waited += sample.waited();
                drawn += sample.drawnTotal();
                maxWait = Math.max(maxWait, sample.maxWait());
                late += sample.late();
                changed += sample.changed();
                drawnMean += sample.drawn() / all.length;
                forced += sample.forced() / all.length;
                waiting += sample.waiting() / all.length;
            }
            System.out.printf(Locale.ROOT, "| %s | %s | %.2f ms | %s | %.2f ms | %.2f ms | %.1f | %.1f | %.1f"
                    + " | %.2f / %.2f / %d | %,d | %d | %.3f ms | %.0f | %.3f ms |%n", configs.get(c).name(), range(all, true),
                p99(all, true),
                range(all, false), p99(all, false), culling, drawnMean, forced, waiting, drawn == 0 ? 0 : waited / (double) drawn,
                changed == 0 ? 0 : late / (double) changed, maxWait, all[0].texels(), all[0].builds(), prepare, movers, mean(all));
        }
    }

    private static Sample measure(VulkanDevice device, MeshletRenderer renderer, RenderView view,
        SceneGeometry geometry, Lights lights, RayTracing rayTracing, int width, int height, boolean moving,
        Config config, ExternalImage target, boolean switching) {
        Camera camera = new Camera();
        place(camera, 0, width, height);
        view.set(camera, 1, MeshletRenderer.MODE_LIT);
        try (FrameGraph graph = new FrameGraph(device, 2)) {
            if (System.getenv("TRUESIM_SHADOWS_SYNC") != null) {
                graph.asyncCompute(false); // every pass on the graphics queue: no overlap in the timings
            }
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
            ShadowSpec[] specs = {ShadowSpec.maps(ShadowSpec.AUTO), ShadowSpec.maps(128), ShadowSpec.maps(512)};
            int frame = 0;
            graph.timings(true); // before the warm-up: turning timings on builds the graph again
            for (int i = 0; i < 30; i++) {
                frame = step(device, graph, camera, width, height, moving, lights, switching, specs, frame, config,
                    renderer.scene());
            }
            int builds = graph.builds();
            double[] frames = new double[FRAMES];
            double[] shadow = new double[FRAMES];
            double[] culling = new double[FRAMES];
            java.util.Map<String, Double> passes = System.getenv("TRUESIM_SHADOWS_PASSES") != null
                ? new java.util.LinkedHashMap<>() : null;
            double drawn = 0;
            double waiting = 0;
            double forced = 0;
            long waited = 0;
            long drawnTotal = 0;
            int maxWait = 0;
            long late = 0;
            long changed = 0;
            double[] prepare = new double[FRAMES];
            double movers = 0;
            for (int i = -4; i < FRAMES; i++) {
                frame = step(device, graph, camera, width, height, moving, lights, switching, specs, frame, config,
                    renderer.scene());
                GpuTimings timings = graph.timings();
                if (i < 0 || timings == null) {
                    continue;
                }
                double start = timings.frameStartMillis();
                double total = Double.isNaN(start) ? 0 : start;
                if (passes != null) { // every pass's time, summed over the frames (passes numbered alike as one)
                    for (int t = 0; t < timings.count(); t++) {
                        double ms = timings.millis(t);
                        passes.merge(timings.pass(t).replaceAll("[0-9]+", "#"), Double.isNaN(ms) ? 0 : ms,
                            Double::sum);
                    }
                    if (owner[0] != null) {
                        passes.merge("(tiles layered)", (double) owner[0].stats().layered(), Double::sum);
                        passes.merge("(tiles over their layer)", (double) owner[0].stats().moving(), Double::sum);
                        passes.merge("(tiles drawn)", (double) owner[0].stats().drawn(), Double::sum);
                    }
                }
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
                    } else if (pass.contains("culling")) {
                        culling[i] += ms; // the frame's views' culling, the tiles' views with the camera's
                    }
                }
                frames[i] = total;
                drawn += owner[0] == null ? 0 : owner[0].stats().drawn() / (double) FRAMES;
                waiting += owner[0] == null ? 0 : owner[0].stats().waiting() / (double) FRAMES;
                if (owner[0] != null) {
                    Shadows.Stats stats = owner[0].stats();
                    forced += stats.forced() / (double) FRAMES;
                    waited += stats.waited();
                    drawnTotal += stats.drawn();
                    maxWait = Math.max(maxWait, stats.maxWait());
                    late += stats.late();
                    changed += stats.drawn() + stats.waiting();
                    prepare[i] = stats.prepareNanos() / 1.0e6;
                    movers += stats.movingCasters() / (double) FRAMES;
                }
            }
            graph.timings(false);
            if (passes != null) {
                StringBuilder line = new StringBuilder(config.name() + " (per frame):");
                passes.forEach((pass, sum) -> line.append(String.format(Locale.ROOT, " %s %.3f;", pass,
                    sum / FRAMES)));
                System.out.println(line);
            }
            device.waitIdle();
            int built = graph.builds() - builds;
            long texels = owner[0] == null ? 0 : owner[0].stats().texels();
            if (owner[0] != null) {
                owner[0].close();
            }
            if (config.rocks() > 0) {
                MeshletBenchmark.rocksOnTerrain(renderer, rockMesh, groundMesh, 30_000, 600, -12); // where they were
            }
            return new Sample(median(frames), median(shadow), drawn, waiting, texels, built, frames, shadow, forced,
                waited, drawnTotal, maxWait, late, changed, median(culling), median(prepare), movers);
        }
    }

    /** A frame: the camera placed (turning, or still), specs switched where asked. */
    private static int step(VulkanDevice device, FrameGraph graph, Camera camera, int width, int height,
        boolean moving, Lights lights, boolean switching, ShadowSpec[] specs, int frame, Config config,
        GpuScene scene) {
        if (moving) {
            place(camera, frame, width, height);
        }
        boolean standing = config.pausing() && frame / PAUSE % 2 == 1;
        if ((config.rocks() > 0 || config.share() > 0) && !standing) {
            MOVING.move(scene, rockMesh, config.rocks(), lights, 0, config.share(), frame / 60.0);
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

    /** The mean of every round's shadow passes over their frames. */
    private static double mean(Sample[] samples) {
        double sum = 0;
        int n = 0;
        for (Sample s : samples) {
            for (double v : s.shadowFrames()) {
                sum += v;
                n++;
            }
        }
        return n == 0 ? 0 : sum / n;
    }

    private static double median(double[] values) {
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }

    /** The 99th percentile of every round's frames together. */
    private static double p99(Sample[] samples, boolean frame) {
        int n = 0;
        for (Sample s : samples) {
            n += s.frames().length;
        }
        double[] all = new double[n];
        int at = 0;
        for (Sample s : samples) {
            double[] values = frame ? s.frames() : s.shadowFrames();
            System.arraycopy(values, 0, all, at, values.length);
            at += values.length;
        }
        Arrays.sort(all);
        return all[Math.min(n - 1, (int) Math.ceil(0.99 * n) - 1)];
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
