package com.truesim.engine.scene;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.app.Plugin;
import com.truesim.engine.app.PluginKey;
import com.truesim.engine.app.Stage;
import com.truesim.engine.app.Startup;
import com.truesim.engine.graph.FrameGraph;
import com.truesim.engine.graph.Rendering;
import com.truesim.engine.render.GeometryPool;
import com.truesim.engine.render.Materials;
import com.truesim.engine.render.Textures;
import com.truesim.engine.settings.Setting;
import com.truesim.scene.assets.AssetCache;
import com.truesim.scene.assets.AssetRef;
import com.truesim.scene.assets.AssetType;
import com.truesim.scene.assets.PathAssets;
import com.truesim.scene.assets.PathSource;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Assets as a plugin: the asset cache on the engine's jobs (loads finish at the start of a frame), assets addressed by
 * path on top of it ({@link #paths()}: mounts, importers), and their GPU side ({@link #resources()}: meshes, materials
 * and textures uploaded once however many scenes draw them). Nothing is mounted or imported by default: the game, or a
 * preset, adds mounts and importers.
 *
 * <pre>{@code
 * app.use(new Rendering(), new Assets().mount("game", Path.of("assets")));
 * Assets assets = engine.get(Assets.KEY);
 * new Gltf(assets.paths()).register();                         // scene-gltf: models, meshes, materials
 * AssetRef<ModelData> car = assets.ref(RenderAssets.MODEL, "game://models/car.glb");
 * }</pre>
 */
public final class Assets implements Plugin {

    /** Its key. */
    public static final PluginKey<Assets> KEY = PluginKey.of("assets", Assets.class);

    /** Megabytes of loaded assets no one holds kept for later, least recently released evicted first. */
    public static final Setting<Integer> BUDGET =
        Setting.integer("assets.budgetMegabytes", 512, 0, 1 << 20, "megabytes of unused assets kept for later");

    private final List<String[]> mounts = new ArrayList<>(); // name, folder: added when it starts
    private GeometryPool.Capacity capacity = GeometryPool.Capacity.of(1 << 21, 1 << 16, 1 << 12);
    private int materialCapacity = 4096;
    private com.truesim.engine.app.Engine engine;
    private Rendering rendering;
    private AssetCache cache;
    private PathAssets paths;
    private GeometryPool pool;
    private Materials materials;
    private Textures textures;
    private SceneResources resources;

    @Override
    public PluginKey<Assets> key() {
        return KEY;
    }

    @Override
    public List<PluginKey<?>> requires() {
        return List.of(Rendering.KEY);
    }

    /** Mounts the files under {@code folder} as {@code name} ({@code name://...}) when it starts. Before startup. */
    @AllowAllocation("set up once")
    public Assets mount(String name, Path folder) {
        mounts.add(new String[] {Objects.requireNonNull(name, "name"), folder.toString()});
        return this;
    }

    /** How much geometry the GPU pool holds. Before startup. */
    public Assets geometry(GeometryPool.Capacity geometry) {
        capacity = Objects.requireNonNull(geometry, "geometry");
        return this;
    }

    /** How many materials the GPU table holds (4096 by default). Before startup. */
    public Assets materials(int count) {
        materialCapacity = count;
        return this;
    }

    @Override
    @AllowAllocation("starting up")
    public void start(Stage stage, Startup startup) {
        switch (stage) {
            case BEFORE_INSTANCE -> {
                engine = startup.engine();
                cache = new AssetCache(engine.tasks());
                paths = new PathAssets(cache);
                for (String[] mount : mounts) {
                    paths.mounts().add(mount[0], PathSource.folder(Path.of(mount[1])));
                }
                startup.registry().addHidden(BUDGET);
                cache.setBudget((long) startup.settings().get(BUDGET) << 20);
                startup.settings().onChange(BUDGET, () -> cache.setBudget((long) startup.settings().get(BUDGET) << 20));
            }
            case DEVICE -> {
                rendering = startup.get(Rendering.KEY);
                FrameGraph graph = rendering.graph();
                pool = new GeometryPool(rendering.device(), capacity, graph.frameSlots());
                materials = new Materials(rendering.device(), materialCapacity, graph.frameSlots());
                textures = new Textures(rendering.device(), graph.frameSlots());
                resources = new SceneResources(cache, pool, materials, textures);
            }
            default -> {
                // nothing of its own in the other stages
            }
        }
    }

    /** The asset cache: acquire, get, release; loads finish at the start of a frame. */
    public AssetCache cache() {
        return made(cache, Stage.BEFORE_INSTANCE);
    }

    /** Assets by path: mounts and importers. */
    public PathAssets paths() {
        return made(paths, Stage.BEFORE_INSTANCE);
    }

    /** The GPU side of assets, for the scenes drawing them. */
    public SceneResources resources() {
        return made(resources, Stage.DEVICE);
    }

    /** The reference to the {@code type} asset at {@code path} ({@code mount://folder/file.ext}). */
    public <T> AssetRef<T> ref(AssetType<T> type, String path) {
        return paths().ref(type, path);
    }

    private static <T> T made(T part, Stage stage) {
        if (part == null) {
            throw new IllegalStateException("The assets plugin makes it in " + stage + ": not yet");
        }
        return part;
    }

    /**
     * Waits for the GPU and for meshes and textures being made ready, frees the GPU side, and closes the cache; before
     * rendering stops, as plugins stop in reverse.
     */
    @Override
    public void stop() {
        if (resources != null) {
            rendering.device().waitIdle();
            long deadline = System.nanoTime() + 10_000_000_000L;
            while (resources.preparingCount() > 0 && System.nanoTime() < deadline) {
                engine.tasks().drainCompleted(); // hands them over, to be freed
                Thread.onSpinWait();
            }
            resources.close();
            textures.close();
            materials.close();
            pool.close();
            resources = null;
        }
        if (cache != null) {
            cache.close();
            cache = null;
        }
    }
}
