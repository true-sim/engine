package com.truesim.engine.render;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Key;
import com.truesim.engine.graph.Technique;
import java.util.List;
import java.util.Objects;

/**
 * The scene shaded as it is drawn: provides {@link Keys#SCENE_COLOR} and {@link Keys#SCENE_DEPTH} from one
 * {@link SceneDraw} of the camera's view with {@link SurfaceOutput#shaded shaded} output, each fragment lit by the
 * lights the frame's {@link Keys#LIGHT_LIST} gives it (the sun without one). Simple, and each draw shades its own fragments, covered later or not. {@code graph.replace} swaps
 * it for another shading technique ({@link VisibilityBufferShading}).
 *
 * <pre>{@code
 * graph.add(new ClusteredForwardShading(geometry, 1920, 1080, VK_FORMAT_R16G16B16A16_SFLOAT, VK_FORMAT_D32_SFLOAT))
 *     .add(new ClusteredLights(lights, camera, 1920, 1080));
 * }</pre>
 */
public final class ClusteredForwardShading implements Technique {

    private final SceneGeometry geometry;
    private final int width;
    private final int height;
    private final int colorFormat;
    private final int depthFormat;
    private final float[] clear = new float[3];
    private final RenderView view;
    private boolean occlusion = true;

    /**
     * {@code geometry} shaded as {@code view} sees it. The providers' pipelines must have been made for
     * {@code colorFormat} and {@code depthFormat}.
     */
    public ClusteredForwardShading(SceneGeometry geometry, RenderView view, int width, int height, int colorFormat,
        int depthFormat) {
        this.geometry = Objects.requireNonNull(geometry, "geometry");
        this.view = Objects.requireNonNull(view, "view");
        this.width = width;
        this.height = height;
        this.colorFormat = colorFormat;
        this.depthFormat = depthFormat;
    }

    /** The color behind everything; read when recording, so it may change every frame. */
    public ClusteredForwardShading clearColor(float r, float g, float b) {
        clear[0] = r;
        clear[1] = g;
        clear[2] = b;
        return this;
    }

    /**
     * Two-phase occlusion culling, on by default where the view was made with it; the graph rebuilds when it changes.
     */
    public ClusteredForwardShading occlusion(boolean on) {
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
        SceneDraw.Targets targets = g.request(new SceneDraw(geometry, view, null,
            SurfaceOutput.shaded(colorFormat, clear), depthFormat, width, height,
            occlusion && view.options().occlusion()));
        g.provide(Keys.SCENE_COLOR, targets.color());
        g.provide(Keys.SCENE_DEPTH, targets.depth());
    }
}
