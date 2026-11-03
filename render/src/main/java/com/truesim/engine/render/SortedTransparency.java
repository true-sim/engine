package com.truesim.engine.render;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.graph.Buffer;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Image;
import com.truesim.engine.graph.Key;
import java.util.List;
import java.util.Objects;

/**
 * Blended surfaces sorted back to front by their distance from the view's eye on the GPU and blended over the scene's
 * color in that order, testing the opaque depth without writing it: the ordinary "over" of each layer on what is
 * behind it, so red glass in front of blue reads differently from blue in front of red, and a nearly opaque layer
 * hides what is behind it. Per view (each eye of a headset, each window), with forward shading or a visibility buffer.
 *
 * <ul>
 *   <li><b>Meshlets</b> (indexed and mesh shader paths): the view's blended meshlets, both occlusion phases', by their
 *       bounding sphere's center, so the far wall of a glass bowl draws before its near wall. They are drawn in runs
 *       of one shading bin (a material type and features, each with its own pipeline), run after run, so the order
 *       holds across shading bins; past {@link Transparency#SORTED_RUNS} runs a view's are ordered shading bin by
 *       shading bin, each's back to front.</li>
 *   <li><b>Classic LOD</b>: each instance's draws by its mesh's bounding sphere, back to front within each shading bin
 *       (mirrored instances apart); the shading bins one after another.</li>
 *   <li><b>Approximate</b> where surfaces intersect, or where a meshlet's center misorders it against another one
 *       overlapping it (a large meshlet lying across a small one): the order is per meshlet, not per pixel.</li>
 *   <li><b>Transmissive</b> surfaces sample the opaque scene behind them ({@link SceneBehind}): what they show through
 *       is the opaque scene, refracted; blended layers behind them are covered as far as they are opaque.</li>
 *   <li><b>Particles</b> ({@code ParticleDraw}) draw after the blended surfaces, emitter by emitter back to front (or
 *       before glass, or split at it), not interleaved with them.</li>
 * </ul>
 *
 * <pre>{@code
 * view.port().add(new SortedTransparency(geometry, view));
 * }</pre>
 */
public final class SortedTransparency implements Transparency {

    private final SceneGeometry geometry;
    private final SceneView view;

    /** {@code geometry}'s blended surfaces as {@code view} sees them, back to front; added to {@code view}'s port. */
    public SortedTransparency(SceneGeometry geometry, SceneView view) {
        this.geometry = Objects.requireNonNull(geometry, "geometry");
        this.view = Objects.requireNonNull(view, "view");
    }

    @Override
    public List<Key<?>> modifies() {
        return List.of(Keys.SCENE_COLOR);
    }

    /**
     * The scene's depth, tested: as the techniques before it leave it (the opaque scene's), so techniques after it
     * that read the depth (particles) need not be built before it.
     */
    @Override
    public List<Key<?>> reads() {
        return List.of(Keys.SCENE_DEPTH);
    }

    @Override
    public long buildVersion() {
        return Transparency.buildVersion(geometry.renderer().scene().materials());
    }

    @Override
    @AllowAllocation("building the graph, when it changes")
    public void build(GraphBuilder g) {
        build(g, null);
    }

    /** Builds it, testing {@code taken} (null: the scene's depth as read in its order), the depth a caller took. */
    @AllowAllocation("building the graph, when it changes")
    void build(GraphBuilder g, Image taken) {
        view.checkBuilding(g, this);
        Image color = g.modify(Keys.SCENE_COLOR);
        Materials materials = geometry.renderer().scene().materials();
        if (materials == null || !materials.hasBlended()) {
            return; // nothing blended: the color as it is
        }
        Image depth = taken != null ? taken : g.read(Keys.SCENE_DEPTH);
        Image behind = Transparency.behind(g, materials, color); // the opaque scene, before anything blends over it
        Buffer lights = g.optional(Keys.LIGHT_LIST);
        ShadowInputs.Set shadows = lights == null ? null : g.request(new ShadowInputs(g.view()));
        BlendedTargets targets = new BlendedTargets(BlendedTargets.Kind.SORTED, color, null, null, depth, lights,
            shadows, g.view(), behind, IndirectInputs.of(g));
        boolean load = false;
        for (GeometryProvider provider : geometry.providers()) {
            provider.drawBlended(g, view.view(), targets, load);
            load = true;
        }
    }
}
