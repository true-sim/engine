package com.truesim.engine.audio;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.math.Maths;
import com.truesim.scene.audio.Bus;
import com.truesim.scene.audio.BusLayout;
import com.truesim.scene.audio.Decibels;
import java.util.Arrays;
import java.util.List;

/**
 * The mixer's buses for one layout (§2.6): a buffer per bus, each processed after the buses feeding it (the layout's
 * order): its effect chain, the snapshots' low-pass and high-pass where set, its pre-fader sends, its gain (volume,
 * mute, player volume and snapshots, times a duck), ramped over the block so nothing clicks, its post-fader sends, its
 * meter, then summed into its parent; the master, last, is the mix. A duck follows its sidechain bus's peak of the
 * block before (attack and release, one pole each): one block late, whatever the order. Meters publish peak and RMS
 * about 30 times a second. Built on the game thread with every buffer allocated, swapped in at a block boundary;
 * processing allocates nothing.
 */
final class BusGraph {

    private final BusLayout layout;
    private final int channels;
    private final int block;
    private final float[][] buffers;
    private final int[] parents;
    private final int[][] sendTargets;
    private final float[][] sendGains;
    private final boolean[][] sendPre;
    /** Each bus's gain: its volume (muted: 0) times the extra gain of player volumes and snapshots. */
    private final float[] layoutGains;
    private final float[] extra;
    private final float[] current;
    private final int master;
    private final int rate;
    private final BusEffects.Effect[][] effects;
    private final BusEffects.Biquad[] lowPasses;
    private final BusEffects.Biquad[] highPasses;
    private final float[] lowCuts;
    private final float[] highCuts;
    private final int[] duckedBus;
    private final int[] sidechain;
    private final float[] duckDepth;
    private final float[] duckAttack;
    private final float[] duckRelease;
    private final float[] duckLevel;
    private final float[] duckGain;
    private final float[] blockPeaks;
    private final float[] peaks;
    private final double[] squares;
    private final float[] meterPeaks;
    private final float[] meterRms;
    private final int meterFrames;
    private int metered;
    private volatile int meterVersion;

    @AllowAllocation("built on the game thread")
    BusGraph(BusLayout layout, int rate, int channels, int block) {
        this.layout = layout;
        this.rate = rate;
        this.channels = channels;
        this.block = block;
        List<BusLayout.Settings> buses = layout.buses();
        int n = buses.size();
        buffers = new float[n][block * channels];
        parents = new int[n];
        sendTargets = new int[n][];
        sendGains = new float[n][];
        sendPre = new boolean[n][];
        layoutGains = new float[n];
        extra = new float[n];
        current = new float[n];
        effects = new BusEffects.Effect[n][];
        lowPasses = new BusEffects.Biquad[n];
        highPasses = new BusEffects.Biquad[n];
        lowCuts = new float[n];
        highCuts = new float[n];
        duckGain = new float[n];
        blockPeaks = new float[n];
        peaks = new float[n];
        squares = new double[n];
        meterPeaks = new float[n];
        meterRms = new float[n];
        meterFrames = Math.max(block, rate / 30);
        Arrays.fill(duckGain, 1);
        for (int i = 0; i < n; i++) {
            BusLayout.Settings s = buses.get(i);
            Bus parent = s.bus().parent();
            parents[i] = parent == null ? -1 : layout.indexOf(parent);
            List<BusLayout.Send> sends = s.sends();
            sendTargets[i] = new int[sends.size()];
            sendGains[i] = new float[sends.size()];
            sendPre[i] = new boolean[sends.size()];
            for (int k = 0; k < sends.size(); k++) {
                sendTargets[i][k] = layout.indexOf(sends.get(k).aux());
                sendGains[i][k] = Decibels.gain(sends.get(k).db());
                sendPre[i][k] = sends.get(k).preFader();
            }
            layoutGains[i] = s.mute() ? 0 : Decibels.gain(s.volume());
            effects[i] = new BusEffects.Effect[s.effects().size()];
            for (int k = 0; k < effects[i].length; k++) {
                effects[i][k] = BusEffects.create(s.effects().get(k), rate, channels);
            }
            lowPasses[i] = BusEffects.Biquad.lowPass(20_000, 0.7071, rate, channels);
            highPasses[i] = BusEffects.Biquad.highPass(10, 0.7071, rate, channels);
            extra[i] = 1;
            current[i] = layoutGains[i];
        }
        master = layout.indexOf(Bus.MASTER);
        List<BusLayout.Ducking> ducks = layout.ducks();
        duckedBus = new int[ducks.size()];
        sidechain = new int[ducks.size()];
        duckDepth = new float[ducks.size()];
        duckAttack = new float[ducks.size()];
        duckRelease = new float[ducks.size()];
        duckLevel = new float[ducks.size()];
        for (int d = 0; d < ducks.size(); d++) {
            BusLayout.Ducking duck = ducks.get(d);
            duckedBus[d] = layout.indexOf(duck.ducked());
            sidechain[d] = layout.indexOf(duck.sidechain());
            duckDepth[d] = Decibels.gain(duck.duck().db());
            duckAttack[d] = (float) (1 - Maths.exp(-(double) block / rate / Math.max(1e-3, duck.duck().attack())));
            duckRelease[d] = (float) (1 - Maths.exp(-(double) block / rate / Math.max(1e-3, duck.duck().release())));
        }
    }

    /**
     * Sets bus {@code i}'s snapshot filters: a low-pass at {@code lowPass} Hz and a high-pass at {@code highPass} Hz,
     * each off at 0. On the audio thread, at a block's start (coefficients from Maths once).
     */
    void filters(int i, float lowPass, float highPass) {
        if (lowPass != lowCuts[i] && lowPass > 0) {
            lowPasses[i].lowPass(lowPass, 0.7071, rate);
        }
        if (highPass != highCuts[i] && highPass > 0) {
            highPasses[i].highPass(highPass, 0.7071, rate);
        }
        lowCuts[i] = lowPass;
        highCuts[i] = highPass;
    }

    /** Bus {@code i}'s peak over the last meter period (about 1/30 s), linear. */
    float meterPeak(int i) {
        return meterPeaks[i];
    }

    /** Bus {@code i}'s RMS over the last meter period, linear. */
    float meterRms(int i) {
        return meterRms[i];
    }

    /** A count that grows whenever the meters publish. */
    int meterVersion() {
        return meterVersion;
    }

    /** The gain duck {@code d} applies now (1 when not ducking): for tests and meters. */
    float duckGain(int bus) {
        return duckGain[bus];
    }

    BusLayout layout() {
        return layout;
    }

    /** Where {@code bus} is, or the master's index for a bus the layout lacks. */
    int index(Bus bus) {
        int i = layout.indexOf(bus);
        return i < 0 ? master : i;
    }

    int size() {
        return buffers.length;
    }

    /** Bus {@code i}'s buffer for this block: interleaved frames. */
    float[] buffer(int i) {
        return buffers[i];
    }

    /** The master's buffer, the mix once processed. */
    float[] mix() {
        return buffers[master];
    }

    /** Sets bus {@code i}'s extra gain (player volume times snapshots), reached over the next block. */
    void extraGain(int i, float gain) {
        extra[i] = gain;
    }

    float extraGain(int i) {
        return extra[i];
    }

    /** Bus {@code i}'s gain now (its volume, mute and extra gain), for audibility. */
    float gain(int i) {
        return current[i];
    }

    /** Zeroes every buffer: the start of a block. */
    void clear() {
        for (float[] b : buffers) {
            Arrays.fill(b, 0);
        }
    }

    /** Mixes the buses down into the master, in order. */
    void process() {
        int n = buffers.length;
        int samples = block * channels;
        Arrays.fill(duckGain, 1);
        for (int d = 0; d < duckedBus.length; d++) {
            float active = blockPeaks[sidechain[d]] > 0.003f ? 1 : 0; // the sidechain sounds above about -50 dBFS
            duckLevel[d] += (active - duckLevel[d]) * (active > duckLevel[d] ? duckAttack[d] : duckRelease[d]);
            duckGain[duckedBus[d]] *= 1 + (duckDepth[d] - 1) * duckLevel[d];
        }
        for (int i = 0; i < n; i++) {
            float[] b = buffers[i];
            for (BusEffects.Effect effect : effects[i]) {
                effect.process(b, block);
            }
            if (lowCuts[i] > 0) {
                lowPasses[i].process(b, block);
            }
            if (highCuts[i] > 0) {
                highPasses[i].process(b, block);
            }
            for (int k = 0; k < sendTargets[i].length; k++) {
                if (sendPre[i][k]) {
                    add(b, buffers[sendTargets[i][k]], sendGains[i][k], samples);
                }
            }
            float from = current[i];
            float to = layoutGains[i] * extra[i] * duckGain[i];
            current[i] = to;
            if (from != 1 || to != 1) {
                float step = (to - from) / block;
                for (int f = 0; f < block; f++) {
                    float g = from + step * (f + 1);
                    for (int c = 0; c < channels; c++) {
                        b[f * channels + c] *= g;
                    }
                }
            }
            for (int k = 0; k < sendTargets[i].length; k++) {
                if (!sendPre[i][k]) {
                    add(b, buffers[sendTargets[i][k]], sendGains[i][k], samples);
                }
            }
            float peak = 0;
            double square = 0;
            for (int s = 0; s < samples; s++) {
                peak = Math.max(peak, Math.abs(b[s]));
                square += b[s] * b[s];
            }
            blockPeaks[i] = peak;
            peaks[i] = Math.max(peaks[i], peak);
            squares[i] += square;
            if (parents[i] >= 0) {
                add(b, buffers[parents[i]], 1, samples);
            }
        }
        metered += block;
        if (metered >= meterFrames) {
            for (int i = 0; i < n; i++) {
                meterPeaks[i] = peaks[i];
                meterRms[i] = (float) Math.sqrt(squares[i] / ((double) metered * channels));
                peaks[i] = 0;
                squares[i] = 0;
            }
            metered = 0;
            meterVersion++;
        }
    }

    private static void add(float[] from, float[] into, float gain, int samples) {
        for (int s = 0; s < samples; s++) {
            into[s] += from[s] * gain;
        }
    }
}
