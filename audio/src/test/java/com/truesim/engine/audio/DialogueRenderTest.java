package com.truesim.engine.audio;

import static com.truesim.testing.AllocationAssertions.assertAllocationFree;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.truesim.common.jobs.TaskScheduler;
import com.truesim.common.state.ChangeClock;
import com.truesim.scene.assets.AssetCache;
import com.truesim.scene.assets.AssetRef;
import com.truesim.scene.audio.Attenuation;
import com.truesim.scene.audio.AudioAssets;
import com.truesim.scene.audio.AudioClip;
import com.truesim.scene.audio.Caption;
import com.truesim.scene.audio.Cue;
import com.truesim.scene.audio.DialogueEvent;
import com.truesim.scene.audio.Line;
import com.truesim.scene.audio.LineInstance;
import com.truesim.scene.audio.Play;
import com.truesim.scene.audio.Priority;
import com.truesim.scene.audio.Sound;
import com.truesim.scene.audio.Sounds;
import com.truesim.scene.audio.Visemes;
import com.truesim.scene.core.SceneGraph;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Dialogue rendered (§6 step 15): cues at the heard time, interruptions fading, visemes, envelopes, indicators. */
class DialogueRenderTest {

    static final int RATE = 48_000;
    static final int BLOCK = 256;

    private TaskScheduler tasks;
    private AssetCache cache;
    private AudioRenderer renderer;
    private AudioSync sync;
    private SceneGraph scene;
    private Sounds sounds;
    private final List<long[]> events = new ArrayList<>();
    private final List<DialogueEvent> kinds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        tasks = new TaskScheduler();
        cache = new AssetCache(tasks);
        renderer = AudioRenderer.offline(AudioFormat.stereo(RATE));
        scene = new SceneGraph(new ChangeClock());
        sounds = new Sounds(scene);
        sync = new AudioSync(renderer, cache).add(sounds);
        sounds.dialogue().listen((event, line, speaker, text, start, end) -> {
            kinds.add(event);
            events.add(new long[] {event.ordinal(), renderer.clock().heardFrame()});
        });
    }

    @AfterEach
    void tearDown() {
        renderer.close();
        cache.close();
        tasks.close();
    }

    static AssetRef<AudioClip> level(float level, int frames) {
        return AssetRef.generated(AudioAssets.CLIP, (l, seed) -> Clips.constant(RATE, l, frames), level, frames);
    }

    private void settle() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (cache.loadingCount() > 0 && System.nanoTime() < deadline) {
            tasks.drainCompleted();
            Thread.sleep(1);
        }
        tasks.drainCompleted();
    }

    private float[] frames(int frames) {
        float[] out = new float[2 * frames];
        for (int done = 0; done < frames; done += BLOCK) {
            tasks.drainCompleted();
            scene.update();
            sync.events();
            sounds.advance(5_333L);
            sync.update();
            renderer.render(out, done, Math.min(BLOCK, frames - done));
        }
        return out;
    }

    private long heardAt(DialogueEvent kind) {
        for (long[] e : events) {
            if (e[0] == kind.ordinal()) {
                return e[1];
            }
        }
        return -1;
    }

    @Test
    void cuesAreReportedAtTheHeardTime() throws InterruptedException {
        sounds.dialogue().say(Line.of(level(0.25f, RATE)).speaker("narrator")
            .cues(List.of(new Cue(0.5, 0.75, "one"))), Priority.NORMAL);
        frames(BLOCK); // loading
        settle();
        float[] out = frames(2 * RATE);
        int first = 0;
        while (out[2 * first] == 0) {
            first++;
        }
        // the device heard frame of the clip's start: this output's frame (renders start at 0) plus the first block
        long start = BLOCK + first;
        long cue = heardAt(DialogueEvent.CUE_STARTED);
        assertThat(cue - (start + RATE / 2)).as("reported within a frame's update of when it is heard")
            .isBetween(0L, (long) 2 * BLOCK);
        assertThat(heardAt(DialogueEvent.CUE_ENDED) - (start + 3 * RATE / 4)).isBetween(0L, (long) 2 * BLOCK);
        assertThat(heardAt(DialogueEvent.LINE_STARTED) - start).isBetween(0L, (long) 2 * BLOCK);
        assertThat(kinds).contains(DialogueEvent.LINE_ENDED);
        assertThat(heardAt(DialogueEvent.LINE_ENDED) - (start + RATE)).isBetween(-renderer.latency() - 1L,
            (long) 2 * BLOCK);
    }

    @Test
    void anInterruptionFadesAndIsReported() throws InterruptedException {
        long head = scene.create();
        sounds.listeners().device(scene.create());
        LineInstance first = sounds.dialogue().say(Line.of(level(0.5f, 4 * RATE)).speaker("guard"), head,
            Priority.NORMAL);
        frames(BLOCK);
        settle();
        frames(RATE / 2);
        assertThat(first.envelope()).as("its level, for a jaw").isGreaterThan(0.1f);
        sounds.dialogue().say(Line.of(level(0.1f, 4 * RATE)).speaker("guard"), head, Priority.HIGH);
        frames(BLOCK);
        settle();
        float[] out = frames(RATE / 2);
        assertThat(kinds).contains(DialogueEvent.INTERRUPTED);
        assertThat(first.alive()).isFalse();
        // fading over 100 ms: from both lines (0.6 centred) down to the new one's level
        int from = 0;
        while (out[2 * from] > 0.6f * 0.7071f - 0.01f) {
            from++;
        }
        int to = from;
        while (out[2 * to] > 0.1f * 0.7071f + 0.005f) {
            to++;
        }
        assertThat((to - from) / (double) RATE).as("the fade, seconds").isBetween(0.05, 0.15);
        float end = out[2 * (RATE / 2 - 1)];
        assertThat(end).as("only the new line").isCloseTo(0.1f * 0.7071f, within(0.02f));
    }

    @Test
    void visemesAndTheHeardTimeFollowTheDevice() throws InterruptedException {
        Visemes track = Visemes.rhubarb("{\"mouthCues\": [{\"start\": 0.0, \"end\": 0.3, \"value\": \"A\"},"
            + "{\"start\": 0.3, \"end\": 2.0, \"value\": \"D\"}]}");
        LineInstance line = sounds.dialogue().say(Line.of(level(0.25f, 2 * RATE)).speaker("x").visemes(track),
            Priority.NORMAL);
        frames(BLOCK);
        settle();
        frames(RATE / 2);
        double heard = line.heard();
        assertThat(heard).isBetween(0.4, 0.55);
        float[] w = new float[9];
        line.visemes(w);
        assertThat(w[3]).as("D at the heard time").isEqualTo(1);
        float[] weights = new float[9];
        assertAllocationFree(() -> {
            line.visemes(weights);
            line.envelope();
        }, 2_000, 200, 10);
    }

    @Test
    void indicatorsPointAtCaptionedSoundsAroundTheListener() throws InterruptedException {
        long head = scene.create();
        long listener = sounds.listeners().device(head).id();
        Sound step = Sound.builder().root(Play.clip(level(0.2f, 4 * RATE))).attenuation(Attenuation.near(1, 50))
            .caption(Caption.of("footsteps")).build();
        Sound shot = Sound.builder().root(Play.clip(level(0.2f, 4 * RATE))).attenuation(Attenuation.near(1, 50))
            .caption(Caption.of("gunfire")).build();
        Sound quiet = Sound.builder().root(Play.clip(level(0.2f, 4 * RATE))).attenuation(Attenuation.near(1, 50))
            .build();
        sounds.fire(step, 3, 0, 0); // right
        sounds.fire(shot, 0, 0, 2); // behind, nearer: louder
        sounds.fire(step, -10, 0, 0); // left, far
        sounds.fire(quiet, 0, 0, -1); // no caption
        frames(BLOCK);
        settle();
        frames(4 * BLOCK);
        Indicators out = new Indicators(2);
        int n = sync.indicators(listener, out);
        assertThat(n).as("the two loudest").isEqualTo(2);
        assertThat(out.caption(0)).isEqualTo(Caption.of("gunfire"));
        assertThat(out.azimuth(0)).as("behind").isCloseTo(Math.PI, within(1e-6));
        assertThat(out.caption(1)).isEqualTo(Caption.of("footsteps"));
        assertThat(out.azimuth(1)).as("right").isCloseTo(Math.PI / 2, within(1e-6));
        assertThat(out.direction(1, 0)).isCloseTo(1, within(1e-6f));
        assertThat(out.loudness(0)).isGreaterThan(out.loudness(1));
        assertThat(out.instance(0)).isNotZero();
        Indicators all = new Indicators(8);
        assertThat(sync.indicators(listener, all)).isEqualTo(3);
        assertThat(all.azimuth(2)).as("left").isCloseTo(-Math.PI / 2, within(1e-6));
        assertThat(sync.indicators(4242, all)).as("not a listener").isZero();
        assertThatThrownBy(() -> all.caption(0)).isInstanceOf(IndexOutOfBoundsException.class);
        assertAllocationFree(() -> sync.indicators(listener, out), 2_000, 200, 10);
    }
}
