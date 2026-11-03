package com.truesim.engine.input;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.platform.GamepadAxis;
import com.truesim.engine.platform.GamepadButton;
import com.truesim.engine.platform.Key;
import com.truesim.engine.platform.MouseButton;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Function;

/**
 * One way to drive an action (application-api.md D58, D69–D83): its controls, through an adapter where they are not
 * the action's own type, held with modifiers, pressed, tapped, held or toggled, through a chain of filters, from any
 * of the player's devices or from one captured device. Immutable; its text ({@link #toString()}) is how the bindings
 * setting saves it, and {@link #parse} reads it back:
 *
 * <pre>
 * key:SPACE                                     a key
 * key:CONTROL+key:S                             a chord: either control key held, then S (D69, D71)
 * pad:LEFT_SHOULDER+pad:SOUTH                   any pad button as a modifier
 * key:E tap   key:E hold(0.5)                   waits: a tap, or held past the hold time (D70)
 * key:LEFT_CONTROL toggle                       a press switches it on, the next off (D79)
 * axis(key:S,key:W)                             two buttons as an axis: -1, 0, 1 (D81, adapters)
 * 2d(key:A,key:D,key:S,key:W)                   four buttons as a 2D axis: left, right, down, up
 * 2d(pad:LEFT_X,pad:LEFT_Y) axes(1,-1) radial(0.15,1)   a stick, up positive, with a dead zone (D82)
 * action(game.move)                             another action's value (D80)
 * pad:SOUTH device(030000005e0400001202000000007200,,)  only the device captured (D72)
 * </pre>
 *
 * A binary action's chord, held, takes its controls from the bindings with fewer modifiers that frame (D69: the most
 * specific binding wins, chords suppress their parts): with Ctrl+S held, S does not also walk back and Ctrl does not
 * crouch, and Ctrl+Shift+S takes them from Ctrl+S. A modifier on an analog binding only gates it (the right mouse
 * button held to look with the mouse) and takes nothing.
 *
 * <p>Bindings in game code name generic controls ({@link Controls}); a specific device is recorded only when a player
 * captures one ({@link Capture}).
 */
public final class Binding {

    /** How its controls make the value. */
    public enum Shape {
        /** One control: its value. */
        SINGLE,
        /** Two buttons or half axes: the second's value less the first's. */
        AXIS,
        /** Four, as left, right, down, up: a 2D vector. */
        BUTTONS_2D,
        /** Two axes, as x and y. */
        AXES_2D,
        /** Six, as left, right, down, up, back, forward: a 3D vector. */
        BUTTONS_3D,
        /** Three axes, as x, y and z. */
        AXES_3D,
        /** Another action's value (D80). */
        ACTION,
        /** Of a kind of device or a filter not present (another plugin's, another version's): kept, does nothing. */
        INERT
    }

    /** When a binary binding is down (D70). */
    public enum Timing {
        /** At once, while its controls are down: the default. */
        PRESS,
        /** For one frame, when its controls are released before the hold time: a tap. */
        TAP,
        /** Once its controls have been down for the hold time, while they stay down. */
        HOLD
    }

    private static final Control[] NONE = new Control[0];
    private static final FilterType.Entry[] NO_FILTERS = new FilterType.Entry[0];

    private final Shape shape;
    private final Control[] inputs;
    private final String source; // the action's full name (ACTION), or the text (INERT)
    private final Control[] modifiers;
    private final boolean toggle;
    private final Timing timing;
    private final float holdSeconds; // 0: the setting
    private final FilterType.Entry[] filters;
    private final DeviceIdentity device; // null: any of the player's

    private Binding(Shape shape, Control[] inputs, String source, Control[] modifiers, boolean toggle, Timing timing,
        float holdSeconds, FilterType.Entry[] filters, DeviceIdentity device) {
        this.shape = shape;
        this.inputs = inputs;
        this.source = source;
        this.modifiers = modifiers;
        this.toggle = toggle;
        this.timing = timing;
        this.holdSeconds = holdSeconds;
        this.filters = filters;
        this.device = device;
    }

    private static Binding of(Shape shape, Control... inputs) {
        for (Control input : inputs) {
            Objects.requireNonNull(input, "input");
            if (input.kind() == ControlKind.POSE && (shape != Shape.SINGLE)) {
                throw new IllegalArgumentException("A pose is bound alone: " + input);
            }
        }
        return new Binding(shape, inputs.clone(), null, NONE, false, Timing.PRESS, 0, NO_FILTERS, null);
    }

    // ---------------------------------------------------------------- made in code

    /** {@code control}'s value. */
    public static Binding of(Control control) {
        return of(Shape.SINGLE, control);
    }

    /** {@code main} held with {@code modifiers}: a chord (D69, D71), e.g. {@code chord(Controls.CONTROL, key(S))}. */
    public static Binding chord(Control modifier, Control main) {
        return of(main).with(modifier);
    }

    /** A key. */
    public static Binding key(Key key) {
        return of(Controls.key(key));
    }

    /** A mouse button. */
    public static Binding mouse(MouseButton button) {
        return of(Controls.mouse(button));
    }

    /** A gamepad button. */
    public static Binding pad(GamepadButton button) {
        return of(Controls.pad(button));
    }

    /** A gamepad axis. */
    public static Binding pad(GamepadAxis axis) {
        return of(Controls.pad(axis));
    }

    /** Two buttons (or half axes) as an axis: {@code positive}'s value less {@code negative}'s. */
    public static Binding axis(Control negative, Control positive) {
        return of(Shape.AXIS, negative, positive);
    }

    /** Four buttons as a 2D axis, x right and y up: W A S D is {@code buttons2d(key(A), key(D), key(S), key(W))}. */
    public static Binding buttons2d(Control left, Control right, Control down, Control up) {
        return of(Shape.BUTTONS_2D, left, right, down, up);
    }

    /** Four keys as a 2D axis: left, right, down, up. */
    public static Binding keys(Key left, Key right, Key down, Key up) {
        return buttons2d(Controls.key(left), Controls.key(right), Controls.key(down), Controls.key(up));
    }

    /** Two axes as a 2D axis. */
    public static Binding axes2d(Control x, Control y) {
        return of(Shape.AXES_2D, x, y);
    }

    /**
     * A gamepad's stick, x right and y up (the pad reports y down: inverted here), with a radial dead zone of 0.15:
     * {@code right} the right stick, else the left.
     */
    public static Binding stick(boolean right) {
        return axes2d(Controls.pad(right ? GamepadAxis.RIGHT_X : GamepadAxis.LEFT_X),
            Controls.pad(right ? GamepadAxis.RIGHT_Y : GamepadAxis.LEFT_Y)).filter(Filters.AXES, 1, -1, 1)
            .filter(Filters.RADIAL, 0.15f, 1);
    }

    /** Six buttons as a 3D axis: left, right, down, up, back, forward. */
    public static Binding buttons3d(Control left, Control right, Control down, Control up, Control back,
        Control forward) {
        return of(Shape.BUTTONS_3D, left, right, down, up, back, forward);
    }

    /** Three axes as a 3D axis. */
    public static Binding axes3d(Control x, Control y, Control z) {
        return of(Shape.AXES_3D, x, y, z);
    }

    /** Another action's value (D80): a binary one's is 1 while it is down. Cycles are rejected. */
    public static Binding action(Action source) {
        return new Binding(Shape.ACTION, NONE, source.fullName(), NONE, false, Timing.PRESS, 0, NO_FILTERS, null);
    }

    /** This, held with {@code modifier} too: a chord. */
    public Binding with(Control modifier) {
        Objects.requireNonNull(modifier, "modifier");
        Control[] more = Arrays.copyOf(modifiers, modifiers.length + 1);
        more[modifiers.length] = modifier;
        return new Binding(shape, inputs, source, more, toggle, timing, holdSeconds, filters, device);
    }

    /** This as a toggle: a press switches the action on, the next off (D79). For binary actions. */
    public Binding toggle() {
        return new Binding(shape, inputs, source, modifiers, true, timing, holdSeconds, filters, device);
    }

    /** This as a tap: down for one frame when released before the hold time (D70). */
    public Binding tap() {
        return new Binding(shape, inputs, source, modifiers, toggle, Timing.TAP, 0, filters, device);
    }

    /** This as a hold: down once held for the hold time (the setting {@link InputSettings#HOLD_TIME}). */
    public Binding hold() {
        return new Binding(shape, inputs, source, modifiers, toggle, Timing.HOLD, 0, filters, device);
    }

    /** This with its own hold time, seconds, for a tap or a hold. */
    public Binding holdTime(float seconds) {
        if (!(seconds > 0)) {
            throw new IllegalArgumentException("A hold time is positive: " + seconds);
        }
        return new Binding(shape, inputs, source, modifiers, toggle, timing, seconds, filters, device);
    }

    /** This with filter {@code type} added at the chain's end, with {@code params} (trailing ones: defaults). */
    public Binding filter(FilterType type, float... params) {
        FilterType.Entry[] more = Arrays.copyOf(filters, filters.length + 1);
        more[filters.length] = new FilterType.Entry(type, type.complete(params));
        return new Binding(shape, inputs, source, modifiers, toggle, timing, holdSeconds, more, device);
    }

    /** This with parameter {@code param} of filter {@code filter} (both from 0) set to {@code value}: D83's adjusting. */
    public Binding filterParameter(int filter, int param, float value) {
        FilterType.Entry[] changed = filters.clone();
        float[] params = changed[filter].params().clone();
        params[param] = value;
        changed[filter] = new FilterType.Entry(changed[filter].type(), params);
        return new Binding(shape, inputs, source, modifiers, toggle, timing, holdSeconds, changed, device);
    }

    /** This without filters. */
    public Binding unfiltered() {
        return new Binding(shape, inputs, source, modifiers, toggle, timing, holdSeconds, NO_FILTERS, device);
    }

    /** This, from {@code only} that device (by D72's matching); null: any of the player's devices. */
    public Binding device(DeviceIdentity only) {
        return new Binding(shape, inputs, source, modifiers, toggle, timing, holdSeconds, filters, only);
    }

    // ---------------------------------------------------------------- reading

    public Shape shape() {
        return shape;
    }

    /** Its controls, in the adapter's order (none for {@link Shape#ACTION}). */
    public List<Control> inputs() {
        return List.of(inputs);
    }

    /** The modifiers held with it. */
    public List<Control> modifiers() {
        return List.of(modifiers);
    }

    /** The source action's full name, for {@link Shape#ACTION}; else null. */
    public String source() {
        return shape == Shape.ACTION ? source : null;
    }

    public boolean toggles() {
        return toggle;
    }

    public Timing timing() {
        return timing;
    }

    /** Its own hold time, seconds; 0 when the setting decides. */
    public float holdSeconds() {
        return holdSeconds;
    }

    /** Its filters' count. */
    public int filterCount() {
        return filters.length;
    }

    /** Filter {@code i}'s type. */
    public FilterType filterType(int i) {
        return filters[i].type();
    }

    /** Filter {@code i}'s parameter {@code p}. */
    public float filterParameter(int i, int p) {
        return filters[i].params()[p];
    }

    FilterType.Entry[] filters() {
        return filters;
    }

    Control[] inputArray() {
        return inputs;
    }

    Control[] modifierArray() {
        return modifiers;
    }

    /** The device captured, or null: any of the player's. */
    public DeviceIdentity device() {
        return device;
    }

    /** How many components its value has: 1 for a control, 2 or 3 for vectors, 0 for a pose or an action's. */
    int dimensions() {
        return switch (shape) {
            case SINGLE -> inputs[0].kind() == ControlKind.POSE ? 0 : 1;
            case AXIS -> 1;
            case BUTTONS_2D, AXES_2D -> 2;
            case BUTTONS_3D, AXES_3D -> 3;
            case ACTION, INERT -> 0;
        };
    }

    // ---------------------------------------------------------------- text

    /** As the bindings setting saves it. */
    @Override
    @AllowAllocation("saving bindings")
    public String toString() {
        if (shape == Shape.INERT) {
            return source;
        }
        StringBuilder text = new StringBuilder();
        for (Control modifier : modifiers) {
            text.append(modifier).append('+');
        }
        switch (shape) {
            case SINGLE -> text.append(inputs[0]);
            case ACTION -> text.append("action(").append(source).append(')');
            default -> {
                text.append(switch (shape) {
                    case AXIS -> "axis(";
                    case BUTTONS_2D, AXES_2D -> "2d(";
                    default -> "3d(";
                });
                for (int i = 0; i < inputs.length; i++) {
                    text.append(i > 0 ? "," : "").append(inputs[i]);
                }
                text.append(')');
            }
        }
        if (toggle) {
            text.append(" toggle");
        }
        if (timing != Timing.PRESS) {
            text.append(' ').append(timing.name().toLowerCase(Locale.ROOT));
            if (holdSeconds > 0) {
                text.append('(').append(holdSeconds).append(')');
            }
        } else if (holdSeconds > 0) {
            text.append(" holdTime(").append(holdSeconds).append(')');
        }
        for (FilterType.Entry filter : filters) {
            text.append(' ').append(filter);
        }
        if (device != null) {
            text.append(" device(").append(device.text()).append(')');
        }
        return text.toString();
    }

    @Override
    @AllowAllocation("comparing bindings")
    public boolean equals(Object o) {
        return o instanceof Binding b && b.toString().equals(toString());
    }

    @Override
    @AllowAllocation("comparing bindings")
    public int hashCode() {
        return toString().hashCode();
    }

    /** {@code bindings} as one setting's text: each {@link #toString()}, separated by {@code ;}. */
    @AllowAllocation("saving bindings")
    public static String format(List<Binding> bindings) {
        StringBuilder text = new StringBuilder();
        for (Binding binding : bindings) {
            text.append(text.isEmpty() ? "" : "; ").append(binding);
        }
        return text.toString();
    }

    /** What parsing needs: the kinds of devices and the filters by their saved names; null when not present. */
    interface Names {
        DeviceClass deviceClass(String prefix);

        FilterType filterType(String id);
    }

    /**
     * Reads {@link #format}'s text: the bindings, those naming a kind of device or a filter not present kept as
     * {@link Shape#INERT}. Throws on text that is not bindings.
     */
    @AllowAllocation("parsing bindings")
    static List<Binding> parseAll(String text, Names names) {
        List<Binding> bindings = new ArrayList<>();
        for (String part : text.split(";")) {
            if (!part.isBlank()) {
                bindings.add(parse(part.strip(), names));
            }
        }
        return bindings;
    }

    @AllowAllocation("parsing bindings")
    static Binding parse(String text, Names names) {
        try {
            return parseKnown(text, names);
        } catch (Unknown e) {
            return new Binding(Shape.INERT, NONE, text, NONE, false, Timing.PRESS, 0, NO_FILTERS, null);
        }
    }

    /** A kind of device or a filter not present: the binding is kept, inert. */
    private static final class Unknown extends RuntimeException {
        private static final long serialVersionUID = 1;

        Unknown() {
            super(null, null, false, false);
        }
    }

    @AllowAllocation("parsing bindings")
    private static Binding parseKnown(String text, Names names) {
        List<String> words = words(text);
        if (words.isEmpty()) {
            throw new IllegalArgumentException("An empty binding");
        }
        String head = words.getFirst();
        List<String> chord = splitTop(head, '+');
        Binding binding = main(chord.getLast(), names);
        for (int i = chord.size() - 2; i >= 0; i--) {
            Control modifier = control(chord.get(i), names);
            binding = binding.withModifierFirst(modifier);
        }
        for (String option : words.subList(1, words.size())) {
            binding = binding.option(option, names);
        }
        return binding;
    }

    private Binding withModifierFirst(Control modifier) {
        Control[] more = new Control[modifiers.length + 1];
        more[0] = modifier;
        System.arraycopy(modifiers, 0, more, 1, modifiers.length);
        return new Binding(shape, inputs, source, more, toggle, timing, holdSeconds, filters, device);
    }

    @AllowAllocation("parsing bindings")
    private static Binding main(String text, Names names) {
        int open = text.indexOf('(');
        if (open < 0) {
            return of(control(text, names));
        }
        String adapter = text.substring(0, open).toLowerCase(Locale.ROOT);
        String inside = arguments(text, open);
        if (adapter.equals("action")) {
            return new Binding(Shape.ACTION, NONE, inside.strip(), NONE, false, Timing.PRESS, 0, NO_FILTERS, null);
        }
        Function<String, Control> read = word -> control(word, names);
        Control[] controls = splitTop(inside, ',').stream().map(read).toArray(Control[]::new);
        Shape shape = switch (adapter + controls.length) {
            case "axis2" -> Shape.AXIS;
            case "2d4" -> Shape.BUTTONS_2D;
            case "2d2" -> Shape.AXES_2D;
            case "3d6" -> Shape.BUTTONS_3D;
            case "3d3" -> Shape.AXES_3D;
            default -> throw new IllegalArgumentException("Not an adapter: '" + text
                + "' (axis of 2 controls, 2d of 4 or 2, 3d of 6 or 3, action of a name)");
        };
        return of(shape, controls);
    }

    @AllowAllocation("parsing bindings")
    private Binding option(String text, Names names) {
        int open = text.indexOf('(');
        String word = (open < 0 ? text : text.substring(0, open)).toLowerCase(Locale.ROOT);
        String inside = open < 0 ? null : arguments(text, open);
        switch (word) {
            case "toggle" -> {
                return toggle();
            }
            case "tap", "hold" -> {
                Binding timed = word.equals("tap") ? tap() : hold();
                return inside == null ? timed : timed.holdTime(Float.parseFloat(inside.strip()));
            }
            case "holdtime" -> {
                return holdTime(Float.parseFloat(String.valueOf(inside).strip()));
            }
            case "device" -> {
                return device(DeviceIdentity.parse(String.valueOf(inside)));
            }
            default -> {
                FilterType type = names.filterType(open < 0 ? text : text.substring(0, open));
                if (type == null) {
                    if (text.matches("[a-zA-Z][a-zA-Z0-9_]*(\\(.*\\))?")) {
                        throw new Unknown(); // a filter not registered here: another plugin's or version's
                    }
                    throw new IllegalArgumentException("Not an option: '" + text + "'");
                }
                float[] params = new float[0];
                if (inside != null && !inside.isBlank()) {
                    List<String> values = splitTop(inside, ',');
                    params = new float[values.size()];
                    for (int i = 0; i < params.length; i++) {
                        params[i] = Float.parseFloat(values.get(i).strip());
                    }
                }
                return filter(type, params);
            }
        }
    }

    @AllowAllocation("parsing bindings")
    private static Control control(String text, Names names) {
        String word = text.strip();
        int colon = word.indexOf(':');
        if (colon <= 0) {
            throw new IllegalArgumentException("A control is prefix:NAME: '" + word + "'");
        }
        DeviceClass type = names.deviceClass(word.substring(0, colon).toLowerCase(Locale.ROOT));
        if (type == null) {
            throw new Unknown();
        }
        Control control = type.control(word.substring(colon + 1));
        if (control == null) {
            throw new IllegalArgumentException(type + " has no control " + word.substring(colon + 1));
        }
        return control;
    }

    @AllowAllocation("parsing bindings")
    private static String arguments(String text, int open) {
        if (!text.endsWith(")")) {
            throw new IllegalArgumentException("Unclosed: '" + text + "'");
        }
        return text.substring(open + 1, text.length() - 1);
    }

    /** {@code text} split at spaces outside parentheses. */
    @AllowAllocation("parsing bindings")
    private static List<String> words(String text) {
        List<String> words = new ArrayList<>();
        int depth = 0;
        StringBuilder word = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            depth += c == '(' ? 1 : c == ')' ? -1 : 0;
            if (Character.isWhitespace(c) && depth == 0) {
                if (!word.isEmpty()) {
                    words.add(word.toString());
                    word.setLength(0);
                }
            } else {
                word.append(c);
            }
        }
        if (!word.isEmpty()) {
            words.add(word.toString());
        }
        return words;
    }

    /** {@code text} split at {@code separator} outside parentheses. */
    @AllowAllocation("parsing bindings")
    private static List<String> splitTop(String text, char separator) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            depth += c == '(' ? 1 : c == ')' ? -1 : 0;
            if (c == separator && depth == 0) {
                parts.add(text.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(text.substring(start));
        return parts;
    }
}
