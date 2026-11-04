package com.truesim.engine.interaction;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.math.Vec3;
import com.truesim.common.math.geometry.Frustum;
import com.truesim.common.math.geometry.Plane;
import com.truesim.engine.input.Player;
import com.truesim.scene.core.ComponentStore;
import com.truesim.scene.core.NodeHandle;
import com.truesim.scene.core.Pickable;
import com.truesim.scene.core.SceneGraph;
import com.truesim.scene.core.Selected;
import com.truesim.scene.core.SpatialIndex;
import java.util.Arrays;
import java.util.Objects;

/**
 * The scene's selection, made by pointers (application-api.md, picking and selection): a click on a pickable object
 * selects it (alone; with {@link SceneActions#ADD} held, too; with {@link SceneActions#TOGGLE} held, in or out), a
 * click on nothing selects nothing (unless a modifier is held), a drag from nothing draws a marquee in the 3D view
 * that selects the objects whose boxes it covers when released, {@link SceneActions#ALL} selects every pickable
 * object and {@link SceneActions#NONE} none. Off until {@link #enabled enabled}; a click a node's listener consumed
 * does not select.
 *
 * <p>The selection is data: the {@link Selected} component of the objects (a model's root for its parts:
 * {@link InteractionWorld#objectOf}), which the selection outline draws and saves keep; game code may set it too, and
 * this follows. {@link #primary()} is the object selected last (the gizmo's and the property panel's). Listeners
 * ({@link #listen}) hear each change. Allocates nothing per frame.
 */
public final class SceneSelection {

    /** What the selection reads of the player's input: the modifiers and commands of {@link SceneActions}. */
    public interface Input {

        /** Whether the add modifier is held. */
        boolean add(int player);

        /** Whether the toggle modifier is held. */
        boolean toggle(int player);

        /** Whether select-all was pressed this frame. */
        boolean all();

        /** Whether select-none was pressed this frame. */
        boolean none();

        /** The input's {@link SceneActions}. */
        @AllowAllocation("set up once")
        static Input of(com.truesim.engine.input.Input input) {
            return new Input() {
                @Override
                public boolean add(int player) {
                    return input.player(Math.min(player, input.players() - 1)).down(SceneActions.ADD);
                }

                @Override
                public boolean toggle(int player) {
                    return input.player(Math.min(player, input.players() - 1)).down(SceneActions.TOGGLE);
                }

                @Override
                public boolean all() {
                    Player p = input.player(0);
                    return p.pressed(SceneActions.ALL);
                }

                @Override
                public boolean none() {
                    return input.player(0).pressed(SceneActions.NONE);
                }
            };
        }
    }

    /** Hears the selection change. */
    @FunctionalInterface
    public interface Listener {

        void changed(SceneSelection selection);
    }

    private final SceneInteraction interaction;
    private Input input;
    private boolean enabled;
    private InteractionWorld world; // the world selected in last
    private long primary = NodeHandle.NONE;
    private Listener[] listeners = new Listener[0];
    private long version;
    // the marquee
    private ScenePointer marquee;
    private InteractionView marqueeView;
    private float marqueeX;
    private float marqueeY;
    private float marqueeToX;
    private float marqueeToY;
    private boolean marqueeAdd;
    private boolean marqueeToggle;
    private final double[] corner = new double[6];
    private final SpatialIndex.Visitor marqueeVisitor = this::marqueeNode;

    @AllowAllocation("one per interaction")
    SceneSelection(SceneInteraction interaction) {
        this.interaction = interaction;
    }

    /** Switches selecting by pointers on or off (off by default); returns it. */
    public SceneSelection enabled(boolean on) {
        enabled = on;
        return this;
    }

    public boolean enabled() {
        return enabled;
    }

    /**
     * Reads modifiers and commands from {@code value} (null, the default without the plugin: the pointers' own
     * modifiers, Shift adding, Ctrl or Meta toggling, and no commands); returns it.
     */
    public SceneSelection input(Input value) {
        input = value;
        return this;
    }

    /** Adds {@code listener}; returns it. */
    @AllowAllocation("listeners are added when the game is set up")
    public SceneSelection listen(Listener listener) {
        listeners = Arrays.copyOf(listeners, listeners.length + 1);
        listeners[listeners.length - 1] = Objects.requireNonNull(listener, "listener");
        return this;
    }

    /** The object selected last, while still selected, or {@link NodeHandle#NONE}. */
    public long primary() {
        return world != null && primary != NodeHandle.NONE && world.graph().contains(primary)
            && world.graph().components().has(primary, Selected.TYPE) ? primary : NodeHandle.NONE;
    }

    /** The world selected in last, or null. */
    public InteractionWorld world() {
        return world;
    }

    /** A count that grows with every change made here. */
    public long version() {
        return version;
    }

    /** Whether {@code node} is selected in {@code world}. */
    public boolean selected(InteractionWorld in, long node) {
        return in.graph().contains(node) && in.graph().components().has(node, Selected.TYPE);
    }

    /** How many nodes of {@code world} are selected. */
    public int count(InteractionWorld in) {
        return in.graph().components().store(Selected.TYPE).size();
    }

    /** Selects {@code node} of {@code world} (its object), alone, or added when {@code add}; the primary now. */
    public void select(InteractionWorld in, long node, boolean add) {
        long object = in.objectOf(node);
        if (!add) {
            clear(in);
        }
        in.graph().components().set(object, Selected.TYPE, Selected.YES);
        world = in;
        primary = object;
        changed();
    }

    /** Takes {@code node}'s object out of the selection. */
    public void deselect(InteractionWorld in, long node) {
        long object = in.objectOf(node);
        if (in.graph().components().remove(object, Selected.TYPE)) {
            if (primary == object) {
                primary = NodeHandle.NONE;
            }
            changed();
        }
    }

    /** Selects nothing in {@code world}. */
    public void selectNone(InteractionWorld in) {
        if (clear(in)) {
            changed();
        }
    }

    /** Selects every enabled pickable object of {@code world}. */
    public void selectAll(InteractionWorld in) {
        ComponentStore<Pickable> pickables = in.graph().components().store(Pickable.TYPE);
        ComponentStore<Selected> selected = in.graph().components().store(Selected.TYPE);
        for (int i = 0; i < pickables.size(); i++) {
            long node = pickables.node(i);
            if (pickables.value(i).enabled() && in.objectOf(node) == node && !selected.has(node)) {
                selected.set(node, Selected.YES);
            }
        }
        world = in;
        changed();
    }

    /** Takes every selected node of {@code world} out; whether there was one. */
    private boolean clear(InteractionWorld in) {
        ComponentStore<Selected> selected = in.graph().components().store(Selected.TYPE);
        boolean any = selected.size() > 0;
        while (selected.size() > 0) {
            selected.remove(selected.node(selected.size() - 1));
        }
        primary = NodeHandle.NONE;
        return any;
    }

    private void changed() {
        version++;
        for (Listener l : listeners) {
            l.changed(this);
        }
    }

    // ---------------------------------------------------------------- driven by the interaction

    private boolean add(ScenePointer p) {
        return input != null ? input.add(p.player) : (p.modifiers & ScenePointer.SHIFT) != 0;
    }

    private boolean toggle(ScenePointer p) {
        return input != null ? input.toggle(p.player)
            : (p.modifiers & (ScenePointer.CTRL | ScenePointer.META)) != 0;
    }

    /** An unconsumed click on {@code node}. */
    void clicked(InteractionWorld in, long node, ScenePointer p) {
        if (!enabled || in == null) {
            return;
        }
        long object = in.objectOf(node);
        if (toggle(p)) {
            if (selected(in, object)) {
                deselect(in, object);
            } else {
                select(in, object, true);
            }
        } else {
            select(in, object, add(p));
        }
    }

    /** A click on nothing. */
    void clickedNothing(ScenePointer p) {
        if (enabled && p.world != null && !add(p) && !toggle(p)) {
            selectNone(p.world);
        }
    }

    /** A drag from nothing began: the marquee. */
    void marqueeStart(ScenePointer p) {
        if (!enabled || p.pressView == null) {
            return;
        }
        marquee = p;
        marqueeView = p.pressView;
        marqueeX = p.pressX;
        marqueeY = p.pressY;
        marqueeToX = p.x;
        marqueeToY = p.y;
        marqueeAdd = add(p);
        marqueeToggle = toggle(p);
    }

    void marqueeMove(ScenePointer p) {
        if (marquee == p) {
            marqueeToX = p.x;
            marqueeToY = p.y;
        }
    }

    /** The marquee ends: released ({@code select}), or cancelled. */
    void marqueeEnd(ScenePointer p, boolean select) {
        if (marquee != p) {
            return;
        }
        marquee = null;
        if (!select) {
            return;
        }
        selectIn(marqueeView, Math.min(marqueeX, marqueeToX), Math.min(marqueeY, marqueeToY),
            Math.max(marqueeX, marqueeToX), Math.max(marqueeY, marqueeToY), marqueeAdd, marqueeToggle);
    }

    private InteractionWorld marqueeWorld;
    private boolean marqueeToggling;

    /**
     * Selects the enabled pickable objects whose boxes the window rectangle {@code left, top} to {@code right, bottom}
     * covers in {@code view} (alone; added with {@code add}; toggled with {@code toggle}).
     */
    @AllowAllocation("a marquee's frustum, once per marquee")
    public void selectIn(InteractionView view, float left, float top, float right, float bottom, boolean add,
        boolean toggle) {
        InteractionWorld in = view.world();
        if (!add && !toggle) {
            clear(in);
        }
        Frustum frustum = frustum(view, left, top, right, bottom);
        if (frustum == null) {
            changed();
            return;
        }
        marqueeWorld = in;
        marqueeToggling = toggle;
        in.picker().pickFrustum(frustum, in.pickLayers(), marqueeVisitor);
        marqueeWorld = null;
        world = in;
        changed();
    }

    private boolean marqueeNode(long node) {
        InteractionWorld in = marqueeWorld;
        long object = in.objectOf(node);
        SceneGraph graph = in.graph();
        if (marqueeToggling && graph.components().has(object, Selected.TYPE)) {
            graph.components().remove(object, Selected.TYPE);
        } else {
            graph.components().set(object, Selected.TYPE, Selected.YES);
            primary = object;
        }
        return true;
    }

    /** The frustum of a window rectangle of {@code view}: its corners' rays, the camera's near side; null if none. */
    @AllowAllocation("a marquee's frustum, once per marquee")
    static Frustum frustum(InteractionView view, float left, float top, float right, float bottom) {
        if (right - left < 1 || bottom - top < 1) {
            return null;
        }
        double[][] c = new double[4][6];
        float[][] at = {{left, top}, {right, top}, {right, bottom}, {left, bottom}};
        for (int i = 0; i < 4; i++) {
            if (!view.ray(at[i][0], at[i][1], c[i])) {
                return null;
            }
        }
        double[] middle = new double[6];
        view.ray((left + right) / 2, (top + bottom) / 2, middle);
        Vec3 inside = new Vec3(middle[0] + middle[3], middle[1] + middle[4], middle[2] + middle[5]);
        Plane[] sides = new Plane[4];
        for (int i = 0; i < 4; i++) {
            double[] a = c[i];
            double[] b = c[(i + 1) % 4];
            Vec3 p0 = new Vec3(a[0], a[1], a[2]);
            Vec3 p1 = new Vec3(a[0] + a[3], a[1] + a[4], a[2] + a[5]);
            Vec3 p2 = new Vec3(b[0] + b[3], b[1] + b[4], b[2] + b[5]);
            if (p0.equals(new Vec3(b[0], b[1], b[2]))) {
                sides[i] = Plane.fromPoints(p0, p1, p2); // perspective: through the eye
            } else {
                sides[i] = Plane.fromPoints(p0, p1, new Vec3(b[0], b[1], b[2])); // orthographic: parallel rays
            }
            if (sides[i].signedDistance(inside) < 0) {
                sides[i] = sides[i].flip();
            }
        }
        Vec3 eye = new Vec3(middle[0], middle[1], middle[2]);
        Vec3 forward = new Vec3(middle[3], middle[4], middle[5]);
        Plane near = Plane.fromPointNormal(eye, forward);
        Plane farPlane = Plane.fromPointNormal(eye.add(forward.scale(1e9)), forward.scale(-1));
        return new Frustum(sides[3], sides[1], sides[2], sides[0], near, farPlane);
    }

    /** The frame's commands: select all, select none. */
    void update() {
        if (!enabled || input == null) {
            return;
        }
        InteractionWorld in = world != null ? world : interaction.worldCount() > 0 ? interaction.world(0) : null;
        if (in == null) {
            return;
        }
        if (input.all()) {
            selectAll(in);
        } else if (input.none()) {
            selectNone(in);
        }
    }

    /** Whether a marquee is being drawn. */
    public boolean marqueeActive() {
        return marquee != null;
    }

    /** The marquee's rectangle in window pixels into {@code out}: left, top, right, bottom; false without one. */
    public boolean marquee(float[] out) {
        if (marquee == null) {
            return false;
        }
        out[0] = Math.min(marqueeX, marqueeToX);
        out[1] = Math.min(marqueeY, marqueeToY);
        out[2] = Math.max(marqueeX, marqueeToX);
        out[3] = Math.max(marqueeY, marqueeToY);
        return true;
    }
}
