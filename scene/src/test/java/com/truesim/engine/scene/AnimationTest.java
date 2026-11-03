package com.truesim.engine.scene;

import static com.truesim.testing.AllocationAssertions.assertAllocationFree;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.within;

import com.truesim.common.state.ChangeClock;
import com.truesim.engine.app.AppConfig;
import com.truesim.engine.app.Application;
import com.truesim.engine.app.Clock;
import com.truesim.engine.app.Engine;
import com.truesim.engine.app.Frame;
import com.truesim.engine.app.Phase;
import com.truesim.engine.app.Step;
import com.truesim.scene.animation.AnimationClip;
import com.truesim.scene.animation.Animator;
import com.truesim.scene.animation.Animators;
import com.truesim.scene.animation.Skeleton;
import com.truesim.scene.animation.Track;
import com.truesim.scene.core.SceneGraph;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The animation plugin running worlds' animators in ANIMATE, and world transforms after fixed steps (D123). */
class AnimationTest {

    private static final long MS = 1_000_000L;

    /** A hip with a foot 1 m to its side. */
    private static Skeleton legs() {
        double[] rest = new double[20];
        for (int j = 0; j < 2; j++) {
            rest[10 * j + 6] = 1;
            rest[10 * j + 7] = 1;
            rest[10 * j + 8] = 1;
            rest[10 * j + 9] = 1;
        }
        rest[10] = 1; // the foot's x
        return new Skeleton(new String[] {"hip", "foot"}, new int[] {-1, 0}, rest);
    }

    /** The hip sliding along x at 10 m/s for 100 s. */
    private static final AnimationClip SLIDE = new AnimationClip("slide", List.of(new Track("hip", 0,
        Track.Property.TRANSLATION, Track.Interpolation.LINEAR, new float[] {0, 100}, new float[] {0, 0, 0, 1000, 0, 0},
        0)));

    /** A game on the animation plugin, logging where the foot is in each phase. */
    static class Game implements Application {
        final List<String> log = new ArrayList<>();
        final Animation animation = new Animation();
        final int threads;
        final SceneGraph scene = new SceneGraph(new ChangeClock());
        boolean withClock;
        Engine engine;
        Clock clock;
        Animator animator;
        Animators animators;
        boolean quiet;
        AnimationClip clip = SLIDE; // played from the second frame
        long foot;

        Game(int threads) {
            this.threads = threads;
        }

        @Override
        public void configure(AppConfig app) {
            app.use(animation).defaults(Engine.FRAME_RATE, 10_000).defaults(Animation.THREADS, threads);
        }

        @Override
        public void start(Engine started) {
            engine = started;
            clock = withClock ? engine.addClock("world").rate(10) : null;
            long hip = scene.create();
            foot = scene.create(hip);
            scene.setTranslation(foot, 1, 0, 0);
            scene.update();
            animators = animation.animators(scene, clock);
            animator = animators.add(legs(), new long[] {hip, foot});
        }

        @Override
        public void update(Frame frame) {
            if (frame.number() == 1) {
                animator.play(clip);
            }
            if (!quiet) {
                log.add("update " + footX());
            }
        }

        @Override
        public void lateUpdate(Frame frame) {
            if (!quiet) {
                log.add("late " + footX());
            }
        }

        long footX() {
            return Math.round(scene.world(foot).translation().x() * 1000);
        }
    }

    @Test
    void animatorsRunInAnimateBetweenUpdateAndLateWithWorldTransformsUpToDate() {
        Game game = new Game(1);
        try (Engine engine = Engine.create(game)) {
            engine.stepAt(0);
            engine.stepAt(100 * MS); // the game starts the clip in UPDATE; ANIMATE moves it on by the frame: 1 m
            engine.stepAt(200 * MS); // 0.1 s later: 2 m along
            engine.stepAt(250 * MS);
            // the foot's world x, in millimetres: the late update sees the frame's pose, the update the frame before's
            assertThat(game.log).containsExactly("update 1000", "late 1000", "update 1000", "late 2000",
                "update 2000", "late 3000", "update 3000", "late 3500");
            assertThat(game.animation.worldCount()).isEqualTo(1);
            assertThat(game.animation.lastNanos(game.animators)).isPositive();
            assertThat(game.animation.lastNanos(new Animators(new SceneGraph(new ChangeClock())))).isEqualTo(-1);
        }
    }

    @Test
    void aWorldOnAClockAnimatesByItsDrawnTime() {
        Game game = new Game(1);
        game.withClock = true;
        try (Engine engine = Engine.create(game)) {
            engine.settings().set(Engine.FIXED_STEP_BUDGET, 1);
            engine.stepAt(0);
            engine.stepAt(100 * MS); // plays from here
            engine.stepAt(350 * MS); // capped to 0.25 s: 2.5 ticks owed, the budget takes one, drops one
            // drawn time moved by one tick and the half left over: 0.15 s, not the frame's 0.25
            assertThat(game.clock.frameDelta()).isCloseTo(0.15, within(1e-9));
            assertThat(game.log.get(game.log.size() - 1)).isEqualTo("late 3500"); // 2000 + 1.5 m, not + 2.5
        }
    }

    private static com.truesim.engine.settings.EngineSettings settings(int threads) {
        com.truesim.engine.settings.EngineSettings settings = new com.truesim.engine.settings.EngineSettings();
        settings.set(Animation.THREADS, threads);
        return settings;
    }

    @Test
    void theThreadsSettingChoosesTheJobSystem() {
        for (int threads : new int[] {1, 3}) {
            Game game = new Game(threads);
            try (Engine engine = Engine.create(game)) {
                assertThat(game.animation.threadCount()).isEqualTo(threads);
            }
        }
        Game every = new Game(0); // every processor
        try (Engine engine = Engine.create(every)) {
            assertThat(every.animation.threadCount()).isEqualTo(Math.max(1, Runtime.getRuntime().availableProcessors()));
        }
    }

    @Test
    void parallelSamplingGivesTheSerialResult() {
        double[] serial = crowd(1);
        double[] parallel = crowd(4);
        assertThat(parallel).containsExactly(serial);
    }

    /** 200 sliding animators at their own speeds after 30 frames on {@code threads}: every foot's world x. */
    private static double[] crowd(int threads) {
        Animation animation = new Animation(settings(threads));
        SceneGraph scene = new SceneGraph(new ChangeClock());
        Animators animators = animation.animators(scene);
        long[] feet = new long[200];
        for (int i = 0; i < feet.length; i++) {
            long hip = scene.create();
            feet[i] = scene.create(hip);
            animators.add(legs(), new long[] {hip, feet[i]}).play(SLIDE).speed(0.5 + i * 0.01);
        }
        for (int f = 0; f < 30; f++) {
            animation.advance(1 / 60.0);
        }
        animation.stop();
        double[] x = new double[feet.length];
        for (int i = 0; i < feet.length; i++) {
            x[i] = scene.world(feet[i]).translation().x();
        }
        return x;
    }

    @Test
    void worldTransformsArePropagatedAfterEveryFixedStep() {
        List<String> log = new ArrayList<>();
        SceneGraph scene = new SceneGraph(new ChangeClock());
        long parent = scene.create();
        long child = scene.create(parent);
        scene.setTranslation(child, 0, 1, 0);
        scene.update();
        Application moving = new Application() {
            @Override
            public void configure(AppConfig app) {
                app.defaults(Engine.FRAME_RATE, 10_000);
            }

            @Override
            public void start(Engine engine) {
                engine.addClock("world").afterStep(scene::update); // D123
            }

            @Override
            public void fixedUpdate(Step step) {
                // reads the child's world position the step before left, then moves the parent on
                log.add(step.tick() + ": " + scene.world(child).translation().x());
                scene.setTranslation(parent, step.tick(), 0, 0);
            }
        };
        try (Engine engine = Engine.create(moving)) {
            engine.stepAt(0);
            engine.stepAt(3 * 16_666_667L + 1); // a catch-up frame of three steps
            assertThat(log).containsExactly("1: 0.0", "2: 1.0", "3: 2.0");
        }
    }

    @Test
    void aSceneHasOneSetOfAnimatorsAndRemovedWorldsStop() {
        Animation animation = new Animation(settings(1));
        SceneGraph scene = new SceneGraph(new ChangeClock());
        Animators animators = animation.animators(scene);
        assertThatIllegalArgumentException().isThrownBy(() -> animation.animators(scene));
        assertThat(animation.lastNanos(animators)).isZero();
        assertThat(animation.remove(animators)).isTrue();
        assertThat(animation.remove(animators)).isFalse();
        assertThat(animation.worldCount()).isZero();
        assertThat(Phase.ANIMATE.ordinal()).isBetween(Phase.UPDATE.ordinal(), Phase.LATE.ordinal());
    }

    @Test
    void framesAnimatingWorldsAllocateNothing() {
        Game game = new Game(2);
        game.withClock = true;
        try (Engine engine = Engine.create(game)) {
            game.quiet = true;
            game.animator.play(SLIDE).loop(true);
            long[] now = {0};
            assertAllocationFree(() -> {
                now[0] += 7 * MS;
                engine.stepAt(now[0]);
            });
            game.animator.fixedSteps(true); // in the world's fixed steps
            assertAllocationFree(() -> {
                now[0] += 7 * MS;
                engine.stepAt(now[0]);
            });
        }
    }

    /** The hip turning about y, 3 rad a second, and sliding as {@link #SLIDE}: a rotation slerped between keys. */
    private static final AnimationClip TURN = new AnimationClip("turn", List.of(
        new Track("hip", 0, Track.Property.TRANSLATION, Track.Interpolation.LINEAR, new float[] {0, 100},
            new float[] {0, 0, 0, 1000, 0, 0}, 0),
        new Track("hip", 0, Track.Property.ROTATION, Track.Interpolation.LINEAR, new float[] {0, 0.5f, 1},
            new float[] {0, 0, 0, 1, 0, (float) Math.sin(0.75), 0, (float) Math.cos(0.75), 0, 1, 0, 0}, 0)));

    @Test
    void animatorsOnFixedStepsRunInTheStepsAndGiveTheSamePosesWhateverTheFrameRate() {
        // The same 60 steps (1 s at 60 a second) in frames of 7 ms, and in frames of 3 to 40 ms: each step's pose, read
        // after it (the clock's hook), is the same to the bit; the frame's ANIMATE leaves it alone.
        double[] even = steppedPoses(new long[] {7});
        double[] uneven = steppedPoses(new long[] {3, 40, 11, 29, 17, 5, 33});
        assertThat(uneven).containsExactly(even);
        assertThat(even[3 * 59]).as("the hip slid 10 m a second").isCloseTo(10.0, within(1e-6));
    }

    /** Each step's hip x and foot x and z (world) for 60 steps of a fixed animator, frames as long as {@code frames}. */
    private static double[] steppedPoses(long[] frames) {
        double[] poses = new double[3 * 60];
        Game game = new Game(2);
        game.withClock = true;
        game.quiet = true;
        try (Engine engine = Engine.create(game)) {
            game.clock.rate(60).afterStep(game.scene::update).afterStep(() -> {
                int at = 3 * (int) (game.clock.tick() - 1);
                if (at < poses.length) {
                    long hip = game.animator.node(0);
                    poses[at] = game.scene.world(hip).translation().x();
                    poses[at + 1] = game.scene.world(game.foot).translation().x();
                    poses[at + 2] = game.scene.world(game.foot).translation().z();
                }
            });
            game.clip = TURN;
            game.animator.fixedSteps(true);
            long now = 0;
            int f = 0;
            engine.stepAt(0);
            while (game.clock.tick() < 60) {
                now += frames[f++ % frames.length] * MS;
                engine.stepAt(Math.min(now, 1_000_000_000L + MS));
                assertThat(game.animator.updated()).isTrue();
            }
        }
        return poses;
    }
}
