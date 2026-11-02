package com.truesim.engine.demo;

import com.truesim.common.math.Maths;
import com.truesim.engine.render.Textures;
import com.truesim.engine.render.particles.Particles;
import com.truesim.scene.assets.AssetId;
import com.truesim.scene.assets.AssetRef;
import com.truesim.scene.assets.AssetType;
import com.truesim.scene.assets.mesh.MeshData;
import com.truesim.scene.assets.mesh.MeshLevel;
import com.truesim.scene.assets.mesh.VertexAttribute;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.stream.IntStream;

/**
 * The textures the demo's fire, smoke and sparks draw with, made from code when it starts (no files): value noise in
 * fractal sums, shaped. All linear, four bytes a texel, their mip chains made on the GPU.
 *
 * <ul>
 *   <li>{@link #FLAME}: a flipbook of 8 x 8 frames, one flame tongue over its life: born round and dense at the bottom,
 *       stretching up as noise rises through it, licking from side to side, its tip tearing off and the rest thinning
 *       into wisps. The color is a temperature, white-yellow in the dense middle, orange at the edges, which the
 *       particle's color (cooling over its life) multiplies.</li>
 *   <li>{@link #GLOW}: a soft round falloff, for the hot core at a fire's base and the fireworks' flashes.</li>
 *   <li>{@link #EMBER}: a bright point in a faint halo, for embers, sparks and stars.</li>
 *   <li>{@link #SMOKE}: a flipbook of 4 x 4 billowing puffs thinning over their life, and {@link #SMOKE_NORMALS}, their
 *       normals (a sphere's, bent by the billows) for smoke lit per pixel.</li>
 * </ul>
 */
final class EffectTextures {

    /** The kind of the refs below: textures made here. */
    static final AssetType<String> TEXTURE = AssetType.of("demo.texture", String.class, s -> 0);
    static final AssetRef<?> FLAME = AssetRef.of(TEXTURE, new AssetId(0, 1));
    static final AssetRef<?> GLOW = AssetRef.of(TEXTURE, new AssetId(0, 2));
    static final AssetRef<?> EMBER = AssetRef.of(TEXTURE, new AssetId(0, 3));
    static final AssetRef<?> SMOKE = AssetRef.of(TEXTURE, new AssetId(0, 4));
    static final AssetRef<?> SMOKE_NORMALS = AssetRef.of(TEXTURE, new AssetId(0, 5));
    /** Where flames take on the fox: its upward-facing skin (see {@link #upward}). */
    static final AssetRef<?> FOX_MASK = AssetRef.of(TEXTURE, new AssetId(0, 6));

    /** The flame flipbook's frames a side, and each frame's texels a side. */
    static final int FLAME_FRAMES = 8;
    private static final int FLAME_CELL = 128;
    /** The smoke flipbook's frames a side, and each frame's texels a side. */
    static final int SMOKE_FRAMES = 4;
    private static final int SMOKE_CELL = 128;

    private EffectTextures() {
    }

    /** Makes the textures in {@code textures} and names them to {@code particles}. */
    static void register(Particles particles, Textures textures) {
        int flame = FLAME_FRAMES * FLAME_CELL;
        particles.texture(FLAME, upload(textures, flame, flame, flame()));
        particles.texture(GLOW, upload(textures, 64, 64, glow(64)));
        particles.texture(EMBER, upload(textures, 32, 32, ember(32)));
        int smoke = SMOKE_FRAMES * SMOKE_CELL;
        float[][] puffs = smoke();
        particles.texture(SMOKE, upload(textures, smoke, smoke, puffs[0]));
        particles.texture(SMOKE_NORMALS, upload(textures, smoke, smoke, puffs[1]));
    }

    /** Makes {@link #FOX_MASK} from the fox's mesh ({@link #upward}) and names it to {@code particles}. */
    static void registerFoxMask(Particles particles, Textures textures, MeshData fox) {
        particles.texture(FOX_MASK, upload(textures, 256, 256, upward(fox, 256)));
    }

    /** Uploads {@code rgba} (floats 0 to 1, four a texel) as a linear texture; its slot. */
    private static int upload(Textures textures, int width, int height, float[] rgba) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment bytes = arena.allocate(rgba.length);
            for (int i = 0; i < rgba.length; i++) {
                bytes.set(ValueLayout.JAVA_BYTE, i, (byte) Math.round(255 * Math.clamp(rgba[i], 0f, 1f)));
            }
            return textures.data(width, height, bytes);
        }
    }

    /** The flame flipbook: frame f of the 64 at column f % 8, row f / 8, the tongue's age f / 63 of its life. */
    static float[] flame() {
        int size = FLAME_FRAMES * FLAME_CELL;
        float[] out = new float[4 * size * size];
        IntStream.range(0, size).parallel().forEach(y -> {
            for (int x = 0; x < size; x++) {
                int frame = y / FLAME_CELL * FLAME_FRAMES + x / FLAME_CELL;
                double t = frame / (FLAME_FRAMES * FLAME_FRAMES - 1.0);
                double u = ((x % FLAME_CELL) + 0.5) / FLAME_CELL * 2 - 1; // -1 to 1 across
                double v = 1 - ((y % FLAME_CELL) + 0.5) / FLAME_CELL; // 0 at the bottom, 1 at the top
                double d = flameDensity(u, v, t);
                double alpha = smoothstep(0, 0.6, d);
                double heat = smoothstep(0.3, 1.3, d);
                int at = 4 * (y * size + x);
                out[at] = (float) (alpha * lerp(1.0, 1.0, heat));
                out[at + 1] = (float) (alpha * lerp(0.3, 0.92, heat));
                out[at + 2] = (float) (alpha * lerp(0.06, 0.7, heat * heat));
                out[at + 3] = (float) alpha;
            }
        });
        return out;
    }

    /**
     * A flame tongue's density at ({@code u}, {@code v}) of its frame (u across from -1 to 1, v up from 0 to 1) at age
     * {@code t} (0 to 1): a teardrop on a round base, licking sideways higher up, eaten by noise rising through it; late
     * in its life its tip tears away and the base thins out from below.
     */
    static double flameDensity(double u, double v, double t) {
        double grown = smoothstep(0, 0.3, t);
        double late = smoothstep(0.4, 1, t);
        double top = 0.55 + 0.4 * grown; // the tip
        double base = 0.14;
        // Licking: the middle line wanders, more higher up, as the noise moves up through it
        double sway = 0.7 * (fbm(1.6 * v - 2.4 * t, 4.7, 1.7 * t, 3) - 0.5) * (0.15 + v);
        double warp = 0.35 * (fbm(3.0 * u + 11, 3.2 * v - 3.4 * t, 0.9 * t, 4) - 0.5) * (0.3 + v);
        double width;
        if (v < base) {
            double k = (base - v) / base;
            width = 0.46 * Maths.sqrt(Math.max(0, 1 - k * k));
        } else {
            double q = Math.min(1, (v - base) / (top - base));
            width = 0.46 * Maths.pow(Math.max(0, 1 - q), 0.85);
        }
        double across = Math.abs(u - sway + warp) / Math.max(width, 0.03);
        double shape = Math.max(-1, 1 - across * across);
        // Noise rising through the tongue: eats its edges into licks, more higher up and later in its life
        double n = fbm(3.6 * u + 5, 4.2 * (v - 1.9 * t), 1.3 * t + 2, 5);
        double fine = fbm(9 * u + 3, 9 * (v - 2.6 * t), 2.1 * t, 3);
        double d = 1.3 * shape - (n - 0.42) * (1.1 + 1.4 * v) - (fine - 0.5) * 0.5 - late * (0.25 + 0.6 * v);
        // The tip torn off late: a gap above the base; and the base thinning from below
        d -= late * 1.1 * Math.max(0, 1 - Math.abs(v - (0.3 + 0.3 * t)) / 0.1) * (0.5 + n);
        d *= smoothstep(0, 0.08 + 0.35 * late, v) * (1 - smoothstep(top - 0.1, top + 0.06, v));
        return Math.max(0, d);
    }

    /** A soft round glow: bright in the middle, falling off smoothly to nothing at the edge. */
    static float[] glow(int size) {
        float[] out = new float[4 * size * size];
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                double r2 = r2(x, y, size);
                double a = Maths.exp(-4.5 * r2) * (1 - smoothstep(0.7, 1, Maths.sqrt(r2)));
                fill(out, 4 * (y * size + x), 1, 1, 1, a);
            }
        }
        return out;
    }

    /** A bright point in a faint halo. */
    static float[] ember(int size) {
        float[] out = new float[4 * size * size];
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                double r2 = r2(x, y, size);
                double a = Math.min(1, Maths.exp(-28 * r2) + 0.3 * Maths.exp(-5 * r2)) * (1 - smoothstep(0.8, 1, r2));
                fill(out, 4 * (y * size + x), 1, 1, 1, a);
            }
        }
        return out;
    }

    /**
     * The smoke flipbook (frame f of the 16 at column f % 4, row f / 4, its puff at age f / 15) and its normals: a
     * billowing ball, the billows turning as it ages and eating it from the edge in.
     */
    static float[][] smoke() {
        int size = SMOKE_FRAMES * SMOKE_CELL;
        float[] color = new float[4 * size * size];
        float[] normals = new float[4 * size * size];
        double[] height = new double[size * size];
        IntStream.range(0, size).parallel().forEach(y -> {
            for (int x = 0; x < size; x++) {
                int frame = y / SMOKE_CELL * SMOKE_FRAMES + x / SMOKE_CELL;
                double t = frame / (SMOKE_FRAMES * SMOKE_FRAMES - 1.0);
                double u = ((x % SMOKE_CELL) + 0.5) / SMOKE_CELL * 2 - 1;
                double v = ((y % SMOKE_CELL) + 0.5) / SMOKE_CELL * 2 - 1;
                double r = Maths.sqrt(u * u + v * v);
                double n = fbm(2.6 * u + 0.8 * t, 2.6 * v - 1.2 * t, 1.5 * t, 5);
                double lobes = fbm(1.4 * u, 1.4 * v - 0.4 * t, 0.6 * t + 4, 2);
                double ball = 1 - smoothstep(0.2, 1, r / (0.6 + 0.5 * lobes));
                double d = ball * (0.25 + 1.2 * n) - 0.12 - 0.35 * t;
                height[y * size + x] = Math.max(0, ball * (0.3 + n));
                double a = 0.95 * smoothstep(0, 0.85, d);
                double shade = 0.8 + 0.2 * n;
                fill(color, 4 * (y * size + x), shade, shade, shade, a);
            }
        });
        // Normals: the sphere's (right, up, towards the camera), bent by the billows' slopes
        IntStream.range(0, size).parallel().forEach(y -> {
            for (int x = 0; x < size; x++) {
                int cx = x % SMOKE_CELL;
                int cy = y % SMOKE_CELL;
                double u = (cx + 0.5) / SMOKE_CELL * 2 - 1;
                double v = (cy + 0.5) / SMOKE_CELL * 2 - 1;
                double z = Maths.sqrt(Math.max(0.05, 1 - u * u - v * v));
                int left = y * size + x - (cx > 0 ? 1 : 0);
                int right = y * size + x + (cx < SMOKE_CELL - 1 ? 1 : 0);
                int up = (y - (cy > 0 ? 1 : 0)) * size + x;
                int down = (y + (cy < SMOKE_CELL - 1 ? 1 : 0)) * size + x;
                double dx = (height[right] - height[left]) * SMOKE_CELL * 0.04;
                double dy = (height[up] - height[down]) * SMOKE_CELL * 0.04; // up the frame
                double nx = u - dx;
                double ny = -v - dy;
                double length = Maths.sqrt(nx * nx + ny * ny + z * z);
                fill(normals, 4 * (y * size + x), 0.5 + 0.5 * nx / length, 0.5 + 0.5 * ny / length,
                    0.5 + 0.5 * z / length, 1);
            }
        });
        return new float[][] {color, normals};
    }

    /**
     * A density over a mesh's texture coordinates ({@code size} texels a side): its upward-facing triangles (by their
     * face's normal against the mesh's +Y, its up as authored) near 1, those facing down near 0.1; each triangle drawn
     * where its UV0 puts it, then grown out a few texels so filtering at seams reads no gaps.
     */
    static float[] upward(MeshData mesh, int size) {
        float[] density = new float[size * size];
        boolean[] covered = new boolean[size * size];
        MeshLevel finest = mesh.levels().getLast();
        MemorySegment triangles = mesh.meshletTriangles();
        MemorySegment vertices = mesh.meshletVertices();
        double[] a = new double[3];
        double[] b = new double[3];
        double[] c = new double[3];
        double[] ta = new double[2];
        double[] tb = new double[2];
        double[] tc = new double[2];
        for (int m = finest.firstMeshlet(); m < finest.firstMeshlet() + finest.meshletCount(); m++) {
            int first = mesh.meshletVertexOffset(m);
            int corner = mesh.meshletTriangleOffset(m);
            for (int t = 0; t < mesh.meshletTriangleCount(m); t++) {
                int va = vertex(vertices, triangles, first, corner + 3 * t);
                int vb = vertex(vertices, triangles, first, corner + 3 * t + 1);
                int vc = vertex(vertices, triangles, first, corner + 3 * t + 2);
                mesh.position(va, a);
                mesh.position(vb, b);
                mesh.position(vc, c);
                mesh.uv(VertexAttribute.UV0, va, ta);
                mesh.uv(VertexAttribute.UV0, vb, tb);
                mesh.uv(VertexAttribute.UV0, vc, tc);
                // The face's normal: its y over its length
                double ux = b[0] - a[0];
                double uy = b[1] - a[1];
                double uz = b[2] - a[2];
                double wx = c[0] - a[0];
                double wy = c[1] - a[1];
                double wz = c[2] - a[2];
                double nx = uy * wz - uz * wy;
                double ny = uz * wx - ux * wz;
                double nz = ux * wy - uy * wx;
                double length = Maths.sqrt(nx * nx + ny * ny + nz * nz);
                if (length < 1e-12) {
                    continue;
                }
                double up = ny / length;
                float value = (float) (0.1 + 0.9 * smoothstep(-0.15, 0.75, up));
                rasterize(density, covered, size, ta, tb, tc, value);
            }
        }
        for (int pass = 0; pass < 4; pass++) {
            dilate(density, covered, size);
        }
        float[] out = new float[4 * size * size];
        for (int i = 0; i < density.length; i++) {
            fill(out, 4 * i, density[i], density[i], density[i], 1);
        }
        return out;
    }

    /** Draws a triangle of texture coordinates into {@code density}, keeping the larger value where they meet. */
    private static void rasterize(float[] density, boolean[] covered, int size, double[] a, double[] b, double[] c,
        float value) {
        double ax = a[0] * size;
        double ay = a[1] * size;
        double bx = b[0] * size;
        double by = b[1] * size;
        double cx = c[0] * size;
        double cy = c[1] * size;
        double area = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax);
        if (Math.abs(area) < 1e-9) {
            return;
        }
        int x0 = Math.max(0, (int) Math.floor(Math.min(ax, Math.min(bx, cx))));
        int x1 = Math.min(size - 1, (int) Math.ceil(Math.max(ax, Math.max(bx, cx))));
        int y0 = Math.max(0, (int) Math.floor(Math.min(ay, Math.min(by, cy))));
        int y1 = Math.min(size - 1, (int) Math.ceil(Math.max(ay, Math.max(by, cy))));
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                double px = x + 0.5;
                double py = y + 0.5;
                double w0 = ((bx - px) * (cy - py) - (by - py) * (cx - px)) / area;
                double w1 = ((cx - px) * (ay - py) - (cy - py) * (ax - px)) / area;
                double w2 = 1 - w0 - w1;
                if (w0 >= -0.02 && w1 >= -0.02 && w2 >= -0.02) {
                    int at = y * size + x;
                    density[at] = covered[at] ? Math.max(density[at], value) : value;
                    covered[at] = true;
                }
            }
        }
    }

    /** Grows the covered texels out by one: an uncovered texel takes the largest of its covered neighbours. */
    private static void dilate(float[] density, boolean[] covered, int size) {
        boolean[] was = covered.clone();
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                if (was[y * size + x]) {
                    continue;
                }
                for (int k = 0; k < 4; k++) {
                    int nx = x + (k == 0 ? -1 : k == 1 ? 1 : 0);
                    int ny = y + (k == 2 ? -1 : k == 3 ? 1 : 0);
                    if (nx >= 0 && ny >= 0 && nx < size && ny < size && was[ny * size + nx]) {
                        density[y * size + x] = Math.max(density[y * size + x], density[ny * size + nx]);
                        covered[y * size + x] = true;
                    }
                }
            }
        }
    }

    /** The mesh vertex of byte {@code corner} of the triangles, of a meshlet whose vertices start at {@code first}. */
    private static int vertex(MemorySegment vertices, MemorySegment triangles, int first, int corner) {
        int local = Byte.toUnsignedInt(triangles.get(ValueLayout.JAVA_BYTE, corner));
        return vertices.get(ValueLayout.JAVA_INT_UNALIGNED, 4L * (first + local));
    }

    /** The squared distance of texel ({@code x}, {@code y})'s center from the middle, 1 at the edge's middle. */
    private static double r2(int x, int y, int size) {
        double u = (x + 0.5) / size * 2 - 1;
        double v = (y + 0.5) / size * 2 - 1;
        return u * u + v * v;
    }

    private static void fill(float[] out, int at, double r, double g, double b, double a) {
        out[at] = (float) r;
        out[at + 1] = (float) g;
        out[at + 2] = (float) b;
        out[at + 3] = (float) a;
    }

    /** A sum of {@code octaves} of value noise, each twice the frequency and half the weight; about 0 to 1. */
    static double fbm(double x, double y, double z, int octaves) {
        double sum = 0;
        double weight = 0.5;
        double total = 0;
        for (int o = 0; o < octaves; o++) {
            sum += weight * noise(x, y, z, o);
            total += weight;
            x = 2.03 * x + 17.1;
            y = 2.03 * y + 31.7;
            z = 2.03 * z + 7.3;
            weight *= 0.5;
        }
        return sum / total;
    }

    /** Value noise: hashed values on the integer lattice, interpolated quintically; 0 to 1. */
    static double noise(double x, double y, double z, int seed) {
        int ix = (int) Math.floor(x);
        int iy = (int) Math.floor(y);
        int iz = (int) Math.floor(z);
        double fx = fade(x - ix);
        double fy = fade(y - iy);
        double fz = fade(z - iz);
        double x00 = lerp(lattice(ix, iy, iz, seed), lattice(ix + 1, iy, iz, seed), fx);
        double x10 = lerp(lattice(ix, iy + 1, iz, seed), lattice(ix + 1, iy + 1, iz, seed), fx);
        double x01 = lerp(lattice(ix, iy, iz + 1, seed), lattice(ix + 1, iy, iz + 1, seed), fx);
        double x11 = lerp(lattice(ix, iy + 1, iz + 1, seed), lattice(ix + 1, iy + 1, iz + 1, seed), fx);
        return lerp(lerp(x00, x10, fy), lerp(x01, x11, fy), fz);
    }

    private static double lattice(int x, int y, int z, int seed) {
        int h = com.truesim.scene.effects.Hash.combine(com.truesim.scene.effects.Hash.combine(
            com.truesim.scene.effects.Hash.combine(seed, x), y), z);
        return (h >>> 8) * 0x1.0p-24;
    }

    private static double fade(double t) {
        return t * t * t * (t * (6 * t - 15) + 10);
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    static double smoothstep(double edge0, double edge1, double x) {
        double t = Math.clamp((x - edge0) / (edge1 - edge0), 0.0, 1.0);
        return t * t * (3 - 2 * t);
    }
}
