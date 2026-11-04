package com.truesim.engine.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.truesim.common.math.color.Color;
import com.truesim.engine.render.Material;
import com.truesim.engine.render.materials.MetallicRoughnessParameters;
import com.truesim.engine.ui.scene.UiSceneTest;
import com.truesim.scene.assets.mesh.MeshData;
import com.truesim.scene.assets.mesh.VertexAttribute;
import com.truesim.scene.assets.mesh.VertexFormat;
import com.truesim.scene.meshbuild.MeshBuilder;
import com.truesim.ui.controls.Ui;
import com.truesim.ui.core.Element;
import com.truesim.ui.style.StyleSheet;
import com.truesim.ui.style.tss.Tss;
import com.truesim.ui.scene.WorldPanel;
import org.junit.jupiter.api.Test;

/**
 * World panels drawn both ways in one view: a "monitor" rendered into a texture its scene material samples (and
 * rendered again only when its UI changes), and a floating panel drawn directly in the 3D view, depth tested (a box
 * in front of it hides part of it).
 */
class WorldPanelGpuTest {

    static final int WIDTH = 480;
    static final int HEIGHT = 320;
    static final StyleSheet PANEL = Tss.compileString("panel.tss", """
        root { layout: none; }
        panel { width: 320px; height: 200px; offsets: 0 0 0 0; anchors: 0 0 0 0; background: box(#1d6f5a);
                border: 4px #f0f0f0; radius: 12px; }
        label { offsets: 24px 70px 0 0; anchors: 0 0 0 0; font-size: 34px; color: #ffffff; }
        """);

    /** A 2 x 2 quad facing +Z with texture coordinates: its top left corner at (0, 0), as a panel's top left pixel. */
    static MeshData texturedQuad() {
        float[] positions = {-1, -1, 0, 1, -1, 0, 1, 1, 0, -1, 1, 0};
        float[] normals = {0, 0, 1, 0, 0, 1, 0, 0, 1, 0, 0, 1};
        float[] uvs = {0, 1, 1, 1, 1, 0, 0, 0};
        return new MeshBuilder(VertexFormat.of(VertexAttribute.NORMAL, VertexAttribute.UV0), 4).positions(positions)
            .attribute(VertexAttribute.NORMAL, normals).attribute(VertexAttribute.UV0, uvs)
            .submesh(new int[] {0, 1, 2, 0, 2, 3}).buildFlat();
    }

    static Ui panelUi(String text, Element[] label) {
        Ui ui = new Ui(PANEL, UiSceneTest.text());
        Element panel = ui.panel(null);
        label[0] = ui.label(null, text);
        ui.root().add(panel).add(label[0]);
        return ui;
    }

    @Test
    void panelsDrawIntoATextureTheSceneSamplesAndDirectlyDepthTested() {
        try (SceneGpuRig s = SceneGpuRig.open(WIDTH, HEIGHT)) {
            MeshData screenMesh = texturedQuad();
            int screenQuad = s.pool.add(screenMesh);
            int floor = s.unlit(0.25f, 0.25f, 0.28f);
            int red = s.unlit(0.9f, 0.05f, 0.05f);
            float half = (float) Math.sqrt(0.5);
            s.scene.set(0, s.quad, floor, 0, 0, 0, -half, 0, 0, half, 20, 20, 1);
            s.box(1, red, 2.4, 1.5, 1, 0.3f); // in front of the direct panel's right edge
            s.camera.position(0, 1.5, 6).set(0, 0, -1, 0, 1, 0, Math.toRadians(60), WIDTH, HEIGHT, 0.1);
            WorldPanelRenderer panels = new WorldPanelRenderer(s.rig.graph, s.camera);
            s.rig.view.add(panels);
            Element[] monitorLabel = new Element[1];
            Element[] floatingLabel = new Element[1];
            WorldPanel monitor = new WorldPanel(panelUi("Texture", monitorLabel), 320, 200).pixelsPerMeter(200)
                .place(-2, 1.5, 0, 0, 0, 0, 1, 1);
            WorldPanel floating = new WorldPanel(panelUi("Direct", floatingLabel), 320, 200).pixelsPerMeter(200)
                .place(2, 1.5, 0, 0, 0, 0, 1, 1);
            panels.add(monitor, WorldPanelRenderer.Mode.TEXTURE).add(floating, WorldPanelRenderer.Mode.DIRECT);
            assertThatIllegalArgumentException().isThrownBy(() -> panels.add(monitor, WorldPanelRenderer.Mode.DIRECT));
            assertThatIllegalArgumentException().isThrownBy(() -> panels.textureSlot(floating));
            // the monitor: a quad of the panel's size whose material shows the panel's texture (as its emission)
            int slot = panels.textureSlot(monitor);
            int screen = s.materials.add(new Material(MetallicRoughnessParameters.DEFAULTS
                .withBaseColor(new Color(0, 0, 0, 1)).withEmissive(new Color(1, 1, 1, 1)).withEmissiveTexture(slot)));
            s.scene.set(2, screenQuad, screen, -2, 1.5, 0, 0, 0, 0, 1, 0.8f, 0.5f, 1);
            long now = 1_000_000_000L;
            for (int frame = 0; frame < 8; frame++) {
                monitor.ui().update(320, 200);
                floating.ui().update(320, 200);
                now += 16_000_000L;
                panels.submit();
                s.rig.frame();
            }
            s.rig.screenshot("integ/world-panels", -1);
            long renders = panels.renders(monitor);
            assertThat(renders).isPositive();
            for (int frame = 0; frame < 4; frame++) {
                monitor.ui().update(320, 200);
                floating.ui().update(320, 200);
                now += 16_000_000L;
                panels.submit();
                s.rig.frame();
            }
            assertThat(panels.renders(monitor)).as("not rendered again while its UI is unchanged").isEqualTo(renders);
            // where each panel shows: x 240 + 46.2 x at depth 6, y 160
            float[] texture = s.rig.at(150, 172);
            assertThat(texture[1]).as("the monitor's green from its texture").isGreaterThan(0.08f)
                .isGreaterThan(texture[0] * 2);
            float[] direct = s.rig.at(320, 172);
            assertThat(direct[1]).as("the direct panel's green").isGreaterThan(0.08f).isGreaterThan(direct[0] * 2);
            float[] hidden = s.rig.at(362, 160);
            assertThat(hidden[0]).as("the box in front hides the panel").isGreaterThan(0.5f);
            assertThat(hidden[1]).isLessThan(0.15f);
            // a change to the monitor's UI renders it again, once
            monitor.ui().text(monitorLabel[0], "Changed");
            for (int frame = 0; frame < 6; frame++) {
                monitor.ui().update(320, 200);
                floating.ui().update(320, 200);
                now += 16_000_000L;
                panels.submit();
                s.rig.frame();
            }
            assertThat(panels.renders(monitor)).isGreaterThan(renders).isLessThan(renders + 6);
            panels.remove(floating);
            assertThat(panels.count()).isEqualTo(1);
            s.rig.device.waitIdle();
            panels.close();
        }
    }
}
