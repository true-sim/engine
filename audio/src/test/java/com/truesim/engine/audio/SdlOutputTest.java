package com.truesim.engine.audio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.lwjgl.sdl.SDLHints.SDL_HINT_AUDIO_DISK_OUTPUT_FILE;
import static org.lwjgl.sdl.SDLHints.SDL_SetHint;
import static org.lwjgl.sdl.SDLInit.SDL_INIT_AUDIO;
import static org.lwjgl.sdl.SDLInit.SDL_QuitSubSystem;

import com.truesim.scene.audio.AudioClip;
import com.truesim.scene.audio.Bus;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The SDL output on SDL's headless drivers (§2.24): the dummy driver, which CI uses, and the disk driver, which writes
 * what SDL plays to a file, for an end-to-end check that what the audio thread pushes is what comes out.
 */
class SdlOutputTest {

    @AfterEach
    void quitAudio() {
        SDL_QuitSubSystem(SDL_INIT_AUDIO);
    }

    @Test
    void theDummyDriverPlaysBlocksWithoutUnderruns() throws InterruptedException {
        SdlOutput.startSdlAudio("dummy");
        SdlOutput.startSdlAudio("dummy"); // started already
        try (SdlOutput out = SdlOutput.open(null, 256, 2); AudioRenderer r = new AudioRenderer(out.format(), 64)) {
            assertThat(out.chosen()).isFalse();
            assertThat(out.deviceName()).isEqualTo("default");
            assertThat(out.lowMark()).isEqualTo(2);
            assertThat(out.lowFrames()).isEqualTo(Math.max(512, out.deviceFrames() + 256));
            assertThat(out.latencyFrames()).isEqualTo(out.lowFrames() + 256 + out.deviceFrames());
            assertThatThrownBy(() -> out.start(new AudioRenderer(AudioFormat.of(out.format().rate() == 48_000
                ? 44_100 : 48_000, 2), 4))).hasMessageContaining("is not the device's");
            out.start(r);
            assertThatThrownBy(() -> out.start(r)).hasMessageContaining("runs already");
            r.commands().play(0, Clips.sine(48_000, 440, 0.3f, 48_000), r.clock().schedulePoint(), 0, 1, 1, Bus.SFX,
                Voices.LOOP, 0, 1);
            Thread.sleep(600);
            assertThat(out.blocks()).isGreaterThan(50);
            assertThat(r.clock().renderedFrame()).isGreaterThan(200L * 256);
            assertThat(r.clock().schedulePoint() - r.clock().renderedFrame()).isEqualTo(r.clock().latency());
            assertThat(r.clock().queued()).isLessThanOrEqualTo(out.lowFrames() + 256);
            out.stop();
            out.stop();
            long blocks = out.blocks();
            Thread.sleep(50);
            assertThat(out.blocks()).isEqualTo(blocks);
            assertThat(out.oversleep999()).isGreaterThanOrEqualTo(0);
            assertThat(out.precise()).isEqualTo(System.getProperty("os.name").startsWith("Windows"));
            assertThat(out.underruns()).isZero();
            assertThat(out.lowMark()).as("no guard raised it").isEqualTo(2);
        }
    }

    @Test
    void aDeviceThatIsNotThereLeavesTheDefault() {
        SdlOutput.startSdlAudio("dummy");
        try (SdlOutput out = SdlOutput.open("no such device", 256, 2)) {
            assertThat(out.chosen()).isFalse();
        }
        try (SdlOutput again = SdlOutput.reopen(AudioFormat.stereo(44_100), 3)) {
            assertThat(again.format()).isEqualTo(AudioFormat.stereo(44_100));
            assertThat(again.lowMark()).isEqualTo(3);
        }
    }

    @Test
    void openingNeedsSdlAudio() {
        assertThatThrownBy(() -> SdlOutput.open(null, 256, 2)).hasMessageContaining("not started");
    }

    @Test
    void theDiskDriverWritesWhatTheThreadPushed(@TempDir Path dir) throws InterruptedException, IOException {
        Path file = dir.resolve("out.raw");
        SDL_SetHint(SDL_HINT_AUDIO_DISK_OUTPUT_FILE, file.toString());
        SdlOutput.startSdlAudio("disk");
        AudioFormat format;
        long at;
        int deviceFrames;
        try (SdlOutput out = SdlOutput.open(null, 256, 2)) {
            format = out.format();
            deviceFrames = out.deviceFrames();
            try (AudioRenderer r = new AudioRenderer(format, 16)) {
                out.start(r);
                Thread.sleep(300);
                at = r.clock().schedulePoint() + 1_000;
                AudioClip clip = Clips.mono(format.rate(), 0.5f, -0.25f, 0.125f);
                r.commands().play(0, clip, at, 0, 1, 1, Bus.SFX, 0, 0, 1);
                Thread.sleep(400);
                out.stop();
            }
        }
        ByteBuffer b = ByteBuffer.wrap(Files.readAllBytes(file)).order(ByteOrder.LITTLE_ENDIAN);
        int channels = format.channels();
        int frames = b.remaining() / (4 * channels);
        int first = -1;
        for (int f = 0; f < frames && first < 0; f++) {
            if (b.getFloat(4 * channels * f) != 0) {
                first = f;
            }
        }
        assertThat(first).as("the clip was played").isPositive();
        float centre = channels == 1 ? 1 : (float) Math.cos(Math.PI / 4);
        assertThat(b.getFloat(4 * channels * first)).isEqualTo(0.5f * centre);
        assertThat(b.getFloat(4 * channels * (first + 1))).isEqualTo(-0.25f * centre);
        assertThat(b.getFloat(4 * channels * (first + 2))).isEqualTo(0.125f * centre);
        assertThat(b.getFloat(4 * channels * (first + 3))).isZero();
        // the device started after the warm-up's 200 blocks: the clip is at its frame in the stream, after the one
        // device buffer of silence the disk driver writes before its first pull (seen every run)
        assertThat((long) first).isEqualTo(at - 200L * 256 + deviceFrames);
    }
}
