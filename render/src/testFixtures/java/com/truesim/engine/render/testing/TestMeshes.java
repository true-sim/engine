package com.truesim.engine.render.testing;

import com.truesim.scene.assets.mesh.MeshData;
import com.truesim.scene.assets.mesh.VertexAttribute;
import com.truesim.scene.assets.mesh.VertexFormat;
import com.truesim.scene.meshbuild.MeshBuilder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Meshes for GPU tests, built in code (no files): a quad, a box, a pair of walls of many meshlets, a bumpy rock and a
 * terrain, with smooth normals. The same in every repo's tests, so scenes and reference images agree. Each call builds
 * a new mesh, which the caller closes (or hands to a pool that copies it).
 */
public final class TestMeshes {

    private TestMeshes() {
    }

    /** A 2 x 2 quad in the xy plane facing +z, flat (no hierarchy). */
    public static MeshData quad() {
        float[] positions = {-1, -1, 0, 1, -1, 0, 1, 1, 0, -1, 1, 0};
        float[] normals = {0, 0, 1, 0, 0, 1, 0, 0, 1, 0, 0, 1};
        return new MeshBuilder(VertexFormat.of(VertexAttribute.NORMAL), 4).positions(positions)
            .attribute(VertexAttribute.NORMAL, normals).submesh(new int[] {0, 1, 2, 0, 2, 3}).buildFlat();
    }

    /** A 2 x 2 x 2 box centered on the origin, a flat normal per face (24 vertices), flat (no hierarchy). */
    public static MeshData box() {
        float[] positions = new float[24 * 3];
        float[] normals = new float[24 * 3];
        int[] indices = new int[36];
        for (int face = 0; face < 6; face++) {
            int axis = face / 2;
            float sign = face % 2 == 0 ? 1 : -1;
            int u = (axis + 1) % 3;
            int w = (axis + 2) % 3;
            float[][] corners = {{-1, -1}, {1, -1}, {1, 1}, {-1, 1}};
            for (int c = 0; c < 4; c++) {
                int v = 4 * face + c;
                positions[3 * v + axis] = sign;
                positions[3 * v + u] = corners[c][0] * sign;
                positions[3 * v + w] = corners[c][1];
                normals[3 * v + axis] = sign;
            }
            int[] q = {0, 1, 2, 0, 2, 3};
            for (int i = 0; i < 6; i++) {
                indices[6 * face + i] = 4 * face + q[i];
            }
        }
        return new MeshBuilder(VertexFormat.of(VertexAttribute.NORMAL), 24).positions(positions)
            .attribute(VertexAttribute.NORMAL, normals).submesh(indices).buildFlat();
    }

    /**
     * Two parallel 2 x 2 walls facing +z, at z = 0.5 (submesh 0) and z = -0.5 (submesh 1), each a grid of {@code n} x
     * {@code n} quads, so of many meshlets: the front and back walls of a glass bowl, for order tests.
     */
    public static MeshData walls(int n) {
        int side = (n + 1) * (n + 1);
        float[] positions = new float[2 * side * 3];
        float[] normals = new float[2 * side * 3];
        int[][] indices = new int[2][n * n * 6];
        for (int wall = 0; wall < 2; wall++) {
            for (int y = 0; y <= n; y++) {
                for (int x = 0; x <= n; x++) {
                    int v = wall * side + y * (n + 1) + x;
                    positions[3 * v] = 2f * x / n - 1;
                    positions[3 * v + 1] = 2f * y / n - 1;
                    positions[3 * v + 2] = wall == 0 ? 0.5f : -0.5f;
                    normals[3 * v + 2] = 1;
                }
            }
            int at = 0;
            for (int y = 0; y < n; y++) {
                for (int x = 0; x < n; x++) {
                    int a = wall * side + y * (n + 1) + x;
                    int[] q = {a, a + 1, a + n + 2, a, a + n + 2, a + n + 1};
                    System.arraycopy(q, 0, indices[wall], at, 6);
                    at += 6;
                }
            }
        }
        return new MeshBuilder(VertexFormat.of(VertexAttribute.NORMAL), 2 * side).positions(positions)
            .attribute(VertexAttribute.NORMAL, normals).submesh(indices[0]).submesh(indices[1]).buildFlat();
    }

    /**
     * A bumpy rock: an icosahedron subdivided {@code subdivisions} times (20 x 4^subdivisions triangles), with noise on
     * the radius. Welded, without poles or seams, so simplification is not pinned anywhere.
     */
    public static MeshData rock(int subdivisions) {
        return rockBuilder(subdivisions).buildHierarchy();
    }

    /** The rock's builder, to build it as a hierarchy or as discrete LODs. */
    public static MeshBuilder rockBuilder(int subdivisions) {
        double t = (1 + Math.sqrt(5)) / 2;
        List<double[]> vertices = new ArrayList<>(List.of(new double[] {-1, t, 0}, new double[] {1, t, 0},
            new double[] {-1, -t, 0}, new double[] {1, -t, 0}, new double[] {0, -1, t}, new double[] {0, 1, t},
            new double[] {0, -1, -t}, new double[] {0, 1, -t}, new double[] {t, 0, -1}, new double[] {t, 0, 1},
            new double[] {-t, 0, -1}, new double[] {-t, 0, 1}));
        int[] faces = {0, 11, 5, 0, 5, 1, 0, 1, 7, 0, 7, 10, 0, 10, 11, 1, 5, 9, 5, 11, 4, 11, 10, 2, 10, 7, 6, 7, 1,
            8, 3, 9, 4, 3, 4, 2, 3, 2, 6, 3, 6, 8, 3, 8, 9, 4, 9, 5, 2, 4, 11, 6, 2, 10, 8, 6, 7, 9, 8, 1};
        for (int s = 0; s < subdivisions; s++) {
            Map<Long, Integer> middles = new HashMap<>();
            int[] next = new int[faces.length * 4];
            int at = 0;
            for (int f = 0; f < faces.length; f += 3) {
                int a = faces[f];
                int b = faces[f + 1];
                int c = faces[f + 2];
                int ab = middle(vertices, middles, a, b);
                int bc = middle(vertices, middles, b, c);
                int ca = middle(vertices, middles, c, a);
                for (int i : new int[] {a, ab, ca, b, bc, ab, c, ca, bc, ab, bc, ca}) {
                    next[at++] = i;
                }
            }
            faces = next;
        }
        float[] positions = new float[vertices.size() * 3];
        for (int v = 0; v < vertices.size(); v++) {
            double[] p = vertices.get(v);
            double length = Math.sqrt(p[0] * p[0] + p[1] * p[1] + p[2] * p[2]);
            double x = p[0] / length;
            double y = p[1] / length;
            double z = p[2] / length;
            double radius = 1 + 0.15 * Math.sin(5 * x + 3 * y) * Math.cos(4 * z - 2 * x) + 0.05 * Math.sin(17 * y
                + 11 * z);
            positions[3 * v] = (float) (x * radius);
            positions[3 * v + 1] = (float) (y * radius);
            positions[3 * v + 2] = (float) (z * radius);
        }
        return new MeshBuilder(VertexFormat.of(VertexAttribute.NORMAL), vertices.size()).positions(positions)
            .attribute(VertexAttribute.NORMAL, normals(positions, faces)).submesh(faces);
    }

    /** The vertex halfway between a and b, made once per edge. */
    private static int middle(List<double[]> vertices, Map<Long, Integer> middles, int a, int b) {
        return middles.computeIfAbsent((long) Math.min(a, b) << 32 | Math.max(a, b), k -> {
            double[] p = vertices.get(a);
            double[] q = vertices.get(b);
            vertices.add(new double[] {(p[0] + q[0]) / 2, (p[1] + q[1]) / 2, (p[2] + q[2]) / 2});
            return vertices.size() - 1;
        });
    }

    /** Smooth normals of the surface: per vertex, the normalized sum of its triangles' area-weighted normals. */
    public static float[] normals(float[] positions, int[] indices) {
        double[] sums = new double[positions.length];
        for (int i = 0; i < indices.length; i += 3) {
            int a = 3 * indices[i];
            int b = 3 * indices[i + 1];
            int c = 3 * indices[i + 2];
            double ux = positions[b] - positions[a];
            double uy = positions[b + 1] - positions[a + 1];
            double uz = positions[b + 2] - positions[a + 2];
            double vx = positions[c] - positions[a];
            double vy = positions[c + 1] - positions[a + 1];
            double vz = positions[c + 2] - positions[a + 2];
            double nx = uy * vz - uz * vy;
            double ny = uz * vx - ux * vz;
            double nz = ux * vy - uy * vx;
            for (int corner : new int[] {a, b, c}) {
                sums[corner] += nx;
                sums[corner + 1] += ny;
                sums[corner + 2] += nz;
            }
        }
        float[] normals = new float[positions.length];
        for (int v = 0; v < normals.length; v += 3) {
            double length = Math.sqrt(sums[v] * sums[v] + sums[v + 1] * sums[v + 1] + sums[v + 2] * sums[v + 2]);
            for (int i = 0; i < 3; i++) {
                normals[v + i] = (float) (sums[v + i] / length);
            }
        }
        return normals;
    }

    /** A bumpy {@code n} x {@code n} grid, 1 unit per quad, centered, facing +y. */
    public static MeshData terrain(int n) {
        int count = (n + 1) * (n + 1);
        float[] positions = new float[count * 3];
        for (int z = 0; z <= n; z++) {
            for (int x = 0; x <= n; x++) {
                int v = z * (n + 1) + x;
                positions[3 * v] = x - n / 2f;
                positions[3 * v + 1] = (float) (3 * Math.sin(x / 9.0) * Math.cos(z / 13.0) + Math.sin(x * z / 4000.0));
                positions[3 * v + 2] = z - n / 2f;
            }
        }
        int[] indices = new int[n * n * 6];
        int at = 0;
        for (int z = 0; z < n; z++) {
            for (int x = 0; x < n; x++) {
                int a = z * (n + 1) + x;
                int[] quad = {a, a + n + 1, a + 1, a + 1, a + n + 1, a + n + 2};
                System.arraycopy(quad, 0, indices, at, 6);
                at += 6;
            }
        }
        return new MeshBuilder(VertexFormat.of(VertexAttribute.NORMAL), count).positions(positions)
            .attribute(VertexAttribute.NORMAL, normals(positions, indices)).submesh(indices).buildHierarchy();
    }

    /** The height of {@link #terrain}(1024), placed at the origin, at x, z. */
    public static double terrainHeight(double x, double z) {
        double gx = x + 512;
        double gz = z + 512;
        return 3 * Math.sin(gx / 9.0) * Math.cos(gz / 13.0) + Math.sin(gx * gz / 4000.0);
    }
}
