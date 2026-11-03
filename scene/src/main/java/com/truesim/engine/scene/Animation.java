package com.truesim.engine.scene;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.jobs.JobSystem;
import com.truesim.engine.app.Clock;
import com.truesim.engine.app.Frame;
import com.truesim.engine.app.Phase;
import com.truesim.engine.app.Plugin;
import com.truesim.engine.app.PluginKey;
import com.truesim.engine.app.Stage;
import com.truesim.engine.app.Startup;
import com.truesim.engine.app.Step;
import com.truesim.engine.settings.EngineSettings;
import com.truesim.engine.settings.Setting;
import com.truesim.scene.animation.Animators;
import com.truesim.scene.core.SceneGraph;
import java.util.Arrays;
import java.util.Objects;

/**
 * Animation as a plugin (animation.md, "A frame"): each world's {@link Animators}, run in {@link Phase#ANIMATE}, after
 * the game's update set what plays and before its late update reads the joints. Per world, in the order added: every
 * animator advances and samples on the plugin's job system in parallel (the result does not depend on the threads), the
 * poses are written into the scene one animator after another and their events dispatched in order, then the scene's
 * world transforms are brought up to date ({@link SceneGraph#update()}), so {@code LATE} reads this frame's joints.
 *
 * <pre>{@code
 * app.use(new Animation());                                   // animation.threads: every processor unless set
 * Animators animators = engine.get(Animation.KEY).animators(scene, clock); // the world's, run every frame from now on
 * Animator fox = animators.add(rig, nodes);
 * // in update: fox.input(move, speed); fox.lookAt(look).target(x, y, z);
 * // in lateUpdate: the joints are this frame's
 * }</pre>
 *
 * <p>A world with a {@link Clock} animates on it (A5: once per rendered frame, by {@link Clock#frameDelta()}: what the
 * world's drawn time moved, so a world losing ticks to the step budget loses them here too); one without on the frame's
 * delta. Its animators on fixed steps ({@code Animator.fixedSteps(true)}, A5) run in {@link Phase#FIXED} instead, once
 * per step of its clock by the step's length ({@link Animators#step}), after the game's fixed update, so they are
 * deterministic whatever the frame rate (D96); the clock's {@link Clock#afterStep} hooks then bring the world
 * transforms up to date (D123: a {@link SceneSync} following the clock does, and draws their joints interpolated between
 * the steps, D91), and the frame's {@code ANIMATE} does in any case. A world without a clock has no steps: its animators
 * on fixed steps stand still. Tools and benchmarks recording frames without an engine call {@link #advance} instead. Ground probes of foot
 * placement run where the animators sample: on the job system's threads, so a {@code Ground} must be thread-safe (as
 * scene's documents it; A13's serial probes need a change in scene). Allocates nothing per frame.
 */
public final class Animation implements Plugin {

    /** Its key. */
    public static final PluginKey<Animation> KEY = PluginKey.of("animation", Animation.class);

    /**
     * The threads animators sample on: 0 (the default) every processor, 1 the main thread alone. Read when the first
     * world's animators are made, so a change takes effect at the next start.
     */
    public static final Setting<Integer> THREADS = Setting.integer("animation.threads", 0, 0, 1024,
        "threads animators sample on (0: every processor, 1: the main thread alone)").restartNeeded();

    private EngineSettings settings; // the engine's once started, or given for frames recorded without one
    private JobSystem jobs; // made with the first world sampling in parallel
    private Animators[] animators = new Animators[0];
    private SceneGraph[] scenes = new SceneGraph[0];
    private Clock[] clocks = new Clock[0];
    private long[] nanos = new long[0]; // each world's last animation, animators and transforms
    private long[] stepNanos = new long[0]; // each world's animators on fixed steps, in the frame's steps so far

    @Override
    public PluginKey<Animation> key() {
        return KEY;
    }

    /** The plugin: its settings are the engine's, from when it starts. */
    public Animation() {
    }

    /** Animation for frames recorded without an engine ({@link #advance}), on {@code settings} ({@link #THREADS}). */
    public Animation(EngineSettings settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    @Override
    @AllowAllocation("starting up")
    public void start(Stage stage, Startup startup) {
        if (stage == Stage.BEFORE_INSTANCE) {
            settings = startup.settings();
            startup.registry().addHidden(THREADS);
        }
    }

    /** The threads the animators sample on: 1 until a world samples in parallel. */
    public int threadCount() {
        return jobs == null ? 1 : jobs.threads();
    }

    /** The animators of {@code scene}, animated every frame on the frame's delta from now on. */
    public Animators animators(SceneGraph scene) {
        return animators(scene, null);
    }

    /**
     * The animators of {@code scene}, a world on {@code clock} (null: on the frame's delta), animated every frame from
     * now on, after the worlds added before.
     */
    @AllowAllocation("a world's animators are made once")
    public Animators animators(SceneGraph scene, Clock clock) {
        Objects.requireNonNull(scene, "scene");
        for (SceneGraph other : scenes) {
            if (other == scene) {
                throw new IllegalArgumentException("The scene has its animators already");
            }
        }
        if (jobs == null && animators.length == 0) {
            int set = settings == null ? 0 : settings.get(THREADS);
            int threads = set == 0 ? Runtime.getRuntime().availableProcessors() : set;
            jobs = threads > 1 ? new JobSystem(threads) : null;
        }
        Animators made = new Animators(scene, jobs);
        int n = animators.length;
        animators = Arrays.copyOf(animators, n + 1);
        scenes = Arrays.copyOf(scenes, n + 1);
        clocks = Arrays.copyOf(clocks, n + 1);
        nanos = Arrays.copyOf(nanos, n + 1);
        stepNanos = Arrays.copyOf(stepNanos, n + 1);
        animators[n] = made;
        scenes[n] = scene;
        clocks[n] = clock;
        return made;
    }

    /** Stops animating the world of {@code removed}. False if it was not one of these. */
    @AllowAllocation("a world is removed")
    public boolean remove(Animators removed) {
        for (int i = 0; i < animators.length; i++) {
            if (animators[i] == removed) {
                animators = drop(animators, i);
                scenes = drop(scenes, i);
                clocks = drop(clocks, i);
                nanos = drop(nanos, i);
                stepNanos = drop(stepNanos, i);
                return true;
            }
        }
        return false;
    }

    @AllowAllocation("a world is removed")
    private static long[] drop(long[] array, int i) {
        long[] kept = new long[array.length - 1];
        System.arraycopy(array, 0, kept, 0, i);
        System.arraycopy(array, i + 1, kept, i, kept.length - i);
        return kept;
    }

    @AllowAllocation("a world is removed")
    private static <T> T[] drop(T[] array, int i) {
        T[] kept = Arrays.copyOf(array, array.length - 1);
        System.arraycopy(array, i + 1, kept, i, kept.length - i);
        return kept;
    }

    /** How many worlds it animates. */
    public int worldCount() {
        return animators.length;
    }

    /**
     * Nanoseconds the last frame spent animating {@code world}'s animators and transforms: in its fixed steps (the
     * animators on them) and in {@code ANIMATE} (the others, and the transforms); -1 if not one of these.
     */
    public long lastNanos(Animators world) {
        for (int i = 0; i < animators.length; i++) {
            if (animators[i] == world) {
                return nanos[i];
            }
        }
        return -1;
    }

    /**
     * {@link Phase#FIXED}: the animators on fixed steps of the world stepping, by the step's length. Their world
     * transforms are left to the clock's after-step hooks (D123): updating them here would log the step's moves before a
     * {@link SceneSync} following the clock marks where the step's changes begin.
     */
    @Override
    public void fixedUpdate(Step step) {
        Clock stepping = step.clock();
        for (int i = 0; i < animators.length; i++) {
            if (clocks[i] == stepping) {
                long start = System.nanoTime();
                animators[i].step(step.delta());
                stepNanos[i] += System.nanoTime() - start;
            }
        }
    }

    /** {@link Phase#ANIMATE}: each world by its clock's frame delta, or the frame's. */
    @Override
    public void animate(Frame frame) {
        for (int i = 0; i < animators.length; i++) {
            animate(i, clocks[i] != null ? clocks[i].frameDelta() : frame.delta());
        }
    }

    /**
     * Animates every world by {@code seconds}, as {@link #animate} does in a frame: for tools and benchmarks recording
     * frames without an engine. Allocates nothing.
     */
    public void advance(double seconds) {
        for (int i = 0; i < animators.length; i++) {
            animate(i, seconds);
        }
    }

    private void animate(int world, double seconds) {
        long start = System.nanoTime();
        animators[world].update(Math.max(0, seconds));
        scenes[world].update(); // world transforms before LATE (animation.md, step 3)
        nanos[world] = System.nanoTime() - start + stepNanos[world];
        stepNanos[world] = 0;
    }

    /** Stops the job system's threads. */
    @Override
    public void stop() {
        if (jobs != null) {
            jobs.close();
            jobs = null;
        }
    }
}
