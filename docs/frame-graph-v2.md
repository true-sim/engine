# Frame graph v2: design proposal

Date: 2026-10-30. Status: accepted. Revised after three independent reviews:
1. against the code (accuracy, feasibility);
2. for future-proofing, flexibility, safety and performance;
3. for extensibility from outside: which features should follow the shadow pattern (§4.18).


Covers engine issues #87–#96 and the remainder of #101 (transfer-queue streaming). It also defines a generic graph
core (`common-graph`), shared with a CPU module graph for game logic (§4.16, §4.17).

## 1. Why a v2

v2 keeps the core of v1:
- techniques provide and modify keys;
- requests are deduplicated;
- results have one owner;
- barriers are derived from declared accesses;
- async compute is batched;
- recording allocates nothing.

What v1 cannot do (review 2026-10-29, §4.3, checked again in the code):

- **One camera per graph.** Keys are global. Culling, cascaded shadow maps (CSM), clustered lights and the top-level
  acceleration structure (TLAS) are all fitted to one camera.
- **No render-to-texture.** A rendered image cannot be sampled by materials or UI.
- **Size changes rebuild.** A rebuild with histories stalls (`waitIdle`, `submitAndWait`: `FrameGraph.java:1086`,
  `:1298`). History images survive rebuilds by name, but their contents are marked invalid.
- **Missing image kinds and per-mip barriers.** There are no arrays, cube maps, 3D images or MSAA. Barriers always cover
  all mips and one layer, so `DepthPyramid` writes its barriers by hand.
- **No toggles.** Turning an effect on or off means a rebuild.
- **No dependency ordering.** Passes run in the order they are added, with no sort. The renderer's buffers are hidden
  from the graph and ordered by token buffers and `ALL_COMMANDS` barriers instead.
- **No transfer-queue streaming.**
- **Vulkan in pass bodies.** They get `VkCommandBuffer` and raw handles.

## 2. What it allows

| Scenario | v2 |
|---|---|
| Split screen, editor viewports, VR | One graph, many views. A view can be stereo (multiview, two eyes in one pass). Views can be enabled, disabled or rendered at a lower rate per frame, without a rebuild. |
| Mirror, portal, security camera, minimap, 3D UI | A view renders into a `Texture` with a stable bindless slot, sampled by any material in the same frame. Per-view technique sets: a mirror without bloom, an unlit minimap. |
| Window resize, dynamic resolution | Sizes come from the view. The render region changes per frame inside an allocation with headroom. |
| SSAO, bloom, shadows on and off at runtime | Switches on producers (with a fallback) and on in-place effects, per view, per frame. No rebuild and no variant compilation. |
| Mip chains, CSM arrays, cube shadows, 3D volumes, IBL, MSAA | Image descriptors with mips, layers and type. Barriers per subresource. |
| Shadows per light: give spotlight 10 variance shadows, light 11 PCF | Shadows are per-light data. A fixed set of methods with fixed passes, allocated per frame by importance and budget. No rebuild. Plugins add their own shadows through the shadow-mask path. |
| Virtual shadow maps, virtual-texture feedback, DDGI or surfels fed by every view | Passes can move work between view and frame scopes: a frame pass reads every view's output, and views read frame results. All of it is ordered by one dependency sort. |
| GPU skinning, particles, simulation | Scene data are keys. Writers are modifier stages. Culling and the TLAS read the final version. |
| Upscalers, frame generation, HDR displays | A `Presenter` stage owns the swapchain, HDR metadata and pacing. Standard keys carry linear colour, motion, depth and the image before UI. |
| Level streaming | A `StreamQueue` on the transfer queue, published to frames when ready. |
| Custom passes | Attachments and load/store declared on the pass, with the graph opening the rendering scope. Typed `PassContext`, generated push writers, raw Vulkan only behind `unsafe()`. |
| Pipelines and rebuilds | A device pipeline cache on disk. Rebuilds without stalls, later in the background. |
| Tests | The compiler runs without a device. |
| Game modules (jME AppStates, done properly) | The same core drives a CPU module graph. Modules declare dependencies and state accesses, run in parallel where they don't conflict, unused ones are culled, and they toggle without a rebuild. |

## 3. What stays

- `Technique`, `Request` deduplication, `Implementation` swapping.
- `FrameGraph.add/remove/replace/implement`.
- Single-owner results and modifier chains. v2 extends them to imported resources (§4.9).
- Declared accesses with derived, batched barriers.
- Async compute with timeline semaphores.
- Allocation-free, parallel recording.
- History images kept by name across rebuilds. v2 also keeps their contents.
- Introspection.
- **v1's steady-state rule:** a frame starts in the state the same compiled graph ends in. v2 keeps it and adds seams
  between different graphs (§4.1).

## 4. The changes

### 4.1 Frame boundaries: home states and seams

**Steady state.** A compiled graph that runs frame after frame starts each frame in its own end state, as v1 does, with
no extra transitions.

**Exported end states.** Each `CompiledGraph` exports a static table of the end state of every cross-frame resource:
layout, queue-family owner and access, per subresource.

**Seams.** When frame N+1 is recorded by a different graph (after a rebuild, or a background swap), the executor emits a
one-off **seam** barrier set. It is computed from graph N's end table to graph N+1's expected start states, and recorded
at the start of N+1. Seams cost a few transitions once, never per frame.

**Home states, for imported and external resources only.** Each resource gets a per-resource home derived from its
usage, not one global layout:
- a sampled texture: `SHADER_READ_ONLY`;
- a storage image: `GENERAL`;
- depth that is sampled across frames: `DEPTH_READ_ONLY`;
- a swapchain image: `PRESENT`, excluded from everything else.

A home state names a queue-family owner. Concurrent sharing is allowed only for buffers and storage-only images, so
compression is kept.

**External writers.** Code outside the graph that writes a graph-visible resource registers as an external writer:
- `Textures`, the `StreamQueue`, `unsafe()` bodies, and host image copies;
- each declares the resource, its queue, the timeline value it signals, and leaves the resource in its home state with
  writes made available.

The graph adds the wait and makes the writes visible at first use.

**Unified layouts.** With `VK_KHR_unified_image_layouts`, every home becomes `GENERAL` and the compiler emits memory
barriers only.

### 4.2 Resources: typed keys, descriptors, subresources, accesses, queues (#87, #88)

```java
Key<ImageResource> SCENE_COLOR = Key.image("scene color");
record ImageDesc(Format format, Extent extent, int mips, int layers, ImageType type, int samples) { ... }
ImageDesc.color(Format.RGBA16F).render(Ratio.ONE)        // relative to the render size
ImageDesc.color(Format.RGBA16F).output(Ratio.ONE)        // relative to the output size (after upscaling)
ImageDesc.depth(Format.D32F).size(2048, 2048).layers(4)  // absolute: the only choice in frame scope
```

- **Format and usage.** `Format` is an engine enum. Usage flags are inferred from the declared accesses.
- **Extents.**
  - There are two relative bases: render and output.
  - Scales are rationals with ceiling division, so tile grids, VRS tiles and HiZ sizes come out exact.
  - Relative extents inherit the view's layer count (two eyes).
  - A relative extent in frame scope is a build error.
- **Subresources.**
  - Declarable ranges: `mip(i)`, `mips(from, n)`, `layer(l)`, `face(f)`, and the depth or stencil aspect.
  - Barriers are tracked per range, merging neighbours in the same state, so the whole-image case costs what it costs
    today.
  - A mip read while another mip is written gets its own view and slot.
- **Accesses.** `Access` becomes an open value type with backend-neutral meaning: stage, read or write, kind. The common
  ones stay as constants. New ones can be added without breaking the API:
  - mesh, task and ray-tracing stages;
  - acceleration-structure build and read;
  - shading-rate attachment;
  - device-generated-commands preprocess;
  - sparse binding.
- **Queues.** Queues become an indexed capability set (graphics, compute, transfer; later video), replacing v1's
  two-queue arrays (`lastReadPass[i][1 - queue]`).
- **Bindless budget.**
  - The graph reserves a share of the bindless slots: 4,096 storage and 16,384 sampled, both shared with game textures.
  - It reports usage per view and fails the build with a clear message when the share is exceeded.
  - Slot allocation sits behind `pool`, so descriptor heaps or descriptor buffers can replace it.
- **Ordering anchors.** `stage()` integers are replaced by named anchors and relative ordering:
  - `before(Anchor.POST)`, `after(Fog.ID)` (typed ids, §4.18);
  - the resolved order is reported at build time, so ties between plugins are visible.

### 4.3 View constants as an array (new)

All 128 bytes of push-constant space are in use, and sizes are baked into pushes at build time
(`VisibilityBufferShading.java:191`).

A per-frame **view constants buffer** holds one entry per view and eye:
- the camera, a high-precision origin (split doubles) for large worlds, the view-projection and the previous one;
- the jitter and the previous jitter;
- the render region and the previous region, and a clamp rectangle for sampling;
- the output size, the cluster grid and the slots of switch fallbacks (§4.5).

`ctx.viewConstants()` gives its base and count. Shaders index it with `ViewIndex` under multiview, so stereo costs
nothing extra in the API.

This buffer is a prerequisite for:
- dynamic resolution (§4.6);
- TAA (§6);
- the switch fallbacks (§4.5);
- freeing push space.

### 4.4 Passes: attachments on the builder, bodies only draw (#93, #94 part)

```java
g.pass("gbuffer")
    .attach(albedo, Load.CLEAR, Store.STORE, Clear.color(0, 0, 0, 1))
    .attach(normals, Load.DONT_CARE, Store.STORE)
    .attachDepth(depth, Load.CLEAR, Store.STORE, Clear.depth(0))
    .read(Keys.INSTANCES, STORAGE_READ_MESH)
    .record(ctx -> {
        DrawPush.write(ctx.push()).viewConstants(ctx.viewConstants()).meshTable(ctx.address(meshes));
        ctx.drawMeshTasksIndirectCount(meshProgram, args, 0, count, 0, maxDraws);
    });
```

**The rendering scope.** The graph opens and closes it from the pass's declarations. Because the graph knows the
attachments, it can:
- set the view mask for multiview, shading-rate attachments and MSAA resolve targets;
- use `UNDEFINED` for attachments whose content is not loaded, which also helps aliasing;
- merge passes later (dynamic rendering local read, tile-based GPUs);
- map the scope to a future non-Vulkan backend.

Load and clear are fixed at build time, which also removes the class of record-time races behind the old
`RenderView.phase` bug.

**Bodies.**
- Draws: the full set, including the indirect-count and mesh-task forms.
- Transfers: copies, blit, clear.
- Readbacks: delivered N frames later on the render thread, with invalidation done for the caller.
- Viewport and scissor: within the attachment.
- Generated static push writers.

**Programs.**
- `g.computeProgram(...)` and `g.graphicsProgram(...)` return handles built through one device `PipelineCache`. It is
  persisted to disk and keyed by shader, entry, specialization and formats.
- `GraphBuilder.device()` and `cached(...)` leave the public API.

**Escape hatch.** Raw Vulkan only through `ctx.unsafe()`, which counts as an external writer (§4.1).

**Tooling.** Every pass gets a debug label. GPU timestamps are available on request through `FrameGraph.timings()`.

**Typed addresses.**
- Address fields in the generated constants and record layouts are typed references to keys.
- A pass that reads a buffer containing addresses implicitly reads the buffers they reference.
- Addresses of untracked data are marked as such.
- CI runs GPU-assisted validation.

Without this, shaders following addresses would read resources no pass declared.

### 4.5 Switches: one compiled graph, per-frame masks (#91 part)

```java
Switch SSAO = Switch.of("ssao", true);
graph.add(new Ssao().when(SSAO).fallback(Fallback.constant(Format.R8_UNORM, 1f)));  // a producer: falls back to 1.0
graph.add(new Bloom().when(BLOOM));                                                  // an in-place modifier
view.set(SSAO, false);                                                               // next frame, no rebuild
```

**What can be switched.**
- **In-place modifiers:** when off, their passes are skipped and the resource is unchanged.
- **Producers, with a declared fallback:** a constant persistent image or buffer, or a cheap clear. Consumers read the
  producer's slot through the view constants, so their programs and bindings never change. The slot points at the real
  result or at the fallback.

**How.**
- The graph is compiled once, with every switch on. Its placement is a superset of every mask's needs.
- Each frame, the executor re-emits only the barriers of the active passes, per view. It writes into arrays allocated
  for the all-on graph, so this costs microseconds, allocates nothing and needs no variant cache.
- Switched-off effects keep their memory: a 4K bloom chain stays allocated. Aliasing (§4.12) shares that memory with
  other transients, and the build report shows it.

**Masks from data.** Besides users (`view.set`), a technique sets the masks of its own passes per frame and per view from
its data: `Shadows` masks off a method no light uses. When counts are only known on the GPU, a pass stays on with
indirect arguments of zero, without barrier changes.

**Per-frame view control.**
- `view.enabled(false)` skips a view.
- `view.rate(4)` renders a security camera every 4th frame.

A skipped view keeps its histories as they are: no swap, no invalidation.

Switches as built (engine 0.73): `Switch.of(setting, technique)` / `technique.when(setting)` with a boolean engine
setting, read when each frame starts (a view's own settings first, then the graph's): its passes are skipped while it
is off, like a skipped view's, barriers kept. A producer's fallback is a constant texture of the image's size, cached by
the graph; while off, `ctx.sampled(image)` returns the fallback's slot, so shading reads it without a rebuild. Only
sampled reads fall back.

As built (engine 0.56):
- **Which passes skip.** Each pass runs for the views of what recorded it: a view's technique for its view, an
  output's copy for its view, and a request for every view that asked for it. A request the frame asked for, and every
  frame technique's pass, always run.
- **Barriers.** A skipped pass keeps its barriers, so every later pass finds the layouts and ordering it was compiled
  for; only its body is left out. The exception is the barriers on a skipped view's histories: they go with the swap,
  so the pair is untouched. A pair whose view is skipped on the frame the graph rebuilds takes its seam layouts by the
  roles it will have when the view next runs.
- **Shared work.** A pass serving several views runs while any of them does; `g.running()`, taken while building and
  read when recording, says whether the building technique's or request's views run, so it can leave out a skipped
  one's part. The renderer's culling does (engine 0.57): a skipped view's lists and occlusion history stay as they are.
- **Outputs (engine 0.58).** A view's output into a `Texture` (`graph.texture(...)`, kept, resting in
  `SHADER_READ_ONLY`, a fixed bindless slot) is always a copy, never drawn into straight: its barriers keep the
  contents, so a skipped frame leaves what the view last rendered. Readers take it with `g.texture(texture)`, which
  declares the copy then, so they order after it within the frame. Outputs into windows are undefined while skipped:
  the presenter skips presenting them. Drawing straight into a texture, mips and previous-frame mode come later.

### 4.6 Resolution and dynamic resolution (#91)

- Techniques take no sizes; sizes come from the view.
- Images of a view are allocated at `render × maxRenderScale` and `output`, with headroom: grown about 1.25×, rounded to
  256 px, and shrunk after an idle period. Dragging a window doesn't rebuild every frame.
- Swapchain size does not drive graph allocation. The `Presenter` (§4.8) scales or composites.
- **Each frame, a view renders into a render region** (`ctx.region()`, also in the view constants):
  - mip sizes are computed from the region (`ctx.extent(image.mip(i))`);
  - sampling clamps to the clamp rectangle;
  - TAA and upscalers read the previous region and the current one;
  - the cluster grid is sized for the maximum, with the active part in the constants.

### 4.7 Views and scopes: one dependency graph across scopes (#90)

```java
ViewPort main   = graph.addView("main", ViewOptions.stereo(false)).camera(player);
ViewPort mirror = graph.addView("mirror", ViewOptions.small()).camera(mirrorCam).size(512, 512)
                       .output(mirrorTexture).techniques(Profile.UNLIT_NO_POST);
```

**Ownership.** A `ViewPort` owns its `RenderView` (culling lists sized by `ViewOptions`, occlusion history), histories and
persistent resources, and retires them through `Retiring` when closed. A stereo view has two layers (eyes) and renders
with multiview.

**Scope is a property of keys and passes, not of frame phases.**
- A **view technique** is instantiated per view by a factory. Per-view state lives in that instance. Its keys are
  view-scoped. Examples:
  - shading;
  - clustered lights for that view's frustum;
  - the depth pyramid and the late cull;
  - post effects, TAA.
- A **frame technique** is built once, for work that depends on no camera:
  - scene upload, skinning, GPU simulation, BLAS builds;
  - the streaming publish step, the presenter.
- **Crossing scopes:**
  - `g.require(view, key)` reads another view's version and implies a dependency between the views.
  - `g.requireAll(key)` lets a frame pass read every view's version.
  - Frame keys are readable from every view.

  That is enough for:
  - virtual shadow maps: views mark pages, one frame pass allocates them, views render and shade;
  - virtual-texture feedback;
  - DDGI or surfels fed by all views;
  - one batched early cull of all views (today's `MeshletCulling.Views`);
  - a shared TLAS built after per-view work.
- **Ordering.** The whole frame is ordered by one stable topological sort across scopes, with ties broken by the order
  added. No fixed prologue, views and epilogue.
- **Scheduling across views.** The default records view after view, which uses the least transient memory. An
  **interleaved** schedule records technique by technique across views: fewer barrier batches and more overlap, for more
  memory. The choice is per graph and measured.

**Per-view technique sets.** `addPerView(factory)` applies to every view by default. A view may pick a named profile, or
add or remove techniques (`view.add(...)`, `view.without(Bloom.ID)`), as jME's per-`ViewPort` processors do.

**Camera-dependent shared work is explicit.**
- **Shadows** are one frame-level technique (§4.15). Directional cascades are shadow views fitted to each camera view;
  far cascades can be shared between views.
- **TLAS** is shared from an anchor view by default. A view farther away than a threshold gets its own, so it doesn't
  lose float precision in large worlds.
- **Origin rebasing.** A rebase event offsets or invalidates world-space persistent caches (DDGI volumes, VSM clipmaps,
  surfels).

**Capacity.** The shared chunk buffer (`MeshletRenderer.java:132`) is split per view, so late culls of different views
don't serialize.

### 4.8 Presentation (new)

A frame-scope **`Presenter`** owns presentation:
- the swapchains of one or more windows;
- per-display HDR colour space and metadata;
- present pacing;
- the composition of views into windows, for split-screen regions and scaling;
- the UI pass;
- a frame-generation hook.

Views render into graph images. The standard keys are:
- `SCENE_LINEAR`;
- `MOTION`;
- `DEPTH`;
- `PRE_UI`, the colour before UI, which frame generation needs;
- `UI`.

Tone mapping reads the colour space of the display its view is composed into. v1's output substitution (rendering
straight into the swapchain image when a view covers it exactly) stays as an optimisation. The caller no longer manages
semaphore pairs: `graph.render()` acquires and presents through the presenter.

### 4.9 Scene data as keys (#92)

- **Keys from `SceneUpload`.** The GPU scene and geometry pool become keys provided by the frame technique
  `SceneUpload`: `INSTANCES`, `POSITIONS`, `ATTRIBUTES`, `MESHLETS`.
- **Uploads become passes.** `SceneUpload` records uploads, texture uploads and BLAS builds as passes, instead of
  `FrameStart` work and `submitAndWait`.
- **Writers are modifiers.** Skinning and simulation `modify` those keys. Culling, drawing and the TLAS `require` the
  final version.
- **Single ownership extends to imported resources.** Only the key's owner or a modifier writes them.
- **What goes.** Token buffers and `ALL_COMMANDS` barriers go away.
- **Material types are published by graph generation.** A new material type or feature becomes visible to the GPU
  tables only once a graph that knows it is live. Until then its objects draw with a fallback material, never through an
  out-of-range type index.

### 4.10 Build and record purity; rebuilds (#94)

The **build contract** comes with the phase 2 migration, so techniques migrate once.
- `build` reads only its snapshot: an open set of typed entries (view descriptors, material types, device features,
  the switch set, …), each hashed into the pipeline-cache key (§4.18).
- `build` returns an **immutable build product**, which pass bodies capture. Technique objects hold only final fields.
- The bytecode check (`truesim.allocation-free`) is extended:
  - record lambdas and `build` may not store into fields or static fields;
  - record lambdas may not store into captured arrays, except into per-frame scratch from the context.

**Rebuilds without stalls** (phase 1):
- old resources retire through `Retiring`;
- new histories are transitioned in the first frame;
- the seam (§4.1) joins the two graphs.

**Background rebuilds** (later):
- A worker builds a new `CompiledGraph` from the snapshot. Programs compile in parallel through the concurrent,
  reference-counted program cache, which retires programs no graph uses any more.
- The old graph keeps rendering, and the swap happens at a frame boundary with a seam.
- The first build is synchronous.
- Changes that arrive mid-build cancel and restart the build.
- Errors come back through a `BuildResult` callback while the old graph keeps running.
- Swapchain-size changes no longer need synchronous rebuilds, because the presenter decouples them (§4.8).

### 4.11 Persistent resources, histories, textures, imported buffers (#89)

- **Three image notions in the API:**
  - `ImageResource`, the graph handle;
  - `Texture`, persistent and sampleable, with a stable bindless slot;
  - `History<T>`, this frame's and the previous frame's version.
- **Persistent resources:** `g.persistent(name, desc)`, per view in view techniques. Contents are kept across frames,
  and across rebuilds while the descriptor is unchanged.
- **Histories:** images and buffers, named per view, and still valid after a rebuild with an unchanged descriptor.
  `resetHistory()` handles camera cuts.
- **Render-to-texture:**
  - The `Texture` target is persistent and never aliased. It rests in its home state, and its mips are generated after
    rendering when it has a chain.
  - Same frame by default.
  - Previous-frame mode needs an image per frame in flight behind one slot, so it comes later.
- **Imported buffers:**
  - An `ExternalBuffer` wraps its `GpuBuffer`.
  - A size change no longer rebuilds.
  - Async compute is allowed when the owner created the buffer concurrent across families. The pool and the GPU scene
    do that from §4.9 on.

### 4.12 Memory aliasing and scheduling (#95)

- Transients are placed in heaps allocated with `VMA_ALLOCATION_CREATE_CAN_ALIAS_BIT`, by lifetime interval, across
  formats and sizes.
- The heaps are shared by the frames in flight, as v1's transients are; per-frame heaps would multiply memory.
- Barrier state is tracked per heap range.
- Each aliased image keeps its own views and slots.
- Resources used by more than one queue stay unaliased.
- Dependency-aware reordering for async overlap is optional, and off until measured.

### 4.13 Streaming on the transfer queue (#101)

**Loads.** `StreamQueue` submits loads on the transfer queue across as many frames as they take, signalling timeline
values. It is an external writer (§4.1).

**Publishing a finished load:**
- At `beginFrame`, completed loads are collected.
- The frame waits on their timeline values.
- Images get a queue-family ownership acquire, after a release on the transfer queue.
- The mesh and material tables are updated in that same frame.

**Mips.** Pre-baked mips (KTX2) upload directly. Images without them get their mips generated on the graphics queue
after the acquire, because the transfer queue cannot blit.

**Control.** Budgets for bytes in flight, priority, cancellation, and eviction through the asset cache.

### 4.14 Internal structure and device-free tests (#96)

```
graph/api/      GraphBuilder, PassBuilder, Key, ImageDesc, Format, Access, Switch, ViewPort, Technique, Request
graph/compile/  resolve → scopes → sort → cull → queues → place → barriers → batches ⇒ CompiledGraph (immutable)
graph/execute/  replays a CompiledGraph (masks, seams) into command buffers: the only Vulkan code
graph/pool/     allocation, bindless slots, heaps, histories, persistent resources
```

- **Placement and allocation are separate.** Today `Physical`'s constructor allocates and registers slots
  (`FrameGraph.java:1563-1577`). In v2, placement only decides homes, and allocation and slots happen in `pool`.
- **Device-free tests.** The compiler emits barriers into an abstract sink, so it is tested without a GPU.
- **Splitting the big classes.** `FrameGraph` (1,939 lines) and `MeshletRenderer` (~840 lines) are split along these
  lines.
- **GPU-free bridge tests.** `SceneSync` gets small table interfaces.
- **One barrier helper** replaces the hand-written ones.

### 4.15 Shadows as data (new)

**Today.** Every shadowed light is a technique of its own: `ShadowMapShadows` per light, plus `CascadedShadowMaps`
for the sun. Giving a light a shadow means `graph.add` and a rebuild. Shading picks the evaluation with a fixed
`switch (s.kind)` in `lights.slang` (`shadowVisibility`), which is compiled into every material type's entry points.

**In v2, which lights cast shadows, and how, is per-light data.** The graph's passes stay fixed.

```java
graph.add(new Shadows(lights, geometry).budget(ShadowBudget.pool(8192, 8192).views(64)));

// per light, at any time, no rebuild; serialisable with the light's data (§4.20)
light10.shadow(ShadowSpec.size(1024).softness(0.3f).method(ShadowMethod.VARIANCE));
light11.shadow(ShadowSpec.size(2048).method(ShadowMethod.PCF));
sun.shadow(ShadowSpec.cascades(4).method(ShadowMethod.PCF));
```

**One `Shadows` owner, with a frame part and per-view parts (§4.7).**
- **Shadow views.** Each shadowed light becomes a lightweight internal shadow view: one per spotlight, six or a
  six-layer array per point light, and cascades for directional lights fitted to each camera view. Near cascades are
  per view; far cascades can be shared in split screen. All shadow views are culled together in the batched cull.
- **Allocation is sticky.**
  - Lights get tiles by importance (screen coverage, distance, priority) within the budget.
  - A tile stays with its light until the light needs a different size, so static lights with unchanged casters keep
    their map and are not redrawn.
  - Hysteresis (a minimum hold time, and a margin before downgrading) stops lights at the budget edge from flickering.
- **One-step fallback.** A light over budget first gets a lower resolution of its method, then PCF, then no shadow. The
  build report and `timings()` show it.

**A fixed set of methods**, each compiling its passes once and handling all its lights in one GPU-driven pass:
- PCF: depth into tiles.
- Variance or moments: moments, then a blur.
- Ray: ray queries in shading, no map. Needs ray queries; without them it falls back to PCF.
- Virtual: added later. Views mark the pages they need, one frame pass allocates physical pages, then pages are rendered.

**Resources are allocated lazily, the first time a method is used,** and shared by format where possible: PCF and a
future PCSS share one depth pool. A method's passes are masked off by the owner from data when no light uses it
(§4.5).

**How games and plugins extend shadows: the shadow-mask path.** A plugin technique renders its own shadow (contact
shadows, PCSS, a game-specific trick) into a per-light visibility mask. It marks the light's entry `MASK`, and shading
samples it (today's `SHADOW_MASK`). This needs no shader change and no engine edit, and it works at runtime.

Adding a method to the inline set is engine work. A registry for game-provided inline methods would need dispatch code
generated when the application is linked, id assignment, version checks and more shader variants. That is large,
permanent machinery for a rare need, so it waits until a real case asks for it (§4.18).

**Replaced.** `ShadowMapShadows`, `CascadedShadowMaps`, `RayTracedShadows` and `InlineRayShadows` become methods and
views inside `Shadows`.

### 4.16 A generic graph core: `common-graph` (new)

Most of what the compiler does has nothing to do with the GPU. That part becomes a library with no Vulkan or engine
dependency: a new `common-graph` repository, next to `common-jobs`. Two graphs build on it: the GPU frame graph and a
CPU **module graph** for game logic (§4.17).

| `common-graph` (generic) | Frame graph only | Module graph only |
|---|---|---|
| typed keys; provides, requires, modifies; requests with deduplication; `Implementation` swapping | images, buffers, descriptors, subresources | game state, components, events |
| declared accesses as abstract resources with read/write | barriers, layouts, queues, seams | happens-before edges, job lanes |
| anchors and a stable topological sort, across scopes | memory placement, aliasing | phases and time steps |
| culling of what nothing requires, unless it has side effects | command recording | parallel job schedule on `common-jobs` |
| switches, rates and masks set by techniques from their data, applied per frame over one compiled schedule; namespaced ids | the presenter, streaming | hand-off to the frame graph |
| immutable compiled schedule, build snapshot, purity contract and its check, introspection, device-free tests | | |

The rule for the core is that none of its types names a GPU concept. An access is
`(resource, read | write, kind)`, where `kind` is opaque to the core and interpreted by the graph built on top. The
frame graph's compile step becomes: core resolve, scopes, sort and cull, then the GPU-specific steps (queues, placement,
barriers, batches).

### 4.17 The module graph (game-API milestone, #97–#100)

The CPU counterpart of the frame graph, run once per tick, separately from rendering.

```java
public final class WeatherModule implements GameModule {
    public void declare(ModuleBuilder m) {
        m.phase(Phase.SIMULATION).after(TimeOfDay.class)
         .reads(Keys.PLAYER_CAMERA, Keys.TIME_OF_DAY).writes(Keys.WIND)
         .rate(2);                                                 // every 2nd tick
        m.render(g -> g.addPerView(v -> new Rain(v).when(RAIN)));  // render passes it brings along
    }
    public void update(TickContext ctx) { ... }
}
game.modules().add(new WeatherModule()).add(new Physics()).add(new EnemyAi()).add(new Hud());
```

- **Parallel by declaration.** Modules declare the state they read and write. Modules whose accesses don't conflict run
  in parallel on `common-jobs`. A write before a read, or two writes, become happens-before edges.
  - With the same inputs, the schedule is the same on every run, so ticks stay deterministic.
  - In debug, accessing undeclared state throws.
- **Dependency culling.** A module whose outputs nothing requires is dropped, as unused passes are. The same goes for
  everything only it required, unless it is marked `sideEffects()` (input, audio, networking, saving).
- **Phases and time steps.**
  - Phases run in order: input, simulation, physics, scene update, render prep.
  - A module chooses a variable or fixed step. Fixed-step modules (physics) run zero or more times per tick.
  - `rate(n)` runs a module every nth tick.
- **Enable and disable** is a switch, applied as a mask for the next tick without recompiling, like jME's
  `setEnabled`. Adding or removing a module recompiles, which is cheap and needs no GPU.
- **Rendering.** A module can bring techniques into the frame graph. Disabling the module flips their switch. Results
  reach the frame graph at one point in the frame, after render prep, through the scene and its keys.
- **Purity.** The same contract as the frame graph: `declare` reads only its snapshot, and the bytecode check covers it.

### 4.18 Extension points: what is open, what stays fixed (new)

The owner wants games and plugins to add and replace behaviour without touching the engine. The question was whether
the shadow pattern should become one generic mechanism for many features: an owner technique, per-object data, and
registered methods with budgets and fallbacks. A critical review judged each feature by cost against benefit.

**Decision: no generic method-registry framework for now.**
- Only materials and shadows fit the pattern, and they differ in essentials:
  - materials are binned into passes per type, while shadows are evaluated inline in shading;
  - materials are budgeted by table slots, while shadows are budgeted by texels, pages and views.
- A generic budget model either knows nothing or leaks each feature's details.
- Generic, allocation-free code over structure-of-arrays data in Java means boxing or code generation.
- It would lock in a design before a second and third real case exist.

The rule: **extract a shared mechanism after three concrete implementations**, not before. Until then, only small,
proven pieces are shared:
- the slot-and-block table allocator with frame retirement (from `Materials`);
- a registry with stable, namespaced ids;
- the Slang-to-Java record generator.

None of this goes into `common-graph`, which stays a graph library.

**Where games and plugins extend the engine**, through seams that exist or come with v2:

| Extend… | Through | Example |
|---|---|---|
| Rendering steps | techniques that `modify` keys, at named anchors | a custom post effect, a fog pass, particle forces |
| Whole features | `Implementation` swapping and `replace` by id | a different AO, upscaler, sky or shading technique |
| Per-view choices | per-view technique sets, switches with a capability check (§4.5, §4.7) | SSAO on the main view only; DLSS → FSR → TAA |
| Geometry | `GeometryProvider` | terrain, foliage, water surfaces, impostors |
| Surface appearance | material types (registered, Slang as the source of truth) | a water or hologram material |
| Shadows | the shadow-mask path (§4.15) | contact shadows, PCSS |
| Particles | modifiers of the particle keys (§4.19) | custom forces, collision |
| Game logic | modules (§4.17) | AI, weather, gameplay systems |

**What stays fixed (engine work, for consistency and speed):**
- light types: point, spot, directional, area later;
- the lighting model and material features;
- the inline shadow methods;
- the particle render methods.

IES profiles are a profile-texture parameter on spot and point lights, not a type.

**Cheap changes made now**, because they keep extension safe whatever comes later:
- **Name-based identity.**
  - Techniques are identified by typed ids (below), not classes, in `replace`, `view.without(...)` and ordering.
  - Registered types (materials first) get stable, name-based ids. Today `Materials.register` returns
    `types.size() - 1`, so ids depend on registration order.
  - Ids are never persisted by number or used in pipeline-cache keys.
  - Replacing a built-in is declared (`replaces(Bloom.ID)`), and silent duplicates are rejected.
- **Typed handles.** The game API never hands out raw `long` handles: scene objects are typed `Node` objects with typed components
  (§4.20). `long` handles stay internal, for bulk engine paths.
- **Typed ids; names only at the edges.** A name is never typed as a string in code. Every id is a typed constant,
  declared once next to what it names, and code refers to the constant:

  ```java
  public final class Bloom implements Technique {
      public static final TechniqueId<Bloom> ID = TechniqueId.of("truesim.bloom", Bloom.class);
  }
  view.without(Bloom.ID);  graph.replace(Bloom.ID, new MyBloom());  g.after(Fog.ID);
  ```

  - Keys (`Key<T>`), switches, anchors and technique ids are constants of this kind. A fixed set (shadow methods) is an
    enum. Material types keep their generated `TYPE` constants, which come from the Slang struct, so their names can't
    be misspelled either.
  - The string inside an id exists only for persistence and diagnostics: scene files, saved settings, logs, the build
    report. It is checked once, when the constant is registered: a duplicate or a malformed namespace fails at startup,
    not at use.
  - Where names come from data (a scene file referring to a material type or a technique), loading resolves them
    against the registry. An unknown name is an error with a "did you mean…" suggestion. The editor offers only
    registered ids, so typos cannot come from there.
  - Ids that come from Slang (material types, generated layouts, constants) are generated, never written by hand.
- **Slang module collisions fail the build.** `CollectSlangLibraries` silently keeps the first module of a path today.
  Plugins namespace their modules (`acme/water.slang`).
- **Masks from data.** Techniques set the masks of their own passes per frame and per view from their data, not only
  users through `view.set` (§4.5). `Shadows` masks off a method no light uses.
- **An open build snapshot.** §4.10's snapshot is an open set of typed entries (material types, device features,
  views…), each hashed into the pipeline-cache key, instead of a fixed list.
- **`Surface` evolves add-only.** It gets a `defaults()` initializer that material types start from, so a new field
  never stays uninitialised in a plugin's type.

### 4.19 Particles (later milestone; the shape decided here)

**Do:**
- **GPU simulation** for the visual bulk, with one simulation path. It is a frame technique that provides particle keys:
  a shared particle pool with a range per emitter, and a generated `[HostLayout] struct Particle`.
- **CPU spawning into the GPU simulation** through a spawn-record ring (emitter, count, transform, velocity, seed),
  consumed by a GPU spawn pass, for impacts and gameplay events.
- **CPU-fed emitters on the same render path.** A gameplay system (a module, §4.17) writes particle records into a
  per-frame mapped buffer through the generated writers, allocation-free, and the GPU renders them exactly like GPU
  particles. This gives particles that gameplay depends on CPU authority and determinism without a second simulator.
- **Extension:** plugins add forces, collision or behaviour as techniques that `modify` the particle keys.
- **Render:** a small fixed set, billboards and mesh particles (through `GeometryProvider`), with ribbons later. Lit or
  unlit and soft (reading `DEPTH`) are flags. Blended particles share the transparency path with blended materials, to
  be decided when transparency is designed.
- **Budgets:** pool capacity per emitter and in total, with spawn-rate reduction and distance culling per view as the
  fallback.

**Don't:**
- **Switch one system between CPU and GPU simulation as a fallback.** Every behaviour would need two identical
  implementations, plus state migration across the readback delay.
- **Read GPU particles back for gameplay.** It arrives 2–3 frames late and is nondeterministic. If gameplay depends on a
  particle, it is simulated on the CPU.

### 4.20 Per-object data on scene objects (new)

Today `Lights` is an engine buffer indexed by int, with 12–14 positional parameters, and has no scene-side form.
Following `MaterialData` (engine-agnostic, by name, serialisable):
- scene gets typed records per feature: `LightData` with its `ShadowSpec`, later `DecalData`, `EmitterData`, probe and
  volume data;
- each record is attached to nodes;
- engine owners (`Shadows`, a `Lights` sync like `SceneSync`) turn them into GPU tables each frame.

Typed records rather than an open-ended component map keep this simple, allocation-free and editor-friendly. The
generated records' `[Range]` and `[Color]` attributes drive editor UI, as they do for materials.

**The future scene API: one `Node` type plus typed components** (game-API milestone, with §4.15; not built in the
frame-graph phases).

```java
Node pole = world.root().attach("street pole").move(10, 0, 4);
Node lamp = pole.attach("lamp").move(0, 4.5, 0).lookDown();      // spot and directional lights point along -Z

SpotLight light = lamp.add(SpotLight.builder()
    .color(Color.rgb(1.0f, 0.85f, 0.7f)).intensity(Lumens.of(1800)).range(25)
    .cone(Degrees.of(20), Degrees.of(35)).profile(IES_STREETLAMP)
    .shadow(ShadowSpec.size(1024).softness(0.3f).method(ShadowMethod.VARIANCE))
    .build());
lamp.add(new Renderable(LAMP_GLASS_MESH, glassMaterial));         // the same node: a mesh and a light

light.intensity(Lumens.of(400 * flicker));                        // typed live handle, allocation-free setters
light.shadow(ShadowSpec.NONE);                                    // any time, no rebuild
light.node().move(0, 0.1, 0);                                     // the transform lives on the node
lamp.get(Light.class);                                            // or keep the handle add() returned
lamp.remove(light);                                               // or lamp.removeFromParent(): everything goes
```

**Why composition instead of a jME-style `Spatial` hierarchy.**
- **Several kinds on one object.** One object can be several things: a lamp that is a mesh and a light, or an emitter
  that also lights.
- **Behaviour lives in one place.** Moving, parenting, layers and removal live only on `Node`, so no shared base class
  grows.
- **Plugins don't subclass engine classes.** They add component types (`lamp.add(new Emitter(...))`) and never inherit
  engine internals.
- **Faster code.** `Node` and every component class are `final`. Calls stay monomorphic, so they inline, and short-lived
  handles are easy to scalarize in escape analysis. Without a deep virtual hierarchy, call sites don't become
  megamorphic.

**Typed throughout.**
- `Node` is one object per scene node, made once and reused, so per-frame use allocates nothing. Using a removed node
  throws, because the object keeps its generation.
- `long` handles stay internal, for bulk engine paths.
- Components are typed. `Light` is sealed (`PointLight`, `SpotLight`, `DirectionalLight`, area later), so a point light
  has no `cone`.
- A node holds at most one component of each type.
- The handle `add` returns is a live view onto the component's slot in its store. Its setters write the store directly.

**Under the hood, each component type is a flat per-scene store,** which the engine reads in bulk: `LightSync` feeds the
GPU light table and `Shadows`, as `SceneSync` does for renderables. Changes are logged on the scene's clock, so the
engine follows changes instead of rescanning.

**Lights (decided).**
- **They light the whole world, by design.** The node only places and orients the light. Limiting what it lights uses
  layer masks (`.layers(INTERIOR)`).
- **Always physical units:** lumens, lux and candela as typed values (`Lumens`, `Lux`, `Candela`), with angles as
  `Degrees`.
- Lights are saved with the scene, and the editor shows their fields.

**Materials on geometry.**

```java
Material rock = Material.of(MetallicRoughnessParameters.DEFAULTS     // generated from the Slang struct
    .withBaseColor(Color.srgb(0.55, 0.50, 0.45))
    .withBaseColorTexture(ROCK_ALBEDO)                               // AssetRef<TextureData>
    .withNormalTexture(ROCK_NORMAL)
    .withRoughness(0.85f));                                          // [Range(0, 1)] checked

Node boulder = world.root().attach("boulder").move(3, 0, 1);
Renderable r = boulder.add(new Renderable(ROCK_MESH, rock));         // every submesh
r.material(1, moss);                                                 // submesh 1

Material wetRock = rock.with(ClearcoatParameters.DEFAULTS.withStrength(0.6f)).alphaMask(0.5f).doubleSided(true);
r.material(wetRock);                                                 // a table write, no rebuild

SharedMaterial wet = world.materials().share(rock);                  // change many at once
boulderB.add(new Renderable(ROCK_MESH, wet));
wet.update(p -> p.withRoughness(0.3f));

Material holo = Material.of(HologramParameters.DEFAULTS.withScanSpeed(2f));   // a plugin's type, same API
Material loaded = assets.load(ROCK_MATERIAL);                        // from a file, via generated fromValues
```

- **A `Material` is an immutable value.** Its type follows from its generated record, so type and values cannot
  mismatch. Identical values share one GPU table entry.
- **Textures are typed asset references.** Bindless slots stay internal to the engine.
- **`SharedMaterial` is the one way to change many objects at once.** Changing one `Renderable` never changes another.
- **Strings exist only in files.** Loading reports unknown types and fields with a "did you mean…" suggestion. The
  editor builds its fields from the generated records' attributes.

**Textures in materials.**

```java
Material<MetallicRoughnessParameters> rock = Material.of(MetallicRoughnessParameters.DEFAULTS
    .withBaseColorTexture(ROCK_ALBEDO)                 // AssetRef<TextureData>: from a file
    .withOrmTexture(ROCK_ORM)
    .withNormalTexture(ROCK_NORMAL).withNormalScale(1.2f));

Texture noise = world.textures().create(TextureDesc.rgba8(256, 256).fullMips(), pixels);  // made at runtime
Material<MetallicRoughnessParameters> screen = Material.of(MetallicRoughnessParameters.DEFAULTS
    .withEmissiveTexture(mirrorTexture));              // a view's render target (§4.11): the same field

Material<MetallicRoughnessParameters> wet = rock.with(p -> p.withBaseColorTexture(ROCK_WET));  // typed edit
Material<MetallicRoughnessParameters> clamped =
    rock.sampler(MetallicRoughnessParameters.BASE_COLOR_TEXTURE, Sampler.CLAMP_LINEAR);         // typed field
rock.with(p -> p.withNormalTexture(TextureSource.NONE));                                         // no texture
```

- **Texture fields accept any `TextureSource`.** That is a sealed type covering an `AssetRef<TextureData>` (from files)
  or a runtime `Texture` (procedural, streamed, or a view's render target). The generated `with…Texture` setters
  accept both.
- **The field decides the colour space** (`[Texture(SRGB)]` or `[Texture(LINEAR)]` in Slang). The engine samples
  through the matching view, so a colour map can't be read as linear, or a normal map as sRGB, by mistake.
- **Samplers default to the material sampler** (trilinear, anisotropic, repeating). A field's default can be declared
  in Slang, and a material overrides it per field through the generated field constant, never by name.
- **Until a texture is resident,** the field's neutral fallback is used: white, a flat normal or no emission. A
  streaming texture fades in and is never an error. Bindless slots stay internal.
- **`Material<P>` is generic in its record,** so `with(p -> …)` edits it without casts or class tokens. `Renderable`
  takes any `Material<?>`.

## 5. Example (graph level; the game-API facade wraps it later)

```java
graph.add(new SceneUpload(scene))                                    // frame technique
     .add(new MeshletCullingBatch(renderer))                         // frame pass reading every view's cameras
     .add(new Shadows(lights, geometry))                             // every light's shadow, per-light data
     .addPerView(v -> new ClusteredLights(lights, v))
     .addPerView(v -> new VisibilityBufferShading(geometry, v))
     .addPerView(v -> new Ssao(v).when(SSAO).fallback(Fallback.constant(Format.R8_UNORM, 1f)))
     .addPerView(v -> new Bloom(v).when(BLOOM))
     .addPerView(v -> new ToneMap(v))
     .add(new Presenter(window).compose(main).ui(hud));

ViewPort main   = graph.addView("main", ViewOptions.defaults()).camera(player);
ViewPort mirror = graph.addView("mirror", ViewOptions.small()).camera(mirrorCam).size(512, 512)
                       .output(mirrorTexture).techniques(Profile.UNLIT_NO_POST);
mirrorMaterial.set("albedo", mirrorTexture);

// every frame
main.renderScale(dynamicResolution.scale());
main.set(SSAO, settings.ssao());
mirror.enabled(mirrorVisible);
graph.render();
```

With the facade, a jME-style post effect becomes `viewPort.post(new Bloom())`.

## 6. Out of scope here, with the hooks v2 leaves

| Topic | Hook in v2 | Later work |
|---|---|---|
| TAA, motion vectors | jitter, the previous view-projection and the previous region in the view constants; history contents kept; `MOTION` key | per-instance previous transforms, motion-vector output |
| Transparency, OIT, refraction | anchors, in-place modifiers of the scene colour, a refraction copy as a request | blended bin, sorting or OIT, blended materials |
| Skinning, BLAS refit | scene data as keys; AS accesses | skinning technique, refit passes |
| Frame generation | `PRE_UI`, `MOTION` and `DEPTH` keys; the presenter's pacing hook | vendor integration |
| Virtual shadow maps, DDGI, surfels | cross-scope requires; persistent resources; rebase event | the techniques |
| Device-generated commands, work graphs | open `Access`, queue capabilities | the techniques |
| Variable rate shading | shading-rate attachments on the pass builder; rational extents | the technique |
| Particles | particle keys, the spawn ring and CPU-fed emitters (§4.19) | the particle milestone |
| MSAA | samples in descriptors; resolve targets on the pass builder | forward-path support |
| Descriptor heaps or buffers | slot allocation behind `pool` | the backend change |
| A non-Vulkan backend | backend-neutral `Access`; graph-owned rendering scopes. Bindless device addresses rule out D3D12 for now. | — |

## 7. Phases

There is one API migration of technique code (phase 2) and one of technique constructors and views (phase 4). Each phase
is released.

| # | Content | Issues | API |
|---|---|---|---|
| 0 | Small, independent: a Slang module collision is a build error (build-logic); stable, name-based material type ids; `Surface.defaults()` | new issues | small |
| 1 | `common-graph` core (§4.16) with its own tests; frame graph compiler on top of it; executor and pool split; immutable `CompiledGraph`; device-free tests; exported end states and seams; home states and the external-writer contract; placement without allocation; rebuilds without stalls; one barrier helper; masks set from data; namespaced technique ids; open build snapshot | #96, #94 part | none |
| 2 | Typed keys, `ImageDesc` with rational extents, `Format`, usage inference, subresources, open `Access`, queue capabilities, anchors; pass builder with attachments, graph-owned rendering scopes; `PassContext` v2, programs, pipeline cache on disk, static push writers, labels, timings, readbacks; view constants array; typed addresses; the build and record purity contract and its bytecode check | #87, #88, #93, #94 part | breaking (one migration) |
| 3 | Scene data as keys, `SceneUpload` passes, material types published by graph generation; no tokens, no `ALL_COMMANDS` | #92 | internal + `Keys` |
| 4 | Views: `ViewPort` and `RenderView` ownership, stereo, per-view factories and technique sets, cross-scope `require(view, key)`/`requireAll`, one topological sort, view enable and rate, `Texture` outputs with persistent images, per-view histories; the `Presenter` with multiple windows and HDR | #90, #89 part | breaking (constructors) |
| 5 | Render regions, dynamic resolution, allocation headroom | #91 part | additive |
| 6 | Switches with fallbacks and per-frame masks | #91 part | additive |
| 6b | Shadows as data (§4.15): the `Shadows` technique, scene-side `LightData` (§4.20), shadow views in the batched cull, sticky lazy allocation, PCF, variance and ray, the mask path for plugins; virtual shadow maps after it | #120 | breaking for shadow techniques |
| 7 | History buffers, imported buffers on async, no rebuild on buffer resize | #89 | additive |
| 8 | `StreamQueue` | #101 | additive |
| 9 | Background rebuilds | #94 | none (the contract came in 2) |
| 10 | Memory aliasing, interleaved view scheduling, optional reordering | #95 | none |

- **Phase 1 goes first.** Every later phase changes the compiler, and the seams are what rebuilds, switches, views and
  background builds rely on.
- **Phase 2 status (2026-10-30):** landed in engine 0.34 to 0.42 (typed keys and ids, `ImageDesc` and `Format`,
  subresources, attachments and graph-owned scopes, `PassContext` v2, programs, the pipeline cache on disk, generated
  push writers, labels, timings, readbacks, the view constants array, the purity contract and its check). Left for
  later phases: typed addresses, open `Access` and queue capabilities, and a graph-level `ctx.viewConstants()` (with
  views in phase 4).
- **Phase 4 status (2026-10-31):** landed in engine 0.53 to 0.66: views with their own keys, sizes, histories and
  per-view techniques (`SceneView`, `SceneViews`); view enable and rate without a rebuild; `Texture` outputs, graph's
  or the app's; one top level per frame relative to a movable origin, built only when instances change; shadow tables
  and cascades per view; window settings; the `Presenter` with several windows; engine settings; HDR output with tone
  mapping per window; VR through OpenXR (`engine-xr`: eyes as views, a desktop mirror, input) in 0.67 to 0.72. Left:
  multiview (both eyes in one pass) and a run with a headset.
- **Phase 6 status (2026-10-31):** switches with fallbacks landed in 0.73.
- **Phase 2 is large.** It lands as several PRs that are released once. Its design decisions (graph-owned scopes, view
  constants as an array, open `Access`, the purity contract) are the ones that would be expensive to change later.
- **After phase 4,** phases 5 to 10 can be reordered by need.
- **The module graph (§4.17)** is part of the game-API milestone. It needs only `common-graph` from phase 1, so it can
  start in parallel with phases 2–10 if wanted.

## 8. Decisions (2026-10-30)

1. **View scheduling:** view after view by default; interleaved as an option, then measured.
2. **Switch memory:** switched-off effects keep their memory until aliasing lands (phase 10).
3. **Previous-frame render-to-texture:** later, when a game needs it.
4. **MSAA:** descriptors and resolve targets only, until a forward-path need appears.
5. **Frame loop:** `graph.render()`, with the presenter owning the swapchains; the game-API facade owns the loop later.
6. **Lights:** always physical units; lights light the whole world, filtered by layer masks.
7. **Scene API:** one final `Node` type plus typed, final components; no `Spatial` hierarchy; typed ids everywhere.
