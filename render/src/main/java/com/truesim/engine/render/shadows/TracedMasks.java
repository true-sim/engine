package com.truesim.engine.render.shadows;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.PassContext;
import com.truesim.engine.render.Lights;
import com.truesim.engine.render.RayTracing;
import com.truesim.engine.render.SceneView;
import com.truesim.engine.render.ShadowInputs;
import com.truesim.engine.render.ShadowTable;
import com.truesim.scene.core.ShadowSpec;

/** A view's ray-traced shadow masks, for the {@link Shadows} owner. */
final class TracedMasks {

    private final Shadows owner;
    private final SceneView view;
    private final Lights lights;

    TracedMasks(Shadows owner, SceneView view, Lights lights) {
        this.owner = owner;
        this.view = view;
        this.lights = lights;
    }

    void startFrame() {
    }

    void add(int light, ShadowSpec spec) {
    }

    long structure() {
        return 0;
    }

    @AllowAllocation("building the graph, when it changes")
    void build(GraphBuilder g, ShadowInputs.Set inputs, RayTracing rayTracing) {
    }

    int write(ShadowTable table, int light, int entry, PassContext ctx) {
        return 0;
    }
}
