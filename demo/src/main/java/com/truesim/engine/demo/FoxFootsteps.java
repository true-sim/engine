package com.truesim.engine.demo;

import com.truesim.common.jobs.TaskScheduler;
import com.truesim.common.math.Maths;
import com.truesim.common.math.random.Hash;
import com.truesim.engine.audio.Audio;
import com.truesim.engine.audio.AudioBindings;
import com.truesim.scene.assets.AssetCache;
import com.truesim.scene.assets.AssetRef;
import com.truesim.scene.assets.Generator;
import com.truesim.scene.audio.Attenuation;
import com.truesim.scene.audio.AudioAssets;
import com.truesim.scene.audio.AudioClip;
import com.truesim.scene.audio.Play;
import com.truesim.scene.audio.Random;
import com.truesim.scene.audio.Range;
import com.truesim.scene.audio.Sound;
import com.truesim.scene.audio.Sounds;
import com.truesim.scene.audio.Switch;
import com.truesim.scene.audio.SwitchGroup;

/**
 * The fox's footsteps (audio step 14): {@link AudioBindings} play a footstep at each paw when the walk or the run puts
 * it down, its surface switch from the ground's material under the paw. The demo's ground has no materials of its
 * own, so bands across the clearing stand in: grass, stone and gravel half a metre wide. Steps are made in code
 * (grass: a soft thump and a rustle; stone: a knock and a scrape; gravel: a crunch of small clicks), three of each.
 */
final class FoxFootsteps implements AutoCloseable {

    enum Surface {
        GRASS, STONE, GRAVEL
    }

    static final SwitchGroup<Surface> SURFACE = SwitchGroup.of("surface", Surface.class, Surface.GRASS);
    private static final int RATE = 48_000;

    /** A step on surface {@code key / 3}, variant {@code key % 3}. */
    static final Generator<Integer, AudioClip> STEP = (key, seed) -> {
        int surface = key / 3;
        int variant = key % 3;
        int frames = RATE / 5;
        float[] s = new float[frames];
        double low = 0;
        float peak = 0;
        for (int i = 0; i < frames; i++) {
            double t = (double) i / RATE;
            double noise = Hash.random(31 * key + 7, i) * 2 - 1;
            double v;
            if (surface == 0) { // grass: a padded thump, a rustle of soft high noise
                low += 0.15 * (noise - low);
                v = 0.6 * Maths.sin(2 * Math.PI * (70 + 8 * variant) * t) * Maths.exp(-t * 35)
                    + 0.5 * (noise - low) * Maths.exp(-t * 18) * Math.min(1, t * 200);
            } else if (surface == 1) { // stone: a knock and a short scrape
                v = 0.5 * Maths.sin(2 * Math.PI * (900 + 120 * variant) * t) * Maths.exp(-t * 90)
                    + 0.5 * Maths.sin(2 * Math.PI * 110 * t) * Maths.exp(-t * 50) + 0.4 * noise * Maths.exp(-t * 60);
            } else { // gravel: many small clicks over 80 ms
                double click = Hash.random(91 * key, i / 48) < 0.12 ? noise : 0;
                v = 0.9 * click * Maths.exp(-t * 25) + 0.3 * Maths.sin(2 * Math.PI * 85 * t) * Maths.exp(-t * 40);
            }
            s[i] = (float) v;
            peak = Math.max(peak, Math.abs(s[i]));
        }
        for (int i = 0; i < frames; i++) {
            s[i] *= 0.8f / peak;
        }
        return AudioClip.builder(RATE, 1).floats(s).build();
    };

    private final AssetCache cache;
    private final TaskScheduler tasks;
    private final Sounds sounds;
    private final AudioBindings bindings;
    private final long listener;
    private final DemoCharacter fox;

    FoxFootsteps(Audio audio, DemoCharacter fox, TaskScheduler tasks) {
        this.fox = fox;
        this.tasks = tasks;
        cache = new AssetCache(tasks);
        sounds = audio.assets(cache).sounds(fox.scene());
        listener = fox.scene().create();
        sounds.listeners().device(listener);
        Switch<Surface> steps = Switch.on(SURFACE);
        for (Surface surface : Surface.values()) {
            steps = steps.when(surface, Random.of(step(surface, 0), step(surface, 1), step(surface, 2)).noRepeat(1));
        }
        Sound step = Sound.builder().root(steps).attenuation(Attenuation.near(1, 40)).volume(Range.db(-4, 0))
            .pitch(Range.semitones(-1.5, 1.5)).build();
        bindings = new AudioBindings(sounds, fox.animators());
        for (int f = 0; f < DemoCharacter.FEET.length; f++) {
            bindings.onEvent(DemoCharacter.FEET[f], step, fox.feet()[f]).surface(SURFACE, FoxFootsteps::materialAt)
                .minimumWeight(0.2f);
        }
    }

    private static Play step(Surface surface, int variant) {
        AssetRef<AudioClip> clip = AssetRef.generated(AudioAssets.CLIP, STEP, 3 * surface.ordinal() + variant, 0);
        return Play.clip(clip);
    }

    /** Bands half a metre wide across the clearing: grass, stone, gravel. */
    static int materialAt(double x, double z) {
        return (int) Math.floorMod((long) Math.floor((x + z) * 2), 3L);
    }

    /** The listener at the camera; the bindings' sounds. After the fox's update. */
    void update(double[] eye, double yaw) {
        tasks.drainCompleted();
        double half = (yaw + Math.PI) / 2;
        fox.scene().setLocal(listener, eye[0], eye[1], eye[2], 0, Maths.sin(half), 0, Maths.cos(half), 1, 1, 1);
        bindings.update();
    }

    /** Footsteps played so far. */
    long played() {
        return bindings.played();
    }

    @Override
    public void close() {
        cache.close();
    }
}
