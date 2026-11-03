package com.truesim.engine.audio;

import com.truesim.annotations.AllowAllocation;
import com.truesim.scene.audio.Bus;
import com.truesim.scene.audio.BusLayout;
import com.truesim.scene.audio.Listeners;
import com.truesim.scene.audio.Sounds;

/**
 * The audio debug overlay (§6 step 20) as text lines a game's HUD or console draws: real, virtual, binaural voices and
 * ambisonic fields; the output's underruns; each bus's meter (peak and RMS in dB, with a bar); the rendering
 * listeners (blocks rendered) and a store's listener graph (its order, edges, delayed and cut edges). The engine draws
 * no text itself, so this is the overlay's content, not its drawing. {@link #update()} rewrites the lines in place:
 * allocation-free once its builders have grown, on the game thread (meters and counts are read as the audio thread
 * last wrote them).
 */
public final class AudioOverlay {

    private static final int MAX_LINES = 40;
    private static final int BAR = 24;

    private final AudioRenderer renderer;
    private final AudioOutput output;
    private final StringBuilder[] lines = new StringBuilder[MAX_LINES];
    private Sounds sounds;
    private int count;

    /** An overlay of {@code renderer}, with {@code output}'s underruns (null: none). */
    @AllowAllocation("made once")
    public AudioOverlay(AudioRenderer renderer, AudioOutput output) {
        this.renderer = renderer;
        this.output = output;
        for (int i = 0; i < MAX_LINES; i++) {
            lines[i] = new StringBuilder(96);
        }
    }

    /** Shows {@code store}'s listener graph too (null: none). */
    public AudioOverlay listeners(Sounds store) {
        sounds = store;
        return this;
    }

    /** Rewrites the lines; returns how many there are. */
    public int update() {
        count = 0;
        StringBuilder s = next();
        s.append("audio: ").append(renderer.realVoices()).append(" real, ").append(renderer.virtualVoices())
            .append(" virtual, ").append(renderer.binauralVoices()).append(" binaural");
        if (output != null) {
            s.append(", ").append(output.underruns()).append(" underruns");
        }
        s.append(", ").append(renderer.starvations()).append(" starved");
        BusLayout layout = renderer.commands().layout();
        for (int i = 0; i < layout.buses().size() && count < MAX_LINES - 8; i++) {
            Bus bus = layout.buses().get(i).bus();
            meter(next(), bus);
        }
        s = next().append("rendering listeners:");
        for (int l = 0; l < Voices.LISTENERS; l++) {
            s.append(' ').append(l).append('=').append(renderer.listenerBlocks(l));
        }
        if (sounds != null && !sounds.isUi()) {
            Listeners listeners = sounds.listeners();
            s = next().append("listener graph: order");
            for (int i = 0; i < listeners.orderCount(); i++) {
                s.append(' ').append((int) listeners.order(i));
            }
            s.append(", ").append(listeners.cycles()).append(" delayed, ").append(listeners.cuts()).append(" cut");
        }
        return count;
    }

    /** A bus's meter: name, peak and RMS in whole dB, a bar of its peak from -60 dB. */
    private void meter(StringBuilder s, Bus bus) {
        float peak = renderer.meterPeak(bus);
        float rms = renderer.meterRms(bus);
        int peakDb = decibels(peak);
        int rmsDb = decibels(rms);
        s.append("  ").append(bus.name());
        for (int pad = bus.name().length(); pad < 10; pad++) {
            s.append(' ');
        }
        int filled = Math.max(0, Math.min(BAR, (peakDb + 60) * BAR / 60));
        s.append('[');
        for (int i = 0; i < BAR; i++) {
            s.append(i < filled ? '#' : '.');
        }
        s.append("] peak ");
        appendDb(s, peakDb);
        s.append(" rms ");
        appendDb(s, rmsDb);
    }

    private static void appendDb(StringBuilder s, int db) {
        if (db <= -120) {
            s.append("-inf");
        } else {
            s.append(db);
        }
        s.append(" dB");
    }

    /** Whole decibels of a linear level, -120 for silence. */
    static int decibels(float level) {
        return level <= 1e-6f ? -120 : (int) Math.floor(20 * com.truesim.common.math.Maths.log10(level) + 0.5);
    }

    private StringBuilder next() {
        StringBuilder s = lines[count++];
        s.setLength(0);
        return s;
    }

    /** Lines from the last {@link #update()}. */
    public int lines() {
        return count;
    }

    /** Line {@code i}. */
    public CharSequence line(int i) {
        return lines[i];
    }

    /** All lines, joined: for tools and logs. */
    @AllowAllocation("a string for tools")
    public String text() {
        StringBuilder all = new StringBuilder();
        for (int i = 0; i < count; i++) {
            all.append(lines[i]).append('\n');
        }
        return all.toString();
    }
}
