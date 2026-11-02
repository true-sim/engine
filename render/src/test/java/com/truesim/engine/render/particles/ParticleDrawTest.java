package com.truesim.engine.render.particles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
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
    private Camera camera;
    private int wall;
    private final SceneGraph nodes = new SceneGraph(new ChangeClock());
    private final Effects effects = new Effects(nodes);
    private final EffectRuntime runtime = new EffectRuntime(effects);

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
        particles = new Particles(device, graph.frameSlots(), 1 << 20, 64);
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
        SceneGeometry geometry = SceneGeometry.meshlets(renderer);
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
        for (int f = 0; f < n; f++) {
            nodes.update();
            runtime.update(FRAME);
            graph.beginFrame();
            particles.update(effects, runtime.spawns());
            view.set(1, MeshletRenderer.MODE_LIT);
            graph.submit(0, 0);
        }
        device.waitIdle();
        return pixels.memory().toArray(ValueLayout.JAVA_INT);
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

    @Test
    void aParticleDrawIsNamedAndDrawsOverTheBlendedMaterials() {
        ParticleDraw draw = new ParticleDraw(particles, view);
        assertThat(draw.id()).isEqualTo(ParticleDraw.ID);
        assertThat(draw.provides()).isEmpty();
        assertThat(draw.order()).isEqualTo(Order.at(Anchor.TRANSPARENT).after(com.truesim.engine.render.Transparency.ID));
    }
}
