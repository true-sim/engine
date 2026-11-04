package com.truesim.engine.ui.scene;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.interaction.InteractionView;
import com.truesim.engine.interaction.InteractionWorld;
import com.truesim.engine.interaction.PointerKind;
import com.truesim.engine.interaction.SceneInteraction;
import com.truesim.engine.interaction.ScenePointer;
import com.truesim.engine.render.Camera;
import com.truesim.engine.settings.Setting;
import com.truesim.scene.core.SceneGraph;
import com.truesim.scene.core.ScenePicker;
import com.truesim.scene.core.SpatialIndex;
import com.truesim.scene.render.PickMeshes;
import com.truesim.ui.controls.Ui;
import com.truesim.ui.core.input.Pointer;
import com.truesim.ui.core.input.UiInput;
import com.truesim.ui.scene.ScenePicking;
import com.truesim.ui.scene.SceneView;
import java.util.Objects;

/**
 * The UI's scene bridge in the engine (design 4.7, UI4, UI5, UI12): the UI runs through a {@link ScenePicking} over the
 * scene's picking model (scene-core's {@link ScenePicker}: {@code Pickable} components and their narrow phases), so
 * world panels, gizmo handles and 3D nodes with an {@code Interactive} take the mouse, touch and XR rays through the
 * same listeners as the screen's elements. Made by {@code UiPlugin.scene(graph, index)}; the plugin then runs it each
 * frame:
 *
 * <ol>
 *   <li>at the frame's start, before the UI's input: the scene's world transforms and the spatial index brought up to
 *       date ({@link #updatesScene}, on by default) and the picker's kept picks forgotten, then the main
 *       {@link SceneView} set from the engine's camera ({@link #camera}: pose in doubles, projection, the UI's
 *       viewport), so this frame's pointers pick what this frame's camera sees, with no frame of delay;</li>
 *   <li>{@link ScenePicking#input} instead of {@code ui.input}: screen layers, gizmo handles, world panels, scene
 *       nodes;</li>
 *   <li>with the engine's {@link SceneInteraction} (application-api.md, scene interaction), the UI's pointers handed
 *       on to it: those the UI took ({@link ScenePicking#claimed}) blocked for the scene, the others with the UI's
 *       own rays, so the picker answers both from one raycast; the scene's listeners and selection then run;</li>
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

    private static final PointerKind[] KINDS = {PointerKind.MOUSE, PointerKind.TOUCH, PointerKind.PEN,
        PointerKind.VIRTUAL_CURSOR, PointerKind.XR_RAY};

    private final Ui ui;
    private final SceneGraph graph;
    private final SpatialIndex index;
    private final ScenePicker picker;
    private final ScenePicking picking;
    private final SceneView main = new SceneView();
    private final PickMeshes pickMeshes = new PickMeshes();
    private final InteractionWorld world; // the engine's scene interaction's, or null
    private InteractionView interactionView;
    private CameraViews camera;
    private boolean updatesScene = true;
    private float far = 100_000;
    private final double[] ray = new double[6];
    private com.truesim.engine.ui.WorldPanelRenderer panels;

    /** The bridge of {@code ui} over {@code graph} and {@code index}, through a picker of its own. */
    @AllowAllocation("one per UI")
    public UiScene(Ui ui, SceneGraph graph, SpatialIndex index) {
        this(ui, new ScenePicker(Objects.requireNonNull(graph, "graph"), Objects.requireNonNull(index, "index")),
            null);
    }

    /**
     * The bridge of {@code ui} picking through {@code scenePicker}, handing its pointers on to {@code interaction}'s
     * world of that picker (null: the UI alone), which it then runs ({@link SceneInteraction#driven}).
     */
    @AllowAllocation("one per UI")
    public UiScene(Ui ui, ScenePicker scenePicker, InteractionWorld interaction) {
        this.ui = Objects.requireNonNull(ui, "ui");
        this.picker = Objects.requireNonNull(scenePicker, "scenePicker");
        this.graph = picker.scene();
        this.index = picker.index();
        this.picking = new ScenePicking(ui, picker);
        this.world = interaction;
        if (interaction != null) {
            interaction.interaction().driven(true);
        }
    }

    /** The picking of the scene, its world panels and its handles. */
    public ScenePicking picking() {
        return picking;
    }

    /** The scene's picker, shared with the engine's scene interaction when there is one. */
    public ScenePicker picker() {
        return picker;
    }

    /** The scene interaction's world it hands its pointers to, or null. */
    public InteractionWorld world() {
        return world;
    }

    /** The UI's view of the main camera, set each frame from {@link #camera}. */
    public SceneView mainView() {
        return main;
    }

    /** The pick meshes of mesh assets, one per mesh (scene-render's). */
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
     * nothing in the scene): each frame's main {@link SceneView} is set from it (and the scene interaction's view of
     * it). Returns this.
     */
    @AllowAllocation("set when the game is set up")
    public UiScene camera(Camera value) {
        camera = value == null ? null : new CameraViews(value, main);
        picking.mainView(value == null ? null : main);
        if (world != null) {
            if (interactionView != null) {
                world.interaction().removeView(interactionView);
                interactionView = null;
            }
            if (value != null) {
                interactionView = world.view(value);
            }
        }
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
        if (world != null) {
            world.updatesScene(on);
        }
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
     * the camera on a {@code width x height} UI, then the picking's input (screen layers, gizmos, panels, scene), then
     * the scene interaction's frame with the UI's pointers.
     */
    public void input(UiInput input, long nanos, float width, float height) {
        if (world != null) {
            world.interaction().beginFrame(nanos); // the scene, its index, the picker's frame
        } else {
            if (updatesScene) {
                graph.update();
                index.update();
            }
            picker.beginFrame();
        }
        if (camera != null) {
            camera.follow(0, 0, width, height, far);
            if (interactionView != null && width > 0 && height > 0) {
                interactionView.rect(0, 0, width, height).reach(far);
            }
        }
        picking.input(input, nanos);
        if (world != null && input != null) {
            handOn(input);
            world.interaction().process();
        }
    }

    /** The UI's pointers as the scene interaction's: blocked where the UI took them, else with the UI's rays. */
    private void handOn(UiInput input) {
        SceneInteraction interaction = world.interaction();
        for (int i = 0; i < input.pointerCount(); i++) {
            Pointer p = input.pointerAt(i);
            int id = p.id();
            ScenePointer sp = interaction.pointer(id, KINDS[p.kind().ordinal()]).buttons(p.buttons())
                .scroll(p.scrollX(), p.scrollY()).modifiers(p.modifiers() & 15).player(p.player())
                .blocked(picking.claimed(id));
            if (p.hasRay()) {
                sp.ray(p.rayOriginX(), p.rayOriginY(), p.rayOriginZ(), p.rayDirectionX(), p.rayDirectionY(),
                    p.rayDirectionZ(), world);
            } else {
                sp.moveTo(p.x(), p.y());
                if (picking.ray(id, ray)) {
                    sp.viewRay(ray[0], ray[1], ray[2], ray[3], ray[4], ray[5], picking.reach(id));
                }
            }
        }
    }

    /**
     * Makes {@code gizmo} follow the scene interaction's selection (application-api.md, selection): each change selects
     * the selected nodes on the gizmo, the primary (the object selected last) active, or clears it. A property panel
     * follows the same way, through {@code SceneSelection.listen}. Needs the scene interaction; returns this.
     */
    @AllowAllocation("set up once")
    public UiScene followSelection(com.truesim.ui.gizmo.Gizmo gizmo) {
        if (world == null) {
            throw new IllegalStateException("No scene interaction to follow the selection of");
        }
        java.util.Objects.requireNonNull(gizmo, "gizmo");
        com.truesim.scene.core.ComponentStore<com.truesim.scene.core.Selected> selected = graph.components()
            .store(com.truesim.scene.core.Selected.TYPE);
        world.interaction().selection().listen(s -> {
            if (selected.size() == 0) {
                gizmo.clear();
                return;
            }
            if (selectedNodes.length < selected.size()) {
                selectedNodes = new long[Math.max(selected.size(), selectedNodes.length * 2)];
            }
            for (int i = 0; i < selected.size(); i++) {
                selectedNodes[i] = selected.node(i);
            }
            long primary = s.primary();
            gizmo.select(selectedNodes, selected.size(), primary != 0 ? primary : selectedNodes[0]);
        });
        return this;
    }

    private long[] selectedNodes = new long[16];

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
     * The frame's second phase, in place of {@code ui.update}: the main view set again from the camera (the game may
     * have moved it in its update: what the UI draws over the scene, gizmos and their depths, is projected by the
     * camera that draws the scene this frame), the UI and its world panels updated, the panels' draw lists handed to
     * their renderer.
     */
    public void update(float width, float height) {
        if (camera != null) {
            camera.follow(0, 0, width, height, far);
        }
        picking.update(width, height);
        if (panels != null) {
            panels.submit();
        }
    }
}
