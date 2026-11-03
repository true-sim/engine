package com.truesim.engine.audio;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.math.Maths;
import com.truesim.scene.audio.Attenuation;
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
 *
 * <p>Placed voices (§2.8) are mixed to mono and take from their listener-relative position the attenuation's gain,
 * spread and air low-pass (a one-pole filter), the direction's pan (equal power, or VBAP on speakers) and the Doppler
 * ratio (smoothed over 50 ms); a new place ramps over a block, a change of listener over 50 ms. Every 4 blocks the
 * most audible voices within the budget are real (AU7): audibility is the gain after attenuation, fades and the bus,
 * times the priority's weight; below -60 dB a voice goes virtual even with budget left, and comes back above -57 dB;
 * the highest priority is never virtual by budget. A voice going virtual fades out over 10 ms and then keeps time (its
 * position moves on without reading samples; a loop wraps), or ends, as its virtualization says; one coming back
 * fades in over 20 ms where it would be.
 */
final class Voices {

    static final byte FREE = 0;
    static final byte WAITING = 1;
    static final byte PLAYING = 2;

    /** Flags of a voice. */
    static final int LOOP = 1;
    /** Placed in 3D: the clip is mixed to mono and panned by direction. */
    static final int SPATIAL = 1 << 1;
    /** The priority's ordinal (0 lowest to 4 highest), in 3 bits from here. */
    static final int PRIORITY_SHIFT = 4;
    /** The virtualization's ordinal (keep time, kill, kill if finite), in 2 bits from here. */
    static final int VIRTUAL_SHIFT = 8;

    static final byte REAL = 0;
    static final byte TO_VIRTUAL = 1;
    static final byte VIRTUAL = 2;
    static final byte TO_REAL = 3;

    /** Real voices at most by default (AU7). */
    static final int DEFAULT_BUDGET = 64;
    private static final float SILENT = 0.001f; // -60 dB
    private static final float AUDIBLE = 0.0014125f; // -57 dB: 3 dB of hysteresis
    private static final float[] PRIORITY_WEIGHTS = {1 / 16f, 1 / 4f, 1, 4, 16};

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
    private final float[] panTarget;
    private final int[] spatialLeft;
    private final float[] pitches;
    private final Attenuation[] attenuations;
    private final float[] where;
    private final boolean[] placed;
    private final float[] attFrom;
    private final float[] attTo;
    private final float[] attTarget;
    private final float[] lowPass;
    private final float[] lowCoef;
    private final float[] dopplerTargets;
    private final float[] dopplers;
    private final byte[] reality;
    private final float[] realGains;
    private final float[] audibility;
    private final float[] scratch;
    private final float dopplerAlpha;
    private final int crossfadeBlocks;
    private final StreamRing[] rings;
    private final boolean[] starving;
    private final long[] resumes;
    private final float[] dryGains;
    private int starvations;
    private int budget = DEFAULT_BUDGET;
    private int real;
    private int virtual;
    private int blocks;
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
        panTarget = new float[capacity * channels];
        spatialLeft = new int[capacity];
        pitches = new float[capacity];
        attenuations = new Attenuation[capacity];
        where = new float[3 * capacity];
        placed = new boolean[capacity];
        attFrom = new float[capacity];
        attTo = new float[capacity];
        attTarget = new float[capacity];
        lowPass = new float[capacity];
        lowCoef = new float[capacity];
        dopplerTargets = new float[capacity];
        dopplers = new float[capacity];
        reality = new byte[capacity];
        realGains = new float[capacity];
        audibility = new float[capacity];
        scratch = new float[capacity];
        rings = new StreamRing[capacity];
        starving = new boolean[capacity];
        resumes = new long[capacity];
        dryGains = new float[capacity];
        dopplerAlpha = (float) (1 - Maths.exp(-(double) AudioFormat.DEFAULT_BLOCK / rate / 0.05));
        crossfadeBlocks = Math.max(1, (int) Math.ceil(0.05 * rate / AudioFormat.DEFAULT_BLOCK));
    }

    /** Sets the real voices at most. */
    void budget(int voices) {
        budget = voices;
    }

    /** Voices real at the last choice. */
    int real() {
        return real;
    }

    /** Voices virtual at the last choice. */
    int virtual() {
        return virtual;
    }

    /** Whether {@code slot} is real (or fading in or out). */
    boolean isReal(int slot) {
        return reality[slot] != VIRTUAL;
    }

    /** Times a streamed voice ran dry since made. */
    int starvations() {
        return starvations;
    }

    /** Where {@code slot} is in its clip, in clip frames. */
    double position(int slot) {
        return positions[slot];
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
            int flagBits, float pan, long tag, StreamRing ring) {
        states[slot] = WAITING;
        tags[slot] = tag;
        flags[slot] = flagBits;
        clips[slot] = clip;
        segments[slot] = clip.segment();
        rings[slot] = ring;
        starving[slot] = false;
        dryGains[slot] = 1;
        pcm16[slot] = clip.encoding() == AudioClip.Encoding.PCM16;
        clipChannels[slot] = clip.channels();
        clipFrames[slot] = ring != null ? ring.end() : clip.frames();
        if (ring != null) {
            flagBits &= ~LOOP; // the ring stitches the loop: the voice reads straight on
            flags[slot] = flagBits;
        }
        loopStarts[slot] = clip.loopStart();
        loopEnds[slot] = clip.loopEnd();
        starts[slot] = start;
        baseRates[slot] = (double) clip.rate() / rate;
        pitches[slot] = pitch;
        dopplers[slot] = 1;
        dopplerTargets[slot] = 1;
        attenuations[slot] = null;
        placed[slot] = false;
        attFrom[slot] = 1;
        attTo[slot] = 1;
        attTarget[slot] = 1;
        lowPass[slot] = 0;
        lowCoef[slot] = 1;
        spatialLeft[slot] = 0;
        reality[slot] = REAL;
        realGains[slot] = 1;
        audibility[slot] = 1;
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
        System.arraycopy(panTo, slot * channels, panTarget, slot * channels, channels);
        highest = Math.max(highest, slot + 1);
    }

    /**
     * Places {@code slot} at ({@code x}, {@code y}, {@code z}) relative to its listener (metres; x right, y up, -z
     * ahead), attenuated as {@code attenuation} says, with the Doppler ratio {@code doppler}; {@code crossfade} when
     * the listener changed (split screen): the change ramps over 50 ms.
     */
    void spatial(int slot, float x, float y, float z, float doppler, Attenuation attenuation, boolean crossfade) {
        where[3 * slot] = x;
        where[3 * slot + 1] = y;
        where[3 * slot + 2] = z;
        dopplerTargets[slot] = doppler;
        attenuations[slot] = attenuation;
        boolean first = !placed[slot];
        placed[slot] = true;
        place(slot);
        if (first) { // the first place: taken at once, not ramped from the middle
            int o = slot * channels;
            System.arraycopy(panTarget, o, panFrom, o, channels);
            System.arraycopy(panTarget, o, panTo, o, channels);
            attFrom[slot] = attTarget[slot];
            attTo[slot] = attTarget[slot];
            dopplers[slot] = doppler;
            spatialLeft[slot] = 0;
        } else {
            spatialLeft[slot] = crossfade ? crossfadeBlocks : 1;
        }
    }

    /** The targets of a placed voice from where it is: gain, pan, low-pass. Called at changes, not per block. */
    private void place(int slot) {
        Attenuation a = attenuations[slot];
        float x = where[3 * slot];
        float y = where[3 * slot + 1];
        float z = where[3 * slot + 2];
        float distance = (float) Math.sqrt(x * x + y * y + z * z);
        attTarget[slot] = a.gain(distance);
        double azimuth = Maths.atan2(x, -z);
        panner.direction(azimuth, a.spread(distance), panTarget, slot * channels);
        float cutoff = a.lowPass(distance);
        lowCoef[slot] = cutoff <= 0 ? 1 : (float) (1 - Maths.exp(-2 * Math.PI * Math.min(cutoff, 0.45 * rate) / rate));
    }

    /** Moves a placed voice's gain and pan a block towards their targets, and its Doppler ratio. */
    private void stepSpatial(int slot) {
        int o = slot * channels;
        int left = spatialLeft[slot];
        if (left > 0) {
            for (int c = 0; c < channels; c++) {
                panTo[o + c] = panFrom[o + c] + (panTarget[o + c] - panFrom[o + c]) / left;
            }
            attTo[slot] = attFrom[slot] + (attTarget[slot] - attFrom[slot]) / left;
            spatialLeft[slot] = left - 1;
        }
        dopplers[slot] += (dopplerTargets[slot] - dopplers[slot]) * dopplerAlpha;
        rateTargets[slot] = baseRates[slot] * pitches[slot] * dopplers[slot];
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
        pitches[slot] = pitch;
        rateTargets[slot] = baseRates[slot] * pitch * dopplers[slot];
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
        attenuations[slot] = null;
        if (rings[slot] != null) {
            rings[slot].close();
            rings[slot] = null;
        }
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
        StreamRing ring = rings[slot];
        if (ring != null) {
            long frame = Math.max(0, i);
            return ring.holds(frame) ? ring.sample(frame, channel) : held(ring, channel);
        }
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

    /** The last frame a dry ring holds (what a starved voice fades out on), or 0. */
    private static float held(StreamRing ring, int channel) {
        long last = ring.written() - 1;
        return ring.holds(last) ? ring.sample(last, channel) : 0;
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
        if (blocks++ % 4 == 0) {
            choose(graph, blockStart, events);
        }
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
            if (placed[slot]) {
                stepSpatial(slot);
            }
            boolean alive = reality[slot] == VIRTUAL ? keepTime(slot, blockStart, frames)
                : renderVoice(slot, graph.buffer(busIndex[slot]), blockStart, frames);
            if (!alive) {
                ended(slot, blockStart + frames, events);
            }
        }
        highest = top;
    }

    /**
     * Chooses the real voices: those at or above -60 dB (-57 dB to come back), the most audible within the budget, and
     * the highest priority beyond it. Voices dropping out fade over 10 ms, then keep time or end.
     */
    private void choose(BusGraph graph, long now, Ring events) {
        int candidates = 0;
        int highestPriority = 0;
        for (int slot = 0; slot < highest; slot++) {
            if (states[slot] == FREE) {
                continue;
            }
            // a fade still to come or under way counts as heard until it is done
            float a = Math.abs(gainTargets[slot]) * Math.max(envelopes[slot], envelopeTargets[slot]) * attTarget[slot]
                * graph.gain(busIndex[slot]);
            boolean wasReal = reality[slot] == REAL || reality[slot] == TO_REAL;
            boolean audible = a >= (wasReal ? SILENT : AUDIBLE);
            int priority = Math.min(4, flags[slot] >>> PRIORITY_SHIFT & 7);
            audibility[slot] = audible ? a * PRIORITY_WEIGHTS[priority] : 0;
            if (audible && priority == 4) {
                highestPriority++;
            } else if (audible) {
                scratch[candidates++] = audibility[slot];
            }
        }
        int room = Math.max(0, budget - highestPriority);
        float least = candidates <= room ? 0 : kth(scratch, candidates, candidates - room);
        real = 0;
        virtual = 0;
        for (int slot = 0; slot < highest; slot++) {
            if (states[slot] == FREE) {
                continue;
            }
            boolean highestOne = (flags[slot] >>> PRIORITY_SHIFT & 7) >= 4;
            boolean keep = audibility[slot] > 0 && (highestOne || audibility[slot] >= least);
            if (keep) {
                real++;
                if (reality[slot] == VIRTUAL || reality[slot] == TO_VIRTUAL) {
                    reality[slot] = TO_REAL; // where it would be, fading in
                }
            } else {
                virtual++;
                if (reality[slot] == REAL || reality[slot] == TO_REAL) {
                    int mode = flags[slot] >>> VIRTUAL_SHIFT & 3;
                    if (mode == 1 || mode == 2 && (flags[slot] & LOOP) == 0) {
                        stop(slot, now, (int) (0.01f * rate), now, events); // ends rather than keeping time
                    } else {
                        reality[slot] = TO_VIRTUAL;
                    }
                }
            }
        }
    }

    /** The value {@code k} places from the bottom of the first {@code n} values of {@code values}: a quickselect. */
    private static float kth(float[] values, int n, int k) {
        int low = 0;
        int high = n - 1;
        while (low < high) {
            float pivot = values[(low + high) >>> 1];
            int i = low;
            int j = high;
            while (i <= j) {
                while (values[i] < pivot) {
                    i++;
                }
                while (values[j] > pivot) {
                    j--;
                }
                if (i <= j) {
                    float t = values[i];
                    values[i] = values[j];
                    values[j] = t;
                    i++;
                    j--;
                }
            }
            if (k <= j) {
                high = j;
            } else if (k >= i) {
                low = i;
            } else {
                return values[k];
            }
        }
        return values[k];
    }

    /** A virtual voice's block: its position moves on as if it played; false when a one-shot reached its end. */
    private boolean keepTime(int slot, long blockStart, int frames) {
        int from = (int) Math.max(0, starts[slot] - blockStart);
        double moved = 0.5 * (rates[slot] + rateTargets[slot]) * (frames - from);
        rates[slot] = rateTargets[slot];
        int o = slot * channels;
        System.arraycopy(panTo, o, panFrom, o, channels);
        attFrom[slot] = attTo[slot];
        if (ending[slot]) {
            return false; // stopping, and out of earshot: over at once
        }
        if (envelopeTargets[slot] == 0 && envelopes[slot] == 0) {
            return true; // paused: held
        }
        double position = positions[slot] + moved;
        if ((flags[slot] & LOOP) != 0) {
            long loopStart = loopStarts[slot];
            long length = loopEnds[slot] - loopStart;
            if (position >= loopEnds[slot]) {
                position -= length * Math.floor((position - loopStart) / length);
            }
        } else if (position >= clipFrames[slot]) {
            return false;
        }
        positions[slot] = position;
        return true;
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
        boolean spatial = (flags[slot] & SPATIAL) != 0;
        float att = attFrom[slot];
        float attStep = (attTo[slot] - att) / frames;
        float realGain = realGains[slot];
        float realStep = reality[slot] == TO_REAL ? 1 / (0.02f * rate)
            : reality[slot] == TO_VIRTUAL ? -1 / (0.01f * rate) : 0;
        float low = lowPass[slot];
        float coef = lowCoef[slot];
        StreamRing ring = rings[slot];
        float dry = dryGains[slot];
        float dryStep = 1 / (0.005f * rate);
        for (int f = from; f < frames; f++) {
            if (ring != null) {
                // playing: the next frames must be there; starved: 50 ms of them before it fades back in
                long lead = starving[slot] ? rate / 20 : 2;
                boolean there = ring.holds((long) position) && ring.holds((long) position + lead);
                if (!there && !starving[slot]) { // dry: hold the last frame fading over 5 ms, keep time, ask
                    starving[slot] = true;
                    starvations++;
                    resumes[slot] = (long) position + rate / 10;
                    ring.resume(resumes[slot]);
                } else if (!there && position > resumes[slot] + rate / 10) {
                    resumes[slot] = (long) position + rate / 10; // passed where it asked before the ring got there
                    ring.resume(resumes[slot]);
                } else if (there && starving[slot]) {
                    starving[slot] = false;
                }
                dry = starving[slot] ? Math.max(0, dry - dryStep) : Math.min(1, dry + dryStep);
            }
            att += attStep;
            if (realStep != 0) {
                realGain = Math.max(0, Math.min(1, realGain + realStep));
            }
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
            float g = gain * env * att * realGain * dry;
            if (stereo) {
                float l = read(slot, position, 0, loop) * g;
                float r = read(slot, position, 1, loop) * g;
                out[f * channels] += l * (panFrom[o] + (panTo[o] - panFrom[o]) * t);
                out[f * channels + 1] += r * (panFrom[o + 1] + (panTo[o + 1] - panFrom[o + 1]) * t);
            } else {
                float s = read(slot, position, 0, loop);
                if (spatial) {
                    for (int c = 1; c < clipChannels[slot]; c++) {
                        s += read(slot, position, c, loop);
                    }
                    s /= clipChannels[slot];
                    low += coef * (s - low); // the air's low-pass: one pole
                    s = low;
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
        lowPass[slot] = low;
        dryGains[slot] = dry;
        if (ring != null) {
            ring.readTo((long) position - 2);
        }
        attFrom[slot] = attTo[slot];
        realGains[slot] = realGain;
        if (reality[slot] == TO_REAL && realGain >= 1) {
            reality[slot] = REAL;
        } else if (reality[slot] == TO_VIRTUAL && realGain <= 0) {
            reality[slot] = VIRTUAL;
        }
        System.arraycopy(panTo, o, panFrom, o, channels);
        return alive;
    }
}
