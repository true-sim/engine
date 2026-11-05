package com.truesim.engine.render.shadows;

import static org.lwjgl.vulkan.VK10.VK_FORMAT_D32_SFLOAT;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Image;
import com.truesim.engine.graph.Key;
import com.truesim.engine.graph.Technique;
import com.truesim.engine.graph.ViewPort;
import com.truesim.engine.render.Camera;
import com.truesim.engine.render.Keys;
import com.truesim.engine.render.Lights;
import com.truesim.engine.render.RenderView;
import com.truesim.engine.render.SceneDraw;
import com.truesim.engine.render.SceneGeometry;
import com.truesim.engine.render.SceneView;
import com.truesim.engine.render.ShadowInputs;
import com.truesim.engine.render.ShadowTable;
import com.truesim.engine.settings.EngineSettings;
import com.truesim.engine.render.SurfaceOutput;
import com.truesim.engine.render.ViewOptions;
import java.util.List;
import java.util.Objects;

/**
 * A directional light's shadows by cascaded shadow maps: the camera's view up to {@code maxDistance} cut into
 * {@code cascades} slices, each with a {@code size} x {@code size} map of its own, so the near ones get the detail.
 * Splits follow the practical scheme (a blend of logarithmic and uniform, {@link #lambda}); each cascade is fitted to
 * the sphere around its slice of the frustum and snapped to whole texels of the world, so its map neither grows nor
 * shimmers as the camera turns and moves. Shading picks a pixel's cascade by its view depth and filters it like any
 * shadow map (the entries' filter: PCF today).
 *
 * <p>Each cascade is a {@link SceneDraw} of a view of its own, made with the technique and drawing the casters only,
 * culled with the camera's in one pass.
 * Call {@link #update} every frame once the camera is set, before the frame records: it fits the cascades.
 *
 * <pre>{@code
 * CascadedShadowMaps sunShadows = new CascadedShadowMaps(geometry, lights, 0, 0, camera, 3, 2048,
 *     ViewOptions.shadow(1 << 16, 1 << 20), 300);
 * graph.add(sunShadows);
 * // per frame
 * sunShadows.update();                           // from the camera's world position: cascades snap in world space
 * }</pre>
 */
public final class CascadedShadowMaps implements Technique, AutoCloseable {

    private final SceneGeometry geometry;
    private final Lights lights;
    private final int light;
    private final int firstEntry;
    private final Camera camera;
    private final int cascades;
    private final int size;
    private final RenderView[] views;
    private final float maxDistance;
    private final Camera[] cameras;
    private final float[] splits;
    private final float[] biases;
    private final float[] normalOffsets;
    private final double[] direction = new double[3];
    private final CascadeFit fit;
    private Float lambda; // these three, or null: as the graph's settings say
    private Float biasTexels;
    private Float casters;
    private float margin; // world units each cascade reaches beyond the camera's view
    private final ViewPort port; // the view's, whose graph's settings it reads; or null: its own settings
    private EngineSettings settings = new EngineSettings(); // without a view: defaults, or as set

    /**
     * Shadows of directional light {@code light} of {@code lights} in shadow table entries {@code firstEntry} on, for
     * the view of {@code camera} up to {@code maxDistance}: {@code cascades} maps of {@code size} x {@code size},
     * drawn as views of {@code geometry} made with {@code options} (casters only, whatever the options say).
     */
    @AllowAllocation("created rarely")
    public CascadedShadowMaps(SceneGeometry geometry, Lights lights, int light, int firstEntry, Camera camera,
        int cascades, int size, ViewOptions options, float maxDistance) {
        this(geometry, lights, light, firstEntry, camera, cascades, size, options, maxDistance, null);
    }

    @AllowAllocation("created rarely")
    private CascadedShadowMaps(SceneGeometry geometry, Lights lights, int light, int firstEntry, Camera camera,
        int cascades, int size, ViewOptions options, float maxDistance, ViewPort port) {
        this.port = port;
        this.geometry = Objects.requireNonNull(geometry, "geometry");
        this.lights = Objects.requireNonNull(lights, "lights");
        this.camera = Objects.requireNonNull(camera, "camera");
        Objects.requireNonNull(options, "options");
        if (cascades < 1 || size < 1 || !(maxDistance > 0)) {
            throw new IllegalArgumentException("Cascades need a count, a size and a distance: " + cascades + ", "
                + size + ", " + maxDistance);
        }
        this.light = light;
        this.firstEntry = firstEntry;
        this.cascades = cascades;
        this.size = size;
        this.views = new RenderView[cascades];
        for (int i = 0; i < cascades; i++) {
            views[i] = geometry.createView(options.withCastersOnly(true));
        }
        this.maxDistance = maxDistance;
        this.cameras = new Camera[cascades];
        for (int i = 0; i < cascades; i++) {
            cameras[i] = new Camera();
        }
        this.splits = new float[cascades + 1];
        this.fit = new CascadeFit(cascades);
        this.biases = new float[cascades];
        this.normalOffsets = new float[cascades];
    }

    /**
     * Like {@link #CascadedShadowMaps(SceneGeometry, Lights, int, int, Camera, int, int, ViewOptions, float)}, for
     * {@code view}: its camera, its own entries (its view's {@link Lights#shadowTable shadow table}), and its cascades'
     * views closed with it: the technique is owned by its port. One per view, e.g. from {@code SceneViews.addPerView};
     * each needs its {@link #update} every frame.
     */
    @AllowAllocation("created rarely")
    public CascadedShadowMaps(SceneGeometry geometry, Lights lights, int light, int firstEntry, SceneView view,
        int cascades, int size, ViewOptions options, float maxDistance) {
        this(geometry, lights, light, firstEntry, view.camera(), cascades, size, options, maxDistance, view.port());
        view.port().own(this);
    }

    /** Closes the cascades' views: their buffers go once no frame in flight reads them. */
    @Override
    public void close() {
        for (RenderView cascade : views) {
            cascade.close();
        }
    }

    /**
     * The settings it reads without a view of its own (made with a camera): its graph's, say. Read at each
     * {@link #update}.
     */
    public CascadedShadowMaps settings(EngineSettings settings) {
        this.settings = java.util.Objects.requireNonNull(settings, "settings");
        return this;
    }

    /** How the splits blend logarithmic (1) and uniform (0) spacing, overriding {@link ShadowSettings#CASCADE_LAMBDA}. */
    @AllowAllocation("set up rarely")
    public CascadedShadowMaps lambda(float lambda) {
        this.lambda = lambda;
        return this;
    }

    /**
     * The bias, in texels (overriding {@link ShadowSettings#CASCADE_BIAS}): the lookup moves that far along the surface's normal, and a third of it
     * deep; both scale with each cascade's texel size.
     */
    @AllowAllocation("set up rarely")
    public CascadedShadowMaps bias(float texels) {
        this.biasTexels = texels;
        return this;
    }

    /** How far behind each cascade's slice casters still cast into it, overriding {@link ShadowSettings#CASCADE_CASTERS}. */
    @AllowAllocation("set up rarely")
    public CascadedShadowMaps casters(float distance) {
        this.casters = distance;
        return this;
    }

    /**
     * Makes each cascade reach {@code units} beyond the camera's view: for cascades {@link #share shared} by views near
     * the camera, such as a headset's eyes (fitted to one eye, a margin of the distance between them covers the other).
     */
    public CascadedShadowMaps margin(float units) {
        if (units < 0) {
            throw new IllegalArgumentException("A margin is not negative: " + units);
        }
        this.margin = units;
        return this;
    }

    /** Cascade {@code i}'s depth bias, in its map's depth units, as of the last {@link #update}. */
    public float depthBias(int i) {
        return biases[i];
    }

    /** Where cascade {@code i} ends, in view depth (cascade 0 starts at the camera's near plane). */
    public float split(int i) {
        return splits[Objects.checkIndex(i, cascades) + 1];
    }

    /** The view cascade {@code i} is drawn as. */
    public RenderView view(int i) {
        return views[Objects.checkIndex(i, cascades)];
    }

    /** The light camera of cascade {@code i}, as last fitted. */
    public Camera camera(int i) {
        return cameras[Objects.checkIndex(i, cascades)];
    }

    /**
     * Fits the cascades to the camera's current view, from its world position, and the light's direction: each
     * cascade's light camera at a world position of its own, snapped to world texels. Call it every frame after
     * setting the camera, before the frame records. Allocates nothing.
     */
    public void update() {
        EngineSettings settings = port != null ? port.settings() : this.settings;
        float lambda = this.lambda != null ? this.lambda : settings.get(ShadowSettings.CASCADE_LAMBDA);
        float biasTexels = this.biasTexels != null ? this.biasTexels : settings.get(ShadowSettings.CASCADE_BIAS);
        // Casters coarser than a pixel of error sit up to that many texels off the surfaces they shadow, towards the
        // light too: so much more depth bias, so they do not shadow those surfaces in spots.
        float casterError = Math.max(0, settings.get(ShadowSettings.CASTER_DETAIL) - 1);
        float casters = this.casters != null ? this.casters : settings.get(ShadowSettings.CASCADE_CASTERS);
        lights.direction(light, direction);
        fit.fit(camera, cascades, maxDistance, lambda, margin, direction[0], direction[1], direction[2]);
        splits[0] = camera.viewProjection()[14];
        for (int i = 0; i < cascades; i++) {
            splits[i + 1] = fit.split(i);
            double texel = fit.place(i, size, casters, cameras[i]);
            double depth = 2 * fit.radius(i) + casters;
            biases[i] = (float) ((biasTexels / 3 + casterError) * texel / depth);
            normalOffsets[i] = (float) (biasTexels * texel);
        }
    }

    /**
     * These cascades for another view (a headset's other eye): add it to that view, this technique to the first. The
     * maps draw once a frame; each view's shadow table points at them. Each call makes a technique for one view more.
     */
    public Technique share() {
        return new Technique() {
            @Override
            public List<Key<?>> provides() {
                return List.of();
            }

            @Override
            public List<Key<?>> modifies() {
                return List.of(Keys.LIGHT_LIST);
            }

            @Override
            public void build(GraphBuilder g) {
                CascadedShadowMaps.this.build(g);
            }

            @Override
            public String toString() {
                return "shared " + CascadedShadowMaps.this;
            }
        };
    }

    @Override
    public List<Key<?>> provides() {
        return List.of();
    }

    @Override
    public List<Key<?>> modifies() {
        return List.of(Keys.LIGHT_LIST);
    }

    @Override
    @AllowAllocation("building the graph, when it changes")
    public void build(GraphBuilder g) {
        g.modify(Keys.LIGHT_LIST);
        ShadowInputs.Set inputs = g.request(new ShadowInputs(g.view()));
        ShadowTable table = lights.shadowTable(g.view()); // its view's entries: its cascades fit its camera
        Image[] maps = new Image[cascades];
        for (int i = 0; i < cascades; i++) {
            maps[i] = g.request(new SceneDraw(geometry, views[i], cameras[i], SurfaceOutput.depth(),
                VK_FORMAT_D32_SFLOAT, size, size, false)).depth();
            inputs.sample(maps[i]);
        }
        inputs.setup(ctx -> {
            for (int i = 0; i < cascades; i++) {
                table.shadowMap(firstEntry + i, cameras[i], ctx.sampled(maps[i]), size, biases[i], splits[i + 1],
                    normalOffsets[i]);
            }
            table.light(light, firstEntry, cascades); // in its view's table: other views may shadow it otherwise
        });
    }
}
