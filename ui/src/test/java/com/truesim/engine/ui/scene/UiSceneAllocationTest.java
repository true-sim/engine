package com.truesim.engine.ui.scene;

import static com.truesim.testing.AllocationAssertions.assertAllocationFree;
import static org.assertj.core.api.Assertions.assertThat;

import com.truesim.common.state.ChangeClock;
import com.truesim.engine.render.Camera;
import com.truesim.scene.core.Mobility;
import com.truesim.scene.core.SceneGraph;
import com.truesim.scene.core.SpatialIndex;
import com.truesim.ui.controls.Ui;
import com.truesim.ui.core.event.EventType;
import com.truesim.ui.core.input.Pointer;
import com.truesim.ui.core.input.UiInput;
import com.truesim.ui.scene.Interactive;
import com.truesim.ui.style.tss.Tss;
import com.truesim.ui.testkit.SyntheticInput;
import org.junit.jupiter.api.Test;

/** The scene bridge's frames allocate nothing: the camera followed, nodes hovered and pressed, the camera moving. */
class UiSceneAllocationTest {

    @Test
    void steadyFramesOfTheSceneBridgeAllocateNothing() {
        Ui ui = new Ui(Tss.defaultTheme(), null);
        SceneGraph graph = new SceneGraph(new ChangeClock());
        SpatialIndex index = new SpatialIndex(graph);
        Camera camera = new Camera().position(0, 0, 10).set(0, 0, -1, 0, 1, 0, Math.toRadians(60), 320, 200, 0.1);
        UiScene scene = new UiScene(ui, graph, index).camera(camera);
        int[] clicks = {0};
        for (int i = 0; i < 50; i++) {
            long node = graph.create();
            graph.setLocalBounds(node, -0.4, -0.4, -0.4, 0.4, 0.4, 0.4);
            graph.setTranslation(node, (i % 10) - 4.5, (i / 10) - 2, 0);
            graph.setMobility(node, Mobility.DYNAMIC);
            scene.picking().add(node, Interactive.of(EventType.CLICK, e -> clicks[0]++));
        }
        SyntheticInput mouse = new SyntheticInput();
        UiInput input = new UiInput(8);
        long[] nanos = {1_000_000_000L};
        int[] frame = {0};
        Runnable step = () -> {
            int f = frame[0]++;
            float x = 40 + ((f / 16) * 7 % 240); // still while pressed: clicks
            mouse.mouse(x, 100, f % 16 >= 4 && f % 16 < 12 ? Pointer.PRIMARY : 0);
            camera.position(0.001 * (f % 100), 0, 10);
            input.beginFrame();
            mouse.poll(input);
            nanos[0] += 16_000_000L;
            scene.input(input, nanos[0], 320, 200);
            scene.update(320, 200);
        };
        for (int i = 0; i < 2000; i++) {
            step.run();
        }
        assertAllocationFree(step, 30_000, 5_000, 10);
        assertThat(clicks[0]).isPositive();
    }
}
