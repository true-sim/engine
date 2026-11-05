package com.truesim.engine.render.shadows;

import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
import static org.lwjgl.vulkan.VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.graph.Access;
import com.truesim.engine.graph.Buffer;
import com.truesim.engine.graph.BufferAccess;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Image;
import com.truesim.engine.graph.ImageDesc;
import com.truesim.engine.graph.PassContext;
import com.truesim.engine.graph.Ratio;
import com.truesim.engine.graph.Technique;
import com.truesim.engine.render.Camera;
import com.truesim.engine.render.Denoiser;
import com.truesim.engine.render.Lights;
import com.truesim.engine.render.MaterialType;
import com.truesim.engine.render.Materials;
import com.truesim.engine.render.MeshletRenderer;
import com.truesim.engine.render.RayTracing;
import com.truesim.engine.render.RenderView;
import com.truesim.engine.render.SceneDraw;
import com.truesim.engine.render.SceneView;
import com.truesim.engine.render.ShadowInputs;
import com.truesim.engine.render.ShadowTable;
import com.truesim.engine.render.SurfaceOutput;
import com.truesim.engine.render.SurfaceOutputs;
import com.truesim.engine.render.ViewOptions;
import com.truesim.engine.render.VisibilityBufferShading;
import com.truesim.engine.render.layout.ShadowMasksPush;
import com.truesim.engine.render.layout.ShadowTraceLayout;
import com.truesim.engine.render.layout.TraceSlotLayout;
import com.truesim.engine.settings.EngineSettings;
import com.truesim.engine.vulkan.FrameBuffer;
import com.truesim.engine.vulkan.Format;
import com.truesim.engine.vulkan.Pipeline;
import com.truesim.scene.core.ShadowMethod;
import com.truesim.scene.core.ShadowSpec;
import java.util.Arrays;

/**
 * A view's ray-traced shadows for the {@link Shadows} owner: a mask per traced light, traced from the view's depth
 * before shading (the visibility buffer's own IDs draw, or a depth draw of its own for forward shading), in one
 * dispatch for every traced light of the frame (shadows/masks.slang). Hard shadows trace one ray per pixel to the
 * light; soft ones {@link ShadowSettings#SOFT_RAYS} to points of its disc or sphere ({@link ShadowSpec#softness}), then
 * the engine's denoiser ({@link Denoiser}, {@link ShadowSettings#SOFT_DENOISER}) filters each light's visibility.
 *
 * <p>Rays respect materials' shapes: alpha masks as everywhere, and the holes of a type's geometry hook
 * ({@code keep()}): a ray whose nearest triangle is of such a type waits in a queue, that type's keep entry point
 * ({@code material-types/entryShadowKeep}) tests the point, and the ray goes on through a hole
 * ({@link ShadowSettings#KEEP_ROUNDS} holes at most). A light keeps its slot (mask, denoiser history) while it is
 * traced; slots are made as more lights are traced than ever before (the graph builds again), never fewer.
 */
final class TracedMasks implements AutoCloseable {

    private static final int SOFT = 1;

    private final Shadows owner;
    private final SceneView view;
    private final Lights lights;
    private final MeshletRenderer renderer;
    // Slots, kept by their light: hard ones then soft ones; -1 free
    private int[] hardLight = new int[0];
    private int[] softLight = new int[0];
    private boolean[] softRestart = new boolean[0];
    private int hardWanted;
    private int softWanted;
    private int hardBuilt;
    private int softBuilt;
    private long structure;
    // This frame: each traced light's slot (hard: index, soft: -2 - index; -1 none), the dispatch's slots in order
    private int[] slotOf = new int[0];
    private final int[] active = new int[256];
    private int activeCount;
    private final ShadowSpec[] activeSpec = new ShadowSpec[256];
    private boolean[] softActive = new boolean[0];
    private FrameBuffer trace; // ShadowTrace, then the TraceSlots: a copy per frame in flight
    private RenderView depthView; // forward shading's own depth draw

    TracedMasks(Shadows owner, SceneView view, Lights lights) {
        this.owner = owner;
        this.view = view;
        this.lights = lights;
        this.renderer = view.view().renderer();
    }

    /** A new frame: no light traced yet. */
    void startFrame() {
        int capacity = lights.capacity();
        if (slotOf.length < capacity) {
            grow(capacity);
        }
        Arrays.fill(slotOf, -1);
        activeCount = 0;
        Arrays.fill(softActive, false);
        Arrays.fill(softRestart, false);
    }

    @AllowAllocation("growing with the lights")
    private void grow(int capacity) {
        slotOf = new int[capacity];
    }

    /** Light {@code light} is traced in this view this frame, as {@code spec} says ({@link #assign} gives slots). */
    void add(int light, ShadowSpec spec) {
        if (activeCount == active.length) {
            return; // more traced lights than a dispatch takes: lit
        }
        activeSpec[activeCount] = spec;
        active[activeCount++] = light;
    }

    /**
     * Gives the frame's traced lights their slots: the slot each had, else a free one (a soft one's history
     * restarting), else a new one (the graph builds again); slots of lights no longer traced are free.
     */
    void assign() {
        for (int k = 0; k < activeCount; k++) {
            int light = active[k];
            boolean soft = activeSpec[k].method() == ShadowMethod.SOFT_RAY_TRACED;
            int[] owners = soft ? softLight : hardLight;
            for (int i = 0; i < owners.length; i++) {
                if (owners[i] == light) {
                    slotOf[light] = soft ? -2 - i : i;
                }
            }
        }
        for (int i = 0; i < hardLight.length; i++) {
            if (hardLight[i] >= 0 && slotOf[hardLight[i]] != i) {
                hardLight[i] = -1;
            }
        }
        for (int i = 0; i < softLight.length; i++) {
            if (softLight[i] >= 0 && slotOf[softLight[i]] != -2 - i) {
                softLight[i] = -1;
            }
        }
        for (int k = 0; k < activeCount; k++) {
            int light = active[k];
            boolean soft = activeSpec[k].method() == ShadowMethod.SOFT_RAY_TRACED;
            if (slotOf[light] == -1) {
                int slot = soft ? free(softLight) : free(hardLight);
                if (slot < 0) {
                    if (soft) {
                        softLight = grown(softLight, ++softWanted);
                        softRestart = Arrays.copyOf(softRestart, softWanted);
                        softActive = Arrays.copyOf(softActive, softWanted);
                        slot = softWanted - 1;
                    } else {
                        hardLight = grown(hardLight, ++hardWanted);
                        slot = hardWanted - 1;
                    }
                    structure++;
                }
                if (soft) {
                    softLight[slot] = light;
                    softRestart[slot] = true; // another light's history, or none
                } else {
                    hardLight[slot] = light;
                }
                slotOf[light] = soft ? -2 - slot : slot;
            }
            int slot = slotOf[light];
            if (slot < -1) {
                softActive[-2 - slot] = true;
            }
            active[k] = slot;
        }
    }

    private static int free(int[] owners) {
        for (int i = 0; i < owners.length; i++) {
            if (owners[i] < 0) {
                return i;
            }
        }
        return -1;
    }

    @AllowAllocation("more traced lights than ever")
    private static int[] grown(int[] owners, int length) {
        int[] more = Arrays.copyOf(owners, length);
        Arrays.fill(more, owners.length, length, -1);
        return more;
    }

    /** What the graph is built for: it builds again when this changes. */
    long structure() {
        Materials materials = renderer.scene().materials();
        return structure * 31 + materials.geometryTypeCount();
    }

    @Override
    public void close() {
        if (trace != null) {
            trace.close();
            trace = null;
        }
        if (depthView != null) {
            depthView.close();
            depthView = null;
        }
    }

    /** Adds the view's tracing, denoising and the masks shading samples, for the slots there are now. */
    @AllowAllocation("building the graph, when it changes")
    void build(GraphBuilder g, ShadowInputs.Set inputs, RayTracing rayTracing) {
        hardBuilt = hardWanted;
        softBuilt = softWanted;
        if (rayTracing == null || hardBuilt + softBuilt == 0) {
            return;
        }
        EngineSettings settings = g.settings();
        int slots = hardBuilt + softBuilt;
        if (trace == null || trace.size() < ShadowTraceLayout.BYTES + (long) TraceSlotLayout.BYTES * slots) {
            if (trace != null) {
                FrameBuffer old = trace;
                view.port().own(old); // freed with the view; frames in flight may read it
            }
            trace = FrameBuffer.create(rayTracing.device(), ShadowTraceLayout.BYTES
                + (long) TraceSlotLayout.BYTES * Math.max(slots, 4), VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
                | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT, renderer.scene().frameSlots());
        }
        SceneDraw draw = depthSource(g);
        Image depth = g.request(draw).depth();
        Buffer topLevel = g.request(new TopLevel(rayTracing, renderer));
        Pipeline tracing = g.computeProgram("shaders/shadows/masks.spv", "traceMain", ShadowMasksPush.BYTES);
        Pipeline continuing = g.computeProgram("shaders/shadows/masks.spv", "continueMain", ShadowMasksPush.BYTES);
        Materials materials = renderer.scene().materials();
        int hooks = materials.geometryTypeCount();
        Pipeline[] keeps = new Pipeline[hooks];
        for (int k = 0; k < hooks; k++) {
            MaterialType type = materials.geometryType(k + 1);
            keeps[k] = g.computeProgram(type.geometryShader().replace("Geometry.spv", "ShadowKeep.spv"),
                type.name() + "ShadowKeepMain", ShadowMasksPush.BYTES);
        }
        Image[] masks = new Image[hardBuilt];
        for (int i = 0; i < hardBuilt; i++) {
            masks[i] = g.create("shadow mask " + i, ImageDesc.like(depth, Ratio.ONE).format(Format.R8_UNORM));
        }
        Image[] softA = new Image[softBuilt];
        Image[] softB = new Image[softBuilt];
        for (int i = 0; i < softBuilt; i++) {
            softA[i] = g.create("soft shadow " + i, ImageDesc.like(depth, Ratio.ONE).format(Denoiser.format()));
            softB[i] = g.create("soft shadow " + i + " B", ImageDesc.like(depth, Ratio.ONE).format(Denoiser.format()));
        }
        Image normal = softBuilt > 0 ? g.create("shadow normals", ImageDesc.like(depth, Ratio.ONE)
            .format(SurfaceOutputs.NORMAL_FORMAT)) : null;
        int capacity = settings.get(ShadowSettings.RAY_QUEUE);
        long queueBytes = 16L + 32L * capacity;
        int rounds = hooks > 0 ? settings.get(ShadowSettings.KEEP_ROUNDS) : 0;
        Buffer[] queues = {g.createBuffer("shadow rays A", queueBytes), g.createBuffer("shadow rays B", queueBytes)};
        java.util.function.BooleanSupplier any = () -> activeCount > 0;
        g.pass("traced shadows reset").write(queues[0], BufferAccess.TRANSFER_DST)
            .write(queues[1], BufferAccess.TRANSFER_DST).when(any).record(ctx -> {
                for (Buffer queue : queues) {
                    ctx.fill(queue, 0, 8, 0);
                    ctx.fill(queue, 8, 8, 1);
                }
            });
        GraphBuilder.PassBuilder pass = g.pass("traced shadows").read(depth, Access.SAMPLED_COMPUTE)
            .read(topLevel, BufferAccess.ACCELERATION_STRUCTURE_READ).write(queues[0], BufferAccess.STORAGE_WRITE)
            .region().async().when(any);
        writes(pass, masks, softA, softB);
        if (normal != null) {
            pass.write(normal, Access.STORAGE_WRITE);
        }
        ShadowMasksPush push = new ShadowMasksPush();
        float offset = settings.get(ShadowSettings.RAY_OFFSET);
        pass.record(ctx -> {
            long header = writeTrace(ctx, rayTracing, depth, normal, masks, softA, softB, offset, capacity,
                settings.get(ShadowSettings.SOFT_RAYS));
            int width = ctx.regionWidth(depth);
            int height = ctx.regionHeight(depth);
            push.trace(header).queueIn(ctx.address(queues[0])).queueOut(ctx.address(queues[0])).mode(0);
            ctx.dispatch(tracing, push, (width + 7) / 8, (height + 7) / 8, activeCount);
        });
        for (int round = 0; round < rounds; round++) {
            Buffer in = queues[round & 1];
            Buffer out = queues[(round + 1) & 1];
            boolean last = round == rounds - 1;
            for (int k = 0; k < hooks; k++) {
                Pipeline keep = keeps[k];
                int geometry = k + 1;
                ShadowMasksPush keepPush = new ShadowMasksPush();
                g.pass("shadow keep " + round + " type " + geometry).read(in, BufferAccess.INDIRECT)
                    .write(in, BufferAccess.STORAGE_WRITE).async().when(any).record(ctx -> {
                        keepPush.trace(trace.deviceAddress()).queueIn(ctx.address(in)).queueOut(0).mode(geometry);
                        ctx.dispatchIndirect(keep, keepPush, in, 4);
                    });
            }
            if (round > 0) {
                g.pass("shadow rays reset " + round).write(out, BufferAccess.TRANSFER_DST).when(any).record(ctx -> {
                    ctx.fill(out, 0, 8, 0);
                    ctx.fill(out, 8, 8, 1);
                });
            }
            GraphBuilder.PassBuilder next = g.pass("shadow rays " + round).read(depth, Access.SAMPLED_COMPUTE)
                .read(topLevel, BufferAccess.ACCELERATION_STRUCTURE_READ).read(in, BufferAccess.INDIRECT)
                .read(in, BufferAccess.STORAGE_READ).write(out, BufferAccess.STORAGE_WRITE).async().when(any);
            writes(next, masks, softA, softB);
            ShadowMasksPush continuePush = new ShadowMasksPush();
            next.record(ctx -> {
                continuePush.trace(trace.deviceAddress()).queueIn(ctx.address(in)).queueOut(ctx.address(out))
                    .mode(last ? 1 : 0);
                ctx.dispatchIndirect(continuing, continuePush, in, 4);
            });
        }
        for (Image mask : masks) {
            inputs.sample(mask);
        }
        this.masks = masks;
        Image[] denoised = new Image[softBuilt];
        Denoiser.Mode mode = settings.get(ShadowSettings.SOFT_DENOISER);
        int passes = settings.get(ShadowSettings.SOFT_PASSES);
        for (int i = 0; i < softBuilt; i++) {
            int slot = i;
            Image a = softA[i];
            Image b = softB[i];
            Image[] result = new Image[1];
            g.masked(() -> softActive.length > slot && softActive[slot], () -> result[0] = Denoiser.denoiseValue(g,
                "soft shadow " + slot, view, depth, normal, null, a, b, mode,
                () -> softRestart.length > slot && softRestart[slot], passes));
            denoised[i] = result[0];
            inputs.sample(denoised[i]);
        }
        this.denoised = denoised;
    }

    private Image[] masks = new Image[0];
    private Image[] denoised = new Image[0];

    private static void writes(GraphBuilder.PassBuilder pass, Image[] masks, Image[] softA, Image[] softB) {
        for (Image mask : masks) {
            pass.write(mask, Access.STORAGE_WRITE);
        }
        for (int i = 0; i < softA.length; i++) {
            pass.write(softA[i], Access.STORAGE_WRITE).write(softB[i], Access.STORAGE_WRITE);
        }
    }

    /** The draw of the view's depth before shading: the visibility buffer's own, or one of its own. */
    @AllowAllocation("building the graph, when it changes")
    private SceneDraw depthSource(GraphBuilder g) {
        for (Technique technique : g.techniquesInView()) {
            if (technique instanceof VisibilityBufferShading shading) {
                return shading.idsDraw(g);
            }
        }
        if (depthView == null) {
            depthView = renderer.createView(ViewOptions.of(view.view().options().draws(),
                view.view().options().triangles()));
        }
        return new SceneDraw(com.truesim.engine.render.SceneGeometry.meshlets(renderer), depthView, view.camera(),
            SurfaceOutput.depth(), renderer.depthFormat(), view.width(), view.height(), false, view.port());
    }

    /** This frame's trace header and slots, written when the trace records; returns its address. */
    private long writeTrace(PassContext ctx, RayTracing rayTracing, Image depth, Image normal, Image[] masks,
        Image[] softA, Image[] softB, float offset, int capacity, int softRays) {
        Camera camera = view.camera();
        float[] inverse = camera.inverseViewProjection();
        float[] matrix = camera.viewProjection();
        for (int i = 0; i < 16; i++) {
            trace.putFloat(ShadowTraceLayout.INVERSE_VIEW_PROJECTION + 4L * i, inverse[i]);
            trace.putFloat(ShadowTraceLayout.VIEW_PROJECTION + 4L * i, matrix[i]);
        }
        for (int axis = 0; axis < 3; axis++) {
            double p = camera.position(axis);
            float high = (float) p;
            trace.putFloat(ShadowTraceLayout.CAMERA_HIGH + 4L * axis, high);
            trace.putFloat(ShadowTraceLayout.CAMERA_LOW + 4L * axis, (float) (p - high));
            trace.putFloat(ShadowTraceLayout.TO_TOP_LEVEL + 4L * axis, (float) (p - rayTracing.origin(axis)));
        }
        trace.putInt(ShadowTraceLayout.DEPTH, ctx.sampled(depth));
        trace.putInt(ShadowTraceLayout.NORMAL, normal == null ? 0 : ctx.storage(normal));
        trace.putInt(ShadowTraceLayout.SIZE, ctx.regionWidth(depth) | ctx.regionHeight(depth) << 16);
        trace.putFloat(ShadowTraceLayout.OFFSET, offset);
        trace.putInt(ShadowTraceLayout.FRAME, (int) ctx.frame());
        trace.putInt(ShadowTraceLayout.CAPACITY, capacity);
        trace.putInt(ShadowTraceLayout.SLOTS, activeCount);
        trace.putLong(ShadowTraceLayout.SCENE, rayTracing.sceneAddress(renderer.scene()));
        trace.putLong(ShadowTraceLayout.LIGHTS, lights.lightsAddress());
        long slots = trace.deviceAddress() + ShadowTraceLayout.BYTES;
        trace.putLong(ShadowTraceLayout.TRACE_SLOTS, slots);
        trace.putLong(ShadowTraceLayout.SCENE_TABLE, renderer.scene().tableAddress());
        trace.putLong(ShadowTraceLayout.VIEW, view.view().address());
        for (int k = 0; k < activeCount; k++) {
            long o = ShadowTraceLayout.BYTES + (long) TraceSlotLayout.BYTES * k;
            int slot = active[k];
            ShadowSpec spec = activeSpec[k];
            int light = slot >= 0 ? hardLight[slot] : softLight[-2 - slot];
            boolean soft = slot < 0;
            trace.putInt(o + TraceSlotLayout.LIGHT, light);
            trace.putInt(o + TraceSlotLayout.OUTPUT, ctx.storage(soft ? softA[-2 - slot] : masks[slot]));
            trace.putInt(o + TraceSlotLayout.OUTPUT_B, soft ? ctx.storage(softB[-2 - slot]) : 0);
            trace.putInt(o + TraceSlotLayout.FLAGS, soft ? SOFT : 0);
            trace.putInt(o + TraceSlotLayout.RAYS, soft ? softRays : 1);
            trace.putFloat(o + TraceSlotLayout.SOFTNESS, (float) spec.softness());
            trace.putFloat(o + TraceSlotLayout.DISTANCE, (float) spec.distance());
        }
        return trace.deviceAddress();
    }

    /** Light {@code light}'s mask entry at {@code entry} if it is traced in this view: 1, else 0. */
    int write(ShadowTable table, int light, int entry, PassContext ctx) {
        if (light >= slotOf.length || slotOf[light] == -1) {
            return 0;
        }
        int slot = slotOf[light];
        if (slot >= 0 ? slot >= masks.length : -2 - slot >= denoised.length) {
            return 0; // not built yet
        }
        if (entry >= table.capacity()) {
            return -1;
        }
        if (slot >= 0) {
            table.shadowMask(entry, ctx.sampled(masks[slot]), 0);
        } else {
            table.shadowMask(entry, ctx.sampled(denoised[-2 - slot]), 3);
        }
        return 1;
    }
}
