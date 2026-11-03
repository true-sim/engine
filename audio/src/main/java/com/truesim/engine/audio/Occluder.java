package com.truesim.engine.audio;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.jobs.JobSystem;
import com.truesim.common.math.Maths;
import com.truesim.common.math.random.Hash;
import com.truesim.scene.audio.AudioWorld;
import com.truesim.scene.audio.Transmission;

/**
 * Occlusion rays (§2.11): each frame up to a budget of rays (64 by default), round-robin over the occluding voices,
 * traced through an {@link AudioWorld} on the job system, each into a result of its own (the same on any number of
 * threads); a volumetric source casts 4 rays across its radius (partial occlusion). The sync then smooths each sound's
 * transmission (attack 100 ms, release 300 ms) and turns it into a gain and a low-pass cutoff.
 */
final class Occluder {

    /** Seconds the transmission takes to fall (a wall coming between) and to rise again. */
    static final double ATTACK = 0.1;
    static final double RELEASE = 0.3;

    private final AudioWorld world;
    private final JobSystem jobs;
    private int budget;
    private double[] rays;
    private Transmission[] results;
    private int[] owners;
    private int count;
    private long traced;
    private final JobSystem.RangeTask task = this::trace;

    @AllowAllocation("set up once")
    Occluder(AudioWorld world, JobSystem jobs, int budget) {
        this.world = world;
        this.jobs = jobs;
        budget(budget);
    }

    @AllowAllocation("the budget changed")
    void budget(int rays) {
        budget = Math.max(1, rays);
        this.rays = new double[6 * budget];
        results = new Transmission[budget];
        for (int i = 0; i < budget; i++) {
            results[i] = new Transmission();
        }
        owners = new int[budget];
    }

    int budget() {
        return budget;
    }

    /** Starts a frame's rays. */
    void begin() {
        count = 0;
    }

    /** Room for {@code n} more rays this frame. */
    boolean room(int n) {
        return count + n <= budget;
    }

    /**
     * Adds the rays of a source of {@code radius} at ({@code ex}, {@code ey}, {@code ez}) heard from ({@code lx},
     * {@code ly}, {@code lz}) for {@code owner}: one, or 4 across the radius, turned by the hash of {@code seed}.
     */
    void add(int owner, double lx, double ly, double lz, double ex, double ey, double ez, double radius, int seed) {
        if (radius <= 0) {
            ray(owner, lx, ly, lz, ex, ey, ez);
            return;
        }
        // two axes across the ray: u from the ray and up (or x when the ray is vertical), v = d x u
        double dx = ex - lx;
        double dy = ey - ly;
        double dz = ez - lz;
        double d = Math.max(1e-9, Math.sqrt(dx * dx + dy * dy + dz * dz));
        dx /= d;
        dy /= d;
        dz /= d;
        double ux = Math.abs(dy) > 0.99 ? 1 : -dz;
        double uy = 0;
        double uz = Math.abs(dy) > 0.99 ? 0 : dx;
        double u = Math.sqrt(ux * ux + uz * uz);
        ux /= u;
        uz /= u;
        double vx = dy * uz - dz * uy;
        double vy = dz * ux - dx * uz;
        double vz = dx * uy - dy * ux;
        double turn = Hash.random(seed, 0) * Maths.HALF_PI;
        for (int k = 0; k < 4; k++) {
            double a = turn + k * Maths.HALF_PI;
            double c = Maths.cos(a) * radius * 0.7;
            double s = Maths.sin(a) * radius * 0.7;
            ray(owner, lx, ly, lz, ex + ux * c + vx * s, ey + uy * c + vy * s, ez + uz * c + vz * s);
        }
    }

    private void ray(int owner, double lx, double ly, double lz, double ex, double ey, double ez) {
        int o = 6 * count;
        rays[o] = lx;
        rays[o + 1] = ly;
        rays[o + 2] = lz;
        rays[o + 3] = ex;
        rays[o + 4] = ey;
        rays[o + 5] = ez;
        owners[count++] = owner;
    }

    /** Traces the frame's rays: on the job system when there is one. */
    void trace() {
        if (jobs != null && count > 8) {
            jobs.parallelFor(count, 4, task);
        } else {
            trace(0, count, 0);
        }
        traced += count;
    }

    private void trace(int from, int to, int worker) {
        for (int i = from; i < to; i++) {
            int o = 6 * i;
            world.trace(rays[o], rays[o + 1], rays[o + 2], rays[o + 3], rays[o + 4], rays[o + 5], results[i].open());
        }
    }

    int count() {
        return count;
    }

    int owner(int i) {
        return owners[i];
    }

    Transmission result(int i) {
        return results[i];
    }

    /** Rays traced so far. */
    long traced() {
        return traced;
    }

    /** The low-pass cutoff for transmissions {@code mid} and {@code high}: the highs' share squared of 20 kHz. */
    static float cutoff(float mid, float high) {
        float ratio = mid <= 1e-6f ? 0 : Math.min(1, high / mid);
        return ratio >= 0.999f ? 0 : Math.max(200, 20_000 * ratio * ratio);
    }

    /** {@code current} moved toward {@code target} over {@code seconds}: falling by the attack, rising by release. */
    static float smooth(float current, float target, double seconds) {
        double tau = target < current ? ATTACK : RELEASE;
        return (float) (current + (target - current) * (1 - Maths.exp(-seconds / tau)));
    }
}
