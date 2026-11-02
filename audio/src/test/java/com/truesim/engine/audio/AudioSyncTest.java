package com.truesim.engine.audio;

import static com.truesim.testing.AllocationAssertions.assertAllocationFree;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.truesim.common.jobs.TaskScheduler;
import com.truesim.common.state.ChangeClock;
import com.truesim.scene.assets.AssetCache;
import com.truesim.scene.assets.AssetRef;
import com.truesim.scene.assets.Generator;
import com.truesim.scene.audio.AudioAssets;
import com.truesim.scene.audio.AudioClip;
import com.truesim.scene.audio.Attenuation;
import com.truesim.scene.audio.Bus;
import com.truesim.scene.audio.Play;
import com.truesim.scene.audio.Segments;
import com.truesim.scene.audio.Sequence;
import com.truesim.scene.audio.Sound;
import com.truesim.scene.audio.SoundInstance;
import com.truesim.scene.audio.SoundParameter;
import com.truesim.scene.audio.Sounds;
import com.truesim.scene.audio.Spatial;
import com.truesim.scene.core.SceneGraph;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The sync from stores to the renderer, offline: starts at the schedule point, stops, ends, drops, positions. */
class AudioSyncTest {

    static final int RATE = 48_000;
    static final float CENTRE = (float) Math.cos(Math.PI / 4);

    /** Clips made from a description: a constant level for a number of frames, or a failure, or a slow load. */
    record Spec(float level, int frames, long sleepMillis) {
    }

    static final Generator<Spec, AudioClip> MAKE = (spec, seed) -> {
        if (spec.frames() < 0) {
            throw new IllegalStateException("no clip");
        }
        Thread.sleep(spec.sleepMillis());
        return Clips.constant(RATE, spec.level(), spec.frames());
    };

    static AssetRef<AudioClip> clip(float level, int frames) {
        return AssetRef.generated(AudioAssets.CLIP, MAKE, new Spec(level, frames, 0), 0);
    }

    private TaskScheduler tasks;
    private AssetCache cache;
    private AudioRenderer renderer;
    private AudioSync sync;
    private SceneGraph scene;
    private Sounds sounds;

    @BeforeEach
    void setUp() {
        tasks = new TaskScheduler();
        cache = new AssetCache(tasks);
        renderer = AudioRenderer.offline(AudioFormat.stereo(RATE));
        scene = new SceneGraph(new ChangeClock());
        sounds = new Sounds(scene);
        sync = new AudioSync(renderer, cache).add(sounds);
    }

    @AfterEach
    void tearDown() {
        renderer.close();
        cache.close();
        tasks.close();
    }

    /** Waits for the loads asked for, a frame at a time. */
    private void settle() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (cache.loadingCount() > 0) {
            tasks.drainCompleted();
            assertThat(System.nanoTime()).isLessThan(deadline);
            Thread.sleep(1);
        }
        tasks.drainCompleted();
    }

    /** One frame: events, the stores advanced, the sync, then {@code frames} rendered. */
    private float[] frame(int frames) {
        sync.events();
        sounds.advance((long) frames * Sounds.TICKS_PER_SECOND / RATE);
        sync.update();
        return renderer.render(frames);
    }

    @Test
    void aSoundStartsAtTheFramesSchedulePointAndEndsItsInstance() throws InterruptedException {
        Sound flat = Sound.builder().root(Play.clip(clip(0.5f, 1_000))).spatial(Spatial.TWO_D).build();
        sounds.fire(flat);
        sync.update(); // acquires the clip
        settle();
        long start = renderer.clock().schedulePoint();
        sync.update();
        assertThat(sync.schedulePoint()).isEqualTo(start);
        assertThat(sync.voices()).isEqualTo(1);
        float[] out = renderer.render(4_096);
        int first = 0;
        while (out[2 * first] == 0) {
            first++;
        }
        assertThat((long) first).isEqualTo(start);
        assertThat(out[2 * first]).isEqualTo(0.5f * CENTRE);
        assertThat(out[2 * (first + 1_000)]).isZero();
        frame(256);
        assertThat(sync.voices()).isZero();
        assertThat(sounds.size()).as("its voices all ended: the instance ends").isZero();
    }

    @Test
    void sequencesFollowAndStopsFade() throws InterruptedException {
        Sound two = Sound.builder().root(Sequence.of(Play.clip(clip(0.25f, 500)), Play.clip(clip(0.5f, 500))))
            .spatial(Spatial.TWO_D).build();
        long id = sounds.fire(two);
        sync.update();
        settle();
        long start = renderer.clock().schedulePoint();
        frame(0);
        frame(0); // the second waits for the first to start, then follows it exactly
        float[] out = renderer.render(2_048);
        assertThat(out[2 * (int) (start + 499)]).isEqualTo(0.25f * CENTRE);
        assertThat(out[2 * (int) (start + 500)]).isEqualTo(0.5f * CENTRE);
        assertThat(out[2 * (int) (start + 1_000)]).isZero();
        frame(256);
        assertThat(sounds.alive(id)).isFalse();

        Sound loop = Sound.builder().root(Play.clip(clip(0.5f, 64))).spatial(Spatial.TWO_D).loop().build();
        SoundInstance looping = sounds.play(loop);
        sync.update();
        settle();
        frame(2_048);
        looping.stop(0.01);
        float[] stopping = frame(2_048);
        assertThat(stopping[2 * 2_000]).isZero();
        frame(256);
        frame(256);
        assertThat(looping.alive()).isFalse();
        assertThat(sync.voices()).isZero();
    }

    @Test
    void anOutroPlaysWhenStopped() throws InterruptedException {
        Sound machine = Sound.builder().root(Segments.loop(clip(0.25f, 64)).outro(clip(0.5f, 6_000)))
            .spatial(Spatial.TWO_D).build();
        SoundInstance m = sounds.play(machine);
        sync.update();
        settle();
        frame(2_048);
        m.stop();
        frame(0);
        float[] out = renderer.render(4_096);
        // the loop fades out over 30 ms as the outro starts: then only the outro
        assertThat((double) out[2 * 4_000]).isCloseTo(0.5 * CENTRE, within(1e-6));
        frame(4_096);
        frame(256);
        frame(256);
        assertThat(m.alive()).isFalse();
    }

    @Test
    void parametersVolumeAndPauseReachTheVoices() throws InterruptedException {
        SoundParameter level = SoundParameter.of("level", 1, 0, 1);
        Sound bed = Sound.builder().root(Play.clip(clip(0.5f, 64)).volume(level,
            com.truesim.scene.audio.Curve.points(0, 0, 1, 1))).spatial(Spatial.TWO_D).loop().build();
        SoundInstance b = sounds.play(bed);
        sync.update();
        settle();
        frame(2_048);
        b.set(level, 0.5f, 0);
        float[] half = frame(4_096);
        assertThat((double) half[2 * 4_000]).isCloseTo(0.25 * CENTRE, within(1e-6));
        b.volume(-6.0206, 0);
        float[] quarter = frame(4_096);
        assertThat((double) quarter[2 * 4_000]).isCloseTo(0.125 * CENTRE, within(1e-5));
        b.pause(true);
        float[] paused = frame(4_096);
        assertThat(paused[2 * 4_000]).isZero();
        b.pause(false);
        b.kill();
        frame(4_096);
        frame(256);
        assertThat(sync.voices()).isZero();
    }

    @Test
    void placedSoundsPanAndAttenuateFromTheListener() throws InterruptedException {
        long head = scene.create();
        scene.setTranslation(head, 7_000_000, 0, 0); // far from the origin: positions are doubles
        scene.update();
        sounds.listeners().device(head);
        Sound knock = Sound.builder().root(Play.clip(clip(0.5f, 48_000))).attenuation(Attenuation.near(1, 100))
            .build();
        sounds.fire(knock, 7_000_000 + 10, 0, 0); // ten metres to the right
        sync.update();
        settle();
        sounds.advance(0.0);
        frame(0);
        float[] out = frame(8_192);
        float expected = 0.5f * Attenuation.near(1, 100).gain(10);
        assertThat((double) out[2 * 8_000 + 1]).as("right").isCloseTo(expected, within(1e-5));
        assertThat((double) out[2 * 8_000]).as("left").isCloseTo(0, within(1e-6));
    }

    @Test
    void clipsLoadedTooLateAreDroppedAndFailedOnesEnd() throws InterruptedException {
        Sound slow = Sound.builder().root(Play.clip(AssetRef.generated(AudioAssets.CLIP, MAKE,
            new Spec(0.5f, 100, 2_000), 0))).spatial(Spatial.TWO_D).maxStartDelay(0.05).build();
        long id = sounds.fire(slow);
        sync.update();
        for (int i = 0; i < 8; i++) {
            frame(1_024); // 0.17 s
        }
        assertThat(sounds.alive(id)).isFalse();
        assertThat(sounds.dropped()).isEqualTo(1);
        assertThat(sync.dropped()).isEqualTo(1);
        Sound broken = Sound.builder().root(Play.clip(clip(0.5f, -1))).spatial(Spatial.TWO_D).build();
        long b = sounds.fire(broken);
        sync.update();
        settle();
        frame(256);
        assertThat(sounds.alive(b)).isFalse();
    }

    @Test
    void theUiStorePlaysOnTheUiBus() throws InterruptedException {
        Sounds ui = Sounds.ui();
        sync.add(ui);
        renderer.commands().busGain(Bus.UI, 0.5f);
        ui.play(clip(0.5f, 4_000));
        sync.update();
        settle();
        frame(0);
        float[] out = renderer.render(4_096);
        assertThat(Clips.peak(out, 2, 0, 1_024, 4_000)).isEqualTo(0.25f * CENTRE);
    }

    @Test
    void killingFreesTheSlotsAndTheLogRebuilds() throws InterruptedException {
        Sounds small = new Sounds(scene, 2);
        sync.add(small);
        Sound flat = Sound.builder().root(Play.clip(clip(0.5f, 48_000))).spatial(Spatial.TWO_D).build();
        long a = small.fire(flat);
        long b = small.fire(flat);
        long c = small.fire(flat); // the log holds two: the sync rebuilds from the store
        sync.update();
        settle();
        frame(256);
        assertThat(sync.voices()).isEqualTo(3);
        small.kill(a);
        small.kill(b);
        small.kill(c);
        frame(1_024);
        frame(256);
        assertThat(sync.voices()).isZero();
    }

    @Test
    void steadyFramesAllocateNothing() throws InterruptedException {
        long head = scene.create();
        scene.update();
        sounds.listeners().device(head);
        Sound loop = Sound.builder().root(Play.clip(clip(0.1f, 64))).loop().build();
        for (int i = 0; i < 20; i++) {
            sounds.fire(loop, i, 0, -i);
        }
        sync.update();
        settle();
        frame(256);
        float[] out = new float[512];
        assertAllocationFree(() -> {
            sync.events();
            sounds.advance(5_333L);
            sync.update();
            renderer.render(out, 0, 256);
        }, 20_000, 2_000, 10);
    }
}
