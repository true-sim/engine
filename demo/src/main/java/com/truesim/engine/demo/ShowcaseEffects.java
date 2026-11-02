package com.truesim.engine.demo;

import com.truesim.common.math.Maths;
import com.truesim.engine.render.GpuScene;
import com.truesim.engine.render.Textures;
import com.truesim.engine.render.particles.ParticleSimulation;
import com.truesim.engine.render.particles.Particles;
import com.truesim.engine.scene.EffectBindings;
import com.truesim.engine.scene.EffectSync;
import com.truesim.engine.vulkan.FrameSlots;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.scene.core.SceneGraph;
import com.truesim.scene.effects.Bind;
import com.truesim.scene.effects.Collide;
import com.truesim.scene.effects.ColorGradient;
import com.truesim.scene.effects.Effect;
import com.truesim.scene.effects.EffectData;
import com.truesim.scene.effects.EffectParameter;
import com.truesim.scene.effects.Effects;
import com.truesim.scene.effects.FloatCurve;
import com.truesim.scene.effects.Forces;
import com.truesim.scene.effects.Init;
import com.truesim.scene.effects.Module;
import com.truesim.scene.effects.Over;
import com.truesim.scene.effects.ParticleEvent;
import com.truesim.scene.effects.ParticleWorld;
import com.truesim.scene.effects.Renderer;
import com.truesim.scene.effects.Shape;
import com.truesim.scene.effects.Spawn;

/**
 * The showcase's effects ({@link Showcase}), all at once around its arena: a bonfire in the middle and torches swaying
 * on poles around it, every fire in layers (a hot core, flame tongues from a flipbook cooling from white to red as they
 * rise, embers, lit smoke, a flickering light); a tenth of the crowd's foxes burning over their moving skins, upward
 * parts the most, and every fox kicking up dust where its feet come down; a fireworks show of launchers around the
 * arena firing in waves, rockets bursting into stars, stars into sparkles, each burst a flash lighting the ground; blasts
 * throwing rocks that tumble, bounce and cast shadows; hail and sparks bouncing and settling; a dust devil and gusting
 * wind pushing smoke about; orbs circling above with ribbons behind and beams reaching them.
 */
final class ShowcaseEffects implements AutoCloseable {

    /** The torches around the arena. */
    static final int TORCHES = 8;
    /** The fireworks' launchers, in a ring in the arena. */
    static final int LAUNCHERS = 12;
    /** The orbs circling above the arena, each with a ribbon and a beam. */
    static final int ORBS = 3;

    /** A ribbon's color. */
    static final EffectParameter.Color TINT = EffectParameter.ofColor("tint");

    /** Flames cooling over their life: white hot, yellow, orange, red, gone (times the birth color). */
    static final ColorGradient COOLING = ColorGradient.builder().linear(0, 1, 1, 1, 0).linear(0.06f, 1, 1, 1, 1)
        .linear(0.25f, 1, 0.78f, 0.45f, 1).linear(0.55f, 0.85f, 0.38f, 0.12f, 0.8f)
        .linear(0.8f, 0.5f, 0.1f, 0.025f, 0.3f).linear(1, 0.2f, 0.03f, 0.01f, 0).build();

    /** A torch's fire, in the world: it rises as the torch sways. */
    static final EffectData TORCH = fire(0.08f, 0.34f, 90, false, 8, 5);
    /** The bonfire in the middle of the arena: the torch's layers, larger. */
    static final EffectData BONFIRE = fire(0.55f, 0.85f, 300, false, 18, 14);
    /**
     * A fox on fire: the same layers born on its skin as posed, its upward-facing parts the most (a mask over its
     * texture coordinates), left behind as it runs.
     */
    static final EffectData FOX_FIRE = fire(0, 0.3f, 900, true, 6, 3);

    /** Fireworks from one launcher: one to three rockets, each bursting into stars (and a flash) as it dies. */
    static final EffectData LAUNCH = EffectData.builder()
        .emitter("rockets", e -> e.duration(0.5).spawn(Spawn.burst(0, Bind.random(1, 3.99f))).init(Shape.disc(0.3f),
                Init.velocity(Bind.random(-1.5f, 1.5f), Bind.random(17, 21), Bind.random(-1.5f, 1.5f)),
                Init.lifetime(Bind.random(1.4f, 1.8f)), Init.size(Bind.constant(0.16f)),
                Init.color(Bind.random(ColorGradient.builder().linear(0, 4, 0.8f, 0.3f, 1)
                    .linear(0.25f, 4, 2.6f, 0.6f, 1).linear(0.5f, 0.5f, 3.5f, 0.9f, 1).linear(0.75f, 0.7f, 1.2f, 4, 1)
                    .linear(1, 4, 0.6f, 3.5f, 1).build())))
            .update(Forces.gravity(), Forces.drag(0.15f))
            .event(ParticleEvent.onDeath("stars", Bind.random(90, 130)), ParticleEvent.onDeath("flash",
                Bind.constant(1)), ParticleEvent.every(0.025, "trail", Bind.constant(1)))
            .render(Renderer.billboard(Renderer.Facing.VELOCITY).additive().set("stretch", 0.06f)
                .asset("texture", EffectTextures.EMBER)))
        .emitter("trail", e -> e.capacity(600).spawn(Spawn.fromEvents()).init(Shape.sphere(0.05f),
                Init.speed(Bind.random(0.2f, 0.8f)), Init.lifetime(Bind.random(0.4f, 0.9f)),
                Init.size(Bind.random(0.05f, 0.09f)), Init.color(Bind.color(3, 2, 1.1f, 1)), Init.inherit(0.15f))
            .update(Forces.gravity(Bind.constant(0.5f)), Forces.drag(1.5f),
                Over.color(ColorGradient.builder().linear(0, 1, 1, 1, 1).linear(1, 0.6f, 0.2f, 0.05f, 0).build()))
            .render(Renderer.billboard(Renderer.Facing.POSITION).additive().asset("texture", EffectTextures.EMBER)))
        .emitter("stars", e -> e.capacity(1200).spawn(Spawn.fromEvents())
            .init(Shape.sphere(0.15f, 0), Init.speed(Bind.random(10, 14)), Init.lifetime(Bind.random(1.3f, 2)),
                Init.size(Bind.random(0.2f, 0.28f)), Init.inherit(0.1f, true, false))
            .update(Forces.gravity(Bind.constant(0.3f)), Forces.drag(2.4f),
                Over.color(ColorGradient.builder().linear(0, 1.8f, 1.8f, 1.8f, 1).linear(0.12f, 1, 1, 1, 1)
                    .linear(0.55f, 0.8f, 0.65f, 0.5f, 1).linear(1, 0.3f, 0.15f, 0.08f, 0).build()))
            .event(ParticleEvent.onDeath("sparkles", Bind.constant(4)).chance(0.35f))
            .render(Renderer.billboard(Renderer.Facing.VELOCITY).additive().set("stretch", 0.05f)
                .asset("texture", EffectTextures.EMBER), Renderer.trail(8).additive().set("width.head", 0.35f)
                .set("width.tail", 0f)))
        .emitter("sparkles", e -> e.capacity(1800).spawn(Spawn.fromEvents()).init(Shape.sphere(0.1f),
                Init.speed(Bind.random(1, 2.5f)), Init.lifetime(Bind.random(0.25f, 0.6f)),
                Init.size(Bind.random(0.06f, 0.1f)), Init.color(Bind.color(6, 5.5f, 4, 1)))
            .update(Forces.gravity(Bind.constant(0.3f)), Forces.drag(1),
                Over.color(ColorGradient.builder().linear(0, 1, 1, 1, 1).linear(0.4f, 0.3f, 0.3f, 0.3f, 1)
                    .linear(0.55f, 1, 1, 1, 1).linear(1, 0.4f, 0.3f, 0.2f, 0).build()))
            .render(Renderer.billboard(Renderer.Facing.POSITION).additive().asset("texture", EffectTextures.EMBER)))
        .emitter("flash", e -> e.capacity(4).spawn(Spawn.fromEvents()).init(Shape.point(), Init.lifetime(0.6f),
                Init.size(Bind.constant(7)), Init.color(Bind.color(2, 2, 2, 0.25f)), Init.inherit(0, true, false))
            .update(Over.color(ColorGradient.builder().linear(0, 1, 1, 1, 1).linear(0.2f, 0.55f, 0.55f, 0.55f, 1)
                .linear(1, 0, 0, 0, 0).build()))
            .render(Renderer.billboard(Renderer.Facing.POSITION).additive().asset("texture", EffectTextures.GLOW),
                Renderer.light(45, 300).set("max", 2)))
        .build();

    /**
     * A blast every four seconds: rocks thrown up, tumbling, bouncing on the terrain and settling, casting shadows (mesh
     * particles); a fireball, sparks bouncing and lighting the ground, a flash, and dust.
     */
    static final EffectData BLAST = EffectData.builder()
        .emitter("rocks", DemoEffects.DEBRIS.emitters().get(0), e -> { })
        .emitter("dust", DemoEffects.DEBRIS.emitters().get(1), e -> { })
        .emitter("fireball", e -> e.duration(4).loops(0).capacity(40).spawn(Spawn.burst(0, 28))
            .init(Shape.sphere(0.6f), Init.velocity(Bind.random(-2.5f, 2.5f), Bind.random(1.5f, 5),
                    Bind.random(-2.5f, 2.5f)), Init.lifetime(Bind.random(0.5f, 0.9f)), Init.size(Bind.random(1.1f, 1.6f)),
                Init.color(Bind.color(5, 3.6f, 2.4f, 1)))
            .update(Forces.drag(3), Forces.gravity(Bind.constant(-0.4f)),
                Over.size(FloatCurve.of(0, 0.5f, 0.3f, 1, 1, 1.3f)), Over.color(COOLING))
            .render(flames()))
        .emitter("sparks", e -> e.duration(4).loops(0).capacity(140).spawn(Spawn.burst(0, 120))
            .init(Shape.cone(0.7f, 0.3f), Init.speed(Bind.random(5, 11)), Init.lifetime(Bind.random(1.2f, 2.2f)),
                Init.size(Bind.random(0.03f, 0.05f)), Init.color(Bind.color(5, 2.2f, 0.6f, 1)))
            .update(Forces.gravity(), Forces.drag(0.3f), Over.color(ColorGradient.builder().linear(0, 1, 1, 1, 1)
                .linear(0.6f, 0.8f, 0.45f, 0.2f, 1).linear(1, 0.3f, 0.05f, 0, 0).build()))
            .collide(Collide.height().bounce(0.4f).friction(0.3f))
            .render(Renderer.billboard(Renderer.Facing.VELOCITY).additive().set("stretch", 0.06f)
                .asset("texture", EffectTextures.EMBER), Renderer.light(3, 1.5f).set("max", 12).set("fraction", 0.2f)))
        .emitter("flash", e -> e.duration(4).loops(0).capacity(2).spawn(Spawn.burst(0, 1)).init(Shape.point(),
                Init.lifetime(0.4f), Init.size(Bind.constant(6)), Init.color(Bind.color(5, 2.6f, 1, 0.5f)))
            .update(Over.color(ColorGradient.builder().linear(0, 1, 1, 1, 1).linear(1, 0, 0, 0, 0).build()))
            .render(Renderer.billboard(Renderer.Facing.POSITION).additive().asset("texture", EffectTextures.GLOW),
                Renderer.light(25, 40).set("max", 1)))
        .build();

    /** A dust devil: dusty puffs whirling up in a vortex that pulls them in, the wind leaning it, lit per pixel. */
    static final EffectData DUST_DEVIL = EffectData.builder().emitter("dust", e -> e.spawn(Spawn.rate(45))
        .init(Shape.disc(1.4f), Init.velocity(Bind.random(-0.2f, 0.2f), Bind.random(0.4f, 0.9f),
                Bind.random(-0.2f, 0.2f)), Init.lifetime(Bind.random(4, 6)), Init.size(Bind.random(0.5f, 0.9f)),
            Init.color(Bind.color(0.55f, 0.46f, 0.36f, 0.5f)))
        .update(Forces.vortex(Bind.constant(7), 2.5f, 0, 1, 0), Forces.curl(Bind.constant(0.8f), 0.5f, 0.5f, 2),
            Forces.gravity(Bind.constant(-0.08f)), Forces.drag(0.6f), Forces.wind(0.3f),
            Over.size(FloatCurve.of(0, 0.6f, 1, 2.6f)),
            Over.color(ColorGradient.builder().linear(0, 1, 1, 1, 0).linear(0.15f, 1, 1, 1, 1).linear(1, 1, 1, 1, 0)
                .build()))
        .render(smoke(0.5f))).build();

    /** A ribbon behind an orb, in its tint, narrowing to its tail. */
    static final EffectData RIBBON = EffectData.builder().parameter(TINT, 0.3f, 1.2f, 2, 1)
        .emitter("ribbon", e -> e.spawn(Spawn.rate(60))
            .init(Init.lifetime(1.6f), Init.size(Bind.constant(0.35f)), Init.color(Bind.param(TINT)))
            .update(Over.color(ColorGradient.builder().linear(0, 1, 1, 1, 1).linear(1, 1, 1, 1, 0).build()))
            .render(Renderer.ribbon().additive().set("width.tail", 0f))).build();

    private final SceneGraph scene;
    private final Effects effects;
    private final Particles particles;
    private final Textures textures;
    private final EffectSync sync;
    private final EffectBindings bindings;
    private final GpuScene instances;
    private final int rock;
    private final int wood;
    private final double x;
    private final double z;
    private final double radius;
    private final long[] torches = new long[TORCHES];
    private final int[] poles = new int[2 * TORCHES]; // instance ids: each torch's pole and head
    private final long[] orbs = new long[ORBS];
    private final double[] launchers = new double[3 * LAUNCHERS]; // x, y, z
    private final int[] fireFoxes;
    private double time;
    private int nextLaunch; // the next of the show's launches (shows repeat)
    private int launched;

    /**
     * The showcase's effects around {@code world}'s arena of {@code radius} about ({@code x}, {@code z}), on nodes of
     * its crowd's scene (which its owner updates before {@link #update}): mesh particles and fox fire drawn in the
     * world's renderer, particle lights in its lights.
     */
    ShowcaseEffects(VulkanDevice device, FrameSlots slots, DemoScene world, double x, double z, double radius)
        throws InterruptedException {
        FoxCrowd crowd = world.crowd();
        this.scene = crowd.scene();
        this.x = x;
        this.z = z;
        this.radius = radius;
        instances = world.gpuScene();
        rock = world.rock();
        wood = world.woodMaterial();
        particles = new Particles(device, slots, 128 << 20);
        textures = new Textures(device, slots);
        EffectTextures.register(particles, textures);
        EffectTextures.registerFoxMask(particles, textures, crowd.bodyMesh());
        effects = new Effects(scene);
        sync = new EffectSync(effects, particles, new ParticleWorld() { // the terrain, for collision
            @Override
            public double height(double x, double z) {
                return DemoMeshes.height(x, z);
            }

            @Override
            public boolean raycast(double x, double y, double z, double dx, double dy, double dz, double max,
                Hit hit) {
                return false;
            }
        }, null);
        particles.meshes(world.renderer(), 1024); // rocks thrown; skins burning
        particles.mesh(DemoEffects.ROCK, world.rock(), world.rockMaterial());
        particles.lights(world.lights(), 256);
        sync.meshes(crowd.sync());
        // Every fox kicks up dust where its feet come down; a tenth of them burn
        bindings = new EffectBindings(effects, crowd.animators());
        for (int f = 0; f < 4; f++) {
            bindings.onEvent(DemoCharacter.FEET[f], DemoEffects.DUST, crowd.feet()[f]).scale(DemoEffects.INTENSITY)
                .minimumWeight(0.2f);
        }
        int burning = 0;
        for (int i = 0; i < crowd.count(); i++) {
            burning += i % 10 == 7 ? 1 : 0;
        }
        fireFoxes = new int[burning];
        for (int i = 0, k = 0; i < crowd.count(); i++) {
            if (i % 10 == 7) {
                fireFoxes[k++] = i;
                effects.play(FOX_FIRE, crowd.body(i)).inheritVelocity(0.7f);
            }
        }
        // The bonfire on a ring of logs in the middle
        double ground = DemoMeshes.height(x, z);
        for (int l = 0; l < 6; l++) {
            double a = l * Math.PI / 3;
            double c = Maths.cos(a / 2);
            double s = Maths.sin(a / 2);
            instances.add(rock, wood, 1, true, x + 0.45 * Maths.cos(a), ground + 0.12, z + 0.45 * Maths.sin(a),
                (float) (0.2 * c), (float) s, 0, (float) c, 0.13f, 0.13f, 0.75f); // logs, leaning in
        }
        for (int l = 0; l < 10; l++) {
            double a = l * Math.PI / 5;
            instances.add(rock, world.rockMaterial(), 1, true, x + 1.25 * Maths.cos(a), ground + 0.05,
                z + 1.25 * Maths.sin(a), 0, 0, 0, 1, 0.22f, 0.16f, 0.22f); // stones around it
        }
        effects.play(BONFIRE, x, ground + 0.15, z).inheritVelocity(0);
        // Torches on poles around the arena, swaying
        for (int t = 0; t < TORCHES; t++) {
            torches[t] = scene.create();
            poles[2 * t] = instances.add(rock, wood, 1, true, x, ground, z, 0, 0, 0, 1, 1, 1, 1);
            poles[2 * t + 1] = instances.add(rock, wood, 1, true, x, ground, z, 0, 0, 0, 1, 1, 1, 1);
        }
        for (int o = 0; o < ORBS; o++) {
            orbs[o] = scene.create();
        }
        place(0);
        scene.update();
        for (int t = 0; t < TORCHES; t++) {
            effects.play(TORCH, torches[t]).inheritVelocity(0.6f);
        }
        float[][] tints = {{0.3f, 1.2f, 2.4f}, {2.4f, 0.5f, 1.6f}, {0.6f, 2.4f, 0.8f}};
        long center = scene.create();
        scene.setTranslation(center, x, ground + 4.5, z);
        for (int o = 0; o < ORBS; o++) {
            effects.play(RIBBON, orbs[o]).set(TINT, tints[o][0], tints[o][1], tints[o][2], 1);
            sync.beamEnd(effects.play(DemoEffects.BEAM, center).id(), 0, orbs[o]);
        }
        // Launchers in a ring in the arena; blasts, hail, sparks, swirls and a dust devil just outside it
        for (int l = 0; l < LAUNCHERS; l++) {
            double a = 2 * Math.PI * l / LAUNCHERS + 0.13;
            double r = radius - 9 + 3 * (l % 2); // among the foxes, bursting over the arena
            launchers[3 * l] = x + r * Maths.cos(a);
            launchers[3 * l + 2] = z + r * Maths.sin(a);
            launchers[3 * l + 1] = DemoMeshes.height(launchers[3 * l], launchers[3 * l + 2]);
        }
        playAt(BLAST, radius + 3, 0.9);
        playAt(DemoEffects.HAIL, radius + 2.5, 2.4);
        playAt(DemoEffects.LIT_SPARKS, radius + 3, 3.3);
        playAt(DemoEffects.SWIRL, radius + 2, 4.3);
        playAt(DUST_DEVIL, radius + 4, 5.4);
        playAt(DemoEffects.LIT_SMOKE, radius + 5, 1.7);
    }

    /** Plays {@code effect} on the ground {@code r} from the arena's middle at angle {@code a}. */
    private Effect playAt(EffectData effect, double r, double a) {
        double px = x + r * Maths.cos(a);
        double pz = z + r * Maths.sin(a);
        return effects.play(effect, px, DemoMeshes.height(px, pz), pz);
    }

    /**
     * Fire in layers (see the class comment): flames from a disc of {@code radius} or a fox's skin ({@code skin},
     * through its upward mask), {@code size} across at their largest and {@code rate} a second, and a flickering light
     * reaching {@code reach} metres at {@code intensity}.
     */
    static EffectData fire(float radius, float size, float rate, boolean skin, float reach, float intensity) {
        float rise = (float) Math.sqrt(Math.max(size, 0.2f) / 0.26f); // larger fires rise faster
        return EffectData.builder()
            .emitter("core", e -> e.spawn(Spawn.rate(skin ? 260 : 18 + 40 * radius / 0.07f))
                .init(on(skin, 0.7f * radius, Init.velocity(Bind.random(-0.05f, 0.05f), Bind.random(0.2f, 0.4f),
                        Bind.random(-0.05f, 0.05f)), Init.lifetime(Bind.random(0.15f, 0.3f)),
                    Init.size(Bind.random(0.8f * size, 1.1f * size)), Init.color(Bind.color(9, 4.2f, 1.3f, 0.3f))))
                .update(Forces.drag(2), Over.color(ColorGradient.builder().linear(0, 1, 1, 1, 0)
                    .linear(0.3f, 1, 1, 1, 1).linear(1, 0.6f, 0.3f, 0.1f, 0).build()))
                .render(Renderer.billboard(Renderer.Facing.POSITION).additive().set("soft", 0.15f)
                    .asset("texture", EffectTextures.GLOW)))
            .emitter("flames", e -> e.spawn(Spawn.rate(rate))
                .init(on(skin, radius, Init.velocity(Bind.random(-0.12f, 0.12f), Bind.random(0.6f * rise,
                        1.1f * rise), Bind.random(-0.12f, 0.12f)), Init.lifetime(skin ? Bind.random(0.3f, 0.55f)
                        : Bind.random(0.45f, 0.8f)), Init.size(Bind.random(0.75f * size, 1.15f * size)),
                    Init.color(Bind.color(4.5f, 3.4f, 2.4f, skin ? 0.6f : 1))))
                .update(Forces.gravity(Bind.constant(-0.3f)), Forces.drag(1.4f),
                    Forces.curl(Bind.constant(1.6f * rise), 1.8f / rise, 1.2f, 2), Forces.wind(0.08f),
                    // drawn in towards the middle as they rise, a fire's tongues meet (on a skin, nothing to meet at)
                    Forces.vortex(Bind.constant(skin ? 0 : 0.3f), skin ? 0 : 2.5f, 0, 1, 0),
                    Over.size(FloatCurve.of(0, 0.45f, 0.25f, 1, 0.7f, 0.85f, 1, 0.35f)), Over.color(COOLING))
                .render(flames()))
            .emitter("embers", e -> e.spawn(Spawn.rate(skin ? 40 : 8 + 30 * radius))
                .init(on(skin, radius, Init.velocity(Bind.random(-0.5f, 0.5f), Bind.random(1, 2.5f),
                        Bind.random(-0.5f, 0.5f)), Init.lifetime(Bind.random(1.2f, 2.5f)),
                    Init.size(Bind.random(0.02f, 0.04f)), Init.color(Bind.color(8, 3, 0.8f, 1))))
                .update(Forces.gravity(Bind.constant(-0.08f)), Forces.drag(0.5f),
                    Forces.curl(Bind.constant(2.5f), 2.2f, 1.5f, 2), Forces.wind(0.8f),
                    Over.color(ColorGradient.builder().linear(0, 1, 1, 1, 1).linear(0.5f, 0.8f, 0.4f, 0.15f, 1)
                        .linear(1, 0.4f, 0.08f, 0, 0).build()))
                .render(Renderer.billboard(Renderer.Facing.VELOCITY).additive().set("stretch", 0.04f)
                    .asset("texture", EffectTextures.EMBER)))
            .emitter("smoke", e -> e.spawn(Spawn.rate(skin ? 14 : 4 + 14 * radius))
                .init(on(skin, radius, Init.velocity(Bind.random(-0.15f, 0.15f), Bind.random(0.8f * rise,
                        1.2f * rise), Bind.random(-0.15f, 0.15f)), Init.lifetime(Bind.random(3.5f, 5)),
                    Init.size(Bind.random(1.6f * size, 2.4f * size)), Init.color(Bind.color(0.32f, 0.3f, 0.28f, 0.3f))))
                .update(Forces.drag(0.5f), Forces.curl(Bind.constant(0.6f), 0.6f, 0.4f, 2), Forces.wind(0.5f),
                    Forces.gravity(Bind.constant(-0.02f)), Over.size(FloatCurve.of(0, 0.5f, 1, 3.5f)),
                    Over.color(ColorGradient.builder().linear(0, 1, 1, 1, 0).linear(0.12f, 1, 1, 1, 0)
                        .linear(0.3f, 1, 1, 1, 0.75f).linear(1, 1.5f, 1.5f, 1.5f, 0).build()))
                .render(smoke(0.4f)))
            .emitter("glow", e -> e.spawn(Spawn.rate(30))
                .init(on(skin, 0.8f * radius, Init.velocity(Bind.constant(0), Bind.random(0.5f, 1),
                        Bind.constant(0)), Init.lifetime(Bind.random(0.08f, 0.16f)),
                    Init.color(Bind.random(ColorGradient.builder().linear(0, 0.45f, 0.2f, 0.05f, 1)
                        .linear(1, 1, 0.5f, 0.16f, 1).build()))))
                .render(Renderer.light(reach, intensity).set("max", 1)))
            .build();
    }

    /**
     * Initializers: where fire is born (on a fox's skin through its upward mask, or on a disc of {@code radius}), then
     * {@code rest}.
     */
    private static Module[] on(boolean skin, float radius, Module... rest) {
        Module[] shape = skin ? new Module[] {Shape.mesh(Shape.SURFACE), Shape.mask(EffectTextures.FOX_MASK)}
            : new Module[] {Shape.disc(radius)};
        Module[] all = java.util.Arrays.copyOf(shape, shape.length + rest.length);
        System.arraycopy(rest, 0, all, shape.length, rest.length);
        return all;
    }

    /** Flame tongues: the flipbook, stretched along their velocity, added. */
    private static Renderer flames() {
        return Renderer.billboard(Renderer.Facing.VELOCITY).additive().set("stretch", 0.3f).set("soft", 0.12f)
            .asset("texture", EffectTextures.FLAME).set("flipbook.columns", EffectTextures.FLAME_FRAMES)
            .set("flipbook.rows", EffectTextures.FLAME_FRAMES);
    }

    /** Smoke puffs: the smoke flipbook lit per pixel by its normals, sorted, soft by {@code soft} metres. */
    private static Renderer smoke(float soft) {
        return Renderer.billboard(Renderer.Facing.POSITION).lighting(Renderer.Lighting.PER_PIXEL).set("soft", soft)
            .asset("texture", EffectTextures.SMOKE).asset("normal", EffectTextures.SMOKE_NORMALS)
            .set("flipbook.columns", EffectTextures.SMOKE_FRAMES).set("flipbook.rows", EffectTextures.SMOKE_FRAMES);
    }

    /** The torches sway on their poles, the orbs circle above the middle. */
    private void place(double t) {
        double ground = DemoMeshes.height(x, z);
        for (int i = 0; i < TORCHES; i++) {
            double a = 2 * Math.PI * (i + 0.5) / TORCHES;
            double bx = x + (radius - 1) * Maths.cos(a);
            double bz = z + (radius - 1) * Maths.sin(a);
            double by = DemoMeshes.height(bx, bz);
            // Tilted about its foot: about x and z by small swaying angles
            double tx = 0.12 * Maths.sin(1.1 * t + 1.7 * i) + 0.05 * Maths.sin(2.9 * t + i);
            double tz = 0.1 * Maths.sin(0.8 * t + 2.3 * i) + 0.04 * Maths.sin(3.3 * t + 0.5 * i);
            double angle = Maths.sqrt(tx * tx + tz * tz);
            double kx = angle > 1e-9 ? tx / angle : 1;
            double kz = angle > 1e-9 ? tz / angle : 0;
            double s = Maths.sin(angle);
            double ux = -kz * s; // the pole's up: (0, 1, 0) turned about (kx, 0, kz)
            double uy = Maths.cos(angle);
            double uz = kx * s;
            float qs = (float) Maths.sin(angle / 2);
            float qw = (float) Maths.cos(angle / 2);
            instances.set(poles[2 * i], rock, wood, bx + 0.8 * ux, by + 0.8 * uy, bz + 0.8 * uz, (float) kx * qs, 0,
                (float) kz * qs, qw, 0.05f, 0.85f, 0.05f);
            instances.set(poles[2 * i + 1], rock, wood, bx + 1.6 * ux, by + 1.6 * uy, bz + 1.6 * uz, (float) kx * qs, 0,
                (float) kz * qs, qw, 0.1f, 0.14f, 0.1f);
            scene.setTranslation(torches[i], bx + 1.72 * ux, by + 1.72 * uy, bz + 1.72 * uz);
        }
        for (int o = 0; o < ORBS; o++) {
            double a = (0.5 + 0.15 * o) * t + 2 * Math.PI * o / ORBS;
            double r = 5 + 1.5 * o;
            scene.setTranslation(orbs[o], x + r * Maths.cos(a), ground + 6 + 0.8 * Maths.sin(1.7 * t + o), z
                + r * Maths.sin(a));
        }
    }

    /**
     * The show's launches: waves every five seconds, each running round the ring of launchers, one after another, a
     * fifth of a second apart, every other wave both ways round from opposite sides.
     */
    private void launch(double t) {
        double wave = 5;
        double step = 0.2;
        while (true) {
            int w = nextLaunch / LAUNCHERS;
            int k = nextLaunch % LAUNCHERS;
            double at = w * wave + k * step;
            if (at > t) {
                return;
            }
            int l = w % 2 == 0 ? (k + 3 * w) % LAUNCHERS : (k % 2 == 0 ? k / 2 : LAUNCHERS - 1 - k / 2);
            effects.play(LAUNCH, launchers[3 * l], launchers[3 * l + 1], launchers[3 * l + 2]);
            launched++;
            nextLaunch++;
        }
    }

    /** The foxes on fire: their numbers in the crowd. */
    int[] fireFoxes() {
        return fireFoxes;
    }

    /** How many fireworks launches so far. */
    int launched() {
        return launched;
    }

    Particles particles() {
        return particles;
    }

    EffectSync sync() {
        return sync;
    }

    ParticleSimulation simulation() {
        return new ParticleSimulation(particles);
    }

    /** Moves the effects on by {@code seconds}, after the crowd's update; before the frame records. */
    void update(double seconds) {
        time += seconds;
        sync.clearViewers();
        sync.addViewer(x, DemoMeshes.height(x, z), z); // the height field around the arena
        // Gusting wind, turning slowly
        double heading = 0.15 * time;
        double strength = 1.5 + 1.2 * Maths.sin(0.7 * time) + 0.5 * Maths.sin(2.3 * time);
        effects.wind((float) (strength * Maths.cos(heading)), 0, (float) (strength * Maths.sin(heading)));
        place(time);
        launch(time);
        bindings.update(); // after the crowd's animators
        scene.update(); // the torches and orbs where they are now
        sync.update(seconds);
    }

    @Override
    public void close() {
        particles.close();
        textures.close();
    }
}
