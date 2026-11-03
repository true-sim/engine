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
import com.truesim.scene.audio.SoundInstance;
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
    void knocksAsTheListenerWalksIntoAHall() throws IOException, InterruptedException {
        try (World w = new World()) {
            w.renderer.commands().layout(com.truesim.scene.audio.BusLayout.builder()
                .bus(com.truesim.scene.audio.Bus.SFX, b -> b.send(AudioSync.REVERB, -3))
                .aux(AudioSync.REVERB, b -> b.effect(com.truesim.scene.audio.AudioEffect.named("reverb")
                    .set("preset", 3))).build());
            long hall = w.scene.create();
            w.scene.setTranslation(hall, 30, 0, 0);
            w.scene.update();
            w.sounds.reverbZones().sphere(hall, 14, 8, com.truesim.scene.audio.AudioEffect.named("reverb")
                .set("preset", 1), 0);
            AssetRef<AudioClip> clip = AssetRef.generated(AudioAssets.CLIP, (v, seed) -> knock(v), 1, 0);
            Sound knock = Sound.builder().root(Play.clip(clip)).attenuation(Attenuation.builder().distance(1, 80)
                .reverbSend(0.4f, 1).build()).pitch(Range.semitones(-2, 2)).build();
            w.sounds.fire(knock, 0, 0, -1000);
            w.load();
            for (int f = 0; f < 60 * 14; f++) {
                double x = 30.0 * f / (60 * 11);
                w.scene.setTranslation(w.head, Math.min(30, x), 0, 0);
                if (f % 42 == 0) {
                    w.sounds.fire(knock, Math.min(30, x) + 2, 0, -3);
                }
                w.frame();
            }
            w.write("au_step10_walking_into_a_hall_reverb.wav");
        }
    }

    static final double BAR_S = 2.4; // 100 bpm in 4/4
    static final int[][] CHORDS = {{57, 60, 64}, {53, 57, 60}, {55, 60, 64}, {55, 59, 62}}; // Am F C G
    static final int[] ROOTS = {45, 41, 48, 43};

    static AssetRef<AudioClip> made(java.util.function.Supplier<AudioClip> make, String name) {
        return AssetRef.generated(AudioAssets.CLIP, (n, seed) -> make.get(), name, 0);
    }

    /** Explore: soft pads, four bars of Am F C G. */
    static AudioClip pads() {
        Synth s = new Synth(4 * BAR_S);
        for (int b = 0; b < 4; b++) {
            for (int n = 0; n < 3; n++) {
                s.note(b * BAR_S, BAR_S - 0.4, CHORDS[b][n], 0.32, 12, 1.7, 0.7, 0.5, (n - 1) * 0.5);
            }
        }
        return s.clip();
    }

    /** Explore: a bell arpeggio over the pads, eighth notes an octave up. */
    static AudioClip bells() {
        Synth s = new Synth(4 * BAR_S);
        int[] order = {0, 1, 2, 1, 2, 0, 1, 2};
        for (int b = 0; b < 4; b++) {
            for (int e = 0; e < 8; e++) {
                s.pluck(b * BAR_S + e * BAR_S / 8, CHORDS[b][order[e]] + 12 + (e == 5 ? 12 : 0), 0.22, 0.35,
                    e % 2 == 0 ? -0.4 : 0.4);
            }
        }
        return s.clip();
    }

    /** Combat: drums and bass. */
    static AudioClip drums() {
        Synth s = new Synth(4 * BAR_S);
        double beat = BAR_S / 4;
        for (int b = 0; b < 4; b++) {
            double t = b * BAR_S;
            s.kick(t, 0.55).kick(t + 2 * beat, 0.5).kick(t + 2.5 * beat, 0.35);
            s.snare(t + beat, 0.4, 100 + b).snare(t + 3 * beat, 0.4, 200 + b);
            for (int e = 0; e < 8; e++) {
                s.hat(t + e * beat / 2, e % 2 == 0 ? 0.5 : 0.3, 300 + 8 * b + e);
            }
        }
        return s.clip();
    }

    static AudioClip bass() {
        Synth s = new Synth(4 * BAR_S);
        for (int b = 0; b < 4; b++) {
            for (int e = 0; e < 8; e++) {
                s.note(b * BAR_S + e * BAR_S / 8, 0.2, ROOTS[b] - 12 + (e == 7 ? 12 : 0), 0.45, 7, 1.3, 0.005, 0.06,
                    0);
            }
        }
        return s.clip();
    }

    /** Combat: brass chords, faded in by intensity. */
    static AudioClip brass() {
        Synth s = new Synth(4 * BAR_S);
        for (int b = 0; b < 4; b++) {
            for (int n = 0; n < 3; n++) {
                s.note(b * BAR_S, BAR_S / 2 - 0.1, CHORDS[b][n] - 12 + (n == 0 ? 12 : 0), 0.22, 16, 0.9, 0.08, 0.15,
                    (n - 1) * 0.6);
                s.note(b * BAR_S + 0.75 * BAR_S, BAR_S / 4 - 0.1, CHORDS[b][n], 0.18, 16, 0.9, 0.05, 0.1,
                    (n - 1) * 0.6);
            }
        }
        return s.clip();
    }

    /** The transition: one bar, a crash and a fill. */
    static AudioClip hit() {
        Synth s = new Synth(BAR_S);
        double beat = BAR_S / 4;
        s.crash(0, 0.6, 7).kick(0, 1);
        for (int i = 0; i < 8; i++) {
            s.snare(2 * beat + i * beat / 4, 0.25 + 0.05 * i, 400 + i);
        }
        return s.clip();
    }

    /** The stinger: a rising arpeggio and a shimmer. */
    static AudioClip rise() {
        Synth s = new Synth(2.4);
        int[] notes = {69, 72, 76, 81, 84, 88};
        for (int i = 0; i < notes.length; i++) {
            s.pluck(i * 0.1, notes[i], 0.35, 0.6, -0.6 + 0.24 * i);
        }
        s.note(0.6, 0.8, 93, 0.12, 4, 1, 0.01, 0.8, 0);
        return s.clip();
    }

    @Test
    void musicExploringThenFighting() throws IOException, InterruptedException {
        try (World w = new World()) {
            w.renderer.commands().layout(com.truesim.scene.audio.BusLayout.builder()
                .bus(com.truesim.scene.audio.Bus.MUSIC, b -> b.send(AudioSync.REVERB, -9))
                .aux(AudioSync.REVERB, b -> b.effect(com.truesim.scene.audio.AudioEffect.named("reverb")
                    .set("preset", 1))).build());
            var intensity = com.truesim.scene.audio.SoundParameter.of("intensity", 0, 0, 1);
            var explore = com.truesim.scene.audio.MusicState.of("explore");
            var combat = com.truesim.scene.audio.MusicState.of("combat");
            var stinger = com.truesim.scene.audio.Stinger.of("rise");
            var four = com.truesim.scene.audio.Bar.of(5);
            AssetRef<AudioClip> brass = made(ListeningProofs::brass, "brass");
            com.truesim.scene.audio.Score score = com.truesim.scene.audio.Score.builder()
                .tempo(com.truesim.scene.audio.Tempo.bpm(100))
                .segment("explore", s -> s.stems(made(ListeningProofs::pads, "pads"),
                    made(ListeningProofs::bells, "bells")).exit(four).loop())
                .segment("combat", s -> s.stems(made(ListeningProofs::drums, "drums"),
                    made(ListeningProofs::bass, "bass"), brass)
                    .stem(brass, intensity,
                        com.truesim.scene.audio.Curve.points(0.2, 0, 1, 1),
                        com.truesim.scene.audio.Quantize.NEXT_BEAT).exit(four).loop())
                .segment("hit", s -> s.clip(made(ListeningProofs::hit, "hit"))
                    .exit(com.truesim.scene.audio.Bar.of(2)))
                .state(explore, com.truesim.scene.audio.Playlist.of("explore"))
                .state(combat, com.truesim.scene.audio.Playlist.of("combat"))
                .transition(explore, combat, t -> t.at(com.truesim.scene.audio.Quantize.NEXT_BAR).fadeOut(0.3)
                    .via("hit"))
                .transition(combat, explore, t -> t.at(com.truesim.scene.audio.Quantize.NEXT_BAR)
                    .crossfade(com.truesim.scene.audio.Beats.of(8)))
                .stinger(stinger, made(ListeningProofs::rise, "rise"), com.truesim.scene.audio.Quantize.NEXT_BEAT)
                .duck(-8).build();
            com.truesim.scene.audio.Music music = new com.truesim.scene.audio.Music(w.renderer.clock(),
                new CachedMusicClips(w.cache));
            w.sync.music(music);
            music.play(score, explore);
            for (int i = 0; i < 2_000 && w.cache.loadingCount() > 0 || i < 5; i++) { // primed before time runs
                w.tasks.drainCompleted();
                music.update();
                Thread.sleep(2);
            }
            for (int f = 0; f < 60 * 54; f++) {
                double t = f / 60.0;
                if (f == 60 * 12) {
                    music.state(combat);
                }
                music.set(intensity, (float) Math.max(0, Math.min(1, (t - 24) / 12)));
                if (f == 60 * 30) {
                    music.stinger(stinger);
                }
                if (f == 60 * 40) {
                    music.state(explore);
                }
                // offline renders run faster than real time: a frame waits for loading, as the game's would not
                while (w.cache.loadingCount() > 0) {
                    Thread.sleep(1);
                    w.tasks.drainCompleted();
                }
                music.update();
                w.frame();
            }
            w.write("au_step11_music_explore_combat_stinger.wav");
        }
    }

    @Test
    void v8DrivingThroughTheGearsThenPassingBy() throws IOException, InterruptedException {
        try (World w = new World()) {
            w.renderer.commands().layout(com.truesim.scene.audio.BusLayout.builder()
                .bus(com.truesim.scene.audio.Bus.SFX, b -> b.send(AudioSync.REVERB, -14))
                .aux(AudioSync.REVERB, b -> b.effect(com.truesim.scene.audio.AudioEffect.named("reverb")
                    .set("preset", 0))).build());
            w.sync.patches().register(com.truesim.engine.audio.dsp.Vehicles.v8());
            var rpm = com.truesim.engine.audio.dsp.Vehicles.RPM;
            var load = com.truesim.engine.audio.dsp.Vehicles.LOAD;
            var shift = com.truesim.engine.audio.dsp.Vehicles.SHIFT;
            var patch = com.truesim.scene.audio.Patch.named("v8", rpm, load, shift);
            // inside: idle, a blip, four gears pulled to 6,000 rpm, a lift-off on overrun, back to idle
            var cabin = w.sounds.play(Sound.builder().root(patch).loop().build());
            double r = 800;
            int gear = 0;
            double[] ratios = {1, 0.62, 0.45, 0.36};
            for (int f = 0; f < 60 * 16; f++) {
                double t = f / 60.0;
                double l;
                if (t < 2) {
                    r = 800;
                    l = 0.05;
                } else if (t < 3) {
                    double u = (t - 2) / 1;
                    r = 800 + 3_200 * Math.sin(Math.PI * u);
                    l = u < 0.4 ? 0.9 : -0.6;
                } else if (t < 12) {
                    l = 1;
                    r += 42 * Math.pow(ratios[gear], 1.5); // pulls slower in the higher gears
                    if (r > 6_000 && gear < 3) {
                        gear++;
                        r *= ratios[gear] / ratios[gear - 1];
                        cabin.trigger(shift);
                    }
                    r = Math.min(r, 6_300);
                } else {
                    l = t < 14.5 ? -1 : 0.05;
                    r = Math.max(800, r - (t < 14.5 ? 1_500 : 900) / 60.0);
                }
                cabin.set(rpm, (float) r, 0.03).set(load, (float) l, 0.08);
                w.frame();
            }
            cabin.stop(0.2);
            // outside: the car passes 6 m away at 110 km/h, in third at 4,500 rpm, lifting off as it goes
            long car = w.scene.create();
            var outside = w.sounds.play(Sound.builder().root(patch).attenuation(Attenuation.builder().distance(3, 400)
                .reverbSend(0.3f, 1).build()).loop().build(), car);
            double v = 110 / 3.6;
            for (int f = 0; f < 60 * 12; f++) {
                double t = f / 60.0;
                w.scene.setTranslation(car, -6 * v + v * t, 0, -6);
                outside.set(rpm, (float) (t < 6.5 ? 4_500 + 60 * t : 4_200 - 200 * (t - 6.5)), 0.05)
                    .set(load, t < 6.5 ? 0.9f : -0.8f, 0.1);
                w.frame();
            }
            w.write("au_step12_v8_gears_and_pass_by.wav");
        }
    }

    @Test
    void streetCamerasHeardFromATelevision() throws IOException, InterruptedException {
        try (World w = new World()) {
            var tvBus = com.truesim.scene.audio.Bus.child(com.truesim.scene.audio.Bus.SFX, "tv");
            w.renderer.commands().layout(com.truesim.scene.audio.BusLayout.builder()
                .bus(com.truesim.scene.audio.Bus.SFX, b -> b.send(AudioSync.REVERB, -10))
                .bus(tvBus, b -> b.effect(com.truesim.scene.audio.AudioEffect.named("eq.bandPass").set("low", 280)
                    .set("high", 4_500)).effect(com.truesim.scene.audio.AudioEffect.named("compressor")
                    .set("threshold", -24).set("ratio", 4).set("makeup", 6)))
                .aux(AudioSync.REVERB, b -> b.effect(com.truesim.scene.audio.AudioEffect.named("reverb")
                    .set("preset", 3))).build());
            w.sync.patches().register(com.truesim.engine.audio.dsp.Vehicles.v8());
            var rpm = com.truesim.engine.audio.dsp.Vehicles.RPM;
            var load = com.truesim.engine.audio.dsp.Vehicles.LOAD;
            // two street cameras far away; the living room's television 2.5 m ahead of the head
            long cam1 = w.scene.create();
            w.scene.setTranslation(cam1, 300, 1.5, 0);
            long cam2 = w.scene.create();
            w.scene.setTranslation(cam2, -400, 1.5, 0);
            long tvNode = w.scene.create();
            w.scene.setTranslation(tvNode, 0, 0.5, -2.5);
            w.scene.update();
            var one = w.sounds.listeners().render(cam1, com.truesim.scene.audio.ListenerMix.mono()
                .buses(com.truesim.scene.audio.Bus.SFX).maxDistance(120).gain(9));
            var two = w.sounds.listeners().render(cam2, com.truesim.scene.audio.ListenerMix.mono()
                .buses(com.truesim.scene.audio.Bus.SFX).maxDistance(120).gain(9));
            Sound speaker = Sound.builder().bus(tvBus).root(com.truesim.scene.audio.Play.input())
                .attenuation(Attenuation.builder().distance(0.5, 30).reverbSend(0.5f, 1).build()).build();
            AssetRef<AudioClip> knockClip = AssetRef.generated(AudioAssets.CLIP, (v, seed) -> knock(v), 2, 0);
            Sound knock = Sound.builder().root(Play.clip(knockClip)).attenuation(Attenuation.near(1, 80))
                .pitch(Range.semitones(-2, 2)).build();
            // the second camera: rain on a roof, pink noise through a band, a gust now and then
            w.sync.patches().register(com.truesim.engine.audio.dsp.Patch.builder("rain").graph(g -> g.output(
                g.add(() -> com.truesim.engine.audio.dsp.Filter.bandPass(2_500, 0.6)).in(g.add(
                    () -> com.truesim.engine.audio.dsp.Noise.pink().seed(4))).plus(g.add(
                    () -> com.truesim.engine.audio.dsp.Crackle.of().seed(9)).set("rate", 90).times(0.5)).times(0.6)))
                .build());
            w.sounds.play(Sound.builder().root(com.truesim.scene.audio.Patch.named("rain"))
                .attenuation(Attenuation.near(2, 60)).loop().build(), -398, 2, -3);
            w.sounds.fire(knock, 0, 0, -1000);
            w.load();
            SoundInstance tv = w.sounds.play(speaker, tvNode).input(one);
            SoundInstance car = null;
            long carNode = w.scene.create();
            for (int f = 0; f < 60 * 22; f++) {
                double t = f / 60.0;
                if (t < 4 && f % 50 == 0) {
                    w.sounds.fire(knock, 302, 1, -2); // someone knocking at a door by the first camera
                }
                if (f == 60 * 4) {
                    car = w.sounds.play(Sound.builder().root(com.truesim.scene.audio.Patch.named("v8", rpm, load,
                        com.truesim.engine.audio.dsp.Vehicles.SHIFT)).attenuation(Attenuation.near(3, 300))
                        .loop().build(), carNode);
                }
                if (car != null) {
                    double ct = t - 4;
                    w.scene.setTranslation(carNode, 300 - 60 + 14 * ct, 0, -8);
                    car.set(rpm, (float) (2_500 + 300 * ct), 0.05).set(load, 0.7f, 0.1);
                    if (ct > 8.5) {
                        car.stop(0.3);
                        car = null;
                    }
                }
                if (f == 60 * 13) {
                    tv.input(two); // switch channel
                }
                if (f == 60 * 17) {
                    w.sounds.fire(knock, 1.5, 0, -1); // a knock in the room itself: full band, near
                }
                if (f == 60 * 19) {
                    tv.stop(); // off
                }
                w.frame();
            }
            w.write("au_step13_street_cameras_on_a_tv.wav");
        }
    }

    /** A footstep on surface {@code key / 3} (grass, stone, gravel), variant {@code key % 3}. */
    static AudioClip footstep(int key) {
        int surface = key / 3;
        int variant = key % 3;
        int frames = RATE / 4;
        float[] s = new float[frames];
        double low = 0;
        double band = 0;
        float peak = 0;
        for (int i = 0; i < frames; i++) {
            double t = (double) i / RATE;
            double noise = Hash.random(31 * key + 7, i) * 2 - 1;
            double v;
            if (surface == 0) { // grass: a padded heel thump, then a rustle of blades under the sole
                low += 0.12 * (noise - low);
                v = 0.7 * Math.sin(2 * Math.PI * (60 + 6 * variant) * t) * Math.exp(-t * 30)
                    + 0.45 * (noise - low) * Math.exp(-Math.abs(t - 0.04) * 30) * Math.min(1, t * 150);
            } else if (surface == 1) { // stone: heel and toe knocks, a little grit scraping
                double toe = t - 0.045 - 0.008 * variant;
                band += 0.3 * (noise - band);
                v = 0.6 * Math.sin(2 * Math.PI * (700 + 90 * variant) * t) * Math.exp(-t * 110)
                    + 0.5 * Math.sin(2 * Math.PI * 95 * t) * Math.exp(-t * 45)
                    + (toe > 0 ? 0.35 * Math.sin(2 * Math.PI * (1_100 + 70 * variant) * toe) * Math.exp(-toe * 140)
                        : 0) + 0.15 * (noise - band) * Math.exp(-t * 25);
            } else { // gravel: a crunch of small stones shifting over 120 ms, a dull thump under it
                double click = Hash.random(91 * key, i / 40) < 0.18 ? noise : 0;
                v = 0.9 * click * Math.exp(-t * 18) * Math.min(1, t * 80)
                    + 0.35 * Math.sin(2 * Math.PI * 80 * t) * Math.exp(-t * 35);
            }
            s[i] = (float) v;
            peak = Math.max(peak, Math.abs(s[i]));
        }
        for (int i = 0; i < frames; i++) {
            s[i] *= 0.8f / peak;
        }
        return AudioClip.builder(RATE, 1).floats(s).build();
    }

    enum Ground {
        GRASS, STONE, GRAVEL
    }

    @Test
    void footstepsWalkingThenRunningOverGrassStoneAndGravel() throws IOException, InterruptedException {
        try (World w = new World()) {
            w.renderer.commands().layout(com.truesim.scene.audio.BusLayout.builder()
                .bus(com.truesim.scene.audio.Bus.SFX, b -> b.send(AudioSync.REVERB, -16))
                .aux(AudioSync.REVERB, b -> b.effect(com.truesim.scene.audio.AudioEffect.named("reverb")
                    .set("preset", 0).set("decay", 0.9))).build());
            var surface = com.truesim.scene.audio.SwitchGroup.of("surface", Ground.class, Ground.GRASS);
            var steps = com.truesim.scene.audio.Switch.on(surface);
            for (Ground g : Ground.values()) {
                com.truesim.scene.audio.SoundNode[] variants = new com.truesim.scene.audio.SoundNode[3];
                for (int v = 0; v < 3; v++) {
                    variants[v] = Play.clip(AssetRef.generated(AudioAssets.CLIP, (k, seed) -> footstep(k),
                        3 * g.ordinal() + v, 0));
                }
                steps = steps.when(g, com.truesim.scene.audio.Random.of(variants).noRepeat(1));
            }
            Sound step = Sound.builder().root(steps).attenuation(Attenuation.near(1, 40)).volume(Range.db(6, 9))
                .pitch(Range.semitones(-1, 1)).build();
            // a walker: hip and two feet; a walk of 1.1 s and a run of 0.66 s, a step each half
            double[] rest = new double[30];
            for (int j = 0; j < 3; j++) {
                rest[10 * j + 6] = 1;
                rest[10 * j + 7] = 1;
                rest[10 * j + 8] = 1;
                rest[10 * j + 9] = 1;
            }
            var legs = new com.truesim.scene.animation.Skeleton(new String[] {"hip", "footL", "footR"},
                new int[] {-1, 0, 0}, rest);
            var r = com.truesim.scene.animation.Rig.builder(legs);
            var footL = r.joint("footL");
            var footR = r.joint("footR");
            var left = com.truesim.scene.animation.AnimationEvent.of("left");
            var right = com.truesim.scene.animation.AnimationEvent.of("right");
            var walk = r.clip(cycle("walk", 1.1f)).event(0.1, left).event(0.65, right);
            var run = r.clip(cycle("run", 0.66f)).event(0.05, left).event(0.38, right);
            var base = r.layer();
            var rig = r.build();
            long hip = w.scene.create();
            long fl = w.scene.create(hip);
            long fr = w.scene.create(hip);
            w.scene.setTranslation(fl, 0, 0, -0.15);
            w.scene.setTranslation(fr, 0, 0, 0.15);
            var animators = new com.truesim.scene.animation.Animators(w.scene);
            var walker = animators.add(rig, new long[] {hip, fl, fr});
            walker.layer(base).play(walk);
            AudioBindings bindings = new AudioBindings(w.sounds, animators);
            AudioBindings.MaterialAt ground = (x, z) -> x < 8 ? 0 : x < 16 ? 1 : 2;
            bindings.onEvent(left, step, footL).surface(surface, ground);
            bindings.onEvent(right, step, footR).surface(surface, ground);
            for (int k = 0; k < 9; k++) { // every clip loaded before the walk
                w.sounds.set(w.sounds.fire(step, 0, 0, -1000), surface, Ground.values()[k / 3]);
            }
            w.load();
            w.scene.setTranslation(w.head, 10, 1.6, 2.5);
            double x = -4;
            animators.update(0);
            for (int f = 0; f < 60 * 17; f++) {
                double t = f / 60.0;
                if (f == 60 * 11) {
                    walker.layer(base).play(run, 0.3);
                }
                x += (t < 11 ? 1.4 : 4.2) / 60;
                w.scene.setTranslation(hip, x, 0, 0);
                w.scene.update();
                animators.update(1 / 60.0);
                w.frame();
            }
            w.write("au_step14_footsteps_grass_stone_gravel.wav");
        }
    }

    private static com.truesim.scene.animation.AnimationClip cycle(String name, float seconds) {
        return new com.truesim.scene.animation.AnimationClip(name, java.util.List.of(
            new com.truesim.scene.animation.Track("hip", 0, com.truesim.scene.animation.Track.Property.TRANSLATION,
                com.truesim.scene.animation.Track.Interpolation.LINEAR, new float[] {0, seconds},
                new float[] {0, 0, 0, 0, 0, 0}, 0)));
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
