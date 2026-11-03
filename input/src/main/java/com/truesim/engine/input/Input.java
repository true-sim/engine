package com.truesim.engine.input;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.app.Clock;
import com.truesim.engine.app.Engine;
import com.truesim.engine.app.Frame;
import com.truesim.engine.app.Phase;
import com.truesim.engine.app.Plugin;
import com.truesim.engine.app.PluginKey;
import com.truesim.engine.app.Stage;
import com.truesim.engine.app.Startup;
import com.truesim.engine.app.Step;
import com.truesim.engine.platform.Desktop;
import com.truesim.engine.platform.PlatformListener;
import com.truesim.engine.settings.EngineSettings;
import com.truesim.engine.settings.Setting;
import com.truesim.engine.settings.SettingGroup;
import com.truesim.engine.settings.SettingRegistry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Input as a plugin (application-api.md §5, D55): actions in groups, bound to devices' controls by bindings that are
 * settings, read per player. Installed with {@code app.use(new Input().add(GAME))}; a module brings its own group and
 * defaults in one call ({@code input.add(UI)}), and plugins bring kinds of device ({@link #addSource}: XR controllers
 * with {@code Xr}). It starts after the desktop when that is installed and listens to its events.
 *
 * <pre>{@code
 * Input input = engine.get(Input.KEY);
 * Player one = input.player(0);
 * if (one.pressed(JUMP)) jump();                        // in update: since the last frame; in a fixed step: since its last
 * walk(one.x(MOVE), one.y(MOVE));
 * input.push(menu);                                     // a context on top: the menu's keys, the pointer free
 * Capture capture = input.capture(one, JUMP);           // rebinding: the next input becomes the binding
 * }</pre>
 *
 * <p>Each frame starts with the devices' values taken and every player's actions worked out once (D59; after the
 * frame driver's begin, so a headset's input synced there is this frame's too), before the fixed steps: actions are
 * read in the same frame they were sampled, with no latency added. Edges are counted per action and player; each
 * world's fixed steps see those since their own last step (D60), and a paused world's are cleared. Nothing is allocated
 * per frame (D65) once the bindings are made ready, which happens when they change. Used from the main thread.
 */
public final class Input implements Plugin {

    /** Its key. */
    public static final PluginKey<Input> KEY = PluginKey.of("input", Input.class);

    /** A context's actions in the order they are read this frame. */
    record Level(InputContext context, int[] actions, boolean text, boolean blocking) {
    }

    /** Edges a world's fixed steps see: the counts when its last step and the one before ran (D60). */
    private static final class ClockEdges {
        final Clock clock;
        int[] basePresses;
        int[] baseReleases;
        int[] markPresses;
        int[] markReleases;

        ClockEdges(Clock clock, int[] presses, int[] releases) {
            this.clock = clock;
            basePresses = presses.clone();
            baseReleases = releases.clone();
            markPresses = presses.clone();
            markReleases = releases.clone();
        }
    }

    /** An action's game filters (D84) with their state per player and reader: the frame, then each world. */
    private static final class Filtered {
        final ActionFilter[] filters;
        final int[] stateAt;
        final int stateSize;
        float[][] state = new float[0][];  // [player * readers + reader]
        float[][] out = new float[0][];
        long[] stamp = new long[0];

        Filtered(ActionFilter[] filters) {
            this.filters = filters;
            stateAt = new int[filters.length];
            int size = 0;
            for (int i = 0; i < filters.length; i++) {
                stateAt[i] = size;
                size += filters[i].stateSize();
            }
            stateSize = size;
        }

        @AllowAllocation("players or worlds added")
        void size(int slots) {
            int before = state.length;
            state = Arrays.copyOf(state, slots);
            out = Arrays.copyOf(out, slots);
            stamp = Arrays.copyOf(stamp, slots);
            for (int i = before; i < slots; i++) {
                state[i] = new float[stateSize];
                out[i] = new float[3];
                stamp[i] = Long.MIN_VALUE;
            }
        }

        void reset() {
            for (int i = 0; i < state.length; i++) {
                Arrays.fill(state[i], 0);
                stamp[i] = Long.MIN_VALUE;
            }
        }
    }

    // Declared: groups, actions, their bindings settings.
    private final List<ActionGroup> roots = new ArrayList<>();
    private final List<ActionGroup> groups = new ArrayList<>();
    private final Set<ActionGroup> disabled = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<ActionGroup, SettingGroup> settingGroups = new IdentityHashMap<>();
    private Action[] actions = new Action[0];
    private int[] slots = new int[0]; // by action id: its index here, or -1
    private final Map<String, Action> byName = new HashMap<>();
    private final List<Setting<String>> bindingSettings = new ArrayList<>();
    private final Set<String> registeredScopes = new HashSet<>();
    private Filtered[] filtered = new Filtered[0];
    // Kinds of device and filters.
    private DeviceClass[] classes = {DeviceClass.KEYBOARD, DeviceClass.MOUSE, DeviceClass.GAMEPAD, DeviceClass.JOYSTICK};
    private final Map<String, FilterType> filterTypes = new LinkedHashMap<>();
    // The engine.
    private Engine engine;
    private EngineSettings settings;
    private SettingRegistry registry;
    private EngineSettings.Subscription changes;
    // Devices and players.
    private final Device keyboard;
    private final Device mouse;
    private final List<Device> devices = new ArrayList<>();
    private final Map<Device, Player> lost = new LinkedHashMap<>(); // unplugged, with whose they were
    private final List<InputSource> sources = new ArrayList<>();
    private final DesktopSource desktop;
    private InputListener[] listeners = new InputListener[0];
    private Player[] players;
    private boolean joinOnPress;
    // Contexts.
    private final List<InputContext> stack = new ArrayList<>();
    private long pushes;
    private Level[] levels = new Level[0];
    private MouseMode mouseDefault = MouseMode.RELEASED;
    private MouseMode mouseMode;
    private InputContext textContext;
    private boolean contextsBuilt;
    // Changes waiting for the next frame.
    private boolean bindingsDirty = true;
    private boolean contextsDirty = true;
    private boolean devicesDirty = true;
    private boolean focused = true;
    // Edges: counts per player and action, at the frame's start, and per world.
    private int[] presses = new int[0];
    private int[] releases = new int[0];
    private int[] framePresses = new int[0];
    private int[] frameReleases = new int[0];
    private ClockEdges[] clocks = new ClockEdges[0];
    private ClockEdges stepping;
    private Step step;
    private Frame frame;
    // Settings read when they change.
    private float pressThreshold = InputSettings.PRESS_THRESHOLD.defaultValue();
    private float releaseThreshold = InputSettings.RELEASE_THRESHOLD.defaultValue();
    private float holdTime = InputSettings.HOLD_TIME.defaultValue();
    private final Set<String> problems = new java.util.LinkedHashSet<>(); // each reported once

    /** Input with no actions yet: add groups with {@link #add}. */
    @AllowAllocation("set up")
    public Input() {
        for (FilterType type : Filters.BUILT_IN) {
            filterTypes.put(type.id(), type);
        }
        desktop = new DesktopSource(this);
        keyboard = new Device(DeviceClass.KEYBOARD, 0, DeviceIdentity.NONE, "keyboard", desktop);
        mouse = new Device(DeviceClass.MOUSE, 0, DeviceIdentity.NONE, "mouse", desktop);
        players = new Player[] {new Player(this, 0)};
        keyboard.player = players[0];
        mouse.player = players[0];
        devices.add(keyboard);
        devices.add(mouse);
    }

    @Override
    public PluginKey<Input> key() {
        return KEY;
    }

    /** After the desktop: a frame samples the events the desktop read for it. */
    @Override
    @AllowAllocation("starting up")
    public List<PluginKey<?>> after() {
        return List.of(Desktop.KEY);
    }

    @Override
    @AllowAllocation("starting up")
    public void start(Stage stage, Startup startup) {
        if (stage != Stage.BEFORE_INSTANCE) {
            return;
        }
        engine = startup.engine();
        settings = startup.settings();
        registry = startup.registry();
        registry.add(InputSettings.GROUP, InputSettings.HOLD_TIME, InputSettings.PRESS_THRESHOLD,
            InputSettings.RELEASE_THRESHOLD);
        for (ActionGroup root : roots) {
            registerSettings(root);
        }
        readTunables();
        changes = settings.onAnyChange(this::settingChanged);
        startup.find(Desktop.KEY).ifPresent(found -> desktop.attach(found));
    }

    @Override
    public void stop() {
        if (changes != null) {
            changes.close();
            changes = null;
        }
        for (InputSource source : sources) {
            source.stop();
        }
        desktop.detach();
    }

    // ---------------------------------------------------------------- actions

    /**
     * Adds {@code root}'s tree of groups and their actions, with their default bindings, in one call: the game's, or a
     * module's (the UI toolkit's {@code ui}). Their bindings become settings, registered for options screens and saved
     * with the player's settings (D58). Before or after the engine starts.
     */
    @AllowAllocation("set up")
    public Input add(ActionGroup... trees) {
        for (ActionGroup root : trees) {
            if (root.parent() != null) {
                throw new IllegalArgumentException("Add a tree by its root, not " + root);
            }
            if (roots.contains(root)) {
                continue;
            }
            roots.add(root);
            addGroup(root);
            if (registry != null) {
                registerSettings(root);
            }
        }
        sizeTables();
        bindingsDirty = true;
        contextsDirty = true;
        return this;
    }

    @AllowAllocation("set up")
    private void addGroup(ActionGroup group) {
        groups.add(group);
        for (Action action : group.actions()) {
            if (byName.containsKey(action.fullName())) {
                throw new IllegalArgumentException("Two actions are named " + action.fullName());
            }
            byName.put(action.fullName(), action);
            if (action.id >= slots.length) {
                int before = slots.length;
                slots = Arrays.copyOf(slots, Math.max(action.id + 1, 2 * slots.length));
                Arrays.fill(slots, before, slots.length, -1);
            }
            slots[action.id] = actions.length;
            actions = Arrays.copyOf(actions, actions.length + 1);
            actions[actions.length - 1] = action;
            bindingSettings.add(Setting.text("input." + action.fullName(), Binding.format(action.defaults()),
                "the bindings of " + action.fullName()));
        }
        for (ActionGroup child : group.children()) {
            addGroup(child);
        }
    }

    @AllowAllocation("set up")
    private void registerSettings(ActionGroup group) {
        SettingGroup parent = group.parent() == null ? InputSettings.GROUP : settingGroups.get(group.parent());
        SettingGroup own = parent.child(group.id(), "settings.input." + group.path(), groups.indexOf(group));
        settingGroups.put(group, own);
        List<Setting<?>> own_ = new ArrayList<>();
        for (Action action : group.actions()) {
            own_.add(bindingSetting(slot(action)));
        }
        registry.add(own, own_.toArray(new Setting<?>[0]));
        for (ActionGroup child : group.children()) {
            registerSettings(child);
        }
    }

    @AllowAllocation("actions, players or worlds added")
    private void sizeTables() {
        int count = players.length * actions.length;
        presses = Arrays.copyOf(presses, count);
        releases = Arrays.copyOf(releases, count);
        framePresses = presses.clone();
        frameReleases = releases.clone();
        for (ClockEdges edges : clocks) {
            edges.basePresses = presses.clone();
            edges.baseReleases = releases.clone();
            edges.markPresses = presses.clone();
            edges.markReleases = releases.clone();
        }
        for (Player player : players) {
            player.resize(actions.length, classes);
        }
        filtered = Arrays.copyOf(filtered, actions.length);
        for (Filtered f : filtered) {
            if (f != null) {
                f.size(players.length * (1 + clocks.length));
            }
        }
    }

    /** Whether {@code action} was added. */
    public boolean has(Action action) {
        return slot(action) >= 0;
    }

    /** The action named {@code fullName} ({@code game.jump}), or null. */
    public Action action(String fullName) {
        return byName.get(fullName);
    }

    int slot(Action action) {
        int id = action.id;
        return id < slots.length ? slots[id] : -1;
    }

    Action action(int slot) {
        return actions[slot];
    }

    /** The setting holding {@code action}'s bindings: {@code input.<group path>.<name>}. */
    public Setting<String> bindingSetting(Action action) {
        return bindingSetting(require(action));
    }

    private Setting<String> bindingSetting(int slot) {
        return bindingSettings.get(slot);
    }

    @AllowAllocation("reporting a mistake")
    private int require(Action action) {
        int slot = slot(action);
        if (slot < 0) {
            throw new IllegalArgumentException(action + " was not added: input.add(" + action.group().root() + ")");
        }
        return slot;
    }

    /** Adds a game filter to {@code action} (D84), after those added before: not saved, may read game state. */
    @AllowAllocation("set up")
    public Input filter(Action action, ActionFilter filter) {
        int slot = require(action);
        Objects.requireNonNull(filter, "filter");
        ActionFilter[] before = filtered[slot] == null ? new ActionFilter[0] : filtered[slot].filters;
        ActionFilter[] after = Arrays.copyOf(before, before.length + 1);
        after[before.length] = filter;
        filtered[slot] = new Filtered(after);
        filtered[slot].size(players.length * (1 + clocks.length));
        return this;
    }

    // ---------------------------------------------------------------- groups and contexts

    /** Switches {@code group} on or off (D68); it is active only while it and all its parents are on (D67). */
    @AllowAllocation("a switch set")
    public Input enable(ActionGroup group, boolean on) {
        if (on ? disabled.remove(group) : disabled.add(group)) {
            contextsDirty = true;
        }
        return this;
    }

    /** Whether {@code group}'s own switch is on. */
    public boolean enabled(ActionGroup group) {
        return !disabled.contains(group);
    }

    /** Whether {@code group} and all its parents are on. */
    public boolean active(ActionGroup group) {
        for (ActionGroup g = group; g != null; g = g.parent()) {
            if (disabled.contains(g)) {
                return false;
            }
        }
        return true;
    }

    /** A new context, not on the stack yet (D61). */
    @AllowAllocation("set up")
    public InputContext context(String name, int priority) {
        return new InputContext(this, name, priority);
    }

    /** Puts {@code context} on the stack (on top of those of its priority); from the next frame. */
    @AllowAllocation("contexts change")
    public Input push(InputContext context) {
        if (!stack.contains(context)) {
            stack.add(context);
        }
        context.pushed = ++pushes;
        contextsDirty = true;
        return this;
    }

    /** Takes {@code context} off the stack, wherever it is; from the next frame. */
    public Input pop(InputContext context) {
        if (stack.remove(context)) {
            context.pushed = 0;
            contextsDirty = true;
        }
        return this;
    }

    /** The mouse's mode when no context on the stack says (released unless set). */
    public Input mouse(MouseMode mode) {
        if (mode == MouseMode.INHERIT) {
            throw new IllegalArgumentException("The default mouse mode is captured or released");
        }
        mouseDefault = mode;
        contextsDirty = true;
        return this;
    }

    /** Whether the mouse is captured this frame (by the contexts, D61). */
    public boolean mouseCaptured() {
        return mouseMode == MouseMode.CAPTURED;
    }

    /** The context taking text this frame, or null. */
    public InputContext textContext() {
        return textContext;
    }

    void contextsChanged() {
        contextsDirty = true;
    }

    // ---------------------------------------------------------------- bindings

    /** The bindings {@code action} has for {@code player} now: the player's own, else everyone's. */
    @AllowAllocation("a snapshot")
    public List<Binding> bindings(Player player, Action action) {
        int slot = require(action);
        return effective(player.index, slot);
    }

    /**
     * Rebinds {@code action} for {@code player}, as the player's setting (saved with the settings, D58). The first
     * player's bindings are everyone's unless a later player has their own. Throws if a binding cannot drive the action
     * or an action source would make a cycle (D80).
     */
    @AllowAllocation("rebinding")
    public Input bind(Player player, Action action, Binding... bindings) {
        int slot = require(action);
        for (Binding binding : bindings) {
            action.check(binding);
        }
        List<Binding> list = List.of(bindings);
        String cycle = cycle(player.index, slot, list);
        if (cycle != null) {
            throw new IllegalArgumentException("Bindings may not make a cycle of actions: " + cycle);
        }
        Setting<String> setting = bindingSetting(slot);
        EngineSettings view = view(player.index);
        if (player.index > 0 && registry != null) {
            String scope = scope(player.index);
            if (registeredScopes.add(scope + "." + setting.name())) {
                registry.addScoped(scope, settingGroups.get(action.group()), setting);
            }
        }
        view.set(setting, Binding.format(list));
        return this;
    }

    /** Replaces binding {@code index} of {@code action} for {@code player} (adds it at the end past the last). */
    @AllowAllocation("rebinding")
    public Input rebind(Player player, Action action, int index, Binding binding) {
        List<Binding> list = new ArrayList<>(bindings(player, action));
        if (index < list.size()) {
            list.set(index, binding);
        } else {
            list.add(binding);
        }
        return bind(player, action, list.toArray(new Binding[0]));
    }

    /** Back to the defaults (the game's, else the action's) for {@code player}. */
    public Input resetBindings(Player player, Action action) {
        view(player.index).reset(bindingSetting(require(action)));
        return this;
    }

    /** The game's default bindings for {@code action}, over the action's own (the game layer, D6). */
    @AllowAllocation("set up")
    public Input defaults(Action action, Binding... bindings) {
        int slot = require(action);
        for (Binding binding : bindings) {
            action.check(binding);
        }
        if (settings == null) {
            throw new IllegalStateException("Game defaults are set once the engine started");
        }
        settings.set(EngineSettings.Layer.GAME, bindingSetting(slot), Binding.format(List.of(bindings)));
        return this;
    }

    /**
     * The bindings of other actions on the same controls as {@code binding} for {@code player}: what rebinding it
     * would clash with.
     */
    @AllowAllocation("checking a binding")
    public List<Conflict> conflicts(Player player, Action action, Binding binding) {
        List<Conflict> found = new ArrayList<>();
        Bound candidate = bound(binding, require(action));
        if (candidate == null) {
            return found;
        }
        for (int a = 0; a < actions.length; a++) {
            if (actions[a] == action) {
                continue;
            }
            for (Binding other : effective(player.index, a)) {
                Bound theirs = bound(other, a);
                if (theirs != null && candidate.sameControls(theirs, classes) && overlap(binding, other)) {
                    found.add(new Conflict(actions[a], other));
                }
            }
        }
        return found;
    }

    private static boolean overlap(Binding a, Binding b) {
        return a.device() == null || b.device() == null || a.device().matches(b.device());
    }

    /**
     * Listens for {@code player}'s next input to bind {@code action} ({@link Capture}); the player's actions see
     * nothing until it is done. Replaces a capture of the player's not done.
     */
    @AllowAllocation("starting a capture")
    public Capture capture(Player player, Action action) {
        require(action);
        if (player.capture != null) {
            player.capture.cancel();
        }
        Capture capture = new Capture(player, action);
        player.capture = capture;
        return capture;
    }

    /** What went wrong reading saved bindings (text not bindings, a cycle cut): kept for diagnostics. */
    @AllowAllocation("a snapshot")
    public List<String> problems() {
        return List.copyOf(problems);
    }

    /** Adds a binding filter type (D83), saved by its id. */
    @AllowAllocation("set up")
    public Input addFilterType(FilterType type) {
        if (filterTypes.putIfAbsent(type.id(), type) != null && filterTypes.get(type.id()) != type) {
            throw new IllegalArgumentException("A filter type " + type.id() + " exists already");
        }
        bindingsDirty = true;
        return this;
    }

    /** The binding filter type saved as {@code id}, or null. */
    public FilterType filterType(String id) {
        return filterTypes.get(id);
    }

    /** {@code text} (a bindings setting's) as bindings, with the kinds of device and filters present here. */
    @AllowAllocation("parsing bindings")
    public List<Binding> parse(String text) {
        return Binding.parseAll(text, names());
    }

    @AllowAllocation("parsing bindings")
    private Binding.Names names() {
        return new Binding.Names() {
            @Override
            public DeviceClass deviceClass(String prefix) {
                for (DeviceClass type : classes) {
                    if (type.prefix().equals(prefix)) {
                        return type;
                    }
                }
                return null;
            }

            @Override
            public FilterType filterType(String id) {
                return filterTypes.get(id);
            }
        };
    }

    @AllowAllocation("a view")
    private EngineSettings view(int player) {
        if (settings == null) {
            throw new IllegalStateException("Bindings are settings: the engine has not started");
        }
        return player == 0 ? settings : settings.scoped(scope(player));
    }

    @AllowAllocation("a name")
    private static String scope(int player) {
        return "player" + (player + 1);
    }

    /** The bindings {@code player} has for action {@code slot}: their own setting, else everyone's; bad text: defaults. */
    @AllowAllocation("reading bindings")
    private List<Binding> effective(int player, int slot) {
        Setting<String> setting = bindingSetting(slot);
        String text;
        if (settings == null) {
            text = setting.defaultValue();
        } else {
            EngineSettings own = view(player);
            text = player > 0 && own.isSet(setting) ? own.get(setting) : settings.get(setting);
        }
        Action action = actions[slot];
        List<Binding> list;
        try {
            list = parse(text);
        } catch (IllegalArgumentException e) {
            problems.add(setting.name() + ": " + e.getMessage() + "; its defaults are used");
            list = parse(setting.defaultValue());
        }
        List<Binding> valid = new ArrayList<>();
        for (Binding binding : list) {
            try {
                action.check(binding);
                valid.add(binding);
            } catch (IllegalArgumentException e) {
                problems.add(setting.name() + ": " + e.getMessage());
            }
        }
        return valid;
    }

    /** The cycle {@code player}'s bindings would make with {@code bindings} for action {@code slot}, or null. */
    @AllowAllocation("checking bindings")
    private String cycle(int player, int slot, List<Binding> bindings) {
        int[][] sources = new int[actions.length][];
        for (int a = 0; a < actions.length; a++) {
            sources[a] = sourcesOf(a == slot ? bindings : effective(player, a));
        }
        int[] color = new int[actions.length];
        for (int a = 0; a < actions.length; a++) {
            String found = visit(a, sources, color, new ArrayList<>());
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    @AllowAllocation("checking bindings")
    private String visit(int a, int[][] sources, int[] color, List<String> path) {
        if (color[a] == 2) {
            return null;
        }
        path.add(actions[a].fullName());
        if (color[a] == 1) {
            return String.join(" <- ", path);
        }
        color[a] = 1;
        for (int source : sources[a]) {
            String found = visit(source, sources, color, path);
            if (found != null) {
                return found;
            }
        }
        color[a] = 2;
        path.removeLast();
        return null;
    }

    @AllowAllocation("checking bindings")
    private int[] sourcesOf(List<Binding> bindings) {
        return bindings.stream().filter(b -> b.shape() == Binding.Shape.ACTION).map(b -> byName.get(b.source()))
            .filter(Objects::nonNull).mapToInt(this::slot).toArray();
    }

    /** {@code binding} made ready for action {@code slot}, or null if it is inert. */
    @AllowAllocation("bindings change")
    private Bound bound(Binding binding, int slot) {
        if (binding.shape() == Binding.Shape.INERT) {
            return null;
        }
        Control[] inputs = binding.inputArray();
        Control[] modifiers = binding.modifierArray();
        int[] inputClass = new int[inputs.length];
        int[] inputIndex = new int[inputs.length];
        int[] modifierClass = new int[modifiers.length];
        int[] modifierIndex = new int[modifiers.length];
        for (int i = 0; i < inputs.length; i++) {
            inputClass[i] = classIndex(inputs[i].type());
            inputIndex[i] = inputs[i].index();
            if (inputClass[i] < 0) {
                return null;
            }
        }
        for (int i = 0; i < modifiers.length; i++) {
            modifierClass[i] = classIndex(modifiers[i].type());
            modifierIndex[i] = modifiers[i].index();
            if (modifierClass[i] < 0) {
                return null;
            }
        }
        Bound bound = new Bound(binding, slot, actions[slot] instanceof BinaryAction, inputClass, inputIndex,
            modifierClass, modifierIndex);
        if (binding.shape() == Binding.Shape.ACTION) {
            Action source = byName.get(binding.source());
            bound.source = source == null ? -1 : slot(source);
            bound.dims = source == null ? 1 : Math.max(1, Math.min(3, source.dimensions()));
        }
        return bound;
    }

    private int classIndex(DeviceClass type) {
        for (int i = 0; i < classes.length; i++) {
            if (classes[i] == type) {
                return i;
            }
        }
        return -1;
    }

    DeviceClass deviceClass(int index) {
        return classes[index];
    }

    /** Makes every player's bindings ready from the settings: on a change, never per frame without one. */
    @AllowAllocation("bindings change")
    private void compile() {
        bindingsDirty = false;
        for (Player player : players) {
            Bound[][] bounds = new Bound[actions.length][];
            int[][] sources = new int[actions.length][];
            for (int a = 0; a < actions.length; a++) {
                List<Bound> made = new ArrayList<>();
                for (Binding binding : effective(player.index, a)) {
                    Bound bound = bound(binding, a);
                    if (bound != null) {
                        made.add(bound);
                    }
                }
                bounds[a] = made.toArray(new Bound[0]);
                sources[a] = made.stream().filter(b -> b.source >= 0).mapToInt(b -> b.source).toArray();
            }
            player.bounds = bounds;
            player.order = order(player, bounds, sources);
        }
    }

    /** The actions sources first (D80); a binding closing a cycle is cut, and reported. */
    @AllowAllocation("bindings change")
    private int[] order(Player player, Bound[][] bounds, int[][] sources) {
        int[] color = new int[actions.length];
        int[] order = new int[actions.length];
        int[] count = {0};
        for (int a = 0; a < actions.length; a++) {
            place(a, bounds, color, order, count, player);
        }
        return order;
    }

    @AllowAllocation("bindings change")
    private void place(int a, Bound[][] bounds, int[] color, int[] order, int[] count, Player player) {
        if (color[a] != 0) {
            return;
        }
        color[a] = 1;
        for (Bound b : bounds[a]) {
            if (b.source < 0) {
                continue;
            }
            if (color[b.source] == 1) {
                problems.add(actions[a].fullName() + " of " + player + ": the binding " + b.binding
                    + " makes a cycle; it is left out");
                b.source = -1;
                continue;
            }
            place(b.source, bounds, color, order, count, player);
        }
        color[a] = 2;
        order[count[0]++] = a;
    }

    /** Orders the contexts and the actions each reads; works out the mouse and the text context (D61). */
    @AllowAllocation("contexts change")
    private void buildContexts() {
        contextsDirty = false;
        List<InputContext> ordered = new ArrayList<>(stack);
        ordered.sort(Comparator.comparingInt(InputContext::priority).reversed()
            .thenComparing(Comparator.comparingLong((InputContext c) -> c.pushed).reversed()));
        int hidden = ordered.size();
        for (int i = 0; i < ordered.size(); i++) {
            if (ordered.get(i).blocking()) {
                hidden = i;
                break;
            }
        }
        List<List<Integer>> per = new ArrayList<>();
        for (int i = 0; i <= ordered.size(); i++) {
            per.add(new ArrayList<>());
        }
        for (int a = 0; a < actions.length; a++) {
            if (!active(actions[a].group())) {
                continue;
            }
            int at = ordered.size(); // the base, under every context
            for (int i = 0; i < ordered.size(); i++) {
                if (ordered.get(i).covers(actions[a].group())) {
                    at = i;
                    break;
                }
            }
            per.get(at).add(a);
        }
        List<Level> made = new ArrayList<>();
        for (int i = 0; i <= Math.min(hidden, ordered.size()); i++) {
            InputContext context = i < ordered.size() ? ordered.get(i) : null;
            if (i == ordered.size() && hidden < ordered.size()) {
                break; // the base is hidden too
            }
            boolean blocking = context != null && context.blocking();
            made.add(new Level(context, per.get(i).stream().mapToInt(Integer::intValue).toArray(),
                context != null && context.takesText(), blocking));
        }
        levels = made.toArray(new Level[0]);
        MouseMode mode = mouseDefault;
        InputContext text = null;
        for (Level level : levels) {
            if (level.context() != null && level.context().mouse() != MouseMode.INHERIT) {
                mode = level.context().mouse();
                break;
            }
        }
        for (Level level : levels) {
            if (level.text()) {
                text = level.context();
                break;
            }
        }
        if (mode != mouseMode) {
            mouseMode = mode;
            desktop.captureMouse(mode == MouseMode.CAPTURED);
        }
        if (text != textContext) {
            boolean wasText = textContext != null;
            textContext = text;
            if (wasText != (text != null)) {
                desktop.textInput(text != null);
            }
        }
        if (contextsBuilt) {
            resetFilters(); // a context switch (D86)
        }
        contextsBuilt = true;
    }

    void text(String typed) {
        if (textContext != null && textContext.textListener() != null) {
            textContext.textListener().text(typed);
        }
    }

    void editing(String typed, int start, int length) {
        if (textContext != null && textContext.textListener() != null) {
            textContext.textListener().editing(typed, start, length);
        }
    }

    // ---------------------------------------------------------------- players and devices

    /** Player {@code index} (from 0); there is always one. */
    public Player player(int index) {
        return players[index];
    }

    /** How many players there are. */
    public int players() {
        return players.length;
    }

    /**
     * A new local player (D63). From the second player on, each player reads only their own devices: give them some
     * with {@link Player#assign}, or let unassigned devices join by a press ({@link #joinOnPress}).
     */
    @AllowAllocation("a player joins")
    public Player addPlayer() {
        players = Arrays.copyOf(players, players.length + 1);
        Player player = new Player(this, players.length - 1);
        players[players.length - 1] = player;
        sizeTables();
        bindingsDirty = true;
        devicesDirty = true;
        return player;
    }

    /** Removes the last player; their devices are no one's. */
    @AllowAllocation("a player leaves")
    public Input removePlayer() {
        if (players.length == 1) {
            throw new IllegalStateException("There is always one player");
        }
        Player gone = players[players.length - 1];
        for (Device device : devices) {
            if (device.player == gone) {
                device.player = null;
            }
        }
        lost.replaceAll((device, player) -> player == gone ? null : player);
        players = Arrays.copyOf(players, players.length - 1);
        sizeTables();
        devicesDirty = true;
        return this;
    }

    /** Whether a device no one has joins as a new player when pushed (local multiplayer's "press a button to join"). */
    public Input joinOnPress(boolean on) {
        joinOnPress = on;
        return this;
    }

    /** All keyboards, as one device (D72). */
    public Device keyboard() {
        return keyboard;
    }

    /** All mice, as one device. */
    public Device mouseDevice() {
        return mouse;
    }

    /** The devices plugged in. Allocates the list. */
    @AllowAllocation("a snapshot")
    public List<Device> devices() {
        return List.copyOf(devices);
    }

    @AllowAllocation("a device moves")
    void assign(Device device, Player player) {
        device.player = player;
        lost.remove(device);
        devicesDirty = true;
    }

    /** Hears of devices plugged and unplugged (D73). */
    @AllowAllocation("adding a listener")
    public Input listen(InputListener listener) {
        listeners = Arrays.copyOf(listeners, listeners.length + 1);
        listeners[listeners.length - 1] = Objects.requireNonNull(listener, "listener");
        return this;
    }

    /** Adds a kind of device ({@link InputSource#start} does, for its own); bindings naming it work from then on. */
    @AllowAllocation("set up")
    public Input addDeviceClass(DeviceClass type) {
        for (DeviceClass known : classes) {
            if (known == type) {
                return this;
            }
            if (known.prefix().equals(type.prefix())) {
                throw new IllegalArgumentException("A kind of device " + type.prefix() + " exists already");
            }
        }
        classes = Arrays.copyOf(classes, classes.length + 1);
        classes[classes.length - 1] = type;
        for (Player player : players) {
            player.resize(actions.length, classes);
        }
        bindingsDirty = true;
        devicesDirty = true;
        return this;
    }

    /** Adds a plugin's kind of device (D55, D64): it is started now and sampled every frame from the next. */
    @AllowAllocation("set up")
    public Input addSource(InputSource source) {
        sources.add(source);
        source.start(this);
        return this;
    }

    /**
     * A device was plugged in (called by its source): a device lost before and matching by D72 returns to its
     * player (D73); else it goes to the only player, or with several players to the first without a device of its
     * kind, or to no one.
     */
    @AllowAllocation("a device is plugged in")
    public Device connect(DeviceClass type, int platformId, DeviceIdentity identity, String name, InputSource source) {
        Device device = new Device(type, platformId, identity, name, source);
        Device match = null;
        int best = 0;
        for (Map.Entry<Device, Player> entry : lost.entrySet()) {
            Device old = entry.getKey();
            int closeness = old.type() == type ? old.identity().closeness(identity) : 0;
            if (closeness > best) {
                best = closeness;
                match = old;
            }
        }
        devices.add(device);
        devicesDirty = true;
        if (match != null) {
            Player owner = lost.remove(match);
            device.player = owner;
            if (owner != null) {
                for (InputListener listener : listeners) {
                    listener.deviceReturned(device, owner);
                }
                return device;
            }
        }
        device.player = players.length == 1 ? players[0] : firstWithout(type);
        for (InputListener listener : listeners) {
            listener.deviceConnected(device, device.player);
        }
        return device;
    }

    private Player firstWithout(DeviceClass type) {
        for (Player player : players) {
            boolean has = false;
            for (Device device : devices) {
                has |= device.player == player && device.connected && device.type() == type;
            }
            if (!has) {
                return player;
            }
        }
        return null;
    }

    /** A device was unplugged (called by its source): reported, remembered for its player, its filters reset (D86). */
    @AllowAllocation("a device is unplugged")
    public void disconnect(Device device) {
        if (!devices.remove(device)) {
            return;
        }
        device.connected = false;
        device.clear();
        Player owner = device.player;
        lost.put(device, owner);
        devicesDirty = true;
        if (owner != null) {
            owner.resetFilters();
            resetActionFilters(owner.index);
        }
        for (InputListener listener : listeners) {
            listener.deviceLost(device, owner);
        }
    }

    /** Each player's devices, by kind: with one player every device, else their own. */
    @AllowAllocation("devices change")
    private void sortDevices() {
        devicesDirty = false;
        for (Player player : players) {
            Device[][] byClass = new Device[classes.length][];
            for (int c = 0; c < classes.length; c++) {
                List<Device> of = new ArrayList<>();
                for (Device device : devices) {
                    if (device.type() == classes[c] && (players.length == 1 || device.player == player)) {
                        of.add(device);
                    }
                }
                byClass[c] = of.toArray(new Device[0]);
            }
            player.byClass = byClass;
        }
    }

    // ---------------------------------------------------------------- each frame

    /** Samples every device and works out every player's actions, once, at the frame's start (D59, D124). */
    @Override
    public void beforeUpdate(Frame current) {
        frame = current;
        stepping = null;
        boolean nowFocused = engine == null || engine.focused();
        if (nowFocused != focused) {
            focused = nowFocused;
            if (!focused) {
                loseFocus();
            }
        }
        if (bindingsDirty) {
            compile();
        }
        if (devicesDirty) {
            sortDevices();
        }
        if (contextsDirty) {
            buildContexts();
        }
        followClocks();
        for (int i = 0; i < sources.size(); i++) {
            sources.get(i).sample(current);
        }
        long number = current.number();
        for (int i = 0; i < devices.size(); i++) {
            Device device = devices.get(i);
            if (device.latch()) {
                device.activeFrame = number;
                active(device);
            }
        }
        if (bindingsDirty) {
            compile(); // a player joined by a press
        }
        if (devicesDirty) {
            sortDevices();
        }
        System.arraycopy(presses, 0, framePresses, 0, presses.length);
        System.arraycopy(releases, 0, frameReleases, 0, releases.length);
        double now = current.realTime();
        double seconds = current.realDelta();
        for (Player player : players) {
            player.evaluate(levels, now, seconds);
        }
        for (ClockEdges edges : clocks) {
            if (edges.clock.paused()) { // a paused world's steps will see nothing from before it resumed (D60)
                System.arraycopy(presses, 0, edges.markPresses, 0, presses.length);
                System.arraycopy(releases, 0, edges.markReleases, 0, releases.length);
            }
        }
    }

    private void active(Device device) {
        Player owner = players.length == 1 ? players[0] : device.player;
        if (owner == null && joinOnPress) {
            owner = join(device);
        }
        if (owner != null) {
            owner.lastDevice = device;
        }
    }

    @AllowAllocation("a player joins")
    private Player join(Device device) {
        Player player = addPlayer();
        assign(device, player);
        for (InputListener listener : listeners) {
            listener.deviceConnected(device, player);
        }
        return player;
    }

    /** Focus lost: every control let go, the filters reset (D86). */
    private void loseFocus() {
        for (int i = 0; i < devices.size(); i++) {
            devices.get(i).clear();
        }
        resetFilters();
    }

    private void resetFilters() {
        for (Player player : players) {
            player.resetFilters();
        }
        for (Filtered f : filtered) {
            if (f != null) {
                f.reset();
            }
        }
    }

    private void resetActionFilters(int player) {
        int readers = 1 + clocks.length;
        for (Filtered f : filtered) {
            if (f != null) {
                for (int r = 0; r < readers; r++) {
                    int slot = player * readers + r;
                    Arrays.fill(f.state[slot], 0);
                    f.stamp[slot] = Long.MIN_VALUE;
                }
            }
        }
    }

    /** Keeps edges for every world clock from the first frame it exists, so its first step sees what came before. */
    private void followClocks() {
        if (engine == null) {
            return;
        }
        int count = engine.clockCount();
        boolean same = count == clocks.length;
        for (int i = 0; same && i < count; i++) {
            same = clocks[i].clock == engine.clock(i);
        }
        if (!same) {
            matchClocks(count);
        }
    }

    @AllowAllocation("worlds made or removed")
    private void matchClocks(int count) {
        ClockEdges[] matched = new ClockEdges[count];
        for (int i = 0; i < count; i++) {
            Clock clock = engine.clock(i);
            for (ClockEdges edges : clocks) {
                if (edges.clock == clock) {
                    matched[i] = edges;
                }
            }
            if (matched[i] == null) {
                matched[i] = new ClockEdges(clock, presses, releases);
            }
        }
        clocks = matched;
        for (Filtered f : filtered) {
            if (f != null) {
                f.size(players.length * (1 + clocks.length));
            }
        }
    }

    /** Before each fixed step: the edges this world's step sees are those since its last step (D60). */
    @Override
    public void beforeFixedUpdate(Step current) {
        step = current;
        ClockEdges edges = edges(current.clock());
        int[] keepPresses = edges.basePresses;
        int[] keepReleases = edges.baseReleases;
        edges.basePresses = edges.markPresses;
        edges.baseReleases = edges.markReleases;
        edges.markPresses = keepPresses;
        edges.markReleases = keepReleases;
        System.arraycopy(presses, 0, edges.markPresses, 0, presses.length);
        System.arraycopy(releases, 0, edges.markReleases, 0, releases.length);
        stepping = edges;
    }

    private ClockEdges edges(Clock clock) {
        for (ClockEdges edges : clocks) {
            if (edges.clock == clock) {
                return edges;
            }
        }
        return addClock(clock);
    }

    @AllowAllocation("a world's first step")
    private ClockEdges addClock(Clock clock) {
        ClockEdges edges = new ClockEdges(clock, presses, releases);
        clocks = Arrays.copyOf(clocks, clocks.length + 1);
        clocks[clocks.length - 1] = edges;
        for (Filtered f : filtered) {
            if (f != null) {
                f.size(players.length * (1 + clocks.length));
            }
        }
        return edges;
    }

    /** Whether the reader is a fixed step: then edges and filters are that world's. */
    private boolean inStep() {
        return stepping != null && engine != null && engine.phase() == Phase.FIXED;
    }

    void edge(int player, int action, boolean down) {
        int at = player * actions.length + action;
        if (down) {
            presses[at]++;
        } else {
            releases[at]++;
        }
    }

    int presses(int player, int action) {
        int at = player * actions.length + action;
        return presses[at] - (inStep() ? stepping.basePresses[at] : framePresses[at]);
    }

    int releases(int player, int action) {
        int at = player * actions.length + action;
        return releases[at] - (inStep() ? stepping.baseReleases[at] : frameReleases[at]);
    }

    /** Component {@code c} of {@code action}'s value for {@code player}, through the game's filters (D84, D85). */
    float component(Player player, Action action, int c) {
        int a = slot(action);
        if (a < 0 || 3 * a + c >= player.values.length) {
            return 0;
        }
        Filtered f = filtered[a];
        if (f == null) {
            return player.raw(a, c);
        }
        int readers = 1 + clocks.length;
        int reader = 0;
        long stamp = frame == null ? 0 : frame.number();
        double seconds = frame == null ? 0 : frame.delta();
        if (inStep()) {
            for (int i = 0; i < clocks.length; i++) {
                if (clocks[i] == stepping) {
                    reader = 1 + i;
                }
            }
            stamp = step.tick();
            seconds = step.delta();
        }
        int slot = player.index * readers + reader;
        float[] out = f.out[slot];
        if (f.stamp[slot] != stamp) { // once per frame, or per step for a world's reader
            f.stamp[slot] = stamp;
            out[0] = player.raw(a, 0);
            out[1] = player.raw(a, 1);
            out[2] = player.raw(a, 2);
            for (int i = 0; i < f.filters.length; i++) {
                f.filters[i].apply(player, out, f.state[slot], f.stateAt[i], seconds);
            }
        }
        return out[c];
    }

    // ---------------------------------------------------------------- settings

    private void settingChanged(String name) {
        if (name.startsWith("input.") || name.startsWith("player") && name.contains(".input.")) {
            bindingsDirty = true;
        }
        if (name.equals(InputSettings.PRESS_THRESHOLD.name()) || name.equals(InputSettings.RELEASE_THRESHOLD.name())
            || name.equals(InputSettings.HOLD_TIME.name())) {
            readTunables();
        }
    }

    private void readTunables() {
        pressThreshold = settings.get(InputSettings.PRESS_THRESHOLD);
        releaseThreshold = Math.min(pressThreshold, settings.get(InputSettings.RELEASE_THRESHOLD));
        holdTime = settings.get(InputSettings.HOLD_TIME);
    }

    float pressThreshold() {
        return pressThreshold;
    }

    float releaseThreshold() {
        return releaseThreshold;
    }

    float holdTime() {
        return holdTime;
    }

    /** What receives the desktop's events (attached to the desktop when it is installed); tests feed it directly. */
    public PlatformListener events() {
        return desktop;
    }

    DesktopSource desktopSource() {
        return desktop;
    }

    Device gamepadOrJoystick(DeviceClass type, int platformId) {
        for (int i = 0; i < devices.size(); i++) {
            Device device = devices.get(i);
            if (device.type() == type && device.platformId() == platformId) {
                return device;
            }
        }
        return null;
    }
}
