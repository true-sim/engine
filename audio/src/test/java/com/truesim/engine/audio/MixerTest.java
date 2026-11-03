package com.truesim.engine.audio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.truesim.scene.audio.AudioClip;
import com.truesim.scene.audio.AudioEffect;
import com.truesim.scene.audio.Bus;
import com.truesim.scene.audio.BusLayout;
import com.truesim.scene.audio.Duck;
import com.truesim.scene.audio.Snapshot;
import org.junit.jupiter.api.Test;

/** The mixer (§6 step 9): effect chains, ducking, snapshots, meters and the accessibility stage. */
class MixerTest {

    static final int RATE = 48_000;
    static final float CENTRE = (float) Math.cos(Math.PI / 4);

    /** The analytic magnitude of an RBJ biquad at {@code hz}. */
    static double magnitude(float[] c, double hz) {
        double w = 2 * Math.PI * hz / RATE;
        double cr = c[0] + c[1] * Math.cos(w) + c[2] * Math.cos(2 * w);
        double ci = -c[1] * Math.sin(w) - c[2] * Math.sin(2 * w);
        double dr = 1 + c[3] * Math.cos(w) + c[4] * Math.cos(2 * w);
        double di = -c[3] * Math.sin(w) - c[4] * Math.sin(2 * w);
        return Math.sqrt((cr * cr + ci * ci) / (dr * dr + di * di));
    }

    /** A sine of {@code hz} through {@code effect}: the peak of its second half over its input's. */
    static double response(BusEffects.Effect effect, double hz) {
        int frames = 94 * 256;
        float[] buffer = new float[2 * frames];
        for (int f = 0; f < frames; f++) {
            float s = (float) Math.sin(2 * Math.PI * hz * f / RATE);
            buffer[2 * f] = s;
            buffer[2 * f + 1] = s;
        }
        for (int at = 0; at < frames; at += 256) {
            float[] part = java.util.Arrays.copyOfRange(buffer, 2 * at, 2 * at + 512);
            effect.process(part, 256);
            System.arraycopy(part, 0, buffer, 2 * at, 512);
        }
        double square = 0;
        for (int f = frames / 2; f < frames; f++) {
            square += buffer[2 * f] * buffer[2 * f];
        }
        return Math.sqrt(2 * square / (frames / 2)); // the amplitude from the RMS: sample peaks miss it at high pitch
    }

    @Test
    void filtersRespondAsTheirAnalyticCurves() {
        BusEffects.Biquad low = BusEffects.Biquad.lowPass(1_000, 0.7071, RATE, 2);
        BusEffects.Biquad high = BusEffects.Biquad.highPass(1_000, 0.7071, RATE, 2);
        BusEffects.Biquad band = BusEffects.Biquad.bandPass(500, 2_000, RATE, 2);
        BusEffects.Biquad peak = BusEffects.Biquad.peak(1_000, 6, 1, RATE, 2);
        for (double hz : new double[] {125, 500, 1_000, 2_000, 8_000}) {
            for (BusEffects.Biquad b : new BusEffects.Biquad[] {low, high, band, peak}) {
                assertThat(response(b, hz)).as("%s Hz", hz).isCloseTo(magnitude(b.coefficients(), hz), within(0.005));
            }
        }
        assertThat(magnitude(low.coefficients(), 1_000)).isCloseTo(Math.sqrt(0.5), within(1e-3)); // -3 dB at cutoff
        assertThat(magnitude(peak.coefficients(), 1_000)).isCloseTo(Math.pow(10, 6 / 20.0), within(1e-3));
        assertThat(magnitude(band.coefficients(), 1_000)).isCloseTo(1, within(1e-3));
    }

    @Test
    void theCompressorLimiterAndDelayDoWhatTheySay() {
        BusEffects.Effect squash = BusEffects.create(AudioEffect.named("compressor").set("threshold", -20)
            .set("ratio", 4).set("attack", 0.001).set("release", 0.05), RATE, 2);
        // a sine at 0 dBFS: 20 dB over, so 15 dB down: about 0.178
        assertThat(response(squash, 500)).isCloseTo(Math.pow(10, -15 / 20.0), within(0.02));
        BusEffects.Effect limit = BusEffects.create(AudioEffect.named("limiter").set("ceiling", -6), RATE, 2);
        assertThat(response(limit, 500)).isCloseTo(Math.pow(10, -6 / 20.0), within(0.002)); // held at the ceiling
        BusEffects.Effect echo = BusEffects.create(AudioEffect.named("delay").set("time", 0.01).set("feedback", 0)
            .set("mix", 0.5), RATE, 2);
        float[] impulse = new float[2 * 1_024];
        impulse[0] = 1;
        echo.process(impulse, 1_024);
        assertThat(impulse[2 * 480]).isEqualTo(0.5f);
        BusEffects.Effect hp = BusEffects.create(AudioEffect.named("eq.highPass").set("cutoff", 200), RATE, 2);
        assertThat(response(hp, 50)).isLessThan(0.1);
        BusEffects.Effect lp = BusEffects.create(AudioEffect.named("eq.lowPass"), RATE, 2);
        assertThat(response(lp, 8_000)).isLessThan(0.05);
        BusEffects.Effect bp = BusEffects.create(AudioEffect.named("eq.bandPass"), RATE, 2);
        assertThat(response(bp, 950)).isCloseTo(1, within(0.01));
        BusEffects.Effect pk = BusEffects.create(AudioEffect.named("eq.peak").set("gain", -12), RATE, 2);
        assertThat(response(pk, 1_000)).isCloseTo(0.25, within(0.01));
        assertThatThrownBy(() -> BusEffects.create(AudioEffect.named("bitCrusher"), RATE, 2))
            .hasMessageContaining("No built-in effect");
    }

    @Test
    void dialogueDucksTheMusicWithItsAttackAndRelease() {
        try (AudioRenderer r = AudioRenderer.offline(AudioFormat.stereo(RATE))) {
            r.commands().layout(BusLayout.builder().bus(Bus.MUSIC).bus(Bus.VOICE)
                .duck(Bus.MUSIC, Bus.VOICE, Duck.by(-9).attack(0.05).release(0.6)).build());
            r.commands().play(0, Clips.constant(RATE, 0.25f, 10 * RATE), 0, 0, 1, 1, Bus.MUSIC, 0, 0, 0);
            r.render(RATE / 2);
            assertThat(r.duckGain(Bus.MUSIC)).isEqualTo(1);
            r.commands().play(1, Clips.constant(RATE, 0.1f, RATE), r.frame() + r.latency(), 0, 1, 1, Bus.VOICE, 0, 0,
                1);
            r.render(2_400 + 256); // 50 ms into the dialogue: one time constant
            double full = Math.pow(10, -9 / 20.0);
            assertThat((double) r.duckGain(Bus.MUSIC)).isCloseTo(1 + (full - 1) * (1 - Math.exp(-1)), within(0.03));
            r.render(RATE / 2);
            assertThat((double) r.duckGain(Bus.MUSIC)).isCloseTo(full, within(0.005));
            r.render(RATE / 2 + 600 * 48); // the dialogue ended; 0.6 s of release: one time constant back
            assertThat((double) r.duckGain(Bus.MUSIC)).isCloseTo(1 + (full - 1) * Math.exp(-1), within(0.05));
        }
    }

    @Test
    void snapshotsBlendByPriorityAndIntensity() {
        try (AudioRenderer r = AudioRenderer.offline(AudioFormat.stereo(RATE))) {
            Mixer mixer = new Mixer(r.commands());
            Snapshot quiet = Snapshot.builder("quiet").set(Bus.SFX, Bus.VOLUME, -20).fadeIn(1).fadeOut(0.5)
                .priority(1).build();
            Snapshot muffled = Snapshot.builder("muffled").set(Bus.SFX, Bus.VOLUME, -10).set(Bus.MUSIC,
                Bus.LOW_PASS, 800).set(Bus.MUSIC, Bus.HIGH_PASS, 200).fadeIn(0).priority(5).build();
            Mixer.SnapshotInstance a = mixer.start(quiet);
            Mixer.SnapshotInstance b = mixer.start(muffled).intensity(0.5f);
            assertThat(mixer.playing()).isEqualTo(2);
            mixer.update(0.5);
            // quiet at half its fade: -10 dB; then muffled, the higher, half way from there to -10: -10
            assertThat((double) mixer.snapshotVolume(Bus.SFX)).isCloseTo(-10, within(1e-4));
            mixer.update(0.5);
            // quiet full: -20; muffled half way to -10: -15
            assertThat((double) mixer.snapshotVolume(Bus.SFX)).isCloseTo(-15, within(1e-4));
            assertThat((double) mixer.snapshotLowPass(Bus.MUSIC)).isCloseTo(12_400, within(1.0));
            b.stop();
            mixer.update(0.1); // its fade-out: the default 0.1 s
            assertThat(mixer.playing()).isEqualTo(1);
            assertThat(mixer.snapshotLowPass(Bus.MUSIC)).isZero();
            mixer.playerVolume(Bus.SFX, 0.5f);
            a.stop();
            mixer.update(0.25);
            assertThat((double) mixer.snapshotVolume(Bus.SFX)).isCloseTo(-10, within(1e-4));
            mixer.update(0.25);
            assertThat(mixer.playing()).isZero();
            assertThat(a.fade()).isZero();
            assertThat(a.intensity()).isEqualTo(1);
            assertThat(a.snapshot()).isEqualTo(quiet);
            // the renderer's bus followed: player half, no snapshot
            r.commands().play(0, Clips.constant(RATE, 0.5f, RATE), 0, 0, 1, 1, Bus.SFX, 0, 0, 0);
            float[] out = r.render(4_096);
            assertThat((double) out[2 * 3_000]).isCloseTo(0.25 * CENTRE, within(1e-5));
            mixer.layout(BusLayout.builder().bus(Bus.SFX).build());
            assertThat(mixer.layout().contains(Bus.MUSIC)).isFalse();
            assertThatThrownBy(() -> mixer.start(muffled)).hasMessageContaining("not in the layout");
        }
    }

    @Test
    void snapshotFiltersMuffleABus() {
        try (AudioRenderer r = AudioRenderer.offline(AudioFormat.stereo(RATE))) {
            r.commands().busFilters(Bus.MUSIC, 500, 0);
            r.commands().play(0, Clips.sine(RATE, 4_000, 0.5f, 3 * RATE), 0, 0, 1, 1, Bus.MUSIC, 0, 0, 0);
            float[] out = r.render(RATE / 2);
            assertThat(Clips.peak(out, 2, 0, RATE / 4, RATE / 2)).isLessThan(0.01f);
            r.commands().busFilters(Bus.MUSIC, 0, 8_000);
            out = r.render(RATE / 2);
            assertThat(Clips.peak(out, 2, 0, RATE / 4, RATE / 2)).isLessThan(0.1f); // an octave under: -12 dB
            r.commands().busFilters(Bus.MUSIC, 0, 0);
            out = r.render(RATE / 2);
            assertThat(Clips.peak(out, 2, 0, RATE / 4, RATE / 2)).isCloseTo(0.5f * CENTRE, within(0.01f));
        }
    }

    @Test
    void metersReadPeakAndRms() {
        try (AudioRenderer r = AudioRenderer.offline(AudioFormat.stereo(RATE))) {
            r.commands().play(0, Clips.sine(RATE, 500, 0.5f, RATE), 0, 0, 1, 1, Bus.SFX, 0, -1, 0);
            r.render(RATE / 4);
            assertThat((double) r.meterPeak(Bus.SFX)).isCloseTo(0.5, within(0.01));
            assertThat((double) r.meterRms(Bus.SFX)).isCloseTo(0.5 / Math.sqrt(2) / Math.sqrt(2), within(0.01));
            assertThat(r.meterPeak(Bus.of("nowhere"))).isZero();
            assertThat(r.meterRms(Bus.of("nowhere"))).isZero();
            assertThat(r.duckGain(Bus.of("nowhere"))).isEqualTo(1);
        }
    }

    @Test
    void monoDownmixNullsOppositeChannelsAndBalanceLowersTheFarSide() {
        try (AudioRenderer r = AudioRenderer.offline(AudioFormat.stereo(RATE))) {
            AudioClip opposite = AudioClip.builder(RATE, 2).floats(new float[] {0.3f, -0.3f, 0.2f, -0.2f}).build();
            r.commands().play(0, opposite, 0, 0, 1, 1, Bus.SFX, Voices.LOOP, 0, 0);
            r.commands().accessibility(true, 0, 0);
            float[] out = r.render(4_096);
            for (int f = 1_000; f < 4_000; f++) {
                assertThat(out[2 * f]).isCloseTo(0, within(1e-9f)); // a null: below -180 dB
            }
            r.commands().kill(0);
            r.commands().play(1, Clips.constant(RATE, 0.5f, 10 * RATE), r.frame() + r.latency(), 0, 1, 1, Bus.SFX, 0,
                -1, 1);
            r.render(1_024);
            out = r.render(1_024);
            // a sound on the left alone, in mono: both ears at -3 dB of it
            assertThat((double) out[2 * 500]).isCloseTo(0.5 * Math.sqrt(0.5), within(1e-5));
            assertThat((double) out[2 * 500 + 1]).isCloseTo(0.5 * Math.sqrt(0.5), within(1e-5));
            r.commands().accessibility(false, 1, 2); // balance right, night
            out = r.render(4_096);
            assertThat(out[2 * 3_000]).isCloseTo(0, within(1e-9f)); // the left gone at balance 1
            r.commands().accessibility(false, 0, 1);
            r.render(4_096);
        }
    }
}
