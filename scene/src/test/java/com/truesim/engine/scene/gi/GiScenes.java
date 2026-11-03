package com.truesim.engine.scene.gi;

import com.truesim.common.math.color.Color;
import com.truesim.engine.render.Camera;
import com.truesim.engine.render.Environment;
import com.truesim.engine.render.GeometryPool;
import com.truesim.engine.render.GpuScene;
import com.truesim.engine.render.Lights;
import com.truesim.engine.render.Material;
import com.truesim.engine.render.Materials;
import com.truesim.engine.render.RayTracing;
import com.truesim.engine.render.materials.MetallicRoughnessParameters;
import com.truesim.scene.assets.mesh.MeshData;
import com.truesim.scene.assets.mesh.VertexAttribute;
import com.truesim.scene.assets.mesh.VertexFormat;
import com.truesim.scene.meshbuild.MeshBuilder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Global illumination's test scenes, built in code: exact and repeatable, by name, for tests, benchmarks and the
 * reference images every GI stage compares against ({@link GiReferences}). Each is boxes (a unit cube with its faces'
 * normals, scaled and placed) of plain metallic-roughness materials, lit by the sun, an environment, point lights or
 * emissive surfaces; dynamic ones have states (0, then 1: a light toggled, a door opened, an object moved) for the lag
 * targets (design D3). Units are metres, y up.
 *
 * <ul>
 *   <li>{@code sun-room}: a closed room lit by the sun and the sky through one window: mostly bounced light;
 *   <li>{@code leak-5cm}, {@code leak-25cm}, {@code leak-50cm}: a closed dark room beside an open sunlit one, the wall
 *   between 5, 25 or 50 cm thick (below, at and above a 0.5 m probe spacing's half), the camera in the dark room
 *   looking at the wall and its corners: the reference is black, any light is a leak;
 *   <li>{@code bleeding}: a closed room of strongly colored walls and floor lit by a ceiling light;
 *   <li>{@code screens}: a dark closed room lit only by emissive panels (a blue screen, a warm strip);
 *   <li>{@code toggle}: a closed room lit by a point light, on in state 0 and off in 1;
 *   <li>{@code door}: a closed room, sunlight outside its door, the door shut in state 0 and open in 1;
 *   <li>{@code mover}: a room lit through a window, a red block beside a white wall, moved 2 m in state 1;
 *   <li>{@code cornell}: the Cornell box (red and green walls, a ceiling light), two blocks inside.
 * </ul>
 */
public final class GiScenes {

    /** What a scene is built into: the renderer's scene and its parts, the camera, lights and environment to set. */
    public static final class Stage {
        public final GeometryPool pool;
        public final GpuScene scene;
        public final Materials materials;
        public final Lights lights;
        public final Camera camera;
        public final int width;
        public final int height;
        final int box;
        int next;          // the next instance id
        int lightCount;
        public Environment environment = Environment.uniform("none", 0, 0, 0);
        public boolean sun; // light 0 is a directional light with ray-traced shadows (entry 0)

        public Stage(GeometryPool pool, GpuScene scene, Materials materials, Lights lights, RayTracing rayTracing,
            Camera camera, int width, int height) {
            this.pool = pool;
            this.scene = scene;
            this.materials = materials;
            this.lights = lights;
            this.camera = camera;
            this.width = width;
            this.height = height;
            MeshData cube = cube();
            this.box = pool.add(cube);
            rayTracing.add(box, cube, cube.levels().size() - 1);
        }

        /** Empties the stage for another scene. */
        public void clear() {
            scene.removeFrom(0);
            next = 0;
            lightCount = 0;
            lights.setCount(0);
            sun = false;
            environment = Environment.uniform("none", 0, 0, 0);
        }

        /** A matte material of color (r, g, b), roughness 0.8, giving off (er, eg, eb). */
        public int matte(float r, float g, float b, float er, float eg, float eb) {
            return materials.add(new Material(MetallicRoughnessParameters.DEFAULTS.withBaseColor(new Color(r, g, b,
                1)).withRoughness(0.8f).withEmissive(new Color(er, eg, eb, 1))));
        }

        public int matte(float r, float g, float b) {
            return matte(r, g, b, 0, 0, 0);
        }

        /** A box from (x0, y0, z0) to (x1, y1, z1) of {@code material}, turned {@code yaw} radians about y. */
        public void box(int material, double x0, double y0, double z0, double x1, double y1, double z1, double yaw) {
            scene.set(next++, box, material, (float) ((x0 + x1) / 2), (float) ((y0 + y1) / 2), (float) ((z0 + z1) / 2),
                0, (float) Math.sin(yaw / 2), 0, (float) Math.cos(yaw / 2), (float) ((x1 - x0) / 2),
                (float) ((y1 - y0) / 2), (float) ((z1 - z0) / 2));
        }

        public void box(int material, double x0, double y0, double z0, double x1, double y1, double z1) {
            box(material, x0, y0, z0, x1, y1, z1, 0);
        }

        /** The sun (light 0) shining along (dx, dy, dz) with irradiance (r, g, b), its shadows ray traced. */
        public void sun(float dx, float dy, float dz, float r, float g, float b) {
            lights.directional(0, dx, dy, dz, r, g, b);
            lightCount = Math.max(lightCount, 1);
            lights.setCount(lightCount);
            sun = true;
        }

        /** A point light (after the sun, if any) at (x, y, z), irradiance (r, g, b) at 1 m, reaching {@code range}. */
        public void point(double x, double y, double z, float range, float r, float g, float b) {
            int index = sun ? Math.max(1, lightCount) : lightCount;
            lights.point(index, x, y, z, range, r, g, b);
            lightCount = index + 1;
            lights.setCount(lightCount);
        }

        /** The camera at (x, y, z) looking along (fx, fy, fz), {@code fov} degrees vertically. */
        public void look(double x, double y, double z, double fx, double fy, double fz, double fov) {
            camera.position(x, y, z).set(fx, fy, fz, 0, 1, 0, Math.toRadians(fov), width, height, 0.02);
        }

        /**
         * A closed shell around the room from (x0, y0, z0) to (x1, y1, z1), walls {@code t} thick, of the materials
         * given (-x, +x, floor, ceiling, -z, +z; -1: that side left open), with an opening in any wall given by
         * {@code holes}: per side null or {a0, a1, y0, y1} (along the wall: z for the x walls, x for the z walls).
         */
        public void room(double x0, double y0, double z0, double x1, double y1, double z1, double t, int[] m,
            double[][] holes) {
            if (m[2] >= 0) {
                box(m[2], x0 - t, y0 - t, z0 - t, x1 + t, y0, z1 + t);
            }
            if (m[3] >= 0) {
                box(m[3], x0 - t, y1, z0 - t, x1 + t, y1 + t, z1 + t);
            }
            wall(m[0], true, x0 - t, x0, y0, y1, z0 - t, z1 + t, holes == null ? null : holes[0]);
            wall(m[1], true, x1, x1 + t, y0, y1, z0 - t, z1 + t, holes == null ? null : holes[1]);
            wall(m[4], false, z0 - t, z0, y0, y1, x0, x1, holes == null ? null : holes[4]);
            wall(m[5], false, z1, z1 + t, y0, y1, x0, x1, holes == null ? null : holes[5]);
        }

        /** A wall slab across {@code a0} to {@code a1} (z for x walls), around a hole if given. */
        private void wall(int material, boolean xWall, double w0, double w1, double y0, double y1, double a0, double a1,
            double[] hole) {
            if (material < 0) {
                return;
            }
            List<double[]> pieces = new ArrayList<>();
            if (hole == null) {
                pieces.add(new double[] {a0, a1, y0, y1});
            } else {
                pieces.add(new double[] {a0, hole[0], y0, y1});
                pieces.add(new double[] {hole[1], a1, y0, y1});
                pieces.add(new double[] {hole[0], hole[1], y0, hole[2]});
                pieces.add(new double[] {hole[0], hole[1], hole[3], y1});
            }
            for (double[] p : pieces) {
                if (p[1] - p[0] < 1e-6 || p[3] - p[2] < 1e-6) {
                    continue;
                }
                if (xWall) {
                    box(material, w0, p[2], p[0], w1, p[3], p[1]);
                } else {
                    box(material, p[0], p[2], w0, p[1], p[3], w1);
                }
            }
        }

        /** Done placing: the instances past the last placed are removed. */
        void finish() {
            scene.removeFrom(next);
        }
    }

    /** A scene: built into a stage in {@code state} (0, or 1 for the dynamic scenes' change). */
    @FunctionalInterface
    public interface Scene {
        void build(Stage stage, int state);
    }

    /** Every scene by name, in order; the dynamic ones have states 0 and 1. */
    public static final Map<String, Scene> ALL = scenes();

    /** The scenes with a state 1 (dynamic: a change to respond to). */
    public static final List<String> DYNAMIC = List.of("toggle", "door", "mover");

    private GiScenes() {
    }

    /** Builds scene {@code name} in {@code state} into {@code stage}, cleared first. */
    public static void build(String name, Stage stage, int state) {
        Scene scene = ALL.get(name);
        if (scene == null) {
            throw new IllegalArgumentException("No GI scene " + name + ": " + ALL.keySet());
        }
        stage.clear();
        scene.build(stage, state);
        stage.finish();
    }

    private static Map<String, Scene> scenes() {
        Map<String, Scene> all = new LinkedHashMap<>();
        all.put("sun-room", (s, state) -> {
            int white = s.matte(0.8f, 0.8f, 0.8f);
            int floor = s.matte(0.55f, 0.5f, 0.45f);
            // 6 x 3 x 6, a window 1.5 x 1.2 in the +x wall; the sun low through it, the sky outside
            s.room(-3, 0, -6, 3, 3, 0, 0.2, new int[] {white, white, floor, white, white, white},
                new double[][] {null, {-4, -2.5, 1, 2.2}, null, null, null, null});
            s.box(s.matte(0.3f, 0.35f, 0.5f), -2.2, 0, -5.2, -0.8, 0.8, -4.2); // a block for contact shade
            s.sun(-0.75f, -0.45f, 0.3f, 5, 4.6f, 4.0f);
            s.environment = Environment.uniform("sky", 0.25f, 0.32f, 0.45f);
            s.look(-2.6, 1.5, -0.4, 0.9, -0.25, -0.7, 70);
        });
        leak(all, "leak-5cm", 0.05);
        leak(all, "leak-25cm", 0.25);
        leak(all, "leak-50cm", 0.5);
        all.put("bleeding", (s, state) -> {
            int red = s.matte(0.75f, 0.06f, 0.05f);
            int green = s.matte(0.08f, 0.6f, 0.1f);
            int blue = s.matte(0.1f, 0.15f, 0.7f);
            int floor = s.matte(0.8f, 0.55f, 0.1f);
            int white = s.matte(0.8f, 0.8f, 0.8f);
            s.room(-2.5, 0, -5, 2.5, 3, 0, 0.2, new int[] {red, green, floor, white, blue, white}, null);
            s.box(s.matte(0, 0, 0, 12, 11, 9), -0.6, 2.95, -3.1, 0.6, 3.0, -1.9); // a ceiling light
            s.box(white, -0.5, 0, -3.5, 0.5, 1.2, -2.5, 0.4);
            s.look(0, 1.5, -0.3, 0, -0.2, -1, 75);
        });
        all.put("screens", (s, state) -> {
            int grey = s.matte(0.6f, 0.6f, 0.6f);
            int dark = s.matte(0.15f, 0.15f, 0.15f);
            s.room(-3, 0, -6, 3, 2.8, 0, 0.2, new int[] {grey, grey, dark, grey, grey, grey}, null);
            s.box(s.matte(0, 0, 0, 0.6f, 1.2f, 4.0f), -1.0, 0.9, -5.98, 1.0, 2.1, -5.92); // a blue screen
            s.box(s.matte(0, 0, 0, 3.0f, 1.6f, 0.6f), 2.92, 0.1, -4.5, 2.98, 0.25, -1.5);  // a warm strip
            s.box(s.matte(0.4f, 0.3f, 0.25f), -1.2, 0, -3.2, 1.2, 0.5, -2.4);              // a couch
            s.look(-1.8, 1.3, -0.5, 0.45, -0.15, -1, 70);
        });
        all.put("toggle", (s, state) -> {
            int white = s.matte(0.8f, 0.8f, 0.8f);
            int floor = s.matte(0.6f, 0.45f, 0.3f);
            s.room(-3, 0, -6, 3, 3, 0, 0.2, new int[] {white, white, floor, white, white, white}, null);
            s.box(s.matte(0.2f, 0.4f, 0.7f), -0.8, 0, -4, 0.8, 1.0, -3);
            if (state == 0) {
                s.point(-1.5, 2.2, -4.5, 12, 6, 5.5f, 5);
            }
            s.look(-2.4, 1.6, -0.4, 0.6, -0.25, -1, 70);
        });
        all.put("door", (s, state) -> {
            int white = s.matte(0.8f, 0.8f, 0.8f);
            int floor = s.matte(0.55f, 0.5f, 0.45f);
            // A doorway 1 x 2.1 in the -z wall, sunlight outside on a ground; the door hinged at its left edge
            s.room(-3, 0, -6, 3, 3, 0, 0.2, new int[] {white, white, floor, white, white, white},
                new double[][] {null, null, null, null, {-0.5, 0.5, 0, 2.1}, null});
            s.box(floor, -10, -0.2, -20, 10, 0, -6.2);
            double yaw = state == 0 ? 0 : -Math.PI / 2;
            double w = 1.0;
            double cx = -0.5 + Math.cos(yaw) * w / 2;
            double cz = -6.1 - Math.sin(yaw) * w / 2;
            s.box(s.matte(0.45f, 0.3f, 0.2f), cx - w / 2, 0, cz - 0.04, cx + w / 2, 2.1, cz + 0.04, yaw);
            s.sun(0.2f, -0.6f, 0.75f, 6, 5.5f, 4.8f);
            s.environment = Environment.uniform("sky", 0.25f, 0.32f, 0.45f);
            s.look(1.5, 1.6, -0.5, -0.45, -0.2, -1, 70);
        });
        all.put("mover", (s, state) -> {
            int white = s.matte(0.85f, 0.85f, 0.85f);
            int floor = s.matte(0.5f, 0.5f, 0.5f);
            s.room(-3, 0, -6, 3, 3, 0, 0.2, new int[] {white, white, floor, white, white, white},
                new double[][] {null, null, null, null, null, null});
            s.box(white, -3, 0, -6, 3, 3, -5.99); // the back wall's face, lit
            double x = state == 0 ? -1.5 : 0.5;
            s.box(s.matte(0.85f, 0.05f, 0.05f), x, 0, -5.6, x + 1, 1.5, -5.0);
            s.point(0.5, 2.6, -2, 15, 8, 8, 8);
            s.look(0, 1.5, -0.3, 0, -0.15, -1, 70);
        });
        all.put("cornell", (s, state) -> {
            int white = s.matte(0.725f, 0.71f, 0.68f);
            int red = s.matte(0.63f, 0.065f, 0.05f);
            int green = s.matte(0.14f, 0.45f, 0.091f);
            s.room(-1, 0, -2, 1, 2, 0, 0.1, new int[] {red, green, white, white, white, -1}, null);
            s.box(s.matte(0, 0, 0, 17, 12, 4), -0.25, 1.98, -1.25, 0.25, 2.0, -0.75);
            s.box(white, -0.65, 0, -1.6, -0.05, 1.2, -1.0, 0.3);
            s.box(white, 0.05, 0, -1.0, 0.65, 0.6, -0.4, -0.3);
            s.look(0, 1, 2.6, 0, 0, -1, 40);
        });
        return all;
    }

    /** Two rooms sharing a wall {@code t} thick: the right one open to the sun above, the camera in the closed left. */
    private static void leak(Map<String, Scene> all, String name, double t) {
        all.put(name, (s, state) -> {
            int white = s.matte(0.8f, 0.8f, 0.8f);
            double h = t / 2;
            // The dark room: x from -4 to -h, closed; the sunlit one: x from h to 4, no ceiling
            s.room(-4, 0, -4, -h, 2.5, 0, 0.3, new int[] {white, -1, white, white, white, white}, null);
            s.room(h, 0, -4, 4, 2.5, 0, 0.3, new int[] {-1, white, white, -1, white, white}, null);
            s.box(white, -h, -0.3, -4.3, h, 2.8, 0.3); // the wall between, floor to ceiling and past both
            s.sun(0.35f, -0.9f, -0.25f, 8, 7.5f, 7);
            s.environment = Environment.uniform("sky", 0.3f, 0.38f, 0.5f);
            s.look(-3.6, 1.2, -0.3, 0.8, -0.15, -0.55, 75);
        });
    }

    /** A cube from -1 to 1, each face's own vertices and normal, counter-clockwise from outside. */
    static MeshData cube() {
        float[] positions = new float[72];
        float[] normals = new float[72];
        int[] indices = new int[36];
        int[][] axes = {{0, 1, 2}, {1, 2, 0}, {2, 0, 1}};
        int v = 0;
        int i = 0;
        for (int[] axis : axes) {
            for (int sign = -1; sign <= 1; sign += 2) {
                int first = v;
                float[][] corners = {{-1, -1}, {1, -1}, {1, 1}, {-1, 1}};
                for (float[] c : corners) {
                    float[] p = new float[3];
                    p[axis[0]] = sign;
                    p[axis[1]] = c[0];
                    p[axis[2]] = c[1];
                    System.arraycopy(p, 0, positions, 3 * v, 3);
                    normals[3 * v + axis[0]] = sign;
                    v++;
                }
                // (axis1, axis2) counter-clockwise seen from +axis0 when sign > 0
                int[] quad = sign > 0 ? new int[] {0, 1, 2, 0, 2, 3} : new int[] {0, 2, 1, 0, 3, 2};
                for (int q : quad) {
                    indices[i++] = first + q;
                }
            }
        }
        return new MeshBuilder(VertexFormat.of(VertexAttribute.NORMAL), 24).positions(positions)
            .attribute(VertexAttribute.NORMAL, normals).submesh(indices).buildFlat();
    }

    /** Runs {@code action} for each scene and state named by {@code names} (comma-separated; null or empty: all). */
    public static void forEach(String names, Consumer<String[]> action) {
        List<String> chosen = names == null || names.isBlank() ? List.copyOf(ALL.keySet()) : List.of(names.split(","));
        for (String name : chosen) {
            action.accept(new String[] {name, "0"});
            if (DYNAMIC.contains(name)) {
                action.accept(new String[] {name, "1"});
            }
        }
    }
}
