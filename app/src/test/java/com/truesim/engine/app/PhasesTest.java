package com.truesim.engine.app;

import static com.truesim.testing.AllocationAssertions.assertAllocationFree;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The phases of a frame, the world clocks and the fixed-step budget (application-api.md D88–D92, D124). */
class PhasesTest {

    private static final long MS = 1_000_000L;

    /** A plugin logging the phase hooks, with the engine's phase as each runs. */
    static final class Phased implements Plugin {
        static final PluginKey<Phased> KEY = PluginKey.of("phased", Phased.class);
        final List<String> log;
        Engine engine;

        Phased(List<String> log) {
            this.log = log;
        }

        @Override
        public PluginKey<Phased> key() {
            return KEY;
        }

        @Override
        public void start(Stage stage, Startup startup) {
            engine = startup.engine();
        }

        @Override
        public void beforeUpdate(Frame frame) {
            log.add("input " + engine.phase());
        }

        @Override
        public void fixedUpdate(Step step) {
            log.add("plugin fixed " + step.clock().name() + " " + step.tick() + " " + engine.phase());
        }

        @Override
        public void animate(Frame frame) {
            log.add("animate " + engine.phase());
        }

        @Override
        public void lateUpdate(Frame frame) {
            log.add("plugin late " + engine.phase());
        }

        @Override
        public void afterUpdate(Frame frame) {
            log.add("sync " + engine.phase());
        }
    }

    /** An application logging its phase hooks. */
    static class Game implements Application {
        final List<String> log;
        final Plugin[] plugins;
        Engine engine;

        Game(List<String> log, Plugin... plugins) {
            this.log = log;
            this.plugins = plugins;
        }

        @Override
        public void configure(AppConfig app) {
            app.use(plugins).defaults(Engine.FRAME_RATE, 10_000);
        }

        @Override
        public void start(Engine started) {
            engine = started;
        }

        @Override
        public void fixedUpdate(Step step) {
            log.add("fixed " + step.clock().name() + " " + step.tick() + " " + engine.phase());
        }

        @Override
        public void update(Frame frame) {
            log.add("update " + engine.phase());
        }

        @Override
        public void lateUpdate(Frame frame) {
            log.add("late " + engine.phase());
        }
    }

    @Test
    void aFrameRunsInputFixedStepsUpdateAnimateLateThenSync() {
        List<String> log = new ArrayList<>();
        Game game = new Game(log, new Phased(log));
        try (Engine engine = Engine.create(game)) {
            Clock world = engine.addClock("world");
            world.afterStep(() -> log.add("transforms " + world.tick() + " " + engine.phase()));
            engine.stepAt(0);
            assertThat(log).containsExactly("input NONE", "update UPDATE", "animate ANIMATE", "late LATE",
                "plugin late LATE", "sync NONE"); // the first frame has no delta: no steps
            log.clear();
            engine.stepAt(2 * 16_666_667L + 1); // two ticks at 60/s
            assertThat(log).containsExactly("input NONE",
                "fixed world 1 FIXED", "plugin fixed world 1 FIXED", "transforms 1 FIXED",
                "fixed world 2 FIXED", "plugin fixed world 2 FIXED", "transforms 2 FIXED",
                "update UPDATE", "animate ANIMATE", "late LATE", "plugin late LATE", "sync NONE");
            assertThat(engine.phase()).isEqualTo(Phase.NONE);
            assertThat(engine.frame().steps()).isEqualTo(2);
        }
    }

    @Test
    void ticksAccumulateWholeWithoutDrift() {
        List<String> log = new ArrayList<>();
        try (Engine engine = Engine.create(new Game(log))) {
            Clock clock = engine.addClock("w");
            long now = 0;
            engine.stepAt(now);
            // 7 ms frames for 6 seconds: 360 ticks exactly at 60/s, whatever 7 ms is in floating point
            for (int f = 0; f < 6000 / 7; f++) {
                now += 7 * MS;
                engine.stepAt(now);
                assertThat(clock.stepsLastFrame()).isBetween(0, 1);
            }
            now += 6000 * MS - now;
            engine.stepAt(now);
            assertThat(clock.tick()).isEqualTo(360);
            assertThat(clock.blend()).isZero();
            assertThat(clock.time()).isEqualTo(6.0);
            assertThat(clock.droppedTicks()).isZero();
            // Frames of exactly one tick at 50/s (20 ms) step once each, the blend staying 0
            clock.rate(50);
            for (int f = 0; f < 100; f++) {
                now += 20 * MS;
                engine.stepAt(now);
                assertThat(clock.stepsLastFrame()).isEqualTo(1);
                assertThat(clock.blend()).isZero();
            }
            assertThat(clock.time()).isEqualTo(8.0);
            assertThat(clock.stepSeconds()).isEqualTo(0.02);
        }
    }

    @Test
    void theBlendIsTheFractionOfTheNextTickAndRenderTimeMovesByTheFrame() {
        try (Engine engine = Engine.create(new Game(new ArrayList<>()))) {
            Clock clock = engine.addClock("w").rate(10); // 100 ms ticks
            engine.stepAt(0);
            engine.stepAt(250 * MS);
            assertThat(clock.tick()).isEqualTo(2);
            assertThat(clock.blend()).isCloseTo(0.5, org.assertj.core.data.Offset.offset(1e-12));
            assertThat(clock.renderTime()).isCloseTo(0.25, org.assertj.core.data.Offset.offset(1e-12));
            engine.stepAt(290 * MS);
            assertThat(clock.stepsLastFrame()).isZero();
            assertThat(clock.blend()).isCloseTo(0.9, org.assertj.core.data.Offset.offset(1e-12));
            assertThat(clock.frameDelta()).isCloseTo(0.04, org.assertj.core.data.Offset.offset(1e-12));
        }
    }

    @Test
    void aCatchUpFrameRunsEveryTickOwedUpToTheSharedBudgetInWorldOrder() {
        List<String> log = new ArrayList<>();
        Game game = new Game(log);
        try (Engine engine = Engine.create(game)) {
            Clock first = engine.addClock("a");
            Clock second = engine.addClock("b").rate(30);
            engine.stepAt(0);
            log.clear();
            // 100 ms: a owes 6 ticks, b 3; the budget of 8 takes a's six, then two of b's: b's third is dropped
            engine.stepAt(100 * MS);
            assertThat(log).containsExactly("fixed a 1 FIXED", "fixed a 2 FIXED", "fixed a 3 FIXED",
                "fixed a 4 FIXED", "fixed a 5 FIXED", "fixed a 6 FIXED", "fixed b 1 FIXED", "fixed b 2 FIXED",
                "update UPDATE", "late LATE");
            assertThat(engine.frame().steps()).isEqualTo(8);
            assertThat(engine.frame().droppedTicks()).isEqualTo(1);
            assertThat(second.droppedLastFrame()).isEqualTo(1);
            assertThat(second.droppedTicks()).isEqualTo(1);
            assertThat(first.droppedTicks()).isZero();
            assertThat(second.blend()).isZero(); // the dropped tick is gone, the fraction kept (none here)
            // The next frame starts afresh: the dropped tick is not owed again
            log.clear();
            engine.stepAt(100 * MS + 100 * MS / 3 + 1);
            assertThat(log).containsExactly("fixed a 7 FIXED", "fixed a 8 FIXED", "fixed b 3 FIXED",
                "update UPDATE", "late LATE");
            assertThat(engine.frame().droppedTicks()).isZero();
        }
    }

    @Test
    void aStalledFrameIsCappedAndReported() {
        try (Engine engine = Engine.create(new Game(new ArrayList<>()))) {
            engine.settings().set(Engine.FIXED_STEP_BUDGET, 100);
            Clock clock = engine.addClock("w");
            engine.stepAt(0);
            engine.stepAt(3_000 * MS); // a breakpoint: 3 s
            assertThat(engine.frame().capped()).isTrue();
            assertThat(engine.frame().realDelta()).isEqualTo(3.0);
            assertThat(engine.frame().delta()).isEqualTo(0.25);
            assertThat(engine.frame().time()).isEqualTo(0.25);
            assertThat(engine.frame().realTime()).isEqualTo(3.0);
            assertThat(clock.tick()).isEqualTo(15); // 0.25 s at 60/s
            engine.stepAt(3_010 * MS);
            assertThat(engine.frame().capped()).isFalse();
        }
    }

    @Test
    void theTimingSettingsTakeEffectFromTheNextFrame() {
        try (Engine engine = Engine.create(new Game(new ArrayList<>()))) {
            engine.settings().set(Engine.FIXED_STEP_BUDGET, 100);
            Clock following = engine.addClock("following");
            Clock own = engine.addClock("own").rate(20);
            assertThat(following.rate()).isEqualTo(60);
            engine.stepAt(0);
            // The cap: a 0.5 s stall is cut to 0.25 s by default, to 0.5 once the setting allows it
            engine.stepAt(500 * MS);
            assertThat(engine.frame().delta()).isEqualTo(0.25);
            assertThat(engine.frame().capped()).isTrue();
            engine.settings().set(Engine.MAX_FRAME_DELTA, 0.5f);
            engine.stepAt(1_000 * MS);
            assertThat(engine.frame().delta()).isEqualTo(0.5);
            assertThat(engine.frame().capped()).isFalse();
            assertThat(engine.frame().maxDelta()).isEqualTo(0.5);
            // The fixed rate: clocks following it change rate from the next frame, a clock with its own rate keeps it
            long before = following.tick();
            engine.settings().set(Engine.FIXED_RATE, 10);
            engine.stepAt(1_500 * MS);
            assertThat(following.rate()).isEqualTo(10);
            assertThat(following.tick() - before).isEqualTo(5);
            assertThat(own.rate()).isEqualTo(20);
            assertThat(engine.addClock("new").rate()).isEqualTo(10);
            // The budget: the next frame runs no more steps than it allows, the rest dropped
            engine.settings().set(Engine.FIXED_STEP_BUDGET, 3);
            engine.stepAt(2_000 * MS);
            assertThat(engine.frame().steps()).isEqualTo(3);
            assertThat(engine.frame().droppedTicks()).isPositive();
        }
    }

    @Test
    void clocksAreMadeOutsideThePhasesAndRemoved() {
        Application adding = new Application() {
            Engine engine;

            @Override
            public void configure(AppConfig app) {
                app.defaults(Engine.FRAME_RATE, 10_000);
            }

            @Override
            public void start(Engine started) {
                engine = started;
            }

            @Override
            public void update(Frame frame) {
                engine.addClock("too late");
            }
        };
        try (Engine engine = Engine.create(adding)) {
            assertThatIllegalStateException().isThrownBy(() -> engine.stepAt(0)).withMessageContaining("UPDATE");
            assertThat(engine.phase()).isEqualTo(Phase.NONE);
            Clock clock = engine.addClock("w");
            assertThat(engine.removeClock(clock)).isTrue();
            assertThat(engine.removeClock(clock)).isFalse();
            assertThatIllegalArgumentException().isThrownBy(() -> clock.rate(0));
            assertThatIllegalArgumentException().isThrownBy(() -> clock.rate(Clock.MAX_RATE + 1));
        }
    }

    @Test
    void timersCountStepsAndFireInsideTheirStepBeforeTheHooks() {
        List<String> log = new ArrayList<>();
        try (Engine engine = Engine.create(new Game(log))) {
            Clock clock = engine.addClock("w").rate(10);
            clock.afterStep(() -> log.add("transforms " + clock.tick()));
            Timer once = clock.after(2, () -> log.add("once " + clock.tick()));
            Timer every = clock.every(3, () -> log.add("every " + clock.tick()));
            Timer seconds = clock.afterSeconds(0.45, () -> log.add("seconds " + clock.tick())); // 4.5 steps: the 5th
            engine.stepAt(0);
            for (int f = 1; f <= 7; f++) {
                engine.stepAt(f * 100 * MS); // a step a frame
            }
            assertThat(log).filteredOn(s -> !s.startsWith("update") && !s.startsWith("late")).containsExactly(
                "fixed w 1 FIXED", "transforms 1",
                "fixed w 2 FIXED", "once 2", "transforms 2",
                "fixed w 3 FIXED", "every 3", "transforms 3",
                "fixed w 4 FIXED", "transforms 4",
                "fixed w 5 FIXED", "seconds 5", "transforms 5",
                "fixed w 6 FIXED", "every 6", "transforms 6",
                "fixed w 7 FIXED", "transforms 7");
            assertThat(once.pending()).isFalse();
            assertThat(seconds.pending()).isFalse();
            assertThat(every.pending()).isTrue();
            assertThat(every.dueTick()).isEqualTo(9);
            assertThat(clock.timerCount()).isEqualTo(1);
            every.cancel();
            engine.stepAt(1_000 * MS);
            assertThat(log).noneMatch(s -> s.equals("every 9"));
            assertThat(clock.timerCount()).isZero();
            assertThatIllegalArgumentException().isThrownBy(() -> clock.after(0, () -> { }));
            assertThatIllegalArgumentException().isThrownBy(() -> clock.everySeconds(-1, () -> { }));
        }
    }

    @Test
    void framesWithStepsAllocateNothing() {
        try (Engine engine = Engine.create(new Game(new ArrayList<>()) {
            @Override
            public void fixedUpdate(Step step) {
            }

            @Override
            public void update(Frame frame) {
            }

            @Override
            public void lateUpdate(Frame frame) {
            }
        }, new String[0])) {
            // (paced at 10,000 frames a second: the measured frames wait 0.1 ms each)
            Clock a = engine.addClock("a");
            engine.addClock("b").rate(120);
            a.afterStep(() -> { });
            a.every(2, () -> { });
            long[] now = {0};
            assertAllocationFree(() -> {
                now[0] += 21 * MS;
                engine.stepAt(now[0]);
            });
        }
    }
}
