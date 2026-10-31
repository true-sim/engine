package com.truesim.engine.render.shadows;


import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.graph.Access;
import com.truesim.engine.graph.Buffer;
import com.truesim.engine.graph.BufferAccess;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Image;
import com.truesim.engine.graph.ImageDesc;
import com.truesim.engine.graph.Ratio;
import com.truesim.engine.graph.Key;
import com.truesim.engine.graph.PassContext;
import com.truesim.engine.graph.Technique;
import com.truesim.engine.render.Camera;
import com.truesim.engine.render.Keys;
import com.truesim.engine.render.RenderView;
import com.truesim.engine.render.Lights;
import com.truesim.engine.render.RayTracing;
import com.truesim.engine.render.SceneDraw;
import com.truesim.engine.render.ShadowInputs;
import com.truesim.engine.render.ShadowTable;
import com.truesim.engine.settings.EngineSettings;
import com.truesim.engine.render.layout.TracedPush;
import com.truesim.engine.vulkan.Format;
import com.truesim.engine.vulkan.Pipeline;
import java.util.List;
import java.util.Objects;

/**
 * A light's shadows by hardware ray tracing, as a screen-space mask: every pixel of {@code depthSource}'s depth traces
 * one ray query towards the light (on the async compute queue), and shading reads the mask through shadow table entry
 * {@code entry}. Exact, with no resolution limit and no map to fit; works with any shading, forward included (which
 * cannot trace inline: {@link InlineRayShadows}). The depth comes from a {@link SceneDraw} of the camera drawn before
 * shading: the visibility buffer's own (shared), or a depth-only one for forward shading.
 */
public final class RayTracedShadows implements Technique {


    private final RayTracing rayTracing;
    private final Lights lights;
    private final int light;
    private final int entry;
    private final SceneDraw depthSource;
    private final Camera camera;
    private Float offset; // or null: as the graph's settings say

    /**
     * Shadows of light {@code light} of {@code lights}, traced into a top level of the depth source's view's scene, for
     * the pixels of {@code depthSource} (drawn from {@code camera}), in shadow table entry {@code entry}.
     */
    public RayTracedShadows(RayTracing rayTracing, Lights lights, int light, int entry, SceneDraw depthSource,
        Camera camera) {
        this.rayTracing = Objects.requireNonNull(rayTracing, "rayTracing");
        this.lights = Objects.requireNonNull(lights, "lights");
        this.light = light;
        this.entry = entry;
        this.depthSource = Objects.requireNonNull(depthSource, "depthSource");
        this.camera = Objects.requireNonNull(camera, "camera");
    }

    /**
     * How far rays start off the surface towards the light, per unit of distance from the camera, overriding {@link ShadowSettings#RAY_OFFSET}: the
     * drawn surface is a detail level within a pixel of the traced one, so a ray from it must clear that difference.
     */
    @AllowAllocation("set up rarely")
    public RayTracedShadows offset(float perUnit) {
        this.offset = perUnit;
        return this;
    }

    @Override
    public List<Key<?>> provides() {
        return List.of();
    }

    @Override
    public List<Key<?>> modifies() {
        return List.of(Keys.LIGHT_LIST);
    }

    @Override
    @AllowAllocation("building the graph, when it changes")
    public void build(GraphBuilder g) {
        g.modify(Keys.LIGHT_LIST);
        Pipeline pipeline = g.computeProgram("shaders/shadows/traced.spv", "tracedMain", TracedPush.BYTES);
        Image depth = g.request(depthSource).depth();
        Buffer topLevel = g.request(new TopLevel(rayTracing, depthSource.view().renderer()));
        Image mask = g.create("shadow mask of light " + light, ImageDesc.like(depth, Ratio.ONE).format(Format.R8_UNORM));
        TracedPush push = new TracedPush().light(light);
        EngineSettings settings = g.settings();
        g.pass("ray-traced shadows of light " + light)
            .read(depth, Access.SAMPLED_COMPUTE)
            .read(topLevel, BufferAccess.ACCELERATION_STRUCTURE_READ)
            .write(mask, Access.STORAGE_WRITE)
            .async()
            .record(ctx -> trace(ctx, pipeline, depth, mask,
                push.offset(offset != null ? offset : settings.get(ShadowSettings.RAY_OFFSET))));
        ShadowTable table = lights.shadowTable(g.view());
        g.request(new ShadowInputs(g.view())).sample(mask).setup(ctx -> {
            table.shadowMask(entry, ctx.sampled(mask), 0); // its view's: the mask is of its pixels
            table.light(light, entry, 1);
        });
    }

    private void trace(PassContext ctx, Pipeline pipeline, Image depth, Image mask, TracedPush push) {
        push.inverseViewProjection(camera.inverseViewProjection()).depth(ctx.sampled(depth)).mask(ctx.storage(mask))
            .scene(rayTracing.slot()).lights(lights.lightsAddress());
        // The way from the camera's positions into the top level's (built relative to the origin), and the light
        // relative to the camera: differences of world positions, small enough for floats.
        double x = camera.position(0);
        double y = camera.position(1);
        double z = camera.position(2);
        push.toTopLevelX((float) (x - rayTracing.origin(0))).toTopLevelY((float) (y - rayTracing.origin(1)))
            .toTopLevelZ((float) (z - rayTracing.origin(2)));
        push.lightX((float) (lights.position(light, 0) - x)).lightY((float) (lights.position(light, 1) - y))
            .lightZ((float) (lights.position(light, 2) - z));
        int width = ctx.regionWidth(mask); // the pixels drawn this frame (dynamic resolution)
        int height = ctx.regionHeight(mask);
        ctx.dispatch(pipeline, push.width(width).height(height), (width + 7) / 8, (height + 7) / 8, 1);
    }
}
