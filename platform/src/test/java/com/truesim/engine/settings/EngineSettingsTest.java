package com.truesim.engine.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EngineSettingsTest {

    enum Curve { NONE, REINHARD, ACES }

    private static final Setting<Boolean> HDR = Setting.bool("present.hdr", true, "HDR where the display has it");
    private static final Setting<Integer> SIZE = Setting.integer("shadows.size", 2048, 256, 8192, "texels across");
    private static final Setting<Float> EXPOSURE = Setting.decimal("tonemap.exposure", 1, 0.01f, 64, "exposure");
    private static final Setting<String> NAME = Setting.text("player.name", "Ada", "shown above the player");
    private static final Setting<Curve> CURVE = Setting.choice("tonemap.curve", Curve.ACES, "tone curve");

    @Test
    void settingsDeclareTheirKindDefaultAndRange() {
        assertThat(HDR.kind()).isEqualTo(Setting.Kind.BOOLEAN);
        assertThat(SIZE.kind()).isEqualTo(Setting.Kind.INTEGER);
        assertThat(SIZE.min()).isEqualTo(256);
        assertThat(SIZE.max()).isEqualTo(8192);
        assertThat(EXPOSURE.kind()).isEqualTo(Setting.Kind.DECIMAL);
        assertThat(NAME.kind()).isEqualTo(Setting.Kind.TEXT);
        assertThat(NAME.min()).isNull();
        assertThat(CURVE.kind()).isEqualTo(Setting.Kind.CHOICE);
        assertThat(CURVE.choices()).containsExactly(Curve.NONE, Curve.REINHARD, Curve.ACES);
        assertThat(HDR.choices()).isEmpty();
        assertThat(SIZE.defaultValue()).isEqualTo(2048);
        assertThat(SIZE.description()).isEqualTo("texels across");
        assertThat(SIZE).hasToString("shadows.size");
        assertThat(SIZE.live()).isTrue();
        assertThat(SIZE.restartNeeded().live()).isFalse();
        assertThat(SIZE.restartNeeded().name()).isEqualTo("shadows.size");
        // Text, both ways; what is not allowed reads as nothing.
        assertThat(HDR.parse(" off ")).isFalse();
        assertThat(HDR.parse("YES")).isTrue();
        assertThat(HDR.parse("1")).isTrue();
        assertThat(HDR.parse("0")).isFalse();
        assertThat(HDR.parse("no")).isFalse();
        assertThat(HDR.parse("on")).isTrue();
        assertThat(HDR.parse("maybe")).isNull();
        assertThat(SIZE.parse("4096")).isEqualTo(4096);
        assertThat(SIZE.parse("100")).as("out of range").isNull();
        assertThat(SIZE.parse("big")).isNull();
        assertThat(EXPOSURE.parse("0.5")).isEqualTo(0.5f);
        assertThat(CURVE.parse("reinhard")).isEqualTo(Curve.REINHARD);
        assertThat(CURVE.parse("filmic")).isNull();
        assertThat(CURVE.format(Curve.ACES)).isEqualTo("ACES");
        assertThat(SIZE.format(512)).isEqualTo("512");
        assertThat(SIZE.allows(512)).isTrue();
        assertThat(SIZE.allows(100)).isFalse();
        assertThat(SIZE.allows("512")).as("not an integer").isFalse();
        // Declarations are checked.
        assertThatIllegalArgumentException().isThrownBy(() -> Setting.integer("x", 1, 2, 3, "default out of range"));
        assertThatIllegalArgumentException().isThrownBy(() -> Setting.bool(" ", true, "blank name"));
        assertThatIllegalArgumentException().isThrownBy(() -> Setting.bool("a=b", true, "with ="));
        assertThatIllegalArgumentException().isThrownBy(() -> Setting.bool(" padded", true, "padded"));
        assertThatNullPointerException().isThrownBy(() -> Setting.text("x", null, "no default"));
        assertThatNullPointerException().isThrownBy(() -> Setting.bool("x", true, null));
    }

    @Test
    void valuesAreSetCheckedAndHeard() {
        EngineSettings settings = new EngineSettings();
        assertThat(settings.get(SIZE)).as("the default").isEqualTo(2048);
        assertThat(settings.isSet(SIZE)).isFalse();
        List<String> heard = new ArrayList<>();
        EngineSettings.Subscription size = settings.onChange(SIZE, () -> heard.add("size"));
        EngineSettings.Subscription any = settings.onAnyChange(heard::add);
        settings.set(SIZE, 4096).set(SIZE, 4096); // the second changes nothing: not heard
        assertThat(settings.get(SIZE)).isEqualTo(4096);
        assertThat(settings.isSet(SIZE)).isTrue();
        assertThat(heard).containsExactly("size", "shadows.size");
        assertThatIllegalArgumentException().isThrownBy(() -> settings.set(SIZE, 100));
        settings.reset(SIZE).reset(SIZE);
        assertThat(settings.get(SIZE)).isEqualTo(2048);
        assertThat(heard).hasSize(4);
        size.close();
        any.close();
        settings.set(SIZE, 512);
        assertThat(heard).as("stopped").hasSize(4);
        // A scoped view: its own values in the same store.
        EngineSettings left = settings.scoped("window.left");
        EngineSettings right = settings.scoped("window.right");
        left.set(NAME, "Left");
        assertThat(left.get(NAME)).isEqualTo("Left");
        assertThat(right.get(NAME)).isEqualTo("Ada");
        assertThat(settings.get(NAME)).isEqualTo("Ada");
        assertThat(left.nameOf(NAME)).isEqualTo("window.left.player.name");
        assertThat(left.scoped("deeper").nameOf(NAME)).isEqualTo("window.left.deeper.player.name");
        List<String> leftHeard = new ArrayList<>();
        left.onChange(NAME, () -> leftHeard.add("left"));
        right.set(NAME, "Right");
        left.set(NAME, "Still left");
        assertThat(leftHeard).containsExactly("left");
        assertThatIllegalArgumentException().isThrownBy(() -> settings.scoped(" "));
        assertThatIllegalArgumentException().isThrownBy(() -> settings.scoped("a."));
    }

    @Test
    void savedSettingsLoadToleratingOtherVersions() {
        EngineSettings settings = new EngineSettings().set(HDR, false).set(CURVE, Curve.REINHARD);
        settings.scoped("window.left").set(EXPOSURE, 2f);
        Map<String, String> saved = settings.toMap();
        assertThat(saved).as("only what was set").containsExactly(Map.entry("present.hdr", "false"),
            Map.entry("tonemap.curve", "REINHARD"), Map.entry("window.left.tonemap.exposure", "2.0"));
        EngineSettings loaded = new EngineSettings().load(saved);
        assertThat(loaded.get(HDR)).isFalse();
        assertThat(loaded.get(CURVE)).isEqualTo(Curve.REINHARD);
        assertThat(loaded.scoped("window.left").get(EXPOSURE)).isEqualTo(2f);
        assertThat(loaded.get(EXPOSURE)).isEqualTo(1f);
        // Another version's file: unknown keys kept and saved again, unreadable values their default.
        Map<String, String> other = Map.of("present.hdr", "sometimes", "shadows.size", "99999", "future.thing", "on",
            "tonemap.exposure", "4");
        EngineSettings tolerant = new EngineSettings();
        List<String> heard = new ArrayList<>();
        tolerant.onAnyChange(heard::add);
        tolerant.load(other);
        assertThat(heard).as("every loaded key told").contains("present.hdr", "future.thing");
        assertThat(tolerant.isSet(HDR)).isTrue();
        assertThat(tolerant.get(HDR)).as("unreadable: the default").isTrue();
        assertThat(tolerant.get(SIZE)).as("out of range: the default").isEqualTo(2048);
        assertThat(tolerant.get(EXPOSURE)).isEqualTo(4f);
        assertThat(tolerant.toMap()).containsEntry("future.thing", "on").containsEntry("tonemap.exposure", "4.0");
        // Loading replaces what was set.
        tolerant.set(NAME, "Bob").load(Map.of());
        assertThat(tolerant.get(NAME)).isEqualTo("Ada");
        assertThat(tolerant.toMap()).isEmpty();
    }
}
