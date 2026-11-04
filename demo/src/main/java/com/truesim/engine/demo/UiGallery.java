package com.truesim.engine.demo;

import com.truesim.common.math.Maths;
import com.truesim.engine.app.AppConfig;
import com.truesim.engine.app.Application;
import com.truesim.engine.app.Engine;
import com.truesim.engine.app.Frame;
import com.truesim.engine.app.TrueSim;
import com.truesim.engine.app.profile.ProfilerSettings;
import com.truesim.engine.graph.Profiling;
import com.truesim.engine.graph.Rendering;
import com.truesim.engine.input.Input;
import com.truesim.engine.input.Player;
import com.truesim.engine.interaction.SceneInteraction;
import com.truesim.engine.platform.Desktop;
import com.truesim.engine.render.Camera;
import com.truesim.engine.render.MeshletRenderer;
import com.truesim.engine.render.SceneView;
import com.truesim.engine.settings.Setting;
import com.truesim.engine.settings.SettingRegistry;
import com.truesim.engine.ui.UiPlugin;
import com.truesim.engine.ui.UiOsWindows;
import com.truesim.engine.ui.WorldPanelRenderer;
import com.truesim.engine.ui.console.UiConsole;
import com.truesim.engine.ui.scene.GpuCameraView;
import com.truesim.engine.ui.scene.UiScene;
import com.truesim.scene.render.RenderablePicking;
import com.truesim.ui.charts.ChartsGallery;
import com.truesim.ui.controls.Ui;
import com.truesim.ui.controls.basic.Button;
import com.truesim.ui.controls.basic.Controls;
import com.truesim.ui.controls.basic.ControlsGallery;
import com.truesim.ui.controls.data.DataGallery;
import com.truesim.ui.controls.files.FilesGallery;
import com.truesim.ui.controls.imm.ImmGallery;
import com.truesim.ui.controls.overlay.Overlays;
import com.truesim.ui.controls.text.TextGallery;
import com.truesim.ui.controls.windows.DockLayout;
import com.truesim.ui.controls.windows.DockPanel;
import com.truesim.ui.controls.windows.DockSpace;
import com.truesim.ui.controls.windows.FloatingWindow;
import com.truesim.ui.controls.windows.OsDockHost;
import com.truesim.ui.controls.windows.WindowManager;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.Layer;
import com.truesim.ui.core.UiStore;
import com.truesim.ui.core.draw.UiTextures;
import com.truesim.ui.core.event.EventType;
import com.truesim.ui.fonts.cjk.BundledCjkFonts;
import com.truesim.ui.fonts.emoji.BundledEmojiFonts;
import com.truesim.ui.gizmo.Gizmo;
import com.truesim.ui.gizmo.ViewCube;
import com.truesim.ui.layout.LayoutKind;
import com.truesim.ui.layout.Unit;
import com.truesim.ui.profiler.ProfilerGallery;
import com.truesim.ui.props.PropsGallery;
import com.truesim.ui.scene.CameraView;
import com.truesim.ui.scene.WorldPanel;
import com.truesim.ui.style.StyleSheet;
import com.truesim.ui.style.tss.SourceLoader;
import com.truesim.ui.style.tss.Tss;
import com.truesim.ui.text.AtlasSettings;
import com.truesim.ui.text.FontRegistry;
import com.truesim.ui.text.GlyphAtlas;
import com.truesim.ui.text.TextSystem;
import com.truesim.ui.text.natives.NativeTextBackend;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The UI gallery: every gallery of the UI toolkit (ui's test fixtures) live in a window, on the engine's UI plugin,
 * one page at a time (the bar at the bottom, or Ctrl+Page Up/Down): controls, overlays, text (spelling: right-click
 * the misspelt words), data, files, charts, property panels, the console, immediate mode, the UI debugger, windows and
 * docking (drag a tab out of the window: it opens an OS window of its own) and the profiler's views; and a 3D page,
 * a scene of rocks with the engine's scene interaction: click, Shift+click and Ctrl+click select, a drag from empty
 * ground draws a marquee, Ctrl+A and Escape select all and none; the selection and the hover are outlined; a gizmo
 * follows the selection (W, E, R, T, B: translate, rotate, scale, universal, bounds; X: local or world); the view cube
 * aligns and orbits the camera; a minimap and a world panel. The camera: right-drag orbits, middle-drag pans, the
 * wheel zooms, Home resets. The live profiler runs over the app's own frames on every page: F3 the frame graph overlay,
 * Shift+F3 the detailed profiler, F4 freeze, Shift+F4 step, Ctrl+F4 export. The backquote opens the console.
 *
 * <p>{@code ./gradlew :engine-demo:uiGallery} ({@code -Pseconds=n} quits after n seconds).
 */
public final class UiGallery implements Application {

    private static final String RULES = """
        panel.gallery-backdrop { background: box(#0d1117); }
        panel.gallery-nav { layout: flex; direction: row; wrap: wrap; gap: 4px; padding: 6px 8px; align-items: center;
          background: box(rgba(#0d1117, 0.94)); }
        panel.gallery-marquee { background: box(rgba(#3fa7ff, 0.12)); border: 1px #3fa7ff; }
        panel.gallery-page { layout: flex; direction: column; gap: 8px; padding: 16px; }
        label.gallery-hint { font-size: 13px; }
        """;
    private static final int NAV_HEIGHT = 44;

    private final double seconds;
    private final boolean tour;
    private long frames;
    private final GalleryWorld world = new GalleryWorld();
    private final UiPlugin uiPlugin = new UiPlugin(this::makeUi).view("main");
    private StyleSheet defaultSheet;
    private StyleSheet testSheet;
    private boolean testTheme;
    private TextSystem text;
    private Engine engine;
    private Ui ui;
    private Player player;
    private UiScene scene;
    private SceneInteraction interaction;
    private UiConsole console;
    private Gizmo gizmo;
    private SceneView minimapView;
    private Layer navLayer;
    private Layer backdropLayer;
    private Layer sceneLayer;
    private Element nav;
    private Element backdrop;
    private Element marquee;
    private final float[] rect = new float[4];
    private final List<Page> pages = new ArrayList<>();
    private int current = -1;
    private ImmGallery imm;
    private long started;
    // the orbit camera
    private double targetX;
    private double targetY;
    private double targetZ;
    private double yaw;
    private double pitch;
    private double distance;

    /** A page: what it added to the UI's layers (detached while another shows), and what it does when shown. */
    private static final class Page {
        final String name;
        final List<Element> parents = new ArrayList<>();
        final List<Element> children = new ArrayList<>();
        Runnable shown = () -> { };
        Runnable hidden = () -> { };
        Button button;

        Page(String name) {
            this.name = name;
        }
    }

    private UiGallery(double seconds, boolean tour) {
        this.seconds = seconds;
        this.tour = tour;
    }

    /** Runs the gallery; {@code --seconds=n} quits after n seconds, {@code --tour} visits every page. */
    public static void main(String[] args) {
        double seconds = 0;
        for (String a : args) {
            if (a.startsWith("--seconds=")) {
                seconds = Double.parseDouble(a.substring("--seconds=".length()));
            }
        }
        TrueSim.run(new UiGallery(seconds, java.util.List.of(args).contains("--tour")), args);
    }

    @Override
    public void configure(AppConfig app) {
        app.id("truesim-ui-gallery").name("true-sim UI gallery").use(new Desktop(),
            new Input().add(GalleryActions.GALLERY), new Rendering(), new Profiling(), new SceneInteraction(), world,
            uiPlugin);
    }

    /** The UI: the default theme and the gallery's few rules, the bundled fonts and colour emoji. */
    private Ui makeUi(UiTextures textures) {
        UiTextures t = textures != null ? textures : new UiTextures();
        defaultSheet = sheet("default-theme");
        testSheet = sheet("test-theme");
        text = text(t);
        return new Ui(defaultSheet, text, t);
    }

    private static StyleSheet sheet(String theme) {
        SourceLoader themes = SourceLoader.classpath(Tss.class.getClassLoader(), Tss.THEMES);
        String source = "@import \"" + theme + "\";\n" + RULES;
        SourceLoader loader = path -> path.equals("gallery.tss") ? source : themes.read(path);
        return Tss.compile(loader, "gallery.tss").sheet();
    }

    private static TextSystem text(UiTextures textures) {
        NativeTextBackend backend = new NativeTextBackend();
        FontRegistry fonts = BundledCjkFonts.registry(backend);
        BundledEmojiFonts.register(fonts);
        GlyphAtlas atlas = new GlyphAtlas(textures, backend.msdfGenerator(), backend.newRasterizer(), null, null,
            new AtlasSettings());
        return new TextSystem(fonts, backend.newShaper(), atlas);
    }

    @Override
    public void start(Engine started) {
        engine = started;
        ui = uiPlugin.ui();
        player = engine.get(Input.KEY).player(0);
        interaction = engine.get(SceneInteraction.KEY);
        engine.settings().set(ProfilerSettings.RUNTIME, true); // GCs, safepoints and compilations on the timeline
        engine.settings().set(ProfilerSettings.OS, true); // the GPU engines' and the cores' load
        uiPlugin.profiler();
        console = uiPlugin.console();
        backdropLayer = ui.layerStack().add("gallery.backdrop", -10);
        sceneLayer = ui.layerStack().add("gallery.scene", 10);
        navLayer = ui.layerStack().add("gallery.nav", 90);
        backdrop = ui.panel("gallery-backdrop");
        backdropLayer.root().add(backdrop);
        ui.on(backdrop, EventType.PRESS, e -> { }); // takes the pointer: the scene behind picks nothing
        sceneWorld();
        buildPages();
        nav = ui.panel("gallery-nav");
        navLayer.root().add(nav);
        Controls c = new Controls(ui);
        for (int i = 0; i < pages.size(); i++) {
            int index = i;
            Page p = pages.get(i);
            p.button = c.button(p.name).onClick(() -> show(index));
            nav.add(p.button.element());
        }
        nav.add(c.button("Theme").onClick(this::switchTheme).element());
        nav.add(c.button("Text -").onClick(() -> textScale(-0.125f)).element());
        nav.add(c.button("Text +").onClick(() -> textScale(0.125f)).element());
        nav.add(c.button("Console").onClick(() -> console.console().toggle()).element());
        nav.add(c.button("Profiler").onClick(() -> toggle(ProfilerSettings.OVERLAY)).element());
        nav.add(c.button("Detailed").onClick(() -> toggle(ProfilerSettings.VIEW)).element());
        show(0);
        this.started = System.nanoTime();
        System.out.println(HELP);
    }

    private static final String HELP = """
        true-sim UI gallery
          the bar at the bottom    pages, theme, text size, console, profiler
          Ctrl+PgUp / Ctrl+PgDn    the page before / the next page
          3D scene: click, Shift+click, Ctrl+click select; drag from the ground: marquee; Ctrl+A, Esc: all, none
                    W E R T B: gizmo translate, rotate, scale, universal, bounds; X: local/world space
                    right-drag orbits, middle-drag pans, the wheel zooms, Home resets; the view cube aligns
          F3 frame graph overlay, Shift+F3 detailed profiler, F4 freeze, Shift+F4 step, Ctrl+F4 export
          ` (backquote) the console
        """;

    // ---------------------------------------------------------------- the 3D page's world

    /** The scene bridge, the interaction and selection, the gizmo, the view cube, the minimap, the world panel. */
    private void sceneWorld() {
        scene = uiPlugin.scene(world.graph, world.index).camera(world.camera);
        scene.world().picker().resolver(new RenderablePicking(world.renderables, world.assets::get));
        interaction.selection().enabled(true);
        gizmo = new Gizmo(scene.picking(), scene.mainView(), null);
        scene.followSelection(gizmo);
        home();
        // the panels drawn into the 3D view, and one of them
        WorldPanelRenderer panels = new WorldPanelRenderer(world.frameGraph, world.camera);
        world.port.add(panels);
        scene.panels(panels);
        Ui panelUi = new Ui(defaultSheet, text);
        Controls pc = new Controls(panelUi);
        Element body = panelUi.panel("gallery-page");
        panelUi.root().add(body);
        body.add(panelUi.label("title", "A world panel"));
        body.add(panelUi.label(null, "It takes the mouse like the screen does."));
        body.add(pc.button("Select all").onClick(() -> interaction.selection().selectAll(scene.world())).element());
        body.add(pc.button("Select none").onClick(() -> interaction.selection().selectNone(scene.world())).element());
        WorldPanel panel = new WorldPanel(panelUi, 360, 200).pixelsPerMeter(60)
            .place(0, DemoMeshes.height(0, -8) + 3, -8, 0, 0, 0, 1, 1);
        scene.picking().addPanel(panel);
        panels.add(panel, WorldPanelRenderer.Mode.DIRECT);
        // the view cube, the minimap and the marquee, in a layer of their own over the page
        ViewCube cube = new ViewCube(ui, scene.mainView(), null).listener(new ViewCube.Listener() {
            @Override
            public void align(double qx, double qy, double qz, double qw, int dx, int dy, int dz) {
                double l = Math.sqrt(dx * dx + dy * dy + dz * dz);
                pitch = Maths.asin(Math.max(-0.99, Math.min(0.99, dy / l)));
                if (dx != 0 || dz != 0) {
                    yaw = Maths.atan2(dx, dz);
                }
            }

            @Override
            public void orbit(double byYaw, double byPitch) {
                yaw += byYaw;
                pitch = Math.max(-1.5, Math.min(1.5, pitch - byPitch));
            }
        });
        Camera top = new Camera();
        GpuCameraView minimap = new GpuCameraView(world.frameGraph, uiPlugin.renderer(), ui.textures(), "minimap",
            512, 512);
        double half = GalleryWorld.GROUND_SIZE / 2.0;
        minimap.onResize((w, h) -> top.position(0, 120, 0).setOrthographic(0, 0, 0, 0, -1, 0, 0, 0, -1,
            half * w / h, half, 300, w, h));
        minimapView = world.viewOn(minimap.view(), top);
        CameraView map = new CameraView(ui, minimap, scene.picking(), "camera-view");
        minimap.follow(top, map);
        marquee = ui.panel("gallery-marquee");
        Element root = sceneLayer.root();
        root.add(cube.element()).add(map.element()).add(marquee);
        Element hint = ui.label("gallery-hint", "Click, Shift+click, Ctrl+click, drag a marquee. W E R T B: gizmo. "
            + "Right-drag orbits, middle-drag pans, wheel zooms, Home resets.");
        root.add(hint);
        none(root);
        place(hint, 16, 12, 900, 24);
        Page page = new Page("3D scene");
        page.children.add(cube.element());
        page.children.add(map.element());
        page.children.add(marquee);
        page.children.add(hint);
        for (int i = 0; i < page.children.size(); i++) {
            page.parents.add(root);
        }
        page.shown = () -> {
            backdrop.detach();
            interaction.selection().enabled(true);
        };
        page.hidden = () -> {
            backdropLayer.root().add(backdrop);
            interaction.selection().enabled(false);
        };
        pages.add(page);
        sized = () -> {
            float w = uiPlugin.view().width();
            place(cube.element(), w - 136, 16, 120, 120);
            place(map.element(), w - 276, 150, 260, 200);
        };
    }

    private Runnable sized = () -> { };

    /** The camera's starting view: the middle of the field from the south, above. */
    private void home() {
        targetX = 0;
        targetZ = 0;
        targetY = DemoMeshes.height(0, 0);
        yaw = 0;
        pitch = 0.5;
        distance = 45;
    }

    // ---------------------------------------------------------------- the pages

    private void buildPages() {
        ControlsGallery.registerIcons(ui);
        FilesGallery.registerIcons(ui);
        page("Controls", () -> ControlsGallery.build(new Controls(ui), ui.root()));
        ControlsGallery.Gallery[] overlays = new ControlsGallery.Gallery[1];
        page("Overlays", () -> {
            Element bar = ui.panel("gallery-page");
            ui.root().add(bar);
            bar.add(new Controls(ui).button("Open every overlay").onClick(() -> overlays[0].openOverlays())
                .element());
            overlays[0] = ControlsGallery.build(new Controls(ui), ui.root());
        });
        page("Text", () -> {
            TextGallery g = TextGallery.live(ui);
            g.area.spelling(g.area.spelling(), new Controls(ui)); // right-click a misspelt word: suggestions
        });
        page("Data", () -> DataGallery.build(ui));
        page("Files", () -> FilesGallery.build(ui));
        page("Charts", () -> ChartsGallery.build(ui));
        page("Props", () -> PropsGallery.build(new Controls(ui), ui.root()));
        Page con = page("Console", () -> text("The developer console over the engine's settings and log: "
            + "` (backquote) opens and closes it. Try help, render.selection, profiler.overlay true."));
        con.shown = () -> console.console().open(true);
        con.hidden = () -> console.console().open(false);
        page("Imm", () -> imm = ImmGallery.live(ui));
        Page dbg = page("Debugger", () -> {
            text("The UI debugger over this UI: bounds, clips, the element tree; pick an element to inspect it.");
            ControlsGallery.build(new Controls(ui), ui.root());
        });
        dbg.shown = () -> console.debugger().show(true);
        dbg.hidden = () -> console.debugger().show(false);
        page("Windows", this::windowsPage);
        page("Profiler", () -> {
            text("The profiler's views over recorded data. The live profiler of this app: F3 (overlay), Shift+F3 "
                + "(detailed), F4 freeze, Shift+F4 step, Ctrl+F4 export.");
            ProfilerGallery.build(ui);
        });
    }

    /** A label at the top of a page. */
    private void text(String s) {
        Element bar = ui.panel("gallery-page");
        ui.root().add(bar);
        bar.add(ui.label("gallery-hint", s));
    }

    /** Docking: an editor-like dock space, two floating windows; a tab dragged out of the window opens an OS one. */
    private void windowsPage() {
        Overlays overlays = new Overlays(ui);
        WindowManager windows = new WindowManager(overlays);
        DockSpace dock = new DockSpace(overlays, windows);
        Element root = dock.root();
        ui.root().add(root);
        ui.codeLayout(root, Ui.CODE_WIDTH | Ui.CODE_HEIGHT);
        ui.layout().width(root.slot(), 100, Unit.PERCENT).height(root.slot(), 92, Unit.PERCENT);
        for (String[] p : new String[][] {{"scene", "Scene"}, {"game", "Game"}, {"hierarchy", "Hierarchy"},
            {"inspector", "Inspector"}, {"console", "Console"}, {"profiler", "Profiler"}}) {
            DockPanel panel = dock.panel(p[0], p[1]);
            panel.content().add(ui.label(null, p[1])).add(ui.label("caption", "Drag my tab: onto another pane to "
                + "dock it, out of the window to tear it off into an OS window."));
        }
        dock.restore(DockLayout.parse("dock split(row; 0.2 0.55 0.25; tabs(0; hierarchy) split(column; 0.65 0.35; "
            + "tabs(0; scene game) tabs(0; console profiler)) tabs(0; inspector))"));
        FloatingWindow material = windows.create("material", "Material").bounds(960, 300, 280, 220);
        material.content().add(ui.label(null, "Roughness 0.4")).add(ui.label(null, "Metallic 1.0"));
        FloatingWindow stats = windows.create("stats", "Statistics").bounds(420, 380, 260, 160);
        stats.content().add(ui.label("value", "144 fps"));
        stats.open();
        material.open();
        UiOsWindows os = uiPlugin.osWindows();
        if (os != null) {
            os.host(new OsDockHost(overlays, dock, os));
        }
    }

    /** Builds a page by {@code build}, keeping what it added to the UI's layers, and hides it. */
    private Page page(String name, Runnable build) {
        Page page = new Page(name);
        UiStore store = ui.store();
        Set<Integer> before = new HashSet<>();
        int layers = ui.layerStack().count();
        for (int l = 0; l < layers; l++) {
            int root = ui.layerStack().at(l).rootSlot();
            for (int c = store.firstChild(root); c != UiStore.NONE; c = store.nextSibling(c)) {
                before.add(c);
            }
        }
        build.run();
        for (int l = 0; l < ui.layerStack().count(); l++) {
            Layer layer = ui.layerStack().at(l);
            if (layer == navLayer || layer == sceneLayer || layer == backdropLayer) {
                continue;
            }
            int root = layer.rootSlot();
            for (int c = store.firstChild(root); c != UiStore.NONE; c = store.nextSibling(c)) {
                if (!before.contains(c)) {
                    page.parents.add(layer.root());
                    page.children.add(store.element(c));
                }
            }
        }
        for (Element child : page.children) {
            child.detach();
        }
        pages.add(page);
        return page;
    }

    private void show(int index) {
        if (index == current) {
            return;
        }
        if (current >= 0) {
            Page old = pages.get(current);
            old.hidden.run();
            for (Element child : old.children) {
                child.detach();
            }
            old.button.element().style(null);
        }
        current = index;
        Page page = pages.get(index);
        for (int i = 0; i < page.children.size(); i++) {
            page.parents.get(i).add(page.children.get(i));
        }
        page.button.element().style("default");
        page.shown.run();
        if (index != 0) {
            interaction.selection().selectNone(scene.world());
        }
    }

    private void switchTheme() {
        testTheme = !testTheme;
        ui.styles().sheet(testTheme ? testSheet : defaultSheet);
    }

    @SuppressWarnings("unchecked")
    private void textScale(float step) {
        SettingRegistry.Entry entry = engine.registry().find("ui.textScale");
        if (entry != null) {
            Setting<Float> s = (Setting<Float>) entry.setting();
            engine.settings().set(s, Math.max(0.5f, Math.min(3, engine.settings().get(s) + step)));
        }
    }

    private void toggle(Setting<Boolean> setting) {
        engine.settings().set(setting, !engine.settings().get(setting));
    }

    // ---------------------------------------------------------------- frames

    @Override
    public void update(Frame frame) {
        if (seconds > 0 && System.nanoTime() - started > seconds * 1e9) {
            engine.quit();
        }
        frames++;
        if (tour && frames % 45 == 0) { // a smoke run: every page, the profiler's views open half way
            show((current + 1) % pages.size());
            engine.settings().set(ProfilerSettings.OVERLAY, frames > 45L * pages.size());
            engine.settings().set(ProfilerSettings.VIEW, frames > 90L * pages.size());
            if (current == 0) {
                interaction.selection().selectAll(scene.world());
            }
        }
        if (player.pressed(GalleryActions.NEXT_PAGE)) {
            show((current + 1) % pages.size());
        }
        if (player.pressed(GalleryActions.PREVIOUS_PAGE)) {
            show((current + pages.size() - 1) % pages.size());
        }
        float w = uiPlugin.view().width();
        float h = uiPlugin.view().height();
        none(navLayer.root());
        place(nav, 0, h - NAV_HEIGHT, w, NAV_HEIGHT);
        none(backdropLayer.root());
        place(backdrop, 0, 0, w, h);
        sized.run();
        if (current == 0) {
            orbit();
        }
        if (interaction.selection().marquee(rect)) {
            place(marquee, rect[0], rect[1], rect[2] - rect[0], rect[3] - rect[1]);
        } else {
            place(marquee, -10, -10, 0, 0);
        }
        if (imm != null && pages.get(current).name.equals("Imm")) {
            imm.tool(); // immediate mode: its calls each frame, between the UI's input and its update
        }
        camera((int) Math.max(1, w), (int) Math.max(1, h));
    }

    /** The orbit camera's input: right-drag orbits, middle-drag pans, the wheel zooms, Home resets. */
    private void orbit() {
        if (player.pressed(GalleryActions.HOME)) {
            home();
        }
        yaw -= player.x(GalleryActions.ORBIT);
        pitch = Math.max(-0.2, Math.min(1.5, pitch + player.y(GalleryActions.ORBIT)));
        float px = player.x(GalleryActions.PAN);
        float py = player.y(GalleryActions.PAN);
        if (px != 0 || py != 0) {
            double k = distance * 0.0015;
            targetX -= (Maths.cos(yaw) * px - Maths.sin(yaw) * Maths.sin(pitch) * py) * k;
            targetZ -= (-Maths.sin(yaw) * px - Maths.cos(yaw) * Maths.sin(pitch) * py) * k;
            targetY += Maths.cos(pitch) * py * k;
        }
        float wheel = player.value(GalleryActions.ZOOM);
        if (wheel != 0) {
            distance = Math.max(3, Math.min(400, distance * Maths.pow(0.88, wheel)));
        }
    }

    private void camera(int width, int height) {
        double cp = Maths.cos(pitch);
        double ex = targetX + distance * cp * Maths.sin(yaw);
        double ey = targetY + distance * Maths.sin(pitch);
        double ez = targetZ + distance * cp * Maths.cos(yaw);
        world.camera.position(ex, ey, ez).set(targetX - ex, targetY - ey, targetZ - ez, 0, 1, 0, Maths.toRadians(55),
            width, height, 0.1);
    }

    @Override
    public void lateUpdate(Frame frame) {
        world.sync();
        minimapView.set(1, MeshletRenderer.MODE_LIT);
    }

    @Override
    public void stop() {
        engine.get(Rendering.KEY).device().waitIdle();
        world.close();
        System.out.printf("%d frames in %.1f s%n", frames, (System.nanoTime() - started) / 1e9);
    }

    // ---------------------------------------------------------------- placement

    /** Children of {@code parent} placed by code ({@link #place}). */
    private void none(Element parent) {
        ui.codeLayout(parent, ui.codeLayout(parent) | Ui.CODE_KIND);
        ui.layout().kind(parent.slot(), LayoutKind.NONE);
    }

    private void place(Element e, float x, float y, float w, float h) {
        ui.codeLayout(e, Ui.CODE_PLACEMENT | Ui.CODE_WIDTH | Ui.CODE_HEIGHT);
        int slot = e.slot();
        ui.layout().anchors(slot, 0, 0, 0, 0).offsets(slot, x, y, 0, 0).width(slot, w, Unit.PX).height(slot, h,
            Unit.PX);
    }
}
