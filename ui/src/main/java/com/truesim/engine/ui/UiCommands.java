package com.truesim.engine.ui;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.input.BinaryAction;
import com.truesim.engine.input.Binding;
import com.truesim.engine.input.Input;
import com.truesim.engine.input.InputContext;
import com.truesim.engine.input.Player;
import com.truesim.engine.input.TextListener;
import com.truesim.engine.platform.GamepadButton;
import com.truesim.engine.platform.Key;
import com.truesim.engine.platform.MouseButton;
import com.truesim.engine.settings.EngineSettings;
import com.truesim.engine.settings.Setting;
import com.truesim.ui.core.input.UiCommand;
import com.truesim.ui.core.input.UiCommandSource;
import com.truesim.ui.core.input.UiInput;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleSupplier;

/**
 * The {@link UiActions} as ui-core's {@link UiCommand}s (design UI6): each frame, at its start after input sampled the
 * devices, {@link #poll} adds every player's commands of the frame to the UI's input: one per press of an action,
 * more while a repeating one is held (navigation and caret moves, deletes: after {@link #REPEAT_DELAY}, at
 * {@link #REPEAT_RATE}), with {@link UiInput#EXTEND_SELECTION} on moves while the selection extends (Shift). Nothing
 * waits a frame: the UI updates and records in the frame the input was sampled in.
 *
 * <p><b>Text.</b> A focused text field calls {@link #startText}: a context taking the keyboard is pushed (keys type,
 * they fire no game actions) and the {@code ui.text} group is switched on with it, from the next frame's sampling;
 * {@link #stopText} undoes both. While text is taken, Home, End and Enter give the text commands (line start, line
 * end, submit) instead of the navigation ones, and Space types rather than accepts.
 *
 * <p><b>The other groups.</b> Tabs ({@code NEXT_TAB}, {@code PREVIOUS_TAB} repeating, {@code CLOSE_TAB}), docking's
 * window keys ({@code NEXT_WINDOW}, {@code WINDOW_MOVE}, {@code WINDOW_RESIZE}) and the console's toggle work while
 * typing too (the toggle's key types nothing: {@link UiTextInput}); the chart reset and the gizmo's switches (modes,
 * spaces, pivots) only while not ({@code W} types). The gizmo's switches share the d-pad with the focus moves: a
 * focused gizmo ({@link #gizmo}) takes it, else navigation does. {@code COMPLETE}, {@code HISTORY_UP} and
 * {@code HISTORY_DOWN} come only while the console's command line has the text ({@link #console}), in place of
 * {@code NEXT}, {@code UP} and {@code DOWN} from the same keys. Mnemonics and pointers' modifiers come from
 * {@link UiKeyboard}.
 *
 * <p><b>Confirm button</b> (UI29): {@link #CONFIRM} says which gamepad button accepts, south or east, or what the
 * platform does ({@link Confirm#PLATFORM}: south on Windows, Linux and macOS); cancel is the other. It sets the
 * game defaults of {@link UiActions#ACCEPT}, {@link UiActions#CANCEL} and the gizmo's {@link UiActions#CANCEL_DRAG},
 * so a player's own bindings stay.
 *
 * <pre>{@code
 * UiCommands commands = new UiCommands(input, engine.settings(), () -> frame.realTime());
 * registry.add(group, UiCommands.ALL.toArray(new Setting<?>[0]));
 * // each frame, before the UI updates:
 * uiInput.beginFrame();
 * commands.poll(uiInput);
 * }</pre>
 *
 * <p>Polling allocates nothing.
 */
public final class UiCommands implements UiCommandSource, AutoCloseable {

    /** Which gamepad button accepts (UI29). */
    public enum Confirm {
        /** As the platform does: south on the desktop platforms (Windows, Linux, macOS). */
        PLATFORM,
        /** The bottom button (Xbox A, PlayStation cross): accept; east cancels. */
        SOUTH,
        /** The right button (Nintendo A, Japanese PlayStation circle): accept; south cancels. */
        EAST
    }

    /** Which gamepad button accepts. */
    public static final Setting<Confirm> CONFIRM = Setting.choice("ui.confirmButton", Confirm.PLATFORM,
        "which gamepad button accepts in menus: PLATFORM (as the system does), SOUTH or EAST; the other cancels");
    /** How long a held key or button waits before it repeats. */
    public static final Setting<Float> REPEAT_DELAY = Setting.decimal("ui.repeatDelay", 0.4f, 0.1f, 2,
        "seconds a held navigation key or button waits before it repeats");
    /** How often a held key or button repeats. */
    public static final Setting<Float> REPEAT_RATE = Setting.decimal("ui.repeatRate", 20, 1, 60,
        "repeats a second of a held navigation key or button");
    /** The settings the UI's input has. */
    public static final List<Setting<?>> ALL = List.of(CONFIRM, REPEAT_DELAY, REPEAT_RATE);

    /** The priority of the text context: over the game's and the UI's own. */
    public static final int TEXT_PRIORITY = 1000;

    private static final int REPEATS = 1;     // the action repeats while held
    private static final int TEXT_ONLY = 2;   // only while text is taken
    private static final int NOT_TEXT = 4;    // not while text is taken (its text twin is given instead)
    private static final int MOVE = 8;        // moves a caret or a selection: extends with Shift
    private static final int CONSOLE_LINE = 16; // only while the console's command line takes the text
    private static final int NAV_MOVE = 32;   // a focus move: none while a focused gizmo's switch is held (the d-pad)
    private static final int GIZMO_SWITCH = 64; // a gizmo's switch: none with a focus move pressed, unless focused

    // The mapping: action, command, flags.
    private static final BinaryAction[] ACTIONS = {
        UiActions.UP, UiActions.DOWN, UiActions.LEFT, UiActions.RIGHT, UiActions.NEXT, UiActions.PREVIOUS,
        UiActions.PAGE_UP, UiActions.PAGE_DOWN, UiActions.HOME, UiActions.END, UiActions.ACCEPT, UiActions.CANCEL,
        UiActions.CONTEXT_MENU, UiActions.MENU,
        UiActions.SELECT_ALL, UiActions.COPY, UiActions.CUT, UiActions.PASTE, UiActions.UNDO, UiActions.REDO,
        UiActions.DELETE_BACK, UiActions.DELETE_FORWARD, UiActions.DELETE_WORD_BACK, UiActions.DELETE_WORD_FORWARD,
        UiActions.WORD_LEFT, UiActions.WORD_RIGHT, UiActions.LINE_START, UiActions.LINE_END, UiActions.DOC_START,
        UiActions.DOC_END, UiActions.SUBMIT, UiActions.NEWLINE,
        UiActions.NEXT_TAB, UiActions.PREVIOUS_TAB, UiActions.CLOSE_TAB, UiActions.RESET_VIEW,
        UiActions.TRANSLATE, UiActions.ROTATE, UiActions.SCALE, UiActions.UNIVERSAL, UiActions.TOGGLE_SPACE,
        UiActions.CANCEL_DRAG, UiActions.BOUNDS, UiActions.NEXT_MODE, UiActions.PREVIOUS_SPACE, UiActions.NEXT_PIVOT,
        UiActions.PREVIOUS_PIVOT, UiActions.NEXT_WINDOW, UiActions.WINDOW_MOVE, UiActions.WINDOW_RESIZE,
        UiActions.TOGGLE_CONSOLE, UiActions.COMPLETE, UiActions.HISTORY_UP, UiActions.HISTORY_DOWN,
    };
    private static final UiCommand[] COMMANDS = {
        UiCommand.UP, UiCommand.DOWN, UiCommand.LEFT, UiCommand.RIGHT, UiCommand.NEXT, UiCommand.PREVIOUS,
        UiCommand.PAGE_UP, UiCommand.PAGE_DOWN, UiCommand.HOME, UiCommand.END, UiCommand.ACCEPT, UiCommand.CANCEL,
        UiCommand.CONTEXT_MENU, UiCommand.MENU,
        UiCommand.SELECT_ALL, UiCommand.COPY, UiCommand.CUT, UiCommand.PASTE, UiCommand.UNDO, UiCommand.REDO,
        UiCommand.DELETE_BACK, UiCommand.DELETE_FORWARD, UiCommand.DELETE_WORD_BACK, UiCommand.DELETE_WORD_FORWARD,
        UiCommand.WORD_LEFT, UiCommand.WORD_RIGHT, UiCommand.LINE_START, UiCommand.LINE_END, UiCommand.DOC_START,
        UiCommand.DOC_END, UiCommand.SUBMIT, UiCommand.NEWLINE,
        UiCommand.NEXT_TAB, UiCommand.PREVIOUS_TAB, UiCommand.CLOSE_TAB, UiCommand.CHART_RESET,
        UiCommand.GIZMO_TRANSLATE, UiCommand.GIZMO_ROTATE, UiCommand.GIZMO_SCALE, UiCommand.GIZMO_UNIVERSAL,
        UiCommand.GIZMO_TOGGLE_SPACE, UiCommand.GIZMO_CANCEL, UiCommand.GIZMO_BOUNDS, UiCommand.GIZMO_NEXT_MODE,
        UiCommand.GIZMO_PREVIOUS_SPACE, UiCommand.GIZMO_NEXT_PIVOT, UiCommand.GIZMO_PREVIOUS_PIVOT,
        UiCommand.NEXT_WINDOW, UiCommand.WINDOW_MOVE, UiCommand.WINDOW_RESIZE, UiCommand.CONSOLE_TOGGLE,
        UiCommand.COMPLETE, UiCommand.HISTORY_UP, UiCommand.HISTORY_DOWN,
    };
    private static final int R = REPEATS;
    private static final int D = NAV_MOVE;
    private static final int G = NOT_TEXT | GIZMO_SWITCH;
    private static final int[] FLAGS = {
        R | MOVE | D, R | MOVE | D, R | MOVE | D, R | MOVE | D, R, R, R | MOVE, R | MOVE, MOVE | NOT_TEXT,
        MOVE | NOT_TEXT, NOT_TEXT, 0, 0, 0,
        TEXT_ONLY, TEXT_ONLY, TEXT_ONLY, TEXT_ONLY, TEXT_ONLY | R, TEXT_ONLY | R,
        TEXT_ONLY | R, TEXT_ONLY | R, TEXT_ONLY | R, TEXT_ONLY | R,
        TEXT_ONLY | R | MOVE, TEXT_ONLY | R | MOVE, TEXT_ONLY | MOVE, TEXT_ONLY | MOVE, TEXT_ONLY | MOVE,
        TEXT_ONLY | MOVE, TEXT_ONLY, TEXT_ONLY | R,
        R, R, 0, NOT_TEXT,
        G, G, G, G, G,
        0, G, G, G, G, G, 0, 0, 0,
        0, CONSOLE_LINE, CONSOLE_LINE | R, CONSOLE_LINE | R,
    };
    /** When a mapping's twin, held, takes its place: in the console's command line, or with the virtual cursor on. */
    private static final int IN_CONSOLE = 1;
    private static final int WITH_CURSOR = 2;
    // Per mapping: the twin action (null: none) and when it takes the place. In the console's line Tab completes, Up
    // and Down walk the history (the console reads UP and DOWN as the history too: one command, not two); the virtual
    // cursor's click is no accept or cancel.
    private static final BinaryAction[] TWINS = new BinaryAction[ACTIONS.length];
    private static final int[] TWIN_WHEN = new int[ACTIONS.length];

    static {
        twin(UiActions.NEXT, UiActions.COMPLETE, IN_CONSOLE);
        twin(UiActions.UP, UiActions.HISTORY_UP, IN_CONSOLE);
        twin(UiActions.DOWN, UiActions.HISTORY_DOWN, IN_CONSOLE);
        twin(UiActions.ACCEPT, UiActions.CURSOR_CLICK, WITH_CURSOR);
        twin(UiActions.CANCEL, UiActions.CURSOR_CLICK, WITH_CURSOR);
    }

    private static void twin(BinaryAction action, BinaryAction twin, int when) {
        int m = Arrays.asList(ACTIONS).indexOf(action);
        TWINS[m] = twin;
        TWIN_WHEN[m] = when;
    }

    /** The command {@code action} gives, or null (tests). */
    static UiCommand commandOf(BinaryAction action) {
        int m = Arrays.asList(ACTIONS).indexOf(action);
        return m < 0 ? null : COMMANDS[m];
    }

    private final Input input;
    private final EngineSettings settings;
    private final DoubleSupplier realTime;
    private final InputContext text;
    private final EngineSettings.Subscription confirmWatch;
    private Confirm applied;
    private double[] nextRepeat = new double[0]; // per player and mapping: when it repeats next, or NaN when not held
    private long dropped;
    private BooleanSupplier consoleLine = () -> false;
    private BooleanSupplier cursorOn = () -> false;
    private BooleanSupplier gizmoFocused = () -> false;

    /**
     * Commands from {@code input}'s players, its {@link UiActions} added if they were not; {@code settings} the
     * engine's (the confirm button, repeats), {@code realTime} the frame's real time in seconds (e.g.
     * {@code Frame.realTime()}), which repeats are timed by.
     */
    @AllowAllocation("made once")
    public UiCommands(Input input, EngineSettings settings, DoubleSupplier realTime) {
        this.input = Objects.requireNonNull(input, "input");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.realTime = Objects.requireNonNull(realTime, "realTime");
        if (!input.has(UiActions.UP)) {
            input.add(UiActions.UI);
        }
        input.enable(UiActions.TEXT, false);
        text = input.context("ui.text", TEXT_PRIORITY).groups(UiActions.NAVIGATION, UiActions.TEXT, UiActions.POINTER,
            UiActions.SCROLL, UiActions.TABS, UiActions.DOCKING, UiActions.CONSOLE); // tabs, windows, the console too
        confirmWatch = settings.onChange(CONFIRM, this::applyConfirm);
        applyConfirm();
    }

    /** Commands from {@code input}'s players, timed by the system's clock. */
    public UiCommands(Input input, EngineSettings settings) {
        this(input, settings, () -> System.nanoTime() / 1e9);
    }

    /** The button {@code confirm} names, platform resolved: south or east. */
    public static GamepadButton acceptButton(Confirm confirm) {
        return confirm == Confirm.EAST ? GamepadButton.EAST : GamepadButton.SOUTH;
    }

    /** Sets the game defaults of accept and cancel to the confirm setting's buttons. */
    @AllowAllocation("when the setting changes")
    private void applyConfirm() {
        Confirm confirm = settings.get(CONFIRM);
        if (confirm == applied) {
            return;
        }
        applied = confirm;
        GamepadButton accept = acceptButton(confirm);
        if (accept == GamepadButton.SOUTH) {
            // The actions' own defaults: nothing over them.
            settings.clear(EngineSettings.Layer.GAME, input.bindingSetting(UiActions.ACCEPT));
            settings.clear(EngineSettings.Layer.GAME, input.bindingSetting(UiActions.CANCEL));
            settings.clear(EngineSettings.Layer.GAME, input.bindingSetting(UiActions.CANCEL_DRAG));
            return;
        }
        // The game layer (as Input.defaults sets it, which waits for the engine to start; this may come before).
        settings.set(EngineSettings.Layer.GAME, input.bindingSetting(UiActions.ACCEPT), Binding.format(
            List.of(Binding.key(Key.ENTER), Binding.key(Key.SPACE), Binding.pad(GamepadButton.EAST))));
        settings.set(EngineSettings.Layer.GAME, input.bindingSetting(UiActions.CANCEL), Binding.format(
            List.of(Binding.key(Key.ESCAPE), Binding.pad(GamepadButton.SOUTH))));
        settings.set(EngineSettings.Layer.GAME, input.bindingSetting(UiActions.CANCEL_DRAG), Binding.format(
            List.of(Binding.key(Key.ESCAPE), Binding.mouse(MouseButton.RIGHT), Binding.pad(GamepadButton.SOUTH))));
    }

    /**
     * A text field takes the keyboard: from the next frame's sampling, keys type (into {@code listener}) and fire no
     * game actions, and the {@code ui.text} commands flow.
     */
    @AllowAllocation("focusing a text field")
    public UiCommands startText(TextListener listener) {
        text.takesText(listener);
        if (!text.active()) {
            input.push(text);
        }
        input.enable(UiActions.TEXT, true);
        return this;
    }

    /** The text field lets go of the keyboard. */
    @AllowAllocation("leaving a text field")
    public UiCommands stopText() {
        if (text.active()) {
            input.pop(text);
        }
        text.noText();
        input.enable(UiActions.TEXT, false);
        return this;
    }

    /**
     * Says when the developer console's command line has the text (e.g. {@code () -> ui.interaction().focused(0) ==
     * console.field().element()}): only then do Tab, Up and Down give {@link UiCommand#COMPLETE},
     * {@link UiCommand#HISTORY_UP} and {@link UiCommand#HISTORY_DOWN}, in place of {@code NEXT}, {@code UP} and
     * {@code DOWN}. Asked each frame while text is taken; must not allocate.
     */
    public UiCommands console(BooleanSupplier commandLineFocused) {
        consoleLine = Objects.requireNonNull(commandLineFocused, "commandLineFocused");
        return this;
    }

    /**
     * Says when a gamepad's virtual cursor is on ({@link VirtualCursor}): its click
     * ({@link UiActions#CURSOR_CLICK}) then gives no accept or cancel. Asked each frame; must not allocate.
     */
    public UiCommands virtualCursor(BooleanSupplier on) {
        cursorOn = Objects.requireNonNull(on, "on");
        return this;
    }

    /**
     * Says when a gizmo is focused (e.g. {@code () -> sceneViewFocused}): only then do the gizmo's switches bound to
     * the same controls as focus moves (by default the d-pad: translate up, rotate right, scale down, universal left)
     * switch it, and those moves are not made; otherwise the moves are made and the switches give nothing (the d-pad
     * navigates). Switches on controls of their own (the keys) work either way. Asked each frame; must not allocate.
     */
    public UiCommands gizmo(BooleanSupplier focused) {
        gizmoFocused = Objects.requireNonNull(focused, "focused");
        return this;
    }

    /** The input it reads. */
    Input input() {
        return input;
    }

    /** The context a text field pushes ({@link #startText}). */
    public InputContext textContext() {
        return text;
    }

    /** Adds this frame's commands of every player to {@code ui}. */
    @Override
    public void poll(UiInput ui) {
        int players = input.players();
        int mappings = ACTIONS.length;
        if (nextRepeat.length < players * mappings) {
            growRepeats(players * mappings);
        }
        double now = realTime.getAsDouble();
        double delay = settings.get(REPEAT_DELAY);
        double period = 1.0 / settings.get(REPEAT_RATE);
        boolean typing = input.textContext() == text;
        boolean console = typing && consoleLine.getAsBoolean();
        boolean cursor = cursorOn.getAsBoolean();
        boolean gizmo = gizmoFocused.getAsBoolean();
        for (int p = 0; p < players; p++) {
            Player player = input.player(p);
            boolean extend = typing && player.down(UiActions.EXTEND_SELECTION);
            // A focused gizmo's switch held takes the focus moves of its controls; else a move pressed takes the
            // switches pressed with it (the same d-pad press).
            boolean switching = false;
            boolean moving = false;
            for (int m = 0; m < mappings; m++) {
                if ((FLAGS[m] & GIZMO_SWITCH) != 0 && gizmo) {
                    switching |= player.down(ACTIONS[m]);
                } else if ((FLAGS[m] & NAV_MOVE) != 0 && !gizmo) {
                    moving |= player.presses(ACTIONS[m]) > 0;
                }
            }
            for (int m = 0; m < mappings; m++) {
                int flags = FLAGS[m];
                BinaryAction action = ACTIONS[m];
                int at = p * mappings + m;
                int when = TWIN_WHEN[m];
                if ((flags & TEXT_ONLY) != 0 && !typing || (flags & NOT_TEXT) != 0 && typing
                    || (flags & CONSOLE_LINE) != 0 && !console
                    || when != 0 && (when == IN_CONSOLE ? console : cursor) && player.down(TWINS[m])
                    || (flags & NAV_MOVE) != 0 && switching || (flags & GIZMO_SWITCH) != 0 && moving) {
                    nextRepeat[at] = Double.NaN;
                    continue;
                }
                int commandFlags = extend && (flags & MOVE) != 0 ? UiInput.EXTEND_SELECTION : 0;
                int presses = player.presses(action);
                for (int i = 0; i < presses; i++) {
                    add(ui, COMMANDS[m], p, commandFlags);
                }
                if ((flags & REPEATS) == 0) {
                    continue;
                }
                if (!player.down(action)) {
                    nextRepeat[at] = Double.NaN;
                } else if (presses > 0 || Double.isNaN(nextRepeat[at])) {
                    nextRepeat[at] = now + delay; // pressed (or held when it came on): repeats after the delay
                } else {
                    // At most a frame's worth of repeats: a long frame does not flood the UI.
                    int repeats = 0;
                    while (now >= nextRepeat[at] && repeats < 4) {
                        add(ui, COMMANDS[m], p, commandFlags);
                        nextRepeat[at] += period;
                        repeats++;
                    }
                    if (now >= nextRepeat[at]) {
                        nextRepeat[at] = now + period;
                    }
                }
            }
        }
    }

    private void add(UiInput ui, UiCommand command, int player, int flags) {
        if (!ui.command(command, player, flags)) {
            dropped++;
        }
    }

    @AllowAllocation("a player added")
    private void growRepeats(int length) {
        int before = nextRepeat.length;
        nextRepeat = Arrays.copyOf(nextRepeat, length);
        Arrays.fill(nextRepeat, before, length, Double.NaN);
    }

    /** Commands dropped because the UI's input had no room left in a frame. */
    public long dropped() {
        return dropped;
    }

    /** Stops following the confirm setting and lets go of the keyboard. */
    @Override
    public void close() {
        confirmWatch.close();
        stopText();
    }
}
