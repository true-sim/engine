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
        final Animation animation;
        final SceneGraph scene = new SceneGraph(new ChangeClock());
        boolean withClock;
        Engine engine;
        Clock clock;
        Animator animator;
        Animators animators;
        boolean quiet;
        long foot;

        Game(Animation animation) {
            this.animation = animation;
        }

        @Override
        public void configure(AppConfig app) {
            app.use(animation).defaults(Engine.FRAME_RATE, 10_000);
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
                animator.play(SLIDE);
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
        Game game = new Game(new Animation().threads(1));
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
        Game game = new Game(new Animation().threads(1));
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

    @Test
    void parallelSamplingGivesTheSerialResult() {
        double[] serial = crowd(1);
        double[] parallel = crowd(4);
        assertThat(parallel).containsExactly(serial);
    }

    /** 200 sliding animators at their own speeds after 30 frames on {@code threads}: every foot's world x. */
    private static double[] crowd(int threads) {
        Animation animation = new Animation().threads(threads);
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
        Animation animation = new Animation().threads(1);
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
        Game game = new Game(new Animation().threads(2));
        game.withClock = true;
        try (Engine engine = Engine.create(game)) {
            game.quiet = true;
            game.animator.play(SLIDE).loop(true);
            long[] now = {0};
            assertAllocationFree(() -> {
                now[0] += 7 * MS;
                engine.stepAt(now[0]);
            });
        }
    }
}
