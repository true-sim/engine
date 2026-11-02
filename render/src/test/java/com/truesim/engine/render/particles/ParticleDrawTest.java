package com.truesim.engine.render.particles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.within;
import static org.assertj.core.api.Assertions.withinPercentage;
import static org.assertj.core.api.Assumptions.assumeThat;
import static com.truesim.testing.AllocationAssertions.assertAllocationFree;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
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

import com.truesim.common.jobs.JobSystem;
import com.truesim.common.math.color.Color;
import com.truesim.common.state.ChangeClock;
import com.truesim.engine.graph.Access;
import com.truesim.engine.graph.Anchor;
import com.truesim.engine.graph.ExternalImage;
import com.truesim.engine.graph.FrameGraph;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Image;
import com.truesim.engine.graph.Key;
import com.truesim.engine.graph.Order;
import com.truesim.engine.graph.ResolutionSettings;
import com.truesim.engine.graph.Technique;
import com.truesim.engine.graph.ViewPort;
import com.truesim.engine.render.Camera;
import com.truesim.engine.render.ClusteredForwardShading;
import com.truesim.engine.render.GeometryPool;
import com.truesim.engine.render.GpuScene;
import com.truesim.engine.render.Keys;
import com.truesim.engine.render.Material;
import com.truesim.engine.render.Materials;
import com.truesim.engine.render.MeshletRenderer;
import com.truesim.engine.render.SceneGeometry;
import com.truesim.engine.render.SceneView;
import com.truesim.engine.render.Textures;
import com.truesim.engine.render.ViewOptions;
import com.truesim.engine.render.WeightedBlendedTransparency;
import com.truesim.engine.render.materials.MetallicRoughnessParameters;
import com.truesim.engine.vulkan.Format;
import com.truesim.engine.vulkan.GpuBuffer;
import com.truesim.engine.vulkan.GpuImage;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.engine.vulkan.VulkanException;
import com.truesim.engine.vulkan.VulkanInstance;
import com.truesim.scene.assets.AssetId;
import com.truesim.scene.assets.AssetRef;
import com.truesim.scene.assets.AssetType;
import com.truesim.scene.assets.mesh.MeshData;
import com.truesim.scene.assets.mesh.VertexAttribute;
import com.truesim.scene.assets.mesh.VertexFormat;
import com.truesim.scene.core.SceneGraph;
import com.truesim.scene.effects.Bind;
import com.truesim.scene.effects.Collide;
import com.truesim.scene.effects.ColorGradient;
import com.truesim.scene.effects.CpuSimulation;
import com.truesim.scene.effects.Effect;
import com.truesim.scene.effects.Forces;
import com.truesim.scene.effects.Over;
import com.truesim.scene.effects.Shape;
import com.truesim.scene.effects.Target;
import com.truesim.scene.effects.EffectData;
import com.truesim.scene.effects.EffectRuntime;
import com.truesim.scene.effects.Effects;
import com.truesim.scene.effects.EmitterData;
import com.truesim.scene.effects.Init;
import com.truesim.scene.effects.Renderer;
import com.truesim.scene.effects.Spawn;
import com.truesim.scene.meshbuild.MeshBuilder;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.lwjgl.vulkan.VkBufferImageCopy;

/**
 * Particles drawn in a view: sprite coverage per facing, the soft edge against a wall (by depth and by volume),
 * additive and alpha blending over a dark scene, flipbook frames blended, three frames with occlusion's two phases, a
 * render region smaller than the image, and one thread or four drawing the same image. Validated, with synchronization
 * validation.
 */
class ParticleDrawTest {

    static final int SIZE = 64;
    private static final long FRAME = 16_667;
    private static final MetallicRoughnessParameters MR = MetallicRoughnessParameters.DEFAULTS;
    private static final AssetType<String> TEXTURE = AssetType.of("test.texture", String.class, s -> 0);
    private static final AssetRef<?> WHITE = AssetRef.of(TEXTURE, new AssetId(0, 1));
    private static final AssetRef<?> FLIPBOOK = AssetRef.of(TEXTURE, new AssetId(0, 2));

    private VulkanInstance instance;
    private VulkanDevice device;
    private FrameGraph graph;
    private GeometryPool pool;
    private GpuScene scene;
    private MeshletRenderer renderer;
    private Materials materials;
    private Textures textures;
    private Particles particles;
    private GpuImage target;
    private ExternalImage output;
    private GpuBuffer pixels;
    private SceneView view;
    private SceneGeometry geometry;
    private final java.util.ArrayList<AutoCloseable> closeLater = new java.util.ArrayList<>();
    private Camera camera;
    private int wall;
    private final SceneGraph nodes = new SceneGraph(new ChangeClock());
    private final Effects effects = new Effects(nodes);
    private final EffectRuntime runtime = new EffectRuntime(effects);
    private final CpuSimulation cpu = new CpuSimulation(runtime, null);

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
        graph = new FrameGraph(device, 2);
        pool = new GeometryPool(device, GeometryPool.Capacity.of(64, 4, 4), graph.frameSlots());
        scene = new GpuScene(device, pool, 4, graph.frameSlots());
        renderer = new MeshletRenderer(device, scene, VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_D32_SFLOAT,
            graph.frameSlots());
        materials = new Materials(device, 8, graph.frameSlots());
        textures = new Textures(device, graph.frameSlots());
        scene.setMaterials(materials);
        particles = new Particles(device, graph.frameSlots(), 24 << 20, 64); // room for 10,000 trails
        int white = textures.color(1, 1, MemorySegment.ofArray(new int[] {0xffffffff}));
        int flipbook = textures.color(2, 1, MemorySegment.ofArray(new int[] {0xff0000ff, 0xff00ff00})); // red, green
        particles.texture(WHITE, white);
        particles.texture(FLIPBOOK, flipbook);
        wall = pool.add(quad());
        int usage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT
            | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
        target = new GpuImage(device, SIZE, SIZE, VK_FORMAT_R8G8B8A8_UNORM, usage, VK_IMAGE_ASPECT_COLOR_BIT);
        output = new ExternalImage("target", SIZE, SIZE, Format.R8G8B8A8_UNORM, usage, VK_IMAGE_LAYOUT_UNDEFINED,
            VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL).set(target.handle(), target.view());
        pixels = GpuBuffer.hostVisible(device, 4L * SIZE * SIZE, VK_BUFFER_USAGE_TRANSFER_DST_BIT);
        camera = new Camera().set(0, 0, -1, 0, 1, 0, Math.PI / 2, SIZE, SIZE, 0.1); // at the origin, looking down -z
        geometry = SceneGeometry.meshlets(renderer);
        ViewPort port = graph.addView("main").size(SIZE, SIZE);
        view = geometry.createView(port, camera, ViewOptions.of(256, 1 << 12).withOcclusion(true));
        port.add(new ClusteredForwardShading(geometry, view, VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_D32_SFLOAT)
                .clearColor(0, 0, 0))
            .add(new WeightedBlendedTransparency(geometry, view)).add(new ParticleDraw(particles, view))
            .add(new Capture(pixels)).output(Keys.SCENE_COLOR, output);
        graph.add(new ParticleSimulation(particles));
    }

    @AfterEach
    void stop() {
        if (device != null) {
            device.waitIdle();
            int errors = instance.validationErrors();
            graph.close();
            for (AutoCloseable c : closeLater) {
                try {
                    c.close();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }
            particles.close();
            pixels.close();
            target.close();
            textures.close();
            materials.close();
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

    /** A 2 x 2 quad in z = 0 facing +z. */
    private static MeshData quad() {
        float[] positions = {-1, -1, 0, 1, -1, 0, 1, 1, 0, -1, 1, 0};
        float[] normals = {0, 0, 1, 0, 0, 1, 0, 0, 1, 0, 0, 1};
        return new MeshBuilder(VertexFormat.of(VertexAttribute.NORMAL), 4).positions(positions)
            .attribute(VertexAttribute.NORMAL, normals).submesh(new int[] {0, 1, 2, 0, 2, 3}).buildFlat();
    }

    /** Copies the scene's color into {@code pixels} after everything drawn over it. */
    private record Capture(GpuBuffer pixels) implements Technique {
        @Override
        public List<Key<?>> provides() {
            return List.of();
        }

        @Override
        public List<Key<?>> modifies() {
            return List.of(Keys.SCENE_COLOR);
        }

        @Override
        public Order order() {
            return Order.at(Anchor.POST);
        }

        @Override
        public void build(GraphBuilder g) {
            Image color = g.modify(Keys.SCENE_COLOR);
            g.pass("capture").read(color, Access.TRANSFER_SRC).sideEffects().record(ctx -> {
                com.truesim.engine.vulkan.Barriers.memory(ctx.unsafe().commands(),
                    org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_TRANSFER_BIT,
                    org.lwjgl.vulkan.VK13.VK_ACCESS_2_TRANSFER_WRITE_BIT,
                    org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_TRANSFER_BIT,
                    org.lwjgl.vulkan.VK13.VK_ACCESS_2_TRANSFER_WRITE_BIT);
                try (var stack = stackPush()) {
                    VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
                    region.bufferRowLength(SIZE).imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                        .layerCount(1);
                    region.imageExtent().set(Math.min(SIZE, color.width()), Math.min(SIZE, color.height()), 1);
                    org.lwjgl.vulkan.VK10.vkCmdCopyImageToBuffer(ctx.unsafe().commands(), ctx.unsafe().image(color),
                        VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, pixels.handle(), region);
                }
            });
            g.provide(Keys.SCENE_COLOR, color);
        }
    }

    /** A black wall's material, emitting nothing. */
    private int blackWall() {
        return materials.add(new Material(MR.withBaseColor(new Color(0, 0, 0, 1)).withRoughness(1)));
    }

    /** An effect of one emitter: {@code count} particles at once, still, living long, set up by {@code setup}. */
    private static EffectData still(int count, Consumer<EmitterData.Builder> setup) {
        return EffectData.builder().emitter("e", e -> setup.accept(e.spawn(Spawn.burst(0, count))
            .init(Init.lifetime(100)))).build();
    }

    /** Frames of the runtime, the particles and the graph; the image read back after the last. */
    private int[] frames(int n) {
        return frames(n, FRAME);
    }

    /** {@link #frames(int)} of {@code ticks} each: 0 for a paused world. */
    private int[] frames(int n, long ticks) {
        for (int f = 0; f < n; f++) {
            nodes.update();
            runtime.update(ticks);
            cpu.update();
            graph.beginFrame();
            particles.update(effects, runtime.spawns(), cpu);
            view.set(1, MeshletRenderer.MODE_LIT);
            graph.submit(0, 0);
        }
        device.waitIdle();
        return pixels.memory().toArray(ValueLayout.JAVA_INT);
    }

    /** {@link #frames(int)} through the runtime's decisions: drawn fractions, levels of detail, placements. */
    private int[] runtimeFrames(int n) {
        for (int f = 0; f < n; f++) {
            nodes.update();
            runtime.update(FRAME);
            cpu.update();
            graph.beginFrame();
            particles.update(runtime, cpu);
            view.set(1, MeshletRenderer.MODE_LIT);
            graph.submit(0, 0);
        }
        device.waitIdle();
        return pixels.memory().toArray(ValueLayout.JAVA_INT);
    }

    /** The sum of channel {@code c} over the image. */
    private static long sum(int[] image, int c) {
        long sum = 0;
        for (int pixel : image) {
            sum += channel(pixel, c);
        }
        return sum;
    }

    private static int channel(int pixel, int c) {
        return (pixel >> (8 * c)) & 0xff;
    }

    private static int at(int[] image, int x, int y, int c) {
        return channel(image[y * SIZE + x], c);
    }

    /** How many pixels have channel {@code c} above {@code threshold}. */
    private static int covered(int[] image, int c, int threshold) {
        int n = 0;
        for (int pixel : image) {
            n += channel(pixel, c) > threshold ? 1 : 0;
        }
        return n;
    }

    /** A white sprite renderer facing as {@code facing}. */
    private static Renderer sprite(Renderer.Facing facing) {
        return Renderer.billboard(facing).asset("texture", WHITE);
    }

    @Test
    void spritesCoverWhatTheirSizeAndFacingSay() {
        // A 1 m sprite 4 m away: 8 pixels a metre at 64 pixels for 90 degrees, so 8 x 8.
        effects.play(still(1, e -> e.init(Init.size(Bind.constant(1)), Init.color(Bind.color(1, 0, 0, 1)))
            .render(sprite(Renderer.Facing.POSITION))), 0, 0, -4);
        int[] image = frames(3);
        assertThat(covered(image, 0, 128)).as("position-facing").isBetween(56, 72);
        assertThat(at(image, SIZE / 2, SIZE / 2, 0)).isGreaterThan(250);
        // View plane and axis alike from here; velocity-facing stretched by speed: 1 m/s, 1 size per m/s.
        effects.play(still(1, e -> e.init(Init.size(Bind.constant(1)), Init.color(Bind.color(0, 1, 0, 1)))
            .render(sprite(Renderer.Facing.VIEW_PLANE))), -2, 0, -4);
        effects.play(still(1, e -> e.init(Init.size(Bind.constant(1)), Init.color(Bind.color(0, 0, 1, 1)))
            .render(sprite(Renderer.Facing.AXIS))), 2, 0, -4);
        effects.play(still(1, e -> e.init(Init.size(Bind.constant(1)), Init.color(Bind.color(1, 1, 0, 1)),
                Init.velocity(Bind.constant(0), Bind.constant(0.01f), Bind.constant(0)))
            .render(sprite(Renderer.Facing.VELOCITY).set("stretch", 100f))), 0, 2, -4);
        image = frames(3);
        int green = 0;
        int blue = 0;
        int yellow = 0;
        for (int pixel : image) {
            boolean r = channel(pixel, 0) > 128;
            boolean g = channel(pixel, 1) > 128;
            boolean b = channel(pixel, 2) > 128;
            green += g && !r ? 1 : 0;
            blue += b ? 1 : 0;
            yellow += r && g ? 1 : 0;
        }
        assertThat(green).as("view-plane").isBetween(56, 72);
        assertThat(blue).as("axis").isBetween(56, 72);
        assertThat(yellow).as("velocity: twice as tall (clipped by the image's top)").isBetween(80, 136);
        assertThat(graph.passes()).contains("particle cull", "particle draw");
    }

    @Test
    void softParticlesFadeIntoAWallAndHideBehindIt() {
        scene.set(0, wall, blackWall(), 0, 0, -4.1, 0, 0, 0, 1, 4, 4, 4);
        Consumer<EmitterData.Builder> green = e -> e.init(Init.size(Bind.constant(1)),
            Init.color(Bind.color(0, 0.5f, 0, 1)));
        // Hard, soft by 1 m, by volume, and behind the wall: side by side.
        effects.play(still(1, e -> green.accept(e.render(sprite(Renderer.Facing.POSITION).additive()))), -1.5, 1.5, -4);
        effects.play(still(1, e -> green.accept(e.render(sprite(Renderer.Facing.POSITION).additive()
            .set("soft", 1f)))), 1.5, 1.5, -4);
        effects.play(still(1, e -> green.accept(e.render(sprite(Renderer.Facing.POSITION).additive()
            .set("soft", 1f).set("volume", 1)))), -1.5, -1.5, -4);
        effects.play(still(1, e -> green.accept(e.render(sprite(Renderer.Facing.POSITION).additive()))), 1.5, -1.5,
            -4.5);
        int[] image = frames(3);
        int hard = at(image, 20, 20, 1);
        int soft = at(image, 44, 20, 1);
        int volume = at(image, 20, 44, 1);
        int behind = at(image, 44, 44, 1);
        assertThat(hard).as("hard: all of it").isBetween(120, 135);
        assertThat(soft).as("0.1 m before the wall, over 1 m: a tenth").isBetween(8, 20);
        assertThat(volume).as("by volume: 0.6 of the ray through the sphere").isBetween(65, 90);
        assertThat(behind).as("behind the wall").isLessThan(3);
    }

    @Test
    void additiveAddsUpAndAlphaCoversOverADarkScene() {
        Consumer<EmitterData.Builder> red = e -> e.init(Init.size(Bind.constant(2)),
            Init.color(Bind.color(0.3f, 0, 0, 1)));
        effects.play(still(1, e -> red.accept(e.render(sprite(Renderer.Facing.POSITION).additive()))), -0.5, 1, -4);
        effects.play(still(1, e -> red.accept(e.render(sprite(Renderer.Facing.POSITION).additive()))), 0.5, 1, -4);
        Consumer<EmitterData.Builder> half = e -> e.init(Init.size(Bind.constant(2)),
            Init.color(Bind.color(0, 1, 0, 0.5f)));
        effects.play(still(1, e -> half.accept(e.render(sprite(Renderer.Facing.POSITION)))), -0.5, -1.5, -4.01);
        effects.play(still(1, e -> half.accept(e.render(sprite(Renderer.Facing.POSITION)))), 0.5, -1.5, -4);
        int[] image = frames(3);
        assertThat(at(image, 26, 24, 0)).as("one additive sprite").isCloseTo(77, within(4));
        assertThat(at(image, 32, 24, 0)).as("two added").isCloseTo(153, within(5));
        assertThat(at(image, 26, 44, 1)).as("half covering").isCloseTo(128, within(5));
        assertThat(at(image, 32, 44, 1)).as("two halves: three quarters").isCloseTo(191, within(5));
    }

    @Test
    void flipbookFramesBlendAndRoundSpritesNeedNoTexture() {
        // Two frames over a 4 s life: at 1 s, halfway from red to green.
        effects.play(EffectData.builder().emitter("e", e -> e.spawn(Spawn.burst(0, 1))
            .init(Init.lifetime(4), Init.size(Bind.constant(2)))
            .render(Renderer.billboard(Renderer.Facing.POSITION).asset("texture", FLIPBOOK)
                .set("flipbook.columns", 2))).build(), -1.5, 0, -4);
        effects.play(EffectData.builder().emitter("e", e -> e.spawn(Spawn.burst(0, 1))
            .init(Init.lifetime(4), Init.size(Bind.constant(2)))
            .render(Renderer.billboard(Renderer.Facing.POSITION).asset("texture", FLIPBOOK)
                .set("flipbook.columns", 2).set("flipbook.blend", 0))).build(), 1.5, 0, -4);
        effects.play(still(1, e -> e.init(Init.size(Bind.constant(2)), Init.color(Bind.color(0, 0, 1, 1)))
            .render(Renderer.billboard(Renderer.Facing.POSITION))), 0, -2, -4);
        int[] image = frames(60);
        int blended = image[32 * SIZE + 20];
        assertThat(channel(blended, 0)).as("red, blended").isBetween(100, 160);
        assertThat(channel(blended, 1)).as("green, blended").isBetween(100, 160);
        int unblended = image[32 * SIZE + 44];
        assertThat(channel(unblended, 0)).as("frame 0 only").isGreaterThan(240);
        assertThat(channel(unblended, 1)).isLessThan(15);
        assertThat(at(image, 32, 48, 2)).as("a round sprite: full in its middle").isGreaterThan(240);
        assertThat(at(image, 32 - 7, 48 - 7, 2)).as("nothing in its corners").isLessThan(10);
    }

    @Test
    void particlesStayAcrossOcclusionPhasesAndRenderScales() {
        scene.set(0, wall, blackWall(), 0, 0, -6, 0, 0, 0, 1, 4, 4, 4);
        effects.play(still(1, e -> e.init(Init.size(Bind.constant(1)), Init.color(Bind.color(1, 0, 0, 1)))
            .render(sprite(Renderer.Facing.POSITION))), 0, 0, -4);
        for (int f = 1; f <= 3; f++) { // the first frame finds the wall late, the others early
            assertThat(covered(frames(1), 0, 128)).as("frame " + f).isBetween(56, 72);
        }
        graph.settings().set(ResolutionSettings.DYNAMIC, true).set(ResolutionSettings.HEADROOM, 1.5f);
        view.port().renderScale(0.5f);
        int[] image = frames(3);
        assertThat(view.port().regionWidth()).isLessThan(view.port().width());
        // In the region's top left corner, a quarter of the pixels: 4 x 4 around the region's middle.
        int middle = view.port().regionWidth() / 2;
        assertThat(at(image, middle, middle, 0)).isGreaterThan(250);
        assertThat(covered(image, 0, 128)).as("at half").isBetween(12, 25);
    }

    @Test
    void oneThreadAndFourDrawTheSameImage() {
        effects.play(EffectData.builder().emitter("e", e -> e.spawn(Spawn.rate(200)).init(Init.lifetime(2),
                Init.size(Bind.random(0.2f, 0.6f)), Init.color(Bind.color(0.4f, 0.3f, 0.2f, 0.6f)),
                Init.velocity(Bind.random(-1, 1), Bind.random(-1, 1), Bind.random(-0.5f, 0.5f)))
            .render(Renderer.billboard(Renderer.Facing.POSITION).alphaToAdd(0.5f).set("soft", 0.5f))).build(),
            0, 0, -4);
        frames(20);
        try (JobSystem jobs = new JobSystem(4)) {
            int[][] images = new int[2][];
            for (int run = 0; run < 2; run++) {
                graph.parallel(run == 0 ? null : jobs, 1);
                for (int f = 0; f < 3; f++) { // frames without an update: the same particles
                    graph.beginFrame();
                    view.set(1, MeshletRenderer.MODE_LIT);
                    graph.submit(0, 0);
                }
                device.waitIdle();
                images[run] = pixels.memory().toArray(ValueLayout.JAVA_INT);
            }
            graph.parallel(null, 1);
            assertThat(images[1]).isEqualTo(images[0]);
            assertThat(covered(images[0], 0, 20)).as("something drawn").isGreaterThan(20);
        }
    }

    /** 60 dim additive sprites spread over a square of 6 m, 8 m away. */
    private static EffectData spread(com.truesim.scene.effects.EffectType type) {
        return EffectData.builder(type).emitter("e", e -> e.spawn(Spawn.burst(0, 60))
            .init(Shape.box(6, 6, 0.1f), Init.lifetime(100), Init.size(Bind.constant(0.4f)),
                Init.color(Bind.color(0.3f, 0, 0, 1)))
            .render(sprite(Renderer.Facing.POSITION).additive())).build();
    }

    @Test
    void levelsOfDetailDrawAFractionOfTheParticlesFadingTheRestOutAndKeepingTheirCover() {
        runtime.addViewer(0, 0, 0);
        com.truesim.scene.effects.Effect first = effects.play(spread(com.truesim.scene.effects.EffectType.DEFAULT), 0,
            0, -8);
        int[] full = runtimeFrames(3);
        first.kill();
        // Beyond 5 m, a third drawn: the change fades in over 0.3 s, not at once
        long reducing = effects.play(spread(com.truesim.scene.effects.EffectType.builder("lod").lod(5, 1, 1, 0.33f)
            .build()), 0, 0, -8).id();
        runtimeFrames(1);
        assertThat(runtime.drawn(reducing)).as("drawn as decided at once when it starts").isEqualTo(0.33f);
        int[] reduced = runtimeFrames(3);
        int differing = 0;
        for (int i = 0; i < full.length; i++) {
            differing += Math.abs(channel(full[i], 0) - channel(reduced[i], 0)) > 20 ? 1 : 0;
        }
        assertThat(differing).as("other particles drawn, larger").isGreaterThan(covered(full, 0, 10) / 4);
        assertThat((double) sum(reduced, 0)).as("the kept grown to keep the cover").isCloseTo(sum(full, 0),
            withinPercentage(40));
    }

    @Test
    void halfResolutionParticlesLookAsFullOnesAndStayBehindTheEdgesOfNearerSurfaces() {
        // A wall covering the left half nearer than the sprites, which cover the view
        scene.set(0, wall, blackWall(), -2, 0, -3, 0, 0, 0, 1, 1, 4, 1);
        Consumer<EmitterData.Builder> red = e -> e.init(Init.size(Bind.constant(8)),
            Init.color(Bind.color(0.6f, 0, 0, 1)));
        com.truesim.scene.effects.Effect first = effects.play(still(1, e -> red.accept(e.render(
            sprite(Renderer.Facing.POSITION).additive()))), 0, 0, -4);
        int[] full = frames(3);
        first.kill();
        effects.play(still(1, e -> red.accept(e.render(sprite(Renderer.Facing.POSITION).additive().set("half", 1)))),
            0, 0, -4);
        int[] half = frames(4);
        assertThat(particles.drawsHalf()).isTrue();
        int differing = 0;
        for (int i = 0; i < full.length; i++) {
            differing += Math.abs(channel(full[i], 0) - channel(half[i], 0)) > 24 ? 1 : 0;
        }
        assertThat(differing).as("pixels apart, at edges only").isLessThan(full.length / 20);
        assertThat(at(half, 4, 32, 0)).as("the wall's middle stays dark").isLessThan(3);
        assertThat(at(half, 50, 32, 0)).as("drawn beside it").isGreaterThan(60);
    }

    @Test
    void placementsDrawTheirVariantsWhereTheyAreMovedScaledAndStoppedAlone() {
        EffectData shared = EffectData.builder().emitter("e", e -> e.space(com.truesim.scene.effects.Space.LOCAL)
                .spawn(Spawn.burst(0, 1)).duration(0.25).loops(0).init(Init.lifetime(0.5f), Init.size(Bind.constant(0.6f)),
                    Init.color(Bind.color(0, 0.6f, 0, 1)))
                .render(sprite(Renderer.Facing.POSITION).additive()))
            .shared(1).build();
        com.truesim.scene.effects.Effect left = effects.play(shared, -2, 0, -6);
        com.truesim.scene.effects.Effect right = effects.play(shared, 2, 0, -6);
        int[] image = runtimeFrames(3);
        assertThat(particles.emitterCount()).as("one simulation").isEqualTo(1);
        assertThat(at(image, 21, 32, 1)).as("drawn on the left").isGreaterThan(80);
        assertThat(at(image, 43, 32, 1)).as("and on the right").isGreaterThan(80);
        int small = covered(image, 1, 20);
        right.scale(2.5f);
        left.moveTo(-2, 2, -6);
        image = runtimeFrames(2);
        assertThat(at(image, 21, 32, 1)).as("moved away").isLessThan(20);
        assertThat(at(image, 21, 21, 1)).as("to where it is now").isGreaterThan(80);
        assertThat(covered(image, 1, 20)).as("scaled").isGreaterThan(2 * small);
        assertThatIllegalStateException().isThrownBy(() -> left.inheritVelocity(1));
        left.stop();
        image = runtimeFrames(45);
        assertThat(at(image, 21, 21, 1)).as("stopped: faded out").isLessThan(20);
        assertThat(at(image, 43, 32, 1)).as("the other still").isGreaterThan(80);
    }

    @Test
    void particlesBeforeGlassAreTintedByItAndThoseAfterItAreNot() {
        int glass = materials.add(new Material(MR.withBaseColor(new Color(0, 0, 1, 0.6f)).withRoughness(1))
            .alphaBlend());
        scene.set(0, wall, glass, 0, 0, -3, 0, 0, 0, 1, 4, 4, 4);
        view.port().add(ParticleDraw.beforeGlass(particles, view, null));
        Consumer<EmitterData.Builder> red = e -> e.init(Init.size(Bind.constant(1.5f)),
            Init.color(Bind.color(1, 0, 0, 1)));
        effects.play(still(1, e -> red.accept(e.render(sprite(Renderer.Facing.POSITION).set("glass", 1)))), -1.2, 0,
            -5);
        effects.play(still(1, e -> red.accept(e.render(sprite(Renderer.Facing.POSITION)))), 1.2, 0, -5);
        int[] image = frames(3);
        int before = at(image, 24, 32, 0);
        int after = at(image, 40, 32, 0);
        assertThat(after).as("after the glass: over it, untinted").isGreaterThan(200);
        assertThat(before).as("before the glass: behind its tint").isLessThan(after - 60);
    }

    @Test
    void theOverdrawHeatMapAddsALayerAtATime() {
        view.port().remove(ParticleDraw.ID);
        view.port().add(new ParticleDraw(particles, view).overdraw(true));
        Consumer<EmitterData.Builder> faint = e -> e.init(Init.size(Bind.constant(2)),
            Init.color(Bind.color(0.01f, 0.01f, 0.01f, 0.05f)));
        effects.play(still(1, e -> faint.accept(e.render(sprite(Renderer.Facing.POSITION)))), -0.5, 0, -4);
        effects.play(still(1, e -> faint.accept(e.render(sprite(Renderer.Facing.POSITION)))), 0.5, 0, -4.01);
        int[] image = frames(3);
        int one = at(image, 26, 32, 0);
        assertThat(one).as("a layer, however faint").isCloseTo(26, within(3));
        assertThat(at(image, 32, 32, 0)).as("two layers").isCloseTo(2 * one, within(3));
    }

    @Test
    void theReactiveMaskCoversWhereParticlesAre() {
        GpuBuffer mask = GpuBuffer.hostVisible(device, 4L * SIZE * SIZE, VK_BUFFER_USAGE_TRANSFER_DST_BIT);
        closeLater.add(mask);
        view.port().add(new ReactiveCapture(mask));
        effects.play(still(1, e -> e.init(Init.size(Bind.constant(1)), Init.color(Bind.color(0, 1, 0, 1)))
            .render(sprite(Renderer.Facing.POSITION))), 1, 1, -4);
        frames(3);
        byte[] coverage = mask.memory().asSlice(0, (long) SIZE * SIZE).toArray(ValueLayout.JAVA_BYTE);
        assertThat(Byte.toUnsignedInt(coverage[24 * SIZE + 40])).as("where the sprite is").isGreaterThan(200);
        assertThat(Byte.toUnsignedInt(coverage[50 * SIZE + 10])).as("elsewhere").isZero();
    }

    /** Copies the view's reactive mask into {@code mask} (R8), its color as it is. */
    private record ReactiveCapture(GpuBuffer mask) implements Technique {
        @Override
        public List<Key<?>> provides() {
            return List.of();
        }

        @Override
        public List<Key<?>> modifies() {
            return List.of(Keys.SCENE_COLOR);
        }

        @Override
        public Order order() {
            return Order.at(Anchor.POST);
        }

        @Override
        public void build(GraphBuilder g) {
            Image color = g.modify(Keys.SCENE_COLOR);
            Image reactive = g.require(ParticleKeys.REACTIVE);
            g.pass("reactive capture").read(reactive, Access.TRANSFER_SRC).sideEffects().record(ctx -> {
                    try (var stack = stackPush()) {
                        VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
                        region.bufferRowLength(SIZE).imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                            .layerCount(1);
                        region.imageExtent().set(SIZE, SIZE, 1);
                        org.lwjgl.vulkan.VK10.vkCmdCopyImageToBuffer(ctx.unsafe().commands(),
                            ctx.unsafe().image(reactive), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, mask.handle(), region);
                    }
                });
            g.provide(Keys.SCENE_COLOR, color);
        }
    }

    @Test
    void aParticleDrawIsNamedAndDrawsOverTheBlendedMaterials() {
        ParticleDraw draw = new ParticleDraw(particles, view);
        assertThat(draw.id()).isEqualTo(ParticleDraw.ID);
        assertThat(draw.provides()).containsExactly(ParticleKeys.REACTIVE); // drawn where something requires it
        assertThat(draw.order()).isEqualTo(Order.at(Anchor.TRANSPARENT).after(com.truesim.engine.render.Transparency.ID));
        ParticleDraw before = ParticleDraw.beforeGlass(particles, view, null);
        assertThat(before.id()).isEqualTo(ParticleDraw.BEFORE_GLASS_ID);
        assertThat(before.provides()).isEmpty();
        assertThat(before.order()).isEqualTo(Order.at(Anchor.TRANSPARENT)
            .before(com.truesim.engine.render.Transparency.ID));
    }

    /** Sparks and puffs, on {@code target}: non-chaotic modules, so the CPU and GPU stay within a pixel. */
    private static EffectData fountain(Target target) {
        return EffectData.builder().emitter("e", e -> e.target(target).spawn(Spawn.rate(80), Spawn.burst(0, 30))
            .init(Shape.cone(0.5f, 0.2f), Init.speed(Bind.random(2, 4)), Init.lifetime(Bind.random(0.8f, 1.5f)),
                Init.size(Bind.random(0.2f, 0.4f)), Init.color(Bind.color(0.6f, 0.4f, 0.2f, 0.7f)))
            .update(Forces.gravity(), Forces.drag(0.5f), Over.color(ColorGradient.builder().linear(0, 1, 1, 1, 1)
                .linear(1, 0.2f, 0.2f, 1, 0).build()))
            .render(Renderer.billboard(Renderer.Facing.POSITION).alphaToAdd(0.5f))).build();
    }

    @Test
    void aCpuEmitterDrawsAsItsGpuTwin() {
        int[][] images = new int[2][];
        for (Target target : new Target[] {Target.GPU, Target.CPU}) {
            Effect effect = effects.play(fountain(target), 0, -1.5, -4).seed(21);
            images[target.ordinal()] = frames(40);
            if (target == Target.CPU) {
                long instance = particles.instance(effect.id(), 0);
                assertThat(instance).as("uploaded into a range").isNotNegative();
                for (int f = 0; f < 3; f++) { // the statistics come back late: frames that do not simulate
                    graph.beginFrame();
                    view.set(1, MeshletRenderer.MODE_LIT);
                    graph.submit(0, 0);
                }
                device.waitIdle();
                assertThat(particles.alive(effect.id(), 0)).isEqualTo(cpu.count(effect.id(), 0)).isPositive();
            }
            effect.kill();
        }
        int differing = 0;
        long sum = 0;
        for (int i = 0; i < images[0].length; i++) {
            for (int c = 0; c < 3; c++) {
                int d = Math.abs(channel(images[0][i], c) - channel(images[1][i], c));
                differing += d > 8 ? 1 : 0;
                sum += d;
            }
        }
        System.out.printf("CPU against GPU emitter: %d channels off by more than 8, mean difference %.3f per pixel%n",
            differing, (double) sum / images[0].length);
        assertThat(covered(images[0], 0, 20)).as("something drawn").isGreaterThan(50);
        assertThat(differing).as("channels off by more than 8 of 255").isLessThan(images[0].length * 3 / 100);
        assertThat((double) sum / images[0].length).as("mean difference per pixel").isLessThan(2.0);
    }

    @Test
    void gameWrittenRecordsDrawAndAllocateNothing() {
        EffectData shards = EffectData.builder().emitter("shards", e -> e.target(Target.CPU).capacity(8)
            .spawn(Spawn.rate(1)).render(sprite(Renderer.Facing.POSITION))).build();
        Effect effect = effects.play(shards, 0, 0, -4);
        frames(1);
        ParticleWriter writer = particles.writer(effect.id(), 0);
        assertThat(particles.writer(effect.id(), 0)).isSameAs(writer);
        assertThat(writer.effect()).isEqualTo(effect.id());
        assertThat(writer.emitter()).isZero();
        assertThat(writer.capacity()).isEqualTo(8);
        Runnable write = () -> {
            writer.clear();
            writer.next().position(-1.5, 0, -4).size(1).color(1, 0, 0, 1).velocity(0, 0, 0).rotation(0).age(0, 1);
            writer.next().position(1.5, 0, -4).size(1).color(0, 1, 0, 1);
        };
        write.run();
        int[] image = frames(3);
        assertThat(writer.count()).isEqualTo(2);
        assertThat(at(image, 20, 32, 0)).as("red on the left").isGreaterThan(240);
        assertThat(at(image, 44, 32, 1)).as("green on the right").isGreaterThan(240);
        assertThat(at(image, 32, 32, 0) + at(image, 32, 32, 1)).as("nothing between").isLessThan(5);
        for (int i = 0; i < 10; i++) {
            writer.next();
        }
        assertThat(writer.count()).isEqualTo(8);
        assertThat(writer.dropped()).isEqualTo(4);
        // Writing and uploading allocate nothing.
        assertAllocationFree(() -> {
            write.run();
            particles.update(effects, runtime.spawns(), cpu);
        });
        assertThatIllegalArgumentException().isThrownBy(() -> particles.writer(effect.id(), 1));
        effect.kill();
        frames(1);
        assertThat(writer.ended()).isTrue();
    }


    @Test
    void rainStopsOnAFloorTheDepthShows() {
        // A floor 1 m below the camera, seen from above at a slant: rain sticks on it where the depth has it (green);
        // rain without depth collision falls through and hides below it (red).
        scene.set(0, wall, blackWall(), 0, -1, -6, (float) -Math.sqrt(0.5), 0, 0,
            (float) Math.sqrt(0.5), 4, 4, 4);
        camera.position(0, 1, 0).set(0, -0.35, -1, 0, 1, 0, Math.PI / 2, SIZE, SIZE, 0.1);
        view.port().add(new ParticleCollisionDepth(particles, view));
        EffectData rain = EffectData.builder().emitter("e", e -> e.duration(1).spawn(Spawn.rate(300)).capacity(3000)
            .init(Shape.box(4, 0, 3), Init.lifetime(10), Init.size(Bind.constant(0.12f)),
                Init.velocity(Bind.constant(0), Bind.constant(-4), Bind.constant(0)),
                Init.color(Bind.color(0, 1, 0, 1)))
            .collide(Collide.rays().orElse(Collide.depth(0.5f)).stick()).render(sprite(Renderer.Facing.POSITION)))
            .build(); // no ray collision set up: the depth after the rays
        EffectData through = EffectData.builder().emitter("e", e -> e.duration(1).spawn(Spawn.rate(300)).capacity(3000)
            .init(Shape.box(4, 0, 3), Init.lifetime(10), Init.size(Bind.constant(0.12f)),
                Init.velocity(Bind.constant(0), Bind.constant(-4), Bind.constant(0)),
                Init.color(Bind.color(1, 0, 0, 1))).render(sprite(Renderer.Facing.POSITION))).build();
        effects.play(rain, -2, 1.5, -6);
        effects.play(through, 2, 1.5, -6);
        int[] image = frames(240); // a second of rain, then three for it to land
        int green = 0;
        int red = 0;
        for (int pixel : image) {
            green += channel(pixel, 1) > 128 && channel(pixel, 0) < 64 ? 1 : 0;
            red += channel(pixel, 0) > 128 && channel(pixel, 1) < 64 ? 1 : 0;
        }
        System.out.printf("Rain on a floor: %d green pixels (resting on it), %d red (fallen through)%n", green, red);
        assertThat(green).as("rain resting on the floor").isGreaterThan(50);
        assertThat(4 * red).as("rain fallen through, hidden below it").isLessThan(green);
        assertThat(graph.passes()).contains("particle collision depth");
    }

    @Test
    void rayCollisionHitsOffScreen() {
        assumeThat(device.rayTracing()).as("no ray tracing").isTrue();
        try (com.truesim.engine.render.RayTracing rays = new com.truesim.engine.render.RayTracing(device, pool, 16)) {
            MeshData floor = quad();
            rays.add(wall, floor, floor.levels().size() - 1);
            particles.rays(rays, renderer);
            assertThat(particles.rayTracing()).isSameAs(rays);
            scene.set(0, wall, blackWall(), 0, -1, -6, (float) -Math.sqrt(0.5), 0, 0, (float) Math.sqrt(0.5), 4, 4,
                4);
            // Looking away from the floor (no depth of it): rays still find it
            camera.position(0, 1, 0).set(0, 0.3, 1, 0, 1, 0, Math.PI / 2, SIZE, SIZE, 0.1);
            effects.play(EffectData.builder().emitter("e", e -> e.duration(1).spawn(Spawn.rate(300)).capacity(3000)
                .init(Shape.box(4, 0, 3), Init.lifetime(10), Init.size(Bind.constant(0.12f)),
                    Init.velocity(Bind.constant(0), Bind.constant(-4), Bind.constant(0)),
                    Init.color(Bind.color(0, 1, 0, 1)))
                .collide(Collide.rays().orElse(Collide.depth()).stick()).render(sprite(Renderer.Facing.POSITION)))
                .build(), 0, 1.5, -6);
            frames(240);
            assertThat(graph.passes()).contains("top level");
            // Turned to the floor: the rain rests on it
            camera.position(0, 1, 0).set(0, -0.35, -1, 0, 1, 0, Math.PI / 2, SIZE, SIZE, 0.1);
            int[] image = frames(3);
            int green = 0;
            for (int pixel : image) {
                green += channel(pixel, 1) > 128 && channel(pixel, 0) < 64 ? 1 : 0;
            }
            System.out.printf("Rain traced onto a floor off screen: %d green pixels%n", green);
            assertThat(green).as("rain resting on the floor it could not see").isGreaterThan(50);
            device.waitIdle();
            particles.rays(null, null);
        }
    }

    /** An emitter of two particles the game writes, drawn sorted or not. */
    private static EffectData two(boolean sorted) {
        return EffectData.builder().emitter("e", e -> e.target(Target.CPU).capacity(2).spawn(Spawn.rate(1))
            .render(sprite(Renderer.Facing.POSITION).sorted(sorted))).build();
    }

    @Test
    void sortedParticlesDrawBackToFrontSeenFromEitherSide() {
        // Near blue written first, far red second, both in the middle of the image.
        Effect sorted = effects.play(two(true), 0, 0, -4);
        frames(1);
        particles.writer(sorted.id(), 0).next().position(0, 0, -3).size(1).color(0, 0, 1, 1).next()
            .position(0, 0, -5).size(1).color(1, 0, 0, 1);
        int[] image = frames(3);
        assertThat(at(image, 32, 32, 2)).as("the near blue over the far red").isGreaterThan(240);
        assertThat(at(image, 32, 32, 0)).isLessThan(15);
        camera.position(0, 0, -8).set(0, 0, 1, 0, 1, 0, Math.PI / 2, SIZE, SIZE, 0.1);
        image = frames(3);
        assertThat(at(image, 32, 32, 0)).as("from the other side the red is near").isGreaterThan(240);
        assertThat(at(image, 32, 32, 2)).isLessThan(15);
        assertThat(graph.passes()).contains("particle sort args", "particle sort keys", "particle sort scatter 5");
        // Unsorted, the one written last covers whatever the side.
        sorted.kill();
        Effect unsorted = effects.play(two(false), 0, 0, -4);
        frames(1);
        particles.writer(unsorted.id(), 0).next().position(0, 0, -5).size(1).color(1, 0, 0, 1).next()
            .position(0, 0, -3).size(1).color(0, 0, 1, 1);
        image = frames(3);
        assertThat(at(image, 32, 32, 2)).as("unsorted: written last, the far blue").isGreaterThan(240);
        assertThat(particles.sortedCount()).isZero();
    }

    @Test
    void overlappingEmittersDrawAsWholesBackToFrontSeenFromEitherSide() {
        Consumer<EmitterData.Builder> setup = e -> e.render(sprite(Renderer.Facing.POSITION));
        effects.play(still(4, e -> setup.accept(e.init(Init.size(Bind.constant(1)),
            Init.color(Bind.color(1, 0, 0, 1))))), 0, 0, -3);
        effects.play(still(4, e -> setup.accept(e.init(Init.size(Bind.constant(1)),
            Init.color(Bind.color(0, 0, 1, 1))))), 0, 0, -5);
        int[] image = frames(3);
        assertThat(at(image, 32, 32, 0)).as("the near red emitter over the blue").isGreaterThan(240);
        camera.position(0, 0, -8).set(0, 0, 1, 0, 1, 0, Math.PI / 2, SIZE, SIZE, 0.1);
        image = frames(3);
        assertThat(at(image, 32, 32, 2)).as("from the other side the blue").isGreaterThan(240);
        assertThat(particles.sortedCount()).isEqualTo(2);
    }

    @Test
    void theEyesOfAPairShareOneSortAndFaceTheirCenter() {
        // Two eyes above and below the camera, their pair's center far to the left: a sprite ahead of them faces the
        // center, so both see it edge on, and the camera's own view sees it whole.
        Camera center = new Camera().position(-6, 0, -4).set(0, 0, -1, 0, 1, 0, Math.PI / 2, SIZE, SIZE, 0.1);
        GpuBuffer[] eyePixels = new GpuBuffer[2];
        GpuImage[] eyeTargets = new GpuImage[2];
        int usage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT
            | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
        SceneView[] eyes = new SceneView[2];
        for (int e = 0; e < 2; e++) {
            eyePixels[e] = GpuBuffer.hostVisible(device, 4L * SIZE * SIZE, VK_BUFFER_USAGE_TRANSFER_DST_BIT);
            closeLater.add(eyePixels[e]);
            eyeTargets[e] = new GpuImage(device, SIZE, SIZE, VK_FORMAT_R8G8B8A8_UNORM, usage,
                VK_IMAGE_ASPECT_COLOR_BIT);
            closeLater.add(eyeTargets[e]);
            Camera eye = new Camera().position(0, e == 0 ? 0.5 : -0.5, 0).set(0, 0, -1, 0, 1, 0, Math.PI / 2,
                SIZE, SIZE, 0.1);
            ViewPort port = graph.addView(e == 0 ? "left" : "right").size(SIZE, SIZE);
            eyes[e] = geometry.createView(port, eye, ViewOptions.of(256, 1 << 12));
            port.add(new ClusteredForwardShading(geometry, eyes[e], VK_FORMAT_R8G8B8A8_UNORM,
                    VK_FORMAT_D32_SFLOAT).clearColor(0, 0, 0)).add(new WeightedBlendedTransparency(geometry, eyes[e]))
                .add(new ParticleDraw(particles, eyes[e], center)).add(new Capture(eyePixels[e]))
                .output(Keys.SCENE_COLOR, new ExternalImage("eye " + e, SIZE, SIZE, Format.R8G8B8A8_UNORM, usage,
                    VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL)
                    .set(eyeTargets[e].handle(), eyeTargets[e].view()));
        }
        effects.play(still(1, e -> e.init(Init.size(Bind.constant(2)), Init.color(Bind.color(1, 1, 1, 1)))
            .render(sprite(Renderer.Facing.POSITION))), 0, 0, -4);
        for (int f = 0; f < 3; f++) {
            eyes[0].set(1, MeshletRenderer.MODE_LIT);
            eyes[1].set(1, MeshletRenderer.MODE_LIT);
            frames(1);
        }
        int[] own = pixels.memory().toArray(ValueLayout.JAVA_INT);
        assertThat(covered(own, 0, 128)).as("the camera's own view: 2 m at 4 m, whole").isBetween(230, 290);
        for (int e = 0; e < 2; e++) {
            int[] image = eyePixels[e].memory().toArray(ValueLayout.JAVA_INT);
            assertThat(covered(image, 0, 128)).as("eye " + e + ": edge on").isLessThan(20);
        }
        assertThat(graph.passes().stream().filter("particle sort keys"::equals).count())
            .as("one sort for the camera, one for the pair").isEqualTo(2);
        assertThat(graph.passes().stream().filter("particle draw"::equals).count()).isEqualTo(3);
    }

    /** The screen column of world x at depth {@code z} (the camera at the origin looking down -z, 90 degrees). */
    private static int column(double x, double z) {
        return (int) Math.floor(SIZE / 2.0 + x / -z * SIZE / 2.0);
    }

    /** The screen row of world y at depth {@code z}. */
    private static int row(double y, double z) {
        return (int) Math.floor(SIZE / 2.0 - y / -z * SIZE / 2.0);
    }

    /** How many columns from {@code from} to {@code to} (inclusive) have channel {@code c} above 40 somewhere in rows y-1..y+1. */
    private static int coveredColumns(int[] image, int y, int from, int to, int c) {
        int n = 0;
        for (int x = Math.max(0, from); x <= Math.min(SIZE - 1, to); x++) {
            boolean lit = false;
            for (int dy = -1; dy <= 1; dy++) {
                lit |= y + dy >= 0 && y + dy < SIZE && at(image, x, y + dy, c) > 40;
            }
            n += lit ? 1 : 0;
        }
        return n;
    }

    /** A ribbon effect: {@code rate} a second, living {@code lifetime}, red, set up further by {@code renderer}. */
    private static EffectData ribbon(float rate, float lifetime, Renderer renderer) {
        return EffectData.builder().emitter("e", e -> e.spawn(Spawn.rate(rate), Spawn.burst(0, 1))
            .init(Init.lifetime(lifetime), Init.size(Bind.constant(0.6f)), Init.color(Bind.color(1, 0, 0, 1)))
            .render(renderer)).build();
    }

    @Test
    void aRibbonBehindAMovingEmitterIsContinuousAcrossChunksAndFrames() {
        // 100 a second for 1.2 s: 120 particles, two chunks, survivors copied every frame; the node at 2 m/s.
        long node = nodes.create();
        nodes.setTranslation(node, -3, 0, -5);
        Effect effect = effects.play(ribbon(100, 1.2f, Renderer.ribbon().additive()), node);
        int[] image = null;
        for (int f = 0; f < 100; f++) {
            nodes.setTranslation(node, -3 + 2 * f / 60.0, 0, -5);
            image = frames(1);
        }
        double head = -3 + 2 * 99 / 60.0;
        int from = column(head - 2.0, -5);
        int to = column(head, -5);
        assertThat(particles.emitterCount()).isOne();
        assertThat(coveredColumns(image, row(0, -5), from, to, 0)).as("every column from 2 m behind to the emitter")
            .isEqualTo(to - from + 1);
        assertThat(coveredColumns(image, row(0, -5), column(head + 0.6, -5), SIZE - 1, 0)).as("nothing ahead")
            .isZero();
        assertThat(coveredColumns(image, row(0, -5), 0, column(head - 2.8, -5), 0)).as("nothing past its tail")
            .isZero();
        // Every 4th particle a point, as far as the eye sees: still continuous.
        effect.kill();
        effects.play(ribbon(100, 1.2f, Renderer.ribbon().additive().set("stride", 4)), node);
        for (int f = 0; f < 100; f++) {
            nodes.setTranslation(node, -3 + 2 * f / 60.0, 0, -5);
            image = frames(1);
        }
        assertThat(coveredColumns(image, row(0, -5), column(head - 1.0, -5), to, 0)).as("strided")
            .isEqualTo(to - column(head - 1.0, -5) + 1);
    }

    @Test
    void aRibbonsHeadIsAtTheEmitterFromItsFirstFrame() {
        // One particle born at the start; then the node jumps 3 m in a frame, too soon for the next birth.
        long node = nodes.create();
        nodes.setTranslation(node, -2, 0, -5);
        effects.play(ribbon(1, 5, Renderer.ribbon().additive()), node);
        frames(1);
        nodes.setTranslation(node, 1, 0, -5);
        int[] image = frames(1);
        int from = column(-2, -5);
        int to = column(1, -5);
        assertThat(coveredColumns(image, row(0, -5), from + 1, to - 1, 0)).as("from the particle to the emitter")
            .isEqualTo(to - from - 1);
    }

    @Test
    void ribbonTexturesStretchOrTileAndWidthTapers() {
        long node = nodes.create();
        nodes.setTranslation(node, -3, 0, -5);
        effects.play(EffectData.builder().emitter("e", e -> e.spawn(Spawn.rate(30), Spawn.burst(0, 1))
            .init(Init.lifetime(2), Init.size(Bind.constant(1)))
            .render(Renderer.ribbon().asset("texture", FLIPBOOK).set("width.tail", 0.2f).set("width.head", 1f)))
            .build(), node);
        int[] image = null;
        for (int f = 0; f < 90; f++) {
            nodes.setTranslation(node, -3 + 3 * f / 60.0, 0, -5);
            image = frames(1);
        }
        // Stretched: u from 0 at the tail to 1 at the head, red to green; the tail thinner than the head.
        double head = -3 + 3 * 89 / 60.0;
        int tail = column(-2.7, -5); // the first particle's, 1.5 s ago
        int near = column(head - 0.3, -5);
        int y = row(0, -5);
        assertThat(at(image, tail, y, 0)).as("red at the tail").isGreaterThan(at(image, tail, y, 1));
        assertThat(at(image, near, y, 1)).as("green at the head").isGreaterThan(at(image, near, y, 0));
        assertThat(height(image, near)).as("wide at the head").isGreaterThan(height(image, tail) + 2);
        assertThat(graph.passes()).contains("particle ribbons");
    }

    /** How many rows of column {@code x} have anything drawn. */
    private static int height(int[] image, int x) {
        int n = 0;
        for (int y = 0; y < SIZE; y++) {
            n += (image[y * SIZE + x] & 0xffffff) != 0 ? 1 : 0;
        }
        return n;
    }

    @Test
    void ribbonsOfSeveralStrandsFollowEachStrand() {
        // Two strands, alternate particles: one shot up, one down, each its own ribbon (no zigzag between them).
        long node = nodes.create();
        nodes.setTranslation(node, -3, 0, -5);
        effects.play(EffectData.builder().emitter("e", e -> e.spawn(Spawn.rate(60)).init(Init.lifetime(1),
                Init.size(Bind.constant(0.3f)), Init.color(Bind.color(1, 0, 0, 1)),
                Init.velocity(Bind.constant(0), Bind.constant(0), Bind.constant(0)))
            .render(Renderer.ribbon().additive().set("ribbons", 2))).build(), node);
        int[] image = null;
        for (int f = 0; f < 60; f++) {
            nodes.setTranslation(node, -3 + 4 * f / 60.0, 0, -5);
            image = frames(1);
        }
        int y = row(0, -5);
        assertThat(coveredColumns(image, y, column(-1.5, -5), column(0.5, -5), 0)).as("both strands along the path")
            .isEqualTo(column(0.5, -5) - column(-1.5, -5) + 1);
    }

    @Test
    void aBeamJoinsTwoNodes() {
        long a = nodes.create();
        long b = nodes.create();
        nodes.setTranslation(a, -2, 0, -5);
        nodes.setTranslation(b, 2, 1, -5);
        Effect beam = effects.play(EffectData.builder().emitter("beam", e -> e.spawn(Spawn.rate(1))
            .render(Renderer.beam().set("width", 0.3f).set("color.r", 0f).set("color.b", 0f).additive())).build(), a);
        frames(1);
        particles.beamEnd(beam.id(), 0, 2, 1, -5);
        int[] image = frames(2);
        assertThat(at(image, column(0, -5), row(0.5, -5), 1)).as("halfway").isGreaterThan(200);
        assertThat(at(image, column(-1, -5), row(0.25, -5), 1)).as("a quarter of the way").isGreaterThan(200);
        assertThat(at(image, column(3, -5), row(1.25, -5), 1)).as("not past the end").isLessThan(10);
        assertThat(at(image, column(0, -5), row(-0.8, -5), 1)).as("nowhere else").isLessThan(10);
        // The far node moves: the beam follows.
        particles.beamEnd(beam.id(), 0, 2, -1, -5);
        image = frames(1);
        assertThat(at(image, column(0, -5), row(-0.5, -5), 1)).as("halfway down").isGreaterThan(200);
        // Without an end: its length up the emitter's +Y.
        Effect upright = effects.play(EffectData.builder().emitter("beam", e -> e.spawn(Spawn.rate(1))
            .render(Renderer.beam().set("width", 0.3f).set("length", 2f).set("color.g", 0f).set("color.b", 0f)
                .additive())).build(), -2.5, -2, -5);
        image = frames(2);
        assertThat(at(image, column(-2.5, -5), row(-1, -5), 0)).as("up from the emitter").isGreaterThan(150);
        assertThat(upright.alive()).isTrue();
        assertThatIllegalArgumentException().isThrownBy(() -> particles.beamEnd(upright.id(), 3, 0, 0, 0));
    }

    @Test
    void trailsFollowTheirParticles() {
        // A particle at 3 m/s keeps its last 16 positions: a trail some 0.8 m long behind it.
        effects.play(EffectData.builder().emitter("e", e -> e.spawn(Spawn.burst(0, 1)).init(Init.lifetime(5),
                Init.size(Bind.constant(0.3f)), Init.color(Bind.color(1, 0, 0, 1)),
                Init.velocity(Bind.constant(3), Bind.constant(0), Bind.constant(0)))
            .render(Renderer.trail(16).additive())).build(), -2, 0, -5);
        int[] image = frames(30);
        double x = -2 + 3 * 30 / 60.0;
        int y = row(0, -5);
        assertThat(coveredColumns(image, y, column(x - 0.7, -5), column(x - 0.1, -5), 0)).as("the trail")
            .isEqualTo(column(x - 0.1, -5) - column(x - 0.7, -5) + 1);
        assertThat(coveredColumns(image, y, 0, column(x - 1.2, -5), 0)).as("no older positions").isZero();
    }

    /** How many channels of {@code a} and {@code b} differ by more than 8 of 255. */
    private static int differing(int[] a, int[] b) {
        int n = 0;
        for (int i = 0; i < a.length; i++) {
            for (int c = 0; c < 3; c++) {
                n += Math.abs(channel(a[i], c) - channel(b[i], c)) > 8 ? 1 : 0;
            }
        }
        return n;
    }

    /** A moving torch: sparks inheriting its velocity, a trail of streaks and a ribbon behind it, all world space. */
    private static EffectData torch() {
        return EffectData.builder()
            .emitter("sparks", e -> e.spawn(Spawn.rate(60)).init(Shape.cone(0.4f, 0.1f), Init.speed(Bind.random(1, 2)),
                    Init.lifetime(Bind.random(0.5f, 1)), Init.size(Bind.random(0.3f, 0.5f)),
                    Init.color(Bind.color(1, 0.5f, 0.2f, 1)))
                .update(Forces.gravity(Bind.constant(0.2f)), Forces.drag(0.5f))
                .render(Renderer.billboard(Renderer.Facing.POSITION).additive()))
            .emitter("streaks", e -> e.spawn(Spawn.rate(10)).init(Init.speed(Bind.constant(1)),
                    Init.lifetime(1), Init.size(Bind.constant(0.05f)), Init.color(Bind.color(0, 1, 0, 1)))
                .render(Renderer.trail(6).additive()))
            .emitter("ribbon", e -> e.spawn(Spawn.rate(30)).init(Init.lifetime(1), Init.size(Bind.constant(0.2f)),
                    Init.color(Bind.color(0, 0, 1, 1)))
                .render(Renderer.ribbon().additive()))
            .build();
    }

    @Test
    void anEffect7000KmOutDrawsAsAtTheOrigin() {
        particles.rebaseDistance(0.5); // rebased every ten frames as it moves
        int[][] images = new int[2][];
        double[] bases = {0, 7_000_000.25};
        long node = nodes.create();
        for (int run = 0; run < 2; run++) {
            double base = bases[run];
            camera.position(base, 0, 0);
            nodes.setTranslation(node, base - 1, -0.5, -4);
            nodes.update();
            Effect effect = effects.play(torch(), node).seed(9).inheritVelocity(0.5f);
            for (int f = 0; f < 40; f++) {
                nodes.setTranslation(node, base - 1 + 0.05 * f, -0.5 + 0.02 * f, -4);
                images[run] = frames(1);
            }
            effect.kill();
        }
        int differing = differing(images[0], images[1]);
        System.out.printf("7,000 km out against the origin: %d channels off by more than 8%n", differing);
        assertThat(particles.rebases()).isGreaterThan(4);
        assertThat(covered(images[0], 0, 40)).as("sparks drawn").isGreaterThan(30);
        assertThat(covered(images[0], 1, 40)).as("streaks drawn").isGreaterThan(3);
        assertThat(covered(images[0], 2, 40)).as("a ribbon drawn").isGreaterThan(30);
        assertThat(differing).as("channels off by more than 8 of 255").isLessThan(images[0].length * 3 / 100);
    }

    @Test
    void aPausedWorldFreezesEffectsWhileViewsDraw() {
        long node = nodes.create();
        nodes.setTranslation(node, 0, -0.5, -4);
        effects.play(torch(), node).seed(4);
        frames(20);
        effects.pause(true); // every effect paused
        int[] paused = frames(1);
        assertThat(frames(5)).as("frozen, still drawn").isEqualTo(paused);
        assertThat(covered(paused, 0, 40)).isGreaterThan(10);
        effects.pause(false);
        int[] stopped = frames(1, 0); // the world paused: a step of 0
        assertThat(frames(5, 0)).isEqualTo(stopped);
        assertThat(differing(frames(5), stopped)).as("moving again").isPositive();
    }

    @Test
    void aReplacedAssetRestartsItsInstances() {
        EffectData red = still(1, e -> e.init(Init.size(Bind.constant(1)), Init.color(Bind.color(1, 0, 0, 1)))
            .render(sprite(Renderer.Facing.POSITION)));
        EffectData green = still(1, e -> e.init(Init.size(Bind.constant(1)), Init.color(Bind.color(0, 1, 0, 1)))
            .render(sprite(Renderer.Facing.POSITION)));
        Effect effect = effects.play(red, 0, 0, -4);
        int[] image = frames(3);
        assertThat(at(image, SIZE / 2, SIZE / 2, 0)).isGreaterThan(250);
        int range = particles.first(effect.id(), 0);
        assertThat(effects.replace(red, green)).isOne();
        image = frames(3);
        assertThat(effect.alive()).isTrue();
        assertThat(at(image, SIZE / 2, SIZE / 2, 0)).as("the old one's particles gone").isLessThan(5);
        assertThat(at(image, SIZE / 2, SIZE / 2, 1)).as("the new one's drawn").isGreaterThan(250);
        assertThat(particles.first(effect.id(), 0)).as("its range kept").isEqualTo(range);
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "TRUESIM_BENCHMARK", matches = "true")
    void trailsOnTenThousandSparksTimed() {
        graph.timings(true);
        effects.play(EffectData.builder().emitter("sparks", e -> e.capacity(10_000).spawn(Spawn.burst(0, 10_000))
                .init(Shape.sphere(0.5f), Init.speed(Bind.random(1, 3)), Init.lifetime(1000),
                    Init.size(Bind.constant(0.02f)), Init.color(Bind.color(1, 0.6f, 0.2f, 1)))
                .update(Forces.gravity(Bind.constant(0.05f)), Forces.drag(0.5f))
                .render(Renderer.trail(16).additive())).build(), 0, 0, -6);
        double[] sums = new double[2];
        int timed = 0;
        for (int f = 0; f < 120; f++) {
            frames(1);
            var timings = graph.timings();
            if (f < 20 || timings == null) {
                continue;
            }
            for (int i = 0; i < timings.count(); i++) {
                sums[0] += timings.pass(i).equals("particle update") ? timings.millis(i) : 0;
                sums[1] += timings.pass(i).equals("particle draw") ? timings.millis(i) : 0;
            }
            timed++;
        }
        System.out.printf("10,000 sparks with trails of 16 points: update %.3f ms, draw %.3f ms (%d x %d)%n",
            sums[0] / timed, sums[1] / timed, SIZE, SIZE);
        assertThat(particles.unplaced()).isZero();
        assertThat(particles.alive()).isEqualTo(10_000);
    }

}
