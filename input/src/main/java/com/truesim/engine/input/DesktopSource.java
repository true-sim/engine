package com.truesim.engine.input;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.app.Frame;
import com.truesim.engine.platform.Controller;
import com.truesim.engine.platform.Desktop;
import com.truesim.engine.platform.GamepadAxis;
import com.truesim.engine.platform.GamepadButton;
import com.truesim.engine.platform.HatPosition;
import com.truesim.engine.platform.InputDevice;
import com.truesim.engine.platform.Key;
import com.truesim.engine.platform.MouseButton;
import com.truesim.engine.platform.Platform;
import com.truesim.engine.platform.PlatformListener;
import com.truesim.engine.platform.Window;
import java.util.List;

/**
 * The desktop's devices as input sees them: its keyboard, mouse, gamepad and joystick events written into input's
 * devices as the desktop reads them, before input takes them at the frame's start; gamepads and joysticks connected
 * and lost with their GUID, serial and path (D72, D73); rumble, mouse capture and text input through the platform.
 * Without a desktop (tests, servers) the events can be handed to it directly ({@link Input#events()}).
 */
final class DesktopSource implements PlatformListener, InputSource {

    private final Input input;
    private Desktop desktop;
    private int focusedWindow = -1;
    private boolean captured;
    private boolean text;

    DesktopSource(Input input) {
        this.input = input;
    }

    @AllowAllocation("starting up")
    void attach(Desktop found) {
        desktop = found;
        desktop.listen(this);
    }

    @AllowAllocation("stopping")
    void detach() {
        if (desktop != null) {
            desktop.unlisten(this);
            desktop = null;
        }
    }

    private Platform platform() {
        return desktop == null ? null : desktop.platform();
    }

    @Override
    public void start(Input of) {
        // its devices come by events
    }

    @Override
    public void sample(Frame frame) {
        // its events were written as they came
    }

    @Override
    public boolean rumble(Device device, float low, float high, int millis) {
        Platform platform = platform();
        if (platform == null) {
            return false;
        }
        Controller controller = device.type() == DeviceClass.GAMEPAD ? platform.gamepad(device.platformId())
            : device.type() == DeviceClass.JOYSTICK ? platform.joystick(device.platformId()) : null;
        return controller != null && controller.rumble(low, high, millis);
    }

    /** Whether the mouse is captured (relative, hidden) in the game's windows. */
    boolean captured() {
        return captured;
    }

    /** Whether the windows take text. */
    boolean takingText() {
        return text;
    }

    @AllowAllocation("the mouse mode changes")
    void captureMouse(boolean on) {
        captured = on;
        Platform platform = platform();
        if (platform != null) {
            List<Window> windows = platform.windows();
            for (Window window : windows) {
                window.setRelativeMouse(on && (focusedWindow < 0 || window.id() == focusedWindow));
            }
        }
    }

    @AllowAllocation("text input starts or stops")
    void textInput(boolean on) {
        text = on;
        Platform platform = platform();
        if (platform != null) {
            for (Window window : platform.windows()) {
                if (on) {
                    window.startTextInput(input.textKind());
                } else {
                    window.stopTextInput();
                }
            }
        }
    }

    // ---------------------------------------------------------------- events

    @Override
    public void windowFocusChanged(int window, boolean focused) {
        if (focused) {
            focusedWindow = window;
            if (captured) {
                captureMouse(true);
            }
        }
    }

    @Override
    public void key(long timestamp, int window, int keyboard, Key key, int character, int modifiers, boolean down,
        boolean repeat) {
        if (!repeat) {
            input.keyboard().press(key.ordinal(), down);
        }
    }

    @Override
    public void text(int window, String typed) {
        input.text(typed);
    }

    @Override
    public void textEditing(int window, String typed, int start, int length) {
        input.editing(typed, start, length);
    }

    @Override
    public void mouseMoved(long timestamp, int window, int mouse, float x, float y, float dx, float dy, int buttons) {
        input.mouseDevice().move(DeviceClass.MOUSE_X, dx);
        input.mouseDevice().move(DeviceClass.MOUSE_Y, dy);
    }

    @Override
    public void mouseButton(long timestamp, int window, int mouse, MouseButton button, boolean down, int clicks,
        float x, float y) {
        input.mouseDevice().press(button.ordinal(), down);
    }

    @Override
    public void mouseWheel(long timestamp, int window, int mouse, float dx, float dy) {
        input.mouseDevice().move(DeviceClass.WHEEL_X, dx);
        input.mouseDevice().move(DeviceClass.WHEEL_Y, dy);
    }

    @Override
    public void gamepadButton(long timestamp, int gamepad, GamepadButton button, boolean down) {
        Device device = input.gamepadOrJoystick(DeviceClass.GAMEPAD, gamepad);
        if (device != null) {
            device.press(button.ordinal(), down);
        }
    }

    @Override
    public void gamepadAxis(long timestamp, int gamepad, GamepadAxis axis, float value) {
        Device device = input.gamepadOrJoystick(DeviceClass.GAMEPAD, gamepad);
        if (device != null) {
            device.set(DeviceClass.PAD_AXIS + axis.ordinal(), value);
        }
    }

    @Override
    public void joystickButton(long timestamp, int joystick, int button, boolean down) {
        Device device = input.gamepadOrJoystick(DeviceClass.JOYSTICK, joystick);
        if (device != null && button < DeviceClass.JOY_BUTTONS) {
            device.press(button, down);
        }
    }

    @Override
    public void joystickAxis(long timestamp, int joystick, int axis, float value) {
        Device device = input.gamepadOrJoystick(DeviceClass.JOYSTICK, joystick);
        if (device != null && axis < DeviceClass.JOY_AXES) {
            device.set(DeviceClass.JOY_AXIS + axis, value);
        }
    }

    @Override
    public void joystickHat(long timestamp, int joystick, int hat, HatPosition position) {
        Device device = input.gamepadOrJoystick(DeviceClass.JOYSTICK, joystick);
        if (device == null || hat >= DeviceClass.JOY_HATS) {
            return;
        }
        int at = DeviceClass.JOY_HAT + 4 * hat; // up, right, down, left
        boolean up = position == HatPosition.UP || position == HatPosition.UP_LEFT || position == HatPosition.UP_RIGHT;
        boolean down = position == HatPosition.DOWN || position == HatPosition.DOWN_LEFT
            || position == HatPosition.DOWN_RIGHT;
        boolean left = position == HatPosition.LEFT || position == HatPosition.UP_LEFT
            || position == HatPosition.DOWN_LEFT;
        boolean right = position == HatPosition.RIGHT || position == HatPosition.UP_RIGHT
            || position == HatPosition.DOWN_RIGHT;
        device.press(at, up);
        device.press(at + 1, right);
        device.press(at + 2, down);
        device.press(at + 3, left);
    }

    @Override
    public void joystickBall(long timestamp, int joystick, int ball, int dx, int dy) {
        Device device = input.gamepadOrJoystick(DeviceClass.JOYSTICK, joystick);
        if (device != null && ball < DeviceClass.JOY_BALLS) {
            device.move(DeviceClass.JOY_BALL + 2 * ball, dx);
            device.move(DeviceClass.JOY_BALL + 2 * ball + 1, dy);
        }
    }

    @Override
    @AllowAllocation("a device is plugged in")
    public void deviceAdded(InputDevice device) {
        DeviceClass type = switch (device.kind()) {
            case GAMEPAD -> DeviceClass.GAMEPAD;
            case JOYSTICK -> DeviceClass.JOYSTICK;
            default -> null; // keyboards and mice are one device each; sensors and haptics are not bound
        };
        if (type != null && input.gamepadOrJoystick(type, device.id()) == null) {
            input.connect(type, device.id(), new DeviceIdentity(device.guid(), device.serial(), device.path()),
                device.name(), this);
        }
    }

    @Override
    public void deviceRemoved(InputDevice.Kind kind, int id) {
        DeviceClass type = kind == InputDevice.Kind.GAMEPAD ? DeviceClass.GAMEPAD
            : kind == InputDevice.Kind.JOYSTICK ? DeviceClass.JOYSTICK : null;
        Device device = type == null ? null : input.gamepadOrJoystick(type, id);
        if (device != null) {
            input.disconnect(device);
        }
    }
}
