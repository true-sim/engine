package com.truesim.engine.platform;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.app.Application;
import com.truesim.engine.app.Engine;
import com.truesim.engine.app.Frame;
import com.truesim.engine.app.Plugin;
import com.truesim.engine.app.PluginKey;
import com.truesim.engine.app.Stage;
import com.truesim.engine.app.Startup;
import com.truesim.engine.app.StartupFailure;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * The desktop platform as a plugin: SDL's windows, displays and input devices ({@link #platform()}), started first, its
 * events read at the start of every frame and handed to the game's listeners ({@link #listen}). The last window
 * closing (or the system's quit) becomes a quit request ({@link Engine#requestQuit}); a window's close button alone is
 * the game's to handle. Focus and minimising reach the application ({@link Application#onFocusChanged},
 * {@link Application#onVisibilityChanged}).
 *
 * <pre>{@code
 * app.use(new Desktop());
 * Desktop desktop = engine.get(Desktop.KEY);
 * Window window = desktop.platform().createWindow(WindowOptions.of("game", 1280, 720), settings.scoped("main"));
 * desktop.listen(myListener);
 * }</pre>
 */
public final class Desktop implements Plugin {

    /** Its key. */
    public static final PluginKey<Desktop> KEY = PluginKey.of("desktop", Desktop.class);

    private static final int NONE = Integer.MIN_VALUE;

    private final Events events = new Events();
    private PlatformListener[] listeners = new PlatformListener[0];
    private Engine engine;
    private Platform platform;
    private int focusedWindow = NONE;
    private boolean visibilityChanged;

    @Override
    public PluginKey<Desktop> key() {
        return KEY;
    }

    @Override
    @AllowAllocation("starting up")
    public void start(Stage stage, Startup startup) {
        if (stage == Stage.BEFORE_INSTANCE) {
            engine = startup.engine();
            try {
                platform = Platform.start();
            } catch (RuntimeException e) {
                throw new StartupFailure("The desktop could not start: " + e.getMessage(), e);
            }
        }
    }

    /** The platform: windows, displays, input devices. */
    public Platform platform() {
        if (platform == null) {
            throw new IllegalStateException("The desktop starts in BEFORE_INSTANCE: not yet");
        }
        return platform;
    }

    /** Hands every platform event to {@code listener} from the next frame on, after those listening already. */
    @AllowAllocation("adding a listener")
    public Desktop listen(PlatformListener listener) {
        Objects.requireNonNull(listener, "listener");
        listeners = Arrays.copyOf(listeners, listeners.length + 1);
        listeners[listeners.length - 1] = listener;
        return this;
    }

    /** Stops handing events to {@code listener}. */
    @AllowAllocation("removing a listener")
    public Desktop unlisten(PlatformListener listener) {
        listeners = Arrays.stream(listeners).filter(l -> l != listener).toArray(PlatformListener[]::new);
        return this;
    }

    /** What receives the platform's events: for tests, which hand it events of their own. */
    PlatformListener events() {
        return events;
    }

    /** Reads the platform's events; allocates nothing of its own. */
    @Override
    public void beforeUpdate(Frame frame) {
        platform.pollEvents(events);
        engine.focusChanged(focusedWindow != NONE);
        if (visibilityChanged) {
            visibilityChanged = false;
            engine.visibilityChanged(anyWindowShown());
        }
    }

    private boolean anyWindowShown() {
        List<Window> windows = platform.windows();
        for (int i = 0; i < windows.size(); i++) {
            if (!windows.get(i).isMinimized()) {
                return true;
            }
        }
        return windows.isEmpty();
    }

    @Override
    public void stop() {
        if (platform != null) {
            platform.close();
            platform = null;
        }
    }

    /** The platform's events: to the game's listeners, and what the engine follows. */
    private final class Events implements PlatformListener {

        @Override
        public void quitRequested() {
            engine.requestQuit(Application.QuitReason.WINDOW_CLOSED, false);
            for (PlatformListener listener : listeners) {
                listener.quitRequested();
            }
        }

        @Override
        public void windowCloseRequested(int window) {
            for (PlatformListener listener : listeners) {
                listener.windowCloseRequested(window);
            }
        }

        @Override
        public void windowResized(int window, int width, int height) {
            for (PlatformListener listener : listeners) {
                listener.windowResized(window, width, height);
            }
        }

        @Override
        public void windowPixelSizeChanged(int window, int width, int height) {
            for (PlatformListener listener : listeners) {
                listener.windowPixelSizeChanged(window, width, height);
            }
        }

        @Override
        public void windowMoved(int window, int x, int y) {
            for (PlatformListener listener : listeners) {
                listener.windowMoved(window, x, y);
            }
        }

        @Override
        public void windowMinimized(int window, boolean minimized) {
            visibilityChanged = true;
            for (PlatformListener listener : listeners) {
                listener.windowMinimized(window, minimized);
            }
        }

        @Override
        public void windowMaximized(int window, boolean maximized) {
            for (PlatformListener listener : listeners) {
                listener.windowMaximized(window, maximized);
            }
        }

        @Override
        public void windowShown(int window, boolean shown) {
            visibilityChanged = true;
            for (PlatformListener listener : listeners) {
                listener.windowShown(window, shown);
            }
        }

        @Override
        public void windowOccluded(int window, boolean occluded) {
            for (PlatformListener listener : listeners) {
                listener.windowOccluded(window, occluded);
            }
        }

        @Override
        public void windowScaleChanged(int window) {
            for (PlatformListener listener : listeners) {
                listener.windowScaleChanged(window);
            }
        }

        @Override
        public void mouseEntered(int window, boolean entered) {
            for (PlatformListener listener : listeners) {
                listener.mouseEntered(window, entered);
            }
        }

        @Override
        public void fileDropped(int window, String path, float x, float y) {
            for (PlatformListener listener : listeners) {
                listener.fileDropped(window, path, x, y);
            }
        }

        @Override
        public void textEditing(int window, String text, int start, int length) {
            for (PlatformListener listener : listeners) {
                listener.textEditing(window, text, start, length);
            }
        }

        @Override
        public void other(int type, long event) {
            for (PlatformListener listener : listeners) {
                listener.other(type, event);
            }
        }

        @Override
        public void windowFocusChanged(int window, boolean focused) {
            if (focused) {
                focusedWindow = window;
            } else if (window == focusedWindow) {
                focusedWindow = NONE;
            }
            for (PlatformListener listener : listeners) {
                listener.windowFocusChanged(window, focused);
            }
        }

        @Override
        public void windowDisplayChanged(int window, int display) {
            for (PlatformListener listener : listeners) {
                listener.windowDisplayChanged(window, display);
            }
        }

        @Override
        public void displaysChanged() {
            for (PlatformListener listener : listeners) {
                listener.displaysChanged();
            }
        }

        @Override
        public void deviceAdded(InputDevice device) {
            for (PlatformListener listener : listeners) {
                listener.deviceAdded(device);
            }
        }

        @Override
        public void deviceRemoved(InputDevice.Kind kind, int device) {
            for (PlatformListener listener : listeners) {
                listener.deviceRemoved(kind, device);
            }
        }

        @Override
        public void batteryChanged(int controller, PowerState state, int percent) {
            for (PlatformListener listener : listeners) {
                listener.batteryChanged(controller, state, percent);
            }
        }

        @Override
        public void key(long timestamp, int window, int keyboard, Key key, int character, int modifiers, boolean down,
            boolean repeat) {
            for (PlatformListener listener : listeners) {
                listener.key(timestamp, window, keyboard, key, character, modifiers, down, repeat);
            }
        }

        @Override
        public void text(int window, String text) {
            for (PlatformListener listener : listeners) {
                listener.text(window, text);
            }
        }

        @Override
        public void mouseMoved(long timestamp, int window, int mouse, float x, float y, float dx, float dy,
            int buttons) {
            for (PlatformListener listener : listeners) {
                listener.mouseMoved(timestamp, window, mouse, x, y, dx, dy, buttons);
            }
        }

        @Override
        public void mouseButton(long timestamp, int window, int mouse, MouseButton button, boolean down, int clicks,
            float x, float y) {
            for (PlatformListener listener : listeners) {
                listener.mouseButton(timestamp, window, mouse, button, down, clicks, x, y);
            }
        }

        @Override
        public void mouseWheel(long timestamp, int window, int mouse, float dx, float dy) {
            for (PlatformListener listener : listeners) {
                listener.mouseWheel(timestamp, window, mouse, dx, dy);
            }
        }

        @Override
        public void gamepadAxis(long timestamp, int gamepad, GamepadAxis axis, float value) {
            for (PlatformListener listener : listeners) {
                listener.gamepadAxis(timestamp, gamepad, axis, value);
            }
        }

        @Override
        public void gamepadButton(long timestamp, int gamepad, GamepadButton button, boolean down) {
            for (PlatformListener listener : listeners) {
                listener.gamepadButton(timestamp, gamepad, button, down);
            }
        }

        @Override
        public void gamepadTouchpad(long timestamp, int gamepad, int touchpad, int finger, TouchPhase phase, float x,
            float y, float pressure) {
            for (PlatformListener listener : listeners) {
                listener.gamepadTouchpad(timestamp, gamepad, touchpad, finger, phase, x, y, pressure);
            }
        }

        @Override
        public void gamepadSensor(long timestamp, int gamepad, SensorType sensor, float x, float y, float z,
            long sensorTimestamp) {
            for (PlatformListener listener : listeners) {
                listener.gamepadSensor(timestamp, gamepad, sensor, x, y, z, sensorTimestamp);
            }
        }

        @Override
        public void gamepadRemapped(int gamepad) {
            for (PlatformListener listener : listeners) {
                listener.gamepadRemapped(gamepad);
            }
        }

        @Override
        public void joystickAxis(long timestamp, int joystick, int axis, float value) {
            for (PlatformListener listener : listeners) {
                listener.joystickAxis(timestamp, joystick, axis, value);
            }
        }

        @Override
        public void joystickButton(long timestamp, int joystick, int button, boolean down) {
            for (PlatformListener listener : listeners) {
                listener.joystickButton(timestamp, joystick, button, down);
            }
        }

        @Override
        public void joystickHat(long timestamp, int joystick, int hat, HatPosition position) {
            for (PlatformListener listener : listeners) {
                listener.joystickHat(timestamp, joystick, hat, position);
            }
        }

        @Override
        public void joystickBall(long timestamp, int joystick, int ball, int dx, int dy) {
            for (PlatformListener listener : listeners) {
                listener.joystickBall(timestamp, joystick, ball, dx, dy);
            }
        }

        @Override
        public void sensor(long timestamp, int sensor, float x, float y, float z, long sensorTimestamp) {
            for (PlatformListener listener : listeners) {
                listener.sensor(timestamp, sensor, x, y, z, sensorTimestamp);
            }
        }

        @Override
        public void touch(long timestamp, int window, long device, long finger, TouchPhase phase, float x, float y,
            float dx, float dy, float pressure) {
            for (PlatformListener listener : listeners) {
                listener.touch(timestamp, window, device, finger, phase, x, y, dx, dy, pressure);
            }
        }

        @Override
        public void penProximity(long timestamp, int window, int pen, boolean near) {
            for (PlatformListener listener : listeners) {
                listener.penProximity(timestamp, window, pen, near);
            }
        }

        @Override
        public void penTouch(long timestamp, int window, int pen, float x, float y, boolean eraser, boolean down) {
            for (PlatformListener listener : listeners) {
                listener.penTouch(timestamp, window, pen, x, y, eraser, down);
            }
        }

        @Override
        public void penButton(long timestamp, int window, int pen, float x, float y, int button, boolean down) {
            for (PlatformListener listener : listeners) {
                listener.penButton(timestamp, window, pen, x, y, button, down);
            }
        }

        @Override
        public void penMoved(long timestamp, int window, int pen, float x, float y, int state) {
            for (PlatformListener listener : listeners) {
                listener.penMoved(timestamp, window, pen, x, y, state);
            }
        }

        @Override
        public void penAxis(long timestamp, int window, int pen, float x, float y, PenAxis axis, float value) {
            for (PlatformListener listener : listeners) {
                listener.penAxis(timestamp, window, pen, x, y, axis, value);
            }
        }
    }
}
