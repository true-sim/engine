package com.truesim.engine.platform;

import static org.lwjgl.sdl.SDLHaptic.SDL_HAPTIC_AUTOCENTER;
import static org.lwjgl.sdl.SDLHaptic.SDL_HAPTIC_CARTESIAN;
import static org.lwjgl.sdl.SDLHaptic.SDL_HAPTIC_CONSTANT;
import static org.lwjgl.sdl.SDLHaptic.SDL_HAPTIC_CUSTOM;
import static org.lwjgl.sdl.SDLHaptic.SDL_HAPTIC_DAMPER;
import static org.lwjgl.sdl.SDLHaptic.SDL_HAPTIC_FRICTION;
import static org.lwjgl.sdl.SDLHaptic.SDL_HAPTIC_GAIN;
import static org.lwjgl.sdl.SDLHaptic.SDL_HAPTIC_INERTIA;
import static org.lwjgl.sdl.SDLHaptic.SDL_HAPTIC_LEFTRIGHT;
import static org.lwjgl.sdl.SDLHaptic.SDL_HAPTIC_PAUSE;
import static org.lwjgl.sdl.SDLHaptic.SDL_HAPTIC_POLAR;
import static org.lwjgl.sdl.SDLHaptic.SDL_HAPTIC_RAMP;
import static org.lwjgl.sdl.SDLHaptic.SDL_HAPTIC_SAWTOOTHDOWN;
import static org.lwjgl.sdl.SDLHaptic.SDL_HAPTIC_SAWTOOTHUP;
import static org.lwjgl.sdl.SDLHaptic.SDL_HAPTIC_SINE;
import static org.lwjgl.sdl.SDLHaptic.SDL_HAPTIC_SPHERICAL;
import static org.lwjgl.sdl.SDLHaptic.SDL_HAPTIC_SPRING;
import static org.lwjgl.sdl.SDLHaptic.SDL_HAPTIC_SQUARE;
import static org.lwjgl.sdl.SDLHaptic.SDL_HAPTIC_STATUS;
import static org.lwjgl.sdl.SDLHaptic.SDL_HAPTIC_STEERING_AXIS;
import static org.lwjgl.sdl.SDLHaptic.SDL_HAPTIC_TRIANGLE;
import static org.lwjgl.sdl.SDLJoystick.SDL_HAT_DOWN;
import static org.lwjgl.sdl.SDLJoystick.SDL_HAT_LEFT;
import static org.lwjgl.sdl.SDLJoystick.SDL_HAT_LEFTDOWN;
import static org.lwjgl.sdl.SDLJoystick.SDL_HAT_LEFTUP;
import static org.lwjgl.sdl.SDLJoystick.SDL_HAT_RIGHT;
import static org.lwjgl.sdl.SDLJoystick.SDL_HAT_RIGHTDOWN;
import static org.lwjgl.sdl.SDLJoystick.SDL_HAT_RIGHTUP;
import static org.lwjgl.sdl.SDLJoystick.SDL_HAT_UP;
import static org.lwjgl.sdl.SDLJoystick.SDL_JOYSTICK_CONNECTION_WIRED;
import static org.lwjgl.sdl.SDLJoystick.SDL_JOYSTICK_CONNECTION_WIRELESS;
import static org.lwjgl.sdl.SDLKeycode.SDLK_SCANCODE_MASK;
import static org.lwjgl.sdl.SDLKeycode.SDL_KMOD_CAPS;
import static org.lwjgl.sdl.SDLKeycode.SDL_KMOD_LALT;
import static org.lwjgl.sdl.SDLKeycode.SDL_KMOD_LCTRL;
import static org.lwjgl.sdl.SDLKeycode.SDL_KMOD_LEVEL5;
import static org.lwjgl.sdl.SDLKeycode.SDL_KMOD_LGUI;
import static org.lwjgl.sdl.SDLKeycode.SDL_KMOD_LSHIFT;
import static org.lwjgl.sdl.SDLKeycode.SDL_KMOD_MODE;
import static org.lwjgl.sdl.SDLKeycode.SDL_KMOD_NUM;
import static org.lwjgl.sdl.SDLKeycode.SDL_KMOD_RALT;
import static org.lwjgl.sdl.SDLKeycode.SDL_KMOD_RCTRL;
import static org.lwjgl.sdl.SDLKeycode.SDL_KMOD_RGUI;
import static org.lwjgl.sdl.SDLKeycode.SDL_KMOD_RSHIFT;
import static org.lwjgl.sdl.SDLKeycode.SDL_KMOD_SCROLL;
import static org.lwjgl.sdl.SDLPen.SDL_PEN_DEVICE_TYPE_DIRECT;
import static org.lwjgl.sdl.SDLPen.SDL_PEN_DEVICE_TYPE_INDIRECT;
import static org.lwjgl.sdl.SDLPen.SDL_PEN_INPUT_DOWN;
import static org.lwjgl.sdl.SDLPen.SDL_PEN_INPUT_ERASER_TIP;
import static org.lwjgl.sdl.SDLPen.SDL_PEN_INPUT_IN_PROXIMITY;
import static org.lwjgl.sdl.SDLPower.SDL_POWERSTATE_CHARGED;
import static org.lwjgl.sdl.SDLPower.SDL_POWERSTATE_CHARGING;
import static org.lwjgl.sdl.SDLPower.SDL_POWERSTATE_NO_BATTERY;
import static org.lwjgl.sdl.SDLPower.SDL_POWERSTATE_ON_BATTERY;
import static org.lwjgl.sdl.SDLTouch.SDL_TOUCH_DEVICE_DIRECT;
import static org.lwjgl.sdl.SDLTouch.SDL_TOUCH_DEVICE_INDIRECT_ABSOLUTE;
import static org.lwjgl.sdl.SDLTouch.SDL_TOUCH_DEVICE_INDIRECT_RELATIVE;
import static org.lwjgl.system.MemoryUtil.memPutAddress;
import static org.lwjgl.system.MemoryUtil.memPutByte;
import static org.lwjgl.system.MemoryUtil.memPutInt;
import static org.lwjgl.system.MemoryUtil.memPutShort;
import static org.lwjgl.system.MemoryUtil.nmemAlloc;

import com.truesim.annotations.AllowAllocation;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.lwjgl.sdl.SDL_HapticCondition;
import org.lwjgl.sdl.SDL_HapticConstant;
import org.lwjgl.sdl.SDL_HapticCustom;
import org.lwjgl.sdl.SDL_HapticDirection;
import org.lwjgl.sdl.SDL_HapticEffect;
import org.lwjgl.sdl.SDL_HapticLeftRight;
import org.lwjgl.sdl.SDL_HapticPeriodic;
import org.lwjgl.sdl.SDL_HapticRamp;

/**
 * The SDL3 backend's mapping between SDL's values and the engine's types. Everything SDL-specific about input is here
 * and in {@link Platform}; the rest of the engine sees only engine types, so another backend replaces only these.
 * Lookups are table- or switch-based and allocate nothing.
 */
final class Sdl {

    private static final Key[] KEYS = new Key[512];
    private static final GamepadButton[] GAMEPAD_BUTTONS = GamepadButton.values();
    private static final GamepadAxis[] GAMEPAD_AXES = GamepadAxis.values();
    private static final GamepadType[] GAMEPAD_TYPES = GamepadType.values();
    private static final ButtonLabel[] LABELS = ButtonLabel.values();
    private static final JoystickType[] JOYSTICK_TYPES = JoystickType.values();
    private static final SensorType[] SENSORS = SensorType.values();
    private static final PenAxis[] PEN_AXES = PenAxis.values();
    private static final MouseButton[] MOUSE_BUTTONS = MouseButton.values();

    static {
        // SDL scancodes are USB HID usages, which Key keeps.
        for (Key key : Key.values()) {
            KEYS[key.usage()] = key;
        }
    }

    private Sdl() {
    }

    // ---------------------------------------------------------------- keyboard and mouse

    static Key key(int scancode) {
        Key key = scancode >= 0 && scancode < KEYS.length ? KEYS[scancode] : null;
        return key == null ? Key.UNKNOWN : key;
    }

    /** The character a keycode stands for, or 0 for keys without one (SDL marks those with the scancode bit). */
    static int character(int keycode) {
        return (keycode & SDLK_SCANCODE_MASK) != 0 ? 0 : keycode;
    }

    static int modifiers(int mod) {
        return bit(mod, SDL_KMOD_LSHIFT, KeyModifiers.LEFT_SHIFT)
            | bit(mod, SDL_KMOD_RSHIFT, KeyModifiers.RIGHT_SHIFT)
            | bit(mod, SDL_KMOD_LCTRL, KeyModifiers.LEFT_CONTROL)
            | bit(mod, SDL_KMOD_RCTRL, KeyModifiers.RIGHT_CONTROL)
            | bit(mod, SDL_KMOD_LALT, KeyModifiers.LEFT_ALT)
            | bit(mod, SDL_KMOD_RALT, KeyModifiers.RIGHT_ALT)
            | bit(mod, SDL_KMOD_LGUI, KeyModifiers.LEFT_META)
            | bit(mod, SDL_KMOD_RGUI, KeyModifiers.RIGHT_META)
            | bit(mod, SDL_KMOD_MODE, KeyModifiers.MODE)
            | bit(mod, SDL_KMOD_LEVEL5, KeyModifiers.LEVEL5)
            | bit(mod, SDL_KMOD_NUM, KeyModifiers.NUM_LOCK)
            | bit(mod, SDL_KMOD_CAPS, KeyModifiers.CAPS_LOCK)
            | bit(mod, SDL_KMOD_SCROLL, KeyModifiers.SCROLL_LOCK);
    }

    private static int bit(int value, int from, int to) {
        return (value & from) != 0 ? to : 0;
    }

    /** SDL numbers buttons from 1: left, middle, right, X1 (back), X2 (forward). */
    static MouseButton mouseButton(int button) {
        return button >= 1 && button <= 5 ? MOUSE_BUTTONS[button - 1] : MouseButton.OTHER;
    }

    /** SDL's button state has button n at bit n - 1, as {@link MouseButton#mask()}. */
    static int mouseButtons(int state) {
        return state & 0x1f;
    }

    // ---------------------------------------------------------------- gamepads and joysticks

    static GamepadButton gamepadButton(int button) {
        return GAMEPAD_BUTTONS[button];
    }

    static int gamepadButton(GamepadButton button) {
        return button.ordinal();
    }

    static GamepadAxis gamepadAxis(int axis) {
        return GAMEPAD_AXES[axis];
    }

    static int gamepadAxis(GamepadAxis axis) {
        return axis.ordinal();
    }

    static GamepadType gamepadType(int type) {
        return type > 0 && type < GAMEPAD_TYPES.length ? GAMEPAD_TYPES[type] : GamepadType.UNKNOWN;
    }

    static ButtonLabel label(int label) {
        return label > 0 && label < LABELS.length ? LABELS[label] : ButtonLabel.UNKNOWN;
    }

    static JoystickType joystickType(int type) {
        return type > 0 && type < JOYSTICK_TYPES.length ? JOYSTICK_TYPES[type] : JoystickType.UNKNOWN;
    }

    static SensorType sensor(int type) {
        return type > 0 && type < SENSORS.length ? SENSORS[type] : SensorType.UNKNOWN;
    }

    static int sensor(SensorType type) {
        return type.ordinal();
    }

    static ConnectionState connection(int state) {
        return switch (state) {
            case SDL_JOYSTICK_CONNECTION_WIRED -> ConnectionState.WIRED;
            case SDL_JOYSTICK_CONNECTION_WIRELESS -> ConnectionState.WIRELESS;
            default -> ConnectionState.UNKNOWN;
        };
    }

    static PowerState power(int state) {
        return switch (state) {
            case SDL_POWERSTATE_ON_BATTERY -> PowerState.ON_BATTERY;
            case SDL_POWERSTATE_NO_BATTERY -> PowerState.NO_BATTERY;
            case SDL_POWERSTATE_CHARGING -> PowerState.CHARGING;
            case SDL_POWERSTATE_CHARGED -> PowerState.CHARGED;
            default -> PowerState.UNKNOWN;
        };
    }

    static HatPosition hat(int value) {
        return switch (value) {
            case SDL_HAT_UP -> HatPosition.UP;
            case SDL_HAT_RIGHT -> HatPosition.RIGHT;
            case SDL_HAT_DOWN -> HatPosition.DOWN;
            case SDL_HAT_LEFT -> HatPosition.LEFT;
            case SDL_HAT_RIGHTUP -> HatPosition.UP_RIGHT;
            case SDL_HAT_RIGHTDOWN -> HatPosition.DOWN_RIGHT;
            case SDL_HAT_LEFTUP -> HatPosition.UP_LEFT;
            case SDL_HAT_LEFTDOWN -> HatPosition.DOWN_LEFT;
            default -> HatPosition.CENTERED;
        };
    }

    // ---------------------------------------------------------------- touch and pens

    static TouchDeviceType touchDeviceType(int type) {
        return switch (type) {
            case SDL_TOUCH_DEVICE_DIRECT -> TouchDeviceType.DIRECT;
            case SDL_TOUCH_DEVICE_INDIRECT_ABSOLUTE -> TouchDeviceType.INDIRECT_ABSOLUTE;
            case SDL_TOUCH_DEVICE_INDIRECT_RELATIVE -> TouchDeviceType.INDIRECT_RELATIVE;
            default -> TouchDeviceType.UNKNOWN;
        };
    }

    static PenType penType(int type) {
        return switch (type) {
            case SDL_PEN_DEVICE_TYPE_DIRECT -> PenType.DIRECT;
            case SDL_PEN_DEVICE_TYPE_INDIRECT -> PenType.INDIRECT;
            default -> PenType.UNKNOWN;
        };
    }

    static PenAxis penAxis(int axis) {
        return PEN_AXES[axis];
    }

    /** SDL keeps buttons 1 to 5 at bits 1 to 5, as {@link PenState} does. */
    static int penState(int state) {
        return bit(state, SDL_PEN_INPUT_DOWN, PenState.DOWN)
            | (state & 0x3e)
            | bit(state, SDL_PEN_INPUT_ERASER_TIP, PenState.ERASER)
            | bit(state, SDL_PEN_INPUT_IN_PROXIMITY, PenState.IN_PROXIMITY);
    }

    // ---------------------------------------------------------------- haptics

    @AllowAllocation("queried once")
    static Set<HapticFeature> hapticFeatures(int features) {
        Set<HapticFeature> set = EnumSet.noneOf(HapticFeature.class);
        for (HapticFeature feature : HapticFeature.values()) {
            if ((features & hapticFeature(feature)) != 0) {
                set.add(feature);
            }
        }
        return set;
    }

    static int hapticFeature(HapticFeature feature) {
        return switch (feature) {
            case CONSTANT -> SDL_HAPTIC_CONSTANT;
            case SINE -> SDL_HAPTIC_SINE;
            case SQUARE -> SDL_HAPTIC_SQUARE;
            case TRIANGLE -> SDL_HAPTIC_TRIANGLE;
            case SAWTOOTH_UP -> SDL_HAPTIC_SAWTOOTHUP;
            case SAWTOOTH_DOWN -> SDL_HAPTIC_SAWTOOTHDOWN;
            case RAMP -> SDL_HAPTIC_RAMP;
            case SPRING -> SDL_HAPTIC_SPRING;
            case DAMPER -> SDL_HAPTIC_DAMPER;
            case INERTIA -> SDL_HAPTIC_INERTIA;
            case FRICTION -> SDL_HAPTIC_FRICTION;
            case LEFT_RIGHT -> SDL_HAPTIC_LEFTRIGHT;
            case CUSTOM -> SDL_HAPTIC_CUSTOM;
            case GAIN -> SDL_HAPTIC_GAIN;
            case AUTOCENTER -> SDL_HAPTIC_AUTOCENTER;
            case STATUS -> SDL_HAPTIC_STATUS;
            case PAUSE -> SDL_HAPTIC_PAUSE;
        };
    }

    /**
     * Writes {@code effect} into the zeroed {@code SDL_HapticEffect} at {@code address}. Returns the address of native
     * sample memory a custom effect points to, which must stay until the effect is destroyed (free it with
     * {@code nmemFree}); 0 for other effects.
     */
    static long writeEffect(HapticEffect effect, long address) {
        memPutShort(address + SDL_HapticEffect.TYPE, (short) hapticFeature(effect.feature()));
        switch (effect) {
            case HapticEffect.Constant c -> {
                long s = address + SDL_HapticEffect.CONSTANT;
                direction(c.direction(), s + SDL_HapticConstant.DIRECTION);
                timing(c.timing(), s + SDL_HapticConstant.LENGTH, s + SDL_HapticConstant.DELAY,
                    s + SDL_HapticConstant.BUTTON, s + SDL_HapticConstant.INTERVAL);
                memPutShort(s + SDL_HapticConstant.LEVEL, signed(c.level()));
                envelope(c.envelope(), s + SDL_HapticConstant.ATTACK_LENGTH, s + SDL_HapticConstant.ATTACK_LEVEL,
                    s + SDL_HapticConstant.FADE_LENGTH, s + SDL_HapticConstant.FADE_LEVEL);
                return 0;
            }
            case HapticEffect.Periodic p -> {
                long s = address + SDL_HapticEffect.PERIODIC;
                direction(p.direction(), s + SDL_HapticPeriodic.DIRECTION);
                timing(p.timing(), s + SDL_HapticPeriodic.LENGTH, s + SDL_HapticPeriodic.DELAY,
                    s + SDL_HapticPeriodic.BUTTON, s + SDL_HapticPeriodic.INTERVAL);
                memPutShort(s + SDL_HapticPeriodic.PERIOD, millis(p.period()));
                memPutShort(s + SDL_HapticPeriodic.MAGNITUDE, signed(p.magnitude()));
                memPutShort(s + SDL_HapticPeriodic.OFFSET, signed(p.offset()));
                memPutShort(s + SDL_HapticPeriodic.PHASE, (short) Math.floorMod(Math.round(p.phase() * 100), 36000));
                envelope(p.envelope(), s + SDL_HapticPeriodic.ATTACK_LENGTH, s + SDL_HapticPeriodic.ATTACK_LEVEL,
                    s + SDL_HapticPeriodic.FADE_LENGTH, s + SDL_HapticPeriodic.FADE_LEVEL);
                return 0;
            }
            case HapticEffect.Condition c -> {
                long s = address + SDL_HapticEffect.CONDITION;
                timing(c.timing(), s + SDL_HapticCondition.LENGTH, s + SDL_HapticCondition.DELAY,
                    s + SDL_HapticCondition.BUTTON, s + SDL_HapticCondition.INTERVAL);
                List<HapticEffect.ConditionAxis> axes = c.axes();
                for (int i = 0; i < axes.size(); i++) {
                    HapticEffect.ConditionAxis axis = axes.get(i);
                    int at = i * Short.BYTES;
                    memPutShort(s + SDL_HapticCondition.RIGHT_SAT + at, unsigned(axis.rightSaturation()));
                    memPutShort(s + SDL_HapticCondition.LEFT_SAT + at, unsigned(axis.leftSaturation()));
                    memPutShort(s + SDL_HapticCondition.RIGHT_COEFF + at, signed(axis.rightCoefficient()));
                    memPutShort(s + SDL_HapticCondition.LEFT_COEFF + at, signed(axis.leftCoefficient()));
                    memPutShort(s + SDL_HapticCondition.DEADBAND + at, unsigned(axis.deadband()));
                    memPutShort(s + SDL_HapticCondition.CENTER + at, signed(axis.center()));
                }
                return 0;
            }
            case HapticEffect.Ramp r -> {
                long s = address + SDL_HapticEffect.RAMP;
                direction(r.direction(), s + SDL_HapticRamp.DIRECTION);
                timing(r.timing(), s + SDL_HapticRamp.LENGTH, s + SDL_HapticRamp.DELAY, s + SDL_HapticRamp.BUTTON,
                    s + SDL_HapticRamp.INTERVAL);
                memPutShort(s + SDL_HapticRamp.START, signed(r.start()));
                memPutShort(s + SDL_HapticRamp.END, signed(r.end()));
                envelope(r.envelope(), s + SDL_HapticRamp.ATTACK_LENGTH, s + SDL_HapticRamp.ATTACK_LEVEL,
                    s + SDL_HapticRamp.FADE_LENGTH, s + SDL_HapticRamp.FADE_LEVEL);
                return 0;
            }
            case HapticEffect.LeftRight l -> {
                long s = address + SDL_HapticEffect.LEFTRIGHT;
                memPutInt(s + SDL_HapticLeftRight.LENGTH, length(l.length()));
                memPutShort(s + SDL_HapticLeftRight.LARGE_MAGNITUDE, unsigned(l.large()));
                memPutShort(s + SDL_HapticLeftRight.SMALL_MAGNITUDE, unsigned(l.small()));
                return 0;
            }
            case HapticEffect.Custom c -> {
                long s = address + SDL_HapticEffect.CUSTOM;
                direction(c.direction(), s + SDL_HapticCustom.DIRECTION);
                timing(c.timing(), s + SDL_HapticCustom.LENGTH, s + SDL_HapticCustom.DELAY,
                    s + SDL_HapticCustom.BUTTON, s + SDL_HapticCustom.INTERVAL);
                float[] samples = c.samples();
                memPutByte(s + SDL_HapticCustom.CHANNELS, (byte) c.channels());
                memPutShort(s + SDL_HapticCustom.PERIOD, millis(c.period()));
                memPutShort(s + SDL_HapticCustom.SAMPLES, (short) (samples.length / c.channels()));
                long data = nmemAlloc((long) samples.length * Short.BYTES);
                for (int i = 0; i < samples.length; i++) {
                    memPutShort(data + (long) i * Short.BYTES, signed(samples[i]));
                }
                memPutAddress(s + SDL_HapticCustom.DATA, data);
                envelope(c.envelope(), s + SDL_HapticCustom.ATTACK_LENGTH, s + SDL_HapticCustom.ATTACK_LEVEL,
                    s + SDL_HapticCustom.FADE_LENGTH, s + SDL_HapticCustom.FADE_LEVEL);
                return data;
            }
        }
    }

    private static void direction(HapticEffect.Direction direction, long address) {
        int type = switch (direction.kind()) {
            case POLAR -> SDL_HAPTIC_POLAR;
            case CARTESIAN -> SDL_HAPTIC_CARTESIAN;
            case SPHERICAL -> SDL_HAPTIC_SPHERICAL;
            case STEERING_AXIS -> SDL_HAPTIC_STEERING_AXIS;
        };
        memPutByte(address + SDL_HapticDirection.TYPE, (byte) type);
        long dir = address + SDL_HapticDirection.DIR;
        switch (direction.kind()) {
            case POLAR -> memPutInt(dir, Math.floorMod(Math.round(direction.x() * 100), 36000));
            case CARTESIAN -> {
                memPutInt(dir, Math.round(direction.x() * 10000));
                memPutInt(dir + Integer.BYTES, Math.round(direction.y() * 10000));
                memPutInt(dir + 2 * Integer.BYTES, Math.round(direction.z() * 10000));
            }
            case SPHERICAL -> {
                memPutInt(dir, Math.floorMod(Math.round(direction.x() * 100), 36000));
                memPutInt(dir + Integer.BYTES, Math.round(direction.y() * 100));
            }
            case STEERING_AXIS -> {
                // the axis is the direction
            }
        }
    }

    private static void timing(HapticEffect.Timing timing, long length, long delay, long button, long interval) {
        memPutInt(length, length(timing.length()));
        memPutShort(delay, millis(timing.delay()));
        memPutShort(button, (short) Math.clamp(timing.button(), 0, 0xffff));
        memPutShort(interval, millis(timing.interval()));
    }

    private static void envelope(HapticEffect.Envelope envelope, long attack, long attackLevel, long fade,
        long fadeLevel) {
        memPutShort(attack, millis(envelope.attack()));
        memPutShort(attackLevel, unsigned(envelope.attackLevel()));
        memPutShort(fade, millis(envelope.fade()));
        memPutShort(fadeLevel, unsigned(envelope.fadeLevel()));
    }

    /** SDL's infinite length is all bits set, as {@link HapticEffect#INFINITE}. */
    static int length(int millis) {
        return millis == HapticEffect.INFINITE ? -1 : Math.max(millis, 0);
    }

    static short millis(int millis) {
        return (short) Math.clamp(millis, 0, 0xffff);
    }

    /** -1..1 to SDL's signed 16-bit range. */
    static short signed(float value) {
        return (short) Math.round(Math.clamp(value, -1f, 1f) * Short.MAX_VALUE);
    }

    /** 0..1 to SDL's unsigned 16-bit range. */
    static short unsigned(float value) {
        return (short) Math.round(Math.clamp(value, 0f, 1f) * 0xffff);
    }
}
