package com.truesim.engine.demo;

import com.truesim.common.math.Maths;
import com.truesim.common.math.color.Color;
import com.truesim.engine.render.GeometryPool;
import com.truesim.engine.render.GpuScene;
import com.truesim.engine.render.Lights;
import com.truesim.engine.render.Material;
import com.truesim.engine.render.Materials;
import com.truesim.engine.render.MeshletRenderer;
import com.truesim.engine.render.SceneGeometry;
import com.truesim.engine.render.materials.MetallicRoughnessParameters;
import com.truesim.engine.vulkan.FrameSlots;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.scene.assets.mesh.MeshData;
import java.util.SplittableRandom;

/**
 * What the demo draws: rolling terrain with rocks on it, some of them spinning (instances changing every frame), lit by
 * the sun and colored lights circling the middle (lights changing every frame). With
 * {@code -Dtruesim.demo.character=<path to the Khronos Fox.gltf>}, an animated fox stands among them
 * ({@link DemoCharacter}).
 */
final class DemoScene implements AutoCloseable {

    static final int TERRAIN_SIZE = 256;
    /** Where the fox stands, if there is one: in front of the benchmark's camera. */
    static final double CHARACTER_X = 26;
    static final double CHARACTER_Z = 26;
    private static final int ROCKS = Integer.getInteger("truesim.demo.rocks", 800); // more for benchmarks
    private static final int POINT_LIGHTS = 6;

    private final GeometryPool pool;
    private final GpuScene scene;
    private final Materials materials;
    private final MeshletRenderer renderer;
    private final Lights lights;
    private final SceneGeometry geometry;
    private final int rock;
    private final float[] rocks = new float[ROCKS * 5]; // x, y, z, scale, spin speed (0: still)
    private final int[] rockMaterials = new int[ROCKS];
    private final DemoCharacter character;
    private final FoxCrowd crowd;

    DemoScene(VulkanDevice device, FrameSlots slots, int colorFormat, int depthFormat) {
        pool = new GeometryPool(device, GeometryPool.Capacity.of(1 << 21, 1 << 16, 8), slots);
        System.out.println("Building meshes (cluster hierarchies)...");
        MeshData terrainMesh = DemoMeshes.terrain(TERRAIN_SIZE);
        int terrain = pool.add(terrainMesh);
        rock = pool.add(DemoMeshes.rock(4));
        materials = new Materials(device, 64, slots);
        MetallicRoughnessParameters base = MetallicRoughnessParameters.DEFAULTS;
        int ground = materials.add(new Material(base.withBaseColor(new Color(0.35f, 0.45f, 0.25f, 1))
            .withRoughness(0.9f)));
        int[] palette = {
            materials.add(new Material(base.withBaseColor(new Color(0.55f, 0.5f, 0.45f, 1)).withRoughness(0.8f))),
            materials.add(new Material(base.withBaseColor(new Color(0.8f, 0.3f, 0.2f, 1)).withRoughness(0.5f))),
            materials.add(new Material(base.withBaseColor(new Color(0.9f, 0.8f, 0.3f, 1)).withMetallic(1)
                .withRoughness(0.3f))),
            materials.add(new Material(base.withBaseColor(new Color(0.2f, 0.4f, 0.9f, 1)).withRoughness(0.2f))),
            materials.add(new Material(base.withBaseColor(new Color(1, 1, 1, 1))
                .withEmissive(new Color(4, 2.5f, 1, 1)))), // glowing: bright for HDR
        };
        scene = new GpuScene(device, pool, ROCKS + 16, slots);
        scene.setMaterials(materials);
        renderer = new MeshletRenderer(device, scene, colorFormat, depthFormat, slots);
        geometry = SceneGeometry.meshlets(renderer);
        scene.set(0, terrain, ground, 0f, 0f, 0f, 0f, 0f, 0f, 1f, 1f, 1f, 1f);
        SplittableRandom random = new SplittableRandom(7);
        boolean clearing = System.getProperty("truesim.demo.character") != null;
        for (int i = 0; i < ROCKS; i++) {
            double x = random.nextDouble(-TERRAIN_SIZE / 2.0 + 4, TERRAIN_SIZE / 2.0 - 4);
            double z = random.nextDouble(-TERRAIN_SIZE / 2.0 + 4, TERRAIN_SIZE / 2.0 - 4);
            double dx = x - CHARACTER_X;
            double dz = z - CHARACTER_Z;
            double away = Math.sqrt(dx * dx + dz * dz);
            if (clearing && away < 12) { // a clearing for the fox: rocks pushed out of it
                x = CHARACTER_X + dx / Math.max(away, 1e-3) * 12;
                z = CHARACTER_Z + dz / Math.max(away, 1e-3) * 12;
            }
            float scale = (float) (0.3 + 1.7 * Maths.pow(random.nextDouble(), 3));
            rocks[5 * i] = (float) x;
            rocks[5 * i + 1] = (float) DemoMeshes.height(x, z) + scale * 0.6f;
            rocks[5 * i + 2] = (float) z;
            rocks[5 * i + 3] = scale;
            rocks[5 * i + 4] = i % 8 == 0 ? (float) random.nextDouble(-2, 2) : 0;
            rockMaterials[i] = palette[i % 23 == 0 ? 4 : random.nextInt(4)];
        }
        lights = new Lights(device, POINT_LIGHTS + 1, 8, slots);
        String file = System.getProperty("truesim.demo.character");
        try {
            character = file == null ? null : new DemoCharacter(device, slots, pool, materials, scene,
                java.nio.file.Path.of(file), CHARACTER_X, CHARACTER_Z);
            int foxes = Integer.getInteger("truesim.demo.foxes", 0);
            crowd = foxes == 0 ? null : new FoxCrowd(device, slots, pool, materials, scene, java.nio.file.Path.of(
                System.getProperty("truesim.demo.crowd", file == null ? "Fox.gltf" : file)), foxes,
                Boolean.getBoolean("truesim.demo.foxLod"), 30, 30);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        update(0);
    }

    SceneGeometry geometry() {
        return geometry;
    }

    MeshletRenderer renderer() {
        return renderer;
    }

    Lights lights() {
        return lights;
    }

    /** The world at {@code seconds}: rocks spun, lights moved. Before the frame records. */
    void update(double seconds) {
        for (int i = 0; i < ROCKS; i++) {
            float angle = (float) (rocks[5 * i + 4] * seconds + i);
            float s = rocks[5 * i + 3];
            scene.set(i + 1, rock, rockMaterials[i], rocks[5 * i], rocks[5 * i + 1], rocks[5 * i + 2], 0,
                (float) Maths.sin(angle / 2), 0, (float) Maths.cos(angle / 2), s, s, s);
        }
        lights.directional(0, -0.4f, -0.8f, -0.45f, 2.5f, 2.3f, 2.0f);
        for (int l = 0; l < POINT_LIGHTS; l++) {
            double a = seconds * 0.3 + l * 2 * Math.PI / POINT_LIGHTS;
            double x = 25 * Maths.cos(a);
            double z = 25 * Maths.sin(a);
            float r = l % 3 == 0 ? 8 : 1;
            float g = l % 3 == 1 ? 8 : 1;
            float b = l % 3 == 2 ? 8 : 1;
            lights.point(l + 1, x, DemoMeshes.height(x, z) + 2.5, z, 14, r, g, b);
        }
        lights.setCount(POINT_LIGHTS + 1);
        // The fox (and the crowd) look at the first circling light
        double a = seconds * 0.3;
        double lx = 25 * Maths.cos(a);
        double lz = 25 * Maths.sin(a);
        if (character != null) {
            character.update(seconds, lx, DemoMeshes.height(lx, lz) + 2.5, lz);
        }
        if (crowd != null) {
            crowd.update(seconds, lx, DemoMeshes.height(lx, lz) + 2.5, lz);
        }
    }

    /** Notes the frame just recorded, for the crowd's report. */
    void recorded() {
        if (crowd != null) {
            crowd.recorded();
        }
    }

    /** What animating the crowd cost, or null without one. */
    String crowdReport() {
        return crowd == null ? null : crowd.report();
    }

    @Override
    public void close() {
        if (crowd != null) {
            crowd.close();
        }
        if (character != null) {
            character.close();
        }
        lights.close();
        renderer.close();
        scene.close();
        materials.close();
        pool.close();
    }
}
