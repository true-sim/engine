package com.truesim.engine.scene;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.math.Vec2;
import com.truesim.common.math.Vec3;
import com.truesim.common.math.Vec4;
import com.truesim.common.math.color.Color;
import com.truesim.engine.render.MaterialValues;
import com.truesim.engine.render.ShadingHookValues;
import com.truesim.engine.render.ShadingHooks;
import com.truesim.scene.core.ComponentStore;
import com.truesim.scene.core.SceneGraph;
import com.truesim.scene.render.MaterialData;
import java.util.Objects;

/**
 * The scene's shading hook components ({@link ShadingHookComponent}) handed to the renderer's
 * {@link ShadingHookValues}: each hook's values from the first node holding some, on while that node is shown (on a
 * layer) and its component says so; a hook no node holds values for any more is dropped. Follows components replaced
 * (a component is a value: replace it to change it). A hook the shaders were built without is left out.
 *
 * <pre>{@code
 * ShadingHookSync hooks = new ShadingHookSync(scene, ShadingHookValues.of(device));
 * // each frame, after scene.update(), before the frame records:
 * hooks.update();
 * }</pre>
 *
 * <p>Allocates nothing per frame unless values change; one thread. Texture fields are not read from content yet (their
 * defaults).
 */
public final class ShadingHookSync {

    private final SceneGraph scene;
    private final ShadingHookValues values;
    private final ComponentStore<ShadingHookComponent> store;
    private final ShadingHooks hooks;
    // by hook index: the component handed over and whether it was on; this update's
    private final ShadingHookComponent[] applied;
    private final boolean[] appliedOn;
    private final ShadingHookComponent[] seen;
    private final boolean[] seenOn;

    /** {@code graph}'s shading hook components handed to {@code values}. */
    public ShadingHookSync(SceneGraph graph, ShadingHookValues values) {
        this.scene = Objects.requireNonNull(graph, "graph");
        this.values = Objects.requireNonNull(values, "values");
        this.store = graph.components().store(ShadingHookComponent.TYPE);
        this.hooks = values.hooks();
        applied = new ShadingHookComponent[hooks.count()];
        appliedOn = new boolean[hooks.count()];
        seen = new ShadingHookComponent[hooks.count()];
        seenOn = new boolean[hooks.count()];
    }

    /** Hands over what changed since the last update. */
    public void update() {
        java.util.Arrays.fill(seen, null);
        for (int i = 0; i < store.size(); i++) {
            ShadingHookComponent c = store.value(i);
            int index = hooks.index(c.hook());
            if (index < 0 || seen[index] != null) {
                continue;
            }
            seen[index] = c;
            seenOn[index] = c.on() && scene.layers(store.node(i)) != 0;
        }
        for (int index = 0; index < seen.length; index++) {
            ShadingHookComponent c = seen[index];
            if (c != applied[index]) {
                hand(index, c);
            } else if (c != null && seenOn[index] != appliedOn[index]) {
                values.on(hooks.hooks().get(index), seenOn[index]);
            }
            appliedOn[index] = seenOn[index];
        }
    }

    @AllowAllocation("values change rarely")
    private void hand(int index, ShadingHookComponent c) {
        String name = hooks.hooks().get(index);
        if (c == null) {
            values.remove(name);
        } else {
            values.set(name, new Values(c.values(), ""), seenOn[index]);
        }
        applied[index] = c;
    }

    /** Content's values by field name as a generated record reads them, nested by path ({@code a.b}). */
    @AllowAllocation("values change rarely")
    record Values(MaterialData.Values values, String prefix) implements MaterialValues {

        @Override
        public float f32(String name, float fallback) {
            return values.f32(prefix + name, fallback);
        }

        @Override
        public int i32(String name, int fallback) {
            return values.i32(prefix + name, fallback);
        }

        private float[] floats(String name, int count) {
            float[] v = values.floats(prefix + name);
            return v != null && v.length >= count ? v : null;
        }

        @Override
        public Vec2 vec2(String name, Vec2 fallback) {
            float[] v = floats(name, 2);
            return v == null ? fallback : new Vec2(v[0], v[1]);
        }

        @Override
        public Vec3 vec3(String name, Vec3 fallback) {
            float[] v = floats(name, 3);
            return v == null ? fallback : new Vec3(v[0], v[1], v[2]);
        }

        @Override
        public Vec4 vec4(String name, Vec4 fallback) {
            float[] v = floats(name, 4);
            return v == null ? fallback : new Vec4(v[0], v[1], v[2], v[3]);
        }

        @Override
        public Color color(String name, Color fallback) {
            float[] v = floats(name, 3);
            return v == null ? fallback : new Color(v[0], v[1], v[2], v.length > 3 ? v[3] : 1);
        }

        @Override
        public int texture(String name, boolean srgb, int fallback) {
            return fallback;
        }

        @Override
        public MaterialValues nested(String name) {
            return new Values(values, prefix + name + ".");
        }
    }
}
