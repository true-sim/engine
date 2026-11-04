package com.truesim.engine.ui;

import com.truesim.engine.app.AppConfig;
import com.truesim.engine.app.Application;
import com.truesim.engine.app.Engine;
import com.truesim.engine.input.ActionGroup;
import com.truesim.engine.input.Axis2Action;
import com.truesim.engine.input.BinaryAction;
import com.truesim.engine.input.Binding;
import com.truesim.engine.input.Input;
import com.truesim.engine.platform.GamepadButton;
import com.truesim.engine.platform.InputDevice;
import com.truesim.engine.platform.Key;
import com.truesim.engine.platform.PlatformListener;
import com.truesim.ui.core.input.UiCommand;
import com.truesim.ui.core.input.UiInput;
import java.util.ArrayList;
import java.util.List;

/**
 * An engine with input, a game's actions beside the UI's, and the UI's commands: events handed to input directly,
 * frames at times the test chooses, each frame's commands polled into a {@link UiInput} as the UI would at its start.
 */
final class InputRig implements AutoCloseable {

    static final long MS = 1_000_000L;

    static final ActionGroup GAME = ActionGroup.root("game");
    static final Axis2Action MOVE = GAME.axis2("move", Binding.keys(Key.A, Key.D, Key.S, Key.W));
    static final BinaryAction JUMP = GAME.binary("jump", Binding.key(Key.SPACE));

    final Input input;
    final Engine engine;
    final UiCommands commands;
    final UiInput ui = new UiInput(64);
    long now;

    InputRig() {
        input = new Input().add(GAME);
        engine = Engine.create(new Application() {
            @Override
            public void configure(AppConfig app) {
                app.use(input).defaults(Engine.FRAME_RATE, 10_000);
            }
        });
        commands = new UiCommands(input, engine.settings(), () -> now / 1e9);
    }

    /** Runs a frame at the time now and polls its commands, then moves the time on by {@code millis}. */
    InputRig frame(long millis) {
        engine.stepAt(now);
        ui.beginFrame();
        commands.poll(ui);
        now += millis * MS;
        return this;
    }

    InputRig frame() {
        return frame(16);
    }

    /** This frame's commands, in order, with " extend" after those extending the selection. */
    List<String> commands() {
        List<String> all = new ArrayList<>();
        for (int i = 0; i < ui.commandCount(); i++) {
            UiCommand command = ui.command(i);
            all.add(command + ((ui.flags(i) & UiInput.EXTEND_SELECTION) != 0 ? " extend" : ""));
        }
        return all;
    }

    PlatformListener events() {
        return input.events();
    }

    /** Listeners given the keys too (a {@link UiKeyboard}), after input. */
    final List<PlatformListener> keyListeners = new ArrayList<>();

    InputRig key(Key key, boolean down) {
        int character = character(key);
        events().key(0, 1, 0, key, character, 0, down, false);
        for (PlatformListener listener : keyListeners) {
            listener.key(0, 1, 0, key, character, 0, down, false);
        }
        return this;
    }

    /** The character a US layout gives {@code key} (letters lower case), or 0. */
    static int character(Key key) {
        String name = key.name();
        if (name.length() == 1) {
            return Character.toLowerCase(name.charAt(0));
        }
        if (name.startsWith("DIGIT_")) {
            return name.charAt(6);
        }
        return key == Key.BACKQUOTE ? '`' : 0;
    }

    InputRig tap(Key key) {
        return key(key, true).key(key, false);
    }

    /** Holds {@code keys} (pressed in order) through a frame, then lets them go: that frame's commands. */
    List<String> press(Key... keys) {
        for (Key key : keys) {
            key(key, true);
        }
        frame();
        List<String> made = commands();
        for (Key key : keys) {
            key(key, false);
        }
        frame();
        return made;
    }

    InputRig plug(int id) {
        events().deviceAdded(new InputDevice(id, InputDevice.Kind.GAMEPAD, "pad " + id, 0x45e, 0xb12,
            "03000000" + id, "", ""));
        return this;
    }

    InputRig pad(int id, GamepadButton button, boolean down) {
        events().gamepadButton(0, id, button, down);
        return this;
    }

    @Override
    public void close() {
        commands.close();
        engine.close();
    }
}
