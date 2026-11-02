package com.truesim.engine.audio;

import com.truesim.annotations.AllowAllocation;
import com.truesim.scene.audio.Bus;
import com.truesim.scene.audio.BusLayout;
import com.truesim.scene.audio.Decibels;
import java.util.Arrays;
import java.util.List;

/**
 * The mixer's buses for one layout (§2.6): a buffer per bus, each processed after the buses feeding it (the layout's
 * order): its sends taken (before or after its volume), its gain applied, ramped over the block so nothing clicks, then
 * summed into its parent; the master, last, is the mix. Built on the game thread with every buffer allocated, swapped
 * in at a block boundary; processing allocates nothing. Effect chains and ducks come with the mixer step (§6 step 9).
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

    @AllowAllocation("built on the game thread")
    BusGraph(BusLayout layout, int channels, int block) {
        this.layout = layout;
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
            extra[i] = 1;
            current[i] = layoutGains[i];
        }
        master = layout.indexOf(Bus.MASTER);
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
        for (int i = 0; i < n; i++) {
            float[] b = buffers[i];
            for (int k = 0; k < sendTargets[i].length; k++) {
                if (sendPre[i][k]) {
                    add(b, buffers[sendTargets[i][k]], sendGains[i][k], samples);
                }
            }
            float from = current[i];
            float to = layoutGains[i] * extra[i];
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
            if (parents[i] >= 0) {
                add(b, buffers[parents[i]], 1, samples);
            }
        }
    }

    private static void add(float[] from, float[] into, float gain, int samples) {
        for (int s = 0; s < samples; s++) {
            into[s] += from[s] * gain;
        }
    }
}
