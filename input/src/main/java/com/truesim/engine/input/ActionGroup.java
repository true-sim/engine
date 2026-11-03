package com.truesim.engine.input;

import com.truesim.annotations.AllowAllocation;
import java.util.ArrayList;
import java.util.List;

/**
 * A group of actions, typed constants in a tree (application-api.md D57, D67): {@code game}, {@code game.vehicle},
 * {@code ui}. Each group has its own switch ({@link Input#enable}, D68), and is active only while it and all its
 * parents are enabled: switching {@code game} off silences {@code game.vehicle} too, and switching it on again leaves
 * {@code game.vehicle} as it was. A module brings its group with its defaults and adds it with one call
 * ({@link Input#add}).
 *
 * <pre>{@code
 * public static final ActionGroup GAME = ActionGroup.root("game");
 * public static final ActionGroup VEHICLE = GAME.child("vehicle");
 * public static final BinaryAction BOOST = VEHICLE.binary("boost", Binding.key(Key.LEFT_SHIFT));
 * }</pre>
 */
@AllowAllocation("declared once")
public final class ActionGroup {

    private final ActionGroup parent;
    private final String id;
    private final String path;
    private final List<ActionGroup> children = new ArrayList<>();
    private final List<Action> actions = new ArrayList<>();

    private ActionGroup(ActionGroup parent, String id) {
        if (!id.matches("[a-z][a-zA-Z0-9_]*")) {
            throw new IllegalArgumentException("A group's id is a word: '" + id + "'");
        }
        this.parent = parent;
        this.id = id;
        this.path = parent == null ? id : parent.path + "." + id;
    }

    /** A group at the top of a tree. */
    public static ActionGroup root(String id) {
        return new ActionGroup(null, id);
    }

    /** A group under this one. */
    public synchronized ActionGroup child(String childId) {
        for (ActionGroup child : children) {
            if (child.id.equals(childId)) {
                throw new IllegalArgumentException(path + " has a group " + childId + " already");
            }
        }
        ActionGroup child = new ActionGroup(this, childId);
        children.add(child);
        return child;
    }

    /** A binary action in this group, with its default bindings. */
    public BinaryAction binary(String name, Binding... defaults) {
        return add(new BinaryAction(this, name, defaults));
    }

    /** A one-number action in this group. */
    public Axis1Action axis1(String name, Binding... defaults) {
        return add(new Axis1Action(this, name, defaults));
    }

    /** A 2D action in this group. */
    public Axis2Action axis2(String name, Binding... defaults) {
        return add(new Axis2Action(this, name, defaults));
    }

    /** A 3D action in this group. */
    public Axis3Action axis3(String name, Binding... defaults) {
        return add(new Axis3Action(this, name, defaults));
    }

    /** A pose action in this group. */
    public PoseAction pose(String name, Binding... defaults) {
        return add(new PoseAction(this, name, defaults));
    }

    private synchronized <A extends Action> A add(A action) {
        for (Action other : actions) {
            if (other.name().equals(action.name())) {
                throw new IllegalArgumentException(path + " has an action " + action.name() + " already");
            }
        }
        actions.add(action);
        return action;
    }

    /** Its parent, or null at the top. */
    public ActionGroup parent() {
        return parent;
    }

    /** Its id under its parent. */
    public String id() {
        return id;
    }

    /** Its ids from the top, dotted: {@code game.vehicle}. */
    public String path() {
        return path;
    }

    /** Its subgroups, in the order made. */
    public synchronized List<ActionGroup> children() {
        return List.copyOf(children);
    }

    /** Its own actions (not its subgroups'), in the order declared. */
    public synchronized List<Action> actions() {
        return List.copyOf(actions);
    }

    /** Whether it is {@code group} or under it. */
    public boolean isIn(ActionGroup group) {
        for (ActionGroup g = this; g != null; g = g.parent) {
            if (g == group) {
                return true;
            }
        }
        return false;
    }

    /** The top of its tree. */
    public ActionGroup root() {
        ActionGroup g = this;
        while (g.parent != null) {
            g = g.parent;
        }
        return g;
    }

    @Override
    public String toString() {
        return path;
    }
}
