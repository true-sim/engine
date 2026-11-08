# engine

The true-sim engine: windows, input and Vulkan rendering on [LWJGL](https://www.lwjgl.org/) 3.4 with SDL3.
Multi-window, multi-GPU and multi-threaded from the start; per-frame paths allocate nothing.

Public scene types (meshes, materials, geometries, scene management) live in a separate repository, so mods
and tools can use them without the engine. Shaders are written in [Slang](https://shader-slang.org/).

| Module            | Package                       | What                                                    |
|-------------------|-------------------------------|---------------------------------------------------------|
| `engine-settings` | `com.truesim.engine.settings` | Settings: declared once by the code using them, values set, saved and loaded; no SDL |
| `engine-app`      | `com.truesim.engine.app`      | The application core: lifecycle, the engine-owned loop, plugins and their startup stages |
| `engine-platform` | `com.truesim.engine.platform` | SDL3: displays, windows, runtime mode changes, all input devices |
| `engine-input`    | `com.truesim.engine.input`    | Input actions: groups, bindings saved as settings, players, contexts, chords, filters |
| `engine-vulkan`   | `com.truesim.engine.vulkan`   | Vulkan 1.3, 1.4 where available: instance, GPUs, devices, swapchains, frames |
| `engine-graph`    | `com.truesim.engine.graph`    | Frame graph: techniques plugged together by the results they provide and need |
| `engine-render`   | `com.truesim.engine.render`   | GPU-driven meshlet rendering of scene's meshes: geometry pool, GPU culling and LOD; blur |
| `engine-scene`    | `com.truesim.engine.scene`    | Draws scenes: scene's renderables and assets onto renderers, one view per scene and camera |
| `engine-audio`    | `com.truesim.engine.audio`    | Sound: the mixer on an allocation-free audio thread, SDL3 output, offline rendering |
| `engine-xr`       | `com.truesim.engine.xr`       | VR headsets through OpenXR: their eyes as frame graph views, a desktop mirror |
| `engine-ui`       | `com.truesim.engine.ui`       | The ui toolkit's engine side: its renderer (`Keys.UI`), the `ui` actions as UI commands, the mouse pointer, the profiler's views |

## Application

A game implements `Application` (every method has a default) and hands it to `TrueSim.run`, which owns the loop;
tools and tests drive `Engine.create(app, args)` and `step()` themselves. The bare application installs nothing: parts
are plugins, installed explicitly (`app.use(...)`) and reached by typed keys (`engine.get(Xr.KEY)`, or `find` for
optional ones).

```java
public final class Hello implements Application {
    public static void main(String[] args) { TrueSim.run(new Hello(), args); }
    public void configure(AppConfig app) { app.id("hello").use(new Rendering(), Xr.ifAvailable()).saveSettings(); }
    public void update(Frame frame) { ... }
}
```

- **Startup stages:** every plugin starts through `BEFORE_INSTANCE`, `INSTANCE`, `GPU`, `DEVICE`, `GRAPH`, `RUN`, each
  after the plugins it `requires()`. A plugin that cannot start throws a `StartupFailure` with a reason for players;
  `onStartupFailure` answers retry, continue without it (optional plugins: no headset, play on the desktop) or quit.
- **One frame:** the frame driver waits (a plugin may claim it, e.g. XR pacing; else frames start at
  `app.frameRate`), finished jobs are delivered, plugins' `beforeUpdate` (input), then the phases (`engine.phase()`):
  `FIXED` (each world's fixed steps: the game's `fixedUpdate(Step)`, plugins' `fixedUpdate`, the clock's after-step
  hooks), `UPDATE` (the game's `update`), `ANIMATE` (plugins' `animate`), `LATE` (the game's and plugins'
  `lateUpdate`); then plugins' `afterUpdate` (scene sync), settings availability refreshed, the driver ends the frame
  (render). Nothing allocated of its own. `stepAt(nanos)` runs a frame at a time a tool or test keeps.
- **Clocks:** `Frame` has real time (`realDelta`, `realTime`) and the app clock (`delta`, `time`: each frame's delta
  cut to `app.maxFrameDelta`, 0.25 s unless set; `capped()` says when). Each world has a `Clock`
  (`engine.addClock(name)`, stepped in the order made) at `app.fixedRate` steps a second (60 unless set, followed from
  the next frame) unless given `rate(n)`: it is owed each frame's delta as an integer of nanoseconds times its rate, so
  steps are whole ticks and never drift; `blend()` is how far it is into its next tick, `frameDelta()` how far its
  drawn time moved. All worlds share one budget of steps per frame (`app.fixedStepBudget`, 8); ticks past it are
  dropped and reported (`Frame.droppedTicks()`, `Clock.droppedTicks()`). The three are developer settings, read every
  frame. Timers on a clock (`after`, `every`, `afterSeconds`, `everySeconds`) count steps and fire inside their step,
  before the after-step hooks. `clock.afterStep(scene::update)` propagates a scene's world transforms after every step
  (or `sceneSync.follow`).
- **`Animation`** (engine-scene): each world's `Animators` (`animation.animators(scene, clock)`), run in `ANIMATE`:
  sampled in parallel on the plugin's threads (`animation.threads`: every processor unless set, read when the first
  world is made; results do not depend on them), poses written and events dispatched in order, then the scene's world
  transforms updated, so `lateUpdate` reads this frame's joints. A world on a clock moves on by its drawn time;
  `advance(seconds)` for frames recorded without an engine. Ground probes of foot placement run where animators sample
  (on the threads): a `Ground` must be thread-safe.
- **Quitting:** close buttons and the system request a quit; `onQuitRequested` allows or defers it (save first?), and a
  second request within 3 seconds or a forced one skips it. `engine.quit()` is the game's own. Shutdown stops the game,
  then the plugins in reverse order, and saves the player's settings (`saveSettings()`: the OS user folder for the
  app id).
- **Focus, visibility, suspend** reach the game as events; nothing pauses by itself.
- **The engine's plugins:** `Desktop` (SDL: windows, input; the last window closing requests a quit), `Rendering`
  (Vulkan instance, device, frame graph on the engine's settings, presenter pacing frames; other plugins add instance
  and device extensions or pick the GPU in the stage before it acts) and `Xr` (a headset: connects first, picks the
  GPU, starts its session with the graph and drives frames, so the game's update reads this frame's poses), `Input`
  (actions, after the desktop; see Input). The demo is an `Application` on them, with `Animation` running the fox's
  and the crowd's animators.
- **`Assets`** (engine-scene): the asset cache on the engine's jobs, path-addressed assets on it (`paths()`: mounts
  and importers; `mount("game", folder)` before startup) and their GPU side (`resources()`). Nothing is imported by
  default: add importers, e.g. `new Gltf(assets.paths()).register()` from scene-gltf. `assets.budgetMegabytes` is a
  developer setting. `Rendering.headless(true)` runs without windows, for servers and tests.


One `Platform` per process, used from the thread that started it. Events go to a `PlatformListener` as engine
types and plain values, with nothing allocated. Nothing in the public API is SDL's: keys are `Key` (physical, USB HID
based) with a layout character and `KeyModifiers` bits, and there are `MouseButton`, `GamepadButton`, `GamepadAxis`,
`HatPosition`, `SensorType`, `TouchPhase`, `PenAxis` and so on. The mapping lives in one package-private class, so
another backend replaces only the implementation. The input module builds on this (see Input); a gamepad's or
joystick's `InputDevice` carries SDL's GUID, its serial and path, which tell it again when it is plugged back in.

Everything SDL3 supports for input is passed through:

- **Keyboards, mice, text:** physical keys, layout characters, modifiers, buttons, wheel, text input.
- **Gamepads** (`platform.gamepad(id)`):
  - buttons, axes, the button labels and pad type;
  - touchpads and motion sensors (gyro, accelerometer);
  - rumble, trigger rumble, RGB LED, player LEDs, raw device effects such as DualSense triggers;
  - battery and connection state, serial and firmware;
  - SDL_GameControllerDB mappings.
- **Joysticks** (`platform.joystick(id)`): wheels, flight sticks, throttles and the like, with axes, buttons, hats
  and trackballs, plus the same outputs as gamepads.
- **Force feedback** (`controller.haptic()`, `platform.openHaptic(id)`, `platform.mouseHaptic()`):
  - `HapticEffect`s: constant, periodic waves, spring, damper, inertia, friction, ramp, left/right rumble, custom;
  - uploaded, run, updated live and stopped;
  - gain, autocenter, pause.
- **Other devices:** a device's own motion sensors (`platform.openSensor(id)`), touchscreens and trackpads, and
  pens (proximity, tip, eraser, barrel buttons, pressure, tilt, rotation).

```java
Gamepad pad = platform.gamepad(id);
pad.rumble(0.3f, 0.8f, 200);
pad.setLed(255, 0, 0);
pad.setSensorEnabled(SensorType.GYROSCOPE, true);   // gamepadSensor events follow

Haptic wheel = platform.joystick(wheelId).haptic();
int spring = wheel.create(new HapticEffect.Condition(ConditionKind.SPRING, Timing.of(HapticEffect.INFINITE),
    List.of(new ConditionAxis(1, 1, 0.6f, 0.6f, 0, 0))));
wheel.run(spring, 1);
```

```java
try (Platform platform = Platform.start()) {
    Window window = platform.createWindow(WindowOptions.of("true-sim", 1280, 720));
    for (Display display : platform.displays()) { ... }        // modes, scale, bounds
    for (InputDevice device : platform.inputDevices()) { ... } // keyboards, mice, gamepads, joysticks

    window.setSize(1920, 1080);                 // all changeable at runtime
    window.borderlessFullscreen();
    window.exclusiveFullscreen(display.fullscreenModes().getFirst());
    window.windowed();

    while (running) {
        platform.pollEvents(listener);          // windowResized, key, gamepadAxis, touch, penAxis, deviceAdded, ...
    }
}
```

**Engine settings** (`com.truesim.engine.settings`): every engine setting is a declared `Setting` (name, kind,
default, range, description, live or not), declared by the module that uses it, next to that code. `EngineSettings`
holds the values in layers, lowest first: the setting's default, the game's (`set(Layer.GAME, ...)`), the player's
(`set`), the command line (`commandLine(args)`, `--set name=value`). `preferred` is the highest layer's value; `get`,
what the engine uses, is that value through the `Constraint`s on it (`constrain(setting, Constraint.lock(false, "no
HDR display"))`, `range`, `choices`), so hardware and game limits never overwrite the player's choice. Change
listeners, scoped views (one window's own values; `inheriting(scope)`: a scoped view reading its parent's value of
every setting it holds none of, so a window's own value overrides one it otherwise follows), and saving and loading
as text (`toMap`/`load`: only the player's layer is saved; unknown keys are kept, unreadable ones fall through, values are parsed when first read). A `SettingRegistry`
lists the settings an app has, each registered once by the part owning it, with a `SettingGroup` (a tree of groups
with translation-key labels and an order, apart from saved names) and optionally a `SettingTier`. Options screens ask
it for groups and their settings up to a tier; hidden tiers show only when revealed; `check()` at startup fails on a
setting with a tier but no group, an undeclared tier or two groups with one id; settings players never see are
registered with `addHidden`. Each setting has an `Availability` for options screens to grey it out with a reason:
unsupported (rules the owning part adds with `availableWhen`), depends on another setting (`dependsOn`), locked by the
game (`lock`, a constraint), or pending a restart (a setting that is not live, changed since `applied`). Availability
listeners hear of changes when the app calls `refreshAvailability()` at a frame boundary, which does nothing unless a
value, rule or lock changed or `invalidateAvailability()` was called (a device or display came or went). The frame graph has settings
(`graph.settings()`): a build reads them with `g.setting(key)` and rebuilds when one it read changes; pass bodies read
`g.settings()` when they record. Windows follow theirs (`platform.createWindow(options, settings.scoped("window.main"))`),
and so does the `Presenter` (the graph's present settings, or a window's own); a target's output transform reads the
graph's settings or a view it is given (`Target.displaySettings(view)`: a window's own paper white, say). The core's
tuning values are settings too, read from the graph's settings unless a technique is given its own: light clusters (`ClusteredLights.ALL`:
tiles, slices, range, lights per cluster, and the irradiance where lights without a range end, `Lights.NO_RANGE`),
occlusion culling (`RenderSettings.OCCLUSION`), cascades and ray offsets (`ShadowSettings.ALL`), and the display
transform (`DisplayTransform.ALL`). No hard list holds them all: `RenderPlugin` registers the renderer's settings and
slots in the engine's `SettingRegistry`, a library's plugin registers its own the same way, and consoles and settings
screens discover every one with `registry.all()` or `registry.all("render")` (a slot's kinds' tunables included,
a mod's too).

**Techniques and their tunables** (`TechniqueSlot`, `TechniqueKind`, `Techniques`): every switchable technique is a
slot, a place a technique plugs into, whose one setting holds the chosen kind's id or `off` (`render.ao=bitmask`). A
kind is an id, a record of its tunables (`TechniqueConfig`, each component annotated with `@Tunable`: description,
range, former names) with its defaults, and a factory making the technique from what the slot's owner hands it and
its live config. Each component is an ordinary setting named by its dotted path (`render.ao.bitmask.radius`): set,
saved, loaded, given on the command line (`--set render.ao.bitmask.radius=1`) and read alone like any other, and read
together as the record (`BITMASK.config(settings)`, the same record until a value changes: nothing allocated per
frame). The kinds a store offers are its registry (`settings.techniques()`): a slot registers the engine's kinds when
first used, and games and mods register theirs through the same `register(slot, kind)`, so a game's own ambient
occlusion is chosen like the engine's (`docs/custom-techniques.md`). `variants(slot)` lists a slot's kinds for an
options screen, `find(path)` a tunable for a console. A saved id no kind has (a mod removed) reads as the slot's default
kind, logged once and listed in `problems()`; the id stays saved, so the kind is chosen again once its mod is back.
Ids match ignoring case, so files saved with the enum-valued settings before (`render.ao=BITMASK`, `OFF`) load, and
tunables read the flat names they had (`render.ao.radius`, `render.ssr.maxSteps`, `audio.hrtf.voices`) where their
own name holds nothing. A frame graph build reads a slot with `g.technique(slot)` and rebuilds when the choice
changes; a slot's owner builds the chosen kind's technique in its place, so swapping kinds is the rebuild it always
was. The slots: `render.ao`, `render.ssr`, `render.gi`, `render.transparency` (never off), `render.gi.lights` (never
off), `render.interpolation`, `display.toneCurve` and `audio.spatializer`. The first four and the spatializer take
games' own techniques; the world light grid's layouts, the interpolations and the tone curves are the shaders' or the
sync's, so a game's kind there makes one of the engine's with tunables of its own.

```java
AmbientOcclusion.SLOT.choose(settings, AmbientOcclusion.GTAO);                // render.ao=gtao
settings.set(AmbientOcclusion.GTAO.setting("radius"), 1f);                     // render.ao.gtao.radius=1.0
settings.techniques().set(settings, AmbientOcclusion.SLOT, "radius", 1f);      // in every kind having one
settings.techniques().register(AmbientOcclusion.SLOT, AcmeAo.KIND);            // a game's own
for (TechniqueKind<?, ?, ?> kind : settings.techniques().variants(AmbientOcclusion.SLOT)) { ... }
```

```java
EngineSettings settings = new EngineSettings().load(saved);
graph.settings(settings);
Window window = platform.createWindow(WindowOptions.of("true-sim", 1280, 720), settings.scoped("window.main"));
settings.scoped("window.main").set(WindowSettings.MODE, WindowMode.BORDERLESS_FULLSCREEN); // applied at once
store(settings.toMap());
```

**Window settings** (`WindowSettings`): every preference the platform can set on a window, as one value to save and
load: mode, display, windowed size and position, the exclusive fullscreen mode, maximized, resizable, borders, always
on top, focusable, opacity, size limits, aspect ratio, mouse and keyboard grab, relative mouse, cursor, keeping the
screen on. `window.apply(settings)` changes a live window as far as the system allows, and `window.settings()` reads
back what it is (its windowed size kept while fullscreen). `toMap()` and `fromMap(...)` save and load them as text:
unknown keys are ignored and missing or out-of-range ones default, so settings saved by another version still load.
Displays are named by `platform.displayKey(id)`: the monitor's name, numbered left to right when identical monitors
share it. Beyond settings, a window can flash for attention, show progress on its task bar entry, take an icon, and
be a (modal) child of another. SDL stays inside `engine-platform`: no SDL type or handle is part of its API, and
Vulkan surfaces come from `window.createVulkanSurface(instance)`.

```java
WindowSettings saved = WindowSettings.fromMap(load("window"));
Window window = platform.createWindow(WindowOptions.of("true-sim", 1280, 720), saved); // applied before it shows
window.apply(window.settings().withMode(WindowMode.BORDERLESS_FULLSCREEN).withDisplay(platform.displayKey(id)));
store("window", window.settings().toMap());
```

## Input

`engine-input` turns devices into actions (application-api.md §5). `app.use(new Input().add(GAME))` installs it; it
starts after the desktop and listens to its events, and other plugins bring kinds of device (`Xr`: the headset's
controllers). A frame starts with every device's values taken once and every player's actions worked out, before the
fixed steps: an action is read in the frame it was sampled in, with nothing allocated.

```java
public static final ActionGroup GAME = ActionGroup.root("game");
public static final ActionGroup VEHICLE = GAME.child("vehicle");          // a tree: on only if its parents are
public static final BinaryAction JUMP = GAME.binary("jump", Binding.key(Key.SPACE), Binding.pad(GamepadButton.SOUTH));
public static final Axis2Action MOVE = GAME.axis2("move", Binding.keys(Key.A, Key.D, Key.S, Key.W), Binding.stick(false));
public static final BinaryAction SAVE = GAME.binary("save", Binding.chord(Controls.CONTROL, Controls.key(Key.S)));

Player one = input.player(0);
if (one.pressed(JUMP)) { ... }          // in update: since the last frame; in a fixed step: since that world's last step
walk(one.x(MOVE), one.y(MOVE));
input.push(input.context("menu", 100).groups(UI).mouse(MouseMode.RELEASED).blocking(true));
Capture capture = input.capture(one, JUMP);   // the next input becomes a binding; input.rebind(one, JUMP, 0, ...)
```

- **Actions** are typed constants in groups: `BinaryAction` (down or not, no analog value), `Axis1Action`,
  `Axis2Action`, `Axis3Action`, `PoseAction`. Groups form a tree, each with its own switch (`input.enable(group, on)`).
  A module adds its group with its defaults in one call (`input.add(UI)`).
- **Bindings are settings**, one text setting per action (`input.game.jump=key:SPACE; pad:SOUTH`), registered in the
  options screen's input group and saved with the player's settings; the game's defaults go on the game layer
  (`input.defaults`), a later player's own under `player<n>.`. A binding's text names generic controls (`key:W`,
  `pad:SOUTH`, `mouse:X`, `joy:AXIS_2`, `xr:RIGHT_TRIGGER`), adapters (`axis(key:S,key:W)`, `2d(...)`, `3d(...)`,
  `action(game.move)`), modifiers (`key:CONTROL+key:S`, left, right or either; any pad button), `toggle`, `tap`,
  `hold(0.5)`, a filter chain by id with parameters (`deadzone`, `radial`, `scale`, `axes`, `curve`, `smooth`,
  `clamp`, and the game's own) and, only when a player captured it, the device (`device(guid,serial,path)`). Text it
  cannot read falls back to the defaults and is reported (`input.problems()`).
- **Edges** are counted per player and action. A fixed step sees the presses and releases since its world's last
  step: once however many steps a catch-up frame runs, and in the next step when the frame had none; a paused world
  (`clock.pause()`) sees none from while it was paused.
- **Chords:** a binary action's chord, held, takes its controls from less specific bindings (Ctrl+S held: S does not
  walk back, Ctrl does not crouch); a modifier on an analog binding only gates it. A press fires at once; only a
  binding marked tap or hold waits (`input.holdTime`, 0.25 s). Analog controls turn binary at `input.pressThreshold`
  (0.6) and back at `input.releaseThreshold` (0.4). Several bindings combine by the largest value, or `SUM`/`FIRST`.
- **Contexts** form a stack by priority: an action is read in the highest context covering its group, the controls
  it binds are taken from those under it, a blocking context hides them, a context taking text leaves them no keys
  and receives the text; the top context saying so captures or releases the mouse.
- **Players:** one player reads every device; with more, each reads their own (`player.assign(device)`, the first
  without a pad gets a new one, `joinOnPress`). An unplugged device is reported (`InputListener`) and, plugged back,
  returns to its player, matched by GUID, then serial, then path. `player.rumble(...)`, `player.glyph(action)` (the
  binding for the last device used), `player.lastDevice()`; devices stay readable directly (`input.keyboard()`).
- **Filters** keep their state per player and binding; game filters on an action (`input.filter(action, filter)`, not
  saved) run once per fixed step on the step's delta, or once per frame. Focus loss, a context switch and a disconnect
  reset them.
- **Capture** listens for a player's next input: a key released alone, a key held while another goes down (a chord),
  an axis pushed (a stick's pair for a 2D action); a pad's device is recorded with it, Escape cancels.
  `input.conflicts(player, action, binding)` lists other actions on the same controls.
- **Cost:** 200 actions for 4 players, events every frame: about 40 to 60 us a frame on a Ryzen 9 9950X3D under load
  from other work, without JaCoCo (`InputBenchmarkTest`, 2026-11-03), 0 bytes allocated; a fixed step readying its
  edges, well under a microsecond.

## Scene interaction and selection

Scene nodes take pointers as UI elements do, without the UI toolkit (application-api.md, "Scene interaction and
selection"). Picking is scene data (scene-core's `Pickable`, `Selected`, `Hovered` and `ScenePicker`); the
`SceneInteraction` plugin (engine-interaction) runs it each frame, at the frame's start, for every pointer (mouse,
fingers, pen, a gamepad's virtual cursor, and any `ScenePointerSource`: engine-xr's `XrSceneRays` adds XR rays)
through this frame's camera, and routes the events:

```java
SceneInteraction interaction = engine.get(SceneInteraction.KEY);
InteractionWorld world = interaction.world(scene, index);
world.view(camera).port(mainView);                                    // or world.view(cameraNode): D129
world.picker().resolver(new RenderablePicking(scene, cache::get));   // Pickable.mesh(): the renderable's triangles
long[] car = model.spawn(scene, scene.create());                      // glTF "truesim.pickable": pickable on spawn
world.on(car[0], SceneEventType.CLICK, e -> honk(e.currentNode()));   // the parts' clicks bubble to the root
interaction.selection().enabled(true);                                // click, Shift, Ctrl, marquee, Ctrl+A, Escape
port.add(new SelectionOutline(graph, geometry, view.view(), view, depthFormat, highlights));
```

- **Events:** enter, exit, move, press, release, click with counts, long press, context, wheel, drag start, drag,
  drag end, drop enter, over, leave and drop (payloads between nodes), focus in and out; capture, target and bubble
  phases along the scene graph, `consume()`, pass-through along the ray (press, wheel, drop over), pointer capture.
  Queries: `world.hovered(node)`, `world.pressed(node)`, each pointer's hits, hit point, distance and triangle.
- **Input:** buttons and the selection's modifiers and commands are the `scene` actions (`SceneActions`), rebound and
  saved like any; positions come from the platform's events. Tunables are settings (`scene.interaction.*`: long press
  delay, double click time and distance, drag thresholds in pixels and for rays in degrees, pass-through depth, ray
  lengths, the virtual cursor).
- **With the UI:** the UI plugin runs first (screen layers, gizmo handles, world panels: `ScenePicking.claimed`) and
  hands the scene the pointers it did not take, with its own rays, so each ray is cast once a frame (the picker keeps
  a frame's picks by ray). Tooltips, context menus and drag and drop between the UI and the world stay the UI's
  (`Interactive`); the gizmo follows the selection (`UiScene.followSelection`).
- **Highlight:** `render.selection` chooses `outline` (default), `tint` or `off`; engine-scene's `SceneHighlights`
  marks the selected and hovered objects' instances (subtrees included) for `SelectionOutline`, which masks them from
  the visibility buffer's IDs (forward views draw IDs of their own while the slot is on).
- **Cameras and lights as components:** `CameraSync` sets a renderer camera from a camera node's world transform and
  `CameraLens`; `LightSync` places `LightSource` components in `Lights`, following moves and changes.
- **Mobility as a hint:** `SceneSync` gives each instance its node's `Mobility` (`GpuScene.setMobility`, CPU only:
  nothing uploads; changes followed from the scene's log) and `LightSync` each light its node's (`Lights.mobility`).
  Shadow maps cache by it (see Shadows: mobility); an instance or light made outside the scene has none and is
  detected.

Measured (2026-11-04, RX 9070 XT, Ryzen 9 9950X3D; `ScenePickingBenchmark`, `SelectionOutlineTest` with
`TRUESIM_BENCHMARK=true`):

| Picking, 100,000 nodes (half moving every frame), one pointer | interaction's frame, median (p99) | raycast alone | allocated |
|---|---|---|---|
| 1% pickable, boxes | 2.1 us (27.5) | 0.7 us (4.5) | 0 B |
| 1% pickable, meshes of 960 triangles | 2.4 us (17.1) | 0.7 us (3.8) | 0 B |
| all pickable, boxes | 3.1 us (25.4) | 1.7 us (13.2) | 20 B on average (the hover store's log growing to its cap) |
| all pickable, meshes | 3.5 us (59.8) | 1.5 us (39.7) | 20 B on average |

| Outline, 30,000 rocks: GPU frame (mask + outline passes) | 1920 x 1080 | 3840 x 2160 |
|---|---|---|
| forward: slot off / outline, nothing highlighted | 1.127 / 1.136 ms | 2.505 / 2.497 ms |
| forward, outline, 1 highlighted | 1.182 ms (0.007 + 0.038) | 2.666 ms (0.023 + 0.145) |
| forward, outline, 300 highlighted | 1.194 ms (0.007 + 0.038) | 2.690 ms (0.025 + 0.145) |
| forward, outline, 3,000 highlighted | 1.245 ms (0.009 + 0.038) | 2.824 ms (0.034 + 0.144) |
| forward, tint, 3,000 highlighted | 1.213 ms | 2.702 ms |
| visibility buffer: slot off / outline, nothing highlighted | 0.890 / 0.891 ms | 2.195 / 2.194 ms |
| visibility buffer, outline, 1 / 300 / 3,000 highlighted | 0.936 / 0.937 / 0.937 ms | 2.380 / 2.377 / 2.373 ms |
| visibility buffer, tint, 3,000 highlighted | 0.905 ms | 2.252 ms |

A forward view's own ID draw draws the highlighted instances alone: its render view filters instances on the GPU by
the highlight flags (`RenderView.highlightedOnly`, the culling's `VIEW_HIGHLIGHTED_ONLY`), and the mask keeps only
the pixels where that draw's depth is the scene's (what the view shows), so its cost follows what is highlighted. The
passes stay built while the slot is on (a hover starting and ending must not rebuild the frame graph); with nothing
highlighted the ID draw finds nothing to draw and the mask and outline do nothing (forward: +0.009 ms at 1080p).

## UI

`engine-ui` is the engine's side of the [ui](https://github.com/true-sim/ui) toolkit (its design: ui's
`docs/ui.md`): it implements the interfaces ui-core declares and never the other way round.

- **Renderer** (`GpuUiRenderer`, ui-core's `UiRenderer`): a frame graph technique providing a view's `Keys.UI`
  image (premultiplied, linear, 1.0 paper white, at the view's output size), which `DisplayTransform` composites after
  the tone curve. Each frame, after the UI recorded and before the frame records, `submit(layers, count, textures,
  width, height)` copies only what changed: a draw list's dirty range and its clips, a texture's dirty region; an
  unchanged frame uploads 0 bytes (`uploadedBytes()`). One draw per layer, without vertex buffers, pulls six vertices per
  primitive (rounded boxes with borders and gradients, blurred shadows, MTSDF glyphs, images, nine-slices, lines,
  triangles, clipped by rounded rectangles; a triangle anti-aliases the edges its flags name and keeps the others hard
  and half-open, so a shape's triangles share edges without seams); long runs of one kind (text floods) get their kind's own shader (`Batching.ADAPTIVE`,
  chosen by `UiBenchmark` over one draw per layer and a draw per run of a kind). A chart's polylines (`Primitive.PATH`:
  miter, bevel or round joins, caps, dashes along the path, each pixel drawn once where segments overlap) and min-max
  envelopes (`Primitive.COLUMNS`, stroked or filled) read the layer's vertex table, uploaded as its dirty ranges (a
  stream uploads the points it appended), each a draw of its own expanding a quad per segment, column or piece in the
  vertex shader. A box covering the image at the bottom of the UI (a console's backdrop, a full-screen panel) is
  cleared to instead of blended over every pixel; its rounded corners and border are drawn along its edges without
  blending (`foldBackdrop`). Square boxes drawn right after it on whole pixels (a chart's plot background over its
  panel), opaque or over the clear alone, are cleared as rectangles to what drawing them leaves
  (`vkCmdClearAttachments`, `clearedBoxes()`).
- **Input** (`UiActions`, `UiCommands`, `MousePointer`): the `ui` action group of design section 10 with keyboard,
  mouse and gamepad defaults, bindings as settings (`input.ui.navigation.accept`); `UiCommands` (a
  `UiCommandSource`) adds each player's presses of the frame as `UiCommand`s, with repeats (`ui.repeatDelay`,
  `ui.repeatRate`), `EXTEND_SELECTION` while Shift is held, text commands while a field took the keyboard
  (`startText`/`stopText`), and the gamepad's confirm button as a setting (`ui.confirmButton`: the platform's, south
  or east). The tabs', chart reset's, gizmo's, docking's (`ui.docking.windowMove` Alt+F7, `windowResize` Alt+F8) and
  console's actions give their commands; tabs, windows and the console's toggle (grave, both sticks, an XR menu
  button held) work while a field types, and the toggle's key types nothing; Tab, Up and Down complete and walk the
  history only while the console's command line has the text (`commands.console(...)`). `MousePointer` (a
  `PointerSource`) reads the mouse from the platform's events directly; `UiKeyboard` gives mnemonics (Alt with a
  letter or digit) and pointers' modifiers (the gizmo's `ui.gizmo.snap` as `Pointer.GIZMO_SNAP`); `TouchPointers` (a
  pointer per finger), `PenPointer`, `XrPointers` (aim poses as world rays, triggers as the primary button) and a
  gamepad's `VirtualCursor` (`ui.virtualCursor`, off by default) are pointers too.
- **Gizmo actions** (`ui.gizmo`): every gizmo operation is a rebindable action. Modes `translate` W, `rotate` E,
  `scale` R, `bounds` B, `universal` T, `nextMode` (no key; XR right A); `toggleSpace` X (XR left X),
  `previousSpace` Shift+X; `nextPivot` period (Blender's pivot key; XR left Y), `previousPivot` Shift+period; `snap`
  Ctrl, LT, an XR thumbstick pressed (rays and grabs: `XrPointers` adds it to the rays' modifiers); `cancelDrag`
  Escape, RMB, east. The keys give nothing while a field types. The d-pad switches modes (up translate, right rotate,
  down scale, left universal) only while a gizmo is focused (`commands.gizmo(() -> focused)`), and then does not
  move focus; otherwise it navigates. XR grabs: `grabLeft`/`grabRight` (the grips; both held: two-handed scale and
  turn) from the poses `handLeft`/`handRight` (grip poses), driven by `XrGizmoGrab` (`plugin.gizmoGrab(grab)` updates
  ui-gizmo's `GizmoGrab` each frame after the UI's input; `cancelDrag` cancels a grab, which waits for the grip to be
  let go). The XR defaults come with `XrPointers.defaults`.
- **Eyedropper** (`GpuScreenSampler`, ui-core's `ScreenSampler`): a block of up to 31 x 31 texels around the pointer
  copied from the view's scene color (before the display transform, HDR kept) and read back without a wait; each
  block names the copy it is, so the newest answer arrives frames in flight later and never a stale one
  (`UiPlugin.screenSampler()`, for `Controls.screenSampler`).
- **Tunables** (`UiTunables`): ui-core's `Tunable`s as settings of the same name and range, written back each frame:
  `UiPlugin.tunables(ui)` lists the UI's (with the charts', scene bridge's and gizmos' through their `TunableSource`
  services) and the console's.
- **OS windows** (`UiOsWindows`, ui-controls' `OsWindows` for docking's `OsDockHost`): a window per dock area, each
  presenting a view of its own that draws its region of the one UI. Each window's target reads a view of the graph's
  settings of its own (`displaySettings(id)`, `EngineSettings.inheriting`): `paperWhite(id, nits)` gives it a paper
  white (the main window too; NaN follows `display.paperWhite` again), which the dock layout saves; a window
  following a drag is see-through by SDL's window opacity (`opacity(id, value)`, the theme's `drag-opacity`) where
  the system allows it, opaque elsewhere (Wayland, the offscreen driver).
- **Scene bridge** (`UiPlugin.scene(graph, index)` → `UiScene`, ui-scene's `ScenePicking`): the UI runs through
  scene picking; each frame starts with the scene and its `SpatialIndex` updated and the main `SceneView` set from the
  engine's `Camera` (`UiScene.camera`: its position in doubles, the rotation, field of view and near plane `Camera`
  now keeps), then the UI's input, so this frame's pointers pick what this frame's camera sees. `PickMeshes` makes a
  `PickMesh` per mesh asset from its leaf meshlets; `ui.scene.far` is how far screen rays reach.
- **Camera views** (`GpuCameraView`, a `CameraViewSource`): a second camera as a view of the same graph, rendered at
  the control's pixel size, tone mapped by the display transform and copied into the top left of a graph `Texture`
  that `GpuUiRenderer.bind(id, texture)` draws for the control's UI texture (no upload; the UI pass reads the texture
  after the view's copy, so the UI shows this frame's image and the main view waits for nothing).
- **World panels** (`WorldPanelRenderer`, per panel): `TEXTURE` renders the panel's UI at the frame's start into an
  image with a bindless slot a material samples (lit and fogged like the scene), again only when its UI changed;
  `DIRECT` draws its primitives after the opaque scene through a panel-to-clip matrix made in doubles relative to the
  camera, depth tested, MTSDF coverage worked out in the panel's pixels. Made with a camera per eye
  (`new WorldPanelRenderer(graph, left, right)`) in a view whose scene colour and depth have a layer per eye, it
  draws both eyes in one multiview pass (a matrix per eye, `SV_ViewID`); the frame graph opens a pass attaching every
  layer of its images as a multiview rendering.
- **3D gizmos** (`GizmoRenderer`, XR): each gizmo's handles as ui-gizmo's `GizmoMesh` (true geometry in world units
  about the gizmo's origin, sized by angular size from the head), drawn after the opaque scene twice with
  complementary hardware depth tests against the scene's depth (where the scene is in front at the `gizmo-occluded`
  opacity, else at full), back faces culled; one camera or one per eye (multiview), matrices made in doubles
  relative to each camera. `ui.gizmo.meshSides` sets the tessellation.
- **Gizmo occlusion** (`Gizmo.depthTest(true)`, ui-core's `Primitive.FLAG_DEPTH`): a depth tested gizmo records its
  handles' lines, triangles and dots with their view depths; the UI pass reads the view's `Keys.SCENE_DEPTH` of the
  same frame (after transparency, its render region mapped onto the UI's image) and fades each pixel of a handle the
  scene is nearer than (times the theme's `gizmo-occluded`), the depth made view depth by `GpuUiRenderer.depthCamera`
  (the plugin sets the scene bridge's camera). Exact per pixel, no frame of delay, also where an occluder moved this
  frame; `ui.gizmo.depthBias` (1e-4, relative) keeps a handle lying on a surface visible. Gizmos of a camera view use
  ui-gizmo's CPU `GizmoOcclusion` (`RayOcclusion`, by boxes) instead.
- **GPU picking** (`GpuPicking`, opt-in): the visibility buffer's IDs (`Keys.VISIBILITY_IDS`) read under the
  pointers by one dispatch and read back, a frame or more late; for skinned, morphed, displaced and alpha-tested
  surfaces. At 100,489 instances, 1920 x 1080 (RX 9070 XT, 2026-11-04, with other builds running): the pick costs
  0.007 ms of GPU; the IDs come free with the visibility buffer and would cost a forward view an ID pass (the
  visibility buffer's: 0.50 ms). The CPU path (3.6 us median per pointer at 100,000 nodes, ui's
  `ScenePickingBenchmarkTest`) stays the default: no latency, no GPU work.
- **Developer console** (`UiPlugin.console()` → `UiConsole`): ui-console's `Console` over every engine setting as a
  variable (`SettingVariables`: values through the constraint overlay; unavailable settings and values a constraint
  would change are refused with the reason), permissions from `console.developer` and `console.cheats`, the history
  in the user's settings folder (`FileHistoryStore`), scripts, assets and nodes from folders and names
  (`FolderScripts`, `FolderAssets`, `NamedNodes`), the UI debugger's commands. Its log comes from SLF4J through
  `ConsoleLogProvider` (choose it with `-Dslf4j.provider=com.truesim.engine.ui.console.ConsoleLogProvider`; it passes
  every call on to the app's own binding): records at `ui.console.captureLevel` and above, with `File.java:line` from
  `ui.console.sourceLevel`. 1,000 INFO records a frame through SLF4J into the inbox: 56 us median.
- **Property sources** (ui-props): `SettingsSource` generates the settings screen from the registry (labels from
  `settings.<name>` keys, ranges, groups, order, choices, tiers hidden) over `SettingsScreen` snapshots written back
  on edit; `MaterialParametersSource` gives the Slang material records' properties (ranges, withers, `DEFAULTS`).

```java
GpuUiRenderer renderer = new GpuUiRenderer(graph);
view.add(renderer);                                        // the view's Keys.UI
presenter.outputTransform(new DisplayTransform());          // composites it (needed to show a UI)
UiCommands commands = new UiCommands(input, engine.settings(), () -> frame.realTime());
MousePointer mouse = new MousePointer(window, input);
desktop.listen(mouse);
UiTunables tunables = new UiTunables(engine.settings()).add(registry, uiGroup, uiTunables);
// each frame, at its start (after input sampled), so nothing waits a frame:
tunables.update();
uiInput.beginFrame();
commands.poll(uiInput);
mouse.poll(uiInput);
// ... the UI updates, lays out and records its draw lists ...
renderer.submit(layers, layerCount, uiTextures, window.pixelWidth(), window.pixelHeight());
presenter.render();
```

Benchmarks (`TRUESIM_BENCHMARK=true ./gradlew :engine-ui:test --tests '*UiBenchmark'`, without JaCoCo or
validation; RX 9070 XT, 2026-11-03; GPU time of the UI pass and the frame start at 3840 x 2160):

| Scene | Uber (1 draw a layer) | Per kind | Adaptive (default) | Target |
|---|---|---|---|---|
| HUD: 500 elements, 5,000 glyphs, about a quarter of the screen | 0.096-0.102 ms, 1 draw | 0.157-0.165 ms, 1,075 draws | 0.103 ms, 1 draw | 0.15 ms |
| 100,000 glyphs | 0.28-0.30 ms | 0.27-0.29 ms | 0.29 ms | 0.4 ms |
| 100,000 glyphs over a full-screen translucent backdrop | 0.40-0.45 ms | 0.39-0.43 ms | 0.44 ms | (0.4 ms) |

Section 11's targets as one suite: `./gradlew :samples-bench:uiBench` (in [true-sim/samples](https://github.com/true-sim/samples), a program: the GPU
scenes above, a chart streaming 1,000,000 samples as a line and as a filled area, 10,000,000 samples zoomed, the
controls gallery idle, the console flooded) writes the samples' `docs/benchmarks-ui.md` with p50 and p99.

Submitting an unchanged HUD takes 0.1 us and uploads nothing; with 50 elements re-recorded a frame, 3 us (one dirty
range spanning them: 400 KB). Numbers vary by a few percent between sessions sharing the GPU; compare within a run.
`UiScreenshots` (`TRUESIM_SCREENSHOTS=true`, `TRUESIM_SCREENSHOTS_DIR=<dir>`) writes a gallery of every primitive at
three scales.

## Profiler

An in-game profiler in two parts, every part a setting (`profiler.*`, group `profiler`) and so a console variable:
the **frame graph** (`profiler.overlay`, F3), shippable and cheap, and the **detailed profiler** (`profiler.view`,
Shift+F3). Install `app.use(new Profiling())` (engine-graph) and call `engine.get(UiPlugin.KEY).profiler()` once; both
views are ui-profiler's controls, on a layer of their own (`profiler`, under the console), styled by the theme.

```java
app.use(new Rendering(), new Input(), new Profiling(), new UiPlugin(...));
UiProfiler profiler = engine.get(UiPlugin.KEY).profiler();         // console commands join the console
engine.settings().set(ProfilerSettings.OVERLAY, true);              // or `profiler.overlay 1`, or F3
static final Zone PHYSICS = Profiler.zone("game.physics");          // a game's own zone
Profiler.begin(PHYSICS); try { step(); } finally { Profiler.end(PHYSICS); }
```

- **Frame graph** (ui-profiler's `FrameGraphView` on a streaming chart): per frame its CPU time (start to submit), what
  of it the CPU waited for the GPU or the swapchain (the frame slot's fence, acquire, present), the frame interval,
  and the GPU's span of the frame, drawn at that frame once the GPU finished it (frames later; nothing waits for it);
  budget lines (`profiler.overlay.budgets`: `60,120,144,display`), hitch lines (`profiler.hitch.factor` times the
  recent mean, at least `profiler.hitch.minMillis`, or over `profiler.hitch.millis`), and min / mean / p99 of each
  line over the frames kept (`profiler.overlay.frames`).
- **CPU zones** (`Profiler`, engine-app): declared once, timed anywhere; each thread writes into a ring of its own
  (`profiler.ringEvents`), drained by the main thread once a frame. Off, a begin or an end is one read of a volatile
  flag (0.16 ns, below). `try (Zone z = PHYSICS.begin()) { ... }` ends a zone however its block is left; a zone left
  open by an exception past a bare `begin` is closed when an enclosing zone ends (the drain matches each end to its
  zone), and the engine's `frame` zone ends in a `finally`, so a throwing frame leaves the depths balanced. The engine's own: the loop's phases (`BEFORE_UPDATE`, `FIXED` and each `fixed step`,
  `UPDATE`, `ANIMATE`, `LATE`, `AFTER_UPDATE`, `end frame`), `render.record`, `render.submit`, `render.chunk` (on
  job threads when recording in parallel), `wait for frame slot`, `acquire`, `present`, `audio.block` (the audio
  thread), `audio.streams fill`, `stream.upload`, `asset.mesh prepare`, `asset.texture prepare`.
- **GPU passes per queue** on the CPU's clock: the frame graph's timestamps (its GPU timings, on while a view shows
  and `profiler.gpu`) placed by `GpuClock`: `VK_KHR_calibrated_timestamps` (or the EXT one) against the host's clock
  that `System.nanoTime` reads (Windows' performance counter, Linux's `CLOCK_MONOTONIC`), calibrated every
  `profiler.gpu.calibrateEvery` seconds on the counters thread; without it, the offset is bounded by the submits (a
  frame's first timestamp is never before its submit; the latest bound of each window wins).
- **Detailed profiler** (`ProfilerView`): a timeline of every thread, GPU queue and the runtime's markers, zoomable
  to microseconds (wheel; drag pans; Shift-drag selects a span; a click on the ruler selects a frame; double click
  goes live), a flame graph and a zone table (calls, total, self, mean, p99, max) of the selected span, counters as
  charts; freeze (F4), step a frame (Shift+F4), freeze after a hitch (`profiler.hitch.freeze`,
  `profiler.hitch.freezeAfter`), keep `profiler.history` seconds, export (Ctrl+F4, `profiler.export`): the own format
  (`.tsprof`, opens again in the view) and Chrome trace JSON (Perfetto, `chrome://tracing`) into
  `profiler.exportFolder`.
- **Counters**, sampled on a thread of their own (`profiler.counters.interval`), never on the frame's: cheap
  (`profiler.counters`): process and system CPU load, heap used and committed, non-heap, direct and mapped buffers,
  GC count and time, live threads, the process's resident memory (FFM: `K32GetProcessMemoryInfo`, `/proc/self/statm`,
  `task_info`), per profiler thread its CPU load and allocation rate (`ThreadMXBean`), LWJGL's native memory as the
  engine counts it (`NativeMemory`, installed before LWJGL first allocates), each heap's usage and budget
  (`VK_EXT_memory_budget` through VMA, VMA's estimate without), GPU memory by category (`GpuMemory`: textures, render
  targets, meshes, acceleration structures, graph transients, staging, buffers), the GPU's busy share. Detailed:
  pipeline statistics per graphics pass (`profiler.pipelineStatistics`), the operating system's per-core and GPU
  engine load (`profiler.os`: PDH on Windows, `/proc/stat`, `gpu_busy_percent` and DRM fdinfo on Linux), garbage
  collections, safepoints and compilations as timeline markers (`profiler.runtime`: a JFR `RecordingStream`).
  What a machine lacks is reported once (`profiler.counters` in the console) instead of assumed.
  `VK_KHR_performance_query` is detected (`VulkanDevice.performanceQuery()`) but not collected.

Overhead (the samples' `./gradlew :samples-bench:profilerBench`, `docs/benchmarks-profiler.md` there; Ryzen 9
9950X3D, RX 9070 XT, 2026-11-04, no JaCoCo): a zone off 0.16 ns, on 38 ns; the frame graph's data path 0.14 us a
frame; the frame graph view 7.9 us a frame of CPU (its lines stream: appended and scrolled by a drawn offset,
ui-charts' streaming); engine frames (headless, an idle UI) +0.008 to +0.011 ms p50 with the overlay, +0.12 to
+0.16 ms with the detailed view live. Frames with the profiler on allocate
nothing (`UiProfilerTest`, counting allocating frames as pitfalls.md says).

## Vulkan

Vulkan 1.3 is required: dynamic rendering, synchronization2, timeline semaphores, buffer device addresses and
descriptor indexing; memory through VMA. Where the loader and GPU have Vulkan 1.4, devices use it and enable every
1.4 feature the GPU supports; `device.features14()` tells renderer paths what they can use (push descriptors,
dynamic rendering local read, maintenance5/6, host image copy, ...), and is `Vulkan14Features.NONE` on 1.3.
`VulkanInstance.builder().maxApiVersion(VK_API_VERSION_1_3)` runs as on a 1.3 GPU, e.g. to test fallbacks. With validation on, every validation message is logged and counted,
and the tests require zero errors.

```java
try (VulkanInstance instance = VulkanInstance.builder().applicationName("game").validation(true).build();
     VulkanDevice device = VulkanDevice.create(instance.bestGpu());   // or any of instance.gpus()
     Surface surface = instance.createSurface(window);
     Swapchain swapchain = new Swapchain(device, surface, PresentMode.MAILBOX, window.pixelWidth(), window.pixelHeight());
     Frames frames = new Frames(device, device.graphics(), 2, workers)) {

    Frames.Frame frame = frames.next();                  // waits for the frame's previous use
    int image = swapchain.acquire(frame.imageAvailable());
    VkCommandBuffer commands = frame.begin(worker);      // one command buffer per worker thread
    ...
    frame.end(worker);
    frame.submit(device.graphics(), swapchain.presentReady(image), true);  // in worker order
    swapchain.present(device.graphics(), image);

    swapchain.recreate(width, height);                   // on windowPixelSizeChanged or needsRecreate()
    swapchain.setPresentMode(PresentMode.FIFO);          // falls back to FIFO when unsupported
}
```

- **Multi-window:** one surface, swapchain and `Frames` per window, all on one device.
- **Multi-GPU:** devices on several GPUs at once; `VulkanDevice.createHeadless` for compute or offscreen work.
- **Multi-threaded:** `Frames` gives every worker its own command pool per frame, so workers (for example a
  common-jobs `JobSystem`'s, by worker index) record without locks. Queues synchronize submissions.
- **Allocation-free:** `next`, `begin`, `end`, `submit`, `acquire`, `present` and `Images.transition` write
  their Vulkan structs straight into stack memory. LWJGL wraps native pointers in `MemorySegment`s, which the
  JIT removes once the code is compiled by C2.
- **Per-frame data, per frame in flight:** a `FrameBuffer` (views, lights, materials) keeps a host-visible
  copy per slot of its `FrameSlots`, plus a master copy in CPU memory. While a frame records, writes reach the master
  and that frame's copy. Between frames they reach only the master, so frames in flight keep reading what they were
  given; each copy takes the range it missed from the master when its slot opens. `FrameSlots.single()` is one copy
  written straight through, for callers that wait for the GPU between frames.
- **Records that change a few at a time:** a `RecordBuffer` is a device-local buffer of fixed-size records (any
  multiple of 16 bytes: instances, particles, game data per object). Changing one writes its bytes once into a list of
  changes (`claim`, then the bytes in `changes(entry)` at `offset(entry)`; a record at most once per upload, from any
  thread once claimed); the frame's first commands copy the list into the frame slot's upload buffer
  (`GpuBuffer.hostWritten`: write-combined, or BAR memory where the GPU has it) and a compute pass scatters it. The CPU
  pays per record changed, frames in flight keep no copies (the queue orders the scatter after the frames before), and
  shaders read device-local memory. `GpuScene`'s instances are one. `changeList(DIRECT, chunkBytes)` writes the
  changes in place instead, into recycled chunks of GPU-visible host memory the scatter reads where they are: the
  thread recording copies nothing (0.6 ms less at 100,000 changed instances), but the writers pay about as much more
  and the scatter reads over the bus (0.2 ms more on an RX 9070 XT), so the copy stays the default
  (`render.changeList`, `render.changeListChunk`; measured in docs/frame-graph-v2.md, phase 8).
- **Work at the frame's start:** `FrameSlots.register(FrameStart)` records commands before every frame's passes (the
  frame graph calls `recordStart`); `FrameUploads` and `RecordBuffer` use it, and so may a game.

### Shaders and pipelines

Shaders are Slang under `src/main/slang`, compiled to SPIR-V resources by build-logic's `truesim.slang` plugin with
a pinned compiler. Buffers reach shaders through device addresses passed in push constants, images through slots of
the device's one bindless descriptor set (`Bindless`: sampled images, samplers, storage images). Every pipeline shares
one layout, so the set is bound once per command buffer. There is no vertex input: shaders pull what they need.

Every pipeline is made through the device's `PipelineCache`; `device.pipelineCache().persist(path)` loads it at start (only
what this GPU and driver saved) and saves it when the device closes, so pipelines compile once, not every run. Frame
graph techniques ask for programs instead of making pipelines: `g.computeProgram(shader, entry, pushBytes,
constants...)` and `g.graphicsProgram(GraphicsProgram.of(pushBytes).vertex(...).fragment(...).color(format))` build each
once and share it between techniques asking for the same; they live as long as the graph. What the device can do
(`g.capabilities()`) picks variants.

```java
try (ShaderModule shaders = ShaderModule.load(device, "shaders/meshlets.spv");
     Pipeline cull = Pipeline.compute(device, shaders, "cullMain", 32);
     Pipeline draw = Pipeline.graphics(device)
         .stage(VK_SHADER_STAGE_VERTEX_BIT, shaders, "vertexMain")
         .stage(VK_SHADER_STAGE_FRAGMENT_BIT, shaders, "fragmentMain")
         .color(VK_FORMAT_R8G8B8A8_UNORM).depth(VK_FORMAT_D32_SFLOAT)   // reverse depth: clear to 0
         .pushConstants(32)
         .build();
     GpuBuffer meshlets = GpuBuffer.upload(device, mesh.meshlets(), STORAGE | SHADER_DEVICE_ADDRESS)) {
    draw.bind(commands);
    draw.push(commands, pushData, 32);    // e.g. meshlets.deviceAddress(), the frame's buffers
}
```

### Custom passes without Vulkan code

A game's own passes need no Vulkan calls for the usual work. Push constants are written by name: with
`slang.pushBase = "com.truesim.engine.vulkan.PushConstants"`, build-logic generates a writer per push struct
(`[[vk::push_constant]] ConstantBuffer<FindPush>` becomes `FindPush`, with `FindPush.BYTES` and a typed setter per
field at the offset slangc lays out). A pass keeps one, made when the graph builds, sets what the build decides once,
and the rest each frame. `Push` writes fields in order instead, for ad hoc use. The pass
context records the commands: `ctx.dispatch` and `dispatchIndirect`; `ctx.fill`, `ctx.copy` (buffers, and between
buffers and images), `ctx.blit` and `ctx.clear`; `ctx.draw`, `drawIndirect`, `drawIndexedIndirect(Count)` and
`drawMeshTasksIndirect`; `ctx.viewport` and `ctx.scissor`. A pass renders by declaring attachments: `attach(color,
Clear.color(...))` or `attach(color, Load.LOAD, Store.STORE)`, `attachDepth(depth, Clear.FAR)`, one layer of an array
(`image.layer(l)`), and `resolve(target)` for a multisampled one. The graph opens the rendering scope around the body,
viewport and scissor all of the attachments, and what is cleared or not loaded starts from an undefined layout, with
no transition of old contents. The accesses a pass declares still decide every barrier; with validation on, a body
that touches a resource its pass did not declare fails. Raw Vulkan stays available through `ctx.unsafe()` (the
command buffer, image, view and buffer handles) for anything else.

```java
Pipeline simulate = g.computeProgram("shaders/particles.spv", "simulateMain", SimulatePush.BYTES);
SimulatePush step = new SimulatePush().count(count);        // once, per pass; the count the build decides
g.pass("simulate").read(state, BufferAccess.STORAGE_READ).write(next, BufferAccess.STORAGE_WRITE)
    .record(ctx -> ctx.dispatch(simulate, step.state(ctx.address(state)).next(ctx.address(next)).dt(clock.delta()),
        (count + 63) / 64));
DrawPush draw = new DrawPush();
g.pass("draw").read(next, BufferAccess.DRAW_READ).attach(color, Clear.color(0, 0, 0, 1))
    .record(ctx -> ctx.draw(particles, draw.particles(ctx.address(next)), 6, count));
```

Shaders import the modules of any dependency using `truesim.slang`: its sources ship in its jar under `slang/`, so a
game's shader can `import meshlets.common;` and read the engine's `Instance`s (`GpuScene.instanceAddress()`) with the
engine's own struct. Compiler arguments a library's modules need ship beside them (`slangc.arguments`). The engine's
tests do this end to end: a compute pass reading the instances through the graph, a fill, a copy and a draw
(`CustomPassTest`).

Devices require what such shaders use: 64-bit integers for pointers, 8 and 16-bit storage for packed data (meshlet
indices, quantized vertices) and draw parameters, on top of the Vulkan 1.3 baseline.

## GPU-driven rendering

`engine-render` draws scene's meshes (`MeshData`: meshlets over quantized vertices with cluster LOD) GPU-driven: the
CPU writes instances and views, the GPU decides what to draw. The world's instances are a `GpuScene`; a
`MeshletRenderer` draws it from any number of `RenderView`s.

```java
FrameSlots slots = graph.frameSlots();                              // a copy per frame in flight
try (GeometryPool pool = new GeometryPool(device, slots);              // starts small, grows as meshes come
     GpuScene scene = new GpuScene(device, pool, 100_000, slots);
     MeshletRenderer renderer = new MeshletRenderer(device, scene, colorFormat, depthFormat, slots)) {
    int car = pool.add(carMesh);                                     // uploaded once
    int id = scene.add(car, 0, 1, true, x, y, z, qx, qy, qz, qw, 1, 1, 1); // world position, doubles: lowest free id
    RenderView view = renderer.createView(ViewOptions.of(1 << 20, 1 << 24).withOcclusion(true));
    view.set(camera.position(cx, cy, cz).set(forward, up, fovY, width, height, near), 1.0f,
        MeshletRenderer.MODE_LIT);
    renderer.record(commands, view, colorView, depthView, width, height, 0, 0, 0);
}
```

- **Who closes what:** whoever makes a resource closes it, after what uses it; handing a resource to another object
  never hands over closing it. The one exception is a graph view's own (`ViewPort.own`): `SceneGeometry.createView`
  and the techniques that make render views for a port register them there, so they close with the port.
- **Geometry pool:** every mesh's streams in shared device-local buffers, reached by buffer device address, with a
  table entry per mesh; any number of meshes and instances draw in one pass.
- **The pool grows by itself:** a buffer without room for a mesh is replaced by one twice its size (or more), its
  contents copied over on the GPU in the next frame, in order with the uploads, and the old one freed once no frame
  reads it; the mesh table grows alike, and ray tracing's and classic LOD's per-mesh data follow. Nothing waits, and
  the graph rebuilds once for the new sizes. A capacity given to the constructor is only where it starts
  (`GeometryPool.START` without one); give one sized for the content to skip the growing.
- **So does the instance table:** taking an id past a `GpuScene`'s capacity doubles it (or more); the instances are
  copied over on the GPU in the next frame, the old buffer freed once no frame reads it, and views' occlusion
  history grows along. `add` never runs out of ids.
- **And the material table:** without ids in a row or room for blocks, `Materials` takes a table twice the size (or
  more) holding what the old one holds, values changed in place included; frames in flight read the old one until
  they finish. Scenes never wait for room.
- **And ray tracing's top level:** it follows the scenes it is built from, growing with their instance tables
  (between frames, in a new bindless slot, the old structure freed once no frame traces it), so no instance is left
  out; the count given to `RayTracing` is only where it starts.
- **Meshes come and go:** `pool.remove(mesh)` frees a mesh's ranges and number for later meshes, first fit and
  deterministic, once no frame in flight can draw it (given the graph's `frameSlots()`). `Materials.remove` and
  `Textures.remove` do the same for material ids and bindless texture slots, so streamed content can be unloaded.
  Classic LOD data and ray tracing's bottom levels follow removals: a mesh leaving the pool leaves them too.
- **Uploads without waiting:** given the graph's `frameSlots()`, `pool.add` and `Textures.color`/`data` stage their
  data at once and return; the copies (and a texture's mip chain) are recorded first thing in the next frame
  (`FrameUploads`, through `FrameSlots.recordStart`, which the frame graph calls), so what was added draws from the
  next frame on and the CPU never waits for the GPU. The benchmark's rock adds in 0.4 ms instead of 2.6-6.6 ms, its
  49,062-meshlet terrain in 7 ms instead of about 100. Without frame slots, uploads wait as before.
- **Prepared on a loader thread:** staging still copies the data on the calling thread (16 MB for a 2048 x 2048
  texture, about 1-1.5 ms, and fresh allocations now and then spike to 3-7 ms). `pool.prepare(mesh)` and
  `textures.prepareColor`/`prepareData` do that part on any thread, a loader's next to the mesh build or texture
  decode, and `pool.add(prepared)` / `textures.add(prepared)` on the frame's thread only place the data and queue its
  copies (`FrameUploads.record(staging, ...)`). Streaming tiles of 131,000 triangles with a 2048 x 2048 texture each
  at 90 Hz: the worst frame went from 14.9 ms to 4.0 ms, frames over 4 ms from 37 to none (`StreamBench --args=large`
  against `large direct`).
- **Streamed on the transfer queue:** prepared meshes' and textures' copies are submitted on the transfer queue
  (`StreamQueue`) when they are added, beside the frames in flight; the next frame waits for them on the GPU before its
  first commands, so they still draw from the next frame. Images change owner (released there, acquired in the frame,
  where a generated mip chain is blitted); the pool's buffers are shared with the transfer family. Frames adding
  131,000-triangle tiles with 2048 x 2048 textures took 0.8-1.0 ms of graphics-queue time instead of 1.5-1.7 (max
  1.3-1.5 instead of 2.4-3.2; RX 9070 XT, 2026-11-03). Up to `render.streamBudget` MiB a frame (64; 0: every copy in
  the frame); past it, uploads go through the frame as before.
- **Culling on the GPU:** an instance pass tests instance bounds and splits each visible instance into chunks of 64
  meshlets; the chunk count is written straight into an indirect command, so only visible work is launched. Each
  meshlet is tested by the cluster LOD rule (its group's error projected on screen at most the allowed pixels, its
  parent's more), the frustum and its normal cone (whole meshlets facing away).
- **Raster bins, by material:** a material says how it rasterizes in its header (`Material.alphaMask(cutoff)`,
  `doubleSided(true)`; opaque and one-sided by default). The cull pass puts each meshlet in its view's list of its
  material's bin, and each bin draws with a pipeline of its own: ordinary opaque meshlets keep the fastest (back faces
  culled, no discard, early depth), double-sided ones draw without culling (their normal-cone test skipped, back faces
  lit as the front), masked ones without culling and with a fragment variant discarding masked texels (the mask
  texture sampled in its UV set, moved by its texture transform, as the color passes sample it) and one-sided
  back faces. Every pass rasterizing them uses the same variants, so shadow maps, depth and the visibility buffer
  mask what the camera does; classic LOD draws through the same bins. A view's extra bins have room for a quarter of
  its opaque bin by default (`ViewOptions.withBins`); `RenderView.visibleMeshlets(bin)` counts each. Ray tracing
  follows them too: instances with a masked material enter the top level as non-opaque, and shadow rays alpha-test
  their triangles (the triangle looked up in its meshlet, its texture coordinates of both sets interpolated, the
  mask texture read in its set and moved by its transform) against the same cutoff, at the finest texels.
- **Two paths, one set of shaders:** a compute pass culls each chunk and lists the visible meshlets. With mesh shaders
  (`VK_EXT_mesh_shader`, enabled where the GPU has it), a mesh workgroup per listed meshlet emits its vertices and
  triangles straight from the pool. Without, an expansion pass writes their triangles into one index buffer, and one
  `vkCmdDrawIndexedIndirect` draws them. The meshlet test, vertex decoding and shading are one Slang module
  (`meshlets/common.slang`) both paths import, and they draw the same image (tested). `Path.AUTO` picks mesh shaders
  where available: they draw whatever the view finds. The indexed path is the optimization for views known to stay
  within their triangle budget (`ViewOptions.triangles`; past it, triangles are dropped and `RenderView.overflowed()`
  says so): on an RX 9070 XT it is the faster one (see the table below), so choose it with `setPath(Path.INDEXED)`
  there. Culling in task shaders instead, a workgroup per chunk, left most lanes idle when instances need
  only a few meshlets: 0.95 ms against 0.59 ms for 30,000 rocks.
- **Instance-level LOD range:** before culling meshlets, the instance pass skips every level finer than the first one
  whose error, seen from the instance's nearest point, is within the allowed pixels: the rule could not pick anything
  there. A far instance costs a chunk or two, however detailed its mesh.
- **World positions, every view relative to its own camera:** instances keep their world position in two floats
  per axis (about 48 bits: sub-millimetre thousands of kilometres out), written once and again only when they move.
  Every view has a camera at a world position of its own (`Camera.position`, in doubles), and the GPU draws each
  instance relative to it, subtracting the high parts and the rest apart. Everything after works in floats near the
  camera, as precise anywhere in the world as at its origin (tested: the same image 7,000 km out; floats alone are
  visibly off there). Moving a camera rewrites nothing but its view; a shadow camera stays where its light is, so its
  map does not change as other cameras move.
- **Stable instance ids, layers and shadows:** `GpuScene.add` takes the lowest free id and `remove` leaves a hole
  the culling skips, so ids stay with their instances (whatever the GPU keeps per instance, such as occlusion history,
  stays right; previous transforms and custom data per instance will be buffers by id). Each instance is on layers
  (32 bits) and casts shadows or not; a view draws the layers of its `ViewOptions` (or `RenderView.filter`), shadow
  views (`ViewOptions.shadow`) and shadow rays leave out what casts none. Several scenes may fill one `GpuScene`, told
  apart by their layers (a world and an editor's gizmos), or each have one of their own.
- **Views, as many as there are:** a `RenderView` is a handle: its camera, its filter, its lists sized by its
  `ViewOptions` (a camera large, a shadow cascade or a mirror smaller) and, with occlusion, a visibility history of its
  own, so every camera or mirror can cull occluded instances. A view's lists cost 16 bytes per meshlet of room; only
  the indexed path adds an index buffer (12 bytes per triangle of room), made as it first draws the view and dropped
  when the renderer switches to mesh shaders. `overflowed()` says whether the last frame wanted more than the lists
  hold (meshlets, or triangles on the indexed path) and `resize(draws, triangles)` gives the view new lists, the old
  ones freed once their frames are done; `bytes()` and `MeshletRenderer.viewBytes()` say what views cost. `cullViews` culls any number at once: one instance pass
  with a row per view (in dispatches of up to 65,535 rows), and one cull pass over all their chunks, each meshlet into
  its view's list; `drawView` then draws each. Through the frame graph, the `MeshletCulling` request gathers every
  view the techniques draw (the scene's camera, the shadow atlas's tile views) into one culling pass.

Measured with `MeshletBenchmark` (`TRUESIM_BENCHMARK=true`) on an AMD Radeon RX 9070 XT at 1920 x 1080 with 1 pixel
of error allowed, GPU time of the whole frame (instance pass, culling, drawing), median per frame of batches of 32
frames recorded back to back (the paths take turns, so they see the same clocks):

| Scene | Source triangles | Mesh shaders | Indexed | No meshlets: full detail | No meshlets: discrete LODs, CPU culling |
|---|---|---|---|---|---|
| 1,000 rocks (82k-triangle mesh) | 81.9 M | 0.118 ms | 0.058 ms | 6.65 ms | 0.12 ms (+0.20 ms CPU) |
| 3,000 rocks | 246 M | 0.232 ms | 0.102 ms | 11.3 ms | 0.30 ms (+0.42 ms CPU) |
| 10,000 rocks | 819 M | 0.641 ms | 0.247 ms | 33.2 ms | 0.95 ms (+0.15 ms CPU) |
| 30,000 rocks | 2.46 billion | 1.33 ms | 0.736 ms | 102 ms | 2.82 ms (+0.45 ms CPU) |
| terrain at a grazing angle | 2.1 M | 0.189 ms | 0.107 ms | 0.30 ms | (one mesh: full detail) |

Measured 2026-10-30, shaded (lit, materials). When the mesh shader path was first measured its vertices carried a
normal and a meshlet number; materials and lighting made that some 20 values. On this GPU a mesh shader's outputs
reach the fragment shader through memory, so the mesh path now shades lean (`lean.slang`: what interpolates per
vertex, what is flat or rare fetched per pixel through the primitive ID and barycentrics), which brought 30,000 rocks
from 1.53 to 1.33 ms; the indexed path stays faster here. Small scenes depend on how the GPU clocks for short frames:
from one session to the next, they measured up to twice as long on the same code. Comparisons are made within a
session.

**Occlusion culling.** Through the frame graph, `SceneDraw` draws the camera in two phases around a `DepthPyramid`. The early
phase draws the instances visible last frame (a bit per instance, kept on the GPU). The late phase tests every
instance in view against the pyramid of that depth, and the meshlets of those newly visible too. It draws what came
into view in the same frame, so nothing pops in. Hidden objects cost only their instance test. The pyramid test is
conservative: a sphere's box is projected, and its nearest depth is compared at the level where the box covers at most
2 x 2 texels, mapped by what each texel really covers. 30,000 rocks, in the same session:

| Scene | Occlusion culling off | On |
|---|---|---|
| in the open (rocks hide rocks) | 1.03 ms, 99,052 meshlets | 0.84 ms, 69,507 meshlets |
| behind a wall (the terrain, upright, 80 units ahead) | 1.10 ms, 99,239 meshlets | 0.30 ms, 2,984 meshlets |

`TRUESIM_SCREENSHOTS=true ./gradlew :engine-render:test --tests '*Screenshots'` renders these scenes to PNGs under
`render/build/screenshots`, shaded and colored per meshlet.

The indexed path draws everything with one draw: culling lists the visible meshlets, an expansion pass writes their
triangles into the frame's index buffer (each index is the meshlet's slot and local vertex), and one
`vkCmdDrawIndexedIndirect` draws them. The baselines without meshlets draw whole meshes, one indirect draw per instance:
at full detail, or as a classic engine does, picking a discrete LOD per instance and culling on the CPU. At the same
error the cluster hierarchy draws fewer triangles than discrete LODs (30,000 rocks: 99,052 meshlets, at most 12.3 M
triangles, against 17.6 M), since each cluster takes the detail its own distance needs. Both builds sum their
simplification errors, which stay close to the real deviation from full detail.

- **Reverse depth with an infinite far plane,** instances relative to each view's camera: precise near and far,
  anywhere in a large world.
- Tested on the GPU: coverage of a quad, culling (behind, outside, facing away), LOD cuts from full detail to a few
  meshlets that keep the covered area within 2 % (no holes), and both paths drawing the same image.


**Memory.** The pool keeps what meshlets need and nothing more: quantized vertices, meshlet descriptors and their
triangles as a byte per local index. An 82k-triangle rock with its whole cluster LOD hierarchy takes 1.50 MiB. A
classic renderer would add a 32-bit index buffer per mesh and per discrete LOD; the pool has none.

### Materials and textures

A material is its **type** (how its parameters become a surface), the type's **parameters**, the **features** it
shows, and how it rasterizes. Type 0 is metallic-roughness as in glTF: a base color, metalness, roughness, a
tangent-space normal map, occlusion and emission, each a factor times an optional texture (occlusion in the ORM
texture's red, or in an occlusion texture of its own with its own UV set and transform).

```java
try (Textures textures = new Textures(device); Materials materials = new Materials(device, 1024)) {
    int albedo = textures.color(512, 512, rgba);       // sRGB, full mip chain made on the GPU
    int normals = textures.data(512, 512, xyz);         // linear
    int rock = materials.add(new Material(MetallicRoughnessParameters.DEFAULTS
        .withBaseColorTexture(albedo).withNormalTexture(normals).withRoughness(0.9f)));
    scene.setMaterials(materials);
    scene.set(0, rockMesh, rock, x, y, z, qx, qy, qz, qw, 1, 1, 1); // submesh s takes material rock + s
}
```

- **One source of truth, in Slang.** A type's parameters are a Slang struct marked `[MaterialParameters]`; a feature's
  one marked `[MaterialFeature]`; a type an `IMaterial` marked `[MaterialType("name")]`. The build (build-logic's
  `truesim.slang`, `slang.javaPackage`) generates from them, before the Java compiles: a Java record per struct, with
  the struct's initializers as `DEFAULTS`, `[Range]` checks, withers, the byte layout slangc itself gives the struct
  (its reflection), a constant per field for writing one value in place (`materials.update(id,
  CarPaintParameters.FLAKES, 0.8f)`), and `fromValues` for data by field name; and per type its visibility-buffer
  entry points and a class holding its `MaterialType`. Structs of other projects are found through an index their jar
  ships, so a game's parameters can nest the engine's:

  ```slang
  import materials.metallicRoughness;

  [MaterialParameters(FEATURE_CLEARCOAT)]            // the features the type allows
  public struct CarPaintParameters
  {
      public MetallicRoughnessParameters base;       // the engine's, nested by value
      [Color] public float3 flakeColor = float3(1, 1, 1);
      [Range(0, 1)] public float flakes = 0;
  };

  [MaterialType("carPaint")]
  public struct CarPaint : IMaterial
  {
      public typealias Parameters = CarPaintParameters;
      public static Surface surface(CarPaintParameters p, MaterialRef m, SurfaceInput in)
      {
          Surface s = evaluate(p.base, in);          // metallic-roughness, as the engine does it
          s.albedo = lerp(s.albedo, p.flakeColor, p.flakes);
          return s;
      }
  };
  ```

  ```java
  materials.register(CarPaint.TYPE);
  int red = materials.add(new Material(CarPaintParameters.DEFAULTS.withFlakes(0.4f))
      .feature(ClearcoatParameters.DEFAULTS.withRoughness(0.05f)));
  ```
- **The table** is a 32-byte slot per material (header: type, double-sided, masked, features, cutoff; the mask's alpha
  and texture; where its blocks lie), then the blocks: the type's parameters and a section per feature shown, each
  material paying only for what it has. Passes that only rasterize (depth, shadows, IDs) read the slot alone, whatever
  the type, but types with geometry hooks ("Geometry hooks" below). Material 0 is metallic-roughness's defaults, so meshes draw before anything is set up. Instances name a
  first material and each submesh of the mesh takes the next.
- **Features are the engine's**, applied on any type allowing them: a clear coat (glTF's `KHR_materials_clearcoat`)
  is laid over the type's surface where the material has a `ClearcoatParameters` section. The visibility buffer sorts
  the screen's 8x8 tiles into a list per type and feature combination they show and shades each with one indirect
  dispatch of its own variant (a specialization constant), holding only its features' code: a plain material never
  pays for a coat. Forward shading does the same by draws: each type and feature combination draws with pipelines of
  its own ("Every color pass by material type" below).
- **Textures** live in the bindless set and are sampled by slot, trilinear and anisotropic (`Bindless.MATERIAL`, up to
  16x where the GPU allows).
- **One shading model** (`shading.slang`): Lambert diffuse and a GGX specular lobe with height-correlated Smith
  visibility and Schlick Fresnel, under an optional coat, with explicit texture gradients so passes without hardware
  derivatives use it too. The vertex stage decodes normals, tangents, texture coordinates and vertex colors from the
  pool's attributes.
- **From scene data:** scene's `MaterialData` names its type and values by field name (`base.roughness` for nested
  ones), and the bridge builds the type's record through its `fromValues`; equal data shared inline (`data.ref()`) is
  one material.


### Lights and light lists

`Lights` holds point, spot and directional lights at world positions (two floats per axis, like instances), and a
shadow table whose entries each have a world position of their own: a shadow map's is its light camera's. A light
list hands lights to shading relative to its camera and moves a surface into an entry's positions before the lookup,
so one set of lights and shadow maps serves every view: the camera, another player's, a mirror's. A **light
binning** technique turns them into a view's light list, `Keys.LIGHT_LIST` (a buffer). Shading techniques use it
if their view has one, and otherwise light by the sun; `port.replace` swaps one binning for another.

`ClusteredLights` is the first binning: the camera's frustum is cut into tiles of 64 pixels and 24 exponential depth
slices, and a compute pass on the async queue gives each cluster the lights whose sphere reaches its box (lights read
in batches of 64 into shared memory; directional lights reach every cluster).

```java
lights.directional(0, -0.4f, -0.8f, -0.3f, 3, 3, 3).shadows(0, 0, 4); // the sun, four cascades: entries 0 to 3
lights.point(1, x, y, z, 10, 20, 15, 10);                              // range 10
lights.setCount(2);
port.add(new ClusteredLights(lights, main));                         // provides the view's Keys.LIGHT_LIST
```

- **One interface for every layout:** a light list is one buffer, a common header (layout, lights, shadows, the
  layout's lists and numbers) then the layout's data. Shading reads it through `lights.slang`'s `ILightList` (the
  lights reaching one surface: `count()`, `light(i)`), with a generic light loop over any implementation.
  `ClusterLightList` implements it; a new layout (tiles, depth bins) is a struct implementing it and a case in
  `lightList()`'s dispatch on the header's layout.
- **Shadows, single or many per light:** a light names consecutive shadow table entries, each of a kind (a shadow
  map with its filter, a mask). A spot light uses one; a directional light one per cascade, chosen by
  view depth; a point light six cube faces, chosen by direction. See "Shadows as data" below: one owner draws every
  light by its `ShadowSpec`.
- **Fixed slots per cluster** (128 by default): no atomics, a deterministic order, and overflow reported (the most
  lights any cluster wanted is in the header).
- Tested against the same cluster boxes computed on the CPU.

### Geometry and shading

Drawing and shading are separate, and each is swappable:

- **Geometry providers** draw one kind of geometry; particles come later. A `SceneGeometry` holds
  the providers of a frame, all drawing its renderer's views.
- **Skinned meshes** draw as any mesh: `GeometryPool.addDeformed(mesh)` gives an instance a copy of its own (vertices,
  attributes and meshlet records; triangles and levels shared), and a `deformation` pass after the scene upload skins
  every posed copy (`Deformation`: joint matrices and the pose's box, linear blend skinning, normals by cofactors),
  recomputes its meshlet spheres, turns cone culling off for it and grows its detail spheres by how far the joints
  move. Morph targets go first: a copy of a mesh with targets takes their weights (`Deformation.weight`), the
  deltas uploaded once per mesh. `SceneSync` does it for renderables with a skin (`Renderable.setSkin`, set by
  `ModelData.spawn`) or morph targets (weights in the node's `MorphWeights` component), posing them each update from
  their joints' world transforms and weights; scene-animation's `Animators` move the joints and, through
  `animators.morphSink((node, w, offset, count) -> MorphWeights.write(scene, node, w, offset, count))`, the weights. Meshlet paths only
  for now; ray tracing traces each copy through a bottom level of its own, refitted as it is posed (Ray tracing).
  Animated material values (glTF's `KHR_animation_pointer`: colors, factors, texture transforms) go into the material
  table in place, for every renderable drawing the material: `resources.animate(material, "baseColor", values, 0, 4)`,
  or animators' pointer tracks through `animators.pointerSink(resources.pointerSink().andThen(visibility))` (a
  scene-render `NodeVisibility` hides and shows nodes by layers). The material converts once more when a value of it is
  first animated, an animated texture getting a transform slot of its own; each change after that allocates nothing.
  Lights and cameras are the application's: it reads their animated values from the same sink or `Animator.value`.
  - `MeshletGeometry`: a renderer's meshlets, with cluster LOD.
  - `ClassicLodGeometry`: the same instances drawn the classic way.
    - It takes discrete LODs (`MeshBuilder.buildDiscrete`) or flat meshes, added with `add(mesh, data)`. Adding waits
      for no frame in flight: the indices are copied first thing in the next frame, the first to draw the mesh.
    - A compute pass per view culls every instance against the frustum and picks one whole level by the same error
      rule. It writes an indexed indirect draw per submesh run of that level, and one `vkCmdDrawIndexedIndirectCount`
      draws them all.
    - It draws depth, shaded color and visibility buffer IDs. Classic IDs carry a top-bit tag, and the resolve rebuilds
      them from the provider's index buffer, so `VisibilityBufferShading` works with it unchanged.
    - It has no occlusion culling yet, and it doesn't mix with meshlets over one renderer's instances.
    - For 30,000 rocks through the graph (forward, occlusion off):

      | Provider | Time |
      |---|---|
      | meshlets, cluster hierarchy | 1.28 ms |
      | meshlets, discrete LODs | 2.07 ms |
      | classic LOD | 2.84 ms |

      Classic LOD matches the CPU-culled classic baseline (2.82 ms), since both draw the same 17.6 M triangles. It
      also frees the CPU.
- **`SceneDraw`**, a shared request, draws every provider from one view into the targets a `SurfaceOutput` asks for:
  depth only, shaded color, or visibility buffer IDs. It does two-phase occlusion culling for views made with it.
  Shading techniques ask for the camera's draw and shadow techniques for the light's; equal requests are drawn once, and the
  renderer culls all its views in one pass.
- **Shading techniques** provide a view's `Keys.SCENE_COLOR` and `Keys.SCENE_DEPTH`, at the size of its port. They
  take a `SceneView` (the port, the `RenderView` its culling draws and the camera) and are added to its port, as are
  the light binning and shadows; built anywhere else, they fail. `port.replace` swaps them:

```java
SceneGeometry geometry = SceneGeometry.meshlets(renderer);
ViewPort port = graph.addView("main").size(width, height);
SceneView main = geometry.createView(port, camera, ViewOptions.of(1 << 20, 1 << 24).withOcclusion(true))
    .set(1, MeshletRenderer.MODE_LIT);
port.add(new ClusteredForwardShading(geometry, main, colorFormat, depthFormat))
    .add(new ClusteredLights(lights, main))
    .add(shadows.view(main))                          // every light's shadows (Shadows, below)
    .output(Keys.SCENE_COLOR, target);
port.replace(new VisibilityBufferShading(geometry, main, colorFormat, depthFormat));
port.size(newWidth, newHeight);                       // a resized window: the graph rebuilds at the new size
```

- `ClusteredForwardShading` shades as the meshlets are drawn, so pixels covered later are shaded too.
- `VisibilityBufferShading` draws only IDs (instance, meshlet, triangle: 8 bytes per pixel) and then shades each pixel
  once in a compute pass. That pass rebuilds the triangle through the same vertex stage, intersects the pixel's ray
  with it for barycentrics and texture gradients, and uses the same shading. Needs `VulkanDevice.primitiveIds()`.

Both light each pixel with the light list's lights for it (and the sky), or with the sun without a light list, and draw the same image
(tested; the screenshots differ by 0.014 of 255 on average). GPU time of the whole frame, 1920 x 1080:

| Scene | Clustered forward | Visibility buffer |
|---|---|---|
| 30,000 rocks on the terrain, the sun | 1.27 ms | 0.87 ms |
| the same, 256 point lights | 1.48 ms | 0.93 ms |
| the same, 4,096 point lights | 5.56 ms | 1.71 ms |
| 3,000 rocks, 1,024 point lights (`Screenshots.renderLights`) | 1.86 ms | 0.74 ms |

**Every color pass by material type** (#67 and its follow-up). A `[MaterialType]` gets forward fragment entry points
generated too (`material-types/entryForward*.slang.template`: per raster bin, from full vertices and on the lean mesh
path), and each shading bin (a type and the features its materials show, numbered as the visibility buffer numbers
them) draws with pipelines of its type's entry points, its features a specialization constant, made for the raster
bins its materials draw in. No shader branches on the type, and every type shades as itself, on every path that shades
color:

- **Forward, meshlets** (indexed and mesh shaders): after culling, a view drawn shaded has its lists split by shading
  bin (`meshlets/split.slang`: count, lay out, scatter, three small dispatches per cull): each (raster bin, shading
  bin) draws with one indirect draw from its part, and on the indexed path the expansion writes each part's indices in
  a range of its own. The blended bin draws the same way, into weighted transparency's targets, as opaque, or sorted
  back to front over the scene ([Transparency](#transparency)).
- **The visibility buffer**: opaque pixels are shaded per type by its compute passes; its blended bin, drawn after the
  IDs, splits the view's blended list alone (the opaque bins keep their lists for the shared ID pipelines) and draws by
  type like forward.
- **Classic LOD**: the selection cuts each draw list in a part per shading bin, room for `maxDraws` draws each (where
  the list is drawn shaded: every list of a shaded view, the blended ones of a visibility buffer's view), and each
  part draws with one `vkCmdDrawIndexedIndirectCount` of its type's pipeline, behind the classic vertex stage.
- **Outside a frame graph** (`MeshletRenderer.record`, `drawView`): views culled there are split too, and shaded draws
  go by type.

A shading bin an instance first draws rebuilds the graph with its pipelines before it draws (made ahead: below); a
material of a bin made after the graph built is left out of that frame (never drawn as another type). Depth, shadow
and ID passes keep shared entry points: they read the slot alone (alpha masking from its cutoff and mask texture),
whatever the type, but for types with geometry hooks.

**Geometry hooks** (the first of #68's): a type may decide where its surfaces are, in every pass. Its parameters'
attribute names the hooks it has beside the features it allows (`[MaterialParameters(GEOMETRY_KEEP)]`; the build
hands the mask on, and `MaterialType.geometry()` reads it), and the type overrides the hook's function, which
`IMaterial` gives every type by default:

```slang
[MaterialParameters(GEOMETRY_KEEP)]
public struct DissolveParameters { [Color] public float3 color = float3(1, 1, 0); public float cell = 0.25; };

[MaterialType("dissolve")]
public struct Dissolve : IMaterial
{
    public typealias Parameters = DissolveParameters;
    public static Surface surface(DissolveParameters p, MaterialRef m, SurfaceInput in) { ... }
    public override static bool keep(DissolveParameters p, MaterialRef m, SurfaceInput in) // false: a hole
    {
        int2 cell = int2(floor(in.uv / p.cell));
        return ((cell.x + cell.y) & 1) == 0;
    }
};
```

- **The same holes everywhere.** The hooks a type has are a specialization constant (`GEOMETRY`) of all its pipelines,
  so a hook runs in every pass or in none: its color entry points discard where `keep()` says (forward, both mesh
  paths, classic LOD, blended), and a type with hooks gets generated depth and ID entry points
  (`material-types/entryGeometry.slang.template`: depth, masked depth, IDs and classic LOD's IDs, from full vertices)
  that discard alike, so its shadow maps, depth passes and visibility buffer show the holes its color does. A `keep()`
  without the attribute's bit runs nowhere.
- **Only those types pay.** Depth, shadow and ID passes keep one shared pipeline per raster bin for every type without
  hooks. Where instances take materials of types with hooks (`Materials.drawsGeometry`; such types numbered as they
  are registered, the number in the slot beside the shading bin), a depth or ID view's opaque lists are split after
  culling by that number (0: no hooks), and each part draws with its pipeline: the shared one, or the type's. Classic
  LOD cuts its depth and ID lists the same way. Until then nothing is split and the passes draw as before.
- **What it costs** (`ForwardShadingBenchmark.depthByGeometryHooks`, RX 9070 XT, 2026-11-03): a depth draw of the
  30,000 rocks through a camera of its own (a shadow map's), 5 rounds after 4 uncounted ones, ranges of the rounds'
  medians; culling includes the split, the depth pass is the draws:

  | Path | Types with hooks | Frame (GPU) | Culling | Depth pass |
  |---|---|---|---|---|
  | mesh shaders | none | 0.591-0.663 ms | 0.123-0.128 ms | 0.450-0.490 ms |
  | mesh shaders | dissolve registered, no rock takes it | 0.590-0.618 ms | 0.122-0.124 ms | 0.446-0.457 ms |
  | mesh shaders | 1 rock in 12 dissolving | 0.642-0.654 ms | 0.118-0.119 ms | 0.491-0.500 ms |
  | indexed | none | 0.626-0.634 ms | 0.207-0.216 ms | 0.418-0.443 ms |
  | indexed | dissolve registered, no rock takes it | 0.619-0.683 ms | 0.207-0.215 ms | 0.418-0.440 ms |
  | indexed | 1 rock in 12 dissolving | 0.659-0.691 ms | 0.223-0.225 ms | 0.429-0.434 ms |

  With no type with hooks drawn, depth takes the path it took before them: nothing split, the shared pipelines. With
  dissolving rocks the frame takes 0.03-0.06 ms more: their own pipeline, and on the indexed path the split.
- **Pipelines by pass** (`geometryHooksCutTheSameHolesInEveryPass`, a dissolving quad drawn on both meshlet paths and
  classic LOD): 12 color pipelines of types, 18 depth (shared and dissolve's per opaque raster bin: 6 per meshlet path,
  6 for classic LOD's lists), 18 ID; with no type with hooks, depth and IDs make none beside the renderer's shared ones.
- **Displacement joins the same way** (#68, not built): another bit (`GEOMETRY_DISPLACE`), a `displace()` with the
  default doing nothing, run by the vertex stages of a type's pipelines (its geometry entry points take vertex stages
  then), its bounds grown for culling.

**Pipelines made ahead.** A renderer keeps the pipelines of material types in one store (`ShadingPipelines`, through
the device's pipeline cache), and has those a material will draw with made on a thread of its own when the material
is written: as a table is set (every bin it has), and as a material first draws in a shading bin and raster bin
(`Materials` tells its listeners): its forward ones for the path frames take, classic LOD's, and a type's depth and ID
ones where it has geometry hooks. Graphs build only for bins instances draw: a material counts once an instance takes
it (`GpuScene` writing an instance, or mesh particles naming their meshes' materials, `Materials.reference`), so adding
one rebuilds nothing, and the frame where an instance first takes it rebuilds the graph with pipelines already made.
Wanted before it is made, a pipeline is made then, or, being made already, waited for, which takes no longer: making
ahead never delays a material's first appearance.

Measured with `ForwardShadingBenchmark` on the RX 9070 XT, 2026-11-03: the 4-bin table drawn, a material of a bin no
table drew before added (its pipeline cold: a specialization constant never used), then rock 1 takes it in front of the
camera; CPU time of recording frames, three runs each:

| A material of a new bin | Before (every color pass by type, 866cdaf) | Made ahead |
|---|---|---|
| added, frames on for 1 s, then taken | the frame after the add 155-201 ms (compiling); the first draw 0.09-0.15 ms | the frame after the add 0.05-0.07 ms; the first draw 1.3-2.4 ms (the rebuild); the pipeline made off the frames' thread in 170-195 ms |
| added and taken in the same frame | the first draw 166-199 ms | the first draw 183-191 ms, 167-190 ms of it waiting for the pipeline being made |

The other frames of the second they ran took up to 10-20 ms in both, also in runs that compiled nothing: this
benchmark's frames as the JIT compiles them (pitfalls.md, "an optimizing compile stops the thread").

One shader for every type is gone (`render.forwardShading` with it). It drew types other than metallic-roughness as
metallic-roughness's defaults (a glow material did not glow, car paint had no flakes, tinted ignored its buffer). A
correct one was built to decide: the shader as the build would generate it, its surface a switch over the material's
type calling each registered type's own `surface()`, features read from the material (prototype in commit 8432bf5,
`test/singleForward.slang`). Measured against by type with `ForwardShadingBenchmark` (`TRUESIM_BENCHMARK=true`) on an
RX 9070 XT at 1920 x 1080, 2026-11-03: 30,000 rocks on the terrain (87,193 meshlets drawn), the rocks' materials spread
over 1, 4 or 12 shading bins (metallic-roughness; with glow, tinted and car paint; and with 8 feature combinations of
metallic-roughness), occlusion culling on. Configurations took turns in 5 rounds after an uncounted warm-up round;
ranges of the rounds' medians. GPU frame time from timestamps around batches of 8 frames; culling (both phases, the
split and the indexed expansion included) and the forward draws (both phases) from the graph's pass timings; CPU time
of recording a frame:

| Path | Bins | Shading | Frame (GPU) | Culling | Forward draws | Record (CPU) | Draws |
|---|---|---|---|---|---|---|---|
| mesh shaders | 1 | by type | 1.131-1.569 ms | 0.122-0.150 ms | 0.964-1.187 ms | 0.024 ms | 2 |
| mesh shaders | 1 | one, generated | 1.175-1.341 ms | 0.124-0.127 ms | 0.975-1.022 ms | 0.022-0.023 ms | 6 |
| mesh shaders | 4 | by type | 0.945-0.956 ms | 0.127-0.129 ms | 0.781-0.790 ms | 0.026-0.027 ms | 8 |
| mesh shaders | 4 | one, generated | 1.139-1.161 ms | 0.123-0.125 ms | 0.945-1.022 ms | 0.022-0.023 ms | 6 |
| mesh shaders | 12 | by type | 1.053-1.077 ms | 0.137-0.140 ms | 0.873-0.885 ms | 0.032-0.033 ms | 24 |
| mesh shaders | 12 | one, generated | 1.184-1.217 ms | 0.105-0.139 ms | 0.983-1.112 ms | 0.022-0.023 ms | 6 |
| indexed | 1 | by type | 0.774-0.814 ms | 0.213-0.220 ms | 0.544-0.552 ms | 0.026-0.027 ms | 2 |
| indexed | 1 | one, generated | 0.816-0.820 ms | 0.205-0.207 ms | 0.555-0.570 ms | 0.024-0.026 ms | 6 |
| indexed | 4 | by type | 0.748-0.761 ms | 0.224-0.227 ms | 0.496-0.499 ms | 0.029 ms | 8 |
| indexed | 4 | one, generated | 0.798-0.809 ms | 0.190-0.208 ms | 0.553-0.556 ms | 0.025 ms | 6 |
| indexed | 12 | by type | 0.840-0.845 ms | 0.264-0.266 ms | 0.533-0.539 ms | 0.035 ms | 24 |
| indexed | 12 | one, generated | 0.849-0.857 ms | 0.207 ms | 0.590-0.597 ms | 0.025 ms | 6 |

The mesh path's 1-bin "by type" range comes from one slow round (the others 1.131-1.150 ms). Registers of the fragment
stages (`VK_AMD_shader_info`): the generated shader 132-134 VGPRs and 70-74 SGPRs, so 11 waves per SIMD; by type
61-94 VGPRs (glow 61, metallic-roughness 82, with a coat and sheen 94), 16 waves. Cold compiles (a specialization
constant never used, so the driver's disk cache had none): the generated shader 320-350 ms, a pipeline by type 80-155
ms (glow 80-115, metallic-roughness 110-145, with a coat and sheen 135-155); the old branching shader took 500-540
ms. By type the draws are faster with any number of bins and the frame never slower; the generated shader's only
gains were a few microseconds of recording and no pipeline to compile for a new bin, which making pipelines ahead
takes away (above). It was not kept.

Pipelines by type: one per shading bin and raster bin drawn, 1, 4 and 12 for the tables above; warm, a table's took
1-4 ms in all.

On the integrated GPU (#151, 2026-11-03), by type does not show a fix: `SceneSyncTest` lost the device in 15 of 36
tests with the old single shader; by type, the 10 tests that ran failed (7 device losses, 3 failed submits) before the GPU
dropped out of the device list. Both times the first loss came in a test drawing metallic-roughness materials only
(faults at 0x2000000000 and at 0x0). One run each; no run of the code before this change on the same driver state.

Next (#46): light binning, shadows per light, buckets, transparency and post effects as swappable techniques.

### Writing a shading hook

What a library adds to every way of shading without the core naming it comes through **shading hooks**: global
illumination's light from all around, fog, aerial perspective and volumetrics along the view ray, weather's wetness or
snow on surfaces. They run in forward shading, the visibility buffer, both blended transparencies, the sky, the
composites of ambient occlusion and reflections, the reference path tracer's hits and the probes' hits. The engine's
own probe volume is one (`probes/hook.slang`); it leaves with global illumination.

A hook is a Slang struct marked `[ShadingHook("name")]` (`hooks.slang`) conforming to the interface of each **hook
point** it has, the compiler checking its functions, with its parameters as a record of its own:

```slang
import hooks;

[ShadingHookParameters]                       // a Java record is generated for it, as for material records
public struct AcmeFogParameters
{
    public float density = 0.02;
    public float3 color = float3(0.6, 0.7, 0.8);
    public uint64_t volume;                   // addresses as Java longs: what only the frame knows
};

[ShadingHook("acme.fog")]
[ShadingHookAfter("acme.rain")]               // ordered after another hook, where there is one (or ShadingHookBefore)
public struct AcmeFog : IMediaHook
{
    public typealias Parameters = AcmeFogParameters;

    // Between the camera and the shaded point: radiance * T + L, transmittance * T
    public static void media(ShadingPoint p, AcmeFogParameters* fog, inout ViewRay ray)
    {
        float t = exp(-fog[0].density * length(p.position));
        ray.through(float3(t), (1 - t) * fog[0].color);
    }
};
```

- **The points.** `ISurfaceHook.surface(p, params, inout Surface s)` changes a surface before it is lit, in world
  terms (`p.world()`, `p.camera` and `p.cameraLow`: the same for every view), at rasterized pixels, the visibility
  buffer's, ray hits and probe hits alike; never its alpha (the dispatch keeps it). A hook puts a coat of its own (a
  water layer, `s.clearcoat`) only where `p.flags & SHADING_COAT_ALLOWED`, which `Material.hookCoat(true)` sets.
  Opting out is per hook, of any point: a material refuses hooks by name (`Material.refuseHooks("weather.snow")`: a
  bit per hook after its feature table, tested in the dispatch before each hook; names the shaders lack are ignored),
  and an override entry switches hooks off or on for instances (below); a material's refusal wins.
  `IIndirectHook.indirect(p, params, q, inout AroundLight light)` gives the light from all around, asked once per point
  for what it uses (`q.wants`, `AROUND_*`): diffuse light and how much of it the hooks cover, specular radiance along
  `q.direction` at `q.roughness` and its coverage, the environment's visibility around the direction and the coat's,
  the back side's diffuse light. `IMediaHook.media(p, params, inout ViewRay ray)` changes the light on its way to the
  camera; the sky is a point at infinity (`p.path == SHADING_SKY`). `ShadingPoint` also gives the geometric normal, the
  pixel (`NO_PIXEL` off screen) and how the point is shaded (`SHADING_RASTER`, `SHADING_COMPOSITE`, `SHADING_RAY_HIT`,
  `SHADING_PROBE_HIT`, `SHADING_SKY`).
- **Imports.** A hook imports `hooks`, `bindless`, `shading` and `lights`, and its own library's modules; never a
  shading path's (`indirect`, `meshlets.*`, `surfaces`, ...): it runs inside all of them (`ShadingHookImportsTest`).
- **One dispatch, merged at build time.** build-logic's `truesim.slang` merges the hooks of a project and its libraries,
  in their order, into `shading-hooks/dispatch.slang` (from this module's `shading-hooks` templates), which
  `indirect.slang` includes. Hook `i` runs under its bool specialization constant, id 1024 + `i`, and its bit of the
  view's hook block. A pipeline must set every such constant its shader declares (vulkan's `Specialization` refuses
  one that does not), so a shading path made without the view's hooks fails rather than silently running none.
- **Listings, found on the classpath.** A project whose shaders declare hooks gets a listing (`<javaPackage>.<Project>
  ShadingHooks`, or `slang { shadingHooks = ... }`) and variants of its libraries' shaders that shade, rebuilt with
  every hook it sees. Listings are services: before a device loads its first shader it takes the one holding every
  other one's hooks and loads its variants wherever the engine asks for the libraries' shaders
  (`VulkanDevice.shaderVariants()`, which takes no replacement once a shader is loaded). A game seeing two hook
  libraries that know nothing of each other lists them in its own build (`slang { shadingHooks =
  "com.example.game.GameShadingHooks" }`); the engine fails naming that line otherwise.
- **Values in layers, one writer.** Every hook has a record in each view's hook block (`ShadingHookBlock`, a copy per
  frame slot, host-visible, its defaults where the view runs it not), written once a frame in one piece. What content
  says goes in a scene component (engine-scene's `ShadingHookComponent`: the hook's values by field name, on or off,
  saved with the scene; `ShadingHookSync` hands them to `ShadingHookValues`). A hook with values in the scene runs in
  every view that shades, with those values, needing no Java. A library's technique adding the hook to a view
  (`ShadingHooks.add`, modifying `Keys.SHADING_HOOKS`) is then its only writer there: its `ShadingHookSource` gets the
  scene's record and merges what each frame knows (a graph buffer's address, regions culled to the view, time):

  ```java
  public List<Key<?>> modifies() { return List.of(Keys.SHADING_HOOKS); }   // built before the view's shading
  public void build(GraphBuilder g) {
      Buffer volume = ...;
      ShadingHooks.of(device).add(g, "acme.fog", new ShadingHookSource() {
          public int bytes() { return AcmeFogParameters.BYTES; }
          public boolean active() { return enabled; }                       // its bit, frame by frame
          public void write(PassContext ctx, ShadingHookParameters authored, MemorySegment into, long offset) {
              ((AcmeFogParameters) authored).withVolume(ctx.address(volume)).write(into, offset);
          }
      }).read(volume);                                                      // shading reads it
  }
  ```
- **Per-instance overrides.** Switches and values can differ per instance, layered over the view's: fog thinner in
  a house, snow off under a roof. `GpuScene.hookOverrides()` (`ShadingHookOverrides`) holds numbered entries, each
  with, per hook, whether it runs (`turn(entry, hook, false / true / null)`: off, on, or as the view) and the fields it
  sets (by field name, or a whole record), and gives each instance one entry or none. Wherever a point of an instance
  is shaded (rasterized, the visibility buffer, ray and probe hits, the media between it and the camera) every hook
  runs as the entry switches it (a material refusing it wins; switched on, it runs where the view's pipelines have it)
  and reads the view's record with the entry's fields in place; only the sky, which has no instance, keeps the view's.
  In content, engine-scene's `ShadingHookOverride` component (a hook, the fields it sets, and on/off/null;
  `ShadingHookOverride.off("weather.snow")`) on a node counts for its subtree, the
  nearest overriding node at or above a renderable giving its entry (`ShadingHookOverrideSync`, after the scene sync each
  frame); a library drawing a node itself asks `entryOf(node)` (effects-particles gives each emitter its node's). Each
  view's block carries the entries' merged records (composed each frame, a technique's source adjusting each in
  `ShadingHookSource.override`), growing as they do (`render.shadingHooks.blockGrowth`, no cap); the instances' entries
  are a host-visible uint per instance id, growing with the scene. Change them between frames, from the scene's thread:

  ```java
  ShadingHookOverrides overrides = gpuScene.hookOverrides();
  int inside = overrides.add();
  overrides.set(inside, "acme.fog", values);   // MaterialValues: the fields held override the view's
  overrides.turn(inside, "acme.snow", false);  // no snow inside (null: as the view again)
  overrides.assign(houseInstance, inside);     // 0: none again; removed instances drop theirs
  ```
- **Compiled in, switched by bits.** A view's pipelines are made with the hooks it runs (`render.shadingHooks.specialize`
  `VIEW`, the default) or with every hook any view of the device has run (`UNION`: one set of pipelines for all views;
  `ShadingHooksBenchmark` measures what a view pays for hooks it runs not). Either way a hook turns on and off from frame
  to frame by its bit (`ShadingHookSource.active`, `ShadingHookValues.on`), with no new pipelines or graph; a hook added
  or removed rebuilds the graph, and pipelines made ahead are made for every set in use. On an RX 9070 XT (30,000
  rocks, 1920 x 1080, GPU per frame and the CPU recording it, ranges of 5 rounds' medians; 2026-11-07, without JaCoCo;
  the override rows give every 10th rock an entry of the fog, one shared or one each):

  | shading | configuration | GPU per frame | CPU recording |
  |---|---|---|---|
  | forward | no hooks | 1.251-1.276 ms | 0.043-0.061 ms |
  | forward | fog, per view | 1.271-1.304 ms | 0.046-0.050 ms |
  | forward | fog, union of 5 | 1.334-1.367 ms | 0.045-0.050 ms |
  | forward | none on, union of 5 | 1.326-1.351 ms | 0.045-0.049 ms |
  | forward | fog, 3,000 rocks overridden, 1 entry | 1.284-1.306 ms | 0.045-0.050 ms |
  | forward | fog, 3,000 rocks overridden, 3,000 entries | 1.316-1.320 ms | 0.113-0.139 ms |
  | visibility buffer | no hooks | 0.944-0.949 ms | 0.044-0.047 ms |
  | visibility buffer | fog, per view | 0.958-0.960 ms | 0.046-0.048 ms |
  | visibility buffer | fog, union of 5 | 0.983-0.989 ms | 0.047-0.066 ms |
  | visibility buffer | none on, union of 5 | 0.983-0.994 ms | 0.046-0.050 ms |
  | visibility buffer | fog, 3,000 rocks overridden, 1 entry | 0.949-0.959 ms | 0.046-0.066 ms |
  | visibility buffer | fog, 3,000 rocks overridden, 3,000 entries | 0.953-0.960 ms | 0.102-0.137 ms |

  Hooks compiled in but off (the union: the test listing's probe volume, an indirect and two surface hooks) cost a
  view about 0.03-0.06 ms (2-5 %): `VIEW` stays the default, `UNION` trades that for one set of pipelines across views.
  Override entries cost the shading nothing measurable against the fog alone (an entry looked up per point); the CPU
  composes every entry's records into each view's block each frame, about 0.02-0.03 us an entry (0.07-0.09 ms for
  3,000). Testing each hook against the material's refusals and the entry's switches (none set here) left the fog's
  frame where it was before them (forward 1.269-1.298 ms, visibility buffer 0.962-0.966 ms in the run before, the
  same day).
- **Glass and the sky.** The scene seen through a transmissive surface came through the media already: shading takes
  the media once for it, less the light they add in front of the glass (`(own) * T + L + through * (1 - F) * (behind -
  L)`). The sky background takes the media as a point at infinity.
- **Known approximations.** The reference path tracer runs surface hooks at its hits but no media: its images are
  unfogged. Screen-space reflections and ambient occlusion's bounce take the color of already fogged pixels, so what
  they add is fogged once more, slightly, where the media are thick. A probe volume's hits read its anchor view's hooks
  (surface hooks are the same in every view) with its own record for the probe hook: its further bounces come from
  itself.

`ShadingHooksTest` (the test sources' `test/hooks/*.slang`, merged with the engine's into `TestEngineShadingHooks`)
draws through a media, an indirect and a surface hook on every way of shading and geometry path; checks that a hook
compiled in but off, or made in only by the union, leaves the image the same, pixel for pixel, and turns on and off
without a rebuild; runs a hook from the scene's values alone and merged by a technique; fogs glass once and the sky;
leaves alpha alone and coats only where allowed; leaves a material refusing a surface hook (and an instance whose
entry switches it off) unchanged while a second surface hook still changes it, a refusal winning over an entry
switching the hook on, an entry switching on a hook the view has off; gives instances side by side
override entries setting one field each (the other the view's), takes one away, turns a technique's fog off for one
instance (bit for bit as without fog) with 41 entries in the block. `ProbeVolumeTest` checks a surface hook at the
probes' hits (the white furnace with its walls tinted) and an override entry there (tinted only with the entry).
engine-scene's `ShadingHookOverrideSyncTest` follows override components through nesting, reparenting, replacing and
removal.

### Transparency

Blended materials (`Material.alphaBlend`, glTF's `BLEND`) and transmissive ones are drawn over a view's opaque scene by
the view's transparency technique (`Transparency.ID`, one per view, at `Anchor.TRANSPARENT`). Two are correct and trade
off, and `render.transparency` (`Transparency.SLOT`, read by `ChosenTransparency`; never off) picks a kind, the graph
rebuilding when it changes:

```java
view.port().add(new ChosenTransparency(geometry, view));                // render.transparency: sorted by default
Transparency.SLOT.choose(graph.settings(), Transparency.WEIGHTED);      // or SortedTransparency, ... directly
```

- **`sorted`** (`SortedTransparency`, the default): blended meshlets sorted back to front on the GPU and blended over
  the scene's color ("over", by alpha), testing the opaque depth. Exact for surfaces apart: red glass in front of blue
  reads differently from blue in front of red, a nearly opaque layer hides what is behind it, and the far wall of a
  glass bowl draws before its near wall (CompareTransmission's bowl, doubled under weighted blending, is clean). After
  the culling's split (and before the indexed path's expansion, which then writes indices in the sorted order),
  `meshlets/sort.slang` keys each blended meshlet by the squared distance of its bounding sphere's center from the eye
  (24 bits, farthest first) and sorts with the core's radix sort (`blocks/sort.slang`, `RadixSort`, 6 passes); the sorted
  list is cut into runs of one shading bin, and each run is a (run slot, shading bin) entry of the split lists'
  header, drawn by its type's pipeline, so the order holds across material types.
  `render.transparency.sorted.runs` (`Transparency.SORTED_RUNS`, 16) run slots are drawn, each an indirect draw per shading bin drawing blended; a view
  with more runs (shading bins interleaving in depth) has its meshlets ordered by shading bin first (two more radix
  passes), each shading bin's back to front. Per view: each eye of a headset, each window sorts from its own eye.
  Forward and visibility buffer views, both meshlet paths. Classic LOD sorts each instance's draws by its mesh's
  bounding sphere within each shading bin (and mirrored or not), the shading bins one after another.
  Approximate where surfaces intersect, or where one meshlet's center misorders it against another overlapping it
  (a large meshlet across a small one): the order is per meshlet, not per pixel.
- **`WEIGHTED`** (`WeightedBlendedTransparency`): weighted blended order-independent transparency (McGuire and Bavoil),
  one pass into two targets and a resolve, no sort. Cheap for masses of thin layers of similar color; the same image
  whatever the order (red over blue reads as blue over red: `SortedTransparencyTest` measures it 46 of 255 off the
  exact composite of two layers of alpha 0.6).
- **`OPAQUE`** (`OpaqueTransparency`): drawn as opaque, into the color and depth.

Transmissive surfaces sample the opaque scene behind them (`SceneBehind`, copied before transparency), refracted; under
`SORTED` a transmissive layer covers the blended ones behind it as far as it is opaque. Particles (`ParticleDraw`) are
drawn after the blended surfaces, emitter by emitter back to front, or before glass (`ParticleDraw.beforeGlass`), not
interleaved with them: emitters as entries of the meshlet sort would need their pipelines in its runs.

What it costs (`TransparencyBenchmark`, RX 9070 XT, 2026-11-03, 1920 x 1080, 5,000 rocks on the terrain and glass
panes of 2 triangles each, double-sided, alpha 0.3; 4 rounds after a warm-up round, ranges of the rounds' medians;
culling includes the sort; with 100,000 panes 82,212 are in view):

| Path | Panes | Way | Frame (GPU) | Culling (+ sort) | Blended draw | Resolve | Recording (CPU) |
|---|---|---|---|---|---|---|---|
| mesh shaders | 100 | weighted | 0.496-0.500 ms | 0.103-0.107 ms | 0.012 ms | 0.006 ms | 0.027-0.032 ms |
| mesh shaders | 100 | sorted | 0.534-0.574 ms | 0.145-0.157 ms | 0.017-0.018 ms | - | 0.054-0.064 ms |
| mesh shaders | 10,000 | weighted | 0.693-0.830 ms | 0.116-0.136 ms | 0.183-0.231 ms | 0.016-0.017 ms | 0.027-0.040 ms |
| mesh shaders | 10,000 | sorted | 0.718-0.981 ms | 0.169-0.208 ms | 0.185-0.246 ms | - | 0.054-0.055 ms |
| mesh shaders | 100,000 | weighted | 2.837-2.871 ms | 0.271-0.274 ms | 2.060-2.078 ms | 0.035 ms | 0.027-0.028 ms |
| mesh shaders | 100,000 | sorted | 2.689-2.704 ms | 0.328-0.331 ms | 1.905-1.923 ms | - | 0.054-0.059 ms |
| indexed | 100 | weighted | 0.387-0.389 ms | 0.131 ms | 0.011-0.012 ms | 0.006 ms | 0.030-0.031 ms |
| indexed | 100 | sorted | 0.421-0.429 ms | 0.178-0.192 ms | 0.008 ms | - | 0.056-0.058 ms |
| indexed | 10,000 | weighted | 0.586-0.641 ms | 0.167-0.174 ms | 0.155-0.165 ms | 0.018 ms | 0.030-0.039 ms |
| indexed | 10,000 | sorted | 0.643-0.683 ms | 0.231-0.244 ms | 0.146-0.157 ms | - | 0.056-0.057 ms |
| indexed | 100,000 | weighted | 2.207-2.248 ms | 0.397-0.399 ms | 1.457-1.464 ms | 0.018 ms | 0.030-0.033 ms |
| indexed | 100,000 | sorted | 2.054-2.062 ms | 0.440-0.463 ms | 1.241-1.300 ms | - | 0.056-0.059 ms |

The sort costs 0.04-0.07 ms of GPU time at every count (32 dispatches, most of them short); drawing sorted, into the
scene's color alone, costs no more than into weighted blending's two targets, and less with many panes, with no
resolve. Panes of two shading bins interleaving in depth (more runs than slots: ordered by shading bin first) cost
up to 0.10 ms more (frames with 100, 10,000 and 100,000 panes: mesh shaders 0.538-0.619, 0.764-0.974 and 2.680-2.689 ms; indexed
0.444-0.484, 0.665-0.738 and 2.149-2.155 ms). Recording takes 0.03 ms more of CPU time. Hence sorted by default; weighted stays for masses of
layers where its order-independence is wanted.

**Opaque draws front to back: measured, not kept.** Sorting a forward view's opaque split lists nearest first within
each shading bin (the same sort, every occlusion phase) against drawing them as culled, two-phase occlusion culling on,
`TransparencyBenchmark`'s scene with rocks only (2026-11-03, the settled rounds 2 and 3 of 4): 30,000 rocks on mesh
shaders 2.582-2.612 ms against 2.409-2.410 ms sorted (draws 0.10 ms shorter, the sort 0.19 ms); on the indexed path
1.673-1.686 ms against 1.818-1.820 ms; 3,000 rocks 0.367-0.413 ms against 0.496-0.623 ms (mesh shaders) and
0.296-0.324 ms against 0.429-0.526 ms (indexed). The occlusion culling's early phase already draws much of what is in
front first, so the draws gain about as much as the sort costs, and lose where the scene is light: no clear win, so
the sort is not offered for opaque draws.


### Surface outputs and screen-space reflections

**Surface outputs** (`SurfaceOutputs`): the opaque shading pass can also write, per pixel, what later passes need of
its surface, as images under `Keys.SURFACE_NORMAL`, `SURFACE_MATERIAL` and `SURFACE_REFLECTANCE`: the shading normal in
view space (x right, y down, z forward; octahedral, two 16-bit signed values in an `R32_UINT`), specular F0 and
perceptual roughness (`R8G8B8A8_UNORM`), and the reflectance: what the environment's reflection along the mirror
direction was weighed by in the pixel's color (the split sum's scale and bias, the occlusion, what a sheen or clear coat
lets through; 0 where shading bent the reflection by anisotropy, or nothing covers the pixel). Forward shading on both
meshlet paths and classic LOD writes them as three more color attachments (generated `*SurfaceMain` entry points per
type, pipelines `ShadingPipelines.COLOR_SURFACES`, made ahead once a graph asked for them), the visibility buffer's
shading as storage images (its variants with `SURFACES` set). Only when a technique of the view asks
(`SurfaceOutputs.Consumer.wantsSurfaces`, read while the graph builds through `GraphBuilder.techniquesInView`): without
one, no image, attachment, pipeline or shader write exists (the keys get images nothing writes, which take no memory
and fail a build that reads them). Post materials (decals, fog by normal, outlines) will take them the same way: a
technique implementing the consumer interface and requiring the keys. Blended and transmissive surfaces write none.

**Screen-space reflections** (`ScreenSpaceReflections`, one per view under `ScreenSpaceReflections.ID`, at
`Order.SCREEN_SPACE_LIGHTING`: after the background and ambient occlusion, before transparency), as the kind `render.ssr` (`ScreenSpaceReflections.SLOT`)
names:

- `off`: the environment's reflections alone; the surface outputs are not written for it.
- `LINEAR`: each pixel's mirror ray marched through the depth in screen space in equal steps (at least a pixel), where
  a straight line stays straight; the crossing refined by bisection; rays pass behind surfaces deeper than the
  thickness allows (below) and find what is behind them.
- `HIZ` (the default: under half of `LINEAR`'s time, below): the hierarchical march of AMD's FidelityFX SSSR over a
  nearest-depth pyramid (`DepthPyramid` with `nearest`: the occlusion culling's pyramid keeps the farthest depth,
  which would let rays pass through surfaces): cells the ray passes wholly in front of are skipped a level coarser,
  so long rays take few steps; the first surface the ray goes behind ends it, a hit as far as the thickness allows.
- **Thickness** (one depth layer cannot tell how far an object goes on behind what it shows): surfaces are slabs of
  `thickness` along their normal, so a ray behind the depth by `gap` along the view is `gap x |n.v|` into the surface
  it went behind (at least a quarter of `gap`); it meets the surface fully within the thickness, fading out to twice
  it. Seen face on that is the gap itself, so rays still pass behind thin poles; at silhouettes and under bulges, where
  the visible surface is seen at a grazing angle and the object goes on behind it, the gap a pixel shows jumps from
  one pixel to the next, and a test of the gap alone made neighbors flip between hit and miss (hairs along outlines, a
  dotted line of sky along contacts). The scene's color is taken at the pixel the ray went behind; the visible normal
  there is not tested against the ray (it is not the surface the ray met, and testing it flipped neighbors too).
- Ray-traced reflections (#379) will be another value of the same setting, filling the same hit image.

Both write a hit image (where, how far, how trusted) at `render.ssr.resolutionScale` of the view, and one composite
pass at full resolution reconstructs each pixel's reflection from the 3 x 3 hit texels around it (a tent, those
traced from the same surface only: same plane and facing), which antialiases outlines and fills a single ray's miss
among hits, and replaces the environment's reflection that shading added along the same mirror direction:
color + reflectance x trust x (scene at the hit - environment along the ray), the environment from the view's
indirect-light table (or the constant sky). Shading already weighed the environment by the reflectance, so energy
stays as it was: a wall as bright as the environment reflected instead of it leaves the image unchanged (tested). Rough
surfaces take the scene at the hit from a mip chain of the scene's color (`SceneBehind`'s), at the level whose texels
are as wide as the GGX lobe's cone where it meets the scene (the lobe's angle as a Phong lobe's, Uludag); since one
ray per pixel finds only the lobe's middle (an object against the sky would keep a sharp outline), a spatial filter
averages the hits and misses of the pixels within the cone's radius on the reflected image (12 taps, this frame only).
Where trust falls (the image's edges over `edgeFade`, the last fifth of `maxDistance`, a ray leaving the image or
finding nothing or passing behind what it finds, roughness from three quarters of `roughnessCutoff`) the environment's reflection
takes over smoothly. No history, no temporal accumulation: no added latency, no ghosting. Each view (each eye, each
window) has its own, within its render region under dynamic resolution. Blended and transmissive surfaces get none and
are not seen in reflections (drawn after it).

Settings: `render.ssr` (`off`, `linear`, `hiz`, default `hiz`; rebuilds), and each kind's tunables
(`ScreenSpaceReflections.March`, under `render.ssr.linear.*` and `render.ssr.hiz.*`): `maxSteps` (64), `thickness`
(0.25 m), `maxDistance` (100 m), `roughnessCutoff` (0.6), `edgeFade` (0.1 of the image), read as frames record, and
`resolutionScale` (1; rebuilds).

What it costs (`ReflectionsBenchmark`, RX 9070 XT, 2026-11-03, after the outline and contact fixes): 30,000 rocks of glossy metal (roughness 0.35) on a
glossy terrain (0.2), so nearly every pixel traces a ray, under a uniform environment, half-float color, 64 steps;
configurations in turns, 4 rounds after 2 uncounted ones, ranges of the rounds' medians, JaCoCo off. Frame: timestamps
around batches of 8 frames. Shading passes: the forward draws (both phases) or the visibility buffer's shading pass,
where the surface outputs are written. Reflection passes: the trace, the composite, the scene's mip chain and (HIZ)
the nearest-depth pyramid. "surface outputs only": a technique asking for them and reading nothing.

| Size | Shading | Reflections | Frame (GPU) | Shading passes | Reflection passes |
|---|---|---|---|---|---|
| 1080p | forward, mesh shaders | off | 1.339-1.356 ms | 1.177-1.186 ms | - |
| 1080p | forward, mesh shaders | surface outputs only | 1.343-1.355 ms | 1.215-1.225 ms | - |
| 1080p | forward, mesh shaders | LINEAR | 2.698-2.731 ms | 1.219-1.239 ms | 1.255-1.304 ms |
| 1080p | forward, mesh shaders | HIZ | 1.987-1.997 ms | 1.229-1.240 ms | 0.595-0.636 ms |
| 1080p | forward, mesh shaders | LINEAR, half | 2.067-2.086 ms | 1.225-1.235 ms | 0.665-0.692 ms |
| 1080p | forward, mesh shaders | HIZ, half | 1.811-1.825 ms | 1.215-1.233 ms | 0.419-0.451 ms |
| 1080p | forward, indexed | off | 0.965-0.977 ms | 0.701-0.717 ms | - |
| 1080p | forward, indexed | surface outputs only | 1.007-1.021 ms | 0.761-0.776 ms | - |
| 1080p | forward, indexed | LINEAR | 2.364-2.387 ms | 0.744-0.751 ms | 1.279-1.287 ms |
| 1080p | forward, indexed | HIZ | 1.629-1.643 ms | 0.745-0.753 ms | 0.576-0.582 ms |
| 1080p | forward, indexed | LINEAR, half | 1.734-1.757 ms | 0.756-0.770 ms | 0.678-0.699 ms |
| 1080p | forward, indexed | HIZ, half | 1.453-1.474 ms | 0.753-0.759 ms | 0.428-0.429 ms |
| 1080p | visibility buffer | off | 0.974-0.993 ms | 0.161-0.174 ms | - |
| 1080p | visibility buffer | surface outputs only | 0.982-1.043 ms | 0.170-0.178 ms | - |
| 1080p | visibility buffer | LINEAR | 2.279-2.327 ms | 0.170-0.172 ms | 1.257-1.268 ms |
| 1080p | visibility buffer | HIZ | 1.577-1.581 ms | 0.171-0.174 ms | 0.570-0.606 ms |
| 1080p | visibility buffer | LINEAR, half | 1.678-1.692 ms | 0.172-0.172 ms | 0.665-0.669 ms |
| 1080p | visibility buffer | HIZ, half | 1.418-1.427 ms | 0.172-0.174 ms | 0.422-0.443 ms |
| 4K | forward, mesh shaders | off | 3.067-3.074 ms | 2.886-2.901 ms | - |
| 4K | forward, mesh shaders | surface outputs only | 3.340-3.363 ms | 2.998-3.011 ms | - |
| 4K | forward, mesh shaders | LINEAR | 11.759-11.836 ms | 3.017-3.039 ms | 8.318-8.370 ms |
| 4K | forward, mesh shaders | HIZ | 5.642-5.648 ms | 3.142-3.156 ms | 2.201-2.206 ms |
| 4K | forward, mesh shaders | LINEAR, half | 7.358-7.377 ms | 2.964-3.019 ms | 4.099-4.121 ms |
| 4K | forward, mesh shaders | HIZ, half | 4.997-5.010 ms | 3.123-3.144 ms | 1.607-1.611 ms |
| 4K | forward, indexed | off | 2.390-2.396 ms | 1.805-1.812 ms | - |
| 4K | forward, indexed | surface outputs only | 2.686-2.691 ms | 2.020-2.026 ms | - |
| 4K | forward, indexed | LINEAR | 11.219-11.251 ms | 1.985-2.011 ms | 8.319-8.330 ms |
| 4K | forward, indexed | HIZ | 4.977-4.986 ms | 2.048-2.076 ms | 2.287-2.297 ms |
| 4K | forward, indexed | LINEAR, half | 6.675-6.687 ms | 1.983-1.996 ms | 4.114-4.137 ms |
| 4K | forward, indexed | HIZ, half | 4.351-4.354 ms | 2.041-2.081 ms | 1.675-1.678 ms |
| 4K | visibility buffer | off | 2.519-2.525 ms | 0.617-0.619 ms | - |
| 4K | visibility buffer | surface outputs only | 2.609-2.616 ms | 0.658-0.661 ms | - |
| 4K | visibility buffer | LINEAR | 11.220-11.258 ms | 0.661-0.666 ms | 8.258-8.469 ms |
| 4K | visibility buffer | HIZ | 4.998-5.000 ms | 0.683-0.685 ms | 2.192-2.197 ms |
| 4K | visibility buffer | LINEAR, half | 6.622-6.633 ms | 0.660-0.662 ms | 4.062-4.092 ms |
| 4K | visibility buffer | HIZ, half | 4.357-4.366 ms | 0.673-0.676 ms | 1.591-1.598 ms |

- **Surface outputs** cost nothing when no technique asks (no attachment or write exists; "off" is the build without
  them). Asked for, at 1080p: forward on mesh shaders 0.00 ms of frame, indexed 0.04-0.10 ms, the visibility buffer
  0.01-0.04 ms; at 4K 0.27-0.29, 0.29-0.30 and 0.09-0.10 ms (three more targets, 12 bytes a pixel).
- **HIZ against LINEAR**, at the same 64 steps: 0.57-0.64 ms against 1.26-1.30 ms of reflection passes at 1080p,
  2.19-2.30 against 8.26-8.47 ms at 4K (LINEAR samples the depth at every step and bisects each crossing). The 3 x 3
  reconstruction and the thickness along the normal added 0.15-0.18 ms (1080p) and 0.64-0.67 ms (4K) to HIZ's passes
  (before them: 0.41-0.45 and 1.55-1.63 ms).
  Hence `HIZ` by default; `LINEAR` stays for scenes where rays must pass behind thick surfaces to find what is
  behind them. Half-resolution tracing (`resolutionScale` 0.5) takes HIZ to 0.42-0.45 ms at 1080p and 1.59-1.68 ms at
  4K.


## Ray tracing

Where the GPU has it (`VK_KHR_acceleration_structure` and `VK_KHR_ray_query`), `VulkanDevice.rayTracing()` is true and
the bindless set gains acceleration structures (binding 3). Everything else runs without it. `RayTracing` builds the
scene for rays from the geometry pool:

```java
try (RayTracing rayTracing = new RayTracing(device, pool, 100_000)) { // at most 100,000 instances
    rayTracing.add(rock, rockMesh, rockMesh.levels().size() - 1);    // a bottom level from one detail level
    // per frame, in the frame graph: the top level from the renderer's instances, on the GPU
}
```

- **Bottom levels straight from the pool:** built from the pool's quantized positions (16-bit, with the mesh's box as
  the geometry's transform) where the GPU accepts that format, so no float copy of the vertices is made. Built for fast
  tracing, then compacted.
- **Streamed without stalls:** with a frame graph's frame slots (the pool's), `add` returns at once. The level's inputs
  go with the pool's uploads; it is built at the start of the frame after they arrive, traced from the frame after
  that build finished (its instances are left out until then), and compacted a few frames later, never waited for.
  `remove` takes it out of the next top level and frees it once no frame in flight traces it, so a pool mesh id can be
  reused at once. Without frame slots both wait for the GPU, as before. Streaming 12 meshes a frame at 90 Hz, frames
  with adds went from 6.1 ms on the render thread (up to 11.6) to 1.3 ms (`StreamBench --args=rays`).
- **Top level on the GPU, every frame:** a compute pass turns the renderer's instances into acceleration structure
  instances, and the top level is rebuilt from them. Nothing per instance is written on the CPU, and recording
  allocates nothing.
- **Scene content traced as drawn:** `resources.rayTracing(rayTracing)` gives every mesh a `SceneResources` uploads a
  bottom level (its finest detail level, or the one a function picks), built in frames as above, meshes uploaded
  before included; each leaves with its mesh.
- **Skinned and morphing copies refitted:** each deformed copy (`GeometryPool.addDeformed`, made by `SceneSync` for
  skinned and morphing renderables, or `rayTracing.addDeformed(copy, sourceData, level)` by hand) has a refittable
  bottom level read straight from its quantized positions, the build transform following the box it is posed in.
  `TopLevel` requests a pass after the deformation pass (`BottomLevelUpdates`, "bottom level refits") that builds new
  copies' levels and refits those skinned that frame, all in one command; the top level is then refitted too. Copies of
  one mesh share its triangles. A refit's error does not grow from refit to refit (it is the pose's distance from the
  one built), so copies are not rebuilt unless asked (`rayTracing.rebuildEvery(refits)`): with a build every 120
  refits, 1000 foxes' refits went from 0.30 to 0.37 ms a frame to 0.57 to 0.63, and tracing changed less than noise.
  Devices whose bottom levels cannot read the quantized positions leave copies out.
- **Instances rays do not trace** (holes, casting no shadows, the GPU's own ids, a mesh without a bottom level) are
  not in the top level at all: the CPU lists the rest for each build (`RayTracing`), with the bottom level each names,
  and the build holds only those. A refit keeps the very list of its build; any change to it builds anew (an instance
  turning inactive or active between a build and its refit is not allowed, and lost the device).
- **Shaders** import `raytracing.slang` for `occluded(scene, origin, direction, tMin, tMax)`, a ray query that stops at
  the first hit, and `triangleMaterial(scene, instance, primitive)`, the material id of a triangle hit.
- **Segments in batches** (`SegmentTracing`, for audio occlusion): a technique in the main view (it modifies
  `Keys.SCENE_COLOR` to be built, leaving it as it is) traces the batch submitted last, from any thread, one ray query
  per segment visiting every triangle on it, and reads back the nearest 8 crossings of each (instance, facing,
  material id) a frame or more later; `collect(ticket, words)` hands them over. Allocation-free per batch.

### Shadows as data: the `Shadows` owner

Which lights cast shadows, and how, is per-light data (engine#120, frame graph v2 §4.15): each light carries a
`ShadowSpec` (scene-core; on a `LightSource` component, which `LightSync` hands to `Lights.shadow(id, spec)`), and one
owner draws every light's shadows by it. Giving a light shadows, switching its method, size or softness, or turning
them off takes effect from the next frame without a graph build.

```java
Shadows shadows = new Shadows(geometry, lights).rayTracing(rayTracing); // rayTracing: optional
views.addPerView(shadows::view);                                         // or port.add(shadows.view(main))
sun.shadow(ShadowSpec.cascades(ShadowSpec.AUTO, ShadowSpec.AUTO, 300)); // on the LightSource component
lamp.shadow(ShadowSpec.maps(ShadowSpec.AUTO).auto(128, 1024));
torch.shadow(ShadowSpec.softRayTraced(0.05));                            // a 5 cm sphere: soft, denoised
lights.shadow(id, ShadowSpec.rayTraced());                               // or straight on the engine's lights
```

| Method | Spot, point lights | Directional lights | Evaluated by |
|---|---|---|---|
| `NONE` | - | - | - |
| `MAPS` | a tile of the shadow atlas; six (cube faces) for a point light; perspective, depth compared at view depth less a bias that grows with distance | one map fitted around each view's camera to `distance` | `PcfShadow` in the tile |
| `CASCADES` | as `MAPS` | `cascades` per view (practical splits, snapped to world texels), tiles of the atlas | `PcfShadow`, the cascade by view depth |
| `RAY_TRACED` | a mask per view: one ray per pixel to the light | one ray per pixel along the light | `MaskShadow` |
| `SOFT_RAY_TRACED` | rays to points of a sphere of radius `softness`, denoised | rays in a cone of angular radius `softness` (the sun: 0.0047), denoised | `MaskShadow` (the denoised value) |

- **One atlas for every map** (`shadows.atlas.size`, 8192 by default: 256 MiB of 32-bit depth, made the first time a
  light uses maps). Tiles are powers of two allocated in a quadtree; a light keeps its tile while its size stays.
  Tiles are drawn in one pass, each through a view of its own culled with the frame's other views (`DepthDraws`:
  geometry hooks too), into its rectangle (cleared, then viewport and scissor).
- **Drawn only when something changed.** A tile is drawn again when its light moved or its spec changed, a caster
  within its frustum moved, was added or removed (the scene's changes before they upload: `GpuScene.changedId`, against
  the instance's bounding sphere before and after), the materials' shapes changed (`Materials.shapesVersion`: masks and
  geometry hooks' fields updated in place), or the caster detail setting changed. A still light over a still scene
  keeps its tile, drawn once; a still camera keeps its cascades. The scene's `Mobility` is a hint for both (see
  Mobility below). Deformed copies dirty the tiles around them every
  frame; instances moved on the GPU (interpolated motion, mesh particles) dirty every tile while there are any.
  Which changed tiles a frame draws is `shadows.atlas.updates` (their light keeps its last map, or casts none, until
  drawn): `IMPORTANCE` (the default) draws a tile the frame it changes where its light shows at least
  `shadows.atlas.urgent` pixels (256: its reach's radius in the view showing it largest, times the spec's importance
  and its brightness against the brightest mapped light's; a light whose reach holds a view and every cascade always),
  and any tile that waited `shadows.atlas.lag` frames (2); the rest, most important and longest waiting first, take
  `shadows.atlas.draws` (64) a frame, or an even share of the waiting over the lag's frames where that is more (no
  burst of late tiles every few frames). `ALL` draws every changed tile; `CAPPED` at most `shadows.atlas.draws`, the
  rest waiting however long. `Shadows.stats()` and the profiler's counters (`Shadows.counters()`:
  `shadows.atlas.drawn`, `waiting`, `forced`, `maxWait`, `meanLag`) say what was drawn and how late.
- **AUTO sizes** (`ShadowSpec.AUTO`, within the spec's `minSize` and `maxSize`): a spot or point light's maps from how
  many pixels its reach covers in the views (`shadows.auto.scale` texels per pixel, times the spec's `importance`), a
  cascade's from the screen's pixel size at its start; rounded to powers of two, changed only past
  `shadows.auto.hysteresis` octaves from the halfway point, so tiles stay as the camera moves. Beyond
  `shadows.auto.cutoff` units from every view (0: no cutoff) an AUTO light casts none.
- **Budget:** over the atlas, the least important light's tiles halve first (not below its `minSize`), then the least
  important lights cast none (`Shadows.stats()`: shrunk, dropped).
- **Rays** (where a `RayTracing` is given; else those lights take maps): every traced light of a view is traced in one
  dispatch from the view's depth (the visibility buffer's own IDs draw; for forward shading a depth draw of its own),
  on the async queue. Soft lights take `shadows.soft.rays` rays a pixel (1) and the engine's denoiser
  (`shadows.soft.denoiser`: SVGF or REBLUR, both kept), its history restarting when a slot changes light. A light
  keeps its mask while traced; more traced lights than ever before in a view build the graph once more.
- **Shadow rays respect materials' shapes:** alpha masks as before, and **geometry hooks' holes** (`keep()`): instances
  of such types enter the top level non-opaque; a ray whose nearest candidate is of a hooked type waits in a queue
  (`shadows.rays.queue`), that type's generated keep entry point (`material-types/entryShadowKeep`) tests the point,
  and the ray goes on beyond a hole, up to `shadows.rays.keepRounds` holes (3); then it counts as blocked.
- **The graph's part:** `PassBuilder.when(supplier)` and `GraphBuilder.masked(supplier, build)` skip passes from data
  each frame (barriers kept, no build): the atlas pass while no tile is drawn, a soft slot's denoiser while unused,
  the traced passes while no light is traced. The atlas, more draw views and more traced slots are structure: they
  only ever grow, each growth one build.
- **The reference** (`ReferencePathTracer`) samples a light's size too: a light's `radius` (its spec's softness) turns
  its shadow rays to points of its disc or sphere, so soft shadows are checked against path-traced penumbras
  (`ShadowOwnerTest.softShadowsMatchThePathTracedReference`).
- **What it costs** (`ShadowsBenchmark`, RX 9070 XT, 2026-11-05, JaCoCo off; 30,000 rocks on the terrain, the
  visibility buffer; GPU time of the whole frame, and of the shadows' passes alone, medians of 60 frames, ranges of 3
  rounds; "before": the single techniques, in the same run):

  | Sun's shadows | 1920 x 1080 still | 1920 x 1080 turning | 3840 x 2160 still | 3840 x 2160 turning |
  |---|---|---|---|---|
  | unshadowed | 0.88 ms | 0.87 ms | 2.00 ms | 1.99 ms |
  | maps: one 4096 to 300 | 0.90 (0.00) | 2.33 (1.37) | 2.08 (0.00) | 3.56 (1.44) |
  | maps: one 4096 to 300, front faces | 0.90 (0.00) | 1.69 (0.72) | 2.08 (0.00) | 2.87 (0.75) |
  | cascades: 3 of 2048 to 300 | 0.90 (0.00) | 2.22 (1.28) | 2.06 (0.00) | 3.40 (1.29) |
  | cascades: 3 of 2048 to 300, front faces | 0.89 (0.00) | 1.65 (0.69) | 2.06 (0.00) | 2.81 (0.69) |
  | cascades: AUTO to 300 (4 cascades, 2048 at 1080p, 4096 at 2160p) | 0.89 (0.00) | 2.55 (1.60) | 2.09 (0.00) | 5.88 (3.72) |
  | before (removed since): `CascadedShadowMaps` 3 of 2048 | 1.58 (0.61) | 1.64 (0.68) | 2.76 (0.63) | 2.81 (0.69) |
  | ray traced | 1.15 (0.27) | 1.16 (0.28) | 3.10 (1.00) | 3.12 (1.02) |
  | before (removed since): `RayTracedShadows` | 1.13 (0.23) | 1.12 (0.24) | 2.96 (0.85) | 2.98 (0.88) |
  | soft ray traced, sun 0.0047, SVGF (2 steps) | 2.06 (1.16) | 2.08 (1.18) | 7.15 (4.82) | 7.26 (4.95) |
  | soft ray traced, REBLUR | 2.04 (1.14) | 2.05 (1.15) | 7.91 (5.71) | 8.04 (5.85) |
  | soft ray traced, 4 rays, SVGF | 2.85 (1.90) | 2.91 (1.97) | 9.95 (7.61) | 10.25 (7.89) |

  A still camera keeps its cascades (no draw at all). Casters drawn two-sided (`shadows.casters.faces` `BOTH`, the
  default: as rays meet them) take about twice the tiles' drawing of front faces alone (`FRONT`, as the technique
  before drew them, which cost the same: 0.68 against 0.69 ms); `FRONT` stays a choice: exact for closed meshes lit from
  outside (every sun), but a light inside a mesh leaks and one-sided surfaces seen from behind cast nothing.
  The owner's traced mask was 0.04 ms (1080p) to 0.15 ms (2160p) slower than `RayTracedShadows`' (its pass 0.27
  against 0.23 ms, in the same runs); the cause was not found (pitfalls.md: what was ruled out). Soft shadows cost the
  denoiser above all (1 ray: trace 0.29 ms, SVGF's temporal pass 0.18 ms and 0.33 ms per a-trous step at 1080p); 2
  steps are also closer to the reference than 4 (`softShadowsMatchThePathTracedReference`: mean penumbra error 0.018
  against 0.024; REBLUR 0.035; hard rays 0.141). AUTO cascades take a texel per pixel at each cascade's end: at 2160p
  four 4096 maps; `shadows.auto.scale` trades that for less.

  200 point lights (ranges 6 to 15) over the rocks, the camera flying over them at 1920 x 1080: unshadowed 0.93 ms;
  AUTO maps 0.97 to 0.98 ms (61 M atlas texels, the shadow atlas pass 0.002 ms in the median frame: tiles stay; at
  most 12 drawn in a frame as sizes change); fixed 256 maps 0.96 to 0.98 ms; with 29 lights' specs switched every
  frame 1.7 to 2.2 ms (64 tiles drawn a frame). No graph build in any of them after the first frames. Measured again
  with room for every caster (2026-11-07, `IMPORTANCE` updates, two runs): unshadowed 0.92-0.94 ms, AUTO maps 1.50-1.75
  ms (shadow passes 0.01-0.02), 256 maps 1.31-1.41 ms, specs switched 2.30-2.54 ms (shadow passes 0.31-0.36, 99 tiles
  drawn a frame). Where the frame's other 0.5 ms with maps went was not established; not the room itself (600,000 and
  2,097,152 chunks timed alike), and not the shadow passes.

  **Moving** (`ShadowsBenchmark`, 2026-11-06, the same 200 lights and flight; rocks circling through the lights'
  ranges, lights circling 4 units wide; GPU time of the frame (shadow passes), tiles drawn a frame, no graph build).
  This table, the update modes' and the caster layers' below were measured while the benchmark's culling had room for
  65,535 chunks of 64 meshlets and frames drawing hundreds of tiles wanted up to 525,000: casters were left out of
  those tiles, so they understate what drawing tiles costs (pitfalls.md). With room for every caster (2026-11-07, the
  defaults: `IMPORTANCE`, caster layers on) 10 / 100 / 500 / 2,000 rocks moving take 0.25 / 0.68 / 1.43 / 1.92 ms of
  shadow passes and 0.23 / 0.40 / 0.61 / 0.66 ms of culling a frame, 10 % of the lights moving 0.66-0.76 and 0.41 ms;
  the tiles drawn and the lag are as below:

  | AUTO maps | `CAPPED`, 64 draws (before the modes) | tiles waiting | draws 1,200 (none waits) | tiles drawn |
  |---|---|---|---|---|
  | all still | 0.97 ms (0.00) | 0 | 1.29 ms (0.01) | 1.7 |
  | 100 rocks moving | 2.13-2.62 (0.68-0.80) | 1,012 | 4.17-4.20 (1.53) | 437 |
  | 500 rocks moving | 3.13 (1.33) | 1,114 | 4.62 (2.28) | 926 |
  | 2,000 rocks moving | 3.29-3.32 (1.49) | 1,120 | 7.05-7.22 (3.40) | 1,001 |
  | 10% of the lights moving | 1.47 (0.38) | 550 | 2.06-2.13 (0.58-0.61) | 121 |
  | 50% of the lights moving | 1.85-1.92 (0.74-0.77) | 1,080 | 5.05-5.12 (2.36) | 601 |
  | 500 rocks, 10% of the lights | 3.15 (1.35) | 1,117 | 4.63-4.68 (2.30) | 953 |
  | 2,000 rocks, 50% of the lights | 3.31-3.35 (1.52) | 1,131 | 5.60-5.64 (2.83) | 1,101 |

  **Update modes** (2026-11-06, the same flight, one queue; shadow GPU: the shadow passes and the tiles' culling;
  lag: frames the changed tiles' shadows are late, mean over every changed tile / most):

  | Scene | `CAPPED` (64) | `ALL` | `IMPORTANCE` 256 / lag 2 (default) | `IMPORTANCE` 1024 / lag 4 |
  |---|---|---|---|---|
  | 100 rocks moving | 0.78 ms, 64 drawn, lag 60 / 94 | 2.38 ms, 437, lag 0 | 1.33 ms, 212, lag 0.71 / 2 | 1.12 ms, 132, lag 1.60 / 4 |
  | 500 rocks moving | 1.47 ms, 64, lag 60 / 94 | 4.08 ms, 926, lag 0 | 2.25 ms, 429, lag 0.77 / 2 | 1.65 ms, 242, lag 1.75 / 4 |
  | 2,000 rocks moving | 1.64 ms, 64, lag 60 / 94 | 5.42 ms, 1,001, lag 0 | 2.46 ms, 464, lag 0.77 / 2 | 1.62 ms, 264, lag 1.74 / 4 |
  | 300 rocks, 10% of the lights (the video's) | 1.31 ms, 64, lag 60 / 94 | 4.21 ms, 839, lag 0 | 2.29 ms, 390, lag 0.76 / 2 | 1.54 ms, 221, lag 1.74 / 4 |

  `CAPPED` starves tiles: the least important are never drawn (late since the first frame: 94 frames, the whole run),
  not "waiting / 64" frames as assumed before. More slots grown once (`IMPORTANCE` and `ALL` draw every tile at the
  start) cost about 0.1 ms a frame with nothing drawn.

  A tile costs about 2.5 µs to draw and 2 µs to cull (1,200 tiles: the atlas pass 2.6 ms, culling 2.1 ms), whatever
  moved: so a few hundred moving rocks redraw nearly every tile (each reaches 4 or so of the 1,136). With the default
  64 draws a frame (`CAPPED`) the rest wait; more draws keep them in step at that cost (more draw views also cost
  0.3 ms with nothing drawn). **Caster layers** (`shadows.casters.layers`, on by default): still casters kept in a layer of their
  own (a second atlas), a tile that only moving casters changed drawn as its layer's depth (a triangle over the tile,
  written untested: no clear first) and the moving casters on top, culled from a list of the moving ids instead of every
  instance. Where a tile's cost goes (`ALL`, one queue, 500 rocks moving, 926 tiles): culling 1.9 ms (2 µs a view, a
  third of it the instance pass over all 30,000 instances), the atlas pass 2.6 ms, of which the casters themselves
  about 0.25 ms; a tile's clear and the layer's depth write about 1 ms each. Before the clear went and the movers were
  culled alone, layers were slower (500 rocks 5.4 against 4.5 ms of culling and atlas); now (culling + layers + atlas):

  | Scene (`ALL`) | every caster | caster layers | finer casters (`casterDetail` 0.25): every caster | layers |
  |---|---|---|---|---|
  | 10 rocks moving (mostly still) | 0.79 ms | 0.54 ms | 0.88 ms | 0.56 ms |
  | 100 rocks moving | 2.44 | 1.83 | 2.76 | 1.83 |
  | 500 rocks moving | 4.87 | 3.60 | 4.68 | 3.90 |
  | 2,000 rocks moving | 6.54 | 6.05 | 6.66 | 6.30 |
  | 300 rocks, 10% of the lights | 4.48 | 3.93 | 4.36 | 3.45 |

  Layers cost a second atlas (256 MiB at 8192), so they stay a choice; they pay most with few movers among many
  still casters.

  **Mobility** (the scene's `Mobility`, per instance and light: a hint, never a promise). `shadows.casters.mobility`
  (`STATIC_ONLY` by default, `USE_HINT`, `DETECT`): a caster marked static joins its tiles' still layers the frame it
  is placed or moved (no `shadows.casters.settle` wait; moved or taken out, its tiles' layers drawn again where it was
  and is; new to the scene, it alone is drawn into the current layers it reaches, over what they hold); the rest is
  detected as before. Static only, because the scene makes every node it is not told otherwise dynamic: taking that at
  its word would keep every caster of an application that never marks its nodes out of the still layers. With
  `USE_HINT`, for applications that mark both ways, a caster marked dynamic counts as moving for good (never in a
  layer: no layers drawn again when it pauses). Instances without a hint (made outside the scene) are detected. A static caster moved again within the settle frames is detected
  moving as any caster, so one that moves anyway costs what detection costs and is shadowed where it is. A hint changed
  without a move only marks its tiles' layers stale. `shadows.lights.mobility`: a static spot or point light that
  nothing wrote (`Lights.version`) keeps its parameters without reading or comparing them (`Shadows.stats().stillLights()`);
  written, it is compared as any; its tiles redraw only when casters change (`STATIC_ONLY`, the default, as
  `USE_HINT`). With `USE_HINT` a dynamic light's changed tiles also rank `shadows.lights.movingWeight` (4) times as
  important in the update order.

  Measured (`ShadowsBenchmark`, `TRUESIM_SHADOWS_MOBILITY`, RX 9070 XT, 2026-11-07, JaCoCo off; the flight over 200
  AUTO-mapped lights, `IMPORTANCE` updates, caster layers on; the moving rocks and lights marked dynamic and everything
  else static, used both ways (`USE_HINT`) against ignored (`DETECT`), in turns in one run; two runs, ranges over both;
  shadow GPU: median (mean) of the shadow passes; prepare: the owner's CPU time a frame, median; measured again since
  the culling has room for every caster, see above):

  | Scene | Shadow GPU, `USE_HINT` / `DETECT` | Tiles drawn | Lag mean / max (frames) | Prepare (CPU) | Casters moving |
  |---|---|---|---|---|---|
  | all still | 0.02 (0.05) / 0.01 (0.05) ms | 1.7 / 1.7 | 0 / 0 | 0.18 / 0.18 ms | 0 / 0 |
  | 10 rocks moving | 0.25-0.26 (0.27) / 0.26-0.27 (0.28) | 49.5 / 49.5 | 0 / 0 | 0.20-0.23 / 0.20 | 10 / 10 |
  | 100 rocks moving | 0.68-0.73 (0.70-0.72) / 0.68-0.73 (0.70-0.72) | 212 / 212 | 0.71 / 2, the same | 0.39 / 0.38-0.39 | 100 / 100 |
  | 500 rocks moving | 1.43-1.44 (1.47) / 1.43-1.44 (1.46-1.47) | 430 / 430 | 0.76 / 2, the same | 1.12-1.33 / 1.09-1.26 | 500 / 500 |
  | 2,000 rocks moving | 1.91-1.97 (2.03) / 1.92-1.94 (2.02) | 461 / 461 | 0.77 / 2, the same | 3.64-3.70 / 3.55-3.68 | 2,000 / 2,000 |
  | 10% of the lights moving | 0.81-0.93 (0.89) / 0.68-0.80 (0.73-0.76) | 121 / 91 | 0 / 0, against 0.29 / 2 | 0.21 / 0.20-0.23 | 0 / 0 |
  | 50% of the lights moving | 3.01-3.05 (3.01-3.07) / 1.78-1.88 (1.88-1.89) | 501 / 290 | 0.20 / 2, against 0.73 / 2 | 0.31-0.32 / 0.28-0.29 | 0 / 0 |
  | 300 rocks, 10% of the lights | 1.94-1.97 (1.94-1.95) / 1.67-1.95 (1.77-1.78) | 431 / 393 | 0.68 / 2, against 0.76 / 2 | 0.78-0.80 / 0.77 | 300 / 300 |
  | 100 rocks moving 40 frames, standing 40 | 0.02 (0.29-0.30) / 0.02 (0.33) | 79 / 79 | 0.66 / 2, the same | 0.18-0.19 / 0.18-0.22 | 100 / 82 |
  | 500 rocks moving 40 frames, standing 40 | 0.02 (0.56) / 0.02 (0.65) | 157 / 157 | 0.73 / 2, the same | 0.19-0.22 / 0.19-0.20 | 500 / 408 |

  Where things move steadily, detection ends where the hints start: the same tiles, time and lag. The hints change
  transients (the dynamic marks only with `USE_HINT`): a dynamic light's tiles are drawn the frame it moves (no lag),
  which costs what those tiles cost: about 30 more tiles and 0.15 ms a frame with 20 lights moving, 210 more and 1.2 ms
  with 100; pausing rocks stay out of the layers (no layers drawn again as they start): 12 to 14 % less shadow time
  over the pauses (mean 0.29 against 0.33 ms with 100, 0.56 against 0.65 with 500). Skipping 200 static lights' tests
  did not show in the owner's CPU time (0.18 ms either way); 2,000 moving rocks cost it 3.6 to 3.7 ms either way (their
  tests against every light).

  Static geometry appearing and changing (`ShadowsBenchmark`, `TRUESIM_SHADOWS_ONLY=static`, RX 9070 XT, 2026-11-07,
  JaCoCo off; the same flight and 200 AUTO-mapped lights, `IMPORTANCE` updates, caster layers on; everything streamed
  or placed marked static, the circling rocks dynamic; the modes in turns in one run, three runs of three rounds, ranges
  over the runs (the last two rows: one run); shadow GPU: median / mean / p99 of the shadow passes; tiles: drawn a
  frame over their layer (with every caster: 1.7 in each, the AUTO sizes changing) / layers drawn again / layers static
  newcomers were drawn into; lag and the tiles drawn the same in each mode; prepare: the owner's CPU time, median;
  "before": static newcomers' layers drawn again at once, in the same session). Cells: rows 200 units wide across the
  path, as deep as the camera flies between them, loaded from 20 units behind to 80 ahead, a row in and one out in one
  batch each; props beside the lights ahead; mixed: those cells and props with 100 rocks circling and 100 circling 40
  frames, standing 40. Measured again since the culling has room for every caster (earlier numbers here were taken
  while it left casters out of busy frames' tiles: pitfalls.md):

  | Scene | Mode | Shadow GPU median | mean | p99 | Tiles over layer / layers drawn / added to | Casters moving | Prepare |
  |---|---|---|---|---|---|---|---|
  | mixed (cells, props, rocks moving) | `STATIC_ONLY` | 0.95-0.97 ms | 1.13-1.14 ms | 3.30-3.33 ms | 257.5 / 16.5 / 7.2 | 182 | 0.45-0.53 ms |
  | | before | 1.06-1.07 | 1.21-1.22 | 3.30-3.31 | 257.5 / 23.6 / 0 | 182 | 0.42-0.48 |
  | | `DETECT` | 1.27-1.31 | 1.37-1.38 | 3.49-3.55 | 257.5 / 22.7 / 0 | 488 | 0.41-0.56 |
  | | `USE_HINT` (movers dynamic) | 0.93-0.95 | 1.05-1.06 | 2.85-2.87 | 257.5 / 8.1 / 7.8 | 200 | 0.43-0.50 |
  | | before | 1.01-1.04 | 1.13-1.16 | 2.98-3.03 | 257.5 / 15.9 / 0 | 200 | 0.41-0.52 |
  | | no caster layers | 1.88-2.31 | 2.27-2.33 | 4.00-5.39 | 0 / 0 / 0 (259.2 with every caster) | 0 | 0.45-0.49 |
  | cells, 100 rocks every 10 frames | `STATIC_ONLY` | 0.02 | 0.17 | 2.00-2.22 | 12.2 / 6.9 / 5.3 | 0 | 0.18 |
  | | before | 0.01-0.02 | 0.20-0.21 | 2.14-2.40 | 12.2 / 12.2 / 0 | 0 | 0.18 |
  | | `DETECT` | 0.02 | 0.18-0.19 | 1.92-2.12 | 12.2 / 8.3 / 0 | 300 | 0.18-0.20 |
  | cells, 20 rocks every 2 frames | `STATIC_ONLY` | 0.25-0.36 | 0.34-0.35 | 1.62-1.91 | 33.4 / 15.4 / 18.0 | 0 | 0.20-0.22 |
  | | before | 0.39-0.53 | 0.45-0.46 | 1.57-1.83 | 33.4 / 33.4 / 0 | 0 | 0.20-0.21 |
  | | `DETECT` | 0.27-0.37 | 0.38-0.39 | 1.61-1.96 | 33.4 / 17.1 / 0 | 300 | 0.23-0.27 |
  | props, one every 5 frames | `STATIC_ONLY` | 0.02 | 0.07 | 0.63-0.64 | 1.4 / 0.7 / 0.7 | 0 | 0.17-0.22 |
  | | before | 0.02 | 0.08 | 0.63-0.64 | 1.4 / 1.4 / 0 | 0 | 0.17-0.20 |
  | | `DETECT` | 0.02 | 0.08 | 0.64-0.82 | 1.4 / 1.0 / 0 | 6 | 0.17-0.18 |
  | one static rock moved once | `STATIC_ONLY` | 0.01-0.02 | 0.05 | 0.54 | 0.2 / 0.2 / 0 | 0 | 0.17 |
  | | `DETECT` | 0.01 | 0.05 | 0.54 | 0.2 / 0.2 / 0 | 1 | 0.17 |
  | 100 rocks circling, nothing static changing | any of the three | 0.68-0.70 | 0.69-0.71 | 1.25-1.26 | 210.5 / 2.1 / 0 | 100-200 | 0.38-0.39 |

  Where static geometry changes among moving casters, the hint pays: the mixed scene's shadows take 18 % less GPU time
  than with detection (mean 1.13 against 1.37 ms, p99 3.3 against 3.5 ms), `USE_HINT` a little less again. Detected,
  every streamed or placed rock counts as moving for its 30 settle frames (488 casters moving against 182), and every
  tile drawn over its layer for the movers culls and draws those too. A static newcomer (a cell's rocks, a prop) is
  drawn alone into the current layers it reaches, over what they hold (no clear: the depth test keeps the nearest, as a
  layer drawn again would), its tiles' layers culling only the newcomers' ids; a stale layer is drawn whole, and a
  static caster moved or taken out (cells leaving behind the camera) still has its tiles' layers drawn again at once,
  as depth cannot be taken out. So streamed cells with nothing moving now cost no more than detection (mean 0.34
  against 0.38 ms at a row of 20 every 2 frames, before 0.45; 0.17 against 0.18 at 100 every 10, before 0.20-0.21), and
  the mixed scene 7 % less than before. Props one at a time and a rock moved once cost the same either way. The images,
  once everything stood 12 frames, match the reference without layers exactly in every mode and round, and the
  reference matches itself in another run (0 pixels).

  Ray-traced point lights: a mask per light over the view's pixels, so at most `shadows.rays.lights` (16) spot and
  point lights are traced a frame, the most important (how big their reach shows, times their importance; those
  traced last frame kept a little longer), the rest take AUTO maps; at most `shadows.soft.lights` (4) of them soft,
  the rest traced hard; no ray beyond a light's range. All 200 traced took 220 to 236 ms a frame (hard) and 915 to
  936 ms (soft). Now (`CAPPED`, 64 draws, before the update modes):

  | 200 lights, rocks and lights moving | hard rays (16 traced) | soft rays (4 soft, 12 hard) |
  |---|---|---|
  | all still | 2.77-2.91 ms (1.82-1.92) | 6.88-7.08 ms (5.87-5.96) |
  | 100 rocks moving | 1.92-2.01 (1.00-1.06) | 5.41-5.71 (4.43-4.70) |
  | 500 rocks moving | 2.73-2.88 (1.47-1.67) | 5.50-5.62 (4.58-4.69) |
  | 2,000 rocks moving | 3.76-3.77 (2.10-2.11) | 7.70-7.86 (6.54-6.69) |
  | 10% of the lights moving | 2.89-3.20 (1.84-2.14) | 6.82-7.24 (5.69-6.04) |
  | 50% of the lights moving | 2.99-3.21 (1.93-2.18) | 6.98-7.28 (5.83-6.17) |
  | 500 rocks, 10% of the lights | 2.75-2.97 (1.51-1.62) | 5.55-5.82 (4.62-4.81) |
  | 2,000 rocks, 50% of the lights | 3.75-3.77 (2.07-2.11) | 7.73-7.88 (6.59-6.70) |

  `ShadowsVideo` renders the scene as PNG frames on a fixed 60 fps timeline, per method, with each frame's measures.
- **Checked** (screenshots of every method, `Screenshots.renderShadowOwner`): with lights above the rocks, AUTO point
  light maps and hard rays differ in 195 pixels darker and 165 brighter of 2 million.
- **Casters drawn two-sided:** tiles draw every face of a caster, both sides (back faces kept by meshlet culling, the
  rasterizer, masked bins and geometry hooks' own entry points), as rays meet every face: a light inside a closed mesh
  is shut in by its maps as by rays (`aLightInsideAClosedMeshIsShutInByMapsAsByRays`: 149 lit before, dark after).
  Classic LOD geometry casts into the atlas too, through its own selection per tile view.
- **What moves on the GPU:** instances drawn between two steps' poses (`InstanceMotion`) redraw only the tiles around
  their way (their poses are known on the CPU); mesh particles (instances the GPU writes) redraw every tile while
  there are any, as where they are is not known here. A material's mask or hook fields updated, or a texture reaching a
  material late (as loading sets the material), redraws the tiles (`Materials.shapesVersion`).
- **Inline ray shadows are gone** (`InlineRayShadows`, the visibility buffer's traced variant, `SHADOW_RAY`): shading
  one material type cannot run another type's `keep()`, so they let light stop at holes; the owner's traced masks cost
  the same (1.15 against 1.17 ms in the table they were measured in) and respect them.
- **Not yet:** variance or moments maps, virtual maps, far cascades shared between views at a distance (near eyes
  share all of them: `shadows.view(right, leftPart, margin)`), AUTO rays (fewer, or hard for soft far away: no
  correct rule per light without knowing where the blocker is).

### Filtering shadow maps

`shadows.maps.filter` chooses how maps (cascades, spot and point tiles) are filtered; both weigh each texel of the
kernel by a tent's integral over it, so the light changes smoothly with the position: no texel's edge shows as a step,
and edges do not crawl as a moving sun turns the map's texels.

| Setting | Default | Meaning |
|---|---|---|
| `shadows.maps.filter` | `PCSS` | `PCSS`: blockers searched over the widest penumbra (16 gathers), their depth giving the penumbra the light's size (its spec's softness: the sun's angular radius, 0.27 degrees) casts there; `PCF`: a fixed kernel |
| `shadows.maps.filterRadius` | 1.5 | the kernel's half-width in texels: PCF's, PCSS's narrowest |
| `shadows.maps.penumbra` | 8 | PCSS: the widest penumbra's half-width in texels, which the search covers |

- **A light without softness** filters as PCF with either. A lookup with no blocker in reach ends after the search, one
  with blockers all over it (the umbra) too; a point in a volume (no normal: fog froxels) takes the narrowest kernel
  without a search.
- **Wide kernels compare each texel at the receiver's plane** (receiver plane depth bias, from the surface's normal),
  so they do not shadow a sloped surface by itself.
- **Measured** (`SoftShadowMapsTest`): a light 0.03 radians in radius, a caster 8 units up, texels of about 4 cm: PCSS
  casts a penumbra of 0.314 (10 to 90 %) against a disc's 0.330; the old 3 x 3 texels weighed alike cast 0.103 and
  stepped 2.4 cm off a straight line, PCSS 2.4 mm, a hard light's edge 1.1 cm (0.23 of a texel: a kernel of 1.5 texels
  keeps the texels' shape faintly; finer cascades are the cure, `shadows.cascades.lambda`).
- **Cost** (RX 9070 XT, 1920 x 1080, effects-atmosphere's scene, cascades 3 x 4096; GPU medians of 240 frames after
  120, two rounds, 2026-11-08):

  | Filter | Shading in the room (the sun through a window) | Shading outdoors, noon |
  |---|---|---|
  | 3 x 3 texels weighed alike (before) | 0.70-0.72 ms | 0.35 ms |
  | `PCF` (1.5 texels) | 0.69-0.74 ms | 0.38 ms |
  | `PCSS` (the sun's 0.27 degrees) | 0.82-0.89 ms | 0.42-0.45 ms |

  Fog froxels' light (a lookup each, no normal): 0.068 ms before, 0.080 after. PCSS for every froxel took 0.42 ms
  before volume lookups skipped the search (docs/pitfalls.md).

### The shadow table

The owner writes each view's shadow table, which shading reads; plugins can write it too. A light names consecutive
entries; each entry has a **kind**, evaluated in shading by an implementation of `lights.slang`'s `IShadow`:

| Kind | Written by | Evaluated by |
|---|---|---|
| shadow map (filter: PCF or PCSS, `ShadowTable.filter`; VSM, ESM fit the same slot) | the owner's atlas tiles (`ShadowTable.atlasMap`): orthographic (cascades) or perspective (spot, point faces: bias and normal offset growing with the distance from the light) | `PcfShadow`: percentage-closer filtering over a tent (each texel weighed by the tent's integral over it), as wide as the light's penumbra with PCSS, within the tile |
| screen-space mask | the owner's traced masks, or a plugin's own (`ShadowTable.shadowMask`) | `MaskShadow`: the mask's texel |

- **Shadow parts modify `Keys.LIGHT_LIST`**, so they go in the shading's view and run whenever shading requires the
  light list. They put what shading must read into the view's shared `ShadowInputs` (images to sample, buffers to wait
  for) with a setup that fills their lights' entries when the frame records; a plugin's shadows (contact shadows, a
  game's own trick) are a mask written the same way, with no shader change.
- **Each view has a shadow table of its own** (`lights.shadowTable(view)`): each view's cascades fit its own camera and
  a traced mask is of its view's pixels. An entry nothing of the view filled reads as lit. A view's table goes with
  the view, freed once no frame in flight reads it. Casters choose detail for `shadows.casterDetail` pixels of error (1,
  as a camera): coarser casters draw far fewer triangles; cascades add that much depth bias.
- **One top level per frame** (`TopLevel`, a shared request) serves every ray-traced technique of every view: VR's two
  eyes, a mirror, a second player. It is built relative to a world position, `rayTracing.origin(x, y, z)` (0, 0, 0 at
  first), not to a camera: each view moves its rays by its camera less the origin. Its transforms are floats, so the
  app keeps the origin within a few kilometres of the cameras, moving it when they have gone far (at 7,000 km floats
  are 0.5 m apart). Its build is skipped while no instance changed (`GpuScene.version()`), no bottom level was added
  and the origin stayed: a still scene builds it once.
- A mask needs the camera's depth before shading: for the visibility buffer that is its own IDs' draw (shared); for
  forward shading, a depth-only draw of the camera as a view of its own.

`Screenshots.renderShadowOwner` renders every method on 3,000 rocks (`owner-*.png`): the sun by one map, cascades,
AUTO cascades, hard and soft rays and the soft sun path traced; 24 point lights at dusk by AUTO maps, hard and soft
rays. Its renderer has room for 2^19 chunks of culling (the point lights' AUTO maps want about 140,000 on one frame
slot, where the renderer cannot grow), and each shot fails if a frame left casters out (`chunksOverflowed()`).

### Global illumination: foundations

The first stage (S0) of global illumination: what every later technique is built on and measured against. Nothing
here lights the real-time image yet.

**The reference path tracer** (`ReferencePathTracer`, in place of a view's shading technique) path traces the view's
scene progressively as ground truth: one path per pixel per frame through the frame's top level (ray queries in
compute), accumulated in 32-bit floats until `render.reference.samples`; it starts again when the camera, the
instances or the materials change, or on `restart()`. A wavefront, a pass per step and bounce: camera rays, nearest
hits, hits sorted by shading bin, shading. Paths see the scene as drawing does: the same instances, posed copies and
materials, the view's light list (with the lights the GPU writes) and its environment, the constant sky without one.

```java
view.port().add(new ReferencePathTracer(geometry, rayTracing, view, Format.R32G32B32A32_SFLOAT.vk()))
    .add(new ClusteredLights(lights, view));
```

- **Every material type shades as itself at ray hits** (design D5): each bounce's hits are counted, laid out and
  scattered by shading bin (as `meshlets/split.slang` does), and each bin is shaded by one indirect dispatch of its
  type's generated hit entry point (`material-types/entryHit.slang.template`, `MaterialType.hitShader`): the type's
  `surface()` with the engine's features (`typedSurface`, which `shadeMaterial` uses too), texture filtering by ray
  cones. No shader branches on the type. Measured against one shader switching over the types (as the build would
  generate it, features read at run time: `test/singleHit.slang`, through `singleHitShader`; the same image,
  `oneShaderSwitchingOverTheTypesShadesHitsAsTheirBinsDo`) with `HitShadingBenchmark` (RX 9070 XT, 2026-11-03,
  3,000 rocks over 7 shading bins of 5 types, 2 bounces, binning and shading passes, 4 rounds after a warm-up):

  | Rays | Way | Binning | Shading | Both |
  |---|---|---|---|---|
  | 256 x 256 (512 probes of 128) | binned, a dispatch per bin | 0.04 ms | 0.10 ms | 0.14-0.15 ms |
  | 256 x 256 | one shader switching | - | 0.25 ms | 0.25 ms |
  | 1920 x 1080 | binned, a dispatch per bin | 0.84-0.99 ms | 1.10-1.27 ms | 1.95-2.27 ms |
  | 1920 x 1080 | one shader switching | - | 2.75-3.34 ms | 2.75-3.34 ms |

  Binning costs its passes and still wins at both sizes: the switch's shading alone takes longer than binning and
  shading by type. It is not offered. A type's geometry hook (`keep()`) makes holes at hits as in every pass, and
  shadow rays go through them: a next-event shadow ray meeting such a type waits with the light it brings for the
  type's keep test, then goes on (`LightRays`; the probe volume and radiance cascades too).
- **Light transport:** the BRDF shading uses (`reflected()`), sampled by its lobes (Lambert by the cosine, GGX and the
  clear coat by their visible normals, diffuse transmission behind), every light of the list by a shadow ray
  (next-event estimation), emission and the environment where paths meet them, transmission refracted through thin
  walls and into volumes (Beer's law inside), blended materials covering by their alpha, back faces of one-sided
  materials let through as drawing culls them. Russian roulette from `render.reference.roulette`; at most
  `render.reference.bounces`. Not traced: dispersion, baked occlusion textures; emissive surfaces and the environment
  are found by paths meeting them (no light sampling of them yet, design S7).
- **Lights at hits:** every light of the list (`render.reference.lights = ALL`, exact), or the world light grid
  (`GRID`, below), as `worldLights(lights)` names it.
- **Tested against closed forms** (`ReferencePathTracerTest`, on lavapipe in CI too): the white furnace (a closed box
  giving off E and reflecting albedo rho everywhere is E / (1 - rho), within 1 %), an open plane under the constant
  sky and a uniform environment (albedo times the sky, within 1 %), the Cornell box converging as one over the root of
  the samples with its walls' colors on the floor, a glow material lighting a floor magenta and car paint showing its
  flakes at hits.
- **Images:** `TRUESIM_SCREENSHOTS=true` renders the Cornell box (`reference-cornell.png`, 512 x 512, 4,096 samples in
  23 s on an RX 9070 XT; 1.3 % noise left in 4 x 4 pixel means) and the demo's scene from where the demo starts
  (the samples' `ReferenceScreenshots`: `reference-demo.png` beside the visibility buffer's `raster-demo.png`; 1280 x
  720, 1,024 samples, 2.0 ms a sample).

**The diffuse weight** (`Keys.SURFACE_DIFFUSE`, a fourth surface output beside screen-space reflections' three,
`SurfaceOutputs.DIFFUSE_FORMAT`, 8 bits, design D10): what the diffuse light from all around the normal (the
environment's spherical harmonics, the constant sky, later the probe volume) is weighed by in each pixel's color, so
global illumination replaces that term exactly, as reflections replace the reflectance's
(`theDiffuseWeightIsWhatTheDiffuseLightIsWeighedBy`: the color is the weights times the light, on both shading
techniques). Written with the other surface outputs, when a technique asks for them: `ReflectionsBenchmark`'s
"surface outputs only" (RX 9070 XT, 2026-11-03) took 1.388-1.404 ms at 1080p on mesh shaders against 1.343-1.355 ms
with three outputs (measured in another session), and at 4K 2.760-2.764 against 2.686-2.691 ms (indexed) and
2.631-2.636 against 2.609-2.616 ms (visibility buffer): about 0.02 to 0.07 ms.

**Motion vectors** (`MotionVectors`, `Keys.MOTION`, design D4): per view (each eye its own), for every pixel how far
its surface moved on screen since the frame before, in pixels from it (two 16-bit floats; a history is read at the
pixel plus the motion), the projections' jitter taken out. Exact for rigid and skinned motion: the pixel's triangle
comes from visibility buffer IDs (the visibility buffer's own draw, shared; for forward shading a second render view
of the same camera), rebuilt at this frame's pose and at the frame before's: the instance table as the frame before
left it (a copy kept every frame after the upload and interpolation, so D91's interpolated instances and any writer
alike), skinned copies' vertices as they were drawn (copied aside before each skinning,
`Deformation.keepPrevious`), the frame before's view-projection relative to this frame's camera. The sky moves with
the camera's turn. `MotionVectorsTest` reads back an instance moved, a skinned quad moved by its joints, still
surfaces and the sky, the camera moving and stopping, on both shading techniques; `motion-demo.png` shows the demo's.
The frame before's render region is not kept yet: with dynamic resolution changing it, the motion is in this frame's
region's pixels.

**World lights for ray hits** (`WorldLights`, a shared request, design D2): light clusters serve a view's pixels; hits
off the screen need lights by world position. A grid of `cells` cells along each axis of `cell` units, around an
anchor camera, built once a frame from the scene's lights and those the GPU writes (particle lights); points outside it
take every light, directional lights are summed directly. Two layouts trade off, the kinds of `render.gi.lights`
(`WorldLights.SLOT`), their tunables under `render.gi.lights.lists.*` and `render.gi.lights.reservoirs.*`:

- `lists`: each cell lists the lights whose reach meets its box (`slots`, overflow noted), built a
  thread per light; a hit sums them all with a shadow ray each. Exact: the reference with it draws the same image as
  with every light (`theWorldLightGridsListsLightHitsAsEveryLightDoes`).
- `reservoirs` (ReGIR, Boksansky, Jukarainen and Wyman 2021): each cell keeps reservoirs, each a light resampled from
  `candidates` drawn uniformly by what it could give the cell's box (never 0 where it reaches); a hit
  resamples its cell's reservoirs by what each gives it and traces one shadow ray. Unbiased
  (`theWorldLightGridsReservoirsConvergeToEveryLight`), constant cost, noisy.

`WorldLightsBenchmark` (`TRUESIM_BENCHMARK=true`), RX 9070 XT, 2026-11-03: the reference path tracer's hits (2
bounces) at 1920 x 1080 over 3,000 rocks at night, 256 lights the GPU writes circling over them and 64 to 4,096 point
lights over 200 x 200 units, a grid of 64 cells of 8 units (64 slots for lists, 16 reservoirs of 16 candidates); 4
rounds after a warm-up round, ranges of the medians; noise of 4 x 4 pixel means after 16 samples:

| Point lights | Lights | Grid build | Hit shading | Both | Noise |
|---|---|---|---|---|---|
| 64 | every light | - | 4.71-4.75 ms | 4.71-4.75 ms | 1.3 % |
| 64 | grid lists | 0.12 ms | 1.59-1.60 ms | 1.71 ms | 1.3 % |
| 64 | grid reservoirs | 0.05 ms | 1.15-1.18 ms | 1.20-1.23 ms | 1.8-2.3 % |
| 1,024 | every light | - | 19.20-19.30 ms | 19.20-19.30 ms | 1.4 % |
| 1,024 | grid lists | 0.37-0.38 ms | 4.63 ms | 5.00-5.01 ms | 1.4 % |
| 1,024 | grid reservoirs | 0.32 ms | 1.53-1.57 ms | 1.85-1.88 ms | 9.8-13.9 % |
| 4,096 | every light | - | 67.5-67.8 ms | 67.5-67.8 ms | 5.0 % |
| 4,096 | grid lists | 1.23 ms | 13.82-13.87 ms | 15.05-15.10 ms | 5.0 % |
| 4,096 | grid reservoirs | 1.17-1.19 ms | 2.58-2.65 ms | 3.77-3.82 ms | 38-74 % |

Reservoirs are built only for the cells hits used in the last `render.gi.lights.reservoirs.keep` frames (8; a cell's state
kept from frame to frame), and a hit in a cell not built sums every light exactly and marks it: built for every cell
each frame, the same grid took 0.61, 2.25 and 3.73 ms to build and 1.66, 3.51 and 5.04 ms with the hits. The noise
rises where hits find a cell built in one frame and not in another (exact there, sampled here), and grows with the
lights. Lists cost as many shadow rays as a cell has lights and add no noise; reservoirs cost one ray a hit whatever
the count. Neither wins outright, so both stay; lists are the default.

**GI test scenes** (engine-scene's tests, `gi.GiScenes`): scenes built in code, exact and repeatable, by name, for
every GI stage's tests and benchmarks: `sun-room` (a closed room lit by the sun and sky through one window: mostly
bounced light), `leak-5cm`, `leak-25cm`, `leak-50cm` (a closed dark room beside a sunlit one, the wall between that
thick: the reference is black, any light there is a leak), `bleeding` (strongly colored walls and floor under a
ceiling light), `screens` (a dark room lit only by emissive panels), the dynamic `toggle` (a point light on, then off),
`door` (shut, then open to the sun) and `mover` (a red block beside a white wall, moved 2 m), and `cornell`. Boxes of
plain materials (a cube with its faces' normals), the sun's shadows ray traced. `gi.GiHarness` renders a scene in a
state path traced or in real time, `gi.GiError` compares an image with a reference (the mean relative luminance
error, its median, 95th and 99th percentiles per pixel, the bias, and the mean absolute difference for black
references), and `GiScenesTest` checks in CI that every scene traces and the dark rooms are black.
`TRUESIM_SCREENSHOTS=true ./gradlew :engine-scene:test --tests '*GiReferences' -PgiOut=<directory>` writes each
scene's reference (640 x 400, 4,096 samples: 4 to 9 s each on an RX 9070 XT) and real-time image as PNGs at the
reference's exposure, and the reference as raw floats for later stages to compare against, with a table of the
real-time image's error (no GI yet: the environment's light reaches closed rooms, 452 % too bright in `sun-room`,
0.30 of luminance in the leak rooms' black; rooms lit by bounces alone come out black). Sponza, the large interior, is
added by `-PgiSponza=<Khronos glTF Sample Assets>/Models/Sponza/glTF/Sponza.gltf` (`gi.GiSponza`: three views, the
sun through the courtyard, 960 x 540, 2,048 samples: 7 to 9 s each): its files are under their own licence, read from
where they are, never committed.

**Ray queries in CI:** CI's pinned Mesa 26.0.8 lavapipe exposes `VK_KHR_acceleration_structure` and
`VK_KHR_ray_query`: `VulkanDevice.rayTracing()` is true there, and the ray-traced tests (`SegmentTracingTest`,
`ShadowsTest`'s) run in CI rather than being skipped. Ray-traced tests still assume `device.rayTracing()`, and the
reference's tests are small (32 x 32 pixels) for a CPU's ray tracing.

### Global illumination: ambient occlusion and the probe volume

The second and third stages (S1, S2): occlusion at small scale and diffuse light that bounces, each a slot that can
be `off`, replacing exactly what shading added through the surface outputs or the indirect-light table.

**Ambient occlusion** (`AmbientOcclusion`, one per view at `Order.SCREEN_SPACE_OCCLUSION`: after the background,
before reflections; `render.ao`, `AmbientOcclusion.SLOT`): the cosine-weighted share of the hemisphere open within the
kind's `radius` (0.5 m). Each kind's tunables are its record (`AmbientOcclusion.Gtao`, `Bitmask`, `Rays`), under
`render.ao.<kind>.*`; a game's own kind builds in its place (`docs/custom-techniques.md`).

- `gtao` (Jimenez et al. 2016, as XeGTAO): slices through the hemisphere, each side's highest horizon found along the
  screen, the visible arc integrated in closed form; samples fade over the last `falloff` of the radius; surfaces
  infinitely thick.
- `bitmask` (the default; Therrien, Levesque and Gilet 2023): surfaces `thickness` thick, each slice's occlusion a
  mask of 32 sectors of equal cosine-weighted measure (the slice's measure from GTAO's closed form, so every sector
  holds as much light), and one short-range bounce of the scene's color from the sectors each sample covers
  (`bounce`).
- `rays` (#34): `rays` cosine-distributed rays per pixel by ray queries, as long as the radius: exact, off-screen
  occluders included, denoised. Devices without ray tracing take `bitmask`.

Only the diffuse light from all around is darkened (design D12): color + diffuse weight x ((ao - 1) x the diffuse
light + bounce), the diffuse light recomputed at the pixel from the view's table (the environment's harmonics, the
constant sky, or the probe volume), so an unoccluded surface keeps its color exactly (tested on every technique), and
direct light, emission and unlit surfaces are untouched (`anOccludedCornerDarkensOnlyTheDiffuseLight`). Reflections
get a separate specular occlusion (each kind's `specular`): the cone of the visible directions around the bent normal
against the GGX lobe's cone (Oat and Sander's cap intersection), relative to what the open hemisphere leaves of the
lobe; it is provided as `Keys.SPECULAR_OCCLUSION`, so screen-space reflections replace the environment as it was
dimmed. The cone's aperture from the occlusion is steep near 1: grazing reflections on open floors lose up to 12 %.
The screen-space ones are single-frame by default (a noise pattern fixed in time, then
`render.denoise.spatialPasses` of an edge-stopping filter); with `temporal`, and for rays, they accumulate over frames.
`render.ao.debug` shows the occlusion, the specular occlusion or the bounce alone.

**The denoiser** (`Denoiser`, design §9, D6), for any signal of two images: `SVGF` (temporal accumulation, then
`render.denoise.passes` steps of an a-trous filter guided by the depth's plane, the normal and the variance) and
`REBLUR` (a blur, the temporal accumulation, a blur whose radius shrinks with the history, the history holding the
blurred result), chosen by each kind's `denoiser` (`render.ao.rays.denoiser`). Both find the history through `Keys.MOTION` (or the camera's
reprojection) and keep only taps of the same surface; the history holds `render.denoise.maxFrames` (30) at most, and a
fast history of `render.denoise.fastFrames` (4) restarts it where the two disagree beyond the fast one's noise
(`render.denoise.clamp` deviations): a change nothing announced shows within 15 frames
(`tracedOcclusionFollowsAnUnannouncedChangeWithinFifteenFrames`, both families). Neither wins outright (below), so
both stay.

**The probe volume** (`ProbeVolume`, a frame-level request; `ProbeVolumeLighting`, per view, `render.gi` = `off` or
`probes`, its tunables `ProbeVolumeLighting.Probes` under `render.gi.probes.*`; DDGI: Majercik et al. 2019 and 2021).
`cascades` (3) grids of `probes` x `probesUp` x
`probes` (16 x 8 x 16) probes, the first `spacing` (1 m) apart, each next twice as far, around the anchor view's
camera; they scroll with it (a slot is a probe's world grid coordinate modulo the grid: only probes coming into the
grid start anew), half a spacing off the grid's planes. Per probe, in octahedral atlases: irradiance (6 x 6 texels and
a border, half floats) with its sky visibility (the share of its rays that met nothing), and the distance's mean and
mean square (14 x 14 and a border), so a shaded point ignores probes that cannot see it (Chebyshev). Each frame:

- **Rays shaded by type:** `rays` (128) rays per probe, turned anew each frame, plus a fixed set for
  relocation and classification, run as paths of the reference path tracer's wavefront in its probe mode: hits binned
  and shaded by their material type's own hit entry point, with the BRDF shading uses, the world light grid
  (`WorldLights`: the sun, punctual lights, the GPU's particle lights) and a shadow ray each, emission, the
  environment where rays leave (through its prefiltered level `skyFootprint` (2) times as wide as the directions each
  ray stands for: a sun in the sky reaches every update by its share, not only the frames a ray meets it), and the
  volume's own light of the frame before at the hit (infinite bounces). Back
  faces mark probes inside geometry: they move out (`relocate`) or are turned off (`classify`).
- **Shading everywhere:** the volume's diffuse light replaces the environment's spherical harmonics through the probe
  volume's shading hook (`probes/hook.slang`, `ProbeVolume.HOOK`; "Writing a shading hook": the core's shading knows no
  probe format) in every path running the view's hooks: opaque on both shading techniques, blended and transmissive surfaces, lit
  particles (which took the constant sky before), and the composites of ambient occlusion and reflections; its sky
  visibility dims the environment's reflections (specular occlusion at the probes' scale), which is what keeps a
  closed room dark. One look-up gives both. Views given equal volumes share one update (a headset's eyes, D7:
  `twoViewsShareOneVolume`).
- **Lag (design D3), no latency:** a probe's history holds `maxFrames` (30) updates at most.
  Probes within `instanceMargin` (3) times a changed instance's bounding sphere (moved, changed or skinned: D91's
  records compared on the GPU with the frame before's) or `lightMargin` (2) times a changed light's reach restart at
  once (`invalidate`), all of them for a directional light or the environment; a probe whose update
  differs from its history beyond its noise (`sensitivity`; the noise its own rays show, and at least the mean of what
  its history's updates showed: a small bright light met by some updates only is noise, not a change), or drifts from
  it over a few updates (`drift`), restarts too (`adaptive`); probes that restarted update again within the frame (`iterations`, 2), a further bounce of the change each time.

Tested against closed forms (`ProbeVolumeTest`): the white furnace through the probes (E / (1 - rho) within 3 %, which
only infinite bounces reach), a light changing color inside a box (90 % in 2 frames told, 2 found, more by the history
cap alone), two views one update, no allocation.

**Against the path-traced references** (engine-scene's `GiEvaluation` and `GiSponza`, `TRUESIM_SCREENSHOTS=true`,
`-PgiReferences=<GiReferences' directory>`; 640 x 400 against 4,096 samples, Sponza 960 x 540 against 2,048; RX 9070
XT, 2026-11-04): mean relative luminance error, its 95th percentile, the bias; the leak rooms' references are black,
so their mean absolute luminance instead:

| Scene | No GI | Probes | Probes + BITMASK | Probes + RAYS (SVGF) |
|---|---|---|---|---|
| sun-room | 452.7 %, 1203 %, +452 % | 7.0 %, 28.5 %, +0.5 % | 6.8 %, 27.6 %, -1.0 % | 8.5 %, 37.9 %, -4.6 % |
| leak-5cm (absolute) | 0.3006 | 0.0001 | 0.0001 | 0.0001 |
| leak-25cm (absolute) | 0.3006 | 0.0000 | 0.0000 | 0.0000 |
| leak-50cm (absolute) | 0.3005 | 0.0000 | 0.0000 | 0.0000 |
| bleeding | 100.0 %, 100 %, -100 % | 19.9 %, 84.9 %, +12.5 % | 19.5 %, 62.2 %, +9.3 % | 19.2 %, 82.2 %, +3.1 % |
| screens | 41.1 %, 100 %, -39.9 % | 19.7 %, 75.8 %, -16.2 % | 18.2 %, 67.7 %, -15.6 % | 17.8 %, 69.7 %, -15.5 % |
| toggle (light on) | 65.4 %, 92.9 %, -65.4 % | 7.9 %, 18.4 %, -6.4 % | 7.6 %, 17.8 %, -6.2 % | 10.5 %, 32.0 %, -9.7 % |
| door (shut, absolute) | 0.2174 | 0.0000 | 0.0000 | 0.0000 |
| door (open) | 339.2 %, 1053 %, +337 % | 9.1 %, 38.1 %, -1.9 % | 9.2 %, 28.9 %, -5.5 % | 9.3 %, 36.5 %, -5.2 % |
| mover (moved) | 81.3 %, 94.3 %, -81.3 % | 5.2 %, 15.8 %, -3.7 % | 4.5 %, 13.3 %, -2.3 % | 7.6 %, 32.0 %, -5.3 % |
| cornell (2 m box, 1 m probes) | 46.6 %, 100 %, -46.4 % | 22.7 %, 115 %, +1.8 % | 19.2 %, 86.0 %, -7.6 % | 22.4 %, 94.0 %, -5.5 % |
| Sponza, view 1 | 331 %, 9004 %, +329 % | 17.9 %, 123 %, -1.8 % | 18.5 %, 87.5 %, -5.3 % | 17.4 %, 92.7 %, -3.5 % |
| Sponza, view 2 | 1421 %, 12932 %, +1418 % | 26.8 %, 129 %, -5.2 % | 26.3 %, 119 %, -6.7 % | 25.5 %, 110 %, -5.5 % |
| Sponza, view 3 | 3546 %, 11244 %, +3543 % | 70.8 %, 196 %, +25.4 % | 69.6 %, 145 %, +7.7 % | 68.7 %, 166 %, +8.5 % |

Ambient occlusion alone against 512 rays a pixel (mean absolute difference, by scene): GTAO 0.030-0.071, BITMASK
0.030-0.073, RAYS with SVGF 0.024-0.070, with ReBLUR 0.015-0.064 (ReBLUR's blurs suit these still views; SVGF keeps
contact sharper and noisier). With the probes, BITMASK's image is closer to the reference in 7 views and GTAO's in 7
(`GiEvaluation`'s full table, the views that are not black): a trade-off, so both stay.

Lag of the dynamic scenes (frames until the image is within 10 % of the change from where it settles, each frame the
mean of 4 runs, compared in 16 x 16 blocks): toggle 3 told, 3 found (48 by the history cap alone); door 2 told, 3
found (48); mover 6 to 26 told and 26 found over two evaluations (47-51): the targets (4 and 15) are not met there.
The moved block changes the room's bounce by a few percent, below what one update's noise shows, and the measure
hovers at its threshold for many frames; that tail converges with the history.

What it costs (engine-scene's `GiBenchmark`, `TRUESIM_BENCHMARK=true -PgiSponza=<Sponza.gltf>`, RX 9070 XT,
2026-11-04, JaCoCo off): Sponza from its first view, the visibility buffer, traced sun shadows; 3 rounds after a
warm-up, ranges of each round's median of the GPU's pass timings. "Frame": every pass; "AO": ambient occlusion's
passes and its denoiser's (the composite samples the volume where it lights the view); "probes": the volume's update
and lighting passes (6,144 probes); "shading": the visibility buffer's shading pass, where the volume is sampled.

| Size | Technique | Frame (passes) | AO passes | Probe passes | Shading pass |
|---|---|---|---|---|---|
| 1920 x 1080 | raster | 0.848-0.892 ms | 0.000-0.000 ms | 0.000-0.000 ms | 0.608-0.648 ms |
| 1920 x 1080 | gtao | 2.587-2.607 ms | 1.551-1.558 ms | 0.000-0.000 ms | 0.789-0.794 ms |
| 1920 x 1080 | bitmask | 2.825-2.972 ms | 1.854-1.940 ms | 0.000-0.000 ms | 0.738-0.786 ms |
| 1920 x 1080 | rays-svgf | 3.605-3.633 ms | 2.567-2.580 ms | 0.000-0.000 ms | 0.797-0.797 ms |
| 1920 x 1080 | rays-reblur | 3.383-3.388 ms | 2.427-2.431 ms | 0.000-0.000 ms | 0.728-0.728 ms |
| 1920 x 1080 | probes | 2.612-2.663 ms | 0.000-0.000 ms | 1.415-1.445 ms | 0.933-0.960 ms |
| 1920 x 1080 | probes+gtao | 5.057-5.073 ms | 2.196-2.206 ms | 1.510-1.534 ms | 1.102-1.106 ms |
| 1920 x 1080 | probes+bitmask | 5.424-5.445 ms | 2.553-2.555 ms | 1.520-1.542 ms | 1.105-1.108 ms |
| 1920 x 1080 | probes+rays-svgf | 6.080-6.082 ms | 3.156-3.164 ms | 1.555-1.557 ms | 1.119-1.120 ms |
| 1920 x 1080 | probes+rays-reblur | 5.810-5.823 ms | 3.016-3.030 ms | 1.488-1.497 ms | 1.069-1.076 ms |
| 1920 x 1080 | probes, 64 rays, 3 cascades | 2.073-2.112 ms | 0.000-0.000 ms | 0.891-0.908 ms | 0.938-0.956 ms |
| 1920 x 1080 | probes, 128 rays, 3 cascades | 2.607-2.656 ms | 0.000-0.000 ms | 1.405-1.452 ms | 0.929-0.958 ms |
| 1920 x 1080 | probes, 256 rays, 3 cascades | 4.075-4.118 ms | 0.000-0.000 ms | 2.890-2.911 ms | 0.940-0.945 ms |
| 1920 x 1080 | probes, 128 rays, 1 cascade | 1.794-1.848 ms | 0.000-0.000 ms | 0.677-0.687 ms | 0.879-0.899 ms |
| 1920 x 1080 | probes, 128 rays, 2 cascades | 2.244-2.308 ms | 0.000-0.000 ms | 1.083-1.111 ms | 0.908-0.935 ms |
| 1920 x 1080 | probes, 128 rays, 4 cascades | 3.001-3.075 ms | 0.000-0.000 ms | 1.833-1.858 ms | 0.933-0.953 ms |
| 3840 x 2160 | raster | 3.384-3.394 ms | 0.000-0.000 ms | 0.000-0.000 ms | 2.460-2.464 ms |
| 3840 x 2160 | gtao | 10.726-10.737 ms | 6.806-6.820 ms | 0.000-0.000 ms | 2.996-3.011 ms |
| 3840 x 2160 | bitmask | 11.906-11.945 ms | 7.959-7.983 ms | 0.000-0.000 ms | 3.052-3.071 ms |
| 3840 x 2160 | rays-svgf | 14.438-14.470 ms | 10.498-10.507 ms | 0.000-0.000 ms | 3.044-3.068 ms |
| 3840 x 2160 | rays-reblur | 14.642-14.644 ms | 11.029-11.052 ms | 0.000-0.000 ms | 2.746-2.749 ms |
| 3840 x 2160 | probes | 6.210-6.220 ms | 0.000-0.000 ms | 1.521-1.534 ms | 3.735-3.748 ms |
| 3840 x 2160 | probes+gtao | 16.287-16.340 ms | 9.432-9.517 ms | 1.620-1.630 ms | 4.304-4.323 ms |
| 3840 x 2160 | probes+bitmask | 17.534-17.568 ms | 10.589-10.636 ms | 1.651-1.667 ms | 4.351-4.355 ms |
| 3840 x 2160 | probes+rays-svgf | 20.065-20.126 ms | 13.072-13.118 ms | 1.663-1.692 ms | 4.398-4.405 ms |
| 3840 x 2160 | probes+rays-reblur | 20.129-20.162 ms | 13.568-13.576 ms | 1.549-1.557 ms | 4.125-4.137 ms |

The volume's update does not depend on the resolution (1.4-1.5 ms at 128 rays and 3 cascades, about linear in rays
and cascades); sampling it costs the shading pass 0.3 ms at 1080p and 1.3 ms at 4K. Ambient occlusion at full
resolution is the expensive part: 1.6-2.6 ms at 1080p, 6.8-11 ms at 4K (each technique's tracing, then the filter);
GTAO is the cheapest, rays the dearest, the two denoisers within 0.15 ms at 1080p.

Not yet: the baked volume (`BAKED`, the same format filled offline by the path tracer and shipped as a scene asset;
it needs the scene repo's asset type, design S4). Ambient occlusion at a lower resolution, the update's budget and
the async queue: below.

### Global illumination: radiance cascades, occlusion at half resolution, the update's budget

**Radiance cascades** (`RadianceCascades`, design D9; Sannikov 2023 carried to 3D; `render.gi` = `cascades`): no
history. `render.gi.cascades.count` (4) grids around the anchor camera, the first `probes` x `probesUp` x `probes`
(32 x 16 x 32) probes `spacing` (0.5 m) apart, each next twice as far apart (as many as enclose the one before) with
four times the directions (`directions`: 8 x 8 octahedral texels for the first, 16 x 16 for the next, ...), each
direction a ray along an interval four times as long as the one before's (`interval`: 2 spacings for the first),
starting where it ended; the last cascade's go on to the environment. The rays are the probe volume's wavefront with
intervals (`PATH_INTERVALS`: a ray ends at its interval; meeting nothing there, it records no light), so hits shade as
everywhere else. Every frame the cascades merge from the last to the first: a direction that met nothing takes the
next cascade's light around the probe in the four directions refining it, trilinearly over the probes not inside
geometry whose intervals continue this one (`visibility`: nothing between where it ends and where theirs start, nor
between a coarser and a finer direction's starts; without that, light came through walls: `docs/pitfalls.md`).

Every cascade becomes a cascade of a probe volume (irradiance with sky visibility, distance moments), sampled by every
shading path exactly as the probe volume is. A point takes the finest cascade whose probes enclose it wherever the
camera is (the box each reaches from the camera however its grid has snapped: 7.25, 7.75, 8.25 and 9.25 m across,
3.25 to 5.25 m up), handing over to the next near that box's edges (`render.gi.probes.blend`'s share of it) and from
the last to the environment: its light follows the camera continuously, not the 0.5 m steps the grids snap by
(`PROBE_VOLUME_ANCHORED`). A coarser cascade's intervals start away from its probes, so each of them also traces the
first cascade's 8 x 8 directions up to where its intervals start (`PATH_NEAR`; a surface met there hides what the
intervals found beyond it), and its own directions are gathered into those 8 x 8 for shading. The cascades all cover
about the first one's region (radiance cascades' cost: an eighth of the probes, four times the directions each), so
`reach` (28 m; vertically as the first grid's probes up to across, 14 m) adds a level beyond the last: probes as
finely spaced as 32 along an axis allow (2 m: 31 x 17 x 31), each tracing the first cascade's 64 directions on to the
environment; past it the environment alone (`reach` 0: past the last cascade). Before, shading had the first cascade
alone and the probes at its border lit every point past it: streaks outdoors, a lamp's light far past its reach, and
steps each time the camera crossed 0.5 m (`docs/pitfalls.md`). Fixed directions and no history: a still scene's light
does not move, changes show in the frame they happen, further bounces a frame each (the cascades of the frame before
light the hits). Tested (`RadianceCascadesTest`): the white furnace within 2.1 % (3 % allowed), a light changing
color in 3 frames, two views one update, no allocation; ground past the reach takes exactly the environment's light,
and ground lit beyond the first cascade changes as little moving 2 cm across a grid's step as elsewhere.

Against the references (as above; RX 9070 XT, the probe volume's columns 2026-11-04 at its new default budget of one
half, the cascades' 2026-11-07; toggle and mover run alone, `-PgiScenes=toggle,mover`: after the other scenes they
start from a state left over, before this change as after):

| Scene | Probes | Probes + BITMASK | Cascades | Cascades + BITMASK |
|---|---|---|---|---|
| sun-room | 7.3 %, 29.0 %, +1.0 % | 6.7 %, 26.0 %, -0.4 % | 7.4 %, 33.0 %, +3.3 % | 6.9 %, 27.5 %, +2.3 % |
| leak-5cm (absolute) | 0.0001 | 0.0001 | 0.0000 | 0.0000 |
| leak-25cm, leak-50cm (absolute) | 0.0000 | 0.0000 | 0.0000 | 0.0000 |
| bleeding | 22.5 %, 96.0 %, +17.4 % | 20.5 %, 74.3 %, +15.8 % | 22.8 %, 142 %, +19.9 % | 21.2 %, 124 %, +17.2 % |
| screens | 17.4 %, 68.6 %, -14.4 % | 16.2 %, 66.9 %, -13.7 % | 14.0 %, 70.3 %, -6.3 % | 12.7 %, 60.8 %, -6.2 % |
| toggle (light on) | 8.2 %, 18.8 %, -6.8 % | 7.5 %, 17.7 %, -6.0 % | 6.7 %, 15.0 %, -5.9 % | 6.9 %, 15.3 %, -6.3 % |
| door (shut, absolute) | 0.0000 | 0.0000 | 0.0000 | 0.0000 |
| door (open) | 8.1 %, 31.5 %, -1.5 % | 7.3 %, 27.5 %, -1.5 % | 6.5 %, 23.4 %, -2.8 % | 6.3 %, 21.3 %, -3.7 % |
| mover (moved) | 5.6 %, 16.7 %, -4.5 % | 4.4 %, 12.9 %, -2.3 % | 4.9 %, 13.6 %, -0.3 % | 4.9 %, 13.7 %, -1.3 % |
| cornell | 35.3 %, 200 %, +19.6 % | 22.7 %, 109 %, +4.2 % | 18.2 %, 82.1 %, -9.5 % | 17.5 %, 82.3 %, -11.8 % |
| Sponza, view 1 | 18.0 %, 119 %, -2.0 % | 18.5 %, 87.6 %, -5.4 % | 17.4 %, 108 %, -2.8 % | 16.9 %, 90.5 %, -4.9 % |
| Sponza, view 2 | 25.9 %, 145 %, -3.0 % | 26.2 %, 122 %, -6.3 % | 28.2 %, 236 %, +6.4 % | 27.0 %, 197 %, +4.9 % |
| Sponza, view 3 | 79.5 %, 293 %, +32.5 % | 78.4 %, 224 %, +25.2 % | 66.3 %, 158 %, +6.1 % | 64.4 %, 138 %, -1.1 % |

Closer than the probe volume in most views and as close in the rest but Sponza's second (where the first grid's
border probes had come closer, 22.6 %, by chance: the far end of the gallery took the light of probes near the camera;
with the environment past the last cascade and no reach, views 2 and 3 were 119 % and 124 %), with a lag of the
bounces alone (frames to 90 %: toggle 6, door 5, mover 3; no noise at all; the probe volume's below). It costs about
5 times as much (8.2 ms at any resolution, 6.7 without the reach, against 1.6 ms for every probe): a trade-off, so both
stay, and `probes` remains the default. Tuning, measured on these scenes: 4 x 4 or
6 x 6 first directions alias small bright emitters (the Cornell box's lamp: 25 % and 48 %), a 1 m spacing loses the
small rooms (sun-room 17 %), other first intervals leak or blur more. Many lights shaded through the world grid's
reservoirs pick one at random: the only noise it can have.

**Ambient occlusion at half resolution** (each AO kind's `resolution` = `FULL` or `HALF`, e.g. `render.ao.gtao.resolution`): one pixel of
each 2 x 2 traced (its depth, normal and halved motion copied into images of that size), denoised at that size
(`Denoiser` takes the resolution's shift), and brought back by the composite from the four texels around each pixel,
bilinear, by how near each lies to the pixel's plane and how alike their normals are. Against 512 rays a pixel, mean
over the GI scenes (`GiEvaluation`'s occlusion table, `-PgiSettings=render.ao.bitmask.resolution=HALF`, and the other kinds'): GTAO 0.0235 full,
0.0244 half; BITMASK 0.0186, 0.0198; rays with ReBLUR 0.0348, 0.0356; rays with SVGF 0.0073, 0.0128 (one ray a pixel
loses the most). At 4K it costs a third: GTAO 6.8 to 2.3 ms, BITMASK 8.0 to 2.6, rays 10.5-11.1 to 3.0-3.1 (1080p: 1.6-2.6 to 0.5-0.8 ms). A
trade-off: both stay, `FULL` the default (`halfResolutionComesCloseToFullResolutionAndKeepsEdges`: every technique
within 0.02 of full resolution, the open floor at a block's edge kept open).

**The update's budget and queue.** `render.gi.probes.budget` now defaults to one half: still probes update in turns,
every other frame; a probe that restarts (a change told or found) updates every frame until its history holds
`render.gi.probes.fresh` (8) updates, so the budget spreads still probes' work, not a change's response. Lag at
budgets 1, 0.5 and 0.25 (frames to 90 %, told / found): toggle 3/3, 3/3, 3/5; door 2/3, 2/5, 2/9; mover 21/30, 7/39,
5/36. The update's passes: 1.56, 0.98, 0.71 ms (not depending on the resolution); errors within the runs' spread.
`render.gi.probes.async` runs the update of either technique (but the setup and the invalidation, which read the scene's
instances) on the async compute queue; persistent images now live on both queue families so async passes may use them
(`Persistent`, `GraphCompiler`). On Sponza that was 0.1 ms slower at 1080p and 4K alike (frame span 2.86 against
2.72 ms, 6.39 against 6.27): little graphics work comes before the shading that waits for it. Off by default
(`theUpdateOnTheAsyncComputeQueueLightsTheSame`).

**The mover's lag** (told 6 to 26 frames over the evaluations, found 26 to 39; the targets are 4 and 15) is not light
arriving late. Averaging 16 runs a frame instead of 4 halves the noise and leaves the told curve as it was: within 13 %
of the change at frame 3, then a plateau at 8 to 9 % for about 25 frames that fades as the restarted probes'
histories fill. It follows the history's length (`maxFrames` 4: 3 frames, with 4.5 times the noise), not which probes
restart (`instanceMargin` 3 and 100 alike: every probe of the room restarts). Restarting each probe once more after
the bounces settle (3, 4 or 6 frames later: lag 23 to 33) and bounding the history by a fast one (2 or 3 of its
deviations: lag 9, noise up 13 to 44 %) did not help; neither was kept. Cause not found. Radiance cascades reach 90 %
in 3 frames there.

What it costs (as above, with the frame's GPU span over every queue; 2026-11-04):

| Size | Technique | Frame (passes) | Frame (GPU span) | AO passes | GI passes | Shading pass |
|---|---|---|---|---|---|---|
| 1920 x 1080 | probes (budget 1) | 2.713-2.729 ms | 2.856-2.872 ms | 0 | 1.557-1.567 ms | 0.914-0.917 ms |
| 1920 x 1080 | probes, budget 0.5 | 2.132-2.174 ms | 2.264-2.274 ms | 0 | 0.973-1.003 ms | 0.901-0.915 ms |
| 1920 x 1080 | probes, budget 0.25 | 1.863-1.892 ms | 1.969-2.015 ms | 0 | 0.711-0.723 ms | 0.906-0.910 ms |
| 1920 x 1080 | probes, graphics queue | 2.625-2.669 ms | 2.689-2.746 ms | 0 | 1.433-1.449 ms | 0.944-0.964 ms |
| 1920 x 1080 | cascades | 9.160-9.237 ms | 9.313-9.359 ms | 0 | 8.137-8.193 ms | 0.602-0.610 ms |
| 1920 x 1080 | cascades, no reach | 7.717-7.764 ms | 7.846-7.890 ms | 0 | 6.715-6.747 ms | 0.574-0.579 ms |
| 1920 x 1080 | cascades, 4 x 4 directions | 3.724-4.149 ms | 3.837-4.285 ms | 0 | 2.689-3.008 ms | 0.598-0.656 ms |
| 1920 x 1080 | cascades, 6 x 6 directions | 6.194-6.315 ms | 6.336-6.478 ms | 0 | 5.168-5.264 ms | 0.598-0.615 ms |
| 1920 x 1080 | gtao, half | 1.525-1.534 ms | 1.581-1.590 ms | 0.541-0.543 ms | 0 | 0.746-0.749 ms |
| 1920 x 1080 | bitmask, half | 1.567-1.629 ms | 1.622-1.657 ms | 0.626-0.628 ms | 0 | 0.709-0.751 ms |
| 1920 x 1080 | rays-svgf, half | 1.741-1.824 ms | 1.801-1.855 ms | 0.809-0.824 ms | 0 | 0.712-0.754 ms |
| 1920 x 1080 | rays-reblur, half | 1.611-1.646 ms | 1.665-1.702 ms | 0.682-0.695 ms | 0 | 0.705-0.725 ms |
| 3840 x 2160 | probes (budget 1) | 6.426-6.440 ms | 6.383-6.399 ms | 0 | 1.765-1.770 ms | 3.688-3.697 ms |
| 3840 x 2160 | probes, budget 0.5 | 5.878-5.891 ms | 5.812-5.839 ms | 0 | 1.201-1.213 ms | 3.697-3.701 ms |
| 3840 x 2160 | cascades | 12.024-12.034 ms | 12.136-12.161 ms | 0 | 8.244-8.247 ms | 2.328-2.336 ms |
| 3840 x 2160 | cascades, no reach | 10.478-10.549 ms | 10.591-10.664 ms | 0 | 6.794-6.833 ms | 2.230-2.259 ms |
| 3840 x 2160 | gtao, half | 5.862-6.047 ms | 5.913-6.104 ms | 2.312-2.317 ms | 0 | 2.699-2.844 ms |
| 3840 x 2160 | bitmask, half | 6.313-6.330 ms | 6.364-6.378 ms | 2.554-2.560 ms | 0 | 2.876-2.885 ms |
| 3840 x 2160 | rays-svgf, half | 6.875-6.883 ms | 6.934-6.944 ms | 3.098-3.106 ms | 0 | 2.888-2.892 ms |
| 3840 x 2160 | rays-reblur, half | 6.643-6.655 ms | 6.697-6.718 ms | 3.016-3.024 ms | 0 | 2.760-2.763 ms |

The cascades' rows (2026-11-07; the others 2026-11-04): 8.2 ms, of which merges 2.6 (a ray per continuation, and every
cascade's probes into the volume), the reach's probes 0.5, tracing 1.7, hit shading 1.8, binning 0.9, rays 0.6. Before
every cascade was shaded (the same day): 6.9 ms (1080p 6.83-6.92, 4K 6.87-6.91), of which merges 3.4, tracing 1.3, hit
shading 1.0, binning 0.8, rays 0.45; gathering a probe's directions by a table of them in shared memory, its distance
kernel by squaring, took the merges from 4.5 to 2.6 ms and the reach's 16,337 probes from 2.6 to 0.5. Memory: the
atlases and states 25 to 67 MiB (40 without the reach), the ray image 52 to 86 MiB (57), the paths 572 to 774 MiB
(614; 5.1 million rays a frame, 4.0 million without the reach, 3.7 million before).
`GiBenchmark` takes `-PgiSetups=a;b` and `-PgiPasses=true` (each pass's median); `GiEvaluation` takes `-PgiVariants`,
`-PgiParts=errors,ao,lag`, `-PgiSettings=name=value,...`, `-PgiTrials` and `-PgiCurves=true`.

## Frame graph

`engine-graph` assembles the frame from **techniques**, such as the meshlet scene, bloom or tone mapping, without any
hand wiring. A technique states which results it provides (by `Key`), requires the results it needs, and adds passes
that declare how they use each image. The graph does the rest:

- **Resolved by key:** requiring a result builds the technique that provides it, the first time. Techniques nothing
  needs are never built, and providers always come before consumers. Errors name the techniques involved: a key
  nobody provides, two providers of one key, a cycle, writing someone else's result, reading an image before
  anything writes it.
- **Shared work runs once:** building blocks such as `Blur` and `DepthPyramid` (a farthest-depth pyramid with a full
  mip chain, for occlusion culling and screen-space effects) are `Request`s, values with inputs and parameters. Every
  equal request from any technique gets the same result: two techniques asking for the scene blurred by 4 texels
  share one blur.
- **Swappable:** `replace` swaps a technique for another providing the same keys (one lighting or translucency
  technique for another). `implement` changes how every request of a type is computed (a cheaper blur on low
  settings). Nothing else changes.
- **Views:** `graph.addView(name)` gives a `ViewPort`, a camera's share of the frame with techniques and outputs of
  its own (`view.add(...)`, `view.output(key, target)`) and a size, `view.size(w, h)`, which its techniques read when
  they build (a resized window sets it, and the graph rebuilds). Its techniques' keys are the view's, so two views can
  each provide the scene color; requiring a key from a view gets the view's version, else the frame's, and the frame's
  techniques see no view's keys. Techniques added to the graph itself are the frame's, built once for every view, and
  equal requests are shared whichever view makes them. `g.view()`, `g.require(view, key)` and `g.requireAll(key)`
  cross between views, and a view's histories are its own. The scene's techniques of a view (shading, light binning,
  shadows) take a `SceneView`, which ties the port to the scene's camera and the `RenderView` its culling draws.
  `graph.addPerView(port -> technique)` gives every view, now and later, a technique of its own, and a view drops one
  by id (`port.remove(id)`). A view owns what is made for it (`port.own(resource)`, its render view among them),
  closed when it is removed or the graph closes. `SceneViews` does both for the scene: `addPerView(view -> ...)`
  factories, and `add(name, camera, options)` making the port, its render view and its techniques at once.
  `view.enabled(false)` skips a view from the next frame and `view.rate(4)` runs it every 4th frame, neither
  rebuilding the graph: a skipped view runs none of its passes, nor shared work only it asked for, its outputs into
  windows are not touched (not acquired, not presented), and its histories keep what the last frame it ran wrote. Shared work that serves several
  views leaves out a skipped one's part by asking `g.running()` at record time: the renderer's culling culls only the
  views that run.
- **Resolution:** `view.size(w, h)` is the size a view shows at; its images, which techniques size themselves by
  (`view.width()`), are that times `resolution.scale` (`ResolutionSettings`, the graph's or the view's own). With
  `resolution.dynamic` on, the images are allocated for `resolution.maxScale` with `resolution.headroom` to spare, and
  each frame renders into a region of them: `view.renderScale(0.7f)` moves it from the next frame, between
  `resolution.minScale` and the maximum, and a new size that still fits moves it too. Neither rebuilds; an allocation
  larger than needed for `resolution.shrinkFrames` frames shrinks. Passes that render within the region say so
  (`pass.region()`: the graph sets the rendering area, viewport and scissor to the region's share of their attachments),
  and compute work reads `ctx.regionWidth(image)` and `ctx.regionHeight(image)` while recording; shared work drawing
  for one view names it (`pass.region(view)`). The engine's techniques all do: the scene's draws, occlusion culling and
  its depth pyramid, light clusters, the visibility buffer's shading and ray-traced shadow masks. `DisplayTransform`
  scales the region up to the size the view shows at, filtered within the region (`g.unscaled(image)` marks such a
  result); other outputs are scaled from the region unfiltered. `new DynamicResolution(graph, view).update()` once a
  frame picks the scale from the GPU time, aiming at `resolution.targetMillis`. Detail follows the scale: a view's
  draws choose levels for the pixels actually rendered (`RenderView.detail`), cameras being set for the size the view
  shows at.
- **Switches:** `technique.when(setting)` (a `Switch`) runs the technique only while a boolean setting is on, read
  every frame: no rebuild. An in-place effect switched off leaves its image as it was; a producer gives a fallback for
  each image others sample (`fallback(key, Fallback.constant(format, value))`), read instead while it is off. A view's
  own settings (`view.ownSettings()`) win over the graph's, so one view can turn an effect off.
- **Textures:** `graph.texture(name, format, w, h)` is an image the graph keeps from frame to frame, resting readable
  by shaders, with a bindless slot that never changes (`texture.slot()`). A view renders into it with
  `view.output(key, texture)` (a mirror, a security camera's screen), and a frame the view is skipped leaves it as it
  was. A technique of another view reads it with `g.texture(texture)`, which orders its pass after the copy into it,
  and samples it at its slot (`ctx.sampled(image)`). It belongs to the graph, not to the view: rendered once and its
  view turned off or removed, it keeps its image. `graph.removeTexture(texture)` frees it once no frame in flight
  samples it; otherwise it closes with the graph. `new Texture(device, name, format, w, h)` is the app's instead: any
  graph renders into and reads it, it outlives them (a one-shot render kept for later), and the app closes it
  (`close()`, or `close(retiring)` to free it once no frame in flight reads it). A graph using a closed texture fails
  when it builds.
- **Presenting:** a `Presenter` shows views of one graph in any number of windows, all from one frame:
  `presenter.present(view, key, window, settings)` gives the window a swapchain and sizes the view to its pixels
  (resized with it), and `presenter.render()` renders a frame and presents every window whose view ran. A window
  whose view is off, between frames of its rate, or minimized is neither acquired nor presented, and keeps its last
  frame. `PresentSettings` (vsync: the present mode) changes while running (`target.apply(settings)`) and saves and
  loads like `WindowSettings`; closing a window or its target stops presenting to it.

- **HDR and tone mapping:** shading writes linear scene color (a float format), and each window's output pass tone
  maps and encodes it for the display that window is on, which its swapchain tells: SDR (sRGB), HDR10 (Rec. 2020,
  PQ) or scRGB (linear floats). `presenter.outputTransform(new DisplayTransform())` turns it on; `PresentSettings.HDR`
  asks for HDR where the display offers it, and a window moved to another display, or HDR toggled, is encoded again.
  `DisplayTransform`'s settings change while running, without a rebuild: the tone curve (`display.toneCurve`: `off`,
  `reinhard`, `aces`, `reinhard_extended`, `aces_fitted`, `agx`, `pbr_neutral`, `gt`, `hable`, each its published
  reference code, tested against a Java port of it in SDR and HDR10; tunables `display.toneCurve.agx.look` and
  `display.toneCurve.reinhard_extended.whitePoint`), exposure, paper white (how bright SDR white and UI show in HDR,
  200 nits), the display's peak brightness (1000 nits) and `display.dither` (a triangular dither of one step before 8
  or 10 bits, on; black and white stay exact). In SDR the curve maps into 0 to 1; in HDR scene color 1.0 shows at
  paper white and brighter colors roll off towards the peak (PBR Neutral and GT by HDR forms of their own). A surface
  offering no sRGB format gets UNORM images (`DisplayEncoding.SDR_UNORM`), which the output pass encodes by the exact
  sRGB curve. Hooks for post-processing plugins (effects-post): a technique of the view providing
  `DisplayTransform.EXPOSURE_INPUT` (a buffer holding a float, auto exposure's) replaces `display.exposure`; and
  `new DisplayTransform().displayEffects()` splits the output pass in two (`OutputTransform.transforms`), tone mapping
  into `DisplayTransform.DISPLAY_COLOR` (display light, 1 at paper white, without the UI) and then compositing the UI
  and encoding it, so techniques modifying `DISPLAY_COLOR` (at `Anchor.TONE_MAP`) run in display light between them.
  HDR windows tell their displays about the content (`VK_EXT_hdr_metadata`, where the GPU has it):
  the peak as its brightest and paper white as its frame average, followed as the settings change
  (`OutputTransform.metadata`, `target.metadata()`).
- **UI composition:** a view's UI is an image under `Keys.UI` (premultiplied alpha, linear, 1.0 paper white, at the
  view's output size; engine-ui's renderer provides it). `DisplayTransform` composites it after the tone curve, so
  the UI is neither exposed nor tone mapped: SDR shows `toneMapped * (1 - a) + ui`, HDR adds the UI at paper white
  times `ui.brightness` (`DisplayTransform.UI_BRIGHTNESS`), UI brighter than white clipped at the peak. A view without
  a UI is encoded as before; a window presented without an output transform shows no UI (use a `DisplayTransform`
  with the `NONE` curve to copy the scene unchanged and show the UI over it).

```java
try (Presenter presenter = new Presenter(graph, instance)) {
    for (int i = 0; i < screens.size(); i++) {           // one window per monitor, each its own view
        presenter.present(views.get(i).port(), Keys.SCENE_COLOR, screens.get(i), PresentSettings.fromMap(saved));
    }
    while (running) {
        platform.pollEvents(listener);
        presenter.render();
    }
}
```
- **Culled:** walking back from the outputs, passes whose images and buffers nothing needs are dropped.
- **Synchronized:** barriers and layout transitions come from the declared accesses, batched into one call per pass,
  with none between reads.
- **Pooled:** transient images and buffers live in a pool kept across frames. Images whose lifetimes do not overlap
  share one; so do buffers, the shared one sized for the largest. An output that fits its target (the swapchain image,
  say) is drawn straight into it; others are blitted.
- **Buffers too:** `g.createBuffer(name, bytes)` makes a transient buffer, which passes declare with a `BufferAccess`
  (indirect, index, storage from compute or draws, transfer) and shaders reach by `ctx.address(buffer)`. Buffer
  barriers come from those accesses like image barriers do, across queues as well: a buffer used by both is shared
  concurrently.
- **Typed keys:** `Key.image("acme.bloom")` and `Key.buffer("truesim.lightList")` name images and buffers;
  `g.require(key)` returns the right type, so asking a buffer key for an image does not compile. Key names are
  namespaced, like every id.
- **External buffers:** `g.importBuffer(new ExternalBuffer(name, gpuBuffer))` brings a buffer the graph does not own
  (a game's simulation state, a `RecordBuffer`, the engine's instances) into the graph: passes declare their accesses
  and the graph orders them. Its contents outlive the frame, so passes writing it are kept, and the frame's first use
  waits for whatever came before (uploads, earlier frames). Async passes use it too where both queues may: the queues
  are of one family, or the buffer has concurrent sharing.
- **The engine's data for custom passes:** `GpuScene.instanceAddress()`/`instanceBuffer()` (records as
  `meshlets/common.slang`'s `Instance`), `GpuScene.materialsAddress()`, and the `GeometryPool`'s buffers and per-mesh
  bases, by device address, for a game's own passes and shaders.
- **Modifiers:** a technique listing a key in `modifies()` takes it after its provider with `g.modify(key)` and
  writes it in place (transparency over the scene, decals) or provides a new version (a post effect). Modifiers of a
  key run in their `order()`: at an `Anchor` (`OPAQUE`, `TRANSPARENT`, `POST`, `TONE_MAP`, `UI`) or a named order
  point inside one (`Order.SKY`, `Order.SCREEN_SPACE_OCCLUSION`, `Order.SCREEN_SPACE_LIGHTING`: libraries place
  themselves by what they do, never by another library's ids), then after or before other techniques by `id()` (ids of techniques not in the graph are ignored), else in the order added. So a
  chain such as exposure, bloom, tone mapping needs no rewiring when an effect is added or removed, and a plugin can
  place its own effect in it; whoever requires the key gets its last version. A modifier nothing needs is never built.
  `graph.remove(id)` removes a technique by its id.
- **Images:** `g.create(name, ImageDesc.of(Format.R16G16B16A16_SFLOAT, w, h))` with `.fullMips()`, `.layers(n)`,
  `.cube()`, `.volume(depth)` or `.samples(n)`, or `ImageDesc.like(image, Ratio.of(1, 2))`; formats are the engine's
  `Format` enum, and usage follows from the declared accesses.
- **Parts of images:** passes declare accesses to `image.mip(i)`, `image.mips(from, n)`, `image.layer(l)` or
  `image.face(cube, f)`; barriers are derived per mip level and layer, so a pass reads one level while writing the
  next, with no barriers by hand. `ctx.sampled(part)` and `ctx.storage(part)` address one level, and attachments one layer.
- **History:** `g.history(name, desc)` keeps an image from frame to frame: this frame writes
  `current()`, and `previous()` holds what the frame before wrote (`ctx.historyValid(history)` says whether there
  was one). The graph swaps the two images' roles every frame and carries their state across the frame boundary.
- **Persistent resources:** `g.persistent(name, desc)` is one image the graph keeps from frame to frame and build to
  build, read and written in place (an accumulation, a cache filled over frames): sampled, a storage image where the
  format allows, an attachment or copied. `g.persistentBuffer(name, size)` is the same for a buffer, and
  `g.historyBuffer(name, size)` is a history of buffers: `current()` to write, `previous()` to read, swapped every
  frame (`ctx.historyValid(history)`). They start zeroed, keep their contents while builds ask for them by the same
  name and description, start over for a new one, and are freed once no build asks for them. A view's are its own.
- **Readbacks:** `g.readback(name, buffer, offset, bytes, receiver)` or `g.readback(name, image.mip(0), receiver)` copies
  off the GPU each frame; the receiver gets the bytes on the render thread once the GPU finished that frame (in the
  next `beginFrame` of its slot, or the next `record`), with nothing to invalidate.
- **Tooling:** every pass records inside a debug label of its name where the instance has debug utils (validation),
  so captures and validation messages name it. `graph.timings(true)` times each pass on the GPU; `graph.timings()`
  has the last finished frame's milliseconds per pass, read without allocating.
- **Purity:** a technique's `build` returns what it makes (images, buffers, programs, writers) and its pass bodies,
  which capture it, only record. Neither stores into fields, static fields or arrays it did not make; `check` enforces
  this on the bytecode (build-logic's `checkPurity`), and `@AllowImpure` marks a deliberate exception. Per-frame
  values come in through what bodies read when they record (the camera, a writer's setters), not through state the
  build left behind.

```java
port.add(new ClusteredForwardShading(geometry, main, VK_FORMAT_R16G16B16A16_SFLOAT, VK_FORMAT_D32_SFLOAT))
    .add(new Bloom())                       // requires Keys.SCENE_COLOR, provides BLOOM
    .add(new ToneMap())                     // requires BLOOM, provides TONEMAPPED
    .output(TONEMAPPED, swapchainImage);    // an ExternalImage, set to the acquired image each frame
graph.beginFrame(); graph.submit(acquired, renderFinished);   // per frame: nothing allocated

final class Bloom implements Technique {
    public static final Id<Bloom> ID = Id.of("acme.bloom", Bloom.class);

    public Id<Bloom> id() { return ID; }
    public List<Key<?>> provides() { return List.of(BLOOM); }
    public Order order() { return Order.at(Anchor.POST).after(Exposure.ID); }

    public void build(GraphBuilder g) {
        Image scene = g.require(Keys.SCENE_COLOR);
        Image soft = g.request(new Blur(scene, 8, Ratio.of(1, 2)));    // shared with any equal request
        Image out = g.create("bloom", ImageDesc.like(scene, Ratio.ONE));
        g.pass("bloom")
            .read(scene, Access.SAMPLED_COMPUTE).read(soft, Access.SAMPLED_COMPUTE)
            .write(out, Access.STORAGE_WRITE)
            .record(ctx -> ...);                             // ctx.sampled(scene), ctx.storage(out): bindless slots
        g.provide(BLOOM, out);
    }
}
```

The graph is rebuilt only when it changes: techniques, outputs or implementations, an external image resized, or
`invalidate()`. Per-frame data lives in the techniques, and pass bodies read it when recording. The graph's own part of
a rebuild waits for no frame in flight, except with GPU timings on (or just turned off): histories are joined and new
persistent images and buffers cleared at the start of the next frame (with async passes, in a prologue on the graphics
queue that the frame's batches wait for), and switching `asyncCompute` or `parallel` between frames waits for nothing
either (tested with synchronization validation and a count of the waits).

**Running frames.** The graph runs its frames itself, on the graphics and compute queues:

```java
graph.parallel(jobs, 4);                    // record on the JobSystem's threads, 4 passes per command buffer
int slot = graph.beginFrame();              // waits until this frame slot's GPU work is done
int image = swapchain.acquire(acquired[slot]);
target.set(swapchain.image(image), swapchain.view(image));
graph.submit(acquired[slot], swapchain.presentReady(image));
swapchain.present(device.graphics(), image);
```

- **Async compute:** passes marked `async()` (compute, sampling, storage and copies), such as the blur, run on the
  compute queue where the GPU has one. The frame is split into batches, a new one where the queue changes or where a
  pass needs work from the other queue. Timeline semaphores order the batches, so graphics work that does not need an
  async pass runs beside it. Images both queues use are shared concurrently, without ownership transfers; memory is
  aliased only between images of one queue, so that sharing never adds a wait. The first pass that touches the
  swapchain image waits for its acquisition, so everything before it can start earlier.
- **Parallel recording:** each batch is cut into chunks of a few passes, and workers record them in parallel, each
  into its own command pools. Chunks are submitted in order, so the GPU sees the same commands on any number of
  threads. The frame start (uploads of instances and such) is recorded first, on the calling thread, and submitted ahead
  of the first graphics chunk. Pass bodies then run on worker threads and must not share what they change; the
  engine's own techniques are tested to draw the same images on one thread and on four.
- **Frame slots:** `graph.frameSlots()` is what renderers, lights and materials given it keep a copy per. `beginFrame`
  opens the frame's slot once the GPU has finished that slot's previous frame, and `submit` closes it, so instances
  and lights may change every frame, before `beginFrame`, while recording, or after `submit`, without touching what a
  frame in flight reads (tested with a quad moving every frame, two frames in flight).
- **Shared slots:** graphs made with `new FrameGraph(device, slots)` share one `FrameSlots`, for several graphs
  drawing from the same meshes and materials (several scenes, see below). They run in step; the caller opens the slot
  once every graph has begun its frame and closes it once every graph has submitted.
- `record(commands)` still records everything into one command buffer, for simple uses; its per-frame data is slot
  0's, and the caller waits for the GPU between frames.

The graph's tests run with synchronization validation, which checks every barrier and cross-queue wait. Dropping the
waits makes it report races between the queues.

## Drawing scenes

`engine-scene` draws scene's `Renderable` components (a mesh and per-submesh materials on a node, from
`scene-render`; application-api.md D46) with the renderers above, following the scene's change logs and asset cache.
A renderable's change logs the fields it changed (mesh, materials, shadows, skin), and the sync redoes only those: a
material swap takes new materials and keeps the mesh and the instance id.

- `SceneResources` is the GPU side of the assets, shared by every scene on a device: meshes in a `GeometryPool`,
  materials in `Materials`, textures in `Textures`. It acquires what scenes use in the `AssetCache`, uploads each asset
  once it loads, however many scenes and nodes use it, and removes it from the GPU (once no frame in flight reads it)
  and releases it in the cache when nothing does. Materials draw as the default until they load, untextured where
  their textures have not loaded (or failed to); a renderable's materials take a block of ids, one per submesh, shared
  by every renderable with the same materials and mesh layout.
- **Streamed or whole, the application's choice and each asset's:** `resources.loading(Loading.STREAM)` (the default)
  draws a renderable as soon as its mesh is on the GPU, as above; `Loading.WHOLE` holds it back until its materials
  and their textures are there too, so nothing pops in. `resources.loading(asset, mode)` overrides the default for one
  material or texture (a sky texture that may come late); meshes are always waited for. Failed assets, and assets that
  cannot go on the GPU, are reported and never waited for. A drawn renderable changing mesh or materials keeps its
  instance id and looks until the new ones are there.
- **Loading screens and startup:** a loading screen keeps drawing frames until `resources.loaded()`
  (`waitingCount()` for a progress bar); `resources.awaitLoaded(tasks, timeout)` instead blocks the main thread,
  draining the scheduler, until everything waited for is on the GPU, for startup and tests. Update the scenes before
  (to acquire) and after (to draw), and the level draws whole from the next frame.
- **Prepared on worker threads:** `resources.prepareOn(tasks, threads)` makes meshes and textures ready for the GPU
  (`GeometryPool.prepare`, `Textures.prepareColor`) on that many threads of the scheduler, handed over as it drains,
  so the main thread only places them: a frame of latency for milliseconds of main-thread time per large asset.
- **Streamed content within a frame budget:** `render.streamFrameBudget` (0.1 ms) bounds what a frame spends taking
  in content made ready on workers (meshes placed, textures added) and letting go of what removed renderables used.
  It runs from the frame's start (`SceneResources.beginFrame()`, which the assets plugin calls before each update), so
  the frame's own streaming work (nodes attached or removed, renderables taken in or torn down) counts toward it, and a
  frame that spent it leaves the rest to the next. Material blocks and meshes others still use are let go of at once (a count taken
  down); only the last user's release waits for the budget. `render.meshSpares` (64) keeps removed meshes' pool ranges
  for meshes of the same sizes (`GeometryPool.spares`), and the assets plugin's `assets.meshPool` (64) keeps evicted
  generated meshes' memory for generators building into it (`Assets.meshPool()`, scene's `MeshPool`): content that
  comes and goes in equal sizes is placed without allocating and leaves without freeing.
- **Batches** (scene's `SceneBatch`, `SceneGraph.attach`, `remove`): a subtree attached or removed in one pass is one
  entry of each change log, and the sync takes it in one pass too: a batch's renderables are reconciled together (a
  mesh shared by neighbours looked up once) and the instances of those torn down leave the `GpuScene` at once
  (`GpuScene.remove(int[], ...)`, the records zeroed in bulk).
- `SceneSync` keeps a scene on the GPU: its renderables as instances of a `GpuScene` at their world positions,
  written once and again only where nodes moved or renderables changed. Given a `JobSystem`, many instances at once
  (most nodes moved) are claimed in order and written on its threads: 30,000 moving rocks in 0.5 ms on 4 threads
  against 1.0 ms on one, the same ids and image either way. Drawing is the renderers' business: cameras
  are `SceneView`s (a `RenderView` in a graph view), each at its own world position (moving one rewrites no
  instance), drawn by their views' techniques, so the rendering mode is the graph's: `ClusteredForwardShading`,
  `VisibilityBufferShading`, shadows, swapped with `port.replace`.
- **Between fixed steps** (`sync.follow(clock)`): after each step of the world's clock the sync updates the scene's
  world transforms and captures the renderables the step moved; each frame draws them `clock.blend()` of the way from
  their pose at the step before to the last step's, as `render.interpolation` says (`gpu` by default, `gpu_precise`, `cpu`, `off`; syncs on resources
  given settings, as the assets plugin's are, follow it as it changes; `sync.interpolation(mode)` sets one by hand).
  On the GPU (`GpuScene.motion()`, an `InstanceMotion`) each moving instance has a pose in each of two buffers that turn
  over every frame with steps: a step uploads only its own pose (the step before's stays; both in a move's first step
  or after a catch-up frame), and a compute pass after the scene upload blends them into the instance table before
  anything reads it: frames without a step cost the CPU nothing per instance. `GPU` packs a pose into 32 bytes
  (positions as floats from where the first mover started, within 0.7 mm 10 km from there; rotations snorm16, within
  0.003 degrees; scales one float where uniform, else halves); `GPU_PRECISE` keeps positions in two parts (64 bytes).
  `CPU` writes every moving
  instance's blended pose each frame (for frames recorded without a frame graph, which run no blend pass); `OFF` draws
  steps as they are. `sync.teleport(node)` in the step that moves it draws it where it lands; `interpolated(node,
  false)` opts a node out; a node moved outside the steps is drawn where it is. With 30,000 rocks moved every step at
  60 steps and 144 frames a second (2026-11-03, RX 9070 XT, on a loaded machine): `GPU` 0.61 ms of sync and 1.08 MB
  uploaded per frame with a step, 0.001 ms and nothing without, the blend pass 0.006 ms on the GPU; `GPU_PRECISE` 0.57
  ms and 2.04 MB, 0.009 ms; `CPU` 0.44 ms and 2.04 MB with a step, 0.24 ms and 2.04 MB without (application-api.md
  D122).
- **Skinned joints between fixed steps** (animation.md A5): an animator on fixed steps (`animator.fixedSteps(true)`)
  runs in the `Animation` plugin's `FIXED`, once per step of its world's clock, deterministic whatever the frame rate.
  The sync following that clock poses the skinned copies whose joints a step moved, on its job system, and draws them
  between their last two steps' poses, the skinning matrices blended linearly (each vertex that part of the way
  between the two poses), as `render.interpolation` says: on the GPU the skinning blends both steps' matrices kept in
  the deformation's buffer, on the CPU the sync writes the blend each frame, `OFF` draws steps. `sync.teleport(body)`
  in a step snaps the joints; joints moved outside the steps are drawn as posed. 1,000 characters of 24 joints at 60
  steps and 144 frames a second (`SkinnedCrowdBenchmark`): animated every frame, 2.4 ms of main thread every frame; on
  fixed steps blended on the GPU, 2.6–3.3 ms in frames with a step and 0.01–0.03 ms in the others (half the time per
  second), 1.7 times the joint matrices uploaded, the same 0.49 ms of GPU deformation (application-api.md D91).

```java
FrameSlots slots = new FrameSlots(2);                 // shared by every graph drawing these resources
GeometryPool pool = new GeometryPool(device, slots); // the caller's: closed by it, after the resources
Materials materials = new Materials(device, 4096, slots);
Textures textures = new Textures(device, slots);
SceneResources resources = new SceneResources(assets, pool, materials, textures)
    .loading(SceneResources.Loading.WHOLE)            // or STREAM, the default; per asset with loading(ref, mode)
    .prepareOn(tasks, 2);

GpuScene worldGpu = new GpuScene(device, resources.pool(), 100_000, slots);
SceneSync world = new SceneSync(resources, worldScene, worldGpu);
SceneSync gizmos = new SceneSync(resources, gizmoScene, worldGpu);     // same table: its nodes on layer 1
SceneGeometry geometry = SceneGeometry.meshlets(new MeshletRenderer(device, worldGpu, colorFormat, depthFormat, slots));
FrameGraph worldGraph = new FrameGraph(device, slots);
ViewPort port = worldGraph.addView("main").size(width, height);
SceneView view = geometry.createView(port, camera, ViewOptions.of(1 << 20, 1 << 24).withOcclusion(true).withLayers(1));
port.add(new VisibilityBufferShading(geometry, view, colorFormat, depthFormat))
    .output(Keys.SCENE_COLOR, swapchainTarget);
SceneSync preview = ...;                              // another scene: its own GpuScene, renderer and graph

// every frame
int slot = worldGraph.beginFrame();
previewGraph.beginFrame();
slots.begin(slot);
tasks.drainCompleted();                               // the frame boundary: loads arrive and upload
worldScene.update(); gizmoScene.update();
world.update(); gizmos.update();
camera.position(cameraX, cameraY, cameraZ);           // the camera's world position, in doubles
view.set(1, MeshletRenderer.MODE_LIT);
...                                                   // the same for the preview
worldGraph.submit(acquired[slot], presentReady);
previewGraph.submit(0, 0);
slots.end();
```

More cameras on a scene are more views (`graph.addView`, each a `SceneView`), each with lists, occlusion history,
scene color and light list of its own. Scenes share a `GpuScene` told apart by layers, or each have their own with a
renderer and graph; either way they use the same resources, and a mesh several use is on the GPU once.
The tests draw two scenes in step, one forward and one through a visibility buffer, and two scenes from one
`GpuScene`, with validation on.

Renderables keep their instance ids while drawn, are on their nodes' layers and cast shadows as they say; their
materials' masked alpha and double-sidedness follow (blended alpha draws opaque until transparents come). Not
followed yet: lights and cameras from the scene.

## Particles

GPU particles live in [true-sim/effects-particles](https://github.com/true-sim/effects-particles): scene-effects'
effects simulated, sorted and drawn in the frame graph, and a scene's effects synced each frame (`EffectSync`,
`EffectsPlugin`, `EffectBindings`). The library uses only the core's generic hooks: the radix sort block
(`RadixSort`), named order points (`Transparency.ID`), the settings registry, GPU-written instances and lights
(`GpuScene.reserveGpuRange`, `Lights.gpuLights`), Slang modules imported from the engine's jars, and the content
techniques hold through `SceneResources` (`hold`, `texture`, `mesh`, `holdMaterials`, `residency`; types the
resources do not know, such as vector fields, go through an uploader registered with `sampled`).

## Audio

`engine-audio` plays scene-audio's stores (design: scene's `docs/audio.md`): a mixer in Java on an allocation-free,
lock-free audio thread, and SDL3's audio stream in push mode as the output (AU1, AU2).

```java
app.use(new Desktop(), new Audio());                  // optional: no device means silence and a log line
Audio audio = engine.get(Audio.KEY).assets(cache);
Sounds sounds = audio.sounds(scene);                  // advanced and synced by the plugin each frame
sounds.listeners().device(cameraNode);
sounds.fire(KNOCK, x, y, z);
audio.ui().play(CLICK);
```

- **`AudioRenderer`**: commands in through a single-producer ring, blocks out, events (voices ended) back; offline with
  `AudioRenderer.offline(AudioFormat.stereo(48_000))` and `render(frames)`, bit-identical for the same commands.
  Voices resample with a cubic Hermite, ramp every gain, pan (VBAP on speaker layouts), attenuate, low-pass and
  Doppler placed sounds, and keep the most audible 64 real (every 4 blocks; the others keep time). Buses from a
  `BusLayout`, a look-ahead limiter on the master. `Fft` for the steps that need spectra.
- **`AudioSync`** (game thread): the stores' change logs into commands at the clock's schedule point, clips acquired
  and started when loaded (dropped past their max start delay), positions relative to each sound's nearest device
  listener in doubles.
- **Outputs**: `SdlOutput` (the device's own format, a Java platform thread with MMCSS "Pro Audio" and a
  high-resolution timer on Windows, the queue kept at least a device buffer and a block deep, underruns counted and
  guarded), `NullOutput`, `OfflineOutput`.
- **HRTF** (§2.9): with `audio.output` set to `HEADPHONES`, sounds marked `Spatial.HRTF` render binaurally through
  the spatializer `audio.spatializer` chooses (`Audio.SPATIALIZER`: `hrtf`, a game's own `Spatializer`, or `off`), the
  16 most audible (`audio.spatializer.hrtf.voices`) through `HrtfSpatializer` (partitioned FFT convolution of minimum-phase filters
  interpolated between measured directions, cross-faded as sources move, a near field), the others panned. Sets are
  `.hrtf` files converted from SOFA at build time (`./gradlew :engine-audio:convertHrtf -Ptruesim.audio.sofa=...`);
  `audio.spatializer.hrtf.set` names one, else the built-in set plays: **SADIE II's KU100 (University of York, Apache License
  2.0; Armstrong et al., Applied Sciences 8(11) 2029, 2018, doi:10.3390/app8112029)**, converted, with its licence
  and notice in `audio/src/main/resources/com/truesim/engine/audio/hrtf/`. Games crediting their audio middleware
  credit it there too (`Hrtf.credit()`).
- **Settings** (group "audio"): player volumes (saved with the app's settings), device, background rule, output and
  HRTF set; block size, queue, voices, driver and HRTF voices hidden.
- **Many sounds**: `Scatter` roots spawn one-shots around the listener (8 at once at most); `sounds.field(sound,
  SoundField.points(...) | line(...) | area(...))` plays one loop from many places: real voices at the nearest few
  and a 2D bed at the rest's summed attenuation.
- **Occlusion** (§2.11): `sync.occlusion(world, jobs)` traces sounds marked `Occlusion.RAYS`, 64 rays a frame
  round-robin, through an `AudioWorld` (physics: `CollisionAudioWorld`), smoothed (attack 100 ms, release 300 ms).
  On devices with ray tracing, the app joins the render's `SegmentTracing` (which knows nothing of audio) to an
  engine-audio `TracedAudioWorld` and gives it to `sync.occlusion(world)`:
  `new TracedAudioWorld(SegmentTracer.of(segments.capacity(), SegmentTracing.WORDS, segments::submit,
  segments::collect), id -> ...)`. The frame's rays go out as one batch of the tracing in the main view, the results
  come back a frame or more later (up to 4 batches out) and the smoothing hides the wait. The table maps the scene's
  material ids to `AudioMaterial`s (`resources.materialOf(id)` names their assets); `segments.frontsOnly(true)` counts
  a closed mesh once, as physics counts a convex body, `false` each triangle, as physics counts a triangle mesh. On
  4,096 random rays through 25 boxes the two agree on every ray, as triangle meshes and as convex bodies (scene
  0.58.1 on: before, physics stepping out of one box missed the box behind it); a batch of 4,096 rays costs the GPU
  0.04 ms and the game thread 0.03 to 0.06 ms a frame, where physics takes 7.6 ms on one thread (RX 9070 XT, demo's `TracedOcclusionTest`, 2026-11-03).
  Without ray tracing there is no `SegmentTracing`: give the physics world, or none.
- **VR**: `audio.head(sounds, xrInput::head)` listens from the headset (and turns the HRTF on with `audio.output` at
  `AUTO`); `audio.headsetDevice(headset.audioOutputDevice())` plays on the headset's device where the runtime names
  it; the headset's idle and focus reach the background rule. HRTF voices past the 16 binaural ones go into a
  third-order ambisonic field per bus, decoded to both ears once a block.
- **Debug overlay**: `AudioOverlay` writes text lines (voices real, virtual, binaural; underruns; each bus's meter;
  rendering listeners and the listener graph) for a HUD or a log; the demo prints it with `--args="--audio-overlay"`.
- **The collector**: run games with `-XX:+UseZGC`; under other collectors the queue starts 4 blocks deep.
- **Tests without a device** use SDL's `dummy` driver and the `disk` driver for an end-to-end check;
  `TRUESIM_AUDIO=true ./gradlew :engine-audio:test --tests '*AudioDeviceProbe*'` measures the real device.

### Audio benchmark

`TRUESIM_AUDIO=true ./gradlew :engine-audio:test --tests '*AudioBenchmark*'` plays each scene through the sync and
the renderer as a game does (60 Hz frames, the listener walking and turning; 48 kHz stereo, 256-frame blocks, so 5.33
ms a block), 30 s of warm-up, then 30 s timed per block (the audio thread's work) and per frame (`AudioSync.update`, on
the game thread), with the bytes the render allocates. The heavy scene is the design's (§2.23 of scene's
`docs/audio.md`): 1,000 placed loops with 64 real voices (16 binaural, the other HRTF ones in the ambisonic field),
two cameras heard from televisions (16 voices each), a V8 patch, 12 streamed loops (4 music stems, 8 ambiences), a
reverb aux, an equalizer and a compressor on the effects bus, the limiter. Target: under 1.5 ms a block. A timed
block slower than a block's 5.33 ms fails the run.

Measured 2026-11-03 on a Ryzen 9 9950X3D (Windows 11, JDK 27, the test JVM's default collector, G1):

| Scene | Block median | p99 | p99.9 | max | Sync median | Sync p99 | Real voices | Bytes a block |
|---|---|---|---|---|---|---|---|---|
| 64 voices of 1,000 placed loops | 0.223 ms | 0.466 ms | 0.534 ms | 0.63 ms | 0.039 ms | 0.072 ms | 64 | 0 |
| 16 HRTF voices | 0.280 ms | 0.397 ms | 0.471 ms | 0.52 ms | 0.001 ms | 0.002 ms | 16 | 0 |
| 64 HRTF voices: 16 binaural, 48 in the field | 0.492 ms | 0.755 ms | 0.888 ms | 1.05 ms | 0.003 ms | 0.006 ms | 64 | 0 |
| 2 rendering listeners, 16 voices each | 0.175 ms | 0.272 ms | 0.361 ms | 0.56 ms | 0.002 ms | 0.005 ms | 34 | 0 |
| 12 streams (4 stems, 8 ambiences) | 0.065 ms | 0.120 ms | 0.163 ms | 0.22 ms | 0.000 ms | 0.001 ms | 12 | 0 |
| V8 patch | 0.016 ms | 0.027 ms | 0.044 ms | 0.08 ms | 0.000 ms | 0.002 ms | 1 | 0 |
| **Heavy scene** | **0.580 ms** | **1.128 ms** | **1.338 ms** | 3.8 ms | 0.088 ms | 0.176 ms | 66 | 0 |

The heavy scene stays under 1.5 ms a block to the 99.9th percentile (10.9 % of the block at the median). Its slowest
block was 3.8 ms in this run and 1.7 and 1.8 ms in two more; the 3.8 ms block's cause was not looked for. The timed
30 s are now a later stretch of the listener's walk than before (66 real voices at most against 64), so the medians
are not comparable with the earlier 10 s warm-up runs. With 10 s of warm-up the slowest blocks were 17 to 27 ms: each was
the optimizing compile of a method called once a block (`AudioRenderer.renderBlock` after about 5,100 blocks,
`Voices.render`, `AudioRenderer.drain`, `Voices.choose`), which stops the calling thread because the test JVM compiles
synchronously (see `docs/pitfalls.md`); an app compiles in the background. Streams decode memory clips here (the
streaming thread's decoding is not in the block time).

## VR

`engine-xr` drives a headset through its OpenXR runtime (SteamVR, Meta's, Monado), behind the engine's own API: no
OpenXR type is part of it. A headset's two eyes are frame graph views with cameras of their own, rendered in the same
frame as the desktop windows, so an eye can also be shown on the desktop for people around the player.

- **Connecting:** `Headset.connect(name)` (`HeadsetException.Unavailable` without a runtime or a headset, to fall back
  to the desktop). The runtime names the Vulkan extensions it needs and the GPU it is connected to: the instance and
  device are made with them (`vulkanInstanceExtensions()`, `gpu(instance)`, `vulkanDeviceExtensions()`).
- **Eyes:** `headset.start(graph)` makes the session and the eyes' images (the runtime's recommended size times
  `XrSettings.RESOLUTION_SCALE`); `headset.present(eye, view, key)` shows a view's result in an eye and sizes the view
  to it. `headset.camera(eye)` is the eye's camera: the tracking space's `origin(x, y, z)` in the world plus the eye's
  pose, with the eye's own off-center field of view, updated for the time each frame shows.
- **Frames:** `headset.render(presenter)` waits for the headset's next frame (pacing the loop), renders the graph
  through the presenter, the windows in the same frame, and hands the eyes' images to the runtime. While the runtime
  does not show the app, the eyes' images are held: their views render only for their other outputs.
- **Mirror:** a view's result goes to several targets, so `presenter.present(leftEye, key, window)` shows the left eye
  on the desktop, scaled to the window; a third view with its own camera is a spectator view.
- **Input** (`headset.input()`), at the level of the platform's devices, for an input manager to combine with mouse,
  keyboard and gamepads: per hand, buttons (`XrButton`: trigger, squeeze, primary A/X, secondary B/Y, menu, thumbstick,
  trackpad, and a tracked hand's pinch and aim gesture), touches (`XrTouch`), axes (`XrAxis`), poses (`XrPose`: grip,
  aim, pinch, poke) in world positions, and vibration (`vibrate(hand, amplitude, frequency, duration)`); the head's
  pose; the eyes' gaze and the hands' 26 joints where the runtime offers them (`XR_EXT_eye_gaze_interaction`,
  `XR_EXT_hand_tracking`). `input.poll(listener)` reports button, touch and controller changes. The engine suggests
  where each input is on Meta Touch, Valve Index, HTC Vive, Windows Mixed Reality and simple controllers, and tracked
  hands (`XR_EXT_hand_interaction`), so the same names work on each. With `Input` installed the headset is a device
  of the engine's actions (`XrControls`: `xr:RIGHT_TRIGGER`, `xr:LEFT_THUMBSTICK_Y_AXIS`, `xr:RIGHT_AIM_POSE`,
  `xr:HEAD_POSE`), sampled after `beginFrame` with the other devices, rebound and saved like them.
  With `SceneInteraction` installed too, `Xr` adds the controllers' aim rays as scene pointers (`XrSceneRays`, a
  `ScenePointerSource`, binding the `scene.pointer` aim and select actions): engine-interaction knows no XR.
- **Settings:** `XrSettings` (resolution scale, the eyes' near plane, seated or room-scale tracking space).
- **Testing:** `HeadsetTest` needs a headset connected and starts the runtime: it runs with `TRUESIM_XR=true`.

```java
try (Headset headset = Headset.connect("true-sim")) {
    VulkanInstance instance = VulkanInstance.builder().extensions(headset.vulkanInstanceExtensions()).build();
    VulkanDevice device = VulkanDevice.create(headset.gpu(instance), headset.vulkanDeviceExtensions());
    headset.start(graph).origin(spawnX, spawnY, spawnZ);
    for (Eye eye : Eye.values()) {
        SceneView view = sceneViews.add(eye.name(), headset.camera(eye), options);
        headset.present(eye, view.port(), Keys.SCENE_COLOR);
    }
    while (!headset.exiting()) {
        platform.pollEvents(listener);
        headset.render(presenter);
    }
}
```

## Samples

The demo, the effects showcase, the stream and stereo benchmarks, the UI gallery and the UI and profiler benchmark
suites live in [true-sim/samples](https://github.com/true-sim/samples): programs built on the published engine
modules, everything loaded through the scene's asset manager and drawn through the scene. The engine's own CI runs
the engine's tests only.

## Building

Needs Java 27 and a Vulkan 1.3 driver (1.4 is used when present); the validation layers (Vulkan SDK) are
optional locally. Tests open hidden windows. Without a display, set `SDL_VIDEO_DRIVER=offscreen`.

CI does that on Mesa's lavapipe through build-logic's `setup-vulkan` action. The action pins and caches the Vulkan
packages and sets `TRUESIM_REQUIRE_VALIDATION=true`, so the Vulkan tests fail when the validation layer is not active.
On pull requests the longest modules' tests (engine-render, engine-scene, engine-ui) run in ten parallel shards,
balanced by the seconds each test class took on CI (`ci/test-timings.txt`, refreshed with `ci/timings.py` from a
run's reports): `./gradlew :engine-render:test -Ptruesim.shard=3/10 -Ptruesim.shardModules=engine-render` runs one
shard locally, `./gradlew testShards` with the same properties prints what it holds. The last job, `build`, checks
those modules' coverage from every shard's data together (`-Ptruesim.mergedCoverage=true`).

Exclusive fullscreen is only tested when `TRUESIM_TEST_FULLSCREEN` is set, because it changes the monitor's mode.

`VulkanInstance.bestGpu()` picks the most capable GPU (discrete first, then the most memory). `TRUESIM_GPU` (or the
system property `truesim.gpu`) chooses another, for the tests too: a type (`discrete`, `integrated`, `virtual`,
`cpu`), an index in `gpus()` or part of a GPU's name, e.g. `TRUESIM_GPU=integrated ./gradlew test`.

A lost device's `DeviceLostException` says what the driver saw, where the GPU has `VK_EXT_device_fault`: the kind of
fault, its address and the buffer that holds it. To have the validation layer check shader reads and writes as they
run, build the instance with `gpuAssistedValidation(true)` (much slower), or set `TRUESIM_GPU_ASSISTED_VALIDATION=true`
for every validated instance, the tests' included: `TRUESIM_GPU_ASSISTED_VALIDATION=true ./gradlew :engine-render:test
--tests '*ShadowsTest'`. GPU-assisted validation instruments the shaders and reports, as validation errors (so the
tests fail), reads and writes through buffer device addresses outside every buffer, pointer loads less aligned than
the SPIR-V says, and bindless indices out of range or unwritten, with the pass (debug region), the invocation and the
SPIR-V instruction; ordinary validation sees none of these, since the engine reaches its data through pointers. Its
other settings come from the layer's own (`VK_LAYER_SETTINGS_PATH`, vkconfig). `TRUESIM_SYNCHRONIZATION_VALIDATION=true`
turns on synchronization validation the same way. Run it on a GPU, on the tests of what you changed: on lavapipe the
instrumented shaders of several tests crash the JVM, so CI's `gpu-av` job runs only a few tests with it, and only
reports. Effects and plugins building on the engine's `TestGpu` get the switch too.

**GPU test fixtures** are published with the modules (Gradle `testFixtures`), for the tests of any repo building on the
engine (effects-*, plugins-*, samples) as for the engine's own:

- `engine-vulkan`: `TestGpu` (a headless, validated device; the test is skipped without a Vulkan 1.3 GPU, fails on
  validation errors, and with `TRUESIM_REQUIRE_VALIDATION=true` without the layer), `Readback` (RGBA8 pixels back on
  the CPU) and `ReferenceImages` (screenshots against PNGs kept with the tests, within a tolerance per channel and a
  share of pixels; differences written to `build/reference-diffs`, `TRUESIM_UPDATE_REFERENCES=true` writes the
  references).
- `engine-graph`: `TestTarget`, a frame graph output read back after each frame.
- `engine-render`: `TestRenderer` (pool, scene, meshlet renderer, render view, graph and target set up as the engine's
  tests do) and `TestMeshes` (quad, box, walls, rock, terrain, built in code).

```kotlin
testImplementation(testFixtures("com.truesim.engine:engine-render:<version>"))
```

Mistakes made here once, each with its evidence and the rule that follows, are in [docs/pitfalls.md](docs/pitfalls.md);
add to it what is verified, nothing else.

```bash
./gradlew build
```

Sibling checkouts (`../build-logic`, `../common-math`, `../common-state`, `../common-jobs`, `../common-graph`,
`../scene`, `../ui`) are used automatically when
present (override with `-Ptruesim.<repo>=<path>`); otherwise they come from GitHub Packages.
