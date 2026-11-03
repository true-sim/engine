package com.truesim.engine.audio;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.math.Maths;
import com.truesim.engine.audio.dsp.PatchVoice;
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
    /** Placed in 3D through the HRTF while a spatializer is set and the voice is among the most audible (§2.9). */
    static final int HRTF = 1 << 2;
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
    private final float[] occGains;
    private final float[] occCutoffs;
    private final float[] dopplerTargets;
    private final float[] dopplers;
    private final byte[] reality;
    private final float[] realGains;
    private final float[] audibility;
    private final float[] scratch;
    private final float dopplerAlpha;
    private final int crossfadeBlocks;
    private final StreamRing[] rings;
    private final PatchVoice[] patches;
    private final int[] auxIndex;
    private final float[] auxFrom;
    private final float[] auxTo;
    private final boolean[] starving;
    private final float[] lastPlayed;
    private final float[] levels;
    private final long[] resumes;
    private final float[] dryGains;
    private int starvations;
    private int budget = DEFAULT_BUDGET;
    private int real;
    private int virtual;
    private int blocks;
    private int highest;
    private long ended;

    // Binaural voices (step 17, §2.9): the spatializer's channels, which voice has each, the fade between pan and HRTF.
    private Spatializer spatializer;
    private int binauralCap;
    private final int[] binauralChannel;
    private final float[] binauralMix;
    private final float[] binauralTarget;
    private int[] channelSlot = new int[0];
    private int[] drainBus = new int[0];
    private int[] drainLeft = new int[0];
    private final float[] binaural;
    // The ambisonics bus (step 18): HRTF voices past the cap, encoded by direction; their gains now and wanted.
    private Ambisonics ambisonics;
    private final float[] fieldGains;
    private final float[] fieldTargets;
    private final float[] fieldInput;

    // Rendering listeners (step 13, §2.10): their mixes, the voices each hears and how loud, re-emitting voices.
    static final int LISTENERS = 4;
    private static final int MIX_RING = 4_096;
    private final int block;
    private final boolean[] mixActive = new boolean[LISTENERS];
    private final int[] mixChannels = new int[LISTENERS];
    private final float[] mixGain = new float[LISTENERS];
    private final int[] mixBus = new int[LISTENERS];
    private final ListenerTap[] mixTaps = new ListenerTap[LISTENERS];
    private final float[][] mixOut;
    private final float[][] mixRing = new float[LISTENERS][MIX_RING];
    private final boolean[] mixRenders = new boolean[LISTENERS];
    private final boolean[] mixDone = new boolean[LISTENERS];
    private final long[] mixBlocks = new long[LISTENERS];
    private final float[] loopGains = {1, 1, 1, 1};
    private final float[][] mixRms = new float[LISTENERS][8];
    private final int[] mixOrder = new int[LISTENERS];
    private int mixOrderCount;
    private final float[][] heardFrom;
    private final float[][] heardTo;
    private final float[] heardMax;
    private final float[][] voiceBuffers;
    private final int[] inputs;
    private final int[] inputPrevious;
    private final long[] inputSwitched;

    @AllowAllocation("made with its renderer")
    Voices(int capacity, int channels, int rate, int block) {
        this.capacity = capacity;
        this.block = block;
        mixOut = new float[LISTENERS][2 * block];
        heardFrom = new float[LISTENERS][2 * capacity];
        heardTo = new float[LISTENERS][2 * capacity];
        heardMax = new float[capacity];
        voiceBuffers = new float[capacity][block];
        binauralChannel = new int[capacity];
        binauralMix = new float[capacity];
        binauralTarget = new float[capacity];
        binaural = new float[block];
        fieldGains = new float[capacity * Ambisonics.CHANNELS];
        fieldTargets = new float[capacity * Ambisonics.CHANNELS];
        fieldInput = new float[block];
        Arrays.fill(binauralChannel, -1);
        inputs = new int[capacity];
        inputPrevious = new int[capacity];
        inputSwitched = new long[capacity];
        Arrays.fill(inputs, -1);
        Arrays.fill(inputPrevious, -1);
        Arrays.fill(mixBus, -1);
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
        patches = new PatchVoice[capacity];
        auxIndex = new int[capacity];
        auxFrom = new float[capacity];
        auxTo = new float[capacity];
        starving = new boolean[capacity];
        lastPlayed = new float[2 * capacity];
        occGains = new float[capacity];
        occCutoffs = new float[capacity];
        levels = new float[capacity];
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
            int flagBits, float pan, long tag, StreamRing ring, PatchVoice patch, int input) {
        states[slot] = WAITING;
        inputs[slot] = input;
        inputPrevious[slot] = -1;
        unheard(slot);
        tags[slot] = tag;
        flags[slot] = flagBits;
        clips[slot] = clip;
        patches[slot] = patch;
        rings[slot] = ring;
        auxIndex[slot] = -1;
        auxFrom[slot] = 0;
        auxTo[slot] = 0;
        starving[slot] = false;
        dryGains[slot] = 1;
        levels[slot] = 0;
        if (patch != null || input >= 0) { // a patch or a listener's mix: mono at the mix rate, endless
            segments[slot] = null;
            pcm16[slot] = false;
            clipChannels[slot] = 1;
            clipFrames[slot] = Long.MAX_VALUE;
            loopStarts[slot] = 0;
            loopEnds[slot] = 0;
            baseRates[slot] = 1;
            flagBits &= ~LOOP;
            flags[slot] = flagBits;
        } else {
            segments[slot] = clip.segment();
            pcm16[slot] = clip.encoding() == AudioClip.Encoding.PCM16;
            clipChannels[slot] = clip.channels();
            clipFrames[slot] = ring != null ? ring.end() : clip.frames();
            if (ring != null) {
                flagBits &= ~LOOP; // the ring stitches the loop: the voice reads straight on
                flags[slot] = flagBits;
            }
            loopStarts[slot] = clip.loopStart();
            loopEnds[slot] = clip.loopEnd();
            baseRates[slot] = (double) clip.rate() / rate;
        }
        starts[slot] = start;
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
        occGains[slot] = 1;
        occCutoffs[slot] = 0;
        spatialLeft[slot] = 0;
        reality[slot] = REAL;
        realGains[slot] = 1;
        audibility[slot] = 1;
        rates[slot] = baseRates[slot] * pitch;
        rateTargets[slot] = rates[slot];
        positions[slot] = input >= 0 ? start : patch != null ? 0 : offset * clip.rate(); // an input: by mix frame
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
        if (first && (flags[slot] & HRTF) != 0 && spatializer != null && binauralCount() < binauralCap) {
            bind(slot, 1); // a voice starting binaural: no fade from the pan
        }
        place(slot);
        if (first) { // the first place: taken at once, not ramped from the middle
            int o = slot * channels;
            System.arraycopy(panTarget, o, panFrom, o, channels);
            System.arraycopy(panTarget, o, panTo, o, channels);
            attFrom[slot] = attTarget[slot];
            attTo[slot] = attTarget[slot];
            System.arraycopy(fieldTargets, slot * Ambisonics.CHANNELS, fieldGains, slot * Ambisonics.CHANNELS,
                Ambisonics.CHANNELS);
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
        attTarget[slot] = a.gain(distance) * occGains[slot];
        double azimuth = Maths.atan2(x, -z);
        panner.direction(azimuth, a.spread(distance), panTarget, slot * channels);
        float air = a.lowPass(distance);
        float occluded = occCutoffs[slot];
        float cutoff = air <= 0 ? occluded : occluded <= 0 ? air : Math.min(air, occluded);
        lowCoef[slot] = cutoff <= 0 ? 1 : (float) (1 - Maths.exp(-2 * Math.PI * Math.min(cutoff, 0.45 * rate) / rate));
        if (binauralChannel[slot] >= 0) {
            spatializer.place(binauralChannel[slot], x, y, z);
        }
        if ((flags[slot] & HRTF) != 0) {
            Ambisonics.encode(x, y, z, fieldTargets, slot * Ambisonics.CHANNELS);
        }
    }

    // ---------------------------------------------------------------- binaural voices

    /**
     * Renders up to {@code cap} placed {@code HRTF} voices through {@code next} (null: none, every voice pans), the
     * most audible at each choice; a voice joining or leaving cross-fades between its pan and the HRTF over a block. A
     * spatializer replaced or removed drops its voices back to panning at once.
     */
    @AllowAllocation("a spatializer of another size: a setting changed")
    void spatializer(Spatializer next, int cap) {
        for (int slot = 0; slot < capacity; slot++) {
            binauralChannel[slot] = -1;
            binauralMix[slot] = 0;
            binauralTarget[slot] = 0;
        }
        spatializer = next;
        ambisonics = next == null ? null : next.ambisonics();
        binauralCap = next == null ? 0 : Math.min(cap, next.capacity());
        int channels = next == null ? 0 : next.capacity();
        if (channelSlot.length != channels) {
            channelSlot = new int[channels];
            drainBus = new int[channels];
            drainLeft = new int[channels];
        }
        Arrays.fill(channelSlot, -1);
        Arrays.fill(drainBus, -1);
    }

    /** Voices rendering binaurally now (fading in or out too). */
    int binauralVoices() {
        int n = 0;
        for (int slot = 0; slot < highest; slot++) {
            n += binauralChannel[slot] >= 0 ? 1 : 0;
        }
        return n;
    }

    /** Whether {@code slot} renders through the HRTF now. */
    boolean binaural(int slot) {
        return binauralChannel[slot] >= 0;
    }

    /** Voices meant to be binaural (not fading out). */
    private int binauralCount() {
        int n = 0;
        for (int slot = 0; slot < highest; slot++) {
            n += binauralChannel[slot] >= 0 && binauralTarget[slot] > 0 ? 1 : 0;
        }
        return n;
    }

    /** Gives {@code slot} a free channel, its mix starting at {@code mix}; false when none is free. */
    private boolean bind(int slot, float mix) {
        for (int c = 0; c < channelSlot.length; c++) {
            if (channelSlot[c] < 0 && drainBus[c] < 0) {
                channelSlot[c] = slot;
                binauralChannel[slot] = c;
                binauralMix[slot] = mix;
                binauralTarget[slot] = 1;
                spatializer.reset(c);
                return true;
            }
        }
        return false;
    }

    /** Takes {@code slot}'s channel back; it renders its tail (no new input) into the voice's bus first. */
    private void unbind(int slot) {
        int c = binauralChannel[slot];
        if (c >= 0) {
            channelSlot[c] = -1;
            drainBus[c] = busIndex[slot];
            drainLeft[c] = spatializer.tailBlocks();
            binauralChannel[slot] = -1;
            binauralMix[slot] = 0;
            binauralTarget[slot] = 0;
        }
    }

    /** The channels let go of render what their filters still hold, for the spatializer's tail. */
    private void drain(BusGraph graph, int frames) {
        for (int c = 0; c < drainBus.length; c++) {
            if (drainBus[c] >= 0) {
                Arrays.fill(binaural, 0, frames, 0);
                spatializer.process(c, binaural, graph.buffer(drainBus[c]), frames);
                if (--drainLeft[c] <= 0) {
                    drainBus[c] = -1;
                }
            }
        }
    }

    /** A layout swap renumbers the buses: tails still to drain are dropped. */
    void layoutChanged() {
        Arrays.fill(drainBus, -1);
        if (ambisonics != null) {
            ambisonics.clear();
        }
    }

    /** Binaural candidates now: real placed HRTF voices; the most audible {@code cap} get (or keep) a channel. */
    private void chooseBinaural() {
        if (spatializer == null) {
            return;
        }
        int n = 0;
        for (int slot = 0; slot < highest; slot++) {
            if (binauralCandidate(slot)) {
                scratch[n++] = audibility[slot];
            }
        }
        float least = n <= binauralCap ? 0 : kth(scratch, n, n - binauralCap);
        for (int slot = 0; slot < highest; slot++) {
            boolean want = binauralCandidate(slot) && audibility[slot] >= least;
            if (binauralChannel[slot] >= 0) {
                binauralTarget[slot] = want ? 1 : 0;
            }
        }
        for (int slot = 0; slot < highest; slot++) { // after the leaving ones made room
            if (binauralCandidate(slot) && audibility[slot] >= least && binauralChannel[slot] < 0
                    && binauralCount() < binauralCap && bind(slot, 0)) {
                place(slot);
            }
        }
    }

    private boolean binauralCandidate(int slot) {
        return states[slot] != FREE && (flags[slot] & (HRTF | SPATIAL)) == (HRTF | SPATIAL) && placed[slot]
            && audibility[slot] > 0 && (reality[slot] == REAL || reality[slot] == TO_REAL) && inputs[slot] < 0
            && inputPrevious[slot] < 0;
    }

    /**
     * Occludes {@code slot} (§2.11): its gain times {@code gain}, and a low-pass at {@code cutoff} Hz (0: none) on top of
     * the air's; moved to over the next block.
     */
    void occlude(int slot, float gain, float cutoff) {
        occGains[slot] = gain;
        occCutoffs[slot] = cutoff;
        if (placed[slot]) {
            place(slot);
            spatialLeft[slot] = Math.max(1, spatialLeft[slot]);
        }
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

    /** Sends {@code slot} to aux bus {@code bus} (-1 none) at {@code gain}, reached over the next block. */
    void send(int slot, int bus, float gain) {
        if (bus != auxIndex[slot]) {
            auxFrom[slot] = 0; // a new aux: from silence
        }
        auxIndex[slot] = bus;
        auxTo[slot] = gain;
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
        unbind(slot);
        states[slot] = FREE;
        attenuations[slot] = null;
        if (rings[slot] != null) {
            rings[slot].close();
            rings[slot] = null;
        }
        clips[slot] = null;
        segments[slot] = null;
        patches[slot] = null;
        inputs[slot] = -1;
        inputPrevious[slot] = -1;
        unheard(slot);
        buses[slot] = null;
        ended++;
    }

    /**
     * Sets input {@code input} of {@code slot}'s patch to {@code value} at mix frame {@code frame} (now is
     * {@code now}), ramped over {@code rampFrames}; or fires it, a trigger. Mapped to the patch's own frames through the
     * voice's position and rate.
     */
    void patchInput(int slot, int input, float value, int rampFrames, boolean trigger, long frame, long now) {
        PatchVoice patch = patches[slot];
        if (patch == null) {
            return;
        }
        long patchFrame = (long) (positions[slot] + Math.max(0, frame - Math.max(now, starts[slot])) * rates[slot]);
        if (trigger) {
            patch.trigger(input, patchFrame);
        } else {
            patch.set(input, value, patchFrame, rampFrames);
        }
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
        if (inputs[slot] >= 0 || inputPrevious[slot] >= 0) {
            return inputSample(slot, i);
        }
        if (patches[slot] != null) {
            return patches[slot].sample(i);
        }
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
        if (spatializer != null) {
            spatializer.begin();
            drain(graph, frames);
        }
        mixing = beginMixes(blockStart, frames);
        int top = 0;
        for (int pass = 0; pass < 2; pass++) {
            if (pass == 1 && mixing) {
                renderMixes(blockStart, frames);
            }
            top = renderPass(graph, blockStart, frames, events, pass == 1, top);
        }
        if (ambisonics != null) {
            ambisonics.decode(graph, frames);
        }
        if (mixing) {
            finishMixes(graph, frames);
        }
        for (int l = 0; l < LISTENERS; l++) {
            System.arraycopy(heardTo[l], 0, heardFrom[l], 0, 2 * highest);
        }
        highest = top;
    }

    private boolean mixing;

    /** One pass over the voices: clips and patches first, then those re-emitting listeners' mixes. */
    private int renderPass(BusGraph graph, long blockStart, int frames, Ring events, boolean inputPass, int top) {
        for (int slot = 0; slot < highest; slot++) {
            if (states[slot] == FREE) {
                continue;
            }
            top = Math.max(top, slot + 1);
            if ((inputs[slot] >= 0 || inputPrevious[slot] >= 0) != inputPass) {
                continue;
            }
            if (starts[slot] >= blockStart + frames) {
                continue;
            }
            states[slot] = PLAYING;
            if (placed[slot]) {
                stepSpatial(slot);
            }
            boolean alive = reality[slot] == VIRTUAL ? keepTime(slot, blockStart, frames)
                : renderVoice(slot, graph.buffer(busIndex[slot]), auxIndex[slot] < 0 ? null
                    : graph.buffer(auxIndex[slot]), blockStart, frames);
            if (!alive) {
                ended(slot, blockStart + frames, events);
            }
        }
        return top;
    }

    // ---------------------------------------------------------------- rendering listeners

    /** Sets up rendering listener {@code l} (or turns it off): channels, linear gain, device bus or -1, tap or null. */
    void listener(int l, boolean active, int channels, float gain, int bus, ListenerTap tap) {
        if (active && !mixActive[l]) {
            Arrays.fill(mixRing[l], 0);
            mixBlocks[l] = 0;
            loopGains[l] = 1;
        }
        mixActive[l] = active;
        mixChannels[l] = channels;
        mixGain[l] = gain;
        mixBus[l] = bus;
        mixTaps[l] = tap;
        if (!active) {
            for (int slot = 0; slot < capacity; slot++) {
                heardTo[l][2 * slot] = 0;
                heardTo[l][2 * slot + 1] = 0;
            }
        }
    }

    /** The order rendering listeners render in: each after those it hears through a chain. */
    void order(int count, double[] values, int from) {
        mixOrderCount = count;
        for (int i = 0; i < count; i++) {
            mixOrder[i] = (int) values[from + i];
        }
    }

    /** How loud rendering listener {@code l} hears {@code slot}, left and right (a mono mix uses the left). */
    void listen(int slot, int l, float left, float right) {
        heardTo[l][2 * slot] = left;
        heardTo[l][2 * slot + 1] = right;
        float max = 0;
        for (int k = 0; k < LISTENERS; k++) {
            max = Math.max(max, Math.max(heardTo[k][2 * slot], heardTo[k][2 * slot + 1]));
        }
        heardMax[slot] = max;
    }

    /** {@code slot} re-emits listener {@code l}'s mix from mix frame {@code now} on: a switch cross-fades 30 ms. */
    void input(int slot, int l, long now) {
        if (inputs[slot] != l && states[slot] != FREE) {
            inputPrevious[slot] = inputs[slot] >= 0 ? inputs[slot] : -1;
            inputs[slot] = l;
            inputSwitched[slot] = now;
        }
    }

    private void unheard(int slot) {
        for (int l = 0; l < LISTENERS; l++) {
            heardTo[l][2 * slot] = 0;
            heardTo[l][2 * slot + 1] = 0;
            heardFrom[l][2 * slot] = 0;
            heardFrom[l][2 * slot + 1] = 0;
        }
        heardMax[slot] = 0;
    }

    /** Voice {@code slot}'s level: its output's RMS over the last block, falling over about 100 ms. */
    float level(int slot) {
        return levels[slot];
    }

    /** Blocks rendering listener {@code l} has rendered (it renders only while something consumes it). */
    long listenerBlocks(int l) {
        return mixBlocks[l];
    }

    /** An input voice's sample at mix frame {@code i}: its listener's mix, cross-faded from the one before. */
    private float inputSample(int slot, long i) {
        float now = inputs[slot] >= 0 ? mixed(inputs[slot], i) : 0;
        if (inputPrevious[slot] < 0) {
            return now;
        }
        float t = (i - inputSwitched[slot]) / (0.03f * rate);
        if (t >= 1) {
            inputPrevious[slot] = -1;
            return now;
        }
        float t0 = Math.max(0, t);
        return mixed(inputPrevious[slot], i) * (1 - t0) + now * t0;
    }

    private float mixed(int l, long frame) {
        return mixActive[l] ? mixRing[l][(int) (frame & (MIX_RING - 1))] : 0;
    }

    /**
     * Which rendering listeners render this block (lazily: those a real voice re-emits, or routed to a device bus or a
     * tap); the others' rings fall silent for it. True when any renders.
     */
    private boolean beginMixes(long blockStart, int frames) {
        boolean any = false;
        for (int l = 0; l < LISTENERS; l++) {
            mixDone[l] = false;
            mixRenders[l] = mixActive[l] && (mixBus[l] >= 0 || mixTaps[l] != null || consumed(l, blockStart + frames));
            any |= mixRenders[l];
            if (mixActive[l] && !mixRenders[l]) {
                for (int f = 0; f < frames; f++) {
                    mixRing[l][(int) ((blockStart + f) & (MIX_RING - 1))] = 0;
                }
            }
        }
        return any;
    }

    private boolean consumed(int l, long blockEnd) {
        for (int slot = 0; slot < highest; slot++) {
            if (states[slot] != FREE && (inputs[slot] == l || inputPrevious[slot] == l) && starts[slot] < blockEnd
                && reality[slot] != VIRTUAL && (envelopes[slot] > 0 || envelopeTargets[slot] > 0)) {
                return true;
            }
        }
        return false;
    }

    /** Renders the listeners in their order (then any the order does not list yet). */
    private void renderMixes(long blockStart, int frames) {
        for (int k = 0; k < mixOrderCount; k++) {
            int l = mixOrder[k];
            if (l >= 0 && l < LISTENERS && mixRenders[l] && !mixDone[l]) {
                mix(l, blockStart, frames);
            }
        }
        for (int l = 0; l < LISTENERS; l++) {
            if (mixRenders[l] && !mixDone[l]) {
                mix(l, blockStart, frames);
            }
        }
    }

    /**
     * Mixes listener {@code l}: the voices it hears (read once in the first pass), and those re-emitting other
     * listeners' mixes, this block's when that listener rendered already, else its previous block's (a cycle: one
     * block of delay, at -6 dB times the loop's howl guard). Never its own re-emission. Then its gain and a soft clip,
     * its ring for those re-emitting it, and the howl detector.
     */
    private void mix(int l, long blockStart, int frames) {
        float[] o = mixOut[l];
        int ch = mixChannels[l];
        Arrays.fill(o, 0, frames * ch, 0);
        float[] from = heardFrom[l];
        float[] to = heardTo[l];
        for (int slot = 0; slot < highest; slot++) {
            float l0 = from[2 * slot];
            float r0 = from[2 * slot + 1];
            float l1 = to[2 * slot];
            float r1 = to[2 * slot + 1];
            if (states[slot] == FREE || starts[slot] >= blockStart + frames || l0 + r0 + l1 + r1 == 0) {
                continue;
            }
            int m = inputs[slot];
            boolean input = m >= 0 || inputPrevious[slot] >= 0;
            if (!input) {
                if (reality[slot] == VIRTUAL) {
                    continue;
                }
                add(o, ch, voiceBuffers[slot], null, 0, 1, frames, l0, r0, l1, r1);
            } else if (m != l && inputPrevious[slot] != l && m >= 0 && mixActive[m]) { // never itself
                int delay = mixDone[m] ? 0 : frames;
                float g = gains[slot] * envelopes[slot] * realGains[slot] * (delay > 0 ? 0.5f * loopGains[m] : 1);
                add(o, ch, null, mixRing[m], blockStart - delay, g, frames, l0, r0, l1, r1);
            }
        }
        float gain = mixGain[l];
        double energy = 0;
        for (int i = 0; i < frames * ch; i++) {
            float x = Math.max(-3, Math.min(3, o[i] * gain));
            float x2 = x * x;
            o[i] = x * (27 + x2) / (27 + 9 * x2); // a soft clip: what feeds back stays bounded
            energy += o[i] * o[i];
        }
        float[] ring = mixRing[l];
        for (int f = 0; f < frames; f++) {
            ring[(int) ((blockStart + f) & (MIX_RING - 1))] = ch == 2 ? 0.5f * (o[2 * f] + o[2 * f + 1]) : o[f];
        }
        // the howl detector: louder than 8 blocks ago by a fifth lowers the loops through it
        float rms = (float) Math.sqrt(energy / (frames * ch));
        int at = (int) (mixBlocks[l] & 7);
        float before = mixRms[l][at];
        mixRms[l][at] = rms;
        if (mixBlocks[l] >= 8 && rms > 1e-5f && rms > 1.2f * before) {
            loopGains[l] = Math.max(0.01f, loopGains[l] * 0.7f);
        } else if (rms < 1e-5f) {
            loopGains[l] = Math.min(1, loopGains[l] * 1.002f); // back only in silence: a howl does not regrow
        }
        mixBlocks[l]++;
        mixDone[l] = true;
    }

    /** Adds a mono source (a buffer, or a ring from mix frame {@code start}) times {@code g}, panned and ramped. */
    private static void add(float[] o, int ch, float[] buffer, float[] ring, long start, float g,
            int frames, float l0, float r0, float l1, float r1) {
        for (int f = 0; f < frames; f++) {
            float x = (buffer != null ? buffer[f] : ring[(int) ((start + f) & (MIX_RING - 1))]) * g;
            float t = (float) (f + 1) / frames;
            o[f * ch] += x * (l0 + (l1 - l0) * t);
            if (ch == 2) {
                o[f * ch + 1] += x * (r0 + (r1 - r0) * t);
            }
        }
    }

    /** Listeners routed to a device bus mix into it; tapped ones write their tap. */
    private void finishMixes(BusGraph graph, int frames) {
        for (int l = 0; l < LISTENERS; l++) {
            if (!mixRenders[l]) {
                continue;
            }
            float[] o = mixOut[l];
            int ch = mixChannels[l];
            if (mixBus[l] >= 0) {
                float[] bus = graph.buffer(mixBus[l]);
                for (int f = 0; f < frames; f++) {
                    for (int c = 0; c < channels; c++) {
                        bus[f * channels + c] += o[f * ch + Math.min(c, ch - 1)];
                    }
                }
            }
            if (mixTaps[l] != null) {
                mixTaps[l].write(o, frames);
            }
        }
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
            float a = Math.abs(gainTargets[slot]) * Math.max(envelopes[slot], envelopeTargets[slot])
                * Math.max(attTarget[slot], heardMax[slot]) * graph.gain(busIndex[slot]); // or a listener hears it
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
        chooseBinaural();
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
        unbind(slot); // out of earshot: its channel is for others
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
    private boolean renderVoice(int slot, float[] out, float[] aux, long blockStart, int frames) {
        int from = (int) Math.max(0, starts[slot] - blockStart);
        boolean loop = (flags[slot] & LOOP) != 0;
        boolean stereo = clipChannels[slot] >= 2 && (flags[slot] & SPATIAL) == 0 && channels >= 2;
        double position = positions[slot];
        double rateFrom = rates[slot];
        double rateStep = (rateTargets[slot] - rateFrom) / frames;
        boolean input = inputs[slot] >= 0 || inputPrevious[slot] >= 0;
        if (input) { // a listener's mix plays by mix frame: no pitch, no Doppler
            rateFrom = 1;
            rateStep = 0;
        }
        float[] heard = mixing && !input && heardMax[slot] > 0 ? voiceBuffers[slot] : null;
        double energy = 0;
        if (heard != null) {
            Arrays.fill(heard, 0, frames, 0);
        }
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
        float send = auxFrom[slot];
        float sendStep = (auxTo[slot] - send) / frames;
        float dry = dryGains[slot];
        float dryStep = 1 / (0.005f * rate);
        int channel = spatial ? binauralChannel[slot] : -1;
        float mix = channel >= 0 ? binauralMix[slot] : 0;
        float mixStep = channel >= 0 ? (binauralTarget[slot] - mix) / frames : 0;
        if (channel >= 0) {
            Arrays.fill(binaural, 0, frames, 0);
        }
        // an HRTF voice not binaural (past the cap, or fading out of it) goes to its bus's ambisonic field, not the pan
        boolean field = spatial && (flags[slot] & HRTF) != 0 && ambisonics != null && busIndex[slot] >= 0
            && busIndex[slot] < ambisonics.fields();
        if (field) {
            Arrays.fill(fieldInput, 0, frames, 0);
        }
        if (patches[slot] != null) { // what this block reads, with the resampler's taps
            patches[slot].fill((long) (position + Math.max(rateFrom, rateTargets[slot]) * (frames - from)) + 4);
        }
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
                // a starved stream holds what it played last, fading (dry), not whatever the ring holds
                boolean hold = ring != null && starving[slot];
                float l = (hold ? lastPlayed[2 * slot] : read(slot, position, 0, loop)) * g;
                float r = (hold ? lastPlayed[2 * slot + 1] : read(slot, position, 1, loop)) * g;
                if (ring != null && !hold) {
                    lastPlayed[2 * slot] = l / Math.max(g, 1e-30f);
                    lastPlayed[2 * slot + 1] = r / Math.max(g, 1e-30f);
                }
                energy += 0.5f * (l * l + r * r);
                out[f * channels] += l * (panFrom[o] + (panTo[o] - panFrom[o]) * t);
                out[f * channels + 1] += r * (panFrom[o + 1] + (panTo[o + 1] - panFrom[o + 1]) * t);
            } else {
                boolean hold = ring != null && starving[slot];
                float s = hold ? lastPlayed[2 * slot] : read(slot, position, 0, loop);
                if (spatial) {
                    for (int c = 1; c < clipChannels[slot]; c++) {
                        s += read(slot, position, c, loop);
                    }
                    s /= clipChannels[slot];
                    low += coef * (s - low); // the air's low-pass: one pole
                    s = low;
                }
                if (ring != null && !hold) {
                    lastPlayed[2 * slot] = s;
                }
                if (heard != null) { // what rendering listeners hear: before the device's attenuation and pan
                    heard[f] = s * gain * env * realGain * dry;
                }
                s *= g;
                energy += s * s;
                float panned = s;
                if (channel >= 0) { // through the HRTF, cross-faded with the pan while joining or leaving
                    mix = Math.max(0, Math.min(1, mix + mixStep));
                    binaural[f] = s * mix;
                    panned = s * (1 - mix);
                }
                if (field) {
                    fieldInput[f] = panned;
                } else {
                    for (int c = 0; c < channels; c++) {
                        out[f * channels + c] += panned * (panFrom[o + c] + (panTo[o + c] - panFrom[o + c]) * t);
                    }
                }
                if (aux != null) { // the reverb send: post-fader, unpanned
                    send += sendStep;
                    for (int c = 0; c < channels; c++) {
                        aux[f * channels + c] += s * send;
                    }
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
        if (field) {
            int g = slot * Ambisonics.CHANNELS;
            ambisonics.add(busIndex[slot], fieldInput, fieldGains, fieldTargets, g, frames);
            System.arraycopy(fieldTargets, g, fieldGains, g, Ambisonics.CHANNELS);
        }
        if (channel >= 0) {
            spatializer.process(channel, binaural, out, frames);
            binauralMix[slot] = mix;
            if (binauralTarget[slot] == 0 && mix <= 0) {
                unbind(slot);
            }
        }
        positions[slot] = position;
        rates[slot] = rateTargets[slot];
        gains[slot] = gain;
        envelopes[slot] = env;
        lowPass[slot] = low;
        dryGains[slot] = dry;
        // its level (a dialogue line's envelope, for a jaw): the block's RMS, falling over about 100 ms
        float rms = (float) Math.sqrt(energy / frames);
        levels[slot] = Math.max(rms, levels[slot] * (1 - Math.min(1, frames / (0.1f * rate))));
        auxFrom[slot] = auxTo[slot];
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
