package com.truesim.engine.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.truesim.engine.settings.EngineSettings;
import com.truesim.engine.settings.Setting;
import com.truesim.engine.settings.SettingGroup;
import com.truesim.engine.settings.SettingRegistry;
import com.truesim.engine.settings.SettingTier;
import com.truesim.engine.ui.props.SettingsScreen;
import com.truesim.engine.ui.props.SettingsSource;
import com.truesim.engine.ui.scene.UiSceneTest;
import com.truesim.ui.controls.basic.Controls;
import com.truesim.ui.controls.Ui;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.UiState;
import com.truesim.ui.core.UiStore;
import com.truesim.ui.core.input.Pointer;
import com.truesim.ui.core.input.UiInput;
import com.truesim.ui.props.Property;
import com.truesim.ui.props.PropertyModel;
import com.truesim.ui.props.PropertyPanel;
import com.truesim.ui.props.annotations.EnumStyle;
import com.truesim.ui.props.annotations.Group;
import com.truesim.ui.props.annotations.Hidden;
import com.truesim.ui.props.annotations.Label;
import com.truesim.ui.props.annotations.Order;
import com.truesim.ui.props.annotations.Range;
import com.truesim.ui.props.annotations.Step;
import com.truesim.ui.props.annotations.Tooltip;
import com.truesim.ui.style.tss.Tss;
import com.truesim.ui.testkit.SyntheticInput;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The settings screen generated from the engine's settings: its model, editing settings live, a GPU screenshot. */
class SettingsScreenTest {

    enum Quality { LOW, MEDIUM, HIGH, ULTRA }

    static final SettingGroup GRAPHICS = SettingGroup.root("graphics", "settings.graphics", 10);
    static final SettingGroup SHADOWS = GRAPHICS.child("shadows", "settings.shadows", 2);
    static final SettingGroup AUDIO = SettingGroup.root("audio", "settings.audio", 20);
    static final Setting<Boolean> VSYNC = Setting.bool("render.vsync", true, "waits for the display");
    static final Setting<Quality> QUALITY = Setting.choice("render.quality", Quality.HIGH, "the overall quality");
    static final Setting<Float> SCALE = Setting.decimal("render.renderScale", 1, 0.5f, 2, "pixels rendered per shown");
    static final Setting<Integer> SIZE = Setting.integer("render.shadowSize", 2048, 256, 8192, "shadow map texels");
    static final Setting<String> FILTER = Setting.choice("render.shadowFilter", "pcf", List.of("hard", "pcf", "pcss"),
        "how shadow edges soften");
    static final Setting<Float> VOLUME = Setting.decimal("audio.volume", 0.8f, 0, 1, "the master volume");
    static final Setting<Boolean> DEBUG = Setting.bool("render.debugShadows", false, "draws cascades in colours");
    static final Setting<Integer> HIDDEN = Setting.integer("internal.cache", 64, 1, 1024, "not for players");

    static SettingRegistry registry() {
        SettingRegistry registry = new SettingRegistry();
        registry.tiers(SettingTier.BASIC, SettingTier.ADVANCED, SettingTier.DEVELOPER);
        registry.add(GRAPHICS, SettingTier.BASIC, VSYNC, QUALITY, SCALE);
        registry.add(SHADOWS, SettingTier.ADVANCED, SIZE, FILTER);
        registry.add(SHADOWS, SettingTier.DEVELOPER, DEBUG);
        registry.add(AUDIO, SettingTier.BASIC, VOLUME);
        registry.addHidden(HIDDEN);
        return registry;
    }

    @Test
    void eachSettingIsAPropertyWithItsRangeLabelGroupOrderAndChoices() {
        SettingsSource source = new SettingsSource(registry()).upTo(SettingTier.ADVANCED)
            .labels(key -> key.equals("settings.graphics") ? "Graphics" : SettingsSource.defaultLabel(key));
        PropertyModel<SettingsScreen.Values> model = source.model();
        assertThat(source.model(SettingsScreen.Values.class)).isSameAs(model);
        assertThat(source.model(String.class)).isNull();
        assertThat(source.priority()).isEqualTo(SettingsSource.PRIORITY);
        assertThat(model.properties()).extracting(Property::name).containsExactly("render.vsync", "render.quality",
            "render.renderScale", "render.shadowSize", "render.shadowFilter", "render.debugShadows", "audio.volume");
        Property<SettingsScreen.Values, ?> size = model.property("render.shadowSize");
        assertThat(size.annotation(Label.class).value()).isEqualTo("Shadow size");
        assertThat(size.annotation(Tooltip.class).value()).isEqualTo("shadow map texels");
        assertThat(size.annotation(Range.class).min()).isEqualTo(256);
        assertThat(size.annotation(Range.class).max()).isEqualTo(8192);
        assertThat(size.annotation(Step.class).value()).isEqualTo(1);
        assertThat(size.annotation(Group.class).value()).isEqualTo("Graphics / Shadows");
        assertThat(size.annotation(Hidden.class)).isNull();
        assertThat(model.property("render.debugShadows").annotation(Hidden.class)).as("a developer setting")
            .isNotNull();
        assertThat(model.property("render.quality").annotation(EnumStyle.class).value())
            .isEqualTo(EnumStyle.Kind.DROPDOWN);
        assertThat(model.property("render.quality").type()).isEqualTo(Quality.class);
        assertThat(model.property("render.shadowFilter").type()).isEqualTo(String.class);
        assertThat(model.property("render.vsync").annotation(Order.class).value())
            .isLessThan(model.property("render.shadowSize").annotation(Order.class).value());
        assertThat(model.property("render.shadowSize").annotation(Order.class).value())
            .isLessThan(model.property("audio.volume").annotation(Order.class).value());
        assertThat(source.setting("render.shadowFilter")).isSameAs(FILTER);
        // values: the preferred one, set in the player's layer
        EngineSettings settings = new EngineSettings();
        try (SettingsScreen screen = new SettingsScreen(registry(), settings)) {
            long version = screen.version();
            @SuppressWarnings("unchecked")
            Property<SettingsScreen.Values, Integer> typed = (Property<SettingsScreen.Values, Integer>) size;
            SettingsScreen.Values values = screen.get();
            assertThat(typed.get(values)).isEqualTo(2048);
            SettingsScreen.Values edited = typed.with(values, 1024);
            assertThat(settings.get(SIZE)).as("withers are pure").isEqualTo(2048);
            assertThat(screen.set(edited)).isTrue();
            assertThat(settings.get(SIZE)).isEqualTo(1024);
            assertThat(screen.version()).isGreaterThan(version);
            assertThat(typed.get(screen.get())).isEqualTo(1024);
            assertThat(screen.set(screen.get())).as("nothing changed").isFalse();
        }
    }

    /** A UI showing the settings screen of {@code settings}, ready to run frames. */
    private static final class Screen {
        final Ui ui;
        final SyntheticInput mouse = new SyntheticInput();
        final UiInput input = new UiInput(8);
        final PropertyPanel<SettingsScreen.Values> panel;
        final int width;
        final int height;
        long now = 1_000_000_000L;

        Screen(com.truesim.ui.style.StyleSheet sheet, EngineSettings settings, int width, int height) {
            this.width = width;
            this.height = height;
            ui = new Ui(sheet, UiSceneTest.text());
            Controls controls = new Controls(ui);
            SettingsSource source = new SettingsSource(registry()).upTo(SettingTier.ADVANCED);
            panel = source.builder().build(controls, new SettingsScreen(registry(), settings));
            ui.root().add(panel.element());
        }

        void frame() {
            input.beginFrame();
            mouse.poll(input);
            ui.update(width, height, input, now);
            now += 16_000_000L;
        }

        void settle() {
            for (int i = 0; i < 300; i++) {
                frame();
                if (ui.textSystem().atlas().pending() == 0 && ui.recorded() == 0 && ui.animator().running() == 0) {
                    return;
                }
            }
        }

        void click(Element e) {
            float x = ui.layout().x(e.slot()) + ui.layout().width(e.slot()) / 2;
            float y = ui.layout().y(e.slot()) + ui.layout().height(e.slot()) / 2;
            mouse.mouse(x, y, 0);
            frame();
            mouse.mouse(x, y, Pointer.PRIMARY);
            frame();
            mouse.mouse(x, y, 0);
            frame();
        }
    }

    static Element find(Element root, String type) {
        UiStore s = root.store();
        if (type.equals(root.type())) {
            return root;
        }
        for (int c = s.firstChild(root.slot()); c != UiStore.NONE; c = s.nextSibling(c)) {
            Element f = find(s.element(c), type);
            if (f != null) {
                return f;
            }
        }
        return null;
    }

    /** The texts of {@code root}'s labels. */
    static String texts(Ui ui, Element root) {
        StringBuilder b = new StringBuilder();
        UiStore s = root.store();
        CharSequence t = ui.text(root);
        if (t != null) {
            b.append(t).append(' ');
        }
        for (int c = s.firstChild(root.slot()); c != UiStore.NONE; c = s.nextSibling(c)) {
            b.append(texts(ui, s.element(c)));
        }
        return b.toString();
    }

    @Test
    void theGeneratedScreenEditsSettingsLiveBothWays() {
        EngineSettings settings = new EngineSettings();
        Screen screen = new Screen(Tss.defaultTheme(), settings, 900, 700);
        screen.settle();
        assertThat(screen.panel.row("render.debugShadows").shown()).as("a developer setting is hidden").isFalse();
        Element checkbox = find(screen.panel.row("render.vsync").editor().element(), "checkbox");
        assertThat(checkbox.is(UiState.CHECKED)).isTrue();
        // a click on the checkbox sets the engine's setting
        screen.click(checkbox);
        assertThat(settings.get(VSYNC)).isFalse();
        assertThat(checkbox.is(UiState.CHECKED)).isFalse();
        // and follows changes made elsewhere (the console, the game), in the next frame
        settings.set(VSYNC, true);
        settings.set(SIZE, 4096);
        screen.frame();
        assertThat(checkbox.is(UiState.CHECKED)).isTrue();
        assertThat(texts(screen.ui, screen.panel.row("render.shadowSize").editor().element())).contains("4096");
        assertThat(screen.panel.row("render.shadowFilter").editorName()).isEqualTo(SettingsSource.CHOICE_EDITOR);
        settings.set(FILTER, "pcss");
        screen.frame();
    }

    @Test
    void aGpuScreenshotOfTheSettingsScreenInBothThemes() {
        for (String theme : new String[] {"default", "test"}) {
            EngineSettings settings = new EngineSettings();
            settings.set(QUALITY, Quality.ULTRA).set(FILTER, "pcss");
            try (GpuRig rig = GpuRig.open(900, 640)) {
                GpuUiRenderer renderer = new GpuUiRenderer(rig.graph);
                rig.view.add(renderer);
                rig.show(com.truesim.engine.render.Keys.UI);
                Screen screen = new Screen(theme.equals("default") ? Tss.defaultTheme() : Tss.testTheme(), settings,
                    rig.width, rig.height);
                screen.settle();
                for (int i = 0; i < 3; i++) {
                    screen.frame();
                    renderer.submit(screen.ui.layers(), screen.ui.layerCount(), screen.ui.textures(), rig.width,
                        rig.height);
                    rig.frame();
                }
                float alpha = 0;
                for (int y = 0; y < rig.height; y += 7) {
                    for (int x = 0; x < rig.width; x += 7) {
                        alpha = Math.max(alpha, rig.at(x, y, 3));
                    }
                }
                assertThat(alpha).as("the screen drew").isGreaterThan(0.5f);
                rig.screenshot("integ/settings-screen-" + theme, 0.05f);
                renderer.close();
            }
        }
    }

    @Test
    void withoutTiersEverySettingShowsAndNewSettingsRemakeTheModel() {
        SettingRegistry registry = new SettingRegistry();
        registry.add(GRAPHICS, VSYNC, FILTER);
        SettingsSource source = new SettingsSource(registry);
        PropertyModel<SettingsScreen.Values> model = source.model();
        assertThat(model.properties()).hasSize(2);
        assertThat(model.property("render.vsync").annotation(Hidden.class)).isNull();
        assertThat(model.property("render.vsync").annotation(Range.class)).isNull();
        assertThat(source.model()).as("cached").isSameAs(model);
        registry.add(AUDIO, VOLUME);
        assertThat(source.model().properties()).as("made again").hasSize(3);
        assertThat(source.setting("nothing")).isNull();
        assertThat(source.upTo(SettingTier.BASIC).labels(key -> "L").model().property("audio.volume")
            .annotation(Label.class).value()).isEqualTo("L");
        assertThat(SettingsSource.defaultLabel("settings.render.shadowSize")).isEqualTo("Shadow size");
        EngineSettings settings = new EngineSettings();
        try (SettingsScreen screen = new SettingsScreen(registry, settings)) {
            assertThat(screen.settings()).isSameAs(settings);
            SettingsScreen.Values values = screen.get();
            assertThat(screen.get()).as("one snapshot while nothing changed").isSameAs(values);
            assertThat(screen.set(values.with("audio.volume", 5f))).as("out of range: not set").isFalse();
            assertThat(screen.set(values.with("unknown", 1))).isFalse();
            assertThat(screen.set(values.with("render.shadowFilter", "hard"))).isTrue();
            assertThat(settings.get(FILTER)).isEqualTo("hard");
            long version = screen.version();
            screen.touch();
            assertThat(screen.version()).isGreaterThan(version);
        }
    }
}
