package com.truesim.engine.audio;

import static com.truesim.testing.AllocationAssertions.assertAllocationFree;
import static org.assertj.core.api.Assertions.assertThat;

import com.truesim.common.jobs.TaskScheduler;
import com.truesim.common.state.ChangeClock;
import com.truesim.scene.assets.AssetCache;
import com.truesim.scene.assets.AssetRef;
import com.truesim.scene.audio.Attenuation;
import com.truesim.scene.audio.AudioAssets;
import com.truesim.scene.audio.AudioClip;
import com.truesim.scene.audio.Curve;
import com.truesim.scene.audio.Play;
import com.truesim.scene.audio.Random;
import com.truesim.scene.audio.Scatter;
import com.truesim.scene.audio.Sound;
import com.truesim.scene.audio.SoundField;
import com.truesim.scene.audio.SoundInstance;
import com.truesim.scene.audio.SoundParameter;
import com.truesim.scene.audio.Sounds;
import com.truesim.scene.core.SceneGraph;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Many sounds (§6 step 19): scatters of one-shots around the listener, fields of points, lines and areas. */
class ManySoundsTest {

    static final int RATE = 48_000;
    static final SoundParameter RAIN = SoundParameter.of("rain", 1, 0, 1);

    private TaskScheduler tasks;
    private AssetCache cache;
    private AudioRenderer renderer;
    private AudioSync sync;
    private SceneGraph scene;
    private Sounds sounds;
    private long head;

    @BeforeEach
    void setUp() {
        tasks = new TaskScheduler();
        cache = new AssetCache(tasks);
        renderer = AudioRenderer.offline(AudioFormat.stereo(RATE));
        scene = new SceneGraph(new ChangeClock());
        sounds = new Sounds(scene);
        sync = new AudioSync(renderer, cache).add(sounds);
        head = scene.create();
        scene.update();
        sounds.listeners().device(head);
    }

    @AfterEach
    void tearDown() {
        renderer.close();
        cache.close();
        tasks.close();
    }

    private void settle() throws InterruptedException {
        sync.update();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (cache.loadingCount() > 0 && System.nanoTime() < deadline) {
            tasks.drainCompleted();
            Thread.sleep(1);
        }
        tasks.drainCompleted();
    }

    /** A 60 Hz frame: the head where asked, events, advance, sync, 800 frames. */
    private float[] frame(double x, double y, double z) {
        scene.setTranslation(head, x, y, z);
        scene.update();
        sync.events();
        sounds.advance(Sounds.TICKS_PER_SECOND / 60);
        sync.update();
        return renderer.render(800);
    }

    private static AssetRef<AudioClip> tone(double hz, int n) {
        return AssetRef.generated(AudioAssets.CLIP, (f, seed) -> Clips.sine(RATE, f, 0.2f, (int) (RATE / f) * 50), hz,
            n);
    }

    /** A drop: 0.4 s of a tone. */
    private static AssetRef<AudioClip> drop(double hz, int n) {
        return AssetRef.generated(AudioAssets.CLIP, (f, seed) -> Clips.sine(RATE, f, 0.2f, RATE * 2 / 5), hz, n);
    }

    @Test
    void aCrowdOfFiveThousandPointsCostsItsNearestVoicesAndABed() throws InterruptedException {
        double[] xyz = new double[3 * 5_000];
        for (int i = 0; i < 5_000; i++) {
            xyz[3 * i] = (i % 100) * 2 - 100;
            xyz[3 * i + 2] = (i / 100) * 2 - 50;
        }
        Sound murmur = Sound.builder().root(Play.clip(tone(200, 1))).attenuation(Attenuation.near(2, 200)).loop()
            .build();
        sounds.field(murmur, SoundField.points(xyz).nearest(4));
        settle();
        float[] out = null;
        for (int f = 0; f < 30; f++) {
            out = frame(0.5 + f * 0.2, 1.7, 0.5);
        }
        for (int f = 0; f < 15; f++) { // standing still: the voices let go have faded
            out = frame(6.5, 1.7, 0.5);
        }
        assertThat(sync.voices()).as("4 nearest and the bed").isEqualTo(5);
        assertThat(Clips.peak(out, 2, 0, 0, 800)).isGreaterThan(0.01f);
        // walking on: other points become the nearest, still 4 voices and the bed (and voices fading out)
        int most = 0;
        for (int f = 0; f < 120; f++) {
            frame(6.5 + f * 0.1, 1.7, 0.5);
            most = Math.max(most, sync.voices());
        }
        assertThat(most).as("and a few fading out as nearer points take over").isLessThanOrEqualTo(9);
        for (int f = 0; f < 15; f++) {
            frame(18.5, 1.7, 0.5);
        }
        assertThat(sync.voices()).isEqualTo(5);
        // allocation-free once going
        double[] x = {18.5};
        float[] block = new float[2 * 800];
        assertAllocationFree(() -> {
            x[0] += 0.01;
            scene.setTranslation(head, x[0], 1.7, 0.5);
            scene.update();
            sync.events();
            sounds.advance(Sounds.TICKS_PER_SECOND / 60);
            sync.update();
            renderer.render(block, 0, 800);
        }, 200, 50, 10);
    }

    @Test
    void walkingAlongARiverHearsOneContinuousSource() throws InterruptedException {
        Sound river = Sound.builder().root(Play.clip(tone(150, 2))).attenuation(Attenuation.near(3, 120)).loop()
            .build();
        SoundInstance flowing = sounds.field(river, SoundField.line(-50, 0, -10, 0, 0, -5, 50, 0, -12));
        settle();
        float[] levels = new float[240];
        for (int f = 0; f < 240; f++) {
            float[] out = frame(-40 + f * 0.33, 1.7, 0);
            double e = 0;
            for (float v : out) {
                e += v * v;
            }
            levels[f] = (float) Math.sqrt(e / out.length);
        }
        assertThat(sync.voices()).as("the voice at the nearest point and the bed").isEqualTo(2);
        for (int f = 30; f < 240; f++) {
            assertThat(levels[f]).as("frame %s", f).isGreaterThan(0.005f);
            assertThat(levels[f] / levels[f - 1]).as("frame %s", f).isBetween(0.85f, 1.18f);
        }
        flowing.stop(0.1);
        for (int f = 0; f < 30; f++) {
            frame(40, 1.7, 0);
        }
        assertThat(sync.voices()).isZero();
        assertThat(sounds.alive(flowing.id())).isFalse();
    }

    @Test
    void standingInAnAreaItsVoiceIsAtTheListener() throws InterruptedException {
        Sound crickets = Sound.builder().root(Play.clip(tone(300, 3))).attenuation(Attenuation.near(2, 60)).loop()
            .build();
        SoundInstance field = sounds.field(crickets, SoundField.area(-20, 0, -20, 20, 0, -20, 20, 0, 20, -20, 0, 20));
        settle();
        float[] out = null;
        for (int f = 0; f < 20; f++) {
            out = frame(3, 0, 3);
        }
        assertThat(sync.voices()).isEqualTo(2);
        // the voice straight below the head: both ears alike
        double left = 0;
        double right = 0;
        for (int i = 0; i < 800; i++) {
            left += out[2 * i] * out[2 * i];
            right += out[2 * i + 1] * out[2 * i + 1];
        }
        assertThat(left / right).isBetween(0.9, 1.1);
        field.pause(true);
        frame(3, 0, 3);
        field.kill();
        for (int f = 0; f < 10; f++) {
            frame(3, 0, 3);
        }
        assertThat(sync.voices()).isZero();
    }

    @Test
    void aScatterSpawnsOneShotsAroundTheListenerEightAtMost() throws InterruptedException {
        Sound drops = Sound.builder().root(Scatter.of(Random.of(Play.clip(drop(2_000, 4)), Play.clip(drop(2_500, 5))))
            .around(6).rate(RAIN, Curve.points(0, 0, 1, 30))).attenuation(Attenuation.near(1, 30))
            .build();
        SoundInstance rain = sounds.play(drops);
        settle();
        int most = 0;
        for (int f = 0; f < 120; f++) {
            frame(0, 1.7, 0);
            most = Math.max(most, sync.voices());
        }
        assertThat(most).isBetween(5, AudioSync.GRAINS);
        rain.set(RAIN, 0); // no rain: no new drops; those falling play out
        for (int f = 0; f < 120; f++) {
            frame(0, 1.7, 0);
        }
        assertThat(sync.voices()).isZero();
        assertThat(sounds.alive(rain.id())).isTrue(); // the scatter goes on, silent
        rain.set(RAIN, 1);
        frame(0, 1.7, 0);
        rain.stop();
        for (int f = 0; f < 120; f++) {
            frame(0, 1.7, 0);
        }
        assertThat(sync.voices()).isZero();
        assertThat(sounds.alive(rain.id())).isFalse();
    }

    @Test
    void aScatterOfAConstantRateAndOneThatCannotLoad() throws InterruptedException {
        Sound birds = Sound.builder().root(Scatter.of(Play.clip(tone(1_000, 6))).around(20).rate(5)).build();
        sounds.play(birds);
        AssetRef<AudioClip> broken = AssetRef.generated(AudioAssets.CLIP, (v, seed) -> {
            throw new IllegalStateException("no clip");
        }, 0, 7);
        SoundInstance silent = sounds.play(Sound.builder().root(Scatter.of(Play.clip(broken))).build());
        settle();
        int heard = 0;
        for (int f = 0; f < 60; f++) {
            frame(0, 0, 0);
            heard = Math.max(heard, sync.voices());
        }
        assertThat(heard).isPositive();
        assertThat(sounds.alive(silent.id())).isFalse();
        assertThat(AudioSync.kthSmallest(new double[] {5, 1, 4, 2, 3}, 5, 1)).isEqualTo(2);
    }
}
