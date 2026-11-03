package com.truesim.engine.input;

import static com.truesim.engine.input.Rig.GAME;
import static com.truesim.engine.input.Rig.JUMP;
import static com.truesim.engine.input.Rig.MOVE;
import static org.assertj.core.api.Assertions.assertThat;

import com.truesim.engine.app.Clock;
import com.truesim.engine.platform.GamepadButton;
import com.truesim.engine.platform.Key;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Edges counted per world clock, per player and per action (D60), action filters per step (D84, D85). */
class EdgesTest {

    /** Logs what each fixed step sees of JUMP: "world tick presses releases". */
    private static List<String> stepLog(Rig rig) {
        List<String> log = new ArrayList<>();
        rig.onStep = step -> log.add(step.clock().name() + " " + step.tick() + " " + rig.one().presses(JUMP) + " "
            + rig.one().releases(JUMP));
        return log;
    }

    @Test
    void aFixedStepSeesTheEdgesSinceItsLastStep() {
        try (Rig rig = new Rig(GAME)) {
            Clock world = rig.engine.addClock("w").rate(100); // a step every 10 ms
            List<String> steps = stepLog(rig);
            rig.frame(5);                                // t 0: no steps
            rig.key(Key.SPACE, true).frame(5);           // t 5 ms: pressed, no step yet
            assertThat(rig.one().pressed(JUMP)).isTrue();
            assertThat(steps).isEmpty();
            rig.frame(5);                                // t 10 ms: the first step sees the press of the frame before
            assertThat(rig.one().pressed(JUMP)).isFalse(); // the frame's readers saw it already
            assertThat(steps).containsExactly("w 1 1 0");
            steps.clear();
            rig.key(Key.SPACE, false).frame(35);         // t 15 ms: released, no step
            rig.frame(5);                                // t 50 ms: a catch-up frame of four steps
            assertThat(steps).containsExactly("w 2 0 1", "w 3 0 0", "w 4 0 0", "w 5 0 0"); // seen once, by the first
            steps.clear();
            // Several presses between two steps all count.
            rig.tap(Key.SPACE).frame(2);                 // t 55 ms: down for a frame
            rig.frame(2);                                // t 57 ms: up
            rig.tap(Key.SPACE).frame(2);                 // t 59 ms: down again
            rig.frame(10);                               // t 61 ms: up, and a step
            assertThat(steps).containsExactly("w 6 2 2");
            assertThat(world.tick()).isEqualTo(6);
        }
    }

    @Test
    void eachWorldSeesAPressOnce() {
        try (Rig rig = new Rig(GAME)) {
            rig.engine.addClock("a").rate(100);
            rig.engine.addClock("b").rate(50);
            List<String> steps = stepLog(rig);
            rig.frame(20);
            rig.key(Key.SPACE, true).frame(20);
            assertThat(steps).containsExactly("a 1 1 0", "a 2 0 0", "b 1 1 0");
        }
    }

    @Test
    void pausingAWorldClearsItsEdges() { // D60, D128
        try (Rig rig = new Rig(GAME)) {
            Clock world = rig.engine.addClock("w").rate(100);
            List<String> steps = stepLog(rig);
            rig.frame(10).frame(10);
            assertThat(steps).containsExactly("w 1 0 0");
            steps.clear();
            world.pause();
            rig.key(Key.SPACE, true).frame(10);       // pressed while paused: the update sees it
            assertThat(rig.one().pressed(JUMP)).isTrue();
            rig.key(Key.SPACE, false).frame(10);
            world.resume();
            rig.frame(10);                            // resumed: no stale press or release
            assertThat(steps).containsExactly("w 2 0 0");
            steps.clear();
            rig.key(Key.SPACE, true).frame(10);       // a press after resuming is seen
            assertThat(steps).containsExactly("w 3 1 0");
        }
    }

    @Test
    void edgesArePerPlayer() { // D60, D63
        try (Rig rig = new Rig(GAME)) {
            Player two = rig.input.addPlayer();
            rig.plug(1).plug(2).frame();
            assertThat(rig.pad(1).player()).isSameAs(rig.one()); // the first player without a pad
            assertThat(rig.pad(2).player()).isSameAs(two);
            rig.pad(2, GamepadButton.SOUTH, true).frame();
            assertThat(two.pressed(JUMP)).isTrue();
            assertThat(rig.one().pressed(JUMP)).isFalse();
            rig.key(Key.SPACE, true).frame();         // the keyboard is the first player's
            assertThat(rig.one().pressed(JUMP)).isTrue();
            assertThat(two.pressed(JUMP)).isFalse();
        }
    }

    @Test
    void actionFiltersRunPerStepOnTheStepsDeltaAndPerFrameOnTheFrames() { // D84, D85
        try (Rig rig = new Rig(GAME)) {
            rig.engine.addClock("w").rate(100);
            List<String> calls = new ArrayList<>();
            float[] aim = {0.5f}; // game state a filter reads
            rig.input.filter(MOVE, new ActionFilter() {
                @Override
                public int stateSize() {
                    return 1;
                }

                @Override
                public void apply(Player player, float[] value, float[] state, int at, double seconds) {
                    state[at] += (float) seconds;
                    value[0] *= aim[0];
                    calls.add(String.format(java.util.Locale.ROOT, "%.3f %.3f", seconds, state[at]));
                }
            });
            List<Float> stepped = new ArrayList<>();
            rig.onStep = step -> {
                stepped.add(rig.one().x(MOVE));
                stepped.add(rig.one().x(MOVE)); // read twice in a step: filtered once
            };
            rig.onUpdate = frame -> rig.one().x(MOVE);
            rig.frame(25);
            rig.key(Key.D, true).frame(25);
            assertThat(stepped).containsExactly(0.5f, 0.5f, 0.5f, 0.5f);
            // frame 0 (delta 0), then frame 1: two steps of 0.01 s on their own state, the frame's 0.025 s on its own
            assertThat(calls).containsExactly("0.000 0.000", "0.010 0.010", "0.010 0.020", "0.025 0.025");
            calls.clear();
            rig.engine.focusChanged(false);
            rig.frame(25);                             // focus lost: the filters' state reset (D86)
            assertThat(calls).contains("0.010 0.010");
        }
    }
}
