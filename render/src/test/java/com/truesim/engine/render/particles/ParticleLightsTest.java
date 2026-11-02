package com.truesim.engine.render.particles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
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

import com.truesim.common.math.color.Color;
import com.truesim.common.state.ChangeClock;
import com.truesim.engine.graph.Access;
import com.truesim.engine.graph.Anchor;
import com.truesim.engine.graph.Buffer;
import com.truesim.engine.graph.BufferAccess;
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
import com.truesim.engine.render.Material;
import com.truesim.engine.render.Materials;
import com.truesim.engine.render.MeshletRenderer;
import com.truesim.engine.render.SceneGeometry;
import com.truesim.engine.render.SceneView;
import com.truesim.engine.render.ViewOptions;
import com.truesim.engine.render.VisibilityBufferShading;
import com.truesim.engine.render.materials.MetallicRoughnessParameters;
import com.truesim.engine.vulkan.Format;
import com.truesim.engine.vulkan.GpuBuffer;
import com.truesim.engine.vulkan.GpuImage;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.engine.vulkan.VulkanException;
import com.truesim.engine.vulkan.VulkanInstance;
import com.truesim.scene.assets.mesh.MeshData;
import com.truesim.scene.assets.mesh.VertexAttribute;
import com.truesim.scene.assets.mesh.VertexFormat;
import com.truesim.scene.core.SceneGraph;
import com.truesim.scene.effects.Bind;
import com.truesim.scene.effects.ColorGradient;
import com.truesim.scene.effects.EffectData;
import com.truesim.scene.effects.EffectRuntime;
import com.truesim.scene.effects.Effects;
import com.truesim.scene.effects.Init;
import com.truesim.scene.effects.Renderer;
import com.truesim.scene.effects.Shape;
import com.truesim.scene.effects.Spawn;
import com.truesim.scene.meshbuild.MeshBuilder;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkBufferImageCopy;

/**
 * Particle lights (step 17b): light renderers write point lights into the lights the GPU writes, the brightest kept
 * within each emitter's budget and the frame's, binned by {@link ClusteredLights} after the scene's lights, so the
 * ground beside sparks is lit, alike by forward and visibility buffer shading; nothing is read back for it. Validated,
 * over three frames or more.
 */
class ParticleLightsTest {

    private static final int SIZE = 64;
    private static final long FRAME = 16_667;
    private static final int LIGHT_WORDS = 20;

    private VulkanInstance instance;
    private VulkanDevice device;
    private FrameGraph graph;
    private GeometryPool pool;
    private GpuScene scene;
    private MeshletRenderer renderer;
    private Materials materials;
    private Lights lights;
    private Particles particles;
    private GpuImage target;
    private GpuBuffer pixels;
    private GpuBuffer copy;
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

    /** A 10 x 10 floor at y = 0, facing up. */
    private static MeshData floor() {
        float[] positions = {-5, 0, -5, 5, 0, -5, 5, 0, 5, -5, 0, 5};
        float[] normals = {0, 1, 0, 0, 1, 0, 0, 1, 0, 0, 1, 0};
        return new MeshBuilder(VertexFormat.of(VertexAttribute.NORMAL), 4).positions(positions)
            .attribute(VertexAttribute.NORMAL, normals).submesh(new int[] {0, 2, 1, 0, 3, 2}).buildFlat();
    }

    /** The graph: a grey floor 1 m below the camera, shaded forward or through the visibility buffer. */
    private void graph(boolean visibility, int budget) {
        graph = new FrameGraph(device, 2);
        pool = new GeometryPool(device, GeometryPool.Capacity.of(64, 4, 4), graph.frameSlots());
        scene = new GpuScene(device, pool, 4, graph.frameSlots());
        renderer = new MeshletRenderer(device, scene, VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_D32_SFLOAT,
            graph.frameSlots());
        materials = new Materials(device, 8, graph.frameSlots());
        scene.setMaterials(materials);
        int grey = materials.add(new Material(MetallicRoughnessParameters.DEFAULTS
            .withBaseColor(new Color(0.5f, 0.5f, 0.5f, 1)).withRoughness(1)));
        scene.set(0, pool.add(floor()), grey, 0f, -1f, -4f, 0f, 0f, 0f, 1f, 1f, 1f, 1f);
        lights = new Lights(device, 4, 4, graph.frameSlots());
        lights.setCount(0); // no scene lights: only the particles'
        particles = new Particles(device, graph.frameSlots(), 2 << 20, 16).lights(lights, budget);
        int usage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT
            | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
        target = new GpuImage(device, SIZE, SIZE, VK_FORMAT_R8G8B8A8_UNORM, usage, VK_IMAGE_ASPECT_COLOR_BIT);
        pixels = GpuBuffer.hostVisible(device, 4L * SIZE * SIZE, VK_BUFFER_USAGE_TRANSFER_DST_BIT);
        copy = GpuBuffer.hostVisible(device, 16L + 80L * Math.max(budget, 1), VK_BUFFER_USAGE_TRANSFER_DST_BIT);
        Camera camera = new Camera().set(0, -0.3, -1, 0, 1, 0, Math.PI / 2, SIZE, SIZE, 0.1);
        SceneGeometry geometry = SceneGeometry.meshlets(renderer);
        ViewPort port = graph.addView("main").size(SIZE, SIZE);
        view = geometry.createView(port, camera, ViewOptions.of(256, 1 << 12));
        port.add(visibility ? new VisibilityBufferShading(geometry, view, VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_D32_SFLOAT)
                    .clearColor(0, 0, 0)
                : new ClusteredForwardShading(geometry, view, VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_D32_SFLOAT)
                    .clearColor(0, 0, 0))
            .add(new ClusteredLights(lights, view)).add(new Capture(pixels, copy, lights))
            .output(Keys.SCENE_COLOR, new ExternalImage("target", SIZE, SIZE, Format.R8G8B8A8_UNORM, usage,
                VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL).set(target.handle(), target.view()));
        graph.add(new ParticleSimulation(particles));
    }

    private void close() {
        device.waitIdle();
        graph.close();
        particles.close();
        lights.close();
        materials.close();
        pixels.close();
        copy.close();
        target.close();
        renderer.close();
        scene.close();
        pool.close();
        graph = null;
    }

    @AfterEach
    void stop() {
        if (device != null) {
            if (graph != null) {
                close();
            }
            device.waitIdle();
            int errors = instance.validationErrors();
            device.close();
            instance.close();
            assertThat(errors).as("validation errors").isZero();
        } else if (instance != null) {
            instance.close();
        }
    }

    /** Copies the scene's color and the lights the GPU wrote into host buffers, after everything. */
    private record Capture(GpuBuffer pixels, GpuBuffer copy, Lights lights) implements Technique {
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
            Buffer written = g.importBuffer(lights.gpuLightsBuffer());
            g.pass("capture").read(color, Access.TRANSFER_SRC).read(written, BufferAccess.TRANSFER_SRC).sideEffects()
                .record(ctx -> {
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
                        org.lwjgl.vulkan.VK10.vkCmdCopyImageToBuffer(ctx.unsafe().commands(),
                            ctx.unsafe().image(color), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, pixels.handle(), region);
                        VkBufferCopy.Buffer bytes = VkBufferCopy.calloc(1, stack).size(copy.size());
                        org.lwjgl.vulkan.VK10.vkCmdCopyBuffer(ctx.unsafe().commands(), ctx.unsafe().buffer(written),
                            copy.handle(), bytes);
                    }
                });
            g.provide(Keys.SCENE_COLOR, color);
        }
    }

    private void frames(int n) {
        for (int f = 0; f < n; f++) {
            nodes.update();
            runtime.update(FRAME);
            graph.beginFrame();
            particles.update(effects, runtime.spawns());
            view.set(1, MeshletRenderer.MODE_LIT);
            graph.submit(0, 0);
        }
        device.waitIdle();
    }

    /** The brightest red of the image: the floor below the particle, where it lights it. */
    private double middle() {
        int[] image = pixels.memory().toArray(ValueLayout.JAVA_INT);
        double most = 0;
        for (int pixel : image) {
            most = Math.max(most, pixel & 0xff);
        }
        return most;
    }

    @Test
    void sparksLightTheGroundAlikeInBothShadings() {
        double[] lit = new double[2];
        int[][] images = new int[2][];
        for (int v = 0; v < 2; v++) {
            graph(v == 1, 16);
            frames(3);
            double dark = middle();
            effects.play(EffectData.builder().emitter("glow", e -> e.capacity(1).spawn(Spawn.burst(0, 1))
                .init(Init.lifetime(100), Init.color(Bind.color(4, 2, 1, 1)))
                .render(Renderer.light(3, 1))).build(), 0, -0.6, -4);
            frames(3);
            lit[v] = middle();
            assertThat(lit[v]).as("the floor below the particle lit, against " + dark).isGreaterThan(dark + 30);
            assertThat(copy.memory().get(ValueLayout.JAVA_INT, 0)).as("one light").isOne();
            images[v] = pixels.memory().toArray(ValueLayout.JAVA_INT);
            close();
        }
        int differing = 0;
        for (int i = 0; i < images[0].length; i++) {
            differing += Math.abs((images[0][i] & 0xff) - (images[1][i] & 0xff)) > 8 ? 1 : 0;
        }
        assertThat(differing).as("forward and visibility buffer shading light alike").isLessThanOrEqualTo(8);
    }

    @Test
    void aBurstOverTheBudgetsKeepsTheBrightest() {
        graph(false, 40);
        // 200 particles of brightness uniform in [0, 1), at most 64 kept of them; 100 of brightness 2, at most 5
        effects.play(EffectData.builder()
            .emitter("dim", e -> e.capacity(200).spawn(Spawn.burst(0, 200)).init(Shape.sphere(1), Init.lifetime(100),
                Init.color(Bind.random(ColorGradient.builder().linear(0, 0, 0, 0, 1).linear(1, 1, 1, 1, 1).build())))
                .render(Renderer.light(1, 1)))
            .emitter("bright", e -> e.capacity(100).spawn(Spawn.burst(0, 100)).init(Shape.sphere(1),
                Init.lifetime(100), Init.color(Bind.color(2, 2, 2, 1)))
                .render(Renderer.light(1, 1).set("max", 5)))
            .build(), 0, 5, -4);
        frames(4);
        MemorySegment m = copy.memory();
        int count = m.get(ValueLayout.JAVA_INT, 0);
        assertThat(count).as("the frame's budget, filled").isEqualTo(40);
        int bright = 0;
        int dim = 0;
        float dimmest = Float.MAX_VALUE;
        for (int i = 0; i < count; i++) {
            long at = 16 + 4L * LIGHT_WORDS * i;
            float r = m.get(ValueLayout.JAVA_FLOAT, at + 16); // color, after position and range
            assertThat(m.get(ValueLayout.JAVA_FLOAT, at + 12)).as("its radius").isEqualTo(1);
            if (r > 1.5f) {
                bright++;
            } else {
                dim++;
                dimmest = Math.min(dimmest, r);
            }
        }
        assertThat(bright).as("the bright emitter's budget").isEqualTo(5);
        assertThat(dim).isEqualTo(35);
        // The 35 brightest of 200 uniform values start near 0.82; buckets are a quarter of an octave
        assertThat(dimmest).as("only the brightest of the dim").isGreaterThan(0.65f);
        assertThatIllegalArgumentException().isThrownBy(() -> particles.lights(lights, -1));
        assertThatIllegalArgumentException().isThrownBy(() -> lights.gpuLights(-1));
        assertThat(lights.gpuCapacity()).isEqualTo(40);
    }
}
