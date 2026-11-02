package com.truesim.engine.audio;

import com.truesim.annotations.AllowAllocation;
import com.truesim.scene.audio.AudioClip;
import com.truesim.scene.audio.Bus;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Arrays;

/**
 * The renderer's voices (§2.5), by slot: each reads a clip from where it is with a 4-point cubic Hermite resampler at
 * its rate, ramps its gain per sample, runs its envelope (fades for starts from an offset, stops, kills and pauses),
 * pans and sums into its bus. Slots are mixed in slot order, so the same commands sum the same floats in the same
 * order (AU10). Used on the audio thread only; allocation-free.
 */
final class Voices {

    static final byte FREE = 0;
    static final byte WAITING = 1;
    static final byte PLAYING = 2;

    /** Flags of a voice. */
    static final int LOOP = 1;
    /** Placed in 3D: the clip is mixed to mono and panned by direction (step 7). */
    static final int SPATIAL = 1 << 1;

    private static final ValueLayout.OfShort SHORT = ValueLayout.JAVA_SHORT_UNALIGNED;
    private static final ValueLayout.OfFloat FLOAT = ValueLayout.JAVA_FLOAT_UNALIGNED;

    private final int channels;
    private final int rate;
    private final Panner panner;
    private final int capacity;

    final byte[] states;
    final long[] tags;
    final int[] flags;
    private final AudioClip[] clips;
    private final MemorySegment[] segments;
    private final boolean[] pcm16;
    private final int[] clipChannels;
    private final long[] clipFrames;
    private final long[] loopStarts;
    private final long[] loopEnds;
    final long[] starts;
    final double[] positions;
    private final double[] rates;
    private final double[] rateTargets;
    private final double[] baseRates;
    final float[] gains;
    private final float[] gainTargets;
    private final float[] gainSteps;
    private final int[] gainLeft;
    final float[] envelopes;
    private final float[] envelopeTargets;
    private final float[] envelopeSteps;
    private final long[] envelopeStarts;
    private final boolean[] ending;
    private final boolean[] paused;
    final Bus[] buses;
    final int[] busIndex;
    private final float[] pans;
    private final float[] panFrom;
    private final float[] panTo;
    private int highest;
    private long ended;

    @AllowAllocation("made with its renderer")
    Voices(int capacity, int channels, int rate) {
        this.capacity = capacity;
        this.channels = channels;
        this.rate = rate;
        panner = new Panner(channels);
        states = new byte[capacity];
        tags = new long[capacity];
        flags = new int[capacity];
        clips = new AudioClip[capacity];
        segments = new MemorySegment[capacity];
        pcm16 = new boolean[capacity];
        clipChannels = new int[capacity];
        clipFrames = new long[capacity];
        loopStarts = new long[capacity];
        loopEnds = new long[capacity];
        starts = new long[capacity];
        positions = new double[capacity];
        rates = new double[capacity];
        rateTargets = new double[capacity];
        baseRates = new double[capacity];
        gains = new float[capacity];
        gainTargets = new float[capacity];
        gainSteps = new float[capacity];
        gainLeft = new int[capacity];
        envelopes = new float[capacity];
        envelopeTargets = new float[capacity];
        envelopeSteps = new float[capacity];
        envelopeStarts = new long[capacity];
        ending = new boolean[capacity];
        paused = new boolean[capacity];
        buses = new Bus[capacity];
        busIndex = new int[capacity];
        pans = new float[capacity];
        panFrom = new float[capacity * channels];
        panTo = new float[capacity * channels];
    }

    int capacity() {
        return capacity;
    }

    /** One past the highest slot that may be in use. */
    int highest() {
        return highest;
    }

    /** Voices that ended since made. */
    long ended() {
        return ended;
    }

    boolean free(int slot) {
        return states[slot] == FREE;
    }

    /**
     * Starts {@code clip} in {@code slot} at mix frame {@code start}, {@code offset} seconds into it: a start from an
     * offset fades in over 20 ms; one from the beginning plays its first sample as it is.
     */
    void play(int slot, AudioClip clip, long start, double offset, float gain, float pitch, Bus bus, int busIdx,
            int flagBits, float pan, long tag) {
        states[slot] = WAITING;
        tags[slot] = tag;
        flags[slot] = flagBits;
        clips[slot] = clip;
        segments[slot] = clip.segment();
        pcm16[slot] = clip.encoding() == AudioClip.Encoding.PCM16;
        clipChannels[slot] = clip.channels();
        clipFrames[slot] = clip.frames();
        loopStarts[slot] = clip.loopStart();
        loopEnds[slot] = clip.loopEnd();
        starts[slot] = start;
        baseRates[slot] = (double) clip.rate() / rate;
        rates[slot] = baseRates[slot] * pitch;
        rateTargets[slot] = rates[slot];
        positions[slot] = offset * clip.rate();
        gains[slot] = gain;
        gainTargets[slot] = gain;
        gainLeft[slot] = 0;
        boolean resumed = offset > 0;
        envelopes[slot] = resumed ? 0 : 1;
        envelopeTargets[slot] = 1;
        envelopeSteps[slot] = resumed ? 1 / (0.02f * rate) : 0;
        envelopeStarts[slot] = start;
        ending[slot] = false;
        paused[slot] = false;
        buses[slot] = bus;
        busIndex[slot] = busIdx;
        pans[slot] = pan;
        panGains(slot, panTo);
        System.arraycopy(panTo, slot * channels, panFrom, slot * channels, channels);
        highest = Math.max(highest, slot + 1);
    }

    /** Moves the gain to {@code gain} over {@code frames} (64 at least, so nothing clicks). */
    void gain(int slot, float gain, int frames) {
        int n = Math.max(64, frames);
        gainTargets[slot] = gain;
        gainSteps[slot] = (gain - gains[slot]) / n;
        gainLeft[slot] = n;
    }

    /** Sets the pitch: a playback-rate ratio on top of the clip's rate, reached over the next block. */
    void pitch(int slot, float pitch) {
        rateTargets[slot] = baseRates[slot] * pitch;
    }

    /** Sets the 2D pan, −1 left to 1 right, reached over the next block. */
    void pan(int slot, float pan) {
        pans[slot] = pan;
        panGains(slot, panTo);
    }

    /** Fades out over {@code frames} from mix frame {@code at}, then ends; a voice not started yet ends at once. */
    void stop(int slot, long at, int frames, long now, Ring events) {
        if (states[slot] == WAITING && at <= starts[slot]) {
            ended(slot, now, events);
            return;
        }
        ending[slot] = true;
        envelopeTargets[slot] = 0;
        envelopeStarts[slot] = Math.max(at, now);
        envelopeSteps[slot] = -envelopes[slot] / Math.max(1, frames);
    }

    /** Pauses (a 10 ms fade, then held where it is) or resumes (a 10 ms fade in). */
    void pause(int slot, boolean pause, long now) {
        if (paused[slot] == pause || ending[slot]) {
            return;
        }
        paused[slot] = pause;
        envelopeTargets[slot] = pause ? 0 : 1;
        envelopeStarts[slot] = now;
        envelopeSteps[slot] = (pause ? -1 : 1) / (0.01f * rate);
    }

    private void end(int slot) {
        states[slot] = FREE;
        clips[slot] = null;
        segments[slot] = null;
        buses[slot] = null;
        ended++;
    }

    /** Ends {@code slot} and tells the game, so it may use the slot again. */
    private void ended(int slot, long frame, Ring events) {
        long tag = tags[slot];
        end(slot);
        int e = events.claim();
        if (e >= 0) {
            events.opcodes[e] = AudioRenderer.ENDED;
            events.slots[e] = slot;
            events.tags[e] = tag;
            events.frames[e] = frame;
            events.publish();
        }
    }

    private void panGains(int slot, float[] into) {
        int o = slot * channels;
        if (clipChannels[slot] >= 2 && (flags[slot] & SPATIAL) == 0 && channels >= 2) {
            // a stereo clip in 2D: a balance, its channels kept apart
            Arrays.fill(into, o, o + channels, 0);
            into[o] = Math.min(1, 1 - pans[slot]);
            into[o + 1] = Math.min(1, 1 + pans[slot]);
        } else if (channels <= 2) {
            panner.pan(pans[slot], 0, into, o);
        } else {
            panner.direction(pans[slot] * Math.PI / 6, 0, into, o);
        }
    }

    /** One tap of {@code slot}'s clip: frame {@code i} (wrapped into the loop, 0 past the end), {@code channel}. */
    private float tap(int slot, long i, int channel, boolean loop) {
        long frame = i;
        if (frame < 0) {
            frame = 0;
        } else if (loop && frame >= loopEnds[slot]) {
            long length = loopEnds[slot] - loopStarts[slot];
            frame = loopStarts[slot] + (frame - loopStarts[slot]) % length;
        } else if (frame >= clipFrames[slot]) {
            return 0;
        }
        long index = frame * clipChannels[slot] + channel;
        return pcm16[slot] ? segments[slot].getAtIndex(SHORT, index) * 0x1p-15f
            : segments[slot].getAtIndex(FLOAT, index);
    }

    /** {@code slot}'s clip at fractional frame {@code position}, {@code channel}: Catmull-Rom cubic Hermite. */
    private float read(int slot, double position, int channel, boolean loop) {
        long i = (long) position;
        float t = (float) (position - i);
        float xm1 = tap(slot, i - 1, channel, loop);
        float x0 = tap(slot, i, channel, loop);
        float x1 = tap(slot, i + 1, channel, loop);
        float x2 = tap(slot, i + 2, channel, loop);
        float c1 = 0.5f * (x1 - xm1);
        float c2 = xm1 - 2.5f * x0 + 2 * x1 - 0.5f * x2;
        float c3 = 0.5f * (x2 - xm1) + 1.5f * (x0 - x1);
        return ((c3 * t + c2) * t + c1) * t + x0;
    }

    /**
     * Mixes every voice into the buses of {@code graph} for the block of {@code frames} starting at mix frame
     * {@code blockStart}; ended voices go to {@code events} as ENDED.
     */
    void render(BusGraph graph, long blockStart, int frames, Ring events) {
        int top = 0;
        for (int slot = 0; slot < highest; slot++) {
            if (states[slot] == FREE) {
                continue;
            }
            top = slot + 1;
            if (starts[slot] >= blockStart + frames) {
                continue;
            }
            states[slot] = PLAYING;
            if (!renderVoice(slot, graph.buffer(busIndex[slot]), blockStart, frames)) {
                ended(slot, blockStart + frames, events);
            }
        }
        highest = top;
    }

    /** Renders one voice into {@code out}; false when it ended. */
    private boolean renderVoice(int slot, float[] out, long blockStart, int frames) {
        int from = (int) Math.max(0, starts[slot] - blockStart);
        boolean loop = (flags[slot] & LOOP) != 0;
        boolean stereo = clipChannels[slot] >= 2 && (flags[slot] & SPATIAL) == 0 && channels >= 2;
        double position = positions[slot];
        double rateFrom = rates[slot];
        double rateStep = (rateTargets[slot] - rateFrom) / frames;
        float gain = gains[slot];
        float env = envelopes[slot];
        float envTarget = envelopeTargets[slot];
        float envStep = envelopeSteps[slot];
        long envStart = envelopeStarts[slot];
        int o = slot * channels;
        long loopEnd = loopEnds[slot];
        long loopLength = loopEnd - loopStarts[slot];
        long end = clipFrames[slot];
        boolean alive = true;
        for (int f = from; f < frames; f++) {
            if (gainLeft[slot] > 0) {
                gain += gainSteps[slot];
                if (--gainLeft[slot] == 0) {
                    gain = gainTargets[slot];
                }
            }
            if (blockStart + f >= envStart && env != envTarget) {
                env += envStep;
                if (envStep > 0 ? env >= envTarget : env <= envTarget) {
                    env = envTarget;
                }
            }
            if (env == 0 && envTarget == 0) {
                if (ending[slot]) {
                    alive = false;
                    break;
                }
                continue; // paused: held where it is
            }
            float t = (float) (f + 1) / frames;
            float g = gain * env;
            if (stereo) {
                float l = read(slot, position, 0, loop) * g;
                float r = read(slot, position, 1, loop) * g;
                out[f * channels] += l * (panFrom[o] + (panTo[o] - panFrom[o]) * t);
                out[f * channels + 1] += r * (panFrom[o + 1] + (panTo[o + 1] - panFrom[o + 1]) * t);
            } else {
                float s = read(slot, position, 0, loop);
                for (int c = 1; c < clipChannels[slot] && (flags[slot] & SPATIAL) != 0; c++) {
                    s += read(slot, position, c, loop);
                }
                if ((flags[slot] & SPATIAL) != 0) {
                    s /= clipChannels[slot];
                }
                s *= g;
                for (int c = 0; c < channels; c++) {
                    out[f * channels + c] += s * (panFrom[o + c] + (panTo[o + c] - panFrom[o + c]) * t);
                }
            }
            position += rateFrom + rateStep * f;
            if (loop && position >= loopEnd) {
                position -= loopLength * Math.floor((position - loopStarts[slot]) / loopLength);
            } else if (!loop && position >= end) {
                alive = false;
                break;
            }
        }
        positions[slot] = position;
        rates[slot] = rateTargets[slot];
        gains[slot] = gain;
        envelopes[slot] = env;
        System.arraycopy(panTo, o, panFrom, o, channels);
        return alive;
    }
}
