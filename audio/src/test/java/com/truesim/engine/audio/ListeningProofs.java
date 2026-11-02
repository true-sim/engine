package com.truesim.engine.audio;

import com.truesim.common.jobs.TaskScheduler;
import com.truesim.common.math.random.Hash;
import com.truesim.common.state.ChangeClock;
import com.truesim.scene.assets.AssetCache;
import com.truesim.scene.assets.AssetRef;
import com.truesim.scene.audio.AudioAssets;
import com.truesim.scene.audio.AudioClip;
import com.truesim.scene.audio.Attenuation;
import com.truesim.scene.audio.Concurrency;
import com.truesim.scene.audio.Decibels;
import com.truesim.scene.audio.Play;
import com.truesim.scene.audio.Range;
import com.truesim.scene.audio.Sound;
import com.truesim.scene.audio.Sounds;
import com.truesim.scene.core.SceneGraph;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Offline renders to listen to (not checks): knocking rocks, a car passing with Doppler and panning, and many loops
 * with stealing as the listener walks through them. {@code -Ptruesim.audio.proofs=<dir>} writes them as WAV files.
 */
@EnabledIfSystemProperty(named = "truesim.audio.proofs", matches = ".+")
class ListeningProofs {

    static final int RATE = 48_000;

    /** The demo's knock: a thump, a body and a click (KnockingRocks). */
    static AudioClip knock(int variant) {
        int frames = RATE / 4;
        float[] s = new float[frames];
        double body = 520 + 60 * variant;
        float peak = 0;
        for (int i = 0; i < frames; i++) {
            double t = (double) i / RATE;
            double noise = Hash.random(17 + variant, i) * 2 - 1;
            s[i] = (float) (0.8 * Math.sin(2 * Math.PI * 85 * t) * Math.exp(-t * 22)
                + 0.5 * Math.sin(2 * Math.PI * body * t) * Math.exp(-t * 45) + 0.6 * noise * Math.exp(-t * 300));
            peak = Math.max(peak, Math.abs(s[i]));
        }
        for (int i = 0; i < frames; i++) {
            s[i] *= 0.9f / peak;
        }
        return AudioClip.builder(RATE, 1).floats(s).build();
    }

    /** An engine-like tone: a few harmonics of {@code hz} with a slow wobble, a loop of whole periods. */
    static AudioClip engine(double hz) {
        int frames = (int) Math.round(RATE / hz) * (int) hz; // about a second of whole periods
        float[] s = new float[frames];
        double f = (double) RATE / Math.round(RATE / hz);
        for (int i = 0; i < frames; i++) {
            double p = 2 * Math.PI * f * i / RATE;
            s[i] = (float) (0.35 * Math.sin(p) + 0.2 * Math.sin(2 * p + 0.3) + 0.12 * Math.sin(3 * p + 1)
                + 0.08 * Math.sin(5 * p) + 0.05 * Math.sin(7 * p + 2));
        }
        return AudioClip.builder(RATE, 1).floats(s).build();
    }

    private static final class World implements AutoCloseable {
        final TaskScheduler tasks = new TaskScheduler();
        final AssetCache cache = new AssetCache(tasks);
        final AudioRenderer renderer = AudioRenderer.offline(AudioFormat.stereo(RATE));
        final SceneGraph scene = new SceneGraph(new ChangeClock());
        final Sounds sounds = new Sounds(scene);
        final AudioSync sync = new AudioSync(renderer, cache).add(sounds);
        final long head = scene.create();
        float[] out = new float[0];
        int frames;

        World() {
            scene.update();
            sounds.listeners().device(head);
        }

        void load() throws InterruptedException {
            sync.update();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (cache.loadingCount() > 0 && System.nanoTime() < deadline) {
                tasks.drainCompleted();
                Thread.sleep(1);
            }
            tasks.drainCompleted();
        }

        /** A 60 Hz frame: 800 frames rendered and kept. */
        void frame() {
            scene.update();
            sync.events();
            sounds.advance(1_000_000L / 60);
            sync.update();
            float[] block = renderer.render(800);
            if (2 * (frames + 800) > out.length) {
                out = java.util.Arrays.copyOf(out, Math.max(2 * out.length, 2 * (frames + 800)));
            }
            System.arraycopy(block, 0, out, 2 * frames, block.length);
            frames += 800;
        }

        void write(String name) throws IOException {
            Path dir = Path.of(System.getProperty("truesim.audio.proofs"));
            Files.createDirectories(dir);
            WavWriter.write(dir.resolve(name), java.util.Arrays.copyOf(out, 2 * frames), 2, RATE, 16);
            System.out.println("PROOF " + dir.resolve(name));
        }

        @Override
        public void close() {
            renderer.close();
            cache.close();
            tasks.close();
        }
    }

    @Test
    void knockingRocks() throws IOException, InterruptedException {
        try (World w = new World()) {
            AssetRef<AudioClip> clip = AssetRef.generated(AudioAssets.CLIP, (v, seed) -> knock(v), 0, 0);
            Sound knock = Sound.builder().root(Play.clip(clip)).attenuation(Attenuation.near(2, 80))
                .volume(Range.db(-3, 0)).pitch(Range.semitones(-3, 3))
                .concurrency(Concurrency.max(8).steal(Concurrency.Steal.OLDEST)).build();
            w.sounds.fire(knock, 0, 0, -1000); // loads the clip
            w.load();
            int rocks = 6;
            double[] y = new double[rocks];
            double[] v = new double[rocks];
            double[] rest = new double[rocks];
            for (int i = 0; i < rocks; i++) {
                y[i] = 3 + i;
                rest[i] = 0.3 * i;
            }
            for (int f = 0; f < 60 * 10; f++) {
                double dt = 1 / 60.0;
                for (int i = 0; i < rocks; i++) {
                    double x = (i - 2.5) * 3;
                    double z = -4 - 3 * i;
                    if (rest[i] > 0) {
                        rest[i] -= dt;
                        if (rest[i] <= 0 && v[i] == 0 && y[i] <= 0.01) {
                            y[i] = 3 + 4 * Hash.random(f, i);
                        }
                        continue;
                    }
                    v[i] -= 9.81 * dt;
                    y[i] += v[i] * dt;
                    if (y[i] <= 0) {
                        y[i] = 0;
                        double speed = -v[i];
                        if (speed > 1) {
                            long id = w.sounds.fire(knock, x, 0, z);
                            w.sounds.volume(id, Decibels.of((float) Math.min(1, speed / 9)), 0);
                        }
                        v[i] = speed > 1.5 ? speed * 0.35 : 0;
                        if (v[i] == 0) {
                            rest[i] = 1.5;
                        }
                    }
                }
                w.frame();
            }
            w.write("au_step6_knocking_rocks.wav");
        }
    }

    @Test
    void carPassingWithDopplerAndPanning() throws IOException, InterruptedException {
        try (World w = new World()) {
            AssetRef<AudioClip> clip = AssetRef.generated(AudioAssets.CLIP, (hz, seed) -> engine(hz), 110.0, 0);
            Sound engine = Sound.builder().root(Play.clip(clip)).attenuation(Attenuation.builder().distance(3, 400)
                .lowPass(20_000, 2_000).spread(2, 0).build()).loop().build();
            long car = w.scene.create();
            w.scene.setTranslation(car, -150, 0, -8);
            w.scene.update();
            w.sounds.play(engine, car);
            w.load();
            for (int f = 0; f < 60 * 10; f++) {
                w.scene.setTranslation(car, -150 + 30.0 * f / 60, 0, -8); // 30 m/s (108 km/h) past, 8 m ahead
                w.frame();
            }
            w.write("au_step7_car_passing_doppler.wav");
        }
    }

    @Test
    void manyLoopsWithStealingAsTheListenerWalks() throws IOException, InterruptedException {
        try (World w = new World()) {
            w.renderer.commands().budget(64);
            Sound[] hums = new Sound[6];
            for (int k = 0; k < hums.length; k++) {
                double hz = 160 * Math.pow(2, k / 4.0);
                AssetRef<AudioClip> clip = AssetRef.generated(AudioAssets.CLIP, (h, seed) -> engine(h), hz, 0);
                hums[k] = Sound.builder().root(Play.clip(clip)).attenuation(Attenuation.near(1, 60))
                    .volume(Range.db(-12, -9)).virtualization(com.truesim.scene.audio.Virtualization.KEEP_TIME).loop()
                    .build();
            }
            for (int i = 0; i < 400; i++) { // a 20 by 20 grid, 4 m apart: 400 loops for 64 voices
                double x = (i % 20 - 9.5) * 4;
                double z = (i / 20 - 9.5) * 4;
                w.sounds.fire(hums[i % hums.length], x, 0, z);
            }
            w.load();
            for (int f = 0; f < 60 * 12; f++) {
                double t = f / 60.0;
                w.scene.setTranslation(w.head, -40 + 80 * t / 12, 0, 8 * Math.sin(t)); // walking through them
                w.frame();
            }
            w.write("au_step7_loops_stealing_under_load.wav");
            System.out.println("PROOF real " + w.renderer.realVoices() + ", virtual " + w.renderer.virtualVoices());
        }
    }
}
