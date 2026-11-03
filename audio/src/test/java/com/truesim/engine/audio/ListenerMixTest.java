package com.truesim.engine.audio;

import static com.truesim.testing.AllocationAssertions.assertAllocationFree;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.truesim.common.jobs.TaskScheduler;
import com.truesim.common.state.ChangeClock;
import com.truesim.scene.assets.AssetCache;
import com.truesim.scene.assets.AssetRef;
import com.truesim.scene.audio.Attenuation;
import com.truesim.scene.audio.AudioAssets;
import com.truesim.scene.audio.AudioClip;
import com.truesim.scene.audio.Bus;
import com.truesim.scene.audio.ListenerMix;
import com.truesim.scene.audio.Play;
import com.truesim.scene.audio.Sound;
import com.truesim.scene.audio.SoundInstance;
import com.truesim.scene.audio.Sounds;
import com.truesim.scene.core.SceneGraph;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Rendering listeners (§6 step 13): order and cycle delay, the self guard, feedback, laziness, switching, routes. */
class ListenerMixTest {

    static final int RATE = 48_000;
    static final int BLOCK = 256;

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

    static AudioClip click() {
        float[] s = new float[RATE / 10];
        s[0] = 0.5f;
        return AudioClip.builder(RATE, 1).floats(s).build();
    }

    /** Frames of {@code tap}, all it holds now. */
    static float[] drain(ListenerTap tap, int frames) {
        float[] out = new float[frames * tap.channels()];
        int n = tap.read(out);
        return java.util.Arrays.copyOf(out, n * tap.channels());
    }

    static int first(float[] x) {
        for (int i = 0; i < x.length; i++) {
            if (Math.abs(x[i]) > 1e-6f) {
                return i;
            }
        }
        return -1;
    }

    /** A click heard by A, re-emitted by a voice that B hears; B tapped. Returns the frame B hears it at. */
    private int clickThroughAToB(float route, int... order) {
        var c = renderer.commands();
        ListenerTap tap = new ListenerTap(1, 1 << 14);
        c.listener(0, true, 1, 1, null, null);
        c.listener(1, true, 1, 1, null, tap);
        c.order(order, order.length);
        c.play(0, click(), 1_000, 0, 1, 1, Bus.SFX, 0, 0, 1);
        c.listen(0, 0, 1, 1);
        c.inputNext(0);
        c.play(1, null, 0, 0, 1, 1, Bus.SFX, 0, 0, 2);
        c.listen(1, 1, 1, 1);
        renderer.render(4 * 1_024);
        float[] heard = drain(tap, 1 << 14);
        int at = first(heard);
        float once = 0.5f * (27 + 0.25f) / (27 + 9 * 0.25f);
        float twice = once * route;
        assertThat(heard[at]).as("clipped softly by each mix, not lost").isCloseTo(
            twice * (27 + twice * twice) / (27 + 9 * twice * twice), within(1e-4f));
        return at + renderer.latency(); // mixes are not delayed by the device's limiter: its look-ahead early
    }

    @Test
    void aChainAddsNoDelayAndACycleEdgeOneBlock() {
        assertThat(clickThroughAToB(1, 0, 1)).as("A before B: the same block").isEqualTo(1_000);
        renderer.close();
        renderer = AudioRenderer.offline(AudioFormat.stereo(RATE));
        assertThat(clickThroughAToB(0.5f, 1, 0)).as("B first: A's previous block, at -6 dB").isEqualTo(1_000 + BLOCK);
    }

    @Test
    void aListenerNeverHearsItsOwnReemission() {
        var c = renderer.commands();
        ListenerTap tap = new ListenerTap(1, 1 << 14);
        c.listener(0, true, 1, 1, null, tap);
        c.play(0, click(), 1_000, 0, 1, 1, Bus.SFX, 0, 0, 1);
        c.listen(0, 0, 1, 1);
        c.inputNext(0);
        c.play(1, null, 0, 0, 1, 1, Bus.SFX, 0, 0, 2);
        c.listen(1, 0, 1, 1); // the listener stands at its own television
        renderer.render(8_192);
        float[] heard = drain(tap, 1 << 14);
        int at = first(heard);
        assertThat(at + renderer.latency()).isEqualTo(1_000);
        for (int i = at + 1; i < heard.length; i++) {
            assertThat(heard[i]).as("no echo of itself at %d", i).isZero();
        }
    }

    @Test
    void twoCamerasFacingEachOthersTelevisionsDecay() {
        var c = renderer.commands();
        ListenerTap tap = new ListenerTap(1, 1 << 17);
        c.listener(0, true, 1, 2, null, tap); // loud mixes: without the guard the loop gains 2 per round trip
        c.listener(1, true, 1, 2, null, null);
        c.order(new int[] {0, 1}, 2);
        c.play(0, click(), 1_000, 0, 1, 1, Bus.SFX, 0, 0, 1);
        c.listen(0, 0, 1, 1);
        c.inputNext(1); // TV B, which camera A films
        c.play(1, null, 0, 0, 1, 1, Bus.SFX, 0, 0, 2);
        c.listen(1, 0, 1, 1);
        c.inputNext(0); // TV A, which camera B films
        c.play(2, null, 0, 0, 1, 1, Bus.SFX, 0, 0, 3);
        c.listen(2, 1, 1, 1);
        renderer.render(2 * RATE);
        float[] heard = drain(tap, 1 << 17);
        double late = 0;
        for (int i = RATE; i < 2 * RATE; i++) {
            late = Math.max(late, Math.abs(heard[i]));
        }
        assertThat(late).as("the loop decayed instead of howling").isLessThan(1e-3);
        double early = 0;
        for (int i = 1_000; i < 1_000 + 4 * BLOCK; i++) {
            early = Math.max(early, Math.abs(heard[i]));
        }
        assertThat(early).isGreaterThan(0.5);
    }

    private static AssetRef<AudioClip> level(float level) {
        return AssetRef.generated(AudioAssets.CLIP, (l, seed) -> Clips.constant(RATE, l, RATE), level, 0);
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

    private long node(double x, double y, double z) {
        long n = scene.create();
        scene.setTranslation(n, x, y, z);
        return n;
    }

    @Test
    void aTelevisionPlaysACamerasMixAndOffStopsItsRendering() throws InterruptedException {
        long head = node(0, 0, 0);
        sounds.listeners().device(head);
        var camera = sounds.listeners().render(node(100, 0, 0), ListenerMix.mono().buses(Bus.SFX));
        Sound hum = Sound.builder().root(Play.clip(level(0.4f))).attenuation(Attenuation.near(1, 50)).loop().build();
        sounds.play(hum, node(100, 0, -1)); // a metre from the camera, 100 m from the head: not heard directly
        Sound speaker = Sound.builder().root(Play.input()).attenuation(Attenuation.near(1, 50)).build();
        SoundInstance tv = sounds.play(speaker, node(0, 0, -1)).input(camera);
        frames(BLOCK);
        settle();
        float[] on = frames(RATE / 2);
        assertThat(Math.abs(on[2 * (RATE / 2 - 1)])).as("the hum, through the camera and the television")
            .isGreaterThan(0.05f);
        long rendered = renderer.listenerBlocks(0);
        assertThat(rendered).isPositive();
        tv.stop();
        frames(RATE / 4);
        long after = renderer.listenerBlocks(0);
        frames(RATE / 4);
        assertThat(renderer.listenerBlocks(0)).as("nothing consumes it: not rendered").isEqualTo(after);
    }

    @Test
    void aChannelSwitchCrossFades() throws InterruptedException {
        sounds.listeners().device(node(0, 0, 0));
        var one = sounds.listeners().render(node(-200, 0, 0), ListenerMix.mono());
        var two = sounds.listeners().render(node(200, 0, 0), ListenerMix.mono());
        Sound bed = Sound.builder().root(Play.clip(level(0.5f))).attenuation(Attenuation.near(1, 50)).loop().build();
        Sound other = Sound.builder().root(Play.clip(level(0.1f))).attenuation(Attenuation.near(1, 50)).loop()
            .build();
        sounds.play(bed, node(-200, 0, 0));
        sounds.play(other, node(200, 0, 0));
        Sound speaker = Sound.builder().root(Play.input()).attenuation(Attenuation.near(1, 50)).build();
        SoundInstance tv = sounds.play(speaker, node(0, 0, -1)).input(one);
        frames(BLOCK);
        settle();
        float[] before = frames(RATE / 4);
        float a = before[before.length - 2];
        tv.input(two);
        float[] after = frames(RATE / 4);
        float b = after[after.length - 2];
        assertThat(b).isLessThan(a * 0.5f);
        float step = 0;
        for (int f = 1; f < RATE / 4; f++) {
            step = Math.max(step, Math.abs(after[2 * f] - after[2 * f - 2]));
        }
        assertThat(step).as("a 30 ms cross-fade, no step").isLessThan(2 * (a - b) / (0.03f * RATE));
    }

    @Test
    void aListenerRoutedToADeviceBusAndATap() throws InterruptedException {
        sounds.listeners().device(node(0, 0, 0));
        long drone = sounds.listeners().render(node(500, 0, 0), ListenerMix.stereo().toBus(Bus.SFX).tap()).id();
        Sound bed = Sound.builder().root(Play.clip(level(0.5f))).attenuation(Attenuation.near(1, 50)).loop().build();
        sounds.play(bed, node(501, 0, 0)); // to the drone's right
        frames(BLOCK);
        settle();
        float[] out = frames(RATE / 4);
        int f = RATE / 4 - 1;
        assertThat(out[2 * f + 1]).as("right, in the device mix").isGreaterThan(0.2f);
        assertThat(Math.abs(out[2 * f])).as("left").isLessThan(0.01f);
        ListenerTap tap = sync.tap(drone);
        float[] heard = drain(tap, 1 << 16);
        assertThat(heard[heard.length - 1]).isGreaterThan(0.2f);
        assertThat(sync.tap(12345)).isNull();
        sounds.listeners().remove(drone);
        frames(BLOCK * 2);
        assertThat(sync.tap(drone)).isNull();
        assertThat(sync.listenersDropped()).isZero();
    }

    @Test
    void moreThanFourRenderingListenersAreDroppedAndCounted() {
        for (int i = 0; i < 6; i++) {
            sounds.listeners().render(node(i, 0, 0), ListenerMix.mono());
        }
        frames(BLOCK);
        assertThat(sync.listenersDropped()).isPositive();
    }

    @Test
    void renderingListenersAreAllocationFree() throws InterruptedException {
        sounds.listeners().device(node(0, 0, 0));
        var camera = sounds.listeners().render(node(100, 0, 0), ListenerMix.stereo());
        Sound bed = Sound.builder().root(Play.clip(level(0.3f))).attenuation(Attenuation.near(1, 50)).loop().build();
        sounds.play(bed, node(100, 0, -2));
        Sound speaker = Sound.builder().root(Play.input()).attenuation(Attenuation.near(1, 50)).build();
        sounds.play(speaker, node(0, 0, -1)).input(camera);
        frames(BLOCK);
        settle();
        frames(4 * BLOCK);
        float[] out = new float[2 * BLOCK];
        assertAllocationFree(() -> {
            scene.update();
            sync.events();
            sounds.advance(5_333L);
            sync.update();
            renderer.render(out, 0, BLOCK);
        }, 20_000, 2_000, 10);
    }
}
