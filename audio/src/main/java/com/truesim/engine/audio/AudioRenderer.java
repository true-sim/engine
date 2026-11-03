package com.truesim.engine.audio;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.audio.dsp.PatchVoice;
import com.truesim.scene.audio.Attenuation;
import com.truesim.scene.audio.AudioClip;
import com.truesim.scene.audio.Bus;
import com.truesim.scene.audio.BusLayout;
import java.util.Objects;

/**
 * The audio thread's work, in pure Java (AU2): commands in, blocks out. The game side writes commands through
 * {@link #commands()} (one producer: the game thread's {@code AudioSync}, or a test); the audio thread calls
 * {@link #render} for each block, which takes the commands waiting, mixes the voices into the buses, the buses into
 * the master and limits it; events (a voice ended) go back to the game through {@link #poll}. Offline, a test or a
 * tool calls {@link #render} itself (AU10: the same commands, block size and rate give the same floats, bit for bit).
 *
 * <pre>{@code
 * try (AudioRenderer r = AudioRenderer.offline(AudioFormat.stereo(48_000))) {
 *     r.commands().play(0, clip, 4_800, 0, 1, 1, Bus.SFX, 0, 0, tag);   // at frame 4,800
 *     float[] second = r.render(48_000);
 * }
 * }</pre>
 *
 * <p>Voices are slots chosen by the producer (first fit, so offline renders are reproducible); a slot is free again
 * once its ENDED event was polled. Timing is in output frames: a voice stamped at frame F starts at output sample F,
 * the limiter's look-ahead accounted for. {@link #render(float[], int, int)} allocates nothing; it must run on one
 * thread at a time.
 */
public final class AudioRenderer implements AutoCloseable {

    /** Event: voice {@code slot} ended (its clip played out, or it was stopped or killed); its tag says whose it was. */
    public static final int ENDED = 1;

    /** Logical voices by default (AU7: 512 per world). */
    public static final int DEFAULT_VOICES = 1024;

    static final int PLAY = 1;
    static final int GAIN = 2;
    static final int PITCH = 3;
    static final int STOP = 4;
    static final int KILL = 5;
    static final int PAUSE = 6;
    static final int PAN = 7;
    static final int LAYOUT = 8;
    static final int BUS_GAIN = 9;
    static final int SPATIAL = 10;
    static final int BUDGET = 11;
    static final int STREAM = 12;
    static final int BUS_FILTER = 13;
    static final int ACCESS = 14;
    static final int SEND = 15;
    static final int REVERB = 16;
    static final int PATCH = 17;
    static final int PATCH_INPUT = 18;

    /** Receives events. */
    @FunctionalInterface
    public interface EventVisitor {
        /** Event {@code kind} for voice {@code slot}, of tag {@code tag}, at output frame {@code frame}. */
        void event(int kind, int slot, long tag, long frame);
    }

    private final AudioFormat format;
    private final Ring commands;
    private final Ring events;
    private final Voices voices;
    private final Limiter limiter;
    private final Accessibility access;
    private final RenderClock clock;
    private final Commands producer;
    private final float[] block;
    private StreamRing pendingRing;
    private PatchVoice pendingPatch;
    private BusGraph graph;
    private long frame;
    /** Frames of {@link #block} not handed out yet: renders of any length are cut from whole blocks. */
    private int left;
    private volatile boolean closed;

    /** A renderer of {@code format} with {@code voices} slots. */
    @AllowAllocation("made once")
    public AudioRenderer(AudioFormat format, int voices) {
        this.format = Objects.requireNonNull(format, "format");
        commands = new Ring(1 << 15);
        events = new Ring(1 << 13);
        this.voices = new Voices(voices, format.channels(), format.rate());
        limiter = new Limiter(format.rate(), format.channels());
        access = new Accessibility(format.rate(), format.channels());
        clock = new RenderClock(format.rate(), limiter.latency());
        block = new float[format.block() * format.channels()];
        graph = new BusGraph(BusLayout.DEFAULT, format.rate(), format.channels(), format.block());
        producer = new Commands();
    }

    /** An offline renderer of {@code format}: no device and no threads; render it yourself. */
    @AllowAllocation("made once")
    public static AudioRenderer offline(AudioFormat format) {
        return new AudioRenderer(format, DEFAULT_VOICES);
    }

    public AudioFormat format() {
        return format;
    }

    /** The audio clock it publishes. */
    public RenderClock clock() {
        return clock;
    }

    /** The commands' producer side: used by one thread. */
    public Commands commands() {
        return producer;
    }

    /** {@code bus}'s peak over the last meter period (about 1/30 s), linear; 0 for a bus the layout lacks. */
    public float meterPeak(Bus bus) {
        BusGraph g = graph;
        int i = g.layout().indexOf(bus);
        return i < 0 ? 0 : g.meterPeak(i);
    }

    /** {@code bus}'s RMS over the last meter period, linear. */
    public float meterRms(Bus bus) {
        BusGraph g = graph;
        int i = g.layout().indexOf(bus);
        return i < 0 ? 0 : g.meterRms(i);
    }

    /** The gain {@code bus}'s ducks apply now (1 when not ducked). */
    public float duckGain(Bus bus) {
        BusGraph g = graph;
        int i = g.layout().indexOf(bus);
        return i < 0 ? 1 : g.duckGain(i);
    }

    /** {@code bus}'s reverb's decay now, seconds (0 without one): for tests, on the audio thread. */
    float reverbDecay(Bus bus) {
        int i = graph.layout().indexOf(bus);
        return i < 0 ? 0 : graph.reverbDecay(i);
    }

    /** Times a streamed voice ran dry. */
    public int starvations() {
        return voices.starvations();
    }

    /** Voices real at the last choice (every 4 blocks). */
    public int realVoices() {
        return voices.real();
    }

    /** Voices virtual at the last choice. */
    public int virtualVoices() {
        return voices.virtual();
    }

    /** Whether voice {@code slot} is real now (fading in or out too): for tests and meters, on the audio thread. */
    boolean isReal(int slot) {
        return voices.isReal(slot);
    }

    /** Where voice {@code slot} is in its clip, in clip frames: for tests, on the audio thread. */
    double position(int slot) {
        return voices.position(slot);
    }

    /** How many voice slots it has. */
    public int voices() {
        return voices.capacity();
    }

    /** Frames the limiter delays the mix by; starts are moved earlier by as much. */
    public int latency() {
        return limiter.latency();
    }

    /** Output frames rendered so far. */
    public long frame() {
        return frame;
    }

    /** Hands the events waiting to {@code visitor}, on the game thread; returns how many. Allocates nothing. */
    public int poll(EventVisitor visitor) {
        int n = 0;
        for (int e = events.peek(); e >= 0; e = events.peek()) {
            visitor.event(events.opcodes[e], events.slots[e], events.tags[e], events.frames[e]);
            events.release();
            n++;
        }
        return n;
    }

    /** Renders {@code frames} into a new array: for tools and tests. */
    @AllowAllocation("a buffer for tools and tests")
    public float[] render(int frames) {
        float[] out = new float[frames * format.channels()];
        render(out, 0, frames);
        return out;
    }

    /**
     * Renders {@code frames} frames, interleaved, into {@code out} from frame {@code offset}: blocks are always
     * rendered whole, and what a call does not take waits for the next. On the audio thread, or offline; allocates
     * nothing.
     */
    public void render(float[] out, int offset, int frames) {
        int size = format.block();
        int channels = format.channels();
        for (int done = 0; done < frames; ) {
            if (left == 0) {
                renderBlock();
                left = size;
            }
            int n = Math.min(left, frames - done);
            System.arraycopy(block, (size - left) * channels, out, (offset + done) * channels, n * channels);
            left -= n;
            done += n;
        }
    }

    /** Renders one block into {@link #block()}: commands, voices, buses, limiter, clock. */
    void renderBlock() {
        drain();
        graph.clear();
        voices.render(graph, frame, format.block(), events);
        graph.process();
        System.arraycopy(graph.mix(), 0, block, 0, block.length);
        access.process(block, format.block());
        limiter.process(block, format.block());
        frame += format.block();
        clock.publish(frame, System.nanoTime(), 0);
    }

    /**
     * Renders a block into {@code scratch} to warm the JIT up before a device starts (§2.3: about 200, so the audio
     * thread never runs interpreted code for long). The blocks count: the clock moves on.
     */
    void warmUp(float[] scratch) {
        render(scratch, 0, format.block());
    }

    /** The last block rendered, interleaved. */
    float[] block() {
        return block;
    }

    /** Applies the commands waiting, in order. */
    private void drain() {
        Ring r = commands;
        int latency = limiter.latency();
        for (int c = r.peek(); c >= 0; c = r.peek()) {
            int slot = r.slots[c];
            int v = c * Ring.VALUES;
            double[] values = r.values;
            switch (r.opcodes[c]) {
                case PLAY -> {
                    Bus bus = (Bus) r.refs[c];
                    voices.play(slot, (AudioClip) r.refs2[c], Math.max(frame, r.frames[c] - latency), values[v + 2],
                        (float) values[v], (float) values[v + 1], bus, graph.index(bus), (int) values[v + 3],
                        (float) values[v + 4], r.tags[c], pendingRing, pendingPatch);
                    pendingRing = null;
                    pendingPatch = null;
                }
                case PATCH -> pendingPatch = (PatchVoice) r.refs[c];
                case PATCH_INPUT -> voices.patchInput(slot, (int) values[v], (float) values[v + 1], (int) values[v + 2],
                    values[v + 3] != 0, r.frames[c] - latency, frame);
                case STREAM -> pendingRing = (StreamRing) r.refs[c];
                case BUS_FILTER -> graph.filters(slot, (float) values[v], (float) values[v + 1]);
                case ACCESS -> access.set(values[v] != 0, (float) values[v + 1], (int) values[v + 2]);
                case SEND -> voices.send(slot, (int) values[v], (float) values[v + 1]);
                case REVERB -> graph.reverb(slot, (float) values[v], (float) values[v + 1]);
                case GAIN -> voices.gain(slot, (float) values[v], (int) values[v + 1]);
                case PITCH -> voices.pitch(slot, (float) values[v]);
                case PAN -> voices.pan(slot, (float) values[v]);
                case STOP -> voices.stop(slot, r.frames[c] - latency, (int) values[v], frame, events);
                case KILL -> voices.stop(slot, frame, (int) (0.01 * format.rate()), frame, events);
                case PAUSE -> voices.pause(slot, values[v] != 0, frame);
                case LAYOUT -> layout((BusGraph) r.refs[c]);
                case BUS_GAIN -> graph.extraGain(slot, (float) values[v]);
                case SPATIAL -> voices.spatial(slot, (float) values[v], (float) values[v + 1], (float) values[v + 2],
                    (float) values[v + 3], (Attenuation) r.refs[c], values[v + 4] != 0);
                case BUDGET -> voices.budget(slot);
                default -> throw new IllegalStateException("Unknown audio command " + r.opcodes[c]);
            }
            r.release();
        }
    }

    private void layout(BusGraph next) {
        for (int slot = 0; slot < voices.highest(); slot++) {
            if (!voices.free(slot)) {
                voices.busIndex[slot] = next.index(voices.buses[slot]);
            }
        }
        graph = next;
    }

    @Override
    public void close() {
        closed = true;
    }

    /** Whether it was closed. */
    public boolean closed() {
        return closed;
    }

    /**
     * The commands' producer side (§2.3): fixed-size records into the ring, stamped in output frames. Methods return
     * false when the ring is full (counted; a start is then the caller's to retry). One producer thread; allocates
     * nothing.
     */
    public final class Commands {

        private BusGraph sent = graph;

        private Commands() {
        }

        /**
         * Starts {@code clip} in voice {@code slot} at output frame {@code frame} ({@code offset} seconds in) with
         * {@code gain} and {@code pitch}, into {@code bus}, with {@link Voices} flags (loop, spatial), a 2D
         * {@code pan}, and {@code tag} for its events.
         */
        public boolean play(int slot, AudioClip clip, long frame, double offset, float gain, float pitch, Bus bus,
                int flags, float pan, long tag) {
            int c = claim(PLAY, slot, frame);
            if (c < 0) {
                return false;
            }
            int v = c * Ring.VALUES;
            commands.values[v] = gain;
            commands.values[v + 1] = pitch;
            commands.values[v + 2] = offset;
            commands.values[v + 3] = flags;
            commands.values[v + 4] = pan;
            commands.tags[c] = tag;
            commands.refs[c] = bus;
            commands.refs2[c] = clip;
            commands.publish();
            return true;
        }

        /** The next {@link #play} plays {@code patch} (its clip ignored, may be null): a procedural voice. */
        public boolean patch(PatchVoice patch) {
            int c = claim(PATCH, 0, 0);
            if (c < 0) {
                return false;
            }
            commands.refs[c] = patch;
            commands.publish();
            return true;
        }

        /**
         * Sets input {@code input} of the patch voice in {@code slot} to {@code value} at output frame {@code frame},
         * ramped over {@code seconds}; with {@code trigger}, fires it at that frame instead.
         */
        public boolean patchInput(int slot, int input, float value, double seconds, boolean trigger, long frame) {
            int c = claim(PATCH_INPUT, slot, frame);
            if (c < 0) {
                return false;
            }
            int v = c * Ring.VALUES;
            commands.values[v] = input;
            commands.values[v + 1] = value;
            commands.values[v + 2] = Math.round(seconds * format.rate());
            commands.values[v + 3] = trigger ? 1 : 0;
            commands.publish();
            return true;
        }

        /** The next {@link #play} reads {@code ring} instead of the clip's memory: a streamed voice. */
        boolean stream(StreamRing ring) {
            int c = claim(STREAM, 0, 0);
            if (c < 0) {
                return false;
            }
            commands.refs[c] = ring;
            commands.publish();
            return true;
        }

        /** Moves voice {@code slot}'s gain to {@code gain} over {@code seconds}. */
        public boolean gain(int slot, float gain, double seconds) {
            return send(GAIN, slot, 0, gain, Math.round(seconds * format.rate()));
        }

        /** Sets voice {@code slot}'s pitch, reached over a block. */
        public boolean pitch(int slot, float pitch) {
            return send(PITCH, slot, 0, pitch, 0);
        }

        /** Sets voice {@code slot}'s 2D pan, −1 to 1. */
        public boolean pan(int slot, float pan) {
            return send(PAN, slot, 0, pan, 0);
        }

        /** Fades voice {@code slot} out over {@code seconds} from output frame {@code frame}, then ends it. */
        public boolean stop(int slot, long frame, double seconds) {
            return send(STOP, slot, frame, Math.round(seconds * format.rate()), 0);
        }

        /** Ends voice {@code slot} now, with a 10 ms fade. */
        public boolean kill(int slot) {
            return send(KILL, slot, 0, 0, 0);
        }

        /** Pauses or resumes voice {@code slot} (10 ms fades). */
        public boolean pause(int slot, boolean pause) {
            return send(PAUSE, slot, 0, pause ? 1 : 0, 0);
        }

        /** Swaps the bus layout at the next block: its graph is built here, on the producer's thread. */
        @AllowAllocation("a new layout")
        public boolean layout(BusLayout layout) {
            BusGraph next = new BusGraph(layout, format.rate(), format.channels(), format.block());
            int c = claim(LAYOUT, 0, 0);
            if (c < 0) {
                return false;
            }
            commands.refs[c] = next;
            commands.publish();
            sent = next;
            return true;
        }

        /** The layout last sent. */
        public BusLayout layout() {
            return sent.layout();
        }

        /**
         * Places voice {@code slot} at ({@code x}, {@code y}, {@code z}) relative to its listener (x right, y up, -z
         * ahead, metres), attenuated by {@code attenuation}, at Doppler ratio {@code doppler}; {@code crossfade} when
         * its listener changed, so the change ramps over 50 ms.
         */
        public boolean spatial(int slot, float x, float y, float z, float doppler, Attenuation attenuation,
                boolean crossfade) {
            int c = claim(SPATIAL, slot, 0);
            if (c < 0) {
                return false;
            }
            int v = c * Ring.VALUES;
            commands.values[v] = x;
            commands.values[v + 1] = y;
            commands.values[v + 2] = z;
            commands.values[v + 3] = doppler;
            commands.values[v + 4] = crossfade ? 1 : 0;
            commands.refs[c] = attenuation;
            commands.publish();
            return true;
        }

        /** Sets {@code bus}'s snapshot filters: a low-pass and a high-pass cutoff in Hz, each off at 0. */
        public boolean busFilters(Bus bus, float lowPass, float highPass) {
            return send(BUS_FILTER, sent.index(bus), 0, lowPass, highPass, 0);
        }

        /**
         * Sets the master's accessibility stage (§2.17): {@code mono} downmix, {@code balance} from -1 (left) to 1, and
         * the dynamic range preset (0 full, 1 home, 2 night).
         */
        public boolean accessibility(boolean mono, float balance, int dynamicRange) {
            return send(ACCESS, 0, 0, mono ? 1 : 0, balance, dynamicRange);
        }

        /** Sends voice {@code slot} to aux bus {@code aux} ({@code null}: none) at {@code gain}. */
        public boolean send(int slot, Bus aux, float gain) {
            int bus = aux == null || !sent.layout().contains(aux) ? -1 : sent.index(aux);
            return send(SEND, slot, 0, bus, gain);
        }

        /** Sets the decay (seconds) and damping of {@code aux}'s reverb: a zone's blended in. */
        public boolean reverb(Bus aux, float decay, float damping) {
            return send(REVERB, sent.index(aux), 0, decay, damping);
        }

        /** Sets the real voices at most (AU7: 64 by default). */
        public boolean budget(int voices) {
            return send(BUDGET, voices, 0, 0, 0);
        }

        /** Sets {@code bus}'s extra gain (a player volume, a snapshot), reached over a block. */
        public boolean busGain(Bus bus, float gain) {
            return send(BUS_GAIN, sent.index(bus), 0, gain, 0);
        }

        private boolean send(int opcode, int slot, long frame, double a, double b) {
            return send(opcode, slot, frame, a, b, 0);
        }

        private boolean send(int opcode, int slot, long frame, double a, double b, double c3) {
            int c = claim(opcode, slot, frame);
            if (c < 0) {
                return false;
            }
            commands.values[c * Ring.VALUES] = a;
            commands.values[c * Ring.VALUES + 1] = b;
            commands.values[c * Ring.VALUES + 2] = c3;
            commands.publish();
            return true;
        }

        private int claim(int opcode, int slot, long frame) {
            int c = commands.claim();
            if (c >= 0) {
                commands.opcodes[c] = opcode;
                commands.slots[c] = slot;
                commands.frames[c] = frame;
            }
            return c;
        }

        /** Commands that found the ring full. */
        public long overflows() {
            return commands.overflows();
        }
    }
}
