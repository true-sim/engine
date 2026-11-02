package com.truesim.engine.audio;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.math.Maths;

/**
 * Gains per output channel for a direction: equal-power panning in stereo, pairwise VBAP (vector base amplitude
 * panning, Pulkki 1997) on the horizontal ring of a speaker layout (quad, 5.1, 7.1, in SDL's channel orders, the LFE
 * left out), mono as is. A spread (0 a point, 1 all around) blends the gains towards an even spread of the same
 * power. Called per voice per block, not per sample: it uses {@link Maths}. Allocation-free.
 */
final class Panner {

    private final int channels;
    /** The speakers on the ring, by azimuth (radians, clockwise from the front), and their channels. */
    private final double[] azimuths;
    private final int[] speakerChannels;

    @AllowAllocation("made with its renderer")
    Panner(int channels) {
        this.channels = channels;
        double[] degrees = switch (channels) {
            case 4 -> new double[] {-45, 45, -135, 135};
            case 6 -> new double[] {-30, 30, 0, Double.NaN, -110, 110};
            case 8 -> new double[] {-30, 30, 0, Double.NaN, -150, 150, -90, 90};
            default -> new double[0];
        };
        int count = 0;
        for (double d : degrees) {
            count += Double.isNaN(d) ? 0 : 1;
        }
        azimuths = new double[count];
        speakerChannels = new int[count];
        int n = 0;
        for (int c = 0; c < degrees.length; c++) {
            if (!Double.isNaN(degrees[c])) {
                azimuths[n] = Maths.toRadians(degrees[c]);
                speakerChannels[n++] = c;
            }
        }
        // sorted by azimuth, so neighbours on the ring are neighbours in the arrays
        for (int i = 1; i < count; i++) {
            for (int j = i; j > 0 && azimuths[j] < azimuths[j - 1]; j--) {
                double a = azimuths[j];
                azimuths[j] = azimuths[j - 1];
                azimuths[j - 1] = a;
                int c = speakerChannels[j];
                speakerChannels[j] = speakerChannels[j - 1];
                speakerChannels[j - 1] = c;
            }
        }
    }

    /**
     * Writes the gains for a source at {@code azimuth} (radians, clockwise from the front, so right is positive) with
     * {@code spread} into {@code out[offset..offset + channels)}; their squares sum to 1.
     */
    void direction(double azimuth, float spread, float[] out, int offset) {
        java.util.Arrays.fill(out, offset, offset + channels, 0);
        if (channels == 1) {
            out[offset] = 1;
            return;
        }
        if (channels == 2) {
            // the sine of the azimuth: front and back pan alike (no elevation or front-back cue without HRTF)
            pan((float) Maths.sin(azimuth), spread, out, offset);
            return;
        }
        double a = Maths.wrapAngle(azimuth);
        int n = azimuths.length;
        int right = 0;
        while (right < n && azimuths[right] < a) {
            right++;
        }
        int left = (right + n - 1) % n;
        right %= n;
        double from = azimuths[left];
        double width = Maths.wrapAngle(azimuths[right] - from);
        if (width <= 0) {
            width += 2 * Math.PI;
        }
        double t = Maths.wrapAngle(a - from);
        if (t < 0) {
            t += 2 * Math.PI;
        }
        // VBAP in the plane: solve g1 l1 + g2 l2 = p, then normalise the power
        double s = Maths.sin(width);
        double g1 = Maths.sin(width - t) / s;
        double g2 = Maths.sin(t) / s;
        double norm = Maths.sqrt(g1 * g1 + g2 * g2);
        out[offset + speakerChannels[left]] = (float) (g1 / norm);
        out[offset + speakerChannels[right]] += (float) (g2 / norm);
        spread(spread, out, offset, n);
    }

    /** Writes stereo gains for a pan from −1 (left) to 1 (right): equal power, and the spread towards the middle. */
    void pan(float pan, float spread, float[] out, int offset) {
        double angle = (Math.max(-1, Math.min(1, pan)) * (1 - spread) + 1) * Math.PI / 4;
        java.util.Arrays.fill(out, offset, offset + channels, 0);
        if (channels == 1) {
            out[offset] = 1;
            return;
        }
        out[offset] = (float) Maths.cos(angle);
        out[offset + 1] = (float) Maths.sin(angle);
    }

    /** Blends ring gains towards an even spread over the {@code n} ring speakers, keeping the power. */
    private void spread(float spread, float[] out, int offset, int n) {
        if (spread <= 0) {
            return;
        }
        float even = (float) (1 / Maths.sqrt(n));
        double power = 0;
        for (int i = 0; i < n; i++) {
            int c = offset + speakerChannels[i];
            out[c] = out[c] * (1 - spread) + even * spread;
            power += out[c] * out[c];
        }
        float scale = (float) (1 / Maths.sqrt(power));
        for (int i = 0; i < n; i++) {
            out[offset + speakerChannels[i]] *= scale;
        }
    }
}
