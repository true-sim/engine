package com.truesim.engine.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.truesim.engine.graph.Access;
import com.truesim.engine.graph.Clear;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Image;
import com.truesim.engine.graph.ImageDesc;
import com.truesim.engine.graph.Key;
import com.truesim.engine.graph.Technique;
import com.truesim.engine.render.Camera;
import com.truesim.engine.render.Keys;
import com.truesim.engine.vulkan.Format;
import com.truesim.scene.core.Mobility;
import com.truesim.ui.controls.Ui;
import com.truesim.ui.core.Element;
import com.truesim.ui.gizmo.Gizmo;
import com.truesim.ui.gizmo.GizmoMode;
import com.truesim.ui.scene.SceneRig;
import com.truesim.ui.scene.WorldPanel;
import com.truesim.ui.style.StyleSheet;
import com.truesim.ui.style.tss.Tss;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/**
 * A headset's two eyes in one multiview pass (design 6.4 and 8, XR), headless: a view whose scene colour and depth have
 * a layer per eye, a direct world panel ({@link WorldPanelRenderer} with a camera per eye) and an XR gizmo drawn as 3D
 * geometry ({@link GizmoRenderer}), both read back per eye: each eye sees them where its own camera puts them (the
 * disparity of the eyes' 64 mm apart), and the scene's depth (an occluder's footprint written into both layers) hides
 * the panel and fades the gizmo per pixel, in each eye.
 */
class StereoGpuTest {

    static final int WIDTH = 320;
    static final int HEIGHT = 240;
    static final double FOV = Math.toRadians(60);
    static final double NEAR = 0.05;
    static final double HALF_IPD = 0.032;
    /** Pixels per unit of x at distance 1: half the height over tan(fov / 2). */
    static final double FOCAL = HEIGHT / 2.0 / Math.tan(FOV / 2);
    static final Key<Image> LEFT_EYE = Key.image("test.leftEye");
    static final float[] BACKGROUND = {0.02f, 0.03f, 0.06f, 1};

    static final StyleSheet PANEL = Tss.compileString("panel.tss", """
        root { layout: none; }
        panel { width: 320px; height: 200px; offsets: 0 0 0 0; anchors: 0 0 0 0; background: box(#1d6f5a);
                border: 4px #f0f0f0; }
        """);

    /** The scene of the stereo view: its colour and depth with a layer per eye, and occluders' footprints. */
    static final class StereoScene implements Technique {
        @Override
        public List<Key<?>> provides() {
            return List.of(Keys.SCENE_COLOR, Keys.SCENE_DEPTH);
        }

        @Override
        public void build(GraphBuilder g) {
            Image color = g.create("eyes", ImageDesc.of(Format.R16G16B16A16_SFLOAT, WIDTH, HEIGHT).layers(2));
            Image depth = g.create("eyes' depth", ImageDesc.of(Format.D32_SFLOAT, WIDTH, HEIGHT).layers(2));
            g.pass("stereo scene").attach(color, Clear.color(BACKGROUND)).attachDepth(depth, Clear.FAR)
                .record(ctx -> {
                    // in front of the panel's lower band (at 0.6 m, the panel at 1 m): it hides the panel there
                    ctx.clearDepth(20, 140, 120, 16, (float) (NEAR / 0.6));
                    // behind the panel (3 m): the panel still shows over it
                    ctx.clearDepth(20, 100, 120, 12, (float) (NEAR / 3));
                    // in front of the outer half of the gizmo's X arrow (0.8 m, the gizmo at 1 m)
                    ctx.clearDepth(250, 100, 70, 40, (float) (NEAR / 0.8));
                });
            g.provide(Keys.SCENE_COLOR, color);
            g.provide(Keys.SCENE_DEPTH, depth);
        }
    }

    /** Reads both eyes back after everything drawn over the scene, and shows the left eye as the rig's result. */
    static final class ReadEyes implements Technique {
        final float[][] eyes = new float[2][WIDTH * HEIGHT * 4];
        long frames;

        @Override
        public List<Key<?>> provides() {
            return List.of(LEFT_EYE);
        }

        @Override
        public void build(GraphBuilder g) {
            Image color = g.require(Keys.SCENE_COLOR); // after every technique modifying it
            g.readback("read the eyes", color.whole(), this::receive);
            Image left = g.create("left eye", ImageDesc.of(Format.R16G16B16A16_SFLOAT, WIDTH, HEIGHT));
            g.pass("show the left eye").read(color.layer(0), Access.TRANSFER_SRC).write(left, Access.TRANSFER_DST)
                .record(ctx -> ctx.blit(color.layer(0), left.whole(), false));
            g.provide(LEFT_EYE, left);
        }

        void receive(MemorySegment data, long frame) {
            for (int eye = 0; eye < 2; eye++) {
                float[] rgba = eyes[eye];
                long base = (long) eye * WIDTH * HEIGHT * 8;
                for (int i = 0; i < rgba.length; i++) {
                    rgba[i] = Float.float16ToFloat(data.get(ValueLayout.JAVA_SHORT_UNALIGNED, base + 2L * i));
                }
            }
            frames = frame;
        }

        float[] at(int eye, int x, int y) {
            int o = (y * WIDTH + x) * 4;
            float[] rgba = eyes[eye];
            return new float[] {rgba[o], rgba[o + 1], rgba[o + 2], rgba[o + 3]};
        }
    }

    /** The eye cameras: at the head (the origin) set off by half the eyes' distance, looking down -z. */
    static Camera eye(double offset) {
        return new Camera().position(0, 0, 0).set(offset, 0, 0, 0, 0, -1, 0, 1, 0, FOV, WIDTH, HEIGHT, NEAR);
    }

    /** The screen x of world point {@code x} at distance {@code d} seen from eye {@code offset}. */
    static double screenX(double x, double d, double offset) {
        return WIDTH / 2.0 + (x - offset) * FOCAL / d;
    }

    static boolean isBackground(float[] c) {
        return Math.abs(c[0] - BACKGROUND[0]) < 0.01f && Math.abs(c[1] - BACKGROUND[1]) < 0.01f
            && Math.abs(c[2] - BACKGROUND[2]) < 0.01f;
    }

    @Test
    void bothEyesSeeTheirOwnPanelAndGizmoInOnePassDepthTestedPerEye() {
        float angular = Gizmo.XR_ANGULAR_SIZE.get();
        Gizmo.XR_ANGULAR_SIZE.set(40); // a big gizmo: its shafts several pixels wide at this resolution
        try (GpuRig rig = GpuRig.open(WIDTH, HEIGHT)) {
            Camera left = eye(-HALF_IPD);
            Camera right = eye(HALF_IPD);
            rig.view.add(new StereoScene());
            ReadEyes eyes = new ReadEyes();
            rig.view.add(eyes);
            rig.show(LEFT_EYE);
            // a direct panel 0.4 x 0.25 m, 1 m ahead, left of centre
            WorldPanelRenderer panels = new WorldPanelRenderer(rig.graph, left, right);
            rig.view.add(panels);
            Ui panelUi = new Ui(PANEL, null);
            Element body = panelUi.panel(null);
            panelUi.root().add(body);
            WorldPanel panel = new WorldPanel(panelUi, 320, 200).pixelsPerMeter(800).place(-0.35, 0, -1, 0, 0, 0, 1, 1);
            panels.add(panel, WorldPanelRenderer.Mode.DIRECT);
            // an XR gizmo (no screen view, sized from the head) on a node 1 m ahead, right of centre
            SceneRig r = new SceneRig(Tss.defaultTheme(), null, WIDTH, HEIGHT);
            r.picking.mainView(null);
            long node = r.box(0.12, 0, -1, 0.05, Mobility.DYNAMIC);
            Gizmo gizmo = new Gizmo(r.picking, null, null).head(0, 0, 0);
            gizmo.select(node);
            r.frames(2);
            GizmoRenderer gizmos = new GizmoRenderer(rig.graph, left, right);
            rig.view.add(gizmos);
            gizmos.add(gizmo);
            assertThatIllegalArgumentException().isThrownBy(() -> gizmos.add(gizmo));
            for (int frame = 0; frame < 4; frame++) {
                panelUi.update(320, 200);
                r.frame();
                panels.submit();
                gizmos.submit();
                rig.frame();
            }
            assertThat(eyes.frames).as("read back").isPositive();
            assertThat(gizmos.count()).isEqualTo(1);
            assertThat(gizmos.vertices(gizmo)).isPositive();
            screenshot("xr/stereo-left", eyes.eyes[0]);
            screenshot("xr/stereo-right", eyes.eyes[1]);
            Camera[] cams = {left, right};
            double[] offsets = {-HALF_IPD, HALF_IPD};
            int y = HEIGHT / 2;
            double size = gizmo.size();
            for (int e = 0; e < 2; e++) {
                String eyeName = e == 0 ? "left eye" : "right eye";
                // the panel's left edge where this eye's camera puts it (the border's first pixel)
                double edge = screenX(-0.35 - 0.2, 1, offsets[e]);
                int first = -1;
                for (int x = 0; x < WIDTH && first < 0; x++) {
                    if (!isBackground(eyes.at(e, x, y))) {
                        first = x;
                    }
                }
                assertThat((double) first).as(eyeName + ": the panel's left edge").isBetween(edge - 1.5, edge + 1.5);
                float[] inside = eyes.at(e, (int) Math.round(edge) + 30, y);
                assertThat(inside[1]).as(eyeName + ": the panel's green").isGreaterThan(0.08f)
                    .isGreaterThan(inside[0] * 2);
                float[] behind = eyes.at(e, (int) Math.round(edge) + 30, 105);
                assertThat(behind[1]).as(eyeName + ": over an occluder behind it").isGreaterThan(0.08f);
                float[] hidden = eyes.at(e, (int) Math.round(edge) + 30, 148);
                assertThat(isBackground(hidden)).as(eyeName + ": hidden by an occluder in front").isTrue();
                // the gizmo's Y shaft, a column at its origin as this eye sees it
                int column = (int) Math.round(screenX(0.12, 1, offsets[e]));
                int up = (int) Math.round(y - 0.5 * size * FOCAL);
                float[] shaft = eyes.at(e, column, up);
                assertThat(shaft[1]).as(eyeName + ": the Y shaft's colour").isGreaterThan(shaft[0])
                    .isGreaterThan(shaft[2]).isGreaterThan(0.1f);
                int other = (int) Math.round(screenX(0.12, 1, offsets[1 - e]));
                assertThat(isBackground(eyes.at(e, other + (other - column) / 2, up)))
                    .as(eyeName + ": nothing where the other eye sees the shaft, past it").isTrue();
                // the X arrow: full before the occluder, faded behind it (at the occluded opacity, 0.28)
                int open = (int) Math.round(screenX(0.12 + 0.35 * size, 1, offsets[e]));
                int covered = (int) Math.round(screenX(0.12 + 0.7 * size, 1, offsets[e]));
                assertThat(open).isLessThan(250);
                assertThat(covered).isBetween(252, 318);
                float[] full = eyes.at(e, open, y);
                float[] faded = eyes.at(e, covered, y);
                assertThat(full[0]).as(eyeName + ": the X shaft in front of the scene").isGreaterThan(full[1] * 2)
                    .isGreaterThan(0.2f);
                assertThat(faded[0]).as(eyeName + ": the X shaft behind the occluder, faded")
                    .isGreaterThan(faded[1] * 1.5f).isLessThan(full[0] * 0.45f).isGreaterThan(full[0] * 0.12f);
                assertThat(cams[e].eye(0)).isEqualTo(offsets[e]);
            }
            gizmos.remove(gizmo);
            assertThat(gizmos.count()).isZero();
            rig.device.waitIdle();
            panels.close();
            gizmos.close();
            gizmo.dispose();
        } finally {
            Gizmo.XR_ANGULAR_SIZE.set(angular);
        }
    }

    @Test
    void aRendererMadeForTwoEyesRefusesAViewOfOneLayer() {
        try (GpuRig rig = GpuRig.open(64, 64)) {
            rig.view.add(new Technique() {
                @Override
                public List<Key<?>> provides() {
                    return List.of(Keys.SCENE_COLOR, Keys.SCENE_DEPTH);
                }

                @Override
                public void build(GraphBuilder g) {
                    Image color = g.create("flat", ImageDesc.of(Format.R16G16B16A16_SFLOAT, 64, 64));
                    Image depth = g.create("flat depth", ImageDesc.of(Format.D32_SFLOAT, 64, 64));
                    g.pass("flat").attach(color, Clear.color(BACKGROUND)).attachDepth(depth, Clear.FAR)
                        .record(ctx -> { });
                    g.provide(Keys.SCENE_COLOR, color);
                    g.provide(Keys.SCENE_DEPTH, depth);
                }
            });
            GizmoRenderer gizmos = new GizmoRenderer(rig.graph, eye(-HALF_IPD), eye(HALF_IPD));
            rig.view.add(gizmos);
            rig.show(Keys.SCENE_COLOR);
            assertThatIllegalStateException().isThrownBy(rig::frame).withMessageContaining("a layer per eye");
            rig.view.remove(gizmos);
            gizmos.close();
            assertThatIllegalArgumentException().isThrownBy(() -> new GizmoRenderer(rig.graph));
            assertThatIllegalArgumentException().isThrownBy(() -> new WorldPanelRenderer(rig.graph, new Camera[7]));
        }
    }

    /** A flat view's scene: one layer of colour and depth, cleared. */
    static final class FlatScene implements Technique {
        @Override
        public List<Key<?>> provides() {
            return List.of(Keys.SCENE_COLOR, Keys.SCENE_DEPTH);
        }

        @Override
        public void build(GraphBuilder g) {
            Image color = g.create("flat", ImageDesc.of(Format.R16G16B16A16_SFLOAT, WIDTH, HEIGHT));
            Image depth = g.create("flat depth", ImageDesc.of(Format.D32_SFLOAT, WIDTH, HEIGHT));
            g.pass("flat").attach(color, Clear.color(BACKGROUND)).attachDepth(depth, Clear.FAR).record(ctx -> { });
            g.provide(Keys.SCENE_COLOR, color);
            g.provide(Keys.SCENE_DEPTH, depth);
        }
    }

    @Test
    void oneCameraDrawsAFlatViewAndEveryModeHiddenOrRemoved() {
        float angular = Gizmo.XR_ANGULAR_SIZE.get();
        Gizmo.XR_ANGULAR_SIZE.set(40);
        try (GpuRig rig = GpuRig.open(WIDTH, HEIGHT)) {
            Camera camera = eye(0);
            rig.view.add(new FlatScene());
            GizmoRenderer gizmos = new GizmoRenderer(rig.graph, camera);
            rig.view.add(gizmos);
            rig.show(Keys.SCENE_COLOR);
            SceneRig r = new SceneRig(Tss.defaultTheme(), null, WIDTH, HEIGHT);
            r.picking.mainView(null);
            long node = r.box(0, 0, -1, 0.05, Mobility.DYNAMIC);
            Gizmo gizmo = new Gizmo(r.picking, null, null).head(0, 0, 0);
            Gizmo idle = new Gizmo(r.picking, null, null).head(0, 0, 0); // nothing selected: nothing drawn
            gizmo.select(node);
            r.frames(2);
            gizmos.add(gizmo).add(idle);
            assertThat(gizmos.vertices(idle)).isZero();
            int most = 0;
            for (GizmoMode mode : new GizmoMode[] {GizmoMode.TRANSLATE, GizmoMode.ROTATE, GizmoMode.UNIVERSAL,
                GizmoMode.SCALE, GizmoMode.BOUNDS}) {
                gizmo.mode(mode); // the universal gizmo outgrows the translate gizmo's buffer
                r.frame();
                gizmos.submit();
                rig.frame();
                most = Math.max(most, gizmos.vertices(gizmo));
                assertThat(gizmos.vertices(gizmo)).as(mode.name()).isPositive();
                assertThat(gizmos.vertices(idle)).isZero();
            }
            assertThat(most).isGreaterThan(gizmos.vertices(gizmo));
            gizmo.mode(GizmoMode.TRANSLATE);
            r.frame();
            gizmos.submit();
            rig.frame();
            int open = (int) Math.round(screenX(0.5 * gizmo.size(), 1, 0));
            float[] shaft = rig.at(open, HEIGHT / 2);
            assertThat(shaft[0]).as("the X shaft in a flat view").isGreaterThan(shaft[1] * 2).isGreaterThan(0.2f);
            gizmo.visible(false);
            gizmos.submit();
            rig.frame();
            assertThat(gizmos.vertices(gizmo)).isZero();
            assertThat(isBackground(rig.at(open, HEIGHT / 2))).as("hidden: nothing drawn").isTrue();
            gizmos.remove(idle);
            gizmos.remove(idle); // not drawn: nothing to do
            assertThat(gizmos.vertices(idle)).isZero();
            rig.device.waitIdle();
            gizmos.close();
            gizmos.close();
            gizmo.dispose();
            idle.dispose();
        } finally {
            Gizmo.XR_ANGULAR_SIZE.set(angular);
        }
    }

    /** Writes an eye's linear colours as an sRGB PNG with {@code TRUESIM_SCREENSHOTS=true}. */
    static void screenshot(String name, float[] rgba) {
        if (!"true".equals(System.getenv("TRUESIM_SCREENSHOTS"))) {
            return;
        }
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                int o = (y * WIDTH + x) * 4;
                int rgb = 0;
                for (int c = 0; c < 3; c++) {
                    rgb = rgb << 8 | (int) (GpuRig.srgb(Math.min(1, Math.max(0, rgba[o + c]))) * 255 + 0.5f);
                }
                image.setRGB(x, y, rgb);
            }
        }
        try {
            Path file = GpuRig.SCREENSHOTS.resolve(name + ".png");
            Files.createDirectories(file.getParent());
            ImageIO.write(image, "png", file.toFile());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
