package com.truesim.engine.render;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Key;
import com.truesim.engine.graph.Technique;
import java.util.List;
import java.util.Objects;

/**
 * The scene shaded as it is drawn: provides {@link Keys#SCENE_COLOR} and {@link Keys#SCENE_DEPTH} from one
 * {@link SceneDraw} of its {@link SceneView}, the size of the view's port, with {@link SurfaceOutput#shaded shaded} output, each fragment lit by the
 * lights the frame's {@link Keys#LIGHT_LIST} gives it (the sun without one). Simple, and each draw shades its own fragments, covered later or not. {@code graph.replace} swaps
 * it for another shading technique ({@link VisibilityBufferShading}).
 *
 * <pre>{@code
 * view.port().add(new ClusteredForwardShading(geometry, view, VK_FORMAT_R16G16B16A16_SFLOAT, VK_FORMAT_D32_SFLOAT))
 *     .add(new ClusteredLights(lights, view));
 * }</pre>
 */
public final class ClusteredForwardShading implements Technique {

    private final SceneGeometry geometry;
    private final int colorFormat;
    private final int depthFormat;
    private final float[] clear = new float[3];
    private final SceneView view;
    private Boolean occlusion; // or null: as the graph's settings say

    /**
     * {@code geometry} shaded as {@code view} sees it; added to {@code view}'s port. The providers' pipelines must have been made for
     * {@code colorFormat} and {@code depthFormat}.
     */
    public ClusteredForwardShading(SceneGeometry geometry, SceneView view, int colorFormat, int depthFormat) {
        this.geometry = Objects.requireNonNull(geometry, "geometry");
        this.view = Objects.requireNonNull(view, "view");
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
     * Two-phase occlusion culling where the view was made with it, overriding the graph's settings
     * ({@link RenderSettings#OCCLUSION}); the graph rebuilds when it changes.
     */
    @AllowAllocation("set up rarely")
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
        view.checkBuilding(g, this);
        SceneDraw.Targets targets = g.request(new SceneDraw(geometry, view.view(), null,
            SurfaceOutput.shaded(colorFormat, clear), depthFormat, view.width(), view.height(),
            (occlusion != null ? occlusion : g.setting(RenderSettings.OCCLUSION))
            && view.view().options().occlusion()));
        g.provide(Keys.SCENE_COLOR, targets.color());
        g.provide(Keys.SCENE_DEPTH, targets.depth());
    }
}
