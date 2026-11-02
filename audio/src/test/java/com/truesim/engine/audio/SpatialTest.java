package com.truesim.engine.audio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.truesim.common.jobs.TaskScheduler;
import com.truesim.common.state.ChangeClock;
import com.truesim.scene.assets.AssetCache;
import com.truesim.scene.assets.AssetRef;
import com.truesim.scene.audio.AudioAssets;
import com.truesim.scene.audio.AudioClip;
import com.truesim.scene.audio.Attenuation;
import com.truesim.scene.audio.Bus;
import com.truesim.scene.audio.Curve;
import com.truesim.scene.audio.Play;
import com.truesim.scene.audio.Priority;
import com.truesim.scene.audio.Sound;
import com.truesim.scene.audio.Sounds;
import com.truesim.scene.audio.Virtualization;
import com.truesim.scene.core.SceneGraph;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** 3D (§6 step 7): attenuation, Doppler, panning, large worlds, split screen, virtual voices and stealing. */
class SpatialTest {

    static final int RATE = 48_000;
    static final float CENTRE = (float) Math.cos(Math.PI / 4);
    static final int SPATIAL = Voices.SPATIAL;

    @Test
    void levelsFollowTheAttenuationCurve() {
        Attenuation linear = Attenuation.builder().distance(1, 50).curve(Curve.LINEAR).build();
        Attenuation inverse = Attenuation.near(2, 100);
        for (Attenuation a : new Attenuation[] {linear, inverse}) {
            for (float d : new float[] {0.5f, 1, 5, 20, 45}) {
                try (AudioRenderer r = AudioRenderer.offline(AudioFormat.stereo(RATE))) {
                    r.commands().play(0, Clips.constant(RATE, 0.5f, RATE), 100, 0, 1, 1, Bus.SFX, SPATIAL | Voices.LOOP,
                        0, 1);
                    r.commands().spatial(0, 0, 0, -d, 1, a, false); // straight ahead
                    float[] out = r.render(4_096);
                    assertThat((double) out[2 * 3_000]).as("%s at %s m", a.curve(), d)
                        .isCloseTo(0.5 * CENTRE * a.gain(d), within(1e-5));
                }
            }
        }
    }

    @Test
    void directionsPanAndFarSoundsAreDuller() {
        Attenuation air = Attenuation.builder().distance(1, 100).lowPass(20_000, 1_000).build();
        float[] near = render(r -> {
            r.commands().play(0, Clips.sine(RATE, 6_000, 0.5f, RATE), 0, 0, 1, 1, Bus.SFX, SPATIAL, 0, 1);
            r.commands().spatial(0, 3, 0, 0, 1, air, false); // to the right
        }, 8_192);
        assertThat(Clips.peak(near, 2, 0, 2_048, 8_000)).isLessThan(1e-6f);
        assertThat(Clips.peak(near, 2, 1, 2_048, 8_000)).isGreaterThan(0.1f);
        float[] far = render(r -> {
            r.commands().play(0, Clips.sine(RATE, 6_000, 0.5f, RATE), 0, 0, 1, 1, Bus.SFX, SPATIAL, 0, 1);
            r.commands().spatial(0, 90, 0, 0, 1, Attenuation.builder().distance(1, 100).curve(Curve.points(0, 1))
                .lowPass(20_000, 1_000).build(), false);
        }, 8_192);
        // no distance gain (a flat curve), but 6 kHz through a low-pass near 1.35 kHz at 90 m: under a third of 0.5
        assertThat(Clips.peak(far, 2, 1, 2_048, 8_000)).isLessThan(0.3f * 0.5f).isGreaterThan(0.05f);
    }

    private static float[] render(java.util.function.Consumer<AudioRenderer> setup, int frames) {
        try (AudioRenderer r = AudioRenderer.offline(AudioFormat.stereo(RATE))) {
            setup.accept(r);
            return r.render(frames);
        }
    }

    @Test
    void ofAThousandLoopsTheLoudestSixtyFourAreRealAndTheOthersKeepTime() {
        try (AudioRenderer r = new AudioRenderer(AudioFormat.stereo(RATE), 1_024)) {
            AudioClip loop = Clips.sine(RATE, 100, 0.2f, 480 * 7); // 7 periods: a seamless loop of 3,360 frames
            Attenuation a = Attenuation.near(1, 2_000);
            for (int v = 0; v < 1_000; v++) {
                r.commands().play(v, loop, 0, 0, 1, 1, Bus.SFX, SPATIAL | Voices.LOOP, 0, v);
                r.commands().spatial(v, 0, 0, -(1 + v), 1, a, false); // voice v at 1 + v metres
            }
            r.render(4 * 256);
            assertThat(r.realVoices()).isEqualTo(64);
            assertThat(r.virtualVoices()).isEqualTo(936);
            r.render(2_048); // the fades done
            for (int v = 0; v < 1_000; v++) {
                assertThat(r.isReal(v)).as("voice %d", v).isEqualTo(v < 64);
            }
            // voice 900 comes near: real again, where its loop would be by now
            r.commands().spatial(900, 0, 0, -0.5f, 1, a, false);
            r.render(4 * 256);
            assertThat(r.isReal(900)).isTrue();
            assertThat(r.realVoices()).isEqualTo(64);
            long played = r.frame(); // every voice started at mix frame 0 (stamped 0: as early as it could)
            double expected = played % 3_360;
            assertThat(r.position(900)).isCloseTo(expected, within(1.0));
            assertThat(r.position(900)).isCloseTo(r.position(0), within(1e-6));
        }
    }

    @Test
    void theHighestPriorityIsNeverStolenAndKilledVoicesEnd() {
        try (AudioRenderer r = new AudioRenderer(AudioFormat.stereo(RATE), 16)) {
            r.commands().budget(2);
            AudioClip loop = Clips.constant(RATE, 0.1f, 64);
            Attenuation a = Attenuation.near(1, 1_000);
            int highest = Priority.HIGHEST.ordinal() << Voices.PRIORITY_SHIFT;
            int kill = Virtualization.KILL.ordinal() << Voices.VIRTUAL_SHIFT;
            r.commands().play(0, loop, 0, 0, 1, 1, Bus.SFX, SPATIAL | Voices.LOOP, 0, 0);
            r.commands().play(1, loop, 0, 0, 1, 1, Bus.SFX, SPATIAL | Voices.LOOP, 0, 1);
            r.commands().play(2, loop, 0, 0, 1, 1, Bus.SFX, SPATIAL | Voices.LOOP | highest, 0, 2);
            r.commands().play(3, loop, 0, 0, 1, 1, Bus.SFX, SPATIAL | Voices.LOOP | kill, 0, 3);
            r.commands().play(4, Clips.constant(RATE, 0.1f, RATE), 0, 0, 1, 1, Bus.SFX,
                SPATIAL | Virtualization.KILL_IF_FINITE.ordinal() << Voices.VIRTUAL_SHIFT, 0, 4);
            r.commands().play(5, loop, 0, 0, 0.0001f, 1, Bus.SFX, Voices.LOOP, 0, 5); // -80 dB: silent
            r.commands().spatial(0, 0, 0, -1, 1, a, false);
            r.commands().spatial(1, 0, 0, -2, 1, a, false);
            r.commands().spatial(2, 0, 0, -300, 1, a, false); // far, but the highest priority
            r.commands().spatial(3, 0, 0, -100, 1, a, false);
            r.commands().spatial(4, 0, 0, -200, 1, a, false);
            r.render(2_048);
            assertThat(r.isReal(2)).isTrue();
            assertThat(r.isReal(5)).as("below -60 dB").isFalse();
            assertThat(r.realVoices()).as("the highest priority counts against the budget").isEqualTo(2);
            assertThat(r.isReal(0)).isTrue();
            assertThat(r.isReal(1)).as("the budget's next: virtual, keeping time").isFalse();
            java.util.List<Long> ended = new java.util.ArrayList<>();
            r.poll((k, s, t, f) -> ended.add(t));
            assertThat(ended).as("killed when virtual; a one-shot ends rather than keeping time")
                .containsExactlyInAnyOrder(3L, 4L);
        }
    }

    /** A world with a listener and a source, synced and rendered frame by frame. */
    private static final class World implements AutoCloseable {
        final TaskScheduler tasks = new TaskScheduler();
        final AssetCache cache = new AssetCache(tasks);
        final AudioRenderer renderer = AudioRenderer.offline(AudioFormat.stereo(RATE));
        final SceneGraph scene = new SceneGraph(new ChangeClock());
        final Sounds sounds = new Sounds(scene);
        final AudioSync sync = new AudioSync(renderer, cache).add(sounds);

        void settle() throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (cache.loadingCount() > 0 && System.nanoTime() < deadline) {
                tasks.drainCompleted();
                Thread.sleep(1);
            }
            tasks.drainCompleted();
        }

        /** A frame of 1/48 s (1,000 frames rendered). */
        float[] frame() {
            scene.update();
            sync.events();
            sounds.advance(1_000_000L / 48);
            sync.update();
            return renderer.render(1_000);
        }

        @Override
        public void close() {
            renderer.close();
            cache.close();
            tasks.close();
        }
    }

    static AssetRef<AudioClip> tone(double hz) {
        return AssetRef.generated(AudioAssets.CLIP, (spec, seed) -> Clips.sine(RATE, spec, 0.5f, 10 * RATE), hz, 0);
    }

    /** {@code frames} of a source flying past the listener at {@code speed} m/s along x, 5 m ahead, from x = -60. */
    private static float[] flyBy(double originX, double speed, int frames) throws InterruptedException {
        try (World w = new World()) {
            long head = w.scene.create();
            w.scene.setTranslation(head, originX, 0, 0);
            long car = w.scene.create();
            w.scene.setTranslation(car, originX - 60, 0, -5);
            w.scene.update();
            w.sounds.listeners().device(head);
            Sound engine = Sound.builder().root(Play.clip(tone(700))).attenuation(Attenuation.near(1, 500)).loop()
                .build();
            w.sounds.play(engine, car);
            w.sync.update();
            w.settle();
            float[] out = new float[2 * frames];
            int done = 0;
            double x = -60;
            while (done < frames) {
                x += speed / 48;
                w.scene.setTranslation(car, originX + x, 0, -5);
                float[] block = w.frame();
                System.arraycopy(block, 0, out, 2 * done, Math.min(block.length, 2 * (frames - done)));
                done += 1_000;
            }
            return out;
        }
    }

    @Test
    void dopplerRaisesThePitchOfWhatComesAndLowersWhatGoes() throws InterruptedException {
        float[] out = flyBy(0, 34.3, 4 * RATE); // 140 m in 4 s: by at 1.75 s
        // approaching: 700 / (1 - 0.1 cos) Hz; the source is at about x = -40 to -26 m in the first second
        double coming = Clips.upCrossings(out, 2, 0, RATE / 4, RATE) / 0.75;
        double going = Clips.upCrossings(out, 2, 1, 3 * RATE, 4 * RATE);
        assertThat(coming).isBetween(760.0, 780.0);  // cos of the angle about 0.99: 700 / 0.901
        assertThat(going).isBetween(632.0, 645.0);   // 700 / 1.0996
        // and it panned from the left to the right
        assertThat(Clips.peak(out, 2, 0, 0, RATE / 2)).isGreaterThan(Clips.peak(out, 2, 1, 0, RATE / 2));
        assertThat(Clips.peak(out, 2, 1, 3 * RATE, 4 * RATE)).isGreaterThan(Clips.peak(out, 2, 0, 3 * RATE, 4 * RATE));
    }

    @Test
    void aSourceSevenThousandKilometresOutSoundsAsOneAtTheOrigin() throws InterruptedException {
        float[] home = flyBy(0, 30, 2 * RATE);
        float[] far = flyBy(7_000_000, 30, 2 * RATE);
        float worst = 0;
        for (int i = 0; i < home.length; i++) {
            worst = Math.max(worst, Math.abs(home[i] - far[i]));
        }
        assertThat(worst).isLessThan(1e-5f);
    }

    @Test
    void aSoundCrossingBetweenSplitScreenPlayersHasNoStep() throws InterruptedException {
        try (World w = new World()) {
            long p1 = w.scene.create();
            long p2 = w.scene.create();
            w.scene.setTranslation(p1, -20, 0, 0);
            w.scene.setTranslation(p2, 20, 0, 0);
            long source = w.scene.create();
            w.scene.setTranslation(source, -18, 0, 0);
            w.scene.update();
            w.sounds.listeners().device(p1);
            w.sounds.listeners().device(p2);
            Sound hum = Sound.builder().root(Play.clip(AssetRef.generated(AudioAssets.CLIP,
                (spec, seed) -> Clips.constant(RATE, 0.4f, RATE), 1, 0))).attenuation(Attenuation.near(1, 100))
                .doppler(0).loop().build();
            w.sounds.play(hum, source);
            w.sync.update();
            w.settle();
            float worst = 0;
            float previousLeft = Float.NaN;
            for (int f = 0; f < 120; f++) { // from beside player 1 to beside player 2 in 2.5 s
                w.scene.setTranslation(source, -18 + 36.0 * f / 120, 0, 0);
                float[] out = w.frame();
                for (int i = 0; i < 1_000; i++) {
                    float left = out[2 * i];
                    if (!Float.isNaN(previousLeft) && f > 4) {
                        worst = Math.max(worst, Math.abs(left - previousLeft));
                    }
                    previousLeft = left;
                }
            }
            // a constant sound moved and handed from one listener to the other: no sample jumps more than a little
            assertThat(worst).isLessThan(0.01f);
        }
    }

    @Test
    void timeScaleLowersThePitchAndPauseHolds() throws InterruptedException {
        try (World w = new World()) {
            Sound tone = Sound.builder().root(Play.clip(tone(1_000))).spatial(com.truesim.scene.audio.Spatial.THREE_D)
                .attenuation(Attenuation.near(10, 100)).loop().build();
            w.sounds.play(tone, 0, 0, -1);
            w.sync.update();
            w.settle();
            for (int i = 0; i < 10; i++) {
                w.frame();
            }
            w.sounds.timeScale(0.5f);
            w.frame();
            float[] slow = new float[2 * 48_000];
            for (int i = 0; i < 48; i++) {
                System.arraycopy(w.frame(), 0, slow, 2_000 * i, 2_000);
            }
            assertThat(Clips.upCrossings(slow, 2, 0, 0, 48_000)).isBetween(495, 505);
            w.sounds.pause(true);
            w.frame();
            float[] paused = w.frame();
            assertThat(Clips.peak(paused, 2, 0, 0, 1_000)).isZero();
        }
    }
}
