package com.truesim.engine.interaction;

import com.truesim.annotations.AllowAllocation;
import com.truesim.scene.core.NodeHandle;

/**
 * A scene interaction event, as a node's {@link SceneListener} hears it: its type and phase, the node it is for (its
 * target) and the node whose listener runs (the current one, as it bubbles), the pointer, where its ray hit, buttons,
 * clicks, scroll, and the calls that steer what happens next: {@link #consume} (stops it, and stops pass-through along
 * the ray), {@link #capturePointer}, {@link #startDrag}, {@link #acceptDrop}. Reused: read it during the call.
 */
public final class SceneEvent {

    /** The phases of a bubbling event's route. */
    public enum Phase {
        /** From the root down to the target's parent, for capture listeners. */
        CAPTURE,
        /** At the target. */
        TARGET,
        /** From the target's parent up to the root. */
        BUBBLE
    }

    private final SceneInteraction interaction;
    SceneEventType type;
    Phase phase;
    long target;
    long current;
    InteractionWorld world;
    ScenePointer pointer;
    int hit; // the index of the target in the pointer's hits, or -1
    int button;
    int clickCount;
    boolean consumed;
    boolean dropAccepted;
    long dropAcceptor;
    boolean dragStarted;
    boolean dropped;
    long time;

    SceneEvent(SceneInteraction interaction) {
        this.interaction = interaction;
    }

    public SceneEventType type() {
        return type;
    }

    public Phase phase() {
        return phase;
    }

    /** The node it is for: the node hit (before bubbling). */
    public long target() {
        return target;
    }

    /** The node whose listener runs now. */
    public long currentNode() {
        return current;
    }

    /** The world of its nodes. */
    public InteractionWorld world() {
        return world;
    }

    /** Its pointer. */
    public ScenePointer pointer() {
        return pointer;
    }

    /** Its pointer's id. */
    public int pointerId() {
        return pointer.id;
    }

    public PointerKind pointerKind() {
        return pointer.kind;
    }

    /** The local player of its pointer. */
    public int player() {
        return pointer.player;
    }

    /** Where its pointer is across, in window pixels. */
    public float x() {
        return pointer.x;
    }

    /** Where its pointer is down, in window pixels. */
    public float y() {
        return pointer.y;
    }

    /** The button a press or release is of ({@link ScenePointer#PRIMARY} and the others), else 0. */
    public int button() {
        return button;
    }

    /** The buttons held. */
    public int buttons() {
        return pointer.buttons;
    }

    /** The modifiers held ({@link ScenePointer#SHIFT} and the others). */
    public int modifiers() {
        return pointer.modifiers;
    }

    /** A click's count: 1, 2 for a double click, and so on. */
    public int clickCount() {
        return clickCount;
    }

    public float scrollX() {
        return pointer.scrollX;
    }

    public float scrollY() {
        return pointer.scrollY;
    }

    /** Where the primary press was, across. */
    public float pressX() {
        return pointer.pressX;
    }

    /** Where the primary press was, down. */
    public float pressY() {
        return pointer.pressY;
    }

    /** The frame's time, nanoseconds. */
    public long time() {
        return time;
    }

    /** Whether the pointer's ray hit the target this frame (it may not: a captured pointer off it). */
    public boolean hit() {
        return hit >= 0;
    }

    /** Where the ray hit the target into {@code out[0..2]}; false if it did not hit it this frame. */
    public boolean hitPoint(double[] out) {
        if (hit < 0) {
            return false;
        }
        pointer.hits.point(hit, out);
        return true;
    }

    /** How far along the ray the target was hit, or infinity. */
    public double hitDistance() {
        return hit < 0 ? Double.POSITIVE_INFINITY : pointer.hits.distance(hit);
    }

    /** The part of the target hit (a triangle of its mesh), or -1. */
    public int part() {
        return hit < 0 ? -1 : pointer.hits.part(hit);
    }

    /** The pointer's world ray this frame (origin, then direction) into {@code out}; false without one. */
    public boolean ray(double[] out) {
        return pointer.ray(out);
    }

    /** Stops it: no further listeners, and no pass-through to what is behind along the ray. */
    public void consume() {
        consumed = true;
    }

    public boolean consumed() {
        return consumed;
    }

    /** Captures the pointer to the current node: its moves and releases go there until the primary release. */
    public void capturePointer() {
        pointer.captured = current;
    }

    /** Releases the pointer's capture. */
    public void releasePointer() {
        pointer.captured = NodeHandle.NONE;
    }

    /**
     * In a {@link SceneEventType#DRAG_START} listener: starts a drag carrying {@code payload} from the current node;
     * nodes it passes over hear {@link SceneEventType#DROP_OVER} and may accept it. Consumes the event.
     */
    public void startDrag(Object payload) {
        if (type != SceneEventType.DRAG_START) {
            throw new IllegalStateException(notDragStart());
        }
        pointer.dragPayload = java.util.Objects.requireNonNull(payload, "payload");
        pointer.dragSource = current;
        dragStarted = true;
        consumed = true;
    }

    @AllowAllocation("diagnostics")
    private String notDragStart() {
        return "A drag starts in a DRAG_START listener, not in " + type;
    }

    /** The payload of the pointer's drag, or null. */
    public Object dragPayload() {
        return pointer.dragPayload;
    }

    /** The node the pointer's drag started from, or {@link NodeHandle#NONE}. */
    public long dragSource() {
        return pointer.dragSource;
    }

    /** In a {@link SceneEventType#DROP_OVER} listener: the current node takes the payload if dropped here. */
    public void acceptDrop() {
        dropAccepted = true;
        dropAcceptor = current;
        consumed = true;
    }

    /** At {@link SceneEventType#DRAG_END}: whether the payload was dropped on a node that accepted it. */
    public boolean dropped() {
        return dropped;
    }

    /** The interaction. */
    public SceneInteraction interaction() {
        return interaction;
    }

    @Override
    @AllowAllocation("diagnostics")
    public String toString() {
        return type + " " + phase + " at " + NodeHandle.toString(current) + " for " + NodeHandle.toString(target);
    }
}
