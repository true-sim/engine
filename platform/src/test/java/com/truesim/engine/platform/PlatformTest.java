package com.truesim.engine.platform;

import static com.truesim.testing.AllocationAssertions.assertAllocationFree;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.lwjgl.sdl.SDLEvents.SDL_EVENT_DISPLAY_ADDED;
import static org.lwjgl.sdl.SDLEvents.SDL_EVENT_GAMEPAD_AXIS_MOTION;
import static org.lwjgl.sdl.SDLEvents.SDL_EVENT_GAMEPAD_BUTTON_DOWN;
import static org.lwjgl.sdl.SDLEvents.SDL_EVENT_JOYSTICK_ADDED;
import static org.lwjgl.sdl.SDLEvents.SDL_EVENT_JOYSTICK_AXIS_MOTION;
import static org.lwjgl.sdl.SDLEvents.SDL_EVENT_JOYSTICK_BUTTON_UP;
import static org.lwjgl.sdl.SDLEvents.SDL_EVENT_JOYSTICK_REMOVED;
import static org.lwjgl.sdl.SDLEvents.SDL_EVENT_KEYBOARD_ADDED;
import static org.lwjgl.sdl.SDLEvents.SDL_EVENT_KEYBOARD_REMOVED;
import static org.lwjgl.sdl.SDLEvents.SDL_EVENT_KEY_DOWN;
import static org.lwjgl.sdl.SDLEvents.SDL_EVENT_MOUSE_BUTTON_UP;
import static org.lwjgl.sdl.SDLEvents.SDL_EVENT_MOUSE_MOTION;
import static org.lwjgl.sdl.SDLEvents.SDL_EVENT_MOUSE_WHEEL;
import static org.lwjgl.sdl.SDLEvents.SDL_EVENT_QUIT;
import static org.lwjgl.sdl.SDLEvents.SDL_EVENT_WINDOW_CLOSE_REQUESTED;
import static org.lwjgl.sdl.SDLEvents.SDL_EVENT_WINDOW_DISPLAY_CHANGED;
import static org.lwjgl.sdl.SDLEvents.SDL_EVENT_WINDOW_FOCUS_GAINED;
import static org.lwjgl.sdl.SDLEvents.SDL_EVENT_WINDOW_MINIMIZED;
import static org.lwjgl.sdl.SDLEvents.SDL_EVENT_WINDOW_MOVED;
import static org.lwjgl.sdl.SDLEvents.SDL_EVENT_WINDOW_RESTORED;
import static org.lwjgl.sdl.SDLEvents.SDL_PushEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.sdl.SDL_CommonEvent;
import org.lwjgl.sdl.SDL_Event;
import org.lwjgl.sdl.SDL_GamepadAxisEvent;
import org.lwjgl.sdl.SDL_GamepadButtonEvent;
import org.lwjgl.sdl.SDL_JoyAxisEvent;
import org.lwjgl.sdl.SDL_JoyButtonEvent;
import org.lwjgl.sdl.SDL_KeyboardDeviceEvent;
import org.lwjgl.sdl.SDL_KeyboardEvent;
import org.lwjgl.sdl.SDL_MouseButtonEvent;
import org.lwjgl.sdl.SDL_MouseMotionEvent;
import org.lwjgl.sdl.SDL_MouseWheelEvent;
import org.lwjgl.sdl.SDL_WindowEvent;

class PlatformTest {

    private Platform platform;
    private final SDL_Event event = SDL_Event.calloc();

    @BeforeEach
    void start() {
        platform = Platform.start();
    }

    @AfterEach
    void stop() {
        platform.close();
        event.free();
    }

    /** Records every event as a line of text. */
    private static final class Recorder implements PlatformListener {
        final List<String> events = new ArrayList<>();
        final List<InputDevice> added = new ArrayList<>();

        @Override
        public void quitRequested() {
            events.add("quit");
        }

        @Override
        public void windowCloseRequested(int window) {
            events.add("close " + window);
        }

        @Override
        public void windowResized(int window, int width, int height) {
            events.add("resized " + window + " " + width + "x" + height);
        }

        @Override
        public void windowPixelSizeChanged(int window, int width, int height) {
            events.add("pixels " + window + " " + width + "x" + height);
        }

        @Override
        public void windowMoved(int window, int x, int y) {
            events.add("moved " + window + " " + x + "," + y);
        }

        @Override
        public void windowMinimized(int window, boolean minimized) {
            events.add("minimized " + window + " " + minimized);
        }

        @Override
        public void windowFocusChanged(int window, boolean focused) {
            events.add("focus " + window + " " + focused);
        }

        @Override
        public void windowDisplayChanged(int window, int display) {
            events.add("display " + window + " " + display);
        }

        @Override
        public void displaysChanged() {
            events.add("displays");
        }

        @Override
        public void deviceAdded(InputDevice device) {
            added.add(device);
            events.add("added " + device.kind() + " " + device.id());
        }

        @Override
        public void deviceRemoved(InputDevice.Kind kind, int device) {
            events.add("removed " + kind + " " + device);
        }

        @Override
        public void key(long timestamp, int window, int keyboard, int scancode, int key, int modifiers, boolean down,
            boolean repeat) {
            events.add("key " + timestamp + " " + window + " " + keyboard + " " + scancode + " " + key + " " + modifiers
                + " " + down + " " + repeat);
        }

        @Override
        public void mouseMoved(long timestamp, int window, int mouse, float x, float y, float dx, float dy, int buttons) {
            events.add("motion " + window + " " + mouse + " " + x + "," + y + " " + dx + "," + dy + " " + buttons);
        }

        @Override
        public void mouseButton(long timestamp, int window, int mouse, int button, boolean down, int clicks, float x,
            float y) {
            events.add("button " + window + " " + mouse + " " + button + " " + down + " " + clicks + " " + x + "," + y);
        }

        @Override
        public void mouseWheel(long timestamp, int window, int mouse, float dx, float dy) {
            events.add("wheel " + window + " " + mouse + " " + dx + "," + dy);
        }

        @Override
        public void gamepadAxis(long timestamp, int gamepad, int axis, float value) {
            events.add("gamepad axis " + gamepad + " " + axis + " " + value);
        }

        @Override
        public void gamepadButton(long timestamp, int gamepad, int button, boolean down) {
            events.add("gamepad button " + gamepad + " " + button + " " + down);
        }

        @Override
        public void joystickAxis(long timestamp, int joystick, int axis, float value) {
            events.add("joystick axis " + joystick + " " + axis + " " + value);
        }

        @Override
        public void joystickButton(long timestamp, int joystick, int button, boolean down) {
            events.add("joystick button " + joystick + " " + button + " " + down);
        }
    }

    private void push(int type) {
        event.clear();
        SDL_CommonEvent.ntype(event.address(), type);
    }

    private void send() {
        assertThat(SDL_PushEvent(event)).isTrue();
    }

    private List<String> poll(Recorder recorder) {
        recorder.events.clear();
        recorder.added.clear();
        platform.pollEvents(recorder);
        return recorder.events;
    }

    // ---------------------------------------------------------------- lifecycle

    @Test
    void onePlatformOnItsOwnThread() throws InterruptedException {
        assertThatIllegalStateException().isThrownBy(Platform::start).withMessage("A platform is already running");
        assertThat(platform.videoDriver()).isNotBlank();
        AtomicReference<Throwable> other = new AtomicReference<>();
        Thread thread = new Thread(() -> {
            try {
                platform.displays();
            } catch (Throwable e) {
                other.set(e);
            }
        });
        thread.start();
        thread.join();
        assertThat(other.get()).isInstanceOf(IllegalStateException.class).hasMessageContaining("belongs to thread");
    }

    @Test
    void closedPlatformsRefuseWork() {
        platform.close();
        platform.close(); // twice is fine
        assertThatIllegalStateException().isThrownBy(platform::displays).withMessage("The platform is closed");
        platform = Platform.start(); // and a new one can start
    }

    // ---------------------------------------------------------------- displays

    @Test
    void displays() {
        List<Display> displays = platform.displays();
        assertThat(displays).isNotEmpty();
        Display primary = displays.getFirst();
        assertThat(primary.primary()).isTrue();
        assertThat(displays).filteredOn(Display::primary).hasSize(1);
        assertThat(primary.name()).isNotNull();
        assertThat(primary.bounds().width()).isPositive();
        assertThat(primary.usableBounds().height()).isPositive();
        assertThat(primary.contentScale()).isPositive();
        assertThat(primary.desktop().width()).isPositive();
        assertThat(primary.current().displayId()).isEqualTo(primary.id());
        assertThat(primary.current().pixelWidth()).isGreaterThanOrEqualTo(primary.current().width());
        assertThat(primary.current().pixelHeight()).isGreaterThanOrEqualTo(primary.current().height());
        for (DisplayMode mode : primary.fullscreenModes()) {
            assertThat(mode.displayId()).isEqualTo(primary.id());
            assertThat(mode.width()).isPositive();
        }
    }

    // ---------------------------------------------------------------- windows

    @Test
    void windowsChangeAtRuntime() {
        Window window = platform.createWindow(WindowOptions.of("first", 640, 480).withHidden(true).withVulkan(false));
        Window second = platform.createWindow(WindowOptions.of("second", 320, 200).withHidden(true).withVulkan(false)
            .withResizable(false).withHighPixelDensity(false));
        assertThat(platform.windows()).containsExactly(window, second);
        assertThat(platform.window(window.id())).isSameAs(window);
        assertThat(platform.window(-1)).isNull();
        assertThat(window.handle()).isNotZero();
        assertThat(window.toString()).isEqualTo("Window " + window.id());
        assertThat(window.title()).isEqualTo("first");
        window.setTitle("renamed");
        assertThat(window.title()).isEqualTo("renamed");
        assertThat(window.width()).isEqualTo(640);
        assertThat(window.height()).isEqualTo(480);
        assertThat(window.pixelWidth()).isGreaterThanOrEqualTo(640);
        assertThat(window.pixelHeight()).isGreaterThanOrEqualTo(480);
        // Runtime resolution change.
        window.setSize(800, 600);
        window.sync();
        assertThat(window.width()).isEqualTo(800);
        assertThat(window.height()).isEqualTo(600);
        window.setPosition(40, 60);
        window.sync();
        window.center();
        window.sync();
        assertThat(window.x()).isNotNegative();
        assertThat(window.y()).isNotNegative();
        assertThat(window.display()).isPositive();
        assertThat(window.displayScale()).isPositive();
        assertThat(window.isResizable()).isTrue();
        assertThat(second.isResizable()).isFalse();
        window.setResizable(false);
        assertThat(window.isResizable()).isFalse();
        assertThat(window.isVisible()).isFalse();
        window.show();
        assertThat(window.isVisible()).isTrue();
        window.raise();
        window.hide();
        assertThat(window.isVisible()).isFalse();
        window.startTextInput();
        window.stopTextInput();
        // Modes: windowed and borderless fullscreen (exclusive fullscreen switches the monitor: opt-in below).
        assertThat(window.mode()).isEqualTo(WindowMode.WINDOWED);
        assertThat(window.fullscreenMode()).isNull();
        window.windowed();
        window.close();
        assertThat(window.isClosed()).isTrue();
        window.close(); // twice is fine
        assertThatIllegalStateException().isThrownBy(window::handle).withMessage("The window is closed");
        assertThat(platform.windows()).containsExactly(second);
        // Closing the platform closes the rest.
    }

    @Test
    void borderlessAndExclusiveFullscreen() {
        Window window = platform.createWindow(WindowOptions.of("fullscreen", 640, 480).withVulkan(false));
        window.borderlessFullscreen();
        window.sync();
        assertThat(window.mode()).isIn(WindowMode.BORDERLESS_FULLSCREEN, WindowMode.WINDOWED);
        window.windowed();
        window.sync();
        assumeThat(System.getenv("TRUESIM_TEST_FULLSCREEN")).as("exclusive fullscreen switches the monitor").isNotNull();
        Display display = platform.displays().getFirst();
        window.exclusiveFullscreen(display.desktop());
        window.sync();
        assertThat(window.mode()).isEqualTo(WindowMode.EXCLUSIVE_FULLSCREEN);
        assertThat(window.fullscreenMode().width()).isEqualTo(display.desktop().width());
        window.windowed();
        window.sync();
    }

    @Test
    void windowOptionsValidate() {
        assertThatIllegalArgumentException().isThrownBy(() -> WindowOptions.of("x", 0, 10));
        assertThatIllegalArgumentException().isThrownBy(() -> WindowOptions.of("x", 10, -1));
        WindowOptions options = WindowOptions.of("x", 10, 20);
        assertThat(options.resizable()).isTrue();
        assertThat(options.hidden()).isFalse();
        assertThat(options.highPixelDensity()).isTrue();
        assertThat(options.vulkan()).isTrue();
    }

    // ---------------------------------------------------------------- events

    @Test
    void eventsAreForwarded() {
        Recorder recorder = new Recorder();
        poll(recorder); // whatever startup produced
        long e = event.address();
        push(SDL_EVENT_KEY_DOWN);
        SDL_KeyboardEvent.ntimestamp(e, 123);
        SDL_KeyboardEvent.nwindowID(e, 7);
        SDL_KeyboardEvent.nwhich(e, 2);
        SDL_KeyboardEvent.nscancode(e, 4);
        SDL_KeyboardEvent.nkey(e, 97);
        SDL_KeyboardEvent.nmod(e, (short) 0x8001);
        SDL_KeyboardEvent.ndown(e, true);
        SDL_KeyboardEvent.nrepeat(e, true);
        send();
        push(SDL_EVENT_MOUSE_MOTION);
        SDL_MouseMotionEvent.nwindowID(e, 7);
        SDL_MouseMotionEvent.nwhich(e, 1);
        SDL_MouseMotionEvent.nx(e, 10.5f);
        SDL_MouseMotionEvent.ny(e, 20);
        SDL_MouseMotionEvent.nxrel(e, 1);
        SDL_MouseMotionEvent.nyrel(e, -2);
        SDL_MouseMotionEvent.nstate(e, 5);
        send();
        push(SDL_EVENT_MOUSE_BUTTON_UP);
        SDL_MouseButtonEvent.nwindowID(e, 7);
        SDL_MouseButtonEvent.nwhich(e, 1);
        SDL_MouseButtonEvent.nbutton(e, (byte) 3);
        SDL_MouseButtonEvent.nclicks(e, (byte) 2);
        SDL_MouseButtonEvent.nx(e, 4);
        SDL_MouseButtonEvent.ny(e, 5);
        send();
        push(SDL_EVENT_MOUSE_WHEEL);
        SDL_MouseWheelEvent.nwindowID(e, 7);
        SDL_MouseWheelEvent.nwhich(e, 1);
        SDL_MouseWheelEvent.nx(e, 0.5f);
        SDL_MouseWheelEvent.ny(e, -1);
        send();
        push(SDL_EVENT_GAMEPAD_AXIS_MOTION);
        SDL_GamepadAxisEvent.nwhich(e, 11);
        SDL_GamepadAxisEvent.naxis(e, (byte) 1);
        SDL_GamepadAxisEvent.nvalue(e, Short.MIN_VALUE);
        send();
        push(SDL_EVENT_GAMEPAD_BUTTON_DOWN);
        SDL_GamepadButtonEvent.nwhich(e, 11);
        SDL_GamepadButtonEvent.nbutton(e, (byte) 2);
        SDL_GamepadButtonEvent.ndown(e, true);
        send();
        push(SDL_EVENT_JOYSTICK_AXIS_MOTION);
        SDL_JoyAxisEvent.nwhich(e, 12);
        SDL_JoyAxisEvent.naxis(e, (byte) 3);
        SDL_JoyAxisEvent.nvalue(e, (short) 32767);
        send();
        push(SDL_EVENT_JOYSTICK_BUTTON_UP);
        SDL_JoyButtonEvent.nwhich(e, 12);
        SDL_JoyButtonEvent.nbutton(e, (byte) 9);
        send();
        assertThat(poll(recorder)).containsExactly(
            "key 123 7 2 4 97 32769 true true",
            "motion 7 1 10.5,20.0 1.0,-2.0 5",
            "button 7 1 3 false 2 4.0,5.0",
            "wheel 7 1 0.5,-1.0",
            "gamepad axis 11 1 -1.0",
            "gamepad button 11 2 true",
            "joystick axis 12 3 1.0",
            "joystick button 12 9 false");
    }

    @Test
    void windowAndSystemEventsAreForwarded() {
        Recorder recorder = new Recorder();
        poll(recorder);
        long e = event.address();
        int[] types = {SDL_EVENT_WINDOW_CLOSE_REQUESTED, SDL_EVENT_WINDOW_MOVED, SDL_EVENT_WINDOW_MINIMIZED,
            SDL_EVENT_WINDOW_RESTORED, SDL_EVENT_WINDOW_FOCUS_GAINED, SDL_EVENT_WINDOW_DISPLAY_CHANGED};
        for (int type : types) {
            push(type);
            SDL_WindowEvent.nwindowID(e, 99); // no such window: forwarded all the same
            SDL_WindowEvent.ndata1(e, 3);
            SDL_WindowEvent.ndata2(e, 4);
            send();
        }
        push(SDL_EVENT_DISPLAY_ADDED);
        send();
        push(SDL_EVENT_QUIT);
        send();
        assertThat(poll(recorder)).containsExactly("close 99", "moved 99 3,4", "minimized 99 true",
            "minimized 99 false", "focus 99 true", "display 99 3", "displays", "quit");
        // Real window changes come back as events.
        Window window = platform.createWindow(WindowOptions.of("events", 300, 200).withHidden(true).withVulkan(false));
        poll(recorder);
        window.setSize(400, 300);
        window.sync();
        assertThat(poll(recorder)).anyMatch(line -> line.startsWith("resized " + window.id() + " 400x300"))
            .anyMatch(line -> line.startsWith("pixels " + window.id()));
    }

    @Test
    void devicesAreListedAndTracked() {
        List<InputDevice> devices = platform.inputDevices();
        for (InputDevice device : devices) {
            assertThat(device.name()).isNotNull();
            assertThat(device.id()).isPositive();
        }
        Recorder recorder = new Recorder();
        poll(recorder);
        long e = event.address();
        push(SDL_EVENT_KEYBOARD_ADDED);
        SDL_KeyboardDeviceEvent.nwhich(e, 4242);
        send();
        push(SDL_EVENT_KEYBOARD_REMOVED);
        SDL_KeyboardDeviceEvent.nwhich(e, 4242);
        send();
        push(SDL_EVENT_JOYSTICK_ADDED); // an id SDL does not know: not a gamepad, cannot be opened
        SDL_KeyboardDeviceEvent.nwhich(e, 4343);
        send();
        push(SDL_EVENT_JOYSTICK_REMOVED); // never opened: not reported
        SDL_KeyboardDeviceEvent.nwhich(e, 4343);
        send();
        assertThat(poll(recorder)).containsExactly("added KEYBOARD 4242", "removed KEYBOARD 4242",
            "added JOYSTICK 4343");
        assertThat(recorder.added.getFirst()).isEqualTo(new InputDevice(4242, InputDevice.Kind.KEYBOARD, "", 0, 0));
        assertThat(Platform.device(InputDevice.Kind.GAMEPAD, 4444).name()).isEmpty();
        assertThat(Platform.axis((short) 0)).isZero();
    }

    @Test
    void moreEventsAndDefaults() {
        Recorder recorder = new Recorder();
        poll(recorder);
        long e = event.address();
        push(org.lwjgl.sdl.SDLEvents.SDL_EVENT_WINDOW_FOCUS_LOST);
        SDL_WindowEvent.nwindowID(e, 5);
        send();
        int[] devices = {org.lwjgl.sdl.SDLEvents.SDL_EVENT_GAMEPAD_ADDED, org.lwjgl.sdl.SDLEvents.SDL_EVENT_GAMEPAD_REMOVED,
            org.lwjgl.sdl.SDLEvents.SDL_EVENT_MOUSE_ADDED, org.lwjgl.sdl.SDLEvents.SDL_EVENT_MOUSE_REMOVED};
        for (int type : devices) {
            push(type);
            SDL_KeyboardDeviceEvent.nwhich(e, 4545); // unknown to SDL: cannot be opened, still reported
            send();
        }
        assertThat(poll(recorder)).containsExactly("focus 5 false", "added GAMEPAD 4545", "removed GAMEPAD 4545",
            "added MOUSE 4545", "removed MOUSE 4545");
        // A listener that overrides nothing ignores everything.
        PlatformListener ignoring = new PlatformListener() {
        };
        ignoring.quitRequested();
        ignoring.windowCloseRequested(1);
        ignoring.windowResized(1, 2, 3);
        ignoring.windowPixelSizeChanged(1, 2, 3);
        ignoring.windowMoved(1, 2, 3);
        ignoring.windowMinimized(1, true);
        ignoring.windowFocusChanged(1, true);
        ignoring.windowDisplayChanged(1, 2);
        ignoring.displaysChanged();
        ignoring.deviceAdded(new InputDevice(1, InputDevice.Kind.MOUSE, "m", 0, 0));
        ignoring.deviceRemoved(InputDevice.Kind.MOUSE, 1);
        ignoring.key(0, 1, 2, 3, 4, 5, true, false);
        ignoring.text(1, "x");
        ignoring.mouseMoved(0, 1, 2, 3, 4, 5, 6, 7);
        ignoring.mouseButton(0, 1, 2, 3, true, 1, 2, 3);
        ignoring.mouseWheel(0, 1, 2, 3, 4);
        ignoring.gamepadAxis(0, 1, 2, 0.5f);
        ignoring.gamepadButton(0, 1, 2, true);
        ignoring.joystickAxis(0, 1, 2, 0.5f);
        ignoring.joystickButton(0, 1, 2, true);
        assertThat(platform.pollEvents(ignoring)).isNotNegative();
    }

    @Test
    void failuresReportSdlErrors() {
        Window window = platform.createWindow(WindowOptions.of("errors", 200, 100).withHidden(true).withVulkan(false));
        DisplayMode nowhere = new DisplayMode(987654, 800, 600, 1, 60, 60, 1, 0);
        assertThatThrownBy(() -> window.exclusiveFullscreen(nowhere)).isInstanceOf(PlatformException.class)
            .hasMessageStartingWith("No fullscreen mode like");
        assertThat(window.mode()).isEqualTo(WindowMode.WINDOWED);
        assertThatThrownBy(() -> window.setSize(-5, -5)).isInstanceOf(PlatformException.class)
            .hasMessageStartingWith("Could not resize the window");
    }

    private long sink;

    @Test
    void pollingAllocatesNothing() {
        PlatformListener counter = new PlatformListener() {
            @Override
            public void key(long timestamp, int window, int keyboard, int scancode, int key, int modifiers,
                boolean down, boolean repeat) {
                sink += scancode;
            }
        };
        platform.pollEvents(counter);
        assertAllocationFree(() -> {
            push(SDL_EVENT_KEY_DOWN);
            SDL_KeyboardEvent.nscancode(event.address(), 4);
            SDL_PushEvent(event);
            sink += platform.pollEvents(counter);
        });
    }
}
