package com.truesim.engine.demo;

import com.truesim.engine.audio.Audio;
import com.truesim.engine.audio.dsp.Vehicles;
import com.truesim.scene.audio.Patch;
import com.truesim.scene.audio.Sound;
import com.truesim.scene.audio.SoundInstance;
import com.truesim.scene.audio.Sounds;

/**
 * The demo's car (audio step 12): the procedural V8 heard as if the fly camera were a car. The camera's speed drives
 * a five-speed gearbox: the engine's rpm follows the speed in the gear, it shifts up past 5,500 rpm and down under
 * 2,000 (a shift trigger each time), and the load follows the acceleration (overrun crackle when slowing hard).
 */
final class CameraCar {

    /** Metres a second per 1,000 rpm in each gear. */
    private static final double[] GEARS = {2.2, 3.6, 5.2, 7.0, 9.0};

    private final SoundInstance engine;
    private final double[] last = new double[3];
    private boolean started;
    private int gear;
    private double speed;

    CameraCar(Audio audio, Sounds sounds) {
        audio.patches().register(Vehicles.v8());
        engine = sounds.play(Sound.builder().root(Patch.named("v8", Vehicles.RPM, Vehicles.LOAD, Vehicles.SHIFT))
            .volume(com.truesim.scene.audio.Range.db(-6, -6)).loop().build());
    }

    /** Follows the camera at {@code eye} over {@code dt} seconds. */
    void update(double dt, double[] eye) {
        if (!started || dt <= 0) {
            System.arraycopy(eye, 0, last, 0, 3);
            started = true;
            return;
        }
        double dx = eye[0] - last[0];
        double dy = eye[1] - last[1];
        double dz = eye[2] - last[2];
        System.arraycopy(eye, 0, last, 0, 3);
        double now = Math.sqrt(dx * dx + dy * dy + dz * dz) / dt;
        double before = speed;
        speed += (now - speed) * Math.min(1, dt * 4); // the camera jumps; a car does not
        double rpm = 800 + 1_000 * speed / GEARS[gear];
        if (rpm > 5_500 && gear < GEARS.length - 1) {
            gear++;
            engine.trigger(Vehicles.SHIFT);
        } else if (rpm < 2_000 && gear > 0) {
            gear--;
            engine.trigger(Vehicles.SHIFT);
        }
        rpm = Math.min(7_000, 800 + 1_000 * speed / GEARS[gear]);
        double accel = (speed - before) / dt;
        double load = Math.max(-1, Math.min(1, 0.15 + accel / 4));
        engine.set(Vehicles.RPM, (float) rpm, 0.05).set(Vehicles.LOAD, (float) load, 0.1);
    }

    void stop() {
        engine.stop(0.3);
    }
}
