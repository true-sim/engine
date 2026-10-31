package com.truesim.engine.platform;

import static org.lwjgl.sdl.SDLError.SDL_GetError;
import static org.lwjgl.sdl.SDLEvents.SDL_EVENT_DISPLAY_ADDED;
import static org.lwjgl.sdl.SDLEvents.SDL_EVENT_DISPLAY_CURRENT_MODE_CHANGED;
import static org.lwjgl.sdl.SDLEvents.SDL_EVENT_DISPLAY_REMOVED;
import static org.lwjgl.sdl.SDLEvents.SDL_EVENT_FINGER_CANCELED;
import static org.lwjgl.sdl.SDLEvents.SDL_EVENT_FINGER_DOWN;
import static org.lwjgl.sdl.SDLEvents.SDL_EVENT_FINGER_MOTION;
import static org.lwjgl.sdl.SDLEvents.SDL_EVENT_FINGER_UP;
import static org.lwjgl.sdl.SDLEvents.SDL_EVENT_GAMEPAD_ADDED;
import static org.lwjgl.sdl.SDLEvents.SDL_EVENT_GAMEPAD_AXIS_MOTION;
import static org.lwjgl.sdl.SDLEvents.SDL_EVENT_GAMEPAD_BUTTON_DOWN;
import static org.lwjgl.sdl.SDLEvents.SDL_EVENT_GAMEPAD_BUTTON_UP;
import static org.lwjgl.sdl.SDLEvents.SDL_EVENT_GAMEPAD_REMAPPED;
import static org.lwjgl.sdl.SDLEvents.SDL_EVENT_GAMEPAD_REMOVED;
import static org.lwjgl.sdl.SDLEvents.SDL_EVENT_GAMEPAD_SENSOR_UPDATE;
import static org.lwjgl.sdl.SDLEvents.SDL_EVENT_GAMEPAD_TOUCHPAD_DOWN;
import static org.lwjgl.sdl.SDLEvents.SDL_EVENT_GAMEPAD_TOUCHPAD_MOTION;
import static org.lwjgl.sdl.SDLEvents.SDL_EVENT_GAMEPAD_TOUCHPAD_UP;
import static org.lwjgl.sdl.SDLEvents.SDL_EVENT_JOYSTICK_ADDED;
import static org.lwjgl.sdl.SDLEvents.SDL_EVENT_JOYSTICK_AXIS_MOTION;
import static org.lwjgl.sdl.SDLEvents.SDL_EVENT_JOYSTICK_BALL_MOTION;
import static org.lwjgl.sdl.SDLEvents.SDL_EVENT_JOYSTICK_BATTERY_UPDATED;
import static org.lwjgl.sdl.SDLEvents.SDL_EVENT_JOYSTICK_BUTTON_DOWN;
import static org.lwjgl.sdl.SDLEvents.SDL_EVENT_JOYSTICK_BUTTON_UP;
import static org.lwjgl.sdl.SDLEvents.SDL_EVENT_JOYSTICK_HAT_MOTION;
import static org.lwjgl.sdl.SDLEvents.SDL_EVENT_JOYSTICK_REMOVED;
import static org.lwjgl.sdl.SDLEvents.SDL_EVENT_KEYBOARD_ADDED;
import static org.lwjgl.sdl.SDLEvents.SDL_EVENT_KEYBOARD_REMOVED;
import static org.lwjgl.sdl.SDLEvents.SDL_EVENT_KEY_DOWN;
import static org.lwjgl.sdl.SDLEvents.SDL_EVENT_KEY_UP;
import static org.lwjgl.sdl.SDLEvents.SDL_EVENT_MOUSE_ADDED;
import static org.lwjgl.sdl.SDLEvents.SDL_EVENT_MOUSE_BUTTON_DOWN;
import static org.lwjgl.sdl.SDLEvents.SDL_EVENT_MOUSE_BUTTON_UP;
import static org.lwjgl.sdl.SDLEvents.SDL_EVENT_MOUSE_MOTION;
import static org.lwjgl.sdl.SDLEvents.SDL_EVENT_MOUSE_REMOVED;
import static org.lwjgl.sdl.SDLEvents.SDL_EVENT_MOUSE_WHEEL;
import static org.lwjgl.sdl.SDLEvents.SDL_EVENT_PEN_AXIS;
import static org.lwjgl.sdl.SDLEvents.SDL_EVENT_PEN_BUTTON_DOWN;
import static org.lwjgl.sdl.SDLEvents.SDL_EVENT_PEN_BUTTON_UP;
import static org.lwjgl.sdl.SDLEvents.SDL_EVENT_PEN_DOWN;
import static org.lwjgl.sdl.SDLEvents.SDL_EVENT_PEN_MOTION;
import static org.lwjgl.sdl.SDLEvents.SDL_EVENT_PEN_PROXIMITY_IN;
import static org.lwjgl.sdl.SDLEvents.SDL_EVENT_PEN_PROXIMITY_OUT;
import static org.lwjgl.sdl.SDLEvents.SDL_EVENT_PEN_UP;
import static org.lwjgl.sdl.SDLEvents.SDL_EVENT_QUIT;
import static org.lwjgl.sdl.SDLEvents.SDL_EVENT_SENSOR_UPDATE;
import static org.lwjgl.sdl.SDLEvents.SDL_EVENT_TEXT_INPUT;
import static org.lwjgl.sdl.SDLEvents.SDL_EVENT_WINDOW_CLOSE_REQUESTED;
import static org.lwjgl.sdl.SDLEvents.SDL_EVENT_WINDOW_DISPLAY_CHANGED;
import static org.lwjgl.sdl.SDLEvents.SDL_EVENT_WINDOW_FOCUS_GAINED;
import static org.lwjgl.sdl.SDLEvents.SDL_EVENT_WINDOW_FOCUS_LOST;
import static org.lwjgl.sdl.SDLEvents.SDL_EVENT_WINDOW_MINIMIZED;
import static org.lwjgl.sdl.SDLEvents.SDL_EVENT_WINDOW_MOVED;
import static org.lwjgl.sdl.SDLEvents.SDL_EVENT_WINDOW_PIXEL_SIZE_CHANGED;
import static org.lwjgl.sdl.SDLEvents.SDL_EVENT_WINDOW_RESIZED;
import static org.lwjgl.sdl.SDLEvents.SDL_EVENT_WINDOW_RESTORED;
import static org.lwjgl.sdl.SDLEvents.SDL_PollEvent;
import static org.lwjgl.sdl.SDLGamepad.SDL_AddGamepadMapping;
import static org.lwjgl.sdl.SDLGamepad.SDL_GetGamepadJoystick;
import static org.lwjgl.sdl.SDLGamepad.SDL_GetGamepadNameForID;
import static org.lwjgl.sdl.SDLGamepad.SDL_GetGamepads;
import static org.lwjgl.sdl.SDLGamepad.SDL_IsGamepad;
import static org.lwjgl.sdl.SDLGamepad.SDL_OpenGamepad;
import static org.lwjgl.sdl.SDLHaptic.SDL_GetHapticNameForID;
import static org.lwjgl.sdl.SDLHaptic.SDL_GetHaptics;
import static org.lwjgl.sdl.SDLHaptic.SDL_IsMouseHaptic;
import static org.lwjgl.sdl.SDLHaptic.SDL_OpenHaptic;
import static org.lwjgl.sdl.SDLHaptic.SDL_OpenHapticFromMouse;
import static org.lwjgl.sdl.SDLInit.SDL_INIT_EVENTS;
import static org.lwjgl.sdl.SDLInit.SDL_INIT_GAMEPAD;
import static org.lwjgl.sdl.SDLInit.SDL_INIT_HAPTIC;
import static org.lwjgl.sdl.SDLInit.SDL_INIT_JOYSTICK;
import static org.lwjgl.sdl.SDLInit.SDL_INIT_SENSOR;
import static org.lwjgl.sdl.SDLInit.SDL_INIT_VIDEO;
import static org.lwjgl.sdl.SDLInit.SDL_Init;
import static org.lwjgl.sdl.SDLInit.SDL_WasInit;
import static org.lwjgl.sdl.SDLMouse.SDL_CursorVisible;
import static org.lwjgl.sdl.SDLMouse.SDL_HideCursor;
import static org.lwjgl.sdl.SDLMouse.SDL_ShowCursor;
import static org.lwjgl.sdl.SDLVideo.SDL_DisableScreenSaver;
import static org.lwjgl.sdl.SDLVideo.SDL_EnableScreenSaver;
import static org.lwjgl.sdl.SDLVideo.SDL_ScreenSaverEnabled;
import static org.lwjgl.sdl.SDLVulkan.SDL_Vulkan_GetInstanceExtensions;
import static org.lwjgl.sdl.SDLInit.SDL_Quit;
import static org.lwjgl.sdl.SDLJoystick.SDL_GetJoystickNameForID;
import static org.lwjgl.sdl.SDLJoystick.SDL_GetJoystickProductForID;
import static org.lwjgl.sdl.SDLJoystick.SDL_GetJoystickVendorForID;
import static org.lwjgl.sdl.SDLJoystick.SDL_GetJoysticks;
import static org.lwjgl.sdl.SDLJoystick.SDL_OpenJoystick;
import static org.lwjgl.sdl.SDLKeyboard.SDL_GetKeyboardNameForID;
import static org.lwjgl.sdl.SDLKeyboard.SDL_GetKeyboards;
import static org.lwjgl.sdl.SDLMouse.SDL_GetMice;
import static org.lwjgl.sdl.SDLMouse.SDL_GetMouseNameForID;
import static org.lwjgl.sdl.SDLPen.SDL_GetPenDeviceType;
import static org.lwjgl.sdl.SDLSensor.SDL_GetSensorNameForID;
import static org.lwjgl.sdl.SDLSensor.SDL_GetSensors;
import static org.lwjgl.sdl.SDLSensor.SDL_OpenSensor;
import static org.lwjgl.sdl.SDLStdinc.SDL_free;
import static org.lwjgl.sdl.SDLTouch.SDL_GetTouchDeviceName;
import static org.lwjgl.sdl.SDLTouch.SDL_GetTouchDeviceType;
import static org.lwjgl.sdl.SDLTouch.SDL_GetTouchDevices;
import static org.lwjgl.sdl.SDLVideo.SDL_GetCurrentDisplayMode;
import static org.lwjgl.sdl.SDLVideo.SDL_GetCurrentVideoDriver;
import static org.lwjgl.sdl.SDLVideo.SDL_GetDesktopDisplayMode;
import static org.lwjgl.sdl.SDLVideo.SDL_GetDisplayBounds;
import static org.lwjgl.sdl.SDLVideo.SDL_GetDisplayContentScale;
import static org.lwjgl.sdl.SDLVideo.SDL_GetDisplayName;
import static org.lwjgl.sdl.SDLVideo.SDL_GetDisplayUsableBounds;
import static org.lwjgl.sdl.SDLVideo.SDL_GetDisplays;
import static org.lwjgl.sdl.SDLVideo.SDL_GetFullscreenDisplayModes;
import static org.lwjgl.sdl.SDLVideo.SDL_GetPrimaryDisplay;

import com.truesim.annotations.AllowAllocation;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.lwjgl.PointerBuffer;
import org.lwjgl.sdl.SDL_CommonEvent;
import org.lwjgl.sdl.SDL_DisplayMode;
import org.lwjgl.sdl.SDL_Event;
import org.lwjgl.sdl.SDL_GamepadDeviceEvent;
import org.lwjgl.sdl.SDL_GamepadAxisEvent;
import org.lwjgl.sdl.SDL_GamepadButtonEvent;
import org.lwjgl.sdl.SDL_GamepadSensorEvent;
import org.lwjgl.sdl.SDL_GamepadTouchpadEvent;
import org.lwjgl.sdl.SDL_JoyAxisEvent;
import org.lwjgl.sdl.SDL_JoyBallEvent;
import org.lwjgl.sdl.SDL_JoyBatteryEvent;
import org.lwjgl.sdl.SDL_JoyButtonEvent;
import org.lwjgl.sdl.SDL_JoyHatEvent;
import org.lwjgl.sdl.SDL_KeyboardDeviceEvent;
import org.lwjgl.sdl.SDL_KeyboardEvent;
import org.lwjgl.sdl.SDL_MouseButtonEvent;
import org.lwjgl.sdl.SDL_MouseMotionEvent;
import org.lwjgl.sdl.SDL_MouseWheelEvent;
import org.lwjgl.sdl.SDL_PenAxisEvent;
import org.lwjgl.sdl.SDL_PenButtonEvent;
import org.lwjgl.sdl.SDL_PenMotionEvent;
import org.lwjgl.sdl.SDL_PenProximityEvent;
import org.lwjgl.sdl.SDL_PenTouchEvent;
import org.lwjgl.sdl.SDL_Rect;
import org.lwjgl.sdl.SDL_SensorEvent;
import org.lwjgl.sdl.SDL_TextInputEvent;
import org.lwjgl.sdl.SDL_TouchFingerEvent;
import org.lwjgl.sdl.SDL_WindowEvent;
import org.lwjgl.system.MemoryStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The engine's platform layer on SDL3: windows, displays, events and input devices. One platform exists at a time, and
 * it belongs to the thread that started it (on macOS that must be the main thread): create windows, list displays and
 * poll events there. Rendering and simulation can run on other threads.
 *
 * <pre>{@code
 * try (Platform platform = Platform.start()) {
 *     Window window = platform.createWindow(WindowOptions.of("true-sim", 1280, 720));
 *     while (running) {
 *         platform.pollEvents(listener);
 *         render(window);
 *     }
 * }
 * }</pre>
 *
 * <p>Gamepads and joysticks are opened as they connect, so their events arrive without further calls, and their
 * {@link Controller handles} give their outputs: rumble, LEDs, force feedback. Every value passed out is an engine
 * type ({@link Key}, {@link GamepadButton}, ...); SDL stays inside this class and {@code Sdl}. The video driver
 * can be chosen with the {@code SDL_VIDEO_DRIVER} environment variable, e.g. {@code offscreen} for headless tests.
 */
public final class Platform implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(Platform.class);
    private static final AtomicBoolean RUNNING = new AtomicBoolean();

    private final Thread owner = Thread.currentThread();
    private final SDL_Event event = SDL_Event.calloc();
    private final List<Window> windows = new ArrayList<>();
    private final List<Gamepad> gamepads = new ArrayList<>();
    private final List<Joystick> joysticks = new ArrayList<>();
    private final List<Haptic> openHaptics = new ArrayList<>();
    private final List<Sensor> openSensors = new ArrayList<>();
    private boolean closed;

    @AllowAllocation("starting up")
    private Platform() {
        if (!SDL_Init(SDL_INIT_VIDEO | SDL_INIT_EVENTS | SDL_INIT_GAMEPAD | SDL_INIT_JOYSTICK | SDL_INIT_HAPTIC
            | SDL_INIT_SENSOR)) {
            event.free();
            throw new PlatformException("SDL could not start: " + SDL_GetError());
        }
        for (InputDevice device : inputDevices()) {
            open(device.kind(), device.id());
        }
        LOG.info("Platform started on video driver {}", SDL_GetCurrentVideoDriver());
    }

    /** Starts the platform on this thread. Only one platform runs at a time. */
    @AllowAllocation("starting up")
    public static Platform start() {
        if (!RUNNING.compareAndSet(false, true)) {
            throw new IllegalStateException("A platform is already running");
        }
        try {
            return new Platform();
        } catch (RuntimeException e) {
            RUNNING.set(false);
            throw e;
        }
    }

    /** The video driver of the platform running, or {@code null} before one has started video. Safe from any thread. */
    public static String runningVideoDriver() {
        return SDL_WasInit(SDL_INIT_VIDEO) == 0 ? null : SDL_GetCurrentVideoDriver();
    }

    /** The video driver in use, e.g. {@code windows}, {@code wayland}, {@code x11}, {@code cocoa} or {@code offscreen}. */
    public String videoDriver() {
        checkThread();
        return SDL_GetCurrentVideoDriver();
    }

    // ---------------------------------------------------------------- windows

    /** Opens a window; several can be open at once. */
    @AllowAllocation("opening a window")
    public Window createWindow(WindowOptions options) {
        checkThread();
        Window window = new Window(this, options);
        windows.add(window);
        return window;
    }

    /**
     * Opens a window as {@code options} say, then changed to {@code settings} (saved preferences, say) before it shows.
     */
    @AllowAllocation("opening a window")
    public Window createWindow(WindowOptions options, WindowSettings settings) {
        Window window = createWindow(options.withHidden(true));
        try {
            window.apply(settings);
            if (!options.hidden()) {
                window.show();
            }
        } catch (RuntimeException e) {
            window.close();
            throw e;
        }
        return window;
    }

    /**
     * Opens a window as {@code options} say that follows the window settings {@code settings} holds (a scoped view for
     * it, say): applied before it shows, and again whenever they change ({@link Window#follow}).
     */
    @AllowAllocation("opening a window")
    public Window createWindow(WindowOptions options, com.truesim.engine.settings.EngineSettings settings) {
        Window window = createWindow(options.withHidden(true));
        try {
            window.follow(settings);
            if (!options.hidden()) {
                window.show();
            }
        } catch (RuntimeException e) {
            window.close();
            throw e;
        }
        return window;
    }

    /** The open windows. */
    @AllowAllocation("a snapshot")
    public List<Window> windows() {
        checkThread();
        return List.copyOf(windows);
    }

    /** The open window with {@code id}, or {@code null}. */
    public Window window(int id) {
        checkThread();
        for (int i = 0; i < windows.size(); i++) {
            Window window = windows.get(i);
            if (window.id() == id) {
                return window;
            }
        }
        return null;
    }

    void closed(Window window) {
        windows.remove(window);
    }

    // ---------------------------------------------------------------- displays

    /** The connected displays, primary first. */
    @AllowAllocation("a snapshot")
    public List<Display> displays() {
        checkThread();
        IntBuffer ids = SDL_GetDisplays();
        if (ids == null) {
            throw new PlatformException("Could not list displays: " + SDL_GetError());
        }
        int primary = SDL_GetPrimaryDisplay();
        List<Display> displays = new ArrayList<>();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            SDL_Rect bounds = SDL_Rect.malloc(stack);
            SDL_Rect usable = SDL_Rect.malloc(stack);
            for (int i = 0; i < ids.remaining(); i++) {
                int id = ids.get(i);
                SDL_GetDisplayBounds(id, bounds);
                SDL_GetDisplayUsableBounds(id, usable);
                Display display = new Display(id, SDL_GetDisplayName(id), bounds(bounds), bounds(usable),
                    SDL_GetDisplayContentScale(id), mode(SDL_GetCurrentDisplayMode(id)),
                    mode(SDL_GetDesktopDisplayMode(id)), fullscreenModes(id), id == primary);
                displays.add(id == primary ? 0 : displays.size(), display);
            }
        } finally {
            SDL_free(ids);
        }
        return List.copyOf(displays);
    }

    /**
     * The key {@link WindowSettings#display()} names display {@code id} by, stable from run to run: its name, or with
     * displays of the same name (identical monitors), its name and its place among them left to right, top to bottom
     * from 1 ({@code "DELL U2720Q #2"}).
     */
    @AllowAllocation("a snapshot")
    public String displayKey(int id) {
        checkThread();
        return key(id, displays());
    }

    /** Display {@code id}'s key among {@code displays} ({@link #displayKey}); empty if it is not among them. */
    @AllowAllocation("a snapshot")
    static String key(int id, List<Display> displays) {
        Display of = null;
        List<Display> same = new ArrayList<>();
        for (Display display : displays) {
            of = display.id() == id ? display : of;
        }
        if (of == null || of.name() == null) {
            return "";
        }
        for (Display display : displays) {
            if (of.name().equals(display.name())) {
                same.add(display);
            }
        }
        if (same.size() == 1) {
            return of.name();
        }
        same.sort(java.util.Comparator.comparingInt((Display d) -> d.bounds().x()).thenComparingInt(d -> d.bounds().y()));
        return of.name() + " #" + (same.indexOf(of) + 1);
    }

    /** The id of the display {@code key} names ({@link #displayKey}): the primary one if empty or none has it. */
    @AllowAllocation("a snapshot")
    int displayNamed(String key) {
        return named(key, displays(), SDL_GetPrimaryDisplay());
    }

    /** The id of the display {@code key} names among {@code displays}: {@code primary} if empty or none has it. */
    @AllowAllocation("a snapshot")
    static int named(String key, List<Display> displays, int primary) {
        if (key.isEmpty()) {
            return primary;
        }
        for (Display display : displays) {
            if (key.equals(key(display.id(), displays))) {
                return display.id();
            }
        }
        for (Display display : displays) { // a monitor of that name, wherever it went
            if (display.name() != null && key.startsWith(display.name())) {
                return display.id();
            }
        }
        return primary;
    }

    // ---------------------------------------------------------------- cursor and screen

    /** Shows or hides the mouse cursor, over every window. */
    public void setCursorVisible(boolean visible) {
        checkThread();
        if (visible) {
            SDL_ShowCursor();
        } else {
            SDL_HideCursor();
        }
    }

    public boolean isCursorVisible() {
        checkThread();
        return SDL_CursorVisible();
    }

    /** Keeps the screen saver and display sleep off while the platform runs (SDL's default), or lets them be. */
    public void setKeepScreenOn(boolean on) {
        checkThread();
        if (on) {
            SDL_DisableScreenSaver();
        } else {
            SDL_EnableScreenSaver();
        }
    }

    public boolean keepScreenOn() {
        checkThread();
        return !SDL_ScreenSaverEnabled();
    }

    /**
     * The Vulkan instance extensions windows need for their surfaces, or none before a platform has started video.
     * Safe from any thread.
     */
    @AllowAllocation("a snapshot")
    public static List<String> vulkanInstanceExtensions() {
        // SDL crashes when asked before its video subsystem runs, so check first.
        if (SDL_WasInit(SDL_INIT_VIDEO) == 0) {
            return List.of();
        }
        PointerBuffer names = SDL_Vulkan_GetInstanceExtensions();
        if (names == null) {
            return List.of();
        }
        List<String> list = new ArrayList<>();
        for (int i = 0; i < names.remaining(); i++) {
            list.add(names.getStringUTF8(i));
        }
        return List.copyOf(list);
    }

    @AllowAllocation("a snapshot")
    private static List<DisplayMode> fullscreenModes(int display) {
        PointerBuffer modes = SDL_GetFullscreenDisplayModes(display);
        if (modes == null) {
            return List.of();
        }
        try {
            List<DisplayMode> list = new ArrayList<>();
            for (int i = 0; i < modes.remaining(); i++) {
                list.add(mode(SDL_DisplayMode.create(modes.get(i))));
            }
            return List.copyOf(list);
        } finally {
            SDL_free(modes);
        }
    }

    @AllowAllocation("a snapshot")
    static DisplayMode mode(SDL_DisplayMode mode) {
        return mode == null ? null : new DisplayMode(mode.displayID(), mode.w(), mode.h(), mode.pixel_density(),
            mode.refresh_rate(), mode.refresh_rate_numerator(), mode.refresh_rate_denominator(), mode.format());
    }

    @AllowAllocation("a snapshot")
    private static Bounds bounds(SDL_Rect rect) {
        return new Bounds(rect.x(), rect.y(), rect.w(), rect.h());
    }

    // ---------------------------------------------------------------- input devices

    /**
     * The connected keyboards, mice, gamepads and joysticks (joysticks that are gamepads are listed as gamepads).
     * Sensors and force feedback devices are listed by {@link #sensors()} and {@link #haptics()}.
     */
    @AllowAllocation("a snapshot")
    public List<InputDevice> inputDevices() {
        checkThread();
        List<InputDevice> devices = new ArrayList<>();
        list(devices, SDL_GetKeyboards(), InputDevice.Kind.KEYBOARD);
        list(devices, SDL_GetMice(), InputDevice.Kind.MOUSE);
        list(devices, SDL_GetGamepads(), InputDevice.Kind.GAMEPAD);
        IntBuffer joysticks = SDL_GetJoysticks();
        IntBuffer plain = joysticks == null ? null : filterJoysticks(joysticks);
        list(devices, plain, InputDevice.Kind.JOYSTICK);
        return List.copyOf(devices);
    }

    @AllowAllocation("a snapshot")
    private static IntBuffer filterJoysticks(IntBuffer joysticks) {
        IntBuffer plain = IntBuffer.allocate(joysticks.remaining());
        for (int i = 0; i < joysticks.remaining(); i++) {
            if (!SDL_IsGamepad(joysticks.get(i))) {
                plain.put(joysticks.get(i));
            }
        }
        SDL_free(joysticks);
        return plain.flip();
    }

    @AllowAllocation("a snapshot")
    private static void list(List<InputDevice> devices, IntBuffer ids, InputDevice.Kind kind) {
        if (ids == null) {
            return;
        }
        for (int i = 0; i < ids.remaining(); i++) {
            devices.add(device(kind, ids.get(i)));
        }
        if (ids.isDirect()) {
            SDL_free(ids);
        }
    }

    @AllowAllocation("devices change rarely")
    static InputDevice device(InputDevice.Kind kind, int id) {
        String name = switch (kind) {
            case KEYBOARD -> SDL_GetKeyboardNameForID(id);
            case MOUSE -> SDL_GetMouseNameForID(id);
            case GAMEPAD -> SDL_GetGamepadNameForID(id);
            case JOYSTICK -> SDL_GetJoystickNameForID(id);
            case SENSOR -> SDL_GetSensorNameForID(id);
            case HAPTIC -> SDL_GetHapticNameForID(id);
        };
        boolean stick = kind == InputDevice.Kind.GAMEPAD || kind == InputDevice.Kind.JOYSTICK;
        int vendor = stick ? SDL_GetJoystickVendorForID(id) & 0xffff : 0;
        int product = stick ? SDL_GetJoystickProductForID(id) & 0xffff : 0;
        return new InputDevice(id, kind, name == null ? "" : name, vendor, product);
    }

    // ---------------------------------------------------------------- gamepads and joysticks

    /** The connected gamepad with {@code id}, or {@code null}. */
    public Gamepad gamepad(int id) {
        checkThread();
        for (int i = 0; i < gamepads.size(); i++) {
            if (gamepads.get(i).id() == id) {
                return gamepads.get(i);
            }
        }
        return null;
    }

    /** The connected gamepads, in the order they connected. */
    @AllowAllocation("a snapshot")
    public List<Gamepad> gamepads() {
        checkThread();
        return List.copyOf(gamepads);
    }

    /** The connected joystick (that is not a gamepad) with {@code id}, or {@code null}. */
    public Joystick joystick(int id) {
        checkThread();
        for (int i = 0; i < joysticks.size(); i++) {
            if (joysticks.get(i).id() == id) {
                return joysticks.get(i);
            }
        }
        return null;
    }

    /** The connected joysticks that are not gamepads, in the order they connected. */
    @AllowAllocation("a snapshot")
    public List<Joystick> joysticks() {
        checkThread();
        return List.copyOf(joysticks);
    }

    /**
     * Adds gamepad mappings in the SDL_GameControllerDB format (one per line), e.g. for controllers the built-in
     * database lacks; returns how many were added or updated. Connected gamepads remap at once.
     */
    @AllowAllocation("mappings are added rarely")
    public int addGamepadMappings(String mappings) {
        checkThread();
        int added = 0;
        for (String line : mappings.split("\n")) {
            String mapping = line.strip();
            if (!mapping.isEmpty() && !mapping.startsWith("#")) {
                int result = SDL_AddGamepadMapping(mapping);
                if (result < 0) {
                    throw new PlatformException("Invalid gamepad mapping: " + SDL_GetError());
                }
                added++;
            }
        }
        return added;
    }

    /** Opens gamepads and joysticks, so their events arrive and their handles exist. */
    @AllowAllocation("devices change rarely")
    private void open(InputDevice.Kind kind, int id) {
        if (kind == InputDevice.Kind.GAMEPAD && gamepad(id) == null) {
            long handle = SDL_OpenGamepad(id);
            if (handle != 0) {
                gamepads.add(new Gamepad(this, id, handle, SDL_GetGamepadJoystick(handle)));
            }
        } else if (kind == InputDevice.Kind.JOYSTICK && joystick(id) == null) {
            long handle = SDL_OpenJoystick(id);
            if (handle != 0) {
                joysticks.add(new Joystick(this, id, handle));
            }
        }
    }

    @AllowAllocation("devices change rarely")
    private void close(InputDevice.Kind kind, int id) {
        Controller controller = kind == InputDevice.Kind.GAMEPAD ? gamepad(id) : joystick(id);
        if (controller != null) {
            (kind == InputDevice.Kind.GAMEPAD ? gamepads : joysticks).remove(controller);
            controller.close();
        }
    }

    // ---------------------------------------------------------------- sensors, haptics, touch and pens

    /** The device's own motion sensors, e.g. a phone's; gamepad sensors are on the {@link Gamepad}. */
    @AllowAllocation("a snapshot")
    public List<InputDevice> sensors() {
        checkThread();
        List<InputDevice> devices = new ArrayList<>();
        list(devices, SDL_GetSensors(), InputDevice.Kind.SENSOR);
        return List.copyOf(devices);
    }

    /** Opens a sensor from {@link #sensors()}: its readings arrive through {@link PlatformListener#sensor}. */
    @AllowAllocation("opening a device")
    public Sensor openSensor(int id) {
        checkThread();
        long handle = SDL_OpenSensor(id);
        if (handle == 0) {
            throw new PlatformException("Could not open sensor " + id + ": " + SDL_GetError());
        }
        Sensor sensor = new Sensor(this, handle);
        openSensors.add(sensor);
        return sensor;
    }

    void closed(Sensor sensor) {
        openSensors.remove(sensor);
    }

    /** The force feedback devices, including those of joysticks; a joystick's is easier to get with its handle. */
    @AllowAllocation("a snapshot")
    public List<InputDevice> haptics() {
        checkThread();
        List<InputDevice> devices = new ArrayList<>();
        list(devices, SDL_GetHaptics(), InputDevice.Kind.HAPTIC);
        return List.copyOf(devices);
    }

    /** Opens a force feedback device from {@link #haptics()}. */
    @AllowAllocation("opening a device")
    public Haptic openHaptic(int id) {
        checkThread();
        long handle = SDL_OpenHaptic(id);
        if (handle == 0) {
            throw new PlatformException("Could not open haptic device " + id + ": " + SDL_GetError());
        }
        return opened(new Haptic(this, handle));
    }

    /** The mouse's force feedback device, or {@code null} when it has none. */
    @AllowAllocation("opening a device")
    public Haptic mouseHaptic() {
        checkThread();
        if (!SDL_IsMouseHaptic()) {
            return null;
        }
        long handle = SDL_OpenHapticFromMouse();
        return handle == 0 ? null : opened(new Haptic(this, handle));
    }

    @AllowAllocation("opening a device")
    private Haptic opened(Haptic haptic) {
        openHaptics.add(haptic);
        return haptic;
    }

    void closed(Haptic haptic) {
        openHaptics.remove(haptic);
    }

    /** The touchscreens and trackpads that report touches. */
    @AllowAllocation("a snapshot")
    public List<TouchDevice> touchDevices() {
        checkThread();
        LongBuffer ids = SDL_GetTouchDevices();
        if (ids == null) {
            return List.of();
        }
        try {
            List<TouchDevice> devices = new ArrayList<>();
            for (int i = 0; i < ids.remaining(); i++) {
                long id = ids.get(i);
                devices.add(new TouchDevice(id, Controller.orEmpty(SDL_GetTouchDeviceName(id)),
                    Sdl.touchDeviceType(SDL_GetTouchDeviceType(id))));
            }
            return List.copyOf(devices);
        } finally {
            SDL_free(ids);
        }
    }

    /** Whether {@code pen} (from pen events) draws on a screen or on a separate tablet. */
    public PenType penType(int pen) {
        checkThread();
        return Sdl.penType(SDL_GetPenDeviceType(pen));
    }

    // ---------------------------------------------------------------- events

    /**
     * Forwards every pending event to {@code listener} and returns how many there were. Call once per frame on the
     * platform's thread. Allocates nothing, except for typed text and connected devices.
     */
    public int pollEvents(PlatformListener listener) {
        checkThread();
        int count = 0;
        while (SDL_PollEvent(event)) {
            count++;
            dispatch(event.address(), listener);
        }
        return count;
    }

    private void dispatch(long e, PlatformListener listener) {
        switch (SDL_CommonEvent.ntype(e)) {
            case SDL_EVENT_QUIT -> listener.quitRequested();
            case SDL_EVENT_WINDOW_CLOSE_REQUESTED -> listener.windowCloseRequested(SDL_WindowEvent.nwindowID(e));
            case SDL_EVENT_WINDOW_RESIZED -> listener.windowResized(
                SDL_WindowEvent.nwindowID(e), SDL_WindowEvent.ndata1(e), SDL_WindowEvent.ndata2(e));
            case SDL_EVENT_WINDOW_PIXEL_SIZE_CHANGED -> listener.windowPixelSizeChanged(
                SDL_WindowEvent.nwindowID(e), SDL_WindowEvent.ndata1(e), SDL_WindowEvent.ndata2(e));
            case SDL_EVENT_WINDOW_MOVED -> listener.windowMoved(
                SDL_WindowEvent.nwindowID(e), SDL_WindowEvent.ndata1(e), SDL_WindowEvent.ndata2(e));
            case SDL_EVENT_WINDOW_MINIMIZED -> listener.windowMinimized(SDL_WindowEvent.nwindowID(e), true);
            case SDL_EVENT_WINDOW_RESTORED -> listener.windowMinimized(SDL_WindowEvent.nwindowID(e), false);
            case SDL_EVENT_WINDOW_FOCUS_GAINED -> listener.windowFocusChanged(SDL_WindowEvent.nwindowID(e), true);
            case SDL_EVENT_WINDOW_FOCUS_LOST -> listener.windowFocusChanged(SDL_WindowEvent.nwindowID(e), false);
            case SDL_EVENT_WINDOW_DISPLAY_CHANGED -> listener.windowDisplayChanged(
                SDL_WindowEvent.nwindowID(e), SDL_WindowEvent.ndata1(e));
            case SDL_EVENT_DISPLAY_ADDED, SDL_EVENT_DISPLAY_REMOVED, SDL_EVENT_DISPLAY_CURRENT_MODE_CHANGED ->
                listener.displaysChanged();
            case SDL_EVENT_KEY_DOWN, SDL_EVENT_KEY_UP -> listener.key(SDL_KeyboardEvent.ntimestamp(e),
                SDL_KeyboardEvent.nwindowID(e), SDL_KeyboardEvent.nwhich(e), Sdl.key(SDL_KeyboardEvent.nscancode(e)),
                Sdl.character(SDL_KeyboardEvent.nkey(e)), Sdl.modifiers(SDL_KeyboardEvent.nmod(e) & 0xffff),
                SDL_KeyboardEvent.ndown(e), SDL_KeyboardEvent.nrepeat(e));
            case SDL_EVENT_TEXT_INPUT -> text(e, listener);
            case SDL_EVENT_MOUSE_MOTION -> listener.mouseMoved(SDL_MouseMotionEvent.ntimestamp(e),
                SDL_MouseMotionEvent.nwindowID(e), SDL_MouseMotionEvent.nwhich(e), SDL_MouseMotionEvent.nx(e),
                SDL_MouseMotionEvent.ny(e), SDL_MouseMotionEvent.nxrel(e), SDL_MouseMotionEvent.nyrel(e),
                Sdl.mouseButtons(SDL_MouseMotionEvent.nstate(e)));
            case SDL_EVENT_MOUSE_BUTTON_DOWN, SDL_EVENT_MOUSE_BUTTON_UP -> listener.mouseButton(
                SDL_MouseButtonEvent.ntimestamp(e), SDL_MouseButtonEvent.nwindowID(e), SDL_MouseButtonEvent.nwhich(e),
                Sdl.mouseButton(SDL_MouseButtonEvent.nbutton(e) & 0xff), SDL_MouseButtonEvent.ndown(e),
                SDL_MouseButtonEvent.nclicks(e) & 0xff, SDL_MouseButtonEvent.nx(e), SDL_MouseButtonEvent.ny(e));
            case SDL_EVENT_MOUSE_WHEEL -> listener.mouseWheel(SDL_MouseWheelEvent.ntimestamp(e),
                SDL_MouseWheelEvent.nwindowID(e), SDL_MouseWheelEvent.nwhich(e), SDL_MouseWheelEvent.nx(e),
                SDL_MouseWheelEvent.ny(e));
            case SDL_EVENT_GAMEPAD_AXIS_MOTION -> listener.gamepadAxis(SDL_GamepadAxisEvent.ntimestamp(e),
                SDL_GamepadAxisEvent.nwhich(e), Sdl.gamepadAxis(SDL_GamepadAxisEvent.naxis(e) & 0xff),
                axis(SDL_GamepadAxisEvent.nvalue(e)));
            case SDL_EVENT_GAMEPAD_BUTTON_DOWN, SDL_EVENT_GAMEPAD_BUTTON_UP -> listener.gamepadButton(
                SDL_GamepadButtonEvent.ntimestamp(e), SDL_GamepadButtonEvent.nwhich(e),
                Sdl.gamepadButton(SDL_GamepadButtonEvent.nbutton(e) & 0xff), SDL_GamepadButtonEvent.ndown(e));
            case SDL_EVENT_GAMEPAD_TOUCHPAD_DOWN -> touchpad(e, TouchPhase.DOWN, listener);
            case SDL_EVENT_GAMEPAD_TOUCHPAD_MOTION -> touchpad(e, TouchPhase.MOVED, listener);
            case SDL_EVENT_GAMEPAD_TOUCHPAD_UP -> touchpad(e, TouchPhase.UP, listener);
            case SDL_EVENT_GAMEPAD_SENSOR_UPDATE -> listener.gamepadSensor(SDL_GamepadSensorEvent.ntimestamp(e),
                SDL_GamepadSensorEvent.nwhich(e), Sdl.sensor(SDL_GamepadSensorEvent.nsensor(e)),
                SDL_GamepadSensorEvent.ndata(e, 0), SDL_GamepadSensorEvent.ndata(e, 1),
                SDL_GamepadSensorEvent.ndata(e, 2), SDL_GamepadSensorEvent.nsensor_timestamp(e));
            case SDL_EVENT_GAMEPAD_REMAPPED -> listener.gamepadRemapped(SDL_GamepadDeviceEvent.nwhich(e));
            case SDL_EVENT_JOYSTICK_AXIS_MOTION -> joystickAxis(e, listener);
            case SDL_EVENT_JOYSTICK_BUTTON_DOWN, SDL_EVENT_JOYSTICK_BUTTON_UP -> joystickButton(e, listener);
            case SDL_EVENT_JOYSTICK_HAT_MOTION -> joystickHat(e, listener);
            case SDL_EVENT_JOYSTICK_BALL_MOTION -> listener.joystickBall(SDL_JoyBallEvent.ntimestamp(e),
                SDL_JoyBallEvent.nwhich(e), SDL_JoyBallEvent.nball(e) & 0xff, SDL_JoyBallEvent.nxrel(e),
                SDL_JoyBallEvent.nyrel(e));
            case SDL_EVENT_JOYSTICK_BATTERY_UPDATED -> listener.batteryChanged(SDL_JoyBatteryEvent.nwhich(e),
                Sdl.power(SDL_JoyBatteryEvent.nstate(e)), SDL_JoyBatteryEvent.npercent(e));
            case SDL_EVENT_SENSOR_UPDATE -> listener.sensor(SDL_SensorEvent.ntimestamp(e), SDL_SensorEvent.nwhich(e),
                SDL_SensorEvent.ndata(e, 0), SDL_SensorEvent.ndata(e, 1), SDL_SensorEvent.ndata(e, 2),
                SDL_SensorEvent.nsensor_timestamp(e));
            case SDL_EVENT_FINGER_DOWN -> finger(e, TouchPhase.DOWN, listener);
            case SDL_EVENT_FINGER_MOTION -> finger(e, TouchPhase.MOVED, listener);
            case SDL_EVENT_FINGER_UP -> finger(e, TouchPhase.UP, listener);
            case SDL_EVENT_FINGER_CANCELED -> finger(e, TouchPhase.CANCELED, listener);
            case SDL_EVENT_PEN_PROXIMITY_IN, SDL_EVENT_PEN_PROXIMITY_OUT -> listener.penProximity(
                SDL_PenProximityEvent.ntimestamp(e), SDL_PenProximityEvent.nwindowID(e), SDL_PenProximityEvent.nwhich(e),
                SDL_CommonEvent.ntype(e) == SDL_EVENT_PEN_PROXIMITY_IN);
            case SDL_EVENT_PEN_DOWN, SDL_EVENT_PEN_UP -> listener.penTouch(SDL_PenTouchEvent.ntimestamp(e),
                SDL_PenTouchEvent.nwindowID(e), SDL_PenTouchEvent.nwhich(e), SDL_PenTouchEvent.nx(e),
                SDL_PenTouchEvent.ny(e), SDL_PenTouchEvent.neraser(e), SDL_PenTouchEvent.ndown(e));
            case SDL_EVENT_PEN_BUTTON_DOWN, SDL_EVENT_PEN_BUTTON_UP -> listener.penButton(
                SDL_PenButtonEvent.ntimestamp(e), SDL_PenButtonEvent.nwindowID(e), SDL_PenButtonEvent.nwhich(e),
                SDL_PenButtonEvent.nx(e), SDL_PenButtonEvent.ny(e), SDL_PenButtonEvent.nbutton(e) & 0xff,
                SDL_PenButtonEvent.ndown(e));
            case SDL_EVENT_PEN_MOTION -> listener.penMoved(SDL_PenMotionEvent.ntimestamp(e),
                SDL_PenMotionEvent.nwindowID(e), SDL_PenMotionEvent.nwhich(e), SDL_PenMotionEvent.nx(e),
                SDL_PenMotionEvent.ny(e), Sdl.penState(SDL_PenMotionEvent.npen_state(e)));
            case SDL_EVENT_PEN_AXIS -> listener.penAxis(SDL_PenAxisEvent.ntimestamp(e), SDL_PenAxisEvent.nwindowID(e),
                SDL_PenAxisEvent.nwhich(e), SDL_PenAxisEvent.nx(e), SDL_PenAxisEvent.ny(e),
                Sdl.penAxis(SDL_PenAxisEvent.naxis(e)), SDL_PenAxisEvent.nvalue(e));
            case SDL_EVENT_KEYBOARD_ADDED -> added(InputDevice.Kind.KEYBOARD, e, listener);
            case SDL_EVENT_MOUSE_ADDED -> added(InputDevice.Kind.MOUSE, e, listener);
            case SDL_EVENT_GAMEPAD_ADDED -> added(InputDevice.Kind.GAMEPAD, e, listener);
            case SDL_EVENT_JOYSTICK_ADDED -> addedJoystick(e, listener);
            case SDL_EVENT_KEYBOARD_REMOVED -> removed(InputDevice.Kind.KEYBOARD, e, listener);
            case SDL_EVENT_MOUSE_REMOVED -> removed(InputDevice.Kind.MOUSE, e, listener);
            case SDL_EVENT_GAMEPAD_REMOVED -> removed(InputDevice.Kind.GAMEPAD, e, listener);
            case SDL_EVENT_JOYSTICK_REMOVED -> removedJoystick(e, listener);
            default -> {
                // not forwarded
            }
        }
    }

    /** A stick or trigger from SDL's signed 16-bit range to [-1, 1]. */
    static float axis(short value) {
        return Math.max(value / 32767f, -1f);
    }

    @AllowAllocation("typed text is a string")
    private static void text(long e, PlatformListener listener) {
        listener.text(SDL_TextInputEvent.nwindowID(e), SDL_TextInputEvent.ntextString(e));
    }

    private static void touchpad(long e, TouchPhase phase, PlatformListener listener) {
        listener.gamepadTouchpad(SDL_GamepadTouchpadEvent.ntimestamp(e), SDL_GamepadTouchpadEvent.nwhich(e),
            SDL_GamepadTouchpadEvent.ntouchpad(e), SDL_GamepadTouchpadEvent.nfinger(e), phase,
            SDL_GamepadTouchpadEvent.nx(e), SDL_GamepadTouchpadEvent.ny(e), SDL_GamepadTouchpadEvent.npressure(e));
    }

    private static void finger(long e, TouchPhase phase, PlatformListener listener) {
        listener.touch(SDL_TouchFingerEvent.ntimestamp(e), SDL_TouchFingerEvent.nwindowID(e),
            SDL_TouchFingerEvent.ntouchID(e), SDL_TouchFingerEvent.nfingerID(e), phase, SDL_TouchFingerEvent.nx(e),
            SDL_TouchFingerEvent.ny(e), SDL_TouchFingerEvent.ndx(e), SDL_TouchFingerEvent.ndy(e),
            SDL_TouchFingerEvent.npressure(e));
    }

    /** Joysticks that are gamepads report through the gamepad events instead. */
    private static void joystickAxis(long e, PlatformListener listener) {
        int joystick = SDL_JoyAxisEvent.nwhich(e);
        if (!SDL_IsGamepad(joystick)) {
            listener.joystickAxis(SDL_JoyAxisEvent.ntimestamp(e), joystick, SDL_JoyAxisEvent.naxis(e) & 0xff,
                axis(SDL_JoyAxisEvent.nvalue(e)));
        }
    }

    private static void joystickButton(long e, PlatformListener listener) {
        int joystick = SDL_JoyButtonEvent.nwhich(e);
        if (!SDL_IsGamepad(joystick)) {
            listener.joystickButton(SDL_JoyButtonEvent.ntimestamp(e), joystick, SDL_JoyButtonEvent.nbutton(e) & 0xff,
                SDL_JoyButtonEvent.ndown(e));
        }
    }

    private static void joystickHat(long e, PlatformListener listener) {
        int joystick = SDL_JoyHatEvent.nwhich(e);
        if (!SDL_IsGamepad(joystick)) {
            listener.joystickHat(SDL_JoyHatEvent.ntimestamp(e), joystick, SDL_JoyHatEvent.nhat(e) & 0xff,
                Sdl.hat(SDL_JoyHatEvent.nvalue(e) & 0xff));
        }
    }

    @AllowAllocation("devices change rarely")
    private void added(InputDevice.Kind kind, long e, PlatformListener listener) {
        int id = SDL_KeyboardDeviceEvent.nwhich(e); // all device events keep the id at the same place
        open(kind, id);
        listener.deviceAdded(device(kind, id));
    }

    @AllowAllocation("devices change rarely")
    private void addedJoystick(long e, PlatformListener listener) {
        int id = SDL_KeyboardDeviceEvent.nwhich(e);
        if (!SDL_IsGamepad(id)) {
            added(InputDevice.Kind.JOYSTICK, e, listener);
        }
    }

    @AllowAllocation("devices change rarely")
    private void removed(InputDevice.Kind kind, long e, PlatformListener listener) {
        int id = SDL_KeyboardDeviceEvent.nwhich(e);
        close(kind, id);
        listener.deviceRemoved(kind, id);
    }

    @AllowAllocation("devices change rarely")
    private void removedJoystick(long e, PlatformListener listener) {
        int id = SDL_KeyboardDeviceEvent.nwhich(e);
        if (joystick(id) != null) {
            removed(InputDevice.Kind.JOYSTICK, e, listener);
        }
    }

    // ---------------------------------------------------------------- lifecycle

    void checkThread() {
        if (Thread.currentThread() != owner) {
            throw new IllegalStateException("The platform belongs to thread " + owner.getName());
        }
        if (closed) {
            throw new IllegalStateException("The platform is closed");
        }
    }

    /** Closes all windows and devices and shuts SDL down. */
    @Override
    @AllowAllocation("shutting down")
    public void close() {
        if (closed) {
            return;
        }
        checkThread();
        for (Window window : List.copyOf(windows)) {
            window.close();
        }
        for (Haptic haptic : List.copyOf(openHaptics)) {
            haptic.close();
        }
        for (Sensor sensor : List.copyOf(openSensors)) {
            sensor.close();
        }
        for (Controller controller : gamepads) {
            controller.close();
        }
        for (Controller controller : joysticks) {
            controller.close();
        }
        gamepads.clear();
        joysticks.clear();
        closed = true;
        event.free();
        SDL_Quit();
        RUNNING.set(false);
    }
}
