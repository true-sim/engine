package com.truesim.engine.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.truesim.engine.render.Camera;
import com.truesim.engine.ui.scene.GpuCameraView;
import com.truesim.engine.ui.scene.UiSceneTest;
import com.truesim.ui.controls.Ui;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.input.UiInput;
import com.truesim.ui.scene.CameraView;
import com.truesim.ui.scene.SceneRig;
import com.truesim.ui.style.tss.Tss;
import org.junit.jupiter.api.Test;

/**
 * A minimap: a second camera of the same frame graph rendered at the control's size into a bindless texture the UI
 * draws, in the frame it renders, over the main view.
 */
class CameraViewGpuTest {

    static final int WIDTH = 480;
    static final int HEIGHT = 320;

    @Test
    void aMinimapShowsASecondCameraInTheSameFrame() {
        try (SceneGpuRig s = SceneGpuRig.open(WIDTH, HEIGHT)) {
            int floor = s.unlit(0.1f, 0.35f, 0.12f);
            int red = s.unlit(0.9f, 0.05f, 0.05f);
            int yellow = s.unlit(0.9f, 0.8f, 0.1f);
            int blue = s.unlit(0.1f, 0.2f, 0.9f);
            float half = (float) Math.sqrt(0.5);
            s.scene.set(0, s.quad, floor, 0, 0, 0, -half, 0, 0, half, 20, 20, 1); // the ground, y = 0
            s.box(1, red, 0, 1, 0, 1);
            s.box(2, yellow, 8, 1, -6, 1);
            s.box(3, blue, -9, 1, 5, 1.5f);
            s.camera.position(0, 6, 18).set(0, -0.35, -1, 0, 1, 0, Math.toRadians(60), WIDTH, HEIGHT, 0.1);
            // the UI: a minimap at the top right
            Ui ui = new Ui(Tss.defaultTheme(), UiSceneTest.text());
            GpuUiRenderer renderer = new GpuUiRenderer(s.rig.graph);
            s.rig.view.add(renderer);
            GpuCameraView minimap = new GpuCameraView(s.rig.graph, renderer, ui.textures(), "minimap", 256, 256);
            Camera top = new Camera();
            minimap.onResize((w, h) -> top.position(0, 40, 0).setOrthographic(0, 0, 0, 0, -1, 0, 0, 0, -1,
                12.0 * w / h, 12, 80, w, h));
            s.view(minimap.view(), top);
            CameraView control = new CameraView(ui, minimap, null, "camera-view");
            minimap.follow(top, control);
            Element map = control.element();
            ui.root().add(map);
            SceneRig.place(ui, map, WIDTH - 200, 20, 180, 140);
            UiInput input = new UiInput(4);
            long now = 1_000_000_000L;
            for (int frame = 0; frame < 6; frame++) {
                ui.update(WIDTH, HEIGHT, input, now += 16_000_000L);
                renderer.submit(ui.layers(), ui.layerCount(), ui.textures(), WIDTH, HEIGHT);
                s.rig.frame();
            }
            assertThat(renderer.bound(minimap.texture().id())).isSameAs(minimap.graphTexture());
            assertThat(renderer.slot(minimap.texture().id())).isEqualTo(minimap.graphTexture().slot());
            // the control asked for its content size; the view renders at it (the frame theme's padding inside)
            assertThat(minimap.width()).isBetween(150, 180);
            assertThat(minimap.height()).isBetween(110, 140);
            assertThat(minimap.view().outputWidth()).isEqualTo(minimap.width());
            assertThat(control.view().isOrthographic()).as("its picking follows its camera").isTrue();
            s.rig.screenshot("integ/camera-view-minimap", -1);
            // the minimap's centre shows the red box seen from above, its corner the ground
            float[] centre = s.rig.at(WIDTH - 110, 90);
            assertThat(centre[0]).as("red box from above, in the minimap").isGreaterThan(0.4f);
            assertThat(centre[1]).isLessThan(0.2f);
            // the main view around it is the 3D scene: the red box in the middle
            float[] mainBox = s.rig.at(WIDTH / 2, 142);
            assertThat(mainBox[0]).isGreaterThan(0.4f);
            // a resize renders at the new size in the frame it is asked for (the control asks with the size it recorded
            // in the frame before: its request runs after layout, its recording after that)
            long version = minimap.version();
            SceneRig.place(ui, map, WIDTH - 250, 20, 230, 180);
            for (int frame = 0; frame < 2; frame++) {
                ui.update(WIDTH, HEIGHT, input, now += 16_000_000L);
                renderer.submit(ui.layers(), ui.layerCount(), ui.textures(), WIDTH, HEIGHT);
                s.rig.frame();
            }
            assertThat(minimap.version()).isGreaterThan(version);
            assertThat(minimap.view().outputWidth()).isEqualTo(minimap.width()).isGreaterThan(180);
            int id = minimap.texture().id();
            minimap.close();
            ui.update(WIDTH, HEIGHT, input, now += 16_000_000L);
            renderer.submit(ui.layers(), ui.layerCount(), ui.textures(), WIDTH, HEIGHT);
            s.rig.frame();
            assertThat(renderer.bound(id)).isNull();
            assertThat(minimap.texture()).isNull();
            renderer.close();
        }
    }
}
