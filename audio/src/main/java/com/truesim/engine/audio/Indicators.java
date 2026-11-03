package com.truesim.engine.audio;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.math.Maths;
import com.truesim.scene.audio.Caption;

/**
 * Visualized sound (§2.17): the loudest captioned sounds a listener hears this frame, loudest first, each with its
 * caption, its direction in the listener's frame (a unit vector: x right, y up, -z ahead) and its loudness, for a HUD
 * ring that shows footsteps and gunfire to players who cannot hear them. Filled by {@link Audio#indicators}; made once
 * with its capacity, then reused.
 */
public final class Indicators {

    private final Caption[] captions;
    private final long[] instances;
    private final float[] directions;
    private final float[] loudness;
    private int count;

    /** Room for the {@code capacity} loudest. */
    @AllowAllocation("made once")
    public Indicators(int capacity) {
        captions = new Caption[capacity];
        instances = new long[capacity];
        directions = new float[3 * capacity];
        loudness = new float[capacity];
    }

    /** How many it holds. */
    public int count() {
        return count;
    }

    public Caption caption(int i) {
        return captions[check(i)];
    }

    /** The sound instance of indicator {@code i}. */
    public long instance(int i) {
        return instances[check(i)];
    }

    /** Component {@code axis} (x right, y up, z behind) of indicator {@code i}'s unit direction. */
    public float direction(int i, int axis) {
        return directions[3 * check(i) + axis];
    }

    /** Indicator {@code i}'s angle around the listener: 0 ahead, π/2 right, ±π behind. */
    public double azimuth(int i) {
        return Maths.atan2(direction(i, 0), -direction(i, 2));
    }

    /** How loud indicator {@code i} is heard (a linear gain). */
    public float loudness(int i) {
        return loudness[check(i)];
    }

    private int check(int i) {
        if (i < 0 || i >= count) {
            throw new IndexOutOfBoundsException(i);
        }
        return i;
    }

    void clear() {
        count = 0;
        java.util.Arrays.fill(captions, null);
    }

    /** Keeps the sound if it is among the loudest: in loudness order, the quietest dropped when full. */
    void offer(Caption caption, long instance, float x, float y, float z, float level) {
        int i = count < captions.length ? count++ : captions.length;
        if (i == captions.length && level <= loudness[i - 1]) {
            return;
        }
        i = Math.min(i, captions.length - 1);
        while (i > 0 && loudness[i - 1] < level) {
            captions[i] = captions[i - 1];
            instances[i] = instances[i - 1];
            loudness[i] = loudness[i - 1];
            System.arraycopy(directions, 3 * (i - 1), directions, 3 * i, 3);
            i--;
        }
        captions[i] = caption;
        instances[i] = instance;
        loudness[i] = level;
        directions[3 * i] = x;
        directions[3 * i + 1] = y;
        directions[3 * i + 2] = z;
    }
}
