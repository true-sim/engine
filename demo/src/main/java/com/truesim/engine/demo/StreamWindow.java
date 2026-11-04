package com.truesim.engine.demo;

import static org.lwjgl.vulkan.VK10.VK_FORMAT_D32_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16B16A16_SFLOAT;

import com.truesim.common.math.color.Color;
import com.truesim.engine.app.AppConfig;
import com.truesim.engine.app.Application;
import com.truesim.engine.app.Engine;
import com.truesim.engine.app.Frame;
import com.truesim.engine.app.Plugin;
import com.truesim.engine.app.PluginKey;
import com.truesim.engine.app.Stage;
import com.truesim.engine.app.Startup;
import com.truesim.engine.app.TrueSim;
import com.truesim.engine.app.profile.ProfilerSettings;
import com.truesim.engine.graph.FrameGraph;
import com.truesim.engine.graph.Profiling;
import com.truesim.engine.graph.Rendering;
import com.truesim.engine.graph.ViewPort;
import com.truesim.engine.input.Input;
import com.truesim.engine.platform.Desktop;
import com.truesim.engine.platform.PlatformListener;
import com.truesim.engine.platform.Window;
import com.truesim.engine.platform.WindowOptions;
import com.truesim.engine.render.Camera;
import com.truesim.engine.render.ClusteredForwardShading;
import com.truesim.engine.render.ClusteredLights;
import com.truesim.engine.render.DisplayTransform;
import com.truesim.engine.render.GeometryPool;
import com.truesim.engine.render.GpuScene;
import com.truesim.engine.render.Keys;
import com.truesim.engine.render.Lights;
import com.truesim.engine.render.Material;
import com.truesim.engine.render.Materials;
import com.truesim.engine.render.MeshletRenderer;
import com.truesim.engine.render.RayTracing;
import com.truesim.engine.render.SceneGeometry;
import com.truesim.engine.render.SceneView;
import com.truesim.engine.render.Textures;
import com.truesim.engine.render.ViewOptions;
import com.truesim.engine.render.materials.MetallicRoughnessParameters;
import com.truesim.engine.render.shadows.CascadedShadowMaps;
import com.truesim.engine.ui.UiPlugin;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.ui.controls.Ui;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.draw.UiTextures;
import com.truesim.ui.fonts.BundledFonts;
import com.truesim.ui.style.Rule;
import com.truesim.ui.style.StyleSheet;
import com.truesim.ui.style.tss.Tss;
import com.truesim.ui.text.AtlasSettings;
import com.truesim.ui.text.GlyphAtlas;
import com.truesim.ui.text.TextSystem;
import com.truesim.ui.text.natives.NativeTextBackend;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * The stream benchmark's fly-over in a window, to watch: the same cells, content and camera path as
 * {@link StreamBench}'s headless run, presented in a resizable window and paced by the display (the presenter), with
 * the profiler's frame graph overlay (F3, on at start) and detailed view (Shift+F3), and a line at the bottom left:
 * cells in range, meshes and textures added and removed in the last second. Runs until the window is closed, or for
 * {@code -Pseconds=n}. {@code ./gradlew :engine-demo:streamBench --args=window} (with rays, large, heavy, direct,
 * inframe or grow as the benchmark takes them).
 *
 * <p>The camera follows real time ({@code x = seconds * SPEED}, which the benchmark's {@code frame * SPEED / 90} is at
 * 90 Hz). Per frame it allocates nothing: the cells are an indexed list searched by key, the range is arithmetic, and
 * the line is rewritten in place once a second; cells coming into range allocate their content, as they do headless.
 */
final class StreamWindow implements Application, PlatformListener {

    private static final int COLOR = VK_FORMAT_R16G16B16A16_SFLOAT;
    private static final int DEPTH = VK_FORMAT_D32_SFLOAT;
    private static final String HUD_STYLE = """
        panel.stream-hud { layout: flex; direction: column; justify-content: end; align-items: start; padding: 12px;
          width: 100%; height: 100%; }
        label.stream-hud-line { font-size: 14px; color: #e6edf3; padding: 4px 8px; radius: 4px;
          background: box(rgba(#0d1117, 0.78)); }
        """;

    private final double seconds; // 0: until the window is closed
    private final Stream stream = new Stream();
    private Engine engine;
    private Ui ui;
    private Element line;
    private final StringBuilder text = new StringBuilder(160);
    private long started = -1;
    private long counted;
    private int meshesAdded;
    private int meshesRemoved;
    private int texturesAdded;
    private int texturesRemoved;

    private StreamWindow(double seconds) {
        this.seconds = seconds;
    }

    static void run(String[] args) {
        double seconds = Double.parseDouble(System.getProperty("truesim.demo.seconds", "0"));
        TrueSim.run(new StreamWindow(seconds), args);
    }

    @Override
    public void configure(AppConfig app) {
        app.id("truesim-stream").name("true-sim stream")
            .use(new Desktop(), new Input(), new Rendering(), new Profiling(), stream,
                new UiPlugin(StreamWindow::ui).view("main"));
    }

    /** The UI: the default theme with the line's style, the bundled fonts. */
    private static Ui ui(UiTextures ignored) {
        NativeTextBackend backend = new NativeTextBackend();
        UiTextures textures = new UiTextures();
        TextSystem text = new TextSystem(BundledFonts.registry(backend), backend.newShaper(),
            new GlyphAtlas(textures, backend.msdfGenerator(), backend.newRasterizer(), null, null,
                new AtlasSettings()));
        StyleSheet theme = Tss.defaultTheme();
        List<Rule> rules = new ArrayList<>(theme.rules());
        rules.addAll(Tss.compileString("stream-hud.tss", HUD_STYLE).rules());
        return new Ui(new StyleSheet(rules, theme.tokens(), theme.tokenSets()), text, textures);
    }

    @Override
    public void start(Engine started) {
        engine = started;
        engine.get(Desktop.KEY).listen(this);
        UiPlugin uiPlugin = engine.get(UiPlugin.KEY);
        uiPlugin.profiler();
        engine.settings().set(ProfilerSettings.OVERLAY, true);
        ui = uiPlugin.ui();
        line = ui.label("stream-hud-line", "streaming");
        ui.layerStack().get(com.truesim.ui.core.Layers.HUD).root().add(ui.panel("stream-hud").add(line));
        System.out.println("GPU: " + engine.get(Rendering.KEY).device().gpu().name());
        System.out.println("""
            Keys: F3 the frame graph overlay (on), Shift+F3 the detailed profiler (wheel zooms, drag pans).
            Close the window to stop.""");
    }

    @Override
    public void update(Frame frame) {
        long now = System.nanoTime();
        if (started < 0) {
            started = now;
            counted = now;
        }
        if (seconds > 0 && now - started >= seconds * 1e9) {
            engine.quit();
        }
        stream.step((now - started) / 1e9);
        meshesAdded += stream.meshesAdded;
        meshesRemoved += stream.meshesRemoved;
        texturesAdded += stream.texturesAdded;
        texturesRemoved += stream.texturesRemoved;
        if (now - counted >= 1_000_000_000L) {
            hud();
            counted = now;
            meshesAdded = 0;
            meshesRemoved = 0;
            texturesAdded = 0;
            texturesRemoved = 0;
        }
    }

    /** The line: what is in range and what streamed in the last second. */
    private void hud() {
        text.setLength(0);
        text.append("cells in range ").append(stream.inRange).append(" (").append(stream.placed)
            .append(" placed, ").append(stream.cells.size() - stream.placed).append(" building)   last second: meshes +")
            .append(meshesAdded).append(" -").append(meshesRemoved).append(", textures +").append(texturesAdded)
            .append(" -").append(texturesRemoved).append("   on the GPU: ").append(stream.pool.meshCount())
            .append(" meshes, ").append(stream.textures.count()).append(" textures   x ")
            .append((int) stream.x).append(" m");
        ui.text(line, text);
    }

    @Override
    public void windowCloseRequested(int id) {
        engine.requestQuit(QuitReason.WINDOW_CLOSED, false);
    }

    /**
     * The streamed world on the engine's device and graph: the window and its view (made in {@link Stage#GRAPH}, so
     * the UI finds the window's view), the cells, and their streaming each frame.
     */
    private static final class Stream implements Plugin {
        static final PluginKey<Stream> KEY = PluginKey.of("demo.stream", Stream.class);

        private final ExecutorService builders = Executors.newFixedThreadPool(3);
        private final ArrayList<StreamBench.Cell> cells = new ArrayList<>();
        private final ArrayDeque<Integer> freeIds = new ArrayDeque<>();
        private final int[] added = new int[1];
        private final Camera camera = new Camera();
        private VulkanDevice device;
        private GeometryPool pool;
        private Materials materials;
        private Textures textures;
        private GpuScene scene;
        private Lights lights;
        private MeshletRenderer renderer;
        private CascadedShadowMaps sun;
        private RayTracing rayTracing;
        private Window window;
        private long seed = 1;
        double x;
        int inRange;
        int placed;
        int meshesAdded;
        int meshesRemoved;
        int texturesAdded;
        int texturesRemoved;

        @Override
        public PluginKey<Stream> key() {
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
            device = rendering.device();
            FrameGraph graph = rendering.graph();
            var slots = graph.frameSlots();
            pool = new GeometryPool(device, StreamBench.capacity(), slots);
            materials = new Materials(device, StreamBench.materialCapacity(), slots);
            textures = new Textures(device, slots);
            scene = new GpuScene(device, pool, StreamBench.instanceCapacity(), slots);
            lights = new Lights(device, 4, 8, slots);
            scene.setMaterials(materials);
            if (StreamBench.inFrame) {
                pool.streamBudget(0);
            }
            StreamBench.sharedPool = pool;
            StreamBench.sharedTextures = textures;
            rayTracing = StreamBench.rays && device.rayTracing() ? new RayTracing(device, pool, 512) : null;
            StreamBench.rayTracing = rayTracing;
            renderer = new MeshletRenderer(device, scene, COLOR, DEPTH, slots);
            SceneGeometry geometry = SceneGeometry.meshlets(renderer);
            StreamBench.groundMaterial = materials.add(new Material(MetallicRoughnessParameters.DEFAULTS
                .withBaseColor(new Color(0.3f, 0.45f, 0.25f, 1))));
            lights.directional(0, -0.4f, -0.8f, -0.45f, 2.5f, 2.3f, 2.0f);
            lights.setCount(1);
            ViewPort port = graph.addView("main");
            SceneView view = geometry.createView(port, camera, ViewOptions.of(1 << 14, 1 << 22).withOcclusion(true))
                .set(1, MeshletRenderer.MODE_LIT);
            sun = new CascadedShadowMaps(geometry, lights, 0, 0, view, 3, 2048, ViewOptions.shadow(1 << 16, 1 << 22),
                120);
            port.add(new ClusteredForwardShading(geometry, view, COLOR, DEPTH).clearColor(0.4f, 0.55f, 0.8f))
                .add(new ClusteredLights(lights, view)).add(sun);
            for (int id = StreamBench.instanceCapacity() - 1; id >= 1; id--) {
                freeIds.push(id);
            }
            window = startup.get(Desktop.KEY).platform().createWindow(
                WindowOptions.of("true-sim stream", 1600, 900), startup.settings().scoped("main"));
            rendering.presenter().outputTransform(new DisplayTransform()).present(port, Keys.SCENE_COLOR, window);
        }

        /** A frame at {@code seconds} into the flight: the camera and the cells streamed as headless. */
        void step(double seconds) {
            x = seconds * StreamBench.SPEED;
            StreamBench.fly(camera, x, Math.max(1, window.pixelWidth()), Math.max(1, window.pixelHeight()));
            added[0] = 0;
            meshesRemoved = 0;
            texturesAdded = 0;
            texturesRemoved = 0;
            int range = StreamBench.range;
            int cx = (int) Math.floor(x / StreamBench.CELL);
            for (int i = cx - range; i <= cx + range; i++) {
                for (int j = -range; j <= range; j++) {
                    long key = (long) i << 32 | (j & 0xffffffffL);
                    if (find(key) < 0) {
                        StreamBench.Cell cell = new StreamBench.Cell(key);
                        long cellSeed = seed++;
                        cell.building = CompletableFuture.supplyAsync(() -> StreamBench.buildMeshes(cellSeed, key),
                            builders);
                        cells.add(cell);
                    }
                }
            }
            for (int c = 0; c < cells.size(); c++) {
                StreamBench.Cell cell = cells.get(c);
                if (cell.building != null && cell.building.isDone()) {
                    texturesAdded += StreamBench.place(cell, cell.building.join(), pool, textures, materials, scene,
                        freeIds, added);
                    cell.building = null;
                }
            }
            inRange = 0;
            placed = 0;
            for (int c = cells.size() - 1; c >= 0; c--) {
                StreamBench.Cell cell = cells.get(c);
                boolean wanted = Math.abs((int) (cell.key >> 32) - cx) <= range && Math.abs((int) cell.key) <= range;
                if (!wanted && cell.building == null) {
                    texturesRemoved += cell.texture >= 0 ? 1 : 0;
                    meshesRemoved += StreamBench.clear(cell, pool, textures, materials, scene, freeIds);
                    cells.set(c, cells.get(cells.size() - 1));
                    cells.remove(cells.size() - 1);
                } else {
                    inRange += wanted ? 1 : 0;
                    placed += cell.building == null ? 1 : 0;
                }
            }
            meshesAdded = added[0];
            sun.update();
        }

        private int find(long key) {
            for (int c = 0; c < cells.size(); c++) {
                if (cells.get(c).key == key) {
                    return c;
                }
            }
            return -1;
        }

        @Override
        public void stop() {
            builders.shutdown(); // the builds started finish (they prepare into the pool), then the pool closes
            try {
                builders.awaitTermination(60, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (device == null) {
                return;
            }
            device.waitIdle();
            for (int c = 0; c < cells.size(); c++) {
                StreamBench.Cell cell = cells.get(c);
                if (cell.building == null) {
                    StreamBench.clear(cell, pool, textures, materials, scene, freeIds);
                } else if (!StreamBench.direct && cell.building.isDone() && !cell.building.isCompletedExceptionally()) {
                    StreamBench.Built unplaced = cell.building.join(); // built but never placed: its prepared content
                    unplaced.preparedMeshes().forEach(GeometryPool.Prepared::close);
                    unplaced.preparedTexture().close();
                }
            }
            device.waitIdle();
            renderer.close();
            if (rayTracing != null) {
                rayTracing.close();
            }
            lights.close();
            scene.close();
            textures.close();
            materials.close();
            pool.close();
        }
    }
}
