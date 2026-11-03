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
 * threads), or handed to a {@link BatchedAudioWorld} as one batch whose results come frames later; a volumetric source
 * casts 4 rays across its radius (partial occlusion). The sync then smooths each sound's transmission (attack 100 ms,
 * release 300 ms) and turns it into a gain and a low-pass cutoff.
 *
 * <p>After {@link #trace}, {@link #results} are the rays whose results are in: this frame's, traced at once, or the
 * batches that came back, each ray with the owner it was cast for then (its track, sound id and store).
 */
final class Occluder {

    /** Seconds the transmission takes to fall (a wall coming between) and to rise again. */
    static final double ATTACK = 0.1;
    static final double RELEASE = 0.3;
    /** Batches handed over and not yet back, at most: past that, no rays are cast until one comes. */
    static final int BATCHES = 4;
    /** Frames a batch may stay out before it is given up (its world dropped it). */
    static final int GIVE_UP = 30;

    private final AudioWorld world;
    private final BatchedAudioWorld batched;
    private final JobSystem jobs;
    private int budget;
    private double[] rays;
    private int[] owners;
    private long[] ids;
    private int[] stores;
    private int count;
    private long traced;
    private final JobSystem.RangeTask task = this::trace;
    // The results in: as many as results says, each with its owner then.
    private Transmission[] results;
    private int[] resultOwners;
    private long[] resultIds;
    private int[] resultStores;
    private int resultCount;
    // The batches out, oldest first from head: ticket, frames out, owners (budget per batch).
    private final long[] tickets = new long[BATCHES];
    private final int[] ages = new int[BATCHES];
    private int[] batchOwners;
    private long[] batchIds;
    private int[] batchStores;
    private final int[] batchCounts = new int[BATCHES];
    private int head;
    private int out;
    private long given;

    @AllowAllocation("set up once")
    Occluder(AudioWorld world, JobSystem jobs, int budget) {
        this.world = world;
        this.batched = null;
        this.jobs = jobs;
        budget(budget);
    }

    @AllowAllocation("set up once")
    Occluder(BatchedAudioWorld world, int budget) {
        this.world = null;
        this.batched = world;
        this.jobs = null;
        budget(budget);
    }

    @AllowAllocation("the budget changed")
    void budget(int rays) {
        budget = Math.max(1, batched == null ? rays : Math.min(rays, batched.capacity()));
        this.rays = new double[6 * budget];
        owners = new int[budget];
        ids = new long[budget];
        stores = new int[budget];
        int kept = batched == null ? budget : BATCHES * budget; // every batch out may come back in one frame
        results = new Transmission[kept];
        for (int i = 0; i < kept; i++) {
            results[i] = new Transmission();
        }
        resultOwners = batched == null ? owners : new int[kept];
        resultIds = batched == null ? ids : new long[kept];
        resultStores = batched == null ? stores : new int[kept];
        batchOwners = new int[BATCHES * budget];
        batchIds = new long[BATCHES * budget];
        batchStores = new int[BATCHES * budget];
        out = 0; // batches out were cast for the old budget's layout: dropped
        resultCount = 0;
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
        return count + n <= budget && (batched == null || out < BATCHES);
    }

    /**
     * Adds the rays of a source of {@code radius} at ({@code ex}, {@code ey}, {@code ez}) heard from ({@code lx},
     * {@code ly}, {@code lz}) for {@code owner} (sound {@code id} of store {@code store}): one, or 4 across the radius,
     * turned by the hash of {@code seed}.
     */
    void add(int owner, long id, int store, double lx, double ly, double lz, double ex, double ey, double ez,
        double radius, int seed) {
        if (radius <= 0) {
            ray(owner, id, store, lx, ly, lz, ex, ey, ez);
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
            ray(owner, id, store, lx, ly, lz, ex + ux * c + vx * s, ey + uy * c + vy * s, ez + uz * c + vz * s);
        }
    }

    private void ray(int owner, long id, int store, double lx, double ly, double lz, double ex, double ey, double ez) {
        int o = 6 * count;
        rays[o] = lx;
        rays[o + 1] = ly;
        rays[o + 2] = lz;
        rays[o + 3] = ex;
        rays[o + 4] = ey;
        rays[o + 5] = ez;
        owners[count] = owner;
        ids[count] = id;
        stores[count++] = store;
    }

    /**
     * Traces the frame's rays: at once (on the job system when there is one), or handed over as a batch, collecting
     * the batches that came back.
     */
    void trace() {
        if (batched != null) {
            submit();
            collect();
            return;
        }
        if (jobs != null && count > 8) {
            jobs.parallelFor(count, 4, task);
        } else {
            trace(0, count, 0);
        }
        traced += count;
        resultCount = count;
    }

    private void trace(int from, int to, int worker) {
        for (int i = from; i < to; i++) {
            int o = 6 * i;
            world.trace(rays[o], rays[o + 1], rays[o + 2], rays[o + 3], rays[o + 4], rays[o + 5], results[i].open());
        }
    }

    /** Hands the frame's rays over as a batch, if any and the world takes it. */
    private void submit() {
        if (count == 0 || out >= BATCHES) {
            return;
        }
        long ticket = batched.submit(rays, count);
        if (ticket == 0) {
            return; // not taken: their sounds get rays when the round-robin comes back to them
        }
        int b = (head + out++) % BATCHES;
        tickets[b] = ticket;
        ages[b] = 0;
        batchCounts[b] = count;
        System.arraycopy(owners, 0, batchOwners, b * budget, count);
        System.arraycopy(ids, 0, batchIds, b * budget, count);
        System.arraycopy(stores, 0, batchStores, b * budget, count);
        traced += count;
    }

    /** Takes the batches back that came, oldest first; gives one up that stayed out too long. */
    private void collect() {
        resultCount = 0;
        for (int i = 0; i < out; i++) {
            ages[(head + i) % BATCHES]++;
        }
        while (out > 0) {
            int b = head;
            int n = batched.collect(tickets[b], results, resultCount);
            if (n < 0) {
                if (ages[b] <= GIVE_UP) {
                    return; // later ones come after it
                }
                given++;
            } else {
                n = Math.min(n, batchCounts[b]);
                System.arraycopy(batchOwners, b * budget, resultOwners, resultCount, n);
                System.arraycopy(batchIds, b * budget, resultIds, resultCount, n);
                System.arraycopy(batchStores, b * budget, resultStores, resultCount, n);
                resultCount += n;
            }
            head = (head + 1) % BATCHES;
            out--;
        }
    }

    int count() {
        return count;
    }

    /** How many rays' results are in, after {@link #trace}. */
    int results() {
        return resultCount;
    }

    /** The track result {@code i} was cast for. */
    int owner(int i) {
        return resultOwners[i];
    }

    /** The sound id of the track result {@code i} was cast for, then. */
    long ownerId(int i) {
        return resultIds[i];
    }

    /** The store of the track result {@code i} was cast for, then. */
    int ownerStore(int i) {
        return resultStores[i];
    }

    Transmission result(int i) {
        return results[i];
    }

    /** Rays traced so far (handed over, for a batched world). */
    long traced() {
        return traced;
    }

    /** Batches given up after {@link #GIVE_UP} frames out. */
    long givenUp() {
        return given;
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
