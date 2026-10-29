package com.truesim.engine.render;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.graph.Access;
import com.truesim.engine.graph.Buffer;
import com.truesim.engine.graph.BufferAccess;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Image;
import com.truesim.engine.graph.Key;
import com.truesim.engine.graph.PassContext;
import com.truesim.engine.graph.Technique;
import com.truesim.engine.vulkan.Pipeline;
import com.truesim.engine.vulkan.Push;
import com.truesim.engine.vulkan.ShaderModule;
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
 * <p>Shading is by {@link MaterialType}: a pass sorts the screen's 8x8 tiles into a list per type they show, and each
 * type of the scene's {@link Materials} (as they are when the graph builds) shades its tiles' pixels with its own
 * shader, by an indirect dispatch.
 *
 * <p>The color format must allow storage images (not sRGB). Needs
 * {@link com.truesim.engine.vulkan.VulkanDevice#primitiveIds()}, and one provider whose triangles the shading pass can
 * rebuild: {@link MeshletGeometry} or {@link ClassicLodGeometry}.
 */
public final class VisibilityBufferShading implements Technique {

    private static final int PUSH_BYTES = 128;
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
    private final Push push = new Push();

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
    public List<Key> provides() {
        return List.of(Keys.SCENE_COLOR, Keys.SCENE_DEPTH);
    }

    @Override
    @AllowAllocation("building the graph, when it changes")
    public void build(GraphBuilder g) {
        SceneDraw.Targets targets = g.request(new SceneDraw(geometry, view, null, SurfaceOutput.ids(), depthFormat,
            width, height, occlusion && view.options().occlusion()));
        Buffer lights = g.optionalBuffer(Keys.LIGHT_LIST);
        ShadowInputs.Set shadows = lights == null ? null : g.request(new ShadowInputs());
        // With inline ray shadows, the variant tracing them, where the device can.
        boolean traced = shadows != null && shadows.traced() && g.device().rayTracing();
        List<MaterialType> types = renderer.scene().materials().types();
        Pipeline[] shade = new Pipeline[types.size()];
        for (int t = 0; t < shade.length; t++) {
            MaterialType type = types.get(t);
            boolean rays = traced && type.tracedShader() != null;
            shade[t] = compute(g, rays ? type.tracedShader() : type.shader(), rays ? type.tracedEntry() : type.entry());
        }
        Pipeline classify = compute(g, "shaders/visibility/classify.spv", "classifyMain");
        Image ids = targets.color();
        Image color = g.create("scene color", width, height, colorFormat);
        int tilesX = (width + TILE - 1) / TILE;
        int tilesY = (height + TILE - 1) / TILE;
        // Per type: its indirect dispatch (x, y, z), its tile count, then its tiles.
        long listBytes = 4L * (4 + (long) tilesX * tilesY);
        Buffer tiles = g.createBuffer("material tiles", listBytes * types.size());
        g.pass("material tiles reset").write(tiles, BufferAccess.TRANSFER_DST).record(ctx -> ctx.fill(tiles, 0));
        g.pass("material classification")
            .read(ids, Access.STORAGE_READ)
            .write(tiles, BufferAccess.STORAGE_WRITE)
            .record(ctx -> ctx.dispatch(classify, push(ctx, ids, null, lights, types.size(), 0, ctx.address(tiles)),
                tilesX, tilesY, 1));
        GraphBuilder.PassBuilder pass = g.pass("visibility shading")
            .read(ids, Access.STORAGE_READ)
            .read(tiles, BufferAccess.INDIRECT)
            .read(tiles, BufferAccess.STORAGE_READ)
            .write(color, Access.STORAGE_WRITE);
        if (lights != null) {
            pass.read(lights, BufferAccess.STORAGE_READ);
        }
        if (shadows != null) {
            shadows.readBy(pass, Access.SAMPLED_COMPUTE, BufferAccess.STORAGE_READ);
        }
        pass.record(ctx -> {
            if (shadows != null) {
                shadows.prepare(ctx);
            }
            for (int t = 0; t < shade.length; t++) {
                long list = listBytes * t;
                ctx.dispatchIndirect(shade[t], push(ctx, ids, color, lights, shade.length, t, ctx.address(tiles) + list),
                    tiles, list);
            }
        });
        g.provide(Keys.SCENE_COLOR, color);
        g.provide(Keys.SCENE_DEPTH, targets.depth());
    }

    @AllowAllocation("building the graph, when it changes")
    private static Pipeline compute(GraphBuilder g, String shader, String entry) {
        return g.cached("visibility shading: " + shader + " " + entry, device -> {
            try (ShaderModule module = ShaderModule.load(device, shader)) {
                return Pipeline.compute(device, module, entry, PUSH_BYTES);
            }
        });
    }

    /** resolve.slang's ShadePush: for {@code types} types, shading type {@code type}'s tiles at {@code tiles}. */
    private Push push(PassContext ctx, Image ids, Image color, Buffer lights, int types, int type, long tiles) {
        GeometryPool pool = renderer.pool();
        return push.clear()
            .address(view.address())
            .address(pool.meshTable().deviceAddress())
            .address(renderer.scene().instanceAddress())
            .address(pool.positions().deviceAddress())
            .address(pool.attributes().deviceAddress())
            .address(pool.meshlets().deviceAddress())
            .address(pool.meshletVertices().deviceAddress())
            .address(pool.meshletTriangles().deviceAddress())
            .address(renderer.scene().materialsAddress())
            .address(lights == null ? 0 : ctx.address(lights))
            .u32(ctx.storage(ids))
            .u32(color == null ? 0 : ctx.storage(color))
            .u32(width | height << 16)
            .u32(types)
            .vec3(clear[0], clear[1], clear[2])
            .u32(type)
            .address(classic == null ? 0 : classic.indicesAddress())
            .address(tiles);
    }
}
