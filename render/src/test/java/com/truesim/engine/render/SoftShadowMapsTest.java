package com.truesim.engine.render;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_D32_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16B16A16_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_UNDEFINED;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
import static org.lwjgl.vulkan.VK10.vkCmdCopyImageToBuffer;

import com.truesim.engine.graph.ExternalImage;
import com.truesim.engine.graph.FrameGraph;
import com.truesim.engine.render.shadows.ShadowSettings;
import com.truesim.engine.render.shadows.Shadows;
import com.truesim.engine.vulkan.GpuBuffer;
import com.truesim.engine.vulkan.GpuImage;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.engine.vulkan.VulkanException;
import com.truesim.engine.vulkan.VulkanInstance;
import com.truesim.scene.assets.mesh.MeshData;
import com.truesim.scene.assets.mesh.VertexFormat;
import com.truesim.scene.core.ShadowSpec;
import java.lang.foreign.ValueLayout;
import java.util.Arrays;
import java.util.Locale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkBufferImageCopy;

/**
 * The edge of a shadow map's shadow on a floor: a large quad 8 units over it, its edge turned 30 degrees from the
 * map's texel rows, under a directional light straight down. The penumbra is as wide as the light's angular size makes
 * it (PCSS), and along the edge the light changes smoothly: no stair steps of the map's texels (each texel compared
 * alone and weighed equally made them: the lit share jumped a texel at a time).
 */
class SoftShadowMapsTest {

    private static final int SIZE = 320;
    /** The caster's height over the floor. */
    private static final double HEIGHT = 8;
    /** The edge's turn from the map's texel rows (the light camera's right is world x). */
    private static final double TURN = Math.toRadians(30);

    private VulkanInstance instance;
    private VulkanDevice device;
    private GeometryPool pool;
    private GpuScene scene;
    private MeshletRenderer renderer;
    private RenderView view;
    private FrameGraph graph;
    private GpuImage colorTarget;
    private GpuBuffer pixels;
    private final Camera camera = new Camera();
    private Shadows owner;
    private Lights lights;

    @BeforeEach
    void start() {
        try {
            instance = VulkanInstance.builder().applicationName("tests").validation(true)
                .synchronizationValidation(true).surfaces(false).build();
        } catch (VulkanException | UnsatisfiedLinkError e) {
            assumeThat(e).as("no Vulkan available").isNull();
            throw e;
        }
        assumeThat(instance.gpus()).as("no Vulkan 1.3 GPU").isNotEmpty();
        device = VulkanDevice.createHeadless(instance.bestGpu());
        pool = new GeometryPool(device, GeometryPool.Capacity.of(1 << 12, 64, 4));
        MeshData quadMesh = MeshletRendererTest.grid(1, 2, false, VertexFormat.POSITIONS_ONLY);
        int quad = pool.add(quadMesh);
        scene = new GpuScene(device, pool, 16);
        renderer = new MeshletRenderer(device, scene, VK_FORMAT_R16G16B16A16_SFLOAT, VK_FORMAT_D32_SFLOAT);
        view = renderer.createView(ViewOptions.of(256, 1 << 12).withOcclusion(true));
        // The floor (y = -5) and, 8 over it, a 20 x 20 quad turned 30 degrees about y, its +x edge through (0, 3, -5).
        scene.set(0, quad, 0, -5, -5, 0, 0, 0, 1, 10, 1, 10);
        double c = Math.cos(TURN);
        double s = Math.sin(TURN);
        scene.set(1, quad, (float) (-10 * c), (float) (-5 + HEIGHT), (float) (-5 + 10 * s), 0,
            (float) Math.sin(TURN / 2), 0, (float) Math.cos(TURN / 2), 10, 1, 10);
        camera.set(0, -1, -1, 0, 1, 0, Math.toRadians(40), SIZE, SIZE, 0.1);
        view.set(camera, 1, MeshletRenderer.MODE_LIT);
        graph = new FrameGraph(device, 2);
        int usage = VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
        colorTarget = new GpuImage(device, SIZE, SIZE, VK_FORMAT_R16G16B16A16_SFLOAT, usage,
            VK_IMAGE_ASPECT_COLOR_BIT);
        pixels = GpuBuffer.hostVisible(device, SIZE * SIZE * 8, VK_BUFFER_USAGE_TRANSFER_DST_BIT);
        lights = new Lights(device, 4, 4);
        lights.directional(0, 0, -1, 0, 1, 1, 1).setCount(1);
        SceneGeometry geometry = SceneGeometry.meshlets(renderer);
        owner = new Shadows(geometry, lights);
        SceneView main = new SceneView(graph.addView("main").size(SIZE, SIZE), view, camera);
        main.port().add(new ClusteredForwardShading(geometry, main, VK_FORMAT_R16G16B16A16_SFLOAT,
                VK_FORMAT_D32_SFLOAT)).add(new ClusteredLights(lights, main)).add(owner.view(main))
            .output(Keys.SCENE_COLOR, new ExternalImage("target", SIZE, SIZE,
                com.truesim.engine.vulkan.Format.R16G16B16A16_SFLOAT, usage, VK_IMAGE_LAYOUT_UNDEFINED,
                VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL).set(colorTarget.handle(), colorTarget.view()));
    }

    @AfterEach
    void stop() {
        if (device != null) {
            device.waitIdle();
            int errors = instance.validationErrors();
            graph.close();
            owner.close();
            lights.close();
            pixels.close();
            colorTarget.close();
            renderer.close();
            scene.close();
            pool.close();
            device.close();
            instance.close();
            assertThat(errors).as("validation errors").isZero();
        } else if (instance != null) {
            instance.close();
        }
    }

    /** A few frames (the late phase draws everything the first, the tiles the next); the last's red, linear. */
    private float[] frames() {
        for (int i = 0; i < 3; i++) {
            device.submitAndWait(device.graphics(), commands -> {
                graph.record(commands);
                try (MemoryStack stack = stackPush()) {
                    VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
                    region.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
                    region.imageExtent().set(SIZE, SIZE, 1);
                    vkCmdCopyImageToBuffer(commands, colorTarget.handle(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                        pixels.handle(), region);
                }
            });
        }
        float[] red = new float[SIZE * SIZE];
        for (int i = 0; i < red.length; i++) {
            red[i] = Float.float16ToFloat(pixels.memory().get(ValueLayout.JAVA_SHORT_UNALIGNED, 8L * i));
        }
        return red;
    }

    /** The image at floor point (x, -5, z), bilinear between pixel centres. */
    private double floor(float[] red, double x, double z) {
        float[] m = camera.viewProjection();
        double y = -5;
        double cx = m[0] * x + m[4] * y + m[8] * z + m[12];
        double cy = m[1] * x + m[5] * y + m[9] * z + m[13];
        double w = m[3] * x + m[7] * y + m[11] * z + m[15];
        double px = (cx / w * 0.5 + 0.5) * SIZE - 0.5;
        double py = (cy / w * 0.5 + 0.5) * SIZE - 0.5;
        int ix = (int) Math.floor(px);
        int iy = (int) Math.floor(py);
        double fx = px - ix;
        double fy = py - iy;
        assertThat(ix).isBetween(0, SIZE - 2);
        assertThat(iy).isBetween(0, SIZE - 2);
        double a = red[iy * SIZE + ix] * (1 - fx) + red[iy * SIZE + ix + 1] * fx;
        double b = red[(iy + 1) * SIZE + ix] * (1 - fx) + red[(iy + 1) * SIZE + ix + 1] * fx;
        return a * (1 - fy) + b * fy;
    }

    /**
     * Across the edge at {@code along} units along it from (0, -5, -5): the light, 0 (shadow) to 1 (lit), at
     * {@code across} units towards the lit side.
     */
    private double lit(float[] red, double along, double across, double dark, double light) {
        double c = Math.cos(TURN);
        double s = Math.sin(TURN);
        // The edge runs along R(turn)(0, 0, 1) = (sin, 0, cos); the lit side is R(turn)(1, 0, 0) = (cos, 0, -sin).
        double x = along * s + across * c;
        double z = -5 + along * c - across * s;
        return (floor(red, x, z) - dark) / (light - dark);
    }

    /** Where across the edge the light passes {@code level}, at {@code along}: searched from -range to range. */
    private double crossing(float[] red, double along, double level, double range, double dark, double light) {
        double step = 0.002;
        double before = lit(red, along, -range, dark, light);
        for (double u = -range + step; u <= range; u += step) {
            double now = lit(red, along, u, dark, light);
            if (before < level && now >= level) {
                return u - step + step * (level - before) / (now - before);
            }
            before = now;
        }
        return Double.NaN;
    }

    /** The edge's measures: [penumbra 10 to 90 %, its spread over the edge, stair steps (largest off a line)]. */
    private double[] measure(float[] red, double range) {
        double c = Math.cos(TURN);
        double s = Math.sin(TURN);
        double light = floor(red, 1.5 * c, -5 - 1.5 * s);
        double dark = floor(red, -1.5 * c, -5 + 1.5 * s);
        assertThat(dark).as("the shadow (sky light alone), against %.3f lit", light).isLessThan(light * 0.6);
        int n = 121;
        double[] along = new double[n];
        double[] half = new double[n];
        double[] width = new double[n];
        for (int i = 0; i < n; i++) {
            along[i] = -1.2 + 2.4 * i / (n - 1);
            half[i] = crossing(red, along[i], 0.5, range, dark, light);
            width[i] = crossing(red, along[i], 0.9, range, dark, light) - crossing(red, along[i], 0.1, range, dark,
                light);
        }
        // The 50 % line's straightness: off its least-squares line
        double ma = Arrays.stream(along).average().orElseThrow();
        double mh = Arrays.stream(half).average().orElseThrow();
        double sxy = 0;
        double sxx = 0;
        for (int i = 0; i < n; i++) {
            sxy += (along[i] - ma) * (half[i] - mh);
            sxx += (along[i] - ma) * (along[i] - ma);
        }
        double slope = sxy / sxx;
        double steps = 0;
        for (int i = 0; i < n; i++) {
            steps = Math.max(steps, Math.abs(half[i] - (mh + slope * (along[i] - ma))));
        }
        double mean = Arrays.stream(width).average().orElseThrow();
        double spread = Arrays.stream(width).map(v -> Math.abs(v - mean)).max().orElseThrow();
        return new double[] {mean, spread, steps};
    }

    @Test
    void thePenumbraIsAsWideAsTheLightsSizeMakesItWithoutStairSteps() {
        // A light 0.03 radians in radius (3.4 degrees across) 8 units over the floor: a disc's penumbra of radius
        // 0.24, 10 to 90 % over 1.374 of it (the share of a disc past a line); texels about 3.3 cm.
        double radius = 0.03;
        lights.shadow(0, ShadowSpec.maps(1024).distance(30).softness(radius));
        double[] edge = measure(frames(), 0.6);
        double expected = 1.374 * HEIGHT * Math.tan(radius);
        System.out.printf(Locale.ROOT, "PCSS: penumbra %.4f (a disc's %.4f), spread %.4f, steps %.4f%n", edge[0],
            expected, edge[1], edge[2]);
        assertThat(edge[0]).as("the penumbra, 10 to 90 %").isCloseTo(expected, within(0.15 * expected));
        assertThat(edge[1]).as("the penumbra's width along the edge").isLessThan(0.1 * expected);
        assertThat(edge[2]).as("stair steps: the 50 % line off straight").isLessThan(0.004);
    }

    @Test
    void aHardLightsEdgeHasNoStairSteps() {
        // No size: the narrowest kernel (shadows.maps.filterRadius, 1.5 texels), by PCSS as by PCF. A hard edge keeps
        // the texels' shape faintly: modelled (a half-plane's texels, tent-filtered), the 50 % line wavers 0.23 of a
        // texel off straight, smoothly, where 3 x 3 texels weighed equally stepped 0.61 (here 4 cm texels: 2.4 cm)
        lights.shadow(0, ShadowSpec.maps(1024).distance(30));
        for (ShadowSettings.Filter filter : ShadowSettings.Filter.values()) {
            graph.settings().set(ShadowSettings.MAP_FILTER, filter);
            double[] edge = measure(frames(), 0.3);
            System.out.printf(Locale.ROOT, "%s, no size: penumbra %.4f, spread %.4f, steps %.4f%n", filter, edge[0],
                edge[1], edge[2]);
            assertThat(edge[2]).as("%s: stair steps, the 50 %% line off straight", filter).isLessThan(0.014);
            assertThat(edge[1]).as("%s: the penumbra's width along the edge", filter).isLessThan(0.3 * edge[0]);
        }
    }

    @Test
    void pcfKeepsItsKernelWhateverTheLightsSize() {
        // PCF: the light's size makes no difference; PCSS widens the penumbra with it
        lights.shadow(0, ShadowSpec.maps(1024).distance(30).softness(0.03));
        graph.settings().set(ShadowSettings.MAP_FILTER, ShadowSettings.Filter.PCF);
        double pcf = measure(frames(), 0.6)[0];
        graph.settings().set(ShadowSettings.MAP_FILTER, ShadowSettings.Filter.PCSS);
        double pcss = measure(frames(), 0.6)[0];
        lights.shadow(0, ShadowSpec.maps(1024).distance(30));
        graph.settings().set(ShadowSettings.MAP_FILTER, ShadowSettings.Filter.PCF);
        double hard = measure(frames(), 0.6)[0];
        System.out.printf(Locale.ROOT, "penumbra: PCF %.4f (no size %.4f), PCSS %.4f%n", pcf, hard, pcss);
        assertThat(pcf).isCloseTo(hard, within(0.1 * hard));
        assertThat(pcss).isGreaterThan(3 * pcf);
    }
}
