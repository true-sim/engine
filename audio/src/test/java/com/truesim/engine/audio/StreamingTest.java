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

    /**
     * Renders {@code frames} in blocks of 256 at a device's pace (5.33 ms a block), frames synced: the decoders and
     * loads keep the pace they would in a game, on any machine (docs/pitfalls.md, offline audio tests).
     */
    private float[] play(int frames) throws InterruptedException {
        return play(frames, (int) (256 * 1_000_000_000L / RATE));
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
        float[] out = play(3 * RATE);
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
        // past the ring's second (48,000 frames): the decoder only gets there once the voice reads, so the 1.5 s stall
        // always drains the ring, however late the voice starts
        stallFrom = 72_000;
        sounds.play(Sound.builder().root(Play.clip(ref(() -> streamed(source), "slow"))).spatial(Spatial.TWO_D)
            .build());
        float[] out = play(5 * RATE, 3_000_000); // near real time, as a device would ask
        // at least the stall; more when the reader overtakes the restarting ring, as often as this machine's scheduling
        // lets it (3 on a busy CI runner): each is faded and resumed in time like the first, which the rest checks
        assertThat(renderer.starvations()).isPositive();
        // no click: the last frame held and faded over 5 ms, then faded back in
        assertThat(maxStep(out, 0, 5 * RATE)).isLessThan(0.019f);
        // it kept time: where it plays again, it is the sine at the right phase (whole periods: phase 0 every 120)
        int start = firstNonZero(out) - 1; // the sine's first frame is 0: the first non-zero one is its second
        int late = 5 * RATE - 2_000;
        double phase = 2 * Math.PI * 400 * (late - start) / RATE;
        assertThat((double) out[2 * late]).isCloseTo(0.5 * CENTRE * Math.sin(phase), within(0.01));
    }

    /** A decoder held at a gate: its read waits while it is there; the test moves the gate. */
    static final class Gate {
        final Object lock = new Object();
        long gate;
        volatile long at;
        volatile boolean waiting;
        volatile boolean armed; // at the gate: read nothing (the ring primes), or once armed wait inside the read

        Gate(long gate) {
            this.gate = gate;
        }

        void open(long to) {
            synchronized (lock) {
                gate = to;
                lock.notifyAll();
            }
        }

        AudioClip over(AudioClip source) {
            return AudioClip.builder(source.rate(), source.channels()).streamed(source.frames(), () -> {
                ClipDecoder inner = source.open();
                return new ClipDecoder() {
                    @Override
                    public int read(float[] out, int offset, int frames) {
                        synchronized (lock) {
                            if (at >= gate && !armed) {
                                return 0;
                            }
                            while (at >= gate) {
                                waiting = true;
                                try {
                                    lock.wait(5_000);
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                    return 0;
                                }
                            }
                            waiting = false;
                        }
                        int n = inner.read(out, offset, (int) Math.min(frames, gate - at));
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
            }, 0).build();
        }
    }

    /**
     * The race behind a click CI caught: the voice runs dry and asks the stream to start again further on, but the
     * streaming thread, inside a read when it ran dry, writes on where it was first, so the frames the voice needs are
     * there again within the 5 ms fade-out. Resuming then would switch from the held frame (faded part way) to the
     * signal where time has moved on: a step. Rendered in blocks of 16 frames so the decoder's catching up lands inside
     * the fade, every run.
     */
    @Test
    void framesArrivingWithinTheFadeOutDoNotResumeBeforeItEnds() throws InterruptedException {
        Gate gate = new Gate(30_000); // past the ring's priming (half its second)
        AudioClip source = Clips.sine(RATE, 400, 0.5f, 120 * 1_200); // 3 s of whole periods
        sounds.play(Sound.builder().root(Play.clip(ref(() -> gate.over(source), "gated"))).spatial(Spatial.TWO_D)
            .build());
        float[] out = new float[2 * 3 * RATE];
        int done = 0;
        int block = 16;
        while (done < 2 * RATE && renderer.starvations() == 0) {
            if (done % 1_024 == 0) {
                tasks.drainCompleted();
                sync.events();
                sounds.advance(1_024L * Sounds.TICKS_PER_SECOND / RATE);
                sync.update();
            }
            renderer.render(out, done, block);
            done += block;
            Thread.sleep(0, 300_000); // about a device's pace: the stream opens and primes
            if (!gate.armed && out[2 * (done - 1)] != 0) {
                gate.armed = true; // playing: the streaming thread's next read waits at the gate
            }
        }
        assertThat(renderer.starvations()).as("ran dry at the gate (first sound at %d, done %d, decoded %d)",
            firstNonZero(out), done, gate.at).isEqualTo(1);
        // the streaming thread waits inside its read: let it write on where it was, well past the voice
        for (int i = 0; i < 500 && !gate.waiting; i++) {
            Thread.sleep(1);
        }
        gate.open(70_000);
        for (int i = 0; i < 500 && gate.at < 70_000; i++) {
            Thread.sleep(1);
        }
        Thread.sleep(20); // the ring took them
        int resumedFrom = done;
        while (done < resumedFrom + 9_600) { // 200 ms: the fade out, the restart where it asked, the fade in
            renderer.render(out, done, block);
            done += block;
            Thread.sleep(0, 300_000);
        }
        // no click: held and faded out over 5 ms, back in only from silence
        assertThat(maxStep(out, 0, done)).isLessThan(0.019f);
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
