package com.truesim.engine.demo;

import com.truesim.common.jobs.TaskScheduler;
import com.truesim.common.math.Maths;
import com.truesim.common.math.random.Hash;
import com.truesim.common.state.ChangeClock;
import com.truesim.engine.audio.Audio;
import com.truesim.engine.audio.HeadPose;
import com.truesim.scene.assets.AssetCache;
import com.truesim.scene.assets.AssetRef;
import com.truesim.scene.assets.Generator;
import com.truesim.scene.audio.AudioAssets;
import com.truesim.scene.audio.AudioClip;
import com.truesim.scene.audio.Attenuation;
import com.truesim.scene.audio.Concurrency;
import com.truesim.scene.audio.Decibels;
import com.truesim.scene.audio.Play;
import com.truesim.scene.audio.Range;
import com.truesim.scene.audio.Sound;
import com.truesim.scene.audio.Sounds;
import com.truesim.scene.core.SceneGraph;

/**
 * The demo's sound (audio step 6): rocks dropped in front of the camera that knock when they land, heard from the
 * camera. Each rock falls, bounces with less each time and rests, then is dropped again; every landing faster than a
 * metre a second fires a knock at the landing place, louder for harder landings. The knock is made in code (a thump,
 * a body and a click from hashed noise), so the demo needs no sound files. With them, the {@link CameraCar}: a
 * procedural V8 following the camera's speed (step 12).
 */
final class KnockingRocks implements AutoCloseable {

    static final int ROCKS = 6;
    private static final int RATE = 48_000;

    /** A knock: a low thump, a wooden body and a click, about a quarter of a second. */
    static final Generator<Integer, AudioClip> KNOCK = (variant, seed) -> {
        int frames = RATE / 4;
        float[] s = new float[frames];
        double body = 520 + 60 * variant;
        float peak = 0;
        for (int i = 0; i < frames; i++) {
            double t = (double) i / RATE;
            double noise = Hash.random((int) seed + variant, i) * 2 - 1;
            double v = 0.8 * Maths.sin(2 * Math.PI * 85 * t) * Maths.exp(-t * 22)
                + 0.5 * Maths.sin(2 * Math.PI * body * t) * Maths.exp(-t * 45)
                + 0.6 * noise * Maths.exp(-t * 300);
            s[i] = (float) v;
            peak = Math.max(peak, Math.abs(s[i]));
        }
        for (int i = 0; i < frames; i++) {
            s[i] *= 0.9f / peak;
        }
        return AudioClip.builder(RATE, 1).floats(s).build();
    };

    private final DemoScene world;
    private final SceneGraph scene = new SceneGraph(new ChangeClock());
    private final long listener;
    private final Sounds sounds;

    /** The rocks' and the car's sounds: for the audio overlay's listener graph. */
    Sounds sounds() {
        return sounds;
    }
    private final AssetCache cache;
    private final TaskScheduler tasks;
    private final Sound knock;
    private final double[] positions = new double[3 * ROCKS];
    private final double[] velocity = new double[ROCKS];
    private final double[] rest = new double[ROCKS];
    private final double[] spots = new double[2 * ROCKS];
    private int drops;
    private final CameraCar car;
    private int knocks;

    KnockingRocks(Audio audio, DemoScene world, TaskScheduler tasks, double[] eye, double yaw, HeadPose head) {
        this.world = world;
        this.tasks = tasks;
        cache = new AssetCache(tasks);
        sounds = audio.assets(cache).sounds(scene);
        listener = scene.create();
        long camera = sounds.listeners().device(listener).id();
        if (head != null) { // in VR the head listens (and the plugin turns the HRTF on), not the desktop camera
            sounds.listeners().remove(camera);
            audio.head(sounds, head);
        }
        car = new CameraCar(audio, sounds);
        AssetRef<AudioClip> clip = AssetRef.generated(AudioAssets.CLIP, KNOCK, 0, 17);
        knock = Sound.builder().root(Play.clip(clip)).attenuation(Attenuation.near(2, 80))
            .volume(Range.db(-3, 0)).pitch(Range.semitones(-3, 3))
            .concurrency(Concurrency.max(8).steal(Concurrency.Steal.OLDEST))
            .spatial(com.truesim.scene.audio.Spatial.HRTF).build(); // binaural on headphones
        // drop spots ahead of the camera, 6 to 20 m out, spread across the view
        double fx = Maths.sin(yaw);
        double fz = Maths.cos(yaw);
        for (int i = 0; i < ROCKS; i++) {
            double ahead = 6 + 14.0 * i / (ROCKS - 1);
            double side = ((i % 3) - 1) * 0.6 * ahead;
            spots[2 * i] = eye[0] + fx * ahead + fz * side;
            spots[2 * i + 1] = eye[2] + fz * ahead - fx * side;
            drop(i, 0.4 * i);
        }
    }

    private void drop(int i, double wait) {
        double x = spots[2 * i];
        double z = spots[2 * i + 1];
        positions[3 * i] = x;
        positions[3 * i + 1] = DemoMeshes.height(x, z) + 4 + 3 * Hash.random(drops++, 1);
        positions[3 * i + 2] = z;
        velocity[i] = 0;
        rest[i] = wait;
    }

    /** Moves the rocks by {@code dt}, knocks where they land, and puts the listener at the camera. */
    void update(double dt, double[] eye, double yaw) {
        tasks.drainCompleted();
        double half = (yaw + Math.PI) / 2; // the listener's -z ahead along the camera's yaw
        scene.setLocal(listener, eye[0], eye[1], eye[2], 0, Maths.sin(half), 0, Maths.cos(half), 1, 1, 1);
        scene.update();
        car.update(dt, eye);
        for (int i = 0; i < ROCKS; i++) {
            if (rest[i] > 0) {
                rest[i] -= dt;
                if (rest[i] <= 0 && velocity[i] == 0 && positions[3 * i + 1] <= ground(i) + 0.31) {
                    drop(i, 0); // rested a while on the ground: up again
                }
            } else {
                velocity[i] -= 9.81 * dt;
                positions[3 * i + 1] += velocity[i] * dt;
                if (positions[3 * i + 1] <= ground(i) + 0.3) {
                    positions[3 * i + 1] = ground(i) + 0.3;
                    double speed = -velocity[i];
                    if (speed > 1) {
                        long id = sounds.fire(knock, positions[3 * i], positions[3 * i + 1], positions[3 * i + 2]);
                        sounds.volume(id, Decibels.of((float) Math.min(1, speed / 9)), 0);
                        knocks++;
                    }
                    velocity[i] = speed > 1.5 ? speed * 0.35 : 0;
                    if (velocity[i] == 0) {
                        rest[i] = 1.5;
                    }
                }
            }
            world.extraRock(i, positions[3 * i], positions[3 * i + 1], positions[3 * i + 2], 0.5f);
        }
    }

    private double ground(int i) {
        return DemoMeshes.height(positions[3 * i], positions[3 * i + 2]);
    }

    /** Knocks fired so far. */
    int knocks() {
        return knocks;
    }

    @Override
    public void close() {
        car.stop();
        cache.close();
    }
}
