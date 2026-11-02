package com.truesim.engine.render.particles;

import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
import static org.lwjgl.vulkan.VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.graph.Id;
import com.truesim.engine.graph.Access;
import com.truesim.engine.graph.Anchor;
import com.truesim.engine.graph.Buffer;
import com.truesim.engine.graph.BufferAccess;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Image;
import com.truesim.engine.graph.Key;
import com.truesim.engine.graph.Load;
import com.truesim.engine.graph.Order;
import com.truesim.engine.graph.Store;
import com.truesim.engine.graph.Technique;
import com.truesim.engine.render.Camera;
import com.truesim.engine.render.Keys;
import com.truesim.engine.render.SceneView;
import com.truesim.engine.render.ShadowInputs;
import com.truesim.engine.render.Transparency;
import com.truesim.engine.render.layout.ParticleConstants;
import com.truesim.engine.render.layout.ParticleDrawPush;
import com.truesim.engine.render.layout.ParticleDrawRecordLayout;
import com.truesim.engine.vulkan.FrameBuffer;
import com.truesim.engine.vulkan.Pipeline;
import java.util.List;
import java.util.Objects;

/**
 * Particles drawn in a view (effects.md §2.5): a technique per view (the camera, each eye, a mirror) that modifies
 * {@link Keys#SCENE_COLOR} at {@link Anchor#TRANSPARENT}, after the blended materials ({@link Transparency#ID}). Its
 * passes: the {@link ParticleSort} of its center (the sorted emitters' particles back to front), "particle cull", each
 * emitter instance's GPU box against the view's frustum, writing its indirect draw; and "particle draw", the
 * billboards of every emitter, emitters as wholes back to front by their distance from the camera, those of sorted
 * renderers in the sort's order, premultiplied (alpha blended, additive, or between them), unlit, within the view's
 * render region. Draws of one pipeline next to each other in that order are one indirect multi-draw. A renderer's
 * settings ({@code ModulePrograms}' renderer records) choose its facing, stretch, soft fade (by depth, or by the view
 * ray's path through the particle's sphere), flipbook and frame blending; the opaque depth is tested in the fragment
 * shader, which reads it for the fade too.
 *
 * <p>The eyes of a headset are a pair (E19): each eye's draw takes the camera at their center, so both share one sort
 * (equal requests) and billboards and strips face the center in both, never turned differently per eye; view-plane
 * billboards face the center's position there, as the view plane rolls with the head.
 *
 * <pre>{@code
 * graph.add(new ParticleSimulation(particles));
 * sceneViews.addPerView(view -> new ParticleDraw(particles, view));
 * leftPort.add(new ParticleDraw(particles, leftView, head)); // a pair of eyes: the camera at their center
 * rightPort.add(new ParticleDraw(particles, rightView, head));
 * }</pre>
 */
public final class ParticleDraw implements Technique {

    public static final Id<ParticleDraw> ID = Id.of("truesim.particleDraw", ParticleDraw.class);
    /** Draws a view has room for: renderers of emitter instances. */
    public static final int MAX_DRAWS = ParticleConstants.MAX_DRAWS;

    private final Particles particles;
    private final SceneView view;
    private final Camera center;
    private final Draws draws;

    /** {@code particles} as {@code view} sees them; added to {@code view}'s port, which closes what it makes. */
    public ParticleDraw(Particles particles, SceneView view) {
        this(particles, view, null);
    }

    /**
     * {@code particles} as {@code view}, an eye of a pair, sees them: sorted by their distance from {@code center}, the
     * camera at the pair's center (read as the frame records), and facing it; null for the view's own camera.
     */
    @AllowAllocation("made with its view")
    public ParticleDraw(Particles particles, SceneView view, Camera center) {
        this.particles = Objects.requireNonNull(particles, "particles");
        this.view = Objects.requireNonNull(view, "view");
        this.center = center;
        this.draws = view.port().own(new Draws(particles));
    }

    @Override
    public Id<ParticleDraw> id() {
        return ID;
    }

    @Override
    public List<Key<?>> provides() {
        return List.of();
    }

    @Override
    public List<Key<?>> modifies() {
        return List.of(Keys.SCENE_COLOR);
    }

    /** Over the blended materials: at {@link Anchor#TRANSPARENT}, after {@link Transparency#ID}. */
    @Override
    public Order order() {
        return Order.at(Anchor.TRANSPARENT).after(Transparency.ID);
    }

    @Override
    @AllowAllocation("building the graph, when it changes")
    public void build(GraphBuilder g) {
        if (g.view() != view.port()) {
            throw new IllegalStateException("ParticleDraw of " + view.port() + " was added to "
                + (g.view() == null ? "the frame" : g.view()) + ": add it to its view");
        }
        Image color = g.modify(Keys.SCENE_COLOR);
        Image depth = g.require(Keys.SCENE_DEPTH);
        Buffer pool = g.require(ParticleKeys.PARTICLES);
        Buffer state = g.require(ParticleKeys.STATE);
        Buffer side = g.require(ParticleKeys.SIDE);
        Buffer order = g.require(ParticleKeys.RIBBON_ORDER);
        Camera camera = view.camera();
        Camera sortCenter = center == null ? camera : center;
        ParticleSort.Order sorted = g.request(new ParticleSort(particles, sortCenter, pool, state));
        // The indirect draws, then each slot's light (per-vertex lighting): four words a particle of the pool
        Buffer commands = g.createBuffer("particle draws", 16L * MAX_DRAWS + 16L * particles.pool().slots());
        Buffer lights = g.optional(Keys.LIGHT_LIST);
        ShadowInputs.Set shadows = lights == null ? null : g.request(new ShadowInputs(g.view()));
        Pipeline cull = g.computeProgram("shaders/particles/draw.spv", "cullMain", ParticleDrawPush.BYTES);
        Pipeline[] pipelines = new Pipeline[ParticleConstants.RENDER_BEAM + 1];
        for (int kind = 0; kind < pipelines.length; kind++) {
            pipelines[kind] = kind == ParticleConstants.RENDER_MESH ? null : particles.pipeline(kind, color.format().vk());
        }
        Draws list = draws;
        com.truesim.engine.render.RenderView renderView = view.view();
        boolean paired = center != null;
        ParticleDrawPush cullPush = new ParticleDrawPush();
        GraphBuilder.PassBuilder culling = g.pass("particle cull");
        if (lights != null) {
            culling.read(lights, BufferAccess.STORAGE_READ); // its address in the draws' records
        }
        culling.read(state, BufferAccess.STORAGE_READ).write(commands, BufferAccess.STORAGE_WRITE)
            .record(ctx -> {
                int n = list.prepare(ctx.frame(), camera, lights == null ? 0 : ctx.address(lights));
                if (n > 0) {
                    ctx.dispatch(cull, list.common(cullPush, renderView.address(), camera, sortCenter, paired)
                        .commands(ctx.address(commands)).drawCount(n), (n + 63) / 64);
                }
            });
        Pipeline light = g.computeProgram("shaders/particles/draw.spv", "lightMain", ParticleDrawPush.BYTES);
        ParticleDrawPush lightPush = new ParticleDrawPush();
        GraphBuilder.PassBuilder lighting = g.pass("particle lighting").read(pool, BufferAccess.STORAGE_READ)
            .read(state, BufferAccess.STORAGE_READ).write(commands, BufferAccess.STORAGE_WRITE);
        if (lights != null) {
            lighting.read(lights, BufferAccess.STORAGE_READ);
            shadows.readBy(lighting, Access.SAMPLED_COMPUTE, BufferAccess.STORAGE_READ);
        }
        lighting.record(ctx -> {
            int n = list.prepare(ctx.frame(), camera, lights == null ? 0 : ctx.address(lights));
            if (n > 0 && list.vertexLit() > 0) {
                if (shadows != null) {
                    shadows.prepare(ctx);
                }
                ctx.dispatch(light, list.common(lightPush, renderView.address(), camera, sortCenter, paired)
                    .commands(ctx.address(commands)),
                    (list.largestLit() + 63) / 64, n, 1);
            }
        });
        ParticleDrawPush drawPush = new ParticleDrawPush();
        GraphBuilder.PassBuilder drawPass = g.pass("particle draw");
        if (lights != null) {
            drawPass.read(lights, BufferAccess.DRAW_READ);
            shadows.readBy(drawPass, Access.SAMPLED_FRAGMENT, BufferAccess.DRAW_READ);
        }
        drawPass.read(pool, BufferAccess.DRAW_READ).read(side, BufferAccess.DRAW_READ)
            .read(order, BufferAccess.DRAW_READ).read(sorted.sorted(), BufferAccess.DRAW_READ)
            .read(sorted.args(), BufferAccess.DRAW_READ).read(commands, BufferAccess.INDIRECT)
            .read(commands, BufferAccess.DRAW_READ)
            .read(depth, Access.SAMPLED_FRAGMENT).attach(color, Load.LOAD, Store.STORE).region()
            .record(ctx -> {
                int n = list.prepare(ctx.frame(), camera, lights == null ? 0 : ctx.address(lights));
                if (shadows != null && list.lit() > 0) {
                    shadows.prepare(ctx);
                }
                list.common(drawPush, renderView.address(), camera, sortCenter, paired).depth(ctx.sampled(depth))
                    .sorted(ctx.address(sorted.sorted())).sortArgs(ctx.address(sorted.args()))
                    .commands(ctx.address(commands));
                // Runs of draws of one pipeline: a multi-draw each, in order
                for (int i = 0; i < n; ) {
                    int kind = list.kind(i);
                    int end = i + 1;
                    while (end < n && list.kind(end) == kind) {
                        end++;
                    }
                    ctx.drawIndirect(pipelines[kind], drawPush.first(i), commands, 16L * i, end - i);
                    i = end;
                }
            });
        g.provide(Keys.SCENE_COLOR, color);
    }

    /**
     * A view's draws in a frame: each renderer of each emitter instance (but meshes and lights, and trails of CPU emitters, which
     * keep no past positions), back to front, written once per frame by whichever of the passes records first (they
     * may record on two threads), with what each draw reads: its emitter, renderer, texture, sorted run and beam end.
     */
    static final class Draws implements AutoCloseable {
        private final Particles particles;
        private final FrameBuffer records;
        private final Staging staged = new Staging((long) ParticleDrawRecordLayout.BYTES * MAX_DRAWS);
        private final int[] emitter = new int[MAX_DRAWS];
        private final int[] renderer = new int[MAX_DRAWS];
        private final int[] texture = new int[MAX_DRAWS];
        private final int[] kind = new int[MAX_DRAWS];
        private final int[] index = new int[MAX_DRAWS];
        private final double[] distance = new double[MAX_DRAWS];
        private final boolean[] sorted = new boolean[MAX_DRAWS];
        private long frame = -1;
        private int count;
        private int lit; // draws lit at all, and per vertex, and the most particles of those per vertex
        private int vertexLit;
        private int largestLit;

        @AllowAllocation("made with its view")
        Draws(Particles particles) {
            this.particles = particles;
            records = FrameBuffer.create(particles.device(), (long) ParticleDrawRecordLayout.BYTES * MAX_DRAWS,
                VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT, particles.slots());
        }

        /**
         * Lists the frame's draws seen from {@code camera} once per frame, lit by the light list at {@code lights}
         * (0: the sun); returns how many there are.
         */
        synchronized int prepare(long frame, Camera camera, long lights) {
            if (frame == this.frame) {
                return count;
            }
            this.frame = frame;
            count = 0;
            lit = 0;
            vertexLit = 0;
            largestLit = 0;
            if (!particles.drawable()) {
                return 0;
            }
            for (int e = 0; e < particles.emitterCount(); e++) {
                double dx = particles.listPosition(e, 0) - camera.position(0);
                double dy = particles.listPosition(e, 1) - camera.position(1);
                double dz = particles.listPosition(e, 2) - camera.position(2);
                double d = dx * dx + dy * dy + dz * dz;
                for (int r = 0; r < particles.renderers(e) && count < MAX_DRAWS; r++) {
                    int k = particles.rendererKind(e, r);
                    if (k == ParticleConstants.RENDER_MESH || k == ParticleConstants.RENDER_LIGHT
                        || k == ParticleConstants.RENDER_TRAIL && particles.listCpu(e)) {
                        continue;
                    }
                    // Insertion in order, farthest first: emitters as wholes, back to front
                    int at = count++;
                    while (at > 0 && distance[at - 1] < d) {
                        emitter[at] = emitter[at - 1];
                        renderer[at] = renderer[at - 1];
                        texture[at] = texture[at - 1];
                        kind[at] = kind[at - 1];
                        index[at] = index[at - 1];
                        distance[at] = distance[at - 1];
                        sorted[at] = sorted[at - 1];
                        at--;
                    }
                    emitter[at] = e;
                    renderer[at] = particles.rendererWord(e, r);
                    texture[at] = particles.rendererTexture(e, r);
                    kind[at] = k;
                    index[at] = r;
                    distance[at] = d;
                    sorted[at] = particles.rendererSorted(e, r);
                }
            }
            for (int i = 0; i < count; i++) {
                long at = (long) ParticleDrawRecordLayout.BYTES * i;
                int e = emitter[i];
                staged.putInt(at + ParticleDrawRecordLayout.EMITTER, e);
                staged.putInt(at + ParticleDrawRecordLayout.RENDERER, renderer[i]);
                staged.putInt(at + ParticleDrawRecordLayout.TEXTURE, texture[i]);
                staged.putInt(at + ParticleDrawRecordLayout.SORT, sorted[i] ? particles.sortOf(e)
                    : ParticleConstants.NO_SORT);
                staged.putFloat(at + ParticleDrawRecordLayout.TIME, particles.listTime(e));
                int lighting = kind[i] == ParticleConstants.RENDER_BILLBOARD ? particles.rendererLighting(e, index[i])
                    : ParticleConstants.LIGHTING_UNLIT;
                staged.putInt(at + ParticleDrawRecordLayout.LIGHTING, lighting);
                staged.putInt(at + ParticleDrawRecordLayout.EXTRA, particles.rendererExtra(e, index[i]));
                staged.putLong(at + ParticleDrawRecordLayout.LIGHTS, lights);
                lit += lighting != ParticleConstants.LIGHTING_UNLIT ? 1 : 0;
                if (lighting == ParticleConstants.LIGHTING_VERTEX) {
                    vertexLit++;
                    largestLit = Math.max(largestLit, particles.capacityOf(e));
                }
                if (kind[i] == ParticleConstants.RENDER_BEAM) {
                    staged.putVector(at + ParticleDrawRecordLayout.END,
                        (float) (particles.beamEnd(e, index[i], 0) - camera.position(0)),
                        (float) (particles.beamEnd(e, index[i], 1) - camera.position(1)),
                        (float) (particles.beamEnd(e, index[i], 2) - camera.position(2)));
                }
            }
            staged.copyTo(records, (long) ParticleDrawRecordLayout.BYTES * count);
            return count;
        }

        /**
         * Sets what every pass of the frame shares, seen from {@code camera}: billboards face {@code center} (the
         * camera itself, or a pair's center when {@code paired}).
         */
        ParticleDrawPush common(ParticleDrawPush push, long view, Camera camera, Camera center, boolean paired) {
            return push.particles(particles.particlesAddress()).emitters(particles.emitterTableAddress()).view(view)
                .words(particles.wordsAddress()).state(particles.stateAddress()).side(particles.sideAddress())
                .order(particles.orderAddress()).draws(records.deviceAddress())
                .facing((float) (center.position(0) - camera.position(0)),
                    (float) (center.position(1) - camera.position(1)),
                    (float) (center.position(2) - camera.position(2)))
                .paired(paired ? 1 : 0);
        }

        /** Draws of the frame lit at all. */
        int lit() {
            return lit;
        }

        /** Draws of the frame lit per vertex. */
        int vertexLit() {
            return vertexLit;
        }

        /** The largest capacity among their emitters. */
        int largestLit() {
            return largestLit;
        }

        /** Draw {@code i}'s renderer's kind. */
        int kind(int i) {
            return kind[i];
        }

        @Override
        public void close() {
            records.close();
        }
    }
}
