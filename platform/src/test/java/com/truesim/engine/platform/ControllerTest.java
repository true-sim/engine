package com.truesim.engine.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.lwjgl.sdl.SDLJoystick.SDL_AttachVirtualJoystick;
import static org.lwjgl.sdl.SDLJoystick.SDL_DetachVirtualJoystick;
import static org.lwjgl.sdl.SDLJoystick.SDL_GetJoystickFromID;
import static org.lwjgl.sdl.SDLJoystick.SDL_HAT_RIGHTDOWN;
import static org.lwjgl.sdl.SDLJoystick.SDL_INIT_INTERFACE;
import static org.lwjgl.sdl.SDLJoystick.SDL_JOYSTICK_TYPE_GAMEPAD;
import static org.lwjgl.sdl.SDLJoystick.SDL_JOYSTICK_TYPE_WHEEL;
import static org.lwjgl.sdl.SDLJoystick.SDL_SendJoystickVirtualSensorData;
import static org.lwjgl.sdl.SDLJoystick.SDL_SetJoystickVirtualAxis;
import static org.lwjgl.sdl.SDLJoystick.SDL_SetJoystickVirtualButton;
import static org.lwjgl.sdl.SDLJoystick.SDL_SetJoystickVirtualHat;
import static org.lwjgl.sdl.SDLJoystick.SDL_SetJoystickVirtualTouchpad;
import static org.lwjgl.sdl.SDLSensor.SDL_SENSOR_ACCEL;
import static org.lwjgl.sdl.SDLSensor.SDL_SENSOR_GYRO;
import static org.lwjgl.system.MemoryUtil.memGetByte;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.sdl.SDL_VirtualJoystickDesc;
import org.lwjgl.sdl.SDL_VirtualJoystickDescRumbleCallback;
import org.lwjgl.sdl.SDL_VirtualJoystickDescRumbleTriggersCallback;
import org.lwjgl.sdl.SDL_VirtualJoystickDescSendEffectCallback;
import org.lwjgl.sdl.SDL_VirtualJoystickDescSetLEDCallback;
import org.lwjgl.sdl.SDL_VirtualJoystickDescSetPlayerIndexCallback;
import org.lwjgl.sdl.SDL_VirtualJoystickDescSetSensorsEnabledCallback;
import org.lwjgl.sdl.SDL_VirtualJoystickSensorDesc;
import org.lwjgl.sdl.SDL_VirtualJoystickTouchpadDesc;
import org.lwjgl.system.MemoryStack;

/**
 * Gamepads and joysticks against SDL's virtual devices, which stand in for hardware: the test drives their inputs
 * and sees the outputs the engine sends (rumble, LEDs, player index, effects, sensors) through callbacks.
 */
class ControllerTest {

    private Platform platform;
    /** What the virtual device received, one line per call. */
    private final List<String> received = new ArrayList<>();
    private final List<org.lwjgl.system.Callback> natives = new ArrayList<>();

    @BeforeEach
    void start() {
        platform = Platform.start();
    }

    @AfterEach
    void stop() {
        platform.close();
        for (org.lwjgl.system.Callback callback : natives) {
            callback.free();
        }
    }

    /** Records what the platform passed on. */
    private static final class Recorder implements PlatformListener {
        final List<String> events = new ArrayList<>();

        @Override
        public void deviceAdded(InputDevice device) {
            events.add("added " + device.kind());
        }

        @Override
        public void deviceRemoved(InputDevice.Kind kind, int device) {
            events.add("removed " + kind);
        }

        @Override
        public void gamepadButton(long timestamp, int gamepad, GamepadButton button, boolean down) {
            events.add("button " + button + " " + down);
        }

        @Override
        public void gamepadAxis(long timestamp, int gamepad, GamepadAxis axis, float value) {
            events.add("axis " + axis + " " + value);
        }

        @Override
        public void gamepadTouchpad(long timestamp, int gamepad, int touchpad, int finger, TouchPhase phase, float x,
            float y, float pressure) {
            events.add("touchpad " + phase + " " + x + "," + y);
        }

        @Override
        public void gamepadSensor(long timestamp, int gamepad, SensorType sensor, float x, float y, float z,
            long sensorTimestamp) {
            events.add("sensor " + sensor + " " + x + "," + y + "," + z);
        }

        @Override
        public void joystickHat(long timestamp, int joystick, int hat, HatPosition position) {
            events.add("hat " + hat + " " + position);
        }

        @Override
        public void joystickBall(long timestamp, int joystick, int ball, int dx, int dy) {
            events.add("ball " + ball + " " + dx + "," + dy);
        }

        @Override
        public void joystickAxis(long timestamp, int joystick, int axis, float value) {
            events.add("joystick axis " + axis + " " + value);
        }

        @Override
        public void joystickButton(long timestamp, int joystick, int button, boolean down) {
            events.add("joystick button " + button + " " + down);
        }
    }

    private List<String> poll() {
        Recorder recorder = new Recorder();
        platform.pollEvents(recorder);
        return recorder.events;
    }

    private <T extends org.lwjgl.system.Callback> T keep(T callback) {
        natives.add(callback);
        return callback;
    }

    /** Attaches a virtual device of {@code type} and returns its id. */
    private int attach(int type, String name) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            SDL_VirtualJoystickDesc desc = SDL_VirtualJoystickDesc.calloc(stack);
            SDL_INIT_INTERFACE(desc);
            desc.type((short) type)
                .vendor_id((short) 0x1234)
                .product_id((short) 0x5678)
                .naxes((short) 6)
                .nbuttons((short) 15)
                .nhats((short) 1)
                .nballs((short) 1)
                .button_mask((1 << 15) - 1)
                .axis_mask(0x3f)
                .name(stack.UTF8(name))
                .Rumble(keep(SDL_VirtualJoystickDescRumbleCallback.create((user, low, high) -> {
                    received.add("rumble " + (low & 0xffff) + " " + (high & 0xffff));
                    return true;
                })))
                .RumbleTriggers(keep(SDL_VirtualJoystickDescRumbleTriggersCallback.create((user, left, right) -> {
                    received.add("triggers " + (left & 0xffff) + " " + (right & 0xffff));
                    return true;
                })))
                .SetLED(keep(SDL_VirtualJoystickDescSetLEDCallback.create((user, r, g, b) -> {
                    received.add("led " + (r & 0xff) + " " + (g & 0xff) + " " + (b & 0xff));
                    return true;
                })))
                .SetPlayerIndex(keep(SDL_VirtualJoystickDescSetPlayerIndexCallback.create((user, player) ->
                    received.add("player " + player))))
                .SendEffect(keep(SDL_VirtualJoystickDescSendEffectCallback.create((user, data, size) -> {
                    StringBuilder bytes = new StringBuilder("effect");
                    for (int i = 0; i < size; i++) {
                        bytes.append(' ').append(memGetByte(data + i));
                    }
                    received.add(bytes.toString());
                    return true;
                })))
                .SetSensorsEnabled(keep(SDL_VirtualJoystickDescSetSensorsEnabledCallback.create((user, enabled) -> {
                    received.add("sensors " + enabled);
                    return true;
                })));
            if (type == SDL_JOYSTICK_TYPE_GAMEPAD) {
                desc.touchpads(SDL_VirtualJoystickTouchpadDesc.calloc(1, stack).nfingers((short) 2))
                    .sensors(SDL_VirtualJoystickSensorDesc.calloc(2, stack)
                        .put(0, SDL_VirtualJoystickSensorDesc.calloc(stack).set(SDL_SENSOR_ACCEL, 250))
                        .put(1, SDL_VirtualJoystickSensorDesc.calloc(stack).set(SDL_SENSOR_GYRO, 250)));
            }
            int id = SDL_AttachVirtualJoystick(desc);
            assertThat(id).as("virtual joystick").isPositive();
            return id;
        }
    }

    @Test
    void gamepadsMapInputsAndOutputs() {
        poll(); // devices already connected
        int id = attach(SDL_JOYSTICK_TYPE_GAMEPAD, "Virtual Pad");
        assertThat(poll()).containsExactly("added GAMEPAD");
        Gamepad pad = platform.gamepad(id);
        assertThat(pad).isNotNull();
        assertThat(platform.gamepads()).contains(pad);
        assertThat(pad.kind()).isEqualTo(InputDevice.Kind.GAMEPAD);
        assertThat(pad.id()).isEqualTo(id);
        assertThat(pad.name()).isEqualTo("Virtual Pad");
        assertThat(pad.vendor()).isEqualTo(0x1234);
        assertThat(pad.product()).isEqualTo(0x5678);
        assertThat(pad.productVersion()).isNotNegative();
        assertThat(pad.firmwareVersion()).isNotNegative();
        assertThat(pad.serial()).isNotNull();
        assertThat(pad.path()).isNotNull();
        assertThat(pad.type()).isNotNull();
        assertThat(pad.realType()).isNotNull();
        assertThat(pad.mapping()).isNotEmpty();
        assertThat(pad.connection()).isNotNull();
        assertThat(pad.powerState()).isNotNull();
        assertThat(pad.batteryPercent()).isBetween(-1, 100);
        assertThat(pad.isConnected()).isTrue();
        assertThat(pad.haptic()).isNull(); // virtual devices have no force feedback
        assertThat(pad.haptic()).isNull(); // asked once
        assertThat(pad.toString()).isEqualTo("GAMEPAD " + id);

        // Outputs reach the device.
        assertThat(pad.hasRumble()).isTrue();
        assertThat(pad.hasTriggerRumble()).isTrue();
        assertThat(pad.hasRgbLed()).isTrue();
        assertThat(pad.hasMonoLed()).isFalse();
        assertThat(pad.hasPlayerLed()).isFalse(); // SDL's virtual devices only advertise rumble and the RGB LED
        assertThat(pad.rumble(0.5f, 1, 100)).isTrue();
        assertThat(pad.rumbleTriggers(1, 0, 100)).isTrue();
        pad.stopRumble();
        assertThat(pad.setLed(255, 0, 300)).isTrue();
        assertThat(pad.setPlayerIndex(2)).isTrue();
        assertThat(pad.playerIndex()).isEqualTo(2);
        assertThat(pad.sendEffect(new byte[] {1, 2, 3})).isTrue();
        assertThat(received).containsSubsequence("rumble 32768 65535", "triggers 65535 0", "rumble 0 0", "triggers 0 0",
            "led 255 0 255", "player 2", "effect 1 2 3");

        // Inputs come back as events and state.
        long device = SDL_GetJoystickFromID(id);
        SDL_SetJoystickVirtualButton(device, 0, true);
        SDL_SetJoystickVirtualAxis(device, 0, Short.MAX_VALUE);
        assertThat(poll()).contains("button SOUTH true", "axis LEFT_X 1.0");
        assertThat(pad.hasButton(GamepadButton.SOUTH)).isTrue();
        assertThat(pad.button(GamepadButton.SOUTH)).isTrue();
        assertThat(pad.button(GamepadButton.EAST)).isFalse();
        assertThat(pad.hasAxis(GamepadAxis.LEFT_X)).isTrue();
        assertThat(pad.axis(GamepadAxis.LEFT_X)).isEqualTo(1f);
        assertThat(pad.label(GamepadButton.SOUTH)).isNotNull();

        // Touchpad.
        assertThat(pad.touchpadCount()).isEqualTo(1);
        assertThat(pad.touchpadFingerCount(0)).isEqualTo(2);
        SDL_SetJoystickVirtualTouchpad(device, 0, 0, true, 0.25f, 0.5f, 1f);
        assertThat(poll()).contains("touchpad DOWN 0.25,0.5");
        float[] finger = new float[3];
        assertThat(pad.touchpadFinger(0, 0, finger)).isTrue();
        assertThat(finger).containsExactly(0.25f, 0.5f, 1f);
        assertThat(pad.touchpadFinger(0, 1, finger)).isFalse();
        assertThat(pad.touchpadFinger(5, 0, finger)).isFalse();

        // Sensors.
        assertThat(pad.hasSensor(SensorType.ACCELEROMETER)).isTrue();
        assertThat(pad.hasSensor(SensorType.GYROSCOPE_LEFT)).isFalse();
        assertThat(pad.sensorRate(SensorType.ACCELEROMETER)).isEqualTo(250f);
        assertThat(pad.isSensorEnabled(SensorType.ACCELEROMETER)).isFalse();
        assertThat(pad.setSensorEnabled(SensorType.ACCELEROMETER, true)).isTrue();
        assertThat(pad.isSensorEnabled(SensorType.ACCELEROMETER)).isTrue();
        assertThat(received).contains("sensors true");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            SDL_SendJoystickVirtualSensorData(device, SDL_SENSOR_ACCEL, 1000, stack.floats(1, 2, 3));
        }
        assertThat(poll()).contains("sensor ACCELEROMETER 1.0,2.0,3.0");
        float[] reading = new float[3];
        assertThat(pad.readSensor(SensorType.ACCELEROMETER, reading)).isTrue();
        assertThat(reading).containsExactly(1f, 2f, 3f);
        assertThat(pad.readSensor(SensorType.GYROSCOPE_RIGHT, reading)).isFalse();

        // Disconnecting closes the handle.
        SDL_DetachVirtualJoystick(id);
        assertThat(poll()).contains("removed GAMEPAD");
        assertThat(pad.isClosed()).isTrue();
        assertThat(pad.isConnected()).isFalse();
        assertThat(platform.gamepad(id)).isNull();
        assertThatIllegalStateException().isThrownBy(pad::name).withMessage("GAMEPAD " + id + " is disconnected");
    }

    @Test
    void joysticksMapHatsBallsAndOutputs() {
        int id = attach(SDL_JOYSTICK_TYPE_WHEEL, "Virtual Wheel");
        assertThat(poll()).contains("added JOYSTICK");
        Joystick wheel = platform.joystick(id);
        assertThat(wheel).isNotNull();
        assertThat(platform.joysticks()).contains(wheel);
        assertThat(wheel.kind()).isEqualTo(InputDevice.Kind.JOYSTICK);
        assertThat(wheel.type()).isEqualTo(JoystickType.WHEEL);
        assertThat(wheel.axisCount()).isEqualTo(6);
        assertThat(wheel.buttonCount()).isEqualTo(15);
        assertThat(wheel.hatCount()).isEqualTo(1);
        assertThat(wheel.ballCount()).isNotNegative(); // virtual devices have no trackballs: see PlatformTest
        long device = SDL_GetJoystickFromID(id);
        SDL_SetJoystickVirtualHat(device, 0, (byte) SDL_HAT_RIGHTDOWN);
        SDL_SetJoystickVirtualButton(device, 3, true);
        SDL_SetJoystickVirtualAxis(device, 2, Short.MIN_VALUE);
        assertThat(poll()).contains("hat 0 DOWN_RIGHT", "joystick button 3 true",
            "joystick axis 2 -1.0");
        assertThat(wheel.hat(0)).isEqualTo(HatPosition.DOWN_RIGHT);
        assertThat(wheel.button(3)).isTrue();
        assertThat(wheel.axis(2)).isEqualTo(-1f);
        assertThat(wheel.rumble(1, 1, 50)).isTrue();
        assertThat(received).contains("rumble 65535 65535");
        SDL_DetachVirtualJoystick(id);
        assertThat(poll()).contains("removed JOYSTICK");
        assertThat(wheel.isClosed()).isTrue();
        assertThat(platform.joystick(id)).isNull();
    }

    @Test
    void controllersBelongToThePlatformThread() throws InterruptedException {
        int id = attach(SDL_JOYSTICK_TYPE_GAMEPAD, "Threaded Pad");
        poll();
        Gamepad pad = platform.gamepad(id);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread other = new Thread(() -> {
            try {
                pad.rumble(1, 1, 10);
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        other.start();
        other.join();
        assertThat(failure.get()).isInstanceOf(IllegalStateException.class);
        platform.close(); // closes the controller
        assertThat(pad.isClosed()).isTrue();
        platform = Platform.start();
    }

    @Test
    void gamepadMappingsCanBeAdded() {
        assertThat(platform.addGamepadMappings("""
            # a comment, then an empty line

            03000000aaaa0000bbbb000000000000,Test Pad,a:b0,b:b1,leftx:a0,lefty:a1,
            """)).isEqualTo(1);
        assertThatThrownBy(() -> platform.addGamepadMappings("not a mapping")).isInstanceOf(PlatformException.class)
            .hasMessageStartingWith("Invalid gamepad mapping");
    }

    @Test
    void hapticAndSensorHandlesFailSafelyWithoutADevice() {
        // SDL checks its handles, so a handle to nothing reports failure instead of crashing.
        Haptic haptic = new Haptic(platform, 0x10);
        assertThat(haptic.features()).isEmpty();
        assertThat(haptic.supports(HapticFeature.CONSTANT)).isFalse();
        HapticEffect effect = new HapticEffect.LeftRight(100, 1, 0.5f);
        assertThat(haptic.supports(effect)).isFalse();
        assertThat(haptic.supports(new HapticEffect.Custom(HapticEffect.Direction.polar(0), HapticEffect.Timing.of(10),
            1, 10, new float[] {0.5f}, HapticEffect.Envelope.NONE))).isFalse();
        assertThat(haptic.id()).isZero();
        assertThat(haptic.name()).isEmpty();
        assertThat(haptic.maxEffects()).isNegative();
        assertThat(haptic.maxPlaying()).isNegative();
        assertThat(haptic.axisCount()).isNegative();
        assertThat(haptic.setGain(0.5f)).isFalse();
        assertThat(haptic.setAutocenter(0.5f)).isFalse();
        assertThat(haptic.pause()).isFalse();
        assertThat(haptic.resume()).isFalse();
        assertThat(haptic.stopAll()).isFalse();
        assertThat(haptic.canRumble()).isFalse();
        assertThat(haptic.rumble(1, 100)).isFalse();
        assertThat(haptic.stopRumble()).isFalse();
        assertThatThrownBy(() -> haptic.create(effect)).isInstanceOf(PlatformException.class)
            .hasMessageStartingWith("Could not create LEFT_RIGHT effect");
        assertThat(haptic.update(0, effect)).isFalse();
        assertThat(haptic.run(0, HapticEffect.INFINITE)).isFalse();
        assertThat(haptic.run(0, 2)).isFalse();
        assertThat(haptic.stop(0)).isFalse();
        assertThat(haptic.isPlaying(0)).isFalse();
        haptic.destroy(0);
        assertThat(haptic.isClosed()).isFalse();
        haptic.close();
        haptic.close();
        assertThat(haptic.isClosed()).isTrue();
        assertThatIllegalStateException().isThrownBy(haptic::name);

        Sensor sensor = new Sensor(platform, 0x10);
        assertThat(sensor.id()).isZero();
        assertThat(sensor.name()).isEmpty();
        assertThat(sensor.type()).isEqualTo(SensorType.UNKNOWN);
        assertThat(sensor.read(new float[3])).isFalse();
        sensor.close();
        sensor.close();
        assertThat(sensor.isClosed()).isTrue();
        assertThatIllegalStateException().isThrownBy(sensor::name);
    }
}
