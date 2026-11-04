package com.truesim.engine.render;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.memPutFloat;
import static org.lwjgl.system.MemoryUtil.memPutInt;
import static org.lwjgl.system.MemoryUtil.memPutLong;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_SHADER_STORAGE_READ_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_TRANSFER_WRITE_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_INDIRECT_COMMAND_READ_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_DRAW_INDIRECT_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_TRANSFER_BIT;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.graph.Buffer;
import com.truesim.engine.graph.BufferAccess;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Key;
import com.truesim.engine.graph.PassContext;
import com.truesim.engine.graph.Request;
import com.truesim.engine.render.layout.WorldBuildPush;
import com.truesim.engine.render.layout.WorldLightGridLayout;
import com.truesim.engine.settings.Setting;
import com.truesim.engine.settings.TechniqueConfig;
import com.truesim.engine.settings.TechniqueConfig.Tunable;
import com.truesim.engine.settings.TechniqueKind;
import com.truesim.engine.settings.TechniqueSlot;
import com.truesim.engine.vulkan.Barriers;
import com.truesim.engine.vulkan.Commands;
import com.truesim.engine.vulkan.Pipeline;
import java.util.List;
import java.util.Objects;
import org.lwjgl.system.MemoryStack;

/**
 * Lights for points anywhere, not only on screen (global illumination's ray hits, design D2): a grid of cells in world
 * space around {@code anchor}'s camera, built once a frame (a shared request: every view and technique asking for the
 * same lights and anchor gets one build) from {@code lights} and the lights the GPU wrote (particle lights). Its
 * buffer is lights/world.slang's {@code WorldLightGrid}; a point finds its cell, and outside the grid every light.
 * Two layouts trade off, the kinds of {@link #SLOT} ({@code render.gi.lights}):
 *
 * <ul>
 *   <li>{@link #LISTS}: each cell lists the lights whose reach meets its box ({@code slots} at most, overflow
 *   noted); a point sums them all, each with a shadow ray: exact, its cost growing with the lights near it;
 *   <li>{@link #RESERVOIRS} (ReGIR, Boksansky, Jukarainen and Wyman, Ray Tracing Gems II, 2021): each cell keeps
 *   {@code slots} reservoirs, each a light resampled from {@code candidates} drawn uniformly among all the lights by
 *   what it could give the cell's box (never 0 where its reach meets it); a point resamples its cell's reservoirs by
 *   what each light gives it and traces one shadow ray: unbiased, its cost constant in the light count, noisy. Only
 *   the cells hits used in the last {@code keep} frames are built (a cell's state kept from frame to frame); a hit in
 *   another sums every light, exactly, and marks its cell for the next frame.
 * </ul>
 *
 * <p>Directional lights reach every point: points sum them directly in both. The cells are {@code cell} units
 * across, {@code cells} along each axis, the grid's corner snapped to the cell size so still cells stay. Allocates
 * nothing per frame.
 *
 * <p>The layouts are the shaders': a game's kind of this slot makes one of the {@link Mode}s, with the tunables of
 * that mode's kind (components of the same names).
 *
 * @param lights the scene's lights
 * @param anchor the camera the grid is centered on (the first view's, or a headset's center)
 */
public record WorldLights(Lights lights, Camera anchor) implements Request<WorldLights.Grid> {

    /**
     * The grid's buffer, and with reservoirs the cells' state kept from frame to frame (which hits used, which were
     * built), which hits write: passes reading the grid declare both ({@link #readBy}).
     */
    public record Grid(Buffer grid, Buffer cells) {

        /** Declares that {@code pass} reads the grid (and writes the cells' use); returns {@code pass}. */
        public GraphBuilder.PassBuilder readBy(GraphBuilder.PassBuilder pass) {
            pass.read(grid, BufferAccess.STORAGE_READ);
            if (cells != null) {
                pass.write(cells, BufferAccess.STORAGE_WRITE);
            }
            return pass;
        }
    }

    /** The grid's layouts. */
    public enum Mode {
        /** Each cell's lights listed, all summed: exact, cost growing with the lights a cell holds. */
        LISTS,
        /** Reservoirs per cell resampled (ReGIR): unbiased, constant cost, noisy. */
        RESERVOIRS
    }

    /** The grid's layout, a kind's id; the graph rebuilds when it or its tunables change. */
    public static final TechniqueSlot<Void, Mode> SLOT = TechniqueSlot.of("render.gi.lights", "lists", false,
        "how ray hits find their lights: lists per world cell, or reservoirs per cell",
        techniques -> techniques.register(WorldLights.SLOT, WorldLights.LISTS)
            .register(WorldLights.SLOT, WorldLights.RESERVOIRS));

    /**
     * The lists' tunables.
     *
     * @param cell the cells' size, in world units
     * @param cells cells along each axis
     * @param slots lights a cell lists
     */
    public record Lists(
        @Tunable(value = "the size of a world light cell, in metres", min = 0.25, max = 1024,
            formerly = "render.gi.lights.cell") float cell,
        @Tunable(value = "world light cells along each axis", min = 1, max = 256,
            formerly = "render.gi.lights.cells") int cells,
        @Tunable(value = "lights each world light cell lists", min = 1, max = 1024,
            formerly = "render.gi.lights.slots") int slots) implements TechniqueConfig {
    }

    /**
     * The reservoirs' tunables.
     *
     * @param cell the cells' size, in world units
     * @param cells cells along each axis
     * @param slots reservoirs a cell keeps
     * @param candidates lights drawn for each reservoir
     * @param keep frames a cell is still built after hits last used it. Cells are built only where hits were; a hit in
     *     a cell not built sums every light (exact, slower), so a cell kept a while spares hits that come and go.
     */
    public record Reservoirs(
        @Tunable(value = "the size of a world light cell, in metres", min = 0.25, max = 1024,
            formerly = "render.gi.lights.cell") float cell,
        @Tunable(value = "world light cells along each axis", min = 1, max = 256,
            formerly = "render.gi.lights.cells") int cells,
        @Tunable(value = "reservoirs each world light cell keeps", min = 1, max = 1024,
            formerly = "render.gi.lights.slots") int slots,
        @Tunable(value = "candidate lights drawn for each world light reservoir", min = 1, max = 1024,
            formerly = "render.gi.lights.candidates") int candidates,
        @Tunable(value = "frames a world light cell keeps its reservoirs built after hits last used it", min = 1,
            max = 1 << 20, formerly = "render.gi.lights.keep") int keep) implements TechniqueConfig {
    }

    /** Each cell's lights listed, all summed: exact. The default. */
    public static final TechniqueKind<Lists, Void, Mode> LISTS = TechniqueKind.of(SLOT, "lists", new Lists(4, 32, 32),
        "each cell's lights listed, all summed: exact", (context, config) -> Mode.LISTS);
    /** Reservoirs per cell resampled (ReGIR): unbiased, constant cost, noisy. */
    public static final TechniqueKind<Reservoirs, Void, Mode> RESERVOIRS = TechniqueKind.of(SLOT, "reservoirs",
        new Reservoirs(4, 32, 32, 16, 8), "reservoirs per cell resampled (ReGIR): unbiased, constant cost, noisy",
        (context, config) -> Mode.RESERVOIRS);

    /** Its settings: the choice and the kinds' tunables. */
    public static final List<Setting<?>> ALL = all();

    static final int HEADER_BYTES = 128;
    static final int LAYOUT_LISTS = 1;      // world.slang's WORLD_LISTS
    static final int LAYOUT_RESERVOIRS = 2; // and WORLD_RESERVOIRS

    public WorldLights {
        Objects.requireNonNull(lights, "lights");
        Objects.requireNonNull(anchor, "anchor");
    }

    private static List<Setting<?>> all() {
        List<Setting<?>> all = new java.util.ArrayList<>();
        all.add(SLOT.setting());
        all.addAll(LISTS.settings());
        all.addAll(RESERVOIRS.settings());
        return List.copyOf(all);
    }

    @Override
    @AllowAllocation("building the graph, when it changes")
    public Grid build(GraphBuilder g) {
        TechniqueKind<?, Void, Mode> kind = g.technique(SLOT);
        Mode mode = kind.create(null, null);
        float cell = g.setting(kind.<Float>setting("cell"));
        int cells = g.setting(kind.<Integer>setting("cells"));
        int slots = g.setting(kind.<Integer>setting("slots"));
        boolean lists = mode == Mode.LISTS; // lists draw no candidates and keep no cells
        int candidates = lists ? RESERVOIRS.defaults().candidates() : g.setting(kind.<Integer>setting("candidates"));
        int keep = lists ? RESERVOIRS.defaults().keep() : g.setting(kind.<Integer>setting("keep"));
        float cutoff = g.setting(ClusteredLights.CUTOFF);
        for (Key<?> key : List.copyOf(lights.writers())) {
            g.optional(key); // the lights the GPU writes: this frame's
        }
        Buffer written = lights.gpuLightsBuffer() == null ? null : g.importBuffer(lights.gpuLightsBuffer());
        int extra = lights.gpuCapacity();
        long count = (long) cells * cells * cells;
        long dataBytes = mode == Mode.LISTS ? 4L * count * (1 + slots) : 8L * count * slots;
        // Reservoirs: the cells built this frame (their count, the build's dispatch, the cells) after the lights
        long builtAt = HEADER_BYTES + dataBytes + (long) Lights.LIGHT_BYTES * extra;
        long builtBytes = mode == Mode.LISTS ? 0 : 16 + 4 * count;
        Buffer grid = g.createBuffer("world lights", builtAt + builtBytes);
        Buffer cellState = mode == Mode.LISTS ? null : g.persistentBuffer("world light cells", 8 * count);
        Pipeline used = g.computeProgram("shaders/lights/worldBuild.spv", "usedMain", WorldBuildPush.BYTES);
        Pipeline args = g.computeProgram("shaders/lights/worldBuild.spv", "argsMain", WorldBuildPush.BYTES);
        Pipeline copy = g.computeProgram("shaders/lights/worldBuild.spv", "copyMain", WorldBuildPush.BYTES);
        Pipeline build = g.computeProgram("shaders/lights/worldBuild.spv", "buildMain", WorldBuildPush.BYTES);
        WorldBuildPush push = new WorldBuildPush();
        long threads = mode == Mode.LISTS ? (long) lights.capacity() + extra : count * slots;
        GraphBuilder.PassBuilder pass = g.pass("world lights");
        if (written != null) {
            pass.read(written, BufferAccess.STORAGE_READ);
        }
        if (cellState != null) {
            pass.write(cellState, BufferAccess.STORAGE_WRITE).read(grid, BufferAccess.INDIRECT);
        }
        Pipeline[] reservoirs = {used, args};
        pass.write(grid, BufferAccess.TRANSFER_DST).write(grid, BufferAccess.STORAGE_WRITE).async()
            .record(ctx -> record(ctx, grid, cellState, written, extra, copy, build, reservoirs, push, mode, cell,
                cells, slots, candidates, keep, cutoff, dataBytes, builtAt, (int) threads));
        return new Grid(grid, cellState);
    }

    private void record(PassContext ctx, Buffer grid, Buffer cellState, Buffer written, int extra, Pipeline copy,
        Pipeline build, Pipeline[] reservoirs, WorldBuildPush push, Mode mode, float cell, int cells, int slots,
        int candidates, int keep, float cutoff, long dataBytes, long builtAt, int threads) {
        int frame = (int) ctx.frame() + 1; // from 1: 0 in a cell's state is never
        long header = ctx.address(grid);
        try (MemoryStack stack = stackPush()) {
            long data = stack.ncalloc(8, 1, HEADER_BYTES);
            memPutInt(data + WorldLightGridLayout.LAYOUT, mode == Mode.LISTS ? LAYOUT_LISTS : LAYOUT_RESERVOIRS);
            memPutInt(data + WorldLightGridLayout.CELLS, cells);
            memPutInt(data + WorldLightGridLayout.SLOTS, slots);
            memPutInt(data + WorldLightGridLayout.CANDIDATES, candidates);
            // The lowest corner: the anchor less half the grid, snapped to the cells, in two parts per axis
            for (int axis = 0; axis < 3; axis++) {
                double corner = Math.floor(anchor.position(axis) / cell - cells / 2.0) * cell;
                float high = (float) corner;
                memPutFloat(data + WorldLightGridLayout.ORIGIN + 4L * axis, high);
                memPutFloat(data + WorldLightGridLayout.ORIGIN_LOW + 4L * axis, (float) (corner - high));
            }
            memPutFloat(data + WorldLightGridLayout.CELL_SIZE, cell);
            memPutInt(data + WorldLightGridLayout.LIGHT_COUNT, lights.count());
            memPutLong(data + WorldLightGridLayout.LIGHTS, lights.lightsAddress());
            memPutLong(data + WorldLightGridLayout.EXTRA, header + HEADER_BYTES + dataBytes); // the GPU's, copied
            memPutLong(data + WorldLightGridLayout.DATA, header + HEADER_BYTES);
            memPutFloat(data + WorldLightGridLayout.CUTOFF, cutoff);
            memPutInt(data + WorldLightGridLayout.FRAME, frame);
            if (cellState != null) {
                memPutLong(data + WorldLightGridLayout.CELL_STATE, ctx.address(cellState));
                memPutLong(data + WorldLightGridLayout.BUILT, header + builtAt);
            }
            Commands.updateBuffer(ctx.unsafe().commands(), ctx.unsafe().buffer(grid), 0, HEADER_BYTES, data);
        }
        if (cellState != null) {
            ctx.fill(grid, builtAt, 16, 0); // no cells listed yet
        }
        Barriers.memory(ctx.unsafe().commands(), VK_PIPELINE_STAGE_2_TRANSFER_BIT, VK_ACCESS_2_TRANSFER_WRITE_BIT,
            VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
            VK_ACCESS_2_SHADER_STORAGE_READ_BIT | VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT);
        // Without lights the GPU writes, a count of 0 all the same (a word of the header's), never a null pointer
        push.grid(header).written(written == null ? header + WorldLightGridLayout.ZERO : ctx.address(written))
            .writtenCapacity(extra).frame(frame).keep(keep);
        // The copy (and the lists' counts cleared, its threads striding over the cells)
        int copyGroups = Math.max((extra + 63) / 64, mode == Mode.LISTS ? Math.min(1024, (cells * cells * cells + 63)
            / 64) : 1);
        ctx.dispatch(copy, push.threads(64 * copyGroups), copyGroups);
        Barriers.memory(ctx.unsafe().commands(), VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
            VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT, VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
            VK_ACCESS_2_SHADER_STORAGE_READ_BIT | VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT);
        if (mode == Mode.LISTS) {
            push.threads(threads); // a thread per light
            int groups = (threads + 63) / 64;
            ctx.dispatch(build, push, Math.min(groups, 1024), (groups + 1023) / 1024, 1); // rows of 1024 groups
            return;
        }
        // Reservoirs only of the cells hits used the frame before: listed, the build's dispatch, the build
        int cellGroups = (cells * cells * cells + 63) / 64;
        ctx.dispatch(reservoirs[0], push, Math.min(cellGroups, 1024), (cellGroups + 1023) / 1024, 1);
        Barriers.memory(ctx.unsafe().commands(), VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
            VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT, VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
            VK_ACCESS_2_SHADER_STORAGE_READ_BIT | VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT);
        ctx.dispatch(reservoirs[1], push, 1);
        Barriers.memory(ctx.unsafe().commands(), VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
            VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT, VK_PIPELINE_STAGE_2_DRAW_INDIRECT_BIT
                | VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
            VK_ACCESS_2_INDIRECT_COMMAND_READ_BIT | VK_ACCESS_2_SHADER_STORAGE_READ_BIT);
        ctx.dispatchIndirect(build, push.threads(Integer.MAX_VALUE), grid, builtAt + 4); // bounded by the list
    }
}
