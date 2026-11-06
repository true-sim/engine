package com.truesim.engine.render.testing;

import static org.lwjgl.vulkan.VK10.VK_FORMAT_D32_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM;

import com.truesim.engine.graph.FrameGraph;
import com.truesim.engine.graph.testing.TestTarget;
import com.truesim.engine.render.Camera;
import com.truesim.engine.render.ClusteredForwardShading;
import com.truesim.engine.render.GeometryPool;
import com.truesim.engine.render.GpuScene;
import com.truesim.engine.render.Keys;
import com.truesim.engine.render.MeshletRenderer;
import com.truesim.engine.render.RenderView;
import com.truesim.engine.render.SceneGeometry;
import com.truesim.engine.render.SceneView;
import com.truesim.engine.render.ViewOptions;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.scene.assets.mesh.MeshData;

/**
 * The renderer set up for a GPU test, as the engine's own tests set it up: a geometry pool, a scene of instances, the
 * meshlet renderer (RGBA8 color, D32 depth) with one render view (occlusion culling on), a frame graph and a
 * {@link TestTarget} the main view's color goes to. A library's test adds its technique to the main view and reads
 * the pixels back; meshes come from {@link TestMeshes}.
 *
 * <pre>{@code
 * try (TestGpu gpu = TestGpu.open(); TestRenderer r = TestRenderer.of(gpu.device(), 64, 64)) {
 *     int box = r.add(TestMeshes.box());
 *     r.scene().set(0, box, 0, 0, -4, 0, 0, 0, 1, 1, 1, 1);   // 4 m ahead, unturned
 *     SceneView main = r.toTarget(r.main(new Camera().set(0, 0, -1, 0, 1, 0, Math.PI / 2, 64, 64, 0.1)));
 *     main.port().add(r.forward(main)).add(new MyFog(main));
 *     r.frames(3);                                           // occlusion culling settles over a few frames
 *     assertThat(r.target().rgba(32, 32)[0]).isGreaterThan(100);
 * }
 * }</pre>
 */
public final class TestRenderer implements AutoCloseable {

    /** The color format the renderer and the target use. */
    public static final int COLOR_FORMAT = VK_FORMAT_R8G8B8A8_UNORM;
    /** The depth format. */
    public static final int DEPTH_FORMAT = VK_FORMAT_D32_SFLOAT;

    private final VulkanDevice device;
    private final int width;
    private final int height;
    private final GeometryPool pool;
    private final GpuScene scene;
    private final MeshletRenderer renderer;
    private final RenderView view;
    private final FrameGraph graph;
    private final TestTarget target;

    private TestRenderer(VulkanDevice device, int width, int height, GeometryPool.Capacity capacity, int instances,
        ViewOptions options) {
        this.device = device;
        this.width = width;
        this.height = height;
        pool = new GeometryPool(device, capacity);
        scene = new GpuScene(device, pool, instances);
        renderer = new MeshletRenderer(device, scene, COLOR_FORMAT, DEPTH_FORMAT);
        view = renderer.createView(options);
        graph = new FrameGraph(device, 2);
        target = new TestTarget(device, width, height);
    }

    /**
     * A renderer for {@code width} x {@code height} frames with room for small scenes: 16,384 vertices, 256 meshlets,
     * 8 meshes, 1,024 instances.
     */
    public static TestRenderer of(VulkanDevice device, int width, int height) {
        return of(device, width, height, GeometryPool.Capacity.of(1 << 14, 256, 8), 1024,
            ViewOptions.of(4096, 1 << 16).withOcclusion(true));
    }

    /** A renderer with the given capacities and view options. */
    public static TestRenderer of(VulkanDevice device, int width, int height, GeometryPool.Capacity capacity,
        int instances, ViewOptions options) {
        return new TestRenderer(device, width, height, capacity, instances, options);
    }

    /** Adds {@code mesh} to the pool (and closes it: the pool holds a copy). Returns its mesh index. */
    public int add(MeshData mesh) {
        try (mesh) {
            return pool.add(mesh);
        }
    }

    /**
     * A view "main" of the graph, {@code width} x {@code height}, of the render view through {@code camera}, drawing
     * lit ({@link MeshletRenderer#MODE_LIT}, 1 pixel of error). Its port takes the techniques and the output.
     */
    public SceneView main(Camera camera) {
        return new SceneView(graph.addView("main").size(width, height), view, camera).set(1,
            MeshletRenderer.MODE_LIT);
    }

    /** Clustered forward shading of the renderer's meshlets as {@code main} sees them: what writes the scene. */
    public ClusteredForwardShading forward(SceneView main) {
        return new ClusteredForwardShading(SceneGeometry.meshlets(renderer), main, COLOR_FORMAT, DEPTH_FORMAT);
    }

    /** Sends {@code main}'s {@link Keys#SCENE_COLOR} to the target. Returns {@code main}. */
    public SceneView toTarget(SceneView main) {
        main.port().output(Keys.SCENE_COLOR, target.output());
        return main;
    }

    /** Records {@code count} frames and reads the last one back ({@link TestTarget#frames}). */
    public void frames(int count) {
        target.frames(graph, count);
    }

    /** The geometry pool. */
    public GeometryPool pool() {
        return pool;
    }

    /** The scene's instances. */
    public GpuScene scene() {
        return scene;
    }

    /** The renderer. */
    public MeshletRenderer renderer() {
        return renderer;
    }

    /** Its render view: what the main view's culling draws. */
    public RenderView view() {
        return view;
    }

    /** The frame graph. */
    public FrameGraph graph() {
        return graph;
    }

    /** What the frames are read back from. */
    public TestTarget target() {
        return target;
    }

    @Override
    public void close() {
        device.waitIdle();
        graph.close();
        target.close();
        renderer.close();
        scene.close();
        pool.close();
    }
}
