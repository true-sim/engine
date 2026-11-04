package com.truesim.engine.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

import com.truesim.common.state.ChangeClock;
import com.truesim.engine.render.Keys;
import com.truesim.engine.ui.scene.UiScene;
import com.truesim.engine.ui.scene.UiSceneTest;
import com.truesim.scene.core.SceneGraph;
import com.truesim.scene.core.SpatialIndex;
import com.truesim.ui.controls.Ui;
import com.truesim.ui.core.input.UiInput;
import com.truesim.ui.gizmo.Gizmo;
import com.truesim.ui.gizmo.GizmoMode;
import com.truesim.ui.scene.SceneView;
import com.truesim.ui.style.tss.Tss;
import org.junit.jupiter.api.Test;

/**
 * Gizmo occlusion by the view's depth, on the GPU in the same frame: a depth tested translate gizmo (ui-gizmo's
 * {@code Gizmo.depthTest}) whose X arrow a box moves in front of. The UI's renderer compares each pixel of the arrow
 * with the scene depth drawn in that very frame: faded (times the theme's {@code gizmo-occluded} opacity) exactly where
 * the box covers it, to the pixel, from the frame the box moved in, and no longer from the frame it moved away in.
 */
class GizmoOcclusionGpuTest {

    static final int WIDTH = 480;
    static final int HEIGHT = 320;
    /** The default theme's gizmo-occluded opacity. */
    static final float OCCLUDED = 0.28f;

    @Test
    void aDepthTestedGizmoIsFadedExactlyWhereTheSceneHidesItInTheFrameTheOccluderMovedIn() {
        try (SceneGpuRig s = SceneGpuRig.open(WIDTH, HEIGHT)) {
            int floor = s.unlit(0.2f, 0.22f, 0.25f);
            int grey = s.unlit(0.55f, 0.6f, 0.65f); // not the X arrow's red: its faded part shows over it
            float h = (float) Math.sqrt(0.5);
            s.scene.set(0, s.quad, floor, 0, -1, 0, -h, 0, 0, h, 20, 20, 1);
            s.camera.position(0, 1, 6).set(0, 0, -1, 0, 1, 0, Math.toRadians(60), WIDTH, HEIGHT, 0.1);
            Ui ui = new Ui(Tss.defaultTheme(), UiSceneTest.text());
            SceneGraph graph = new SceneGraph(new ChangeClock());
            UiScene scene = new UiScene(ui, graph, new SpatialIndex(graph)).camera(s.camera);
            long node = graph.create();
            graph.setTranslation(node, 0, 1, -2);
            graph.setLocalBounds(node, -0.2, -0.2, -0.2, 0.2, 0.2, 0.2);
            Gizmo gizmo = new Gizmo(scene.picking(), scene.mainView(), null).mode(GizmoMode.TRANSLATE)
                .depthTest(true);
            gizmo.select(node);
            GpuUiRenderer renderer = new GpuUiRenderer(s.rig.graph).depthCamera(s.camera);
            s.rig.view.add(renderer);
            s.rig.view.removeOutput(SceneGpuRig.SHOWN);
            s.rig.show(Keys.UI); // the UI alone: its alpha is each pixel's opacity
            UiInput input = new UiInput(4);
            long[] now = {1_000_000_000L};
            Runnable frame = () -> {
                input.beginFrame();
                scene.input(input, now[0] += 16_000_000L, WIDTH, HEIGHT);
                scene.update(WIDTH, HEIGHT);
                renderer.submit(ui.layers(), ui.layerCount(), ui.textures(), WIDTH, HEIGHT);
                s.rig.frame();
            };
            SceneView view = scene.mainView();
            // the box out of the way: the arrow is drawn whole
            s.box(1, grey, 0, 6, 0, 0.1f);
            frame.run();
            frame.run();
            double size = gizmo.size();
            int row = pixel(view, 0, 1, -2)[1];
            int inside = pixel(view, 0.75 * size, 1, -2)[0];
            int outside = pixel(view, 0.35 * size, 1, -2)[0];
            float visible = s.rig.at(inside, row, 3);
            assertThat(visible).as("the arrow is drawn").isGreaterThan(0.5f);
            assertThat(s.rig.at(outside, row, 3)).isCloseTo(visible, offset(0.02f));

            // the box moved between the camera and the arrow's outer part, in this frame
            double t = 6.0 / 8; // the box at z 0, the gizmo at z -2, the camera at z 6
            double cx = 0.75 * size * t;
            float half = (float) (0.12 * size * t);
            s.box(1, grey, cx, 1, 0, half);
            frame.run();
            assertThat(s.rig.at(inside, row, 3)).as("hidden: faded in the frame the box moved in")
                .isCloseTo(visible * OCCLUDED, offset(0.03f));
            assertThat(s.rig.at(outside, row, 3)).as("beside the box: not faded").isCloseTo(visible, offset(0.02f));
            // to the pixel: the box's left silhouette (its back face's left edge, the box being right of the camera)
            int edge = pixel(view, cx - half, 1, -half)[0];
            assertThat(s.rig.at(edge - 2, row, 3)).as("just left of the box").isCloseTo(visible, offset(0.02f));
            assertThat(s.rig.at(edge + 2, row, 3)).as("just inside it").isCloseTo(visible * OCCLUDED,
                offset(0.03f));
            // the floor hides nothing above it; the arrow's tip beyond the box is not faded
            int beyond = pixel(view, 0.97 * size, 1, -2)[0];
            if (beyond > pixel(view, cx + half, 1, half)[0] + 2) {
                assertThat(s.rig.at(beyond, row, 3)).isGreaterThan(visible * 0.6f);
            }

            // the box moved away again: whole again in that frame
            s.box(1, grey, 0, 6, 0, 0.1f);
            frame.run();
            assertThat(s.rig.at(inside, row, 3)).isCloseTo(visible, offset(0.02f));

            // the screenshot: the scene and the UI as shown, the box back in front of the arrow
            s.box(1, grey, cx, 1, 0, half);
            s.rig.view.removeOutput(Keys.UI);
            s.rig.show(SceneGpuRig.SHOWN);
            frame.run();
            s.rig.screenshot("integ/gizmo-depth-occlusion", -1);
            renderer.close();
        }
    }

    /** The UI pixel world point {@code x, y, z} projects to. */
    private static int[] pixel(SceneView view, double x, double y, double z) {
        float[] at = new float[3];
        assertThat(view.project(x, y, z, at)).isTrue();
        return new int[] {(int) Math.floor(at[0]), (int) Math.floor(at[1])};
    }
}
