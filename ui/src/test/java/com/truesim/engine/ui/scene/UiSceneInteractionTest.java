package com.truesim.engine.ui.scene;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import com.truesim.common.state.ChangeClock;
import com.truesim.engine.app.AppConfig;
import com.truesim.engine.app.Application;
import com.truesim.engine.app.Engine;
import com.truesim.engine.app.StartupFailure;
import com.truesim.engine.graph.Rendering;
import com.truesim.engine.input.Input;
import com.truesim.engine.interaction.InteractionWorld;
import com.truesim.engine.interaction.SceneEventType;
import com.truesim.engine.interaction.SceneInteraction;
import com.truesim.engine.render.Camera;
import com.truesim.engine.ui.UiPlugin;
import com.truesim.scene.core.Pickable;
import com.truesim.scene.core.SceneGraph;
import com.truesim.scene.core.Selected;
import com.truesim.scene.core.SpatialIndex;
import com.truesim.ui.controls.Ui;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.event.EventType;
import com.truesim.ui.core.input.Pointer;
import com.truesim.ui.scene.Interactive;
import com.truesim.ui.style.tss.Tss;
import com.truesim.ui.testkit.SyntheticInput;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The UI and the engine's scene interaction together: the UI picks first and hands the pointers it did not take on,
 * with its rays, so a scene node's engine listeners and the selection hear the scene and the scene picker casts each
 * ray once a frame.
 */
class UiSceneInteractionTest {

    @Test
    void theUiHandsTheSceneThePointersItDidNotTake() {
        SyntheticInput mouse = new SyntheticInput();
        UiPlugin plugin = new UiPlugin(textures -> new Ui(Tss.defaultTheme(), null)).view("main").pointers(mouse);
        SceneInteraction interaction = new SceneInteraction();
        Engine engine;
        try {
            engine = Engine.create(new Application() {
                @Override
                public void configure(AppConfig app) {
                    app.use(new Rendering().headless(true), new Input(), new UiSceneTest.Views(), plugin, interaction);
                }
            });
        } catch (StartupFailure e) {
            assumeThat(e).as("no Vulkan available: " + e.reason()).isNull();
            throw e;
        }
        try (engine) {
            SceneGraph graph = new SceneGraph(new ChangeClock());
            SpatialIndex index = new SpatialIndex(graph);
            Camera camera = new Camera().position(0, 0, 10).set(0, 0, -1, 0, 1, 0, Math.toRadians(60),
                UiSceneTest.WIDTH, UiSceneTest.HEIGHT, 0.1);
            UiScene scene = plugin.scene(graph, index).camera(camera);
            InteractionWorld world = scene.world();
            assertThat(world).isSameAs(interaction.worldOf(graph));
            assertThat(scene.picker()).isSameAs(world.picker());
            assertThat(interaction.driven()).isTrue();
            long crate = graph.create();
            graph.setLocalBounds(crate, -1, -1, -0.1, 1, 1, 0.1);
            graph.components().set(crate, Pickable.TYPE, Pickable.bounds());
            List<String> seen = new ArrayList<>();
            world.on(crate, SceneEventType.ALL_MASK, e -> seen.add("scene " + e.type()));
            scene.picking().add(crate, Interactive.of(EventType.CLICK, e -> seen.add("ui CLICK")));
            interaction.selection().enabled(true);
            com.truesim.ui.gizmo.Gizmo gizmo = new com.truesim.ui.gizmo.Gizmo(scene.picking(), scene.mainView(), null);
            scene.followSelection(gizmo);
            float[] at = UiSceneTest.screen(0, 0);
            mouse.mouse(at[0], at[1], 0);
            engine.step();
            long raycasts = world.picker().raycasts();
            mouse.mouse(at[0], at[1], Pointer.PRIMARY);
            engine.step();
            mouse.mouse(at[0], at[1], 0);
            engine.step();
            assertThat(seen).contains("scene ENTER", "scene PRESS", "scene CLICK", "ui CLICK");
            assertThat(graph.components().has(crate, Selected.TYPE)).as("selected by the click").isTrue();
            assertThat(gizmo.active()).as("the gizmo follows the selection").isEqualTo(crate);
            interaction.selection().selectNone(world);
            assertThat(gizmo.count()).isZero();
            assertThat(world.picker().cacheHits()).as("the UI's and the scene's picks: one raycast").isPositive();
            assertThat(world.picker().raycasts() - raycasts).as("two frames, two casts per frame at most")
                .isLessThanOrEqualTo(4);
            // a screen element over the crate takes the mouse: the scene sees it leave
            seen.clear();
            Element panel = plugin.ui().panel(null);
            plugin.ui().layer("overlay").root().add(panel);
            plugin.ui().on(panel, EventType.PRESS, e -> { });
            com.truesim.ui.scene.SceneRig.place(plugin.ui(), panel, 0, 0, UiSceneTest.WIDTH, UiSceneTest.HEIGHT);
            engine.step();
            mouse.mouse(at[0] + 1, at[1], 0);
            engine.step();
            assertThat(seen).contains("scene EXIT");
            assertThat(interaction.pointer(0).isBlocked()).isTrue();
        }
    }
}
