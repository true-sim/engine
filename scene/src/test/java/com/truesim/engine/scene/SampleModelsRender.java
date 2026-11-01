package com.truesim.engine.scene;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_D32_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16B16A16_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_UNDEFINED;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_STORAGE_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
import static org.lwjgl.vulkan.VK10.vkCmdCopyImageToBuffer;

import com.truesim.common.jobs.TaskScheduler;
import com.truesim.common.state.ChangeClock;
import com.truesim.engine.graph.Access;
import com.truesim.engine.graph.Anchor;
import com.truesim.engine.graph.ExternalImage;
import com.truesim.engine.graph.FrameGraph;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Image;
import com.truesim.engine.graph.Key;
import com.truesim.engine.graph.Order;
import com.truesim.engine.graph.Technique;
import com.truesim.engine.graph.ViewPort;
import com.truesim.engine.render.Camera;
import com.truesim.engine.render.Environment;
import com.truesim.engine.render.EnvironmentLighting;
import com.truesim.engine.render.SkyBackground;
import com.truesim.engine.render.TextureFormat;
import com.truesim.engine.render.ClusteredForwardShading;
import com.truesim.engine.render.ClusteredLights;
import com.truesim.engine.render.GeometryPool;
import com.truesim.engine.render.GpuScene;
import com.truesim.engine.render.Keys;
import com.truesim.engine.render.Lights;
import com.truesim.engine.render.Materials;
import com.truesim.engine.render.MeshletRenderer;
import com.truesim.engine.render.RenderView;
import com.truesim.engine.render.SceneGeometry;
import com.truesim.engine.render.SceneView;
import com.truesim.engine.render.Textures;
import com.truesim.engine.render.ViewOptions;
import com.truesim.engine.render.WeightedBlendedTransparency;
import com.truesim.engine.vulkan.FrameSlots;
import com.truesim.engine.vulkan.Format;
import com.truesim.engine.vulkan.GpuBuffer;
import com.truesim.engine.vulkan.GpuImage;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.engine.vulkan.VulkanException;
import com.truesim.engine.vulkan.VulkanInstance;
import com.truesim.scene.assets.AssetCache;
import com.truesim.scene.assets.AssetRef;
import com.truesim.scene.assets.PathAssets;
import com.truesim.scene.assets.PathSource;
import com.truesim.scene.assets.mesh.MeshData;
import com.truesim.scene.core.SceneGraph;
import com.truesim.scene.gltf.Gltf;
import com.truesim.scene.render.ImageImporter;
import com.truesim.scene.render.ModelData;
import com.truesim.scene.render.RadianceImporter;
import com.truesim.scene.render.TextureData;
import com.truesim.scene.render.RenderAssets;
import com.truesim.scene.render.Renderables;
import java.awt.image.BufferedImage;
import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import com.truesim.scene.animation.AnimationClip;
import com.truesim.scene.animation.Animators;
import com.truesim.scene.animation.ClipState;
import org.junit.jupiter.api.Test;
import org.lwjgl.vulkan.VkBufferImageCopy;

/**
 * Renders Khronos sample models (glTF-Sample-Assets' {@code Models}, given by {@code -PgltfSamples=...}) to PNG images
 * in {@code build/samples}, to compare by eye with the models' screenshots: many test models show green checks where a
 * renderer is right and red crosses where it is not. Each model is framed from the front (+z), lit by its own glTF
 * lights where it has some (KHR_lights_punctual) or else the sun, and the sky, or with {@code -PgltfEnvironment=a.hdr}
 * an HDR environment (lighting and background) besides; tone mapped with Khronos PBR Neutral. Skipped without the samples; {@code -PgltfModels=A,B} picks models.
 */
class SampleModelsRender {

    private static final int SIZE = Integer.getInteger("truesim.gltf.size", 512);

    @Test
    void sampleModelsRenderToImages() throws Exception {
        String root = System.getProperty("truesim.gltf.samples");
        assumeThat(root).as("no samples given: -PgltfSamples=<glTF-Sample-Assets/Models>").isNotNull();
        String only = System.getProperty("truesim.gltf.models");
        List<Path> files;
        try (Stream<Path> walk = Files.walk(Path.of(root))) {
            files = walk.filter(p -> p.getParent().getFileName().toString().equals("glTF"))
                .filter(p -> p.toString().endsWith(".gltf")).sorted()
                .filter(p -> only == null || List.of(only.split(",")).contains(
                    p.getParent().getParent().getFileName().toString())).toList();
        }
        VulkanInstance instance;
        try {
            instance = VulkanInstance.builder().applicationName("samples").validation(true).surfaces(false).build();
        } catch (VulkanException | UnsatisfiedLinkError e) {
            assumeThat(e).as("no Vulkan available").isNull();
            throw e;
        }
        Path out = Path.of("build", "samples");
        Files.createDirectories(out);
        try (instance; VulkanDevice device = VulkanDevice.createHeadless(instance.bestGpu())) {
            for (Path file : files) {
                render(device, file, out.resolve(file.getParent().getParent().getFileName() + ".png"));
            }
            device.waitIdle();
            assertThat(instance.validationErrors()).as("validation errors").isZero();
        }
    }

    private static void render(VulkanDevice device, Path file, Path png) throws Exception {
        FrameSlots slots = new FrameSlots(2);
        List<AutoCloseable> closing = new ArrayList<>();
        try (TaskScheduler tasks = new TaskScheduler(); AssetCache cache = new AssetCache(tasks);
             GeometryPool pool = new GeometryPool(device, GeometryPool.Capacity.of(1 << 21, 1 << 16, 1 << 11), slots);
             Materials materials = new Materials(device, 1024, slots);
             Textures textures = new Textures(device, slots);
             SceneResources resources = new SceneResources(cache, pool, materials, textures)) {
          try {
            PathAssets paths = new PathAssets(cache);
            paths.mounts().add("s", PathSource.folder(file.getParent()));
            new Gltf(paths).register();
            for (String extension : List.of("png", "jpg", "jpeg", "webp")) {
                paths.importers().add(RenderAssets.TEXTURE, extension, ImageImporter.INSTANCE);
            }
            AssetRef<ModelData> ref = paths.ref(RenderAssets.MODEL, "s://" + file.getFileName());
            cache.acquire(ref);
            ModelData model = await(tasks, cache, ref);
            double[][] world = worldMatrices(model);
            double[] bounds = bounds(model, world, tasks, cache);
            SceneGraph scene = new SceneGraph(new ChangeClock());
            Renderables renderables = new Renderables(scene);
            long[] nodes = model.spawn(scene, renderables, scene.create());
            // -PgltfAnimation=t1,t2,...: the model's first clip (or -PgltfClip=name) posed at each time, an image each
            String times = System.getProperty("truesim.gltf.animation");
            Animators animators = new Animators(scene);
            ClipState playing = null;
            if (times != null && !model.animations().isEmpty()) {
                String name = System.getProperty("truesim.gltf.clip");
                AssetRef<AnimationClip> clipRef = name != null ? model.animation(name)
                    : model.animations().get(0).clip();
                cache.acquire(clipRef);
                playing = animators.add(model.skeleton(), nodes).play(await(tasks, cache, clipRef));
            }
            GpuScene gpu = new GpuScene(device, pool, 4096, slots);
            closing.add(gpu);
            SceneSync sync = new SceneSync(resources, renderables, gpu);
            closing.add(sync);
            FrameGraph graph = new FrameGraph(device, slots);
            closing.add(graph);
            MeshletRenderer renderer = new MeshletRenderer(device, gpu, VK_FORMAT_R16G16B16A16_SFLOAT,
                VK_FORMAT_D32_SFLOAT, slots);
            closing.add(renderer);
            SceneGeometry geometry = SceneGeometry.meshlets(renderer);
            ViewPort port = graph.addView("main").size(SIZE, SIZE);
            RenderView view = geometry.createView(ViewOptions.of(1 << 14, 1 << 20));
            closing.add(view);
            SceneView main = new SceneView(port, view, new Camera());
            int usage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT
                | VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_STORAGE_BIT;
            GpuImage target = new GpuImage(device, SIZE, SIZE, VK_FORMAT_R16G16B16A16_SFLOAT, usage,
                VK_IMAGE_ASPECT_COLOR_BIT);
            closing.add(target);
            ExternalImage output = new ExternalImage("target", SIZE, SIZE, Format.R16G16B16A16_SFLOAT, usage,
                VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL).set(target.handle(), target.view());
            GpuBuffer readback = GpuBuffer.hostVisible(device, SIZE * SIZE * 8L, VK_BUFFER_USAGE_TRANSFER_DST_BIT);
            closing.add(readback);
            Lights lights = lights(device, model, world, bounds);
            if (lights != null) {
                closing.add(lights);
                port.add(new ClusteredLights(lights, main));
            }
            String hdr = System.getProperty("truesim.gltf.environment");
            if (hdr != null) {
                TextureData panorama;
                try (var in = Files.newInputStream(Path.of(hdr))) {
                    panorama = RadianceImporter.read(in, hdr);
                }
                int sky = textures.add(TextureFormat.RGBA16F, panorama.width(), panorama.height(), Textures.GENERATE,
                    panorama.texels());
                Environment environment = Environment.panorama("hdr", sky, panorama.width());
                port.add(new EnvironmentLighting(environment, main)).add(new SkyBackground(environment, main));
            }
            port.add(new ClusteredForwardShading(geometry, main, VK_FORMAT_R16G16B16A16_SFLOAT, VK_FORMAT_D32_SFLOAT)
                    .clearColor(0.05f, 0.05f, 0.05f))
                .add(new WeightedBlendedTransparency(geometry, main)).add(new Readback(readback))
                .output(Keys.SCENE_COLOR, output);
            // Framed from the front, the bounds' sphere filling the view.
            double cx = (bounds[0] + bounds[3]) / 2;
            double cy = (bounds[1] + bounds[4]) / 2;
            double cz = (bounds[2] + bounds[5]) / 2;
            double radius = Math.max(1e-3, 0.5 * Math.sqrt(Math.pow(bounds[3] - bounds[0], 2)
                + Math.pow(bounds[4] - bounds[1], 2) + Math.pow(bounds[5] - bounds[2], 2)));
            // -PgltfZoom=z,x,y: z times closer, looking at (x, y) of the framed view (-1 to 1, y up)
            String[] zoom = System.getProperty("truesim.gltf.zoom", "1,0,0").split(",");
            double fov = 2 * Math.atan(Math.tan(Math.toRadians(45) / 2) / Double.parseDouble(zoom[0]));
            double distance = radius / Math.sin(Math.toRadians(45) / 2) * 0.85;
            double half = Math.tan(Math.toRadians(45) / 2) * distance * 0.85 / 0.85;
            cx += Double.parseDouble(zoom[1]) * half;
            cy += Double.parseDouble(zoom[2]) * half;
            Camera camera = new Camera().position(cx, cy, cz + distance)
                .set(0, 0, -1, 0, 1, 0, fov, SIZE, SIZE, Math.max(distance - radius * 2, distance * 1e-3));
            String[] poses = playing != null ? times.split(",") : new String[] {null};
            for (int pose = 0; pose < poses.length; pose++) {
            if (playing != null) {
                playing.time(Double.parseDouble(poses[pose]));
                animators.update(0);
            }
            for (int round = 0; round < (pose == 0 ? 12 : 3); round++) {
                int slot = graph.beginFrame();
                slots.begin(slot);
                tasks.drainCompleted();
                scene.update();
                renderables.update();
                sync.update();
                view.set(camera, 1, MeshletRenderer.MODE_LIT);
                graph.submit(0, 0);
                slots.end();
                long deadline = System.nanoTime() + 30_000_000_000L;
                while ((cache.loadingCount() > 0 || tasks.pending() > 0) && System.nanoTime() < deadline) {
                    Thread.sleep(1);
                    tasks.drainCompleted();
                }
            }
            device.waitIdle();
            write(readback, playing == null ? png
                : png.resolveSibling(png.getFileName().toString().replace(".png", "_" + poses[pose] + "s.png")));
            }
          } finally {
            device.waitIdle();
            for (int i = closing.size() - 1; i >= 0; i--) {
                closing.get(i).close();
            }
          }
        }
    }

    private static <T> T await(TaskScheduler tasks, AssetCache cache, AssetRef<T> ref) throws InterruptedException {
        long deadline = System.nanoTime() + 60_000_000_000L;
        while (cache.get(ref) == null && cache.failure(ref) == null) {
            assertThat(System.nanoTime()).as("loading " + ref).isLessThan(deadline);
            Thread.sleep(1);
            tasks.drainCompleted();
        }
        assertThat(cache.failure(ref)).as(ref.toString()).isNull();
        return cache.get(ref);
    }

    /** Each node's world matrix, column-major. */
    private static double[][] worldMatrices(ModelData model) {
        List<ModelData.Node> nodes = model.nodes();
        double[][] world = new double[nodes.size()][];
        for (int i = 0; i < nodes.size(); i++) {
            ModelData.Node n = nodes.get(i);
            world[i] = n.parent() < 0 ? trs(n) : multiply(world[n.parent()], trs(n));
        }
        return world;
    }

    /**
     * The model's own lights (glTF's KHR_lights_punctual) at their nodes, as the renderer's: intensity times color
     * (lux for directional lights, candela, the illuminance at 1 m, for the others); null without any.
     */
    private static Lights lights(VulkanDevice device, ModelData model, double[][] world, double[] bounds) {
        List<ModelData.Node> nodes = model.nodes();
        int count = 0;
        for (ModelData.Node n : nodes) {
            count += n.light() >= 0 ? 1 : 0;
        }
        if (count == 0) {
            return null;
        }
        Lights lights = new Lights(device, count, 1);
        double reach = 10 * Math.max(1, Math.max(bounds[3] - bounds[0], Math.max(bounds[4] - bounds[1],
            bounds[5] - bounds[2])));
        int index = 0;
        for (int i = 0; i < nodes.size(); i++) {
            if (nodes.get(i).light() < 0) {
                continue;
            }
            ModelData.Light l = model.lights().get(nodes.get(i).light());
            double[] m = world[i];
            double length = Math.sqrt(m[8] * m[8] + m[9] * m[9] + m[10] * m[10]);
            float dx = (float) (-m[8] / length); // lights shine along their node's -z
            float dy = (float) (-m[9] / length);
            float dz = (float) (-m[10] / length);
            float r = (float) (l.r() * l.intensity());
            float g = (float) (l.g() * l.intensity());
            float b = (float) (l.b() * l.intensity());
            float range = (float) (l.range() > 0 ? l.range() : reach);
            switch (l.kind()) {
                case DIRECTIONAL -> lights.directional(index, dx, dy, dz, r, g, b);
                case POINT -> lights.point(index, m[12], m[13], m[14], range, r, g, b);
                case SPOT -> lights.spot(index, m[12], m[13], m[14], range, r, g, b, dx, dy, dz, l.innerCone(),
                    l.outerCone());
            }
            index++;
        }
        lights.setCount(count);
        return lights;
    }

    /** The model's box in world space: min x, y, z, max x, y, z, its meshes' boxes moved by their nodes. */
    private static double[] bounds(ModelData model, double[][] world, TaskScheduler tasks, AssetCache cache)
        throws InterruptedException {
        List<ModelData.Node> nodes = model.nodes();
        double[] box = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE,
            -Double.MAX_VALUE};
        for (int i = 0; i < nodes.size(); i++) {
            ModelData.Node n = nodes.get(i);
            if (n.mesh() == null) {
                continue;
            }
            cache.acquire(n.mesh());
            MeshData mesh = await(tasks, cache, n.mesh());
            for (int c = 0; c < 8; c++) {
                double[] p = new double[3];
                for (int a = 0; a < 3; a++) {
                    p[a] = mesh.boxMin(a) + ((c >> a & 1) != 0 ? mesh.boxExtent(a) : 0);
                }
                for (int a = 0; a < 3; a++) {
                    double v = world[i][a] * p[0] + world[i][4 + a] * p[1] + world[i][8 + a] * p[2] + world[i][12 + a];
                    box[a] = Math.min(box[a], v);
                    box[3 + a] = Math.max(box[3 + a], v);
                }
            }
            cache.release(n.mesh());
        }
        return box;
    }

    /** A node's local matrix, column-major. */
    private static double[] trs(ModelData.Node n) {
        double x = n.rx();
        double y = n.ry();
        double z = n.rz();
        double w = n.rw();
        return new double[] {
            (1 - 2 * (y * y + z * z)) * n.sx(), 2 * (x * y + z * w) * n.sx(), 2 * (x * z - y * w) * n.sx(), 0,
            2 * (x * y - z * w) * n.sy(), (1 - 2 * (x * x + z * z)) * n.sy(), 2 * (y * z + x * w) * n.sy(), 0,
            2 * (x * z + y * w) * n.sz(), 2 * (y * z - x * w) * n.sz(), (1 - 2 * (x * x + y * y)) * n.sz(), 0,
            n.tx(), n.ty(), n.tz(), 1};
    }

    private static double[] multiply(double[] a, double[] b) {
        double[] m = new double[16];
        for (int c = 0; c < 4; c++) {
            for (int r = 0; r < 4; r++) {
                for (int k = 0; k < 4; k++) {
                    m[c * 4 + r] += a[k * 4 + r] * b[c * 4 + k];
                }
            }
        }
        return m;
    }

    /** The half-float image tone mapped (Khronos PBR Neutral) and sRGB-encoded into {@code png}. */
    private static void write(GpuBuffer readback, Path png) throws Exception {
        BufferedImage image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                long base = ((long) y * SIZE + x) * 8;
                double[] c = new double[3];
                for (int i = 0; i < 3; i++) {
                    c[i] = Float.float16ToFloat(readback.memory().get(ValueLayout.JAVA_SHORT_UNALIGNED, base + 2L * i));
                }
                neutral(c);
                int rgb = 0;
                for (int i = 0; i < 3; i++) {
                    double v = Double.isFinite(c[i]) ? Math.max(0, Math.min(1, c[i])) : 1;
                    v = v <= 0.0031308 ? v * 12.92 : 1.055 * Math.pow(v, 1 / 2.4) - 0.055;
                    rgb = rgb << 8 | (int) Math.round(v * 255);
                }
                image.setRGB(x, y, rgb);
            }
        }
        ImageIO.write(image, "png", png.toFile());
    }

    /** Khronos PBR Neutral tone mapping, in place. */
    private static void neutral(double[] c) {
        double x = Math.min(c[0], Math.min(c[1], c[2]));
        double offset = x < 0.08 ? x - 6.25 * x * x : 0.04;
        for (int i = 0; i < 3; i++) {
            c[i] -= offset;
        }
        double peak = Math.max(c[0], Math.max(c[1], c[2]));
        double start = 0.8 - 0.04;
        if (peak < start) {
            return;
        }
        double d = 1 - start;
        double newPeak = 1 - d * d / (peak + d - start);
        double g = 1 - 1 / (0.15 * (peak - newPeak) + 1);
        for (int i = 0; i < 3; i++) {
            c[i] = c[i] * newPeak / peak * (1 - g) + newPeak * g;
        }
    }

    /** Copies {@link Keys#SCENE_COLOR} into {@code readback} every frame, after everything drawn into it. */
    private record Readback(GpuBuffer readback) implements Technique {
        @Override
        public List<Key<?>> provides() {
            return List.of();
        }

        @Override
        public Order order() {
            return Order.at(Anchor.UI);
        }

        @Override
        public List<Key<?>> modifies() {
            return List.of(Keys.SCENE_COLOR);
        }

        @Override
        public void build(GraphBuilder g) {
            Image color = g.modify(Keys.SCENE_COLOR);
            g.pass("readback").read(color, Access.TRANSFER_SRC).sideEffects().record(ctx -> {
                try (var stack = stackPush()) {
                    VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
                    region.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
                    region.imageExtent().set(SIZE, SIZE, 1);
                    vkCmdCopyImageToBuffer(ctx.unsafe().commands(), ctx.unsafe().image(color),
                        VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, readback.handle(), region);
                }
            });
            g.provide(Keys.SCENE_COLOR, color);
        }
    }
}
