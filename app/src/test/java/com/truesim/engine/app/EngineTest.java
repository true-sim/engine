package com.truesim.engine.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.truesim.engine.settings.Setting;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EngineTest {

    /** A plugin that logs what is called, and can fail a stage some number of times. */
    static class Logged implements Plugin {
        final PluginKey<Logged> key;
        final List<PluginKey<?>> requires;
        final List<String> log;
        boolean optional;
        Stage failIn;
        int failures;
        Consumer<Startup> onRun = startup -> { };

        Logged(String name, List<String> log, PluginKey<?>... requires) {
            this.key = PluginKey.of(name, Logged.class);
            this.log = log;
            this.requires = List.of(requires);
        }

        @Override
        public PluginKey<Logged> key() {
            return key;
        }

        @Override
        public List<PluginKey<?>> requires() {
            return requires;
        }

        @Override
        public boolean optional() {
            return optional;
        }

        @Override
        public void start(Stage stage, Startup startup) {
            if (stage == failIn && failures > 0) {
                failures--;
                throw new StartupFailure(key + " failed in " + stage);
            }
            log.add(key + " " + stage);
            if (stage == Stage.RUN) {
                onRun.accept(startup);
            }
        }

        @Override
        public void beforeUpdate(Frame frame) {
            log.add(key + " before " + frame.number());
        }

        @Override
        public void afterUpdate(Frame frame) {
            log.add(key + " after " + frame.number());
        }

        @Override
        public void stop() {
            log.add(key + " stop");
        }
    }

    /** An application installing {@code plugins}, logging its calls. */
    static class App implements Application {
        final List<String> log;
        final List<Plugin> plugins;
        final List<StartupFailure> failures = new ArrayList<>();
        StartupFailure.Response answer;
        QuitResponse quitAnswer = QuitResponse.ALLOW;
        Consumer<AppConfig> config = app -> { };

        App(List<String> log, Plugin... plugins) {
            this.log = log;
            this.plugins = List.of(plugins);
        }

        @Override
        public void configure(AppConfig app) {
            app.use(plugins.toArray(new Plugin[0])).defaults(Engine.FRAME_RATE, 10_000);
            config.accept(app);
        }

        @Override
        public void start(Engine engine) {
            log.add("app start");
        }

        @Override
        public void update(Frame frame) {
            log.add("app update " + frame.number());
        }

        @Override
        public void stop() {
            log.add("app stop");
        }

        @Override
        public StartupFailure.Response onStartupFailure(StartupFailure failure) {
            failures.add(failure);
            return answer != null ? answer : Application.super.onStartupFailure(failure);
        }

        @Override
        public QuitResponse onQuitRequested(QuitReason reason) {
            log.add("quit? " + reason);
            return quitAnswer;
        }

        @Override
        public void onFocusChanged(boolean focused) {
            log.add("focus " + focused);
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            log.add("visible " + visible);
        }

        @Override
        public void onSuspend() {
            log.add("suspend");
        }

        @Override
        public void onResume() {
            log.add("resume");
        }
    }

    @Test
    void pluginsStartStageByStageAfterWhatTheyRequire() {
        List<String> log = new ArrayList<>();
        Logged rendering = new Logged("rendering", log);
        Logged xr = new Logged("xr", log, rendering.key);
        App app = new App(log, xr, rendering); // installed out of order: started by what they require
        try (Engine engine = Engine.create(app)) {
            assertThat(log).containsExactly("rendering BEFORE_INSTANCE", "xr BEFORE_INSTANCE", "rendering INSTANCE",
                "xr INSTANCE", "rendering GPU", "xr GPU", "rendering DEVICE", "xr DEVICE", "rendering GRAPH",
                "xr GRAPH", "rendering RUN", "xr RUN", "app start");
            assertThat(engine.get(xr.key)).isSameAs(xr);
            assertThat(engine.find(rendering.key)).containsSame(rendering);
            PluginKey<Logged> audio = PluginKey.of("audio", Logged.class);
            assertThat(engine.find(audio)).isEmpty();
            assertThatIllegalStateException().isThrownBy(() -> engine.get(audio))
                .withMessage("The plugin audio is not installed: add it with app.use(...)");
            assertThat(engine.id()).isEqualTo("app");
            log.clear();
            engine.step();
            assertThat(log).containsExactly("rendering before 0", "xr before 0", "app update 0", "rendering after 0",
                "xr after 0");
            assertThat(engine.frame().number()).isZero();
            assertThat(engine.frame().delta()).isZero();
            engine.step();
            assertThat(engine.frame().delta()).isBetween(0.0, Frame.MAX_DELTA);
            assertThat(engine.frame().time()).isEqualTo(engine.frame().delta());
            log.clear();
        }
        assertThat(log).containsExactly("app stop", "xr stop", "rendering stop");
    }

    @Test
    void optionalPluginsAreDroppedWhenTheyCannotStart() {
        List<String> log = new ArrayList<>();
        Logged rendering = new Logged("rendering", log);
        Logged xr = new Logged("xr", log, rendering.key);
        xr.optional = true;
        xr.failIn = Stage.BEFORE_INSTANCE;
        xr.failures = 1;
        Logged hands = new Logged("hands", log, xr.key); // requires XR: cannot start without it
        hands.optional = true;
        App app = new App(log, rendering, xr, hands);
        try (Engine engine = Engine.create(app)) {
            assertThat(app.failures).extracting(StartupFailure::plugin, StartupFailure::stage, StartupFailure::required)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(xr.key, Stage.BEFORE_INSTANCE, false),
                    org.assertj.core.groups.Tuple.tuple(hands.key, Stage.BEFORE_INSTANCE, false));
            assertThat(app.failures.get(0).reason()).isEqualTo("xr failed in BEFORE_INSTANCE");
            assertThat(app.failures.get(1).reason()).isEqualTo("it requires xr, which could not start");
            assertThat(log).contains("xr stop", "hands stop").doesNotContain("xr RUN", "hands RUN");
            assertThat(engine.find(xr.key)).isEmpty();
            assertThatIllegalStateException().isThrownBy(() -> engine.get(xr.key))
                .withMessageContaining("could not start");
            log.clear();
            engine.step();
            assertThat(log).containsExactly("rendering before 0", "app update 0", "rendering after 0");
        }
    }

    @Test
    void startupRetriesOrQuits() {
        List<String> log = new ArrayList<>();
        Logged xr = new Logged("xr", log);
        xr.failIn = Stage.DEVICE;
        xr.failures = 2;
        App retrying = new App(log, xr);
        retrying.answer = StartupFailure.Response.RETRY; // the player plugged the headset in
        try (Engine engine = Engine.create(retrying)) {
            assertThat(retrying.failures).hasSize(2);
            assertThat(engine.find(xr.key)).isPresent();
        }
        // A required plugin: quitting stops what started and throws.
        log.clear();
        Logged rendering = new Logged("rendering", log);
        Logged device = new Logged("device", log);
        device.failIn = Stage.GPU;
        device.failures = 1;
        App quitting = new App(log, rendering, device);
        assertThatExceptionOfType(StartupFailure.class).isThrownBy(() -> Engine.create(quitting))
            .withMessage("device failed in GPU");
        assertThat(log).endsWith("device stop", "rendering stop").doesNotContain("app start");
        // Continuing without a required plugin is quitting.
        Logged gpu = new Logged("gpu", log);
        gpu.failIn = Stage.INSTANCE;
        gpu.failures = 1;
        App stubborn = new App(log, gpu);
        stubborn.answer = StartupFailure.Response.CONTINUE_WITHOUT;
        assertThatExceptionOfType(StartupFailure.class).isThrownBy(() -> Engine.create(stubborn));
        // Anything a plugin throws is a startup failure, with its cause.
        PluginKey<Plugin> brokenKey = PluginKey.of("broken", Plugin.class);
        Plugin broken = new Plugin() {
            @Override
            public PluginKey<?> key() {
                return brokenKey;
            }

            @Override
            public void start(Stage stage, Startup startup) {
                throw new UnsupportedOperationException();
            }
        };
        assertThatExceptionOfType(StartupFailure.class).isThrownBy(() -> Engine.create(new App(log, broken)))
            .withMessage("UnsupportedOperationException").withCauseInstanceOf(UnsupportedOperationException.class)
            .satisfies(f -> assertThat(f.required()).isTrue());
        assertThat(new StartupFailure("no headset").stage()).isNull();
    }

    @Test
    void configurationMistakesAreCaughtBeforeAnythingStarts() {
        List<String> log = new ArrayList<>();
        Logged xr = new Logged("xr", log, PluginKey.of("rendering", Logged.class));
        assertThatIllegalStateException().isThrownBy(() -> Engine.create(new App(log, xr)))
            .withMessage("The plugin xr requires rendering, which is not installed: add it with app.use(...)");
        Logged a = new Logged("a", log);
        Logged b = new Logged("b", log, a.key);
        Logged cyclic = new Logged("a", log, b.key) {
            @Override
            public PluginKey<Logged> key() {
                return a.key;
            }
        };
        assertThatIllegalStateException().isThrownBy(() -> Engine.create(new App(log, cyclic, b)))
            .withMessageContaining("cycle");
        assertThatIllegalArgumentException().isThrownBy(() -> Engine.create(new App(log, a, a)))
            .withMessageContaining("installed twice");
        App badId = new App(log);
        badId.config = app -> app.id("Rock Garden");
        assertThatIllegalArgumentException().isThrownBy(() -> Engine.create(badId));
        assertThat(log).isEmpty();
    }

    @Test
    void onePluginDrivesFrames() {
        List<String> log = new ArrayList<>();
        Logged xr = new Logged("xr", log);
        xr.onRun = startup -> startup.driveFrames(new FrameDriver() {
            @Override
            public void beginFrame(Frame frame) {
                log.add("wait " + frame.number());
            }

            @Override
            public void endFrame(Frame frame) {
                log.add("submit " + frame.number());
            }
        });
        Logged other = new Logged("other", log);
        try (Engine engine = Engine.create(new App(log, xr))) {
            log.clear();
            engine.step();
            assertThat(log).containsExactly("wait 0", "xr before 0", "app update 0", "xr after 0", "submit 0");
        }
        other.onRun = startup -> startup.driveFrames(frame -> { });
        xr.onRun = startup -> startup.driveFrames(frame -> { });
        assertThatExceptionOfType(StartupFailure.class).isThrownBy(() -> Engine.create(new App(log, xr, other)))
            .withMessageContaining("xr drives frames already");
        // A dropped plugin gives the slot back.
        Logged optional = new Logged("optional", log);
        optional.optional = true;
        optional.onRun = startup -> {
            startup.driveFrames(frame -> log.add("never"));
            throw new StartupFailure("lost the headset");
        };
        try (Engine engine = Engine.create(new App(log, optional))) {
            log.clear();
            engine.step();
            assertThat(log).containsExactly("app update 0");
        }
    }

    @Test
    void theDefaultDriverPacesFramesAtTheFrameRate() {
        App app = new App(new ArrayList<>());
        app.config = config -> config.defaults(Engine.FRAME_RATE, 100);
        try (Engine engine = Engine.create(app)) {
            long start = System.nanoTime();
            for (int i = 0; i < 6; i++) {
                engine.step();
            }
            assertThat(System.nanoTime() - start).as("5 periods of 10 ms").isGreaterThan(45_000_000L);
        }
    }

    @Test
    void quitsAreRequestedDeferredAndForced() {
        List<String> log = new ArrayList<>();
        App app = new App(log);
        app.quitAnswer = Application.QuitResponse.DEFER;
        try (Engine engine = Engine.create(app)) {
            engine.requestQuit(Application.QuitReason.WINDOW_CLOSED, false);
            assertThat(engine.running()).as("deferred: save first?").isTrue();
            engine.requestQuit(Application.QuitReason.WINDOW_CLOSED, false);
            assertThat(engine.running()).as("asked again within 3 s: forced").isFalse();
            assertThat(log).containsExactly("app start", "quit? WINDOW_CLOSED");
            log.clear();
            engine.step();
            assertThat(log).as("no frames once quitting").isEmpty();
        }
        try (Engine engine = Engine.create(app)) {
            engine.requestQuit(Application.QuitReason.SYSTEM, true);
            assertThat(engine.running()).isFalse();
        }
        app.quitAnswer = Application.QuitResponse.ALLOW;
        try (Engine engine = Engine.create(app)) {
            engine.requestQuit(Application.QuitReason.PLUGIN, false);
            assertThat(engine.running()).isFalse();
        }
        Engine quitting = Engine.create(app);
        quitting.quit();
        assertThat(quitting.running()).isFalse();
        quitting.close();
        quitting.close(); // once
        // The engine owns the loop.
        App counting = new App(new ArrayList<>()) {
            @Override
            public void update(Frame frame) {
                if (frame.number() == 2) {
                    TrueSimHolder.engine.quit();
                }
            }

            @Override
            public void start(Engine engine) {
                TrueSimHolder.engine = engine;
            }
        };
        TrueSim.run(counting);
        assertThat(TrueSimHolder.engine.frame().number()).isEqualTo(2);
    }

    /** The engine of the application TrueSim runs, for the test above. */
    static final class TrueSimHolder {
        static Engine engine;
    }

    @Test
    void focusVisibilityAndSuspendReachTheApplication() {
        List<String> log = new ArrayList<>();
        try (Engine engine = Engine.create(new App(log))) {
            log.clear();
            engine.focusChanged(true); // no change
            engine.focusChanged(false);
            engine.visibilityChanged(true); // no change
            engine.visibilityChanged(false);
            engine.suspended();
            engine.resumed();
            engine.visibilityChanged(true);
            assertThat(log).containsExactly("focus false", "visible false", "suspend", "resume", "visible true");
            assertThat(engine.focused()).isFalse();
            assertThat(engine.visible()).isTrue();
        }
    }

    private static final Setting<Integer> SIZE = Setting.integer("shadows.size", 2048, 256, 8192, "texels across");

    @Test
    void thePlayersSettingsAreLoadedAndSaved(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("rock-garden/settings.properties");
        App app = new App(new ArrayList<>());
        app.config = config -> config.id("rock-garden").name("Rock Garden").version("1.2").saveSettings(file)
            .defaults(SIZE, 1024);
        try (Engine engine = Engine.create(app, "--set", "app.frameRate=5000")) {
            assertThat(engine.settings().get(SIZE)).as("the game's default").isEqualTo(1024);
            assertThat(engine.settings().get(Engine.FRAME_RATE)).as("the command line").isEqualTo(5000);
            engine.settings().set(SIZE, 4096);
            assertThat(engine.name()).isEqualTo("Rock Garden");
            assertThat(engine.version()).isEqualTo("1.2");
            assertThat(engine.registry().find("app.frameRate")).isNotNull();
            assertThat(engine.tasks()).isNotNull();
        }
        assertThat(SettingsFile.load(file)).as("only the player's saved").containsExactly(Map.entry("shadows.size",
            "4096"));
        assertThat(Files.readString(file)).contains("Rock Garden 1.2");
        try (Engine engine = Engine.create(app)) {
            assertThat(engine.settings().get(SIZE)).as("loaded again").isEqualTo(4096);
        }
        assertThat(SettingsFile.load(dir.resolve("none.properties"))).isEmpty();
        App defaultFolder = new App(new ArrayList<>());
        defaultFolder.config = config -> {
            config.id("rock-garden").saveSettings();
            assertThat(config.settingsFile().endsWith(Path.of("rock-garden", "settings.properties"))).isTrue();
            config.saveSettings(file);
        };
        Engine.create(defaultFolder).close();
    }

    @Test
    void settingsFoldersFollowTheSystem() {
        assertThat(SettingsFile.folder("game", "Windows 11", "C:\\Users\\a\\AppData\\Roaming", null, "C:\\Users\\a"))
            .isEqualTo(Path.of("C:\\Users\\a\\AppData\\Roaming", "game"));
        assertThat(SettingsFile.folder("game", "Mac OS X", null, null, "/Users/a"))
            .isEqualTo(Path.of("/Users/a", "Library", "Application Support", "game"));
        assertThat(SettingsFile.folder("game", "Linux", null, "/home/a/.cfg", "/home/a"))
            .isEqualTo(Path.of("/home/a/.cfg", "game"));
        assertThat(SettingsFile.folder("game", "Linux", null, "", "/home/a"))
            .isEqualTo(Path.of("/home/a", ".config", "game"));
        assertThat(SettingsFile.folder("game", "Windows 11", null, null, "/home/a"))
            .isEqualTo(Path.of("/home/a", ".config", "game"));
        assertThat(SettingsFile.folder("game")).isNotNull();
    }
}
