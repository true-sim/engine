package com.truesim.engine.scene;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.app.Frame;
import com.truesim.engine.app.Plugin;
import com.truesim.engine.app.PluginKey;
import com.truesim.engine.app.Stage;
import com.truesim.engine.app.Startup;
import com.truesim.engine.graph.FrameGraph;
import com.truesim.engine.graph.Rendering;
import com.truesim.engine.render.Lights;
import com.truesim.engine.render.MeshletRenderer;
import com.truesim.engine.render.RayTracing;
import com.truesim.engine.render.SceneView;
import com.truesim.engine.render.SceneViews;
import com.truesim.engine.render.particles.ParticleCollisionDepth;
import com.truesim.engine.render.particles.ParticleDraw;
import com.truesim.engine.render.particles.ParticleSimulation;
import com.truesim.engine.render.particles.Particles;
import com.truesim.engine.settings.EngineSettings;
import com.truesim.engine.settings.Setting;
import com.truesim.scene.animation.Animators;
import com.truesim.scene.core.SceneGraph;
import com.truesim.scene.effects.Effects;
import com.truesim.scene.effects.Quality;
import java.util.List;
import java.util.Objects;

/**
 * Particle effects as a plugin (effects.md §2.2): the GPU side ({@link Particles}) with its simulation in the frame
 * graph, a world's {@link Effects} synced every frame after the game's update ({@link EffectSync}), with its
 * {@link EffectBindings} from animation evaluated first, a {@link ParticleDraw} per scene view on request, and the
 * quality setting {@link #QUALITY}. Named so beside scene-effects' {@code Effects}, the store it fills.
 *
 * <pre>{@code
 * app.use(new Rendering(), new EffectsPlugin());
 * EffectsPlugin fx = engine.get(EffectsPlugin.KEY);
 * Effects effects = fx.effects(scene);                       // the world's effects, synced each frame
 * fx.draw(sceneViews);                                      // a ParticleDraw in every view of the scene
 * effects.play(TORCH_FIRE, torchNode);
 * fx.bindings(animators).onEvent(LEFT_FOOT_DOWN, DUST, footL).scale(INTENSITY);
 * }</pre>
 *
 * <p>One world's effects per plugin: the pool follows one {@link Effects}. The world's time is the frame's delta
 * ({@link Frame#delta()}); a paused world passes a step of 0 by {@link Effects#pause}. Viewers for distances go to
 * {@link #sync()}.
 */
public final class EffectsPlugin implements Plugin {

    /** Its key. */
    public static final PluginKey<EffectsPlugin> KEY = PluginKey.of("effects", EffectsPlugin.class);

    /** The quality effects spawn at: effect types scale spawns by it, emitters may need more. */
    public static final Setting<Quality> QUALITY =
        Setting.choice("effects.quality", Quality.HIGH, "how many particles effects spawn");
    /** Megabytes of the particle pool, read when it starts. */
    public static final Setting<Integer> BUDGET =
        Setting.integer("effects.budgetMegabytes", 64, 1, 1 << 16, "megabytes of GPU memory for particles");

    private Rendering rendering;
    private EngineSettings settings;
    private Particles particles;
    private EffectSync sync;
    private EffectBindings bindings;

    @Override
    public PluginKey<EffectsPlugin> key() {
        return KEY;
    }

    @Override
    public List<PluginKey<?>> requires() {
        return List.of(Rendering.KEY);
    }

    @Override
    @AllowAllocation("starting up")
    public void start(Stage stage, Startup startup) {
        if (stage == Stage.BEFORE_INSTANCE) {
            settings = startup.settings();
            startup.registry().addHidden(QUALITY, BUDGET);
            settings.onChange(QUALITY, () -> {
                if (sync != null) {
                    sync.quality(settings.get(QUALITY));
                }
            });
        } else if (stage == Stage.DEVICE) {
            rendering = startup.get(Rendering.KEY);
            FrameGraph graph = rendering.graph();
            particles = new Particles(rendering.device(), graph.frameSlots(), (long) settings.get(BUDGET) << 20);
            graph.add(new ParticleSimulation(particles));
        }
    }

    /** The GPU side: the pool, the programs, the frame's tables. */
    public Particles particles() {
        if (particles == null) {
            throw new IllegalStateException("The effects plugin makes it in " + Stage.DEVICE + ": not yet");
        }
        return particles;
    }

    /** The effects of {@code scene}, synced each frame from now on; once (one world's effects per plugin). */
    @AllowAllocation("set up once")
    public Effects effects(SceneGraph scene) {
        if (sync != null) {
            throw new IllegalStateException("The effects plugin syncs one world's effects already");
        }
        sync = new EffectSync(new Effects(scene), particles());
        sync.quality(settings.get(QUALITY));
        return sync.effects();
    }

    /** The sync of the world's effects ({@link #effects}): viewers, beams, the runtime and CPU particles. */
    public EffectSync sync() {
        if (sync == null) {
            throw new IllegalStateException("No world's effects yet: effects(scene)");
        }
        return sync;
    }

    /** Animation driving the world's effects ({@link #effects}), evaluated each frame before they sync; once. */
    @AllowAllocation("set up once")
    public EffectBindings bindings(Animators animators) {
        if (bindings != null) {
            throw new IllegalStateException("The effects plugin has bindings already");
        }
        bindings = new EffectBindings(sync().effects(), animators);
        return bindings;
    }

    /**
     * Draws the particles in every view of {@code views}, those made later too: after the glass, and the renderers drawn
     * before it ({@code glass} 1) by {@link ParticleDraw#beforeGlass}.
     */
    @AllowAllocation("set up once")
    public void draw(SceneViews views) {
        Particles p = particles();
        Objects.requireNonNull(views, "views").addPerView(view -> new ParticleDraw(p, view));
        views.addPerView(view -> ParticleDraw.beforeGlass(p, view, null));
    }

    /** Depth collision ({@code collide.depth}) collides with {@code view}'s last depth: the camera, a headset's eye. */
    @AllowAllocation("set up once")
    public EffectsPlugin collisionView(SceneView view) {
        view.port().add(new ParticleCollisionDepth(particles(), view));
        return this;
    }

    /**
     * Mesh shapes emit from the renderables {@code sync} draws, and mesh particles take up to {@code instances} of
     * {@code renderer}'s scene ({@link Particles#meshes(MeshletRenderer, int)}); after {@link #effects}.
     */
    public EffectsPlugin meshes(SceneSync sync, MeshletRenderer renderer, int instances) {
        particles().meshes(renderer, instances);
        sync().meshes(sync);
        return this;
    }

    /** Particle light renderers light the scene through {@code lights}, at most {@code budget} a frame (null: none). */
    public EffectsPlugin lights(Lights lights, int budget) {
        particles().lights(lights, budget);
        return this;
    }

    /** {@code collide.rays} traces into {@code renderer}'s top level where the device traces rays (null: none). */
    public EffectsPlugin rays(RayTracing rayTracing, MeshletRenderer renderer) {
        particles().rays(rayTracing, renderer);
        return this;
    }

    @Override
    public void afterUpdate(Frame frame) {
        if (bindings != null) {
            bindings.update();
        }
        if (sync != null) {
            sync.update(frame.delta());
        }
    }

    /** Waits for the GPU and frees the particles, before rendering stops (plugins stop in reverse). */
    @Override
    public void stop() {
        if (particles != null) {
            rendering.device().waitIdle();
            rendering.graph().remove(ParticleSimulation.ID);
            particles.close();
            particles = null;
        }
    }
}
