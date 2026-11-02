package com.truesim.engine.scene;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.jobs.JobSystem;
import com.truesim.common.math.TransformArray;
import com.truesim.engine.render.particles.Particles;
import com.truesim.scene.core.NodeHandle;
import com.truesim.scene.core.SceneGraph;
import com.truesim.scene.effects.CpuSimulation;
import com.truesim.scene.effects.EffectRuntime;
import com.truesim.scene.effects.Effects;
import com.truesim.scene.effects.Module;
import com.truesim.scene.effects.Shape;
import com.truesim.scene.effects.ParticleWorld;
import com.truesim.scene.effects.Quality;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * The effects playing in a scene, drawn: each frame it runs the emitter logic ({@link EffectRuntime}) and the CPU
 * emitters ({@link CpuSimulation}) on the scene's effects, then hands the frame to the GPU side ({@link Particles}),
 * as {@link SceneSync} does for renderables (effects.md §2.4).
 *
 * <pre>{@code
 * Effects effects = new Effects(scene);
 * EffectSync sync = new EffectSync(effects, particles);           // particles: the GPU side, in the frame graph
 * Effect torch = effects.play(TORCH_FIRE, torchNode);             // attached: follows the node, a joint's for a socket
 * sync.beamEnd(zap.id(), 0, targetNode);                           // a beam from its emitter to another node
 * // each frame, after scene.update():
 * sync.clearViewers();
 * sync.addViewer(camera.x, camera.y, camera.z);                   // for distances: culling, levels of detail
 * sync.update(frame.delta());                                     // 0 while the world is paused: frozen, still drawn
 * }</pre>
 *
 * <p>What the effects do, it follows: effects attached to nodes and joints (with their offsets) move with them as of
 * the last {@code scene.update()}; world-space emitters spawn along their motion and inherit their share of its
 * velocity; local-space ones carry their particles along. Stopped effects spawn no more and end once their particles
 * died; killed ones go at once; time scales and pauses (per effect, for all, or a paused world's delta of 0) scale the
 * step every emitter takes, so paused effects stay as they are in every view. Origins far from the effect rebase on
 * the GPU, so an effect 7,000 km out draws as precisely as at the origin. An ended effect's ranges and programs stay
 * warm for the next instance of the same effect ({@link Particles#warmFrames}); an effect restarted with new data
 * ({@link Effects#replace}, a hot reload) keeps the ranges that still fit. Transforms are the nodes' world transforms
 * as they are: interpolation between fixed steps (application-api D91) is not built yet.
 *
 * <p>Mesh shapes ({@code Shape.mesh}) emit from the renderable of the node their effect follows, as the
 * {@link #meshes} sync draws it: a skinned one's posed copy, so fire runs over a moving character's skin; bone shapes
 * ({@code Shape.bones}) along the joints given them ({@link #joints}). The particles need the renderer
 * ({@link Particles#meshes}).
 *
 * <p>Given a {@link ParticleWorld}, CPU emitters collide with it and GPU emitters with its heights around the first
 * viewer ({@link Particles#heights}); depth collision needs a {@code ParticleCollisionDepth} in the collision view.
 *
 * <p>Call {@link #update} once per frame, between frames or while it records (after {@code beginFrame}); on the
 * scene's thread. Allocates nothing per frame once its tables have grown to the effects playing.
 */
public final class EffectSync {

    /** Metres across the height field GPU emitters collide with, around the first viewer, given a world. */
    public static final float HEIGHT_FIELD_SIZE = 256;
    /** Its points along each side: 2 m apart. */
    public static final int HEIGHT_FIELD_RESOLUTION = 129;

    private final Effects effects;
    private final Particles particles;
    private final EffectRuntime runtime;
    private final CpuSimulation simulation;
    private final SceneGraph scene;
    // Beams ended at nodes: effect, emitter, node.
    private long[] beamEffects = new long[4];
    private int[] beamEmitters = new int[4];
    private long[] beamNodes = new long[4];
    private int beams;
    // Bone shapes along nodes: effect, emitter, the nodes; and the renderables mesh shapes emit from.
    private long[] jointEffects = new long[4];
    private int[] jointEmitters = new int[4];
    private long[][] jointNodes = new long[4][];
    private int jointChains;
    private final double[] jointScratch = new double[3 * com.truesim.engine.render.layout.ParticleConstants.MAX_JOINTS];
    private final double[] world = new double[12];
    private SceneSync meshes;
    private int viewers;

    /** The effects of {@code effects} drawn through {@code particles}, simulated on the calling thread. */
    public EffectSync(Effects effects, Particles particles) {
        this(effects, particles, null, null);
    }

    /**
     * The effects of {@code effects} drawn through {@code particles}; CPU emitters collide with {@code world} (may be
     * null) and the effects spawn and simulate on {@code jobs} (null: on the calling thread).
     */
    @AllowAllocation("set up once")
    public EffectSync(Effects effects, Particles particles, ParticleWorld world, JobSystem jobs) {
        this.effects = Objects.requireNonNull(effects, "effects");
        this.particles = Objects.requireNonNull(particles, "particles");
        scene = effects.scene();
        runtime = new EffectRuntime(effects, jobs);
        simulation = new CpuSimulation(runtime, world, jobs);
        if (world != null) { // GPU emitters' height collision: the world's heights around the first viewer
            particles.heights(world, HEIGHT_FIELD_SIZE, HEIGHT_FIELD_RESOLUTION);
        }
        particles.sources(this::emit);
    }

    public Effects effects() {
        return effects;
    }

    public Particles particles() {
        return particles;
    }

    /** The emitter logic: significance, levels of detail and the frame's spawns. */
    public EffectRuntime runtime() {
        return runtime;
    }

    /** The CPU emitters' particles: gameplay reads them ({@link CpuSimulation#particles}). */
    public CpuSimulation simulation() {
        return simulation;
    }

    /** The quality effects spawn at ({@code effects.quality}). */
    public void quality(Quality quality) {
        runtime.quality(quality);
    }

    /** Forgets the viewers; with none, every effect counts as near. */
    public void clearViewers() {
        runtime.clearViewers();
        viewers = 0;
    }

    /**
     * A viewer (a camera, a headset's eyes' center) at ({@code x}, {@code y}, {@code z}), for distances; the first is
     * where the height field GPU emitters collide with centers.
     */
    public void addViewer(double x, double y, double z) {
        runtime.addViewer(x, y, z);
        if (viewers++ == 0) {
            particles.heightFocus(x, z);
        }
    }

    /**
     * Ends the beams of emitter {@code emitter} of playing effect {@code effect} at node {@code node}, following it
     * every frame (as of the last {@code scene.update()}) while both last; without an end, a beam reaches its
     * {@code length} along its emitter's +Y.
     */
    @AllowAllocation("a beam's end, set up once")
    public void beamEnd(long effect, int emitter, long node) {
        if (!scene.contains(node)) {
            throw new IllegalArgumentException("Not a node of the scene: " + NodeHandle.toString(node));
        }
        if (!effects.alive(effect)) {
            throw new IllegalArgumentException("No effect plays as " + Long.toHexString(effect));
        }
        for (int i = 0; i < beams; i++) {
            if (beamEffects[i] == effect && beamEmitters[i] == emitter) {
                beamNodes[i] = node;
                return;
            }
        }
        if (beams == beamEffects.length) {
            beamEffects = Arrays.copyOf(beamEffects, 2 * beams);
            beamEmitters = Arrays.copyOf(beamEmitters, 2 * beams);
            beamNodes = Arrays.copyOf(beamNodes, 2 * beams);
        }
        beamEffects[beams] = effect;
        beamEmitters[beams] = emitter;
        beamNodes[beams] = node;
        beams++;
    }

    /**
     * Mesh shapes emit from the renderables {@code sync} draws (null: none): each from the renderable of its effect's
     * node, as drawn, a skinned one's posed copy; with none drawn there (not loaded yet, or no renderable), its
     * particles are born dead. {@code sync} draws the effects' scene.
     */
    public EffectSync meshes(SceneSync sync) {
        meshes = sync;
        return this;
    }

    /**
     * Runs the bone shapes of emitter {@code emitter} of playing effect {@code effect} along {@code nodes} (joints'
     * nodes, at most {@code MAX_JOINTS}, followed every frame as of the last {@code scene.update()}) while it plays.
     */
    @AllowAllocation("a chain of joints, set up once")
    public void joints(long effect, int emitter, long... nodes) {
        if (nodes.length > com.truesim.engine.render.layout.ParticleConstants.MAX_JOINTS) {
            throw new IllegalArgumentException("At most " + com.truesim.engine.render.layout.ParticleConstants
                .MAX_JOINTS + " joints: " + nodes.length);
        }
        for (long node : nodes) {
            if (!scene.contains(node)) {
                throw new IllegalArgumentException("Not a node of the scene: " + NodeHandle.toString(node));
            }
        }
        if (!effects.alive(effect)) {
            throw new IllegalArgumentException("No effect plays as " + Long.toHexString(effect));
        }
        int at = jointChains;
        for (int i = 0; i < jointChains; i++) {
            if (jointEffects[i] == effect && jointEmitters[i] == emitter) {
                at = i;
            }
        }
        if (at == jointEffects.length) {
            jointEffects = Arrays.copyOf(jointEffects, 2 * at);
            jointEmitters = Arrays.copyOf(jointEmitters, 2 * at);
            jointNodes = Arrays.copyOf(jointNodes, 2 * at);
        }
        jointEffects[at] = effect;
        jointEmitters[at] = emitter;
        jointNodes[at] = nodes.clone();
        jointChains = Math.max(jointChains, at + 1);
    }

    /** Chains of joints bone shapes run along, of effects still playing. */
    public int jointChainCount() {
        return jointChains;
    }

    /** Names what emitter {@code emitter} of {@code effect} emits from this frame: its node's mesh, its joints. */
    private void emit(long effect, int emitter) {
        List<Module> init = effects.data(effect).emitters().get(emitter).init();
        boolean meshed = false;
        for (int i = 0; i < init.size(); i++) {
            meshed |= init.get(i).type().equals(Shape.MESH);
        }
        if (meshed) {
            long node = effects.node(effect);
            int mesh = meshes == null || node == NodeHandle.NONE || !scene.contains(node) ? -1
                : meshes.drawnMeshOf(node);
            if (mesh >= 0) {
                meshes.worldOf(node, world);
                particles.emitFrom(effect, emitter, mesh, meshes.meshDataOf(node), world);
            } else {
                particles.emitFrom(effect, emitter, -1, null, null);
            }
        }
        for (int i = 0; i < jointChains; i++) {
            if (jointEffects[i] == effect && jointEmitters[i] == emitter) {
                long[] nodes = jointNodes[i];
                TransformArray transforms = scene.worldTransforms();
                MemorySegment s = transforms.segment();
                int count = 0;
                for (long node : nodes) {
                    if (scene.contains(node)) {
                        long base = NodeHandle.index(node) * transforms.stride();
                        jointScratch[3 * count] = s.get(ValueLayout.JAVA_DOUBLE, base);
                        jointScratch[3 * count + 1] = s.get(ValueLayout.JAVA_DOUBLE, base + 8);
                        jointScratch[3 * count + 2] = s.get(ValueLayout.JAVA_DOUBLE, base + 16);
                        count++;
                    }
                }
                particles.joints(effect, emitter, jointScratch, count);
            }
        }
    }

    /** Beams ended at nodes, of effects still playing. */
    public int beamCount() {
        return beams;
    }

    /**
     * Moves the effects on by {@code seconds} of the world clock (0 while the world is paused): the runtime advances
     * them and spawns, the CPU emitters simulate, and the frame goes to the GPU. After {@code scene.update()}, once per
     * frame. Allocates nothing.
     */
    public void update(double seconds) {
        runtime.update(seconds);
        simulation.update();
        particles.update(effects, runtime.spawns(), simulation);
        TransformArray world = scene.worldTransforms();
        MemorySegment s = world.segment();
        int kept = 0;
        for (int i = 0; i < beams; i++) {
            long effect = beamEffects[i];
            long node = beamNodes[i];
            if (!effects.alive(effect) || !scene.contains(node)) {
                continue; // ended, or its end was removed: dropped
            }
            beamEffects[kept] = effect;
            beamEmitters[kept] = beamEmitters[i];
            beamNodes[kept] = node;
            kept++;
            if (particles.instance(effect, beamEmitters[i]) >= 0) {
                long base = NodeHandle.index(node) * world.stride();
                particles.beamEnd(effect, beamEmitters[i], s.get(ValueLayout.JAVA_DOUBLE, base),
                    s.get(ValueLayout.JAVA_DOUBLE, base + 8), s.get(ValueLayout.JAVA_DOUBLE, base + 16));
            }
        }
        beams = kept;
        kept = 0;
        for (int i = 0; i < jointChains; i++) {
            if (effects.alive(jointEffects[i])) {
                jointEffects[kept] = jointEffects[i];
                jointEmitters[kept] = jointEmitters[i];
                jointNodes[kept] = jointNodes[i];
                kept++;
            }
        }
        for (int i = kept; i < jointChains; i++) {
            jointNodes[i] = null;
        }
        jointChains = kept;
    }
}
