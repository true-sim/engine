package com.truesim.engine.audio;

import static com.truesim.testing.AllocationAssertions.assertAllocationFree;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.truesim.common.jobs.JobSystem;
import com.truesim.common.jobs.TaskScheduler;
import com.truesim.common.state.ChangeClock;
import com.truesim.scene.assets.AssetCache;
import com.truesim.scene.assets.AssetRef;
import com.truesim.scene.audio.Attenuation;
import com.truesim.scene.audio.AudioAssets;
import com.truesim.scene.audio.AudioClip;
import com.truesim.scene.audio.AudioWorld;
import com.truesim.scene.audio.Occlusion;
import com.truesim.scene.audio.Play;
import com.truesim.scene.audio.Sound;
import com.truesim.scene.audio.Sounds;
import com.truesim.scene.core.SceneGraph;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Occlusion (§6 step 16): a wall's level and cutoff, the ray budget, results on any number of threads. */
class OcclusionRenderTest {

    static final int RATE = 48_000;
    static final int BLOCK = 256;
    /** A wall across x = 5: half the lows, a quarter of the mids, a twentieth of the highs. */
    static final AudioWorld WALL = (x0, y0, z0, x1, y1, z1, out) -> {
        boolean crosses = (x0 - 5) * (x1 - 5) < 0;
        if (crosses) {
            out.cross(0.5f, 0.25f, 0.05f);
        }
        return crosses;
    };

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
        sounds.listeners().device(scene.create());
    }

    @AfterEach
    void tearDown() {
        renderer.close();
        cache.close();
        tasks.close();
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

    static AssetRef<AudioClip> sine(double hz) {
        return AssetRef.generated(AudioAssets.CLIP, (f, seed) -> Clips.sine(RATE, f.floatValue(), 0.5f, RATE), hz, 0);
    }

    static AssetRef<AudioClip> dc() {
        return AssetRef.generated(AudioAssets.CLIP, (f, seed) -> Clips.constant(RATE, f, RATE), 0.5f, 0);
    }

    static Sound sound(AssetRef<AudioClip> clip, Occlusion occlusion, float radius) {
        return Sound.builder().root(Play.clip(clip)).occlusion(occlusion).loop()
            .attenuation(Attenuation.builder().distance(1, 100).spread(radius, 0).build()).build();
    }

    static double rms(float[] out, int from) {
        double s = 0;
        for (int i = 2 * from; i < out.length; i++) {
            s += out[i] * out[i];
        }
        return Math.sqrt(s / (out.length - 2 * from));
    }

    /** RMS of {@code clip} at x = 10 behind the wall (or not occluded), after it settles. */
    private double level(AssetRef<AudioClip> clip, Occlusion occlusion) throws InterruptedException {
        long id = sounds.fire(sound(clip, occlusion, 0), 10, 0, 0);
        frames(BLOCK);
        settle();
        double level = rms(frames(RATE / 2), RATE / 4);
        sounds.kill(id);
        frames(2 * BLOCK);
        return level;
    }

    @Test
    void aWallLowersTheLevelAndTheCutoffAsConfigured() throws InterruptedException {
        sync.occlusion(WALL, null);
        double open = level(dc(), Occlusion.NONE);
        double walled = level(dc(), Occlusion.RAYS);
        double gain = Math.sqrt(0.5 * 0.25);
        assertThat(walled / open).as("between the lows' and the mids' transmission").isCloseTo(gain, within(0.01));
        float cutoff = Occluder.cutoff((float) gain, 0.05f);
        assertThat(cutoff).as("the highs' share squared of 20 kHz").isCloseTo(400, within(1f));
        double openHigh = level(sine(4_000), Occlusion.NONE);
        double walledHigh = level(sine(4_000), Occlusion.RAYS);
        double onePole = 1 / Math.sqrt(1 + Math.pow(4_000.0 / cutoff, 2));
        assertThat(walledHigh / openHigh).as("the gain, and the low-pass at 4 kHz")
            .isCloseTo(gain * onePole, within(gain * onePole * 0.15));
        assertThat(sync.occlusionRays()).isPositive();
    }

    @Test
    void theBudgetHoldsWith500Emitters() throws InterruptedException {
        AtomicInteger calls = new AtomicInteger();
        sync.occlusion((x0, y0, z0, x1, y1, z1, out) -> {
            calls.incrementAndGet();
            return WALL.trace(x0, y0, z0, x1, y1, z1, out);
        }, null);
        long[] ids = new long[500];
        for (int i = 0; i < ids.length; i++) {
            double a = 2 * Math.PI * i / ids.length;
            ids[i] = sounds.fire(sound(dc(), Occlusion.RAYS, 0), 20 * Math.cos(a), 0, 20 * Math.sin(a));
        }
        frames(BLOCK);
        settle();
        int most = 0;
        for (int f = 0; f < 10; f++) {
            calls.set(0);
            frames(BLOCK);
            most = Math.max(most, calls.get());
        }
        assertThat(most).as("rays a frame").isLessThanOrEqualTo(64);
        int occluded = 0;
        for (long id : ids) {
            float o = sync.occlusion(id);
            occluded += o < 0.5f ? 1 : 0;
            assertThat(o == 1 || Math.abs(o - Math.sqrt(0.125)) < 1e-6).as("traced within 8 frames: open or walled")
                .isTrue();
        }
        assertThat(occluded).as("those past the wall").isBetween(100, 250);
        sync.occlusionBudget(16);
        calls.set(0);
        frames(BLOCK);
        assertThat(calls.get()).isLessThanOrEqualTo(16);
        float[] out = new float[2 * BLOCK];
        assertAllocationFree(() -> {
            scene.update();
            sync.events();
            sounds.advance(5_333L);
            sync.update();
            renderer.render(out, 0, BLOCK);
        }, 2_000, 200, 10);
    }

    /** The occlusion of 200 sources moving through a field of soft walls, after 20 frames, with {@code threads}. */
    private float[] run(int threads) throws InterruptedException {
        try (JobSystem jobs = new JobSystem(threads)) {
            setUp();
            sync.occlusion((x0, y0, z0, x1, y1, z1, out) -> {
                for (int w = 1; w < 8; w++) {
                    if ((x0 - 3 * w) * (x1 - 3 * w) < 0) {
                        out.cross(0.9f, 0.7f - 0.05f * w, 0.4f);
                    }
                }
                return true;
            }, jobs);
            long[] ids = new long[200];
            for (int i = 0; i < ids.length; i++) {
                ids[i] = sounds.fire(sound(dc(), Occlusion.RAYS, i % 3 == 0 ? 1.5f : 0), (i % 25) - 2, 0, i / 25.0);
            }
            frames(BLOCK);
            settle();
            frames(20 * BLOCK);
            float[] result = new float[ids.length];
            for (int i = 0; i < ids.length; i++) {
                result[i] = sync.occlusion(ids[i]);
            }
            tearDown();
            return result;
        }
    }

    @Test
    void resultsAreTheSameOnAnyNumberOfThreads() throws InterruptedException {
        tearDown();
        float[] one = run(1);
        float[] many = run(6);
        assertThat(many).isEqualTo(one);
        float lowest = 1;
        for (float o : one) {
            lowest = Math.min(lowest, o);
        }
        assertThat(lowest).isLessThan(0.3f);
        setUp();
    }

    @Test
    void aVolumetricSourceIsPartlyOccluded() throws InterruptedException {
        sync.occlusion((x0, y0, z0, x1, y1, z1, out) -> {
            boolean crosses = (x0 - 5) * (x1 - 5) < 0 && z1 > 0; // a wall covering only z > 0
            if (crosses) {
                out.cross(0, 0, 0);
            }
            return crosses;
        }, null);
        long id = sounds.fire(sound(dc(), Occlusion.RAYS, 2), 10, 0, 0);
        frames(BLOCK);
        settle();
        frames(4 * BLOCK);
        assertThat(sync.occlusion(id)).as("some of its 4 rays blocked").isStrictlyBetween(0f, 1f);
        sync.occlusion(null, null);
        frames(BLOCK);
        assertThat(sync.occlusionRays()).isZero();
        assertThat(sync.occlusion(12345)).isEqualTo(1);
    }

    /**
     * {@code world} in batches, as a GPU pass would trace them: each batch traced when submitted and given back on the
     * {@code delay + 1}th collect of it; every {@code refuse}th submit refused, and batch {@code lose} never given back.
     */
    static final class Batches implements BatchedAudioWorld {
        final AudioWorld world;
        final int capacity;
        final int delay;
        final int refuse;
        final long lose;
        final com.truesim.scene.audio.Transmission[] traced;
        final int[] counts = new int[8];
        final long[] tickets = new long[8];
        final int[] asked = new int[8];
        long issued;
        int submits;
        int largest;

        Batches(AudioWorld world, int capacity, int delay, int refuse, long lose) {
            this.world = world;
            this.capacity = capacity;
            this.delay = delay;
            this.refuse = refuse;
            this.lose = lose;
            traced = new com.truesim.scene.audio.Transmission[8 * capacity];
            for (int i = 0; i < traced.length; i++) {
                traced[i] = new com.truesim.scene.audio.Transmission();
            }
        }

        @Override
        public int capacity() {
            return capacity;
        }

        @Override
        public long submit(double[] rays, int count) {
            if (count < 1 || count > capacity) {
                throw new AssertionError("a batch of " + count);
            }
            if (refuse > 0 && ++submits % refuse == 0) {
                return 0;
            }
            largest = Math.max(largest, count);
            long ticket = ++issued;
            int slot = (int) (ticket % 8);
            for (int i = 0; i < count; i++) {
                int o = 6 * i;
                world.trace(rays[o], rays[o + 1], rays[o + 2], rays[o + 3], rays[o + 4], rays[o + 5],
                    traced[slot * capacity + i].open());
            }
            tickets[slot] = ticket;
            counts[slot] = count;
            asked[slot] = 0;
            return ticket;
        }

        @Override
        public int collect(long ticket, com.truesim.scene.audio.Transmission[] out, int offset) {
            int slot = (int) (ticket % 8);
            if (tickets[slot] != ticket || ticket == lose || asked[slot]++ < delay) {
                return -1;
            }
            for (int i = 0; i < counts[slot]; i++) {
                com.truesim.scene.audio.Transmission t = traced[slot * capacity + i];
                out[offset + i].open().cross(t.low(), t.mid(), t.high());
            }
            return counts[slot];
        }
    }

    @Test
    void aBatchedWorldOccludesAsTheWorldDoesFramesLater() throws InterruptedException {
        sync.occlusion(WALL, null);
        double walled = level(dc(), Occlusion.RAYS);
        Batches batches = new Batches(WALL, 64, 2, 0, -1);
        sync.occlusion(batches);
        double batched = level(dc(), Occlusion.RAYS);
        assertThat(batched).as("the same wall, its results frames later").isCloseTo(walled, within(walled * 0.01));
        assertThat(sync.occlusionRays()).isPositive();
        assertThat(sync.occlusionBatchesGivenUp()).isZero();
    }

    @Test
    void batchesRefusedOrLostAreGivenUpAndTheBudgetIsTheWorldsCapacity() throws InterruptedException {
        Batches batches = new Batches(WALL, 16, 1, 3, 2); // every third refused; the second lost
        sync.occlusion(batches).occlusionBudget(100);
        long[] ids = new long[40];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = sounds.fire(sound(dc(), Occlusion.RAYS, 0), 10, 0, i - 20);
        }
        frames(BLOCK);
        settle();
        frames(60 * BLOCK);
        assertThat(batches.largest).as("batches as large as the world takes").isEqualTo(16);
        assertThat(sync.occlusionBatchesGivenUp()).as("the lost batch").isEqualTo(1);
        for (long id : ids) {
            assertThat(sync.occlusion(id)).as("every sound walled in the end").isCloseTo((float) Math.sqrt(0.125),
                within(1e-3f));
        }
        float[] out = new float[2 * BLOCK];
        assertAllocationFree(() -> {
            scene.update();
            sync.events();
            sounds.advance(5_333L);
            sync.update();
            renderer.render(out, 0, BLOCK);
        }, 6_000, 200, 10);
        sync.occlusion((BatchedAudioWorld) null);
        assertThat(sync.occlusionBatchesGivenUp()).isZero();
    }
}
