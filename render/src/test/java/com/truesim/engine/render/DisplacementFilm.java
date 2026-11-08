package com.truesim.engine.render;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_D32_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_UNDEFINED;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
import static org.lwjgl.vulkan.VK10.vkCmdCopyImageToBuffer;

import com.truesim.common.math.color.Color;
import com.truesim.engine.graph.ExternalImage;
import com.truesim.engine.graph.FrameGraph;
import com.truesim.engine.render.materials.Grass;
import com.truesim.engine.render.materials.GrassParameters;
import com.truesim.engine.render.materials.MetallicRoughnessParameters;
import com.truesim.engine.render.materials.TestWindParameters;
import com.truesim.engine.render.shadows.Shadows;
import com.truesim.engine.vulkan.Format;
import com.truesim.engine.vulkan.GpuBuffer;
import com.truesim.engine.vulkan.GpuImage;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.engine.vulkan.VulkanInstance;
import com.truesim.scene.assets.mesh.MeshData;
import com.truesim.scene.assets.mesh.VertexAttribute;
import com.truesim.scene.assets.mesh.VertexFormat;
import com.truesim.scene.core.ShadowSpec;
import java.awt.image.BufferedImage;
import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.function.IntFunction;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkBufferImageCopy;

/**
 * Frames of the benchmark's grass field ({@link DisplacementBenchmark}) for films: the field waving with the camera
 * moving through it; RASTER against COMPUTE under ray-traced shadows (rays meet RASTER's blades undisplaced); the
 * blades' shadow maps moving with the wind beside the motion vectors, and how well the frame before reprojected by them
 * matches each frame (what a temporal technique would see: ghosting where they are wrong). PNG frames into
 * {@code TRUESIM_FILM/<film>/f%04d.png}. Run with {@code TRUESIM_FILM=<directory> ./gradlew :engine-render:test --tests
 * '*DisplacementFilm'}.
 */
@EnabledIfEnvironmentVariable(named = "TRUESIM_FILM", matches = ".+")
class DisplacementFilm {

    private static final int FPS = 30;

    /** A camera at frame {@code f}: position x, y, z and forward x, y, z. */
    private interface Path3 {
        double[] at(int f);
    }

    @Test
    void films() throws Exception {
        Path out = Path.of(System.getenv("TRUESIM_FILM"));
        try (VulkanInstance instance = VulkanInstance.builder().applicationName("film").surfaces(false).build();
             VulkanDevice device = VulkanDevice.createHeadless(instance.bestGpu());
             GeometryPool pool = new GeometryPool(device, GeometryPool.Capacity.of(1 << 23, 1 << 17, 2048));
             GpuScene gpu = new GpuScene(device, pool, DisplacementBenchmark.PATCHES * DisplacementBenchmark.PATCHES
                 + 8);
             MeshletRenderer renderer = MeshletBenchmark.withView(new MeshletRenderer(device, gpu,
                 VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_D32_SFLOAT), 80_000_000);
             Materials materials = new Materials(device, 8);
             Lights lights = new Lights(device, 4, 8)) {
            RayTracing rays = device.rayTracing() ? new RayTracing(device, pool, 1024) : null;
            MeshData patchMesh = DisplacementBenchmark.patch(7);
            int patch = pool.add(patchMesh);
            MeshData groundMesh = MeshletRendererTest.grid(1, 2, false, VertexFormat.of(VertexAttribute.NORMAL));
            int ground = pool.add(groundMesh);
            if (rays != null) {
                rays.add(patch, patchMesh, 0);
                rays.add(ground, groundMesh, 0);
            }
            materials.register(Grass.TYPE);
            int soil = materials.add(new Material(MetallicRoughnessParameters.DEFAULTS
                .withBaseColor(new Color(0.35f, 0.26f, 0.15f, 1)).withRoughness(0.9f)));
            GrassParameters blade = GrassParameters.DEFAULTS;
            int grass = materials.add(DisplacementBenchmark.grass(blade));
            gpu.setMaterials(materials);
            float extent = DisplacementBenchmark.PATCHES * DisplacementBenchmark.PATCH;
            gpu.set(0, ground, soil, 0, 0, 0, 0, 0, 0, 1, extent / 2, 1, extent / 2);
            DisplacementBenchmark.Field field = new DisplacementBenchmark.Field(device, gpu, pool, rays, patch,
                patchMesh, grass, grass);
            Environment sky = Environment.uniform("sky", 0.35f, 0.45f, 0.6f);
            try {
                // 1. The field waving, the camera walking through it (RASTER, cascaded shadows)
                field.as(Displacement.RASTER);
                lights.directional(0, 0.35f, -0.75f, 0.55f, 3, 3, 2.7f).setCount(1);
                Path3 walk = f -> new double[] {-2 + 0.03 * f, 1.1, 18 - 0.12 * f, 0.15 + 0.002 * f, -0.28, -1};
                film(device, renderer, lights, null, sky, walk, 240, 1280, 720, ShadowSpec.cascades(4, 2048, 40),
                    false, out.resolve("flythrough"));
                // Popping at bounds: the same frames with the blades' bounds grown far past their reach (10 against
                // 0.51) must be the same image: nothing was culled that the displacement brought into view
                materials.set(grass, new Material(blade).doubleSided(true).displacement(10));
                film(device, renderer, lights, null, sky, walk, 60, 1280, 720, ShadowSpec.cascades(4, 2048, 40),
                    false, out.resolve("flythrough-wide"));
                long differing = 0;
                for (int f = 0; f < 60; f++) {
                    String name = String.format(Locale.ROOT, "f%04d.png", f);
                    BufferedImage a = ImageIO.read(out.resolve("flythrough").resolve(name).toFile());
                    BufferedImage b = ImageIO.read(out.resolve("flythrough-wide").resolve(name).toFile());
                    for (int y = 0; y < a.getHeight(); y++) {
                        for (int x = 0; x < a.getWidth(); x++) {
                            differing += a.getRGB(x, y) != b.getRGB(x, y) ? 1 : 0;
                        }
                    }
                }
                System.out.printf(Locale.ROOT, "bounds check: %,d pixels of 60 frames differ with bounds grown to 10%n",
                    differing);
                materials.set(grass, DisplacementBenchmark.grass(blade));
                // 3. The blades moving with the wind beside their motion vectors (camera still)
                lights.directional(0, 0.6f, -0.35f, 0.7f, 3, 3, 2.7f).setCount(1);
                Path3 still = f -> new double[] {0, 1.4, 4, 0, -0.9, -1};
                film(device, renderer, lights, null, sky, still, 180, 640, 720, ShadowSpec.cascades(4, 2048, 20),
                    true, out.resolve("motion"));
            } finally {
                field.as(null);
                device.waitIdle();
                gpu.setMaterials(null);
                if (rays != null) {
                    rays.close();
                }
            }
        }
    }

    /**
     * The grass field through changing weather, the camera moving low through it (RASTER, cascaded shadows, a low sun):
     * calm (each blade at its own rest posture), a breeze, strong gusts rolling across the field, calm again; 18 s.
     * At 1280 x 720 into {@code TRUESIM_FILM/weather}, and the same shot at 640 x 720 into {@code weather-narrow} (one
     * half of a before and after side by side).
     */
    @Test
    void grassInChangingWeather() throws Exception {
        Path out = Path.of(System.getenv("TRUESIM_FILM"));
        try (VulkanInstance instance = VulkanInstance.builder().applicationName("film").surfaces(false).build();
             VulkanDevice device = VulkanDevice.createHeadless(instance.bestGpu());
             GeometryPool pool = new GeometryPool(device, GeometryPool.Capacity.of(1 << 23, 1 << 17, 2048));
             GpuScene gpu = new GpuScene(device, pool, DisplacementBenchmark.PATCHES * DisplacementBenchmark.PATCHES
                 + 8);
             MeshletRenderer renderer = MeshletBenchmark.withView(new MeshletRenderer(device, gpu,
                 VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_D32_SFLOAT), 80_000_000);
             Materials materials = new Materials(device, 8);
             Lights lights = new Lights(device, 4, 8)) {
            MeshData patchMesh = DisplacementBenchmark.patch(7);
            int patch = pool.add(patchMesh);
            int ground = pool.add(MeshletRendererTest.grid(1, 2, false, VertexFormat.of(VertexAttribute.NORMAL)));
            materials.register(Grass.TYPE);
            int soil = materials.add(new Material(MetallicRoughnessParameters.DEFAULTS
                .withBaseColor(new Color(0.35f, 0.26f, 0.15f, 1)).withRoughness(0.9f)));
            int grass = materials.add(DisplacementBenchmark.grass(GrassParameters.DEFAULTS));
            gpu.setMaterials(materials);
            float extent = DisplacementBenchmark.PATCHES * DisplacementBenchmark.PATCH;
            gpu.set(0, ground, soil, 0, 0, 0, 0, 0, 0, 1, extent / 2, 1, extent / 2);
            DisplacementBenchmark.Field field = new DisplacementBenchmark.Field(device, gpu, pool, null, patch,
                patchMesh, grass, grass);
            Environment sky = Environment.uniform("sky", 0.35f, 0.45f, 0.6f);
            try {
                field.as(Displacement.RASTER);
                lights.directional(0, 0.45f, -0.5f, 0.75f, 3, 3, 2.7f).setCount(1);
                int frames = 18 * FPS;
                Path3 low = f -> {
                    double t = f / (double) FPS;
                    return new double[] {-3 + 0.8 * Math.sin(t * 0.25), 0.75, 10 - 0.45 * t,
                        0.25 * Math.sin(t * 0.2 + 0.4), -0.2, -1};
                };
                IntFunction<TestWindParameters> weather = f -> DisplacementBenchmark.wind(f / (float) FPS,
                    weather(f / (float) FPS));
                String only = System.getenv("TRUESIM_FILM_ONLY"); // "narrow": the side by side's half alone
                if (!"narrow".equals(only)) {
                    film(device, renderer, lights, null, sky, low, frames, 1280, 720,
                        ShadowSpec.cascades(4, 2048, 40), false, out.resolve("weather"), weather);
                }
                film(device, renderer, lights, null, sky, low, frames, 640, 720, ShadowSpec.cascades(4, 2048, 40),
                    false, out.resolve("weather-narrow"), weather);
            } finally {
                field.as(null);
                device.waitIdle();
                gpu.setMaterials(null);
            }
        }
    }

    /** The wind's strength at {@code t} s: calm 3 s, a breeze (0.55) by 5 s, strong gusts (1.35) 10 to 14 s, calm. */
    private static float weather(float t) {
        if (t < 3) {
            return 0;
        } else if (t < 5) {
            return 0.55f * smooth((t - 3) / 2);
        } else if (t < 8) {
            return 0.55f;
        } else if (t < 10) {
            return 0.55f + 0.8f * smooth((t - 8) / 2);
        } else if (t < 14) {
            return 1.35f;
        } else if (t < 16) {
            return 1.35f * (1 - smooth((t - 14) / 2));
        }
        return 0;
    }

    private static float smooth(float x) {
        return x * x * (3 - 2 * x);
    }

    /**
     * Swinging boards (the test type wave, upright 2 x 2 quads swung 1.2 along x by the wind's sine) over a floor, a low
     * sun: by ray-traced shadows displaced in the stages (rays meet the boards still: their shadows stand) and into
     * copies (the shadows swing with them); by shadow maps the camera circling.
     */
    @Test
    void swingingBoards() throws Exception {
        Path out = Path.of(System.getenv("TRUESIM_FILM"));
        try (VulkanInstance instance = VulkanInstance.builder().applicationName("film").surfaces(false).build();
             VulkanDevice device = VulkanDevice.createHeadless(instance.bestGpu());
             GeometryPool pool = new GeometryPool(device, GeometryPool.Capacity.of(1 << 14, 256, 64));
             GpuScene gpu = new GpuScene(device, pool, 32);
             MeshletRenderer renderer = new MeshletRenderer(device, gpu, VK_FORMAT_R8G8B8A8_UNORM,
                 VK_FORMAT_D32_SFLOAT);
             Materials materials = new Materials(device, 8);
             Lights lights = new Lights(device, 4, 8)) {
            RayTracing rays = device.rayTracing() ? new RayTracing(device, pool, 64) : null;
            MeshData quadMesh = MeshletRendererTest.grid(4, 0.5f, false, VertexFormat.of(VertexAttribute.NORMAL));
            int quad = pool.add(quadMesh);
            if (rays != null) {
                rays.add(quad, quadMesh, 0);
            }
            materials.register(com.truesim.engine.render.materials.Wave.TYPE);
            int floor = materials.add(new Material(MetallicRoughnessParameters.DEFAULTS
                .withBaseColor(new Color(0.8f, 0.8f, 0.75f, 1)).withRoughness(0.8f)));
            int board = materials.add(new Material(com.truesim.engine.render.materials.WaveParameters.DEFAULTS
                .withColor(new Color(0.9f, 0.3f, 0.05f, 1))
                .withSwing(new com.truesim.common.math.Vec3(1.2, 0, 0))).doubleSided(true).displacement(1.25f));
            gpu.setMaterials(materials);
            gpu.set(0, quad, floor, 0, 0, 0, 0, 0, 0, 1, 8, 1, 8);
            int[] copies = new int[6];
            for (int i = 0; i < copies.length; i++) {
                copies[i] = pool.addDeformed(quad);
                pool.deformation().displace(copies[i]);
                if (rays != null) {
                    rays.addDeformed(copies[i], quadMesh, 0);
                }
            }
            Environment sky = Environment.uniform("sky", 0.35f, 0.45f, 0.6f);
            lights.directional(0, -0.25f, -0.45f, -0.85f, 3, 3, 2.7f).setCount(1);
            try {
                java.util.function.Consumer<Boolean> boards = compute -> {
                    for (int i = 0; i < copies.length; i++) { // upright (turned about x), facing +z, in two rows
                        double x = (i % 3 - 1) * 4.5;
                        double z = (i / 3) * 5 - 3;
                        gpu.set(1 + i, compute ? copies[i] : quad, board, x, 1.2, z, (float) Math.sqrt(0.5), 0, 0,
                            (float) Math.sqrt(0.5), 1, 1, 1);
                    }
                };
                Path3 view = f -> new double[] {0, 7, 13, 0, -0.55, -1};
                if (rays != null) {
                    boards.accept(false);
                    film(device, renderer, lights, rays, sky, view, 150, 640, 720, ShadowSpec.rayTraced(), false,
                        out.resolve("boards-rays-raster"), 2.5f);
                    boards.accept(true);
                    film(device, renderer, lights, rays, sky, view, 150, 640, 720, ShadowSpec.rayTraced(), false,
                        out.resolve("boards-rays-compute"), 2.5f);
                }
                boards.accept(false);
                Path3 circle = f -> {
                    double a = f * 0.01;
                    return new double[] {13 * Math.sin(a), 7, 13 * Math.cos(a), -Math.sin(a), -0.55, -Math.cos(a)};
                };
                film(device, renderer, lights, null, sky, circle, 180, 640, 720, ShadowSpec.cascades(4, 2048, 30),
                    true, out.resolve("boards-maps"), 2.5f);
            } finally {
                device.waitIdle();
                gpu.setMaterials(null);
                if (rays != null) {
                    rays.close();
                }
            }
        }
    }

    /**
     * Renders {@code frames} frames of {@code width} x {@code height} along {@code path}, the wind's time at 1 / 30 s a
     * frame, into {@code dir}; with {@code motion} also the motion vectors (hue by direction, brightness by length) and
     * the error of the frame before reprojected by them, printed against the error of no reprojection.
     */
    private static void film(VulkanDevice device, MeshletRenderer renderer, Lights lights, RayTracing rays,
        Environment sky, Path3 path, int frames, int width, int height, ShadowSpec spec, boolean motion, Path dir)
        throws Exception {
        film(device, renderer, lights, rays, sky, path, frames, width, height, spec, motion, dir, 1);
    }

    /** As above, the wind's time running {@code speed} times as fast. */
    private static void film(VulkanDevice device, MeshletRenderer renderer, Lights lights, RayTracing rays,
        Environment sky, Path3 path, int frames, int width, int height, ShadowSpec spec, boolean motion, Path dir,
        float speed) throws Exception {
        film(device, renderer, lights, rays, sky, path, frames, width, height, spec, motion, dir,
            f -> DisplacementBenchmark.wind(speed * f / (float) FPS));
    }

    /** As above, the wind of frame {@code f} given by {@code wind}. */
    private static void film(VulkanDevice device, MeshletRenderer renderer, Lights lights, RayTracing rays,
        Environment sky, Path3 path, int frames, int width, int height, ShadowSpec spec, boolean motion, Path dir,
        IntFunction<TestWindParameters> wind) throws Exception {
        Files.createDirectories(dir);
        lights.shadow(0, spec);
        int usage = VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
        try (GpuImage color = new GpuImage(device, width, height, VK_FORMAT_R8G8B8A8_UNORM, usage,
                 VK_IMAGE_ASPECT_COLOR_BIT);
             GpuImage vectors = new GpuImage(device, width, height, VK_FORMAT_R16G16_SFLOAT, usage,
                 VK_IMAGE_ASPECT_COLOR_BIT);
             GpuBuffer colorBack = GpuBuffer.hostVisible(device, 4L * width * height, VK_BUFFER_USAGE_TRANSFER_DST_BIT);
             GpuBuffer motionBack = GpuBuffer.hostVisible(device, 4L * width * height,
                 VK_BUFFER_USAGE_TRANSFER_DST_BIT);
             Shadows shadows = new Shadows(SceneGeometry.meshlets(renderer), lights).rayTracing(rays);
             FrameGraph graph = new FrameGraph(device, 2)) {
            Camera camera = new Camera();
            RenderView view = renderer.createView(ViewOptions.of(1 << 20, 1 << 24).withOcclusion(true));
            SceneView main = new SceneView(graph.addView("main").size(width, height), view, camera);
            SceneGeometry geometry = SceneGeometry.meshlets(renderer);
            main.port().add(new VisibilityBufferShading(geometry, main, VK_FORMAT_R8G8B8A8_UNORM,
                VK_FORMAT_D32_SFLOAT)).add(new ClusteredLights(lights, main)).add(shadows.view(main))
                .add(new EnvironmentLighting(sky, main)).add(new SkyBackground(sky, main))
                .output(Keys.SCENE_COLOR, new ExternalImage("color", width, height, Format.R8G8B8A8_UNORM, usage,
                    VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL).set(color.handle(), color.view()));
            if (motion) {
                main.port().add(new MotionVectors(geometry, view, main, VK_FORMAT_D32_SFLOAT))
                    .output(Keys.MOTION, new ExternalImage("motion", width, height, Format.R16G16_SFLOAT, usage,
                        VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL)
                        .set(vectors.handle(), vectors.view()));
            }
            GeometryHooks hooks = renderer.scene().geometryHooks();
            int[] previous = null;
            double reprojected = 0;
            double unprojected = 0;
            long counted = 0;
            for (int f = -8; f < frames; f++) { // 8 frames to settle (late phase, tiles, history)
                double[] c = path.at(Math.max(f, 0));
                camera.position(c[0], c[1], c[2]).set(c[3], c[4], c[5], 0, 1, 0, Math.PI / 3, width, height, 0.05);
                if (f == -8) {
                    view.set(camera, 1, MeshletRenderer.MODE_LIT); // once: read again as frames cull it
                }
                hooks.set("test.wind", wind.apply(Math.max(f, 0)));
                device.submitAndWait(device.graphics(), commands -> {
                    graph.record(commands);
                    copy(commands, color, colorBack, width, height);
                    if (motion) {
                        copy(commands, vectors, motionBack, width, height);
                    }
                });
                if (f < 0) {
                    continue;
                }
                int[] rgba = new int[width * height];
                for (int i = 0; i < rgba.length; i++) {
                    rgba[i] = colorBack.memory().getAtIndex(ValueLayout.JAVA_INT, i);
                }
                BufferedImage image = new BufferedImage(motion ? 2 * width : width, height, BufferedImage.TYPE_INT_RGB);
                for (int y = 0; y < height; y++) {
                    for (int x = 0; x < width; x++) {
                        image.setRGB(x, y, rgb(rgba[y * width + x]));
                    }
                }
                if (motion) {
                    for (int y = 0; y < height; y++) {
                        for (int x = 0; x < width; x++) {
                            int p = y * width + x;
                            float mx = Float.float16ToFloat(motionBack.memory().getAtIndex(ValueLayout.JAVA_SHORT,
                                2L * p));
                            float my = Float.float16ToFloat(motionBack.memory().getAtIndex(ValueLayout.JAVA_SHORT,
                                2L * p + 1));
                            image.setRGB(width + x, y, motionColor(mx, my));
                            if (previous != null) { // the frame before at where the vectors say this was
                                int px = Math.round(x + mx);
                                int py = Math.round(y + my);
                                if (px >= 0 && py >= 0 && px < width && py < height) {
                                    reprojected += difference(rgba[p], previous[py * width + px]);
                                    unprojected += difference(rgba[p], previous[p]);
                                    counted++;
                                }
                            }
                        }
                    }
                    previous = rgba;
                }
                ImageIO.write(image, "png", dir.resolve(String.format(Locale.ROOT, "f%04d.png", f)).toFile());
            }
            if (motion) {
                System.out.printf(Locale.ROOT, "%s: mean colour difference to the frame before, reprojected by the "
                    + "motion vectors %.3f, not reprojected %.3f (0-255 a channel)%n", dir.getFileName(),
                    reprojected / counted, unprojected / counted);
            }
            device.waitIdle();
            view.close();
        }
    }

    private static void copy(org.lwjgl.vulkan.VkCommandBuffer commands, GpuImage image, GpuBuffer buffer, int width,
        int height) {
        try (MemoryStack stack = stackPush()) {
            VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
            region.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
            region.imageExtent().set(width, height, 1);
            vkCmdCopyImageToBuffer(commands, image.handle(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, buffer.handle(),
                region);
        }
    }

    private static int rgb(int p) {
        return (p & 0xff) << 16 | ((p >> 8) & 0xff) << 8 | ((p >> 16) & 0xff);
    }

    private static double difference(int a, int b) {
        return (Math.abs((a & 0xff) - (b & 0xff)) + Math.abs(((a >> 8) & 0xff) - ((b >> 8) & 0xff))
            + Math.abs(((a >> 16) & 0xff) - ((b >> 16) & 0xff))) / 3.0;
    }

    /** A motion vector as a colour: hue by direction, brightness by length (8 pixels: full). */
    private static int motionColor(float mx, float my) {
        double length = Math.sqrt(mx * mx + my * my);
        float hue = (float) ((Math.atan2(my, mx) / (2 * Math.PI) + 1) % 1);
        return java.awt.Color.HSBtoRGB(hue, 0.8f, (float) Math.min(1, length / 8));
    }
}
