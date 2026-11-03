package com.truesim.engine.input;

import com.truesim.annotations.AllowAllocation;
import java.util.List;
import java.util.Objects;

/**
 * A layer of input (application-api.md D61): the action groups it covers, its priority, what it does with the mouse,
 * and whether it takes the keyboard's keys for a text field. Contexts on the stack ({@link Input#push}) are ordered by
 * priority, the last pushed first among equals; the actions of a group are read in the highest context covering it,
 * and controls bound there are taken from the contexts under it, so a menu's Escape does not also pause the game. A
 * blocking context hides every context under it (a modal dialog); a context taking text leaves the contexts under it no
 * keyboard keys but those it binds itself. Groups in no context on the stack are read under every context. Switching
 * contexts resets the filters (D86).
 *
 * <pre>{@code
 * InputContext menu = input.context("menu", 100).groups(UI).mouse(MouseMode.RELEASED).blocking(true);
 * input.push(menu);   // the game's actions stop; the pointer is back
 * input.pop(menu);
 * }</pre>
 */
public final class InputContext {

    private final Input input;
    private final String name;
    private final int priority;
    private List<ActionGroup> groups = List.of();
    private MouseMode mouse = MouseMode.INHERIT;
    private boolean blocking;
    private boolean takesText;
    private TextListener text;
    long pushed; // when it was pushed (an increasing number), 0 when not on the stack

    InputContext(Input input, String name, int priority) {
        this.input = input;
        this.name = Objects.requireNonNull(name, "name");
        this.priority = priority;
    }

    /** Its name, in reports. */
    public String name() {
        return name;
    }

    /** Its priority: higher is read first. */
    public int priority() {
        return priority;
    }

    /** The action groups it covers, with their subgroups. */
    @AllowAllocation("set up")
    public InputContext groups(ActionGroup... covered) {
        groups = List.of(covered);
        input.contextsChanged();
        return this;
    }

    /** What it does with the mouse. */
    public InputContext mouse(MouseMode mode) {
        mouse = Objects.requireNonNull(mode, "mode");
        input.contextsChanged();
        return this;
    }

    /** Whether it hides the contexts under it. */
    public InputContext blocking(boolean on) {
        blocking = on;
        input.contextsChanged();
        return this;
    }

    /**
     * Whether it takes the keyboard for a text field: while on the stack and not hidden, the window takes text input
     * and the contexts under it get no keys. {@code listener} receives the text; null for none.
     */
    public InputContext takesText(TextListener listener) {
        takesText = true;
        text = listener;
        input.contextsChanged();
        return this;
    }

    /** Stops taking text. */
    public InputContext noText() {
        takesText = false;
        text = null;
        input.contextsChanged();
        return this;
    }

    public List<ActionGroup> groups() {
        return groups;
    }

    public MouseMode mouse() {
        return mouse;
    }

    public boolean blocking() {
        return blocking;
    }

    public boolean takesText() {
        return takesText;
    }

    TextListener textListener() {
        return text;
    }

    /** Whether it is on the stack. */
    public boolean active() {
        return pushed != 0;
    }

    /** Whether it covers {@code group}. */
    boolean covers(ActionGroup group) {
        for (int i = 0; i < groups.size(); i++) {
            if (group.isIn(groups.get(i))) {
                return true;
            }
        }
        return false;
    }

    @Override
    @AllowAllocation("describing")
    public String toString() {
        return name + " (" + priority + ")";
    }
}
