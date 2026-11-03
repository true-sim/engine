package com.truesim.engine.audio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.truesim.common.jobs.TaskScheduler;
import com.truesim.common.state.ChangeClock;
import com.truesim.scene.assets.AssetCache;
import com.truesim.scene.assets.AssetRef;
import com.truesim.scene.audio.AudioAssets;
import com.truesim.scene.audio.AudioClip;
import com.truesim.scene.audio.AudioEffect;
import com.truesim.scene.audio.Attenuation;
import com.truesim.scene.audio.Bus;
import com.truesim.scene.audio.BusLayout;
import com.truesim.scene.audio.Play;
import com.truesim.scene.audio.Sound;
import com.truesim.scene.audio.Sounds;
import com.truesim.scene.core.SceneGraph;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Reverb (§6 step 10): decay times from impulse responses, zones sending and blending, continuously. */
class ReverbTest {

    static final int RATE = 48_000;

    /** RT60 of {@code reverb}'s impulse response: Schroeder's backward integration, a fit from -5 to -25 dB, times 3. */
    static double rt60(BusEffects.Effect reverb, int seconds) {
        int frames = seconds * RATE;
        float[] out = new float[2 * frames];
        out[0] = 1;
        out[1] = 1;
        for (int at = 0; at < frames; at += 256) {
            float[] part = java.util.Arrays.copyOfRange(out, 2 * at, 2 * at + 512);
            reverb.process(part, 256);
            System.arraycopy(part, 0, out, 2 * at, 512);
        }
        double[] energy = new double[frames];
        double sum = 0;
        for (int f = frames - 1; f >= 0; f--) {
            sum += out[2 * f] * out[2 * f] + out[2 * f + 1] * out[2 * f + 1];
            energy[f] = sum;
        }
        int t5 = -1;
        int t25 = -1;
        for (int f = 0; f < frames && t25 < 0; f++) {
            double db = 10 * Math.log10(energy[f] / energy[0]);
            if (t5 < 0 && db <= -5) {
                t5 = f;
            }
            if (db <= -25) {
                t25 = f;
            }
        }
        return 3.0 * (t25 - t5) / RATE;
    }

    @Test
    void decayTimesMatchThePresets() {
        for (int preset = 0; preset < 4; preset++) {
            // undamped, so the broadband decay is the lines' (damping shortens the highs' tail, by design)
            BusEffects.Effect reverb = BusEffects.create(AudioEffect.named("reverb").set("preset", preset)
                .set("damping", 0), RATE, 2);
            double expected = Reverb.PRESET_DECAY[preset];
            assertThat(rt60(reverb, 6)).as("preset %d", preset).isCloseTo(expected, within(0.15 * expected));
        }
        Reverb set = Reverb.create(AudioEffect.named("reverb").set("decay", 1.0).set("size", 0.8).set("damping", 0),
            RATE, 2);
        assertThat(rt60(set, 4)).isCloseTo(1.0, within(0.15));
        Reverb damped = Reverb.create(AudioEffect.named("reverb").set("decay", 1.0).set("damping", 0.6), RATE, 2);
        assertThat(rt60(damped, 4)).as("damping: the highs die sooner").isLessThan(0.9);
        set.set(3, 0.2f);
        assertThat(set.decay()).isEqualTo(3);
        Reverb mono = Reverb.create(AudioEffect.named("reverb"), RATE, 1);
        float[] one = new float[256];
        one[0] = 1;
        mono.process(one, 256);
        Reverb wide = Reverb.create(AudioEffect.named("reverb"), RATE, 6);
        wide.process(new float[6 * 256], 256);
    }

    /** A world with a reverb aux of a small room, a listener on a node and a hall zone of 10 m with 5 m of fade. */
    private static final class World implements AutoCloseable {
        final TaskScheduler tasks = new TaskScheduler();
        final AssetCache cache = new AssetCache(tasks);
        final AudioRenderer renderer = AudioRenderer.offline(AudioFormat.stereo(RATE));
        final SceneGraph scene = new SceneGraph(new ChangeClock());
        final Sounds sounds = new Sounds(scene);
        final AudioSync sync = new AudioSync(renderer, cache).add(sounds);
        final long head = scene.create();
        final long hall = scene.create();

        World(boolean zone) {
            renderer.commands().layout(BusLayout.builder().bus(Bus.SFX, b -> b.send(AudioSync.REVERB, -6))
                .aux(AudioSync.REVERB, b -> b.effect(AudioEffect.named("reverb").set("preset", 3))).build());
            scene.setTranslation(hall, 50, 0, 0);
            scene.update();
            sounds.listeners().device(head);
            if (zone) {
                sounds.reverbZones().sphere(hall, 10, 5, AudioEffect.named("reverb").set("preset", 1), 0);
            }
        }

        void frame() {
            scene.update();
            sync.events();
            sounds.advance(1_000_000L / 48);
            sync.update();
            renderer.render(1_000);
        }

        void settle() throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (cache.loadingCount() > 0 && System.nanoTime() < deadline) {
                tasks.drainCompleted();
                Thread.sleep(1);
            }
            tasks.drainCompleted();
        }

        @Override
        public void close() {
            renderer.close();
            cache.close();
            tasks.close();
        }
    }

    @Test
    void walkingIntoAHallBlendsItsReverbContinuously() throws InterruptedException {
        try (World w = new World(true)) {
            double previous = Double.NaN;
            double first = Double.NaN;
            for (int step = 0; step <= 100; step++) {
                w.scene.setTranslation(w.head, 30 + 0.2 * step, 0, 0); // from 20 m away to its middle...
                w.frame();
                double decay = w.renderer.reverbDecay(AudioSync.REVERB);
                if (step == 0) {
                    first = decay;
                }
                if (!Double.isNaN(previous)) {
                    assertThat(Math.abs(decay - previous)).as("step %d", step).isLessThan(0.2);
                }
                previous = decay;
            }
            assertThat(first).as("outside: the aux's own small room").isCloseTo(0.3, within(0.01));
            assertThat(previous).as("inside the hall").isCloseTo(2.2, within(0.03));
        }
    }

    @Test
    void emittersSendToTheReverbFromTheirZone() throws InterruptedException {
        try (World w = new World(true); World open = new World(false)) {
            Sound knock = Sound.builder().root(Play.clip(AssetRef.generated(AudioAssets.CLIP,
                (s, seed) -> Clips.constant(RATE, 0.5f, 10 * RATE), 1, 0))).attenuation(Attenuation.builder()
                .distance(1, 100).reverbSend(0.5f, 1).build()).loop().build();
            long inside = w.sounds.fire(knock, 50, 0, 0);
            long outside = open.sounds.fire(knock, 5, 0, 0);
            w.sync.update();
            open.sync.update();
            w.settle();
            open.settle();
            for (int i = 0; i < 10; i++) {
                w.frame();
                open.frame();
            }
            assertThat(w.renderer.meterPeak(AudioSync.REVERB)).as("in the hall").isGreaterThan(0.002f);
            assertThat(open.renderer.meterPeak(AudioSync.REVERB)).as("no zones: everything sends").isGreaterThan(0.002f);
            w.sounds.moveTo(inside, -100, 0, 0); // out of every zone
            for (int i = 0; i < 40; i++) {
                w.frame();
            }
            assertThat(w.renderer.meterPeak(AudioSync.REVERB)).as("outside the zone: no send").isLessThan(1e-3f);
            assertThat(open.sounds.alive(outside)).isTrue();
        }
    }
}
