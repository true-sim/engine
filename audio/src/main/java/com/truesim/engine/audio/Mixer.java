package com.truesim.engine.audio;

import com.truesim.annotations.AllowAllocation;
import com.truesim.scene.audio.Bus;
import com.truesim.scene.audio.BusLayout;
import com.truesim.scene.audio.Decibels;
import com.truesim.scene.audio.Snapshot;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * The mixer's game-thread side (§2.6): the bus layout, player volumes and snapshots. A snapshot started fades in, stays
 * at its intensity, and fades out when stopped; every frame ({@link #update}) the snapshots playing blend in priority
 * order, lowest first, FMOD's model: each moves a bus's volume (decibels from 0) or filter cutoff (from off) towards its
 * target by its intensity times its fade. The result times the player's volume goes to the renderer as each bus's
 * extra gain, and the cutoffs as its snapshot filters. Allocation-free once started.
 *
 * <pre>{@code
 * SnapshotInstance paused = mixer.start(PAUSE_MENU);
 * paused.intensity(0.5f);
 * paused.stop();
 * }</pre>
 */
public final class Mixer {

    private static final float OFF_LOW = 24_000;
    private static final float OFF_HIGH = 10;

    private final AudioRenderer.Commands commands;
    private BusLayout layout;
    private final java.util.Map<Bus, Float> players = new java.util.HashMap<>();
    private SnapshotInstance[] playing = new SnapshotInstance[0];
    private int count;
    private float[] volume = new float[0];
    private float[] lowPass = new float[0];
    private float[] highPass = new float[0];
    private float[] sentGain = new float[0];
    private float[] sentLow = new float[0];
    private float[] sentHigh = new float[0];

    /** A mixer writing through {@code commands}. */
    @AllowAllocation("made once")
    public Mixer(AudioRenderer.Commands commands) {
        this.commands = Objects.requireNonNull(commands, "commands");
        layout(commands.layout());
    }

    /** Swaps the layout (at the renderer's next block); snapshots keep playing on the buses it has. */
    @AllowAllocation("a new layout")
    public Mixer layout(BusLayout next) {
        if (next != commands.layout()) {
            commands.layout(next);
        }
        layout = next;
        int n = next.buses().size();
        volume = new float[n];
        lowPass = new float[n];
        highPass = new float[n];
        sentGain = new float[n];
        sentLow = new float[n];
        sentHigh = new float[n];
        Arrays.fill(sentGain, Float.NaN);
        Arrays.fill(sentLow, Float.NaN);
        Arrays.fill(sentHigh, Float.NaN);
        return this;
    }

    public BusLayout layout() {
        return layout;
    }

    /** Sets {@code bus}'s player volume, linear (a setting): on top of the layout and the snapshots. */
    @AllowAllocation("a bus's first player volume")
    public void playerVolume(Bus bus, float gain) {
        players.put(bus, gain);
    }

    /** Starts {@code snapshot} at intensity 1, fading in over its fade-in. */
    @AllowAllocation("a handle per snapshot started")
    public SnapshotInstance start(Snapshot snapshot) {
        snapshot.check(layout);
        SnapshotInstance s = new SnapshotInstance(snapshot);
        if (count == playing.length) {
            playing = Arrays.copyOf(playing, Math.max(4, 2 * count));
        }
        int i = count++;
        while (i > 0 && playing[i - 1].snapshot().priority() > snapshot.priority()) {
            playing[i] = playing[i - 1];
            i--;
        }
        playing[i] = s;
        return s;
    }

    /** Snapshots playing (fading out too). */
    public int playing() {
        return count;
    }

    /** Moves fades on by {@code seconds}, blends the snapshots and sends what changed. Allocates nothing. */
    public void update(double seconds) {
        for (int i = 0; i < count; i++) {
            playing[i].advance(seconds);
        }
        for (int i = 0; i < count; ) { // the ones faded out leave
            if (playing[i].done()) {
                System.arraycopy(playing, i + 1, playing, i, count - i - 1);
                playing[--count] = null;
            } else {
                i++;
            }
        }
        Arrays.fill(volume, 0);
        Arrays.fill(lowPass, OFF_LOW);
        Arrays.fill(highPass, OFF_HIGH);
        for (int i = 0; i < count; i++) {
            SnapshotInstance s = playing[i];
            float w = s.weight();
            List<Snapshot.Target> targets = s.snapshot().targets();
            for (int t = 0; t < targets.size(); t++) {
                Snapshot.Target target = targets.get(t);
                int b = layout.indexOf(target.bus());
                switch (target.property()) {
                    case VOLUME -> volume[b] += (target.value() - volume[b]) * w;
                    case LOW_PASS -> lowPass[b] += (target.value() - lowPass[b]) * w;
                    case HIGH_PASS -> highPass[b] += (target.value() - highPass[b]) * w;
                }
            }
        }
        List<BusLayout.Settings> buses = layout.buses();
        for (int b = 0; b < volume.length; b++) {
            Bus bus = buses.get(b).bus();
            Float player = players.get(bus);
            float gain = Decibels.gain(volume[b]) * (player == null ? 1 : player);
            if (gain != sentGain[b]) {
                sentGain[b] = gain;
                commands.busGain(bus, gain);
            }
            float low = lowPass[b] >= OFF_LOW - 1 ? 0 : lowPass[b];
            float high = highPass[b] <= OFF_HIGH + 1 ? 0 : highPass[b];
            if (low != sentLow[b] || high != sentHigh[b]) {
                sentLow[b] = low;
                sentHigh[b] = high;
                commands.busFilters(bus, low, high);
            }
        }
    }

    /** {@code bus}'s blended snapshot volume in decibels after the last update. */
    public float snapshotVolume(Bus bus) {
        return volume[layout.indexOf(bus)];
    }

    /** {@code bus}'s blended snapshot low-pass cutoff after the last update; 0 when off. */
    public float snapshotLowPass(Bus bus) {
        return sentLow[layout.indexOf(bus)];
    }

    /** A playing snapshot: its intensity and its stop. */
    public static final class SnapshotInstance {
        private final Snapshot snapshot;
        private float intensity = 1;
        private float fade;
        private boolean stopping;

        private SnapshotInstance(Snapshot snapshot) {
            this.snapshot = snapshot;
        }

        public Snapshot snapshot() {
            return snapshot;
        }

        /** Sets how much of it applies, 0 to 1. */
        public SnapshotInstance intensity(float value) {
            intensity = Math.max(0, Math.min(1, value));
            return this;
        }

        public float intensity() {
            return intensity;
        }

        /** Fades it out over its fade-out; it leaves once faded. */
        public void stop() {
            stopping = true;
        }

        /** How far faded in, 0 to 1. */
        public float fade() {
            return fade;
        }

        void advance(double seconds) {
            double length = stopping ? snapshot.fadeOut() : snapshot.fadeIn();
            float step = length <= 0 ? 1 : (float) (seconds / length);
            fade = stopping ? Math.max(0, fade - step) : Math.min(1, fade + step);
        }

        boolean done() {
            return stopping && fade == 0;
        }

        float weight() {
            return intensity * fade;
        }
    }
}
