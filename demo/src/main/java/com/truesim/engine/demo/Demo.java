package com.truesim.engine.demo;

import com.truesim.common.math.Maths;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_D32_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16B16A16_SFLOAT;

import com.truesim.engine.app.AppConfig;
import com.truesim.engine.app.Application;
import com.truesim.engine.app.Engine;
import com.truesim.engine.app.Frame;
import com.truesim.engine.app.SettingsFile;
import com.truesim.engine.app.StartupFailure;
import com.truesim.engine.app.TrueSim;
import com.truesim.engine.graph.DynamicResolution;
import com.truesim.engine.graph.FrameGraph;
import com.truesim.engine.graph.GpuTimings;
import com.truesim.engine.graph.PresentSettings;
import com.truesim.engine.graph.Presenter;
import com.truesim.engine.graph.Rendering;
import com.truesim.engine.graph.ResolutionSettings;
import com.truesim.engine.graph.Technique;
import com.truesim.engine.graph.ViewPort;
import com.truesim.engine.platform.Desktop;
import com.truesim.engine.platform.Key;

import com.truesim.engine.platform.MouseButton;
import com.truesim.engine.platform.Platform;
import com.truesim.engine.platform.PlatformListener;
import com.truesim.engine.platform.Window;
import com.truesim.engine.platform.WindowMode;
import com.truesim.engine.platform.WindowOptions;
import com.truesim.engine.platform.WindowSettings;
import com.truesim.engine.render.Camera;
import com.truesim.engine.render.ClusteredForwardShading;
import com.truesim.engine.render.ClusteredLights;
import com.truesim.engine.render.DisplayTransform;
import com.truesim.engine.render.Keys;
import com.truesim.engine.render.MeshletRenderer;
import com.truesim.engine.render.RenderSettings;
import com.truesim.engine.render.SceneView;
import com.truesim.engine.render.ViewOptions;
import com.truesim.engine.render.VisibilityBufferShading;
import com.truesim.engine.render.shadows.CascadedShadowMaps;
import com.truesim.engine.settings.EngineSettings;
import com.truesim.engine.vulkan.PresentMode;
import com.truesim.engine.xr.Eye;
import com.truesim.engine.xr.Hand;
import com.truesim.engine.xr.Headset;
import com.truesim.engine.xr.XrAxis;
import com.truesim.engine.xr.XrButton;
import com.truesim.engine.xr.XrInput;
import com.truesim.engine.xr.XrListener;
import com.truesim.engine.xr.Xr;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The engine's demo: rolling terrain with rocks, the sun with cascaded shadows and colored lights, to try what the
 * engine does live. Run with {@code ./gradlew :engine-demo:run}; {@code --args="--vr"} adds a headset (OpenXR, e.g.
 * SteamVR), {@code --args="--validation"} turns Vulkan validation on. Settings are saved to
 * {@code ~/.truesim/demo.properties} on exit (and with F5) and read back at start. The keys are printed at start.
 *
 * <p>An {@link Application} on the engine's plugins: {@link Desktop} for windows and input, {@link Rendering} for the
 * device, graph and presenter, and with {@code --vr} {@link Xr} if a headset is there, which then paces frames.
 */
public final class Demo implements Application, PlatformListener {

    private static final int COLOR = VK_FORMAT_R16G16B16A16_SFLOAT;
    private static final int DEPTH = VK_FORMAT_D32_SFLOAT;
    private static final ViewOptions CAMERA = ViewOptions.of(1 << 14, 1 << 22).withOcclusion(true);
    private static final ViewOptions CASCADES = ViewOptions.shadow(1 << 16, 1 << 22);
    private static final String HELP = """
        Keys:
          W A S D, Q E      move (Shift: faster); hold the right mouse button to look around
          1 / 2             forward shading / visibility buffer shading
          H                 HDR on or off (where the display offers it)
          T                 next tone curve; [ and ] exposure
          V                 next present mode (vsync, relaxed, mailbox, immediate)
          F or F11          windowed, borderless fullscreen, exclusive fullscreen
          R                 dynamic resolution on or off
          - and =           render scale down or up (a rebuild at a fixed resolution, none when dynamic)
          G                 dynamic resolution following the GPU time on or off; , and . its target in ms
          O                 occlusion culling on or off
          N                 a second window, looking down on the scene
          P                 VR: the desktop window shows the left eye or its own camera
          F5                save the settings now; Escape quits
        """;

    private final boolean vr;
    private final boolean validation;
    private final double seconds; // quits by itself after this long (a smoke test); 0: runs until closed
    private final String[] args;
    private final Path settingsFile = Path.of(System.getProperty("user.home"), ".truesim", "demo.properties");
    private final boolean[] held = new boolean[Key.values().length];
    private Engine engine;
    private EngineSettings settings;
    private boolean looking;
    private long started;
    private long titled;
    private int titledFrames;
    private double yaw = -2.4;
    private double pitch = -0.25;
    private final double[] eye = {40, 14, 40};

    private Platform platform;
    private Window window;
    private FrameGraph graph;
    private Presenter presenter;
    private DemoScene world;
    private ViewPort main;
    private SceneView mainView;
    private final Camera camera = new Camera();
    private final List<SceneView> shaded = new ArrayList<>(); // every view drawing the scene: shading swaps in all
    private final List<CascadedShadowMaps> cascades = new ArrayList<>(); // fitted to their cameras every frame
    private boolean visibility;
    private Presenter.Target mainTarget;
    private DynamicResolution automatic;
    private float scale = 1; // the dynamic scale asked for by hand
    // The second window and its view, from above.
    private Window mapWindow;
    private ViewPort map;
    private final Camera mapCamera = new Camera();
    // VR.
    private Headset headset;
    private final ViewPort[] eyes = new ViewPort[2];
    private boolean spectating;
    private final boolean[] triggers = new boolean[2];
    private final double[] origin = new double[3]; // the tracking space's, in the world

    private Demo(String[] args, boolean vr, boolean validation, double seconds) {
        this.args = args;
        this.vr = vr;
        this.validation = validation;
        this.seconds = seconds;
    }

    public static void main(String[] args) {
        List<String> options = List.of(args);
        double seconds = 0;
        for (String option : options) {
            if (option.startsWith("--seconds=")) {
                seconds = Double.parseDouble(option.substring("--seconds=".length()));
            }
        }
        TrueSim.run(new Demo(args, options.contains("--vr"), options.contains("--validation"), seconds), args);
    }

    @Override
    public void configure(AppConfig app) {
        app.id("truesim-demo").name("true-sim demo").saveSettings(settingsFile)
            .use(new Desktop(), new Rendering().validation(validation));
        if (vr) {
            app.use(Xr.ifAvailable());
        }
    }

    @Override
    public StartupFailure.Response onStartupFailure(StartupFailure failure) {
        if (!failure.required()) {
            System.out.println(failure.reason() + ": the desktop only");
        }
        return Application.super.onStartupFailure(failure);
    }

    @Override
    public void start(Engine started) {
        engine = started;
        settings = engine.settings();
        Desktop desktop = engine.get(Desktop.KEY);
        platform = desktop.platform();
        desktop.listen(this);
        Rendering rendering = engine.get(Rendering.KEY);
        graph = rendering.graph().timings(true);
        presenter = rendering.presenter().outputTransform(new DisplayTransform());
        world = new DemoScene(rendering.device(), graph.frameSlots(), COLOR, DEPTH);
        System.out.println("GPU: " + rendering.device().gpu().name() + (validation ? ", validation on" : ""));
        window = platform.createWindow(WindowOptions.of("true-sim demo", 1600, 900), settings.scoped("main"));
        main = graph.addView("main");
        mainView = sceneView(main, camera);
        if (List.of(args).contains("--probe")) {
            main.add(new Probe());
        }
        mainTarget = presenter.present(main, Keys.SCENE_COLOR, window);
        headset = engine.find(Xr.KEY).map(Xr::headset).orElse(null);
        if (headset != null) {
            System.out.println("Headset: " + headset);
            startHeadset();
        }
        System.out.println(HELP);
        this.started = System.nanoTime();
        titled = this.started;
    }

    @Override
    public void update(Frame frame) {
        long now = System.nanoTime();
        if (seconds > 0 && now - started >= seconds * 1e9) {
            engine.quit();
        }
        double dt = Math.min(0.1, frame.delta());
        move(dt);
        world.update((now - started) / 1e9);
        for (CascadedShadowMaps sun : cascades) {
            sun.update();
        }
        if (automatic != null) {
            automatic.update();
        }
        if (headset != null) {
            xrInput(dt); // the headset's input and poses are this frame's: read when the frame began
        }
        titledFrames++;
        if (now - titled > 500_000_000L) {
            window.setTitle(status(titledFrames * 1e9 / (now - titled)));
            titled = now;
            titledFrames = 0;
        }
    }

    @Override
    public void stop() {
        Rendering rendering = engine.get(Rendering.KEY);
        rendering.device().waitIdle();
        if (world != null) {
            world.close();
        }
        if (validation) {
            System.out.println("Validation errors: " + rendering.instance().validationErrors());
        }
    }

    /** A view of the scene through {@code through} on {@code port}: shading, lights and the sun's shadows. */
    private SceneView sceneView(ViewPort port, Camera through) {
        SceneView view = world.geometry().createView(port, through, CAMERA).set(1, MeshletRenderer.MODE_LIT);
        CascadedShadowMaps sun = new CascadedShadowMaps(world.geometry(), world.lights(), 0, 0, view, 3, 2048,
            CASCADES, 150);
        cascades.add(sun);
        port.add(shading(view)).add(new ClusteredLights(world.lights(), view)).add(sun);
        shaded.add(view);
        return view;
    }

    /** A view of the scene for an eye: shading and lights, with the sun's cascades both eyes share. */
    private SceneView eyeView(ViewPort port, Camera through, Technique sun) {
        SceneView view = world.geometry().createView(port, through, CAMERA).set(1, MeshletRenderer.MODE_LIT);
        port.add(shading(view)).add(new ClusteredLights(world.lights(), view)).add(sun);
        shaded.add(view);
        return view;
    }

    private Technique shading(SceneView view) {
        return visibility
            ? new VisibilityBufferShading(world.geometry(), view, COLOR, DEPTH).clearColor(0.4f, 0.55f, 0.8f)
            : new ClusteredForwardShading(world.geometry(), view, COLOR, DEPTH).clearColor(0.4f, 0.55f, 0.8f);
    }

    private void startHeadset() {
        origin[1] = DemoMeshes.height(0, 0);
        headset.origin(origin[0], origin[1], origin[2]); // its session started with the graph, by the plugin
        // One set of sun cascades for both eyes, fitted to the left one: the margin covers the other eye and how far the
        // head moves in a frame (the eyes are located just before the frame records).
        CascadedShadowMaps sun = new CascadedShadowMaps(world.geometry(), world.lights(), 0, 0,
            headset.camera(Eye.LEFT), 3, 2048, CASCADES, 150).margin(0.25f);
        cascades.add(sun);
        for (Eye e : Eye.values()) {
            ViewPort port = graph.addView(e.name().toLowerCase(Locale.ROOT) + " eye");
            eyeView(port, headset.camera(e), e == Eye.LEFT ? sun : sun.share());
            headset.present(e, port, Keys.SCENE_COLOR);
            eyes[e.ordinal()] = port;
        }
        System.out.println("Eyes: " + headset.recommendedWidth() + " x " + headset.recommendedHeight()
            + "; P shows the left eye on the desktop");
    }

    /** The free camera: moved by the keys held, looking where the mouse turned it; the map's above it. */
    private void move(double dt) {
        double speed = (held[Key.LEFT_SHIFT.ordinal()] || held[Key.RIGHT_SHIFT.ordinal()] ? 40 : 8) * dt;
        double fx = Maths.cos(pitch) * Maths.sin(yaw);
        double fy = Maths.sin(pitch);
        double fz = Maths.cos(pitch) * Maths.cos(yaw);
        double rx = Maths.cos(yaw);
        double rz = -Maths.sin(yaw);
        double forward = (held[Key.W.ordinal()] ? 1 : 0) - (held[Key.S.ordinal()] ? 1 : 0);
        double right = (held[Key.D.ordinal()] ? 1 : 0) - (held[Key.A.ordinal()] ? 1 : 0);
        double up = (held[Key.E.ordinal()] ? 1 : 0) - (held[Key.Q.ordinal()] ? 1 : 0);
        eye[0] += speed * (forward * fx - right * rx);
        eye[1] += speed * (forward * fy + up);
        eye[2] += speed * (forward * fz - right * rz);
        eye[1] = Math.max(eye[1], DemoMeshes.height(eye[0], eye[2]) + 0.5);
        int width = Math.max(1, window.pixelWidth());
        int height = Math.max(1, window.pixelHeight());
        camera.position(eye[0], eye[1], eye[2]).set(fx, fy, fz, 0, 1, 0, (70 * Math.PI / 180), width, height, 0.1);
        if (map != null) {
            mapCamera.position(eye[0], eye[1] + 60, eye[2]).set(0, -1, 0, Maths.sin(yaw), 0, Maths.cos(yaw),
                (60 * Math.PI / 180), Math.max(1, mapWindow.pixelWidth()), Math.max(1, mapWindow.pixelHeight()), 1);
        }
    }

    /** Walks with the left thumbstick, buzzes a controller when its trigger goes down, prints profile changes. */
    private void xrInput(double dt) {
        if (!headset.running()) {
            return;
        }
        XrInput input = headset.input();
        input.poll(new XrListener() {
            @Override
            public void profile(Hand hand, String profile) {
                System.out.println("VR " + hand + ": " + (profile.isEmpty() ? "nothing" : profile));
            }
        });
        double[] head = new double[3];
        float[] orientation = new float[4];
        if (input.head(head, orientation)) {
            // The head's yaw from its orientation: walking goes where the head looks.
            float x = orientation[0];
            float y = orientation[1];
            float z = orientation[2];
            float w = orientation[3];
            double fx = -2 * (x * z + w * y);
            double fz = -(1 - 2 * (x * x + y * y));
            double length = Math.max(1e-6, Maths.hypot(fx, fz));
            double ahead = input.axis(Hand.LEFT, XrAxis.THUMBSTICK_Y) * 3 * dt;
            double side = input.axis(Hand.LEFT, XrAxis.THUMBSTICK_X) * 3 * dt;
            origin[0] += ahead * fx / length - side * fz / length;
            origin[2] += ahead * fz / length + side * fx / length;
            origin[1] = DemoMeshes.height(origin[0], origin[2]);
            headset.origin(origin[0], origin[1], origin[2]);
        }
        for (Hand hand : Hand.values()) {
            boolean down = input.pressed(hand, XrButton.TRIGGER);
            if (down && !triggers[hand.ordinal()]) {
                input.vibrate(hand, 0.6f, 0, 80_000_000L);
            }
            triggers[hand.ordinal()] = down;
        }
    }

    private String status(double fps) {
        GpuTimings timings = graph.timings();
        double gpu = timings == null ? Double.NaN : timings.totalMillis();
        return String.format(Locale.ROOT, "true-sim demo  |  %.0f fps, GPU %.2f ms  |  %s  |  render %dx%d of %dx%d, "
                + "scale %.2f%s  |  %s, %s, %s%s",
            fps, gpu, visibility ? "visibility buffer" : "forward", main.regionWidth(), main.regionHeight(),
            main.outputWidth(), main.outputHeight(), main.renderScale(),
            main.dynamic() ? (automatic != null ? " dynamic, GPU-timed" : " dynamic") : "",
            mainTarget.encoding(), mainTarget.settings().presentMode(), settings.get(DisplayTransform.CURVE),
            headset == null ? "" : "  |  headset frames " + headset.frames());
    }

    // ---------------------------------------------------------------- events

    @Override
    public void windowCloseRequested(int id) {
        if (mapWindow != null && id == mapWindow.id()) {
            closeMap();
        } else {
            engine.requestQuit(QuitReason.WINDOW_CLOSED, false);
        }
    }

    @Override
    public void mouseButton(long timestamp, int id, int mouse, MouseButton button, boolean down, int clicks, float x,
        float y) {
        if (button == MouseButton.RIGHT && id == window.id()) {
            looking = down;
            window.setRelativeMouse(down);
        }
    }

    @Override
    public void mouseMoved(long timestamp, int id, int mouse, float x, float y, float dx, float dy, int buttons) {
        if (looking) {
            yaw -= dx * 0.003;
            pitch = Math.max(-1.5, Math.min(1.5, pitch - dy * 0.003));
        }
    }

    @Override
    public void key(long timestamp, int id, int keyboard, Key key, int character, int modifiers, boolean down,
        boolean repeat) {
        held[key.ordinal()] = down;
        if (!down || repeat && key != Key.MINUS && key != Key.EQUALS) {
            return;
        }
        switch (key) {
            case ESCAPE -> engine.quit();
            case DIGIT_1 -> shadeWith(false);
            case DIGIT_2 -> shadeWith(true);
            case H -> say("HDR", toggle(PresentSettings.HDR));
            case T -> say("tone curve", next(DisplayTransform.CURVE, DisplayTransform.Curve.values()));
            case LEFT_BRACKET -> say("exposure", scaleBy(DisplayTransform.EXPOSURE, 1 / 1.25f));
            case RIGHT_BRACKET -> say("exposure", scaleBy(DisplayTransform.EXPOSURE, 1.25f));
            case V -> say("present mode", next(PresentSettings.PRESENT_MODE, PresentMode.values()));
            case F, F11 -> say("window mode", next(settings.scoped("main"), WindowSettings.MODE, WindowMode.values()));
            case R -> say("dynamic resolution", toggle(ResolutionSettings.DYNAMIC));
            case MINUS -> renderScale(-0.05f);
            case EQUALS -> renderScale(0.05f);
            case G -> automatic();
            case COMMA -> say("GPU target ms", add(ResolutionSettings.TARGET_MILLIS, -1));
            case PERIOD -> say("GPU target ms", add(ResolutionSettings.TARGET_MILLIS, 1));
            case O -> say("occlusion culling", toggle(RenderSettings.OCCLUSION));
            case N -> toggleMap();
            case P -> spectate();
            case F5 -> save();
            default -> {
            }
        }
    }

    // ---------------------------------------------------------------- actions

    private void shadeWith(boolean visibilityBuffer) {
        if (visibilityBuffer && !graph.device().primitiveIds()) {
            System.out.println("The visibility buffer needs primitive IDs, which this GPU does not have");
            return;
        }
        visibility = visibilityBuffer;
        for (SceneView view : shaded) {
            view.port().replace(shading(view));
        }
        say("shading", visibility ? "visibility buffer" : "forward");
    }

    private void renderScale(float step) {
        if (main.dynamic()) {
            if (automatic != null) {
                System.out.println("The GPU time chooses the scale: G turns that off");
                return;
            }
            scale = Math.max(0.25f, Math.min(2, scale + step));
            for (ViewPort port : views()) {
                port.renderScale(scale);
            }
            say("render scale (dynamic, no rebuild)", String.format(Locale.ROOT, "%.2f", scale));
        } else {
            say("render scale (fixed, rebuilds)", String.format(Locale.ROOT, "%.2f",
                add(ResolutionSettings.SCALE, step)));
        }
    }

    private void automatic() {
        if (automatic == null) {
            settings.set(ResolutionSettings.DYNAMIC, true);
            automatic = new DynamicResolution(graph, views().toArray(new ViewPort[0]));
            say("dynamic resolution following the GPU time, target ms", settings.get(ResolutionSettings.TARGET_MILLIS));
        } else {
            automatic = null;
            say("dynamic resolution following the GPU time", false);
        }
    }

    /** The views rendering the scene for a display: the desktop's and the eyes'. */
    private List<ViewPort> views() {
        List<ViewPort> views = new ArrayList<>(List.of(main));
        for (ViewPort e : eyes) {
            if (e != null) {
                views.add(e);
            }
        }
        return views;
    }

    private void toggleMap() {
        if (mapWindow != null) {
            closeMap();
            return;
        }
        mapWindow = platform.createWindow(WindowOptions.of("true-sim demo: map", 640, 480), settings.scoped("map"));
        if (map == null) {
            map = graph.addView("map");
            sceneView(map, mapCamera);
        }
        map.enabled(true);
        presenter.present(map, Keys.SCENE_COLOR, mapWindow, settings.scoped("map"));
        say("second window", true);
    }

    private void closeMap() {
        mapWindow.close(); // the presenter drops it
        mapWindow = null;
        map.enabled(false);
        say("second window", false);
    }

    /** VR: the desktop window shows the left eye (for people watching) or the free camera. */
    private void spectate() {
        if (headset == null) {
            System.out.println("No headset: start with --vr");
            return;
        }
        spectating = !spectating;
        mainTarget.close();
        ViewPort shown = spectating ? eyes[Eye.LEFT.ordinal()] : main;
        main.enabled(!spectating);
        mainTarget = presenter.present(shown, Keys.SCENE_COLOR, window);
        say("desktop shows", spectating ? "the left eye" : "its own camera");
    }

    // ---------------------------------------------------------------- settings

    private boolean toggle(com.truesim.engine.settings.Setting<Boolean> setting) {
        boolean on = !settings.get(setting);
        settings.set(setting, on);
        return on;
    }

    private <E extends Enum<E>> E next(com.truesim.engine.settings.Setting<E> setting, E[] values) {
        return next(settings, setting, values);
    }

    private static <E extends Enum<E>> E next(EngineSettings in, com.truesim.engine.settings.Setting<E> setting,
        E[] values) {
        E value = values[(in.get(setting).ordinal() + 1) % values.length];
        in.set(setting, value);
        return value;
    }

    private float scaleBy(com.truesim.engine.settings.Setting<Float> setting, float factor) {
        return add(setting, settings.get(setting) * (factor - 1));
    }

    private float add(com.truesim.engine.settings.Setting<Float> setting, float step) {
        float value = settings.get(setting) + step;
        try {
            settings.set(setting, value);
        } catch (IllegalArgumentException e) {
            System.out.println(settings.nameOf(setting) + " stays at " + settings.get(setting) + ": " + e.getMessage());
        }
        return settings.get(setting);
    }

    private static void say(String what, Object value) {
        System.out.println(what + ": " + value);
    }

    private void save() {
        try {
            SettingsFile.save(settingsFile, settings.toMap(), "true-sim demo settings");
            System.out.println("Settings saved to " + settingsFile);
        } catch (java.io.UncheckedIOException e) {
            System.out.println("Could not save " + settingsFile + ": " + e.getMessage());
        }
    }
}
