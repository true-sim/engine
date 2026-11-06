package com.truesim.engine.demo;

import com.truesim.common.math.Maths;
import com.truesim.common.math.color.Color;
import com.truesim.engine.render.GeometryPool;
import com.truesim.engine.render.GpuScene;
import com.truesim.engine.render.Lights;
import com.truesim.engine.render.Material;
import com.truesim.engine.render.Materials;
import com.truesim.engine.render.MeshletRenderer;
import com.truesim.engine.render.RayTracing;
import com.truesim.engine.render.SceneGeometry;
import com.truesim.engine.render.materials.MetallicRoughnessParameters;
import com.truesim.engine.scene.Animation;
import com.truesim.engine.vulkan.FrameSlots;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.scene.assets.mesh.MeshData;
import java.util.Objects;
import java.util.SplittableRandom;

/**
 * What the demo draws: rolling terrain with rocks on it, some of them spinning (instances changing every frame), lit by
 * the sun and colored lights circling the middle (lights changing every frame). With
 * {@code -Dtruesim.demo.character=<path to the Khronos Fox.gltf>}, an animated fox stands among them
 * ({@link DemoCharacter}). With ray tracing ({@link #DemoScene(VulkanDevice, FrameSlots, int, int, boolean)}), every
 * mesh has a bottom level: the terrain and rock as added, the fox's and the crowd's through their scene resources, their
 * skinned copies refitted as they move.
 */
final class DemoScene implements AutoCloseable {

    /** The sun's shadows: three cascades of 2048 x 2048 to 150 m. */
    static final com.truesim.scene.core.ShadowSpec SUN_SHADOWS = com.truesim.scene.core.ShadowSpec.cascades(3, 2048,
        150);

    /**
     * A showcase's arena ({@link Showcase}): rocks cleared from a circle of {@code radius} about ({@code x}, {@code z}),
     * {@code foxes} foxes from {@code fox} roaming in it ({@link FoxCrowd}), at dusk: the sun low, dim and orange, the
     * circling lights off, so fires and fireworks light the ground.
     */
    record Arena(double x, double z, double radius, java.nio.file.Path fox, int foxes) {
    }

    static final int TERRAIN_SIZE = 256;
    /** Where the fox stands, if there is one: in front of the benchmark's camera. */
    static final double CHARACTER_X = 26;
    static final double CHARACTER_Z = 26;
    private static final int ROCKS = Integer.getInteger("truesim.demo.rocks", 800); // more for benchmarks
    private static final int CIRCLING_LIGHTS = 6;
    private static final int POINT_LIGHTS = Math.max(CIRCLING_LIGHTS, Integer.getInteger("truesim.demo.lights",
        CIRCLING_LIGHTS)); // more scattered about the middle, still

    private final GeometryPool pool;
    private final GpuScene scene;
    private final Materials materials;
    private final MeshletRenderer renderer;
    private final Lights lights;
    private final SceneGeometry geometry;
    private final int rock;
    private final int pane;
    private final int glassMaterial;
    private final float[] rocks = new float[ROCKS * 5]; // x, y, z, scale, spin speed (0: still)
    private final int[] rockMaterials = new int[ROCKS];
    private final int rockMaterial;
    private final int woodMaterial;
    private final Arena arena; // or null
    private final DemoCharacter character;
    private final FoxCrowd crowd;
    private final RayTracing rayTracing; // or null
    private final Animation animation; // runs the fox's and the crowd's animators
    private final boolean ownsAnimation; // made here, for frames recorded without an engine: advanced by update
    private double last; // the seconds of the last update

    DemoScene(VulkanDevice device, FrameSlots slots, int colorFormat, int depthFormat) {
        this(device, slots, colorFormat, depthFormat, false);
    }

    /**
     * The scene in an application, its animators run by {@code animation} (the plugin, in ANIMATE): {@link #update} in
     * UPDATE sets what plays, {@link #sync} after animation draws it.
     */
    DemoScene(VulkanDevice device, FrameSlots slots, int colorFormat, int depthFormat, Animation animation) {
        this(device, slots, colorFormat, depthFormat, false, null, java.util.Objects.requireNonNull(animation));
    }

    /** The scene, with ray tracing where {@code rays} and the device has it. */
    DemoScene(VulkanDevice device, FrameSlots slots, int colorFormat, int depthFormat, boolean rays) {
        this(device, slots, colorFormat, depthFormat, rays, null, null);
    }

    /** The scene around a showcase's {@code arena}, at dusk. */
    DemoScene(VulkanDevice device, FrameSlots slots, int colorFormat, int depthFormat, Arena arena) {
        this(device, slots, colorFormat, depthFormat, false, arena, null);
    }

    private DemoScene(VulkanDevice device, FrameSlots slots, int colorFormat, int depthFormat, boolean rays,
        Arena arena, Animation animation) {
        this.ownsAnimation = animation == null;
        this.animation = ownsAnimation ? new Animation() : animation;
        this.arena = arena;
        pool = new GeometryPool(device, GeometryPool.Capacity.of(1 << 21, 1 << 16, 8), slots);
        rayTracing = rays && device.rayTracing() ? new RayTracing(device, pool, ROCKS + 16) : null;
        if (rayTracing != null && Integer.getInteger("truesim.demo.rebuildEvery") != null) {
            rayTracing.rebuildEvery(Integer.getInteger("truesim.demo.rebuildEvery")); // refits between builds
        }
        System.out.println("Building meshes (cluster hierarchies)...");
        MeshData terrainMesh = DemoMeshes.terrain(TERRAIN_SIZE);
        int terrain = pool.add(terrainMesh);
        MeshData rockMesh = DemoMeshes.rock(4);
        rock = pool.add(rockMesh);
        pane = pool.add(DemoMeshes.pane());
        if (rayTracing != null) {
            rayTracing.add(terrain, terrainMesh, terrainMesh.levels().size() - 1);
            rayTracing.add(rock, rockMesh, rockMesh.levels().size() - 1);
        }
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
        };
        rockMaterial = palette[0];
        woodMaterial = materials.add(new Material(base.withBaseColor(new Color(0.12f, 0.07f, 0.04f, 1))
            .withRoughness(0.95f)));
        glassMaterial = materials.add(new Material(base.withBaseColor(new Color(0.35f, 0.6f, 0.85f, 0.45f))
            .withRoughness(0.05f)).alphaBlend().doubleSided(true));
        scene = new GpuScene(device, pool, ROCKS + 16, slots);
        scene.setMaterials(materials);
        renderer = new MeshletRenderer(device, scene, colorFormat, depthFormat, slots);
        geometry = SceneGeometry.meshlets(renderer);
        scene.set(0, terrain, ground, 0f, 0f, 0f, 0f, 0f, 0f, 1f, 1f, 1f, 1f);
        SplittableRandom random = new SplittableRandom(7);
        boolean clearing = arena != null || System.getProperty("truesim.demo.character") != null;
        double clearX = arena == null ? CHARACTER_X : arena.x();
        double clearZ = arena == null ? CHARACTER_Z : arena.z();
        double clear = arena == null ? 12 : arena.radius();
        for (int i = 0; i < ROCKS; i++) {
            double x = random.nextDouble(-TERRAIN_SIZE / 2.0 + 4, TERRAIN_SIZE / 2.0 - 4);
            double z = random.nextDouble(-TERRAIN_SIZE / 2.0 + 4, TERRAIN_SIZE / 2.0 - 4);
            double dx = x - clearX;
            double dz = z - clearZ;
            double away = Math.sqrt(dx * dx + dz * dz);
            if (clearing && away < clear) { // a clearing for the fox (the arena): rocks pushed out of it, spread
                double out = arena == null ? clear : clear * (1 + 0.6 * away / clear);
                x = clearX + dx / Math.max(away, 1e-3) * out;
                z = clearZ + dz / Math.max(away, 1e-3) * out;
            }
            float scale = (float) (0.3 + 1.7 * Maths.pow(random.nextDouble(), 3));
            rocks[5 * i] = (float) x;
            rocks[5 * i + 1] = (float) DemoMeshes.height(x, z) + scale * 0.6f;
            rocks[5 * i + 2] = (float) z;
            rocks[5 * i + 3] = scale;
            rocks[5 * i + 4] = i % 8 == 0 ? (float) random.nextDouble(-2, 2) : 0;
            rockMaterials[i] = palette[i % 23 == 0 ? 0 : random.nextInt(4)]; // every 23rd stone: the others as they were
        }
        lights = new Lights(device, POINT_LIGHTS + 1, 8, slots);
        lights.shadow(0, SUN_SHADOWS); // the sun: drawn by the views' Shadows parts
        String file = System.getProperty("truesim.demo.character");
        try {
            character = file == null ? null : new DemoCharacter(device, slots, pool, materials, scene,
                java.nio.file.Path.of(file), CHARACTER_X, CHARACTER_Z, rayTracing,
                this.animation);
            int foxes = Integer.getInteger("truesim.demo.foxes", 0);
            crowd = arena != null ? new FoxCrowd(device, slots, pool, materials, scene, arena, this.animation)
                : foxes == 0 ? null : new FoxCrowd(device, slots, pool, materials, scene, java.nio.file.Path.of(
                System.getProperty("truesim.demo.crowd", file == null ? "Fox.gltf" : file)), foxes,
                Boolean.getBoolean("truesim.demo.foxLod"), 30, 30, rayTracing, this.animation);
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

    /** The rock's mesh in the pool. */
    int rock() {
        return rock;
    }

    /** The plain rock material. */
    int rockMaterial() {
        return rockMaterial;
    }

    /** A unit square pane facing +Z, in the pool: windows. */
    int pane() {
        return pane;
    }

    /** Tinted glass, blended: windows. */
    int glassMaterial() {
        return glassMaterial;
    }

    /** A dark wood: torches' poles. */
    int woodMaterial() {
        return woodMaterial;
    }

    /** The scene's material table. */
    Materials materials() {
        return materials;
    }

    /** The instances drawn. */
    GpuScene gpuScene() {
        return scene;
    }

    /** The crowd of foxes, or null without. */
    FoxCrowd crowd() {
        return crowd;
    }

    /** The fox, or null without. */
    DemoCharacter character() {
        return character;
    }

    /** Its ray tracing, or null without. */
    RayTracing rayTracing() {
        return rayTracing;
    }

    /** Puts extra rock {@code k} (0 to 7: the knocking rocks) at ({@code x}, {@code y}, {@code z}), unrotated. */
    void extraRock(int k, double x, double y, double z, float scale) {
        scene.set(ROCKS + 1 + Objects.checkIndex(k, 8), rock, rockMaterial, (float) x, (float) y, (float) z, 0, 0, 0, 1,
            scale, scale, scale);
    }

    /**
     * The world at {@code seconds}: rocks spun, lights moved, what the fox and the crowd do. In an application this is
     * UPDATE, and {@link #sync} follows ANIMATE; with an animation of its own, the animators run and the scene is drawn
     * here too, before the frame records.
     */
    void update(double seconds) {
        set(seconds);
        if (ownsAnimation) {
            animation.advance(Math.max(0, seconds - last));
            last = seconds;
            sync();
        }
    }

    /** Draws the fox and the crowd as animation posed them: after ANIMATE, before the frame records. */
    void sync() {
        if (character != null) {
            character.draw();
        }
        if (crowd != null) {
            crowd.draw();
        }
    }

    private void set(double seconds) {
        for (int i = 0; i < ROCKS; i++) {
            float angle = (float) (rocks[5 * i + 4] * seconds + i);
            float s = rocks[5 * i + 3];
            scene.set(i + 1, rock, rockMaterials[i], rocks[5 * i], rocks[5 * i + 1], rocks[5 * i + 2], 0,
                (float) Maths.sin(angle / 2), 0, (float) Maths.cos(angle / 2), s, s, s);
        }
        if (arena != null) {
            // Dusk: a low orange sun, the circling lights off; the crowd looks at a point circling above the middle
            lights.directional(0, -0.75f, -0.32f, -0.58f, 0.9f, 0.45f, 0.22f);
            lights.setCount(1);
            double a = seconds * 0.4;
            crowd.update(seconds, arena.x() + 6 * Maths.cos(a), DemoMeshes.height(arena.x(), arena.z()) + 2,
                arena.z() + 6 * Maths.sin(a));
            return;
        }
        lights.directional(0, -0.4f, -0.8f, -0.45f, 2.5f, 2.3f, 2.0f);
        for (int l = CIRCLING_LIGHTS; l < POINT_LIGHTS; l++) {
            // Scattered: a hash of the light's number, within 30 m of the middle, low over the ground
            double x = (((l * 2654435761L) >>> 8 & 0xffff) / 65535.0 - 0.5) * 60;
            double z = (((l * 40503L * 2654435761L) >>> 8 & 0xffff) / 65535.0 - 0.5) * 60;
            float r = 0.5f + 2.5f * ((l * 7) % 5) / 4f;
            float g = 0.5f + 2.5f * ((l * 3) % 7) / 6f;
            float b = 0.5f + 2.5f * ((l * 5) % 3) / 2f;
            lights.point(l + 1, x, DemoMeshes.height(x, z) + 0.6, z, 3.5f, r, g, b);
        }
        for (int l = 0; l < CIRCLING_LIGHTS; l++) {
            double a = seconds * 0.3 + l * 2 * Math.PI / CIRCLING_LIGHTS;
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
        if (ownsAnimation) {
            animation.stop();
        }
        if (crowd != null) {
            crowd.close();
        }
        if (character != null) {
            character.close();
        }
        lights.close();
        renderer.close();
        if (rayTracing != null) {
            rayTracing.close();
        }
        scene.close();
        materials.close();
        pool.close();
    }
}
