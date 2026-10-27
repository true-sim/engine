package com.truesim.engine.platform;

import static org.lwjgl.sdl.SDLJoystick.SDL_CloseJoystick;
import static org.lwjgl.sdl.SDLJoystick.SDL_GetJoystickAxis;
import static org.lwjgl.sdl.SDLJoystick.SDL_GetJoystickButton;
import static org.lwjgl.sdl.SDLJoystick.SDL_GetJoystickHat;
import static org.lwjgl.sdl.SDLJoystick.SDL_GetJoystickType;
import static org.lwjgl.sdl.SDLJoystick.SDL_GetNumJoystickAxes;
import static org.lwjgl.sdl.SDLJoystick.SDL_GetNumJoystickBalls;
import static org.lwjgl.sdl.SDLJoystick.SDL_GetNumJoystickButtons;
import static org.lwjgl.sdl.SDLJoystick.SDL_GetNumJoystickHats;

/**
 * A joystick without a gamepad layout: wheels, flight sticks, throttles, pedals, arcade sticks and the like, with
 * numbered axes, buttons, hats and trackballs. Events arrive through {@link PlatformListener#joystickAxis},
 * {@link PlatformListener#joystickButton}, {@link PlatformListener#joystickHat} and
 * {@link PlatformListener#joystickBall}; the state can also be read here. Wheels usually have force feedback through
 * {@link #haptic()}.
 */
public final class Joystick extends Controller {

    Joystick(Platform platform, int id, long joystick) {
        super(platform, id, joystick);
    }

    @Override
    public InputDevice.Kind kind() {
        return InputDevice.Kind.JOYSTICK;
    }

    public JoystickType type() {
        check();
        return Sdl.joystickType(SDL_GetJoystickType(joystick));
    }

    public int axisCount() {
        check();
        return SDL_GetNumJoystickAxes(joystick);
    }

    public int buttonCount() {
        check();
        return SDL_GetNumJoystickButtons(joystick);
    }

    public int hatCount() {
        check();
        return SDL_GetNumJoystickHats(joystick);
    }

    /** Trackballs, which report movement only, through {@link PlatformListener#joystickBall}. */
    public int ballCount() {
        check();
        return SDL_GetNumJoystickBalls(joystick);
    }

    /** Axis {@code axis}'s position now, from -1 to 1. */
    public float axis(int axis) {
        check();
        return Platform.axis(SDL_GetJoystickAxis(joystick, axis));
    }

    public boolean button(int button) {
        check();
        return SDL_GetJoystickButton(joystick, button);
    }

    public HatPosition hat(int hat) {
        check();
        return Sdl.hat(SDL_GetJoystickHat(joystick, hat));
    }

    @Override
    void closeHandle() {
        SDL_CloseJoystick(joystick);
    }
}
