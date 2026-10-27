package com.truesim.engine.platform;

import static org.lwjgl.sdl.SDLGamepad.SDL_CloseGamepad;
import static org.lwjgl.sdl.SDLGamepad.SDL_GamepadHasAxis;
import static org.lwjgl.sdl.SDLGamepad.SDL_GamepadHasButton;
import static org.lwjgl.sdl.SDLGamepad.SDL_GamepadHasSensor;
import static org.lwjgl.sdl.SDLGamepad.SDL_GamepadSensorEnabled;
import static org.lwjgl.sdl.SDLGamepad.SDL_GetGamepadAxis;
import static org.lwjgl.sdl.SDLGamepad.SDL_GetGamepadButton;
import static org.lwjgl.sdl.SDLGamepad.SDL_GetGamepadButtonLabel;
import static org.lwjgl.sdl.SDLGamepad.SDL_GetGamepadMapping;
import static org.lwjgl.sdl.SDLGamepad.SDL_GetGamepadSensorDataRate;
import static org.lwjgl.sdl.SDLGamepad.SDL_GetGamepadType;
import static org.lwjgl.sdl.SDLGamepad.SDL_GetNumGamepadTouchpadFingers;
import static org.lwjgl.sdl.SDLGamepad.SDL_GetNumGamepadTouchpads;
import static org.lwjgl.sdl.SDLGamepad.SDL_GetRealGamepadType;
import static org.lwjgl.sdl.SDLGamepad.SDL_SetGamepadSensorEnabled;
import static org.lwjgl.sdl.SDLGamepad.nSDL_GetGamepadSensorData;
import static org.lwjgl.sdl.SDLGamepad.nSDL_GetGamepadTouchpadFinger;
import static org.lwjgl.system.MemoryUtil.memGetByte;
import static org.lwjgl.system.MemoryUtil.memGetFloat;

import com.truesim.annotations.AllowAllocation;
import org.lwjgl.system.MemoryStack;

/**
 * A gamepad: a controller with a known layout of {@link GamepadButton}s and {@link GamepadAxis axes}, as on Xbox,
 * PlayStation and Switch pads. Besides the {@linkplain Controller outputs} it has motion sensors and touchpads on
 * some models; enable a sensor to receive {@link PlatformListener#gamepadSensor} events. Button and axis events
 * arrive through {@link PlatformListener#gamepadButton} and {@link PlatformListener#gamepadAxis}; the state can also
 * be read here. Reading allocates nothing.
 */
public final class Gamepad extends Controller {

    private final long gamepad;

    Gamepad(Platform platform, int id, long gamepad, long joystick) {
        super(platform, id, joystick);
        this.gamepad = gamepad;
    }

    @Override
    public InputDevice.Kind kind() {
        return InputDevice.Kind.GAMEPAD;
    }

    /** The kind of gamepad, possibly overridden by a mapping; e.g. for button glyphs. */
    public GamepadType type() {
        check();
        return Sdl.gamepadType(SDL_GetGamepadType(gamepad));
    }

    /** The kind of gamepad the hardware is, ignoring mappings. */
    public GamepadType realType() {
        check();
        return Sdl.gamepadType(SDL_GetRealGamepadType(gamepad));
    }

    /** What is printed on {@code button}, e.g. {@link ButtonLabel#CROSS} for {@link GamepadButton#SOUTH} on PS5. */
    public ButtonLabel label(GamepadButton button) {
        check();
        return Sdl.label(SDL_GetGamepadButtonLabel(gamepad, Sdl.gamepadButton(button)));
    }

    public boolean hasButton(GamepadButton button) {
        check();
        return SDL_GamepadHasButton(gamepad, Sdl.gamepadButton(button));
    }

    /** Whether {@code button} is held now. */
    public boolean button(GamepadButton button) {
        check();
        return SDL_GetGamepadButton(gamepad, Sdl.gamepadButton(button));
    }

    public boolean hasAxis(GamepadAxis axis) {
        check();
        return SDL_GamepadHasAxis(gamepad, Sdl.gamepadAxis(axis));
    }

    /** The axis' position now: sticks from -1 to 1, triggers from 0 to 1. */
    public float axis(GamepadAxis axis) {
        check();
        return Platform.axis(SDL_GetGamepadAxis(gamepad, Sdl.gamepadAxis(axis)));
    }

    /** The mapping in the SDL_GameControllerDB format that turns this device's inputs into the gamepad layout. */
    @AllowAllocation("a mapping is a string")
    public String mapping() {
        check();
        return orEmpty(SDL_GetGamepadMapping(gamepad));
    }

    // ---------------------------------------------------------------- sensors

    public boolean hasSensor(SensorType sensor) {
        check();
        return SDL_GamepadHasSensor(gamepad, Sdl.sensor(sensor));
    }

    /** Turns a sensor's {@link PlatformListener#gamepadSensor} events and readings on or off; off by default. */
    public boolean setSensorEnabled(SensorType sensor, boolean enabled) {
        check();
        return SDL_SetGamepadSensorEnabled(gamepad, Sdl.sensor(sensor), enabled);
    }

    public boolean isSensorEnabled(SensorType sensor) {
        check();
        return SDL_GamepadSensorEnabled(gamepad, Sdl.sensor(sensor));
    }

    /** Readings per second; 0 when unknown. */
    public float sensorRate(SensorType sensor) {
        check();
        return SDL_GetGamepadSensorDataRate(gamepad, Sdl.sensor(sensor));
    }

    /** Writes the latest x, y, z of an enabled sensor into {@code out}; false when there is none. */
    public boolean readSensor(SensorType sensor, float[] out) {
        check();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long data = stack.nmalloc(Float.BYTES, 3 * Float.BYTES);
            if (!nSDL_GetGamepadSensorData(gamepad, Sdl.sensor(sensor), data, 3)) {
                return false;
            }
            for (int i = 0; i < 3; i++) {
                out[i] = memGetFloat(data + (long) i * Float.BYTES);
            }
            return true;
        }
    }

    // ---------------------------------------------------------------- touchpads

    /** How many touchpads the gamepad has, e.g. 1 on PlayStation pads. */
    public int touchpadCount() {
        check();
        return SDL_GetNumGamepadTouchpads(gamepad);
    }

    /** How many fingers {@code touchpad} tracks at once. */
    public int touchpadFingerCount(int touchpad) {
        check();
        return SDL_GetNumGamepadTouchpadFingers(gamepad, touchpad);
    }

    /**
     * Writes finger {@code finger}'s x and y (0 to 1, from the top left) and pressure on {@code touchpad} into
     * {@code out} and returns whether it touches.
     */
    public boolean touchpadFinger(int touchpad, int finger, float[] out) {
        check();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long down = stack.ncalloc(1, 1, 1);
            long values = stack.nmalloc(Float.BYTES, 3 * Float.BYTES);
            if (!nSDL_GetGamepadTouchpadFinger(gamepad, touchpad, finger, down, values, values + Float.BYTES,
                values + 2 * Float.BYTES)) {
                return false;
            }
            for (int i = 0; i < 3; i++) {
                out[i] = memGetFloat(values + (long) i * Float.BYTES);
            }
            return memGetByte(down) != 0;
        }
    }

    @Override
    void closeHandle() {
        SDL_CloseGamepad(gamepad);
    }
}
