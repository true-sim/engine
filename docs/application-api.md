# Application API: design decisions (draft)

The public layer a regular user writes a game or application against. Decided step by step in conversation on
2026-11-01, then reviewed (consistency, performance, feasibility against the code) and revised. Numbers are stable
(D1–D137); a revised decision keeps its number and says what changed. Nothing here is built yet unless marked
**exists**.

**Names** (D117): a *plugin* is an engine part installed once at setup; a *game system* is game logic added and removed
at runtime; plugins start in *stages*, game logic runs in *phases*; an *importer* turns a source format into engine
data. (The scene repo's `scene.world.Feature` is a world content variant and keeps its name.)

## 1. Application setup

**Built since:** `engine-app` (D1, D2, D31–D33, D35, D118, D125–D127): `Application`, `AppConfig`, `Plugin`,
`PluginKey`, `Stage`, `Startup`, `StartupFailure`, `FrameDriver`, `Frame`, `Engine`, `TrueSim`, `SettingsFile` (D7).
Plugins: `Desktop` (engine-platform), `Rendering` (engine-graph: instance, device, graph, presenter as the default
frame driver), `Xr` (engine-xr: `Xr.ifAvailable()` / `Xr.required()`, drives frames with `Headset.beginFrame` and
`submit`); the demo runs on them. `Assets` (engine-scene: cache, path assets, GPU resources); glTF import is
scene's `scene-gltf` module, models are `RenderAssets.MODEL` (D17), sub-assets path fragments (`#mesh/0`). Not yet: `fixedUpdate` (with the clocks, D88–D92), BaseGame, the native error box.

```java
public final class RockGarden extends BaseGame {
    public static void main(String[] args) { run(new RockGarden(), args); }
    @Override protected void configureGame(AppConfig app) { app.use(Xr.ifAvailable()); }
    @Override protected void setup() { world().spawn(assets().model("game://models/boulder.glb")).move(0, 0, -5); }
    @Override protected void update(Frame frame) { ... }
}
// embedding, tools, tests: try (Engine e = Engine.create(config)) { while (e.running()) e.step(); }
```

- **D1** The engine owns the loop (`TrueSim.run`, which `BaseGame.run` calls); `Engine.create` + `step()` is the escape
  hatch for tools, tests and embedding. A plugin may take the *frame driver* slot (D118), e.g. XR pacing frames.
- **D2** Lifecycle interface `Application`: `configure(AppConfig)`, `start(Engine)`, `update(Frame)`,
  `fixedUpdate(Step)`, `stop()`, all with defaults.
- **D3** (revised) If the player can change it, it is a setting. `AppConfig` holds what the player cannot change: id,
  name, version, which XR modes the game supports (none, optional, required), required GPU features. The tick and fixed
  rates are settings of the developer tier whose defaults the game sets. Whether an optional headset is used is the
  player's setting `xr.enabled`.
- **D5** Game code runs on one main thread; parallelism comes through game systems (D95).
- **D28** No static globals; objects are passed.
- **D31 / D41** The bare application installs nothing. Its core is infrastructure only: lifecycle, loop and time, jobs,
  the settings store, installing plugins. Assets and worlds are plugins too.
- **D32** (renamed) Optional parts are plugins with typed keys: `PluginKey<T>` constants in the providing module;
  `engine.get(key)` for installed ones, `engine.find(key)` (`Optional`) for optional ones. Not on the classpath: does not
  compile; not installed: error naming `app.use(...)`.
- **D33** (revised by D118) Plugins declare `requires(...)` and take part in startup stages.
- **D34** A plugin brings its whole slice: settings and groups (registered explicitly, D8), importers, render
  techniques, game systems.
- **D35** Explicit `app.use(...)`, no classpath scanning; bundles (`Desktop.defaults()`) keep it short.
- **D44** Separate modules: `engine-app` (bare), `engine-basegame`; settings in their own module (D121).
- **D49** Three layers, each a subclass of the one above: `Application` → `BaseGame` (what a release needs: window,
  rendering, input, a world and camera, settings file, assets) → `PrototypeGame` (adds development tools only: hot
  reload, fly camera, F1 overlay, console).
- **D42** `BaseGame`/`PrototypeGame` use only the public API; their source doubles as the wiring reference.
- **D43** (revised) `BaseGame.configure` and `start` are `final`: `configure` installs the preset, then calls
  `configureGame(AppConfig)`; `start` builds the default world and view, then calls `setup()`. Forgetting `super` is
  impossible. Hooks: `configureGame`, `setup`, `update`, `fixedUpdate`, `worldType`.
- **D51** Both are thin wrappers around presets (`Presets.baseGame()`, `Presets.prototyping()`); dev tools are an
  ordinary plugin (`Dev.tools()`) that `PrototypeGame` installs.
- **D115** Plugins ≠ game systems. A plugin can bring game systems.
- **D118** (new) Startup stages, in order: `BEFORE_INSTANCE` (connect a headset), `INSTANCE` (instance extensions),
  `GPU` (filter or pick the GPU), `DEVICE` (device extensions, features), `GRAPH` (after the frame graph exists),
  `RUN`. One plugin may claim the frame driver (wait, render, submit, pacing); the default is the presenter.
- **D119** (built) `Headset.render` splits into `beginFrame` (wait, sync actions, poses) and `submit`, so XR input is read
  at frame start (D59) and the XR plugin can drive frames.
- **D120** (new) BaseGame's shortcuts (`world()`, `assets()`, `input()`, `camera()`) are sugar for `get(KEY)` calls, so
  code moves between layers unchanged; `world()` is typed by `worldType()`; calling them before `setup` throws a named
  error.

## 2. Settings

**Exists:** `Setting` (bool, integer, decimal, text, choice; `restartNeeded`), `EngineSettings` (get/set/reset,
listeners, scoped views, `toMap`/`load`), declared as static constants in the modules using them; in `engine-platform`.
**Built since:** the module of its own (D121), layers and constraints (D6, D14, D15: `Layer`, `preferred`,
`Constraint`, `commandLine`), the registry with groups and tiers (D8–D12: `SettingRegistry`, `SettingGroup`,
`SettingTier`; plugins call it once plugins exist), availability (D13: `Availability`, `availableWhen`,
`dependsOn`, `lock`, `applied`, `refreshAvailability`). Still to come with the app layer: `app.lock`/`app.limit` as
sugar, settings files per app id (D7), plugins registering their lists (D8).

- **D6** (revised) The store has four explicit layers, lowest first: engine defaults, game defaults
  (`app.defaults(...)`), the player's values, the command line (`--set name=value`). Only the player layer is saved.
- **D7** One `.properties` file per app id in the OS user folder; saved on exit and when a menu applies.
- **D8** (revised) Plugins register their settings explicitly (a list per plugin), not through class loading. The
  registry knows scopes, so duplicate full names (with scopes) fail at startup. Game systems read settings but never
  declare them.
- **D9** UI groups (`SettingGroup`, a tree with subgroups) are separate from the saved name; every player-visible
  setting must have a group (startup check).
- **D10** (decided) Tiers are optional: a setting has none unless given one, and nothing is filtered by tier by
  default. The game configures them: which tiers exist (`BASIC`, `ADVANCED`, `DEVELOPER` are offered), which a query
  shows, and which stay hidden unless a debug flag or launch option turns them on.
- **D11** Groups are typed constants with label and order; labels are translation keys.
- **D12** Per-window settings appear as one group per window (from scoped views).
- **D13** `Availability` per setting: `AVAILABLE`, `UNSUPPORTED`, `DEPENDS_ON`, `LOCKED`, `RESTART_PENDING`, with a
  reason; rules registered by the owning plugin, re-evaluated on device or display change; listeners run at the frame
  boundary.
- **D14** (revised) A constraint overlay per setting computes the effective value: `get()` returns it, `preferred()`
  returns the player layer. A value the hardware rejects no longer reads silently as the default.
- **D15** Choices and ranges can be narrowed (`allowedChoices`, `allowedRange`) by hardware or by the game
  (`app.lock`, `app.limit`); `enabledWhen(...)` declares dependencies. The view → graph fallback in `ViewPort.resolve`
  and `Switch` is inheritance between scopes, not a layer; it stays by hand until scoped views get a parent.
- **D121** (new) Settings move out of `engine-platform` into their own module with no SDL or LWJGL dependency, usable by
  servers and by the scene repo.

## 3. Worlds and nodes

- **D36** World types are plugins: `WorldType<W>` keys, `worlds.create(type, config)` returns the concrete type.
  `SimpleWorld` comes with the worlds plugin.
- **D37** (corrected) One fixed node model in every world type; world types differ in how content gets in (streaming,
  procedural, replicated), not in what a node is. Positions are `double`, so no origin shifting is needed.
- **D38** Several worlds at once; a view shows one world through one camera. Each world gets its own `GpuScene` and
  `SceneSync` over shared `SceneResources` (supported today); layers stay for game use.
- **D39** Each world has its own clock.
- **D40** Assets are shared across worlds and counted once per user.
- **D45** (refined) One `final` `Node` type: one object per live scene-graph slot, kept in an array and reused, holding
  the handle with its generation; queries return existing objects, never new ones; a stale `Node` throws. Kinds of
  objects come from components; prefabs are model assets.
- **D46** (refined) Games and plugins may add component types (typed keys with stable names, so they can be saved).
  Components live in per-type stores (sparse sets) in scene-core, not in per-node maps. Engine systems ignore unknown
  types. Camera and light components, with their sync to the engine's `Camera` and `Lights`, are part of this work.

## 4. Assets

**Exists (scene repo):** `AssetCache` (manual acquire/release, LRU budget; one loader per type, a source per cache or per
type), `AssetType`, `AssetRef`/`AssetId` (128-bit; documented as never depending on paths), `AssetLoader` (sees id, part
and resources, no path), `AssetSource` (directory, gzip, `firstOf`), async on job lanes, delivered at
`drainCompleted()`. **No file-format importer exists.** Engine: `SceneResources`, `SceneSync`.

- **D16** (revised) Paths are the identity of path-addressed assets: `mount://folder/file.ext` (the double slash avoids
  clashing with drive letters). The id is a hash of the normalised path (separators, case rules, `..` resolved), so
  `AssetId`'s contract changes to "stable while the path is stable" and its documentation is rewritten. A thread-safe
  path index maps ids back to mounts and paths, for sources and background importers. A mount may list redirects (old
  path → new path) so renames don't break saved references. Generated `GameAssets` constants are optional
  compile-time-checked paths.
- **D17** (revised) A `MODEL` asset type: a node tree plus the references to meshes, materials and textures it uses.
- **D18** (revised) Ownership: a spawned node holds its model (and through it the model's references); `Renderables`
  keep holding meshes and materials as today. `Asset<T>` handles (closeable) hold without placing.
- **D19** (revised) Nothing blocks in the engine; callbacks on the main thread at frame start, drained within a time
  budget so a burst of finished loads spreads over frames. Loading screens poll a `LoadProgress` (renamed: `Loading`
  is `SceneResources`' mode). Tools may `await`.
- **D20** Streaming by default (coarsest detail first); `Loading.WHOLE` on request. *(Whether parts stream coarsest
  first today is not verified.)*
- **D21** Failures show a marked placeholder and go to diagnostics; `LoadProgress.failures()`.
- **D23** A mount is an `AssetSource`; the path layer on top is new.
- **D24** Several locations per mount name, in priority order (mods, patches, DLC override by path).
- **D25** Folder mounts reload on file change (development). Needs `AssetCache.reload(ref)` with a `replaced` listener
  event and a re-upload in `SceneResources` reusing `SceneSync`'s mesh swap. Large; built late.
- **D26** Only the game adds network mounts; importers treat mod files as untrusted.
- **D29** The asset API, path layer and importers live in the scene repo; the asset plugin (key, settings, wiring) in the
  engine.
- **D30** Sub-accessors: `assets.mounts()`, `assets.importers()`.
- **D47** (revised) Importers are registered by extension and asset type. Underneath, the path layer registers one
  dispatching `AssetLoader` per type and gives it the path (`AssetInput.path()` is added).
- **D48** Nothing registered by default anywhere.
- **D22** (decided) Runtime import only for now: importers run at load, from source folders. A cooker that writes
  engine data into packs is deferred until load times show a need. Committed now: an importer's output is data the
  engine can save, so a cooker can be added later without changing importers.
- **D50** Cooking keeps paths.
- **D52** Source folder vs pack is a choice of mount, invisible to game code.
- **D53** (deferred) No asset mode for now; a cooked mode comes with the cooker.
- **D54** If a cooker is added, whether it becomes the default is decided by measurement (load time, first frame,
  memory, size).

## 5. Input

**Exists:** platform events for keyboards, mice, gamepads, joysticks, haptics, touch, pens; `KeyModifiers` bits;
several devices, each with an id valid while connected, vendor and product (no GUID exposed; serial often empty); XR
input through `Headset.input()`, one fixed OpenXR action set synced inside `Headset.render`.

- **D55** Input is a plugin; device kinds come with plugins (XR controllers with `Xr`).
- **D56** (decided) Devices can be read directly; actions are what is rebound, saved and shown. Direct reading is
  offered in `BaseGame` too (and so in `PrototypeGame`), for debug keys and quick hacks.
- **D57** Actions are typed constants in groups. **D67** Groups form a tree; a group is active only if it and all its
  parents are enabled. **D68** Each keeps its own switch.
- **D58** Bindings are settings, saved like them.
- **D59** Input is sampled once per frame, at its start (XR too, D119).
- **D60** (revised) Edges are counted per world clock, per player and per action; a fixed-step reader sees the edges
  since its last step. Pausing a world clears its counters, so resuming never fires a stale press.
- **D61** Contexts form a stack with priority; they can capture or release the mouse; text fields take keys.
- **D62** Glyphs follow the last device used.
- **D63** Players and local multiplayer from the start; single player gets every device.
- **D64** (revised) OpenXR actions are the device layer: generic controller inputs, attached once per session as
  today. Engine actions and bindings run on top, so XR bindings can be rebound, saved and filtered like any other.
- **D65** Nothing allocated per frame.
- **D66** Rumble per player.
- **D69** The most specific binding wins (chords suppress their parts). **D70** (decided) A press fires at once by
  default. Only a binding marked tap-and-hold waits: the tap fires on release before the hold time (about 0.25 s), the
  hold once it passes. **D71** Modifiers left, right or either; any pad button as a
  modifier.
- **D72** (refined) Game code binds only generic controls; a specific device is recorded only when the player captures
  a binding. The platform layer exposes SDL's GUID; devices match by GUID, then serial, then path. Keyboards and mice
  are not told apart.
- **D73** Unplugging is reported; replugging returns the device to the same player (by D72's matching).
- **D75** Action types by value: `BinaryAction`, `Axis1Action`, `Axis2Action`, `Axis3Action`, `PoseAction`.
  **D76** Binary has no analog value. **D77** Analog to binary switches at a threshold with hysteresis (0.6/0.4).
- **D79** Toggle or hold is the binding's choice. **D80** An action can be another's source; cycles rejected.
- **D81** Adapters are saved with the binding.
- **D82** A binding holds a chain of filters. **D83** Binding filters are saved by typed id with parameters and
  adjustable. **D84** Action filters belong to the game, may read game state, are not saved. **D85** (refined) Filters
  keep state per player and binding and allocate nothing; binding filters run per frame on real time (device feel);
  action filters read by fixed-step systems run per step on the step's delta. **D86** Focus loss, context switch and
  disconnects reset them. **D87** Several bindings combine by the largest value (per-action rule selectable).
- XR head and hand poses are read late in the frame and are never interpolated (D91).

## 6. Game logic and time

- **D88** Clocks: real time, app clock, one clock per world.
- **D89** (decided) Fixed steps per world, each world's clock with its own rate, 60/s by default; the shared budget
  (D92) caps the total. Worlds step out of phase, so reading another world gives its latest state.
- **D90** Phases: `FIXED`, `UPDATE`, `LATE`, then scene update and rendering.
- **D91** (revised) Interpolation between fixed steps runs on the GPU: when a step runs, `SceneSync` uploads the
  previous and current world transforms of instances moved in `FIXED`; each frame passes one blend factor. Frames
  without a step cost no CPU, and the same data gives motion vectors. A teleport or spawn sets previous = current.
  The scene graph keeps simulated values only. **Measured first** (D122). On by default for nodes moved in `FIXED`;
  a node can opt out.
- **D92** (revised) Frame delta capped (0.25 s); one budget of fixed steps per frame shared by all worlds, in a fixed
  world order; when it is hit, the remaining accumulated time is discarded. Accumulation is in whole ticks, not
  floating-point seconds. Caps are reported.
- **D93** Three ways to write logic, all inside the same phases: the game class, behaviours, game systems.
- **D96** (scoped) A fixed step is deterministic on the same build and machine class, given the same inputs per step
  and the same order. Transcendental math in `FIXED` uses `StrictMath`; replays record input per step; no hash
  iteration order reaches game logic.
- **D97** (refined) Timers on a clock (`after`, `every`); timers read by `FIXED` count steps. Creating may allocate,
  running does not.
- **D98** A server runs fixed steps at the tick rate, without rendering; behaviours with `fixedUpdate` run there too
  (D113).
- **D99** No coroutines or threads in game logic for now.
- **D122** (new) Before building D91: measure the GPU cost (memory per moving instance, about 80–160 B; upload per
  step; shader cost) against the CPU alternative on the scene benchmark with 30,000 moving rocks, bytes allocated
  included.
- **D123** (new) World transforms are propagated after every fixed step (transforms only; change logging once per
  frame), so step 2 never reads stale parent transforms and a catch-up frame does not overflow the change log.
- **D124** (new) Frame start order: drain finished loads (time-budgeted) → sample input → `FIXED` → `UPDATE` → `LATE`
  → scene update and GPU sync → render.

### Behaviours

- **D94** Behaviours are components run by built-in game systems.
- **D100** (refined) Active is a state: passive unless `wake()`, `sleep()` returns to passive; changes made during an
  update are buffered and take effect next tick. Classes without `update`/`fixedUpdate` never enter an update list.
- **D101** Passive behaviours react to events (interaction, triggers, timers, added/removed, typed game events).
- **D102** Interfaces mark kinds (`Interactable`); the world keeps a list per type (`forEach`, `nearest`).
- **D103** (revised) The update list is grouped by class for memory locality and a fixed order. One loop call site
  stays megamorphic regardless; per-class generated loops are added only if D106 shows dispatch matters.
- **D104** Update rates per behaviour (`updateRate`, `wakeAt` on a timing wheel), on the world clock.
- **D105** Behaviours run on the main thread.
- **D106** (refined) Benchmark first, with a defined body (read pose, rotate, write): 10,000 awake behaviours under
  0.5 ms with 0 bytes allocated, sleeping ones unmeasurable; a second number including scene update and GPU sync.
- **D113** (revised) Behaviours run in two built-in game systems: one in `FIXED` (`fixedUpdate`), one in `UPDATE`,
  each alone on the main thread. Behaviours write immediately; deferral (D116) is for parallel game systems only.

### Game systems

**Exists:** common-graph `NodeGraph`/`Schedule` (dependencies, culling, up to 64 switches), used by the frame graph.
Order is the order added; nodes without side effects whose writes nobody reads are culled; the schedule stores
predecessors only. `JobSystem` runs one `parallelFor` at a time, and one called from inside a job runs inline.
**Missing:** `GameSystem`, `SystemBuilder`, `TickContext`, a dependency-driven executor.

- **D95** Game systems declare reads and writes; the schedule is deterministic; systems without conflicts run in
  parallel.
- **D107** (revised) Built on `NodeGraph`, with additions: a topological sort by declarations (stable by add order), so
  add order never decides who reads what; a read that resolves to the previous tick must be declared
  (`readsPrevious(key)`); game systems count as having side effects, so none is culled silently; dependents and
  in-degrees stored at compile.
- **D108** Typed `StateKey<T>`; input actions are resources; one writer per state value (deferred node writes, D116,
  are the exception).
- **D109** (revised) A new executor: per tick, an `int[]` of remaining dependencies reset by copy, a lock-free ready
  queue, work-stealing workers; a system's own `parallelFor` runs its chunks on the same pool, never inline. Switched-off
  systems count as done at once. Nothing allocated per tick.
- **D110** Declarations by component type and state key.
- **D111** (refined) Enable/disable through switches, no rebuild; the switch set grows past 64 (a `long[]` mask), and
  the action-group tree is flattened into it.
- **D112** Undeclared access throws in debug builds, checked once when a system acquires a view, behind a constant flag
  the JIT removes.
- **D114** Timing per system; printable schedule.
- **D116** (revised) Deferred node writes are their own kind of access: deferring writers don't conflict with each
  other, readers see the start-of-phase state, writes go into per-system growable buffers and are applied at the end of
  the phase in schedule order (not completion order); debug builds report two systems writing the same node.
  (`SceneBatch`, which stages new nodes, is not this mechanism.) Node sets later, only if measured.

## 7. Lifecycle and views

- **D125** (new) Startup failures: a stage that cannot start throws a `StartupFailure` (stage, plugin, reason, whether
  the plugin is required). The engine calls `onStartupFailure(failure)`, which returns `RETRY`, `CONTINUE_WITHOUT` (an
  optional plugin is dropped, e.g. no headset: play on the desktop) or `QUIT`. The default shows a native message box
  and quits. Plugins declare themselves optional; code using an optional plugin reads it with `find`, never `get`.
- **D126** (new) Quitting: the close button, Alt+F4, the OS and `app.quit()` all become a quit request.
  `onQuitRequested(reason)` returns `ALLOW` or `DEFER`; after deferring (a "save before quitting?" dialog) the game
  calls `app.quit()`. A forced quit (OS shutdown, a second request within a few seconds) skips the veto. Shutdown runs
  in reverse startup order and saves the player's settings (D7).
- **D127** (new) Focus, visibility and suspend are app events: `onFocusChanged`, `onVisibilityChanged`, `onSuspend`,
  `onResume` (headset idle counts as hidden). Nothing pauses by itself; a hidden or minimised app throttles rendering.
- **D128** (new) Pausing is per world: `world.clock().pause()` stops its `FIXED` steps and timers and clears its input
  edges (D60); `UPDATE`, UI and other worlds keep running. `BaseGame.pauseOnFocusLoss(true)` opts in to pausing the
  game's worlds on focus loss and suspend.
- **D129** (new) A camera is a component on a node (D46): it moves and animates with the scene graph. In XR the tracked
  head is the camera node.
- **D130** (new) A `View` is one world seen through one camera node into one target, at a rectangle of it:
  `app.views().add(world, cameraNode, target, rect)`. Targets: a window, a headset (both eyes), a texture (a material
  samples it: minimaps, security cameras, mirrors). Each view has its own settings scope (`view.<name>`, as the frame
  graph's views already have). Split screen is several views in one window.
- **D131** (new) A view may belong to a local player (`view.player(p)`, D63): that player's glyphs and UI draw in it,
  their camera input goes to it, and the engine lays out the rectangles as players join and leave (one full; two side
  by side or stacked, a setting; three or four in quadrants) unless the game sets them. Audio listens from every
  player's camera.
- **D132** (new) `BaseGame` makes the main window and one view of its world through a default camera node; more windows
  through `app.windows()`. A window whose display is unplugged moves to the primary display; its views follow it.

## 8. Model import and rendering features

- **D133** (new) glTF import leaves nothing out: every extension becomes engine data (material extensions as features,
  lights and visibility on models, texture transforms, quantized and compressed data); points and lines are an asset of
  their own (`LineData`). The renderer then takes features up one PR each. A file requiring an extension not read
  fails, as glTF asks.
- **D134** (new) Decoders: meshopt through LWJGL's meshoptimizer, KTX2/Basis through LWJGL's libktx, WebP through the
  TwelveMonkeys ImageIO plugin; Draco through a small native binding, later.
- **D135** (new) Material features stay modules with a bit each (as clearcoat); the visibility buffer makes a pipeline
  only for each combination of type and features materials use (a shading bin, numbered as it first appears; the
  feature mask a specialization constant of one build-time SPIR-V), not for every combination. No shader compiler at
  runtime. The slot holds a 32-bit feature mask and an offset to a table of the material's feature sections.
- **D136** (new) Order: the bins (D135); texture transforms and the second UV set; unlit; alpha blending (a sorted
  transparent pass); specular, IOR, sheen; transmission and volume (the scene behind); iridescence, anisotropy,
  dispersion, diffuse transmission. Lights from models come with the world plugin.
- **D136a** (built) Shading bins (D135) and texture transforms with the second UV set: a texture reference carries
  its UV set (bit 20) and transform (bits 21-24) above its sampler; the transforms are a feature every type allows
  (`TextureTransformParameters`, up to eight per material); glTF's per-texture `TexCoord`, `Offset`, `Rotation`,
  `Scale` become them. Alpha-mask textures follow their UV set, not a transform (the slot has no room).
- **D136b** (built, revised after a review) Unlit (a feature every type allows) and alpha blending. Blended materials
  (`Material.alphaBlend`, glTF's `BLEND`) are a fourth raster bin from the same culling, never drawn by the opaque,
  depth or ID passes; its lists are appended to across both occlusion phases (one counter) and drawn once, after the
  late cull. How they are drawn is a frame graph technique, one per view under `Transparency.ID`, modifying
  `SCENE_COLOR` at `Anchor.TRANSPARENT`, swapped with `port.replace` (which now also replaces by id):
  `WeightedBlendedTransparency` (weighted blended order-independent transparency, resolved in place; the default a
  preset adds) or `OpaqueTransparency` (as opaque, color and depth); sorted meshlets or per-pixel lists would be more of
  them. A view without one draws no blended surfaces. Providers draw the bin per `RenderView` (`drawBlended`); classic
  LOD's selection is a shared request. Blended surfaces cast no shadows yet (a shadow decision, not transparency's).
- **D136c** (built) Specular, IOR and sheen as features (modules `materials/specular.slang`, `ior.slang`,
  `sheen.slang`, field names glTF's own so imported data converts as it is): the surface carries a dielectric's
  reflectance head-on (`f0`, from the IOR, scaled and tinted by specular) and at grazing angles (`f90`), and a sheen
  (Charlie distribution, Neubelt visibility, the base dimmed by an average sheen albedo). Metallic-roughness allows them;
  a game's type opts in as for clearcoat.
- **D136d** (built) Transmission and volume as features (`materials/transmission.slang`, `volume.slang`, glTF's
  field names; volume's `attenuationDistance` 0 stands for glTF's infinity). Transmissive materials are drawn with the
  blended ones, by the view's `Transparency` technique (chosen over a bin and technique of their own: one place to pick
  quality, no new cull path): it requests `SceneBehind`, a copy of the opaque scene's color with averaging mips, which
  shading samples where the ray refracted through the volume leaves it, a coarser mip for rougher glass, attenuated by
  Beer's law and tinted by the albedo; the diffuse part gives way to it. Glass behind glass, and blended surfaces behind
  glass, are not seen through it (the copy holds the opaque scene); the volume's thickness is in the mesh's units,
  scaled by the instance's largest scale.
- **D136e** (built) Anisotropy, iridescence, dispersion and diffuse transmission as features (`materials/anisotropy.slang`,
  `iridescence.slang`, `dispersion.slang`, `diffuseTransmission.slang`; glTF's field names, the importer's
  `diffuse_transmission` taken too), as the glTF sample viewer: an anisotropic GGX lobe along the tangent frame turned
  by the rotation (any frame without tangents); a thin film's reflectance (Belcour and Barla) evaluated once per point
  and mixed into the Fresnel; each color channel refracted by its own IOR through a volume; and light through thin
  surfaces in the transmission color, from lights behind and the sky. Checked against the Khronos models; the specular
  ones (anisotropy, iridescence) wait for environment lighting to compare fully.
- **D138** (built) Environment lighting, as a high-effort review recommended, decided with the user (HDR images first,
  filtered on the GPU at runtime, later real-time GI must fit): shading reads one per-view **indirect-light table**
  (`Keys.INDIRECT_LIGHT`, indirect.slang's `IndirectLight`) through one push pointer, its sections filled by their
  providers and their images gathered by the shared `IndirectInputs` request, as `LIGHT_LIST` and `ShadowInputs` work.
  `EnvironmentLighting` provides it and fills the distant environment's section; probe volumes, reflection probes and
  screen-space techniques will modify it with sections of their own, shading combining screen space, probes and the
  environment in one place (screen-space reflections near, the environment far), without new variants. `Environment`
  is an app object (a panorama or a color; intensity and rotation applied at lookup, so changing them costs nothing);
  `EnvironmentMaps` filters it into graph-persistent cubes (radiance with a mip chain, GGX levels by roughness, Charlie
  for sheen) and spherical harmonics, again only when its version changes or its images are new (`PassContext.fresh`),
  so views and eyes share one filtering; `EnvironmentMaps.BrdfTable` is made once per graph. `SkyBackground` shows an
  environment behind a view at `Anchor.OPAQUE`, chosen apart from the lighting (AR passthrough shows none, a product
  viewer a plain color). The graph's persistent images gained cube faces and mip chains with a slot per level, and the
  bindless set cube and 2D-array views. HDR panoramas come from scene's `RadianceImporter` as half-float `TextureData`.
- **D137** (new) Checked against the Khronos glTF sample models: an optional sweep (`-PgltfSamples=...`) imports every
  model with all its parts.

## Open questions

- Not designed yet: saving game state (stable names on component and state keys, D46/D108); localisation (a locale
  setting and string tables behind D11's keys).

## Review notes (2026-11-01)

Three reviews (consistency and API design, performance and determinism, feasibility against the code). Code claims
checked: `scene.world.Feature` exists (name clash); `AssetId` documented as never depending on paths; one loader per
asset type; `InputDevice` without GUID; double positions without origin shifting; `xrSyncActions` inside
`Headset.render`; `NodeGraph` orders by add order, culls side-effect-free nodes, 64 switches; nested `parallelFor`
runs inline; `SceneSync` rewrites all instances above half changed; `SceneBatch` only stages new nodes. Rough build
order from the feasibility review: settings module (M) → `engine-app` with stages (M) → Headset split and demo startup
as plugins (M) → asset path layer (M) → glTF/PNG importers and `MODEL` (L) → node components with camera and light (L)
→ worlds plugin (M) → input (L, filters and chords M) → game system executor (M) and behaviours (M) → base classes (S),
hot reload (L).
