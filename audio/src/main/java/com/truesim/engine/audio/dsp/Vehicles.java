package com.truesim.engine.audio.dsp;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.audio.dsp.FiringPulses.FiringOrder;
import com.truesim.engine.audio.dsp.Patch.Signal;
import com.truesim.scene.audio.SoundParameter;

/**
 * Procedural vehicle engines (§2.13, §3.7): the V8 patch, reading {@link #RPM}, {@link #LOAD} and the {@link #SHIFT}
 * trigger. Firing pulses of a cross-plane V8 (rpm / 60 × 4 a second: 200 at 3,000 rpm) through a 1.6 m exhaust tube
 * and a muffler whose cutoff opens with load; intake noise gated by the firing; a mechanical whine at 4 × the crank;
 * overrun crackle through a short tube when the load is below -0.2; a shift dips the load for about 0.15 s.
 */
public final class Vehicles {

    /** Crank speed, rpm. */
    public static final SoundParameter RPM = SoundParameter.of("rpm", 800, 0, 9_000);
    /** Load: -1 overrun to 1 full throttle. */
    public static final SoundParameter LOAD = SoundParameter.of("load", 0, -1, 1);
    /** A gear shift. */
    public static final SoundParameter SHIFT = SoundParameter.trigger("shift");

    private Vehicles() {
    }

    /** The V8 patch, registered as {@code "v8"}. */
    @AllowAllocation("built once")
    public static Patch v8() {
        return Patch.builder("v8").input(RPM).input(LOAD).trigger(SHIFT).graph(g -> {
            Signal rpm = g.param(RPM).smooth(0.02);
            Signal load = g.param(LOAD).smooth(0.02);
            Signal shift = g.add(Envelope::of).in(g.trigger(SHIFT)).set("attack", 0.01).set("decay", 0.12);
            Signal hardness = load.remap(-1, 1, 0.3, 1).times(shift.times(-0.6).plus(1));
            Signal firing = g.add(() -> FiringPulses.fourStroke(8, FiringOrder.CROSS_PLANE_V8).jitter(0.03f).seed(17))
                .set("rpm", rpm).set("hardness", hardness);
            Signal exhaust = g.add(() -> Tube.of(1.6)).in(firing).set("damping", 0.35);
            Signal muffled = g.add(Filter::lowPass).in(exhaust).set("cutoff", load.remap(-1, 1, 900, 5_000))
                .set("q", 0.9);
            Signal intake = g.add(() -> Filter.bandPass(1_800, 1.2)).in(g.add(Noise::pink))
                .times(firing.envelope(0.002, 0.01).times(load.clamp(0, 1)).times(0.4));
            Signal whine = g.add(Oscillator::sine).set("frequency", rpm.times(4.0 / 60)).times(0.05);
            Signal crackle = g.add(() -> Crackle.of().seed(5))
                .set("rate", Signal.when(load.below(-0.2), rpm.remap(3_000, 7_000, 0, 12), 0));
            g.output(g.mix(muffled, intake, whine, g.add(() -> Tube.of(0.4)).in(crackle)).times(0.8));
        }).build();
    }
}
