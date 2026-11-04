package com.truesim.engine.ui.console;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.truesim.engine.app.Engine;
import com.truesim.engine.settings.Availability;
import com.truesim.engine.settings.Constraint;
import com.truesim.engine.settings.EngineSettings;
import com.truesim.engine.settings.Setting;
import com.truesim.engine.settings.SettingGroup;
import com.truesim.engine.ui.UiPlugin;
import com.truesim.engine.ui.scene.UiSceneTest;
import com.truesim.ui.console.ConsoleCommand;
import com.truesim.ui.console.HistoryStore;
import com.truesim.ui.console.LogInbox;
import com.truesim.ui.console.LogLevel;
import com.truesim.ui.console.LogRecord;
import com.truesim.ui.console.LogRing;
import com.truesim.ui.controls.Ui;
import com.truesim.ui.style.tss.Tss;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.event.Level;
import org.slf4j.helpers.NOPLogger;
import org.slf4j.spi.SLF4JServiceProvider;

/** The console over the engine: settings round trips through the overlay, the log from SLF4J, history, providers. */
class UiConsoleTest {

    enum Bloom { OFF, CHEAP, PHYSICAL }

    static final Setting<Bloom> BLOOM = Setting.choice("render.bloom", Bloom.CHEAP, "Bloom technique");
    static final Setting<Integer> SAMPLES = Setting.integer("render.samples", 4, 1, 16, "samples a pixel");
    static final Setting<Float> GAMMA = Setting.decimal("render.gamma", 2.2f, 1, 3, "display gamma");

    /** The last {@code n} messages of {@code ring}. */
    static List<String> last(LogRing ring, int n) {
        List<String> out = new ArrayList<>();
        for (int i = Math.max(0, ring.size() - n); i < ring.size(); i++) {
            out.add(ring.get(i).message().toString());
        }
        return out;
    }

    @Test
    void settingsRoundTripThroughTheConsoleAndItsOverlay() {
        UiPlugin plugin = new UiPlugin(textures -> new Ui(Tss.defaultTheme(), UiSceneTest.text())).view("main");
        try (Engine engine = UiSceneTest.engine(plugin, new com.truesim.engine.app.Application() {
        })) {
            EngineSettings settings = engine.settings();
            engine.registry().add(SettingGroup.root("render", "settings.render", 10), BLOOM, SAMPLES, GAMMA);
            UiConsole console = plugin.console("test", HistoryStore.memory());
            assertThatIllegalStateException().isThrownBy(() -> plugin.console("test", HistoryStore.memory()));
            assertThat(plugin.developerConsole()).isSameAs(console);
            assertThat(engine.registry().find("ui.console.logCapacity")).as("the console's tunables").isNotNull();
            assertThat(engine.registry().find("console.cheats")).isNotNull();
            LogRing log = console.console().log();
            engine.step();
            // printing
            console.console().run("render.bloom");
            assertThat(last(log, 1).get(0)).contains("render.bloom = CHEAP").contains("default CHEAP")
                .contains("OFF, CHEAP, PHYSICAL");
            // setting
            console.console().run("render.bloom physical");
            assertThat(settings.get(BLOOM)).isEqualTo(Bloom.PHYSICAL);
            // the overlay refuses what the hardware does not allow, with its reason
            settings.constrain(BLOOM, Constraint.choices(Set.of(Bloom.OFF, Bloom.CHEAP), Bloom.CHEAP,
                "no compute queue"));
            console.console().run("render.bloom PHYSICAL");
            assertThat(String.join("\n", last(log, 2))).contains("no compute queue");
            assertThat(settings.preferred(BLOOM)).as("the refused value is not set").isEqualTo(Bloom.PHYSICAL);
            console.console().run("render.bloom OFF");
            assertThat(settings.get(BLOOM)).isEqualTo(Bloom.OFF);
            // locked by the game: unavailable, with why
            settings.lock(SAMPLES, 2, "fixed by the benchmark preset");
            console.console().run("render.samples");
            assertThat(last(log, 1).get(0)).contains("fixed by the benchmark preset");
            console.console().run("render.samples 8");
            assertThat(String.join("\n", last(log, 2))).contains("locked");
            assertThat(settings.get(SAMPLES)).isEqualTo(2);
            // numbers: range checked, then set
            console.console().run("render.gamma 9");
            assertThat(settings.get(GAMMA)).isEqualTo(2.2f);
            console.console().run("render.gamma 2.4");
            assertThat(settings.get(GAMMA)).isEqualTo(2.4f);
            // the UI's own tunables are settings: set through the console, they reach the UI at the next frame
            console.console().run("ui.scale 1.25");
            engine.step();
            assertThat(com.truesim.ui.layout.Layout.UI_SCALE.get()).isEqualTo(1.25f);
            console.console().run("reset ui.scale");
            engine.step();
            assertThat(com.truesim.ui.layout.Layout.UI_SCALE.get()).isEqualTo(1f);
            // permissions from settings
            console.shell().register(ConsoleCommand.named("god").describe("cheat").cheat().action(call -> { }));
            assertThat(console.shell().refusal(console.shell().command("god"))).isNotNull();
            settings.set(SettingPermissions.CHEATS, true);
            assertThat(console.shell().refusal(console.shell().command("god"))).isNull();
            // the debugger and its commands
            assertThat(console.debugger()).isSameAs(console.debugger());
            settings.set(SettingPermissions.DEVELOPER, true);
            console.console().run("ui.inspect on");
            assertThat(console.debugger().shown()).isTrue();
            engine.step();
        } finally {
            com.truesim.ui.layout.Layout.UI_SCALE.set(1);
        }
    }

    @Test
    void settingVariablesDescribeAvailabilityWithoutAnEngine() {
        com.truesim.engine.settings.SettingRegistry registry = new com.truesim.engine.settings.SettingRegistry();
        EngineSettings settings = new EngineSettings();
        registry.add(SettingGroup.root("render", "settings.render", 10), BLOOM, SAMPLES);
        SettingVariables variables = new SettingVariables(registry, settings);
        assertThat(variables.names()).containsExactly("render.bloom", "render.samples");
        assertThat(variables.find("nothing")).isNull();
        var samples = variables.find("render.samples");
        assertThat(samples.min()).isEqualTo(1);
        assertThat(samples.max()).isEqualTo(16);
        assertThat(samples.kind()).isEqualTo(com.truesim.ui.console.ConsoleVariable.Kind.INTEGER);
        assertThat(samples.set("x")).contains("not a value");
        settings.availableWhen(SAMPLES, s -> Availability.unsupported("no MSAA here"));
        assertThat(samples.availability()).isEqualTo("unsupported: no MSAA here");
        assertThat(samples.set("8")).isEqualTo("unsupported: no MSAA here");
        assertThat(variables.find("render.bloom").choices()).containsExactly("OFF", "CHEAP", "PHYSICAL");
        assertThat(variables.find("render.bloom").description()).isEqualTo("Bloom technique");
    }

    @Test
    void aLogRecordFromSlf4jReachesTheRingWithItsSource() {
        ConsoleLogProvider provider = new ConsoleLogProvider();
        List<String> passedOn = new ArrayList<>();
        provider.initialize(recording(passedOn));
        assertThat(provider.getRequestedApiVersion()).startsWith("2.0");
        assertThat(provider.getMarkerFactory()).isNotNull();
        assertThat(provider.getMDCAdapter()).isNotNull();
        Logger logger = provider.getLoggerFactory().getLogger("com.example.Game");
        assertThat(provider.getLoggerFactory().getLogger("com.example.Game")).isSameAs(logger);
        LogInbox inbox = ConsoleLog.inbox();
        LogRing drained = new LogRing(64);
        inbox.drainTo(new LogRing(4096)); // what other tests left
        int captured = ConsoleLog.captureLevel();
        try {
            ConsoleLog.captureLevel(LogLevel.INFO);
            logger.debug("not captured {}", 1);
            logger.info("loaded {} of {}", 3, 4);
            logger.warn("slow frame: {} ms", 21.5, new IllegalStateException("late"));
            inbox.drainTo(drained);
        } finally {
            ConsoleLog.captureLevel(captured);
        }
        assertThat(drained.size()).isEqualTo(2);
        LogRecord info = drained.get(0);
        assertThat(info.message().toString()).isEqualTo("loaded 3 of 4");
        assertThat(info.level()).isEqualTo(LogLevel.INFO);
        assertThat(info.logger()).isEqualTo("com.example.Game");
        assertThat(info.source()).as("only warnings look up their source").isNull();
        LogRecord warn = drained.get(1);
        assertThat(warn.message().toString()).startsWith("slow frame: 21.5 ms").contains("late");
        assertThat(warn.source().toString()).startsWith("UiConsoleTest.java:");
        assertThat(passedOn).as("the other binding gets every call").containsExactly("DEBUG not captured {} [1]",
            "INFO loaded {} of {} [3, 4]", "WARN slow frame: {} ms [21.5] late");
        // alone, without another binding
        ConsoleLogProvider alone = new ConsoleLogProvider();
        alone.initialize(null);
        alone.getLoggerFactory().getLogger("x").error("e");
        assertThat(alone.delegate()).isNull();
        assertThat(UiConsole.level("OFF")).isGreaterThan(LogLevel.ERROR);
        assertThat(UiConsole.level("WARN")).isEqualTo(LogLevel.WARN);
        inbox.drainTo(drained);
    }

    /** A provider whose loggers write what they are handed into {@code out}. */
    private static SLF4JServiceProvider recording(List<String> out) {
        return new SLF4JServiceProvider() {
            final org.slf4j.helpers.BasicMarkerFactory markers = new org.slf4j.helpers.BasicMarkerFactory();

            @Override
            public org.slf4j.ILoggerFactory getLoggerFactory() {
                return name -> new org.slf4j.helpers.LegacyAbstractLogger() {
                    @Override
                    protected String getFullyQualifiedCallerName() {
                        return null;
                    }

                    @Override
                    protected void handleNormalizedLoggingCall(Level level, org.slf4j.Marker marker, String msg,
                        Object[] arguments, Throwable throwable) {
                        out.add(level + " " + msg + " " + java.util.Arrays.toString(arguments)
                            + (throwable == null ? "" : " " + throwable.getMessage()));
                    }

                    @Override
                    public boolean isTraceEnabled() {
                        return false;
                    }

                    @Override
                    public boolean isDebugEnabled() {
                        return true;
                    }

                    @Override
                    public boolean isInfoEnabled() {
                        return true;
                    }

                    @Override
                    public boolean isWarnEnabled() {
                        return true;
                    }

                    @Override
                    public boolean isErrorEnabled() {
                        return true;
                    }
                };
            }

            @Override
            public org.slf4j.IMarkerFactory getMarkerFactory() {
                return markers;
            }

            @Override
            public org.slf4j.spi.MDCAdapter getMDCAdapter() {
                return new org.slf4j.helpers.NOPMDCAdapter();
            }

            @Override
            public String getRequestedApiVersion() {
                return "2.0.99";
            }

            @Override
            public void initialize() {
            }
        };
    }

    @Test
    void historyScriptsAssetsAndNodesFromFilesAndTheScene(@TempDir Path folder) throws Exception {
        FileHistoryStore store = new FileHistoryStore(folder.resolve("settings"));
        assertThat(store.load("game")).isEmpty();
        store.save("game", List.of("render.bloom OFF", "two\nlines", "spawn 3"));
        assertThat(store.load("game")).containsExactly("render.bloom OFF", "spawn 3");
        assertThat(store.file("a/b")).hasFileName("console-history-a_b.txt");
        Files.writeString(store.file("bad"), "\n\nx\n");
        assertThat(store.load("bad")).containsExactly("x");

        Path scripts = Files.createDirectories(folder.resolve("cfg"));
        Files.writeString(scripts.resolve("autoexec.cfg"), "render.bloom OFF");
        Files.createDirectories(scripts.resolve("maps"));
        Files.writeString(scripts.resolve("maps/a.cfg"), "echo a");
        FolderScripts source = new FolderScripts(scripts);
        assertThat(source.read("autoexec.cfg")).isEqualTo("render.bloom OFF");
        assertThat(source.read("../settings/console-history-game.txt")).as("never outside the folder").isNull();
        assertThat(source.read("none.cfg")).isNull();
        List<String> listed = new ArrayList<>();
        source.list("ma", listed);
        assertThat(listed).containsExactly("maps/a.cfg");

        Path assets = Files.createDirectories(folder.resolve("assets/models"));
        Files.writeString(assets.resolve("crate.gltf"), "{}");
        Files.writeString(assets.resolve("crate.png"), "");
        FolderAssets provider = new FolderAssets(folder.resolve("assets")).type("model", ".gltf", ".glb");
        List<String> models = new ArrayList<>();
        provider.complete("model", "models/c", models);
        assertThat(models).containsExactly("models/crate.gltf");
        assertThat(provider.exists("model", "models/crate.gltf")).isTrue();
        assertThat(provider.exists("model", "models/crate.png")).isFalse();
        assertThat(provider.exists("texture", "models/crate.png")).as("a type without extensions").isTrue();

        com.truesim.scene.core.SceneGraph graph = new com.truesim.scene.core.SceneGraph(
            new com.truesim.common.state.ChangeClock());
        long gate = graph.create();
        long gone = graph.create();
        NamedNodes nodes = new NamedNodes(graph).name("gate", gate).name("garden", gone).name("tower", graph.create());
        graph.remove(gone);
        List<String> names = new ArrayList<>();
        nodes.complete("g", names);
        assertThat(names).containsExactly("gate");
        assertThat(nodes.exists("gate")).isTrue();
        assertThat(nodes.exists("garden")).isFalse();
        assertThat(nodes.node("gate")).isEqualTo(gate);
        assertThat(NOPLogger.NOP_LOGGER.getName()).isNotNull();
    }
}
