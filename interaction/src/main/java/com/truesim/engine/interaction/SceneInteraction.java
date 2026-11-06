package com.truesim.engine.interaction;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.app.Frame;
import com.truesim.engine.app.Plugin;
import com.truesim.engine.app.PluginKey;
import com.truesim.engine.app.Stage;
import com.truesim.engine.app.Startup;
import com.truesim.engine.graph.Presenter;
import com.truesim.engine.graph.Rendering;
import com.truesim.engine.input.Input;
import com.truesim.engine.platform.Desktop;
import com.truesim.engine.platform.Window;
import com.truesim.engine.settings.EngineSettings;
import com.truesim.engine.settings.Setting;
import com.truesim.engine.settings.SettingGroup;
import com.truesim.scene.core.NodeEvent;
import com.truesim.scene.core.NodeHandle;
import com.truesim.scene.core.PickHits;
import com.truesim.scene.core.RoutedListener;
import com.truesim.scene.core.SceneEventType;
import com.truesim.scene.core.SceneGraph;
import com.truesim.scene.core.ScenePicker;
import com.truesim.scene.core.SpatialIndex;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Scene interaction: pointers on scene nodes, as UI elements get them (application-api.md, picking and selection).
 * Each frame, at its start (after input is sampled, before the game's update), every pointer (the mouse, fingers, a
 * pen, XR controllers' rays, a gamepad's virtual cursor, a test's) is picked once, through the camera of the view it is
 * over as the frame starts (no frame of delay), by the world's {@link ScenePicker} ({@code Pickable} components and
 * their narrow phases: boxes, meshes' triangle BVHs, skinned meshes posed on the CPU, a game's own tests); then the
 * frame's events are routed to the nodes' {@link SceneListener}s:
 *
 * <ul>
 *   <li><b>Hover</b>: enter and exit for each node a pointer comes over or leaves (the hit node and its ancestors),
 *       moves; the pickable object under a pointer is marked {@code Hovered}.</li>
 *   <li><b>Buttons</b>: press (passing along the ray until consumed), release (to the node that captured the pointer),
 *       click with counts (double clicks), long press (consumed, it suppresses the click; unconsumed on a finger, a
 *       context event), context (the secondary button), wheel (passing along the ray).</li>
 *   <li><b>Drag and drop</b>: drag start past the threshold, drag, drag end; a drag started with a payload
 *       ({@link SceneEvent#startDrag}) is offered to the nodes it passes over (drop over, passing along the ray until
 *       accepted), with drop enter, leave and drop on the one that accepted it.</li>
 *   <li><b>Focus</b>: a primary press focuses its node (focus out, focus in; no pointer).</li>
 *   <li><b>Tooltips</b>: a pointer resting on a node asks for its tooltip.</li>
 *   <li><b>Routing</b>: capture (root to target), target, bubble (target to root) along the scene graph;
 *       {@link SceneEvent#consume} stops it and its pass-through; a press captures its pointer until released
 *       ({@link SceneEvent#capturePointer} for any node). One route for every listener: at each node its own
 *       listeners, then its front end's ({@link RoutedListener}: the UI's {@code Interactive} ones), so a
 *       consume on either side stops both.</li>
 *   <li><b>Queries</b>: {@link InteractionWorld#hovered}, {@link InteractionWorld#pressed}, each pointer's hits, hit
 *       point, distance and part ({@link ScenePointer}).</li>
 * </ul>
 *
 * <pre>{@code
 * SceneInteraction interaction = engine.get(SceneInteraction.KEY);
 * InteractionWorld world = interaction.world(scene, index);
 * world.view(camera).port(rendering.graph().views().get(0));
 * world.on(door, SceneEventType.CLICK, e -> open(e.currentNode()));
 * interaction.selection().enabled(true);           // clicks and marquees select, drawn by the selection outline
 * }</pre>
 *
 * <p>Pointer buttons and the selection's modifiers are input actions ({@link SceneActions}), rebound and saved like any
 * action; positions come from the platform's events (fingers and pens are pointers of their own). With the UI plugin,
 * the UI runs first and hands its pointers on ({@link #driven}): those over screen UI, world panels or gizmo handles are
 * blocked for the scene, and both pick the same rays once (the picker keeps a frame's picks). Its tunables are
 * settings ({@link #ALL}, group {@code scene}). Nothing is allocated per frame. Used on the main thread.
 */
public final class SceneInteraction implements Plugin {

    /** Its key. */
    public static final PluginKey<SceneInteraction> KEY = PluginKey.of("sceneInteraction", SceneInteraction.class);

    /** Seconds a press is held still before it is a long press. */
    public static final Setting<Float> LONG_PRESS_DELAY = Setting.decimal("scene.interaction.longPressDelay", 0.5f,
        0.1f, 5, "seconds a press on a scene node is held still before it counts as a long press");
    /** Seconds between the clicks of a double click. */
    public static final Setting<Float> DOUBLE_CLICK_TIME = Setting.decimal("scene.interaction.doubleClickTime", 0.4f,
        0.1f, 2, "seconds between the clicks of a double click on a scene node");
    /** Pixels the pointer may move between the clicks of a double click. */
    public static final Setting<Float> DOUBLE_CLICK_DISTANCE = Setting.decimal(
        "scene.interaction.doubleClickDistance", 6, 0, 64, "pixels a pointer may move between a double click's clicks");
    /** Pixels a pressed mouse or pen moves before it drags. */
    public static final Setting<Float> DRAG_THRESHOLD = Setting.decimal("scene.interaction.dragThreshold", 5, 0, 64,
        "pixels a pressed mouse or pen moves before it drags a scene node");
    /** Pixels a finger moves before it drags. */
    public static final Setting<Float> TOUCH_DRAG_THRESHOLD = Setting.decimal("scene.interaction.touchDragThreshold",
        12, 0, 128, "pixels a finger moves before it drags a scene node");
    /** How deep along a pointer's ray picking goes (pass-through). */
    public static final Setting<Integer> HITS = Setting.integer("scene.interaction.hits", 4, 1, ScenePicker.MAX_HITS,
        "how many scene nodes along a pointer's ray take part in picking (pass-through goes this deep)");
    /** Metres an XR ray reaches. */
    public static final Setting<Float> RAY_LENGTH = Setting.decimal("scene.interaction.rayLength", 1000, 1, 1e6f,
        "metres an XR controller's ray reaches into the scene");
    /** Metres a screen pointer's ray reaches. */
    public static final Setting<Float> FAR = Setting.decimal("scene.interaction.far", 100_000, 1, 1e9f,
        "metres a screen pointer's ray reaches into the scene");
    /** Degrees an XR ray turns from where it pressed before it drags. */
    public static final Setting<Float> RAY_DRAG_ANGLE = Setting.decimal("scene.interaction.rayDragAngle", 2, 0, 45,
        "degrees a pressed XR controller's ray turns before it drags a scene node");
    /** Pixels a second the virtual cursor moves at full tilt. */
    public static final Setting<Float> VIRTUAL_CURSOR_SPEED = Setting.decimal("scene.interaction.virtualCursorSpeed",
        1000, 50, 10_000, "pixels a second the gamepad's cursor over the scene moves at full tilt");
    /** Seconds a pointer rests on a node before its tooltip event. */
    public static final Setting<Float> TOOLTIP_DELAY = Setting.decimal("scene.interaction.tooltipDelay", 0.6f, 0, 10,
        "seconds a pointer rests on a scene node before it asks for its tooltip");
    /** Whether a gamepad drives a virtual cursor over the scene. */
    public static final Setting<Boolean> VIRTUAL_CURSOR = Setting.bool("scene.interaction.virtualCursor", false,
        "a gamepad's right stick moves a cursor over the scene and its south button clicks");
    /** Its settings. */
    public static final List<Setting<?>> ALL = List.of(LONG_PRESS_DELAY, DOUBLE_CLICK_TIME, DOUBLE_CLICK_DISTANCE,
        DRAG_THRESHOLD, TOUCH_DRAG_THRESHOLD, RAY_DRAG_ANGLE, HITS, RAY_LENGTH, FAR, TOOLTIP_DELAY, VIRTUAL_CURSOR,
        VIRTUAL_CURSOR_SPEED);

    /** The most pointers at once. */
    public static final int MAX_POINTERS = 16;

    private EngineSettings settings; // null: the defaults (tests)
    private Input input;
    private Desktop desktop;
    private final SceneSelection selection = new SceneSelection(this);

    private InteractionWorld[] worlds = new InteractionWorld[0];
    private InteractionWorld rayWorld;
    private InteractionView[] views = new InteractionView[0];
    private ScenePointerSource[] sources = new ScenePointerSource[0];
    private final ScenePointer[] pointers = new ScenePointer[MAX_POINTERS];
    private int pointerCount;
    private boolean driven;
    private long frame;
    private long now;
    private long focused = NodeHandle.NONE;
    private InteractionWorld focusedWorld;

    // dispatch
    private final SceneEvent[] events = new SceneEvent[4];
    private int depth;
    private long[][] paths = new long[4][32];
    private final long[] objects = new long[ScenePicker.MAX_HITS];
    private long dispatches;

    // the setting values of the frame
    private long longPressNanos;
    private long doubleClickNanos;
    private float doubleClickDistance;
    private float dragThreshold;
    private float touchDragThreshold;
    private double rayDragCos;
    private long tooltipNanos;
    private int hits;
    private double rayLength;
    private double far;

    /** An interaction of no worlds yet; installed as a plugin, or run by hand ({@link #update}) in tests and tools. */
    @AllowAllocation("one per engine")
    public SceneInteraction() {
        for (int i = 0; i < events.length; i++) {
            events[i] = new SceneEvent(this);
        }
        for (int i = 0; i < MAX_POINTERS; i++) {
            pointers[i] = new ScenePointer(4);
        }
    }

    @Override
    public PluginKey<SceneInteraction> key() {
        return KEY;
    }

    @Override
    public List<PluginKey<?>> requires() {
        return List.of(Input.KEY);
    }

    @Override
    public List<PluginKey<?>> after() {
        return List.of(Desktop.KEY, Rendering.KEY);
    }

    @Override
    @AllowAllocation("starting up")
    public void start(Stage stage, Startup startup) {
        switch (stage) {
            case BEFORE_INSTANCE -> {
                settings = startup.settings();
                startup.registry().add(SettingGroup.root("scene", "settings.scene", 47), ALL.toArray(new Setting<?>[0]));
                input = startup.get(Input.KEY);
                input.add(SceneActions.SCENE);
                desktop = startup.find(Desktop.KEY).orElse(null);
            }
            case RUN -> wire(startup);
            default -> {
                // views come from the game, after startup
            }
        }
    }

    @AllowAllocation("starting up")
    private void wire(Startup startup) {
        Window window = null;
        Rendering rendering = startup.find(Rendering.KEY).orElse(null);
        if (rendering != null && !rendering.headless()) {
            for (Presenter.Target t : rendering.presenter().targets()) {
                window = t.window();
                break;
            }
        }
        if (window != null && desktop != null) {
            ScenePointers.Mouse mouse = new ScenePointers.Mouse(window, input);
            ScenePointers.Touch touch = new ScenePointers.Touch(window);
            ScenePointers.Pen pen = new ScenePointers.Pen(window);
            desktop.listen(mouse).listen(touch).listen(pen);
            pointers(mouse).pointers(touch).pointers(pen);
        }
        pointers(new ScenePointers.VirtualCursor(input, this::virtualCursorOn, () -> setting(VIRTUAL_CURSOR_SPEED),
            window));
        selection.input(SceneSelection.Input.of(input));
    }

    private boolean virtualCursorOn() {
        return setting(VIRTUAL_CURSOR);
    }

    /** Adds {@code source}'s pointers each frame; returns it. */
    @AllowAllocation("sources are added when the game is set up")
    public SceneInteraction pointers(ScenePointerSource source) {
        sources = Arrays.copyOf(sources, sources.length + 1);
        sources[sources.length - 1] = Objects.requireNonNull(source, "source");
        return this;
    }

    // ---------------------------------------------------------------- worlds and views

    /** Adds a world: {@code graph} picked through {@code index}, by a {@link ScenePicker} of its own. */
    @AllowAllocation("worlds are added when the game is set up")
    public InteractionWorld world(SceneGraph graph, SpatialIndex index) {
        return world(new ScenePicker(graph, index));
    }

    /** Adds a world picked through {@code picker} (shared with the UI's scene bridge: a ray is cast once a frame). */
    @AllowAllocation("worlds are added when the game is set up")
    public InteractionWorld world(ScenePicker picker) {
        for (InteractionWorld w : worlds) {
            if (w.picker() == picker) {
                return w;
            }
        }
        InteractionWorld world = new InteractionWorld(this, picker);
        worlds = Arrays.copyOf(worlds, worlds.length + 1);
        worlds[worlds.length - 1] = world;
        if (rayWorld == null) {
            rayWorld = world;
        }
        return world;
    }

    /** The world of {@code graph}, or null. */
    public InteractionWorld worldOf(SceneGraph graph) {
        for (InteractionWorld w : worlds) {
            if (w.graph() == graph) {
                return w;
            }
        }
        return null;
    }

    /** How many worlds there are. */
    public int worldCount() {
        return worlds.length;
    }

    /** World {@code i}. */
    public InteractionWorld world(int i) {
        return worlds[i];
    }

    /** The world XR rays pick in (default: the first added); returns it. */
    public SceneInteraction rayWorld(InteractionWorld world) {
        rayWorld = world;
        return this;
    }

    @AllowAllocation("views are added when the game is set up")
    InteractionView addView(InteractionView view) {
        views = Arrays.copyOf(views, views.length + 1);
        views[views.length - 1] = view;
        return view;
    }

    /** Removes {@code view}: pointers over it pick nothing. */
    @AllowAllocation("views are removed rarely")
    public void removeView(InteractionView view) {
        for (int i = 0; i < views.length; i++) {
            if (views[i] == view) {
                InteractionView[] fewer = new InteractionView[views.length - 1];
                System.arraycopy(views, 0, fewer, 0, i);
                System.arraycopy(views, i + 1, fewer, i, views.length - i - 1);
                views = fewer;
                return;
            }
        }
    }

    /** The view window point {@code x, y} is over: the last added of those it is on, or null. */
    public InteractionView viewAt(float x, float y) {
        for (int i = views.length - 1; i >= 0; i--) {
            if (views[i].contains(x, y)) {
                return views[i];
            }
        }
        return null;
    }

    /** The selection clicks and marquees make (off until {@link SceneSelection#enabled enabled}). */
    public SceneSelection selection() {
        return selection;
    }

    // ---------------------------------------------------------------- pointers

    /**
     * Pointer {@code id} of {@code kind} for this frame, made when new: a source writes it ({@link ScenePointer#moveTo},
     * {@link ScenePointer#buttons}, ...). A pointer not written in a frame leaves.
     */
    public ScenePointer pointer(int id, PointerKind kind) {
        ScenePointer p = byId(id);
        if (p == null) {
            if (pointerCount == MAX_POINTERS) {
                throw new IllegalStateException(tooMany());
            }
            p = pointers[pointerCount++];
            reset(p);
            p.id = id;
            p.used = true;
        }
        p.kind = Objects.requireNonNull(kind, "kind");
        p.writtenFrame = frame + 1; // written for the frame being prepared
        p.blocked = false;
        p.scrollX = 0;
        p.scrollY = 0;
        return p;
    }

    @AllowAllocation("diagnostics")
    private static String tooMany() {
        return "More than " + MAX_POINTERS + " pointers at once";
    }

    private ScenePointer byId(int id) {
        for (int i = 0; i < pointerCount; i++) {
            if (pointers[i].id == id) {
                return pointers[i];
            }
        }
        return null;
    }

    /** How many pointers there are this frame. */
    public int pointerCount() {
        return pointerCount;
    }

    /** Pointer {@code i} (0 to {@link #pointerCount()} - 1). */
    public ScenePointer pointer(int i) {
        return pointers[Objects.checkIndex(i, pointerCount)];
    }

    /** The pointer of {@code id}, or null. */
    public ScenePointer pointerById(int id) {
        return byId(id);
    }

    /** The node the scene's focus is on, or {@link NodeHandle#NONE}. */
    public long focused() {
        return focused;
    }

    /** Whether a pointer is over {@code node} (or a descendant) in any world. */
    public boolean hovered(long node) {
        for (InteractionWorld w : worlds) {
            if (w.hovered(node)) {
                return true;
            }
        }
        return false;
    }

    /** How many events were dispatched to listeners so far (a test counts them). */
    public long dispatches() {
        return dispatches;
    }

    /**
     * Whether something runs its frames: the UI plugin, which picks its screen layers, world panels and gizmos first
     * and then hands the scene the pointers it did not take ({@link #beginFrame}, {@link #process}). While driven, the
     * plugin's own frame step and its pointer sources rest; returns it.
     */
    public SceneInteraction driven(boolean on) {
        driven = on;
        return this;
    }

    /** Whether something else runs its frames. */
    public boolean driven() {
        return driven;
    }

    // ---------------------------------------------------------------- the frame

    @Override
    public void beforeUpdate(Frame current) {
        if (!driven) {
            update((long) (current.realTime() * 1e9));
        }
    }

    /** One frame at time {@code nanos}: {@link #beginFrame}, the sources' pointers, {@link #process}. */
    public void update(long nanos) {
        beginFrame(nanos);
        for (ScenePointerSource source : sources) {
            source.poll(this);
        }
        process();
    }

    /**
     * The frame's first step: the worlds' scenes and spatial indexes brought up to date (where they update them), their
     * pickers' kept picks forgotten, the frame's settings read. Pointers may be written after it.
     */
    public void beginFrame(long nanos) {
        now = nanos;
        for (InteractionWorld w : worlds) {
            if (w.updatesScene()) {
                w.graph().update();
                w.index().update();
            }
            w.picker().beginFrame();
        }
        longPressNanos = (long) (setting(LONG_PRESS_DELAY) * 1e9);
        doubleClickNanos = (long) (setting(DOUBLE_CLICK_TIME) * 1e9);
        doubleClickDistance = setting(DOUBLE_CLICK_DISTANCE);
        dragThreshold = setting(DRAG_THRESHOLD);
        touchDragThreshold = setting(TOUCH_DRAG_THRESHOLD);
        tooltipNanos = (long) (setting(TOOLTIP_DELAY) * 1e9);
        rayDragCos = com.truesim.common.math.Maths.cos(setting(RAY_DRAG_ANGLE) * Math.PI / 180);
        hits = setting(HITS);
        rayLength = setting(RAY_LENGTH);
        far = setting(FAR);
    }

    /** The frame's second step: every pointer picked, its events routed; pointers not written this frame leave. */
    public void process() {
        frame++;
        for (int i = 0; i < pointerCount; i++) {
            ScenePointer p = pointers[i];
            if (p.writtenFrame != frame) {
                leave(p);
                pointers[i] = pointers[--pointerCount];
                pointers[pointerCount] = p;
                p.used = false;
                i--;
                continue;
            }
            pick(p);
            route(p);
        }
        selection.update();
    }

    private <T> T setting(Setting<T> setting) {
        return settings == null ? setting.defaultValue() : settings.get(setting);
    }

    // ---------------------------------------------------------------- picking

    private void pick(ScenePointer p) {
        if (p.hits.capacity() != hits) {
            p.hits = depthHits(hits);
        }
        p.world = null;
        p.view = null;
        p.picked = false;
        p.hits.clear();
        if (p.blocked) {
            return;
        }
        double reach;
        if (p.hasRay) {
            p.world = p.rayWorld != null ? p.rayWorld : rayWorld;
            System.arraycopy(p.ray, 0, p.pickedRay, 0, 6);
            double[] r = p.pickedRay;
            reach = rayLength / Math.sqrt(r[3] * r[3] + r[4] * r[4] + r[5] * r[5]);
        } else {
            InteractionView view = viewAt(p.x, p.y);
            if (view == null) {
                return;
            }
            if (p.hinted) {
                System.arraycopy(p.hint, 0, p.pickedRay, 0, 6);
                reach = p.hintReach;
            } else if (view.ray(p.x, p.y, p.pickedRay)) {
                reach = view.reachOr(far);
            } else {
                return;
            }
            p.view = view;
            p.world = view.world();
        }
        if (p.world == null) {
            return;
        }
        double[] r = p.pickedRay;
        p.world.picker().pick(r[0], r[1], r[2], r[3], r[4], r[5], reach, p.world.pickLayers(), p.hits);
        p.picked = true;
    }

    @AllowAllocation("when scene.interaction.hits changes")
    private static PickHits depthHits(int depth) {
        return new PickHits(depth);
    }

    // ---------------------------------------------------------------- routing

    private void route(ScenePointer p) {
        long target = p.hits.count() > 0 ? p.hits.node(0) : NodeHandle.NONE;
        if (p.blocked && p.pressing) {
            cancelPress(p); // something in front took it (the UI's drag of a node's payload, say)
        }
        hover(p, target);
        boolean moved = p.x != p.lastX || p.y != p.lastY || !sameRay(p);
        if (moved || p.buttons != 0 || target != p.restNode) {
            p.restNode = target;
            p.restTime = now;
            p.tooltipFired = p.buttons != 0; // a press shows no tooltip until the pointer moves on
        } else if (!p.tooltipFired && target != NodeHandle.NONE && now - p.restTime >= tooltipNanos) {
            p.tooltipFired = true;
            dispatch(SceneEventType.TOOLTIP, target, p.world, p, 0);
        }
        int down = p.buttons & ~p.lastButtons;
        int up = p.lastButtons & ~p.buttons;
        for (int bit = ScenePointer.PRIMARY; bit <= ScenePointer.MIDDLE; bit <<= 1) {
            if ((down & bit) != 0) {
                press(p, bit);
            }
        }
        boolean held = p.pressing && (p.buttons & ScenePointer.PRIMARY) != 0;
        if (held && !p.dragging && movedFar(p)) {
            startDrag(p); // this frame's move is the drag's first
        }
        if (moved) {
            if (p.dragging) {
                drag(p);
            } else if (p.captured != NodeHandle.NONE) {
                dispatch(SceneEventType.MOVE, p.captured, p.pressWorld, p, 0);
            } else {
                dispatch(SceneEventType.MOVE, target, p.world, p, 0);
            }
        }
        if (held && !p.dragging) {
            longPress(p);
        }
        for (int bit = ScenePointer.PRIMARY; bit <= ScenePointer.MIDDLE; bit <<= 1) {
            if ((up & bit) != 0) {
                release(p, bit);
            }
        }
        if (p.scrollX != 0 || p.scrollY != 0) {
            passThrough(SceneEventType.WHEEL, p, 0);
        }
        p.lastButtons = p.buttons;
        p.lastX = p.x;
        p.lastY = p.y;
        System.arraycopy(p.pickedRay, 0, p.lastRay, 0, 6);
    }

    /** Whether a held press moved past the drag threshold: pixels for points, an angle for rays of their own. */
    private boolean movedFar(ScenePointer p) {
        if (p.hasRay) {
            double[] a = p.pickedRay;
            double[] b = p.pressRay;
            double dot = a[3] * b[3] + a[4] * b[4] + a[5] * b[5];
            double lengths = Math.sqrt((a[3] * a[3] + a[4] * a[4] + a[5] * a[5]) * (b[3] * b[3] + b[4] * b[4]
                + b[5] * b[5]));
            return dot < rayDragCos * lengths;
        }
        float threshold = p.kind == PointerKind.TOUCH ? touchDragThreshold : dragThreshold;
        float dx = p.x - p.pressX;
        float dy = p.y - p.pressY;
        return dx * dx + dy * dy > threshold * threshold;
    }

    private static boolean sameRay(ScenePointer p) {
        double[] a = p.pickedRay;
        double[] b = p.lastRay;
        return a[0] == b[0] && a[1] == b[1] && a[2] == b[2] && a[3] == b[3] && a[4] == b[4] && a[5] == b[5];
    }

    /** Enter and exit along the pointer's paths; its object marked hovered. */
    private void hover(ScenePointer p, long target) {
        InteractionWorld world = p.world;
        if (target == p.hovered && world == p.hoverWorld) {
            return;
        }
        // the nodes left: from the old target up to the first the new path shares
        long[] oldPath = path(p.hoverWorld, p.hovered, 0);
        int oldDepth = pathDepth;
        long[] newPath = path(world, target, 1);
        int newDepth = pathDepth;
        int shared = 0;
        if (world == p.hoverWorld) {
            while (shared < oldDepth && shared < newDepth
                && oldPath[oldDepth - 1 - shared] == newPath[newDepth - 1 - shared]) {
                shared++;
            }
        }
        for (int i = 0; i < oldDepth - shared; i++) {
            dispatch(SceneEventType.EXIT, oldPath[i], p.hoverWorld, p, 0);
        }
        if (p.hoverRoot != NodeHandle.NONE) {
            p.hoverWorld.exitObject(p.hoverRoot);
        }
        p.hovered = target;
        p.hoverWorld = world;
        p.hoverRoot = target == NodeHandle.NONE ? NodeHandle.NONE : world.objectOf(target);
        if (p.hoverRoot != NodeHandle.NONE) {
            world.enterObject(p.hoverRoot);
        }
        for (int i = newDepth - shared - 1; i >= 0; i--) {
            dispatch(SceneEventType.ENTER, newPath[i], world, p, 0);
        }
    }

    private int pathDepth;

    /** {@code node} and its ancestors into path buffer {@code buffer}, the node first; its depth in {@link #pathDepth}. */
    private long[] path(InteractionWorld world, long node, int buffer) {
        long[] path = paths[buffer];
        int n = 0;
        if (world != null && node != NodeHandle.NONE) {
            SceneGraph graph = world.graph();
            for (long at = node; at != NodeHandle.NONE && graph.contains(at); at = graph.parent(at)) {
                if (n == path.length) {
                    path = growPath(buffer);
                }
                path[n++] = at;
            }
        }
        pathDepth = n;
        return path;
    }

    @AllowAllocation("a deeper scene graph than before")
    private long[] growPath(int buffer) {
        paths[buffer] = Arrays.copyOf(paths[buffer], paths[buffer].length * 2);
        return paths[buffer];
    }

    private void press(ScenePointer p, int bit) {
        if (bit == ScenePointer.PRIMARY) {
            p.pressX = p.x;
            p.pressY = p.y;
            System.arraycopy(p.pickedRay, 0, p.pressRay, 0, 6);
            p.pressWorld = p.world;
            p.pressView = p.view;
            p.pressTime = now;
        }
        long hit = passThrough(SceneEventType.PRESS, p, bit);
        if (hit == NodeHandle.NONE && p.hits.count() > 0) {
            hit = p.hits.node(0); // nobody consumed it: the nearest is pressed
        }
        if (bit == ScenePointer.PRIMARY) {
            p.pressing = true;
            p.pressed = hit;
            p.pressedNothing = hit == NodeHandle.NONE;
            p.longFired = false;
            p.suppressClick = false;
            p.dragging = false;
            if (p.captured == NodeHandle.NONE) {
                p.captured = hit;
            }
            focus(hit, p.world);
        } else if (bit == ScenePointer.SECONDARY) {
            long target = p.hits.count() > 0 ? p.hits.node(0) : NodeHandle.NONE;
            dispatch(SceneEventType.CONTEXT, target, p.world, p, bit);
        }
    }

    private void longPress(ScenePointer p) {
        if (p.longFired || p.pressed == NodeHandle.NONE || now - p.pressTime < longPressNanos) {
            return;
        }
        p.longFired = true;
        if (dispatch(SceneEventType.LONG_PRESS, p.pressed, p.pressWorld, p, ScenePointer.PRIMARY)) {
            p.suppressClick = true;
        } else if (p.kind == PointerKind.TOUCH) {
            p.suppressClick = true; // a long touch asks for a context menu instead of clicking
            dispatch(SceneEventType.CONTEXT, p.pressed, p.pressWorld, p, ScenePointer.PRIMARY);
        }
    }

    private void startDrag(ScenePointer p) {
        p.dragging = true;
        if (p.pressedNothing) {
            selection.marqueeStart(p);
            return;
        }
        SceneEvent e = event(SceneEventType.DRAG_START, p.pressed, p.pressWorld, p, ScenePointer.PRIMARY);
        route(e);
        depth--;
    }

    private void drag(ScenePointer p) {
        if (p.pressedNothing) {
            selection.marqueeMove(p);
            return;
        }
        dispatch(SceneEventType.DRAG, p.pressed, p.pressWorld, p, ScenePointer.PRIMARY);
        if (p.dragPayload == null || p.world != p.pressWorld) {
            return; // payloads stay within their world
        }
        // offer the payload along the ray until a node accepts it
        long acceptor = NodeHandle.NONE;
        for (int k = 0; k < p.hits.count() && acceptor == NodeHandle.NONE; k++) {
            long node = p.hits.node(k);
            if (p.world.above(node, p.dragSource) || p.world.above(p.dragSource, node)) {
                continue; // not onto itself
            }
            SceneEvent e = event(SceneEventType.DROP_OVER, node, p.world, p, 0);
            route(e);
            acceptor = e.dropAccepted ? e.dropAcceptor : NodeHandle.NONE;
            depth--;
        }
        if (acceptor != p.dropTarget) {
            if (p.dropTarget != NodeHandle.NONE) {
                dispatch(SceneEventType.DROP_LEAVE, p.dropTarget, p.pressWorld, p, 0);
            }
            p.dropTarget = acceptor;
            if (acceptor != NodeHandle.NONE) {
                dispatch(SceneEventType.DROP_ENTER, acceptor, p.pressWorld, p, 0);
            }
        }
    }

    private void release(ScenePointer p, int bit) {
        long target = p.hits.count() > 0 ? p.hits.node(0) : NodeHandle.NONE;
        if (p.captured != NodeHandle.NONE) {
            dispatch(SceneEventType.RELEASE, p.captured, p.pressWorld, p, bit);
        } else {
            dispatch(SceneEventType.RELEASE, target, p.world, p, bit);
        }
        if (bit != ScenePointer.PRIMARY || !p.pressing) {
            return;
        }
        if (p.dragging) {
            endDrag(p, true);
        } else if (p.pressedNothing) {
            if (target == NodeHandle.NONE) {
                selection.clickedNothing(p);
            }
        } else if (!p.suppressClick && p.world == p.pressWorld && target != NodeHandle.NONE
            && p.world.above(p.pressed, target)) {
            click(p);
        }
        p.pressing = false;
        p.pressed = NodeHandle.NONE;
        p.captured = NodeHandle.NONE;
    }

    private void click(ScenePointer p) {
        float dx = p.x - p.clickX;
        float dy = p.y - p.clickY;
        boolean again = p.clickNode == p.pressed && now - p.clickTime <= doubleClickNanos
            && dx * dx + dy * dy <= doubleClickDistance * doubleClickDistance;
        p.clickCount = again ? p.clickCount + 1 : 1;
        p.clickNode = p.pressed;
        p.clickTime = now;
        p.clickX = p.x;
        p.clickY = p.y;
        SceneEvent e = event(SceneEventType.CLICK, p.pressed, p.pressWorld, p, ScenePointer.PRIMARY);
        e.clickCount = p.clickCount;
        boolean consumed = route(e);
        depth--;
        if (!consumed) {
            selection.clicked(p.pressWorld, p.pressed, p);
        }
    }

    private void endDrag(ScenePointer p, boolean drop) {
        if (p.pressedNothing) {
            selection.marqueeEnd(p, drop);
        } else {
            boolean dropped = false;
            if (p.dragPayload != null && p.dropTarget != NodeHandle.NONE) {
                if (drop) {
                    dispatch(SceneEventType.DROP, p.dropTarget, p.pressWorld, p, 0);
                    dropped = true;
                } else {
                    dispatch(SceneEventType.DROP_LEAVE, p.dropTarget, p.pressWorld, p, 0);
                }
            }
            SceneEvent e = event(SceneEventType.DRAG_END, p.dragSource != NodeHandle.NONE ? p.dragSource : p.pressed,
                p.pressWorld, p, ScenePointer.PRIMARY);
            e.dropped = dropped;
            route(e);
            depth--;
        }
        p.dragging = false;
        p.dragPayload = null;
        p.dragSource = NodeHandle.NONE;
        p.dropTarget = NodeHandle.NONE;
    }

    private void focus(long node, InteractionWorld world) {
        if (node == focused && world == focusedWorld) {
            return;
        }
        long old = focused;
        InteractionWorld oldWorld = focusedWorld;
        focused = node;
        focusedWorld = world;
        if (old != NodeHandle.NONE) {
            dispatch(SceneEventType.FOCUS_OUT, old, oldWorld, null, 0);
        }
        if (node != NodeHandle.NONE) {
            dispatch(SceneEventType.FOCUS_IN, node, world, null, 0);
        }
    }

    /** Ends a held press without a click: its drag cancelled, its capture released. */
    private void cancelPress(ScenePointer p) {
        if (p.dragging) {
            endDrag(p, false);
        }
        p.pressing = false;
        p.pressed = NodeHandle.NONE;
        p.captured = NodeHandle.NONE;
        p.lastButtons = p.buttons; // its release is not this interaction's
    }

    /** A pointer gone: it leaves what it was over, its drag is cancelled, its press and capture end. */
    private void leave(ScenePointer p) {
        p.hits.clear();
        p.world = null;
        hover(p, NodeHandle.NONE);
        if (p.dragging) {
            endDrag(p, false);
        }
        reset(p);
    }

    private static void reset(ScenePointer p) {
        p.pressing = false;
        p.pressed = NodeHandle.NONE;
        p.captured = NodeHandle.NONE;
        p.dragging = false;
        p.dragPayload = null;
        p.dragSource = NodeHandle.NONE;
        p.dropTarget = NodeHandle.NONE;
        p.lastButtons = 0;
        p.buttons = 0;
        p.hovered = NodeHandle.NONE;
        p.hoverRoot = NodeHandle.NONE;
        p.hoverWorld = null;
        p.pressWorld = null;
        p.pressView = null;
        p.clickNode = NodeHandle.NONE;
        p.clickCount = 0;
        p.picked = false;
        p.hasRay = false;
        p.rayWorld = null;
        p.player = 0;
        p.modifiers = 0;
        Arrays.fill(p.lastRay, Double.NaN);
    }

    // ---------------------------------------------------------------- dispatch

    /** Offers an event along the pointer's hits until one consumes it (once per object); returns that node or NONE. */
    private long passThrough(SceneEventType type, ScenePointer p, int button) {
        int count = p.hits.count();
        for (int k = 0; k < count; k++) {
            long node = p.hits.node(k);
            long object = p.world.objectOf(node);
            boolean seen = false;
            for (int j = 0; j < k; j++) {
                seen |= objects[j] == object;
            }
            objects[k] = object;
            if (!seen && dispatch(type, node, p.world, p, button)) {
                return node;
            }
        }
        return NodeHandle.NONE;
    }

    /** Dispatches an event of {@code type} to {@code target}; whether a listener consumed it. */
    private boolean dispatch(SceneEventType type, long target, InteractionWorld world, ScenePointer p, int button) {
        if (target == NodeHandle.NONE || world == null) {
            return false;
        }
        SceneEvent e = event(type, target, world, p, button);
        boolean consumed = route(e);
        depth--;
        return consumed;
    }

    /** The next free event, filled; release it with {@code depth--} once routed. */
    private SceneEvent event(SceneEventType type, long target, InteractionWorld world, ScenePointer p, int button) {
        if (depth == events.length) {
            throw new IllegalStateException("Scene events nested deeper than " + events.length);
        }
        SceneEvent e = events[depth++];
        e.type = type;
        e.target = target;
        e.current = target;
        e.world = world;
        e.pointer = p;
        e.hit = p == null ? -1 : p.hits.indexOf(target);
        e.button = button;
        e.clickCount = 0;
        e.consumed = false;
        e.dropAccepted = false;
        e.dropAcceptor = NodeHandle.NONE;
        e.dragStarted = false;
        e.dropped = false;
        e.time = now;
        return e;
    }

    /** Routes {@code e}: capture, target, bubble for bubbling types, the target alone otherwise. */
    private boolean route(SceneEvent e) {
        InteractionWorld world = e.world;
        if (world == null || !world.graph().contains(e.target)) {
            return false;
        }
        e.world = world;
        int bit = e.type.bit();
        if (!e.type.bubbles()) {
            invoke(e, world, e.target, NodeEvent.Phase.TARGET, bit);
            return e.consumed;
        }
        long[] path = path(world, e.target, 2 + Math.min(depth - 1, 1));
        int n = pathDepth;
        for (int i = n - 1; i > 0 && !e.consumed; i--) {
            invoke(e, world, path[i], NodeEvent.Phase.CAPTURE, bit);
        }
        if (!e.consumed) {
            invoke(e, world, path[0], NodeEvent.Phase.TARGET, bit);
        }
        for (int i = 1; i < n && !e.consumed; i++) {
            invoke(e, world, path[i], NodeEvent.Phase.BUBBLE, bit);
        }
        return e.consumed;
    }

    /** The node's own listeners for {@code phase}, then its front end's ({@link RoutedListener}: the UI's). */
    private void invoke(SceneEvent e, InteractionWorld world, long node, NodeEvent.Phase phase, int bit) {
        e.phase = phase;
        e.current = node;
        InteractionWorld.NodeListeners l = world.listeners.get(node);
        if (l != null && (l.all & bit) != 0) {
            for (int i = 0; i < l.listeners.length && !e.consumed; i++) {
                if ((l.masks[i] & bit) != 0 && (phase == NodeEvent.Phase.TARGET
                    || l.capture[i] == (phase == NodeEvent.Phase.CAPTURE))) {
                    dispatches++;
                    l.listeners[i].handle(e);
                }
            }
        }
        RoutedListener routed = world.routed.get(node);
        if (routed != null && !e.consumed && routed.hears(e.type, phase)) {
            dispatches++;
            e.phase = phase;
            e.current = node;
            routed.listener().handle(e);
        }
    }

    @Override
    public void stop() {
        for (ScenePointerSource source : sources) {
            if (desktop != null && source instanceof com.truesim.engine.platform.PlatformListener listener) {
                desktop.unlisten(listener);
            }
        }
    }
}
