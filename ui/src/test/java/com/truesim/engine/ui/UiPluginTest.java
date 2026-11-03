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

    /** Glyphs recorded in {@code label}'s range of the HUD's draw list (ready in the atlas or not). */
    private static int glyphs(Ui ui, Element label) {
        com.truesim.ui.core.draw.DrawList list = ui.layers()[0];
        int start = ui.store().rangeStart(label.slot());
        int n = 0;
        for (int i = start; i < start + ui.store().rangeCapacity(label.slot()); i++) {
            if (com.truesim.ui.core.draw.Primitive.kind(list.header(i)) == com.truesim.ui.core.draw.Primitive.GLYPH) {
                n++;
            }
        }
        return n;
    }

    private static com.truesim.ui.text.TextSystem text() {
        com.truesim.ui.text.natives.NativeTextBackend backend = new com.truesim.ui.text.natives.NativeTextBackend();
        return new com.truesim.ui.text.TextSystem(com.truesim.ui.fonts.cjk.BundledCjkFonts.registry(backend),
            backend.newShaper(), new com.truesim.ui.text.GlyphAtlas(new com.truesim.ui.core.draw.UiTextures(),
                backend.msdfGenerator(), backend.newRasterizer(), null, null, new com.truesim.ui.text.AtlasSettings()));
    }

    @Test
    void theGameSeesAClickInTheFrameOfTheClickAndWhatItChangesShowsInThatFrame() {
        com.truesim.ui.testkit.SyntheticInput pointer = new com.truesim.ui.testkit.SyntheticInput();
        com.truesim.ui.style.StyleSheet sheet = Tss.compileString("frame.tss", """
            root { layout: none; }
            panel { width: 80px; height: 30px; offsets: 10px 10px 0 0; anchors: 0 0 0 0; }
            label { offsets: 10px 60px 0 0; anchors: 0 0 0 0; font-size: 14px; }
            """);
        UiPlugin plugin = new UiPlugin(textures -> new Ui(sheet, text())).view("main").pointers(pointer);
        long[] frames = {0};
        long[] clickedAt = {-1};
        long[] seenAt = {-1};
        Element[] label = new Element[1];
        Engine engine;
        try {
            engine = Engine.create(new Application() {
                @Override
                public void configure(AppConfig app) {
                    app.use(new Rendering().headless(true), new Input(), new Views(), plugin);
                }

                @Override
                public void update(com.truesim.engine.app.Frame frame) {
                    frames[0]++;
                    if (clickedAt[0] >= 0 && seenAt[0] < 0) {
                        seenAt[0] = frames[0]; // the game reacts to the click in its update
                        plugin.ui().text(label[0], "clicked");
                    }
                }
            });
        } catch (StartupFailure e) {
            assumeThat(e).as("no Vulkan available: " + e.reason()).isNull();
            throw e;
        }
        try (engine) {
            Ui u = plugin.ui();
            Element button = u.panel(null);
            label[0] = u.label(null, "-");
            u.root().add(button).add(label[0]);
            u.on(button, com.truesim.ui.core.event.EventType.CLICK, e -> clickedAt[0] = frames[0] + 1);
            for (int i = 0; i < 3; i++) {
                engine.step(); // laid out and drawn: the button can be picked
            }
            assertThat(glyphs(u, label[0])).isEqualTo(1);
            pointer.mouse(30, 20, com.truesim.ui.core.input.Pointer.PRIMARY);
            engine.step();
            pointer.mouse(30, 20, 0);
            engine.step(); // the release: a click, the game's reaction, and its drawing, all in this frame
            assertThat(clickedAt[0]).as("clicked in the frame of the release").isEqualTo(frames[0]);
            assertThat(seenAt[0]).as("the game's update of that frame sees it").isEqualTo(frames[0]);
            assertThat(glyphs(u, label[0])).as("its change is drawn in that frame").isEqualTo(7);
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
