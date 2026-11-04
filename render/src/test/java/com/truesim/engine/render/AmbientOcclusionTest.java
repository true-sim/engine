package com.truesim.engine.render;

import static com.truesim.testing.AllocationAssertions.assertAllocationFree;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_D32_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_UNDEFINED;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
import static org.lwjgl.vulkan.VK10.vkCmdCopyImageToBuffer;

import com.truesim.common.math.color.Color;
import com.truesim.engine.graph.Access;
import com.truesim.engine.graph.ExternalImage;
import com.truesim.engine.graph.FrameGraph;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Image;
import com.truesim.engine.graph.ImageDesc;
import com.truesim.engine.graph.Key;
import com.truesim.engine.graph.Technique;
import com.truesim.engine.render.materials.MetallicRoughnessParameters;
import com.truesim.engine.render.materials.SpecularParameters;
import com.truesim.engine.render.materials.UnlitParameters;
import com.truesim.engine.settings.EngineSettings;
import com.truesim.engine.settings.TechniqueConfig;
import com.truesim.engine.settings.TechniqueConfig.Tunable;
import com.truesim.engine.settings.Setting;
import com.truesim.engine.settings.TechniqueKind;
import com.truesim.engine.vulkan.Format;
import com.truesim.engine.vulkan.GpuBuffer;
import com.truesim.engine.vulkan.GpuImage;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.engine.vulkan.VulkanException;
import com.truesim.engine.vulkan.VulkanInstance;
import com.truesim.scene.assets.mesh.MeshData;
import java.lang.foreign.ValueLayout;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkBufferImageCopy;

/**
 * Ambient occlusion against closed forms and against itself traced: a floor at y = 0 and a block standing on it whose
 * side faces +x at x = 0, much taller and wider than the radius, so a floor point d from it (d below the radius r) has
 * the cosine-weighted occlusion of an infinite wall: 1 - (acos(d / r) - d / r sqrt(1 - (d / r)^2)) / pi, 1/2 at the
 * wall. Lit by a uniform white environment; the occlusion read as the image ({@link AmbientOcclusion#DEBUG}).
 */
class AmbientOcclusionTest {

    private static final int SIZE = 96;
    private static final float HALF = (float) Math.sqrt(0.5);
    private static final MetallicRoughnessParameters MR = MetallicRoughnessParameters.DEFAULTS;
    private static final EnvironmentMaps.Quality SMALL = new EnvironmentMaps.Quality(64, 32, 4, 16, 64);
    private static final float RADIUS = 0.5f;

    private VulkanInstance instance;
    private VulkanDevice device;
    private GeometryPool pool;
    private GpuScene scene;
    private MeshletRenderer renderer;
    private RenderView view;
    private RayTracing rayTracing;
    private FrameGraph graph;
    private GpuImage target;
    private ExternalImage output;
    private GpuBuffer pixels;
    private int quad;
    private int box;

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
        pool = new GeometryPool(device, GeometryPool.Capacity.of(1 << 14, 256, 8));
        MeshData quadData = ScreenSpaceReflectionsTest.quad();
        quad = pool.add(quadData);
        MeshData boxData = ScreenSpaceReflectionsTest.box();
        box = pool.add(boxData);
        scene = new GpuScene(device, pool, 64);
        renderer = new MeshletRenderer(device, scene, VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_D32_SFLOAT);
        view = renderer.createView(ViewOptions.of(4096, 1 << 16));
        if (device.rayTracing()) {
            rayTracing = new RayTracing(device, pool, 64);
            rayTracing.add(quad, quadData, quadData.levels().size() - 1);
            rayTracing.add(box, boxData, boxData.levels().size() - 1);
        }
        graph = new FrameGraph(device, 2);
        int usage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT
            | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
        target = new GpuImage(device, SIZE, SIZE, VK_FORMAT_R8G8B8A8_UNORM, usage, VK_IMAGE_ASPECT_COLOR_BIT);
        output = new ExternalImage("target", SIZE, SIZE, Format.R8G8B8A8_UNORM, usage, VK_IMAGE_LAYOUT_UNDEFINED,
            VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL).set(target.handle(), target.view());
        pixels = GpuBuffer.hostVisible(device, SIZE * SIZE * 4, VK_BUFFER_USAGE_TRANSFER_DST_BIT);
        tune("radius", RADIUS);
    }

    @AfterEach
    void stop() {
        if (device != null) {
            device.waitIdle();
            int errors = instance.validationErrors();
            graph.close();
            pixels.close();
            target.close();
            if (rayTracing != null) {
                rayTracing.close();
            }
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

    /** A floor of {@code material} at y = 0, 40 x 40 around the origin. */
    private void floor(int material) {
        scene.set(0, quad, material, 0, 0, 0, -HALF, 0, 0, HALF, 20, 20, 1);
    }

    /** A block of {@code material} from x = -4 to 0, y = 0 to 4, z = -20 to 20: a wall facing +x for the radius. */
    private void block(int id, int material, double x) {
        scene.set(id, box, material, x - 2, 2, 0, 0, 0, 0, 1, 2, 2, 20);
    }

    /** A diffuse white material without a specular lobe. */
    private static Material white() {
        return new Material(MR.withBaseColor(new Color(1, 1, 1, 1)).withRoughness(1))
            .feature(SpecularParameters.DEFAULTS.withSpecularFactor(0));
    }

    /** Looking down at the floor from 2 above (x to the right, z down the image), a 90 degree field of view. */
    private static Camera above() {
        return new Camera().position(1.5, 2, 0).set(0, -1, 0, 0, 0, -1, Math.PI / 2, SIZE, SIZE, 0.05);
    }

    /** Looking at the block's side and the floor before it at a slant: the wall and the corner both on screen. */
    private static Camera slanted() {
        return new Camera().position(2.2, 1.4, 0).set(-1, -0.7, 0, 0, 1, 0, Math.PI / 2, SIZE, SIZE, 0.05);
    }

    /** The main view, shaded by {@code shading}, lit by a uniform white environment, with ambient occlusion. */
    private SceneView main(Camera camera, Function<SceneView, Technique> shading) {
        view.set(camera, 1, MeshletRenderer.MODE_LIT);
        SceneView main = new SceneView(graph.addView("main").size(SIZE, SIZE), view, camera)
            .set(1, MeshletRenderer.MODE_LIT);
        Environment white = Environment.uniform("white", 1, 1, 1);
        main.port().add(shading.apply(main)).add(new EnvironmentLighting(white, main, SMALL))
            .add(new AmbientOcclusion(main, rayTracing)).output(Keys.SCENE_COLOR, output);
        return main;
    }

    private Technique visibilityBuffer(SceneView v) {
        return new VisibilityBufferShading(SceneGeometry.meshlets(renderer), v, VK_FORMAT_R8G8B8A8_UNORM,
            VK_FORMAT_D32_SFLOAT).clearColor(0, 0, 0);
    }

    /** {@code count} frames, the last one read back: each pixel's red, 0 to 1. */
    private float[] frames(int count) {
        for (int i = 0; i < count; i++) {
            device.submitAndWait(device.graphics(), commands -> {
                graph.record(commands);
                try (MemoryStack stack = stackPush()) {
                    VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
                    region.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
                    region.imageExtent().set(SIZE, SIZE, 1);
                    vkCmdCopyImageToBuffer(commands, target.handle(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                        pixels.handle(), region);
                }
            });
        }
        float[] image = new float[SIZE * SIZE];
        for (int i = 0; i < image.length; i++) {
            image[i] = (pixels.memory().getAtIndex(ValueLayout.JAVA_INT, i) & 0xff) / 255f;
        }
        return image;
    }

    /** Writes {@code image} as {@code name}.png into the directory TRUESIM_AO_IMAGES names, if set. */
    static void save(String name, float[] image) {
        String directory = System.getenv("TRUESIM_AO_IMAGES");
        if (directory == null) {
            return;
        }
        int[] rgb = new int[image.length];
        for (int i = 0; i < image.length; i++) {
            int v = Math.round(Math.max(0, Math.min(1, image[i])) * 255);
            rgb[i] = v | v << 8 | v << 16;
        }
        ScreenSpaceReflectionsTest.save(name, rgb, SIZE, directory);
    }

    /** The cosine-weighted occlusion of an infinite wall at distance {@code d} within radius {@code r}. */
    static double wall(double d, double r) {
        double a = Math.min(d / r, 1);
        return 1 - (Math.acos(a) - a * Math.sqrt(1 - a * a)) / Math.PI;
    }

    /** The floor's x at image column {@code column} from {@link #above()}. */
    private static double xOf(int column) {
        return 1.5 + 2 * (2 * (column + 0.5) / SIZE - 1);
    }

    @Test
    void tracedOcclusionMatchesTheWallsClosedForm() {
        assumeThat(device.rayTracing()).as("no ray tracing").isTrue();
        try (Materials materials = new Materials(device, 8)) {
            int white = materials.add(white());
            scene.setMaterials(materials);
            floor(white);
            block(1, white, 0);
            main(above(), this::visibilityBuffer);
            choose(AmbientOcclusion.RAYS).set(AmbientOcclusion.RAYS.setting("rays"), 512).set(Denoiser.PASSES, 0)
                .set(Denoiser.MAX_FRAMES, 1)
                .set(AmbientOcclusion.DEBUG, AmbientOcclusion.Show.OCCLUSION);
            float[] ao = frames(3);
            int row = SIZE / 2;
            double worst = 0;
            for (int column = 0; column < SIZE; column++) {
                double x = xOf(column);
                if (x < 0.02 || x > 1.2) {
                    continue; // the block's top, the pixel across its edge
                }
                double expected = wall(x, RADIUS);
                worst = Math.max(worst, Math.abs(ao[row * SIZE + column] - expected));
            }
            assertThat(worst).as("the most a floor pixel's traced occlusion is off the closed form").isLessThan(0.05);
            scene.setMaterials(null);
        }
    }

    @Test
    void anOpenFloorIsUnoccludedByEveryTechnique() {
        try (Materials materials = new Materials(device, 8)) {
            int white = materials.add(white());
            scene.setMaterials(materials);
            floor(white);
            scene.removeFrom(1);
            main(slanted(), this::visibilityBuffer);
            graph.settings().set(AmbientOcclusion.DEBUG, AmbientOcclusion.Show.OCCLUSION);
            for (TechniqueKind<?, AmbientOcclusion, Technique> mode : modes()) {
                choose(mode);
                float[] ao = frames(4);
                float least = 1;
                for (int row = SIZE / 2 + 4; row < SIZE; row++) { // the floor, below the horizon
                    for (int column = 0; column < SIZE; column++) {
                        least = Math.min(least, ao[row * SIZE + column]);
                    }
                }
                assertThat(least).as("%s: the least occlusion on an open floor", mode).isGreaterThan(0.97f);
            }
            scene.setMaterials(null);
        }
    }

    @Test
    void screenSpaceOcclusionComesCloseToTheTracedOne() {
        assumeThat(device.rayTracing()).as("no ray tracing").isTrue();
        try (Materials materials = new Materials(device, 8)) {
            int white = materials.add(white());
            scene.setMaterials(materials);
            floor(white);
            block(1, white, 0);
            main(slanted(), this::visibilityBuffer);
            graph.settings().set(AmbientOcclusion.DEBUG, AmbientOcclusion.Show.OCCLUSION);
            choose(AmbientOcclusion.RAYS).set(AmbientOcclusion.RAYS.setting("rays"), 512).set(Denoiser.PASSES, 0)
                .set(Denoiser.MAX_FRAMES, 1);
            float[] traced = frames(3);
            graph.settings().set(Denoiser.PASSES, 4).set(Denoiser.MAX_FRAMES, 30);
            for (TechniqueKind<?, AmbientOcclusion, Technique> mode : List.of(AmbientOcclusion.GTAO,
                AmbientOcclusion.BITMASK)) {
                choose(mode);
                float[] ao = frames(3);
                save("traced", traced);
                save(mode.id().toUpperCase(java.util.Locale.ROOT), ao);
                double sum = 0;
                int count = 0;
                for (int i = 0; i < ao.length; i++) {
                    if (traced[i] < 0.995f) { // where something occludes
                        sum += Math.abs(ao[i] - traced[i]);
                        count++;
                    }
                }
                assertThat(count).as("occluded pixels").isGreaterThan(SIZE * SIZE / 20);
                assertThat(sum / count).as("%s: the mean difference from the traced occlusion", mode)
                    .isLessThan(0.08);
            }
            scene.setMaterials(null);
        }
    }

    @Test
    void halfResolutionComesCloseToFullResolutionAndKeepsEdges() {
        try (Materials materials = new Materials(device, 8)) {
            int white = materials.add(white());
            scene.setMaterials(materials);
            floor(white);
            block(1, white, 0);
            main(slanted(), this::visibilityBuffer);
            graph.settings().set(AmbientOcclusion.DEBUG, AmbientOcclusion.Show.OCCLUSION);
            for (TechniqueKind<?, AmbientOcclusion, Technique> mode : modes()) {
                choose(mode);
                Setting<AmbientOcclusion.Resolution> resolution = mode.setting("resolution");
                graph.settings().set(resolution, AmbientOcclusion.Resolution.FULL);
                float[] full = frames(40);
                graph.settings().set(resolution, AmbientOcclusion.Resolution.HALF);
                float[] half = frames(40);
                graph.settings().set(resolution, AmbientOcclusion.Resolution.FULL);
                save(mode.id() + "-full", full);
                save(mode.id() + "-half", half);
                double sum = 0;
                for (int i = 0; i < half.length; i++) {
                    sum += Math.abs(half[i] - full[i]);
                }
                assertThat(sum / half.length).as("%s: the mean difference of half from full resolution", mode)
                    .isLessThan(0.02);
                // The open floor stays open up to the block's edge: no occlusion bled across depth edges
                float least = 1;
                for (int row = SIZE - 4; row < SIZE; row++) {
                    for (int column = 0; column < SIZE; column++) {
                        if (full[row * SIZE + column] > 0.99f) {
                            least = Math.min(least, half[row * SIZE + column]);
                        }
                    }
                }
                assertThat(least).as("%s: open floor at half resolution", mode).isGreaterThan(0.9f);
            }
            scene.setMaterials(null);
        }
    }

    @Test
    void anOccludedCornerDarkensOnlyTheDiffuseLight() {
        try (Materials materials = new Materials(device, 8)) {
            int white = materials.add(white());
            int unlit = materials.add(new Material(MR.withBaseColor(new Color(0.7f, 0.7f, 0.7f, 1)))
                .feature(UnlitParameters.DEFAULTS));
            int metal = materials.add(new Material(MR.withBaseColor(new Color(1, 1, 1, 1)).withMetallic(1)
                .withRoughness(0.5f)));
            scene.setMaterials(materials);
            floor(white);
            block(1, white, 0);
            main(slanted(), this::visibilityBuffer);
            tune("specular", false);
            tune("bounce", false);
            choose(null);
            float[] off = frames(3);
            choose(AmbientOcclusion.BITMASK);
            float[] on = frames(3);
            int darker = 0;
            for (int i = 0; i < on.length; i++) {
                darker += on[i] < off[i] - 0.05f ? 1 : 0;
            }
            assertThat(darker).as("diffuse pixels darkened in the corner").isGreaterThan(SIZE * SIZE / 50);
            // Unlit surfaces take no light from around: their image stays as it was
            floor(unlit);
            block(1, unlit, 0);
            choose(null);
            off = frames(3);
            choose(AmbientOcclusion.BITMASK);
            on = frames(3);
            float most = 0;
            for (int i = 0; i < on.length; i++) {
                most = Math.max(most, Math.abs(on[i] - off[i]));
            }
            assertThat(most).as("the most an unlit pixel changed").isLessThanOrEqualTo(1 / 255f);
            // Metal's reflections dim in the corner with specular occlusion
            floor(metal);
            block(1, metal, 0);
            choose(null);
            off = frames(3);
            tune("specular", true);
            choose(AmbientOcclusion.BITMASK);
            on = frames(3);
            darker = 0;
            for (int i = 0; i < on.length; i++) {
                darker += on[i] < off[i] - 0.02f ? 1 : 0;
            }
            assertThat(darker).as("reflections dimmed in the corner").isGreaterThan(SIZE * SIZE / 100);
            scene.setMaterials(null);
        }
    }

    @Test
    void unoccludedSurfacesKeepTheirColorExactly() {
        try (Materials materials = new Materials(device, 8)) {
            int white = materials.add(new Material(MR.withBaseColor(new Color(0.6f, 0.5f, 0.4f, 1))
                .withRoughness(0.4f)));
            scene.setMaterials(materials);
            floor(white);
            scene.removeFrom(1);
            main(slanted(), this::visibilityBuffer);
            choose(null);
            float[] off = frames(3);
            for (TechniqueKind<?, AmbientOcclusion, Technique> mode : modes()) {
                // The diffuse light's replacement is exact; the specular occlusion's cone (its aperture from the
                // occlusion, 90 degrees at 1) dims grazing reflections near the horizon a little
                for (boolean specular : new boolean[] {false, true}) {
                    choose(mode);
                    tune("specular", specular);
                    float[] on = frames(4);
                    float most = 0;
                    for (int i = 0; i < on.length; i++) {
                        most = Math.max(most, Math.abs(on[i] - off[i]));
                    }
                    assertThat(most).as("%s, specular occlusion %s: the most an open floor's pixel changed", mode,
                        specular).isLessThanOrEqualTo(specular ? 0.12f : 2 / 255f);
                }
            }
            scene.setMaterials(null);
        }
    }

    @Test
    void forwardAndVisibilityBufferShadingOccludeAlike() {
        try (Materials materials = new Materials(device, 8)) {
            int white = materials.add(white());
            scene.setMaterials(materials);
            floor(white);
            block(1, white, 0);
            SceneView main = main(slanted(), this::visibilityBuffer);
            graph.settings().set(AmbientOcclusion.DEBUG, AmbientOcclusion.Show.OCCLUSION);
            choose(AmbientOcclusion.BITMASK);
            float[] buffer = frames(3);
            main.port().replace(MeshletBenchmark.forward(main).clearColor(0, 0, 0));
            float[] forward = frames(3);
            double sum = 0;
            for (int i = 0; i < buffer.length; i++) {
                sum += Math.abs(buffer[i] - forward[i]);
            }
            assertThat(sum / buffer.length).as("the mean difference").isLessThan(0.01);
            scene.setMaterials(null);
        }
    }

    /**
     * The traced occlusion's lag (design D3): a block appearing beside the floor is no change the denoiser is told of
     * on the floor's pixels (they are still the floor); the fast history brings them within 10 % of what they settle
     * at in at most 15 frames, with either denoiser, and a still view's noise falls as the history grows.
     */
    @Test
    void tracedOcclusionFollowsAnUnannouncedChangeWithinFifteenFrames() {
        assumeThat(device.rayTracing()).as("no ray tracing").isTrue();
        try (Materials materials = new Materials(device, 8)) {
            int white = materials.add(white());
            scene.setMaterials(materials);
            floor(white);
            block(1, white, -50); // far away
            main(above(), this::visibilityBuffer);
            graph.settings().set(AmbientOcclusion.DEBUG, AmbientOcclusion.Show.OCCLUSION);
            choose(AmbientOcclusion.RAYS).set(AmbientOcclusion.RAYS.setting("rays"), 1);
            int row = SIZE / 2;
            int[] columns = new int[16];
            int found = 0;
            for (int column = 0; column < SIZE && found < columns.length; column++) {
                double x = xOf(column);
                if (x > 0.05 && x < 0.3) {
                    columns[found++] = column;
                }
            }
            for (Denoiser.Mode mode : Denoiser.Mode.values()) {
                tune("denoiser", mode);
                block(1, white, -50);
                frames(40); // settled without it
                block(1, white, 0);
                int lag = -1;
                float[][] seen = new float[60][];
                for (int f = 0; f < seen.length; f++) {
                    seen[f] = frames(1);
                }
                double settled = mean(seen[seen.length - 1], row, columns, found);
                double change = 1 - settled;
                assertThat(change).as("%s: the block's occlusion of the floor beside it", mode).isGreaterThan(0.2);
                for (int f = 0; f < seen.length && lag < 0; f++) {
                    if (Math.abs(mean(seen[f], row, columns, found) - settled) <= 0.1 * change) {
                        lag = f + 1;
                    }
                }
                assertThat(lag).as("%s: frames until the floor beside the block is within 10 %% of settled", mode)
                    .isBetween(1, 15);
            }
            scene.setMaterials(null);
        }
    }

    @Test
    void occludingFramesAllocateNothing() {
        try (Materials materials = new Materials(device, 8)) {
            int white = materials.add(white());
            scene.setMaterials(materials);
            floor(white);
            block(1, white, 0);
            main(slanted(), this::visibilityBuffer);
            for (TechniqueKind<?, AmbientOcclusion, Technique> mode : modes()) {
                for (Denoiser.Mode denoiser : Denoiser.Mode.values()) {
                    choose(mode);
                    tune("denoiser", denoiser);
                    tune("temporal", true);
                    frames(3);
                    long builds = graph.builds();
                    for (int i = 0; i < 5; i++) {
                        graph.beginFrame();
                        graph.submit(0, 0);
                    }
                    assertThat(graph.builds()).as("no rebuilds").isEqualTo(builds);
                    assertAllocationFree(() -> {
                        graph.beginFrame();
                        graph.submit(0, 0);
                    }, 8000, 300, 8);
                    device.waitIdle();
                }
            }
            scene.setMaterials(null);
        }
    }

    /** A game's own kind of ambient occlusion's tunables: the grey it paints. */
    record Grey(@Tunable(value = "the grey every pixel takes", min = 0, max = 1) float level)
        implements TechniqueConfig {
    }

    /** A kind of ambient occlusion the engine does not have, registered as a game would. */
    static final TechniqueKind<Grey, AmbientOcclusion, Technique> GREY = TechniqueKind.of(AmbientOcclusion.SLOT,
        "test-grey", new Grey(0.5f), "every pixel grey", (ao, config) -> new GreyOcclusion(config));

    /** {@link #GREY}'s technique: the scene's color cleared to its grey, read as frames record. */
    record GreyOcclusion(Supplier<Grey> config) implements Technique {

        @Override
        public List<Key<?>> provides() {
            return List.of(Keys.SPECULAR_OCCLUSION);
        }

        @Override
        public void build(GraphBuilder g) {
            Image color = g.modify(Keys.SCENE_COLOR);
            Image specular = g.create("grey specular occlusion", ImageDesc.of(AmbientOcclusion.SPECULAR_FORMAT, 1, 1));
            g.pass("grey").write(color, Access.TRANSFER_DST).write(specular, Access.TRANSFER_DST).record(ctx -> {
                float grey = config.get().level();
                ctx.clear(color.whole(), grey, grey, grey, 1);
                ctx.clear(specular.whole(), 1, 1, 1, 1);
            });
            g.provide(Keys.SPECULAR_OCCLUSION, specular);
        }
    }

    @Test
    void aGamesOwnKindIsChosenThroughTheSettingsAsTheEnginesAre() {
        try (Materials materials = new Materials(device, 8)) {
            int white = materials.add(white());
            scene.setMaterials(materials);
            floor(white);
            scene.removeFrom(1);
            main(above(), this::visibilityBuffer);
            float[] engine = frames(3);
            EngineSettings settings = graph.settings();
            settings.techniques().register(AmbientOcclusion.SLOT, GREY);
            assertThat(settings.techniques().variants(AmbientOcclusion.SLOT)).contains(AmbientOcclusion.BITMASK, GREY);
            settings.set(AmbientOcclusion.SLOT.setting(), "test-grey").set(GREY.setting("level"), 0.25f);
            assertThat(farthest(frames(3), 0.25f)).as("the game's kind, at its level").isLessThanOrEqualTo(1 / 255f);
            int builds = graph.builds();
            settings.set(GREY.<Float>setting("level"), 0.75f); // read as frames record: no rebuild
            assertThat(farthest(frames(2), 0.75f)).as("the level changed").isLessThanOrEqualTo(1 / 255f);
            assertThat(graph.builds()).as("rebuilds for a tunable read as frames record").isEqualTo(builds);
            // Saved and loaded again, as a settings file holds it
            settings.load(settings.toMap());
            assertThat(settings.toMap()).containsEntry("render.ao", "test-grey")
                .containsEntry("render.ao.test-grey.level", "0.75");
            assertThat(farthest(frames(2), 0.75f)).as("loaded").isLessThanOrEqualTo(1 / 255f);
            // The engine's kind again: its image as before
            AmbientOcclusion.SLOT.choose(settings, AmbientOcclusion.BITMASK);
            assertThat(frames(3)).containsExactly(engine);
            scene.setMaterials(null);
        }
    }

    /** How far the pixel of {@code image} farthest from {@code value} is from it. */
    private static float farthest(float[] image, float value) {
        float most = 0;
        for (float v : image) {
            most = Math.max(most, Math.abs(v - value));
        }
        return most;
    }

    /** The mean of {@code image} over {@code count} of {@code columns} in {@code row}. */
    private static double mean(float[] image, int row, int[] columns, int count) {
        double sum = 0;
        for (int k = 0; k < count; k++) {
            sum += image[row * SIZE + columns[k]];
        }
        return sum / count;
    }

    /** The engine's kinds the device runs: rays where it traces them. */
    private List<TechniqueKind<?, AmbientOcclusion, Technique>> modes() {
        return device.rayTracing() ? List.of(AmbientOcclusion.GTAO, AmbientOcclusion.BITMASK, AmbientOcclusion.RAYS)
            : List.of(AmbientOcclusion.GTAO, AmbientOcclusion.BITMASK);
    }

    /** Chooses {@code kind} of ambient occlusion (null: off) in the graph's settings, which it returns. */
    private EngineSettings choose(TechniqueKind<?, AmbientOcclusion, Technique> kind) {
        return AmbientOcclusion.SLOT.choose(graph.settings(), kind);
    }

    /** Sets the tunable {@code field} to {@code value} in every kind of ambient occlusion having it. */
    private void tune(String field, Object value) {
        graph.settings().techniques().set(graph.settings(), AmbientOcclusion.SLOT, field, value);
    }
}
