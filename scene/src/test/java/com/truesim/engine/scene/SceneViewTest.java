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
import com.truesim.common.state.ChangeClock;
import com.truesim.engine.graph.Access;
import com.truesim.engine.graph.ExternalImage;
import com.truesim.engine.graph.FrameGraph;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Image;
import com.truesim.engine.graph.Key;
import com.truesim.engine.graph.Technique;
import com.truesim.engine.render.Camera;
import com.truesim.engine.render.ClusteredForwardShading;
import com.truesim.engine.render.GeometryPool;
import com.truesim.engine.render.Keys;
import com.truesim.engine.render.Materials;
import com.truesim.engine.render.MeshletRenderer;
import com.truesim.engine.render.Textures;
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
 * Scenes drawn through views, validated: assets load into the shared resources once, renderables follow the scene's
 * changes, and several scenes draw with frame graphs of their own, each in its own rendering mode, sharing frame slots.
 */
class SceneViewTest {

    private static final int SIZE = 64;
    private static final float HALF = (float) Math.sqrt(0.5);
    private static final MeshletRenderer.Limits LIMITS = new MeshletRenderer.Limits(4, 256, 64, 1 << 12);

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
    static final Generator<Glow, MaterialData> GLOWS = (glow, seed) -> MaterialData.builder()
        .baseColor(0, 0, 0, 1).emissive(glow.r(), glow.g(), glow.b()).build();
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

    private <T extends AutoCloseable> T closing(T closeable) {
        closing.add(closeable);
        return closeable;
    }

    private MeshletRenderer renderer() {
        return closing(new MeshletRenderer(device, pool, LIMITS, VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_D32_SFLOAT, slots));
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
        public List<Key> provides() {
            return List.of();
        }

        @Override
        public List<Key> modifies() {
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
                    vkCmdCopyImageToBuffer(ctx.commands(), ctx.image(color), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                        readbacks[graph.frameSlots().current()].handle(), region);
                }
            });
            g.provide(Keys.SCENE_COLOR, color);
        }
    }

    /** A graph drawing {@code view} in a rendering mode, reading every frame back. */
    private FrameGraph graph(SceneView view, boolean visibilityBuffer, GpuBuffer[] readbacks) {
        FrameGraph graph = closing(new FrameGraph(device, slots));
        Technique shading = visibilityBuffer
            ? new VisibilityBufferShading(view.geometry(), SIZE, SIZE, VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_D32_SFLOAT)
                .clearColor(0, 0, 0)
            : new ClusteredForwardShading(view.geometry(), SIZE, SIZE, VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_D32_SFLOAT)
                .clearColor(0, 0, 0);
        int usage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT
            | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
        GpuImage target = closing(new GpuImage(device, SIZE, SIZE, VK_FORMAT_R8G8B8A8_UNORM, usage,
            VK_IMAGE_ASPECT_COLOR_BIT));
        ExternalImage output = new ExternalImage("target", SIZE, SIZE, VK_FORMAT_R8G8B8A8_UNORM, usage,
            VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL).set(target.handle(), target.view());
        return graph.add(shading).add(new Readback(graph, readbacks)).output(Keys.SCENE_COLOR, output);
    }

    /**
     * Runs a frame of every graph in step, as an application drawing several scenes does: every graph begins (waiting
     * for the slot's last frame), the shared slot opens, the views update, every graph submits, the slot closes.
     */
    private int frame(List<FrameGraph> graphs, List<SceneView> views, Camera camera) {
        int slot = -1;
        for (int g = 0; g < graphs.size(); g++) {
            slot = graphs.get(g).beginFrame();
        }
        slots.begin(slot);
        tasks.drainCompleted(); // the frame boundary: loads arrive
        for (int v = 0; v < views.size(); v++) {
            SceneView view = views.get(v);
            view.renderables().scene().update();
            view.renderables().update();
            view.update();
            view.geometry().setView(0, camera, 1, MeshletRenderer.MODE_LIT);
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
        SceneView view = closing(new SceneView(resources, renderables, renderer()));
        GpuBuffer[] readbacks = {readback(), readback()};
        FrameGraph graph = graph(view, false, readbacks);
        // Far from the world origin, left of a camera there too: floats relative to the camera keep it precise.
        long node = facing(scene, 1e7 - 1, 0, -3);
        renderables.set(node, SQUARE, List.of(RED));
        camera.position(1e7, 0, 0);
        frame(List.of(graph), List.of(view), camera);
        assertThat(view.renderableCount()).isEqualTo(1);
        assertThat(view.instanceCount()).as("waiting for the mesh").isZero();
        assertThat(view.slotOf(node)).isEqualTo(-1);
        assertThat(view.firstMaterialOf(node)).isEqualTo(-1);
        assertThat(view.meshOf(node)).isEqualTo(-1);
        load();
        for (int frame = 0; frame < 4; frame++) {
            frame(List.of(graph), List.of(view), camera);
        }
        device.waitIdle();
        assertThat(view.instanceCount()).isEqualTo(1);
        assertThat(view.slotOf(node)).isZero();
        assertThat(view.meshOf(node)).isNotNegative();
        assertThat(view.firstMaterialOf(node)).isPositive();
        assertThat(resources.uploadedMeshCount()).isEqualTo(1);
        assertThat(resources.materialSetCount()).isEqualTo(1);
        assertThat(resources.materialCount()).isEqualTo(1);
        assertThat(litColumn(readbacks[0], 0)).as("red, left of the camera").isLessThan(SIZE / 2.0 - 4);
        assertThat(litColumn(readbacks[0], 8)).as("no green").isNaN();
        // Moving the node moves its instance; moving the camera moves every instance.
        scene.setTranslation(node, 1e7 + 1, 0, -3);
        for (int frame = 0; frame < 2; frame++) {
            frame(List.of(graph), List.of(view), camera);
        }
        device.waitIdle();
        assertThat(litColumn(readbacks[0], 0)).as("moved right").isGreaterThan(SIZE / 2.0 + 4);
        camera.position(1e7 + 2, 0, 0);
        for (int frame = 0; frame < 2; frame++) {
            frame(List.of(graph), List.of(view), camera);
        }
        device.waitIdle();
        assertThat(litColumn(readbacks[0], 0)).as("the camera moved past it").isLessThan(SIZE / 2.0 - 4);
        // A material change draws in the new one.
        renderables.setMaterials(node, List.of(GREEN));
        frame(List.of(graph), List.of(view), camera);
        load();
        for (int frame = 0; frame < 2; frame++) {
            frame(List.of(graph), List.of(view), camera);
        }
        device.waitIdle();
        assertThat(litColumn(readbacks[0], 8)).as("green now").isLessThan(SIZE / 2.0 - 4);
        assertThat(resources.materialCount()).as("red let go").isEqualTo(1);
        assertThat(assets.holders(RED)).isZero();
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
        SceneView firstView = closing(new SceneView(resources, firstRenderables, renderer()));
        SceneView secondView = closing(new SceneView(resources, secondRenderables, renderer()));
        GpuBuffer[] firstReadbacks = {readback(), readback()};
        GpuBuffer[] secondReadbacks = {readback(), readback()};
        List<FrameGraph> graphs = List.of(graph(firstView, false, firstReadbacks),
            graph(secondView, visibility, secondReadbacks));
        List<SceneView> views = List.of(firstView, secondView);
        long left = facing(first, -1, 0, -3);
        long right = facing(second, 1, 0, -3);
        firstRenderables.set(left, SQUARE, List.of(RED));
        secondRenderables.set(right, SQUARE, List.of(RED));
        frame(graphs, views, camera);
        load();
        for (int frame = 0; frame < 4; frame++) {
            assertThat(frame(graphs, views, camera)).isEqualTo((frame + 1) % 2);
        }
        device.waitIdle();
        assertThat(resources.meshCount()).isEqualTo(1);
        assertThat(pool.meshCount()).isEqualTo(1);
        assertThat(resources.materialSetCount()).isEqualTo(1);
        assertThat(firstView.firstMaterialOf(left)).isEqualTo(secondView.firstMaterialOf(right));
        assertThat(assets.holders(SQUARE)).as("held once for both").isEqualTo(1);
        for (int slot = 0; slot < 2; slot++) {
            assertThat(litColumn(firstReadbacks[slot], 0)).as("the first scene").isLessThan(SIZE / 2.0 - 4);
            assertThat(litColumn(secondReadbacks[slot], 0)).as("the second scene").isGreaterThan(SIZE / 2.0 + 4);
        }
        // The first view goes: the second still draws the shared mesh.
        int mesh = secondView.meshOf(right);
        firstView.close();
        assertThat(pool.contains(mesh)).isTrue();
        assertThat(firstView.instanceCount()).isZero();
        assertThatIllegalStateException().isThrownBy(firstView::update);
        firstView.close(); // once is enough
        // The second scene's node goes too: the mesh leaves the GPU, its room reused once no frame draws it.
        second.remove(right);
        frame(List.of(graphs.get(1)), List.of(secondView), camera);
        assertThat(secondView.instanceCount()).isZero();
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
            (glow, seed) -> MaterialData.builder().baseColorTexture(white).build(), new Glow(1, 1, 1), 2);
        SceneGraph scene = new SceneGraph(new ChangeClock());
        Renderables renderables = new Renderables(scene);
        SceneView view = closing(new SceneView(resources, renderables, renderer()));
        long a = facing(scene, -1, 0, -3);
        long b = facing(scene, 1, 0, -3);
        long bare = facing(scene, 0, 1, -3);
        renderables.set(a, SQUARE, List.of(textured));
        renderables.set(b, SQUARE, List.of(textured));
        renderables.set(bare, SQUARE, List.of()); // the default material
        update(scene, renderables, view);
        load();
        update(scene, renderables, view);
        load(); // the texture, asked for once the material loaded
        update(scene, renderables, view);
        assertThat(view.instanceCount()).isEqualTo(3);
        assertThat(view.firstMaterialOf(a)).isEqualTo(view.firstMaterialOf(b));
        assertThat(view.firstMaterialOf(bare)).isNotEqualTo(view.firstMaterialOf(a));
        assertThat(resources.textureCount()).isEqualTo(1);
        assertThat(textures.count()).isEqualTo(1);
        assertThat(assets.state(white)).isEqualTo(AssetState.LOADED);
        assertThat(view.meshCount()).isEqualTo(1);
        // Gone: the texture leaves too.
        renderables.remove(a);
        renderables.remove(b);
        update(scene, renderables, view);
        assertThat(resources.textureCount()).isZero();
        assertThat(assets.holders(white)).isZero();
        assertThat(view.renderableCount()).isEqualTo(1);
    }

    private void update(SceneGraph scene, Renderables renderables, SceneView view) {
        tasks.drainCompleted();
        scene.update();
        renderables.update();
        view.update();
    }

    @Test
    void renderablesBeyondTheRendererWaitForSlots() throws InterruptedException {
        SceneGraph scene = new SceneGraph(new ChangeClock());
        Renderables renderables = new Renderables(scene);
        SceneView view = closing(new SceneView(resources, renderables, renderer()));
        long[] nodes = new long[LIMITS.instances() + 2];
        for (int i = 0; i < nodes.length; i++) {
            nodes[i] = facing(scene, i, 0, -3);
            renderables.set(nodes[i], SQUARE, List.of());
        }
        update(scene, renderables, view);
        load();
        update(scene, renderables, view);
        assertThat(view.instanceCount()).isEqualTo(LIMITS.instances());
        assertThat(view.waitingCount()).isEqualTo(2);
        long drawn = nodes[nodes.length - 1];
        long waiting = nodes[0];
        assertThat(view.slotOf(drawn)).isNotNegative();
        assertThat(view.slotOf(waiting)).isEqualTo(-1);
        renderables.remove(drawn); // its slot goes to a waiting renderable
        update(scene, renderables, view);
        assertThat(view.instanceCount()).isEqualTo(LIMITS.instances());
        assertThat(view.waitingCount()).isEqualTo(1);
        renderables.remove(waiting); // waiting renderables may go too
        update(scene, renderables, view);
        assertThat(view.waitingCount()).isZero();
        assertThat(view.instanceCount()).isEqualTo(LIMITS.instances());
        // A mesh change: drawn again with the new mesh once it loads.
        renderables.setMesh(nodes[2], SMALL);
        update(scene, renderables, view);
        load();
        update(scene, renderables, view);
        assertThat(view.meshCount()).isEqualTo(2);
        assertThat(view.meshOf(nodes[2])).isNotEqualTo(view.meshOf(nodes[3]));
        // Falling behind the renderables' log rebuilds from them as they are.
        Renderables few = new Renderables(scene, 2);
        try (SceneView behind = new SceneView(resources, few, renderer())) {
            behind.update();
            for (int i = 0; i < 3; i++) {
                few.set(facing(scene, 0, -i, -3), SQUARE, List.of());
            }
            update(scene, few, behind);
            assertThat(behind.instanceCount()).isEqualTo(3);
        }
        assertThat(view.slotOf(scene.create())).isEqualTo(-1);
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
            (glow, seed) -> MaterialData.builder().baseColorTexture(missing).ormTexture(rough, 1).build(),
            new Glow(0.5f, 0.5f, 0.5f), 3);
        AssetRef<MaterialData> moss = AssetRef.generated(RenderAssets.MATERIAL, (Generator<Glow, MaterialData>)
            (glow, seed) -> MaterialData.builder().baseColorTexture(missing).build(), new Glow(0, 0.5f, 0), 3);
        SceneGraph scene = new SceneGraph(new ChangeClock());
        Renderables renderables = new Renderables(scene);
        SceneView view = closing(new SceneView(resources, renderables, renderer()));
        long rock = facing(scene, 0, 0, -3);
        long bush = facing(scene, 1, 0, -3);
        renderables.set(rock, SQUARE, List.of(stone));
        renderables.set(bush, SMALL, List.of(moss));
        update(scene, renderables, view);
        load(); // the meshes: drawn at the next update, their materials asked for then
        update(scene, renderables, view);
        load(); // the materials, then their textures
        update(scene, renderables, view);
        assertThat(view.instanceCount()).isEqualTo(2);
        assertThat(assets.state(missing)).isEqualTo(AssetState.FAILED);
        assertThat(resources.textureCount()).isEqualTo(2);
        assertThat(textures.count()).as("the one that loaded").isEqualTo(1);
        // Gone, and back: what the cache still has draws at once.
        renderables.remove(rock);
        renderables.remove(bush);
        update(scene, renderables, view);
        assertThat(resources.materialCount()).isZero();
        assertThat(resources.textureCount()).isZero();
        assertThat(textures.count()).isZero();
        assertThat(assets.state(stone)).isEqualTo(AssetState.LOADED);
        renderables.set(rock, SQUARE, List.of(stone));
        update(scene, renderables, view);
        assertThat(view.instanceCount()).isEqualTo(1);
        assertThat(textures.count()).isEqualTo(1);
        // A material arriving for a renderable already gone is never written.
        renderables.setMaterials(rock, List.of(GREEN));
        update(scene, renderables, view);
        load();
        renderables.remove(rock);
        update(scene, renderables, view);
        assertThat(resources.materialSetCount()).isZero();
        // Assets used elsewhere than in views are not uploaded, nor freed when they leave the cache.
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
    void meshesWaitForRoomInThePool() throws InterruptedException {
        SceneGraph scene = new SceneGraph(new ChangeClock());
        Renderables renderables = new Renderables(scene);
        SceneView view = closing(new SceneView(resources, renderables, renderer()));
        int meshes = pool.capacity().meshes() + 1;
        long[] nodes = new long[meshes];
        for (int i = 0; i < meshes; i++) {
            nodes[i] = facing(scene, i, 0, -3);
            renderables.set(nodes[i], AssetRef.generated(RenderAssets.MESH, SQUARES, new Square(i + 1), 1), List.of());
        }
        update(scene, renderables, view);
        load();
        update(scene, renderables, view);
        assertThat(resources.waitingForRoomCount()).isEqualTo(1);
        assertThat(resources.meshCount()).isEqualTo(meshes);
        assertThat(resources.uploadedMeshCount()).isEqualTo(meshes - 1);
        assertThat(view.instanceCount()).isEqualTo(meshes - 1);
        assertThat(view.renderableCount()).isEqualTo(meshes);
        // A drawn one goes: its room frees once no frame in flight draws it, and the waiting mesh goes in.
        long drawn = NodeHandle.NONE;
        long waiting = NodeHandle.NONE;
        for (long node : nodes) {
            if (view.meshOf(node) < 0) {
                waiting = node;
            } else {
                drawn = node;
            }
        }
        renderables.remove(drawn);
        update(scene, renderables, view);
        assertThat(view.meshOf(waiting)).as("the room is still drawn by frames in flight").isEqualTo(-1);
        for (int frame = 0; frame < slots.count(); frame++) {
            slots.begin(frame);
            slots.end();
        }
        update(scene, renderables, view);
        assertThat(resources.waitingForRoomCount()).isZero();
        assertThat(view.meshOf(waiting)).isNotNegative();
        assertThat(view.instanceCount()).isEqualTo(meshes - 1);
        // A waiting mesh nobody uses any more stops waiting.
        renderables.set(scene.create(), AssetRef.generated(RenderAssets.MESH, SQUARES, new Square(99), 1), List.of());
        update(scene, renderables, view);
        load();
        update(scene, renderables, view);
        assertThat(resources.waitingForRoomCount()).isEqualTo(1);
        view.close();
        assertThat(resources.waitingForRoomCount()).isZero();
        assertThat(resources.meshCount()).isZero();
    }

    @Test
    void onlyWhatViewsFollowIsRewritten() throws InterruptedException {
        // A scene keeping few changes, and nodes past the first of the view's arrays.
        SceneGraph scene = new SceneGraph(new ChangeClock(), 16, 4);
        Renderables renderables = new Renderables(scene);
        SceneView view = closing(new SceneView(resources, renderables, renderer()));
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
        update(scene, renderables, view);
        load();
        update(scene, renderables, view);
        assertThat(view.instanceCount()).isEqualTo(3);
        int slot = view.slotOf(b);
        // Shadows are not followed yet: nothing changes. Nor do nodes without renderables.
        renderables.setCastsShadows(b, false);
        scene.setTranslation(bare, 5, 5, 5);
        update(scene, renderables, view);
        assertThat(view.slotOf(b)).isEqualTo(slot);
        // More moves than the scene keeps: every instance is written again.
        for (int i = 0; i < 8; i++) {
            scene.setTranslation(a, -1, i, -3);
            scene.update();
        }
        update(scene, renderables, view);
        // The middle one of a mesh's renderables goes.
        renderables.remove(b);
        update(scene, renderables, view);
        assertThat(view.instanceCount()).isEqualTo(2);
        assertThat(view.slotOf(b)).isEqualTo(-1);
        renderables.remove(a);
        renderables.remove(c);
        update(scene, renderables, view);
        assertThat(resources.meshCount()).isZero();
    }

    @Test
    void framesAllocateNothingOnceWarm() throws InterruptedException {
        // The whole frame, as an application runs it: the frame boundary, the scene, the view and both graphs'
        // frames, one forward, one through a visibility buffer, blitted into their targets.
        SceneGraph scene = new SceneGraph(new ChangeClock());
        Renderables renderables = new Renderables(scene);
        SceneView view = closing(new SceneView(resources, renderables, renderer()));
        long[] nodes = new long[3];
        for (int i = 0; i < nodes.length; i++) {
            nodes[i] = facing(scene, i - 1, 0, -3);
            renderables.set(nodes[i], SQUARE, List.of(RED));
        }
        List<FrameGraph> graphs = List.of(graph(view, false, new GpuBuffer[] {readback(), readback()}),
            graph(view, device.primitiveIds(), new GpuBuffer[] {readback(), readback()}));
        List<SceneView> views = List.of(view);
        frame(graphs, views, camera);
        load();
        int[] frame = {0};
        com.truesim.testing.AllocationAssertions.assertAllocationFree(() -> {
            scene.setTranslation(nodes[0], -1, 0.01 * (frame[0]++ % 8), -3);
            camera.position(0, 0, 0.001 * (frame[0] % 4));
            frame(graphs, views, camera);
        }, 3000, 300, 5);
    }

    @Test
    void renderablesComingGoingAndChangingAllocateNothingOnceTheirAssetsAreIn() throws InterruptedException {
        SceneGraph scene = new SceneGraph(new ChangeClock());
        Renderables renderables = new Renderables(scene);
        SceneView view = closing(new SceneView(resources, renderables, renderer()));
        List<AssetRef<MaterialData>> red = List.of(RED);
        List<AssetRef<MaterialData>> green = List.of(GREEN);
        long kept = facing(scene, 0, 0, -3);
        renderables.set(kept, SQUARE, red);
        renderables.set(facing(scene, 1, 0, -3), SQUARE, green); // both blocks stay in use
        renderables.set(facing(scene, 2, 0, -3), SQUARE, red);
        long[] coming = {facing(scene, -1, 0, -3)};
        renderables.set(coming[0], SQUARE, red);
        update(scene, renderables, view);
        load();
        update(scene, renderables, view);
        int[] frame = {0};
        com.truesim.testing.AllocationAssertions.assertAllocationFree(() -> {
            boolean even = frame[0]++ % 2 == 0;
            renderables.setMaterials(kept, even ? green : red);
            scene.remove(coming[0]);
            coming[0] = scene.create();
            renderables.set(coming[0], SQUARE, even ? red : green);
            update(scene, renderables, view);
        }, 20_000, 2_000, 5);
        assertThat(view.instanceCount()).isEqualTo(4);
        assertThat(resources.materialSetCount()).isEqualTo(2);
    }

    @Test
    void materialsSharedWithinABlockAreLetGoOnce() throws InterruptedException {
        // Meshes of three submeshes: one material for all (the last one fills the rest), and a list naming a material
        // twice; the materials share a texture. Removing them lets go of each material and texture once.
        AssetRef<TextureData> white = AssetRef.generated(RenderAssets.TEXTURE, PLAINS, new Plain(0xffffffff), 1);
        Generator<Glow, MaterialData> textured = (glow, seed) -> MaterialData.builder()
            .baseColor(glow.r(), glow.g(), glow.b(), 1).baseColorTexture(white).build();
        AssetRef<MaterialData> a = AssetRef.generated(RenderAssets.MATERIAL, textured, new Glow(1, 0, 0), 5);
        AssetRef<MaterialData> b = AssetRef.generated(RenderAssets.MATERIAL, textured, new Glow(0, 1, 0), 5);
        AssetRef<MeshData> strips = AssetRef.generated(RenderAssets.MESH, STRIPS, 3, 1);
        SceneGraph scene = new SceneGraph(new ChangeClock());
        Renderables renderables = new Renderables(scene);
        SceneView view = closing(new SceneView(resources, renderables, renderer()));
        long one = facing(scene, -2, 0, -6);
        long twice = facing(scene, 1, 0, -6);
        renderables.set(one, strips, List.of(a));
        renderables.set(twice, strips, List.of(a, b, a));
        update(scene, renderables, view);
        load();
        update(scene, renderables, view);
        load(); // the texture, asked for once the materials loaded
        update(scene, renderables, view);
        assertThat(view.instanceCount()).isEqualTo(2);
        assertThat(resources.materialSetCount()).isEqualTo(2);
        assertThat(materials.count()).as("the default and two blocks of three").isEqualTo(7);
        assertThat(textures.count()).isEqualTo(1);
        assertThat(assets.holders(a)).isEqualTo(1);
        assertThat(assets.holders(white)).isEqualTo(1);
        renderables.remove(one);
        renderables.remove(twice);
        update(scene, renderables, view);
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
        SceneView view = closing(new SceneView(resources, renderables, renderer()));
        // Removed while its mesh loads, the mesh arriving at the frame boundary before the view hears of it.
        long early = facing(scene, 0, 0, -3);
        long stays = facing(scene, 1, 0, -3);
        renderables.set(early, SQUARE, List.of());
        renderables.set(stays, SQUARE, List.of());
        update(scene, renderables, view);
        renderables.remove(early);
        load(); // arrives: the view is told before it follows the removal
        update(scene, renderables, view);
        assertThat(view.slotOf(early)).isEqualTo(-1);
        assertThat(view.slotOf(stays)).isZero();
        assertThat(view.instanceCount()).isEqualTo(1);
        // The renderer full, one waiting; a drawn one and the waiting one go in the same frame.
        long[] nodes = new long[LIMITS.instances() + 1];
        nodes[0] = stays;
        for (int i = 1; i < nodes.length; i++) {
            nodes[i] = facing(scene, i, 0, -3);
            renderables.set(nodes[i], SQUARE, List.of());
        }
        update(scene, renderables, view);
        assertThat(view.waitingCount()).isEqualTo(1);
        long waiting = NodeHandle.NONE;
        for (long node : nodes) {
            waiting = view.slotOf(node) < 0 ? node : waiting;
        }
        renderables.remove(stays);
        renderables.remove(waiting);
        update(scene, renderables, view);
        assertThat(view.waitingCount()).isZero();
        assertThat(view.instanceCount()).isEqualTo(LIMITS.instances() - 1);
        assertThat(view.slotOf(waiting)).isEqualTo(-1);
        // Waiting ones draw oldest first as slots free.
        long first = facing(scene, 10, 0, -3);
        long second = facing(scene, 11, 0, -3);
        long third = facing(scene, 12, 0, -3);
        renderables.set(first, SQUARE, List.of());
        renderables.set(second, SQUARE, List.of());
        renderables.set(third, SQUARE, List.of());
        update(scene, renderables, view);
        assertThat(view.slotOf(first)).isNotNegative();
        assertThat(view.waitingCount()).isEqualTo(2);
        renderables.remove(first);
        update(scene, renderables, view);
        assertThat(view.slotOf(second)).isNotNegative();
        assertThat(view.slotOf(third)).isEqualTo(-1);
    }

    @Test
    void fallingBehindTheRenderablesKeepsWhatIsOnTheGpu() throws InterruptedException {
        SceneGraph scene = new SceneGraph(new ChangeClock());
        Renderables renderables = new Renderables(scene, 4); // keeps four changes
        SceneView view = closing(new SceneView(resources, renderables, renderer()));
        long kept = facing(scene, 0, 0, -3);
        long gone = facing(scene, 1, 0, -3);
        long changed = facing(scene, 2, 0, -3);
        renderables.set(kept, SQUARE, List.of(RED));
        renderables.set(gone, SQUARE, List.of(RED));
        renderables.set(changed, SQUARE, List.of(RED));
        update(scene, renderables, view);
        load();
        update(scene, renderables, view);
        int mesh = view.meshOf(kept);
        int block = view.firstMaterialOf(kept);
        // More changes than the log keeps: the view reconciles every renderable, and keeps what did not change.
        renderables.remove(gone);
        renderables.setMaterials(changed, List.of(GREEN));
        for (int i = 0; i < 4; i++) {
            renderables.setCastsShadows(kept, i % 2 == 0);
        }
        update(scene, renderables, view);
        assertThat(view.meshOf(kept)).isEqualTo(mesh);
        assertThat(view.firstMaterialOf(kept)).isEqualTo(block);
        assertThat(pool.contains(mesh)).isTrue();
        assertThat(view.slotOf(gone)).isEqualTo(-1);
        assertThat(view.renderableCount()).isEqualTo(2);
        assertThat(assets.holders(SQUARE)).isEqualTo(1);
        assertThat(resources.materialSetCount()).isEqualTo(2);
    }

    @Test
    void renderablesWaitForRoomInTheMaterialTable() throws InterruptedException {
        // The table holds 16 ids, the default one taken: 15 renderables of materials of their own fit, one more waits.
        SceneGraph scene = new SceneGraph(new ChangeClock());
        Renderables renderables = new Renderables(scene);
        SceneView view = closing(new SceneView(resources, renderables,
            closing(new MeshletRenderer(device, pool, new MeshletRenderer.Limits(32, 256, 64, 1 << 12),
                VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_D32_SFLOAT, slots))));
        long[] nodes = new long[16];
        for (int i = 0; i < nodes.length; i++) {
            nodes[i] = facing(scene, i, 0, -3);
            renderables.set(nodes[i], SQUARE,
                List.of(AssetRef.generated(RenderAssets.MATERIAL, GLOWS, new Glow(i, 0, 0), 9)));
        }
        update(scene, renderables, view);
        load();
        update(scene, renderables, view);
        assertThat(view.instanceCount()).isEqualTo(15);
        assertThat(view.waitingCount()).isEqualTo(1);
        assertThat(materials.hasRoom(1)).isFalse();
        // A drawn one goes: its ids free once no frame in flight reads them, and the waiting one draws.
        long drawn = NodeHandle.NONE;
        for (long node : nodes) {
            drawn = view.slotOf(node) >= 0 ? node : drawn;
        }
        renderables.remove(drawn);
        update(scene, renderables, view);
        assertThat(view.waitingCount()).isEqualTo(1);
        for (int frame = 0; frame < slots.count(); frame++) {
            slots.begin(frame);
            slots.end();
        }
        update(scene, renderables, view);
        assertThat(view.waitingCount()).isZero();
        assertThat(view.instanceCount()).isEqualTo(15);
    }

    @Test
    void idsStayWhileDrawnAndLayersAndShadowsFollowTheScene() throws InterruptedException {
        SceneGraph scene = new SceneGraph(new ChangeClock());
        Renderables renderables = new Renderables(scene);
        MeshletRenderer renderer = renderer();
        SceneView view = closing(new SceneView(resources, renderables, renderer));
        GpuBuffer[] readbacks = {readback(), readback()};
        List<FrameGraph> graphs = List.of(graph(view, false, readbacks));
        long[] nodes = new long[3];
        for (int i = 0; i < 3; i++) {
            nodes[i] = facing(scene, 2 * i - 2, 0, -8);
            renderables.set(nodes[i], SQUARE, List.of());
        }
        frame(graphs, List.of(view), camera);
        load();
        frame(graphs, List.of(view), camera);
        int[] ids = {view.slotOf(nodes[0]), view.slotOf(nodes[1]), view.slotOf(nodes[2])};
        assertThat(ids).containsExactlyInAnyOrder(0, 1, 2);
        // The middle one goes: the others keep their ids, the renderer keeps the hole.
        renderables.remove(nodes[1]);
        frame(graphs, List.of(view), camera);
        assertThat(view.slotOf(nodes[0])).isEqualTo(ids[0]);
        assertThat(view.slotOf(nodes[2])).isEqualTo(ids[2]);
        assertThat(view.instanceCount()).isEqualTo(2);
        assertThat(renderer.instanceCount()).isEqualTo(Math.max(ids[0], ids[2]) + 1);
        // A new one takes the lowest free id.
        long next = facing(scene, 0, 0, -8);
        renderables.set(next, SQUARE, List.of());
        frame(graphs, List.of(view), camera);
        assertThat(view.slotOf(next)).isEqualTo(ids[1]);
        // Layers and shadows, from the scene: one node on layer 5, one casting no shadow.
        scene.setLayers(nodes[0], 1 << 5);
        renderables.setCastsShadows(nodes[2], false);
        renderer.setViewFilter(0, 1, false);
        frame(graphs, List.of(view), camera);
        device.waitIdle();
        assertThat(renderer.visibleMeshlets()).as("on layer 0").isEqualTo(2);
        renderer.setViewFilter(0, -1, true);
        frame(graphs, List.of(view), camera);
        device.waitIdle();
        assertThat(renderer.visibleMeshlets()).as("casting shadows").isEqualTo(2);
        // The highest id goes: the renderer's count shrinks to the highest in use.
        renderables.remove(nodes[2]);
        renderables.remove(nodes[0]);
        frame(graphs, List.of(view), camera);
        assertThat(renderer.instanceCount()).isEqualTo(view.slotOf(next) + 1);
    }

    @Test
    void misuseIsChecked() {
        SceneGraph scene = new SceneGraph(new ChangeClock());
        try (GeometryPool other = new GeometryPool(device, GeometryPool.Capacity.of(64, 1, 1));
             MeshletRenderer elsewhere = new MeshletRenderer(device, other, LIMITS, VK_FORMAT_R8G8B8A8_UNORM,
                 VK_FORMAT_D32_SFLOAT)) {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new SceneView(resources, new Renderables(scene), elsewhere))
                .withMessageContaining("another geometry pool");
        }
        SceneView view = closing(new SceneView(resources, new Renderables(scene), renderer()));
        assertThat(view.resources()).isSameAs(resources);
        assertThat(view.renderer().pool()).isSameAs(pool);
        resources.close();
        resources.close();
        assertThat(resources.pool()).isSameAs(pool);
        assertThat(resources.materials()).isSameAs(materials);
        assertThat(resources.textures()).isSameAs(textures);
        assertThat(resources.waitingForRoomCount()).isZero();
        Renderables renderables = view.renderables();
        renderables.set(scene.create(), SQUARE, List.of());
        assertThatIllegalStateException().isThrownBy(view::update).withMessageContaining("closed");
    }
}
