package com.truesim.engine.render;

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
import com.truesim.engine.vulkan.Format;
import com.truesim.engine.vulkan.Pipeline;
import java.util.List;
import java.util.Objects;

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
    private final int width;
    private final int height;
    private final int colorFormat;
    private final int depthFormat;
    private final float[] clear = new float[3];
    private final RenderView view;
    private boolean occlusion = true;
    // One per pass: a frame graph may record the classification and the shading on two threads at once.
    private final ShadePush classifyPush = new ShadePush(); // one per pass: passes may record at once
    private final ShadePush shadePush = new ShadePush();

    /** {@code geometry} shaded as {@code view} sees it. */
    public VisibilityBufferShading(SceneGeometry geometry, RenderView view, int width, int height, int colorFormat,
        int depthFormat) {
        this.geometry = Objects.requireNonNull(geometry, "geometry");
        this.view = Objects.requireNonNull(view, "view");
        MeshletGeometry meshlets = geometry.provider(MeshletGeometry.class);
        this.classic = geometry.provider(ClassicLodGeometry.class);
        if (geometry.providers().size() != 1 || meshlets == null && classic == null) {
            throw new IllegalArgumentException(
                "The visibility buffer shades one provider's geometry, meshlets or classic LOD, for now");
        }
        this.renderer = meshlets != null ? meshlets.renderer() : classic.renderer();
        if (width < 1 || height < 1 || width > 0xffff || height > 0xffff) {
            throw new IllegalArgumentException("A size of 1 to 65,535 per side: " + width + " x " + height);
        }
        this.width = width;
        this.height = height;
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
     * Two-phase occlusion culling, on by default where the view was made with it; the graph rebuilds when it changes.
     */
    public VisibilityBufferShading occlusion(boolean on) {
        this.occlusion = on;
        return this;
    }

    @Override
    public List<Key<?>> provides() {
        return List.of(Keys.SCENE_COLOR, Keys.SCENE_DEPTH);
    }

    @Override
    @AllowAllocation("building the graph, when it changes")
    public void build(GraphBuilder g) {
        SceneDraw.Targets targets = g.request(new SceneDraw(geometry, view, null, SurfaceOutput.ids(), depthFormat,
            width, height, occlusion && view.options().occlusion()));
        Buffer lights = g.optional(Keys.LIGHT_LIST);
        ShadowInputs.Set shadows = lights == null ? null : g.request(new ShadowInputs());
        // With inline ray shadows, the variant tracing them, where the device can.
        boolean traced = shadows != null && shadows.traced() && g.capabilities().rayTracing();
        List<MaterialType> types = renderer.scene().materials().types();
        int variants = 1 << Material.FEATURE_BITS;
        Pipeline[] shade = new Pipeline[types.size() * variants];
        for (int bin = 0; bin < shade.length; bin++) {
            MaterialType type = types.get(bin / variants);
            int features = bin % variants;
            if ((features & ~type.allowedFeatures()) != 0) {
                continue; // no material of the type shows them: no variant, and no pixels of the bin
            }
            boolean rays = traced && type.tracedShader() != null;
            shade[bin] = compute(g, rays ? type.tracedShader() : type.shader(), rays ? type.tracedEntry() : type.entry(),
                features);
        }
        Pipeline classify = compute(g, "shaders/visibility/classify.spv", "classifyMain", 0);
        Image ids = targets.color();
        Image color = g.create("scene color", ImageDesc.of(Format.of(colorFormat), width, height));
        int tilesX = (width + TILE - 1) / TILE;
        int tilesY = (height + TILE - 1) / TILE;
        // Per bin: its indirect dispatch (x, y, z), its tile count, then its tiles.
        long listBytes = 4L * (4 + (long) tilesX * tilesY);
        Buffer tiles = g.createBuffer("material tiles", listBytes * shade.length);
        SceneData.Buffers scene = g.request(new SceneData(renderer));
        g.pass("material tiles reset").write(tiles, BufferAccess.TRANSFER_DST).record(ctx -> ctx.fill(tiles, 0));
        scene.read(g.pass("material classification"), BufferAccess.STORAGE_READ)
            .read(ids, Access.STORAGE_READ)
            .write(tiles, BufferAccess.STORAGE_WRITE)
            .record(ctx -> ctx.dispatch(classify, push(classifyPush, ctx, ids, null, null, types.size(), 0,
                ctx.address(tiles)), tilesX, tilesY, 1));
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
        pass.record(ctx -> {
            if (shadows != null) {
                shadows.prepare(ctx);
            }
            for (int bin = 0; bin < shade.length; bin++) {
                if (shade[bin] == null) {
                    continue;
                }
                long list = listBytes * bin;
                ctx.dispatchIndirect(shade[bin], push(shadePush, ctx, ids, color, lights, types.size(), bin,
                    ctx.address(tiles) + list), tiles, list);
            }
        });
        g.provide(Keys.SCENE_COLOR, color);
        g.provide(Keys.SCENE_DEPTH, targets.depth());
    }

    @AllowAllocation("building the graph, when it changes")
    /** {@code entry} of {@code shader}, with resolve.slang's FEATURES {@code features}. */
    private static Pipeline compute(GraphBuilder g, String shader, String entry, int features) {
        return g.computeProgram(shader, entry, PUSH_BYTES, features);
    }

    /**
     * resolve.slang's ShadePush, into {@code push}: for {@code types} types, shading bin {@code bin}'s tiles at
     * {@code tiles}.
     */
    private ShadePush push(ShadePush push, PassContext ctx, Image ids, Image color, Buffer lights, int types, int bin,
        long tiles) {
        GeometryPool pool = renderer.pool();
        return push.view(view.address())
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
            .size(width | height << 16)
            .types(types)
            .clear(clear[0], clear[1], clear[2])
            .bin(bin)
            .classicIndices(classic == null ? 0 : classic.indicesAddress())
            .tiles(tiles);
    }
}
