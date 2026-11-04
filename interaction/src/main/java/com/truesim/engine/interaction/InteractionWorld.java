package com.truesim.engine.interaction;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.render.Camera;
import com.truesim.scene.core.ComponentStore;
import com.truesim.scene.core.ComponentType;
import com.truesim.scene.core.Hovered;
import com.truesim.scene.core.NodeHandle;
import com.truesim.scene.core.Pickable;
import com.truesim.scene.core.RoutedListener;
import com.truesim.scene.core.SceneEventType;
import com.truesim.scene.core.SceneGraph;
import com.truesim.scene.core.ScenePicker;
import com.truesim.scene.core.SpatialIndex;
import java.util.Arrays;
import java.util.Objects;

/**
 * One world's part of the {@link SceneInteraction}: its scene, spatial index and {@link ScenePicker}, the listeners on
 * its nodes, and the views pointers pick it through. Listeners hear the full pointer interaction set on their node and,
 * for events that bubble, on its descendants:
 *
 * <pre>{@code
 * InteractionWorld world = interaction.world(scene, index);
 * world.view(camera).port(mainView);
 * world.picker().resolver(new RenderablePicking(renderables, cache::get));    // meshes by their triangles
 * scene.components().set(door, Pickable.TYPE, Pickable.mesh());
 * world.on(door, SceneEventType.CLICK, e -> open(e.currentNode()));
 * world.on(level, SceneEventType.CLICK.bit() | SceneEventType.CONTEXT.bit(), e -> ...); // its doors' too, bubbled
 * }</pre>
 *
 * <p>Listeners are a transient component of their nodes, so they go with them. A node's "object" ({@link #objectOf})
 * is the topmost node of the unbroken chain of pickable nodes above it: a model's root when the model was spawned
 * pickable. Hover marks objects {@link Hovered}, and selection selects objects. Used on the scene's thread.
 */
public final class InteractionWorld {

    /** The listeners on a node: a transient component. */
    static final ComponentType<NodeListeners> LISTENERS = ComponentType.transientOf("truesim.interaction.listeners",
        NodeListeners.class);

    private final SceneInteraction interaction;
    private final SceneGraph graph;
    private final SpatialIndex index;
    private final ScenePicker picker;
    final ComponentStore<NodeListeners> listeners;
    final ComponentStore<RoutedListener> routed;
    private final ComponentStore<Pickable> pickables;
    private final ComponentStore<Hovered> hoveredStore;
    private int[] hoverCounts = new int[64]; // by node slot: pointers hovering the object there
    private long[] hoverNodes = new long[64];
    private boolean updatesScene = true;
    private int pickLayers = -1;

    @AllowAllocation("a world is added once")
    InteractionWorld(SceneInteraction interaction, ScenePicker picker) {
        this.interaction = interaction;
        this.picker = Objects.requireNonNull(picker, "picker");
        this.graph = picker.scene();
        this.index = picker.index();
        this.listeners = graph.components().store(LISTENERS);
        this.routed = graph.components().store(RoutedListener.TYPE);
        this.pickables = graph.components().store(Pickable.TYPE);
        this.hoveredStore = graph.components().store(Hovered.TYPE);
    }

    /** The scene. */
    public SceneGraph graph() {
        return graph;
    }

    /** Its spatial index. */
    public SpatialIndex index() {
        return index;
    }

    /** The picker its pointers pick through (give it a resolver for meshes). */
    public ScenePicker picker() {
        return picker;
    }

    /** The interaction. */
    public SceneInteraction interaction() {
        return interaction;
    }

    /**
     * Whether the interaction updates the scene's world transforms and its spatial index at the frame's start, before
     * picking (default true; false where the game does so itself before the interaction); returns it.
     */
    public InteractionWorld updatesScene(boolean on) {
        updatesScene = on;
        return this;
    }

    /** Whether it updates the scene before picking. */
    public boolean updatesScene() {
        return updatesScene;
    }

    /** Its pointers pick pickables on the pick layers of {@code mask} (default: every one); returns it. */
    public InteractionWorld pickLayers(int mask) {
        pickLayers = mask;
        return this;
    }

    /** The pick layers its pointers pick. */
    public int pickLayers() {
        return pickLayers;
    }

    /** A view of this world through the renderer's {@code camera}; give it a rectangle ({@code port} or {@code rect}). */
    @AllowAllocation("views are added when the game is set up")
    public InteractionView view(Camera camera) {
        return interaction.addView(new InteractionView(this, Objects.requireNonNull(camera, "camera"),
            NodeHandle.NONE));
    }

    /** A view of this world through camera node {@code cameraNode} (its {@code CameraLens}, application-api.md D129). */
    @AllowAllocation("views are added when the game is set up")
    public InteractionView view(long cameraNode) {
        if (!graph.contains(cameraNode)) {
            throw new IllegalArgumentException("Not a node of the scene: " + NodeHandle.toString(cameraNode));
        }
        return interaction.addView(new InteractionView(this, null, cameraNode));
    }

    // ---------------------------------------------------------------- listeners

    /** Adds {@code listener} for events of {@code type} on {@code node} (and its descendants', for bubbling types). */
    public InteractionWorld on(long node, SceneEventType type, SceneListener listener) {
        return on(node, type.bit(), listener);
    }

    /** Adds {@code listener} for the event types of {@code mask} ({@link SceneEventType#bit()} or'ed); returns it. */
    @AllowAllocation("listeners are added when a scene is built")
    public InteractionWorld on(long node, int mask, SceneListener listener) {
        add(node, mask, listener, false);
        return this;
    }

    /** Adds {@code listener} for {@code mask} in the capture phase: before its descendants' own listeners. */
    @AllowAllocation("listeners are added when a scene is built")
    public InteractionWorld onCapture(long node, int mask, SceneListener listener) {
        add(node, mask, listener, true);
        return this;
    }

    @AllowAllocation("listeners are added when a scene is built")
    private void add(long node, int mask, SceneListener listener, boolean capture) {
        Objects.requireNonNull(listener, "listener");
        NodeListeners old = listeners.get(node);
        listeners.set(node, old == null ? new NodeListeners(mask, listener, capture) : old.with(mask, listener,
            capture));
    }

    /** Takes {@code listener} off {@code node}; false if it was not on it. */
    @AllowAllocation("listeners are taken off rarely")
    public boolean off(long node, SceneListener listener) {
        NodeListeners old = listeners.get(node);
        if (old == null || !old.has(listener)) {
            return false;
        }
        NodeListeners without = old.without(listener);
        if (without == null) {
            listeners.remove(node);
        } else {
            listeners.set(node, without);
        }
        return true;
    }

    /** Whether {@code node} has listeners. */
    public boolean listened(long node) {
        return listeners.has(node);
    }

    // ---------------------------------------------------------------- queries

    /** Whether a pointer is over {@code node} or one of its descendants. */
    public boolean hovered(long node) {
        for (int i = 0; i < interaction.pointerCount(); i++) {
            ScenePointer p = interaction.pointer(i);
            if (p.hoverWorld == this && above(node, p.hovered)) {
                return true;
            }
        }
        return false;
    }

    /** Whether a held primary press went to {@code node} or one of its descendants. */
    public boolean pressed(long node) {
        for (int i = 0; i < interaction.pointerCount(); i++) {
            ScenePointer p = interaction.pointer(i);
            if (p.pressing && p.world == this && above(node, p.pressed)) {
                return true;
            }
        }
        return false;
    }

    /** Whether {@code ancestor} is {@code node} or above it. */
    boolean above(long ancestor, long node) {
        for (long n = node; n != NodeHandle.NONE && graph.contains(n); n = graph.parent(n)) {
            if (n == ancestor) {
                return true;
            }
        }
        return false;
    }

    /**
     * The object {@code node} is part of: the topmost node of the unbroken chain of pickable nodes from it up (itself
     * when its parent is not pickable), so the parts of a model spawned pickable are one object, its root.
     */
    public long objectOf(long node) {
        long object = node;
        for (long n = graph.parent(node); n != NodeHandle.NONE && pickables.has(n); n = graph.parent(n)) {
            object = n;
        }
        return object;
    }

    /** Counts a pointer over {@code object}, marking it {@link Hovered} for the first. */
    void enterObject(long object) {
        int slot = NodeHandle.index(object);
        if (slot >= hoverCounts.length) {
            grow(slot + 1);
        }
        if (hoverNodes[slot] != object) {
            hoverNodes[slot] = object;
            hoverCounts[slot] = 0;
        }
        if (hoverCounts[slot]++ == 0 && graph.contains(object)) {
            hoveredStore.set(object, Hovered.YES);
        }
    }

    /** Counts a pointer less over {@code object}, unmarking it after the last. */
    void exitObject(long object) {
        int slot = NodeHandle.index(object);
        if (slot < hoverCounts.length && hoverNodes[slot] == object && hoverCounts[slot] > 0
            && --hoverCounts[slot] == 0 && graph.contains(object)) {
            hoveredStore.remove(object);
        }
    }

    @AllowAllocation("growing with the scene")
    private void grow(int length) {
        int n = Math.max(length, hoverCounts.length * 2);
        hoverCounts = Arrays.copyOf(hoverCounts, n);
        hoverNodes = Arrays.copyOf(hoverNodes, n);
    }

    /** The listeners on a node: copied when changed, so a dispatch runs the set it started with. */
    static final class NodeListeners {
        final int[] masks;
        final SceneListener[] listeners;
        final boolean[] capture;
        final int all; // every mask or'ed

        @AllowAllocation("listeners are added when a scene is built")
        NodeListeners(int mask, SceneListener listener, boolean inCapture) {
            this(new int[] {mask}, new SceneListener[] {listener}, new boolean[] {inCapture});
        }

        private NodeListeners(int[] masks, SceneListener[] listeners, boolean[] capture) {
            this.masks = masks;
            this.listeners = listeners;
            this.capture = capture;
            int or = 0;
            for (int m : masks) {
                or |= m;
            }
            this.all = or;
        }

        @AllowAllocation("listeners are added when a scene is built")
        NodeListeners with(int mask, SceneListener listener, boolean inCapture) {
            int n = masks.length;
            int[] m = Arrays.copyOf(masks, n + 1);
            SceneListener[] l = Arrays.copyOf(listeners, n + 1);
            boolean[] c = Arrays.copyOf(capture, n + 1);
            m[n] = mask;
            l[n] = listener;
            c[n] = inCapture;
            return new NodeListeners(m, l, c);
        }

        boolean has(SceneListener listener) {
            for (SceneListener l : listeners) {
                if (l == listener) {
                    return true;
                }
            }
            return false;
        }

        @AllowAllocation("listeners are taken off rarely")
        NodeListeners without(SceneListener listener) {
            int kept = 0;
            for (SceneListener l : listeners) {
                kept += l == listener ? 0 : 1;
            }
            if (kept == 0) {
                return null;
            }
            int[] m = new int[kept];
            SceneListener[] l = new SceneListener[kept];
            boolean[] c = new boolean[kept];
            int k = 0;
            for (int i = 0; i < listeners.length; i++) {
                if (listeners[i] != listener) {
                    m[k] = masks[i];
                    l[k] = listeners[i];
                    c[k++] = capture[i];
                }
            }
            return new NodeListeners(m, l, c);
        }
    }
}
