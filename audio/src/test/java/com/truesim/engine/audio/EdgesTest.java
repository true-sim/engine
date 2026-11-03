package com.truesim.engine.audio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.truesim.scene.audio.AudioClip;
import com.truesim.scene.audio.Bus;
import org.junit.jupiter.api.Test;

/** The renderer's edges: a full command ring, speaker rings wrapping, spatial clips of several channels, the timer. */
class EdgesTest {

    static final int RATE = 48_000;

    @Test
    void aFullRingRefusesCommandsAndCountsThem() {
        try (AudioRenderer r = AudioRenderer.offline(AudioFormat.stereo(RATE))) {
            AudioClip clip = Clips.constant(RATE, 0.1f, 64);
            int sent = 0;
            while (r.commands().pitch(0, 1)) {
                sent++;
            }
            assertThat(sent).isEqualTo(1 << 15);
            assertThat(r.commands().play(0, clip, 0, 0, 1, 1, Bus.SFX, 0, 0, 1)).isFalse();
            assertThat(r.commands().stop(0, 0, 0)).isFalse();
            assertThat(r.commands().layout(com.truesim.scene.audio.BusLayout.DEFAULT)).isFalse();
            assertThat(r.commands().overflows()).isEqualTo(4);
            r.render(256); // drains them
            assertThat(r.commands().play(0, clip, 300, 0, 1, 1, Bus.SFX, 0, 0, 1)).isTrue();
        }
    }

    @Test
    void speakerRingsWrapAroundTheBack() {
        Panner surround = new Panner(6);
        float[] g = new float[6];
        surround.direction(Math.toRadians(180), 0, g, 0); // between the two surrounds, through the back
        assertThat(g[4]).isCloseTo(g[5], within(1e-6f));
        assertThat((double) (g[4] * g[4] + g[5] * g[5])).isCloseTo(1, within(1e-5));
        surround.direction(Math.toRadians(-170), 0, g, 0);
        assertThat(g[4]).isGreaterThan(g[5]);
        surround.direction(Math.toRadians(-20), 0, g, 0);
        assertThat(g[0]).isGreaterThan(g[2]);
    }

    @Test
    void spatialClipsAreMixedToMonoAndQuadPans() {
        try (AudioRenderer r = new AudioRenderer(AudioFormat.of(RATE, 4), 4)) {
            AudioClip stereo = AudioClip.builder(RATE, 2).floats(new float[] {0.2f, 0.6f, 0.2f, 0.6f}).build();
            r.commands().play(0, stereo, 100, 0, 1, 1, Bus.SFX, Voices.SPATIAL | Voices.LOOP, 0, 1);
            float[] out = r.render(1_024);
            // mono 0.4, panned ahead on a quad ring: half power to each front speaker
            assertThat((double) out[4 * 500]).isCloseTo(0.4 * Math.sqrt(0.5), within(1e-5));
            assertThat((double) out[4 * 500 + 1]).isCloseTo(0.4 * Math.sqrt(0.5), within(1e-5));
            assertThat(out[4 * 500 + 2]).isZero();
        }
        try (AudioRenderer mono = new AudioRenderer(new AudioFormat(RATE, 1, 256), 4)) {
            mono.commands().play(0, Clips.constant(RATE, 0.5f, 1_000), 100, 0, 1, 1, Bus.SFX, 0, 0.7f, 1);
            assertThat(mono.render(512)[300]).isEqualTo(0.5f);
        }
    }

    @Test
    void theWaiterSleepsAboutAsLongAsAsked() {
        try (Waiter waiter = new Waiter()) {
            waiter.sleep(0);
            long before = System.nanoTime();
            waiter.sleep(2_000_000);
            long slept = System.nanoTime() - before;
            assertThat(slept).isGreaterThanOrEqualTo(1_500_000);
            assertThat(Waiter.proAudio()).isEqualTo(System.getProperty("os.name").startsWith("Windows"));
        }
    }
}
