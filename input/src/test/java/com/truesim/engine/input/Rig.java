package com.truesim.engine.input;

import com.truesim.engine.app.AppConfig;
import com.truesim.engine.app.Application;
import com.truesim.engine.app.Engine;
import com.truesim.engine.app.Frame;
import com.truesim.engine.app.Step;
import com.truesim.engine.platform.GamepadAxis;
import com.truesim.engine.platform.GamepadButton;
import com.truesim.engine.platform.InputDevice;
import com.truesim.engine.platform.Key;
import com.truesim.engine.platform.MouseButton;
import com.truesim.engine.platform.PlatformListener;
import java.nio.file.Path;
import java.util.function.Consumer;

/** An engine with input and no desktop: events handed to input directly, frames at times the test chooses. */
final class Rig implements AutoCloseable {

    static final long MS = 1_000_000L;

    // The actions most tests use.
    static final ActionGroup GAME = ActionGroup.root("game");
    static final ActionGroup VEHICLE = GAME.child("vehicle");
    static final BinaryAction JUMP = GAME.binary("jump", Binding.key(Key.SPACE), Binding.pad(GamepadButton.SOUTH));
    static final Axis2Action MOVE = GAME.axis2("move", Binding.keys(Key.A, Key.D, Key.S, Key.W), Binding.stick(false));
    static final BinaryAction SAVE = GAME.binary("save", Binding.chord(Controls.CONTROL, Controls.key(Key.S)));
    static final BinaryAction SAVE_ALL = GAME.binary("saveAll",
        Binding.chord(Controls.CONTROL, Controls.key(Key.S)).with(Controls.SHIFT));
    static final BinaryAction CROUCH = GAME.binary("crouch", Binding.key(Key.LEFT_CONTROL));
    static final Axis1Action THROTTLE = GAME.axis1("throttle", Binding.pad(GamepadAxis.RIGHT_TRIGGER));
    static final BinaryAction FIRE = GAME.binary("fire", Binding.pad(GamepadAxis.RIGHT_TRIGGER),
        Binding.mouse(MouseButton.LEFT));
    static final BinaryAction PAUSE = GAME.binary("pause", Binding.key(Key.ESCAPE), Binding.pad(GamepadButton.START));
    static final BinaryAction BOOST = VEHICLE.binary("boost", Binding.key(Key.LEFT_SHIFT));
    static final Axis1Action STEER = VEHICLE.axis1("steer", Binding.axis(Controls.key(Key.LEFT), Controls.key(Key.RIGHT)));
    static final ActionGroup UI = ActionGroup.root("ui");
    static final BinaryAction BACK = UI.binary("back", Binding.key(Key.ESCAPE), Binding.pad(GamepadButton.EAST));
    static final BinaryAction CONFIRM = UI.binary("confirm", Binding.key(Key.ENTER), Binding.pad(GamepadButton.SOUTH));

    final Input input;
    final Engine engine;
    long now;
    Consumer<Frame> onUpdate = frame -> { };
    Consumer<Step> onStep = step -> { };

    Rig(ActionGroup... groups) {
        this(null, groups);
    }

    Rig(Path settingsFile, ActionGroup... groups) {
        input = new Input().add(groups);
        engine = Engine.create(new Application() {
            @Override
            public void configure(AppConfig app) {
                app.use(input).defaults(Engine.FRAME_RATE, 10_000);
                if (settingsFile != null) {
                    app.saveSettings(settingsFile);
                }
            }

            @Override
            public void update(Frame frame) {
                onUpdate.accept(frame);
            }

            @Override
            public void fixedUpdate(Step step) {
                onStep.accept(step);
            }
        });
    }

    /** Runs a frame at the time now, then moves the time on by {@code millis}. */
    Rig frame(long millis) {
        engine.stepAt(now);
        now += millis * MS;
        return this;
    }

    Rig frame() {
        return frame(16);
    }

    Player one() {
        return input.player(0);
    }

    PlatformListener events() {
        return input.events();
    }

    Rig key(Key key, boolean down) {
        events().key(0, 1, 0, key, 0, 0, down, false);
        return this;
    }

    Rig tap(Key key) {
        return key(key, true).key(key, false);
    }

    Rig pad(int id, GamepadButton button, boolean down) {
        events().gamepadButton(0, id, button, down);
        return this;
    }

    Rig axis(int id, GamepadAxis axis, float value) {
        events().gamepadAxis(0, id, axis, value);
        return this;
    }

    Rig plug(int id, String guid, String serial, String path) {
        events().deviceAdded(new InputDevice(id, InputDevice.Kind.GAMEPAD, "pad " + id, 0x45e, 0xb12, guid, serial,
            path));
        return this;
    }

    Rig plug(int id) {
        return plug(id, "03000000" + id, "", "");
    }

    Rig unplug(int id) {
        events().deviceRemoved(InputDevice.Kind.GAMEPAD, id);
        return this;
    }

    Device pad(int id) {
        return input.gamepadOrJoystick(DeviceClass.GAMEPAD, id);
    }

    @Override
    public void close() {
        engine.close();
    }
}
