package com.truesim.engine.audio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.truesim.common.jobs.TaskScheduler;
import com.truesim.common.state.ChangeClock;
import com.truesim.scene.assets.AssetCache;
import com.truesim.scene.assets.AssetRef;
import com.truesim.scene.audio.AudioAssets;
import com.truesim.scene.audio.AudioClip;
import com.truesim.scene.audio.Bar;
import com.truesim.scene.audio.Music;
import com.truesim.scene.audio.MusicState;
import com.truesim.scene.audio.Playlist;
import com.truesim.scene.audio.Quantize;
import com.truesim.scene.audio.Score;
import com.truesim.scene.audio.Sounds;
import com.truesim.scene.audio.Stinger;
import com.truesim.scene.audio.Tempo;
import com.truesim.scene.core.SceneGraph;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Music rendered (§6 step 11): transitions on the bar's sample, stems aligned for ten minutes, stingers ducking. */
class MusicRenderTest {

    static final int RATE = 48_000;
    static final int BAR = 48_000; // 240 bpm in 4/4: a beat of 12,000 frames
    static final float CENTRE = (float) Math.cos(Math.PI / 4);
    static final MusicState A = MusicState.of("a");
    static final MusicState B = MusicState.of("b");

    /** {@code bars} bars with a click of {@code level} on each bar's first frame. */
    static AudioClip clicks(int bars, float level) {
        float[] s = new float[bars * BAR];
        for (int b = 0; b < bars; b++) {
            s[b * BAR] = level;
        }
        return AudioClip.builder(RATE, 1).floats(s).build();
    }

    static AssetRef<AudioClip> ref(Supplier<AudioClip> make, String name) {
        return AssetRef.generated(AudioAssets.CLIP, (n, seed) -> make.get(), name, 0);
    }

    private TaskScheduler tasks;
    private AssetCache cache;
    private AudioRenderer renderer;
    private AudioSync sync;
    private Music music;

    @BeforeEach
    void setUp() {
        tasks = new TaskScheduler();
        cache = new AssetCache(tasks);
        renderer = AudioRenderer.offline(AudioFormat.stereo(RATE));
        sync = new AudioSync(renderer, cache).add(new Sounds(new SceneGraph(new ChangeClock())));
        music = new Music(renderer.clock(), new CachedMusicClips(cache));
        sync.music(music);
    }

    @AfterEach
    void tearDown() {
        renderer.close();
        cache.close();
        tasks.close();
    }

    /** Renders {@code frames}, updating the music and the sync every 1,024 frames, into a new array (or none). */
    private float[] play(int frames, boolean keep) throws InterruptedException {
        float[] out = keep ? new float[2 * frames] : null;
        float[] scratch = new float[2 * 1_024];
        for (int done = 0; done < frames; done += 1_024) {
            tasks.drainCompleted();
            sync.events();
            music.update();
            sync.update();
            renderer.render(keep ? out : scratch, keep ? done : 0, Math.min(1_024, frames - done));
            if (cache.loadingCount() > 0) {
                Thread.sleep(1);
            }
        }
        return out;
    }

    private static int first(float[] out, int from, float value) {
        for (int f = from; f < out.length / 2; f++) {
            if (Math.abs(out[2 * f] - value) < 1e-5) {
                return f;
            }
        }
        return -1;
    }

    @Test
    void aTransitionLandsOnTheBarsSample() throws InterruptedException {
        Score score = Score.builder().tempo(Tempo.bpm(240))
            .segment("a", s -> s.clip(ref(() -> clicks(4, 1), "a")).exit(Bar.of(5)).loop())
            .segment("b", s -> s.clip(ref(() -> clicks(4, 0.5f), "b")).exit(Bar.of(5)).loop())
            .state(A, Playlist.of("a")).state(B, Playlist.of("b"))
            .transition(A, B, t -> t.at(Quantize.NEXT_BAR).fadeOut(0.001))
            .build();
        music.play(score, A);
        float[] before = play(2 * BAR + 10_000, true);
        int a = first(before, 0, CENTRE);
        assertThat(a).isPositive();
        music.state(B);
        float[] after = play(3 * BAR, true);
        int b = first(after, 0, 0.5f * CENTRE) + before.length / 2;
        assertThat(b).isGreaterThan(before.length / 2);
        assertThat((b - a) % BAR).as("on a bar line of a, to the sample").isZero();
        // b was primed when asked for: it starts on the next bar line after it was ready
        assertThat(b - a).isBetween(3 * BAR, 4 * BAR);
    }

    @Test
    void stemsStayAlignedForTenMinutes() throws InterruptedException {
        AudioClip tone = Clips.sine(RATE, 300, 0.4f, 4 * BAR);
        float[] negated = new float[4 * BAR];
        for (int i = 0; i < negated.length; i++) {
            negated[i] = -tone.sample(i, 0);
        }
        AssetRef<AudioClip> s1 = ref(() -> tone, "stem");
        AssetRef<AudioClip> s2 = ref(() -> AudioClip.builder(RATE, 1).floats(negated).build(), "anti-stem");
        Score score = Score.builder().tempo(Tempo.bpm(240))
            .segment("stems", s -> s.stems(s1, s2).exit(Bar.of(5)).loop()).state(A, Playlist.of("stems")).build();
        music.play(score, A);
        float[] scratch = new float[2 * 1_024];
        float worst = 0;
        int heard = 0;
        for (int done = 0; done < 10 * 60 * RATE; done += 1_024) {
            tasks.drainCompleted();
            sync.events();
            music.update();
            sync.update();
            renderer.render(scratch, 0, 1_024);
            for (float x : scratch) {
                worst = Math.max(worst, Math.abs(x));
            }
            heard = Math.max(heard, sync.voices());
            if (cache.loadingCount() > 0) {
                Thread.sleep(1);
            }
        }
        assertThat(heard).as("the stems played").isPositive();
        assertThat(worst).as("a stem and its negation, re-scheduled every 4 bars for 10 minutes").isLessThan(1e-6f);
    }

    @Test
    void aStingerPlaysOnTheBeatAndDucksTheMusic() throws InterruptedException {
        Score score = Score.builder().tempo(Tempo.bpm(240))
            .segment("bed", s -> s.clip(ref(() -> Clips.constant(RATE, 0.5f, 4 * BAR), "bed")).exit(Bar.of(5))
                .loop())
            .state(A, Playlist.of("bed"))
            .stinger(Stinger.of("hit"), ref(() -> Clips.constant(RATE, 0.1f, RATE / 2), "hit"), Quantize.NEXT_BEAT)
            .duck(-12).build();
        music.play(score, A);
        play(BAR, false);
        music.stinger(Stinger.of("hit"));
        float[] out = play(RATE, true);
        // ducked: the bed at -12 dB (0.125) plus the stinger's 0.1, centred
        assertThat((double) out[2 * (RATE / 4)]).isCloseTo((0.125 + 0.1) * CENTRE, within(0.01));
        float[] later = play(RATE, true);
        assertThat((double) later[2 * (RATE - 1_000)]).as("back after it").isCloseTo(0.5 * CENTRE, within(0.01));
        music.stop(Quantize.IMMEDIATE, 0.01);
        play(4_096, false);
        play(4_096, false);
        assertThat(sync.voices()).isZero();
    }
}
