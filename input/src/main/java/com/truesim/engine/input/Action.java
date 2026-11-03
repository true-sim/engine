package com.truesim.engine.input;

import com.truesim.annotations.AllowAllocation;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Something the player does, as the game reads it (application-api.md D57, D75): a typed constant in an
 * {@link ActionGroup}, with the bindings it has unless the game or the player rebinds it. Its type says what its value
 * is: {@link BinaryAction} (down or not, no analog value: D76), {@link Axis1Action}, {@link Axis2Action},
 * {@link Axis3Action}, {@link PoseAction}. Declared in groups:
 *
 * <pre>{@code
 * public static final ActionGroup GAME = ActionGroup.root("game");
 * public static final BinaryAction JUMP = GAME.binary("jump", Binding.key(Key.SPACE), Binding.pad(GamepadButton.SOUTH));
 * public static final Axis2Action MOVE = GAME.axis2("move", Binding.keys(Key.A, Key.D, Key.S, Key.W), Binding.stick(false));
 * }</pre>
 *
 * <p>Actions are shared by every engine in the process: their state is in each {@link Input}, read through a
 * {@link Player}.
 */
public abstract sealed class Action permits BinaryAction, Axis1Action, Axis2Action, Axis3Action, PoseAction {

    private static final AtomicInteger IDS = new AtomicInteger(); // a number per action, for each input's table

    final int id = IDS.getAndIncrement();
    private final ActionGroup group;
    private final String name;
    private final List<Binding> defaults;
    private Combine combine = Combine.LARGEST;

    @AllowAllocation("declared once")
    Action(ActionGroup group, String name, Binding... defaults) {
        if (!name.matches("[a-zA-Z][a-zA-Z0-9_]*")) {
            throw new IllegalArgumentException("An action's name is a word: '" + name + "'");
        }
        this.group = Objects.requireNonNull(group, "group");
        this.name = name;
        this.defaults = List.of(defaults);
        for (Binding binding : defaults) {
            check(binding);
        }
    }

    /** Throws if {@code binding} cannot drive this action. */
    @AllowAllocation("checking a binding")
    void check(Binding binding) {
        int dims = binding.dimensions();
        if (binding.shape() == Binding.Shape.ACTION || binding.shape() == Binding.Shape.INERT) {
            return;
        }
        boolean pose = binding.shape() == Binding.Shape.SINGLE && dims == 0;
        if (pose != (this instanceof PoseAction)) {
            throw new IllegalArgumentException(fullName() + ": " + (pose ? "a pose drives pose actions only: "
                : "a pose action is bound to a pose: ") + binding);
        }
        if ((binding.toggles() || binding.timing() != Binding.Timing.PRESS) && !(this instanceof BinaryAction)) {
            throw new IllegalArgumentException(fullName() + ": toggles, taps and holds are for binary actions: "
                + binding);
        }
        if (dimensions() > 0 && dims > dimensions()) {
            throw new IllegalArgumentException(fullName() + " has " + dimensions() + " components, " + binding
                + " gives " + dims);
        }
    }

    /** Its group. */
    public ActionGroup group() {
        return group;
    }

    /** Its name in its group. */
    public String name() {
        return name;
    }

    /** Its group's path and its name: {@code game.vehicle.boost}; names its bindings setting. */
    @AllowAllocation("a name is a string")
    public String fullName() {
        return group.path() + "." + name;
    }

    /** The bindings it has unless the game or the player rebinds it. */
    public List<Binding> defaults() {
        return defaults;
    }

    /** How its bindings make one value (D87): {@link Combine#LARGEST} unless set. */
    public Combine combine() {
        return combine;
    }

    /** Makes its bindings combine by {@code rule}; set where it is declared. */
    public Action combine(Combine rule) {
        this.combine = Objects.requireNonNull(rule, "rule");
        return this;
    }

    /** Its value's components: 0 binary, 1 to 3 for axes, 7 for a pose (position, orientation). */
    abstract int dimensions();

    @Override
    @AllowAllocation("describing")
    public String toString() {
        return fullName();
    }
}
