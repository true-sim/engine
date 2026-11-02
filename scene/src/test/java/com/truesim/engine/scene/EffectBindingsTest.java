package com.truesim.engine.scene;

import static com.truesim.testing.AllocationAssertions.assertAllocationFree;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.truesim.common.state.ChangeClock;
import com.truesim.scene.animation.AnimationClip;
import com.truesim.scene.animation.AnimationCurve;
import com.truesim.scene.animation.AnimationEvent;
import com.truesim.scene.animation.Animator;
import com.truesim.scene.animation.Animators;
import com.truesim.scene.animation.Clip;
import com.truesim.scene.animation.Joint;
import com.truesim.scene.animation.Rig;
import com.truesim.scene.animation.Skeleton;
import com.truesim.scene.animation.Track;
import com.truesim.scene.core.SceneGraph;
import com.truesim.scene.effects.Effect;
import com.truesim.scene.effects.EffectData;
import com.truesim.scene.effects.EffectParameter;
import com.truesim.scene.effects.Effects;
import com.truesim.scene.effects.Init;
import com.truesim.scene.effects.Spawn;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Animation driving effects: events playing effects at joints, curves driving parameters frame by frame. */
class EffectBindingsTest {

    private static final AnimationEvent LEFT = AnimationEvent.of("left foot down");
    private static final AnimationEvent RIGHT = AnimationEvent.of("right foot down");
    private static final AnimationCurve FLICKER = AnimationCurve.of("flicker");
    private static final EffectParameter.Float INTENSITY = EffectParameter.ofFloat("intensity");
    private static final EffectData DUST = EffectData.builder().parameter(INTENSITY, 1)
        .emitter("dust", e -> e.duration(0.1).spawn(Spawn.burst(0, 8)).init(Init.lifetime(0.5f))).build();

    private final SceneGraph scene = new SceneGraph(new ChangeClock());
    private final Animators animators = new Animators(scene);
    private final Effects effects = new Effects(scene);

    /** A hip with two feet. */
    private static Skeleton legs() {
        double[] rest = new double[30];
        for (int j = 0; j < 3; j++) {
            rest[10 * j + 6] = 1;
            rest[10 * j + 7] = 1;
            rest[10 * j + 8] = 1;
            rest[10 * j + 9] = 1;
        }
        return new Skeleton(new String[] {"hip", "footL", "footR"}, new int[] {-1, 0, 0}, rest);
    }

    /** A step of a second: the hip still, a curve rising from 0 to 1 over it. */
    private static AnimationClip step() {
        return new AnimationClip("step", List.of(new Track("hip", 0, Track.Property.TRANSLATION,
            Track.Interpolation.LINEAR, new float[] {0, 1}, new float[] {0, 0, 0, 0, 0, 0}, 0)));
    }

    /** A hip at x with its feet a metre to either side. */
    private long[] spawn(double x) {
        long hip = scene.create();
        scene.setTranslation(hip, x, 0, 0);
        long footL = scene.create(hip);
        long footR = scene.create(hip);
        scene.setTranslation(footL, -1, 0, 0);
        scene.setTranslation(footR, 1, 0, 0);
        return new long[] {hip, footL, footR};
    }

    @Test
    void eventsPlayEffectsAtTheirJointsWithTheirWeights() {
        Rig.Builder r = Rig.builder(legs());
        Joint footL = r.joint("footL");
        Joint footR = r.joint("footR");
        Clip walk = r.clip(step()).event(0.25, LEFT).event(0.75, RIGHT);
        Rig.Layer base = r.layer();
        Rig rig = r.build();
        long[] fox = spawn(0);
        long[] other = spawn(10);
        Animator a = animators.add(rig, fox);
        a.layer(base).play(walk);
        animators.add(rig, other).layer(base).play(walk).weight(0.5);
        EffectBindings fx = new EffectBindings(effects, animators);
        fx.onEvent(LEFT, DUST, footL).scale(INTENSITY).offset(0, -0.1, 0);
        fx.onEvent(RIGHT, DUST, footR).scale(INTENSITY, 2).minimumWeight(0.75f);
        fx.onEvent(LEFT, DUST, new Joint(7, "not a joint of this rig"));
        List<Long> played = new ArrayList<>();
        animators.update(0);
        animators.update(0.5); // past the left step
        effects.forEach(played::add);
        assertThat(played).as("one per animator").hasSize(2);
        assertThat(fx.played()).isEqualTo(2);
        long first = played.get(0);
        assertThat(effects.node(first)).isEqualTo(a.node(footL));
        assertThat(effects.offset(first, 1)).isEqualTo(-0.1);
        assertThat(effects.parameter(first, DUST.parameterOffset("intensity"))).as("its weight").isEqualTo(1);
        assertThat(effects.parameter(played.get(1), DUST.parameterOffset("intensity"))).isEqualTo(0.5f);
        scene.update();
        effects.advance(0.01);
        assertThat(effects.position(first, 0)).as("at the left foot").isCloseTo(-1, within(1e-9));
        // The right step: weighed 0.5 on the second animator, below its binding's minimum.
        animators.update(0.5);
        assertThat(fx.played()).isEqualTo(3);
        played.clear();
        effects.forEach(played::add);
        long right = played.get(played.size() - 1);
        assertThat(effects.node(right)).isEqualTo(a.node(footR));
        assertThat(effects.parameter(right, DUST.parameterOffset("intensity"))).as("weight times 2").isEqualTo(2);
        // Animators of another set fire nothing here.
        Animators others = new Animators(scene);
        others.add(rig, spawn(20)).layer(base).play(walk);
        others.update(0);
        others.update(0.5);
        assertThat(fx.played()).isEqualTo(3);
    }

    @Test
    void eventsBurstIntoSharedInstancesWhereTheirJointsAre() {
        Rig.Builder r = Rig.builder(legs());
        Joint footL = r.joint("footL");
        Clip walk = r.clip(step()).event(0.25, LEFT);
        Rig.Layer base = r.layer();
        Rig rig = r.build();
        for (int i = 0; i < 20; i++) {
            animators.add(rig, spawn(0.02 * i)).layer(base).play(walk); // feet in one cell
        }
        EffectBindings fx = new EffectBindings(effects, animators);
        fx.onEvent(LEFT, DUST, footL).offset(0, -0.5, 0).minimumWeight(0.2f).burst();
        scene.update();
        animators.update(0);
        animators.update(0.5);
        assertThat(fx.played()).as("a burst per animator").isEqualTo(20);
        List<Long> playing = new ArrayList<>();
        effects.forEach(playing::add);
        assertThat(playing).as("into one shared instance of the cell").hasSize(1);
        assertThat(effects.burstCount()).isEqualTo(20);
        assertThat(effects.burstPosition(0, 0)).as("at the left foot").isCloseTo(-1, within(1e-9));
        assertThat(effects.burstPosition(0, 1)).as("offset in its frame").isCloseTo(-0.5, within(1e-9));
        for (int f = 0; f < 4; f++) {
            animators.update(0.5);
        }
        assertThat(fx.played()).isEqualTo(100);
    }

    @Test
    void curvesDriveParametersFrameByFrame() {
        Rig.Builder r = Rig.builder(legs());
        Clip flicker = r.clip(step()).curve(FLICKER, new float[] {0, 1}, new float[] {0, 1});
        Rig.Layer base = r.layer();
        Rig rig = r.build();
        Animator a = animators.add(rig, spawn(0));
        a.layer(base).play(flicker);
        Effect torch = effects.play(DUST, 0, 0, 0);
        Effect ended = effects.play(DUST, 0, 0, 0);
        ended.kill();
        EffectBindings fx = new EffectBindings(effects, animators);
        fx.curve(a, FLICKER, torch, INTENSITY).map(2, 0.5);
        fx.curve(a, FLICKER, ended, INTENSITY);
        animators.update(0);
        for (int f = 1; f <= 5; f++) {
            animators.update(0.1);
            fx.update();
            assertThat(torch.get(INTENSITY)).as("frame " + f).isCloseTo((float) (2 * 0.1 * f + 0.5), within(1e-5f));
        }
        assertAllocationFree(fx::update, 2_000, 500, 10);
    }
}
