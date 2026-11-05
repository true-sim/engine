package com.truesim.engine.render;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.memPutInt;
import static org.lwjgl.system.MemoryUtil.memPutLong;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.graph.Access;
import com.truesim.engine.graph.Buffer;
import com.truesim.engine.graph.BufferAccess;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Image;
import com.truesim.engine.graph.PassContext;
import com.truesim.engine.render.layout.ShadowMasksPush;
import com.truesim.engine.render.layout.ShadowTraceLayout;
import com.truesim.engine.render.shadows.ShadowSettings;
import com.truesim.engine.vulkan.Commands;
import com.truesim.engine.vulkan.Pipeline;
import java.util.function.Function;
import org.lwjgl.system.MemoryStack;

/**
 * The path tracer's shadow rays through the holes of geometry hooks ({@code keep()}): where the material table has
 * types with hooks, a shadow ray of next-event estimation (paths.slang's lightVisible) whose nearest triangle is of such
 * a type waits in a queue with the light it brings; after the bounces, each such type's keep entry point
 * ({@code material-types/entryShadowKeep}) tests the points, rays through holes go on (shadows/masks.slang's
 * lightRaysMain), up to {@link ShadowSettings#KEEP_ROUNDS} holes, and what comes through is added to each pixel's (or
 * probe ray's) radiance (lightMergeMain). The reference path tracer, the probe volume and the radiance cascades use it,
 * so their shadows have the holes every other pass draws.
 *
 * <p>Its data lie in the paths' counts buffer, past the counts: a trace record (shadows/rays.slang's
 * {@code ShadowTrace}), two queues and the sums. Without types with hooks it is not made: shadow rays never wait.
 */
@AllowAllocation("building the graph, when it changes")
final class LightRays {

    private static final int QUEUE_HEADER = 16;
    private static final int RECORD = 80;

    private final MeshletRenderer renderer;
    private final RayTracing rayTracing;
    private final RenderView view;
    private final int width;
    private final int height;
    private final int capacity;
    private final int rounds;
    private final Pipeline[] keeps;
    private final Pipeline continuing;
    private final Pipeline merge;
    private final long recordBytes;
    private final long queueBytes;
    private Buffer counts;
    private long at;

    private LightRays(GraphBuilder g, MeshletRenderer renderer, RayTracing rayTracing, RenderView view, int width,
        int height) {
        this.renderer = renderer;
        this.rayTracing = rayTracing;
        this.view = view;
        this.width = width;
        this.height = height;
        this.capacity = g.setting(ShadowSettings.RAY_QUEUE);
        this.rounds = g.setting(ShadowSettings.KEEP_ROUNDS);
        Materials materials = renderer.scene().materials();
        keeps = new Pipeline[materials.geometryTypeCount()];
        for (int k = 0; k < keeps.length; k++) {
            MaterialType type = materials.geometryType(k + 1);
            keeps[k] = g.computeProgram(type.geometryShader().replace("Geometry.spv", "ShadowKeep.spv"),
                type.name() + "ShadowKeepMain", ShadowMasksPush.BYTES);
        }
        continuing = g.computeProgram("shaders/shadows/masks.spv", "lightRaysMain", ShadowMasksPush.BYTES);
        merge = g.computeProgram("shaders/shadows/masks.spv", "lightMergeMain", ShadowMasksPush.BYTES);
        recordBytes = (ShadowTraceLayout.BYTES + 15) / 16 * 16;
        queueBytes = QUEUE_HEADER + (long) RECORD * capacity;
    }

    /**
     * Light rays for paths of {@code renderer}'s scene traced in {@code rayTracing}, keep() run through {@code view}'s
     * camera, their pixels in a {@code width} x {@code height} image; null where the table has no type with hooks.
     */
    static LightRays of(GraphBuilder g, MeshletRenderer renderer, RayTracing rayTracing, RenderView view, int width,
        int height) {
        if (renderer.scene().materials().geometryTypeCount() == 0) {
            return null;
        }
        return new LightRays(g, renderer, rayTracing, view, width, height);
    }

    /** The bytes it takes in the counts buffer. */
    long bytes() {
        return recordBytes + 2 * queueBytes + 12L * width * height;
    }

    /** Places it in {@code counts} at byte {@code offset} (a multiple of 16), past the counts. */
    void place(Buffer counts, long offset) {
        if (offset % 16 != 0) {
            throw new IllegalArgumentException("At a multiple of 16: " + offset);
        }
        this.counts = counts;
        this.at = offset;
    }

    /** PathPush's lightRays: words from the counts buffer's start to its record. */
    int words() {
        return (int) (at / 4);
    }

    /**
     * Writes its record and empties its queues and sums: from a pass writing the counts buffer as a transfer
     * destination, ranges of it apart from this one's. Allocates nothing.
     */
    void reset(PassContext ctx) {
        try (MemoryStack stack = stackPush()) {
            long record = stack.ncalloc(16, 1, (int) recordBytes);
            memPutInt(record + ShadowTraceLayout.SIZE, width | height << 16);
            memPutInt(record + ShadowTraceLayout.CAPACITY, capacity);
            memPutLong(record + ShadowTraceLayout.SCENE, rayTracing.sceneAddress(renderer.scene()));
            memPutLong(record + ShadowTraceLayout.SCENE_TABLE, renderer.scene().tableAddress());
            memPutLong(record + ShadowTraceLayout.VIEW, view.address());
            memPutInt(record + ShadowTraceLayout.QUEUE_B, (int) ((recordBytes + queueBytes) / 4));
            memPutInt(record + ShadowTraceLayout.SUMS, (int) ((recordBytes + 2 * queueBytes) / 4));
            Commands.updateBuffer(ctx.unsafe().commands(), ctx.unsafe().buffer(counts), at, recordBytes, record);
        }
        for (int q = 0; q < 2; q++) {
            long header = at + recordBytes + q * queueBytes;
            ctx.fill(counts, header, 8, 0);
            ctx.fill(counts, header + 8, 8, 1);
        }
        ctx.fill(counts, at + recordBytes + 2 * queueBytes, 12L * width * height, 0);
    }

    /**
     * After the bounces: the keep tests and the rays going on, round after round, then each pixel's light added to
     * storage image {@code radiance} (rgb), in passes {@code pass} makes (on the queue the paths' passes are on).
     */
    void resolve(GraphBuilder g, String name, Function<String, GraphBuilder.PassBuilder> pass, Buffer topLevel,
        Image radiance) {
        for (int round = 0; round < rounds; round++) {
            long in = at + recordBytes + (round & 1) * queueBytes;
            long out = at + recordBytes + ((round + 1) & 1) * queueBytes;
            boolean last = round == rounds - 1;
            for (int k = 0; k < keeps.length; k++) {
                Pipeline keep = keeps[k];
                int geometry = k + 1;
                ShadowMasksPush push = new ShadowMasksPush();
                pass.apply(name + " light keep " + round + " type " + geometry).read(counts, BufferAccess.INDIRECT)
                    .write(counts, BufferAccess.STORAGE_WRITE).record(ctx -> {
                        long base = ctx.address(counts);
                        push.trace(base + at).queueIn(base + in).queueOut(0).mode(geometry).pad(RECORD / 4);
                        ctx.dispatchIndirect(keep, push, counts, in + 4);
                    });
            }
            pass.apply(name + " light rays reset " + round).write(counts, BufferAccess.TRANSFER_DST).record(ctx -> {
                ctx.fill(counts, out, 8, 0);
                ctx.fill(counts, out + 8, 8, 1);
            });
            ShadowMasksPush push = new ShadowMasksPush();
            pass.apply(name + " light rays " + round).read(topLevel, BufferAccess.ACCELERATION_STRUCTURE_READ)
                .read(counts, BufferAccess.INDIRECT).write(counts, BufferAccess.STORAGE_WRITE).record(ctx -> {
                    long base = ctx.address(counts);
                    push.trace(base + at).queueIn(base + in).queueOut(base + out).mode(last ? 1 : 0).pad(0);
                    ctx.dispatchIndirect(continuing, push, counts, in + 4);
                });
        }
        ShadowMasksPush push = new ShadowMasksPush();
        pass.apply(name + " light merge").read(counts, BufferAccess.STORAGE_READ).write(radiance, Access.STORAGE_WRITE)
            .record(ctx -> {
                push.trace(ctx.address(counts) + at).queueIn(0).queueOut(0).mode(ctx.storage(radiance)).pad(0);
                ctx.dispatch(merge, push, (width + 7) / 8, (height + 7) / 8, 1);
            });
    }
}
