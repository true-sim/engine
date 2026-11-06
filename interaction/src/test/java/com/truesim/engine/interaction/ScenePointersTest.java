package com.truesim.engine.interaction;

import static org.assertj.core.api.Assertions.assertThat;

import com.truesim.common.state.ChangeClock;
import com.truesim.engine.app.AppConfig;
import com.truesim.engine.app.Application;
import com.truesim.engine.app.Engine;
import com.truesim.engine.input.Input;
import com.truesim.engine.platform.Key;
import com.truesim.engine.platform.MouseButton;
import com.truesim.engine.platform.PenState;
import com.truesim.engine.platform.TouchPhase;
import com.truesim.engine.render.Camera;
import com.truesim.scene.core.NodeHandle;
import com.truesim.scene.core.Pickable;
import com.truesim.scene.core.SceneEventType;
import com.truesim.scene.core.SceneGraph;
import com.truesim.scene.core.Selected;
import com.truesim.scene.core.SpatialIndex;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The plugin in an engine with input and no desktop: its settings, actions and pointer sources fed events. */
class ScenePointersTest {

    private static final long MS = 1_000_000L;

    private final Input input = new Input();
    private final SceneInteraction interaction = new SceneInteraction();
    private final Engine engine = Engine.create(new Application() {
        @Override
        public void configure(AppConfig app) {
            app.use(input, interaction).defaults(Engine.FRAME_RATE, 10_000);
        }
    });
    private long now;
    private final SceneGraph scene = new SceneGraph(new ChangeClock());
    private final InteractionWorld world = interaction.world(scene, new SpatialIndex(scene));
    private final List<String> log = new ArrayList<>();

    ScenePointersTest() {
        Camera camera = new Camera().position(0, 0, 0).set(0, 0, -1, 0, 1, 0, Math.PI / 3, 800, 600, 0.1);
        world.view(camera).rect(0, 0, 800, 600);
    }

    private void frame() {
        engine.stepAt(now);
        now += 16 * MS;
    }

    private long box(double x) {
        long node = scene.create();
        scene.setTranslation(node, x, 0, -10);
        scene.setLocalBounds(node, -1, -1, -1, 1, 1, 1);
        scene.components().set(node, Pickable.TYPE, Pickable.bounds());
        world.on(node, SceneEventType.ALL_MASK, e -> log.add(e.pointerKind() + " " + e.type()));
        return node;
    }

    @Test
    void thePluginRegistersItsSettingsAndActions() {
        frame();
        assertThat(engine.settings().get(SceneInteraction.HITS)).isEqualTo(4);
        assertThat(input.has(SceneActions.PRESS)).isTrue();
        assertThat(engine.get(SceneInteraction.KEY)).isSameAs(interaction);
        assertThat(interaction.driven()).isFalse();
        assertThat(interaction.worldOf(scene)).isSameAs(world);
        assertThat(interaction.world(world.picker())).isSameAs(world);
        assertThat(interaction.worldCount()).isOne();
        assertThat(interaction.world(0)).isSameAs(world);
        engine.close();
    }

    @Test
    void theMouseClicksThroughItsActionsAndTheSelectionReadsItsModifiers() {
        long a = box(0);
        long b = box(4);
        interaction.selection().enabled(true).input(SceneSelection.Input.of(input));
        ScenePointers.Mouse mouse = new ScenePointers.Mouse(null, input);
        interaction.pointers(mouse);
        mouse.mouseMoved(0, 1, 0, 400, 300, 0, 0, 0);
        mouse.mouseMoved(0, 1, -1, 10, 10, 0, 0, 0); // made from a touch: the finger's own pointer stands for it
        mouse.mouseMoved(0, 1, -2, 10, 10, 0, 0, 0); // made from a pen
        mouse.mouseWheel(0, 1, -1, 0, 5);
        input.events().key(0, 1, 0, Key.LEFT_CONTROL, 0, 0, true, false);
        input.events().key(0, 1, 0, Key.LEFT_ALT, 0, 0, true, false);
        input.events().key(0, 1, 0, Key.LEFT_META, 0, 0, true, false);
        frame();
        assertThat(interaction.pointerById(ScenePointers.MOUSE_ID).modifierBits()).isEqualTo(
            ScenePointer.CTRL | ScenePointer.ALT | ScenePointer.META);
        assertThat(interaction.pointerById(ScenePointers.MOUSE_ID).x()).isEqualTo(400);
        input.events().key(0, 1, 0, Key.LEFT_CONTROL, 0, 0, false, false);
        input.events().key(0, 1, 0, Key.LEFT_ALT, 0, 0, false, false);
        input.events().key(0, 1, 0, Key.LEFT_META, 0, 0, false, false);
        frame();
        input.events().mouseButton(0, 1, 0, MouseButton.LEFT, true, 1, 400, 300);
        frame();
        input.events().mouseButton(0, 1, 0, MouseButton.LEFT, false, 1, 400, 300);
        mouse.mouseWheel(0, 1, 0, 0, 1);
        frame();
        assertThat(log).contains("MOUSE ENTER", "MOUSE PRESS", "MOUSE CLICK", "MOUSE WHEEL");
        assertThat(scene.components().has(a, Selected.TYPE)).isTrue();
        // shift held: added
        float bx = SceneInteractionTest.project(null, 4, 0, -10)[0];
        mouse.mouseMoved(0, 1, 0, bx, 300, 0, 0, 0);
        input.events().key(0, 1, 0, Key.LEFT_SHIFT, 0, 0, true, false);
        frame();
        input.events().mouseButton(0, 1, 0, MouseButton.LEFT, true, 1, bx, 300);
        frame();
        input.events().mouseButton(0, 1, 0, MouseButton.LEFT, false, 1, bx, 300);
        frame();
        assertThat(scene.components().has(a, Selected.TYPE)).isTrue();
        assertThat(scene.components().has(b, Selected.TYPE)).isTrue();
        assertThat(interaction.pointerById(ScenePointers.MOUSE_ID).modifierBits() & ScenePointer.SHIFT).isNotZero();
        // escape: none
        input.events().key(0, 1, 0, Key.LEFT_SHIFT, 0, 0, false, false);
        input.events().key(0, 1, 0, Key.ESCAPE, 0, 0, true, false);
        frame();
        assertThat(scene.components().store(Selected.TYPE).size()).isZero();
        // the secondary button: context
        log.clear();
        input.events().mouseButton(0, 1, 0, MouseButton.RIGHT, true, 1, bx, 300);
        frame();
        assertThat(log).contains("MOUSE CONTEXT");
        engine.close();
    }

    @Test
    void fingersPensAndTheVirtualCursorArePointersToo() {
        box(0);
        ScenePointers.Touch touch = new ScenePointers.Touch(null).size(800, 600);
        ScenePointers.Pen pen = new ScenePointers.Pen(null);
        boolean[] cursorOn = {false};
        ScenePointers.VirtualCursor cursor = new ScenePointers.VirtualCursor(input, () -> cursorOn[0], () -> 1000, null)
            .size(800, 600);
        interaction.pointers(touch).pointers(pen).pointers(cursor);
        touch.touch(0, 1, 7, 1, TouchPhase.DOWN, 0.5f, 0.5f, 0, 0, 1);
        frame();
        touch.touch(0, 1, 7, 1, TouchPhase.UP, 0.5f, 0.5f, 0, 0, 0);
        frame();
        frame();
        assertThat(log).contains("TOUCH ENTER", "TOUCH PRESS", "TOUCH CLICK", "TOUCH EXIT");
        log.clear();
        // a tap within a frame: pressed for one poll
        touch.touch(0, 1, 7, 2, TouchPhase.DOWN, 0.5f, 0.5f, 0, 0, 1);
        touch.touch(0, 1, 7, 2, TouchPhase.UP, 0.5f, 0.5f, 0, 0, 0);
        touch.touch(0, 1, 7, 3, TouchPhase.MOVED, 0.5f, 0.5f, 0, 0, 0); // a finger never down: ignored
        frame();
        frame();
        assertThat(log).contains("TOUCH PRESS", "TOUCH CLICK");
        log.clear();
        pen.penProximity(0, 1, 0, true);
        pen.penMoved(0, 1, 0, 400, 300, 0);
        frame();
        pen.penTouch(0, 1, 0, 400, 300, false, true);
        frame();
        pen.penTouch(0, 1, 0, 400, 300, false, false);
        frame();
        pen.penMoved(0, 1, 0, 400, 300, PenState.BUTTON1);
        frame();
        pen.penProximity(0, 1, 0, false);
        frame();
        assertThat(log).contains("PEN ENTER", "PEN CLICK", "PEN CONTEXT", "PEN EXIT");
        log.clear();
        cursorOn[0] = true;
        frame();
        frame();
        assertThat(log).contains("VIRTUAL_CURSOR ENTER");
        cursorOn[0] = false;
        frame();
        assertThat(log).contains("VIRTUAL_CURSOR EXIT");
        assertThat(interaction.pointerById(ScenePointers.LEFT_RAY_ID)).as("no headset: no rays").isNull();
        engine.close();
    }

    @Test
    void aDrivenInteractionRestsUntilItsDriverRunsIt() {
        long node = box(0);
        interaction.driven(true);
        frame();
        assertThat(interaction.pointerCount()).isZero();
        interaction.beginFrame(now);
        interaction.pointer(9, PointerKind.MOUSE).moveTo(400, 300);
        interaction.process();
        assertThat(interaction.pointerById(9).hovered()).isEqualTo(node);
        assertThat(interaction.pointer(0).toString()).isEqualTo("MOUSE pointer 9");
        assertThat(interaction.dispatches()).isPositive();
        assertThat(NodeHandle.NONE).isZero();
        engine.close();
    }
}
