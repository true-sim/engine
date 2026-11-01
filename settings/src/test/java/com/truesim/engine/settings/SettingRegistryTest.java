package com.truesim.engine.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import org.junit.jupiter.api.Test;

class SettingRegistryTest {

    private static final SettingGroup GRAPHICS = SettingGroup.root("graphics", "settings.graphics", 10);
    private static final SettingGroup SHADOWS = GRAPHICS.child("shadows", "settings.graphics.shadows", 20);
    private static final SettingGroup QUALITY = GRAPHICS.child("quality", "settings.graphics.quality", 10);
    private static final SettingGroup AUDIO = SettingGroup.root("audio", "settings.audio", 20);
    private static final SettingGroup DISPLAY = SettingGroup.root("display", "settings.display", 0);
    private static final SettingGroup MAIN_WINDOW = DISPLAY.child("window.main", "settings.window.main", 0);
    private static final SettingGroup SIDE_WINDOW = DISPLAY.child("window.side", "settings.window.side", 1);

    private static final Setting<Integer> SIZE = Setting.integer("shadows.size", 2048, 256, 8192, "texels across");
    private static final Setting<Boolean> SOFT = Setting.bool("shadows.soft", true, "soft edges");
    private static final Setting<Float> SCALE = Setting.decimal("render.scale", 1, 0.25f, 2, "render scale");
    private static final Setting<Boolean> VALIDATION = Setting.bool("vulkan.validation", false, "validation layers");
    private static final Setting<Float> VOLUME = Setting.decimal("audio.volume", 1, 0, 1, "volume");
    private static final Setting<Integer> WIDTH = Setting.integer("window.width", 1280, 1, 16384, "width");
    private static final Setting<Integer> CACHE = Setting.integer("cache.size", 64, 1, 4096, "megabytes");

    @Test
    void groupsFormATreeApartFromNames() {
        assertThat(SHADOWS.id()).isEqualTo("graphics/shadows");
        assertThat(SHADOWS.parent()).isSameAs(GRAPHICS);
        assertThat(SHADOWS.label()).isEqualTo("settings.graphics.shadows");
        assertThat(SHADOWS.isIn(GRAPHICS)).isTrue();
        assertThat(SHADOWS.isIn(SHADOWS)).isTrue();
        assertThat(GRAPHICS.isIn(SHADOWS)).isFalse();
        assertThat(AUDIO.parent()).isNull();
        assertThatIllegalArgumentException().isThrownBy(() -> SettingGroup.root(" ", "x", 0));
    }

    @Test
    void optionsScreensListGroupsAndTheirSettings() {
        SettingRegistry registry = new SettingRegistry()
            .add(SHADOWS, SIZE, SOFT)
            .add(QUALITY, SettingTier.ADVANCED, SCALE)
            .add(QUALITY, SettingTier.DEVELOPER, VALIDATION)
            .add(AUDIO, VOLUME)
            .addScoped("window.main", MAIN_WINDOW, WIDTH)
            .addScoped("window.side", SIDE_WINDOW, WIDTH)
            .addHidden(CACHE)
            .tiers(SettingTier.BASIC, SettingTier.ADVANCED, SettingTier.DEVELOPER)
            .hide(SettingTier.DEVELOPER);
        registry.check();
        // Top-level groups by order; subgroups by order, then id.
        assertThat(registry.groups(null, null)).containsExactly(DISPLAY, GRAPHICS, AUDIO);
        assertThat(registry.groups(GRAPHICS, null)).containsExactly(QUALITY, SHADOWS);
        assertThat(registry.settings(SHADOWS, null)).extracting(e -> (Object) e.setting()).containsExactly(SIZE, SOFT);
        assertThat(registry.settings(GRAPHICS, null)).as("only those directly in it").isEmpty();
        // Tiers: up to one; hidden ones only when revealed; a group with nothing shown is left out.
        assertThat(registry.settings(QUALITY, null)).extracting(e -> (Object) e.setting()).containsExactly(SCALE);
        assertThat(registry.groups(GRAPHICS, SettingTier.BASIC)).containsExactly(SHADOWS);
        registry.reveal(true);
        assertThat(registry.settings(QUALITY, null)).extracting(e -> (Object) e.setting()).containsExactly(SCALE, VALIDATION);
        assertThat(registry.settings(QUALITY, SettingTier.ADVANCED)).extracting(e -> (Object) e.setting())
            .containsExactly(SCALE);
        // One group per window, each reading its own scope.
        assertThat(registry.groups(DISPLAY, null)).containsExactly(MAIN_WINDOW, SIDE_WINDOW);
        SettingRegistry.Entry side = registry.settings(SIDE_WINDOW, null).getFirst();
        assertThat(side.fullName()).isEqualTo("window.side.window.width");
        EngineSettings settings = new EngineSettings();
        side.view(settings).set(WIDTH, 640);
        assertThat(settings.scoped("window.side").get(WIDTH)).isEqualTo(640);
        assertThat(registry.find("window.main.window.width").group()).isSameAs(MAIN_WINDOW);
        // Hidden: registered, never listed.
        assertThat(registry.find("cache.size").visible()).isFalse();
        assertThat(registry.entries()).hasSize(8);
    }

    @Test
    void registrationAndStartupCheckCatchMistakes() {
        SettingRegistry registry = new SettingRegistry().add(SHADOWS, SIZE);
        assertThatIllegalArgumentException().isThrownBy(() -> registry.add(AUDIO, SIZE))
            .withMessageContaining("shadows.size is registered twice");
        registry.addScoped("window.main", MAIN_WINDOW, SIZE); // another full name: fine
        assertThatIllegalArgumentException().isThrownBy(() -> registry.addScoped("a.", MAIN_WINDOW, SOFT));
        // Tiers are optional: none declared, none may be used.
        registry.add(QUALITY, SettingTier.ADVANCED, SCALE);
        assertThatIllegalStateException().isThrownBy(registry::check)
            .withMessageContaining("render.scale: the tier 'advanced' is not declared (no tiers are)");
        registry.tiers(SettingTier.ADVANCED);
        registry.check();
        // Without tiers, queries filter nothing.
        SettingRegistry plain = new SettingRegistry().add(QUALITY, SCALE, VALIDATION);
        plain.check();
        assertThat(plain.settings(QUALITY, SettingTier.BASIC)).hasSize(2);
        // A player-visible setting needs a group; groups need unique ids.
        assertThatIllegalStateException().isThrownBy(
            () -> new SettingRegistry().add(new SettingRegistry.Entry(SOFT, null, null, SettingTier.BASIC))
                .tiers(SettingTier.BASIC).check())
            .withMessageContaining("shadows.soft: a tier, but no group");
        SettingGroup impostor = SettingGroup.root("graphics", "settings.other", 0);
        assertThatIllegalStateException().isThrownBy(
            () -> new SettingRegistry().add(SHADOWS, SIZE).add(impostor, SOFT).check())
            .withMessageContaining("two groups have the id 'graphics'");
    }
}
