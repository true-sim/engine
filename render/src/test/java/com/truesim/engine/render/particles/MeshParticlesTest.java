package com.truesim.engine.render.particles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
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
import com.truesim.engine.render.GeometryPool;
import com.truesim.engine.render.GpuScene;
import com.truesim.engine.render.Keys;
import com.truesim.engine.render.Material;
import com.truesim.engine.render.Materials;
import com.truesim.engine.render.MeshletRenderer;
import com.truesim.engine.render.RayTracing;
import com.truesim.engine.render.SceneGeometry;
import com.truesim.engine.render.SceneView;
import com.truesim.engine.render.ViewOptions;
import com.truesim.engine.render.VisibilityBufferShading;
import com.truesim.engine.render.layout.CommonConstants;
import com.truesim.engine.render.layout.InstanceLayout;
import com.truesim.engine.render.materials.MetallicRoughnessParameters;
import com.truesim.engine.render.shadows.TopLevel;
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
import com.truesim.scene.effects.Init;
import com.truesim.scene.effects.Renderer;
import com.truesim.scene.effects.Spawn;
import com.truesim.scene.meshbuild.MeshBuilder;
import java.lang.foreign.ValueLayout;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkBufferImageCopy;

/**
 * Mesh particles (E15, effects.md §3, step 16): instances the simulation writes into the scene's range the GPU writes,
 * before culling, drawn, culled in two phases and shadowed as any, the same by forward and visibility buffer shading;
 * left out of ray tracing, so the top level stands while they move. Validated, over three frames or more.
 */
class MeshParticlesTest {

    private static final int SIZE = 64;
    private static final long FRAME = 16_667;
    private static final AssetType<String> MESHES = AssetType.of("test.mesh", String.class, s -> 0);
    private static final AssetRef<?> QUAD = AssetRef.of(MESHES, new AssetId(0, 1));
    private static final AssetRef<?> UNLOADED = AssetRef.of(MESHES, new AssetId(0, 2));

    private VulkanInstance instance;
    private VulkanDevice device;
    private GeometryPool pool;
    private GpuScene scene;
    private MeshletRenderer renderer;
    private Materials materials;
    private Particles particles;
    private int quad;
    private MeshData quadData;
    private int glowing;
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

    @AfterEach
    void stop() {
        if (device != null) {
            device.waitIdle();
            int errors = instance.validationErrors();
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

    /** The scene, its renderer and the particles, with room for {@code meshParticles} mesh particles. */
    private void scene(FrameGraph graph, int meshParticles) {
        pool = new GeometryPool(device, GeometryPool.Capacity.of(64, 4, 4), graph.frameSlots());
        scene = new GpuScene(device, pool, 4, graph.frameSlots());
        renderer = new MeshletRenderer(device, scene, VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_D32_SFLOAT,
            graph.frameSlots());
        materials = new Materials(device, 8, graph.frameSlots());
        scene.setMaterials(materials);
        quadData = quad();
        quad = pool.add(quadData);
        glowing = materials.add(new Material(MetallicRoughnessParameters.DEFAULTS
            .withBaseColor(new Color(0, 0, 0, 1)).withEmissive(new Color(1, 1, 1, 1))));
        particles = new Particles(device, graph.frameSlots(), 2 << 20, 16).meshes(renderer, meshParticles);
        particles.mesh(QUAD, quad, glowing);
    }

    private void close(FrameGraph graph) {
        device.waitIdle();
        graph.close();
        particles.close();
        materials.close();
        renderer.close();
        scene.close();
        pool.close();
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

    /** Builds the top level of {@code rayTracing} each frame. */
    private record Traced(RayTracing rayTracing, MeshletRenderer renderer) implements Technique {
        static final Key<Image> TRACED = Key.image("test.traced");

        @Override
        public List<Key<?>> provides() {
            return List.of(TRACED);
        }

        @Override
        public void build(GraphBuilder g) {
            com.truesim.engine.graph.Buffer top = g.request(new TopLevel(rayTracing, renderer));
            Image image = g.create("traced", com.truesim.engine.graph.ImageDesc.of(Format.R8G8B8A8_UNORM, 4, 4));
            g.pass("traced").read(top, com.truesim.engine.graph.BufferAccess.ACCELERATION_STRUCTURE_READ)
                .write(image, Access.TRANSFER_DST).record(ctx -> ctx.clear(image.mip(0), 0, 0, 0, 1));
            g.provide(TRACED, image);
        }
    }

    /** A frame graph drawing the scene from the origin down -z, shaded forward or through the visibility buffer. */
    private FrameGraph graph(boolean visibility, GpuBuffer pixels, GpuImage target) {
        FrameGraph graph = new FrameGraph(device, 2);
        scene(graph, 16);
        SceneGeometry geometry = SceneGeometry.meshlets(renderer);
        ViewPort port = graph.addView("main").size(SIZE, SIZE);
        Camera camera = new Camera().set(0, 0, -1, 0, 1, 0, Math.PI / 2, SIZE, SIZE, 0.1);
        SceneView view = geometry.createView(port, camera, ViewOptions.of(256, 1 << 12).withOcclusion(true))
            .set(1, MeshletRenderer.MODE_LIT);
        port.add(visibility ? new VisibilityBufferShading(geometry, view, VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_D32_SFLOAT)
                    .clearColor(0, 0, 0)
                : new ClusteredForwardShading(geometry, view, VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_D32_SFLOAT)
                    .clearColor(0, 0, 0))
            .add(new ParticleDraw(particles, view)).add(new Capture(pixels))
            .output(Keys.SCENE_COLOR, new ExternalImage("target", SIZE, SIZE, Format.R8G8B8A8_UNORM,
                VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT,
                VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL)
                .set(target.handle(), target.view()));
        graph.add(new ParticleSimulation(particles));
        return graph;
    }

    /** A frame: the runtime, the particles, the graph. */
    private void frame(FrameGraph graph) {
        nodes.update();
        runtime.update(FRAME);
        graph.beginFrame();
        particles.update(effects, runtime.spawns());
        graph.submit(0, 0);
    }

    /** Pixels lit (red above 128). */
    private static int lit(GpuBuffer pixels) {
        int n = 0;
        for (int pixel : pixels.memory().toArray(ValueLayout.JAVA_INT)) {
            n += (pixel & 0xff) > 128 ? 1 : 0;
        }
        return n;
    }

    /** Debris: a burst of quads at once, each as its emitter turns it, of the size given, slowly moving along x. */
    private static EffectData debris(int count, float size, boolean shadows) {
        return EffectData.builder().emitter("rocks", e -> e.capacity(count).spawn(Spawn.burst(0, count))
            .init(Init.lifetime(100), Init.size(Bind.constant(size)),
                Init.velocity(Bind.constant(0.3f), Bind.constant(0), Bind.constant(0)))
            .render(Renderer.mesh(QUAD, QUAD).set("orient", 2).castsShadows(shadows)))
            .build();
    }

    @Test
    void meshParticlesDrawAsInstancesFromTheirFirstFrameAndBothShadingsAgree() {
        int[][] images = new int[2][];
        int usage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT
            | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
        for (int v = 0; v < 2; v++) {
            try (GpuBuffer pixels = GpuBuffer.hostVisible(device, 4L * SIZE * SIZE, VK_BUFFER_USAGE_TRANSFER_DST_BIT);
                 GpuImage target = new GpuImage(device, SIZE, SIZE, VK_FORMAT_R8G8B8A8_UNORM, usage,
                     VK_IMAGE_ASPECT_COLOR_BIT)) {
                FrameGraph graph = graph(v == 1, pixels, target);
                com.truesim.scene.effects.Effect played = effects.play(debris(1, 1, false), 0, 0, -4).seed(3);
                frame(graph);
                device.waitIdle();
                // Written before culling: the frame it is born in draws it (a 2 m quad 4 m away: 16 x 16 pixels)
                assertThat(lit(pixels)).as("drawn in its first frame").isBetween(200, 300);
                long version = scene.version();
                for (int f = 0; f < 4; f++) { // occlusion's two phases over frames: it stays drawn as it moves
                    frame(graph);
                }
                device.waitIdle();
                assertThat(lit(pixels)).as("still drawn").isBetween(200, 300);
                assertThat(scene.version()).as("the CPU changed no instance").isEqualTo(version);
                assertThat(graph.passes()).contains("particle instances");
                images[v] = pixels.memory().toArray(ValueLayout.JAVA_INT);
                effects.play(EffectData.builder().emitter("unloaded", e -> e.capacity(4).spawn(Spawn.burst(0, 4))
                    .init(Init.lifetime(100)).render(Renderer.mesh(UNLOADED, UNLOADED))).build(), 0, 0, -2);
                frame(graph);
                frame(graph);
                device.waitIdle();
                assertThat(lit(pixels)).as("a mesh not registered draws nothing").isBetween(200, 300);
                played.kill();
                close(graph);
            }
        }
        int differing = 0;
        for (int i = 0; i < images[0].length; i++) {
            differing += Math.abs((images[0][i] & 0xff) - (images[1][i] & 0xff)) > 8 ? 1 : 0;
        }
        assertThat(differing).as("forward and visibility buffer shading draw the same").isLessThanOrEqualTo(4);
    }

    /** Copies the scene's instances into a host buffer. */
    private GpuBuffer instances() {
        GpuBuffer copy = GpuBuffer.hostVisible(device, scene.instanceBuffer().size(), VK_BUFFER_USAGE_TRANSFER_DST_BIT);
        device.waitIdle();
        device.submitAndWait(device.graphics(), commands -> {
            try (var stack = stackPush()) {
                VkBufferCopy.Buffer region = VkBufferCopy.calloc(1, stack).size(copy.size());
                org.lwjgl.vulkan.VK10.vkCmdCopyBuffer(commands, scene.instanceBuffer().handle(), copy.handle(),
                    region);
            }
        });
        return copy;
    }

    @Test
    void theirInstancesCastShadowsAsTheirRendererSaysAndStayOutOfRayTracing() {
        FrameGraph graph = new FrameGraph(device, 2);
        scene(graph, 8);
        RayTracing rayTracing = device.rayTracing() ? new RayTracing(device, pool, 16) : null;
        if (rayTracing != null) {
            rayTracing.add(quad, quadData, quadData.levels().size() - 1);
        }
        int usage = VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
        GpuImage target = new GpuImage(device, 4, 4, VK_FORMAT_R8G8B8A8_UNORM, usage
            | VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT, VK_IMAGE_ASPECT_COLOR_BIT);
        graph.add(new ParticleSimulation(particles));
        GpuImage traced = new GpuImage(device, 4, 4, VK_FORMAT_R8G8B8A8_UNORM, usage, VK_IMAGE_ASPECT_COLOR_BIT);
        if (rayTracing != null) {
            graph.add(new Traced(rayTracing, renderer)).output(Traced.TRACED, new ExternalImage("traced", 4, 4,
                Format.R8G8B8A8_UNORM, usage, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL)
                .set(traced.handle(), traced.view()));
        }
        // A culled view: what culls the scene builds the simulation first (SceneData.written)
        SceneGeometry geometry = SceneGeometry.meshlets(renderer);
        ViewPort port = graph.addView("main").size(4, 4);
        SceneView view = geometry.createView(port, new Camera().set(0, 0, -1, 0, 1, 0, 1, 4, 4, 0.1),
            ViewOptions.of(64, 1 << 10)).set(1, MeshletRenderer.MODE_LIT);
        port.add(new ClusteredForwardShading(geometry, view, VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_D32_SFLOAT))
            .output(Keys.SCENE_COLOR, new ExternalImage("target", 4, 4, Format.R8G8B8A8_UNORM,
                usage | VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT, VK_IMAGE_LAYOUT_UNDEFINED,
                VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL).set(target.handle(), target.view()));
        int base = scene.gpuFirst();
        assertThat(scene.gpuCount()).isEqualTo(8);
        assertThat(scene.highWater()).isEqualTo(base + 8);
        assertThatIllegalArgumentException().isThrownBy(() -> scene.set(base, quad, 0, 0f, 0f, 0f, 0f, 0f, 0f, 1f,
            1f, 1f, 1f)).withMessageContaining("GPU");
        assertThatIllegalStateException().isThrownBy(() -> scene.reserveGpuRange(2));
        assertThat(scene.freeId() < base || scene.freeId() >= base + 8).as("the CPU takes ids beside the range").isTrue();
        com.truesim.scene.effects.Effect casting = effects.play(debris(3, 0.5f, true), 1, 2, 3);
        com.truesim.scene.effects.Effect plain = effects.play(debris(2, 0.5f, false), 1, 2, 3);
        for (int f = 0; f < 3 || rayTracing != null && rayTracing.pendingBottomLevels() > 0 && f < 30; f++) {
            frame(graph); // the bottom level built in frames meanwhile
        }
        frame(graph);
        frame(graph);
        long builds = rayTracing == null ? 0 : rayTracing.topLevelBuilds();
        for (int f = 0; f < 4; f++) {
            frame(graph);
        }
        if (rayTracing != null) {
            assertThat(rayTracing.topLevelBuilds()).as("the top level stands while particles move").isEqualTo(builds);
        }
        try (GpuBuffer copy = instances()) {
            int shadowed = 0;
            int live = 0;
            for (int id = base; id < base + 8; id++) {
                int mesh = copy.memory().get(ValueLayout.JAVA_INT, (long) InstanceLayout.BYTES * id
                    + InstanceLayout.MESH);
                live += (mesh & CommonConstants.INSTANCE_LIVE) != 0 ? 1 : 0;
                shadowed += (mesh & CommonConstants.INSTANCE_CASTS_SHADOWS) != 0 ? 1 : 0;
                if ((mesh & CommonConstants.INSTANCE_LIVE) != 0) {
                    assertThat(mesh & 0xffffff).isEqualTo(quad);
                    float x = copy.memory().get(ValueLayout.JAVA_FLOAT, (long) InstanceLayout.BYTES * id
                        + InstanceLayout.POSITION) + copy.memory().get(ValueLayout.JAVA_FLOAT, (long) InstanceLayout
                        .BYTES * id + InstanceLayout.POSITION_LOW);
                    assertThat(x).as("moved along x").isGreaterThan(1.05f);
                    assertThat(copy.memory().get(ValueLayout.JAVA_FLOAT, (long) InstanceLayout.BYTES * id
                        + InstanceLayout.SCALE)).isEqualTo(0.5f);
                }
            }
            assertThat(live).isEqualTo(5);
            assertThat(shadowed).as("only the casting emitter's").isEqualTo(3);
        }
        // Ended: their slots go back to holes
        casting.kill();
        plain.kill();
        for (int f = 0; f < 3; f++) {
            frame(graph);
        }
        try (GpuBuffer copy = instances()) {
            for (int id = base; id < base + 8; id++) {
                assertThat(copy.memory().get(ValueLayout.JAVA_INT, (long) InstanceLayout.BYTES * id
                    + InstanceLayout.MESH)).as("a hole").isZero();
            }
        }
        assertThat(particles.meshUnplaced()).isZero();
        assertThatIllegalArgumentException().isThrownBy(() -> particles.meshes(renderer, -1));
        assertThatIllegalArgumentException().isThrownBy(() -> scene.reserveGpuRange(0));
        device.waitIdle();
        graph.close();
        if (rayTracing != null) {
            rayTracing.close();
        }
        target.close();
        traced.close();
        particles.meshes(renderer, 0); // gives the range back
        assertThat(scene.gpuCount()).isZero();
        particles.forgetMesh(QUAD);
        particles.close();
        materials.close();
        renderer.close();
        scene.close();
        pool.close();
    }
}
