package com.truesim.engine.render;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.graph.Buffer;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Key;
import com.truesim.engine.graph.Technique;
import java.util.List;

/**
 * A view's shading hooks' block, written each frame: what provides {@link Keys#SHADING_HOOKS} in every view (and the
 * frame) by default, built where shading requires it. Techniques adding hooks modify the key, so they build before the
 * view's shading, which then writes each shading render view's block in a pass of its own
 * ({@link ShadingHookInputs.Set}: the bits and records, composed in plain memory, handed over at once) and points the
 * view's shaders at the frame's copy. The key's buffer is a token, never written: requiring it orders the techniques. The graph rebuilds when the hooks the scene
 * holds values for change ({@link ShadingHookValues#versions}).
 */
public final class ViewShadingHooks implements Technique {

    @Override
    public List<Key<?>> provides() {
        return List.of(Keys.SHADING_HOOKS);
    }

    @Override
    @AllowAllocation("building the graph, when it changes")
    public void build(GraphBuilder g) {
        Buffer token = g.persistentBuffer("shading hooks", 16); // never written: it only orders
        g.provide(Keys.SHADING_HOOKS, token);
    }

    @Override
    public long buildVersion() {
        return ShadingHookValues.versions();
    }
}
