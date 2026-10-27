package com.truesim.engine.platform;

import com.truesim.annotations.AllowAllocation;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * A force feedback effect for a {@link Haptic} device, e.g. a wheel's road feel or a joystick's recoil. Levels and
 * magnitudes are from -1 to 1 (or 0 to 1 where noted), times in milliseconds. Upload an effect with
 * {@link Haptic#create}, then run, update and stop it by its id.
 *
 * <pre>{@code
 * int kick = haptic.create(new HapticEffect.Constant(Direction.polar(180), Timing.of(150), 0.8f, Envelope.NONE));
 * haptic.run(kick, 1);
 * }</pre>
 */
public sealed interface HapticEffect {

    /** A length or iteration count that never ends. */
    int INFINITE = -1;

    /** The {@link HapticFeature} a device needs for this effect. */
    HapticFeature feature();

    /** Where a force comes from. */
    record Direction(Kind kind, float x, float y, float z) {

        public enum Kind {
            /** {@code x} degrees clockwise from north (away from the user). */
            POLAR,
            /** A vector ({@code x}, {@code y}, {@code z}): x to the right, y towards the user, z up. */
            CARTESIAN,
            /** {@code x} degrees of azimuth, {@code y} degrees of elevation. */
            SPHERICAL,
            /** Along a wheel's steering axis; positive is to the right. */
            STEERING_AXIS
        }

        public static Direction polar(float degrees) {
            return new Direction(Kind.POLAR, degrees, 0, 0);
        }

        public static Direction cartesian(float x, float y, float z) {
            return new Direction(Kind.CARTESIAN, x, y, z);
        }

        public static Direction spherical(float azimuth, float elevation) {
            return new Direction(Kind.SPHERICAL, azimuth, elevation, 0);
        }

        public static Direction steering() {
            return new Direction(Kind.STEERING_AXIS, 0, 0, 0);
        }
    }

    /**
     * When an effect plays: for {@code length} ms ({@link #INFINITE} for ever) after {@code delay} ms; with a
     * {@code button} (from 1) it starts when that button is pressed, at most every {@code interval} ms.
     */
    record Timing(int length, int delay, int button, int interval) {

        public static Timing of(int length) {
            return new Timing(length, 0, 0, 0);
        }
    }

    /** Fades the effect in over {@code attack} ms from {@code attackLevel}, and out over {@code fade} ms. 0 to 1. */
    record Envelope(int attack, float attackLevel, int fade, float fadeLevel) {

        public static final Envelope NONE = new Envelope(0, 0, 0, 0);
    }

    /** A steady force of {@code level}. */
    record Constant(Direction direction, Timing timing, float level, Envelope envelope) implements HapticEffect {

        @Override
        public HapticFeature feature() {
            return HapticFeature.CONSTANT;
        }
    }

    /** The shape of a {@link Periodic} wave. */
    enum Waveform {
        SINE, SQUARE, TRIANGLE, SAWTOOTH_UP, SAWTOOTH_DOWN
    }

    /** A wave with {@code period} ms, {@code magnitude}, {@code offset} and {@code phase} degrees, e.g. an engine. */
    record Periodic(Waveform waveform, Direction direction, Timing timing, int period, float magnitude, float offset,
        float phase, Envelope envelope) implements HapticEffect {

        @Override
        public HapticFeature feature() {
            return switch (waveform) {
                case SINE -> HapticFeature.SINE;
                case SQUARE -> HapticFeature.SQUARE;
                case TRIANGLE -> HapticFeature.TRIANGLE;
                case SAWTOOTH_UP -> HapticFeature.SAWTOOTH_UP;
                case SAWTOOTH_DOWN -> HapticFeature.SAWTOOTH_DOWN;
            };
        }
    }

    /** What a {@link Condition} reacts to. */
    enum ConditionKind {
        /** The position: pulls back to the center, like a spring. */
        SPRING,
        /** The velocity, like moving through a fluid. */
        DAMPER,
        /** The acceleration, like moving a mass. */
        INERTIA,
        /** Movement at all, like rubbing. */
        FRICTION
    }

    /**
     * One axis of a {@link Condition}: the force on either side of {@code center} (-1 to 1) grows with
     * {@code coefficient} (-1 to 1) up to {@code saturation} (0 to 1), with no force within {@code deadband} (0 to 1).
     */
    record ConditionAxis(float rightSaturation, float leftSaturation, float rightCoefficient, float leftCoefficient,
        float deadband, float center) {
    }

    /** A force that depends on the device's own movement, per axis (up to 3), e.g. a wheel's centering spring. */
    record Condition(ConditionKind kind, Timing timing, List<ConditionAxis> axes) implements HapticEffect {

        public Condition {
            if (axes.isEmpty() || axes.size() > 3) {
                throw new IllegalArgumentException("A condition has 1 to 3 axes: " + axes.size());
            }
            axes = List.copyOf(axes);
        }

        @Override
        public HapticFeature feature() {
            return switch (kind) {
                case SPRING -> HapticFeature.SPRING;
                case DAMPER -> HapticFeature.DAMPER;
                case INERTIA -> HapticFeature.INERTIA;
                case FRICTION -> HapticFeature.FRICTION;
            };
        }
    }

    /** A force going from {@code start} to {@code end} over the effect's length. */
    record Ramp(Direction direction, Timing timing, float start, float end, Envelope envelope) implements HapticEffect {

        @Override
        public HapticFeature feature() {
            return HapticFeature.RAMP;
        }
    }

    /** Rumble with a {@code large} (low frequency) and {@code small} (high frequency) motor, 0 to 1. */
    record LeftRight(int length, float large, float small) implements HapticEffect {

        @Override
        public HapticFeature feature() {
            return HapticFeature.LEFT_RIGHT;
        }
    }

    /**
     * Force samples played every {@code period} ms, interleaved for {@code channels} axes, from -1 to 1.
     *
     * @param samples copied, so later changes do not affect the effect
     */
    record Custom(Direction direction, Timing timing, int channels, int period, float[] samples, Envelope envelope)
        implements HapticEffect {

        public Custom {
            if (channels < 1 || samples.length == 0 || samples.length % channels != 0) {
                throw new IllegalArgumentException(samples.length + " samples do not fit " + channels + " channels");
            }
            samples = samples.clone();
        }

        @Override
        public float[] samples() {
            return samples.clone();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Custom c && direction.equals(c.direction) && timing.equals(c.timing)
                && channels == c.channels && period == c.period && Arrays.equals(samples, c.samples)
                && envelope.equals(c.envelope);
        }

        @Override
        @AllowAllocation("hashing a value")
        public int hashCode() {
            return Objects.hash(direction, timing, channels, period, Arrays.hashCode(samples), envelope);
        }

        @Override
        @AllowAllocation("diagnostics")
        public String toString() {
            return "Custom[" + direction + ", " + timing + ", " + channels + " channels, period " + period + ", "
                + samples.length + " samples, " + envelope + "]";
        }

        @Override
        public HapticFeature feature() {
            return HapticFeature.CUSTOM;
        }
    }
}
