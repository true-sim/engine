package com.truesim.engine.render;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.memPutInt;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.graph.Access;
import com.truesim.engine.graph.Buffer;
import com.truesim.engine.graph.BufferAccess;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Image;
import com.truesim.engine.graph.ImageDesc;
import com.truesim.engine.graph.Key;
import com.truesim.engine.graph.PassContext;
import com.truesim.engine.graph.Technique;
import com.truesim.engine.render.layout.ShadePush;
import com.truesim.engine.vulkan.Commands;
import com.truesim.engine.vulkan.Format;
import com.truesim.engine.vulkan.Pipeline;
import java.util.List;
import java.util.Objects;
import org.lwjgl.system.MemoryStack;

/**
 * The scene shaded through a visibility buffer: provides {@link Keys#SCENE_COLOR} and {@link Keys#SCENE_DEPTH} from a
 * {@link SceneDraw} of IDs (instance, meshlet, triangle), then shades every pixel once in a compute pass that rebuilds
 * its triangle through the same vertex stage, intersects the pixel's ray with it, and lights it as
 * {@link ClusteredForwardShading} does, by the frame's {@link Keys#LIGHT_LIST} (the sun without one). Shading no
 * longer grows with overdraw; triangles are read twice.
 * {@code graph.replace} swaps the two.
 *
 * <p>Shading is by {@link MaterialType} and features: a pass sorts the screen's 8x8 tiles into a list per bin (a type
 * and a combination of {@link Material#features features}) they show, and each bin of the scene's {@link Materials}'
 * types (as they are when the graph builds) shades its tiles' pixels with its type's shader, by an indirect dispatch:
 * a variant compiled with only its features' code, for each combination of the features the type allows.
 *
 * <p>The color format must allow storage images (not sRGB). Needs
 * {@link com.truesim.engine.vulkan.VulkanDevice#primitiveIds()}, and one provider whose triangles the shading pass can
 * rebuild: {@link MeshletGeometry} or {@link ClassicLodGeometry}.
 */
public final class VisibilityBufferShading implements Technique {

    private static final int PUSH_BYTES = ShadePush.BYTES;
    private static final int TILE = 8;

    private final SceneGeometry geometry;
    private final MeshletRenderer renderer;
    private final ClassicLodGeometry classic; // null: meshlets
    private final int colorFormat;
    private final int depthFormat;
    private final float[] clear = new float[3];
    private final SceneView view;
    private Boolean occlusion; // or null: as the graph's settings say
    // One per pass: a frame graph may record the classification and the shading on two threads at once.
    private final ShadePush classifyPush = new ShadePush(); // one per pass: passes may record at once
    private final ShadePush shadePush = new ShadePush();

    /** {@code geometry} shaded as {@code view} sees it, at its port's size; added to {@code view}'s port. */
    public VisibilityBufferShading(SceneGeometry geometry, SceneView view, int colorFormat, int depthFormat) {
        this.geometry = Objects.requireNonNull(geometry, "geometry");
        this.view = Objects.requireNonNull(view, "view");
        MeshletGeometry meshlets = geometry.provider(MeshletGeometry.class);
        this.classic = geometry.provider(ClassicLodGeometry.class);
        if (geometry.providers().size() != 1 || meshlets == null && classic == null) {
            throw new IllegalArgumentException(
                "The visibility buffer shades one provider's geometry, meshlets or classic LOD, for now");
        }
        this.renderer = meshlets != null ? meshlets.renderer() : classic.renderer();
        this.colorFormat = colorFormat;
        this.depthFormat = depthFormat;
    }

    /** The color behind everything; read when recording. */
    public VisibilityBufferShading clearColor(float r, float g, float b) {
        clear[0] = r;
        clear[1] = g;
        clear[2] = b;
        return this;
    }

    /**
     * Two-phase occlusion culling where the view was made with it, overriding the graph's settings
     * ({@link RenderSettings#OCCLUSION}); the graph rebuilds when it changes.
     */
    @AllowAllocation("set up rarely")
    public VisibilityBufferShading occlusion(boolean on) {
        this.occlusion = on;
        return this;
    }

    @Override
    public List<Key<?>> provides() {
        return List.of(Keys.SCENE_COLOR, Keys.SCENE_DEPTH);
    }

    /**
     * The scene's material table, its count of shading bins and the graph buffers its materials name: a shading pass
     * per bin (a type and features), so a combination first shown after the graph built (or another table) rebuilds it
     * before its materials draw, instead of shading as metallic-roughness; and a buffer named anew is imported, ordered
     * after its writers.
     */
    @Override
    public long buildVersion() {
        Materials materials = renderer.scene().materials();
        return ((long) System.identityHashCode(materials) << 32 | materials.binCount())
            + 31 * materials.graphBuffersVersion();
    }

    @Override
    @AllowAllocation("building the graph, when it changes")
    public void build(GraphBuilder g) {
        view.checkBuilding(g, this);
        int width = view.width();
        int height = view.height();
        if (width > 0xffff || height > 0xffff) {
            throw new IllegalStateException("The visibility buffer shades up to 65,535 pixels per side: " + width
                + " x " + height);
        }
        SceneDraw draw = new SceneDraw(geometry, view.view(), null, SurfaceOutput.ids(), depthFormat, width, height,
            (occlusion != null ? occlusion : g.setting(RenderSettings.OCCLUSION)) && view.view().options().occlusion());
        SceneDraw.Targets targets = g.request(draw);
        Buffer lights = g.optional(Keys.LIGHT_LIST);
        ShadowInputs.Set shadows = lights == null ? null : g.request(new ShadowInputs(g.view()));
        IndirectInputs.Set indirect = IndirectInputs.of(g);
        // With inline ray shadows, the variant tracing them, where the device can.
        boolean traced = shadows != null && shadows.traced() && g.capabilities().rayTracing();
        // A variant per bin: a type, and the features its materials show; made for the combinations in use only.
        Materials materials = renderer.scene().materials();
        Pipeline[] shade = new Pipeline[materials.binCount()];
        for (int bin = 0; bin < shade.length; bin++) {
            MaterialType type = materials.binType(bin);
            int features = materials.binFeatures(bin);
            boolean rays = traced && type.tracedShader() != null;
            shade[bin] = compute(g, rays ? type.tracedShader() : type.shader(), rays ? type.tracedEntry() : type.entry(),
                features);
        }
        Pipeline classify = compute(g, "shaders/visibility/classify.spv", "classifyMain", 0);
        Image ids = targets.color();
        Image color = g.create("scene color", ImageDesc.of(Format.of(colorFormat), width, height));
        int tilesX = (width + TILE - 1) / TILE;
        int tilesY = (height + TILE - 1) / TILE;
        // Per bin: its indirect dispatch (x, y, z), its tile count, then its tiles; room for all of the images, and
        // each frame, the tiles of the render region (dynamic resolution).
        long listBytes = 4L * (4 + (long) tilesX * tilesY);
        // Lists only for bins with a variant (others get no pixels), after a table of each bin's list (resolve.slang's
        // tileList); only the lists' headers cleared each frame.
        int[] listOf = new int[shade.length];
        int lists = 0;
        for (int bin = 0; bin < shade.length; bin++) {
            listOf[bin] = shade[bin] == null ? 0 : lists++;
        }
        int listCount = lists;
        long tableBytes = 16L * ((shade.length + 3) / 4);
        Buffer tiles = g.createBuffer("material tiles", tableBytes + listBytes * Math.max(1, listCount));
        SceneData.Buffers scene = g.request(new SceneData(renderer));
        g.pass("material tiles reset").write(tiles, BufferAccess.TRANSFER_DST).record(ctx -> {
            try (MemoryStack stack = stackPush()) {
                long table = stack.ncalloc(4, (int) (tableBytes / 4), 4);
                for (int bin = 0; bin < listOf.length; bin++) {
                    memPutInt(table + 4L * bin, listOf[bin]);
                }
                Commands.updateBuffer(ctx.unsafe().commands(), ctx.unsafe().buffer(tiles), 0, tableBytes, table);
            }
            for (int list = 0; list < listCount; list++) {
                ctx.fill(tiles, tableBytes + listBytes * list, 16, 0); // its dispatch and count
            }
        });
        scene.read(g.pass("material classification"), BufferAccess.STORAGE_READ)
            .read(ids, Access.STORAGE_READ)
            .write(tiles, BufferAccess.STORAGE_WRITE)
            // The stride between bins' lists as the bin: the whole image's, as the shading passes below space them.
            .record(ctx -> ctx.dispatch(classify, push(classifyPush, ctx, ids, null, null, size(ctx, ids), shade.length,
                (int) (listBytes / 4), ctx.address(tiles)), (ctx.regionWidth(ids) + TILE - 1) / TILE,
                (ctx.regionHeight(ids) + TILE - 1) / TILE, 1));
        GraphBuilder.PassBuilder pass = scene.read(g.pass("visibility shading"), BufferAccess.STORAGE_READ)
            .read(ids, Access.STORAGE_READ)
            .read(tiles, BufferAccess.INDIRECT)
            .read(tiles, BufferAccess.STORAGE_READ)
            .write(color, Access.STORAGE_WRITE);
        if (lights != null) {
            pass.read(lights, BufferAccess.STORAGE_READ);
        }
        if (shadows != null) {
            shadows.readBy(pass, Access.SAMPLED_COMPUTE, BufferAccess.STORAGE_READ, traced);
        }
        if (indirect != null) {
            indirect.readBy(pass, Access.SAMPLED_COMPUTE, BufferAccess.STORAGE_READ);
        }
        pass.record(ctx -> {
            if (shadows != null) {
                shadows.prepare(ctx);
            }
            for (int bin = 0; bin < shade.length; bin++) {
                if (shade[bin] == null) {
                    continue;
                }
                long list = tableBytes + listBytes * listOf[bin];
                ctx.dispatchIndirect(shade[bin], push(shadePush, ctx, ids, color, lights, size(ctx, ids), shade.length,
                    bin, ctx.address(tiles) + list).indirect(indirect == null ? 0 : ctx.address(indirect.table())),
                    tiles, list);
            }
        });
        g.provide(Keys.SCENE_COLOR, color);
        g.provide(Keys.SCENE_DEPTH, targets.depth());
    }

    /**
     * A color as RGB9E5, three 9-bit mantissas and a shared 5-bit exponent (EXT_texture_shared_exponent), as resolve.slang
     * decodes the clear color: a push word for three floats. Allocates nothing.
     */
    static int rgb9e5(float r, float g, float b) {
        float max = 65408;
        float rc = Math.max(0, Math.min(r, max));
        float gc = Math.max(0, Math.min(g, max));
        float bc = Math.max(0, Math.min(b, max));
        float maxc = Math.max(rc, Math.max(gc, bc));
        int exponent = Math.max(-16, maxc > 0 ? Math.getExponent(maxc) : -16) + 1 + 15;
        double scale = Math.scalb(1.0, exponent - 15 - 9);
        if (Math.floor(maxc / scale + 0.5) == 512) {
            exponent++;
            scale *= 2;
        }
        int rs = (int) Math.floor(rc / scale + 0.5);
        int gs = (int) Math.floor(gc / scale + 0.5);
        int bs = (int) Math.floor(bc / scale + 0.5);
        return rs | gs << 9 | bs << 18 | exponent << 27;
    }

    /** The render region in {@code ids} this frame: width, and height in the high 16 bits (resolve.slang's size). */
    private static int size(PassContext ctx, Image ids) {
        return ctx.regionWidth(ids) | ctx.regionHeight(ids) << 16;
    }

    @AllowAllocation("building the graph, when it changes")
    /** {@code entry} of {@code shader}, with resolve.slang's FEATURES {@code features}. */
    private static Pipeline compute(GraphBuilder g, String shader, String entry, int features) {
        return g.computeProgram(shader, entry, PUSH_BYTES, features);
    }

    /**
     * resolve.slang's ShadePush, into {@code push}: for images of {@code size} (width, height in the high 16 bits),
     * {@code bins} bins, shading bin {@code bin}'s tiles at {@code tiles}.
     */
    private ShadePush push(ShadePush push, PassContext ctx, Image ids, Image color, Buffer lights, int size, int bins,
        int bin, long tiles) {
        GeometryPool pool = renderer.pool();
        return push.view(view.view().address())
            .meshes(pool.meshTable().deviceAddress())
            .instances(renderer.scene().instanceAddress())
            .positions(pool.positions().deviceAddress())
            .attributes(pool.attributes().deviceAddress())
            .meshlets(pool.meshlets().deviceAddress())
            .meshletVertices(pool.meshletVertices().deviceAddress())
            .meshletTriangles(pool.meshletTriangles().deviceAddress())
            .materials(renderer.scene().materialsAddress())
            .lights(lights == null ? 0 : ctx.address(lights))
            .ids(ctx.storage(ids))
            .color(color == null ? 0 : ctx.storage(color))
            .size(size)
            .bins(bins)
            .clear(rgb9e5(clear[0], clear[1], clear[2]))
            .bin(bin)
            .classicIndices(classic == null ? 0 : classic.indicesAddress())
            .tiles(tiles);
    }
}
