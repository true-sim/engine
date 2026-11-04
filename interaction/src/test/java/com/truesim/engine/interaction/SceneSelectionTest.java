package com.truesim.engine.interaction;

import static org.assertj.core.api.Assertions.assertThat;

import com.truesim.scene.core.NodeHandle;
import com.truesim.scene.core.Pickable;
import com.truesim.scene.core.SceneEventType;
import com.truesim.scene.core.Selected;
import org.junit.jupiter.api.Test;

class SceneSelectionTest {

    private static boolean selected(SceneInteractionTest.Rig r, long node) {
        return r.scene.components().has(node, Selected.TYPE);
    }

    @Test
    void clicksSelectObjectsAloneAddedOrToggledAndNothingClears() {
        SceneInteractionTest.Rig r = new SceneInteractionTest.Rig();
        SceneSelection selection = r.interaction.selection().enabled(true);
        long car = r.scene.create();
        r.scene.components().set(car, Pickable.TYPE, Pickable.bounds()); // a model spawned pickable: its root too
        long body = r.box(car, 0, 0, -10, 1);
        long crate = r.box(NodeHandle.NONE, 3, 0, -10, 1);
        int[] changes = {0};
        selection.listen(s -> changes[0]++);
        r.frame().click();
        assertThat(selected(r, car)).as("the object, not the part").isTrue();
        assertThat(selected(r, body)).isFalse();
        assertThat(selection.primary()).isEqualTo(car);
        assertThat(selection.world()).isSameAs(r.world);
        float[] onCrate = SceneInteractionTest.project(r, 3, 0, -10);
        r.at(onCrate[0], onCrate[1]).click();
        assertThat(selected(r, car)).as("alone").isFalse();
        assertThat(selected(r, crate)).isTrue();
        r.modifiers = ScenePointer.SHIFT;
        r.at(400, 300).click();
        assertThat(selection.count(r.world)).as("added").isEqualTo(2);
        r.modifiers = ScenePointer.CTRL;
        r.click();
        assertThat(selected(r, car)).as("toggled out").isFalse();
        assertThat(selection.primary()).isEqualTo(NodeHandle.NONE);
        r.click();
        assertThat(selected(r, car)).as("toggled in").isTrue();
        r.at(5, 5).click();
        assertThat(selection.count(r.world)).as("a modifier held: kept").isEqualTo(2);
        r.modifiers = 0;
        r.click();
        assertThat(selection.count(r.world)).as("nothing clicked: nothing selected").isZero();
        assertThat(changes[0]).isEqualTo(6);
        // a consumed click does not select; a disabled selection neither
        r.world.on(crate, SceneEventType.CLICK, SceneEvent::consume);
        r.at(onCrate[0], onCrate[1]).click();
        assertThat(selected(r, crate)).isFalse();
        selection.enabled(false);
        assertThat(selection.enabled()).isFalse();
        r.at(400, 300).click();
        assertThat(selected(r, car)).isFalse();
    }

    @Test
    void marqueesSelectWhatTheyCover() {
        SceneInteractionTest.Rig r = new SceneInteractionTest.Rig();
        SceneSelection selection = r.interaction.selection().enabled(true);
        long left = r.box(NodeHandle.NONE, -2, 0, -10, 0.5);
        long right = r.box(NodeHandle.NONE, 2, 0, -10, 0.5);
        long far = r.box(NodeHandle.NONE, 6.5, 0, -10, 0.5);
        float[] a = SceneInteractionTest.project(r, -3, 1, -10);
        float[] b = SceneInteractionTest.project(r, 3, -1, -10);
        r.at(a[0], a[1]).press();
        r.at(b[0], b[1]);
        assertThat(selection.marqueeActive()).isTrue();
        float[] rect = new float[4];
        assertThat(selection.marquee(rect)).isTrue();
        assertThat(rect[0]).isEqualTo(a[0]);
        r.release();
        assertThat(selection.marqueeActive()).isFalse();
        assertThat(selection.marquee(rect)).isFalse();
        assertThat(selected(r, left)).isTrue();
        assertThat(selected(r, right)).isTrue();
        assertThat(selected(r, far)).isFalse();
        // toggling: those covered flip
        r.modifiers = ScenePointer.CTRL;
        float[] c = SceneInteractionTest.project(r, 1, 1, -10);
        float[] d = SceneInteractionTest.project(r, 7.5, -1, -10);
        r.at(c[0], c[1]).press();
        r.at(d[0], d[1]).release();
        assertThat(selected(r, left)).isTrue();
        assertThat(selected(r, right)).isFalse();
        assertThat(selected(r, far)).isTrue();
        // a pointer leaving cancels its marquee
        r.modifiers = 0;
        r.at(a[0], a[1]).press();
        r.at(b[0], b[1]);
        r.mouse = false;
        r.frame();
        assertThat(selected(r, far)).as("unchanged").isTrue();
    }

    @Test
    void marqueesWorkThroughOrthographicCamerasAndTinyOnesSelectNothing() {
        SceneInteractionTest.Rig r = new SceneInteractionTest.Rig();
        SceneSelection selection = r.interaction.selection().enabled(true);
        long a = r.box(NodeHandle.NONE, -1, 0, -10, 0.25);
        long b = r.box(NodeHandle.NONE, 3, 0, -10, 0.25);
        r.frame();
        selection.select(r.world, b, false);
        selection.selectIn(r.view, 10, 10, 10.5f, 10.5f, false, false);
        assertThat(selection.count(r.world)).as("too small to cover anything; cleared").isZero();
        r.camera.setOrthographic(0, 0, 0, 0, 0, -1, 0, 1, 0, 4, 3, 100, 800, 600);
        selection.selectIn(r.view, 0, 0, 400, 600, false, false); // the left half: x from -4 to 0
        assertThat(selection.selected(r.world, a)).isTrue();
        assertThat(selection.selected(r.world, b)).isFalse();
        assertThat(selection.primary()).isEqualTo(a);
        r.scene.remove(a);
        assertThat(selection.primary()).as("gone").isEqualTo(NodeHandle.NONE);
        selection.selectIn(r.view, 400, 0, 800, 600, true, false);
        assertThat(selection.selected(r.world, b)).isTrue();
    }

    @Test
    void commandsSelectAllOrNone() {
        SceneInteractionTest.Rig r = new SceneInteractionTest.Rig();
        boolean[] all = {false};
        boolean[] none = {false};
        SceneSelection selection = r.interaction.selection().enabled(true).input(new SceneSelection.Input() {
            @Override
            public boolean add(int player) {
                return false;
            }

            @Override
            public boolean toggle(int player) {
                return false;
            }

            @Override
            public boolean all() {
                return all[0];
            }

            @Override
            public boolean none() {
                return none[0];
            }
        });
        long a = r.box(NodeHandle.NONE, 0, 0, -10, 1);
        long b = r.box(NodeHandle.NONE, 3, 0, -10, 1);
        r.box(NodeHandle.NONE, 6, 0, -10, 1);
        r.scene.components().get(b, Pickable.TYPE).enabled(false);
        all[0] = true;
        r.frame();
        all[0] = false;
        assertThat(selection.count(r.world)).as("the enabled ones").isEqualTo(2);
        assertThat(selection.selected(r.world, a)).isTrue();
        none[0] = true;
        r.frame();
        assertThat(selection.count(r.world)).isZero();
        long version = selection.version();
        selection.select(r.world, a, false);
        selection.deselect(r.world, a);
        selection.deselect(r.world, a);
        assertThat(selection.version()).isEqualTo(version + 2);
    }
}
