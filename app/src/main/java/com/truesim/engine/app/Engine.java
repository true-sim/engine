package com.truesim.engine.app;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.jobs.TaskScheduler;
import com.truesim.engine.settings.EngineSettings;
import com.truesim.engine.settings.Setting;
import com.truesim.engine.settings.SettingRegistry;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/**
 * A running application: its plugins, settings, jobs and frame loop. {@link TrueSim#run} makes one and runs its loop;
 * tools, tests and embedding drive it themselves:
 *
 * <pre>{@code
 * try (Engine engine = Engine.create(app, args)) {
 *     while (engine.running()) {
 *         engine.step();
 *     }
 * }
 * }</pre>
 *
 * <p>{@link #create} configures the application, loads the player's settings and the command line, then starts every
 * plugin through every {@link Stage}, each after those it requires, and calls {@link Application#start}. A frame
 * ({@link #step}) waits for the frame driver, delivers finished jobs, runs the plugins' {@code beforeUpdate}, the
 * application's update and the plugins' {@code afterUpdate}, refreshes settings availability and ends the frame.
 * {@link #close} stops the application and the plugins in reverse order and saves the player's settings.
 *
 * <p>Used from the main thread only. A frame allocates nothing of its own.
 */
public final class Engine implements AutoCloseable {

    /** Frames per second when no plugin paces frames (a server, a test): a developer setting. */
    public static final Setting<Integer> FRAME_RATE =
        Setting.integer("app.frameRate", 60, 1, 10_000, "frames per second when no plugin paces frames");

    /** Two quit requests within this time force the quit. */
    static final long FORCE_WITHIN_NANOS = TimeUnit.SECONDS.toNanos(3);

    private final Application app;
    private final EngineSettings settings = new EngineSettings();
    private final SettingRegistry registry = new SettingRegistry();
    private final TaskScheduler tasks = new TaskScheduler();
    private final Frame frame = new Frame();
    private final Startup startup = new Startup(this);
    private final Set<PluginKey<?>> installed = new LinkedHashSet<>();
    private final Map<PluginKey<?>, Plugin> active = new IdentityHashMap<>();
    private Plugin[] running = new Plugin[0]; // the started plugins, in start order
    private String id;
    private String name;
    private String version;
    private Path settingsFile;
    private FrameDriver driver = new Paced();
    private Plugin driverOwner; // the plugin that claimed the frame driver, or null
    private FrameDriver defaultDriver; // a plugin's default (the presenter), or null: paced
    private Plugin defaultOwner;
    private boolean live;
    private boolean closed;
    private boolean focused = true;
    private boolean visible = true;
    private long lastQuitRequest = Long.MIN_VALUE;

    private Engine(Application app) {
        this.app = app;
    }

    /**
     * Configures {@code app}, loads the player's settings and {@code args}' {@code --set name=value}, starts every
     * plugin through every stage and calls {@link Application#start}. Throws the {@link StartupFailure} the
     * application answered with {@link StartupFailure.Response#QUIT}, after stopping what started.
     */
    @AllowAllocation("starting up")
    public static Engine create(Application app, String... args) {
        Engine engine = new Engine(app);
        try {
            engine.configure(args);
            engine.startPlugins();
            engine.registry.check();
            engine.live = true;
            app.start(engine);
        } catch (RuntimeException | Error e) {
            engine.live = false;
            engine.stopPlugins(e);
            engine.tasks.close();
            engine.closed = true;
            throw e;
        }
        return engine;
    }

    @AllowAllocation("starting up")
    private void configure(String... args) {
        AppConfig config = new AppConfig(settings);
        app.configure(config);
        id = config.id();
        name = config.name();
        version = config.version();
        settingsFile = config.settingsFile();
        registry.addHidden(FRAME_RATE);
        if (settingsFile != null) {
            settings.load(SettingsFile.load(settingsFile));
        }
        settings.commandLine(args);
        List<Plugin> plugins = config.plugins();
        for (Plugin plugin : plugins) {
            installed.add(plugin.key());
        }
        for (Plugin plugin : plugins) {
            for (PluginKey<?> required : plugin.requires()) {
                if (!installed.contains(required)) {
                    throw new IllegalStateException("The plugin " + plugin.key() + " requires " + required
                        + ", which is not installed: add it with app.use(...)");
                }
            }
        }
        running = ordered(plugins).toArray(new Plugin[0]);
        for (Plugin plugin : running) {
            active.put(plugin.key(), plugin);
        }
    }

    /** {@code plugins}, each after those it requires, else in the order installed. */
    @AllowAllocation("starting up")
    private static List<Plugin> ordered(List<Plugin> plugins) {
        List<Plugin> order = new ArrayList<>();
        Set<PluginKey<?>> placed = new LinkedHashSet<>();
        List<Plugin> left = new ArrayList<>(plugins);
        while (!left.isEmpty()) {
            Plugin next = null;
            for (Plugin plugin : left) {
                if (placed.containsAll(plugin.requires())) {
                    next = plugin;
                    break;
                }
            }
            if (next == null) {
                throw new IllegalStateException("The plugins require each other in a cycle: "
                    + left.stream().map(p -> p.key().name()).toList());
            }
            left.remove(next);
            placed.add(next.key());
            order.add(next);
        }
        return order;
    }

    @AllowAllocation("starting up")
    private void startPlugins() {
        for (Stage stage : Stage.values()) {
            for (Plugin plugin : running.clone()) {
                if (active.get(plugin.key()) == plugin) {
                    start(plugin, stage);
                }
            }
        }
    }

    /** Starts {@code plugin}'s part of {@code stage}, asking the application on failure. */
    @AllowAllocation("starting up")
    private void start(Plugin plugin, Stage stage) {
        while (true) {
            try {
                for (PluginKey<?> required : plugin.requires()) {
                    if (!active.containsKey(required)) {
                        throw new StartupFailure("it requires " + required + ", which could not start");
                    }
                }
                startup.current(plugin);
                plugin.start(stage, startup);
                return;
            } catch (Exception e) {
                StartupFailure failure = StartupFailure.of(e, stage, plugin);
                StartupFailure.Response response = app.onStartupFailure(failure);
                if (response == StartupFailure.Response.CONTINUE_WITHOUT && plugin.optional()) {
                    drop(plugin);
                    return;
                }
                if (response != StartupFailure.Response.RETRY) {
                    throw failure;
                }
            } finally {
                startup.current(null);
            }
        }
    }

    @AllowAllocation("starting up")
    private void drop(Plugin plugin) {
        active.remove(plugin.key());
        List<Plugin> kept = new ArrayList<>(List.of(running));
        kept.remove(plugin);
        running = kept.toArray(new Plugin[0]);
        if (driverOwner == plugin) {
            driverOwner = null;
        }
        if (defaultOwner == plugin) {
            defaultDriver = null;
            defaultOwner = null;
        }
        driver = driverOwner != null ? driver : defaultDriver != null ? defaultDriver : new Paced();
        plugin.stop();
    }

    void claimFrameDriver(Plugin plugin, FrameDriver claimed) {
        if (driverOwner != null && driverOwner != plugin) {
            throw new IllegalStateException("The plugin " + driverOwner.key() + " drives frames already");
        }
        driver = claimed;
        driverOwner = plugin;
    }

    void defaultFrameDriver(Plugin plugin, FrameDriver fallback) {
        if (defaultOwner != null && defaultOwner != plugin) {
            throw new IllegalStateException("The plugin " + defaultOwner.key() + " gives the default frame driver");
        }
        defaultDriver = fallback;
        defaultOwner = plugin;
        if (driverOwner == null) {
            driver = fallback;
        }
    }

    /** The plugin installed by {@code key}; throws naming {@code app.use(...)} if it is not, or could not start. */
    public <T extends Plugin> T get(PluginKey<T> key) {
        Plugin plugin = active.get(key);
        if (plugin == null) {
            throw missing(key);
        }
        return key.type().cast(plugin);
    }

    @AllowAllocation("reporting a mistake")
    private IllegalStateException missing(PluginKey<?> key) {
        return new IllegalStateException(installed.contains(key)
            ? "The plugin " + key + " could not start: read it with find(...) where it is optional"
            : "The plugin " + key + " is not installed: add it with app.use(...)");
    }

    /** The plugin installed by {@code key}, if it is and started: for optional plugins. */
    @AllowAllocation("an Optional: read plugins once, at start")
    public <T extends Plugin> Optional<T> find(PluginKey<T> key) {
        Plugin plugin = active.get(key);
        return plugin == null ? Optional.empty() : Optional.of(key.type().cast(plugin));
    }

    /** The settings store: the engine's defaults, the game's, the player's, the command line's. */
    public EngineSettings settings() {
        return settings;
    }

    /** The settings registry, for options screens. */
    public SettingRegistry registry() {
        return registry;
    }

    /** The job scheduler: work on lanes in the background, delivered on the main thread at the start of a frame. */
    public TaskScheduler tasks() {
        return tasks;
    }

    /** The application's id ({@link AppConfig#id}). */
    public String id() {
        return id;
    }

    /** The application's name. */
    public String name() {
        return name;
    }

    /** The application's version. */
    public String version() {
        return version;
    }

    /** The frame running, or the last one. */
    public Frame frame() {
        return frame;
    }

    /** Whether it runs: false once quitting or closed. */
    public boolean running() {
        return live;
    }

    /** Whether the application has keyboard focus. */
    public boolean focused() {
        return focused;
    }

    /** Whether the application is visible: not minimised, covered or in a headset taken off. */
    public boolean visible() {
        return visible;
    }

    /** Runs one frame; does nothing once quitting. Allocates nothing of its own. */
    public void step() {
        if (!live) {
            return;
        }
        frame.next(System.nanoTime());
        driver.beginFrame(frame);
        tasks.drainCompleted();
        Plugin[] plugins = running;
        for (Plugin plugin : plugins) {
            plugin.beforeUpdate(frame);
        }
        app.update(frame);
        for (Plugin plugin : plugins) {
            plugin.afterUpdate(frame);
        }
        settings.refreshAvailability();
        driver.endFrame(frame);
    }

    /** Quits after this frame, as the game decided: no one is asked. */
    public void quit() {
        live = false;
    }

    /**
     * A quit is requested for {@code reason} (by a plugin: a window's close button, the system). The application is
     * asked ({@link Application#onQuitRequested}) unless {@code forced} or a request came in the last 3 seconds.
     */
    public void requestQuit(Application.QuitReason reason, boolean forced) {
        long now = System.nanoTime();
        boolean again = lastQuitRequest != Long.MIN_VALUE && now - lastQuitRequest < FORCE_WITHIN_NANOS;
        lastQuitRequest = now;
        if (forced || again || app.onQuitRequested(reason) == Application.QuitResponse.ALLOW) {
            live = false;
        }
    }

    /** The application gained or lost focus (told by the platform plugin). */
    public void focusChanged(boolean hasFocus) {
        if (hasFocus != focused) {
            focused = hasFocus;
            app.onFocusChanged(hasFocus);
        }
    }

    /** The application became visible or hidden (told by the platform or XR plugin). */
    public void visibilityChanged(boolean isVisible) {
        if (isVisible != visible) {
            visible = isVisible;
            app.onVisibilityChanged(isVisible);
        }
    }

    /** The system suspends the application. */
    public void suspended() {
        app.onSuspend();
    }

    /** The system resumed the application. */
    public void resumed() {
        app.onResume();
    }

    /**
     * Stops the application, then the plugins in reverse start order, saves the player's settings and stops the jobs.
     * Each part is stopped though one before throws; the first exception is rethrown after, later ones suppressed.
     */
    @Override
    @AllowAllocation("shutting down")
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        live = false;
        Throwable failure = null;
        try {
            app.stop();
        } catch (RuntimeException | Error e) {
            failure = e;
        }
        failure = stopPlugins(failure);
        try {
            if (settingsFile != null) {
                SettingsFile.save(settingsFile, settings.toMap(), name + " " + version);
            }
        } catch (RuntimeException e) {
            failure = add(failure, e);
        }
        tasks.close();
        if (failure instanceof RuntimeException e) {
            throw e;
        }
        if (failure instanceof Error e) {
            throw e;
        }
    }

    /** Stops the started plugins in reverse order; returns {@code failure} with what they threw added. */
    private Throwable stopPlugins(Throwable failure) {
        Throwable result = failure;
        for (int i = running.length - 1; i >= 0; i--) {
            try {
                running[i].stop();
            } catch (RuntimeException | Error e) {
                result = add(result, e);
            }
        }
        running = new Plugin[0];
        active.clear();
        return result;
    }

    private static Throwable add(Throwable failure, Throwable more) {
        if (failure == null) {
            return more;
        }
        failure.addSuppressed(more);
        return failure;
    }

    /** The default frame driver: starts frames at {@link #FRAME_RATE}. */
    private final class Paced implements FrameDriver {
        private long next = Long.MIN_VALUE;

        @Override
        public void beginFrame(Frame current) {
            long period = 1_000_000_000L / settings.get(FRAME_RATE);
            long now = System.nanoTime();
            if (next != Long.MIN_VALUE && now < next) {
                LockSupport.parkNanos(next - now);
                now = next;
            }
            next = (next == Long.MIN_VALUE || now - next > period ? now : next) + period;
        }
    }
}
