package com.truesim.engine.audio;

import com.truesim.common.jobs.TaskScheduler;
import com.truesim.common.math.random.Hash;
import com.truesim.common.state.ChangeClock;
import com.truesim.engine.audio.dsp.Vehicles;
import com.truesim.scene.assets.AssetCache;
import com.truesim.scene.assets.AssetRef;
import com.truesim.scene.audio.Attenuation;
import com.truesim.scene.audio.AudioAssets;
import com.truesim.scene.audio.AudioClip;
import com.truesim.scene.audio.AudioEffect;
import com.truesim.scene.audio.Bus;
import com.truesim.scene.audio.BusLayout;
import com.truesim.scene.audio.ListenerMix;
import com.truesim.scene.audio.Play;
import com.truesim.scene.audio.Sound;
import com.truesim.scene.audio.SoundInstance;
import com.truesim.scene.audio.Sounds;
import com.truesim.scene.audio.Spatial;
import com.truesim.scene.core.SceneGraph;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * The audio benchmark (§6 step 20, §2.23): scenes of voices, HRTF voices, rendering listeners, streams and patches,
 * each played through the sync and the renderer as a game does (60 Hz frames, 48 kHz, 256-frame blocks, the listener
 * walking), timed per block on the audio side (percentiles) and per frame for the sync, with the bytes the render
 * allocates. Behind {@code TRUESIM_AUDIO=true}, so the build runs it without coverage probes:
 *
 * <pre>TRUESIM_AUDIO=true ./gradlew :engine-audio:test --tests '*AudioBenchmark*'</pre>
 *
 * The heavy scene is §2.23's: 1,000 placed loops with 64 real (16 of them binaural, the rest of the HRTF ones in the
 * ambisonic field), two cameras heard from televisions (16 voices each), a V8 patch, 12 streamed loops (4 music
 * stems, 8 ambiences), a reverb aux, an equalizer and a compressor on the effects bus, the limiter. Target: under
 * 1.5 ms a block.
 */
@EnabledIfEnvironmentVariable(named = "TRUESIM_AUDIO", matches = "true")
class AudioBenchmark {

    static final int RATE = 48_000;
    static final int FRAMES = 60 * 30; // 30 s of game frames timed, after 10 s of warm-up

    private final List<String> rows = new ArrayList<>();

    @Test
    void scenes() throws InterruptedException {
        run("64 voices of 1,000 placed loops", 1_000, 0, 0, 0, false, false, false);
        run("16 HRTF voices", 16, 16, 0, 0, false, false, false);
        run("64 HRTF voices: 16 binaural, 48 in the field", 64, 64, 0, 0, false, false, false);
        run("2 rendering listeners, 16 voices each", 0, 0, 2, 0, false, false, false);
        run("12 streams (4 stems, 8 ambiences)", 0, 0, 0, 12, false, false, false);
        run("V8 patch", 0, 0, 0, 0, true, false, false);
        run("Heavy scene (2.23)", 1_000, 64, 2, 12, true, true, true);
        System.out.println();
        System.out.println("| Scene | Block median | p99 | p99.9 | max | Sync median | Sync p99 | Real voices | Listener blocks | Bytes a block |");
        System.out.println("|---|---|---|---|---|---|---|---|---|---|");
        for (String row : rows) {
            System.out.println(row);
        }
    }

    /** A loop of whole periods of a few harmonics of {@code hz}, about a second. */
    static AudioClip hum(double hz) {
        int period = (int) Math.round(RATE / hz);
        int frames = period * (int) Math.max(1, Math.round(hz));
        float[] s = new float[frames];
        for (int i = 0; i < frames; i++) {
            double p = 2 * Math.PI * i / period;
            s[i] = (float) (0.2 * Math.sin(p) + 0.1 * Math.sin(2 * p + 1) + 0.05 * Math.sin(3 * p + 2));
        }
        return AudioClip.builder(RATE, 1).floats(s).build();
    }

    @SuppressWarnings("checkstyle:ParameterNumber")
    private void run(String name, int loops, int hrtf, int cameras, int streams, boolean patch, boolean effects,
            boolean heavy) throws InterruptedException {
        try (TaskScheduler tasks = new TaskScheduler(); AssetCache cache = new AssetCache(tasks);
             AudioRenderer renderer = new AudioRenderer(AudioFormat.stereo(RATE), 2_048)) {
            SceneGraph scene = new SceneGraph(new ChangeClock());
            Sounds sounds = new Sounds(scene);
            AudioSync sync = new AudioSync(renderer, cache).add(sounds);
            long head = scene.create();
            scene.update();
            sounds.listeners().device(head);
            renderer.commands().budget(64);
            if (hrtf > 0) {
                renderer.commands().spatializer(new HrtfSpatializer(Hrtf.builtIn(RATE), 256, 20), 16);
            }
            if (effects) {
                renderer.commands().layout(BusLayout.builder()
                    .bus(Bus.SFX, b -> b.send(AudioSync.REVERB, -12)
                        .effect(AudioEffect.named("eq.peak").set("frequency", 2_500).set("gain", 3))
                        .effect(AudioEffect.named("compressor")))
                    .bus(Bus.AMBIENCE).bus(Bus.MUSIC)
                    .aux(AudioSync.REVERB, b -> b.effect(AudioEffect.named("reverb").set("preset", 1))).build());
            }
            List<AssetRef<AudioClip>> hums = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                hums.add(AssetRef.generated(AudioAssets.CLIP, (hz, seed) -> hum(hz), 80.0 + 37 * i, i));
            }
            for (int i = 0; i < loops; i++) {
                boolean binaural = i < hrtf;
                Sound sound = Sound.builder().root(Play.clip(hums.get(i % hums.size())))
                    .attenuation(Attenuation.near(2, 400)).spatial(binaural ? Spatial.HRTF : Spatial.THREE_D).loop()
                    .build();
                double a = 2 * Math.PI * Hash.random(1, i);
                double r = binaural ? 3 + 10 * Hash.random(2, i) : 5 + 300 * Hash.random(2, i);
                sounds.fire(sound, r * Math.sin(a), 1 + 2 * Hash.random(3, i), r * Math.cos(a));
            }
            for (int c = 0; c < cameras; c++) {
                long cameraNode = scene.create();
                scene.setTranslation(cameraNode, 200 * (c + 1), 1, 0);
                long tvNode = scene.create();
                scene.setTranslation(tvNode, 2 * c - 1, 1, -3);
                scene.update();
                var camera = sounds.listeners().render(cameraNode, ListenerMix.mono().buses(Bus.SFX));
                sounds.play(Sound.builder().root(Play.input()).attenuation(Attenuation.near(1, 50))
                    .priority(com.truesim.scene.audio.Priority.HIGHEST).build(), tvNode)
                    .input(camera);
                for (int v = 0; v < 16; v++) { // what each camera films
                    sounds.fire(Sound.builder().root(Play.clip(hums.get(v % hums.size())))
                        .attenuation(Attenuation.near(2, 60)).loop().build(), 200 * (c + 1) + 4 * Math.sin(v), 1,
                        4 * Math.cos(v));
                }
            }
            for (int s = 0; s < streams; s++) {
                AudioClip source = hum(60 + 20 * s);
                AssetRef<AudioClip> streamed = AssetRef.generated(AudioAssets.CLIP,
                    (n, seed) -> StreamingTest.streamed(source), s, 100);
                sounds.play(Sound.builder().root(Play.clip(streamed)).bus(s < 4 ? Bus.MUSIC : Bus.AMBIENCE)
                    .spatial(Spatial.TWO_D).volume(com.truesim.scene.audio.Range.db(-12, -12)).loop()
                    .priority(s < 4 ? com.truesim.scene.audio.Priority.HIGHEST : com.truesim.scene.audio.Priority.HIGH)
                    .build()); // music never goes virtual; ambiences rarely
            }
            SoundInstance car = null;
            if (patch) {
                sync.patches().register(Vehicles.v8());
                car = sounds.play(Sound.builder().root(PatchRenderTest.V8).attenuation(Attenuation.near(3, 200))
                    .priority(com.truesim.scene.audio.Priority.HIGH).loop().build(), 0, 0.5, -8);
            }
            // load, then 10 s of warm-up and 30 s timed
            sync.update();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (cache.loadingCount() > 0 && System.nanoTime() < deadline) {
                tasks.drainCompleted();
                Thread.sleep(1);
            }
            tasks.drainCompleted();
            for (int i = 0; i < 200; i++) { // streams prime on their thread: the store's time stands meanwhile
                sync.update();
                tasks.drainCompleted();
                Thread.sleep(2);
            }
            float[] out = new float[2 * 256];
            long[] blocks = new long[FRAMES * 4];
            long[] syncs = new long[FRAMES];
            int blockCount = 0;
            long carried = 0;
            var threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
            long bytes = 0;
            int real = 0;
            for (int f = -600; f < FRAMES; f++) {
                double t = (f + 600) / 60.0;
                scene.setTranslation(head, 20 * Math.sin(t / 7), 1.7, 20 * Math.cos(t / 9));
                double half = t / 3;
                scene.setRotation(head, 0, Math.sin(half), 0, Math.cos(half));
                scene.update();
                if (car != null) {
                    car.set(Vehicles.RPM, (float) (2_000 + 1_500 * Math.sin(t)), 0.05);
                }
                tasks.drainCompleted();
                sync.events();
                sounds.advance(Sounds.TICKS_PER_SECOND / 60);
                long s0 = System.nanoTime();
                sync.update();
                long s1 = System.nanoTime();
                if (f >= 0) {
                    syncs[f] = s1 - s0;
                }
                carried += 800;
                while (carried >= 256) { // the blocks of this frame, as the audio thread renders them
                    carried -= 256;
                    long a0 = threads.getCurrentThreadAllocatedBytes();
                    long b0 = System.nanoTime();
                    renderer.render(out, 0, 256);
                    long b1 = System.nanoTime();
                    long a1 = threads.getCurrentThreadAllocatedBytes();
                    if (f >= 0) {
                        blocks[blockCount++] = b1 - b0;
                        bytes += a1 - a0;
                    }
                }
                real = Math.max(real, renderer.realVoices());
            }
            long listened = renderer.listenerBlocks(0) + renderer.listenerBlocks(1);
            long[] b = Arrays.copyOf(blocks, blockCount);
            Arrays.sort(b);
            Arrays.sort(syncs);
            rows.add(String.format(Locale.ROOT, "| %s | %.3f ms | %.3f ms | %.3f ms | %.3f ms | %.3f ms | %.3f ms | %d | %d | %.1f |",
                name, b[b.length / 2] / 1e6, b[b.length * 99 / 100] / 1e6, b[b.length * 999 / 1000] / 1e6,
                b[b.length - 1] / 1e6, syncs[FRAMES / 2] / 1e6, syncs[FRAMES * 99 / 100] / 1e6, real, listened,
                (double) bytes / blockCount));
            System.out.println(rows.get(rows.size() - 1));
            if (heavy) {
                AudioOverlay overlay = new AudioOverlay(renderer, null).listeners(sounds);
                overlay.update();
                System.out.println(overlay.text());
            }
        }
    }
}
