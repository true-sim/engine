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
import com.truesim.engine.settings.EngineSettings;
import com.truesim.engine.settings.Setting;
import com.truesim.ui.core.input.UiCommand;
import com.truesim.ui.core.input.UiCommandSource;
import com.truesim.ui.core.input.UiInput;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
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
 * <p><b>Confirm button</b> (UI29): {@link #CONFIRM} says which gamepad button accepts, south or east, or what the
 * platform does ({@link Confirm#PLATFORM}: south on Windows, Linux and macOS); cancel is the other. It sets the
 * game defaults of {@link UiActions#ACCEPT} and {@link UiActions#CANCEL}, so a player's own bindings stay.
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

    // The mapping: action, command, flags.
    private static final BinaryAction[] ACTIONS = {
        UiActions.UP, UiActions.DOWN, UiActions.LEFT, UiActions.RIGHT, UiActions.NEXT, UiActions.PREVIOUS,
        UiActions.PAGE_UP, UiActions.PAGE_DOWN, UiActions.HOME, UiActions.END, UiActions.ACCEPT, UiActions.CANCEL,
        UiActions.CONTEXT_MENU, UiActions.MENU,
        UiActions.SELECT_ALL, UiActions.COPY, UiActions.CUT, UiActions.PASTE, UiActions.UNDO, UiActions.REDO,
        UiActions.DELETE_BACK, UiActions.DELETE_FORWARD, UiActions.DELETE_WORD_BACK, UiActions.DELETE_WORD_FORWARD,
        UiActions.WORD_LEFT, UiActions.WORD_RIGHT, UiActions.LINE_START, UiActions.LINE_END, UiActions.DOC_START,
        UiActions.DOC_END, UiActions.SUBMIT, UiActions.NEWLINE,
    };
    private static final UiCommand[] COMMANDS = {
        UiCommand.UP, UiCommand.DOWN, UiCommand.LEFT, UiCommand.RIGHT, UiCommand.NEXT, UiCommand.PREVIOUS,
        UiCommand.PAGE_UP, UiCommand.PAGE_DOWN, UiCommand.HOME, UiCommand.END, UiCommand.ACCEPT, UiCommand.CANCEL,
        UiCommand.CONTEXT_MENU, UiCommand.MENU,
        UiCommand.SELECT_ALL, UiCommand.COPY, UiCommand.CUT, UiCommand.PASTE, UiCommand.UNDO, UiCommand.REDO,
        UiCommand.DELETE_BACK, UiCommand.DELETE_FORWARD, UiCommand.DELETE_WORD_BACK, UiCommand.DELETE_WORD_FORWARD,
        UiCommand.WORD_LEFT, UiCommand.WORD_RIGHT, UiCommand.LINE_START, UiCommand.LINE_END, UiCommand.DOC_START,
        UiCommand.DOC_END, UiCommand.SUBMIT, UiCommand.NEWLINE,
    };
    private static final int R = REPEATS;
    private static final int[] FLAGS = {
        R | MOVE, R | MOVE, R | MOVE, R | MOVE, R, R, R | MOVE, R | MOVE, MOVE | NOT_TEXT, MOVE | NOT_TEXT, NOT_TEXT, 0,
        0, 0,
        TEXT_ONLY, TEXT_ONLY, TEXT_ONLY, TEXT_ONLY, TEXT_ONLY | R, TEXT_ONLY | R,
        TEXT_ONLY | R, TEXT_ONLY | R, TEXT_ONLY | R, TEXT_ONLY | R,
        TEXT_ONLY | R | MOVE, TEXT_ONLY | R | MOVE, TEXT_ONLY | MOVE, TEXT_ONLY | MOVE, TEXT_ONLY | MOVE,
        TEXT_ONLY | MOVE, TEXT_ONLY, TEXT_ONLY | R,
    };

    private final Input input;
    private final EngineSettings settings;
    private final DoubleSupplier realTime;
    private final InputContext text;
    private final EngineSettings.Subscription confirmWatch;
    private Confirm applied;
    private double[] nextRepeat = new double[0]; // per player and mapping: when it repeats next, or NaN when not held
    private long dropped;

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
            UiActions.SCROLL, UiActions.TABS);
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
            return;
        }
        // The game layer (as Input.defaults sets it, which waits for the engine to start; this may come before).
        settings.set(EngineSettings.Layer.GAME, input.bindingSetting(UiActions.ACCEPT), Binding.format(
            List.of(Binding.key(Key.ENTER), Binding.key(Key.SPACE), Binding.pad(GamepadButton.EAST))));
        settings.set(EngineSettings.Layer.GAME, input.bindingSetting(UiActions.CANCEL), Binding.format(
            List.of(Binding.key(Key.ESCAPE), Binding.pad(GamepadButton.SOUTH))));
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
        for (int p = 0; p < players; p++) {
            Player player = input.player(p);
            boolean extend = typing && player.down(UiActions.EXTEND_SELECTION);
            for (int m = 0; m < mappings; m++) {
                int flags = FLAGS[m];
                BinaryAction action = ACTIONS[m];
                int at = p * mappings + m;
                if ((flags & TEXT_ONLY) != 0 && !typing || (flags & NOT_TEXT) != 0 && typing) {
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
