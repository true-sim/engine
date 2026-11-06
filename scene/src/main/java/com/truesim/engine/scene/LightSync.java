package com.truesim.engine.scene;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.math.TransformArray;
import com.truesim.engine.render.Lights;
import com.truesim.scene.core.ComponentStore;
import com.truesim.scene.core.LightSource;
import com.truesim.scene.core.NodeHandle;
import com.truesim.scene.core.SceneGraph;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Arrays;
import java.util.Objects;

/**
 * The scene's light components placed in the renderer's {@link Lights} (application-api.md D46): each node with a
 * {@link LightSource} takes a light id while it has one, set from the node's world transform (point and spot lights at
 * its origin, spot and directional lights along its -Z) and the light's colour times intensity (candela, or lux for a
 * directional light: the irradiance at 1 m), range and cone, and its {@link com.truesim.scene.core.ShadowSpec} (how
 * it casts shadows, which the engine's {@code Shadows} owner draws). Following moves (the scene's versions) and changes
 * made in place (the light's version); a node hidden (on no layer) or removed turns its light off.
 *
 * <pre>{@code
 * LightSync lightSync = new LightSync(scene, lights);
 * // each frame, after scene.update(), before the frame records:
 * lightSync.update();
 * }</pre>
 *
 * <p>Allocates nothing per frame once its lights have their ids; one thread.
 */
public final class LightSync {

    private static final ValueLayout.OfDouble DOUBLE = ValueLayout.JAVA_DOUBLE;

    private final SceneGraph scene;
    private final Lights lights;
    private final ComponentStore<LightSource> store;
    // by node slot: the light id there (-1: none), the node it is of, what of it was written last
    private int[] ids = new int[0];
    private long[] nodes = new long[0];
    private long[] lightVersions = new long[0];
    private long[] nodeVersions = new long[0];
    private boolean[] shown = new boolean[0];
    private int placed;

    /** {@code graph}'s light components placed in {@code renderLights}. */
    public LightSync(SceneGraph graph, Lights renderLights) {
        this.scene = Objects.requireNonNull(graph, "graph");
        this.lights = Objects.requireNonNull(renderLights, "renderLights");
        this.store = graph.components().store(LightSource.TYPE);
    }

    /** How many lights it has placed. */
    public int count() {
        return placed;
    }

    /** The light id {@code node}'s light has, or -1. */
    public int idOf(long node) {
        int slot = NodeHandle.index(node);
        return slot < ids.length && nodes[slot] == node ? ids[slot] : -1;
    }

    /** Places what changed: lights added, changed, moved, hidden or removed since the last update. */
    public void update() {
        // lights whose nodes or components are gone
        for (int slot = 0; slot < ids.length; slot++) {
            if (ids[slot] >= 0 && (!scene.contains(nodes[slot]) || !store.has(nodes[slot]))) {
                lights.remove(ids[slot]);
                ids[slot] = -1;
                nodes[slot] = NodeHandle.NONE;
                placed--;
            }
        }
        TransformArray world = scene.worldTransforms();
        for (int i = 0; i < store.size(); i++) {
            long node = store.node(i);
            LightSource light = store.value(i);
            int slot = NodeHandle.index(node);
            if (slot >= ids.length) {
                grow(slot + 1);
            }
            if (nodes[slot] != node || ids[slot] < 0) {
                ids[slot] = lights.add();
                nodes[slot] = node;
                lightVersions[slot] = -1;
                placed++;
            }
            long version = scene.version(node);
            boolean visible = scene.layers(node) != 0;
            if (light.version() != lightVersions[slot] || version != nodeVersions[slot] || visible != shown[slot]) {
                write(ids[slot], light, world, slot, visible);
                lightVersions[slot] = light.version();
                nodeVersions[slot] = version;
                shown[slot] = visible;
            }
        }
    }

    private void write(int id, LightSource light, TransformArray world, int slot, boolean visible) {
        MemorySegment s = world.segment();
        long o = slot * world.stride();
        double x = s.get(DOUBLE, o);
        double y = s.get(DOUBLE, o + 8);
        double z = s.get(DOUBLE, o + 16);
        double qx = s.get(DOUBLE, o + 24);
        double qy = s.get(DOUBLE, o + 32);
        double qz = s.get(DOUBLE, o + 40);
        double qw = s.get(DOUBLE, o + 48);
        float dx = (float) (-2 * (qx * qz + qw * qy)); // -Z turned
        float dy = (float) (-2 * (qy * qz - qw * qx));
        float dz = (float) (-(1 - 2 * (qx * qx + qy * qy)));
        float scale = visible ? (float) light.intensity() : 0;
        float r = light.red() * scale;
        float g = light.green() * scale;
        float b = light.blue() * scale;
        float range = light.range() == LightSource.NO_RANGE ? Lights.NO_RANGE : (float) light.range();
        switch (light.kind()) {
            case POINT -> lights.point(id, x, y, z, range, r, g, b);
            case SPOT -> lights.spot(id, x, y, z, range, r, g, b, dx, dy, dz, light.innerCone(), light.outerCone());
            case DIRECTIONAL -> lights.directional(id, dx, dy, dz, r, g, b);
        }
        lights.shadow(id, light.shadow());
    }

    @AllowAllocation("growing with the scene")
    private void grow(int length) {
        int n = Math.max(length, ids.length * 2);
        int old = ids.length;
        ids = Arrays.copyOf(ids, n);
        Arrays.fill(ids, old, n, -1);
        nodes = Arrays.copyOf(nodes, n);
        lightVersions = Arrays.copyOf(lightVersions, n);
        nodeVersions = Arrays.copyOf(nodeVersions, n);
        shown = Arrays.copyOf(shown, n);
    }
}
