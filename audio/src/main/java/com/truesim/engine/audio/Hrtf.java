package com.truesim.engine.audio;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.math.Maths;
import com.truesim.scene.audio.HrtfSet;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * An {@link HrtfSet} prepared for an output rate (§2.9, steps 1, 4 and 5 of the preparation): its filters and delays
 * resampled (windowed sinc), its directions triangulated (the convex hull of the unit vectors, which on a sphere is
 * their Delaunay triangulation) and a lookup from any direction to the triangle around it: a cube map of 6 × 48 × 48
 * cells (about 2° each) naming a triangle near each, from which a walk across edges reaches the right one in a step
 * or two. Finding a direction's three neighbours and their barycentric weights is then a table read and a few 3×3
 * determinants, allocation-free; the filters stay in the time domain.
 *
 * <p>Made once per set and rate, off the audio thread (it allocates and takes a few tens of milliseconds for 2,000
 * directions); immutable after, so any number of renderers may share it.
 */
public final class Hrtf {

    /** The default set: SADIE II's KU100 at 48 kHz (Apache 2.0; its notice is next to it), converted at build time. */
    public static final String BUILT_IN = "hrtf/sadie-ii-ku100.hrtf";

    private static final int GRID = 48;

    private final int rate;
    private final int taps;
    private final int count;
    private final String credit;
    private final float[] filters;
    private final float[] delays;
    private final double[] vertices;
    private final int[] triangles;
    private final int[] neighbours;
    private final int[] grid;
    private final float maxDelay;

    private Hrtf(int rate, int taps, String credit, float[] filters, float[] delays, double[] vertices,
            int[] triangles, int[] neighbours, int[] grid) {
        this.rate = rate;
        this.taps = taps;
        this.count = delays.length / 2;
        this.credit = credit;
        this.filters = filters;
        this.delays = delays;
        this.vertices = vertices;
        this.triangles = triangles;
        this.neighbours = neighbours;
        this.grid = grid;
        float most = 0;
        for (float d : delays) {
            most = Math.max(most, d);
        }
        maxDelay = most;
    }

    /** The built-in set prepared for {@code rate}. */
    @AllowAllocation("preparing a set")
    public static Hrtf builtIn(int rate) {
        try (InputStream in = Hrtf.class.getResourceAsStream(BUILT_IN)) {
            return prepare(HrtfSet.read(in), rate);
        } catch (IOException e) {
            throw new UncheckedIOException("The built-in HRTF set does not load", e);
        }
    }

    /** {@code set} prepared for output at {@code rate}. */
    @AllowAllocation("preparing a set")
    public static Hrtf prepare(HrtfSet set, int rate) {
        int n = set.count();
        double ratio = (double) rate / set.rate();
        int pad = ratio == 1 ? 0 : (int) Math.ceil(17 * ratio); // the sinc's ringing before the filter's start
        int taps = Math.max(1, (int) Math.round(set.taps() * ratio)) + pad;
        float[] filters = new float[2 * n * taps];
        float[] delays = new float[2 * n];
        float[] source = new float[set.taps()];
        float[] resampled = new float[taps];
        for (int i = 0; i < n; i++) {
            for (int ear = 0; ear < 2; ear++) {
                set.filter(i, ear, source, 0);
                resample(source, ratio, pad, resampled);
                System.arraycopy(resampled, 0, filters, (2 * i + ear) * taps, taps);
                delays[2 * i + ear] = (float) (set.delay(i, ear) * ratio);
            }
        }
        double[] vertices = new double[3 * n];
        for (int i = 0; i < n; i++) {
            for (int axis = 0; axis < 3; axis++) {
                vertices[3 * i + axis] = set.direction(i, axis);
            }
        }
        int[] triangles = hull(vertices);
        int[] neighbours = neighbours(triangles);
        Hrtf h = new Hrtf(rate, taps, set.credit(), filters, delays, vertices, triangles, neighbours,
            new int[6 * GRID * GRID]);
        h.fillGrid();
        return h;
    }

    /**
     * {@code in} at {@code ratio} times its rate into {@code out}, {@code pad} output samples late (room for the
     * ringing before the start: a common delay of every filter): windowed sinc (a Blackman window over 17 input
     * samples each side), low-passed at the lower Nyquist when the rate falls, scaled by 1 / ratio so the filter's
     * gain stays.
     */
    static void resample(float[] in, double ratio, int pad, float[] out) {
        if (ratio == 1) {
            System.arraycopy(in, 0, out, 0, Math.min(in.length, out.length));
            return;
        }
        double cutoff = Math.min(1, ratio);
        int half = 16;
        for (int k = 0; k < out.length; k++) {
            double t = (k - pad) / ratio;
            double sum = 0;
            int centre = (int) Math.floor(t);
            for (int i = centre - half; i <= centre + half + 1; i++) {
                if (i < 0 || i >= in.length) {
                    continue;
                }
                double x = (t - i) * cutoff;
                double sinc = x == 0 ? 1 : Maths.sin(Maths.PI * x) / (Maths.PI * x);
                double w = (t - i) / (half + 1);
                if (Math.abs(w) >= 1) {
                    continue;
                }
                double window = 0.42 + 0.5 * Maths.cos(Maths.PI * w) + 0.08 * Maths.cos(2 * Maths.PI * w);
                sum += in[i] * sinc * cutoff * window;
            }
            out[k] = (float) (sum / ratio);
        }
    }

    /** The output rate. */
    public int rate() {
        return rate;
    }

    /** Taps per filter at the output rate. */
    public int taps() {
        return taps;
    }

    /** Directions. */
    public int count() {
        return count;
    }

    /** Triangles of its triangulation. */
    public int triangles() {
        return triangles.length / 3;
    }

    /** Where the set comes from and its licence. */
    public String credit() {
        return credit;
    }

    /** The largest ear delay, samples at the output rate. */
    public float maxDelay() {
        return maxDelay;
    }

    /** Ear {@code ear}'s delay for direction {@code i}, samples. */
    public float delay(int i, int ear) {
        return delays[2 * i + ear];
    }

    /** Component {@code axis} of direction {@code i}'s unit vector. */
    public double direction(int i, int axis) {
        return vertices[3 * i + axis];
    }

    /** Tap {@code t} of ear {@code ear}'s filter for direction {@code i}. */
    public float tap(int i, int ear, int t) {
        return filters[(2 * i + ear) * taps + t];
    }

    /**
     * The three directions around ({@code x}, {@code y}, {@code z}) (any length but 0) into {@code at} and their
     * barycentric weights, summing to 1, into {@code weights}. Allocation-free.
     */
    public void locate(double x, double y, double z, int[] at, float[] weights) {
        int t = grid[cell(x, y, z)];
        for (int step = 0; step < 4 * triangles.length; step++) {
            int a = triangles[3 * t];
            int b = triangles[3 * t + 1];
            int c = triangles[3 * t + 2];
            double wa = det(x, y, z, b, c);
            double wb = det(a, x, y, z, c);
            double wc = det(a, b, x, y, z);
            int worst = wa <= wb && wa <= wc ? 0 : wb <= wc ? 1 : 2;
            double least = Math.min(wa, Math.min(wb, wc));
            double sum = wa + wb + wc;
            if (least >= -1e-12 * Math.abs(sum) && sum > 0) {
                at[0] = a;
                at[1] = b;
                at[2] = c;
                weights[0] = (float) (Math.max(0, wa) / sum);
                weights[1] = (float) (Math.max(0, wb) / sum);
                weights[2] = 1 - weights[0] - weights[1];
                return;
            }
            t = neighbours[3 * t + worst];
        }
        throw new IllegalStateException("No triangle around " + x + ", " + y + ", " + z);
    }

    /** det(p, B, C) with p = (x, y, z): its weight for vertex A. */
    private double det(double x, double y, double z, int b, int c) {
        double bx = vertices[3 * b];
        double by = vertices[3 * b + 1];
        double bz = vertices[3 * b + 2];
        double cx = vertices[3 * c];
        double cy = vertices[3 * c + 1];
        double cz = vertices[3 * c + 2];
        return x * (by * cz - bz * cy) - y * (bx * cz - bz * cx) + z * (bx * cy - by * cx);
    }

    private double det(int a, double x, double y, double z, int c) {
        return det(x, y, z, c, a);
    }

    private double det(int a, int b, double x, double y, double z) {
        return det(x, y, z, a, b);
    }

    /** Interpolates ear {@code ear}'s filters of directions {@code at} by {@code weights} into {@code out}. */
    public void interpolate(int[] at, float[] weights, int ear, float[] out) {
        int o0 = (2 * at[0] + ear) * taps;
        int o1 = (2 * at[1] + ear) * taps;
        int o2 = (2 * at[2] + ear) * taps;
        float w0 = weights[0];
        float w1 = weights[1];
        float w2 = weights[2];
        for (int t = 0; t < taps; t++) {
            out[t] = w0 * filters[o0 + t] + w1 * filters[o1 + t] + w2 * filters[o2 + t];
        }
    }

    /** Ear {@code ear}'s delay at directions {@code at} weighted by {@code weights}, samples. */
    public float delay(int[] at, float[] weights, int ear) {
        return weights[0] * delays[2 * at[0] + ear] + weights[1] * delays[2 * at[1] + ear]
            + weights[2] * delays[2 * at[2] + ear];
    }

    /** The cube map's cell of a direction: the face of its largest axis, then a 48 × 48 grid on it. */
    private static int cell(double x, double y, double z) {
        double ax = Math.abs(x);
        double ay = Math.abs(y);
        double az = Math.abs(z);
        int face;
        double u;
        double v;
        if (ax >= ay && ax >= az) {
            face = x > 0 ? 0 : 1;
            u = y / ax;
            v = z / ax;
        } else if (ay >= az) {
            face = y > 0 ? 2 : 3;
            u = x / ay;
            v = z / ay;
        } else {
            face = z > 0 ? 4 : 5;
            u = x / az;
            v = y / az;
        }
        int i = Math.min(GRID - 1, (int) ((u + 1) * 0.5 * GRID));
        int j = Math.min(GRID - 1, (int) ((v + 1) * 0.5 * GRID));
        return (face * GRID + j) * GRID + i;
    }

    /** Each cell's triangle: found by walking from the cell before, the first by a search. */
    private void fillGrid() {
        int[] at = new int[3];
        float[] w = new float[3];
        int last = 0;
        for (int face = 0; face < 6; face++) {
            for (int j = 0; j < GRID; j++) {
                for (int i = 0; i < GRID; i++) {
                    double u = (i + 0.5) / GRID * 2 - 1;
                    double v = (j + 0.5) / GRID * 2 - 1;
                    double s = (face & 1) == 0 ? 1 : -1;
                    double x;
                    double y;
                    double z;
                    if (face < 2) {
                        x = s;
                        y = u;
                        z = v;
                    } else if (face < 4) {
                        x = u;
                        y = s;
                        z = v;
                    } else {
                        x = u;
                        y = v;
                        z = s;
                    }
                    int c = cell(x, y, z);
                    grid[c] = last;
                    locate(x, y, z, at, w);
                    last = triangleOf(at);
                    grid[c] = last;
                }
            }
        }
    }

    /** The triangle with vertices {@code at} (as {@link #locate} gave them, in its order). */
    private int triangleOf(int[] at) {
        for (int t = 0; t < triangles.length / 3; t++) {
            if (triangles[3 * t] == at[0] && triangles[3 * t + 1] == at[1] && triangles[3 * t + 2] == at[2]) {
                return t;
            }
        }
        throw new IllegalStateException("not a triangle");
    }

    // ---------------------------------------------------------------- triangulation

    /**
     * The convex hull of unit vectors as triangles (vertex indices, counter-clockwise seen from outside), built
     * incrementally. Points are nudged by a hashed millionth of a millimetre for the hull's tests only, so rings of
     * coplanar directions (every measured grid has them) do not leave flat faces undecided; a direction repeated (a
     * pole measured at several azimuths) is left out after its first.
     */
    @AllowAllocation("preparing a set")
    static int[] hull(double[] points) {
        int n = points.length / 3;
        double[] p = points.clone();
        for (int i = 0; i < p.length; i++) {
            int h = com.truesim.common.math.random.Hash.pcg(i);
            p[i] += ((h & 0xFFFF) / 65_536.0 - 0.5) * 1e-9;
        }
        boolean[] used = new boolean[n];
        int i0 = 0;
        int i1 = farthest(p, n, i0);
        int i2 = -1;
        double best = -1;
        for (int i = 0; i < n; i++) {
            double d = lineDistance(p, i0, i1, i);
            if (d > best) {
                best = d;
                i2 = i;
            }
        }
        int i3 = -1;
        best = -1;
        for (int i = 0; i < n; i++) {
            double d = Math.abs(plane(p, i0, i1, i2, i));
            if (d > best) {
                best = d;
                i3 = i;
            }
        }
        if (best < 1e-9) {
            throw new IllegalArgumentException("The directions lie in a plane: no sphere to triangulate");
        }
        double cx = (p[3 * i0] + p[3 * i1] + p[3 * i2] + p[3 * i3]) / 4;
        double cy = (p[3 * i0 + 1] + p[3 * i1 + 1] + p[3 * i2 + 1] + p[3 * i3 + 1]) / 4;
        double cz = (p[3 * i0 + 2] + p[3 * i1 + 2] + p[3 * i2 + 2] + p[3 * i3 + 2]) / 4;
        List<int[]> faces = new ArrayList<>();
        int[][] start = {{i0, i1, i2}, {i0, i1, i3}, {i0, i2, i3}, {i1, i2, i3}};
        for (int[] f : start) {
            faces.add(outward(p, f, cx, cy, cz));
        }
        used[i0] = used[i1] = used[i2] = used[i3] = true;
        List<Boolean> alive = new ArrayList<>(List.of(true, true, true, true));
        for (int i = 0; i < n; i++) {
            if (used[i] || repeated(points, i)) {
                continue;
            }
            Set<Long> edges = new HashSet<>();
            List<Integer> visible = new ArrayList<>();
            for (int f = 0; f < faces.size(); f++) {
                if (alive.get(f) && plane(p, faces.get(f)[0], faces.get(f)[1], faces.get(f)[2], i) > 1e-15) {
                    visible.add(f);
                    int[] v = faces.get(f);
                    for (int e = 0; e < 3; e++) {
                        edges.add((long) v[e] * n + v[(e + 1) % 3]);
                    }
                }
            }
            if (visible.isEmpty()) {
                continue;
            }
            used[i] = true;
            for (int f : visible) {
                alive.set(f, false);
                int[] v = faces.get(f);
                for (int e = 0; e < 3; e++) {
                    int a = v[e];
                    int b = v[(e + 1) % 3];
                    if (!edges.contains((long) b * n + a)) { // a horizon edge: its other face stays
                        faces.add(new int[] {a, b, i});
                        alive.add(true);
                    }
                }
            }
        }
        int[] out = new int[3 * (int) alive.stream().filter(b -> b).count()];
        int k = 0;
        for (int f = 0; f < faces.size(); f++) {
            if (alive.get(f)) {
                System.arraycopy(faces.get(f), 0, out, 3 * k++, 3);
            }
        }
        return out;
    }

    private static boolean repeated(double[] points, int i) {
        for (int j = 0; j < i; j++) {
            double dx = points[3 * i] - points[3 * j];
            double dy = points[3 * i + 1] - points[3 * j + 1];
            double dz = points[3 * i + 2] - points[3 * j + 2];
            if (dx * dx + dy * dy + dz * dz < 1e-12) {
                return true;
            }
        }
        return false;
    }

    /** {@code f} ordered so its normal points away from the inside point. */
    private static int[] outward(double[] p, int[] f, double cx, double cy, double cz) {
        double[] inside = {cx, cy, cz};
        double d = planeAt(p, f[0], f[1], f[2], inside, 0);
        return d > 0 ? new int[] {f[0], f[2], f[1]} : f;
    }

    private static int farthest(double[] p, int n, int from) {
        int best = from;
        double most = -1;
        for (int i = 0; i < n; i++) {
            double dx = p[3 * i] - p[3 * from];
            double dy = p[3 * i + 1] - p[3 * from + 1];
            double dz = p[3 * i + 2] - p[3 * from + 2];
            double d = dx * dx + dy * dy + dz * dz;
            if (d > most) {
                most = d;
                best = i;
            }
        }
        return best;
    }

    private static double lineDistance(double[] p, int a, int b, int i) {
        double ux = p[3 * b] - p[3 * a];
        double uy = p[3 * b + 1] - p[3 * a + 1];
        double uz = p[3 * b + 2] - p[3 * a + 2];
        double vx = p[3 * i] - p[3 * a];
        double vy = p[3 * i + 1] - p[3 * a + 1];
        double vz = p[3 * i + 2] - p[3 * a + 2];
        double cx = uy * vz - uz * vy;
        double cy = uz * vx - ux * vz;
        double cz = ux * vy - uy * vx;
        return cx * cx + cy * cy + cz * cz;
    }

    /** How far point {@code i} is above the plane of a, b, c (counter-clockwise), times twice the triangle's area. */
    private static double plane(double[] p, int a, int b, int c, int i) {
        return planeAt(p, a, b, c, p, 3 * i);
    }

    private static double planeAt(double[] p, int a, int b, int c, double[] q, int o) {
        double ux = p[3 * b] - p[3 * a];
        double uy = p[3 * b + 1] - p[3 * a + 1];
        double uz = p[3 * b + 2] - p[3 * a + 2];
        double vx = p[3 * c] - p[3 * a];
        double vy = p[3 * c + 1] - p[3 * a + 1];
        double vz = p[3 * c + 2] - p[3 * a + 2];
        double nx = uy * vz - uz * vy;
        double ny = uz * vx - ux * vz;
        double nz = ux * vy - uy * vx;
        return nx * (q[o] - p[3 * a]) + ny * (q[o + 1] - p[3 * a + 1]) + nz * (q[o + 2] - p[3 * a + 2]);
    }

    /** For each triangle's vertex k, the triangle across the edge opposite it. */
    @AllowAllocation("preparing a set")
    static int[] neighbours(int[] triangles) {
        Map<Long, Integer> byEdge = new HashMap<>();
        int count = triangles.length / 3;
        for (int t = 0; t < count; t++) {
            for (int e = 0; e < 3; e++) {
                byEdge.put(edge(triangles[3 * t + e], triangles[3 * t + (e + 1) % 3]), t);
            }
        }
        int[] out = new int[triangles.length];
        Arrays.fill(out, -1);
        for (int t = 0; t < count; t++) {
            for (int k = 0; k < 3; k++) {
                int a = triangles[3 * t + (k + 1) % 3];
                int b = triangles[3 * t + (k + 2) % 3];
                out[3 * t + k] = byEdge.getOrDefault(edge(b, a), t);
            }
        }
        return out;
    }

    private static long edge(int a, int b) {
        return (long) a << 32 | b & 0xFFFFFFFFL;
    }
}
