package com.truesim.engine.audio.dsp;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.math.Maths;

/**
 * A sample-accurate parameter (§2.13, like Web Audio's {@code AudioParam}): constant for most blocks, or carrying
 * events at sample frames (set, linear and exponential ramps), or driven by a signal in a patch. A node reads
 * {@link #at} per sample (k-rate nodes read {@link #value} once a block). Events are kept in frame order, up to 32
 * pending; a full queue replaces its last. Values are clamped to the range.
 */
public final class Param {

    private static final int EVENTS = 32;
    private static final byte SET = 0;
    private static final byte LINEAR = 1;
    private static final byte EXPONENTIAL = 2;

    private final NodeParam declared;
    private final float[] values;
    private final long[] at = new long[EVENTS];
    private final float[] to = new float[EVENTS];
    private final int[] lengths = new int[EVENTS];
    private final byte[] kinds = new byte[EVENTS];
    private int count;
    private float current;
    private float target;
    private float step;
    private double ratio;
    private boolean exponential;
    private int rampLeft;
    private boolean constant = true;
    private float[] driven;

    @AllowAllocation("set up once")
    Param(NodeParam declared, int maxBlock) {
        this.declared = declared;
        values = new float[maxBlock];
        current = declared.fallback();
    }

    /** Its value at frame {@code i} of the block. */
    public float at(int i) {
        return driven != null ? driven[i] : constant ? current : values[i];
    }

    /** Its value at the block's start (driven: its first frame). */
    public float value() {
        return driven != null ? driven[0] : constant ? current : values[0];
    }

    /** Whether it holds one value over the block. */
    public boolean constant() {
        return driven == null && constant;
    }

    /** Sets it to {@code value} at frame {@code frame}. */
    public void set(float value, long frame) {
        add(SET, frame, value, 0);
    }

    /** Ramps it linearly to {@code value} over {@code frames}, starting at frame {@code frame}. */
    public void linear(float value, long frame, int frames) {
        add(frames <= 0 ? SET : LINEAR, frame, value, frames);
    }

    /** Ramps it exponentially to {@code value} over {@code frames} (linear where either end is 0 or signs differ). */
    public void exponential(float value, long frame, int frames) {
        add(frames <= 0 ? SET : EXPONENTIAL, frame, value, frames);
    }

    /** Drives it from {@code signal} (a patch's buffer), or back to events with {@code null}. */
    void drive(float[] signal) {
        driven = signal;
    }

    private void add(byte kind, long frame, float value, int frames) {
        float v = Math.max(declared.min(), Math.min(declared.max(), value));
        int i = Math.min(count, EVENTS - 1);
        count = i + 1;
        while (i > 0 && at[i - 1] > frame) { // kept in frame order; equal frames in the order given
            at[i] = at[i - 1];
            to[i] = to[i - 1];
            lengths[i] = lengths[i - 1];
            kinds[i] = kinds[i - 1];
            i--;
        }
        at[i] = frame;
        to[i] = v;
        lengths[i] = frames;
        kinds[i] = kind;
    }

    /** Its values over the block of {@code frames} starting at frame {@code frame}: the events that fall in it. */
    void advance(long frame, int frames) {
        if (driven != null) {
            return;
        }
        if (rampLeft == 0 && (count == 0 || at[0] >= frame + frames)) {
            constant = true;
            return;
        }
        constant = false;
        for (int i = 0; i < frames; i++) {
            while (count > 0 && at[0] <= frame + i) {
                begin();
            }
            if (rampLeft > 0) {
                current = exponential ? (float) (current * ratio) : current + step;
                if (--rampLeft == 0) {
                    current = target;
                }
            }
            values[i] = current;
        }
    }

    private void begin() {
        byte kind = kinds[0];
        float v = to[0];
        int length = lengths[0];
        count--;
        System.arraycopy(at, 1, at, 0, count);
        System.arraycopy(to, 1, to, 0, count);
        System.arraycopy(lengths, 1, lengths, 0, count);
        System.arraycopy(kinds, 1, kinds, 0, count);
        target = v;
        if (kind == SET) {
            current = v;
            rampLeft = 0;
            return;
        }
        rampLeft = length;
        exponential = kind == EXPONENTIAL && current * v > 0;
        if (exponential) {
            ratio = Maths.pow(v / current, 1.0 / length);
        } else {
            step = (v - current) / length;
        }
    }

    /** Back to its default, no events. */
    void reset() {
        count = 0;
        rampLeft = 0;
        current = declared.fallback();
        constant = true;
    }
}
