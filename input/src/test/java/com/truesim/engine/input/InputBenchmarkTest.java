package com.truesim.engine.input;

import static com.truesim.testing.AllocationAssertions.assertAllocationFree;
import static org.assertj.core.api.Assertions.assertThat;

import com.truesim.engine.app.Clock;
import com.truesim.engine.platform.GamepadAxis;
import com.truesim.engine.platform.GamepadButton;
import com.truesim.engine.platform.Key;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * Nothing allocated per frame (D65), and what a frame of input costs: 200 actions, 4 players with a pad each, events
 * every frame, a world stepping. Prints the time per frame.
 */
class InputBenchmarkTest {

    static final ActionGroup BENCH = ActionGroup.root("bench");
    static final Key[] KEYS = {Key.A, Key.B, Key.C, Key.D, Key.E, Key.F, Key.G, Key.H, Key.I, Key.J, Key.K, Key.L,
        Key.M, Key.N, Key.O, Key.P, Key.Q, Key.R, Key.S, Key.T, Key.U, Key.V, Key.W, Key.X, Key.Y, Key.Z};
    static final GamepadButton[] BUTTONS = GamepadButton.values();
    static final BinaryAction[] BINARY = new BinaryAction[120];
    static final Axis2Action[] MOVES = new Axis2Action[40];
    static final Axis1Action[] TRIGGERS = new Axis1Action[20];
    static final BinaryAction[] TIMED = new BinaryAction[20];

    static {
        for (int i = 0; i < BINARY.length; i++) {
            Binding key = Binding.key(KEYS[i % KEYS.length]);
            if (i % 3 == 1) {
                key = key.with(Controls.CONTROL); // chords taking their parts
            } else if (i % 3 == 2) {
                key = key.with(Controls.SHIFT);
            }
            BINARY[i] = BENCH.binary("b" + i, key, Binding.pad(BUTTONS[i % 15]));
        }
        for (int i = 0; i < MOVES.length; i++) {
            MOVES[i] = BENCH.axis2("m" + i, Binding.keys(Key.A, Key.D, Key.S, Key.W), Binding.stick(i % 2 == 0));
        }
        for (int i = 0; i < TRIGGERS.length; i++) {
            TRIGGERS[i] = BENCH.axis1("t" + i, Binding.pad(GamepadAxis.RIGHT_TRIGGER).filter(Filters.DEADZONE, 0.1f)
                .filter(Filters.SMOOTH, 0.05f).filter(Filters.CURVE, 2));
        }
        for (int i = 0; i < TIMED.length; i++) {
            TIMED[i] = BENCH.binary("h" + i, i % 2 == 0 ? Binding.key(KEYS[i]).tap() : Binding.key(KEYS[i]).hold(),
                Binding.pad(BUTTONS[i % 4]).toggle());
        }
    }

    @Test
    void aFrameOf200ActionsFor4PlayersAllocatesNothing() {
        try (Rig rig = new Rig(BENCH)) {
            setUp(rig);
            int[] frame = {0};
            Runnable step = () -> frame(rig, frame[0]++);
            assertAllocationFree(step); // whole engine frames, a world stepping
            // Timed: the events and input's own frame start (the engine's paced frames would time its pacing).
            Runnable sample = () -> {
                events(rig, frame[0]++);
                rig.input.beforeUpdate(rig.engine.frame());
            };
            double perFrame = time(sample, 50_000);
            Runnable events = () -> events(rig, frame[0]++);
            double eventsOnly = time(events, 50_000);
            // Fixed steps: 1,000 in a frame, each readying its edges (800 counts twice) and reading 120 actions.
            rig.engine.settings().set(com.truesim.engine.app.Engine.FIXED_STEP_BUDGET, 1000);
            rig.engine.settings().set(com.truesim.engine.app.Engine.MAX_FRAME_DELTA, 1f);
            Clock world = rig.engine.addClock("world").rate(10_000);
            rig.frame(100);
            Runnable bigFrame = () -> rig.frame(100);
            double stepping = time(bigFrame, 200) / 1000;
            rig.onStep = s -> { };
            double bare = time(bigFrame, 200) / 1000;
            System.out.printf(Locale.ROOT, "Input, 200 actions, 4 players: %.1f us a frame (feeding events %.1f us "
                + "of it); a fixed step %.2f us with 120 edge reads, %.2f us without (%d steps a frame)%n",
                perFrame * 1e6, eventsOnly * 1e6, stepping * 1e6, bare * 1e6, world.stepsLastFrame());
            assertThat(perFrame).isLessThan(0.001);
            assertThat(BENCH.actions()).hasSize(200);
        }
    }

    private static void setUp(Rig rig) {
        rig.input.addPlayer();
        rig.input.addPlayer();
        rig.input.addPlayer();
        rig.onStep = s -> {
            for (BinaryAction a : BINARY) {
                rig.input.player(s.tick() % 4 == 0 ? 0 : 1).pressed(a);
            }
        };
        for (int p = 1; p <= 4; p++) {
            rig.plug(p);
        }
        rig.frame();
    }

    private static void frame(Rig rig, int n) {
        events(rig, n);
        rig.frame(16);
    }

    /** A few events for every player: keys, buttons, sticks, triggers, the mouse. */
    private static void events(Rig rig, int n) {
        rig.key(KEYS[n % KEYS.length], n % 2 == 0);
        rig.key(Key.LEFT_CONTROL, n % 7 < 3);
        rig.events().mouseMoved(0, 1, 0, 0, 0, n % 5, -1, 0);
        for (int p = 1; p <= 4; p++) {
            rig.pad(p, BUTTONS[(n + p) % 15], (n + p) % 3 == 0);
            rig.axis(p, GamepadAxis.LEFT_X, (n % 20) / 20f);
            rig.axis(p, GamepadAxis.RIGHT_TRIGGER, (n % 10) / 10f);
        }
    }

    private static double time(Runnable step, int frames) {
        for (int i = 0; i < frames; i++) {
            step.run();
        }
        long start = System.nanoTime();
        for (int i = 0; i < frames; i++) {
            step.run();
        }
        return (System.nanoTime() - start) / 1e9 / frames;
    }
}
