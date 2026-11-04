package com.truesim.engine.interaction;

import com.truesim.annotations.AllowAllocation;
import com.truesim.scene.core.NodeHandle;
import com.truesim.scene.core.PickHits;

/**
 * One pointer of the {@link SceneInteraction}: what its source wrote this frame (a point in a window or a world ray,
 * buttons, scroll, modifiers) and what it found (its hits along its ray, nearest first, the node it is over, pressed,
 * captured). Sources write it at the frame's start ({@link ScenePointerSource#poll}); game code reads it. Allocates
 * nothing.
 */
public final class ScenePointer {

    /** The primary button: the mouse's left, a finger, a pen's tip, a trigger. */
    public static final int PRIMARY = 1;
    /** The secondary button: the mouse's right, a pen's first barrel button. */
    public static final int SECONDARY = 2;
    /** The middle button. */
    public static final int MIDDLE = 4;

    /** Shift held. */
    public static final int SHIFT = 1;
    /** Control held. */
    public static final int CTRL = 2;
    /** Alt held. */
    public static final int ALT = 4;
    /** Meta (Cmd, the Windows key) held. */
    public static final int META = 8;

    // written by its source
    int id;
    PointerKind kind = PointerKind.MOUSE;
    int player;
    float x;
    float y;
    boolean hasRay;
    final double[] ray = new double[6];
    int buttons;
    float scrollX;
    float scrollY;
    int modifiers;
    boolean blocked;
    InteractionWorld rayWorld; // the world a ray of its own picks in, or null: the interaction's ray world
    long writtenFrame = -1;
    boolean used;

    // found this frame
    PickHits hits;
    InteractionWorld world;
    InteractionView view;
    boolean picked;
    final double[] pickedRay = new double[6];

    // the interaction's state
    long hovered = NodeHandle.NONE;   // the node it is over (its first hit)
    long hoverRoot = NodeHandle.NONE; // the pickable object it hovers, marked Hovered
    InteractionWorld hoverWorld;
    long pressed = NodeHandle.NONE;   // the node the primary press went to
    long captured = NodeHandle.NONE;  // the node that gets its moves and releases
    boolean pressing;                 // the primary press is held
    boolean pressedNothing;           // the primary press hit nothing
    int lastButtons;
    float lastX;
    float lastY;
    final double[] lastRay = new double[6];
    float pressX;
    float pressY;
    final double[] pressRay = new double[6];
    InteractionWorld pressWorld;
    InteractionView pressView;
    long pressTime;
    boolean longFired;
    boolean suppressClick;
    boolean dragging;
    long clickNode = NodeHandle.NONE;
    long clickTime;
    float clickX;
    float clickY;
    int clickCount;
    // a drag of the scene's own (a payload carried between nodes)
    Object dragPayload;
    long dragSource = NodeHandle.NONE;
    long dropTarget = NodeHandle.NONE;

    @AllowAllocation("pointers are made once")
    ScenePointer(int capacity) {
        hits = new PickHits(capacity);
    }

    // ---------------------------------------------------------------- written by sources

    /** Puts it at {@code px, py}, in the pixels of the window its views are in; returns it. */
    public ScenePointer moveTo(float px, float py) {
        x = px;
        y = py;
        hasRay = false;
        return this;
    }

    /**
     * Gives it a world ray of its own (an XR controller's aim), picked in {@code world} (null: the interaction's ray
     * world); returns it.
     */
    public ScenePointer ray(double ox, double oy, double oz, double dx, double dy, double dz, InteractionWorld world) {
        ray[0] = ox;
        ray[1] = oy;
        ray[2] = oz;
        ray[3] = dx;
        ray[4] = dy;
        ray[5] = dz;
        hasRay = true;
        rayWorld = world;
        return this;
    }

    /** Sets the buttons held ({@link #PRIMARY} and the others); returns it. */
    public ScenePointer buttons(int bits) {
        buttons = bits;
        return this;
    }

    /** Sets this frame's scroll; returns it. */
    public ScenePointer scroll(float dx, float dy) {
        scrollX = dx;
        scrollY = dy;
        return this;
    }

    /** Sets the modifiers held ({@link #SHIFT} and the others); returns it. */
    public ScenePointer modifiers(int bits) {
        modifiers = bits;
        return this;
    }

    /** Sets the local player it belongs to; returns it. */
    public ScenePointer player(int index) {
        player = index;
        return this;
    }

    /**
     * Marks it as taken by something in front of the scene this frame (a screen UI element, a world panel, a gizmo
     * handle): it picks nothing in the scene, so scene nodes see it leave; returns it.
     */
    public ScenePointer blocked(boolean on) {
        blocked = on;
        return this;
    }

    // ---------------------------------------------------------------- read by game code

    /** Its id, unique among the interaction's pointers. */
    public int id() {
        return id;
    }

    public PointerKind kind() {
        return kind;
    }

    /** The local player it belongs to. */
    public int player() {
        return player;
    }

    /** Where it is across, in window pixels (an XR ray's is meaningless). */
    public float x() {
        return x;
    }

    /** Where it is down, in window pixels. */
    public float y() {
        return y;
    }

    /** The buttons held. */
    public int buttonBits() {
        return buttons;
    }

    /** The modifiers held. */
    public int modifierBits() {
        return modifiers;
    }

    /** This frame's horizontal scroll. */
    public float scrollX() {
        return scrollX;
    }

    /** This frame's vertical scroll. */
    public float scrollY() {
        return scrollY;
    }

    /** Whether something in front of the scene took it this frame. */
    public boolean isBlocked() {
        return blocked;
    }

    /** Whether it picked in the scene this frame: it had a ray (over a view, or its own) and was not blocked. */
    public boolean picked() {
        return picked;
    }

    /** Its world ray this frame (origin, then direction) into {@code out[0..5]}; false if it had none. */
    public boolean ray(double[] out) {
        if (!picked) {
            return false;
        }
        System.arraycopy(pickedRay, 0, out, 0, 6);
        return true;
    }

    /** What its ray hit this frame, nearest first (empty if it picked nothing). */
    public PickHits hits() {
        return hits;
    }

    /** The world it picked in this frame, or null. */
    public InteractionWorld world() {
        return world;
    }

    /** The view it picked through this frame, or null (blocked, off every view, or a ray of its own). */
    public InteractionView view() {
        return view;
    }

    /** The node it is over (its nearest hit), or {@link NodeHandle#NONE}. */
    public long hovered() {
        return hovered;
    }

    /** The node its primary press went to while held, or {@link NodeHandle#NONE}. */
    public long pressed() {
        return pressing ? pressed : NodeHandle.NONE;
    }

    /** The node that has captured it, or {@link NodeHandle#NONE}. */
    public long captured() {
        return captured;
    }

    /** Whether it is dragging (pressed and moved past the threshold). */
    public boolean dragging() {
        return dragging;
    }

    /** The node it hit nearest this frame, or {@link NodeHandle#NONE}. */
    public long hitNode() {
        return hits.count() > 0 ? hits.node(0) : NodeHandle.NONE;
    }

    /** How far along its ray the nearest hit is, or infinity. */
    public double hitDistance() {
        return hits.count() > 0 ? hits.distance(0) : Double.POSITIVE_INFINITY;
    }

    /** The nearest hit's world point into {@code out[0..2]}; false without a hit. */
    public boolean hitPoint(double[] out) {
        if (hits.count() == 0) {
            return false;
        }
        hits.point(0, out);
        return true;
    }

    /** The part of the nearest hit (a triangle), or -1. */
    public int hitPart() {
        return hits.count() > 0 ? hits.part(0) : -1;
    }

    @Override
    @AllowAllocation("diagnostics")
    public String toString() {
        return kind + " pointer " + id;
    }
}
