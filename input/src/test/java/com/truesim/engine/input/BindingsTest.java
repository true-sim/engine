package com.truesim.engine.input;

import static com.truesim.engine.input.Rig.GAME;
import static com.truesim.engine.input.Rig.JUMP;
import static com.truesim.engine.input.Rig.MOVE;
import static com.truesim.engine.input.Rig.SAVE;
import static com.truesim.engine.input.Rig.UI;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.within;

import com.truesim.engine.platform.GamepadAxis;
import com.truesim.engine.platform.GamepadButton;
import com.truesim.engine.platform.HatPosition;
import com.truesim.engine.platform.Key;
import com.truesim.engine.platform.MouseButton;
import com.truesim.engine.settings.EngineSettings;
import com.truesim.engine.settings.SettingRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Bindings as text and as settings (D58), filters saved by id (D81–D83), defaults in one call, conflicts. */
class BindingsTest {

    private static final Binding.Names NAMES = new Binding.Names() {
        @Override
        public DeviceClass deviceClass(String prefix) {
            for (DeviceClass type : new DeviceClass[] {DeviceClass.KEYBOARD, DeviceClass.MOUSE, DeviceClass.GAMEPAD,
                DeviceClass.JOYSTICK}) {
                if (type.prefix().equals(prefix)) {
                    return type;
                }
            }
            return null;
        }

        @Override
        public FilterType filterType(String id) {
            for (FilterType type : Filters.BUILT_IN) {
                if (type.id().equals(id)) {
                    return type;
                }
            }
            return null;
        }
    };

    private static Binding roundTrip(Binding binding) {
        Binding read = Binding.parse(binding.toString(), NAMES);
        assertThat(read.toString()).isEqualTo(binding.toString());
        assertThat(read).isEqualTo(binding).hasSameHashCodeAs(binding);
        return read;
    }

    @Test
    void bindingsSaveAsTextAndReadBack() { // D58, D81, D82, D83
        roundTrip(Binding.key(Key.SPACE));
        roundTrip(Binding.chord(Controls.CONTROL, Controls.key(Key.S)).with(Controls.SHIFT));
        roundTrip(Binding.mouse(MouseButton.RIGHT).toggle());
        roundTrip(Binding.key(Key.E).tap());
        roundTrip(Binding.key(Key.E).hold().holdTime(0.5f));
        roundTrip(Binding.key(Key.E).holdTime(0.5f));
        roundTrip(Binding.axis(Controls.key(Key.Q), Controls.key(Key.E)));
        roundTrip(Binding.stick(true).filter(Filters.CURVE, 3).filter(Filters.SMOOTH, 0.1f));
        roundTrip(Binding.axes3d(Controls.joystickAxis(0), Controls.joystickAxis(1), Controls.joystickAxis(2)));
        roundTrip(Binding.buttons3d(Controls.key(Key.A), Controls.key(Key.D), Controls.key(Key.Q),
            Controls.key(Key.E), Controls.key(Key.S), Controls.key(Key.W)));
        roundTrip(Binding.action(MOVE));
        roundTrip(Binding.pad(GamepadButton.SOUTH).device(new DeviceIdentity("0300abc", "S/N 1,2", "\\\\?\\HID#VID")));
        roundTrip(Binding.of(Controls.stick(true, HatPosition.DOWN)).filter(Filters.DEADZONE, 0.2f));
        Binding parsed = Binding.parse("key:CONTROL+2d(key:A,key:D,key:S,key:W)  scale(2) toggle", NAMES);
        assertThat(parsed.modifiers()).containsExactly(Controls.CONTROL);
        assertThat(parsed.shape()).isEqualTo(Binding.Shape.BUTTONS_2D);
        assertThat(parsed.inputs()).hasSize(4);
        assertThat(parsed.toggles()).isTrue();
        assertThat(parsed.filterCount()).isEqualTo(1);
        assertThat(parsed.filterType(0)).isSameAs(Filters.SCALE);
        assertThat(Binding.parse("action(game.move)", NAMES).source()).isEqualTo("game.move");
        assertThat(Binding.key(Key.A).source()).isNull();
        assertThat(Binding.parseAll("key:A; ;pad:SOUTH", NAMES)).hasSize(2);
        assertThat(Binding.format(List.of(Binding.key(Key.A), Binding.pad(GamepadButton.SOUTH))))
            .isEqualTo("key:A; pad:SOUTH");
    }

    @Test
    void bindingsOfKindsOfDeviceOrFiltersNotPresentAreKeptInert() {
        Binding xr = Binding.parse("xr:RIGHT_TRIGGER", NAMES);
        assertThat(xr.shape()).isEqualTo(Binding.Shape.INERT);
        assertThat(xr.toString()).isEqualTo("xr:RIGHT_TRIGGER");
        assertThat(Binding.parse("key:A modsFilter(1)", NAMES).shape()).isEqualTo(Binding.Shape.INERT);
        for (String bad : new String[] {"key:NOPE", "keyA", "2d(key:A)", "key:A frobnicate!", "key:A hold(", ""}) {
            assertThatIllegalArgumentException().as(bad).isThrownBy(() -> Binding.parse(bad, NAMES));
        }
    }

    @Test
    void filtersAreSavedByIdWithParametersAndAdjustable() { // D83
        Binding stick = Binding.stick(false);
        assertThat(stick.filterCount()).isEqualTo(2);
        assertThat(stick.filterType(1)).isSameAs(Filters.RADIAL);
        assertThat(stick.filterParameter(1, 0)).isEqualTo(0.15f);
        Binding wider = stick.filterParameter(1, 0, 0.25f);
        assertThat(wider.toString()).contains("radial(0.25,1)");
        assertThat(wider.unfiltered().filterCount()).isZero();
        assertThat(Filters.RADIAL.parameterName(0)).isEqualTo("inner");
        assertThat(Filters.RADIAL.parameterDefault(1)).isEqualTo(1);
        assertThat(Filters.RADIAL.parameterCount()).isEqualTo(2);
        assertThat(Filters.RADIAL.toString()).isEqualTo("radial[inner, outer]");
        assertThatIllegalArgumentException().isThrownBy(() -> Binding.key(Key.A).filter(Filters.SCALE, 1, 2));
        assertThatIllegalArgumentException().isThrownBy(() -> new FilterType("Bad id", 0, new String[0]) {
            @Override
            public void apply(float[] value, int dims, float[] params, float[] state, int at, double seconds) {
            }
        });
    }

    @Test
    void theBuiltInFiltersShapeValues() { // D82
        float[] v = {0.1f, -0.5f, 0.9f};
        Filters.DEADZONE.apply(v, 3, new float[] {0.2f, 0.8f}, null, 0, 0);
        assertThat(v[0]).isZero();
        assertThat(v[1]).isCloseTo(-0.5f, within(1e-6f));
        assertThat(v[2]).isEqualTo(1);
        float[] w = {0.6f, 0.8f, 0};
        Filters.CURVE.apply(w, 2, new float[] {2}, null, 0, 0);
        assertThat(Filters.length(w, 2)).isCloseTo(1, within(1e-6f));
        float[] small = {0.3f, 0.4f, 0};
        Filters.CURVE.apply(small, 2, new float[] {2}, null, 0, 0);
        assertThat(Filters.length(small, 2)).isCloseTo(0.25f, within(1e-6f));
        float[] c = {-3, 3, 0.5f};
        Filters.CLAMP.apply(c, 3, new float[] {-1, 1}, null, 0, 0);
        assertThat(c).containsExactly(-1, 1, 0.5f);
        float[] s = {2, 0, 0};
        Filters.SCALE.apply(s, 1, new float[] {0.5f}, null, 0, 0);
        assertThat(s[0]).isEqualTo(1);
        float[] zero = {0, 0, 0};
        Filters.RADIAL.apply(zero, 2, new float[] {0.1f, 1}, null, 0, 0);
        Filters.CURVE.apply(zero, 2, new float[] {2}, null, 0, 0);
        assertThat(zero).containsExactly(0, 0, 0);
        assertThat(Filters.stretch(0.5f, 0.5f, 0.5f)).isZero();
        assertThat(Filters.stretch(0.6f, 0.5f, 0.5f)).isEqualTo(1);
        float[] state = new float[4];
        float[] smooth = {1, 0, 0};
        Filters.SMOOTH.apply(smooth, 1, new float[] {0}, state, 0, 0.1); // no time constant: as it is
        assertThat(smooth[0]).isEqualTo(1);
    }

    @Test
    void anActionChecksItsBindings() {
        ActionGroup group = ActionGroup.root("checked");
        assertThatIllegalArgumentException().isThrownBy(
            () -> group.axis1("tooWide", Binding.keys(Key.A, Key.D, Key.S, Key.W)));
        assertThatIllegalArgumentException().isThrownBy(() -> group.axis1("toggled", Binding.key(Key.A).toggle()));
        DeviceClass poses = DeviceClass.builder("poses", true).add("AIM", ControlKind.POSE).build();
        assertThatIllegalArgumentException().isThrownBy(() -> group.binary("posed", Binding.of(poses.control("AIM"))));
        assertThatIllegalArgumentException().isThrownBy(() -> group.pose("unposed", Binding.key(Key.A)));
        assertThatIllegalArgumentException().isThrownBy(() -> Binding.axis(poses.control("AIM"), Controls.key(Key.A)));
        assertThatIllegalArgumentException().isThrownBy(() -> group.binary("bad name"));
        assertThatIllegalArgumentException().isThrownBy(() -> ActionGroup.root("Bad"));
        group.binary("twice");
        assertThatIllegalArgumentException().isThrownBy(() -> group.binary("twice"));
        group.child("sub");
        assertThatIllegalArgumentException().isThrownBy(() -> group.child("sub"));
        assertThat(group.actions()).hasSize(1);
        assertThat(group.children().getFirst().root()).isSameAs(group);
        assertThat(JUMP.toString()).isEqualTo("game.jump");
        assertThat(JUMP.name()).isEqualTo("jump");
        assertThat(JUMP.group()).isSameAs(GAME);
        assertThat(JUMP.defaults()).hasSize(2);
        assertThatIllegalArgumentException().isThrownBy(() -> Binding.key(Key.A).holdTime(0));
        assertThatIllegalArgumentException().isThrownBy(() -> Controls.joystickButton(32));
        assertThatIllegalArgumentException().isThrownBy(() -> Controls.stick(false, HatPosition.UP_LEFT));
        assertThatIllegalArgumentException().isThrownBy(() -> DeviceClass.builder("Bad", false));
        assertThatIllegalArgumentException().isThrownBy(
            () -> DeviceClass.builder("dup", false).add("A", ControlKind.BUTTON).add("a", ControlKind.BUTTON).build());
        assertThat(Controls.pad(GamepadAxis.LEFT_TRIGGER).kind()).isEqualTo(ControlKind.HALF_AXIS);
        assertThat(Controls.key(Key.W).name()).isEqualTo("W");
        assertThat(Controls.SHIFT.type().distinct()).isFalse();
        assertThat(DeviceClass.GAMEPAD.toString()).isEqualTo("pad");
        assertThat(DeviceClass.KEYBOARD.control("nope")).isNull();
    }

    @Test
    void bindingsAreSettingsSavedAndLoadedLikeThem(@TempDir Path folder) throws Exception { // D58, D7
        Path file = folder.resolve("settings.properties");
        try (Rig rig = new Rig(file, GAME)) {
            SettingRegistry registry = rig.engine.registry();
            assertThat(registry.find("input.game.jump")).isNotNull();
            assertThat(registry.find("input.game.jump").group().id()).endsWith("game");
            assertThat(registry.find("input.game.vehicle.boost").group().parent()).isSameAs(registry.find("input.game.jump").group());
            assertThat(registry.find("input.holdTime")).isNotNull();
            assertThat(rig.input.bindingSetting(JUMP).defaultValue()).isEqualTo("key:SPACE; pad:SOUTH");
            rig.input.bind(rig.one(), JUMP, Binding.key(Key.J));
            rig.key(Key.J, true).frame();
            assertThat(rig.one().down(JUMP)).isTrue();
            assertThat(rig.one().bindings(JUMP)).containsExactly(Binding.key(Key.J));
        }
        assertThat(Files.readString(file)).contains("input.game.jump=");
        try (Rig rig = new Rig(file, GAME)) { // loaded back
            rig.key(Key.J, true).frame();
            assertThat(rig.one().down(JUMP)).isTrue();
            rig.input.resetBindings(rig.one(), JUMP);
            rig.key(Key.J, false).key(Key.SPACE, true).frame();
            assertThat(rig.one().down(JUMP)).isTrue();
        }
    }

    @Test
    void laterPlayersKeepTheirOwnBindingsWhenTheyRebind() {
        try (Rig rig = new Rig(GAME)) {
            Player two = rig.input.addPlayer();
            rig.input.bind(two, JUMP, Binding.key(Key.K));
            assertThat(two.bindings(JUMP)).containsExactly(Binding.key(Key.K));
            assertThat(rig.one().bindings(JUMP)).hasSize(2); // everyone's stay
            EngineSettings settings = rig.engine.settings();
            assertThat(settings.toMap()).containsEntry("player2.input.game.jump", "key:K");
            assertThat(rig.engine.registry().find("player2.input.game.jump")).isNotNull();
            rig.input.bind(two, JUMP, Binding.key(Key.L)); // registered once
            rig.input.resetBindings(two, JUMP);
            assertThat(two.bindings(JUMP)).hasSize(2);
        }
    }

    @Test
    void theGamesDefaultsAndAModulesGroupInOneCall() {
        try (Rig rig = new Rig(GAME)) {
            rig.input.defaults(JUMP, Binding.key(Key.UP));
            assertThat(rig.one().bindings(JUMP)).containsExactly(Binding.key(Key.UP));
            assertThat(rig.engine.settings().toMap()).doesNotContainKey("input.game.jump"); // not the player's
            assertThat(rig.input.has(Rig.BACK)).isFalse();
            rig.input.add(UI); // a module's group, defaults with it, after the engine started
            rig.input.add(UI);
            assertThat(rig.input.has(Rig.BACK)).isTrue();
            assertThat(rig.input.action("ui.back")).isSameAs(Rig.BACK);
            assertThat(rig.engine.registry().find("input.ui.back")).isNotNull();
            rig.key(Key.ESCAPE, true).frame();
            assertThat(rig.one().down(Rig.BACK)).isTrue();
            assertThatIllegalArgumentException().isThrownBy(() -> rig.input.add(Rig.VEHICLE));
            ActionGroup stranger = ActionGroup.root("stranger");
            BinaryAction unknown = stranger.binary("unknown");
            assertThat(rig.one().down(unknown)).isFalse();
            assertThat(rig.one().presses(unknown)).isZero();
            assertThatIllegalArgumentException().isThrownBy(() -> rig.input.bindingSetting(unknown))
                .withMessageContaining("input.add(stranger)");
        }
        assertThatIllegalStateException().isThrownBy(() -> new Input().add(GAME).defaults(JUMP));
    }

    @Test
    void badSavedTextFallsBackToTheDefaultsAndIsReported() {
        try (Rig rig = new Rig(GAME)) {
            rig.engine.settings().set(rig.input.bindingSetting(JUMP), "key:NOT_A_KEY");
            rig.engine.settings().set(rig.input.bindingSetting(MOVE), "key:A toggle; key:W tap");
            rig.key(Key.SPACE, true).frame();
            assertThat(rig.one().down(JUMP)).isTrue();
            assertThat(rig.input.problems()).anyMatch(p -> p.startsWith("input.game.jump"))
                .anyMatch(p -> p.startsWith("input.game.move"));
            assertThat(rig.one().bindings(MOVE)).isEmpty();
        }
    }

    @Test
    void conflictsAreTheOtherActionsOnTheSameControls() {
        try (Rig rig = new Rig(GAME)) {
            List<Conflict> conflicts = rig.input.conflicts(rig.one(), JUMP, Binding.key(Key.LEFT_CONTROL));
            assertThat(conflicts).extracting(Conflict::action).containsExactly(Rig.CROUCH);
            assertThat(rig.input.conflicts(rig.one(), JUMP, Binding.chord(Controls.key(Key.LEFT_CONTROL),
                Controls.key(Key.S)))).extracting(Conflict::action).containsExactly(SAVE); // either covers left
            assertThat(rig.input.conflicts(rig.one(), JUMP, Binding.key(Key.F12))).isEmpty();
            assertThat(rig.input.conflicts(rig.one(), JUMP, rig.input.parse("xr:A").getFirst())).isEmpty();
            DeviceIdentity mine = new DeviceIdentity("g", "1", "");
            rig.input.bind(rig.one(), Rig.PAUSE, Binding.pad(GamepadButton.NORTH).device(mine));
            assertThat(rig.input.conflicts(rig.one(), JUMP, Binding.pad(GamepadButton.NORTH)
                .device(new DeviceIdentity("g", "2", "")))).isEmpty(); // another pad
            assertThat(rig.input.conflicts(rig.one(), JUMP, Binding.pad(GamepadButton.NORTH))).hasSize(1);
        }
    }
}
