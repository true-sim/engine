package com.truesim.engine.render.particles;

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
import com.truesim.engine.render.ClusteredForwardShading;
import com.truesim.engine.render.ClusteredLights;
import com.truesim.engine.render.GeometryPool;
import com.truesim.engine.render.GpuScene;
import com.truesim.engine.render.Keys;
import com.truesim.engine.render.Lights;
import com.truesim.engine.render.MeshletRenderer;
import com.truesim.engine.render.SceneGeometry;
import com.truesim.engine.render.SceneView;
import com.truesim.engine.render.Textures;
import com.truesim.engine.render.ViewOptions;
import com.truesim.engine.render.layout.ParticleConstants;
import com.truesim.engine.vulkan.Format;
import com.truesim.engine.vulkan.GpuBuffer;
import com.truesim.engine.vulkan.GpuImage;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.engine.vulkan.VulkanException;
import com.truesim.engine.vulkan.VulkanInstance;
import com.truesim.scene.assets.AssetId;
import com.truesim.scene.assets.AssetRef;
import com.truesim.scene.assets.AssetType;
import com.truesim.scene.core.SceneGraph;
import com.truesim.scene.effects.Bind;
import com.truesim.scene.effects.EffectData;
import com.truesim.scene.effects.EffectRuntime;
import com.truesim.scene.effects.Effects;
import com.truesim.scene.effects.Init;
import com.truesim.scene.effects.Quality;
import com.truesim.scene.effects.Renderer;
import com.truesim.scene.effects.Spawn;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.vulkan.VkBufferImageCopy;

/**
 * Lit particles (E14, step 17): billboards lit per vertex (the light list's lights at their center, the sun's shadow),
 * per pixel (a normal map or a sphere's normal) or by six-way maps, read through the view's light list; lowered by
 * quality. A point light to one side lights that side. Validated, over three frames or more.
 */
class LitParticlesTest {

    private static final int SIZE = 64;
    private static final long FRAME = 16_667;
    private static final AssetType<String> TEXTURE = AssetType.of("test.texture", String.class, s -> 0);
    private static final AssetRef<?> RIGHT = AssetRef.of(TEXTURE, new AssetId(0, 1));
    private static final AssetRef<?> DARK = AssetRef.of(TEXTURE, new AssetId(0, 2));

    private VulkanInstance instance;
    private VulkanDevice device;
    private FrameGraph graph;
    private GeometryPool pool;
    private GpuScene scene;
    private MeshletRenderer renderer;
    private Textures textures;
    private Lights lights;
    private Particles particles;
    private GpuImage target;
    private GpuBuffer pixels;
    private SceneView view;
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
    }

    /** The graph, with a light list (one point light at {@code lx}, 0, -4) or without one (the sun). */
    private void graph(boolean lit, double lx) {
        graph = new FrameGraph(device, 2);
        pool = new GeometryPool(device, GeometryPool.Capacity.of(64, 4, 4), graph.frameSlots());
        scene = new GpuScene(device, pool, 4, graph.frameSlots());
        renderer = new MeshletRenderer(device, scene, VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_D32_SFLOAT,
            graph.frameSlots());
        textures = new Textures(device, graph.frameSlots());
        lights = new Lights(device, 4, 4, graph.frameSlots());
        lights.point(0, lx, 0, -4, 8, 40, 40, 40);
        lights.setCount(1);
        particles = new Particles(device, graph.frameSlots(), 2 << 20, 16);
        // Six-way maps: lit from the right (the first map's red) and nothing else
        particles.texture(RIGHT, textures.color(1, 1, MemorySegment.ofArray(new int[] {0xff0000ff})));
        particles.texture(DARK, textures.color(1, 1, MemorySegment.ofArray(new int[] {0x00000000})));
        int usage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT
            | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
        target = new GpuImage(device, SIZE, SIZE, VK_FORMAT_R8G8B8A8_UNORM, usage, VK_IMAGE_ASPECT_COLOR_BIT);
        pixels = GpuBuffer.hostVisible(device, 4L * SIZE * SIZE, VK_BUFFER_USAGE_TRANSFER_DST_BIT);
        Camera camera = new Camera().set(0, 0, -1, 0, 1, 0, Math.PI / 2, SIZE, SIZE, 0.1);
        SceneGeometry geometry = SceneGeometry.meshlets(renderer);
        ViewPort port = graph.addView("main").size(SIZE, SIZE);
        view = geometry.createView(port, camera, ViewOptions.of(256, 1 << 12));
        port.add(new ClusteredForwardShading(geometry, view, VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_D32_SFLOAT)
                .clearColor(0, 0, 0)).add(new ParticleDraw(particles, view)).add(new Capture(pixels))
            .output(Keys.SCENE_COLOR, new ExternalImage("target", SIZE, SIZE, Format.R8G8B8A8_UNORM, usage,
                VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL).set(target.handle(), target.view()));
        if (lit) {
            port.add(new ClusteredLights(lights, view));
        }
        graph.add(new ParticleSimulation(particles));
    }

    @AfterEach
    void stop() {
        if (device != null) {
            device.waitIdle();
            int errors = instance.validationErrors();
            if (graph != null) {
                graph.close();
                particles.close();
                lights.close();
                textures.close();
                pixels.close();
                target.close();
                renderer.close();
                scene.close();
                pool.close();
            }
            device.close();
            instance.close();
            assertThat(errors).as("validation errors").isZero();
        } else if (instance != null) {
            instance.close();
        }
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
                    region.imageExtent().set(SIZE, SIZE, 1);
                    org.lwjgl.vulkan.VK10.vkCmdCopyImageToBuffer(ctx.unsafe().commands(), ctx.unsafe().image(color),
                        VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, pixels.handle(), region);
                }
            });
            g.provide(Keys.SCENE_COLOR, color);
        }
    }

    /** Frames; the image after the last. */
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

    /** The mean red of the columns from {@code from} to {@code to} (excluded). */
    private static double red(int[] image, int from, int to) {
        double sum = 0;
        for (int y = 0; y < SIZE; y++) {
            for (int x = from; x < to; x++) {
                sum += image[y * SIZE + x] & 0xff;
            }
        }
        return sum / (SIZE * (to - from));
    }

    /** {@code count} white particles at once from x = {@code from} on, 1 m apart, of size {@code size}, as drawn. */
    private void puffs(int count, float from, float size, Renderer drawn) {
        effects.play(EffectData.builder().emitter("e", e -> e.capacity(count).spawn(Spawn.burst(0, count))
            .init(Init.lifetime(100), Init.size(Bind.constant(size)), Init.color(Bind.color(1, 1, 1, 1)))
            .render(drawn)).build(), from, 0, -4);
    }

    @Test
    void smokeLitPerPixelFromOneSideIsBrighterThere() {
        graph(true, 2);
        puffs(1, 0, 3, Renderer.billboard(Renderer.Facing.POSITION).lighting(Renderer.Lighting.PER_PIXEL));
        int[] image = frames(3);
        double left = red(image, SIZE / 2 - 10, SIZE / 2 - 2);
        double right = red(image, SIZE / 2 + 2, SIZE / 2 + 10);
        assertThat(right).as("the light's side").isGreaterThan(2 * left + 10);
        assertThat(graph.passes()).contains("particle lighting", "particle draw");
    }

    @Test
    void particlesLitPerVertexTakeTheLightAtTheirCenters() {
        graph(true, 3);
        puffs(1, -1.5f, 0.8f, Renderer.billboard(Renderer.Facing.POSITION).lighting(Renderer.Lighting.PER_VERTEX));
        puffs(1, 1.5f, 0.8f, Renderer.billboard(Renderer.Facing.POSITION).lighting(Renderer.Lighting.PER_VERTEX));
        int[] image = frames(3);
        double left = red(image, 0, SIZE / 2);
        double right = red(image, SIZE / 2, SIZE);
        assertThat(right).as("nearer the light").isGreaterThan(2 * left);
        assertThat(left).as("lit a little: the sky, the far light").isGreaterThan(0);
    }

    @Test
    void sixWayMapsTakeTheLightFromTheirSide() {
        graph(true, 2.5);
        Renderer sixWay = Renderer.billboard(Renderer.Facing.POSITION).lighting(Renderer.Lighting.SIX_WAY)
            .asset("texture", RIGHT).asset("texture.b", DARK);
        puffs(1, 0, 2, sixWay);
        double fromRight = red(frames(3), 0, SIZE);
        lights.point(0, -2.5, 0, -4, 8, 40, 40, 40); // the light to the left: the map has nothing there
        double fromLeft = red(frames(3), 0, SIZE);
        assertThat(fromRight).isGreaterThan(3 * fromLeft + 2);
    }

    @Test
    void withoutALightListTheSunLights() {
        graph(false, 0);
        puffs(1, 0, 2, Renderer.billboard(Renderer.Facing.POSITION).lighting(Renderer.Lighting.PER_VERTEX));
        puffs(1, 2, 1, Renderer.billboard(Renderer.Facing.POSITION).lighting(Renderer.Lighting.PER_PIXEL));
        puffs(1, -2, 1, Renderer.billboard(Renderer.Facing.POSITION).lighting(Renderer.Lighting.SIX_WAY));
        assertThat(red(frames(3), 0, SIZE)).isGreaterThan(5);
    }

    @Test
    void qualityLowersLighting() {
        Renderer.Kind b = Renderer.Kind.BILLBOARD;
        assertThat(Particles.lighting(b, Renderer.Lighting.PER_PIXEL, Quality.HIGH))
            .isEqualTo(ParticleConstants.LIGHTING_PIXEL);
        assertThat(Particles.lighting(b, Renderer.Lighting.PER_PIXEL, Quality.EPIC))
            .isEqualTo(ParticleConstants.LIGHTING_PIXEL);
        assertThat(Particles.lighting(b, Renderer.Lighting.PER_PIXEL, Quality.MEDIUM))
            .isEqualTo(ParticleConstants.LIGHTING_VERTEX);
        assertThat(Particles.lighting(b, Renderer.Lighting.SIX_WAY, Quality.MEDIUM))
            .isEqualTo(ParticleConstants.LIGHTING_SIX_WAY);
        assertThat(Particles.lighting(b, Renderer.Lighting.SIX_WAY, Quality.LOW))
            .isEqualTo(ParticleConstants.LIGHTING_VERTEX);
        assertThat(Particles.lighting(b, Renderer.Lighting.PER_VERTEX, Quality.LOW))
            .isEqualTo(ParticleConstants.LIGHTING_VERTEX);
        assertThat(Particles.lighting(b, Renderer.Lighting.UNLIT, Quality.EPIC))
            .isEqualTo(ParticleConstants.LIGHTING_UNLIT);
        assertThat(Particles.lighting(Renderer.Kind.RIBBON, Renderer.Lighting.PER_PIXEL, Quality.EPIC))
            .as("only billboards are lit").isEqualTo(ParticleConstants.LIGHTING_UNLIT);
        graph(true, 2);
        assertThat(particles.quality()).isEqualTo(Quality.HIGH);
        // Drawn per vertex at medium quality: still lit from the light's side
        particles.quality(Quality.MEDIUM);
        puffs(1, 0, 3, Renderer.billboard(Renderer.Facing.POSITION).lighting(Renderer.Lighting.PER_PIXEL));
        int[] image = frames(3);
        assertThat(red(image, 0, SIZE)).isGreaterThan(5);
    }
}
