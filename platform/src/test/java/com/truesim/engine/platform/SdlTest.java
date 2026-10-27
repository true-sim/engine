package com.truesim.engine.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.lwjgl.system.MemoryUtil.memGetShort;
import static org.lwjgl.system.MemoryUtil.nmemFree;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.lwjgl.sdl.SDLGamepad;
import org.lwjgl.sdl.SDLHaptic;
import org.lwjgl.sdl.SDLJoystick;
import org.lwjgl.sdl.SDLKeycode;
import org.lwjgl.sdl.SDLPen;
import org.lwjgl.sdl.SDLPower;
import org.lwjgl.sdl.SDLScancode;
import org.lwjgl.sdl.SDLSensor;
import org.lwjgl.sdl.SDLTouch;
import org.lwjgl.sdl.SDL_HapticEffect;

/** The SDL mapping against SDL's own constants, so a value SDL adds or renumbers cannot slip through unmapped. */
class SdlTest {

    private static int constant(Class<?> type, String name) {
        try {
            return type.getField(name).getInt(null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("SDL has no " + name, e);
        }
    }

    @Test
    void everyScancodeIsAKey() throws IllegalAccessException {
        int mapped = 0;
        for (Field field : SDLScancode.class.getFields()) {
            String name = field.getName();
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == int.class
                && name.startsWith("SDL_SCANCODE_") && !name.endsWith("RESERVED") && !name.endsWith("COUNT")
                && !name.endsWith("UNKNOWN")) {
                int scancode = field.getInt(null);
                Key key = Sdl.key(scancode);
                assertThat(key).as(name).isNotEqualTo(Key.UNKNOWN);
                assertThat(key.usage()).isEqualTo(scancode);
                mapped++;
            }
        }
        assertThat(mapped).isEqualTo(Key.values().length - 1);
        assertThat(Sdl.key(SDLScancode.SDL_SCANCODE_W)).isEqualTo(Key.W);
        assertThat(Sdl.key(SDLScancode.SDL_SCANCODE_LGUI)).isEqualTo(Key.LEFT_META);
        assertThat(Sdl.key(1)).isEqualTo(Key.UNKNOWN); // unassigned
        assertThat(Sdl.key(-1)).isEqualTo(Key.UNKNOWN);
        assertThat(Sdl.key(4096)).isEqualTo(Key.UNKNOWN);
    }

    @Test
    void keycodesAndModifiers() {
        assertThat(Sdl.character('a')).isEqualTo('a');
        assertThat(Sdl.character(0x20ac)).isEqualTo(0x20ac); // the euro sign
        assertThat(Sdl.character(SDLKeycode.SDLK_UP)).isZero();
        int[][] modifiers = {
            {SDLKeycode.SDL_KMOD_LSHIFT, KeyModifiers.LEFT_SHIFT}, {SDLKeycode.SDL_KMOD_RSHIFT, KeyModifiers.RIGHT_SHIFT},
            {SDLKeycode.SDL_KMOD_LCTRL, KeyModifiers.LEFT_CONTROL}, {SDLKeycode.SDL_KMOD_RCTRL, KeyModifiers.RIGHT_CONTROL},
            {SDLKeycode.SDL_KMOD_LALT, KeyModifiers.LEFT_ALT}, {SDLKeycode.SDL_KMOD_RALT, KeyModifiers.RIGHT_ALT},
            {SDLKeycode.SDL_KMOD_LGUI, KeyModifiers.LEFT_META}, {SDLKeycode.SDL_KMOD_RGUI, KeyModifiers.RIGHT_META},
            {SDLKeycode.SDL_KMOD_MODE, KeyModifiers.MODE}, {SDLKeycode.SDL_KMOD_LEVEL5, KeyModifiers.LEVEL5},
            {SDLKeycode.SDL_KMOD_NUM, KeyModifiers.NUM_LOCK}, {SDLKeycode.SDL_KMOD_CAPS, KeyModifiers.CAPS_LOCK},
            {SDLKeycode.SDL_KMOD_SCROLL, KeyModifiers.SCROLL_LOCK}};
        int all = 0;
        int allEngine = 0;
        for (int[] pair : modifiers) {
            assertThat(Sdl.modifiers(pair[0])).isEqualTo(pair[1]);
            all |= pair[0];
            allEngine |= pair[1];
        }
        assertThat(Sdl.modifiers(all)).isEqualTo(allEngine);
        assertThat(Sdl.modifiers(0)).isZero();
        assertThat(KeyModifiers.has(Sdl.modifiers(SDLKeycode.SDL_KMOD_RSHIFT), KeyModifiers.SHIFT)).isTrue();
        assertThat(KeyModifiers.has(0, KeyModifiers.CONTROL | KeyModifiers.ALT | KeyModifiers.META)).isFalse();
    }

    @Test
    void mouseButtons() {
        assertThat(Sdl.mouseButton(1)).isEqualTo(MouseButton.LEFT);
        assertThat(Sdl.mouseButton(2)).isEqualTo(MouseButton.MIDDLE);
        assertThat(Sdl.mouseButton(3)).isEqualTo(MouseButton.RIGHT);
        assertThat(Sdl.mouseButton(4)).isEqualTo(MouseButton.BACK);
        assertThat(Sdl.mouseButton(5)).isEqualTo(MouseButton.FORWARD);
        assertThat(Sdl.mouseButton(0)).isEqualTo(MouseButton.OTHER);
        assertThat(Sdl.mouseButton(6)).isEqualTo(MouseButton.OTHER);
        // SDL_BUTTON_MASK(b) is 1 << (b - 1)
        for (int b = 1; b <= 5; b++) {
            assertThat(Sdl.mouseButtons(1 << (b - 1))).isEqualTo(Sdl.mouseButton(b).mask());
        }
        assertThat(Sdl.mouseButtons(0xff)).isEqualTo(0x1f);
        assertThat(MouseButton.OTHER.mask()).isZero();
    }

    @Test
    void gamepadValues() {
        for (GamepadButton button : GamepadButton.values()) {
            int sdl = constant(SDLGamepad.class, "SDL_GAMEPAD_BUTTON_" + button.name());
            assertThat(Sdl.gamepadButton(button)).isEqualTo(sdl);
            assertThat(Sdl.gamepadButton(sdl)).isEqualTo(button);
        }
        assertThat(GamepadButton.values()).hasSize(SDLGamepad.SDL_GAMEPAD_BUTTON_COUNT);
        String[] axes = {"LEFTX", "LEFTY", "RIGHTX", "RIGHTY", "LEFT_TRIGGER", "RIGHT_TRIGGER"};
        for (GamepadAxis axis : GamepadAxis.values()) {
            int sdl = constant(SDLGamepad.class, "SDL_GAMEPAD_AXIS_" + axes[axis.ordinal()]);
            assertThat(Sdl.gamepadAxis(axis)).isEqualTo(sdl);
            assertThat(Sdl.gamepadAxis(sdl)).isEqualTo(axis);
        }
        String[] types = {"UNKNOWN", "STANDARD", "XBOX360", "XBOXONE", "PS3", "PS4", "PS5", "NINTENDO_SWITCH_PRO",
            "NINTENDO_SWITCH_JOYCON_LEFT", "NINTENDO_SWITCH_JOYCON_RIGHT", "NINTENDO_SWITCH_JOYCON_PAIR", "GAMECUBE"};
        for (GamepadType type : GamepadType.values()) {
            assertThat(Sdl.gamepadType(constant(SDLGamepad.class, "SDL_GAMEPAD_TYPE_" + types[type.ordinal()])))
                .isEqualTo(type);
        }
        assertThat(GamepadType.values()).hasSize(SDLGamepad.SDL_GAMEPAD_TYPE_COUNT);
        assertThat(Sdl.gamepadType(99)).isEqualTo(GamepadType.UNKNOWN);
        for (ButtonLabel label : ButtonLabel.values()) {
            assertThat(Sdl.label(constant(SDLGamepad.class, "SDL_GAMEPAD_BUTTON_LABEL_" + label.name()))).isEqualTo(label);
        }
        assertThat(Sdl.label(99)).isEqualTo(ButtonLabel.UNKNOWN);
    }

    @Test
    void joystickValues() {
        for (JoystickType type : JoystickType.values()) {
            assertThat(Sdl.joystickType(constant(SDLJoystick.class, "SDL_JOYSTICK_TYPE_" + type.name()))).isEqualTo(type);
        }
        assertThat(JoystickType.values()).hasSize(SDLJoystick.SDL_JOYSTICK_TYPE_COUNT);
        assertThat(Sdl.joystickType(-1)).isEqualTo(JoystickType.UNKNOWN);
        assertThat(Sdl.connection(SDLJoystick.SDL_JOYSTICK_CONNECTION_WIRED)).isEqualTo(ConnectionState.WIRED);
        assertThat(Sdl.connection(SDLJoystick.SDL_JOYSTICK_CONNECTION_WIRELESS)).isEqualTo(ConnectionState.WIRELESS);
        assertThat(Sdl.connection(SDLJoystick.SDL_JOYSTICK_CONNECTION_UNKNOWN)).isEqualTo(ConnectionState.UNKNOWN);
        assertThat(Sdl.connection(SDLJoystick.SDL_JOYSTICK_CONNECTION_INVALID)).isEqualTo(ConnectionState.UNKNOWN);
        assertThat(Sdl.power(SDLPower.SDL_POWERSTATE_ON_BATTERY)).isEqualTo(PowerState.ON_BATTERY);
        assertThat(Sdl.power(SDLPower.SDL_POWERSTATE_NO_BATTERY)).isEqualTo(PowerState.NO_BATTERY);
        assertThat(Sdl.power(SDLPower.SDL_POWERSTATE_CHARGING)).isEqualTo(PowerState.CHARGING);
        assertThat(Sdl.power(SDLPower.SDL_POWERSTATE_CHARGED)).isEqualTo(PowerState.CHARGED);
        assertThat(Sdl.power(SDLPower.SDL_POWERSTATE_ERROR)).isEqualTo(PowerState.UNKNOWN);
        String[] hats = {"CENTERED", "UP", "RIGHT", "DOWN", "LEFT", "RIGHTUP", "RIGHTDOWN", "LEFTUP", "LEFTDOWN"};
        for (HatPosition hat : HatPosition.values()) {
            assertThat(Sdl.hat(constant(SDLJoystick.class, "SDL_HAT_" + hats[hat.ordinal()]))).isEqualTo(hat);
        }
        String[] sensors = {"UNKNOWN", "ACCEL", "GYRO", "ACCEL_L", "GYRO_L", "ACCEL_R", "GYRO_R"};
        for (SensorType sensor : SensorType.values()) {
            int sdl = constant(SDLSensor.class, "SDL_SENSOR_" + sensors[sensor.ordinal()]);
            assertThat(Sdl.sensor(sdl)).isEqualTo(sensor);
            assertThat(Sdl.sensor(sensor)).isEqualTo(sdl);
        }
        assertThat(Sdl.sensor(SDLSensor.SDL_SENSOR_INVALID)).isEqualTo(SensorType.UNKNOWN);
    }

    @Test
    void touchAndPenValues() {
        assertThat(Sdl.touchDeviceType(SDLTouch.SDL_TOUCH_DEVICE_DIRECT)).isEqualTo(TouchDeviceType.DIRECT);
        assertThat(Sdl.touchDeviceType(SDLTouch.SDL_TOUCH_DEVICE_INDIRECT_ABSOLUTE))
            .isEqualTo(TouchDeviceType.INDIRECT_ABSOLUTE);
        assertThat(Sdl.touchDeviceType(SDLTouch.SDL_TOUCH_DEVICE_INDIRECT_RELATIVE))
            .isEqualTo(TouchDeviceType.INDIRECT_RELATIVE);
        assertThat(Sdl.touchDeviceType(SDLTouch.SDL_TOUCH_DEVICE_INVALID)).isEqualTo(TouchDeviceType.UNKNOWN);
        assertThat(Sdl.penType(SDLPen.SDL_PEN_DEVICE_TYPE_DIRECT)).isEqualTo(PenType.DIRECT);
        assertThat(Sdl.penType(SDLPen.SDL_PEN_DEVICE_TYPE_INDIRECT)).isEqualTo(PenType.INDIRECT);
        assertThat(Sdl.penType(SDLPen.SDL_PEN_DEVICE_TYPE_UNKNOWN)).isEqualTo(PenType.UNKNOWN);
        String[] axes = {"PRESSURE", "XTILT", "YTILT", "DISTANCE", "ROTATION", "SLIDER", "TANGENTIAL_PRESSURE"};
        for (PenAxis axis : PenAxis.values()) {
            assertThat(Sdl.penAxis(constant(SDLPen.class, "SDL_PEN_AXIS_" + axes[axis.ordinal()]))).isEqualTo(axis);
        }
        for (int button = 1; button <= 5; button++) {
            assertThat(Sdl.penState(constant(SDLPen.class, "SDL_PEN_INPUT_BUTTON_" + button)))
                .isEqualTo(PenState.BUTTON1 << (button - 1));
        }
        assertThat(Sdl.penState(SDLPen.SDL_PEN_INPUT_DOWN)).isEqualTo(PenState.DOWN);
        assertThat(Sdl.penState(SDLPen.SDL_PEN_INPUT_ERASER_TIP)).isEqualTo(PenState.ERASER);
        assertThat(Sdl.penState(SDLPen.SDL_PEN_INPUT_IN_PROXIMITY)).isEqualTo(PenState.IN_PROXIMITY);
    }

    @Test
    void hapticFeatures() {
        int all = 0;
        for (HapticFeature feature : HapticFeature.values()) {
            String name = feature.name().replace("_", "").replace("SAWTOOTH", "SAWTOOTH");
            assertThat(Sdl.hapticFeature(feature)).isEqualTo(constant(SDLHaptic.class, "SDL_HAPTIC_" + name));
            assertThat(Sdl.hapticFeatures(Sdl.hapticFeature(feature))).containsExactly(feature);
            all |= Sdl.hapticFeature(feature);
        }
        assertThat(Sdl.hapticFeatures(all)).isEqualTo(EnumSet.allOf(HapticFeature.class));
        assertThat(Sdl.hapticFeatures(0)).isEmpty();
    }

    @Test
    void hapticEffectsAreWrittenInSdlUnits() {
        HapticEffect.Envelope envelope = new HapticEffect.Envelope(10, 0.5f, 20, 1);
        try (SDL_HapticEffect sdl = SDL_HapticEffect.calloc()) {
            HapticEffect.Timing timing = new HapticEffect.Timing(500, 5, 2, 7);
            assertThat(Sdl.writeEffect(new HapticEffect.Constant(HapticEffect.Direction.polar(-90), timing, -1f,
                envelope), sdl.address())).isZero();
            assertThat(sdl.type()).isEqualTo((short) SDLHaptic.SDL_HAPTIC_CONSTANT);
            assertThat(sdl.constant().direction().type()).isEqualTo((byte) SDLHaptic.SDL_HAPTIC_POLAR);
            assertThat(sdl.constant().direction().dir(0)).isEqualTo(27000);
            assertThat(sdl.constant().length()).isEqualTo(500);
            assertThat(sdl.constant().delay()).isEqualTo((short) 5);
            assertThat(sdl.constant().button()).isEqualTo((short) 2);
            assertThat(sdl.constant().interval()).isEqualTo((short) 7);
            assertThat(sdl.constant().level()).isEqualTo((short) -32767);
            assertThat(sdl.constant().attack_length()).isEqualTo((short) 10);
            assertThat(sdl.constant().attack_level() & 0xffff).isEqualTo(32768);
            assertThat(sdl.constant().fade_length()).isEqualTo((short) 20);
            assertThat(sdl.constant().fade_level() & 0xffff).isEqualTo(65535);
        }
        try (SDL_HapticEffect sdl = SDL_HapticEffect.calloc()) {
            Sdl.writeEffect(new HapticEffect.Periodic(HapticEffect.Waveform.SAWTOOTH_DOWN,
                HapticEffect.Direction.cartesian(1, -0.5f, 0), HapticEffect.Timing.of(HapticEffect.INFINITE), 100,
                0.5f, -0.25f, 90, HapticEffect.Envelope.NONE), sdl.address());
            assertThat(sdl.type()).isEqualTo((short) SDLHaptic.SDL_HAPTIC_SAWTOOTHDOWN);
            assertThat(sdl.periodic().direction().type()).isEqualTo((byte) SDLHaptic.SDL_HAPTIC_CARTESIAN);
            assertThat(sdl.periodic().direction().dir(0)).isEqualTo(10000);
            assertThat(sdl.periodic().direction().dir(1)).isEqualTo(-5000);
            assertThat(sdl.periodic().length()).isEqualTo(-1); // SDL_HAPTIC_INFINITY
            assertThat(sdl.periodic().period()).isEqualTo((short) 100);
            assertThat(sdl.periodic().magnitude()).isEqualTo((short) 16384);
            assertThat(sdl.periodic().offset()).isEqualTo((short) -8192);
            assertThat(sdl.periodic().phase()).isEqualTo((short) 9000);
        }
        try (SDL_HapticEffect sdl = SDL_HapticEffect.calloc()) {
            Sdl.writeEffect(new HapticEffect.Condition(HapticEffect.ConditionKind.FRICTION, HapticEffect.Timing.of(100),
                List.of(new HapticEffect.ConditionAxis(1, 0.5f, -1, 0.5f, 0.25f, -0.5f),
                    new HapticEffect.ConditionAxis(0, 0, 0, 0, 0, 1))), sdl.address());
            assertThat(sdl.type()).isEqualTo((short) SDLHaptic.SDL_HAPTIC_FRICTION);
            assertThat(sdl.condition().right_sat(0) & 0xffff).isEqualTo(65535);
            assertThat(sdl.condition().left_sat(0) & 0xffff).isEqualTo(32768);
            assertThat(sdl.condition().right_coeff(0)).isEqualTo((short) -32767);
            assertThat(sdl.condition().left_coeff(0)).isEqualTo((short) 16384);
            assertThat(sdl.condition().deadband(0) & 0xffff).isEqualTo(16384);
            assertThat(sdl.condition().center(0)).isEqualTo((short) -16383);
            assertThat(sdl.condition().center(1)).isEqualTo((short) 32767);
            assertThat(sdl.condition().center(2)).isZero();
        }
        try (SDL_HapticEffect sdl = SDL_HapticEffect.calloc()) {
            Sdl.writeEffect(new HapticEffect.Ramp(HapticEffect.Direction.spherical(45, 10), HapticEffect.Timing.of(50),
                -1, 1, envelope), sdl.address());
            assertThat(sdl.type()).isEqualTo((short) SDLHaptic.SDL_HAPTIC_RAMP);
            assertThat(sdl.ramp().direction().type()).isEqualTo((byte) SDLHaptic.SDL_HAPTIC_SPHERICAL);
            assertThat(sdl.ramp().direction().dir(0)).isEqualTo(4500);
            assertThat(sdl.ramp().direction().dir(1)).isEqualTo(1000);
            assertThat(sdl.ramp().start()).isEqualTo((short) -32767);
            assertThat(sdl.ramp().end()).isEqualTo((short) 32767);
        }
        try (SDL_HapticEffect sdl = SDL_HapticEffect.calloc()) {
            Sdl.writeEffect(new HapticEffect.LeftRight(250, 1, 0), sdl.address());
            assertThat(sdl.type()).isEqualTo((short) SDLHaptic.SDL_HAPTIC_LEFTRIGHT);
            assertThat(sdl.leftright().length()).isEqualTo(250);
            assertThat(sdl.leftright().large_magnitude() & 0xffff).isEqualTo(65535);
            assertThat(sdl.leftright().small_magnitude()).isZero();
        }
        try (SDL_HapticEffect sdl = SDL_HapticEffect.calloc()) {
            long data = Sdl.writeEffect(new HapticEffect.Custom(HapticEffect.Direction.steering(),
                HapticEffect.Timing.of(100), 2, 10, new float[] {1, -1, 0.5f, 0}, envelope), sdl.address());
            try {
                assertThat(sdl.type()).isEqualTo((short) SDLHaptic.SDL_HAPTIC_CUSTOM);
                assertThat(sdl.custom().direction().type()).isEqualTo((byte) SDLHaptic.SDL_HAPTIC_STEERING_AXIS);
                assertThat(sdl.custom().channels()).isEqualTo((byte) 2);
                assertThat(sdl.custom().period()).isEqualTo((short) 10);
                assertThat(sdl.custom().samples()).isEqualTo((short) 2);
                assertThat(data).isNotZero();
                assertThat(memGetShort(data)).isEqualTo((short) 32767);
                assertThat(memGetShort(data + 2)).isEqualTo((short) -32767);
            } finally {
                nmemFree(data);
            }
        }
    }

    @Test
    void sdlUnits() {
        assertThat(Sdl.length(HapticEffect.INFINITE)).isEqualTo(-1);
        assertThat(Sdl.length(-5)).isZero();
        assertThat(Sdl.millis(100_000) & 0xffff).isEqualTo(0xffff);
        assertThat(Sdl.millis(-1)).isZero();
        assertThat(Sdl.signed(2)).isEqualTo(Short.MAX_VALUE);
        assertThat(Sdl.signed(-2)).isEqualTo((short) -Short.MAX_VALUE);
        assertThat(Sdl.unsigned(-1)).isZero();
        assertThat(Sdl.unsigned(2) & 0xffff).isEqualTo(0xffff);
    }

    @Test
    void hapticEffectsValidate() {
        HapticEffect.Timing timing = HapticEffect.Timing.of(10);
        assertThatIllegalArgumentException().isThrownBy(() -> new HapticEffect.Condition(HapticEffect.ConditionKind.SPRING,
            timing, List.of()));
        HapticEffect.ConditionAxis axis = new HapticEffect.ConditionAxis(1, 1, 1, 1, 0, 0);
        assertThatIllegalArgumentException().isThrownBy(() -> new HapticEffect.Condition(HapticEffect.ConditionKind.SPRING,
            timing, List.of(axis, axis, axis, axis)));
        assertThatIllegalArgumentException().isThrownBy(() -> new HapticEffect.Custom(HapticEffect.Direction.polar(0),
            timing, 2, 10, new float[] {1, 2, 3}, HapticEffect.Envelope.NONE));
        assertThatIllegalArgumentException().isThrownBy(() -> new HapticEffect.Custom(HapticEffect.Direction.polar(0),
            timing, 0, 10, new float[] {1}, HapticEffect.Envelope.NONE));
        assertThatIllegalArgumentException().isThrownBy(() -> new HapticEffect.Custom(HapticEffect.Direction.polar(0),
            timing, 1, 10, new float[0], HapticEffect.Envelope.NONE));
        float[] samples = {0.5f};
        HapticEffect.Custom custom = new HapticEffect.Custom(HapticEffect.Direction.polar(0), timing, 1, 10, samples,
            HapticEffect.Envelope.NONE);
        samples[0] = 1;
        assertThat(custom.samples()).containsExactly(0.5f);
        HapticEffect.Custom same = new HapticEffect.Custom(HapticEffect.Direction.polar(0), timing, 1, 10,
            new float[] {0.5f}, HapticEffect.Envelope.NONE);
        assertThat(custom).isEqualTo(same).hasSameHashCodeAs(same).isNotEqualTo("custom");
        assertThat(custom).isNotEqualTo(new HapticEffect.Custom(HapticEffect.Direction.polar(1), timing, 1, 10,
            new float[] {0.5f}, HapticEffect.Envelope.NONE));
        assertThat(custom.toString()).contains("1 samples");
        for (HapticEffect.Waveform waveform : HapticEffect.Waveform.values()) {
            assertThat(new HapticEffect.Periodic(waveform, HapticEffect.Direction.polar(0), timing, 10, 1, 0, 0,
                HapticEffect.Envelope.NONE).feature().name().replace("_", ""))
                .isEqualTo(waveform.name().replace("_", ""));
        }
        for (HapticEffect.ConditionKind kind : HapticEffect.ConditionKind.values()) {
            assertThat(new HapticEffect.Condition(kind, timing, List.of(axis)).feature().name()).isEqualTo(kind.name());
        }
    }
}
