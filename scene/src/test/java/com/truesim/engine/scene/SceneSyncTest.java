package com.truesim.engine.scene;

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
import static org.lwjgl.vulkan.VK10.vkCmdCopyImageToBuffer;

import com.truesim.common.jobs.TaskScheduler;
import java.time.Duration;
import com.truesim.common.state.ChangeClock;
import com.truesim.engine.graph.Access;
import com.truesim.engine.graph.ExternalImage;
import com.truesim.engine.graph.FrameGraph;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Image;
import com.truesim.engine.graph.Key;
import com.truesim.engine.graph.Technique;
import com.truesim.engine.graph.ViewPort;
import com.truesim.engine.render.Camera;
import com.truesim.engine.render.ClusteredForwardShading;
import com.truesim.engine.render.GeometryPool;
import com.truesim.engine.render.GpuScene;
import com.truesim.engine.render.Keys;
import com.truesim.common.math.color.Color;
import com.truesim.engine.render.Material;
import com.truesim.engine.render.materials.ClearcoatParameters;
import com.truesim.engine.render.materials.MetallicRoughnessParameters;
import com.truesim.engine.scene.materials.Tinted;
import com.truesim.engine.scene.materials.TintedParameters;
import com.truesim.engine.render.Materials;
import com.truesim.engine.render.MeshletRenderer;
import com.truesim.engine.render.RenderView;
import com.truesim.engine.render.SceneGeometry;
import com.truesim.engine.render.SceneView;
import com.truesim.engine.render.Textures;
import com.truesim.engine.render.ViewOptions;
import com.truesim.engine.render.VisibilityBufferShading;
import com.truesim.engine.vulkan.FrameSlots;
import com.truesim.engine.vulkan.GpuBuffer;
import com.truesim.engine.vulkan.GpuImage;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.engine.vulkan.VulkanException;
import com.truesim.engine.vulkan.VulkanInstance;
import com.truesim.scene.assets.AssetCache;
import com.truesim.scene.assets.AssetRef;
import com.truesim.scene.assets.AssetState;
import com.truesim.scene.assets.Generator;
import com.truesim.scene.assets.mesh.MeshData;
import com.truesim.scene.assets.mesh.VertexAttribute;
import com.truesim.scene.assets.mesh.VertexFormat;
import com.truesim.scene.core.NodeHandle;
import com.truesim.scene.core.SceneGraph;
import com.truesim.scene.meshbuild.MeshBuilder;
import com.truesim.scene.render.MaterialData;
import com.truesim.scene.render.RenderAssets;
import com.truesim.scene.render.Renderables;
import com.truesim.scene.render.TextureData;
import com.truesim.engine.vulkan.Format;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkBufferImageCopy;

/**
 * Scenes kept on the GPU and drawn, validated: assets load into the shared resources once, renderables follow the scene's
 * changes, and several scenes draw with frame graphs of their own, each in its own rendering mode, sharing frame slots.
 */
class SceneSyncTest {

    private static final String MR = MaterialData.METALLIC_ROUGHNESS;

    private static final int SIZE = 64;
    private static final float HALF = (float) Math.sqrt(0.5);
    private static final int INSTANCES = 4;

    /** A flat square facing up, {@code size} wide. */
    record Square(float size) {
    }

    /** A material glowing in one color. */
    record Glow(float r, float g, float b) {
    }

    /** A texture of one color. */
    record Plain(int rgba) {
    }

    static final Generator<Square, MeshData> SQUARES = (square, seed) -> square(square.size());
    /** Squares side by side, one submesh each: a mesh of {@code count} submeshes. */
    static final Generator<Integer, MeshData> STRIPS = (count, seed) -> strips(count);
    static final Generator<Glow, MaterialData> GLOWS = (glow, seed) -> MaterialData.builder(MR)
        .set("baseColor", 0f, 0f, 0f, 1f).set("emissive", glow.r(), glow.g(), glow.b()).build();
    static final Generator<Plain, TextureData> PLAINS = (plain, seed) -> {
        MemorySegment texels = Arena.ofAuto().allocate(4 * 4);
        for (int i = 0; i < 4; i++) {
            texels.setAtIndex(ValueLayout.JAVA_INT, i, plain.rgba());
        }
        return new TextureData(2, 2, TextureData.ColorSpace.SRGB, texels);
    };
    static final AssetRef<MeshData> SQUARE = AssetRef.generated(RenderAssets.MESH, SQUARES, new Square(2), 1);
    static final AssetRef<MeshData> SMALL = AssetRef.generated(RenderAssets.MESH, SQUARES, new Square(1), 1);
    static final AssetRef<MaterialData> RED = AssetRef.generated(RenderAssets.MATERIAL, GLOWS, new Glow(1, 0, 0), 1);
    static final AssetRef<MaterialData> GREEN = AssetRef.generated(RenderAssets.MATERIAL, GLOWS, new Glow(0, 1, 0), 1);

    private VulkanInstance instance;
    private VulkanDevice device;
    private final FrameSlots slots = new FrameSlots(2);
    private GeometryPool pool;
    private Materials materials;
    private Textures textures;
    private TaskScheduler tasks;
    private AssetCache assets;
    private SceneResources resources;
    private final List<AutoCloseable> closing = new ArrayList<>();
    private final List<RenderView> cameraViews = new ArrayList<>(); // set to the camera every frame

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
        pool = new GeometryPool(device, GeometryPool.Capacity.of(1 << 10, 16, 4), slots);
        materials = new Materials(device, 16, slots);
        textures = new Textures(device, slots);
        tasks = new TaskScheduler();
        assets = new AssetCache(tasks);
        resources = new SceneResources(assets, pool, materials, textures);
    }

    @AfterEach
    void stop() throws Exception {
        if (device != null) {
            device.waitIdle();
            int errors = instance.validationErrors();
            for (int i = closing.size() - 1; i >= 0; i--) {
                closing.get(i).close();
            }
            resources.close();
            assets.close();
            tasks.close();
            textures.close();
            materials.close();
            pool.close();
            device.close();
            instance.close();
            assertThat(errors).as("validation errors").isZero();
        } else if (instance != null) {
            instance.close();
        }
    }

    static MeshData strips(int count) {
        float[] positions = new float[12 * count];
        float[] normals = new float[12 * count];
        float[] uvs = new float[8 * count];
        MeshBuilder builder = new MeshBuilder(VertexFormat.of(VertexAttribute.NORMAL, VertexAttribute.UV0), 4 * count);
        int[][] submeshes = new int[count][];
        for (int i = 0; i < count; i++) {
            float[] quad = {i, 0, -0.5f, i + 1, 0, -0.5f, i, 0, 0.5f, i + 1, 0, 0.5f};
            System.arraycopy(quad, 0, positions, 12 * i, 12);
            for (int v = 0; v < 4; v++) {
                normals[12 * i + 3 * v + 1] = 1;
                uvs[8 * i + 2 * v] = v % 2;
                uvs[8 * i + 2 * v + 1] = v / 2;
            }
            int b = 4 * i;
            submeshes[i] = new int[] {b, b + 2, b + 1, b + 1, b + 2, b + 3};
        }
        builder.positions(positions).attribute(VertexAttribute.NORMAL, normals).attribute(VertexAttribute.UV0, uvs);
        for (int[] submesh : submeshes) {
            builder.submesh(submesh);
        }
        return builder.buildFlat();
    }

    static MeshData square(float size) {
        float h = size / 2;
        float[] positions = {-h, 0, -h, h, 0, -h, -h, 0, h, h, 0, h};
        float[] normals = {0, 1, 0, 0, 1, 0, 0, 1, 0, 0, 1, 0};
        float[] uvs = {0, 0, 1, 0, 0, 1, 1, 1};
        return new MeshBuilder(VertexFormat.of(VertexAttribute.NORMAL, VertexAttribute.UV0), 4).positions(positions)
            .attribute(VertexAttribute.NORMAL, normals).attribute(VertexAttribute.UV0, uvs)
            .submesh(new int[] {0, 2, 1, 1, 2, 3}).buildFlat();
    }

    /** Lets the background loads finish, at frame boundaries. */
    private void load() throws InterruptedException {
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (assets.loadingCount() > 0 || tasks.pending() > 0) {
            assertThat(System.nanoTime()).as("loads finishing").isLessThan(deadline);
            Thread.sleep(1);
            tasks.drainCompleted();
        }
    }

    /**
     * Frames until what they ask for has loaded and shows. A load can start another only in a frame (a renderable's
     * materials are asked for when its mesh shows), so frames alternate with waiting for the loads they started.
     */
    private void settle(List<FrameGraph> graphs, List<SceneSync> syncs) throws InterruptedException {
        for (int round = 0; round < 3; round++) { // the mesh, then its materials (with their textures), then shown
            frame(graphs, syncs, camera);
            load();
        }
        frame(graphs, syncs, camera);
    }

    private <T extends AutoCloseable> T closing(T closeable) {
        closing.add(closeable);
        return closeable;
    }

    private GpuScene gpu() {
        return closing(new GpuScene(device, pool, INSTANCES, slots));
    }

    private GpuBuffer readback() {
        return closing(GpuBuffer.hostVisible(device, SIZE * SIZE * 4, VK_BUFFER_USAGE_TRANSFER_DST_BIT));
    }

    /** A node facing a camera looking down -z from the origin, at (x, y, z). */
    private static long facing(SceneGraph scene, double x, double y, double z) {
        long node = scene.create();
        scene.setTranslation(node, x, y, z);
        scene.setRotation(node, HALF, 0, 0, HALF);
        return node;
    }

    /** Copies {@link Keys#SCENE_COLOR} of each frame into the readback of the frame's slot. */
    private record Readback(FrameGraph graph, GpuBuffer[] readbacks) implements Technique {
        @Override
        public List<Key<?>> provides() {
            return List.of();
        }

        @Override
        public List<Key<?>> modifies() {
            return List.of(Keys.SCENE_COLOR);
        }

        @Override
        public void build(GraphBuilder g) {
            Image color = g.modify(Keys.SCENE_COLOR);
            g.pass("readback").read(color, Access.TRANSFER_SRC).sideEffects().record(ctx -> {
                try (MemoryStack stack = stackPush()) {
                    VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
                    region.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
                    region.imageExtent().set(SIZE, SIZE, 1);
                    vkCmdCopyImageToBuffer(ctx.unsafe().commands(), ctx.unsafe().image(color), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                        readbacks[graph.frameSlots().current()].handle(), region);
                }
            });
            g.provide(Keys.SCENE_COLOR, color);
        }
    }

    /**
     * A graph drawing {@code sync}'s instances with a renderer of its own, in a rendering mode, reading every frame back;
     * its camera's sync is the last of {@link #cameraViews}, made with {@code options}.
     */
    private FrameGraph graph(SceneSync sync, boolean visibilityBuffer, GpuBuffer[] readbacks, ViewOptions options) {
        FrameGraph graph = closing(new FrameGraph(device, slots));
        MeshletRenderer renderer = closing(new MeshletRenderer(device, sync.gpu(), VK_FORMAT_R8G8B8A8_UNORM,
            VK_FORMAT_D32_SFLOAT, slots));
        SceneGeometry geometry = SceneGeometry.meshlets(renderer);
        ViewPort port = graph.addView("main").size(SIZE, SIZE);
        // the frame's camera is set on the render view every frame (frame), not taken from the scene view
        SceneView cameraView = new SceneView(port, geometry.createView(options), new Camera());
        cameraViews.add(cameraView.view());
        Technique shading = visibilityBuffer
            ? new VisibilityBufferShading(geometry, cameraView, VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_D32_SFLOAT)
                .clearColor(0, 0, 0)
            : new ClusteredForwardShading(geometry, cameraView, VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_D32_SFLOAT)
                .clearColor(0, 0, 0);
        int usage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT
            | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
        GpuImage target = closing(new GpuImage(device, SIZE, SIZE, VK_FORMAT_R8G8B8A8_UNORM, usage,
            VK_IMAGE_ASPECT_COLOR_BIT));
        ExternalImage output = new ExternalImage("target", SIZE, SIZE, Format.R8G8B8A8_UNORM, usage,
            VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL).set(target.handle(), target.view());
        port.add(shading).add(new Readback(graph, readbacks)).output(Keys.SCENE_COLOR, output);
        return graph;
    }

    /** {@link #graph(SceneSync, boolean, GpuBuffer[], ViewOptions)} of a sync of every layer, with occlusion. */
    private FrameGraph graph(SceneSync sync, boolean visibilityBuffer, GpuBuffer[] readbacks) {
        return graph(sync, visibilityBuffer, readbacks, ViewOptions.of(256, 1 << 12).withOcclusion(true));
    }

    /**
     * Runs a frame of every graph in step, as an application drawing several scenes does: every graph begins (waiting
     * for the slot's last frame), the shared slot opens, the scenes update, the cameras' syncs are set, every graph
     * submits, the slot closes.
     */
    private int frame(List<FrameGraph> graphs, List<SceneSync> syncs, Camera camera) {
        int slot = -1;
        for (int g = 0; g < graphs.size(); g++) {
            slot = graphs.get(g).beginFrame();
        }
        slots.begin(slot);
        tasks.drainCompleted(); // the frame boundary: loads arrive
        for (int s = 0; s < syncs.size(); s++) {
            SceneSync sync = syncs.get(s);
            sync.renderables().scene().update();
            sync.renderables().update();
            sync.update();
        }
        for (int v = 0; v < cameraViews.size(); v++) {
            cameraViews.get(v).set(camera, 1, MeshletRenderer.MODE_LIT);
        }
        for (int g = 0; g < graphs.size(); g++) {
            graphs.get(g).submit(0, 0);
        }
        slots.end();
        return slot;
    }

    /** The mean of channel {@code shift} (0 red, 8 green) over the lit pixels' columns, or NaN with none lit. */
    private static double litColumn(GpuBuffer readback, int shift) {
        double sum = 0;
        int count = 0;
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                if (((readback.memory().getAtIndex(ValueLayout.JAVA_INT, y * SIZE + x) >> shift) & 0xff) > 128) {
                    sum += x;
                    count++;
                }
            }
        }
        return count == 0 ? Double.NaN : sum / count;
    }

    private final Camera camera = new Camera().set(0, 0, -1, 0, 1, 0, Math.PI / 2, SIZE, SIZE, 0.1);

    @Test
    void renderablesDrawOnceTheirAssetsLoadRelativeToTheCamera() throws InterruptedException {
        SceneGraph scene = new SceneGraph(new ChangeClock());
        Renderables renderables = new Renderables(scene);
        SceneSync sync = closing(new SceneSync(resources, renderables, gpu()));
        GpuBuffer[] readbacks = {readback(), readback()};
        FrameGraph graph = graph(sync, false, readbacks);
        // Far from the world origin, left of a camera there too: floats relative to the camera keep it precise.
        long node = facing(scene, 1e7 - 1, 0, -3);
        renderables.set(node, SQUARE, List.of(RED));
        camera.position(1e7, 0, 0);
        frame(List.of(graph), List.of(sync), camera);
        assertThat(sync.renderableCount()).isEqualTo(1);
        assertThat(sync.instanceCount()).as("waiting for the mesh").isZero();
        assertThat(sync.slotOf(node)).isEqualTo(-1);
        assertThat(sync.firstMaterialOf(node)).isEqualTo(-1);
        assertThat(sync.meshOf(node)).isEqualTo(-1);
        load();
        for (int frame = 0; frame < 4; frame++) {
            frame(List.of(graph), List.of(sync), camera);
        }
        device.waitIdle();
        assertThat(sync.instanceCount()).isEqualTo(1);
        assertThat(sync.slotOf(node)).isZero();
        assertThat(sync.meshOf(node)).isNotNegative();
        assertThat(sync.firstMaterialOf(node)).isPositive();
        assertThat(resources.uploadedMeshCount()).isEqualTo(1);
        assertThat(resources.materialSetCount()).isEqualTo(1);
        assertThat(resources.materialCount()).isEqualTo(1);
        assertThat(litColumn(readbacks[0], 0)).as("red, left of the camera").isLessThan(SIZE / 2.0 - 4);
        assertThat(litColumn(readbacks[0], 8)).as("no green").isNaN();
        // Moving the node moves its instance; moving the camera moves every instance.
        scene.setTranslation(node, 1e7 + 1, 0, -3);
        for (int frame = 0; frame < 2; frame++) {
            frame(List.of(graph), List.of(sync), camera);
        }
        device.waitIdle();
        assertThat(litColumn(readbacks[0], 0)).as("moved right").isGreaterThan(SIZE / 2.0 + 4);
        camera.position(1e7 + 2, 0, 0);
        for (int frame = 0; frame < 2; frame++) {
            frame(List.of(graph), List.of(sync), camera);
        }
        device.waitIdle();
        assertThat(litColumn(readbacks[0], 0)).as("the camera moved past it").isLessThan(SIZE / 2.0 - 4);
        // A material change draws in the new one, keeping the mesh on the GPU and the instance id.
        int mesh = sync.meshOf(node);
        renderables.setMaterials(node, List.of(GREEN));
        frame(List.of(graph), List.of(sync), camera);
        assertThat(sync.meshOf(node)).as("the same mesh, not uploaded again").isEqualTo(mesh);
        assertThat(sync.slotOf(node)).isZero();
        load();
        for (int frame = 0; frame < 2; frame++) {
            frame(List.of(graph), List.of(sync), camera);
        }
        device.waitIdle();
        assertThat(litColumn(readbacks[0], 8)).as("green now").isLessThan(SIZE / 2.0 - 4);
        assertThat(resources.materialCount()).as("red let go").isEqualTo(1);
        assertThat(assets.holders(RED)).isZero();
    }

    @Test
    void instancesWrittenOnManyThreadsAreTheSame() throws InterruptedException {
        // 5,000 small squares, followed twice: by a sync writing on the calling thread and one writing on four. All
        // move each frame: every instance is rewritten, in parallel in the second, and both draw the same image.
        SceneGraph scene = new SceneGraph(new ChangeClock(), 6000, SceneGraph.DEFAULT_LOG_CAPACITY);
        Renderables renderables = new Renderables(scene);
        long[] nodes = new long[5000];
        for (int i = 0; i < nodes.length; i++) {
            nodes[i] = facing(scene, (i % 100 - 49.5) * 0.05, (i / 100 - 24.5) * 0.05, -3);
            scene.setScale(nodes[i], 0.01, 0.01, 0.01);
            renderables.set(nodes[i], SQUARE, List.of(RED));
        }
        try (com.truesim.common.jobs.JobSystem jobs = new com.truesim.common.jobs.JobSystem(4)) {
            SceneSync serial = closing(new SceneSync(resources, renderables, closing(new GpuScene(device, pool, 5000,
                slots))));
            SceneSync parallel = closing(new SceneSync(resources, renderables, closing(new GpuScene(device, pool,
                5000, slots)), jobs));
            GpuBuffer[] serialReadbacks = {readback(), readback()};
            GpuBuffer[] parallelReadbacks = {readback(), readback()};
            ViewOptions room = ViewOptions.of(8192, 1 << 16).withOcclusion(true); // every square's meshlet listed
            List<FrameGraph> graphs = List.of(graph(serial, false, serialReadbacks, room),
                graph(parallel, false, parallelReadbacks, room));
            List<SceneSync> syncs = List.of(serial, parallel);
            frame(graphs, syncs, camera);
            load();
            for (int frame = 0; frame < 4; frame++) {
                for (int i = 0; i < nodes.length; i++) {
                    scene.setTranslation(nodes[i], (i % 100 - 49.5) * 0.05 + 0.01 * frame, (i / 100 - 24.5) * 0.05,
                        -3);
                }
                frame(graphs, syncs, camera);
            }
            device.waitIdle();
            assertThat(parallel.instanceCount()).isEqualTo(5000);
            assertThat(cameraViews.get(1).visibleMeshlets()).isEqualTo(cameraViews.get(0).visibleMeshlets())
                .isPositive();
            for (int slot = 0; slot < 2; slot++) {
                for (int p = 0; p < SIZE * SIZE; p++) {
                    assertThat(parallelReadbacks[slot].memory().getAtIndex(ValueLayout.JAVA_INT, p))
                        .as("pixel %d of slot %d", p, slot)
                        .isEqualTo(serialReadbacks[slot].memory().getAtIndex(ValueLayout.JAVA_INT, p));
                }
            }
            assertThat(litColumn(parallelReadbacks[0], 0)).as("drawn").isNotNaN();
        }
    }

    @Test
    void maskedAndDoubleSidedMaterialsFollowTheirData() throws InterruptedException {
        // A scene's materials say how they rasterize, as glTF's do: one masked, one double-sided, one blended (opaque
        // until transparents come): each renderable draws in its raster bin.
        AssetRef<MaterialData> masked = AssetRef.generated(RenderAssets.MATERIAL, (Generator<Glow, MaterialData>)
            (glow, seed) -> MaterialData.builder(MR).set("baseColor", 1f, 1f, 1f, 1f)
                .alpha(MaterialData.AlphaMode.MASK, 0.5f).build(), new Glow(1, 1, 1), 4);
        AssetRef<MaterialData> twoSided = AssetRef.generated(RenderAssets.MATERIAL, (Generator<Glow, MaterialData>)
            (glow, seed) -> MaterialData.builder(MR).doubleSided(true).build(), new Glow(1, 1, 1), 5);
        AssetRef<MaterialData> blended = AssetRef.generated(RenderAssets.MATERIAL, (Generator<Glow, MaterialData>)
            (glow, seed) -> MaterialData.builder(MR).alpha(MaterialData.AlphaMode.BLEND, 0.5f).build(),
            new Glow(1, 1, 1), 6);
        SceneGraph scene = new SceneGraph(new ChangeClock());
        Renderables renderables = new Renderables(scene);
        SceneSync sync = closing(new SceneSync(resources, renderables, gpu()));
        List<FrameGraph> graphs = List.of(graph(sync, false, new GpuBuffer[] {readback(), readback()}));
        RenderView cameraView = cameraViews.getLast();
        renderables.set(facing(scene, -1.5, 0, -4), SQUARE, List.of(masked));
        renderables.set(facing(scene, 0, 0, -4), SQUARE, List.of(twoSided));
        renderables.set(facing(scene, 1.5, 0, -4), SQUARE, List.of(blended));
        settle(graphs, List.of(sync));
        device.waitIdle();
        assertThat(cameraView.visibleMeshlets(0)).as("the blended one, opaque").isEqualTo(1);
        assertThat(cameraView.visibleMeshlets(1)).as("the double-sided one").isEqualTo(1);
        assertThat(cameraView.visibleMeshlets(2)).as("the masked one").isEqualTo(1);
    }

    @Test
    void materialDataNamesItsTypeAndItsValuesByField() throws InterruptedException {
        // A game's type, from a project of its own: its data by field name, its nested base's by path. Two
        // renderables given equal data inline share one material; data the renderer cannot use draws as the default.
        materials.register(Tinted.TYPE);
        MaterialData green = MaterialData.builder("tinted").set("base.baseColor", 1f, 1f, 1f, 1f)
            .set("base.roughness", 0.3f).set("tint", 0f, 1f, 0f)
            .feature("clearcoat", coat -> coat.set("roughness", 0.2f)).build();
        MaterialData same = MaterialData.builder("tinted").set("base.baseColor", 1f, 1f, 1f, 1f)
            .set("base.roughness", 0.3f).set("tint", 0f, 1f, 0f)
            .feature("clearcoat", coat -> coat.set("roughness", 0.2f)).build();
        MaterialData unknown = MaterialData.builder("marble").build();
        MaterialData outOfRange = MaterialData.builder(MR).set("roughness", 7f).build();
        MaterialData unknownFeature = MaterialData.builder(MR).set("roughness", 0.25f)
            .feature("sparkles", sparkles -> sparkles.set("density", 1f)).build();
        MaterialData typos = MaterialData.builder(MR).set("roughnes", 0.5f).set("metallic", 0.5f)
            .feature("clearcoat", coat -> coat.set("strenght", 1f)).build();
        List<String> problems = new ArrayList<>();
        resources.diagnostics(new SceneResources.Diagnostics() {
            @Override
            public void materialProblem(AssetRef<MaterialData> material, String problem) {
                problems.add(problem);
            }
        });
        SceneGraph scene = new SceneGraph(new ChangeClock());
        Renderables renderables = new Renderables(scene);
        SceneSync sync = closing(new SceneSync(resources, renderables, closing(new GpuScene(device, pool, 8, slots))));
        List<FrameGraph> graphs = List.of(graph(sync, false, new GpuBuffer[] {readback(), readback()}));
        int before = materials.count();
        renderables.set(facing(scene, -1, 0, -4), SQUARE, List.of(green.ref()));
        renderables.set(facing(scene, 1, 0, -4), SQUARE, List.of(same.ref()));
        renderables.set(facing(scene, 0, 1, -4), SQUARE, List.of(unknown.ref()));
        renderables.set(facing(scene, 0, -1, -4), SQUARE, List.of(outOfRange.ref()));
        renderables.set(facing(scene, 2, 0, -4), SQUARE, List.of(unknownFeature.ref()));
        renderables.set(facing(scene, -2, 0, -4), SQUARE, List.of(typos.ref()));
        settle(graphs, List.of(sync));
        device.waitIdle();
        assertThat(materials.count()).as("one for the equal data, one each for the rest").isEqualTo(before + 5);
        // What could not be used is reported: the unknown type, the value out of range, the unknown feature, typos.
        assertThat(problems).anyMatch(p -> p.startsWith("no material type marble"))
            .anyMatch(p -> p.contains("roughness") && p.endsWith("it draws as the default"))
            .anyMatch(p -> p.startsWith("no material feature sparkles"))
            .contains("no field roughnes in its type: ignored", "clearcoat: no field strenght in its type: ignored")
            .hasSize(5);
        List<Material> made = new ArrayList<>();
        for (int id = 1; id < 64; id++) {
            if (materials.contains(id)) {
                made.add(materials.material(id));
            }
        }
        Material tinted = made.stream().filter(m -> m.parameters() instanceof TintedParameters).findFirst()
            .orElseThrow();
        TintedParameters p = (TintedParameters) tinted.parameters();
        assertThat(p.tint()).isEqualTo(new Color(0, 1, 0, 1));
        assertThat(p.base().baseColor()).isEqualTo(new Color(1, 1, 1, 1));
        assertThat(p.base().roughness()).isEqualTo(0.3f);
        assertThat(p.base().metallic()).as("not in the data: the default").isZero();
        assertThat(tinted.features()).containsExactly(ClearcoatParameters.DEFAULTS.withRoughness(0.2f));
        assertThat(made.stream().filter(m -> m.equals(Material.DEFAULT)).count())
            .as("the unknown type and the value out of range").isEqualTo(2);
        assertThat(made).anyMatch(m -> m.parameters() instanceof MetallicRoughnessParameters mr
            && mr.roughness() == 0.25f && m.features().isEmpty());
    }

    @Test
    void aClearCoatInTheDataTurnsTheFeatureOn() throws InterruptedException {
        AssetRef<MaterialData> coated = AssetRef.generated(RenderAssets.MATERIAL, (Generator<Glow, MaterialData>)
            (glow, seed) -> MaterialData.builder(MR)
                .feature("clearcoat", coat -> coat.set("roughness", 0.1f)).build(), new Glow(1, 1, 1), 7);
        SceneGraph scene = new SceneGraph(new ChangeClock());
        Renderables renderables = new Renderables(scene);
        SceneSync sync = closing(new SceneSync(resources, renderables, gpu()));
        List<FrameGraph> graphs = List.of(graph(sync, false, new GpuBuffer[] {readback(), readback()}));
        renderables.set(facing(scene, 0, 0, -4), SQUARE, List.of(coated));
        settle(graphs, List.of(sync));
        device.waitIdle();
        Materials materials = resources.materials();
        int found = 0;
        for (int id = 0; id < 64; id++) {
            if (materials.contains(id) && materials.features(id) == Material.FEATURE_CLEARCOAT) {
                found++;
            }
        }
        assertThat(found).isEqualTo(1);
    }

    @Test
    void scenesShareOneGpuSceneToldApartByLayers() throws InterruptedException {
        // A world and an editor's gizmos, two scenes in one GPU scene: their instances take ids from the one table,
        // and each graph's view draws one of them by its layers (the gizmos' nodes on layer 1).
        SceneGraph world = new SceneGraph(new ChangeClock());
        SceneGraph gizmos = new SceneGraph(new ChangeClock());
        Renderables worldRenderables = new Renderables(world);
        Renderables gizmoRenderables = new Renderables(gizmos);
        GpuScene gpu = gpu();
        SceneSync worldSync = closing(new SceneSync(resources, worldRenderables, gpu));
        SceneSync gizmoSync = closing(new SceneSync(resources, gizmoRenderables, gpu));
        GpuBuffer[] worldReadbacks = {readback(), readback()};
        GpuBuffer[] gizmoReadbacks = {readback(), readback()};
        List<FrameGraph> graphs = List.of(
            graph(worldSync, false, worldReadbacks, ViewOptions.of(256, 1 << 12).withLayers(1)),
            graph(gizmoSync, false, gizmoReadbacks, ViewOptions.of(256, 1 << 12).withLayers(1 << 1)));
        List<SceneSync> syncs = List.of(worldSync, gizmoSync);
        long left = facing(world, -1, 0, -3);
        long right = facing(gizmos, 1, 0, -3);
        gizmos.setLayers(right, 1 << 1);
        worldRenderables.set(left, SQUARE, List.of(RED));
        gizmoRenderables.set(right, SQUARE, List.of(RED));
        frame(graphs, syncs, camera);
        load();
        for (int frame = 0; frame < 3; frame++) {
            frame(graphs, syncs, camera);
        }
        device.waitIdle();
        assertThat(gpu.count()).isEqualTo(2);
        assertThat(worldSync.slotOf(left)).isNotEqualTo(gizmoSync.slotOf(right)).isNotNegative();
        assertThat(worldSync.gpu()).isSameAs(gizmoSync.gpu());
        for (int slot = 0; slot < 2; slot++) {
            assertThat(litColumn(worldReadbacks[slot], 0)).as("the world alone").isLessThan(SIZE / 2.0 - 4);
            assertThat(litColumn(gizmoReadbacks[slot], 0)).as("the gizmos alone").isGreaterThan(SIZE / 2.0 + 4);
        }
        // The gizmos go: their id frees, the world keeps its own.
        int worldId = worldSync.slotOf(left);
        gizmoSync.close();
        assertThat(gpu.count()).isEqualTo(1);
        assertThat(worldSync.slotOf(left)).isEqualTo(worldId);
        assertThat(gpu.freeId()).isEqualTo(worldId == 0 ? 1 : 0);
    }

    @Test
    void scenesDrawInGraphsOfTheirOwnSharingWhatTheyUse() throws InterruptedException {
        // Two scenes, each drawn by a graph of its own sharing the frame slots: one shaded forward, one through a
        // visibility buffer where the device can. Both use the same mesh and material: on the GPU once.
        boolean visibility = device.primitiveIds();
        SceneGraph first = new SceneGraph(new ChangeClock());
        SceneGraph second = new SceneGraph(new ChangeClock());
        Renderables firstRenderables = new Renderables(first);
        Renderables secondRenderables = new Renderables(second);
        SceneSync firstSync = closing(new SceneSync(resources, firstRenderables, gpu()));
        SceneSync secondSync = closing(new SceneSync(resources, secondRenderables, gpu()));
        GpuBuffer[] firstReadbacks = {readback(), readback()};
        GpuBuffer[] secondReadbacks = {readback(), readback()};
        List<FrameGraph> graphs = List.of(graph(firstSync, false, firstReadbacks),
            graph(secondSync, visibility, secondReadbacks));
        List<SceneSync> syncs = List.of(firstSync, secondSync);
        long left = facing(first, -1, 0, -3);
        long right = facing(second, 1, 0, -3);
        firstRenderables.set(left, SQUARE, List.of(RED));
        secondRenderables.set(right, SQUARE, List.of(RED));
        frame(graphs, syncs, camera);
        load();
        for (int frame = 0; frame < 4; frame++) {
            assertThat(frame(graphs, syncs, camera)).isEqualTo((frame + 1) % 2);
        }
        device.waitIdle();
        assertThat(resources.meshCount()).isEqualTo(1);
        assertThat(pool.meshCount()).isEqualTo(1);
        assertThat(resources.materialSetCount()).isEqualTo(1);
        assertThat(firstSync.firstMaterialOf(left)).isEqualTo(secondSync.firstMaterialOf(right));
        assertThat(assets.holders(SQUARE)).as("held once for both").isEqualTo(1);
        for (int slot = 0; slot < 2; slot++) {
            assertThat(litColumn(firstReadbacks[slot], 0)).as("the first scene").isLessThan(SIZE / 2.0 - 4);
            assertThat(litColumn(secondReadbacks[slot], 0)).as("the second scene").isGreaterThan(SIZE / 2.0 + 4);
        }
        // The first sync goes: the second still draws the shared mesh.
        int mesh = secondSync.meshOf(right);
        firstSync.close();
        assertThat(pool.contains(mesh)).isTrue();
        assertThat(firstSync.instanceCount()).isZero();
        assertThatIllegalStateException().isThrownBy(firstSync::update);
        firstSync.close(); // once is enough
        // The second scene's node goes too: the mesh leaves the GPU, its room reused once no frame draws it.
        second.remove(right);
        frame(List.of(graphs.get(1)), List.of(secondSync), camera);
        assertThat(secondSync.instanceCount()).isZero();
        assertThat(resources.meshCount()).isZero();
        assertThat(resources.materialSetCount()).isZero();
        assertThat(pool.contains(mesh)).isFalse();
        assertThat(assets.holders(SQUARE)).isZero();
        assertThat(materials.count()).as("the default alone").isEqualTo(1);
    }

    @Test
    void submeshesTakeTheirMaterialsAndTexturesArriveLater() throws InterruptedException {
        AssetRef<TextureData> white = AssetRef.generated(RenderAssets.TEXTURE, PLAINS, new Plain(0xffffffff), 1);
        AssetRef<MaterialData> textured = AssetRef.generated(RenderAssets.MATERIAL, (Generator<Glow, MaterialData>)
            (glow, seed) -> MaterialData.builder(MR).texture("baseColorTexture", white).build(), new Glow(1, 1, 1), 2);
        SceneGraph scene = new SceneGraph(new ChangeClock());
        Renderables renderables = new Renderables(scene);
        SceneSync sync = closing(new SceneSync(resources, renderables, gpu()));
        long a = facing(scene, -1, 0, -3);
        long b = facing(scene, 1, 0, -3);
        long bare = facing(scene, 0, 1, -3);
        renderables.set(a, SQUARE, List.of(textured));
        renderables.set(b, SQUARE, List.of(textured));
        renderables.set(bare, SQUARE, List.of()); // the default material
        update(scene, renderables, sync);
        load();
        update(scene, renderables, sync);
        load(); // the texture, asked for once the material loaded
        update(scene, renderables, sync);
        assertThat(sync.instanceCount()).isEqualTo(3);
        assertThat(sync.firstMaterialOf(a)).isEqualTo(sync.firstMaterialOf(b));
        assertThat(sync.firstMaterialOf(bare)).isNotEqualTo(sync.firstMaterialOf(a));
        assertThat(resources.textureCount()).isEqualTo(1);
        assertThat(textures.count()).isEqualTo(1);
        assertThat(assets.state(white)).isEqualTo(AssetState.LOADED);
        assertThat(sync.meshCount()).isEqualTo(1);
        // Gone: the texture leaves too.
        renderables.remove(a);
        renderables.remove(b);
        update(scene, renderables, sync);
        assertThat(resources.textureCount()).isZero();
        assertThat(assets.holders(white)).isZero();
        assertThat(sync.renderableCount()).isEqualTo(1);
    }

    private void update(SceneGraph scene, Renderables renderables, SceneSync sync) {
        tasks.drainCompleted();
        scene.update();
        renderables.update();
        sync.update();
    }

    /** A material of one gray, textured with {@code texture}: one of its own per {@code seed}. */
    private static AssetRef<MaterialData> textured(AssetRef<TextureData> texture, int seed) {
        return AssetRef.generated(RenderAssets.MATERIAL, (Generator<Glow, MaterialData>) (glow, s) ->
            MaterialData.builder(MR).texture("baseColorTexture", texture).build(), new Glow(0.5f, 0.5f, 0.5f), seed);
    }

    @Test
    void wholeRenderablesWaitForTheirAssetsStreamedOnesDoNot() throws InterruptedException {
        SceneGraph scene = new SceneGraph(new ChangeClock());
        Renderables renderables = new Renderables(scene);
        SceneSync sync = closing(new SceneSync(resources, renderables, gpu()));
        AssetRef<TextureData> white = AssetRef.generated(RenderAssets.TEXTURE, PLAINS, new Plain(0xffffffff), 41);
        AssetRef<TextureData> late = AssetRef.generated(RenderAssets.TEXTURE, PLAINS, new Plain(0xff00ff00), 42);
        AssetRef<MaterialData> whole = textured(white, 41);
        AssetRef<MaterialData> streamed = textured(late, 42);
        assertThat(resources.loadingOf(white)).as("the default").isEqualTo(SceneResources.Loading.STREAM);
        resources.loading(SceneResources.Loading.WHOLE).loading(streamed, SceneResources.Loading.STREAM)
            .loading(late, SceneResources.Loading.STREAM);
        assertThat(resources.loadingOf(white)).isEqualTo(SceneResources.Loading.WHOLE);
        assertThat(resources.loadingOf(late)).isEqualTo(SceneResources.Loading.STREAM);
        long waits = facing(scene, 0, 0, -3);
        long draws = facing(scene, 1, 0, -3);
        renderables.set(waits, SQUARE, List.of(whole));
        renderables.set(draws, SQUARE, List.of(streamed));
        update(scene, renderables, sync);
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (tasks.pending() > 0) { // the mesh loaded, not handed over yet
            assertThat(System.nanoTime()).isLessThan(deadline);
            Thread.sleep(1);
        }
        tasks.drainCompleted(); // the mesh arrives: its renderables' materials are asked for
        scene.update();
        renderables.update();
        sync.update(); // nothing more handed over meanwhile
        assertThat(sync.slotOf(draws)).as("streamed: drawn with its mesh").isNotNegative();
        assertThat(sync.slotOf(waits)).as("whole: waits for its material and texture").isEqualTo(-1);
        assertThat(sync.waitingCount()).isEqualTo(1);
        assertThat(resources.loadingCount()).isPositive();
        for (int round = 0; round < 4 && sync.slotOf(waits) < 0; round++) {
            load();
            update(scene, renderables, sync);
        }
        assertThat(sync.slotOf(waits)).as("drawn once all of it is on the GPU").isNotNegative();
        assertThat(sync.waitingCount()).isZero();
        assertThat(resources.loadingCount()).isZero();
        // A whole asset that fails is not waited for.
        AssetRef<TextureData> broken = AssetRef.generated(RenderAssets.TEXTURE, (Generator<Plain, TextureData>)
            (plain, seed) -> {
                throw new IllegalStateException("no such texture");
            }, new Plain(0), 43);
        long failing = facing(scene, 2, 0, -3);
        renderables.set(failing, SQUARE, List.of(textured(broken, 43)));
        for (int round = 0; round < 4 && sync.slotOf(failing) < 0; round++) {
            update(scene, renderables, sync);
            load();
        }
        update(scene, renderables, sync);
        assertThat(sync.slotOf(failing)).isNotNegative();
        resources.loading(late, null);
        assertThat(resources.loadingOf(late)).as("back to the default").isEqualTo(SceneResources.Loading.WHOLE);
    }

    @Test
    void assetsPreparedOnWorkersAreAwaitedWhole() throws InterruptedException {
        SceneGraph scene = new SceneGraph(new ChangeClock());
        Renderables renderables = new Renderables(scene);
        SceneSync sync = closing(new SceneSync(resources, renderables, gpu()));
        resources.prepareOn(tasks.platformLane("gpu prepare", 2)).loading(SceneResources.Loading.WHOLE);
        AssetRef<TextureData> white = AssetRef.generated(RenderAssets.TEXTURE, PLAINS, new Plain(0xffffffff), 51);
        long node = facing(scene, 0, 0, -3);
        renderables.set(node, SQUARE, List.of(textured(white, 51)));
        update(scene, renderables, sync);
        assertThat(resources.loadingCount()).isPositive();
        // A loading screen's wait: the mesh, its material and texture loaded, made ready on the workers and added.
        assertThat(resources.awaitLoaded(tasks, Duration.ofSeconds(10))).isTrue();
        assertThat(resources.loadingCount()).isZero();
        assertThat(resources.uploadedMeshCount()).isEqualTo(1);
        assertThat(textures.count()).isEqualTo(1);
        update(scene, renderables, sync);
        assertThat(sync.slotOf(node)).as("all of it drawn from the next frame").isNotNegative();
        // Let go of while being made ready: freed once the workers are done, nothing leaks.
        AssetRef<TextureData> gray = AssetRef.generated(RenderAssets.TEXTURE, PLAINS, new Plain(0xff808080), 52);
        long brief = facing(scene, 1, 0, -3);
        renderables.set(brief, SMALL, List.of(textured(gray, 52)));
        update(scene, renderables, sync);
        assertThat(resources.awaitLoaded(tasks, Duration.ZERO)).as("not at once").isFalse();
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (resources.meshCount() < 2 || tasks.pending() == 0 && resources.uploadedMeshCount() < 2) {
            assertThat(System.nanoTime()).isLessThan(deadline);
            Thread.sleep(1);
            tasks.drainCompleted();
        }
        renderables.remove(brief);
        update(scene, renderables, sync);
        load();
        update(scene, renderables, sync);
        assertThat(resources.meshCount()).isEqualTo(1);
        assertThat(resources.loadingCount()).isZero();
        resources.prepareOn(null);
    }

    @Test
    void renderablesBeyondTheTablesRoomGrowIt() throws InterruptedException {
        SceneGraph scene = new SceneGraph(new ChangeClock());
        Renderables renderables = new Renderables(scene);
        SceneSync sync = closing(new SceneSync(resources, renderables, gpu()));
        long[] nodes = new long[INSTANCES + 2];
        for (int i = 0; i < nodes.length; i++) {
            nodes[i] = facing(scene, i, 0, -3);
            renderables.set(nodes[i], SQUARE, List.of());
        }
        update(scene, renderables, sync);
        load();
        update(scene, renderables, sync);
        assertThat(sync.instanceCount()).as("the instance table grew").isEqualTo(INSTANCES + 2);
        assertThat(sync.waitingCount()).isZero();
        assertThat(sync.slotOf(nodes[nodes.length - 1])).isNotNegative();
        renderables.remove(nodes[nodes.length - 1]);
        update(scene, renderables, sync);
        assertThat(sync.instanceCount()).isEqualTo(INSTANCES + 1);
        // A mesh change: drawn again with the new mesh once it loads.
        renderables.setMesh(nodes[2], SMALL);
        update(scene, renderables, sync);
        load();
        update(scene, renderables, sync);
        assertThat(sync.meshCount()).isEqualTo(2);
        assertThat(sync.meshOf(nodes[2])).isNotEqualTo(sync.meshOf(nodes[3]));
        // Falling behind the renderables' log rebuilds from them as they are.
        Renderables few = new Renderables(scene, 2);
        try (SceneSync behind = new SceneSync(resources, few, gpu())) {
            behind.update();
            for (int i = 0; i < 3; i++) {
                few.set(facing(scene, 0, -i, -3), SQUARE, List.of());
            }
            update(scene, few, behind);
            assertThat(behind.instanceCount()).isEqualTo(3);
        }
        assertThat(sync.slotOf(scene.create())).isEqualTo(-1);
    }

    @Test
    void cachedAssetsDrawAtOnceAndUnusableOnesStayOff() throws InterruptedException {
        AssetRef<TextureData> missing = AssetRef.generated(RenderAssets.TEXTURE, (Generator<Plain, TextureData>)
            (plain, seed) -> {
                throw new java.io.IOException("no such texture");
            }, new Plain(0), 1);
        AssetRef<TextureData> rough = AssetRef.generated(RenderAssets.TEXTURE, (Generator<Plain, TextureData>)
            (plain, seed) -> {
                MemorySegment texels = Arena.ofAuto().allocate(4);
                texels.set(ValueLayout.JAVA_INT, 0, plain.rgba());
                return new TextureData(1, 1, TextureData.ColorSpace.LINEAR, texels);
            }, new Plain(0xff00ff00), 1);
        AssetRef<MaterialData> stone = AssetRef.generated(RenderAssets.MATERIAL, (Generator<Glow, MaterialData>)
            (glow, seed) -> MaterialData.builder(MR).texture("baseColorTexture", missing)
                .texture("ormTexture", rough).set("occlusion", 1f).build(),
            new Glow(0.5f, 0.5f, 0.5f), 3);
        AssetRef<MaterialData> moss = AssetRef.generated(RenderAssets.MATERIAL, (Generator<Glow, MaterialData>)
            (glow, seed) -> MaterialData.builder(MR).texture("baseColorTexture", missing).build(), new Glow(0, 0.5f, 0), 3);
        SceneGraph scene = new SceneGraph(new ChangeClock());
        Renderables renderables = new Renderables(scene);
        List<AssetRef<?>> failed = new ArrayList<>();
        resources.diagnostics(new SceneResources.Diagnostics() {
            @Override
            public void assetFailed(AssetRef<?> asset, Throwable cause) {
                failed.add(asset);
            }
        });
        SceneSync sync = closing(new SceneSync(resources, renderables, gpu()));
        long rock = facing(scene, 0, 0, -3);
        long bush = facing(scene, 1, 0, -3);
        renderables.set(rock, SQUARE, List.of(stone));
        renderables.set(bush, SMALL, List.of(moss));
        update(scene, renderables, sync);
        load(); // the meshes: drawn at the next update, their materials asked for then
        update(scene, renderables, sync);
        load(); // the materials, then their textures
        update(scene, renderables, sync);
        assertThat(sync.instanceCount()).isEqualTo(2);
        assertThat(assets.state(missing)).isEqualTo(AssetState.FAILED);
        assertThat(failed).as("reported").containsExactly(missing);
        assertThat(resources.textureCount()).isEqualTo(2);
        assertThat(textures.count()).as("the one that loaded").isEqualTo(1);
        // Gone, and back: what the cache still has draws at once.
        renderables.remove(rock);
        renderables.remove(bush);
        update(scene, renderables, sync);
        assertThat(resources.materialCount()).isZero();
        assertThat(resources.textureCount()).isZero();
        assertThat(textures.count()).isZero();
        assertThat(assets.state(stone)).isEqualTo(AssetState.LOADED);
        renderables.set(rock, SQUARE, List.of(stone));
        update(scene, renderables, sync);
        assertThat(sync.instanceCount()).isEqualTo(1);
        assertThat(textures.count()).isEqualTo(1);
        // A material arriving for a renderable already gone is never written.
        renderables.setMaterials(rock, List.of(GREEN));
        update(scene, renderables, sync);
        load();
        renderables.remove(rock);
        update(scene, renderables, sync);
        assertThat(resources.materialSetCount()).isZero();
        // Assets used elsewhere than in syncs are not uploaded, nor freed when they leave the cache.
        AssetRef<MeshData> elsewhere = AssetRef.generated(RenderAssets.MESH, SQUARES, new Square(3), 1);
        assets.acquire(elsewhere);
        assets.acquire(RED);
        load();
        assets.release(elsewhere);
        assets.release(RED);
        assets.setBudget(0);
        assertThat(assets.state(elsewhere)).isEqualTo(AssetState.ABSENT);
        assertThat(pool.meshCount()).isZero();
        assertThat(resources.meshCount()).isZero();
    }

    @Test
    void thePoolGrowsForMoreMeshes() throws InterruptedException {
        SceneGraph scene = new SceneGraph(new ChangeClock());
        Renderables renderables = new Renderables(scene);
        SceneSync sync = closing(new SceneSync(resources, renderables, gpu()));
        int meshes = pool.capacity().meshes() + 1;
        long[] nodes = new long[meshes];
        for (int i = 0; i < meshes; i++) {
            nodes[i] = facing(scene, i, 0, -3);
            renderables.set(nodes[i], AssetRef.generated(RenderAssets.MESH, SQUARES, new Square(i + 1), 1), List.of());
        }
        update(scene, renderables, sync);
        load();
        update(scene, renderables, sync);
        assertThat(resources.uploadedMeshCount()).isEqualTo(meshes);
        assertThat(pool.meshCount()).isEqualTo(meshes);
        assertThat(pool.capacity().meshes()).isGreaterThanOrEqualTo(meshes);
        sync.close();
        assertThat(resources.meshCount()).isZero();
    }

    @Test
    void onlyWhatViewsFollowIsRewritten() throws InterruptedException {
        // A scene keeping few changes, and nodes past the first of the sync's arrays.
        SceneGraph scene = new SceneGraph(new ChangeClock(), 16, 4);
        Renderables renderables = new Renderables(scene);
        SceneSync sync = closing(new SceneSync(resources, renderables, gpu()));
        for (int i = 0; i < 70; i++) {
            scene.create();
        }
        long a = facing(scene, -1, 0, -3);
        long b = facing(scene, 0, 0, -3);
        long c = facing(scene, 1, 0, -3);
        long bare = scene.create();
        for (long node : new long[] {a, b, c}) {
            renderables.set(node, SQUARE, List.of());
        }
        update(scene, renderables, sync);
        load();
        update(scene, renderables, sync);
        assertThat(sync.instanceCount()).isEqualTo(3);
        int slot = sync.slotOf(b);
        // Shadows are not followed yet: nothing changes. Nor do nodes without renderables.
        renderables.setCastsShadows(b, false);
        scene.setTranslation(bare, 5, 5, 5);
        update(scene, renderables, sync);
        assertThat(sync.slotOf(b)).isEqualTo(slot);
        // More moves than the scene keeps: every instance is written again.
        for (int i = 0; i < 8; i++) {
            scene.setTranslation(a, -1, i, -3);
            scene.update();
        }
        update(scene, renderables, sync);
        // The middle one of a mesh's renderables goes.
        renderables.remove(b);
        update(scene, renderables, sync);
        assertThat(sync.instanceCount()).isEqualTo(2);
        assertThat(sync.slotOf(b)).isEqualTo(-1);
        renderables.remove(a);
        renderables.remove(c);
        update(scene, renderables, sync);
        assertThat(resources.meshCount()).isZero();
    }

    @Test
    void framesAllocateNothingOnceWarm() throws InterruptedException {
        // The whole frame, as an application runs it: the frame boundary, the scene, the sync and both graphs'
        // frames, one forward, one through a visibility buffer, blitted into their targets.
        SceneGraph scene = new SceneGraph(new ChangeClock());
        Renderables renderables = new Renderables(scene);
        SceneSync sync = closing(new SceneSync(resources, renderables, gpu()));
        long[] nodes = new long[3];
        for (int i = 0; i < nodes.length; i++) {
            nodes[i] = facing(scene, i - 1, 0, -3);
            renderables.set(nodes[i], SQUARE, List.of(RED));
        }
        List<FrameGraph> graphs = List.of(graph(sync, false, new GpuBuffer[] {readback(), readback()}),
            graph(sync, device.primitiveIds(), new GpuBuffer[] {readback(), readback()}));
        List<SceneSync> syncs = List.of(sync);
        frame(graphs, syncs, camera);
        load();
        int[] frame = {0};
        com.truesim.testing.AllocationAssertions.assertAllocationFree(() -> {
            scene.setTranslation(nodes[0], -1, 0.01 * (frame[0]++ % 8), -3);
            camera.position(0, 0, 0.001 * (frame[0] % 4));
            frame(graphs, syncs, camera);
        }, 3000, 300, 5);
    }

    @Test
    void renderablesComingGoingAndChangingAllocateNothingOnceTheirAssetsAreIn() throws InterruptedException {
        SceneGraph scene = new SceneGraph(new ChangeClock());
        Renderables renderables = new Renderables(scene);
        SceneSync sync = closing(new SceneSync(resources, renderables, gpu()));
        List<AssetRef<MaterialData>> red = List.of(RED);
        List<AssetRef<MaterialData>> green = List.of(GREEN);
        long kept = facing(scene, 0, 0, -3);
        renderables.set(kept, SQUARE, red);
        renderables.set(facing(scene, 1, 0, -3), SQUARE, green); // both blocks stay in use
        renderables.set(facing(scene, 2, 0, -3), SQUARE, red);
        long[] coming = {facing(scene, -1, 0, -3)};
        renderables.set(coming[0], SQUARE, red);
        update(scene, renderables, sync);
        load();
        update(scene, renderables, sync);
        int[] frame = {0};
        com.truesim.testing.AllocationAssertions.assertAllocationFree(() -> {
            boolean even = frame[0]++ % 2 == 0;
            renderables.setMaterials(kept, even ? green : red);
            scene.remove(coming[0]);
            coming[0] = scene.create();
            renderables.set(coming[0], SQUARE, even ? red : green);
            update(scene, renderables, sync);
        }, 20_000, 2_000, 5);
        assertThat(sync.instanceCount()).isEqualTo(4);
        assertThat(resources.materialSetCount()).isEqualTo(2);
    }

    @Test
    void materialsSharedWithinABlockAreLetGoOnce() throws InterruptedException {
        // Meshes of three submeshes: one material for all (the last one fills the rest), and a list naming a material
        // twice; the materials share a texture. Removing them lets go of each material and texture once.
        AssetRef<TextureData> white = AssetRef.generated(RenderAssets.TEXTURE, PLAINS, new Plain(0xffffffff), 1);
        Generator<Glow, MaterialData> textured = (glow, seed) -> MaterialData.builder(MR)
            .set("baseColor", glow.r(), glow.g(), glow.b(), 1f).texture("baseColorTexture", white).build();
        AssetRef<MaterialData> a = AssetRef.generated(RenderAssets.MATERIAL, textured, new Glow(1, 0, 0), 5);
        AssetRef<MaterialData> b = AssetRef.generated(RenderAssets.MATERIAL, textured, new Glow(0, 1, 0), 5);
        AssetRef<MeshData> strips = AssetRef.generated(RenderAssets.MESH, STRIPS, 3, 1);
        SceneGraph scene = new SceneGraph(new ChangeClock());
        Renderables renderables = new Renderables(scene);
        SceneSync sync = closing(new SceneSync(resources, renderables, gpu()));
        long one = facing(scene, -2, 0, -6);
        long twice = facing(scene, 1, 0, -6);
        renderables.set(one, strips, List.of(a));
        renderables.set(twice, strips, List.of(a, b, a));
        update(scene, renderables, sync);
        load();
        update(scene, renderables, sync);
        load(); // the texture, asked for once the materials loaded
        update(scene, renderables, sync);
        assertThat(sync.instanceCount()).isEqualTo(2);
        assertThat(resources.materialSetCount()).isEqualTo(2);
        assertThat(materials.count()).as("the default and two blocks of three").isEqualTo(7);
        assertThat(textures.count()).isEqualTo(1);
        assertThat(assets.holders(a)).isEqualTo(1);
        assertThat(assets.holders(white)).isEqualTo(1);
        renderables.remove(one);
        renderables.remove(twice);
        update(scene, renderables, sync);
        assertThat(resources.materialSetCount()).isZero();
        assertThat(resources.materialCount()).isZero();
        assertThat(resources.textureCount()).isZero();
        assertThat(materials.count()).isEqualTo(1);
        assertThat(textures.count()).isZero();
        assertThat(assets.holders(a)).isZero();
        assertThat(assets.holders(b)).isZero();
        assertThat(assets.holders(white)).isZero();
    }

    @Test
    void renderablesGoneBeforeTheyCouldDrawAreNotDrawn() throws InterruptedException {
        SceneGraph scene = new SceneGraph(new ChangeClock());
        Renderables renderables = new Renderables(scene);
        SceneSync sync = closing(new SceneSync(resources, renderables, gpu()));
        // Removed while its mesh loads, the mesh arriving at the frame boundary before the sync hears of it.
        long early = facing(scene, 0, 0, -3);
        long stays = facing(scene, 1, 0, -3);
        renderables.set(early, SQUARE, List.of());
        renderables.set(stays, SQUARE, List.of());
        update(scene, renderables, sync);
        renderables.remove(early);
        load(); // arrives: the sync is told before it follows the removal
        update(scene, renderables, sync);
        assertThat(sync.slotOf(early)).isEqualTo(-1);
        assertThat(sync.slotOf(stays)).isZero();
        assertThat(sync.instanceCount()).isEqualTo(1);
    }

    @Test
    void fallingBehindTheRenderablesKeepsWhatIsOnTheGpu() throws InterruptedException {
        SceneGraph scene = new SceneGraph(new ChangeClock());
        Renderables renderables = new Renderables(scene, 4); // keeps four changes
        SceneSync sync = closing(new SceneSync(resources, renderables, gpu()));
        long kept = facing(scene, 0, 0, -3);
        long gone = facing(scene, 1, 0, -3);
        long changed = facing(scene, 2, 0, -3);
        renderables.set(kept, SQUARE, List.of(RED));
        renderables.set(gone, SQUARE, List.of(RED));
        renderables.set(changed, SQUARE, List.of(RED));
        update(scene, renderables, sync);
        load();
        update(scene, renderables, sync);
        int mesh = sync.meshOf(kept);
        int block = sync.firstMaterialOf(kept);
        // More changes than the log keeps: the sync reconciles every renderable, and keeps what did not change.
        renderables.remove(gone);
        renderables.setMaterials(changed, List.of(GREEN));
        for (int i = 0; i < 4; i++) {
            renderables.setCastsShadows(kept, i % 2 == 0);
        }
        update(scene, renderables, sync);
        assertThat(sync.meshOf(kept)).isEqualTo(mesh);
        assertThat(sync.firstMaterialOf(kept)).isEqualTo(block);
        assertThat(pool.contains(mesh)).isTrue();
        assertThat(sync.slotOf(gone)).isEqualTo(-1);
        assertThat(sync.renderableCount()).isEqualTo(2);
        assertThat(assets.holders(SQUARE)).isEqualTo(1);
        assertThat(resources.materialSetCount()).isEqualTo(2);
    }

    @Test
    void theMaterialTableGrowsForMoreMaterials() throws InterruptedException {
        // The table starts with 16 ids, the default one taken: 16 renderables of materials of their own grow it.
        SceneGraph scene = new SceneGraph(new ChangeClock());
        Renderables renderables = new Renderables(scene);
        SceneSync sync = closing(new SceneSync(resources, renderables, closing(new GpuScene(device, pool, 32, slots))));
        long[] nodes = new long[16];
        for (int i = 0; i < nodes.length; i++) {
            nodes[i] = facing(scene, i, 0, -3);
            renderables.set(nodes[i], SQUARE,
                List.of(AssetRef.generated(RenderAssets.MATERIAL, GLOWS, new Glow(i, 0, 0), 9)));
        }
        update(scene, renderables, sync);
        load();
        update(scene, renderables, sync);
        assertThat(sync.instanceCount()).isEqualTo(16);
        assertThat(sync.waitingCount()).isZero();
        assertThat(materials.count()).isEqualTo(17);
        // A drawn one changes its material: drawn on with the new one, keeping its mesh and id.
        long changing = nodes[3];
        int mesh = sync.meshOf(changing);
        int slot = sync.slotOf(changing);
        renderables.setMaterials(changing,
            List.of(AssetRef.generated(RenderAssets.MATERIAL, GLOWS, new Glow(99, 0, 0), 9)));
        update(scene, renderables, sync);
        assertThat(sync.slotOf(changing)).isEqualTo(slot);
        assertThat(sync.meshOf(changing)).isEqualTo(mesh);
    }

    @Test
    void idsStayWhileDrawnAndLayersAndShadowsFollowTheScene() throws InterruptedException {
        SceneGraph scene = new SceneGraph(new ChangeClock());
        Renderables renderables = new Renderables(scene);
        GpuScene gpu = gpu();
        SceneSync sync = closing(new SceneSync(resources, renderables, gpu));
        GpuBuffer[] readbacks = {readback(), readback()};
        List<FrameGraph> graphs = List.of(graph(sync, false, readbacks));
        RenderView cameraView = cameraViews.getLast();
        long[] nodes = new long[3];
        for (int i = 0; i < 3; i++) {
            nodes[i] = facing(scene, 2 * i - 2, 0, -8);
            renderables.set(nodes[i], SQUARE, List.of());
        }
        frame(graphs, List.of(sync), camera);
        load();
        frame(graphs, List.of(sync), camera);
        int[] ids = {sync.slotOf(nodes[0]), sync.slotOf(nodes[1]), sync.slotOf(nodes[2])};
        assertThat(ids).containsExactlyInAnyOrder(0, 1, 2);
        // The middle one goes: the others keep their ids, the renderer keeps the hole.
        renderables.remove(nodes[1]);
        frame(graphs, List.of(sync), camera);
        assertThat(sync.slotOf(nodes[0])).isEqualTo(ids[0]);
        assertThat(sync.slotOf(nodes[2])).isEqualTo(ids[2]);
        assertThat(sync.instanceCount()).isEqualTo(2);
        assertThat(gpu.highWater()).isEqualTo(Math.max(ids[0], ids[2]) + 1);
        assertThat(gpu.count()).isEqualTo(2);
        // A new one takes the lowest free id.
        long next = facing(scene, 0, 0, -8);
        renderables.set(next, SQUARE, List.of());
        frame(graphs, List.of(sync), camera);
        assertThat(sync.slotOf(next)).isEqualTo(ids[1]);
        // Layers and shadows, from the scene: one node on layer 5, one casting no shadow.
        scene.setLayers(nodes[0], 1 << 5);
        renderables.setCastsShadows(nodes[2], false);
        cameraView.filter(1, false);
        frame(graphs, List.of(sync), camera);
        device.waitIdle();
        assertThat(cameraView.visibleMeshlets()).as("on layer 0").isEqualTo(2);
        cameraView.filter(-1, true);
        frame(graphs, List.of(sync), camera);
        device.waitIdle();
        assertThat(cameraView.visibleMeshlets()).as("casting shadows").isEqualTo(2);
        // The highest id goes: the ids culled shrink to the highest in use.
        renderables.remove(nodes[2]);
        renderables.remove(nodes[0]);
        frame(graphs, List.of(sync), camera);
        assertThat(gpu.highWater()).isEqualTo(sync.slotOf(next) + 1);
    }

    @Test
    void nodesRemovedAfterTheRenderablesUpdateStopDrawing() throws InterruptedException {
        // A node moved, then removed between renderables.update() and sync.update(): the renderables have not seen the
        // removal yet, the scene's log has. Its instance goes at once, whether the sync follows the changes (one node
        // of four moved) or rewrites every instance (all three left moved).
        SceneGraph scene = new SceneGraph(new ChangeClock());
        Renderables renderables = new Renderables(scene);
        GpuScene gpu = gpu();
        SceneSync sync = closing(new SceneSync(resources, renderables, gpu));
        List<FrameGraph> graphs = List.of(graph(sync, false, new GpuBuffer[] {readback(), readback()}));
        long[] nodes = new long[4];
        for (int i = 0; i < nodes.length; i++) {
            nodes[i] = facing(scene, 2 * i - 3, 0, -8);
            renderables.set(nodes[i], SQUARE, List.of());
        }
        frame(graphs, List.of(sync), camera);
        load();
        frame(graphs, List.of(sync), camera);
        assertThat(sync.instanceCount()).isEqualTo(4);
        for (int moving : new int[] {1, 3}) {
            // First the last node moves and goes; then the three left move and the first goes.
            int from = moving == 1 ? 3 : 0;
            long removed = nodes[from];
            for (int i = from; i < from + moving; i++) {
                scene.setTranslation(nodes[i], 2 * i - 3, 1, -8);
            }
            int slot = graphs.getFirst().beginFrame();
            slots.begin(slot);
            tasks.drainCompleted();
            scene.update();
            renderables.update();
            scene.remove(removed);
            sync.update();
            graphs.getFirst().submit(0, 0);
            slots.end();
            assertThat(sync.slotOf(removed)).as("%d moved", moving).isEqualTo(-1);
            assertThat(sync.instanceCount()).as("%d moved", moving).isEqualTo(moving == 1 ? 3 : 2);
            frame(graphs, List.of(sync), camera); // the renderables see the removal: nothing changes
            assertThat(sync.instanceCount()).isEqualTo(moving == 1 ? 3 : 2);
            assertThat(sync.renderableCount()).isEqualTo(moving == 1 ? 3 : 2);
        }
        // A renderable set after the renderables' update, its node removed the same frame: never drawn.
        int slot = graphs.getFirst().beginFrame();
        slots.begin(slot);
        tasks.drainCompleted();
        scene.update();
        renderables.update();
        long brief = facing(scene, 0, 0, -8);
        renderables.set(brief, SQUARE, List.of());
        scene.remove(brief);
        sync.update();
        graphs.getFirst().submit(0, 0);
        slots.end();
        assertThat(sync.slotOf(brief)).isEqualTo(-1);
        assertThat(sync.renderableCount()).isEqualTo(2);
    }

    @Test
    void misuseIsChecked() {
        SceneGraph scene = new SceneGraph(new ChangeClock());
        try (GeometryPool other = new GeometryPool(device, GeometryPool.Capacity.of(64, 1, 1));
             GpuScene elsewhere = new GpuScene(device, other, INSTANCES)) {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new SceneSync(resources, new Renderables(scene), elsewhere))
                .withMessageContaining("another geometry pool");
        }
        SceneSync sync = closing(new SceneSync(resources, new Renderables(scene), gpu()));
        assertThat(sync.resources()).isSameAs(resources);
        assertThat(sync.gpu().pool()).isSameAs(pool);
        resources.close();
        resources.close();
        assertThat(resources.pool()).isSameAs(pool);
        assertThat(resources.materials()).isSameAs(materials);
        assertThat(resources.textures()).isSameAs(textures);
        assertThat(resources.loadingCount()).isZero();
        Renderables renderables = sync.renderables();
        renderables.set(scene.create(), SQUARE, List.of());
        assertThatIllegalStateException().isThrownBy(sync::update).withMessageContaining("closed");
    }
}
