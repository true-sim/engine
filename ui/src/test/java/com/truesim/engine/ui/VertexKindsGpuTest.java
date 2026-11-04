package com.truesim.engine.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.truesim.engine.render.Keys;
import com.truesim.testing.AllocationAssertions;
import com.truesim.ui.core.draw.DrawList;
import com.truesim.ui.core.draw.Primitive;
import com.truesim.ui.core.draw.Rgba;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.Test;

/**
 * The primitives over the vertex table on the GPU: paths (joins, caps, dashes, one coverage where segments overlap),
 * envelope columns (stroked, as stairs, filled), their order among other primitives in every batching, and streaming:
 * changed vertices upload alone.
 */
class VertexKindsGpuTest {

    private static final int W = 256;
    private static final int H = 128;
    private static final long RED = Rgba.pack(1, 0, 0, 1);
    private static final long GREEN = Rgba.pack(0, 1, 0, 1);
    private static final Offset<Float> CLOSE = within(0.02f);

    /** A path through {@code xy} (pairs), {@code width} wide. */
    private static void path(DrawList l, float width, long color, int stroke, float dash, float gap, float... xy) {
        int n = xy.length / 2;
        int at = l.allocateVertices(n);
        float s = 0;
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                s += (float) Math.hypot(xy[i * 2] - xy[i * 2 - 2], xy[i * 2 + 1] - xy[i * 2 - 1]);
            }
            l.pathVertex(at + i, xy[i * 2], xy[i * 2 + 1], s);
        }
        l.path(at, n, width, color, stroke, 4, dash, gap, 0, 0, 0);
    }

    @Test
    void pathsJoinTheirSegmentsAndCapTheirEnds() {
        try (GpuRig rig = GpuRig.open(W, H).show(Keys.UI); GpuUiRenderer ui = new GpuUiRenderer(rig.graph)) {
            rig.view.add(ui);
            // the same right-angled bend four times: no join, miter, bevel, round (outer corner at the top right)
            int[] joins = {Primitive.JOIN_NONE, Primitive.JOIN_MITER, Primitive.JOIN_BEVEL, Primitive.JOIN_ROUND};
            DrawList list = UiRendererTest.list(8, l -> {
                for (int j = 0; j < 4; j++) {
                    float x = 40 + j * 50;
                    path(l, 8, RED, joins[j], 0, 0, x - 30, 20, x, 20, x, 50);
                }
                path(l, 6, GREEN, Primitive.CAP_BUTT, 0, 0, 20, 70, 60, 70);
                path(l, 6, GREEN, Primitive.CAP_SQUARE, 0, 0, 90, 70, 130, 70);
                path(l, 6, GREEN, Primitive.CAP_ROUND, 0, 0, 160, 70, 200, 70);
            });
            rig.frame(ui, null, list);
            assertThat(rig.at(42, 16, 3)).as("no join: the notch").isZero();
            assertThat(rig.at(92, 16, 3)).as("miter: the corner filled").isCloseTo(1, CLOSE);
            assertThat(rig.at(143, 17, 3)).as("miter only: beveled away").isZero();
            assertThat(rig.at(93, 17, 3)).isCloseTo(1, CLOSE);
            assertThat(rig.at(141, 18, 3)).as("bevel: inside its edge").isCloseTo(1, CLOSE);
            assertThat(rig.at(192, 18, 3)).as("round: inside its disc").isGreaterThan(0.9f);
            assertThat(rig.at(193, 16, 3)).as("round: outside it").isLessThan(0.05f);
            for (int j = 0; j < 4; j++) {
                assertThat(rig.at(25 + j * 50, 20, 3)).as("the body").isCloseTo(1, CLOSE);
                assertThat(rig.at(40 + j * 50, 40, 3)).as("the second segment").isCloseTo(1, CLOSE);
            }
            assertThat(rig.at(17, 70, 3)).as("butt").isZero();
            assertThat(rig.at(87, 70, 3)).as("square: half the width on").isCloseTo(1, CLOSE);
            assertThat(rig.at(157, 70, 3)).as("round: the disc").isGreaterThan(0.9f);
            assertThat(rig.at(157, 67, 3)).as("round: not the corner").isLessThan(0.5f);
            rig.screenshot("paths-joins-caps");
        }
    }

    @Test
    void aTranslucentPathCoversEachPixelOnceWhereItsSegmentsOverlap() {
        try (GpuRig rig = GpuRig.open(W, H).show(Keys.UI); GpuUiRenderer ui = new GpuUiRenderer(rig.graph)) {
            rig.view.add(ui);
            long half = Rgba.pack(1, 1, 1, 0.5f);
            DrawList list = UiRendererTest.list(2, l -> {
                path(l, 8, half, Primitive.JOIN_ROUND, 0, 0, 20, 20, 60, 20, 60, 60, 100, 30, 101, 31, 140, 90);
            });
            rig.frame(ui, null, list);
            assertThat(rig.at(57, 23, 3)).as("the bend's inner corner: both segments, drawn once").isCloseTo(0.5f,
                CLOSE);
            assertThat(rig.at(62, 18, 3)).as("its round join").isCloseTo(0.5f, CLOSE);
            assertThat(rig.at(101, 31, 3)).as("a segment of a pixel among its neighbours").isCloseTo(0.5f, CLOSE);
            for (int y = 0; y < H; y++) {
                for (int x = 0; x < W; x++) {
                    assertThat(rig.at(x, y, 3)).as("nowhere more than once").isLessThanOrEqualTo(0.51f);
                }
            }
            rig.screenshot("paths-translucent");
        }
    }

    @Test
    void dashesRunAlongThePathAcrossItsBends() {
        try (GpuRig rig = GpuRig.open(W, H).show(Keys.UI); GpuUiRenderer ui = new GpuUiRenderer(rig.graph)) {
            rig.view.add(ui);
            DrawList list = UiRendererTest.list(2, l -> {
                path(l, 4, RED, Primitive.JOIN_MITER, 10, 10, 20, 30, 115, 30, 115, 100);
                int at = l.allocateVertices(2); // a phase: the pattern 5 px on
                l.pathVertex(at, 20, 110, 0);
                l.pathVertex(at + 1, 120, 110, 100);
                l.path(at, 2, 4, GREEN, 0, 4, 10, 10, 0, 0, 5);
            });
            rig.frame(ui, null, list);
            assertThat(rig.at(25, 30, 0)).as("the first dash: 0 to 10").isCloseTo(1, CLOSE);
            assertThat(rig.at(35, 30, 0)).as("the first gap").isLessThan(0.01f);
            assertThat(rig.at(45, 30, 0)).isCloseTo(1, CLOSE);
            assertThat(rig.at(29, 30, 0)).as("a dash's last pixel").isCloseTo(1, CLOSE);
            assertThat(rig.at(30, 30, 0)).isLessThan(0.01f);
            // 95 px along the first segment, the pattern goes on down the second: 100 to 110 a dash, 110 to 120 a gap
            assertThat(rig.at(115, 42, 0)).as("107 px along: a dash").isCloseTo(1, CLOSE);
            assertThat(rig.at(115, 52, 0)).as("117 px along: a gap").isZero();
            assertThat(rig.at(22, 110, 1)).as("the phase: 5 to 10 a dash").isCloseTo(1, CLOSE);
            assertThat(rig.at(27, 110, 1)).as("10 to 20 a gap").isLessThan(0.01f);
            rig.screenshot("paths-dashes");
        }
    }

    /** Columns at x 40.5 to 49.5: flat, a tall one, a jump, flat, empty, then two more. */
    private static void columns(DrawList l, int at) {
        float nan = Float.NaN;
        float[][] v = {{60, 60, 60, 60}, {60, 70, 40, 80}, {100, 100, 100, 100}, {100, 100, 99.8f, 100.2f},
            {nan, nan, nan, nan}, {50, 50, 50, 50}, {51, 51, 51, 51}, {52, 52, 52, 52}, {53, 53, 53, 53},
            {54, 54, 54, 54}};
        for (int k = 0; k < v.length; k++) {
            l.columnVertex(at + k, v[k][0], v[k][1], v[k][2], v[k][3]);
        }
    }

    @Test
    void envelopeColumnsStrokeTheirSpansAndConnectorsOrFillToTheBaseline() {
        try (GpuRig rig = GpuRig.open(W, H).show(Keys.UI); GpuUiRenderer ui = new GpuUiRenderer(rig.graph)) {
            rig.view.add(ui);
            DrawList list = UiRendererTest.list(3, l -> {
                int at = l.allocateVertices(10);
                columns(l, at);
                l.columns(at, 10, 40.5f, 1, 1, RED, 0, false);
                int steps = l.allocateVertices(10);
                columns(l, steps);
                l.columns(steps, 10, 100.5f, 1, 1, RED, 0, true);
                l.columns(at, 10, 160.5f, 1, 0, GREEN, 110, false); // filled from y = 110
            });
            rig.frame(ui, null, list);
            assertThat(rig.at(41, 60, 0)).as("the tall column's stroke").isCloseTo(1, CLOSE);
            assertThat(rig.at(41, 39, 0)).as("its end grown by half the width").isCloseTo(0.5f, within(0.1f));
            assertThat(rig.at(41, 38, 0)).isZero();
            assertThat(rig.at(41, 90, 0) + rig.at(42, 90, 0)).as("the connector to the jump").isGreaterThan(0.3f);
            assertThat(rig.at(44, 100, 0)).as("no column: nothing").isLessThan(0.05f);
            assertThat(rig.at(44, 75, 0) + rig.at(45, 75, 0)).as("nor a connector across it").isLessThan(0.05f);
            assertThat(rig.at(47, 52, 0)).as("flat columns: their connectors").isGreaterThan(0.4f);
            assertThat(rig.at(30, 60, 0)).isZero();
            // stairs: level at 80's last (70), then down at the next column
            assertThat(rig.at(101, 70, 0)).as("level from the tall column's last").isGreaterThan(0.5f);
            assertThat(rig.at(102, 85, 0)).as("then down at the next").isCloseTo(1, CLOSE);
            assertThat(rig.at(101, 85, 0)).as("not diagonally").isLessThan(0.1f);
            // filled: each column from the baseline to its farthest sample, a column wide
            assertThat(rig.at(161, 45, 1)).as("to the tall column's top").isCloseTo(1, CLOSE);
            assertThat(rig.at(161, 39, 1)).isZero();
            assertThat(rig.at(160, 59, 1)).isZero();
            assertThat(rig.at(160, 61, 1)).isCloseTo(1, CLOSE);
            assertThat(rig.at(164, 105, 1)).as("an empty column: not filled").isZero();
            assertThat(rig.at(162, 105, 1)).isCloseTo(1, CLOSE);
            assertThat(rig.at(159, 105, 1)).as("no fill beside them").isZero();
            assertThat(rig.at(170, 105, 1)).isZero();
            rig.screenshot("columns");
        }
    }

    @Test
    void theyDrawInTheirPlaceAmongTheOtherPrimitivesInEveryBatching() {
        try (GpuRig rig = GpuRig.open(W, H).show(Keys.UI); GpuUiRenderer ui = new GpuUiRenderer(rig.graph)) {
            rig.view.add(ui);
            DrawList list = UiRendererTest.list(6, l -> {
                l.box(10, 10, 100, 100, Rgba.pack(0, 0, 1, 1), 0, 0, 0, 0, 0, 0);
                path(l, 6, RED, Primitive.JOIN_ROUND, 0, 0, 20, 50, 90, 50);
                l.box(40, 40, 60, 60, GREEN, 0, 0, 0, 0, 0, 0); // over the path
                int at = l.allocateVertices(10);
                columns(l, at);
                l.columns(at, 10, 150.5f, 1, 0, RED, 110, false);
            });
            float[] uber = null;
            for (GpuUiRenderer.Batching mode : GpuUiRenderer.Batching.values()) {
                ui.batching(mode);
                rig.frame(ui, null, list);
                assertThat(rig.at(30, 50, 0)).as(mode + ": the path over the first box").isCloseTo(1, CLOSE);
                assertThat(rig.at(50, 50)).as(mode + ": the second box over the path")
                    .containsExactly(new float[] {0, 1, 0, 1}, CLOSE);
                assertThat(rig.at(152, 105, 0)).isCloseTo(1, CLOSE);
                if (uber == null) {
                    uber = rig.rgba.clone();
                    assertThat(ui.draws()).as("box, path, box, columns").isEqualTo(4);
                } else {
                    assertThat(rig.rgba).containsExactly(uber, within(1e-3f));
                }
            }
        }
    }

    @Test
    void aStreamUploadsTheVerticesItChangedAndSubmittingAllocatesNothing() {
        try (GpuRig rig = GpuRig.open(W, H).show(Keys.UI); GpuUiRenderer ui = new GpuUiRenderer(rig.graph)) {
            rig.view.add(ui);
            DrawList list = new DrawList();
            int start = list.allocate(1);
            int at = list.allocateVertices(200);
            for (int k = 0; k < 200; k++) {
                list.columnVertex(at + k, 50, 60, 40, 70);
            }
            list.begin(start, 1);
            list.columns(at, 200, 20.5f, 1, 1.5f, RED, 0, false);
            list.end();
            rig.frame(ui, null, list);
            assertThat(rig.at(100, 55, 0)).isCloseTo(1, CLOSE);
            for (int k = 190; k < 200; k++) { // the newest ten columns rise
                list.columnVertex(at + k, 20, 30, 10, 30);
            }
            rig.frame(ui, null, list);
            assertThat(ui.uploadedBytes()).as("ten vertices").isEqualTo(10L * DrawList.VERTEX_FLOATS * 4);
            assertThat(rig.at(215, 12, 0)).isCloseTo(1, CLOSE);
            assertThat(rig.at(205, 12, 0)).isZero();
            int[] n = {0};
            DrawList[] layers = {list};
            Runnable frame = () -> {
                int k = 150 + n[0]++ % 40;
                list.columnVertex(at + k, 20 + n[0] % 3, 30, 10, 30);
                ui.submit(layers, 1, null, W, H);
            };
            for (int i = 0; i < 2000; i++) {
                frame.run();
            }
            AllocationAssertions.assertAllocationFree(frame);
        }
    }
}
