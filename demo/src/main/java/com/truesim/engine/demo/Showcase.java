package com.truesim.engine.demo;

import static org.lwjgl.vulkan.VK10.VK_FORMAT_D32_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16B16A16_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_UNDEFINED;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT;

import com.truesim.engine.graph.ExternalImage;
import com.truesim.engine.graph.FrameGraph;
import com.truesim.engine.graph.GpuTimings;
import com.truesim.engine.graph.ViewPort;
import com.truesim.engine.render.Camera;
import com.truesim.engine.render.ClusteredForwardShading;
import com.truesim.engine.render.ClusteredLights;
import com.truesim.engine.render.DisplayTransform;
import com.truesim.engine.render.Keys;
import com.truesim.engine.render.MeshletRenderer;
import com.truesim.engine.render.SceneView;
import com.truesim.engine.render.ViewOptions;
import com.truesim.engine.render.ChosenTransparency;
import com.truesim.engine.render.particles.ParticleDraw;
import com.truesim.engine.render.shadows.CascadedShadowMaps;
import com.truesim.engine.vulkan.Format;
import com.truesim.engine.vulkan.GpuImage;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.engine.vulkan.VulkanInstance;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The effects showcase: a hundred animated foxes in an arena at dusk ({@link DemoScene.Arena}), with everything the
 * particle system does at once ({@link ShowcaseEffects}): layered fire on a bonfire, on swaying torches and on a tenth
 * of the foxes as they run, dust at every fox's feet, a fireworks show lighting the ground, blasts of rocks bouncing
 * and casting shadows, hail, sparks, swirls and smoke in gusting wind, ribbons and beams. {@code ./gradlew
 * :engine-demo:showcase -Pcrowd=<glTF-Sample-Assets>/Models/Fox/glTF/Fox.gltf}, then either:
 *
 * <ul>
 *   <li>frames as a window shows them ({@link Capture}: tone mapped, sRGB) from one camera along a path:
 *       {@code -Pout=<folder> -Pframes=900 -Pfirst=300 -Pevery=2 -Psize=1920x1080 -Pcamera=orbit} (see
 *       {@link CameraPath}; simulated at 60 Hz, so every second frame makes a 30 fps clip), {@code -Pstills=360,720}
 *       for single frames too;</li>
 *   <li>{@code --args=measure}: complete frame times in stereo at a Quest 3's eye size, as {@code StereoBench} draws
 *       (offscreen, nothing presented): wall-clock frame time from one frame's start to the next, the main thread's CPU
 *       time by stage, the GPU's time for the frame and per pass, and whether each frame waited for the GPU.</li>
 * </ul>
 */
public final class Showcase {

    private static final double ARENA_X = 0;
    private static final double ARENA_Z = 0;
    private static final double ARENA_RADIUS = 24;
    private static final int EYE_WIDTH = 2064;
    private static final int EYE_HEIGHT = 2208;
    /** A frame that waited longer than this for the GPU to finish an older one was GPU-bound. */
    private static final double GPU_BOUND_WAIT_MS = 0.5;

    private Showcase() {
    }

    public static void main(String[] args) throws Exception {
        boolean measure = List.of(args).contains("measure");
        String fox = System.getProperty("truesim.demo.crowd", System.getProperty("truesim.demo.character"));
        if (fox == null) {
            throw new IllegalArgumentException("The showcase needs the Khronos fox: -Pcrowd=<path to Fox.gltf>");
        }
        int color = VK_FORMAT_R16G16B16A16_SFLOAT;
        int depth = VK_FORMAT_D32_SFLOAT;
        try (VulkanInstance instance = VulkanInstance.builder().applicationName("showcase").surfaces(false).build();
             VulkanDevice device = VulkanDevice.createHeadless(instance.bestGpu());
             FrameGraph graph = new FrameGraph(device, 2).timings(measure);
             DemoScene world = new DemoScene(device, graph.frameSlots(), color, depth, new DemoScene.Arena(ARENA_X,
                 ARENA_Z, ARENA_RADIUS, Path.of(fox), Integer.getInteger("truesim.demo.foxes", 100)));
             ShowcaseEffects effects = new ShowcaseEffects(device, graph.frameSlots(), world, ARENA_X, ARENA_Z,
                 ARENA_RADIUS)) {
            graph.add(effects.simulation());
            graph.settings().set(DisplayTransform.EXPOSURE, Float.parseFloat(System.getProperty(
                "truesim.demo.exposure", "0.35")));
            CameraPath path = new CameraPath(System.getProperty("truesim.demo.camera", "orbit"), ARENA_X, ARENA_Z,
                world.crowd());
            if (measure) {
                measure(device, graph, world, effects, path);
            } else {
                capture(device, graph, world, effects, path);
            }
        }
    }

    /** A view of {@code camera} at its size in {@code port}: forward shading, lights, particles, the sun's shadows. */
    private static CascadedShadowMaps view(DemoScene world, ShowcaseEffects effects, ViewPort port, Camera camera,
        Camera head, int color, int depth) {
        SceneView view = world.geometry().createView(port, camera, ViewOptions.of(1 << 14, 1 << 22)
            .withOcclusion(true)).set(1, MeshletRenderer.MODE_LIT);
        CascadedShadowMaps cascades = new CascadedShadowMaps(world.geometry(), world.lights(), 0, 0, view, 3, 2048,
            ViewOptions.shadow(1 << 16, 1 << 22), 150);
        port.add(new ClusteredForwardShading(world.geometry(), view, color, depth).clearColor(0.07f, 0.1f, 0.26f))
            .add(new ClusteredLights(world.lights(), view))
            .add(head == null ? new ParticleDraw(effects.particles(), view)
                : new ParticleDraw(effects.particles(), view, head))
            .add(cascades);
        if (ShowcaseEffects.GLASS > 0) { // windows: blended glass, and the fires drawn before it
            port.add(new ChosenTransparency(world.geometry(), view))
                .add(ParticleDraw.beforeGlass(effects.particles(), view, head));
        }
        return cascades;
    }

    /** Frames from one camera along the path, written as PNG files (and stills), simulated at 60 Hz. */
    private static void capture(VulkanDevice device, FrameGraph graph, DemoScene world, ShowcaseEffects effects,
        CameraPath path) throws Exception {
        String[] size = System.getProperty("truesim.demo.size", "1920x1080").split("x");
        int width = Integer.parseInt(size[0]);
        int height = Integer.parseInt(size[1]);
        int frames = Integer.getInteger("truesim.demo.frames", 600);
        int first = Integer.getInteger("truesim.demo.first", 0);
        int every = Integer.getInteger("truesim.demo.every", 2);
        String out = System.getProperty("truesim.demo.out");
        List<String> stills = List.of(System.getProperty("truesim.demo.stills", "").split(","));
        if (out != null) {
            Files.createDirectories(Path.of(out));
        }
        Camera camera = new Camera();
        ViewPort port = graph.addView("camera").size(width, height);
        CascadedShadowMaps cascades = view(world, effects, port, camera, null, VK_FORMAT_R16G16B16A16_SFLOAT,
            VK_FORMAT_D32_SFLOAT);
        double[] eye = new double[3];
        double[] target = new double[3];
        int written = 0;
        try (Capture capture = new Capture(device, port); cascades) {
            for (int frame = 0; frame < frames; frame++) {
                double seconds = frame / 60.0;
                world.update(seconds);
                path.at(seconds, eye, target);
                camera.position(eye[0], eye[1], eye[2]).set(target[0] - eye[0], target[1] - eye[1],
                    target[2] - eye[2], 0, 1, 0, Math.PI / 3, width, height, 0.05);
                world.crowd().lod(eye[0], eye[2]);
                effects.update(1 / 60.0, eye);
                cascades.update();
                graph.beginFrame();
                graph.submit(0, 0);
                world.recorded();
                boolean clip = out != null && frame >= first && (frame - first) % every == 0;
                boolean still = out != null && stills.contains(Integer.toString(frame));
                if (clip || still) {
                    device.waitIdle();
                    if (clip) {
                        capture.write(Path.of(out, String.format(Locale.ROOT, "f_%05d.png", written++)));
                    }
                    if (still) {
                        capture.write(Path.of(out, "still_" + frame + ".png"));
                    }
                }
                if (frame % 120 == 0) {
                    System.out.printf(Locale.ROOT, "frame %d: %d particles alive, %d launches%n", frame,
                        effects.particles().alive(), effects.launched());
                }
            }
            device.waitIdle();
        }
        System.out.println(written + " frames written to " + out);
    }

    /**
     * Complete frame times in stereo: two eyes 64 mm apart at a Quest 3's eye size along the camera path, offscreen
     * (rendered into images, nothing presented), simulated at 72 Hz.
     */
    private static void measure(VulkanDevice device, FrameGraph graph, DemoScene world, ShowcaseEffects effects,
        CameraPath path) throws Exception {
        int warmUp = 120;
        int frames = Integer.getInteger("truesim.demo.frames", 720);
        int usage = VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
        List<AutoCloseable> owned = new ArrayList<>();
        Camera[] eyes = {new Camera(), new Camera()};
        Camera head = new Camera();
        CascadedShadowMaps[] cascades = new CascadedShadowMaps[2];
        for (int e = 0; e < 2; e++) {
            GpuImage target = new GpuImage(device, EYE_WIDTH, EYE_HEIGHT, VK_FORMAT_R8G8B8A8_UNORM, usage,
                VK_IMAGE_ASPECT_COLOR_BIT);
            owned.add(target);
            ViewPort port = graph.addView(e == 0 ? "left" : "right").size(EYE_WIDTH, EYE_HEIGHT);
            cascades[e] = view(world, effects, port, eyes[e], head, VK_FORMAT_R16G16B16A16_SFLOAT,
                VK_FORMAT_D32_SFLOAT);
            owned.add(cascades[e]);
            port.output(Keys.SCENE_COLOR, new ExternalImage("eye " + e, EYE_WIDTH, EYE_HEIGHT, Format.R8G8B8A8_UNORM,
                usage, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL)
                .set(target.handle(), target.view()));
        }
        // Per measured frame, milliseconds: start to next start; scene update, animation, effects sync, waiting for a
        // frame slot, recording and submitting; the GPU's frame (first pass to last) and its passes summed
        int n = frames - warmUp;
        double[] wall = new double[n];
        double[] scene = new double[n];
        double[] animation = new double[n];
        double[] sync = new double[n];
        double[] wait = new double[n];
        double[] record = new double[n];
        double[] gpuSpan = new double[n];
        double[] gpuSum = new double[n];
        double[] passSums = new double[0];
        int[] passCounts = new int[0];
        String[] passNames = new String[0];
        long lastTimed = -1;
        double[] eye = new double[3];
        double[] target = new double[3];
        long previous = 0;
        long particles = 0;
        for (int frame = 0; frame < frames; frame++) {
            long start = System.nanoTime();
            int at = frame - warmUp;
            if (at > 0) {
                wall[at - 1] = (start - previous) / 1e6;
            }
            previous = start;
            double seconds = frame / 72.0;
            world.update(seconds);
            path.at(seconds, eye, target);
            for (int e = 0; e < 2; e++) {
                // The eyes 64 mm apart across the view
                double dx = target[0] - eye[0];
                double dz = target[2] - eye[2];
                double length = Math.max(1e-9, com.truesim.common.math.Maths.sqrt(dx * dx + dz * dz));
                double offset = e == 0 ? -0.032 : 0.032;
                eyes[e].position(eye[0] - offset * dz / length, eye[1], eye[2] + offset * dx / length)
                    .set(dx, target[1] - eye[1], dz, 0, 1, 0, Math.PI / 2, EYE_WIDTH, EYE_HEIGHT, 0.05);
            }
            head.position(eye[0], eye[1], eye[2]);
            world.crowd().lod(eye[0], eye[2]);
            cascades[0].update();
            cascades[1].update();
            long updated = System.nanoTime();
            effects.update(1 / 72.0, eye);
            long synced = System.nanoTime();
            graph.beginFrame();
            long begun = System.nanoTime();
            graph.submit(0, 0);
            long submitted = System.nanoTime();
            world.recorded();
            if (at >= 0) {
                double animated = world.crowd().lastAnimation() / 1e6;
                animation[at] = animated;
                scene[at] = (updated - start) / 1e6 - animated;
                sync[at] = (synced - updated) / 1e6;
                wait[at] = (begun - synced) / 1e6;
                record[at] = (submitted - begun) / 1e6;
                gpuSpan[at] = Double.NaN;
                GpuTimings timings = graph.timings();
                if (timings != null && timings.frame() != lastTimed) {
                    lastTimed = timings.frame();
                    gpuSpan[at] = timings.spanMillis();
                    gpuSum[at] = timings.totalMillis();
                    if (passSums.length != timings.count()) {
                        passSums = new double[timings.count()];
                        passCounts = new int[timings.count()];
                        passNames = new String[timings.count()];
                        for (int i = 0; i < passNames.length; i++) {
                            passNames[i] = timings.pass(i);
                        }
                    }
                    for (int i = 0; i < timings.count(); i++) {
                        if (!Double.isNaN(timings.millis(i))) {
                            passSums[i] += timings.millis(i);
                            passCounts[i]++;
                        }
                    }
                }
                particles += effects.particles().alive();
            }
        }
        device.waitIdle();
        wall[n - 1] = wall[n - 2]; // the last frame has no next start: as the one before
        report(wall, scene, animation, sync, wait, record, gpuSpan, gpuSum, passNames, passSums, passCounts,
            particles / n, effects);
        System.out.println(world.crowdReport());
        for (AutoCloseable resource : owned) {
            resource.close();
        }
    }

    private static void report(double[] wall, double[] scene, double[] animation, double[] sync, double[] wait,
        double[] record, double[] gpuSpan, double[] gpuSum, String[] passNames, double[] passSums, int[] passCounts,
        long particles, ShowcaseEffects effects) {
        int n = wall.length;
        double[] sorted = wall.clone();
        Arrays.sort(sorted);
        double onePercentLow = sorted[Math.min(n - 1, (int) Math.ceil(0.99 * n) - 1)];
        int gpuBound = 0;
        double[] cpu = new double[n];
        for (int i = 0; i < n; i++) {
            gpuBound += wait[i] > GPU_BOUND_WAIT_MS ? 1 : 0;
            cpu[i] = scene[i] + animation[i] + sync[i] + record[i];
        }
        System.out.printf(Locale.ROOT, "Showcase, stereo %d x %d per eye, forward shading, offscreen (no present), %d"
            + " frames after %d of warm-up; about %d particles alive, %d foxes on fire, %d launches%n", EYE_WIDTH,
            EYE_HEIGHT, n, 120, particles, effects.fireFoxes().length, effects.launched());
        System.out.printf(Locale.ROOT, "  %-44s %8s %8s %8s%n", "ms per frame", "mean", "1% high", "worst");
        row("wall clock, frame start to next start", wall);
        row("CPU, main thread (all stages but the wait)", cpu);
        row("  scene update (rocks, lights, crowd sync)", scene);
        row("  animation (animators, scene graph)", animation);
        row("  effects sync (bindings, EffectSync)", sync);
        row("  graph record and submit", record);
        row("  waiting for a frame slot (GPU behind)", wait);
        row("GPU, frame (first pass start to last end)", gpuSpan);
        row("GPU, passes summed", gpuSum);
        System.out.printf(Locale.ROOT, "  wall clock: 1%% low %.1f fps (%.3f ms); bound by: GPU %d frames, CPU %d"
            + " frames (GPU-bound: waited over %.1f ms for a frame slot)%n", 1000 / onePercentLow, onePercentLow,
            gpuBound, n - gpuBound, GPU_BOUND_WAIT_MS);
        System.out.println("Mean GPU ms per pass:");
        Map<String, Integer> seen = new LinkedHashMap<>(); // both eyes have passes of one name
        for (int i = 0; i < passNames.length; i++) {
            if (passCounts[i] == 0) {
                continue;
            }
            int k = seen.merge(passNames[i], 1, Integer::sum);
            System.out.printf(Locale.ROOT, "  %-50s %7.3f%n", k == 1 ? passNames[i] : passNames[i] + " (#" + k + ")",
                passSums[i] / passCounts[i]);
        }
    }

    /** A row of the report: the mean, the 99th percentile and the largest of {@code values} (NaN left out). */
    private static void row(String name, double[] values) {
        double[] kept = Arrays.stream(values).filter(v -> !Double.isNaN(v)).sorted().toArray();
        if (kept.length == 0) {
            return;
        }
        double mean = Arrays.stream(kept).average().orElse(Double.NaN);
        double high = kept[Math.min(kept.length - 1, (int) Math.ceil(0.99 * kept.length) - 1)];
        System.out.printf(Locale.ROOT, "  %-44s %8.3f %8.3f %8.3f%n", name, mean, high, kept[kept.length - 1]);
    }
}
