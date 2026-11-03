package com.truesim.engine.audio;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.math.Maths;
import com.truesim.scene.audio.AudioEffect;
import com.truesim.scene.audio.Decibels;
import java.util.Arrays;

/**
 * The built-in effects of bus chains (§2.6, step 9), made from {@link AudioEffect} records on the game thread with
 * every buffer allocated there, and run on the audio thread allocation-free: RBJ biquads (low-pass, high-pass, band-pass
 * and peak), a feed-forward compressor, a look-ahead limiter and a feedback delay. Coefficients come from {@link Maths}
 * when made (or changed), never per sample.
 */
final class BusEffects {

    private BusEffects() {
    }

    /** One effect in a chain: processes interleaved frames in place. */
    interface Effect {
        void process(float[] buffer, int frames);
    }

    /** The effect {@code record} names, for {@code channels} at {@code rate}; reverb comes with step 10. */
    @AllowAllocation("built on the game thread")
    static Effect create(AudioEffect record, int rate, int channels) {
        return switch (record.name()) {
            case "eq.lowPass" -> Biquad.lowPass(record.value("cutoff", 1_000), record.value("q", 0.7071f), rate,
                channels);
            case "eq.highPass" -> Biquad.highPass(record.value("cutoff", 100), record.value("q", 0.7071f), rate,
                channels);
            case "eq.bandPass" -> Biquad.bandPass(record.value("low", 300), record.value("high", 3_000), rate,
                channels);
            case "eq.peak" -> Biquad.peak(record.value("frequency", 1_000), record.value("gain", 0),
                record.value("q", 1), rate, channels);
            case "compressor" -> new Compressor(record.value("threshold", -18), record.value("ratio", 4),
                record.value("attack", 0.005f), record.value("release", 0.1f), record.value("makeup", 0), rate,
                channels);
            case "limiter" -> new LimiterEffect(new Limiter(rate, channels, record.value("ceiling", -1)));
            case "delay" -> new Delay(record.value("time", 0.25f), record.value("feedback", 0.3f),
                record.value("mix", 0.3f), rate, channels);
            default -> throw new IllegalArgumentException("No built-in effect " + record.name());
        };
    }

    /** An RBJ biquad (Bristow-Johnson's cookbook), direct form I, a state per channel. */
    static final class Biquad implements Effect {
        private final int channels;
        private float b0;
        private float b1;
        private float b2;
        private float a1;
        private float a2;
        private final float[] x1;
        private final float[] x2;
        private final float[] y1;
        private final float[] y2;

        @AllowAllocation("built on the game thread")
        private Biquad(int channels) {
            this.channels = channels;
            x1 = new float[channels];
            x2 = new float[channels];
            y1 = new float[channels];
            y2 = new float[channels];
        }

        @AllowAllocation("built on the game thread")
        static Biquad lowPass(double cutoff, double q, int rate, int channels) {
            Biquad b = new Biquad(channels);
            b.lowPass(cutoff, q, rate);
            return b;
        }

        @AllowAllocation("built on the game thread")
        static Biquad highPass(double cutoff, double q, int rate, int channels) {
            Biquad b = new Biquad(channels);
            b.highPass(cutoff, q, rate);
            return b;
        }

        /** A band-pass between {@code low} and {@code high}: centred at their geometric mean, Q from the band. */
        @AllowAllocation("built on the game thread")
        static Biquad bandPass(double low, double high, int rate, int channels) {
            double centre = Maths.sqrt(low * high);
            Biquad b = new Biquad(channels);
            b.set(centre, centre / (high - low), rate, 2, 0);
            return b;
        }

        @AllowAllocation("built on the game thread")
        static Biquad peak(double frequency, double db, double q, int rate, int channels) {
            Biquad b = new Biquad(channels);
            b.set(frequency, q, rate, 3, db);
            return b;
        }

        /** Sets a low-pass at {@code cutoff}: for snapshots' filters, changed at a block's start. */
        void lowPass(double cutoff, double q, int rate) {
            set(cutoff, q, rate, 0, 0);
        }

        void highPass(double cutoff, double q, int rate) {
            set(cutoff, q, rate, 1, 0);
        }

        /** The cookbook's coefficients: kind 0 low-pass, 1 high-pass, 2 band-pass (0 dB peak), 3 peak. */
        private void set(double frequency, double q, int rate, int kind, double db) {
            double w = 2 * Math.PI * Math.min(frequency, 0.49 * rate) / rate;
            double cos = Maths.cos(w);
            double alpha = Maths.sin(w) / (2 * q);
            double a = Maths.pow(10, db / 40);
            double nb0;
            double nb1;
            double nb2;
            double na0;
            double na1 = -2 * cos;
            double na2;
            switch (kind) {
                case 0 -> {
                    nb0 = (1 - cos) / 2;
                    nb1 = 1 - cos;
                    nb2 = nb0;
                    na0 = 1 + alpha;
                    na2 = 1 - alpha;
                }
                case 1 -> {
                    nb0 = (1 + cos) / 2;
                    nb1 = -(1 + cos);
                    nb2 = nb0;
                    na0 = 1 + alpha;
                    na2 = 1 - alpha;
                }
                case 2 -> {
                    nb0 = alpha;
                    nb1 = 0;
                    nb2 = -alpha;
                    na0 = 1 + alpha;
                    na2 = 1 - alpha;
                }
                default -> {
                    nb0 = 1 + alpha * a;
                    nb1 = -2 * cos;
                    nb2 = 1 - alpha * a;
                    na0 = 1 + alpha / a;
                    na2 = 1 - alpha / a;
                }
            }
            b0 = (float) (nb0 / na0);
            b1 = (float) (nb1 / na0);
            b2 = (float) (nb2 / na0);
            a1 = (float) (na1 / na0);
            a2 = (float) (na2 / na0);
        }

        /** Its coefficients b0, b1, b2, a1, a2 (a0 1): for tests. */
        @AllowAllocation("tests")
        float[] coefficients() {
            return new float[] {b0, b1, b2, a1, a2};
        }

        @Override
        public void process(float[] buffer, int frames) {
            for (int c = 0; c < channels; c++) {
                float px1 = x1[c];
                float px2 = x2[c];
                float py1 = y1[c];
                float py2 = y2[c];
                for (int f = 0; f < frames; f++) {
                    int i = f * channels + c;
                    float x = buffer[i];
                    float y = b0 * x + b1 * px1 + b2 * px2 - a1 * py1 - a2 * py2;
                    px2 = px1;
                    px1 = x;
                    py2 = py1;
                    py1 = y;
                    buffer[i] = y;
                }
                x1[c] = px1;
                x2[c] = px2;
                y1[c] = py1;
                y2[c] = py2;
            }
        }
    }

    /**
     * A feed-forward compressor: a peak envelope (attack and release, one pole each) of the loudest channel, and above
     * the threshold a gain of (level − threshold)(1/ratio − 1) dB, computed every 16 frames and ramped between.
     */
    static final class Compressor implements Effect {
        private final int channels;
        private final float threshold;
        private final float slope;
        private final float makeup;
        private final float attack;
        private final float release;
        private float envelope;
        private float gain = 1;

        @AllowAllocation("built on the game thread")
        Compressor(double thresholdDb, double ratio, double attackSeconds, double releaseSeconds, double makeupDb,
                int rate, int channels) {
            this.channels = channels;
            threshold = (float) thresholdDb;
            slope = (float) (1 / Math.max(1, ratio) - 1);
            makeup = Decibels.gain((float) makeupDb);
            attack = (float) (1 - Maths.exp(-1 / (Math.max(1e-4, attackSeconds) * rate)));
            release = (float) (1 - Maths.exp(-1 / (Math.max(1e-4, releaseSeconds) * rate)));
        }

        @Override
        public void process(float[] buffer, int frames) {
            for (int start = 0; start < frames; start += 16) {
                int end = Math.min(frames, start + 16);
                for (int f = start; f < end; f++) {
                    float peak = 0;
                    for (int c = 0; c < channels; c++) {
                        peak = Math.max(peak, Math.abs(buffer[f * channels + c]));
                    }
                    envelope += (peak - envelope) * (peak > envelope ? attack : release);
                }
                float level = Decibels.of(envelope);
                float target = level > threshold ? Decibels.gain((level - threshold) * slope) : 1;
                float step = (target - gain) / (end - start);
                for (int f = start; f < end; f++) {
                    gain += step;
                    for (int c = 0; c < channels; c++) {
                        buffer[f * channels + c] *= gain * makeup;
                    }
                }
            }
        }
    }

    /** The look-ahead limiter as a bus effect (its delay is not compensated on a bus). */
    record LimiterEffect(Limiter limiter) implements Effect {
        @Override
        public void process(float[] buffer, int frames) {
            limiter.process(buffer, frames);
        }
    }

    /** A feedback delay: what went in {@code time} ago, fed back by {@code feedback}, mixed in by {@code mix}. */
    static final class Delay implements Effect {
        private final int channels;
        private final float[] line;
        private final int length;
        private final float feedback;
        private final float mix;
        private int at;

        @AllowAllocation("built on the game thread")
        Delay(double seconds, double feedback, double mix, int rate, int channels) {
            this.channels = channels;
            length = Math.max(1, (int) Math.round(seconds * rate));
            line = new float[length * channels];
            this.feedback = (float) Math.min(0.95, feedback);
            this.mix = (float) mix;
            Arrays.fill(line, 0);
        }

        @Override
        public void process(float[] buffer, int frames) {
            for (int f = 0; f < frames; f++) {
                for (int c = 0; c < channels; c++) {
                    int i = f * channels + c;
                    float delayed = line[at * channels + c];
                    line[at * channels + c] = buffer[i] + delayed * feedback;
                    buffer[i] += delayed * mix;
                }
                at = at + 1 == length ? 0 : at + 1;
            }
        }
    }
}
