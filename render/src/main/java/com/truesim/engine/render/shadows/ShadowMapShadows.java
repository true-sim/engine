package com.truesim.engine.render.shadows;

import static org.lwjgl.vulkan.VK10.VK_FORMAT_D32_SFLOAT;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Image;
import com.truesim.engine.graph.Key;
import com.truesim.engine.graph.Technique;
import com.truesim.engine.render.Camera;
import com.truesim.engine.render.Keys;
import com.truesim.engine.render.Lights;
import com.truesim.engine.render.RenderView;
import com.truesim.engine.render.SceneDraw;
import com.truesim.engine.render.SceneGeometry;
import com.truesim.engine.render.ShadowInputs;
import com.truesim.engine.render.SurfaceOutput;
import java.util.List;
import java.util.Objects;

/**
 * A light's shadows the classic way, by one shadow map: the scene's depth drawn from {@code light}'s camera (an
 * orthographic one along a directional light, a perspective one for a spot light, each at its own world position: the
 * map does not change as other cameras move, and serves every view that shades the light) as {@code view}, culled
 * with the other views in one pass, then filtered in shading (3 x 3 PCF) through the light's shadow
 * table entry {@code entry}. Cheap, with the classic trade-offs: the map's resolution over its area, a bias between
 * acne and shadows that come loose from their casters, and nothing outside the map.
 *
 * <p>Like every shadow technique it is per light, and modifies {@link Keys#LIGHT_LIST}: lights of one frame may each
 * use another technique.
 */
public final class ShadowMapShadows implements Technique {

    private final SceneGeometry geometry;
    private final Lights lights;
    private final int light;
    private final int entry;
    private final Camera camera;
    private final int size;
    private final RenderView view;
    private final float bias;

    /**
     * Shadows of light {@code light} of {@code lights}: a {@code size} x {@code size} map of {@code geometry}
     * drawn from {@code camera} (read when recording) as {@code view} (one of {@code geometry}'s; it draws the casters
     * only, see {@link com.truesim.engine.render.ViewOptions#shadow}), in shadow table entry {@code entry}, compared
     * with {@code bias} (the map's depth units: a texel's worth of depth slope is a good start).
     */
    public ShadowMapShadows(SceneGeometry geometry, Lights lights, int light, int entry, Camera camera, int size,
        RenderView view, float bias) {
        this.geometry = Objects.requireNonNull(geometry, "geometry");
        this.lights = Objects.requireNonNull(lights, "lights");
        this.light = light;
        this.entry = entry;
        this.camera = Objects.requireNonNull(camera, "camera");
        this.size = size;
        this.view = Objects.requireNonNull(view, "view");
        this.bias = bias;
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
        view.filter(-1, true); // the map holds the casters only
        Image map = g.request(new SceneDraw(geometry, view, camera, SurfaceOutput.depth(), VK_FORMAT_D32_SFLOAT,
            size, size, false)).depth();
        g.request(new ShadowInputs(g.view())).sample(map).setup(ctx -> {
            lights.shadowMap(entry, camera, ctx.sampled(map), size, bias, Float.MAX_VALUE);
            lights.shadows(light, entry, 1);
        });
    }
}
