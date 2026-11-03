package com.truesim.engine.audio;

import static com.truesim.testing.AllocationAssertions.assertAllocationFree;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.truesim.scene.audio.AudioClip;
import com.truesim.scene.audio.Bus;
import com.truesim.scene.audio.BusLayout;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The offline renderer: exact starts, resampling, ramps, buses, the limiter, events, goldens and allocation. */
class RendererTest {

    static final int RATE = 48_000;
    static final float CENTRE = (float) Math.cos(Math.PI / 4);

    private static AudioRenderer renderer() {
        return AudioRenderer.offline(AudioFormat.stereo(RATE));
    }

    @Test
    void aClickStampedAtFrameFIsAtSampleF() {
        try (AudioRenderer r = renderer()) {
            AudioClip click = Clips.mono(RATE, 1);
            r.commands().play(0, click, 1_000, 0, 1, 1, Bus.SFX, 0, 0, 7);
            float[] out = r.render(2_048);
            for (int f = 0; f < 2_048; f++) {
                float expected = f == 1_000 ? CENTRE : 0;
                assertThat(out[2 * f]).as("left at %d", f).isEqualTo(expected);
                assertThat(out[2 * f + 1]).as("right at %d", f).isEqualTo(expected);
            }
            List<String> events = new ArrayList<>();
            assertThat(r.poll((kind, slot, tag, frame) -> events.add(kind + " " + slot + " " + tag))).isEqualTo(1);
            assertThat(events).containsExactly("1 0 7");
            assertThat(r.frame()).isEqualTo(2_048);
            assertThat(r.latency()).isEqualTo(72);
        }
    }

    @Test
    void sixteenBitClipsReadAsTheirSamples() {
        try (AudioRenderer r = renderer()) {
            AudioClip clip = AudioClip.builder(RATE, 1).pcm16(new short[] {0, 16_384, 0, 0}).build();
            r.commands().play(0, clip, 100, 0, 1, 1, Bus.SFX, 0, 0, 1);
            float[] out = r.render(256);
            assertThat(out[2 * 101]).isEqualTo(0.5f * CENTRE);
        }
    }

    @Test
    void aPitchedLoopPlaysAtItsPitch() {
        try (AudioRenderer r = renderer()) {
            AudioClip tone = Clips.sine(RATE, 100, 0.5f, 480); // one period exactly: a seamless loop
            r.commands().play(0, tone, 0, 0, 1, 2, Bus.SFX, Voices.LOOP, 0, 1);
            float[] out = r.render(RATE);
            assertThat(Clips.upCrossings(out, 2, 0, 0, RATE)).isBetween(199, 201);
            assertThat((double) Clips.peak(out, 2, 0, RATE / 2, RATE)).isCloseTo(0.5 * CENTRE, within(0.01));
            r.commands().pitch(0, 0.5f);
            float[] slower = r.render(RATE);
            assertThat(Clips.upCrossings(slower, 2, 0, 2_048, RATE)).isBetween(48, 51);
        }
    }

    @Test
    void aClipAtAnotherRateIsResampled() {
        try (AudioRenderer r = renderer()) {
            AudioClip tone = Clips.sine(44_100, 441, 0.5f, 44_100);
            r.commands().play(0, tone, 0, 0, 1, 1, Bus.SFX, 0, 0, 1);
            float[] out = r.render(RATE);
            assertThat(Clips.upCrossings(out, 2, 0, 0, RATE - 2_048)).isBetween(420, 423); // 441 Hz for 0.957 s
        }
    }

    @Test
    void busesSumWithTheirVolumes() {
        try (AudioRenderer r = renderer()) {
            r.commands().layout(BusLayout.builder().bus(Bus.SFX).bus(Bus.MUSIC, b -> b.volume(-6.0206))
                .bus(Bus.VOICE, b -> b.mute()).build());
            AudioClip dc = Clips.constant(RATE, 0.25f, RATE);
            r.commands().play(0, dc, 0, 0, 1, 1, Bus.SFX, 0, -1, 1);
            r.commands().play(1, dc, 0, 0, 1, 1, Bus.MUSIC, 0, 1, 2);
            r.commands().play(2, dc, 0, 0, 1, 1, Bus.VOICE, 0, 0, 3);
            float[] out = r.render(4_096);
            int f = 2_000;
            assertThat((double) out[2 * f]).as("sfx, panned left").isCloseTo(0.25, within(1e-6));
            assertThat((double) out[2 * f + 1]).as("music at half, right").isCloseTo(0.125, within(1e-5));
            assertThat(r.commands().layout().contains(Bus.MUSIC)).isTrue();
            r.commands().layout(BusLayout.builder().bus(Bus.SFX, b -> b.volume(-6.0206)).build()); // voices playing
            r.render(1_024); // the voice of the muted bus was virtual: it comes back over 20 ms
            float[] swapped = r.render(1_024);
            // music and the muted voice bus are not in it: their sounds go into the master
            assertThat((double) swapped[2 * 1_000]).isCloseTo(0.125 + 0.25 * CENTRE, within(1e-5));
            assertThat((double) swapped[2 * 1_000 + 1]).isCloseTo(0.25 + 0.25 * CENTRE, within(1e-5));
            r.commands().busGain(Bus.SFX, 0);
            float[] quiet = r.render(1_024);
            assertThat(quiet[2 * 1_000]).as("sfx silenced: the voice in the master").isCloseTo(0.25f * CENTRE, within(1e-6f));
        }
    }

    @Test
    void aSoundAndItsNegationCancelExactly() {
        try (AudioRenderer r = renderer()) {
            AudioClip tone = Clips.sine(44_100, 997, 0.4f, 44_100);
            float[] negated = new float[44_100];
            for (int i = 0; i < negated.length; i++) {
                negated[i] = -tone.sample(i, 0);
            }
            r.commands().play(0, tone, 100, 0, 0.8f, 1.1f, Bus.SFX, 0, 0.3f, 1);
            r.commands().play(1, Clips.mono(44_100, negated), 100, 0, 0.8f, 1.1f, Bus.SFX, 0, 0.3f, 2);
            for (float s : r.render(RATE)) {
                assertThat(s).isZero();
            }
        }
    }

    @Test
    void gainChangesAndStopsAreRampedWithoutSteps() {
        try (AudioRenderer r = renderer()) {
            AudioClip dc = Clips.constant(RATE, 0.5f, 1_000);
            AudioClip loop = AudioClip.builder(RATE, 1).floats(new float[] {0.5f, 0.5f, 0.5f, 0.5f}).build();
            r.commands().play(0, loop, 0, 0, 1, 1, Bus.SFX, Voices.LOOP, 0, 1);
            r.render(1_024);
            r.commands().gain(0, 0.1f, 0);
            float[] out = r.render(1_024);
            assertThat(maxStep(out)).isLessThan(0.01f);
            assertThat((double) out[2 * 1_000]).isCloseTo(0.05 * CENTRE, within(1e-6));
            r.commands().gain(0, 1, 0.01);
            r.commands().stop(0, r.frame() + 500, 0.005);
            float[] stopped = r.render(2_048);
            assertThat(maxStep(stopped)).isLessThan(0.01f);
            assertThat(stopped[2 * 2_000]).isZero();
            assertThat(r.poll((k, s, t, f) -> { })).isEqualTo(1);
            r.commands().play(1, dc, r.frame(), 0, 1, 1, Bus.SFX, 0, 0, 2);
            float[] played = r.render(2_048);
            assertThat(played[2 * 999]).as("a short clip ends by itself").isNotZero();
            assertThat(played[2 * 1_100]).isZero();
        }
    }

    private static float maxStep(float[] out) {
        float m = 0;
        for (int i = 2; i < out.length; i += 2) {
            m = Math.max(m, Math.abs(out[i] - out[i - 2]));
        }
        return m;
    }

    @Test
    void killPauseAndCancelledStarts() {
        try (AudioRenderer r = renderer()) {
            AudioClip dc = Clips.constant(RATE, 0.5f, RATE);
            r.commands().play(0, dc, 0, 0, 1, 1, Bus.SFX, 0, 0, 1);
            r.commands().play(1, dc, 10_000, 0, 1, 1, Bus.SFX, 0, 0, 2);
            r.commands().stop(1, 10_000, 0.1); // stopped before it started: never heard
            r.render(1_024);
            r.commands().pause(0, true);
            r.commands().pause(0, true);
            float[] paused = r.render(2_048);
            assertThat(paused[2 * 2_000]).isZero();
            r.commands().pause(0, false);
            float[] resumed = r.render(2_048);
            assertThat((double) resumed[2 * 2_000]).isCloseTo(0.5 * CENTRE, within(1e-6));
            r.commands().kill(0);
            r.commands().pause(0, true); // ending: a pause changes nothing
            float[] killed = r.render(2_048);
            assertThat(killed[2 * 1_000]).isZero();
            List<Long> tags = new ArrayList<>();
            r.poll((k, s, t, f) -> tags.add(t));
            assertThat(tags).containsExactly(2L, 1L);
        }
    }

    @Test
    void startsFromAnOffsetFadeIn() {
        try (AudioRenderer r = renderer()) {
            AudioClip dc = Clips.constant(RATE, 0.5f, RATE);
            r.commands().play(0, dc, 0, 0.5, 1, 1, Bus.SFX, 0, 0, 1);
            float[] out = r.render(2_048);
            assertThat(out[0]).isLessThan(0.01f);
            assertThat((double) out[2 * 1_500]).isCloseTo(0.5 * CENTRE, within(1e-6));
        }
    }

    @Test
    void stereoClipsKeepTheirChannels() {
        try (AudioRenderer r = renderer()) {
            AudioClip stereo = AudioClip.builder(RATE, 2).floats(new float[] {0.25f, -0.5f, 0.25f, -0.5f}).build();
            r.commands().play(0, stereo, 0, 0, 1, 1, Bus.SFX, Voices.LOOP, 0, 1);
            float[] out = r.render(1_024);
            assertThat(out[2 * 500]).isEqualTo(0.25f);
            assertThat(out[2 * 500 + 1]).isEqualTo(-0.5f);
            r.commands().pan(0, 0.5f);
            float[] balanced = r.render(1_024);
            assertThat(balanced[2 * 500]).isEqualTo(0.125f);
            assertThat(balanced[2 * 500 + 1]).isEqualTo(-0.5f);
        }
    }

    @Test
    void theLimiterHoldsTheCeilingWithoutSteps() {
        try (AudioRenderer r = renderer()) {
            AudioClip loud = Clips.sine(RATE, 200, 1, RATE);
            for (int v = 0; v < 4; v++) {
                r.commands().play(v, loud, 0, 0, 1, 1, Bus.SFX, 0, 0, v);
            }
            float[] out = r.render(RATE / 2);
            float ceiling = (float) Math.pow(10, -1 / 20.0);
            assertThat(Clips.peak(out, 2, 0, 0, RATE / 2)).isLessThanOrEqualTo(ceiling + 1e-6f);
            assertThat(Clips.peak(out, 2, 0, 4_096, RATE / 2)).isGreaterThan(0.85f);
        }
    }

    @Test
    void speakerLayoutsPanByVbap() {
        try (AudioRenderer r = new AudioRenderer(AudioFormat.of(RATE, 6), 8)) {
            r.commands().play(0, Clips.constant(RATE, 0.5f, 4_096), 0, 0, 1, 1, Bus.SFX, 0, 0, 1);
            float[] out = r.render(2_048);
            assertThat(out[6 * 1_000 + 2]).as("centre").isCloseTo(0.5f, within(1e-6f));
            assertThat(out[6 * 1_000]).isCloseTo(0, within(1e-6f));
        }
        Panner seven = new Panner(8);
        float[] g = new float[8];
        seven.direction(Math.toRadians(-120), 0, g, 0);
        assertThat((double) (g[4] * g[4] + g[6] * g[6])).isCloseTo(1, within(1e-5));
        assertThat(g[3]).as("no LFE").isZero();
        seven.direction(Math.toRadians(180), 0.5f, g, 0);
        double power = 0;
        for (float x : g) {
            power += x * x;
        }
        assertThat(power).isCloseTo(1, within(1e-5));
        Panner quad = new Panner(4);
        quad.direction(Math.toRadians(45), 0, g, 0);
        assertThat(g[1]).isCloseTo(1, within(1e-6f));
        Panner mono = new Panner(1);
        mono.direction(1, 0, g, 0);
        assertThat(g[0]).isEqualTo(1);
        mono.pan(1, 0, g, 0);
        assertThat(g[0]).isEqualTo(1);
        Panner stereo = new Panner(2);
        stereo.direction(Math.PI / 2, 0, g, 0);
        assertThat(g[1]).isCloseTo(1, within(1e-6f));
    }

    @Test
    void theSameCommandsRenderTheSameBits() {
        assertThat(script()).isEqualTo(script());
    }

    private static float[] script() {
        try (AudioRenderer r = renderer()) {
            r.commands().play(0, Clips.sine(44_100, 333, 0.3f, 4_410), 10, 0, 0.7f, 1.3f, Bus.SFX, Voices.LOOP, -0.2f, 1);
            r.commands().play(1, Clips.sine(RATE, 1_234, 0.6f, RATE), 777, 0, 0.9f, 0.9f, Bus.MUSIC, 0, 0.6f, 2);
            r.commands().gain(0, 0.2f, 0.3);
            return r.render(RATE);
        }
    }

    @Test
    void goldenRenders() throws IOException {
        golden("click", render(r -> r.commands().play(0, Clips.mono(RATE, 1, -0.5f), 4_800, 0, 1, 1, Bus.SFX, 0, 0, 1)));
        golden("pitched_loop", render(r -> r.commands().play(0, Clips.sine(RATE, 100, 0.5f, 480), 0, 0, 1, 1.5f,
            Bus.SFX, Voices.LOOP, 0.4f, 1)));
        golden("two_buses", render(r -> {
            r.commands().layout(BusLayout.builder().bus(Bus.SFX, b -> b.volume(-3)).bus(Bus.MUSIC, b -> b.volume(-9))
                .build());
            r.commands().play(0, Clips.sine(RATE, 440, 0.5f, RATE), 0, 0, 1, 1, Bus.SFX, 0, -0.7f, 1);
            r.commands().play(1, Clips.sine(RATE, 660, 0.5f, RATE), 2_400, 0, 1, 1, Bus.MUSIC, 0, 0.7f, 2);
        }));
    }

    private static float[] render(java.util.function.Consumer<AudioRenderer> commands) {
        try (AudioRenderer r = renderer()) {
            commands.accept(r);
            return r.render(9_984); // 39 blocks of 256: goldens are pinned to 256-frame blocks
        }
    }

    /** Compares {@code samples} bit for bit with the stored golden, or writes it with -Ptruesim.audio.goldens=write. */
    private static void golden(String name, float[] samples) throws IOException {
        String file = "golden_" + name + ".wav";
        if ("write".equals(System.getProperty("truesim.audio.goldens"))) {
            WavWriter.write(Path.of(System.getProperty("truesim.audio.resources"), file), samples, 2, RATE, 32);
            return;
        }
        try (InputStream in = RendererTest.class.getResourceAsStream(file)) {
            assertThat(in).as("golden " + file + ": write it with -Ptruesim.audio.goldens=write").isNotNull();
            ByteBuffer b = ByteBuffer.wrap(in.readAllBytes()).order(ByteOrder.LITTLE_ENDIAN);
            assertThat(b.remaining()).isEqualTo(44 + 4 * samples.length);
            for (int i = 0; i < samples.length; i++) {
                assertThat(Float.floatToRawIntBits(b.getFloat(44 + 4 * i))).as("%s sample %d", name, i)
                    .isEqualTo(Float.floatToRawIntBits(samples[i]));
            }
        }
    }

    @Test
    void renderingAllocatesNothing() {
        try (AudioRenderer r = renderer()) {
            AudioClip tone = Clips.sine(44_100, 220, 0.2f, 44_100);
            for (int v = 0; v < 64; v++) {
                r.commands().play(v, tone, 0, 0, 0.1f, 1 + v * 0.01f, Bus.SFX, Voices.LOOP, (v % 9 - 4) / 4f, v);
            }
            float[] out = new float[256 * 2];
            assertAllocationFree(() -> {
                r.commands().gain(3, 0.2f, 0.01);
                r.render(out, 0, 256);
                r.poll((k, s, t, f) -> { });
            }, 20_000, 2_000, 10);
        }
    }

    @Test
    void offlineOutputBouncesToWav(@TempDir Path dir) throws IOException {
        try (AudioRenderer r = renderer(); OfflineOutput out = new OfflineOutput()) {
            assertThat(out.samples()).isEmpty();
            assertThatThrownBy(() -> out.render(1)).hasMessage("Not started");
            out.start(r);
            r.commands().play(0, Clips.sine(RATE, 440, 0.5f, RATE), 0, 0, 1, 1, Bus.SFX, 0, 0, 1);
            out.render(0.5);
            out.render(0.5);
            assertThat(out.frames()).isEqualTo(48_128);
            assertThat(out.samples()).hasSize(2 * 48_128);
            assertThat(out.underruns()).isZero();
            Path file = dir.resolve("bounce.wav");
            out.write(file, 16);
            assertThat(Files.size(file)).isEqualTo(44 + 2 * 2 * 48_128);
            out.write(dir.resolve("float.wav"), 32);
            assertThat(Files.size(dir.resolve("float.wav"))).isEqualTo(44 + 4 * 2 * 48_128);
            assertThatThrownBy(() -> out.write(dir.resolve("nowhere/x.wav"), 16)).isInstanceOf(
                java.io.UncheckedIOException.class);
            assertThatThrownBy(() -> WavWriter.bytes(new float[2], 2, RATE, 24)).hasMessageContaining("16-bit");
        }
    }

    @Test
    void formatsAndRingsRefuseWhatCannotBe() {
        assertThat(AudioFormat.stereo(RATE).block(128).blockSeconds()).isEqualTo(128.0 / RATE);
        assertThatThrownBy(() -> AudioFormat.stereo(100)).hasMessageContaining("Not an output rate");
        assertThatThrownBy(() -> AudioFormat.stereo(500_000)).hasMessageContaining("Not an output rate");
        assertThatThrownBy(() -> AudioFormat.of(RATE, 3)).hasMessageContaining("channels");
        assertThatThrownBy(() -> AudioFormat.stereo(RATE).block(100)).hasMessageContaining("power of two");
        assertThatThrownBy(() -> AudioFormat.stereo(RATE).block(8)).hasMessageContaining("power of two");
        assertThatThrownBy(() -> new Ring(3)).hasMessageContaining("power of two");
        Ring ring = new Ring(2);
        assertThat(ring.claim()).isZero();
        ring.publish();
        assertThat(ring.claim()).isEqualTo(1);
        ring.publish();
        assertThat(ring.claim()).isEqualTo(-1);
        assertThat(ring.overflows()).isEqualTo(1);
        assertThat(ring.size()).isEqualTo(2);
        assertThat(ring.capacity()).isEqualTo(2);
        try (AudioRenderer r = renderer()) {
            assertThat(r.render(100)).hasSize(200);
            assertThat(r.voices()).isEqualTo(AudioRenderer.DEFAULT_VOICES);
            assertThat(r.format().rate()).isEqualTo(RATE);
            assertThat(r.commands().overflows()).isZero();
            assertThat(r.closed()).isFalse();
        }
        AudioRenderer closing = renderer();
        closing.close();
        assertThat(closing.closed()).isTrue();
    }

    @Test
    void theClockPublishesFramesAndLatency() {
        try (AudioRenderer r = renderer()) {
            RenderClock clock = r.clock();
            assertThat(clock.rate()).isEqualTo(RATE);
            r.render(512);
            assertThat(clock.renderedFrame()).isEqualTo(512);
            assertThat(clock.schedulePoint()).isEqualTo(512 + r.latency());
            assertThat(clock.heardFrame()).isEqualTo(512);
            assertThat(clock.publishedNanos()).isPositive();
            clock.device(100);
            clock.latency(1_000);
            assertThat(clock.latency()).isEqualTo(1_000);
            assertThat(clock.heardFrame()).isEqualTo(412);
            assertThat(clock.queued()).isZero();
        }
    }
}
