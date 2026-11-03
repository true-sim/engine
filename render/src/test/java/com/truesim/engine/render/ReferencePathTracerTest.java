package com.truesim.engine.render;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_D32_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R32G32B32A32_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_UNDEFINED;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
import static org.lwjgl.vulkan.VK10.vkCmdCopyImageToBuffer;

import com.truesim.common.math.color.Color;
import com.truesim.engine.graph.Buffer;
import com.truesim.engine.graph.BufferAccess;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Key;
import com.truesim.engine.graph.Technique;
import com.truesim.engine.vulkan.Commands;
import java.util.List;
import org.lwjgl.system.MemoryUtil;
import com.truesim.engine.graph.ExternalImage;
import com.truesim.engine.graph.FrameGraph;
import com.truesim.engine.render.materials.CarPaint;
import com.truesim.engine.render.materials.CarPaintParameters;
import com.truesim.engine.render.materials.Dissolve;
import com.truesim.engine.render.materials.DissolveParameters;
import com.truesim.engine.render.materials.Glow;
import com.truesim.engine.render.materials.GlowParameters;
import com.truesim.engine.render.materials.MetallicRoughnessParameters;
import com.truesim.engine.render.materials.SpecularParameters;
import com.truesim.engine.render.materials.Tinted;
import com.truesim.engine.render.materials.TintedParameters;
import com.truesim.engine.vulkan.Format;
import com.truesim.engine.vulkan.GpuBuffer;
import com.truesim.engine.vulkan.GpuImage;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.engine.vulkan.VulkanException;
import com.truesim.engine.vulkan.VulkanInstance;
import com.truesim.scene.assets.mesh.MeshData;
import com.truesim.scene.assets.mesh.VertexFormat;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkBufferImageCopy;

/**
 * The reference path tracer against closed forms and by material type, validated: a closed box glowing and reflecting
 * uniformly (the white furnace: E / (1 - albedo) everywhere), an open plane under a uniform sky (albedo times the sky),
 * a Cornell box converging (noise, color bleeding), and material types shading as themselves at hits. Sizes are small
 * enough for a CPU's ray tracing (lavapipe, in CI).
 */
class ReferencePathTracerTest {

    static final int SIZE = 32;
    static final float HALF = (float) Math.sqrt(0.5);
    static final MetallicRoughnessParameters MR = MetallicRoughnessParameters.DEFAULTS;
    /** No Fresnel and no specular lobe: a Lambertian surface (KHR_materials_specular at 0). */
    static final SpecularParameters LAMBERT = SpecularParameters.DEFAULTS.withSpecularFactor(0);
    /** shading.slang's SKY_IRRADIANCE, the constant sky's irradiance. */
    static final double SKY_IRRADIANCE = 0.78539816 / 1.02;

    private VulkanInstance instance;
    private VulkanDevice device;
    private GeometryPool pool;
    private GpuScene scene;
    private MeshletRenderer renderer;
    private RenderView view;
    private RayTracing rayTracing;
    private Materials materials;
    private Lights lights;
    private FrameGraph graph;
    private GpuImage target;
    private GpuBuffer pixels;
    private int quad;
    private int size = SIZE; // of the images: SIZE but for screenshots
    private final Camera camera = new Camera();

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
        assumeThat(device.rayTracing()).as("no ray tracing").isTrue();
        pool = new GeometryPool(device, GeometryPool.Capacity.of(1 << 12, 64, 4));
        MeshData quadMesh = MeshletRendererTest.grid(1, 2, false, VertexFormat.POSITIONS_ONLY);
        quad = pool.add(quadMesh);
        scene = new GpuScene(device, pool, 32);
        materials = new Materials(device, 16);
        scene.setMaterials(materials);
        renderer = new MeshletRenderer(device, scene, VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_D32_SFLOAT);
        view = renderer.createView(ViewOptions.of(256, 1 << 12));
        rayTracing = new RayTracing(device, pool, 32);
        rayTracing.add(quad, quadMesh, quadMesh.levels().size() - 1);
        lights = new Lights(device, 64, 4);
        graph = new FrameGraph(device, 2);
    }

    @AfterEach
    void stop() {
        if (device != null) {
            device.waitIdle();
            int errors = instance.validationErrors();
            if (graph != null) {
                graph.close();
                if (target != null) {
                    pixels.close();
                    target.close();
                }
                lights.close();
                rayTracing.close();
                renderer.close();
                scene.close();
                materials.close();
                pool.close();
            }
            device.close();
            instance.close();
            assertThat(errors).as("validation errors").isZero();
        } else if (instance != null) {
            instance.close();
        }
    }

    /** The main view, path traced, its light list {@code count} lights of {@link #lights}, into {@link #target}. */
    private ReferencePathTracer traced(int count, Environment environment) {
        if (target == null) {
            target = new GpuImage(device, size, size, VK_FORMAT_R32G32B32A32_SFLOAT,
                VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT, VK_IMAGE_ASPECT_COLOR_BIT);
            pixels = GpuBuffer.hostVisible(device, (long) size * size * 16, VK_BUFFER_USAGE_TRANSFER_DST_BIT);
        }
        view.set(camera, 1, MeshletRenderer.MODE_LIT);
        SceneView main = new SceneView(graph.addView("main").size(size, size), view, camera);
        lights.setCount(count);
        ReferencePathTracer reference = new ReferencePathTracer(SceneGeometry.meshlets(renderer), rayTracing, main,
            VK_FORMAT_R32G32B32A32_SFLOAT);
        main.port().add(reference).add(new ClusteredLights(lights, main))
            .output(Keys.SCENE_COLOR, new ExternalImage("target", size, size, Format.R32G32B32A32_SFLOAT,
                VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT, VK_IMAGE_LAYOUT_UNDEFINED,
                VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL).set(target.handle(), target.view()));
        if (environment != null) {
            main.port().add(new EnvironmentLighting(environment, main));
        }
        return reference;
    }

    /** Records {@code frames} frames (a sample each) and reads the image back: rgba floats per pixel. */
    private float[] frames(int frames) {
        for (int done = 0; done < frames; done += 16) {
            int batch = Math.min(16, frames - done);
            device.submitAndWait(device.graphics(), commands -> {
                for (int f = 0; f < batch; f++) {
                    graph.record(commands);
                }
                try (MemoryStack stack = stackPush()) {
                    VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
                    region.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
                    region.imageExtent().set(size, size, 1);
                    vkCmdCopyImageToBuffer(commands, target.handle(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                        pixels.handle(), region);
                }
            });
        }
        return pixels.memory().asSlice(0, (long) size * size * 16).toArray(ValueLayout.JAVA_FLOAT);
    }

    /** Quad {@code id} (2 x 2, scaled by {@code half} each way) of {@code material}, facing along the axis given. */
    private void wall(int id, int material, float x, float y, float z, float[] facing, float half) {
        scene.set(id, quad, material, x, y, z, facing[0], facing[1], facing[2], facing[3], half, half, half);
    }

    static final float[] UP = {0, 0, 0, 1};
    static final float[] DOWN = {1, 0, 0, 0};
    static final float[] PLUS_X = {0, 0, -HALF, HALF};
    static final float[] MINUS_X = {0, 0, HALF, HALF};
    static final float[] PLUS_Z = {HALF, 0, 0, HALF};
    static final float[] MINUS_Z = {-HALF, 0, 0, HALF};

    /** A box from -1 to 1 each way, its faces inward, each of the material given (-x, +x, -y, +y, -z, +z). */
    private void box(int first, int[] material, boolean front) {
        float half = 1.001f; // past each other at the edges: no cracks
        wall(first, material[0], -1, 0, 0, PLUS_X, half);
        wall(first + 1, material[1], 1, 0, 0, MINUS_X, half);
        wall(first + 2, material[2], 0, -1, 0, UP, half);
        wall(first + 3, material[3], 0, 1, 0, DOWN, half);
        wall(first + 4, material[4], 0, 0, -1, PLUS_Z, half);
        if (front) {
            wall(first + 5, material[5], 0, 0, 1, MINUS_Z, half);
        }
    }

    static double luminance(float[] image, int p) {
        return 0.2126 * image[4 * p] + 0.7152 * image[4 * p + 1] + 0.0722 * image[4 * p + 2];
    }

    @Test
    void aClosedBoxGlowingAndReflectingEvenlyIsEOverOneMinusTheAlbedoEverywhere() {
        // The white furnace: every surface gives off E and reflects a fraction rho, so the radiance is E / (1 - rho).
        float rho = 0.5f;
        float glow = 0.25f;
        int furnace = materials.add(new Material(MR.withBaseColor(new Color(rho, rho, rho, 1))
            .withEmissive(new Color(glow, glow, glow, 1))).feature(LAMBERT));
        int[] all = {furnace, furnace, furnace, furnace, furnace, furnace};
        box(0, all, true);
        camera.set(0.3, -0.2, -1, 0, 1, 0, Math.PI / 2, SIZE, SIZE, 0.01);
        ReferencePathTracer reference = traced(0, null);
        graph.settings().set(ReferencePathTracer.BOUNCES, 24);
        float[] image = frames(128);
        assertThat(reference.samples()).isEqualTo(128);
        double expected = glow / (1 - rho) * (1 - Math.pow(rho, 24)); // to the bounce limit
        double sum = 0;
        double worst = 0;
        for (int p = 0; p < SIZE * SIZE; p++) {
            for (int c = 0; c < 3; c++) {
                sum += image[4 * p + c];
            }
            worst = Math.max(worst, Math.abs(image[4 * p] - expected) / expected);
        }
        double mean = sum / (3.0 * SIZE * SIZE);
        assertThat(mean).as("the mean radiance").isCloseTo(expected, within(expected * 0.01));
        assertThat(worst).as("each pixel, after 128 samples").isLessThan(0.1);
        assertThat(graph.passes()).contains("reference camera rays", "reference trace 0", "reference bins 0",
            "reference scatter 0", "reference shade 0", "reference resolve");
    }

    @Test
    void anOpenPlaneUnderAUniformSkyReflectsItsAlbedoOfIt() {
        // A Lambertian plane sees only the sky above it: its radiance is albedo times the sky's, whatever the angle.
        float rho = 0.6f;
        int plane = materials.add(new Material(MR.withBaseColor(new Color(rho, rho, rho, 1))).feature(LAMBERT));
        wall(0, plane, 0, -1, 0, UP, 200);
        camera.set(0, -0.3, -1, 0, 1, 0, Math.PI / 2, SIZE, SIZE, 0.01);
        // The constant sky (no environment), no lights: SKY_IRRADIANCE / pi from every direction.
        traced(0, null);
        float[] image = frames(64);
        double sky = SKY_IRRADIANCE / Math.PI;
        int skyPixel = 2 * SIZE + SIZE / 2;            // the top rows: the sky
        int planePixel = (SIZE - 3) * SIZE + SIZE / 2; // the bottom rows: the plane
        for (int c = 0; c < 3; c++) {
            assertThat((double) image[4 * skyPixel + c]).as("the sky").isCloseTo(sky, within(sky * 1.0e-3));
        }
        double plain = 0;
        int count = 0;
        for (int y = SIZE * 3 / 4; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                plain += luminance(image, y * SIZE + x);
                count++;
            }
        }
        assertThat(plain / count).as("the plane, albedo times the sky").isCloseTo(rho * sky,
            within(rho * sky * 0.01));
        assertThat((double) image[4 * planePixel]).isCloseTo(rho * sky, within(rho * sky * 0.1));
        // Under a uniform environment of a color: the plane reflects its albedo of that color.
        graph.close();
        graph = new FrameGraph(device, 2);
        traced(0, Environment.uniform("blue sky", 0.2f, 0.4f, 0.8f));
        image = frames(64);
        double[] blue = {0.2, 0.4, 0.8};
        for (int c = 0; c < 3; c++) {
            assertThat((double) image[4 * skyPixel + c]).as("the environment").isCloseTo(blue[c],
                within(blue[c] * 0.02));
            double mean = 0;
            for (int y = SIZE * 3 / 4; y < SIZE; y++) {
                for (int x = 0; x < SIZE; x++) {
                    mean += image[4 * (y * SIZE + x) + c];
                }
            }
            mean /= count;
            assertThat(mean).as("the plane in channel %d", c).isCloseTo(rho * blue[c], within(rho * blue[c] * 0.02));
        }
    }

    /** The Cornell box: red left, green right, white elsewhere, open in front, lit by a ceiling light; a dark sky. */
    void cornellBox() {
        int white = materials.add(new Material(MR.withBaseColor(new Color(0.725f, 0.71f, 0.68f, 1))).feature(LAMBERT));
        int red = materials.add(new Material(MR.withBaseColor(new Color(0.63f, 0.065f, 0.05f, 1))).feature(LAMBERT));
        int green = materials.add(new Material(MR.withBaseColor(new Color(0.14f, 0.45f, 0.091f, 1))).feature(LAMBERT));
        int light = materials.add(new Material(MR.withBaseColor(new Color(0, 0, 0, 1))
            .withEmissive(new Color(17, 12, 4, 1))).feature(LAMBERT));
        box(0, new int[] {red, green, white, white, white, white}, false);
        wall(5, light, 0, 0.99f, 0, DOWN, 0.25f);
        camera.position(0, 0, 3.4).set(0, 0, -1, 0, 1, 0, 0.66, size, size, 0.01);
    }

    /**
     * The noise of the mean of {@code full} (2n samples), whose first n are {@code half}: the second half's samples
     * alone (2 x full - half) against the first's, over blocks of 4 x 4 pixels, relative to the mean.
     */
    static double noise(float[] half, float[] full) {
        return noise(half, full, SIZE);
    }

    /** {@link #noise(float[], float[])} of images {@code size} square. */
    static double noise(float[] half, float[] full, int size) {
        double noise = 0;
        double signal = 0;
        int count = 0;
        int block = 4;
        for (int by = 0; by < size; by += block) {
            for (int bx = 0; bx < size; bx += block) {
                double a = 0;
                double b = 0;
                for (int y = by; y < by + block; y++) {
                    for (int x = bx; x < bx + block; x++) {
                        int p = y * size + x;
                        a += luminance(half, p);
                        b += 2 * luminance(full, p) - luminance(half, p);
                    }
                }
                noise += (a - b) * (a - b) / 4;
                signal += (a + b) / 2;
                count++;
            }
        }
        return Math.sqrt(noise / count) / (signal / count);
    }

    @Test
    void theCornellBoxConvergesWithItsColorsBleeding() {
        cornellBox();
        traced(0, Environment.uniform("night", 0, 0, 0));
        float[] first = frames(32);
        float[] second = frames(32);
        double early = noise(first, second);
        frames(64);
        float[] half = frames(128);
        float[] full = frames(256);
        double late = noise(half, full);
        // Unbiased: noise falls as one over the root of the samples (64 to 512: by 2.8); the light, small, is found by
        // paths meeting it (no light sampling of emissive surfaces yet), so it falls from where it is high.
        assertThat(late).as("noise at 512 samples against 64 (%f)", early).isLessThan(early / 2.4);
        // The floor near the red wall is redder than near the green one.
        int left = (SIZE - 4) * SIZE + SIZE / 4;
        int right = (SIZE - 4) * SIZE + SIZE * 3 / 4;
        double leftRed = full[4 * left] - full[4 * left + 1];
        double rightRed = full[4 * right] - full[4 * right + 1];
        assertThat(leftRed).as("red bleeding onto the floor").isGreaterThan(rightRed + 0.02);
    }

    @Test
    void eachMaterialTypeShadesAsItselfAtHits() {
        materials.register(Glow.TYPE);
        materials.register(CarPaint.TYPE);
        int white = materials.add(new Material(MR.withBaseColor(new Color(0.8f, 0.8f, 0.8f, 1))).feature(LAMBERT));
        int glow = materials.add(new Material(GlowParameters.DEFAULTS.withStrength(4)));
        int paint = materials.add(new Material(CarPaint.TYPE, CarPaintParameters.DEFAULTS.withFlakes(1)
            .withFlakeColor(new Color(1, 1, 0, 1))));
        // In the dark: a white floor lit by a magenta glow above it alone, a car paint quad (yellow flakes) beside.
        wall(0, white, 0, -1, -2, UP, 3);
        wall(1, glow, -0.7f, 0, -2, DOWN, 0.4f);
        wall(2, paint, 0.9f, -0.4f, -2.5f, PLUS_Z, 0.4f); // right of the middle, x 0.5 to 1.3
        camera.set(0, -0.5, -1, 0, 1, 0, Math.PI / 2, SIZE, SIZE, 0.01);
        traced(1, Environment.uniform("night", 0, 0, 0));
        // A white light in front of the paint, none on the floor below the glow
        lights.point(0, 0.9, -0.4, -1.8, 1.0f, 1, 1, 1);
        float[] image = frames(64);
        assertThat(materials.binCount()).as("three types and plain metallic-roughness").isEqualTo(4);
        // The floor under the glow: magenta, which only the glow gives off.
        double r = 0;
        double g = 0;
        double b = 0;
        for (int y = SIZE * 3 / 4; y < SIZE; y++) {
            for (int x = 0; x < SIZE / 3; x++) {
                int p = y * SIZE + x;
                r += image[4 * p];
                g += image[4 * p + 1];
                b += image[4 * p + 2];
            }
        }
        assertThat(r).as("the floor under the glow").isGreaterThan(0.5);
        assertThat(b).isCloseTo(r, within(r * 0.1));
        assertThat(g).as("little green: magenta, but for the paint's yellow bounced").isLessThan(r * 0.03);
        // The car paint: yellow where the light reaches it, its type's flakes, not metallic-roughness's grey.
        float pr = 0;
        float pg = 0;
        float pb = 0;
        for (int y = 9; y < 12; y++) {
            for (int x = 20; x < 23; x++) {
                int p = y * SIZE + x;
                pr += image[4 * p];
                pg += image[4 * p + 1];
                pb += image[4 * p + 2];
            }
        }
        assertThat(pr).as("lit").isGreaterThan(0.05f);
        assertThat(pg).isCloseTo(pr, within(pr * 0.2f));
        assertThat(pb).as("yellow: little blue").isLessThan(pr * 0.3f);
    }

    /** Provides {@link #GPU_LIGHTS}: writes {@code count} point lights into the lights the GPU writes, as particles. */
    private record GpuLightWriter(Lights lights, int count) implements Technique {

        @Override
        public List<Key<?>> provides() {
            return List.of(GPU_LIGHTS);
        }

        @Override
        public void build(GraphBuilder g) {
            Buffer written = g.importBuffer(lights.gpuLightsBuffer());
            g.pass("gpu lights").write(written, BufferAccess.TRANSFER_DST).record(ctx -> {
                try (MemoryStack stack = stackPush()) {
                    int bytes = 16 + 80 * count;
                    long data = stack.ncalloc(16, 1, bytes); // no ByteBuffer: frames allocate nothing
                    MemoryUtil.memPutInt(data, count);
                    for (int i = 0; i < count; i++) {
                        long o = data + 16 + 80L * i;
                        MemoryUtil.memPutFloat(o, -2.5f + 5f * i / count);
                        MemoryUtil.memPutFloat(o + 4, -0.6f);
                        MemoryUtil.memPutFloat(o + 8, -3.5f);
                        MemoryUtil.memPutFloat(o + 12, 1.5f); // range
                        MemoryUtil.memPutFloat(o + 16, 0.3f);
                        MemoryUtil.memPutFloat(o + 20, 0.2f);
                        MemoryUtil.memPutFloat(o + 24, 1.0f);
                        MemoryUtil.memPutInt(o + 52, -1);
                    }
                    Commands.updateBuffer(ctx.unsafe().commands(), ctx.unsafe().buffer(written), 0, bytes, data);
                }
            });
            g.provide(GPU_LIGHTS, written);
        }
    }

    static final Key<Buffer> GPU_LIGHTS = Key.buffer("test.gpuLights");

    /** A floor and quads standing on it, lit by 40 point lights of the scene and 8 the GPU writes, in the dark. */
    private float[] lightsOnTheFloor(ReferencePathTracer.Lighting lighting, WorldLights.Mode mode, int frames) {
        graph.close();
        graph = new FrameGraph(device, 2);
        int white = materials.add(new Material(MR.withBaseColor(new Color(0.8f, 0.8f, 0.8f, 1))).feature(LAMBERT));
        wall(0, white, 0, -1, -4, UP, 4);
        wall(1, white, -1, -0.5f, -4, PLUS_Z, 0.5f);
        wall(2, white, 1.2f, -0.5f, -4.5f, PLUS_X, 0.5f);
        camera.position(0, 0, 0).set(0, -0.6, -1, 0, 1, 0, Math.PI / 2, size, size, 0.01);
        java.util.SplittableRandom random = new java.util.SplittableRandom(5);
        for (int i = 0; i < 40; i++) {
            lights.point(i, random.nextDouble(-3, 3), random.nextDouble(-0.9, 0.5), random.nextDouble(-7, -1),
                (float) random.nextDouble(0.5, 2.5), (float) random.nextDouble(0.1, 1), (float) random.nextDouble(0.1,
                    1), (float) random.nextDouble(0.1, 1));
        }
        lights.gpuLights(8);
        lights.writtenBy(GPU_LIGHTS);
        graph.add(new GpuLightWriter(lights, 8));
        ReferencePathTracer reference = traced(40, Environment.uniform("night", 0, 0, 0)).worldLights(lights);
        graph.settings().set(ReferencePathTracer.LIGHTS, lighting).set(WorldLights.MODE, mode)
            .set(WorldLights.CELL, 0.5f).set(WorldLights.SLOTS, mode == WorldLights.Mode.LISTS ? 64 : 16);
        return frames(frames);
    }

    /** The types test/singleHit.slang switches over, registered in its order; a material of each on quads. */
    private void everyType() {
        materials.register(Glow.TYPE);
        materials.register(Tinted.TYPE);
        materials.register(CarPaint.TYPE);
        materials.register(Dissolve.TYPE);
        int white = materials.add(new Material(MR.withBaseColor(new Color(0.8f, 0.8f, 0.8f, 1))).feature(LAMBERT));
        int coated = materials.add(new Material(MR.withBaseColor(new Color(0.2f, 0.4f, 0.9f, 1)).withRoughness(0.3f))
            .feature(com.truesim.engine.render.materials.ClearcoatParameters.DEFAULTS));
        int glow = materials.add(new Material(GlowParameters.DEFAULTS.withStrength(2)));
        int tinted = materials.add(new Material(TintedParameters.DEFAULTS));
        int paint = materials.add(new Material(CarPaint.TYPE, CarPaintParameters.DEFAULTS.withFlakes(1)
            .withFlakeColor(new Color(1, 1, 0, 1))));
        int dissolve = materials.add(new Material(DissolveParameters.DEFAULTS));
        wall(0, white, 0, -1, -2, UP, 3);
        wall(1, glow, -0.7f, 0, -2, DOWN, 0.4f);
        wall(2, paint, 0.9f, -0.4f, -2.5f, PLUS_Z, 0.4f);
        wall(3, dissolve, -0.2f, -0.5f, -2.2f, PLUS_Z, 0.3f);
        wall(4, coated, 0.3f, -0.8f, -1.8f, UP, 0.3f);
        wall(5, tinted, 0.6f, 0.4f, -2.6f, PLUS_Z, 0.2f);
        camera.set(0, -0.5, -1, 0, 1, 0, Math.PI / 2, size, size, 0.01);
    }

    @Test
    void oneShaderSwitchingOverTheTypesShadesHitsAsTheirBinsDo() {
        everyType();
        traced(0, null);
        float[] binned = frames(16);
        graph.close();
        graph = new FrameGraph(device, 2);
        traced(0, null).singleHitShader("shaders/test/singleHit.spv", "singleHitMain");
        float[] switched = frames(16);
        assertThat(graph.passes()).doesNotContain("reference bins 0", "reference scatter 0");
        double worst = 0;
        for (int i = 0; i < binned.length; i++) {
            worst = Math.max(worst, Math.abs(binned[i] - switched[i]) / Math.max(Math.abs(binned[i]), 1.0e-2));
        }
        // The same paths and the same shading: the same image, but for the features known at run time or not
        assertThat(worst).as("the most a value differs").isLessThan(1.0e-3);
    }

    /**
     * How many of {@code frames} frames begun and submitted (as an application runs them) allocated on the thread,
     * after as many to warm up: a per-frame allocation shows in every one, the compiler's once in a few.
     */
    static int framesAllocating(VulkanDevice device, FrameGraph graph, int frames) {
        com.sun.management.ThreadMXBean threads =
            (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
        int allocating = 0;
        for (int frame = -frames; frame < frames; frame++) {
            long before = threads.getCurrentThreadAllocatedBytes();
            graph.beginFrame();
            graph.submit(0, 0);
            if (frame >= 0 && threads.getCurrentThreadAllocatedBytes() != before) {
                allocating++;
            }
        }
        device.waitIdle();
        return allocating;
    }

    @Test
    void recordingTheReferenceAllocatesNothing() {
        for (WorldLights.Mode mode : WorldLights.Mode.values()) {
            lightsOnTheFloor(ReferencePathTracer.Lighting.GRID, mode, 1);
            // Nothing per frame: a frame allocating once in a while is the compiler's
            assertThat(framesAllocating(device, graph, 400)).as("frames allocating, %s", mode).isLessThan(8);
        }
    }

    @Test
    void theWorldLightGridsListsLightHitsAsEveryLightDoes() {
        float[] every = lightsOnTheFloor(ReferencePathTracer.Lighting.ALL, WorldLights.Mode.LISTS, 32);
        float[] listed = lightsOnTheFloor(ReferencePathTracer.Lighting.GRID, WorldLights.Mode.LISTS, 32);
        assertThat(graph.passes()).contains("world lights", "gpu lights");
        double sum = 0;
        double worst = 0;
        for (int p = 0; p < size * size; p++) {
            double a = luminance(every, p);
            sum += a;
            worst = Math.max(worst, Math.abs(luminance(listed, p) - a) / Math.max(a, 1.0e-3));
        }
        assertThat(sum / (size * size)).as("lit").isGreaterThan(0.05);
        // The same paths, the same lights (those reaching the cell): the same image, but for the order of the sums.
        assertThat(worst).as("the most a pixel differs").isLessThan(1.0e-3);
    }

    @Test
    void theWorldLightGridsReservoirsConvergeToEveryLight() {
        float[] every = lightsOnTheFloor(ReferencePathTracer.Lighting.ALL, WorldLights.Mode.LISTS, 256);
        float[] sampled = lightsOnTheFloor(ReferencePathTracer.Lighting.GRID, WorldLights.Mode.RESERVOIRS, 256);
        double a = 0;
        double b = 0;
        for (int p = 0; p < size * size; p++) {
            a += luminance(every, p);
            b += luminance(sampled, p);
        }
        // Unbiased: the mean the same, within what 256 samples of one light a point leave of noise.
        assertThat(b).as("the image's mean, resampled").isCloseTo(a, within(a * 0.02));
    }

    /**
     * The Cornell box path traced at 512 x 512, 4,096 samples (TRUESIM_SCREENSHOTS=true), into
     * build/screenshots/reference-cornell.png, with the noise left in it.
     */
    @Test
    @EnabledIfEnvironmentVariable(named = "TRUESIM_SCREENSHOTS", matches = "true")
    void cornellBoxScreenshot() throws IOException {
        size = 512;
        graph.settings().set(ReferencePathTracer.BOUNCES, 32);
        cornellBox();
        traced(0, Environment.uniform("night", 0, 0, 0));
        long start = System.nanoTime();
        float[] half = frames(2048);
        float[] full = frames(2048);
        double seconds = (System.nanoTime() - start) / 1e9;
        Path out = Files.createDirectories(Path.of("build", "screenshots"));
        writePng(full, size, size, 1, out.resolve("reference-cornell.png"));
        System.out.printf("Cornell box, %d x %d, 4096 samples in %.1f s; noise of 4 x 4 means %.4f%n", size, size,
            seconds, noise(half, full, size));
    }

    /**
     * Linear rgba floats as an sRGB PNG, times {@code exposure}, compressed above 1 (x / (1 + x) past 0.8, as a soft
     * shoulder) so lights keep their color.
     */
    static void writePng(float[] rgba, int width, int height, float exposure, Path file) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int p = y * width + x;
                int rgb = 0;
                for (int c = 0; c < 3; c++) {
                    double v = Math.max(0, rgba[4 * p + c] * exposure);
                    if (v > 0.8) {
                        double over = v - 0.8;
                        v = 0.8 + 0.2 * over / (0.2 + over);
                    }
                    double srgb = v <= 0.0031308 ? 12.92 * v : 1.055 * Math.pow(v, 1 / 2.4) - 0.055;
                    rgb = rgb << 8 | (int) Math.round(Math.min(1, srgb) * 255);
                }
                image.setRGB(x, y, rgb);
            }
        }
        ImageIO.write(image, "png", file.toFile());
    }
}
