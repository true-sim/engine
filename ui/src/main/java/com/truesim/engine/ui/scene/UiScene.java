package com.truesim.engine.ui.scene;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.render.Camera;
import com.truesim.engine.settings.Setting;
import com.truesim.scene.core.SceneGraph;
import com.truesim.scene.core.SceneLayer;
import com.truesim.scene.core.SpatialIndex;
import com.truesim.ui.controls.Ui;
import com.truesim.ui.core.input.UiInput;
import com.truesim.ui.scene.ScenePicking;
import com.truesim.ui.scene.SceneView;
import java.util.Objects;

/**
 * The UI's scene bridge in the engine (design 4.7, UI4, UI5, UI12): the UI runs through a {@link ScenePicking} over the
 * game's {@link SceneGraph} and {@link SpatialIndex}, so 3D nodes with an {@code Interactive}, world panels and gizmo
 * handles take the mouse, touch and XR rays through the same listeners as the screen's elements. Made by
 * {@code UiPlugin.scene(graph, index)}; the plugin then runs it each frame:
 *
 * <ol>
 *   <li>at the frame's start, before the UI's input: the scene's world transforms and the spatial index brought up to
 *       date ({@link #updatesScene}, on by default), then the main {@link SceneView} set from the engine's camera
 *       ({@link #camera}: pose in doubles, projection, the UI's viewport), so this frame's pointers pick what this
 *       frame's camera sees, with no frame of delay;</li>
 *   <li>{@link ScenePicking#input} instead of {@code ui.input};</li>
 *   <li>after the game's update, {@link ScenePicking#update} (world panels' UIs too) instead of {@code ui.update}.</li>
 * </ol>
 *
 * <pre>{@code
 * UiScene scene = engine.get(UiPlugin.KEY).scene(graph, index).camera(camera);
 * scene.picking().add(door, Interactive.of(EventType.CLICK, e -> open())
 *     .mesh(scene.pickMeshes().of(doorMesh)));
 * }</pre>
 *
 * <p>Per frame it allocates nothing.
 */
public final class UiScene {

    /** How far a screen pointer's ray reaches into the scene (the engine's cameras have no far plane). */
    public static final Setting<Float> FAR = Setting.decimal("ui.scene.far", 100_000, 1, 1e9f,
        "metres a screen pointer's ray reaches into the scene, picking through the main camera");
    /** The layer interactive nodes go on unless another is given. */
    public static final SceneLayer INTERACTIVE = SceneLayer.of("interactive", 30);

    private final Ui ui;
    private final SceneGraph graph;
    private final SpatialIndex index;
    private final ScenePicking picking;
    private final SceneView main = new SceneView();
    private final PickMeshes pickMeshes = new PickMeshes();
    private CameraViews camera;
    private boolean updatesScene = true;
    private float far = 100_000;
    private com.truesim.engine.ui.WorldPanelRenderer panels;

    /** The bridge of {@code ui} over {@code graph} and {@code index}, interactive nodes on {@code layer}. */
    @AllowAllocation("one per UI")
    public UiScene(Ui ui, SceneGraph graph, SpatialIndex index, SceneLayer layer) {
        this.ui = Objects.requireNonNull(ui, "ui");
        this.graph = Objects.requireNonNull(graph, "graph");
        this.index = Objects.requireNonNull(index, "index");
        this.picking = new ScenePicking(ui, graph, index, Objects.requireNonNull(layer, "layer"));
    }

    /** The picking of the scene, its world panels and its handles. */
    public ScenePicking picking() {
        return picking;
    }

    /** The UI's view of the main camera, set each frame from {@link #camera}. */
    public SceneView mainView() {
        return main;
    }

    /** The pick meshes of mesh assets, one per mesh. */
    public PickMeshes pickMeshes() {
        return pickMeshes;
    }

    /** The scene. */
    public SceneGraph graph() {
        return graph;
    }

    /** Its spatial index. */
    public SpatialIndex index() {
        return index;
    }

    /** The UI. */
    public Ui ui() {
        return ui;
    }

    /**
     * Follows {@code value}, the engine camera the main view draws through (null: none, screen pointers then pick
     * nothing in the scene): each frame's main {@link SceneView} is set from it. Returns this.
     */
    @AllowAllocation("set when the game is set up")
    public UiScene camera(Camera value) {
        camera = value == null ? null : new CameraViews(value, main);
        picking.mainView(value == null ? null : main);
        return this;
    }

    /** The camera followed, or null. */
    public Camera camera() {
        return camera == null ? null : camera.camera();
    }

    /**
     * Whether the bridge updates the scene and its index before the UI's input (default true); false where the game
     * already does at the frame's start. Returns this.
     */
    public UiScene updatesScene(boolean on) {
        updatesScene = on;
        return this;
    }

    /** Whether it updates the scene before the input. */
    public boolean updatesScene() {
        return updatesScene;
    }

    /** Sets how far screen rays reach ({@link #FAR}, which the plugin keeps it at). */
    public void far(float metres) {
        far = metres;
    }

    /**
     * The frame's first phase, in place of {@code ui.input}: the scene and its index updated, the main view set from
     * the camera on a {@code width x height} UI, then the picking's input (screen layers, gizmos, panels, scene).
     */
    public void input(UiInput input, long nanos, float width, float height) {
        if (updatesScene) {
            graph.update();
            index.update();
        }
        if (camera != null) {
            camera.follow(0, 0, width, height, far);
        }
        picking.input(input, nanos);
    }

    /**
     * Draws the world panels through {@code renderer} (null: the game draws them): each frame's {@link #update} hands
     * it the panels' UIs after updating them. Returns this.
     */
    public UiScene panels(com.truesim.engine.ui.WorldPanelRenderer renderer) {
        panels = renderer;
        return this;
    }

    /** The world panels' renderer, or null. */
    public com.truesim.engine.ui.WorldPanelRenderer panels() {
        return panels;
    }

    /**
     * The frame's second phase, in place of {@code ui.update}: the UI and its world panels updated, the panels' draw
     * lists handed to their renderer.
     */
    public void update(float width, float height) {
        picking.update(width, height);
        if (panels != null) {
            panels.submit();
        }
    }
}
