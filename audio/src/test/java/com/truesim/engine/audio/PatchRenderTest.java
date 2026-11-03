package com.truesim.engine.audio;

import static com.truesim.testing.AllocationAssertions.assertAllocationFree;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.truesim.common.jobs.TaskScheduler;
import com.truesim.common.state.ChangeClock;
import com.truesim.engine.audio.dsp.Patch;
import com.truesim.engine.audio.dsp.PatchVoice;
import com.truesim.engine.audio.dsp.Vehicles;
import com.truesim.scene.assets.AssetCache;
import com.truesim.scene.audio.Bus;
import com.truesim.scene.audio.Sound;
import com.truesim.scene.audio.SoundInstance;
import com.truesim.scene.audio.SoundParameter;
import com.truesim.scene.audio.Sounds;
import com.truesim.scene.core.SceneGraph;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Patches played by the renderer and the sync (§6 step 12): inputs at their sample, triggers, pools, no garbage. */
class PatchRenderTest {

    static final int RATE = 48_000;
    static final float CENTRE = (float) Math.cos(Math.PI / 4);
    static final SoundParameter LEVEL = SoundParameter.of("level", 0, 0, 1);
    static final com.truesim.scene.audio.Patch V8 = com.truesim.scene.audio.Patch.named("v8", Vehicles.RPM,
        Vehicles.LOAD, Vehicles.SHIFT);

    private TaskScheduler tasks;
    private AssetCache cache;
    private AudioRenderer renderer;
    private AudioSync sync;
    private Sounds sounds;

    @BeforeEach
    void setUp() {
        tasks = new TaskScheduler();
        cache = new AssetCache(tasks);
        renderer = AudioRenderer.offline(AudioFormat.stereo(RATE));
        sounds = new Sounds(new SceneGraph(new ChangeClock()));
        sync = new AudioSync(renderer, cache).add(sounds);
    }

    @AfterEach
    void tearDown() {
        renderer.close();
        cache.close();
        tasks.close();
    }

    private float[] play(int frames) {
        float[] out = new float[2 * frames];
        for (int done = 0; done < frames; done += 256) {
            sync.events();
            sounds.advance(5_333L);
            sync.update();
            renderer.render(out, done, Math.min(256, frames - done));
        }
        return out;
    }

    @Test
    void anInputChangesAtItsSample() {
        Patch level = Patch.builder("level").input(LEVEL).graph(g -> g.output(g.param(LEVEL))).build();
        PatchVoice voice = level.voice(RATE, 256, sync.patches().nodes());
        var commands = renderer.commands();
        commands.patch(voice);
        commands.play(0, null, 1_000, 0, 1, 1, Bus.SFX, 0, 0, 7);
        commands.patchInput(0, 0, 0.5f, 0, false, 3_000);
        commands.patchInput(0, 0, 0.25f, 0.01, false, 5_000);
        float[] out = new float[2 * 8_192];
        renderer.render(out, 0, 8_192);
        assertThat(out[2 * 2_999]).isZero();
        assertThat(out[2 * 3_000]).isCloseTo(0.5f * CENTRE, within(1e-6f));
        assertThat(out[2 * 5_240]).as("ramped over 10 ms").isCloseTo(0.375f * CENTRE, within(1e-3f));
        assertThat(out[2 * 5_480]).isCloseTo(0.25f * CENTRE, within(1e-6f));
        commands.patchInput(1, 0, 1, 0, false, 9_000); // no patch in that slot: ignored
        renderer.render(out, 0, 1_024);
    }

    @Test
    void aSoundPlaysItsPatchFollowingItsParameters() {
        sync.patches().register(Vehicles.v8());
        SoundInstance engine = sounds.play(Sound.builder().root(V8).loop().build());
        engine.set(Vehicles.RPM, 3_000, 0).set(Vehicles.LOAD, 0.5f, 0);
        float[] idle = play(RATE);
        assertThat(sync.voices()).isEqualTo(1);
        double low = rms(idle, RATE / 2);
        assertThat(low).isPositive();
        engine.set(Vehicles.RPM, 6_000).set(Vehicles.LOAD, 1);
        engine.trigger(Vehicles.SHIFT);
        float[] revving = play(RATE);
        assertThat(rms(revving, RATE / 2)).isNotEqualTo(low);
        engine.stop();
        play(RATE / 4);
        assertThat(sync.voices()).isZero();
        assertThat(engine.alive()).isFalse();
        // its voice went back to the pool: the next takes it without making one
        SoundInstance again = sounds.play(Sound.builder().root(V8).loop().build());
        play(1_024);
        assertThat(sync.voices()).isEqualTo(1);
        again.kill();
        play(2_048);
    }

    @Test
    void anUnregisteredPatchIsSilentAndEnds() {
        SoundInstance nothing = sounds.play(Sound.builder().root(com.truesim.scene.audio.Patch.named("none"))
            .build());
        play(1_024);
        assertThat(nothing.alive()).isFalse();
        sync.patches(); // made: still nothing called "none"
        SoundInstance still = sounds.play(Sound.builder().root(com.truesim.scene.audio.Patch.named("none"))
            .build());
        play(1_024);
        assertThat(still.alive()).isFalse();
    }

    @Test
    void playingAPatchIsAllocationFree() {
        sync.patches().register(Vehicles.v8());
        SoundInstance engine = sounds.play(Sound.builder().root(V8).loop().build());
        play(2_048);
        float[] out = new float[512];
        float[] rpm = {1_000};
        assertAllocationFree(() -> {
            rpm[0] = rpm[0] > 6_000 ? 1_000 : rpm[0] + 7;
            engine.set(Vehicles.RPM, rpm[0]);
            sync.events();
            sounds.advance(5_333L);
            sync.update();
            renderer.render(out, 0, 256);
        }, 20_000, 2_000, 10);
    }

    private static double rms(float[] x, int fromFrame) {
        double s = 0;
        for (int i = 2 * fromFrame; i < x.length; i++) {
            s += x[i] * x[i];
        }
        return Math.sqrt(s / (x.length - 2 * fromFrame));
    }
}
