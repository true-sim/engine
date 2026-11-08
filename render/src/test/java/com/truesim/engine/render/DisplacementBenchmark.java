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
import static org.lwjgl.vulkan.VK10.VK_QUERY_RESULT_64_BIT;
import static org.lwjgl.vulkan.VK10.VK_QUERY_RESULT_WAIT_BIT;
import static org.lwjgl.vulkan.VK10.VK_QUERY_TYPE_TIMESTAMP;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.vkCmdCopyImageToBuffer;
import static org.lwjgl.vulkan.VK10.vkCmdResetQueryPool;
import static org.lwjgl.vulkan.VK10.vkCreateQueryPool;
import static org.lwjgl.vulkan.VK10.vkDestroyQueryPool;
import static org.lwjgl.vulkan.VK10.vkGetPhysicalDeviceProperties;
import static org.lwjgl.vulkan.VK10.vkGetQueryPoolResults;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT;
import static org.lwjgl.vulkan.VK13.vkCmdWriteTimestamp2;

import com.truesim.common.math.Vec3;
import com.truesim.common.math.color.Color;
import com.truesim.engine.graph.ExternalImage;
import com.truesim.engine.graph.FrameGraph;
import com.truesim.engine.graph.GpuTimings;
import com.truesim.engine.render.materials.Grass;
import com.truesim.engine.render.materials.GrassParameters;
import com.truesim.engine.render.materials.MetallicRoughnessParameters;
import com.truesim.engine.render.materials.TestWindParameters;
import com.truesim.engine.render.shadows.Shadows;
import com.truesim.engine.vulkan.Format;
import com.truesim.engine.vulkan.GpuBuffer;
import com.truesim.engine.vulkan.GpuImage;
import com.truesim.engine.vulkan.GpuMemory;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.engine.vulkan.VulkanInstance;
import com.truesim.scene.assets.mesh.MeshData;
import com.truesim.scene.assets.mesh.VertexAttribute;
import com.truesim.scene.assets.mesh.VertexFormat;
import com.truesim.scene.core.ShadowSpec;
import com.truesim.scene.meshbuild.MeshBuilder;
import java.awt.image.BufferedImage;
import java.lang.foreign.ValueLayout;
import java.nio.LongBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.Random;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;

/**
 * A grass field bent by the wind ({@code grass}, test/grass.slang, reading the test geometry hook {@code test.wind}
 * each frame): {@link #PATCHES} x {@link #PATCHES} patches of {@link #BLADES} x {@link #BLADES} blades (8 triangles
 * each) over a ground plane, the sun's shadows by cascades or by rays, shaded by the visibility buffer at 1920 x 1080.
 * The displacement in the vertex stages of every pass ({@link Displacement#RASTER}) against once a frame into a copy
 * per patch ({@link Displacement#COMPUTE}, the copies' bottom levels refitted each frame), and the same field still (a
 * metallic-roughness material, no displacement) for reference; with ray traced shadows and without. GPU time of whole
 * frames (timestamps around batches of 8), configurations taking turns round after round, 3 uncounted rounds first
 * (pitfalls.md); GPU memory of meshes and acceleration structures after each configuration stands. Run with
 * {@code TRUESIM_BENCHMARK=true ./gradlew :engine-render:test --tests '*DisplacementBenchmark'}; with
 * {@code TRUESIM_SHOTS=<directory>} it also writes PNGs of the field there.
 */
@EnabledIfEnvironmentVariable(named = "TRUESIM_BENCHMARK", matches = "true")
class DisplacementBenchmark {

    private static final int WIDTH = 1920;
    private static final int HEIGHT = 1080;
    static final int PATCHES = 24;
    static final int BLADES = 32;
    static final float PATCH = 2; // metres a side
    private static final int ROUNDS = 5;
    private static final int BATCHES = 12;
    private static final int BATCH = 8;

    private record Configuration(String name, Displacement mode, boolean rays) {
    }

    private static final Configuration[] CONFIGURATIONS = {
        new Configuration("still grass (no displacement), maps", null, false),
        new Configuration("RASTER, maps", Displacement.RASTER, false),
        new Configuration("COMPUTE, maps", Displacement.COMPUTE, false),
        new Configuration("still grass (no displacement), rays", null, true),
        new Configuration("RASTER, rays", Displacement.RASTER, true),
        new Configuration("COMPUTE, rays", Displacement.COMPUTE, true),
    };

    /** A patch of blades: each 4 segments up (10 vertices, 8 triangles), v the height on the blade, normals sideways. */
    static MeshData patch(long seed) {
        Random random = new Random(seed);
        int blades = BLADES * BLADES;
        int per = 10;
        float[] positions = new float[3 * per * blades];
        float[] normals = new float[3 * per * blades];
        float[] uvs = new float[2 * per * blades];
        int[] indices = new int[24 * blades];
        for (int b = 0; b < blades; b++) {
            float x = ((b % BLADES) + random.nextFloat()) / BLADES * PATCH - PATCH / 2;
            float z = ((b / BLADES) + random.nextFloat()) / BLADES * PATCH - PATCH / 2;
            double angle = random.nextDouble() * Math.PI;
            float dx = (float) Math.cos(angle) * 0.02f;
            float dz = (float) Math.sin(angle) * 0.02f;
            float height = 0.35f + 0.3f * random.nextFloat();
            for (int s = 0; s <= 4; s++) {
                float v = s / 4f;
                float width = 1 - 0.8f * v; // narrowing to the tip
                for (int side = 0; side < 2; side++) {
                    int vertex = b * per + 2 * s + side;
                    float sign = side == 0 ? -1 : 1;
                    positions[3 * vertex] = x + sign * dx * width;
                    positions[3 * vertex + 1] = height * v;
                    positions[3 * vertex + 2] = z + sign * dz * width;
                    normals[3 * vertex] = (float) -Math.sin(angle);
                    normals[3 * vertex + 2] = (float) Math.cos(angle);
                    uvs[2 * vertex] = side;
                    uvs[2 * vertex + 1] = v;
                }
            }
            for (int s = 0; s < 4; s++) {
                int a = b * per + 2 * s;
                int[] quad = {a, a + 1, a + 2, a + 1, a + 3, a + 2};
                System.arraycopy(quad, 0, indices, 24 * b + 6 * s, 6);
            }
        }
        VertexFormat format = VertexFormat.of(VertexAttribute.NORMAL, VertexAttribute.UV0);
        return new MeshBuilder(format, per * blades).positions(positions).attribute(VertexAttribute.NORMAL, normals)
            .attribute(VertexAttribute.UV0, uvs).submesh(indices).buildFlat();
    }

    /** The wind at {@code time} seconds. */
    static TestWindParameters wind(float time) {
        return TestWindParameters.DEFAULTS.withDirection(new Vec3(0.8, 0, 0.6)).withStrength(1).withTime(time)
            .withFrequency(0.4f).withWavelength(9);
    }

    @Test
    void grassFieldInBothModesWithAndWithoutRays() throws Exception {
        try (VulkanInstance instance = VulkanInstance.builder().applicationName("benchmark").surfaces(false).build();
             VulkanDevice device = VulkanDevice.createHeadless(instance.bestGpu());
             GeometryPool pool = new GeometryPool(device, GeometryPool.Capacity.of(1 << 23, 1 << 17, 2048));
             GpuScene gpu = new GpuScene(device, pool, PATCHES * PATCHES + 8);
             MeshletRenderer renderer = MeshletBenchmark.withView(new MeshletRenderer(device, gpu,
                 VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_D32_SFLOAT), 80_000_000);
             Materials materials = new Materials(device, 8);
             Lights lights = new Lights(device, 4, 8);
             GpuImage shaded = new GpuImage(device, WIDTH, HEIGHT, VK_FORMAT_R8G8B8A8_UNORM,
                 VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT, VK_IMAGE_ASPECT_COLOR_BIT);
             GpuBuffer pixels = GpuBuffer.hostVisible(device, 4L * WIDTH * HEIGHT, VK_BUFFER_USAGE_TRANSFER_DST_BIT);
             MemoryStack stack = stackPush()) {
            boolean rayTracing = device.rayTracing();
            RayTracing rays = rayTracing ? new RayTracing(device, pool, PATCHES * PATCHES + 8) : null;
            MeshData patchMesh = patch(7);
            int patch = pool.add(patchMesh);
            MeshData groundMesh = MeshletRendererTest.grid(1, 2, false, VertexFormat.of(VertexAttribute.NORMAL));
            int ground = pool.add(groundMesh);
            if (rays != null) {
                rays.add(patch, patchMesh, 0);
                rays.add(ground, groundMesh, 0);
            }
            materials.register(Grass.TYPE);
            int soil = materials.add(new Material(MetallicRoughnessParameters.DEFAULTS
                .withBaseColor(new Color(0.25f, 0.18f, 0.1f, 1)).withRoughness(0.9f)));
            GrassParameters blade = GrassParameters.DEFAULTS;
            int grass = materials.add(new Material(blade).doubleSided(true).displacement(blade.bend() + 0.01f));
            int still = materials.add(new Material(MetallicRoughnessParameters.DEFAULTS
                .withBaseColor(new Color(0.2f, 0.4f, 0.08f, 1)).withRoughness(0.8f)).doubleSided(true));
            gpu.setMaterials(materials);
            float extent = PATCHES * PATCH;
            gpu.set(0, ground, soil, 0, 0, 0, 0, 0, 0, 1, extent / 2, 1, extent / 2);
            lights.directional(0, 0.35f, -0.8f, 0.45f, 3, 3, 2.7f).setCount(1);
            Camera camera = new Camera().position(0, 1.7, extent / 2 - 2)
                .set(0, -0.3, -1, 0, 1, 0, Math.PI / 3, WIDTH, HEIGHT, 0.05);
            RenderView view = MeshletBenchmark.mainView(renderer);
            view.set(camera, 1, MeshletRenderer.MODE_LIT);
            ExternalImage target = new ExternalImage("shaded", WIDTH, HEIGHT, Format.R8G8B8A8_UNORM,
                VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT, VK_IMAGE_LAYOUT_UNDEFINED,
                VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL).set(shaded.handle(), shaded.view());
            VkPhysicalDeviceProperties properties = VkPhysicalDeviceProperties.malloc(stack);
            vkGetPhysicalDeviceProperties(device.gpu().handle(), properties);
            double period = properties.limits().timestampPeriod();
            LongBuffer handle = stack.mallocLong(1);
            vkCreateQueryPool(device.handle(), org.lwjgl.vulkan.VkQueryPoolCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO).queryType(VK_QUERY_TYPE_TIMESTAMP).queryCount(2),
                null, handle);
            long queries = handle.get(0);
            Field field = new Field(device, gpu, pool, rays, patch, patchMesh, grass, still);
            String shots = System.getenv("TRUESIM_SHOTS");
            try {
                System.out.printf(Locale.ROOT, "Grass field on %s, %d x %d: %d patches of %d blades, %,d triangles; "
                    + "ray tracing %s%n", device.gpu().name(), WIDTH, HEIGHT, PATCHES * PATCHES, BLADES * BLADES,
                    (long) PATCHES * PATCHES * BLADES * BLADES * 8, rayTracing);
                double[][] ms = new double[CONFIGURATIONS.length][ROUNDS];
                long[][] memory = new long[CONFIGURATIONS.length][];
                for (int round = -3; round < ROUNDS; round++) {
                    for (int c = 0; c < CONFIGURATIONS.length; c++) {
                        Configuration configuration = CONFIGURATIONS[c];
                        if (configuration.rays() && rays == null) {
                            continue;
                        }
                        field.as(configuration.mode());
                        double frame = measure(device, renderer, view, camera, lights, rays, target, configuration,
                            queries, period);
                        if (round >= 0) {
                            ms[c][round] = frame;
                        }
                        if (memory[c] == null) {
                            device.waitIdle();
                            memory[c] = new long[] {GpuMemory.bytes(GpuMemory.Category.MESHES),
                                GpuMemory.bytes(GpuMemory.Category.ACCELERATION), GpuMemory.total(),
                                pool.vertexCount()};
                        }
                        System.out.printf(Locale.ROOT, "  round %d, %s: %.3f ms GPU%n", round, configuration.name(),
                            frame);
                        if (shots != null && round == ROUNDS - 1 && configuration.mode() != null) {
                            save(device, shaded, pixels, Path.of(shots, "grass-" + configuration.mode().name()
                                .toLowerCase(Locale.ROOT) + (configuration.rays() ? "-rays" : "-maps") + ".png"));
                        }
                    }
                }
                System.out.println("| configuration | GPU per frame (ranges of the rounds' medians) | meshes | "
                    + "acceleration structures | all GPU memory | pool vertices |");
                for (int c = 0; c < CONFIGURATIONS.length; c++) {
                    if (memory[c] == null) {
                        continue;
                    }
                    double[] all = ms[c].clone();
                    Arrays.sort(all);
                    System.out.printf(Locale.ROOT, "| %s | %.3f-%.3f ms | %.1f MiB | %.1f MiB | %.1f MiB | %,d |%n",
                        CONFIGURATIONS[c].name(), all[0], all[all.length - 1], memory[c][0] / 1048576.0,
                        memory[c][1] / 1048576.0, memory[c][2] / 1048576.0, memory[c][3]);
                }
                long copyBytes = (long) patchMesh.vertexCount() * 16 + (long) patchMesh.meshletCount() * (32 + 40);
                System.out.printf(Locale.ROOT, "A patch: %,d vertices, %,d meshlets; a copy's own vertices, attributes "
                    + "and meshlet records about %.2f MiB, %d copies %.1f MiB%n", patchMesh.vertexCount(),
                    patchMesh.meshletCount(), copyBytes / 1048576.0, PATCHES * PATCHES,
                    copyBytes * PATCHES * PATCHES / 1048576.0);
            } finally {
                field.as(null);
                device.waitIdle();
                gpu.setMaterials(null);
                vkDestroyQueryPool(device.handle(), queries, null);
                if (rays != null) {
                    rays.close();
                }
            }
        }
    }

    /** The patches as a mode draws them: still (null), displaced where drawn, or each its own displaced copy. */
    static final class Field {
        private final VulkanDevice device;
        private final GpuScene gpu;
        private final GeometryPool pool;
        private final RayTracing rays;
        private final int patch;
        private final MeshData mesh;
        private final int grass;
        private final int still;
        private final int[] copies = new int[PATCHES * PATCHES];
        private boolean copied;

        Field(VulkanDevice device, GpuScene gpu, GeometryPool pool, RayTracing rays, int patch, MeshData mesh,
            int grass, int still) {
            this.device = device;
            this.gpu = gpu;
            this.pool = pool;
            this.rays = rays;
            this.patch = patch;
            this.mesh = mesh;
            this.grass = grass;
            this.still = still;
        }

        /** Draws the patches as {@code mode} says (null: still). */
        void as(Displacement mode) {
            if (mode == Displacement.COMPUTE && !copied) {
                for (int i = 0; i < copies.length; i++) {
                    copies[i] = pool.addDeformed(patch);
                    pool.deformation().displace(copies[i]);
                    if (rays != null) {
                        rays.addDeformed(copies[i], mesh, 0);
                    }
                }
                copied = true;
            }
            for (int i = 0; i < copies.length; i++) {
                double x = (i % PATCHES + 0.5) * PATCH - PATCHES * PATCH / 2;
                double z = (i / PATCHES + 0.5) * PATCH - PATCHES * PATCH / 2;
                int mesh = mode == Displacement.COMPUTE ? copies[i] : patch;
                gpu.set(1 + i, mesh, mode == null ? still : grass, x, 0, z, 0, 0, 0, 1, 1, 1, 1);
            }
            if (mode != Displacement.COMPUTE && copied) {
                device.waitIdle(); // no frame in flight draws the copies any more
                for (int copy : copies) {
                    pool.remove(copy);
                }
                copied = false;
            }
        }
    }

    /** The median GPU time of a frame of {@code configuration}, the wind's time moving on each frame. */
    private static double measure(VulkanDevice device, MeshletRenderer renderer, RenderView view, Camera camera,
        Lights lights, RayTracing rays, ExternalImage target, Configuration configuration, long queries,
        double period) {
        lights.shadow(0, configuration.rays() ? ShadowSpec.rayTraced() : ShadowSpec.cascades(4, 2048, 60));
        try (Shadows shadows = new Shadows(SceneGeometry.meshlets(renderer), lights).rayTracing(rays);
             FrameGraph graph = new FrameGraph(device, 2)) { // the graph closes first, then the owner
            SceneView main = new SceneView(graph.addView("main").size(WIDTH, HEIGHT), view, camera);
            SceneGeometry geometry = SceneGeometry.meshlets(renderer);
            main.port().add(new VisibilityBufferShading(geometry, main, VK_FORMAT_R8G8B8A8_UNORM,
                VK_FORMAT_D32_SFLOAT)).add(new ClusteredLights(lights, main)).add(shadows.view(main))
                .output(Keys.SCENE_COLOR, target);
            GeometryHooks hooks = renderer.scene().geometryHooks();
            float[] time = {0};
            for (int i = 0; i < 16; i++) {
                hooks.set("test.wind", wind(time[0] += 1 / 60f));
                device.submitAndWait(device.graphics(), graph::record);
            }
            double[] gpu = new double[BATCHES];
            for (int batch = 0; batch < BATCHES; batch++) {
                device.submitAndWait(device.graphics(), commands -> {
                    vkCmdResetQueryPool(commands, queries, 0, 2);
                    vkCmdWriteTimestamp2(commands, VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, queries, 0);
                    for (int i = 0; i < BATCH; i++) {
                        hooks.set("test.wind", wind(time[0] += 1 / 60f));
                        graph.record(commands);
                    }
                    vkCmdWriteTimestamp2(commands, VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, queries, 1);
                });
                try (MemoryStack stack = stackPush()) {
                    LongBuffer stamps = stack.mallocLong(2);
                    vkGetQueryPoolResults(device.handle(), queries, 0, 2, stamps, 8,
                        VK_QUERY_RESULT_64_BIT | VK_QUERY_RESULT_WAIT_BIT);
                    gpu[batch] = (stamps.get(1) - stamps.get(0)) * period / 1e6 / BATCH;
                }
            }
            if (System.getenv("TRUESIM_PASSES") != null) { // where the frame's time goes, pass by pass
                graph.timings(true);
                for (int i = 0; i < 8; i++) {
                    hooks.set("test.wind", wind(time[0] += 1 / 60f));
                    device.submitAndWait(device.graphics(), graph::record);
                }
                GpuTimings timings = graph.timings();
                StringBuilder line = new StringBuilder("    passes of " + configuration.name() + ":");
                for (int i = 0; i < timings.count(); i++) {
                    if (timings.millis(i) >= 0.03) {
                        line.append(String.format(Locale.ROOT, " %s %.3f;", timings.pass(i), timings.millis(i)));
                    }
                }
                System.out.println(line);
                graph.timings(false);
            }
            if (renderer.chunksOverflowed()) {
                throw new IllegalStateException("Culling ran out of room: the frames drew less than the field");
            }
            device.waitIdle();
            Arrays.sort(gpu);
            return gpu[BATCHES / 2];
        }
    }

    private static void save(VulkanDevice device, GpuImage image, GpuBuffer pixels, Path file) throws Exception {
        device.submitAndWait(device.graphics(), commands -> {
            try (MemoryStack stack = stackPush()) {
                VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
                region.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
                region.imageExtent().set(WIDTH, HEIGHT, 1);
                vkCmdCopyImageToBuffer(commands, image.handle(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    pixels.handle(), region);
            }
        });
        BufferedImage out = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                int p = pixels.memory().getAtIndex(ValueLayout.JAVA_INT, (long) y * WIDTH + x);
                out.setRGB(x, y, (p & 0xff) << 16 | ((p >> 8) & 0xff) << 8 | ((p >> 16) & 0xff));
            }
        }
        Files.createDirectories(file.getParent());
        ImageIO.write(out, "png", file.toFile());
    }
}
