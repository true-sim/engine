package com.truesim.engine.render.particles;

import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
import static org.lwjgl.vulkan.VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.graph.Id;
import com.truesim.engine.graph.Access;
import com.truesim.engine.graph.Anchor;
import com.truesim.engine.graph.Buffer;
import com.truesim.engine.graph.BufferAccess;
import com.truesim.engine.graph.Clear;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Image;
import com.truesim.engine.graph.ImageDesc;
import com.truesim.engine.graph.Key;
import com.truesim.engine.graph.Load;
import com.truesim.engine.graph.Order;
import com.truesim.engine.graph.PassContext;
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
import com.truesim.engine.render.layout.ParticleUpsamplePush;
import com.truesim.engine.render.layout.PyramidPush;
import com.truesim.engine.vulkan.Format;
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
 * renderers in the sort's order, premultiplied (alpha blended, additive, or between them), within the view's render
 * region. Draws of one pipeline next to each other in that order are one indirect multi-draw. A renderer's settings
 * ({@code ModulePrograms}' renderer records) choose its facing, stretch, soft fade (by depth, or by the view ray's path
 * through the particle's sphere), flipbook and frame blending; the opaque depth is tested in the fragment shader, which
 * reads it for the fade too.
 *
 * <p>Renderers drawn at half resolution ({@code half} 1, or every billboard at low quality) go first, into a target of
 * half the view's size tested against the farthest of each 2 x 2 of the scene's depth ("particle half depth",
 * "particle draw, half"), then over the scene by a depth-aware upsample ("particle upsample", effects step 18): behind
 * every full-resolution particle. Renderers drawn before glass ({@code glass} 1) are drawn by {@link #beforeGlass}, a
 * technique of their own at {@link Anchor#TRANSPARENT} before the blended materials, so glass tints them and
 * transmissive glass refracts them; those split at glass ({@code glass} 2) draw their fragments behind a view's
 * {@link ParticleKeys#GLASS_DEPTH} there and the rest after it, or all after it without one (step 21). The reactive
 * mask ({@link ParticleKeys#REACTIVE}) is drawn where something requires it.
 *
 * <p>The eyes of a headset are a pair (E19): each eye's draw takes the camera at their center, so both share one sort
 * (equal requests) and billboards and strips face the center in both, never turned differently per eye; view-plane
 * billboards face the center's position there, as the view plane rolls with the head.
 *
 * <pre>{@code
 * graph.add(new ParticleSimulation(particles));
 * sceneViews.addPerView(view -> new ParticleDraw(particles, view));
 * sceneViews.addPerView(view -> ParticleDraw.beforeGlass(particles, view, null)); // fires seen through windows
 * leftPort.add(new ParticleDraw(particles, leftView, head)); // a pair of eyes: the camera at their center
 * rightPort.add(new ParticleDraw(particles, rightView, head));
 * }</pre>
 */
public final class ParticleDraw implements Technique {

    public static final Id<ParticleDraw> ID = Id.of("truesim.particleDraw", ParticleDraw.class);
    /** The id of the draws before glass ({@link #beforeGlass}). */
    public static final Id<ParticleDraw> BEFORE_GLASS_ID = Id.of("truesim.particleDrawBeforeGlass",
        ParticleDraw.class);
    /** Draws a view has room for: renderers of emitter instances and placements. */
    public static final int MAX_DRAWS = ParticleConstants.MAX_DRAWS;

    // Groups of a view's draws, each drawn by a pass of its own, in this order within it.
    static final int HALF = 0;
    static final int FULL = 1;
    static final int SPLIT = 2;
    private static final int GROUPS = 3;

    private final Particles particles;
    private final SceneView view;
    private final Camera center;
    private final boolean before;
    private final Draws draws;
    private boolean overdraw;

    /** {@code particles} as {@code view} sees them; added to {@code view}'s port, which closes what it makes. */
    public ParticleDraw(Particles particles, SceneView view) {
        this(particles, view, null);
    }

    /**
     * {@code particles} as {@code view}, an eye of a pair, sees them: sorted by their distance from {@code center}, the
     * camera at the pair's center (read as the frame records), and facing it; null for the view's own camera.
     */
    public ParticleDraw(Particles particles, SceneView view, Camera center) {
        this(particles, view, center, false);
    }

    @AllowAllocation("made with its view")
    private ParticleDraw(Particles particles, SceneView view, Camera center, boolean before) {
        this.particles = Objects.requireNonNull(particles, "particles");
        this.view = Objects.requireNonNull(view, "view");
        this.center = center;
        this.before = before;
        this.draws = view.port().own(new Draws(particles, before));
    }

    /**
     * The renderers drawn before glass of {@code particles} as {@code view} sees them (the camera at {@code center}, or
     * null for its own), a technique beside the view's {@link ParticleDraw}: at {@link Anchor#TRANSPARENT} before the
     * blended materials, so the scene behind transmissive glass holds them and glass tints them.
     */
    @AllowAllocation("made with its view")
    public static ParticleDraw beforeGlass(Particles particles, SceneView view, Camera center) {
        return new ParticleDraw(particles, view, center, true);
    }

    /**
     * Draws the overdraw heat map instead of the particles (a debug view, effects step 18): each layer adds a dim
     * orange, about ten to white; the graph rebuilds.
     */
    public ParticleDraw overdraw(boolean on) {
        overdraw = on;
        return this;
    }

    @Override
    public Id<ParticleDraw> id() {
        return before ? BEFORE_GLASS_ID : ID;
    }

    @Override
    public List<Key<?>> provides() {
        return before ? List.of() : List.of(ParticleKeys.REACTIVE);
    }

    @Override
    public List<Key<?>> modifies() {
        return List.of(Keys.SCENE_COLOR);
    }

    /**
     * The scene's depth, which it tests and fades into, as the techniques before it leave it: the opaque scene's
     * before glass, also where transparency writes the depth after it ({@link com.truesim.engine.render.OpaqueTransparency}).
     */
    @Override
    public List<Key<?>> reads() {
        return List.of(Keys.SCENE_DEPTH);
    }

    /** Over the blended materials: at {@link Anchor#TRANSPARENT}, after {@link Transparency#ID} (or before, before glass). */
    @Override
    public Order order() {
        return before ? Order.at(Anchor.TRANSPARENT).before(Transparency.ID)
            : Order.at(Anchor.TRANSPARENT).after(Transparency.ID);
    }

    /** What the draws' passes and buffers follow: the sort's room, and whether anything is drawn at half resolution. */
    @Override
    public long buildVersion() {
        return particles.sortSlots() * 4L + (particles.drawsHalf() ? 1 : 0) + (overdraw ? 2 : 0);
    }

    @Override
    @AllowAllocation("building the graph, when it changes")
    public void build(GraphBuilder g) {
        if (g.view() != view.port()) {
            throw new IllegalStateException("ParticleDraw of " + view.port() + " was added to "
                + (g.view() == null ? "the frame" : g.view()) + ": add it to its view");
        }
        Image color = g.modify(Keys.SCENE_COLOR);
        Image depth = g.read(Keys.SCENE_DEPTH);
        Buffer pool = g.require(ParticleKeys.PARTICLES);
        Buffer state = g.require(ParticleKeys.STATE);
        Buffer side = g.require(ParticleKeys.SIDE);
        Buffer order = g.require(ParticleKeys.RIBBON_ORDER);
        Image glass = g.optional(ParticleKeys.GLASS_DEPTH);
        Camera camera = view.camera();
        Camera sortCenter = center == null ? camera : center;
        ParticleSort.Order sorted = g.request(new ParticleSort(particles, sortCenter, pool, state));
        // The indirect draws, then each slot's light (per-vertex lighting): four words a particle of the pool
        String name = before ? "particle draws before glass" : "particle draws";
        Buffer commands = g.createBuffer(name, 16L * MAX_DRAWS + 16L * particles.pool().slots());
        Buffer lights = g.optional(Keys.LIGHT_LIST);
        ShadowInputs.Set shadows = lights == null ? null : g.request(new ShadowInputs(g.view()));
        Pipeline cull = g.computeProgram("shaders/particles/draw.spv", "cullMain", ParticleDrawPush.BYTES);
        Pipeline[] pipelines = pipelines(color.format().vk(), overdraw ? Particles.DRAW_OVERDRAW
            : Particles.DRAW_COLOR);
        Draws list = draws;
        com.truesim.engine.render.RenderView renderView = view.view();
        boolean paired = center != null;
        boolean split = glass != null;
        String suffix = before ? ", before glass" : "";
        ParticleDrawPush cullPush = new ParticleDrawPush();
        GraphBuilder.PassBuilder culling = g.pass("particle cull" + suffix);
        if (lights != null) {
            culling.read(lights, BufferAccess.STORAGE_READ); // its address in the draws' records
        }
        culling.read(state, BufferAccess.STORAGE_READ).write(commands, BufferAccess.STORAGE_WRITE)
            .record(ctx -> {
                int n = list.prepare(ctx.frame(), camera, lights == null ? 0 : ctx.address(lights), split);
                if (n > 0) {
                    ctx.dispatch(cull, list.common(cullPush, renderView.address(), camera, sortCenter, paired)
                        .commands(ctx.address(commands)).drawCount(n), (n + 63) / 64);
                }
            });
        Pipeline light = g.computeProgram("shaders/particles/draw.spv", "lightMain", ParticleDrawPush.BYTES);
        ParticleDrawPush lightPush = new ParticleDrawPush();
        GraphBuilder.PassBuilder lighting = g.pass("particle lighting" + suffix).read(pool, BufferAccess.STORAGE_READ)
            .read(state, BufferAccess.STORAGE_READ).write(commands, BufferAccess.STORAGE_WRITE);
        if (lights != null) {
            lighting.read(lights, BufferAccess.STORAGE_READ);
            shadows.readBy(lighting, Access.SAMPLED_COMPUTE, BufferAccess.STORAGE_READ);
        }
        lighting.record(ctx -> {
            int n = list.prepare(ctx.frame(), camera, lights == null ? 0 : ctx.address(lights), split);
            if (n > 0 && list.vertexLit() > 0) {
                if (shadows != null) {
                    shadows.prepare(ctx);
                }
                ctx.dispatch(light, list.common(lightPush, renderView.address(), camera, sortCenter, paired)
                    .commands(ctx.address(commands)),
                    (list.largestLit() + 63) / 64, n, 1);
            }
        });
        if (!before && particles.drawsHalf()) {
            half(g, color, depth, pool, side, order, sorted, commands, lights, shadows, camera, sortCenter, paired);
        }
        ParticleDrawPush drawPush = new ParticleDrawPush();
        GraphBuilder.PassBuilder drawPass = g.pass("particle draw" + suffix);
        if (lights != null) {
            drawPass.read(lights, BufferAccess.DRAW_READ);
            shadows.readBy(drawPass, Access.SAMPLED_FRAGMENT, BufferAccess.DRAW_READ);
        }
        if (glass != null) {
            drawPass.read(glass, Access.SAMPLED_FRAGMENT);
        }
        drawPass.read(pool, BufferAccess.DRAW_READ).read(side, BufferAccess.DRAW_READ)
            .read(order, BufferAccess.DRAW_READ).read(sorted.sorted(), BufferAccess.DRAW_READ)
            .read(sorted.args(), BufferAccess.DRAW_READ).read(commands, BufferAccess.INDIRECT)
            .read(commands, BufferAccess.DRAW_READ)
            .read(depth, Access.SAMPLED_FRAGMENT).attach(color, Load.LOAD, Store.STORE).region()
            .record(ctx -> {
                int n = list.prepare(ctx.frame(), camera, lights == null ? 0 : ctx.address(lights), split);
                if (shadows != null && list.lit() > 0) {
                    shadows.prepare(ctx);
                }
                list.common(drawPush, renderView.address(), camera, sortCenter, paired).depth(ctx.sampled(depth))
                    .sorted(ctx.address(sorted.sorted())).sortArgs(ctx.address(sorted.args()))
                    .commands(ctx.address(commands)).glassDepth(glass == null ? ParticleConstants.NO_TEXTURE
                        : ctx.sampled(glass));
                int flags = paired ? 1 : 0;
                drawPush.flags(flags);
                drawRuns(ctx, list, pipelines, drawPush, commands, list.start(FULL), list.start(SPLIT));
                // Split emitters: behind the glass before it, in front of it after it
                drawPush.flags(flags | (before ? 1 : 2) << 1);
                drawRuns(ctx, list, pipelines, drawPush, commands, list.start(SPLIT), n);
            });
        if (!before) {
            reactive(g, color, depth, pool, side, order, sorted, commands, camera, sortCenter, paired);
        }
        g.provide(Keys.SCENE_COLOR, color);
    }

    /** The pipelines of each renderer kind (none for meshes) into {@code format}, or for the reactive mask. */
    private Pipeline[] pipelines(int format, int mode) {
        Pipeline[] pipelines = new Pipeline[ParticleConstants.RENDER_BEAM + 1];
        for (int kind = 0; kind < pipelines.length; kind++) {
            pipelines[kind] = kind == ParticleConstants.RENDER_MESH ? null : particles.pipeline(kind, format, mode);
        }
        return pipelines;
    }

    /**
     * The half-resolution draws: the farthest depth of each 2 x 2 of the scene's, the draws of renderers drawn at half
     * resolution into a target of half the view's size, cleared, then laid over the scene's color by the depth-aware
     * upsample.
     */
    @AllowAllocation("building the graph, when it changes")
    private void half(GraphBuilder g, Image color, Image depth, Buffer pool, Buffer side, Buffer order,
        ParticleSort.Order sorted, Buffer commands, Buffer lights, ShadowInputs.Set shadows, Camera camera,
        Camera sortCenter, boolean paired) {
        int width = (color.width() + 1) / 2;
        int height = (color.height() + 1) / 2;
        Image halfDepth = g.create("particle half depth", ImageDesc.of(Format.R32_SFLOAT, width, height));
        Image halfColor = g.create("particle half color", ImageDesc.of(color.format(), width, height));
        Pipeline farthest = g.computeProgram("shaders/blocks/pyramid.spv", "pyramidMain", PyramidPush.BYTES);
        PyramidPush depthPush = new PyramidPush().fromDepth(1);
        Draws list = draws;
        g.pass("particle half depth").read(depth, Access.SAMPLED_COMPUTE).write(halfDepth, Access.STORAGE_WRITE)
            .record(ctx -> {
                if (list.count(HALF) > 0) {
                    ctx.dispatch(farthest, depthPush.sourceSize(color.width(), color.height()).targetSize(width,
                        height).source(ctx.sampled(depth)).target(ctx.storage(halfDepth)), (width + 7) / 8,
                        (height + 7) / 8, 1);
                }
            });
        Pipeline[] pipelines = pipelines(color.format().vk(), overdraw ? Particles.DRAW_OVERDRAW
            : Particles.DRAW_COLOR);
        com.truesim.engine.render.RenderView renderView = view.view();
        ParticleDrawPush drawPush = new ParticleDrawPush();
        GraphBuilder.PassBuilder drawPass = g.pass("particle draw, half");
        if (lights != null) {
            drawPass.read(lights, BufferAccess.DRAW_READ);
            shadows.readBy(drawPass, Access.SAMPLED_FRAGMENT, BufferAccess.DRAW_READ);
        }
        drawPass.read(pool, BufferAccess.DRAW_READ).read(side, BufferAccess.DRAW_READ)
            .read(order, BufferAccess.DRAW_READ).read(sorted.sorted(), BufferAccess.DRAW_READ)
            .read(sorted.args(), BufferAccess.DRAW_READ).read(commands, BufferAccess.INDIRECT)
            .read(commands, BufferAccess.DRAW_READ).read(halfDepth, Access.SAMPLED_FRAGMENT)
            .attach(halfColor, Clear.color(0, 0, 0, 0))
            .record(ctx -> {
                if (list.count(HALF) == 0) {
                    return;
                }
                if (shadows != null && list.lit() > 0) {
                    shadows.prepare(ctx);
                }
                list.common(drawPush, renderView.address(), camera, sortCenter, paired).depth(ctx.sampled(halfDepth))
                    .sorted(ctx.address(sorted.sorted())).sortArgs(ctx.address(sorted.args()))
                    .commands(ctx.address(commands)).glassDepth(ParticleConstants.NO_TEXTURE);
                drawRuns(ctx, list, pipelines, drawPush, commands, list.start(HALF), list.start(FULL));
            });
        Pipeline upsample = particles.upsamplePipeline(color.format().vk());
        ParticleUpsamplePush upsamplePush = new ParticleUpsamplePush();
        g.pass("particle upsample").read(halfColor, Access.SAMPLED_FRAGMENT).read(halfDepth, Access.SAMPLED_FRAGMENT)
            .read(depth, Access.SAMPLED_FRAGMENT).attach(color, Load.LOAD, Store.STORE)
            .record(ctx -> {
                if (list.count(HALF) > 0) {
                    ctx.draw(upsample, upsamplePush.color(ctx.sampled(halfColor)).halfDepth(ctx.sampled(halfDepth))
                        .depth(ctx.sampled(depth)).halfSize(width, height), 3, 1);
                }
            });
    }

    /** The reactive mask: every draw after the glass again, their coverage added into an R8 image, cleared. */
    @AllowAllocation("building the graph, when it changes")
    private void reactive(GraphBuilder g, Image color, Image depth, Buffer pool, Buffer side, Buffer order,
        ParticleSort.Order sorted, Buffer commands, Camera camera, Camera sortCenter, boolean paired) {
        Image mask = g.create("particle reactive mask", ImageDesc.of(Format.R8_UNORM, color.width(), color.height()));
        Pipeline[] pipelines = pipelines(Format.R8_UNORM.vk(), Particles.DRAW_REACTIVE);
        com.truesim.engine.render.RenderView renderView = view.view();
        Draws list = draws;
        ParticleDrawPush push = new ParticleDrawPush();
        g.pass("particle reactive mask").read(pool, BufferAccess.DRAW_READ).read(side, BufferAccess.DRAW_READ)
            .read(order, BufferAccess.DRAW_READ).read(sorted.sorted(), BufferAccess.DRAW_READ)
            .read(sorted.args(), BufferAccess.DRAW_READ).read(commands, BufferAccess.INDIRECT)
            .read(commands, BufferAccess.DRAW_READ).read(depth, Access.SAMPLED_FRAGMENT)
            .attach(mask, Clear.color(0, 0, 0, 0)).region()
            .record(ctx -> {
                int n = list.count();
                list.common(push, renderView.address(), camera, sortCenter, paired).depth(ctx.sampled(depth))
                    .sorted(ctx.address(sorted.sorted())).sortArgs(ctx.address(sorted.args()))
                    .commands(ctx.address(commands)).glassDepth(ParticleConstants.NO_TEXTURE);
                drawRuns(ctx, list, pipelines, push, commands, 0, n);
            });
        g.provide(ParticleKeys.REACTIVE, mask);
    }

    /** Draws {@code from} to {@code to} of the list: runs of draws of one pipeline, a multi-draw each, in order. */
    private static void drawRuns(PassContext ctx, Draws list, Pipeline[] pipelines, ParticleDrawPush push,
        Buffer commands, int from, int to) {
        for (int i = from; i < to; ) {
            int kind = list.kind(i);
            int end = i + 1;
            while (end < to && list.kind(end) == kind) {
                end++;
            }
            ctx.drawIndirect(pipelines[kind], push.first(i), commands, 16L * i, end - i);
            i = end;
        }
    }

    /**
     * A view's draws in a frame: each renderer of each emitter instance and placement of the technique's side of the
     * glass (but meshes and lights, trails of CPU emitters, which keep no past positions, and variants of shared
     * effects, drawn at their placements), in groups (half resolution, full, split at glass), each back to front,
     * written once per frame by whichever of the passes records first (they may record on two threads), with what each
     * draw reads: its emitter, renderer, texture, sorted run and beam end.
     */
    static final class Draws implements AutoCloseable {
        private final Particles particles;
        private final boolean before;
        private final FrameBuffer records;
        private final Staging staged = new Staging((long) ParticleDrawRecordLayout.BYTES * MAX_DRAWS);
        private final int[] emitter = new int[MAX_DRAWS];
        private final int[] renderer = new int[MAX_DRAWS];
        private final int[] texture = new int[MAX_DRAWS];
        private final int[] kind = new int[MAX_DRAWS];
        private final int[] index = new int[MAX_DRAWS];
        private final int[] group = new int[MAX_DRAWS];
        private final double[] distance = new double[MAX_DRAWS];
        private final boolean[] sorted = new boolean[MAX_DRAWS];
        private final int[] order = new int[MAX_DRAWS];
        private final int[] starts = new int[GROUPS + 1];
        private long frame = -1;
        private int count;
        private int lit; // draws lit at all, and per vertex, and the most particles of those per vertex
        private int vertexLit;
        private int largestLit;

        @AllowAllocation("made with its view")
        Draws(Particles particles, boolean before) {
            this.particles = particles;
            this.before = before;
            records = FrameBuffer.create(particles.device(), (long) ParticleDrawRecordLayout.BYTES * MAX_DRAWS,
                VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT, particles.slots());
        }

        /**
         * The group renderer {@code r} of the record at {@code e} draws in on this side of the glass, -1 for none:
         * before glass the renderers drawn there and those split (with a glass depth); after it the others, at half
         * resolution where they are drawn so.
         */
        private int groupOf(int e, int r, boolean split) {
            int drawn = particles.rendererDrawn(e, r);
            boolean beforeGlass = (drawn & ModulePrograms.DRAWN_BEFORE_GLASS) != 0;
            boolean splits = split && (drawn & ModulePrograms.DRAWN_SPLIT) != 0;
            if (before) {
                return splits ? SPLIT : beforeGlass ? FULL : -1;
            }
            if (beforeGlass) {
                return -1;
            }
            return splits ? SPLIT : (drawn & ModulePrograms.DRAWN_HALF) != 0 ? HALF : FULL;
        }

        /**
         * Lists the frame's draws seen from {@code camera} once per frame, lit by the light list at {@code lights}
         * (0: the sun), with emitters split at glass apart when {@code split} (a glass depth is there); returns how
         * many there are.
         */
        synchronized int prepare(long frame, Camera camera, long lights, boolean split) {
            if (frame == this.frame) {
                return count;
            }
            this.frame = frame;
            count = 0;
            lit = 0;
            vertexLit = 0;
            largestLit = 0;
            java.util.Arrays.fill(starts, 0);
            if (!particles.drawable()) {
                return 0;
            }
            for (int e = 0; e < particles.drawCount(); e++) {
                if (particles.listHidden(e)) {
                    continue;
                }
                double dx = particles.listPosition(e, 0) - camera.position(0);
                double dy = particles.listPosition(e, 1) - camera.position(1);
                double dz = particles.listPosition(e, 2) - camera.position(2);
                double d = dx * dx + dy * dy + dz * dz;
                for (int r = 0; r < particles.renderers(e) && count < MAX_DRAWS; r++) {
                    int k = particles.rendererKind(e, r);
                    int g = groupOf(e, r, split);
                    if (g < 0 || k == ParticleConstants.RENDER_MESH || k == ParticleConstants.RENDER_LIGHT
                        || k == ParticleConstants.RENDER_TRAIL && particles.listCpu(e)) {
                        continue;
                    }
                    int at = count++;
                    emitter[at] = e;
                    renderer[at] = particles.rendererWord(e, r);
                    texture[at] = particles.rendererTexture(e, r);
                    kind[at] = k;
                    index[at] = r;
                    group[at] = g;
                    distance[at] = d;
                    sorted[at] = particles.rendererSorted(e, r);
                    order[at] = at;
                    starts[g + 1]++;
                }
            }
            for (int g = 0; g < GROUPS; g++) {
                starts[g + 1] += starts[g];
            }
            sort();
            for (int i = 0; i < count; i++) {
                int o = order[i];
                long at = (long) ParticleDrawRecordLayout.BYTES * i;
                int e = emitter[o];
                staged.putInt(at + ParticleDrawRecordLayout.EMITTER, e);
                staged.putInt(at + ParticleDrawRecordLayout.RENDERER, renderer[o]);
                staged.putInt(at + ParticleDrawRecordLayout.TEXTURE, texture[o]);
                staged.putInt(at + ParticleDrawRecordLayout.SORT, sorted[o] ? particles.sortOf(e)
                    : ParticleConstants.NO_SORT);
                staged.putFloat(at + ParticleDrawRecordLayout.TIME, particles.listTime(e));
                int lighting = kind[o] == ParticleConstants.RENDER_BILLBOARD ? particles.rendererLighting(e, index[o])
                    : ParticleConstants.LIGHTING_UNLIT;
                if (lighting == ParticleConstants.LIGHTING_VERTEX && particles.listPlacement(e)) {
                    lighting = ParticleConstants.LIGHTING_PIXEL; // a slot's light is its variant's, not where it is
                }
                staged.putInt(at + ParticleDrawRecordLayout.LIGHTING, lighting);
                staged.putInt(at + ParticleDrawRecordLayout.EXTRA, particles.rendererExtra(e, index[o]));
                staged.putLong(at + ParticleDrawRecordLayout.LIGHTS, lights);
                lit += lighting != ParticleConstants.LIGHTING_UNLIT ? 1 : 0;
                if (lighting == ParticleConstants.LIGHTING_VERTEX) {
                    vertexLit++;
                    largestLit = Math.max(largestLit, particles.capacityOf(e));
                }
                if (kind[o] == ParticleConstants.RENDER_BEAM) {
                    staged.putVector(at + ParticleDrawRecordLayout.END,
                        (float) (particles.beamEnd(e, index[o], 0) - camera.position(0)),
                        (float) (particles.beamEnd(e, index[o], 1) - camera.position(1)),
                        (float) (particles.beamEnd(e, index[o], 2) - camera.position(2)));
                }
            }
            for (int i = 0; i < count; i++) { // kinds by place, for the passes' runs
                index[i] = kind[order[i]];
            }
            System.arraycopy(index, 0, kind, 0, count);
            staged.copyTo(records, (long) ParticleDrawRecordLayout.BYTES * count);
            return count;
        }

        /** Heap sort of the draws' order by group, then farthest first, then as listed: deterministic, allocation-free. */
        private void sort() {
            for (int i = count / 2 - 1; i >= 0; i--) {
                sift(i, count);
            }
            for (int end = count - 1; end > 0; end--) {
                int top = order[0];
                order[0] = order[end];
                order[end] = top;
                sift(0, end);
            }
        }

        private void sift(int i, int size) {
            while (2 * i + 1 < size) {
                int child = 2 * i + 1;
                if (child + 1 < size && before(order[child], order[child + 1])) {
                    child++;
                }
                if (!before(order[i], order[child])) {
                    return;
                }
                int swap = order[i];
                order[i] = order[child];
                order[child] = swap;
                i = child;
            }
        }

        /** Whether draw {@code a} comes before draw {@code b}. */
        private boolean before(int a, int b) {
            if (group[a] != group[b]) {
                return group[a] < group[b];
            }
            if (distance[a] != distance[b]) {
                return distance[a] > distance[b];
            }
            return a < b;
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
                .flags(paired ? 1 : 0);
        }

        /** The frame's draws. */
        int count() {
            return count;
        }

        /** The frame's draws of group {@code g}. */
        int count(int g) {
            return starts[g + 1] - starts[g];
        }

        /** The first draw of group {@code g} (the count past the last). */
        int start(int g) {
            return g == GROUPS ? count : starts[g];
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
