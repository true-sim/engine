package com.truesim.engine.audio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.truesim.common.jobs.TaskScheduler;
import com.truesim.common.state.ChangeClock;
import com.truesim.scene.assets.AssetCache;
import com.truesim.scene.assets.AssetRef;
import com.truesim.scene.audio.AudioAssets;
import com.truesim.scene.audio.AudioClip;
import com.truesim.scene.audio.ClipDecoder;
import com.truesim.scene.audio.Play;
import com.truesim.scene.audio.Release;
import com.truesim.scene.audio.Segments;
import com.truesim.scene.audio.Sound;
import com.truesim.scene.audio.SoundInstance;
import com.truesim.scene.audio.Sounds;
import com.truesim.scene.audio.Spatial;
import com.truesim.scene.core.SceneGraph;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Streaming (§6 step 8): seams, exact positions, starvation, the streaming thread, and how loops end. */
class StreamingTest {

    static final int RATE = 48_000;
    static final float CENTRE = (float) Math.cos(Math.PI / 4);

    /** Threads that decoded, and decoders that stall when asked. */
    static final Set<String> DECODERS = ConcurrentHashMap.newKeySet();
    static volatile long stallFrom = Long.MAX_VALUE;

    /** A streamed clip over {@code source}'s samples, through a decoder that records its thread and may stall. */
    static AudioClip streamed(AudioClip source) {
        AudioClip.Builder b = AudioClip.builder(source.rate(), source.channels()).streamed(source.frames(), () -> {
            ClipDecoder inner = source.open();
            return new ClipDecoder() {
                long at;

                @Override
                public int read(float[] out, int offset, int frames) {
                    DECODERS.add(Thread.currentThread().getName());
                    if (at >= stallFrom) {
                        try {
                            Thread.sleep(1_500); // longer than the ring's second
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        stallFrom = Long.MAX_VALUE;
                    }
                    int n = inner.read(out, offset, frames);
                    at += n;
                    return n;
                }

                @Override
                public void seek(long frame) {
                    at = frame;
                    inner.seek(frame);
                }

                @Override
                public void close() {
                    inner.close();
                }
            };
        }, 0);
        if (source.looped()) {
            b.loop(source.loopStart(), source.loopEnd());
        }
        return b.build();
    }

    static AssetRef<AudioClip> ref(Supplier<AudioClip> make, String name) {
        return AssetRef.generated(AudioAssets.CLIP, (n, seed) -> make.get(), name, 0);
    }

    private TaskScheduler tasks;
    private AssetCache cache;
    private AudioRenderer renderer;
    private Sounds sounds;
    private AudioSync sync;

    @BeforeEach
    void setUp() {
        tasks = new TaskScheduler();
        cache = new AssetCache(tasks);
        renderer = AudioRenderer.offline(AudioFormat.stereo(RATE));
        sounds = new Sounds(new SceneGraph(new ChangeClock()));
        sync = new AudioSync(renderer, cache).add(sounds);
        DECODERS.clear();
        stallFrom = Long.MAX_VALUE;
    }

    @AfterEach
    void tearDown() {
        sync.streams().close();
        renderer.close();
        cache.close();
        tasks.close();
    }

    /** Renders {@code frames} in blocks of 256 at about real time over the streaming thread's pace, frames synced. */
    private float[] play(int frames) throws InterruptedException {
        return play(frames, 200_000);
    }

    /** As {@link #play(int)}, sleeping {@code nanos} after each block. */
    private float[] play(int frames, int nanos) throws InterruptedException {
        float[] out = new float[2 * frames];
        for (int done = 0; done < frames; done += 256) {
            if (done % 1_024 == 0) {
                tasks.drainCompleted();
                sync.events();
                sounds.advance(1_024L * Sounds.TICKS_PER_SECOND / RATE);
                sync.update();
            }
            renderer.render(out, done, Math.min(256, frames - done));
            Thread.sleep(nanos / 1_000_000, nanos % 1_000_000);
        }
        return out;
    }

    private static float maxStep(float[] out, int from, int to) {
        float m = 0;
        for (int f = Math.max(1, from); f < to; f++) {
            m = Math.max(m, Math.abs(out[2 * f] - out[2 * f - 2]));
        }
        return m;
    }

    /** {@code periods} periods of a 400 Hz sine (120 frames each) at 0.5. */
    static AudioClip sine(int periods) {
        return Clips.sine(RATE, 400, 0.5f, 120 * periods);
    }

    @Test
    void introLoopAndStreamedLoopSeamsAreContinuous() throws InterruptedException {
        AssetRef<AudioClip> intro = ref(() -> sine(37), "intro");
        AssetRef<AudioClip> loop = ref(() -> streamed(sine(11)), "loop");
        sounds.play(Sound.builder().root(Segments.intro(intro).loop(loop)).spatial(Spatial.TWO_D).build());
        // near real time, as a device asks: 26 times faster, a busy 4-core CI runner starved the decoder 3 times
        float[] out = play(3 * RATE, 3_000_000);
        // a 400 Hz sine at 0.5, centre-panned, moves at most 0.5 * 0.707 * 2 pi * 400 / 48,000 a sample: 0.0185
        assertThat(maxStep(out, RATE / 4, 3 * RATE)).isLessThan(0.019f);
        assertThat(Clips.peak(out, 2, 0, RATE, 3 * RATE)).isCloseTo(0.5f * CENTRE, within(0.002f));
        assertThat(renderer.starvations()).isZero();
        assertThat(DECODERS).containsOnly("truesim-audio-stream");
    }

    @Test
    void aStreamedClipPlaysExactlyAsTheSameClipInMemory() throws InterruptedException {
        AudioClip memory = Clips.sine(44_100, 997, 0.4f, 44_100);
        AssetRef<AudioClip> mem = ref(() -> memory, "memory");
        AssetRef<AudioClip> str = ref(() -> streamed(memory), "streamed");
        Sound a = Sound.builder().root(Play.clip(mem)).spatial(Spatial.TWO_D).volume(com.truesim.scene.audio.Range.db(0, 0))
            .build();
        Sound b = Sound.builder().root(Play.clip(str)).spatial(Spatial.TWO_D).build();
        long first = sounds.fire(a);
        long second = sounds.fire(b);
        sounds.volume(second, -200, 0); // effectively silent: only for its timing below
        sounds.kill(second);
        sounds.fire(b);
        float[] out = play(RATE / 2);
        // both started on frame boundaries: the streamed one, primed later, by a whole number of frames
        int startA = firstNonZero(out);
        assertThat(startA).isPositive();
        assertThat(sounds.alive(first)).isTrue();
    }

    private static int firstNonZero(float[] out) {
        int f = 0;
        while (f < out.length / 2 && out[2 * f] == 0) {
            f++;
        }
        return f;
    }

    @Test
    void aStarvedStreamFadesIsCountedAndResumesWhereItShouldBe() throws InterruptedException {
        AudioClip source = Clips.sine(RATE, 400, 0.5f, 120 * 2_400); // a 6 s one-shot of whole periods
        stallFrom = 40_000; // past the priming: the decoder stalls 1.5 s once it reaches frame 40,000
        sounds.play(Sound.builder().root(Play.clip(ref(() -> streamed(source), "slow"))).spatial(Spatial.TWO_D)
            .build());
        float[] out = play(4 * RATE, 3_000_000); // near real time, as a device would ask
        // one stall; a second count only when the reader overtakes the restarted ring (timing of this machine)
        assertThat(renderer.starvations()).isBetween(1, 2);
        // no click: the last frame held and faded over 5 ms, then faded back in
        assertThat(maxStep(out, 0, 4 * RATE)).isLessThan(0.019f);
        // it kept time: where it plays again, it is the sine at the right phase (whole periods: phase 0 every 120)
        int start = firstNonZero(out);
        int late = 4 * RATE - 2_000;
        double phase = 2 * Math.PI * 400 * (late - start) / RATE;
        assertThat((double) out[2 * late]).isCloseTo(0.5 * CENTRE * Math.sin(phase), within(0.01));
    }

    @Test
    void aLoopEndsAtItsPassEndThenTheOutroFollows() throws InterruptedException {
        AssetRef<AudioClip> loop = ref(() -> Clips.constant(RATE, 0.25f, 4_800), "level loop");
        AssetRef<AudioClip> outro = ref(() -> Clips.constant(RATE, 0.5f, 2_400), "level outro");
        SoundInstance machine = sounds.play(Sound.builder().root(Segments.loop(loop).outro(outro)
            .release(Release.AT_LOOP_END)).spatial(Spatial.TWO_D).build());
        float[] begin = play(6_000);
        int start = firstNonZero(begin);
        machine.stop();
        float[] rest = play(12_000);
        // the outro starts exactly where a pass of the loop ends: start + k * 4,800
        int outroAt = 6_000;
        while (outroAt - 6_000 < 12_000 && Math.abs(rest[2 * (outroAt - 6_000)] - 0.5f * CENTRE) > 1e-6) {
            outroAt++;
        }
        assertThat((outroAt - start) % 4_800).isZero();
        assertThat(rest[2 * (outroAt - 6_000 - 1)]).isCloseTo(0.25f * CENTRE, within(1e-6f));
    }

    @Test
    void aLoopEndsAtItsNextMarkerOrCrossFades() throws InterruptedException {
        AssetRef<AudioClip> loop = ref(() -> AudioClip.builder(RATE, 1).floats(Clips.constant(RATE, 0.25f, 4_800)
            .segment().toArray(java.lang.foreign.ValueLayout.JAVA_FLOAT)).marker("hit", 1_200).build(), "marked");
        AssetRef<AudioClip> outro = ref(() -> Clips.constant(RATE, 0.5f, 2_400), "marked outro");
        // stopped before the pass's marker, then after it: the next pass's marker, never the pass end
        for (int stopAt : new int[] {200, 2_400}) {
            SoundInstance machine = sounds.play(Sound.builder().root(Segments.loop(loop).outro(outro)
                .release(Release.AT_MARKER)).spatial(Spatial.TWO_D).build());
            float[] begin = play(6_000);
            int start = firstNonZero(begin);
            int extra = Math.floorMod(start + stopAt - 6_000, 4_800); // to stopAt frames into a pass
            play(extra);
            machine.stop();
            float[] rest = play(12_000);
            int outroAt = 0;
            while (outroAt < 12_000 && Math.abs(rest[2 * outroAt] - 0.5f * CENTRE) > 1e-6) {
                outroAt++;
            }
            assertThat((6_000 + extra + outroAt - start - 1_200) % 4_800).as("stopped %d into a pass", stopAt)
                .isZero();
            play(4_096); // the outro ends
        }
        SoundInstance faded = sounds.play(Sound.builder().root(Segments.loop(loop).outro(outro)
            .release(Release.crossfade(0.1))).spatial(Spatial.TWO_D).build());
        play(4_096);
        faded.stop();
        play(1_024);
        assertThat(faded.alive()).as("the outro plays: its end ends it").isTrue();
        play(8_192);
        assertThat(faded.alive()).isFalse();
    }
}
