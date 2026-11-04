package com.truesim.engine.ui.scene;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.within;
import static org.assertj.core.api.Assumptions.assumeThat;

import com.truesim.common.state.ChangeClock;
import com.truesim.engine.app.AppConfig;
import com.truesim.engine.app.Application;
import com.truesim.engine.app.Engine;
import com.truesim.engine.app.Plugin;
import com.truesim.engine.app.PluginKey;
import com.truesim.engine.app.Stage;
import com.truesim.engine.app.Startup;
import com.truesim.engine.app.StartupFailure;
import com.truesim.engine.graph.Rendering;
import com.truesim.engine.input.Input;
import com.truesim.engine.render.Camera;
import com.truesim.engine.ui.UiPlugin;
import com.truesim.scene.assets.mesh.MeshData;
import com.truesim.scene.assets.mesh.VertexFormat;
import com.truesim.scene.core.Mobility;
import com.truesim.scene.core.NodeHandle;
import com.truesim.scene.core.SceneGraph;
import com.truesim.scene.core.SpatialIndex;
import com.truesim.scene.meshbuild.MeshBuilder;
import com.truesim.ui.controls.Ui;
import com.truesim.ui.core.event.EventType;
import com.truesim.ui.core.input.Pointer;
import com.truesim.ui.scene.Interactive;
import com.truesim.ui.scene.PickMesh;
import com.truesim.ui.style.tss.Tss;
import com.truesim.ui.testkit.SyntheticInput;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The scene bridge in a running engine: the mouse hovers, clicks and drags 3D nodes of a real scene through the
 * engine's camera, in the frame of the input, with mesh narrow phases from mesh assets.
 */
public class UiSceneTest {

    public static final int WIDTH = 320;
    public static final int HEIGHT = 200;

    /** A view for the UI, as a game adds its views. */
    static final class Views implements Plugin {
        static final PluginKey<Views> KEY = PluginKey.of("test.views", Views.class);

        @Override
        public PluginKey<Views> key() {
            return KEY;
        }

        @Override
        public List<PluginKey<?>> requires() {
            return List.of(Rendering.KEY);
        }

        @Override
        public void start(Stage stage, Startup startup) {
            if (stage == Stage.GRAPH) {
                startup.get(Rendering.KEY).graph().addView("main").size(WIDTH, HEIGHT);
            }
        }
    }

    /** An engine running {@code plugin} headless. */
    public static Engine engine(UiPlugin plugin, Application game) {
        try {
            return Engine.create(new Application() {
                @Override
                public void configure(AppConfig app) {
                    app.use(new Rendering().headless(true), new Input(), new Views(), plugin);
                }

                @Override
                public void update(com.truesim.engine.app.Frame frame) {
                    game.update(frame);
                }
            });
        } catch (StartupFailure e) {
            assumeThat(e).as("no Vulkan available: " + e.reason()).isNull();
            throw e;
        }
    }

    /** A text system with the bundled fonts: the same text on every machine. */
    public static com.truesim.ui.text.TextSystem text() {
        com.truesim.ui.text.natives.NativeTextBackend backend = new com.truesim.ui.text.natives.NativeTextBackend();
        return new com.truesim.ui.text.TextSystem(com.truesim.ui.fonts.cjk.BundledCjkFonts.registry(backend),
            backend.newShaper(), new com.truesim.ui.text.GlyphAtlas(new com.truesim.ui.core.draw.UiTextures(),
                backend.msdfGenerator(), backend.newRasterizer(), null, null, new com.truesim.ui.text.AtlasSettings()));
    }

    /** A triangle (the lower left half of a 2 x 2 square about the origin, facing +Z) as a mesh asset. */
    static MeshData halfSquare() {
        float[] positions = {-1, -1, 0, 1, -1, 0, -1, 1, 0};
        return new MeshBuilder(VertexFormat.POSITIONS_ONLY, 3).positions(positions).submesh(new int[] {0, 1, 2})
            .buildHierarchy();
    }

    /** The UI pixel world point {@code x, y, 0} shows at, seen from (0, 0, 10) down -Z with 60 degrees vertically. */
    static float[] screen(double x, double y) {
        double halfHeight = 10 * Math.tan(Math.toRadians(30));
        double halfWidth = halfHeight * WIDTH / HEIGHT;
        return new float[] {(float) (WIDTH / 2.0 * (1 + x / halfWidth)), (float) (HEIGHT / 2.0 * (1 - y / halfHeight))};
    }

    @Test
    void theMouseHoversClicksAndDragsANodeOfARealSceneThroughTheEnginesCamera() {
        SyntheticInput mouse = new SyntheticInput();
        UiPlugin plugin = new UiPlugin(textures -> new Ui(Tss.defaultTheme(), null)).view("main").pointers(mouse);
        List<String> seen = new ArrayList<>();
        long[] frames = {0};
        try (Engine engine = engine(plugin, new Application() {
            @Override
            public void update(com.truesim.engine.app.Frame frame) {
                frames[0]++;
            }
        })) {
            assertThat(engine.registry().find("ui.scene.far")).isNotNull();
            assertThat(plugin.scene()).isNull();
            SceneGraph graph = new SceneGraph(new ChangeClock());
            SpatialIndex index = new SpatialIndex(graph);
            Camera camera = new Camera().position(0, 0, 10).set(0, 0, -1, 0, 1, 0, Math.toRadians(60), WIDTH, HEIGHT,
                0.1);
            UiScene scene = plugin.scene(graph, index).camera(camera);
            assertThat(plugin.scene()).isSameAs(scene);
            assertThatIllegalStateException().isThrownBy(() -> plugin.scene(graph, index));
            // a crate at the origin, and a wall behind it
            long crate = graph.create();
            graph.setLocalBounds(crate, -1, -1, -0.1, 1, 1, 0.1);
            graph.setMobility(crate, Mobility.DYNAMIC);
            long wall = graph.create();
            graph.setLocalBounds(wall, -20, -20, -0.5, 20, 20, 0.5);
            graph.setTranslation(wall, 0, 0, -5);
            MeshData mesh = halfSquare();
            PickMesh pick = scene.pickMeshes().of(mesh);
            assertThat(scene.pickMeshes().of(mesh)).as("one per mesh asset").isSameAs(pick);
            assertThat(pick.triangleCount()).isEqualTo(1);
            long[] clickedAt = {-1};
            double[] hit = new double[3];
            scene.picking().add(crate, Interactive.create().mesh(pick)
                .on(EventType.CLICK, e -> {
                    clickedAt[0] = frames[0] + 1; // before this frame's update
                    scene.picking().hitPoint(e, hit);
                })
                .on(EventType.DRAG_START.bit() | EventType.DRAG.bit() | EventType.DRAG_END.bit(), e -> {
                    seen.add(e.type().name());
                    double[] ray = new double[6];
                    scene.picking().ray(e.pointerId(), ray);
                    double t = -ray[2] / ray[5]; // onto the plane z = 0
                    graph.setTranslation(crate, ray[0] + ray[3] * t, ray[1] + ray[4] * t, 0);
                }));
            scene.picking().add(wall, Interactive.of(EventType.CLICK, e -> seen.add("wall")));
            engine.step();
            // hovering the triangle's middle picks the crate
            float[] p = screen(-0.4, -0.4);
            mouse.mouse(p[0], p[1], 0);
            engine.step();
            assertThat(scene.picking().hovered(crate)).isTrue();
            assertThat(scene.mainView().viewportWidth()).isEqualTo(WIDTH);
            // its box but not its triangle: the mesh lets the ray through to the wall
            float[] q = screen(0.6, 0.6);
            mouse.mouse(q[0], q[1], 0);
            engine.step();
            assertThat(scene.picking().hovered(crate)).isFalse();
            assertThat(scene.picking().hovered(wall)).isTrue();
            mouse.mouse(q[0], q[1], Pointer.PRIMARY);
            engine.step();
            mouse.mouse(q[0], q[1], 0);
            engine.step();
            assertThat(seen).containsExactly("wall");
            // a click on the triangle, seen in the frame of the release
            mouse.mouse(p[0], p[1], Pointer.PRIMARY);
            engine.step();
            mouse.mouse(p[0], p[1], 0);
            engine.step();
            assertThat(clickedAt[0]).isEqualTo(frames[0]);
            assertThat(hit[0]).isCloseTo(-0.4, within(1e-3));
            assertThat(hit[1]).isCloseTo(-0.4, within(1e-3));
            assertThat(hit[2]).isCloseTo(0, within(1e-6));
            // a drag moves it along the plane under the mouse
            mouse.mouse(p[0], p[1], Pointer.PRIMARY);
            engine.step();
            float[] to = screen(3, 1);
            for (int i = 1; i <= 8; i++) {
                mouse.mouse(p[0] + (to[0] - p[0]) * i / 8, p[1] + (to[1] - p[1]) * i / 8, Pointer.PRIMARY);
                engine.step();
            }
            mouse.mouse(to[0], to[1], 0);
            engine.step();
            assertThat(seen).contains("DRAG_START", "DRAG", "DRAG_END");
            graph.update();
            double[] at = new double[3];
            worldPosition(graph, crate, at);
            assertThat(at[0]).isCloseTo(3, within(1e-3));
            assertThat(at[1]).isCloseTo(1, within(1e-3));
            // the camera moves: the next frame's rays go through it
            camera.position(5, 0, 10);
            mouse.mouse(WIDTH / 2f, HEIGHT / 2f, 0);
            engine.step();
            assertThat(scene.mainView().x()).isEqualTo(5);
            assertThat(scene.picking().hovered(wall)).isTrue();
            scene.picking().remove(crate);
            scene.picking().remove(wall);
            assertThat(NodeHandle.NONE).isNotEqualTo(crate);
        }
    }

    private static void worldPosition(SceneGraph graph, long node, double[] out) {
        java.lang.foreign.MemorySegment s = graph.worldTransforms().segment();
        long o = NodeHandle.index(node) * graph.worldTransforms().stride();
        for (int a = 0; a < 3; a++) {
            out[a] = s.get(java.lang.foreign.ValueLayout.JAVA_DOUBLE, o + 8L * a);
        }
    }

    @Test
    void theMainViewFollowsTheCameraInDoublesFarFromTheOrigin() {
        Camera camera = new Camera().position(1e7, 2, -3e6).set(1, 0, 0, 0, 1, 0, 1.2, 640, 480, 0.5);
        com.truesim.ui.scene.SceneView view = new com.truesim.ui.scene.SceneView();
        CameraViews views = new CameraViews(camera, view);
        views.follow(0, 0, 640, 480, 5000);
        long version = view.version();
        views.follow(0, 0, 640, 480, 5000);
        assertThat(view.version()).as("nothing moved").isEqualTo(version);
        assertThat(view.x()).isEqualTo(1e7);
        assertThat(view.fovY()).isEqualTo(1.2);
        assertThat(view.near()).isEqualTo(0.5);
        assertThat(view.far()).isEqualTo(5000);
        assertThat(view.forward(0)).isCloseTo(1, within(1e-12));
        float[] out = new float[3];
        assertThat(view.project(1e7 + 10, 2, -3e6, out)).isTrue();
        assertThat(out[0]).isCloseTo(320, within(1e-3f));
        assertThat(out[1]).isCloseTo(240, within(1e-3f));
        camera.setOrthographic(0, 0, 0, 0, -1, 0, 0, 0, -1, 8, 6, 100, 640, 480);
        views.follow(0, 0, 640, 480, 5000);
        assertThat(view.isOrthographic()).isTrue();
        assertThat(view.unitsPerPixel(1) * 480).isCloseTo(12, within(1e-9));
        version = view.version();
        views.follow(0, 0, 640, 480, 5000);
        assertThat(view.version()).isEqualTo(version);
    }

    @Test
    void theBridgeWithoutACameraOrSceneUpdatesPicksNothingOnTheScreenAndKeepsItsParts() {
        Ui ui = new Ui(Tss.defaultTheme(), null);
        SceneGraph graph = new SceneGraph(new ChangeClock());
        SpatialIndex index = new SpatialIndex(graph);
        UiScene scene = new UiScene(ui, graph, index, UiScene.INTERACTIVE);
        assertThat(scene.ui()).isSameAs(ui);
        assertThat(scene.graph()).isSameAs(graph);
        assertThat(scene.index()).isSameAs(index);
        assertThat(scene.camera()).isNull();
        assertThat(scene.updatesScene()).isTrue();
        assertThat(scene.updatesScene(false).updatesScene()).isFalse();
        assertThat(scene.panels()).isNull();
        Camera camera = new Camera().position(0, 0, 10).set(0, 0, -1, 0, 1, 0, 1.0, 320, 200, 0.1);
        assertThat(scene.camera(camera).camera()).isSameAs(camera);
        assertThat(scene.picking().mainView()).isSameAs(scene.mainView());
        scene.far(50);
        com.truesim.ui.core.input.UiInput input = new com.truesim.ui.core.input.UiInput(4);
        input.beginFrame();
        scene.input(input, 1, 0, 0); // no size yet: the viewport is kept
        scene.input(input, 2, 320, 200);
        scene.update(320, 200);
        assertThat(scene.mainView().far()).isEqualTo(50);
        assertThat(scene.camera(null).camera()).isNull();
        assertThat(scene.picking().mainView()).isNull();
        scene.input(input, 3, 320, 200);
        assertThat(scene.pickMeshes().count()).isZero();
        MeshData mesh = halfSquare();
        scene.pickMeshes().of(mesh);
        assertThat(scene.pickMeshes().count()).isEqualTo(1);
        scene.pickMeshes().forget(mesh);
        assertThat(scene.pickMeshes().count()).isZero();
    }
}
