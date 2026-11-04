package com.truesim.engine.demo;

import static org.lwjgl.vulkan.VK10.VK_FORMAT_D32_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16B16A16_SFLOAT;

import com.truesim.common.math.Maths;
import com.truesim.common.state.ChangeClock;
import com.truesim.engine.app.Plugin;
import com.truesim.engine.app.PluginKey;
import com.truesim.engine.app.Stage;
import com.truesim.engine.app.Startup;
import com.truesim.engine.graph.FrameGraph;
import com.truesim.engine.graph.Rendering;
import com.truesim.engine.graph.ViewPort;
import com.truesim.engine.platform.Desktop;
import com.truesim.engine.platform.Window;
import com.truesim.engine.platform.WindowOptions;
import com.truesim.engine.render.Camera;
import com.truesim.engine.render.ClusteredForwardShading;
import com.truesim.engine.render.DisplayTransform;
import com.truesim.engine.render.GeometryPool;
import com.truesim.engine.render.GpuScene;
import com.truesim.engine.render.Keys;
import com.truesim.engine.render.Materials;
import com.truesim.engine.render.MeshletRenderer;
import com.truesim.engine.render.RenderView;
import com.truesim.engine.render.SceneGeometry;
import com.truesim.engine.render.SceneView;
import com.truesim.engine.render.SelectionHighlights;
import com.truesim.engine.render.SelectionOutline;
import com.truesim.engine.render.Textures;
import com.truesim.engine.render.ViewOptions;
import com.truesim.engine.scene.SceneHighlights;
import com.truesim.engine.scene.SceneResources;
import com.truesim.engine.scene.SceneSync;
import com.truesim.engine.vulkan.FrameSlots;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.scene.assets.AssetCache;
import com.truesim.scene.assets.AssetRef;
import com.truesim.scene.assets.Generator;
import com.truesim.scene.assets.mesh.MeshData;
import com.truesim.scene.core.Pickable;
import com.truesim.scene.core.SceneGraph;
import com.truesim.scene.core.SpatialIndex;
import com.truesim.scene.render.MaterialData;
import com.truesim.scene.render.RenderAssets;
import com.truesim.scene.render.Renderables;
import java.util.List;
import java.util.SplittableRandom;

/**
 * The UI gallery's window and 3D world: a resizable window presenting the "main" view (HDR where the display has it),
 * and a scene of rocks on rolling ground drawn through the scene graph ({@link SceneSync}, forward shading), every rock
 * pickable by its triangles, the selection and the hover outlined ({@link SelectionOutline}). Made in the graph stage,
 * so the UI plugin finds the window's view when it wires up; {@link #sync} brings the GPU scene up to date each frame.
 */
final class GalleryWorld implements Plugin {

    static final PluginKey<GalleryWorld> KEY = PluginKey.of("demo.gallery.world", GalleryWorld.class);

    static final int COLOR = VK_FORMAT_R16G16B16A16_SFLOAT;
    static final int DEPTH = VK_FORMAT_D32_SFLOAT;
    static final int ROCKS = Integer.getInteger("truesim.gallery.rocks", 400);
    static final int GROUND_SIZE = 160;
    private static final ViewOptions OPTIONS = ViewOptions.of(1 << 20, 80_000_000).withOcclusion(true);

    record Rock(int subdivisions) {
    }

    record Ground(int size) {
    }

    record Stone(float r, float g, float b) {
    }

    private static final Generator<Rock, MeshData> ROCKS_MADE = (rock, seed) -> DemoMeshes.rock(rock.subdivisions());
    private static final Generator<Ground, MeshData> GROUNDS = (ground, seed) -> DemoMeshes.terrain(ground.size());
    private static final Generator<Stone, MaterialData> STONES = (stone, seed) -> MaterialData.builder(
        MaterialData.METALLIC_ROUGHNESS).set("baseColor", stone.r(), stone.g(), stone.b(), 1f).set("roughness", 0.7f)
        .build();
    static final AssetRef<MeshData> ROCK = AssetRef.generated(RenderAssets.MESH, ROCKS_MADE, new Rock(4), 1);
    static final AssetRef<MeshData> GROUND = AssetRef.generated(RenderAssets.MESH, GROUNDS, new Ground(GROUND_SIZE), 1);
    private static final List<AssetRef<MaterialData>> STONE_COLORS = List.of(
        AssetRef.generated(RenderAssets.MATERIAL, STONES, new Stone(0.55f, 0.5f, 0.45f), 1),
        AssetRef.generated(RenderAssets.MATERIAL, STONES, new Stone(0.8f, 0.3f, 0.2f), 1),
        AssetRef.generated(RenderAssets.MATERIAL, STONES, new Stone(0.85f, 0.75f, 0.3f), 1),
        AssetRef.generated(RenderAssets.MATERIAL, STONES, new Stone(0.25f, 0.45f, 0.85f), 1));
    private static final AssetRef<MaterialData> GRASS =
        AssetRef.generated(RenderAssets.MATERIAL, STONES, new Stone(0.3f, 0.45f, 0.22f), 1);

    final Camera camera = new Camera();
    final SceneGraph graph = new SceneGraph(new ChangeClock(), ROCKS + 64, SceneGraph.DEFAULT_LOG_CAPACITY);
    final SpatialIndex index = new SpatialIndex(graph);
    final Renderables renderables = new Renderables(graph);
    final long[] rocks = new long[ROCKS];
    Window window;
    ViewPort port;
    FrameGraph frameGraph;
    SceneGeometry geometry;
    SceneView view;
    AssetCache assets;
    private RenderView ids;
    private GeometryPool pool;
    private Materials materials;
    private Textures textures;
    private GpuScene gpu;
    private MeshletRenderer renderer;
    private SceneResources resources;
    private SceneSync sync;
    private SceneHighlights highlights;
    private SelectionOutline outline;

    @Override
    public PluginKey<GalleryWorld> key() {
        return KEY;
    }

    @Override
    public List<PluginKey<?>> requires() {
        return List.of(Rendering.KEY, Desktop.KEY);
    }

    @Override
    public void start(Stage stage, Startup startup) {
        if (stage != Stage.GRAPH) {
            return;
        }
        Rendering rendering = startup.get(Rendering.KEY);
        VulkanDevice device = rendering.device();
        frameGraph = rendering.graph();
        FrameSlots slots = frameGraph.frameSlots();
        window = startup.get(Desktop.KEY).platform().createWindow(WindowOptions.of("true-sim UI gallery", 1600, 1000),
            startup.settings().scoped("gallery"));
        pool = new GeometryPool(device, GeometryPool.Capacity.of(1 << 21, 100_000, 4), slots);
        materials = new Materials(device, 64, slots);
        textures = new Textures(device, slots);
        assets = new AssetCache(startup.engine().tasks());
        resources = new SceneResources(assets, pool, materials, textures);
        gpu = new GpuScene(device, pool, ROCKS + 64, slots);
        renderer = new MeshletRenderer(device, gpu, COLOR, DEPTH, slots);
        geometry = SceneGeometry.meshlets(renderer);
        port = frameGraph.addView("main");
        view = geometry.createView(port, camera, OPTIONS);
        ids = renderer.createView(OPTIONS); // the forward view's own IDs of the highlighted, for the outline
        SelectionHighlights flags = new SelectionHighlights();
        outline = new SelectionOutline(frameGraph, geometry, ids, view, DEPTH, flags);
        port.add(new ClusteredForwardShading(geometry, view, COLOR, DEPTH).clearColor(0.45f, 0.6f, 0.8f)).add(outline);
        rendering.presenter().outputTransform(new DisplayTransform()).present(port, Keys.SCENE_COLOR, window);
        build();
        sync = new SceneSync(resources, renderables, gpu);
        highlights = new SceneHighlights(sync, flags);
    }

    /** The ground and the rocks on it, each pickable by its triangles. */
    private void build() {
        long ground = graph.create();
        renderables.set(ground, GROUND, List.of(GRASS));
        SplittableRandom random = new SplittableRandom(5);
        double extent = GROUND_SIZE / 2.0 - 6;
        for (int i = 0; i < ROCKS; i++) {
            long rock = graph.create();
            double x = random.nextDouble(-extent, extent);
            double z = random.nextDouble(-extent, extent);
            double scale = 0.4 + 1.6 * Maths.pow(random.nextDouble(), 3);
            double a = random.nextDouble(Math.PI);
            graph.setTranslation(rock, x, DemoMeshes.height(x, z) + 0.6 * scale, z);
            graph.setRotation(rock, 0, Maths.sin(a), 0, Maths.cos(a));
            graph.setScale(rock, scale, scale * random.nextDouble(0.7, 1.2), scale);
            renderables.set(rock, ROCK, List.of(STONE_COLORS.get(random.nextInt(STONE_COLORS.size()))));
            graph.components().set(rock, Pickable.TYPE, Pickable.mesh());
            rocks[i] = rock;
        }
        graph.update();
        index.update();
    }

    /** After the frame's changes (gizmos, selection): the scene's transforms, instances and highlights to the GPU. */
    void sync() {
        graph.update();
        renderables.update();
        sync.update();
        highlights.update();
        view.set(1, MeshletRenderer.MODE_LIT);
        ids.set(camera, 1, MeshletRenderer.MODE_LIT);
    }

    /** A second view of the scene through {@code through} on {@code target} (a minimap's), shaded like the main one. */
    SceneView viewOn(ViewPort target, Camera through) {
        SceneView v = geometry.createView(target, through, OPTIONS);
        target.add(new ClusteredForwardShading(geometry, v, COLOR, DEPTH).clearColor(0.45f, 0.6f, 0.8f));
        return v;
    }

    /** Frees the scene's GPU objects: call with the device idle. */
    void close() {
        if (sync != null) {
            sync.close();
            outline.close();
            ids.close();
            renderer.close();
            gpu.close();
            resources.close();
            textures.close();
            materials.close();
            pool.close();
            sync = null;
        }
    }
}
