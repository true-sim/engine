package com.truesim.engine.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assumptions.assumeThat;

import com.truesim.engine.app.AppConfig;
import com.truesim.engine.app.Application;
import com.truesim.engine.app.Engine;
import com.truesim.engine.app.Plugin;
import com.truesim.engine.app.PluginKey;
import com.truesim.engine.app.Stage;
import com.truesim.engine.app.Startup;
import com.truesim.engine.app.StartupFailure;
import com.truesim.engine.graph.Rendering;
import com.truesim.engine.input.Input;
import com.truesim.engine.platform.SystemCursor;
import com.truesim.ui.controls.Ui;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.Tunable;
import com.truesim.ui.core.input.Cursor;
import com.truesim.ui.style.tss.Tss;
import org.junit.jupiter.api.Test;

/** The UI as a plugin: its settings, its actions, and each frame its input, update and rendering on the view. */
class UiPluginTest {

    /** A view for the UI, as a game adds its views. */
    private static final class Views implements Plugin {
        static final PluginKey<Views> KEY = PluginKey.of("test.views", Views.class);

        @Override
        public PluginKey<Views> key() {
            return KEY;
        }

        @Override
        public java.util.List<PluginKey<?>> requires() {
            return java.util.List.of(Rendering.KEY);
        }

        @Override
        public void start(Stage stage, Startup startup) {
            if (stage == Stage.GRAPH) {
                startup.get(Rendering.KEY).graph().addView("main").size(320, 200);
            }
        }
    }

    @Test
    void thePluginRegistersSettingsAndActionsAndRunsTheUiEachFrameOnItsView() {
        UiPlugin plugin = new UiPlugin(textures -> new Ui(Tss.defaultTheme(), null)).view("main");
        assertThatIllegalStateException().isThrownBy(plugin::ui);
        Engine engine;
        try {
            engine = Engine.create(new Application() {
                @Override
                public void configure(AppConfig app) {
                    app.use(new Rendering().headless(true), new Input(), new Views(), plugin);
                }
            });
        } catch (StartupFailure e) {
            assumeThat(e).as("no Vulkan available: " + e.reason()).isNull();
            throw e;
        }
        try (engine) {
            UiPlugin ui = engine.get(UiPlugin.KEY);
            assertThat(ui).isSameAs(plugin);
            assertThat(ui.key()).isEqualTo(UiPlugin.KEY);
            Ui u = ui.ui();
            for (Tunable t : u.tunables()) {
                assertThat(engine.registry().find(t.name())).as("the setting of %s", t.name()).isNotNull();
            }
            assertThat(engine.registry().find("ui.confirmButton")).isNotNull();
            assertThat(engine.get(Input.KEY).action("ui.navigation.up")).isNotNull();
            assertThat(ui.view().name()).isEqualTo("main");
            assertThat(ui.renderer()).isNotNull();
            assertThat(ui.commands()).isNotNull();
            Element box = u.panel("card");
            u.root().add(box);
            // a setting changes the tunable at the next frame's start
            engine.settings().set(scaleSetting(engine), 1.5f);
            engine.step();
            engine.step();
            assertThat(com.truesim.ui.layout.Layout.UI_SCALE.get()).isEqualTo(1.5f);
            assertThat(u.layout().width(u.store().rootSlot())).isEqualTo(320);
            assertThat(u.layout().height(u.store().rootSlot())).isEqualTo(200);
            assertThat(ui.renderer().totalUploadedBytes()).as("the layers reached the renderer").isPositive();
            assertThat(ui.input()).isNotNull();
            engine.settings().set(scaleSetting(engine), 1f);
            engine.step();
        } finally {
            com.truesim.ui.layout.Layout.UI_SCALE.set(1);
        }
    }

    @SuppressWarnings("unchecked")
    private static com.truesim.engine.settings.Setting<Float> scaleSetting(Engine engine) {
        return (com.truesim.engine.settings.Setting<Float>) engine.registry().find("ui.scale").setting();
    }

    @Test
    void withoutItsViewTheUiDoesNotStart() {
        for (String name : new String[] {null, "elsewhere"}) {
            UiPlugin plugin = new UiPlugin(textures -> new Ui(Tss.defaultTheme(), null)).view(name);
            boolean views = name != null;
            try {
                Engine.create(new Application() {
                    @Override
                    public void configure(AppConfig app) {
                        if (views) {
                            app.use(new Rendering().headless(true), new Input(), new Views(), plugin);
                        } else {
                            app.use(new Rendering().headless(true), new Input(), plugin);
                        }
                    }
                }).close();
                org.assertj.core.api.Assertions.fail("started without its view");
            } catch (StartupFailure e) {
                assumeThat(e.getMessage()).as("Vulkan available").doesNotContain("Rendering could not start");
                assertThat(e.getMessage() + e.getCause()).contains(name == null ? "needs a view" : "elsewhere");
            }
        }
    }

    @Test
    void theFirstViewServesWhenNoneIsNamed() {
        UiPlugin plugin = new UiPlugin(textures -> new Ui(Tss.defaultTheme(), null));
        Engine engine;
        try {
            engine = Engine.create(new Application() {
                @Override
                public void configure(AppConfig app) {
                    app.use(new Rendering().headless(true), new Input(), new Views(), plugin);
                }
            });
        } catch (StartupFailure e) {
            assumeThat(e).as("no Vulkan available: " + e.reason()).isNull();
            throw e;
        }
        try (engine) {
            assertThat(plugin.view().name()).isEqualTo("main");
            engine.step();
        }
        plugin.stop(); // stopping twice is harmless
    }

    @Test
    void everyUiCursorHasASystemCursor() {
        for (Cursor c : Cursor.values()) {
            assertThat(UiPlugin.systemCursor(c)).as("%s", c).isNotNull();
        }
        assertThat(UiPlugin.systemCursor(Cursor.TEXT)).isEqualTo(SystemCursor.TEXT);
        assertThat(UiPlugin.systemCursor(Cursor.NONE)).isEqualTo(SystemCursor.HIDDEN);
        assertThat(UiPlugin.systemCursor(Cursor.AUTO)).isEqualTo(SystemCursor.DEFAULT);
    }
}
