package com.truesim.engine.platform;

import static org.lwjgl.sdl.SDLHaptic.SDL_IsJoystickHaptic;
import static org.lwjgl.sdl.SDLHaptic.SDL_OpenHapticFromJoystick;
import static org.lwjgl.sdl.SDLJoystick.SDL_GetJoystickConnectionState;
import static org.lwjgl.sdl.SDLJoystick.SDL_GetJoystickFirmwareVersion;
import static org.lwjgl.sdl.SDLJoystick.SDL_GetJoystickName;
import static org.lwjgl.sdl.SDLJoystick.SDL_GetJoystickPath;
import static org.lwjgl.sdl.SDLJoystick.SDL_GetJoystickPlayerIndex;
import static org.lwjgl.sdl.SDLJoystick.SDL_GetJoystickProduct;
import static org.lwjgl.sdl.SDLJoystick.SDL_GetJoystickProductVersion;
import static org.lwjgl.sdl.SDLJoystick.SDL_GetJoystickProperties;
import static org.lwjgl.sdl.SDLJoystick.SDL_GetJoystickSerial;
import static org.lwjgl.sdl.SDLJoystick.SDL_GetJoystickVendor;
import static org.lwjgl.sdl.SDLJoystick.SDL_JoystickConnected;
import static org.lwjgl.sdl.SDLJoystick.SDL_PROP_JOYSTICK_CAP_MONO_LED_BOOLEAN;
import static org.lwjgl.sdl.SDLJoystick.SDL_PROP_JOYSTICK_CAP_PLAYER_LED_BOOLEAN;
import static org.lwjgl.sdl.SDLJoystick.SDL_PROP_JOYSTICK_CAP_RGB_LED_BOOLEAN;
import static org.lwjgl.sdl.SDLJoystick.SDL_PROP_JOYSTICK_CAP_RUMBLE_BOOLEAN;
import static org.lwjgl.sdl.SDLJoystick.SDL_PROP_JOYSTICK_CAP_TRIGGER_RUMBLE_BOOLEAN;
import static org.lwjgl.sdl.SDLJoystick.SDL_RumbleJoystick;
import static org.lwjgl.sdl.SDLJoystick.SDL_RumbleJoystickTriggers;
import static org.lwjgl.sdl.SDLJoystick.SDL_SetJoystickLED;
import static org.lwjgl.sdl.SDLJoystick.SDL_SetJoystickPlayerIndex;
import static org.lwjgl.sdl.SDLJoystick.nSDL_GetJoystickPowerInfo;
import static org.lwjgl.sdl.SDLJoystick.nSDL_SendJoystickEffect;
import static org.lwjgl.sdl.SDLProperties.SDL_GetBooleanProperty;
import static org.lwjgl.system.MemoryUtil.memGetInt;
import static org.lwjgl.system.MemoryUtil.memPutByte;

import com.truesim.annotations.AllowAllocation;
import org.lwjgl.system.MemoryStack;

/**
 * A connected gamepad or joystick: what it is, how it is powered and connected, and its outputs: rumble, trigger
 * rumble, LEDs, the player index, device-specific effects and full {@linkplain #haptic() force feedback}. The platform
 * opens controllers as they connect ({@link Platform#gamepad}, {@link Platform#joystick}) and closes them as they
 * disconnect; use them on the platform's thread. Calls on a disconnected controller throw.
 *
 * <p>Outputs return whether the device took them: a device without rumble returns false instead of throwing.
 */
public abstract sealed class Controller permits Gamepad, Joystick {

    final Platform platform;
    final int id;
    final long joystick;
    private Haptic haptic;
    private boolean hapticChecked;
    private boolean closed;

    Controller(Platform platform, int id, long joystick) {
        this.platform = platform;
        this.id = id;
        this.joystick = joystick;
    }

    /** The id input events carry for this controller. */
    public int id() {
        return id;
    }

    public abstract InputDevice.Kind kind();

    @AllowAllocation("a name is a string")
    public String name() {
        check();
        return orEmpty(SDL_GetJoystickName(joystick));
    }

    /** The system's path for the device, e.g. for telling identical controllers apart; empty when unknown. */
    @AllowAllocation("a path is a string")
    public String path() {
        check();
        return orEmpty(SDL_GetJoystickPath(joystick));
    }

    /** The USB vendor id; 0 when unknown. */
    public int vendor() {
        check();
        return SDL_GetJoystickVendor(joystick) & 0xffff;
    }

    /** The USB product id; 0 when unknown. */
    public int product() {
        check();
        return SDL_GetJoystickProduct(joystick) & 0xffff;
    }

    public int productVersion() {
        check();
        return SDL_GetJoystickProductVersion(joystick) & 0xffff;
    }

    public int firmwareVersion() {
        check();
        return SDL_GetJoystickFirmwareVersion(joystick) & 0xffff;
    }

    /** The serial number; empty when unknown. */
    @AllowAllocation("a serial is a string")
    public String serial() {
        check();
        return orEmpty(SDL_GetJoystickSerial(joystick));
    }

    /** The player this controller belongs to, from 0, as shown by its player LEDs; -1 for none. */
    public int playerIndex() {
        check();
        return SDL_GetJoystickPlayerIndex(joystick);
    }

    /** Assigns the controller to a player (from 0), lighting its player LEDs; -1 clears it. */
    public boolean setPlayerIndex(int player) {
        check();
        return SDL_SetJoystickPlayerIndex(joystick, player);
    }

    public ConnectionState connection() {
        check();
        return Sdl.connection(SDL_GetJoystickConnectionState(joystick));
    }

    /** How the controller is powered; {@link PlatformListener#batteryChanged} reports changes. */
    public PowerState powerState() {
        check();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            return Sdl.power(nSDL_GetJoystickPowerInfo(joystick, stack.nmalloc(Integer.BYTES, Integer.BYTES)));
        }
    }

    /** The battery charge in percent, or -1 when unknown. */
    public int batteryPercent() {
        check();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long percent = stack.nmalloc(Integer.BYTES, Integer.BYTES);
            nSDL_GetJoystickPowerInfo(joystick, percent);
            return memGetInt(percent);
        }
    }

    /** Whether the device is still attached; it is closed with the next {@link Platform#pollEvents} after it left. */
    public boolean isConnected() {
        return !closed && SDL_JoystickConnected(joystick);
    }

    // ---------------------------------------------------------------- capabilities

    public boolean hasRumble() {
        return capability(SDL_PROP_JOYSTICK_CAP_RUMBLE_BOOLEAN);
    }

    /** Rumble motors in the triggers, as on Xbox One and later pads. */
    public boolean hasTriggerRumble() {
        return capability(SDL_PROP_JOYSTICK_CAP_TRIGGER_RUMBLE_BOOLEAN);
    }

    /** A colored LED, as on PlayStation pads. */
    public boolean hasRgbLed() {
        return capability(SDL_PROP_JOYSTICK_CAP_RGB_LED_BOOLEAN);
    }

    /** A single-color LED whose brightness can be set. */
    public boolean hasMonoLed() {
        return capability(SDL_PROP_JOYSTICK_CAP_MONO_LED_BOOLEAN);
    }

    /** LEDs showing the {@linkplain #setPlayerIndex player}. */
    public boolean hasPlayerLed() {
        return capability(SDL_PROP_JOYSTICK_CAP_PLAYER_LED_BOOLEAN);
    }

    private boolean capability(String property) {
        check();
        return SDL_GetBooleanProperty(SDL_GetJoystickProperties(joystick), property, false);
    }

    // ---------------------------------------------------------------- outputs

    /**
     * Rumbles the low frequency (left, heavy) and high frequency (right, light) motors, 0 to 1, for {@code millis} ms,
     * replacing any rumble playing. Returns false when the device cannot rumble.
     */
    public boolean rumble(float low, float high, int millis) {
        check();
        return SDL_RumbleJoystick(joystick, Sdl.unsigned(low), Sdl.unsigned(high), Math.max(millis, 0));
    }

    /** Rumbles the motors in the left and right triggers, 0 to 1, for {@code millis} ms. */
    public boolean rumbleTriggers(float left, float right, int millis) {
        check();
        return SDL_RumbleJoystickTriggers(joystick, Sdl.unsigned(left), Sdl.unsigned(right), Math.max(millis, 0));
    }

    /** Stops the rumble and trigger rumble. */
    public void stopRumble() {
        rumble(0, 0, 0);
        rumbleTriggers(0, 0, 0);
    }

    /** Sets the LED's color, 0 to 255 per channel; a single-color LED uses the brightest channel. */
    public boolean setLed(int red, int green, int blue) {
        check();
        return SDL_SetJoystickLED(joystick, (byte) Math.clamp(red, 0, 255), (byte) Math.clamp(green, 0, 255),
            (byte) Math.clamp(blue, 0, 255));
    }

    /**
     * Sends a device-specific raw effect packet, e.g. DualSense adaptive trigger settings, in the device's own
     * format. Returns false when the device does not take it.
     */
    public boolean sendEffect(byte[] data) {
        check();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long packet = stack.nmalloc(1, data.length);
            for (int i = 0; i < data.length; i++) {
                memPutByte(packet + i, data[i]);
            }
            return nSDL_SendJoystickEffect(joystick, packet, data.length);
        }
    }

    /** The controller's force feedback device, e.g. a wheel's, or {@code null} without one. Closed with it. */
    @AllowAllocation("opened once")
    public Haptic haptic() {
        check();
        if (!hapticChecked) {
            hapticChecked = true;
            if (SDL_IsJoystickHaptic(joystick)) {
                long handle = SDL_OpenHapticFromJoystick(joystick);
                haptic = handle == 0 ? null : new Haptic(platform, handle);
            }
        }
        return haptic;
    }

    // ---------------------------------------------------------------- lifecycle

    /** Whether the controller was disconnected (or the platform closed): its methods then throw. */
    public boolean isClosed() {
        return closed;
    }

    void check() {
        platform.checkThread();
        if (closed) {
            throw new IllegalStateException(kind() + " " + id + " is disconnected");
        }
    }

    final void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (haptic != null) {
            haptic.close();
        }
        closeHandle();
    }

    abstract void closeHandle();

    @AllowAllocation("diagnostics")
    static String orEmpty(String value) {
        return value == null ? "" : value;
    }

    @Override
    @AllowAllocation("diagnostics")
    public String toString() {
        return kind() + " " + id;
    }
}
