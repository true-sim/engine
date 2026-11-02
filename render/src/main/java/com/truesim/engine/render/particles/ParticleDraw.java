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
 * passes: "particle cull", each emitter instance's GPU box against the view's frustum, writing its indirect draw; and
 * "particle draw", the billboards of every emitter, emitters as wholes back to front by their distance from the
 * camera, premultiplied (alpha blended, additive, or between them), unlit, within the view's render region. A
 * renderer's settings ({@code ModulePrograms}' renderer records) choose its facing, stretch, soft fade (by depth, or by
 * the view ray's path through the particle's sphere), flipbook and frame blending; the opaque depth is tested in the
 * fragment shader, which reads it for the fade too. View-plane billboards face the view's plane; on a headset each eye
 * is its own view until the pairs of eyes of step 10.
 *
 * <pre>{@code
 * graph.add(new ParticleSimulation(particles));
 * sceneViews.addPerView(view -> new ParticleDraw(particles, view));
 * }</pre>
 */
public final class ParticleDraw implements Technique {

    public static final Id<ParticleDraw> ID = Id.of("truesim.particleDraw", ParticleDraw.class);
    /** Draws a view has room for: renderers of emitter instances. */
    public static final int MAX_DRAWS = 4096;

    private final Particles particles;
    private final SceneView view;
    private final Draws draws;

    /** {@code particles} as {@code view} sees them; added to {@code view}'s port, which closes what it makes. */
    @AllowAllocation("made with its view")
    public ParticleDraw(Particles particles, SceneView view) {
        this.particles = Objects.requireNonNull(particles, "particles");
        this.view = Objects.requireNonNull(view, "view");
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
        Buffer commands = g.createBuffer("particle draws", 16L * MAX_DRAWS);
        Pipeline cull = g.computeProgram("shaders/particles/draw.spv", "cullMain", ParticleDrawPush.BYTES);
        Pipeline[] pipelines = new Pipeline[ParticleConstants.RENDER_BEAM + 1];
        for (int kind = 0; kind < pipelines.length; kind++) {
            pipelines[kind] = kind == ParticleConstants.RENDER_MESH ? null : particles.pipeline(kind, color.format().vk());
        }
        Draws list = draws;
        Camera camera = view.camera();
        com.truesim.engine.render.RenderView renderView = view.view();
        ParticleDrawPush cullPush = new ParticleDrawPush();
        g.pass("particle cull").read(state, BufferAccess.STORAGE_READ).write(commands, BufferAccess.STORAGE_WRITE)
            .record(ctx -> {
                int n = list.prepare(ctx.frame(), camera);
                if (n > 0) {
                    ctx.dispatch(cull, list.common(cullPush, renderView.address()).draws(list.address())
                        .commands(ctx.address(commands)).drawCount(n), (n + 63) / 64);
                }
            });
        ParticleDrawPush drawPush = new ParticleDrawPush();
        g.pass("particle draw").read(pool, BufferAccess.DRAW_READ).read(side, BufferAccess.DRAW_READ)
            .read(order, BufferAccess.DRAW_READ).read(commands, BufferAccess.INDIRECT)
            .read(depth, Access.SAMPLED_FRAGMENT).attach(color, Load.LOAD, Store.STORE).region()
            .record(ctx -> {
                int n = list.prepare(ctx.frame(), camera);
                list.common(drawPush, renderView.address()).depth(ctx.sampled(depth));
                for (int i = 0; i < n; i++) {
                    ctx.drawIndirect(pipelines[list.set(drawPush, i, camera)], drawPush, commands, 16L * i, 1);
                }
            });
        g.provide(Keys.SCENE_COLOR, color);
    }

    /**
     * A view's draws in a frame: each renderer of each emitter instance (but meshes, and trails of CPU emitters, which
     * keep no past positions), back to front, written once per frame by whichever of the passes records first (they
     * may record on two threads).
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
        private long frame = -1;
        private int count;

        @AllowAllocation("made with its view")
        Draws(Particles particles) {
            this.particles = particles;
            records = FrameBuffer.create(particles.device(), (long) ParticleDrawRecordLayout.BYTES * MAX_DRAWS,
                VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT, particles.slots());
        }

        /** Lists the frame's draws seen from {@code camera} once per frame; returns how many there are. */
        synchronized int prepare(long frame, Camera camera) {
            if (frame == this.frame) {
                return count;
            }
            this.frame = frame;
            count = 0;
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
                    if (k == ParticleConstants.RENDER_MESH
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
                        at--;
                    }
                    emitter[at] = e;
                    renderer[at] = particles.rendererWord(e, r);
                    texture[at] = particles.rendererTexture(e, r);
                    kind[at] = k;
                    index[at] = r;
                    distance[at] = d;
                }
            }
            for (int i = 0; i < count; i++) {
                long at = (long) ParticleDrawRecordLayout.BYTES * i;
                staged.putInt(at + ParticleDrawRecordLayout.EMITTER, emitter[i]);
                staged.putInt(at + ParticleDrawRecordLayout.RENDERER, renderer[i]);
                staged.putInt(at + ParticleDrawRecordLayout.TEXTURE, texture[i]);
            }
            staged.copyTo(records, (long) ParticleDrawRecordLayout.BYTES * count);
            return count;
        }

        long address() {
            return records.deviceAddress();
        }

        /** Sets what every pass of the frame shares. */
        ParticleDrawPush common(ParticleDrawPush push, long view) {
            return push.particles(particles.particlesAddress()).emitters(particles.emitterTableAddress()).view(view)
                .words(particles.wordsAddress()).state(particles.stateAddress()).side(particles.sideAddress())
                .order(particles.orderAddress());
        }

        /** Sets draw {@code i} as seen from {@code camera}; returns its renderer's kind. */
        int set(ParticleDrawPush push, int i, Camera camera) {
            int e = emitter[i];
            push.draw(i).emitter(e).renderer(renderer[i]).texture(texture[i]).time(particles.listTime(e));
            if (kind[i] == ParticleConstants.RENDER_BEAM) {
                push.end((float) (particles.beamEnd(e, index[i], 0) - camera.position(0)),
                    (float) (particles.beamEnd(e, index[i], 1) - camera.position(1)),
                    (float) (particles.beamEnd(e, index[i], 2) - camera.position(2)));
            }
            return kind[i];
        }

        @Override
        public void close() {
            records.close();
        }
    }
}
