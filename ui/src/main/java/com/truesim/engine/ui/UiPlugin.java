package com.truesim.engine.ui;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.app.Frame;
import com.truesim.engine.app.Plugin;
import com.truesim.engine.app.PluginKey;
import com.truesim.engine.app.Stage;
import com.truesim.engine.app.Startup;
import com.truesim.engine.graph.FrameGraph;
import com.truesim.engine.graph.Presenter;
import com.truesim.engine.graph.Rendering;
import com.truesim.engine.graph.ViewPort;
import com.truesim.engine.input.Input;
import com.truesim.engine.platform.Desktop;
import com.truesim.engine.platform.Platform;
import com.truesim.engine.platform.SystemCursor;
import com.truesim.engine.platform.Window;
import com.truesim.engine.settings.Setting;
import com.truesim.engine.settings.SettingGroup;
import com.truesim.engine.settings.SettingRegistry;
import com.truesim.ui.controls.Ui;
import com.truesim.ui.core.Tunable;
import com.truesim.ui.core.input.Cursor;
import com.truesim.ui.core.input.PointerSource;
import com.truesim.ui.core.input.UiInput;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * The UI as an engine plugin: one {@link Ui} on a view, its input, settings and rendering wired into the frame.
 * Installed with {@code app.use(new UiPlugin(textures -> new Ui(theme, textSystem(textures))))} next to
 * {@link Rendering} and {@link Input} (and the {@link Desktop} when there is a window); game code reaches the UI by
 * {@code engine.get(UiPlugin.KEY).ui()}.
 *
 * <ul>
 * <li><b>Settings</b> (design: tunables are settings): every tunable of the UI ({@link Ui#tunables()}) becomes a
 * setting through {@link UiTunables}, and {@link UiCommands#ALL} joins them, in the settings group {@code ui}
 * ({@code settings.ui}); changed settings reach the tunables at each frame's start.</li>
 * <li><b>Input</b>: the {@code ui} action group ({@link UiActions#UI}) is added to input; each frame starts with the
 * frame's {@link UiCommands}, the {@link MousePointer} and the typed text and IME composition of {@link UiTextInput}
 * written into one {@link UiInput}, with any other {@link PointerSource}s added ({@link #pointers}: a virtual
 * cursor, touch, tests). The window's clipboard ({@link SdlClipboard}) is the UI's.</li>
 * <li><b>The frame, in two phases</b> (no frame of latency either way): at its start ({@link #beforeUpdate}), after
 * input sampled the devices, the UI processes that input ({@code ui.input(input, nanos)}, the frame's real time):
 * picking, events, focus, commands and text, so the game's update sees this frame's clicks. After the game's update
 * and late update ({@link #afterUpdate}) the UI updates ({@code ui.update(width, height)}, the view's size in pixels:
 * listeners, animations, styles, layout, recording) and its layers are submitted to its {@link GpuUiRenderer}, a
 * technique of the view providing {@code Keys.UI}, so what the input, the UI's listeners and the game changed shows
 * in this frame.</li>
 * <li><b>The window</b>: the display's content scale ({@code Window.displayScale()}) is the layout's content scale, so
 * the UI keeps its size in points on a high-density display; the mouse cursor follows the UI's ({@code cursor} in
 * styles) while the pointer is over the UI.</li>
 * <li><b>OS windows</b> for docking ({@link #osWindows()}, with a window and a presenter): given a host
 * ({@code osWindows().host(new OsDockHost(overlays, dock, osWindows()))}), each frame draws every OS window's region
 * of the UI into it, maps the mouse of every window into the UI, starts text input on the window holding the field,
 * and closes a window's area when its close button is pressed.</li>
 * </ul>
 *
 * <p>Per frame it allocates nothing: the input, the layers and the cursor change are kept.
 */
public final class UiPlugin implements Plugin {

    /** Its key. */
    public static final PluginKey<UiPlugin> KEY = PluginKey.of("ui", UiPlugin.class);

    private static final Cursor[] CURSORS = Cursor.values();
    private static final SystemCursor[] SYSTEM = new SystemCursor[CURSORS.length];

    static {
        for (Cursor c : CURSORS) {
            SYSTEM[c.ordinal()] = systemCursor(c);
        }
    }

    private final Function<com.truesim.ui.core.draw.UiTextures, Ui> factory;
    private String viewName;
    private Ui ui;
    private final UiInput input = new UiInput(64);
    private UiTunables tunables;
    private UiCommands commands;
    private MousePointer mouse;
    private UiTextInput text;
    private GpuUiRenderer renderer;
    private ViewPort view;
    private Window window;
    private Platform platform;
    private Desktop desktop;
    private UiOsWindows osWindows;
    private SystemCursor shownCursor;
    private float shownScale = -1;
    private PointerSource[] sources = new PointerSource[0];
    private com.truesim.engine.ui.scene.UiScene scene; // the scene bridge, or null: the UI alone
    private com.truesim.engine.settings.EngineSettings settings;
    private SettingRegistry registry;
    private SettingGroup group;
    private String appId = "app";
    private com.truesim.engine.ui.console.UiConsole console; // the developer console, or null

    /**
     * The UI made by {@code factory} at startup, given the textures it should use (null: its own, or its text
     * system's atlas): {@code textures -> new Ui(Tss.defaultTheme(), text)}.
     */
    public UiPlugin(Function<com.truesim.ui.core.draw.UiTextures, Ui> factory) {
        this.factory = Objects.requireNonNull(factory, "factory");
    }

    /** Adds {@code source}'s pointers to the UI's input each frame, after the mouse's; returns this. */
    @AllowAllocation("sources are added when the game is set up")
    public UiPlugin pointers(PointerSource source) {
        sources = java.util.Arrays.copyOf(sources, sources.length + 1);
        sources[sources.length - 1] = Objects.requireNonNull(source, "source");
        return this;
    }

    /** Draws on the view named {@code name} (default: the view the first window presents, else the first view). */
    public UiPlugin view(String name) {
        viewName = name;
        return this;
    }

    @Override
    public PluginKey<UiPlugin> key() {
        return KEY;
    }

    @Override
    public List<PluginKey<?>> requires() {
        return List.of(Rendering.KEY, Input.KEY);
    }

    @Override
    public List<PluginKey<?>> after() {
        return List.of(Desktop.KEY);
    }

    @Override
    @AllowAllocation("starting up")
    public void start(Stage stage, Startup startup) {
        switch (stage) {
            case BEFORE_INSTANCE -> {
                ui = Objects.requireNonNull(factory.apply(null), "the factory made no UI");
                Input in = startup.get(Input.KEY);
                in.add(UiActions.UI);
                SettingRegistry registry = startup.registry();
                SettingGroup group = SettingGroup.root("ui", "settings.ui", 45);
                registry.add(group, UiCommands.ALL.toArray(new Setting<?>[0]));
                registry.add(group, com.truesim.engine.ui.scene.UiScene.FAR,
                    com.truesim.engine.ui.scene.DepthOcclusion.LEVEL);
                settings = startup.settings();
                this.registry = registry;
                this.group = group;
                appId = startup.engine().id();
                tunables = new UiTunables(startup.settings()).add(registry, group,
                    ui.tunables().toArray(new Tunable[0]));
                commands = new UiCommands(in, startup.settings());
                desktop = startup.find(Desktop.KEY).orElse(null);
            }
            case RUN -> wire(startup);
            default -> {
                // nothing in the other stages: views exist from GRAPH on, windows by RUN
            }
        }
    }

    @AllowAllocation("starting up")
    private void wire(Startup startup) {
        Rendering rendering = startup.get(Rendering.KEY);
        FrameGraph graph = rendering.graph();
        Presenter.Target target = null;
        if (!rendering.headless()) {
            for (Presenter.Target t : rendering.presenter().targets()) {
                if (viewName == null || t.view().name().equals(viewName)) {
                    target = t;
                    break;
                }
            }
        }
        if (target != null) {
            view = target.view();
            window = target.window();
        } else {
            for (ViewPort v : graph.views()) {
                if (viewName == null || v.name().equals(viewName)) {
                    view = v;
                    break;
                }
            }
        }
        if (view == null) {
            throw new IllegalStateException(viewName == null ? "The UI needs a view: add one to the frame graph"
                : "No view is named " + viewName);
        }
        renderer = new GpuUiRenderer(graph);
        view.add(renderer);
        Input in = startup.get(Input.KEY);
        if (window != null && desktop != null) {
            platform = desktop.platform();
            mouse = new MousePointer(window, in);
            desktop.listen(mouse);
            text = new UiTextInput(commands, window);
            ui.interaction().textInputHost(text);
            ui.interaction().clipboard(new SdlClipboard(platform));
            if (target != null) {
                osWindows = new UiOsWindows(platform, graph, rendering.presenter(), window, renderer,
                    target.settings());
                desktop.listen(osWindows);
                mouse.mapper(osWindows);
                text.locator(osWindows);
            }
        }
    }

    /**
     * Docking's OS windows over this UI's window ({@link UiOsWindows}): give it an {@code OsDockHost} and panels tear
     * out into windows of their own. Null without a window or a presenter (headless). From {@link Stage#RUN}.
     */
    public UiOsWindows osWindows() {
        return osWindows;
    }

    /** The UI; from the first startup stage on. */
    public Ui ui() {
        if (ui == null) {
            throw new IllegalStateException("The UI is made when the engine starts");
        }
        return ui;
    }

    /**
     * Runs the UI through a scene bridge over the game's {@code graph} and its {@code index} (design 4.7): 3D nodes,
     * world panels and gizmos take pointers by their rays, interactive nodes on {@code UiScene.INTERACTIVE}. Give it the
     * camera the main view draws through ({@code UiScene.camera}). Call once, after the engine started.
     */
    @AllowAllocation("set up once")
    public com.truesim.engine.ui.scene.UiScene scene(com.truesim.scene.core.SceneGraph graph,
        com.truesim.scene.core.SpatialIndex index) {
        return scene(graph, index, com.truesim.engine.ui.scene.UiScene.INTERACTIVE);
    }

    /** As {@link #scene(com.truesim.scene.core.SceneGraph, com.truesim.scene.core.SpatialIndex)}, on {@code layer}. */
    @AllowAllocation("set up once")
    public com.truesim.engine.ui.scene.UiScene scene(com.truesim.scene.core.SceneGraph graph,
        com.truesim.scene.core.SpatialIndex index, com.truesim.scene.core.SceneLayer layer) {
        if (scene != null) {
            throw new IllegalStateException("The UI has a scene bridge already");
        }
        scene = new com.truesim.engine.ui.scene.UiScene(ui(), graph, index, layer);
        return scene;
    }

    /**
     * Adds the developer console (design 5.10): {@code Console.create(ui, appId, store)} over the engine's settings as
     * variables, its permissions from settings, its log fed by SLF4J ({@code ConsoleLogProvider}); registers the
     * console's tunables and settings. Call once, after the engine started.
     */
    @AllowAllocation("set up once")
    public com.truesim.engine.ui.console.UiConsole console(String id, com.truesim.ui.console.HistoryStore store) {
        if (console != null) {
            throw new IllegalStateException("The UI has a console already");
        }
        Ui u = ui();
        registry.add(group, com.truesim.engine.ui.console.UiConsole.SETTINGS.toArray(new Setting<?>[0]));
        tunables.add(registry, group, com.truesim.ui.console.ConsoleTunables.all().toArray(new Tunable[0]));
        console = new com.truesim.engine.ui.console.UiConsole(u, id, store, registry, settings);
        return console;
    }

    /** The console for the engine's app id, its history in the user's settings folder. */
    @AllowAllocation("set up once")
    public com.truesim.engine.ui.console.UiConsole console() {
        return console(appId, new com.truesim.engine.ui.console.FileHistoryStore(
            com.truesim.engine.app.SettingsFile.folder(appId)));
    }

    /** The developer console, or null. */
    public com.truesim.engine.ui.console.UiConsole developerConsole() {
        return console;
    }

    /** The scene bridge, or null. */
    public com.truesim.engine.ui.scene.UiScene scene() {
        return scene;
    }

    /** The view it draws on; from {@link Stage#RUN}. */
    public ViewPort view() {
        return view;
    }

    /** Its renderer, a technique of {@link #view()}. */
    public GpuUiRenderer renderer() {
        return renderer;
    }

    /** The UI's input of the frame (pointers, commands, text). */
    public UiInput input() {
        return input;
    }

    /** The commands from the {@code ui} actions. */
    public UiCommands commands() {
        return commands;
    }

    /** At the frame's start: settings, the frame's input sampled into the UI's, the UI's first phase (input). */
    @Override
    public void beforeUpdate(Frame frame) {
        if (view == null) {
            return;
        }
        tunables.update();
        float scale = window != null ? window.displayScale() : 1;
        if (scale > 0 && scale != shownScale) {
            shownScale = scale;
            ui.layout().contentScale(scale);
            if (text != null) {
                text.scale((float) window.width() / Math.max(1, window.pixelWidth()));
            }
        }
        input.beginFrame();
        commands.poll(input);
        if (mouse != null) {
            mouse.poll(input);
        }
        if (text != null) {
            text.poll(input);
        }
        for (PointerSource source : sources) {
            source.poll(input);
        }
        long nanos = (long) (frame.realTime() * 1e9);
        if (scene != null) {
            scene.far(settings.get(com.truesim.engine.ui.scene.UiScene.FAR));
            scene.input(input, nanos, view.width(), view.height());
        } else {
            ui.input(input, nanos);
        }
    }

    /**
     * After the game's update and late update: the UI's second phase (what the game changed shows in this frame) and
     * the submit of its layers; the mouse cursor follows the UI's.
     */
    @Override
    public void afterUpdate(Frame frame) {
        if (view == null) {
            return;
        }
        int width = view.width();
        int height = view.height();
        if (scene != null) {
            scene.update(width, height);
        } else {
            ui.update(width, height);
        }
        renderer.submit(ui.layers(), ui.layerCount(), ui.textures(), width, height);
        if (osWindows != null) {
            osWindows.submit(ui);
        }
        if (platform != null && mouse != null) {
            SystemCursor wanted = SYSTEM[ui.interaction().cursor(MousePointer.ID).ordinal()];
            if (wanted != shownCursor) {
                shownCursor = wanted;
                platform.setCursor(wanted);
            }
        }
    }

    @Override
    public void stop() {
        if (desktop != null && mouse != null) {
            desktop.unlisten(mouse);
        }
        if (osWindows != null) {
            desktop.unlisten(osWindows);
            osWindows.closeAll();
            osWindows = null;
        }
        if (commands != null) {
            commands.close();
            commands = null;
        }
        if (renderer != null) {
            renderer.close();
            renderer = null;
        }
    }

    /** The system cursor showing the UI's {@code cursor}. */
    static SystemCursor systemCursor(Cursor cursor) {
        return switch (cursor) {
            case AUTO, DEFAULT -> SystemCursor.DEFAULT;
            case POINTER -> SystemCursor.POINTER;
            case TEXT -> SystemCursor.TEXT;
            case MOVE, GRABBING -> SystemCursor.MOVE;
            case GRAB -> SystemCursor.POINTER;
            case NOT_ALLOWED -> SystemCursor.NOT_ALLOWED;
            case RESIZE_EW -> SystemCursor.RESIZE_EW;
            case RESIZE_NS -> SystemCursor.RESIZE_NS;
            case RESIZE_NESW -> SystemCursor.RESIZE_NESW;
            case RESIZE_NWSE -> SystemCursor.RESIZE_NWSE;
            case CROSSHAIR -> SystemCursor.CROSSHAIR;
            case WAIT -> SystemCursor.WAIT;
            case NONE -> SystemCursor.HIDDEN;
        };
    }
}
