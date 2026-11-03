package com.truesim.engine.audio;

import static com.truesim.testing.AllocationAssertions.assertAllocationFree;
import static org.assertj.core.api.Assertions.assertThat;

import com.truesim.common.state.ChangeClock;
import com.truesim.scene.audio.Bus;
import com.truesim.scene.audio.ListenerMix;
import com.truesim.scene.audio.Sounds;
import com.truesim.scene.core.SceneGraph;
import org.junit.jupiter.api.Test;

/** The audio debug overlay (§6 step 20): voices, meters, listeners, written in place without garbage. */
class AudioOverlayTest {

    @Test
    void theOverlaySaysWhatPlaysAndHowLoud() {
        try (AudioRenderer r = AudioRenderer.offline(AudioFormat.stereo(48_000))) {
            r.commands().play(0, Clips.sine(48_000, 440, 0.5f, 48_000), 0, 0, 1, 1, Bus.SFX, Voices.LOOP, 0, 1);
            r.render(48_000 / 10);
            SceneGraph scene = new SceneGraph(new ChangeClock());
            Sounds sounds = new Sounds(scene);
            long node = scene.create();
            scene.update();
            sounds.listeners().render(node, ListenerMix.mono());
            sounds.advance(1_000);
            AudioOverlay overlay = new AudioOverlay(r, new NullOutput(true)).listeners(sounds);
            int lines = overlay.update();
            assertThat(lines).isEqualTo(overlay.lines());
            String text = overlay.text();
            assertThat(text).contains("audio: 1 real, 0 virtual, 0 binaural, 0 underruns, 0 starved")
                .contains("  sfx       [").contains("peak -9 dB").contains("ui").contains("-inf dB") // 0.5 panned
                .contains("rendering listeners: 0=0").contains("listener graph: order 0, 0 delayed, 0 cut");
            assertThat(overlay.line(0).toString()).startsWith("audio:");
            assertAllocationFree(overlay::update, 2_000, 200, 10);
            AudioOverlay plain = new AudioOverlay(r, null).listeners(Sounds.ui());
            plain.update();
            assertThat(plain.text()).doesNotContain("underruns").doesNotContain("listener graph");
            assertThat(new AudioOverlay(r, null).listeners(null).update()).isPositive();
        }
        assertThat(AudioOverlay.decibels(1)).isZero();
        assertThat(AudioOverlay.decibels(0)).isEqualTo(-120);
        assertThat(AudioOverlay.decibels(0.5f)).isEqualTo(-6);
    }
}
