package com.truesim.engine.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.truesim.common.state.ChangeClock;
import com.truesim.engine.ui.scene.DepthOcclusion;
import com.truesim.engine.ui.scene.UiScene;
import com.truesim.engine.ui.scene.UiSceneTest;
import com.truesim.scene.core.SceneGraph;
import com.truesim.scene.core.SpatialIndex;
import com.truesim.ui.controls.Ui;
import com.truesim.ui.core.input.UiInput;
import com.truesim.ui.gizmo.Gizmo;
import com.truesim.ui.gizmo.GizmoMode;
import com.truesim.ui.style.tss.Tss;
import org.junit.jupiter.api.Test;

/**
 * Gizmo occlusion from the view's depth: a translate gizmo behind a box, its hidden handles faded as the box's depth,
 * read back from the frame before, says.
 */
class GizmoOcclusionGpuTest {

    static final int WIDTH = 480;
    static final int HEIGHT = 320;

    @Test
    void aGizmosPartsBehindTheSceneAreFadedByTheReadBackDepth() {
        try (SceneGpuRig s = SceneGpuRig.open(WIDTH, HEIGHT)) {
            int floor = s.unlit(0.2f, 0.22f, 0.25f);
            int red = s.unlit(0.8f, 0.1f, 0.1f);
            float half = (float) Math.sqrt(0.5);
            s.scene.set(0, s.quad, floor, 0, 0, 0, -half, 0, 0, half, 20, 20, 1);
            s.box(1, red, 0.9, 1, 1, 0.7f); // between the camera and the gizmo's right half
            s.camera.position(0, 1, 6).set(0, 0, -1, 0, 1, 0, Math.toRadians(60), WIDTH, HEIGHT, 0.1);
            DepthOcclusion occlusion = new DepthOcclusion(s.camera, 1);
            s.rig.view.add(occlusion);
            Ui ui = new Ui(Tss.defaultTheme(), UiSceneTest.text());
            SceneGraph graph = new SceneGraph(new ChangeClock());
            SpatialIndex index = new SpatialIndex(graph);
            UiScene scene = new UiScene(ui, graph, index, UiScene.INTERACTIVE).camera(s.camera);
            long node = graph.create();
            graph.setTranslation(node, 0, 1, -2);
            graph.setLocalBounds(node, -0.2, -0.2, -0.2, 0.2, 0.2, 0.2);
            Gizmo gizmo = new Gizmo(scene.picking(), scene.mainView(), null).mode(GizmoMode.TRANSLATE);
            gizmo.select(node);
            gizmo.occlusion(occlusion);
            GpuUiRenderer renderer = new GpuUiRenderer(s.rig.graph);
            s.rig.view.add(renderer);
            UiInput input = new UiInput(4);
            long now = 1_000_000_000L;
            assertThat(occlusion.occluded(scene.mainView(), 0, 1, -5)).as("nothing read back yet").isFalse();
            for (int frame = 0; frame < 8; frame++) {
                input.beginFrame();
                scene.input(input, now += 16_000_000L, WIDTH, HEIGHT);
                scene.update(WIDTH, HEIGHT);
                renderer.submit(ui.layers(), ui.layerCount(), ui.textures(), WIDTH, HEIGHT);
                s.rig.frame();
            }
            assertThat(occlusion.frame()).as("a depth came back").isPositive();
            // behind the box, in front of it, beside it (sky), behind the camera
            assertThat(occlusion.occluded(scene.mainView(), 0.9, 1, -3)).isTrue();
            assertThat(occlusion.occluded(scene.mainView(), 0.9, 1, 3)).isFalse();
            assertThat(occlusion.occluded(scene.mainView(), -2.5, 1.5, -3)).isFalse();
            assertThat(occlusion.occluded(scene.mainView(), 0, 1, 9)).isFalse();
            // the floor hides what is below it
            assertThat(occlusion.occluded(scene.mainView(), -2, -0.5, -2)).isTrue();
            s.rig.screenshot("integ/gizmo-depth-occlusion", -1);
            renderer.close();
        }
    }
}
