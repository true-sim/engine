package com.truesim.engine.audio;

import static com.truesim.testing.AllocationAssertions.assertAllocationFree;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import com.truesim.scene.assets.AssetRef;
import com.truesim.scene.audio.AudioAssets;
import com.truesim.scene.audio.AudioClip;
import com.truesim.scene.audio.Decibels;
import com.truesim.scene.audio.Play;
import com.truesim.scene.audio.Sound;
import com.truesim.scene.audio.SoundInstance;
import com.truesim.scene.audio.SoundParameter;
import com.truesim.scene.audio.Sounds;
import com.truesim.scene.audio.Switch;
import com.truesim.scene.audio.SwitchGroup;
import com.truesim.scene.core.SceneGraph;
import com.truesim.scene.effects.EffectData;
import com.truesim.scene.effects.Effects;
import com.truesim.scene.effects.Init;
import com.truesim.scene.effects.Spawn;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Animation and effects driving sounds (§6 step 14): footsteps with their surface, curves, effect starts and bursts. */
class AudioBindingsTest {

    enum Surface {
        GRASS, STONE, GRAVEL
    }

    static final AnimationEvent LEFT = AnimationEvent.of("left foot down");
    static final AnimationEvent RIGHT = AnimationEvent.of("right foot down");
    static final AnimationCurve BREATH = AnimationCurve.of("breath");
    static final SwitchGroup<Surface> SURFACE = SwitchGroup.of("surface", Surface.class, Surface.GRASS);
    static final SoundParameter INTENSITY = SoundParameter.of("intensity", 0, 0, 10);
    static final EffectData FLASH = EffectData.builder()
        .emitter("flash", e -> e.duration(0.1).spawn(Spawn.burst(0, 1)).init(Init.lifetime(0.1f))).build();

    private final SceneGraph scene = new SceneGraph(new ChangeClock());
    private final Animators animators = new Animators(scene);
    private final Sounds sounds = new Sounds(scene);

    static AssetRef<AudioClip> clip(int n) {
        return AssetRef.generated(AudioAssets.CLIP, (k, seed) -> Clips.constant(48_000, 0.1f, 100), n, 0);
    }

    static final Sound STEP = Sound.builder().root(Switch.on(SURFACE).when(Surface.GRASS, Play.clip(clip(1)))
        .when(Surface.STONE, Play.clip(clip(2))).when(Surface.GRAVEL, Play.clip(clip(3)))).build();

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

    private static AnimationClip cycle(String name, float seconds) {
        return new AnimationClip(name, List.of(new Track("hip", 0, Track.Property.TRANSLATION,
            Track.Interpolation.LINEAR, new float[] {0, seconds}, new float[] {0, 0, 0, 0, 0, 0}, 0)));
    }

    private long[] spawn(double x) {
        long hip = scene.create();
        scene.setTranslation(hip, x, 0, 0);
        long footL = scene.create(hip);
        long footR = scene.create(hip);
        scene.setTranslation(footL, -1, 0, 0);
        scene.setTranslation(footR, 1, 0, 0);
        return new long[] {hip, footL, footR};
    }

    private final List<Long> heard = new ArrayList<>();

    private void collect() {
        heard.clear();
        sounds.forEach(heard::add);
    }

    @Test
    void aFootstepPerFootDownOfTheWalkAndTheRunOnItsSurface() {
        Rig.Builder r = Rig.builder(legs());
        Joint footL = r.joint("footL");
        Joint footR = r.joint("footR");
        Clip walk = r.clip(cycle("walk", 1)).event(0.25, LEFT).event(0.75, RIGHT);
        Clip run = r.clip(cycle("run", 0.6f)).event(0.1, LEFT).event(0.4, RIGHT);
        Rig.Layer base = r.layer();
        Rig rig = r.build();
        long[] fox = spawn(0);
        Animator a = animators.add(rig, fox);
        a.layer(base).play(walk);
        AudioBindings bindings = new AudioBindings(sounds, animators);
        // the terrain: stone left of x = 0, gravel past x = 5, nothing loaded past x = 50, grass elsewhere
        AudioBindings.MaterialAt terrain = (x, z) -> x > 50 ? -1 : x < 0 ? 1 : x > 5 ? 2 : 0;
        bindings.onEvent(LEFT, STEP, footL).surface(SURFACE, terrain);
        bindings.onEvent(RIGHT, STEP, footR).surface(SURFACE, terrain);
        scene.update();
        animators.update(0);
        for (int f = 0; f < 40; f++) { // 4 s of walking: 8 steps
            animators.update(0.1);
        }
        assertThat(bindings.played()).isEqualTo(8);
        collect();
        long left = heard.get(0);
        assertThat(sounds.voiceClip(left, 0)).as("the left foot on stone").isEqualTo(clip(2));
        assertThat(sounds.voiceClip(heard.get(1), 0)).as("the right on grass").isEqualTo(clip(1));
        assertThat(sounds.node(left)).isEqualTo(a.node(footL));
        a.layer(base).play(run);
        for (int f = 0; f < 30; f++) { // 3 s of running: 5 cycles, 10 steps
            animators.update(0.1);
        }
        assertThat(bindings.played()).isEqualTo(18);
        scene.setTranslation(fox[0], 10, 0, 0);
        scene.update();
        animators.update(0.3);
        collect();
        assertThat(sounds.voiceClip(heard.get(heard.size() - 1), 0)).as("on gravel").isEqualTo(clip(3));
        scene.setTranslation(fox[0], 100, 0, 0);
        scene.update();
        animators.update(0.3);
        collect();
        assertThat(sounds.voiceClip(heard.get(heard.size() - 1), 0)).as("unloaded: the fallback").isEqualTo(clip(1));
    }

    @Test
    void anEventsWeightScalesItsVolume() {
        Rig.Builder r = Rig.builder(legs());
        Joint footL = r.joint("footL");
        Clip walk = r.clip(cycle("walk", 1)).event(0.25, LEFT);
        Rig.Layer base = r.layer();
        Rig rig = r.build();
        animators.add(rig, spawn(0)).layer(base).play(walk).weight(0.5);
        animators.add(rig, spawn(5)).layer(base).play(walk).weight(0.1);
        AudioBindings bindings = new AudioBindings(sounds, animators);
        bindings.onEvent(LEFT, STEP, footL).minimumWeight(0.2f);
        bindings.onEvent(LEFT, STEP, new Joint(9, "not in this rig"));
        bindings.onEvent(RIGHT, STEP, footL).scaled(false); // never fires: no such event in the clip
        animators.update(0);
        animators.update(0.5);
        assertThat(bindings.played()).as("the light one below the minimum").isEqualTo(1);
        collect();
        assertThat(sounds.volume(heard.get(0))).isCloseTo(Decibels.of(0.5f), within(1e-4f));
        Animators others = new Animators(scene);
        others.add(rig, spawn(20)).layer(base).play(walk);
        others.update(0);
        others.update(0.5);
        assertThat(bindings.played()).as("another set's animators fire nothing here").isEqualTo(1);
    }

    @Test
    void curvesDriveParametersFrameByFrame() {
        Rig.Builder r = Rig.builder(legs());
        Clip breathe = r.clip(cycle("breathe", 1)).curve(BREATH, new float[] {0, 1}, new float[] {0, 1});
        Rig.Layer base = r.layer();
        Rig rig = r.build();
        Animator a = animators.add(rig, spawn(0));
        a.layer(base).play(breathe);
        Sound breathing = Sound.builder().root(Play.clip(clip(4)).volume(INTENSITY,
            com.truesim.scene.audio.Curve.points(0, 0, 10, 1))).loop().build();
        SoundInstance breath = sounds.play(breathing);
        SoundInstance gone = sounds.play(breathing);
        gone.kill();
        AudioBindings bindings = new AudioBindings(sounds, animators);
        bindings.curve(a, BREATH, breath, INTENSITY).map(2, 0.5);
        bindings.curve(a, BREATH, gone, INTENSITY);
        animators.update(0);
        for (int f = 1; f <= 5; f++) {
            animators.update(0.1);
            bindings.update();
            assertThat(breath.get(INTENSITY)).as("frame " + f).isCloseTo((float) (2 * 0.1 * f + 0.5), within(1e-5f));
        }
        assertAllocationFree(bindings::update, 2_000, 500, 10);
    }

    @Test
    void anEffectStartPlaysItsSoundOnce() {
        Effects effects = new Effects(scene);
        AudioBindings bindings = new AudioBindings(sounds, null).effects(effects);
        bindings.onEffect(FLASH, STEP);
        long muzzle = scene.create();
        scene.setTranslation(muzzle, 3, 0, 0);
        effects.play(FLASH, muzzle);
        effects.play(FLASH, 7, 0, 0);
        effects.play(EffectData.builder().emitter("other", e -> e.duration(0.1).spawn(Spawn.burst(0, 1))
            .init(Init.lifetime(0.1f))).build(), 0, 0, 0);
        bindings.update();
        assertThat(bindings.played()).isEqualTo(2);
        collect();
        assertThat(heard).hasSize(2);
        assertThat(sounds.node(heard.get(0))).isEqualTo(muzzle);
        bindings.update();
        assertThat(bindings.played()).as("once").isEqualTo(2);
        effects.burst(FLASH, 1, 2, 3, 0, 1, 0, 0);
        effects.burst(FLASH, 4, 5, 6, 0, 1, 0, 0);
        bindings.update();
        assertThat(bindings.played()).as("a burst each").isEqualTo(4);
        bindings.update();
        assertThat(bindings.played()).isEqualTo(4);
        assertThatThrownBy(() -> new AudioBindings(sounds, null).onEffect(FLASH, STEP))
            .hasMessageContaining("effects(");
        assertThatThrownBy(() -> new AudioBindings(sounds, null).onEvent(LEFT, STEP, new Joint(0, "hip")))
            .hasMessageContaining("animators");
    }
}
