# engine

The true-sim engine: windows, input and Vulkan rendering on [LWJGL](https://www.lwjgl.org/) 3.4 with SDL3.
Multi-window, multi-GPU and multi-threaded from the start; per-frame paths allocate nothing.

Public scene types (meshes, materials, geometries, scene management) live in a separate repository, so mods
and tools can use them without the engine. Shaders are written in [Slang](https://shader-slang.org/).

| Module            | Package                       | What                                                    |
|-------------------|-------------------------------|---------------------------------------------------------|
| `engine-platform` | `com.truesim.engine.platform` | SDL3: displays, windows, runtime mode changes, all input devices |
| `engine-vulkan`   | `com.truesim.engine.vulkan`   | Vulkan 1.3, 1.4 where available: instance, GPUs, devices, swapchains, frames |
| `engine-graph`    | `com.truesim.engine.graph`    | Frame graph: techniques plugged together by the results they provide and need |
| `engine-render`   | `com.truesim.engine.render`   | GPU-driven meshlet rendering of scene's meshes: geometry pool, GPU culling and LOD; blur |
| `engine-scene`    | `com.truesim.engine.scene`    | Draws scenes: scene's renderables and assets onto renderers, one view per scene and camera |

## Platform

One `Platform` per process, used from the thread that started it. Events go to a `PlatformListener` as engine
types and plain values, with nothing allocated. Nothing in the public API is SDL's: keys are `Key` (physical, USB HID
based) with a layout character and `KeyModifiers` bits, and there are `MouseButton`, `GamepadButton`, `GamepadAxis`,
`HatPosition`, `SensorType`, `TouchPhase`, `PenAxis` and so on. The mapping lives in one package-private class, so
another backend replaces only the implementation. The input module builds on this.

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
  changes (`claim`, then the bytes in `changes()`; a record at most once per upload, from any thread once claimed); the
  frame's first commands copy the list into the frame slot's upload buffer (`GpuBuffer.hostWritten`: write-combined,
  or BAR memory where the GPU has it) and a compute pass scatters it. The CPU pays per record changed, frames in flight
  keep no copies (the queue orders the scatter after the frames before), and shaders read device-local memory.
  `GpuScene`'s instances are one.
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
try (GeometryPool pool = new GeometryPool(device, GeometryPool.Capacity.of(1 << 22, 65_536, 1024));
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

- **Geometry pool:** every mesh's streams in shared device-local buffers, reached by buffer device address, with a
  table entry per mesh; any number of meshes and instances draw in one pass.
- **Meshes come and go:** `pool.remove(mesh)` frees a mesh's ranges and number for later meshes, first fit and
  deterministic, once no frame in flight can draw it (given the graph's `frameSlots()`). `Materials.remove` and
  `Textures.remove` do the same for material ids and bindless texture slots, so streamed content can be unloaded.
  Classic LOD and ray tracing data per mesh don't follow removals yet.
- **Uploads without waiting:** given the graph's `frameSlots()`, `pool.add` and `Textures.color`/`data` stage their
  data at once and return; the copies (and a texture's mip chain) are recorded first thing in the next frame
  (`FrameUploads`, through `FrameSlots.recordStart`, which the frame graph calls), so what was added draws from the
  next frame on and the CPU never waits for the GPU. The benchmark's rock adds in 0.4 ms instead of 2.6-6.6 ms, its
  49,062-meshlet terrain in 7 ms instead of about 100. Without frame slots, uploads wait as before.
- **Culling on the GPU:** an instance pass tests instance bounds and splits each visible instance into chunks of 64
  meshlets; the chunk count is written straight into an indirect command, so only visible work is launched. Each
  meshlet is tested by the cluster LOD rule (its group's error projected on screen at most the allowed pixels, its
  parent's more), the frustum and its normal cone (whole meshlets facing away).
- **Raster bins, by material:** a material says how it rasterizes in its header (`Material.alphaMask(cutoff)`,
  `doubleSided(true)`; opaque and one-sided by default). The cull pass puts each meshlet in its view's list of its
  material's bin, and each bin draws with a pipeline of its own: ordinary opaque meshlets keep the fastest (back faces
  culled, no discard, early depth), double-sided ones draw without culling (their normal-cone test skipped, back faces
  lit as the front), masked ones without culling and with a fragment variant discarding masked texels and one-sided
  back faces. Every pass rasterizing them uses the same variants, so shadow maps, depth and the visibility buffer
  mask what the camera does; classic LOD draws through the same bins. A view's extra bins have room for a quarter of
  its opaque bin by default (`ViewOptions.withBins`); `RenderView.visibleMeshlets(bin)` counts each. Not yet followed
  by ray tracing: its shadow rays see masked surfaces as opaque.
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
  view the techniques draw (the scene's camera, `ShadowMapShadows`' sun, the cascades) into one culling pass.

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
tangent-space normal map, occlusion and emission, each a factor times an optional texture.

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
  the type. Material 0 is metallic-roughness's defaults, so meshes draw before anything is set up. Instances name a
  first material and each submesh of the mesh takes the next.
- **Features are the engine's**, applied on any type allowing them: a clear coat (glTF's `KHR_materials_clearcoat`)
  is laid over the type's surface where the material has a `ClearcoatParameters` section. The visibility buffer sorts
  the screen's 8x8 tiles into a list per type and feature combination they show and shades each with one indirect
  dispatch of its own variant (a specialization constant), holding only its features' code: a plain material never
  pays for a coat. Forward shading branches on the material's features; it draws types other than metallic-roughness
  as its defaults for now.
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
  map with its filter, a mask, an inline ray). A spot light uses one; a directional light one per cascade, chosen by
  view depth; a point light six cube faces, chosen by direction. See "Shadows per light" below.
- **Fixed slots per cluster** (128 by default): no atomics, a deterministic order, and overflow reported (the most
  lights any cluster wanted is in the header).
- Tested against the same cluster boxes computed on the CPU.

### Geometry and shading

Drawing and shading are separate, and each is swappable:

- **Geometry providers** draw one kind of geometry; skinned meshes and particles come later. A `SceneGeometry` holds
  the providers of a frame, all drawing its renderer's views.
  - `MeshletGeometry`: a renderer's meshlets, with cluster LOD.
  - `ClassicLodGeometry`: the same instances drawn the classic way.
    - It takes discrete LODs (`MeshBuilder.buildDiscrete`) or flat meshes, added with `add(mesh, data)`.
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
    .add(new ShadowMapShadows(geometry, lights, 0, 0, sunCamera, 2048,
        geometry.createView(ViewOptions.shadow(1 << 18, 1 << 22)), bias))
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

Next (#46): light binning, shadows per light, buckets, transparency and post effects as swappable techniques.


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
- **Top level on the GPU, every frame:** a compute pass turns the renderer's instances into acceleration structure
  instances, and the top level is rebuilt from them. Nothing per instance is written on the CPU, and recording
  allocates nothing.
- **Shaders** import `raytracing.slang` for `occluded(scene, origin, direction, tMin, tMax)`, a ray query that stops at
  the first hit.

### Shadows per light

Every light chooses its own shadow technique, and lights of one frame may mix them. A light names consecutive entries
of the shadow table; each entry has a **kind**, evaluated in shading by an implementation of `lights.slang`'s `IShadow`
interface:

| Kind | Technique | Evaluated by |
|---|---|---|
| shadow map (filter: PCF; PCSS, VSM, ESM fit the same slot) | `ShadowMapShadows`: the scene's depth from the light's camera, a `SceneDraw` of its own view, culled with the camera | `PcfShadow`: 3 x 3 percentage-closer filtering |
| shadow maps, cascaded (directional lights) | `CascadedShadowMaps`: practical splits, each cascade fitted to its slice's sphere and snapped to world texels (stable), a `SceneDraw` per cascade | `PcfShadow`, the cascade chosen by view depth, looked up from a normal offset of about a texel |
| screen-space mask | `RayTracedShadows`: a ray query per pixel of the camera's depth, on the async queue | `MaskShadow`: the mask's texel |
| inline ray | `InlineRayShadows`: nothing but the frame's top level | `RayShadow`: a ray query while shading |

- **Shadow techniques modify `Keys.LIGHT_LIST`**, so they go in the shading's view and run whenever shading requires
  the light list. They put what shading must read into the view's shared `ShadowInputs` (images to sample, buffers to
  wait for) with a setup that fills their lights' entries when the frame records. A new technique is one class; shading needs no change.
- **Each view has a shadow table of its own** (`lights.shadowTable(view)`), with the same entry numbers: a light names
  its entries once, and each view's techniques fill them in its table, which its light list points shading at. So each
  view's cascades fit its own camera (`new CascadedShadowMaps(geometry, lights, light, entry, sceneView, ...)`, one per
  view through `addPerView`, each `update()`d every frame), and a ray-traced mask is of its view's pixels. An entry no
  technique of the view filled reads as lit. A view's table and its cascades' views go with the view, freed once no
  frame in flight reads them.
- **Ray tracing is a generic parameter** (`IShadowRays`): shading pipelines that cannot trace use `NoRays`, so only the
  visibility buffer's traced variant (`shadeTraced.slang`) needs ray queries. Forward shading counts inline ray
  shadows as lit; a ray-traced mask works with any shading.
- **One top level per frame** (`TopLevel`, a shared request) serves every ray-traced technique of every view: VR's two
  eyes, a mirror, a second player. It is built relative to a world position, `rayTracing.origin(x, y, z)` (0, 0, 0 at
  first), not to a camera: each view moves its rays by its camera less the origin. Its transforms are floats, so the
  app keeps the origin within a few kilometres of the cameras, moving it when they have gone far (at 7,000 km floats
  are 0.5 m apart). Rendering stays relative to each camera; the top level's records are only for its build.
  Its build is skipped while no instance changed (`GpuScene.version()`), no bottom level was added and the origin
  stayed: a still scene builds it once.
- A mask needs the camera's depth before shading: for the visibility buffer that is its own IDs' draw (shared); for
  forward shading, a depth-only draw of the camera as a view of its own, for now.

30,000 rocks on the terrain, 1920 x 1080, the sun's shadows over a 600-unit square, GPU time of the whole frame:

| | Forward | Visibility buffer |
|---|---|---|
| the sun, unshadowed | 1.32 ms | 0.89 ms |
| shadow map 2048 x 2048 | 1.76 ms | 1.32 ms |
| shadow map 4096 x 4096 | 2.23 ms | 1.81 ms |
| 3 cascades of 2048 x 2048 to 300 units | 2.14 ms | 1.70 ms |
| ray-traced mask | 1.97 ms (with its own depth draw) | 1.17 ms |
| inline rays | (not traced) | 1.15 ms |

The scene stands still, so the top level is built once and stands (it was 0.31 ms a frame rebuilt; the ray rows were
1.34 and 1.31 ms then). With the visibility buffer, exact ray-traced shadows cost about 0.22 ms, half what a 2048 x 2048
shadow map does. Ray
tracing gives crisp contact shadows, thin details, and no acne or peter-panning at any distance. Even at 2048 x 2048
over a 150-unit square, the shadow map shows texel steps along shadow edges and acne where the sun grazes. The shadow
map needs no ray tracing and no memory beyond itself; ray tracing needs bottom levels (103 MiB here, mostly the
terrain's 2.1 M triangles at full detail).

`Screenshots.renderShadows` renders the sun's shadows through the visibility buffer by one map, by three cascades and
by inline rays (`shadows-mapped.png`, `shadows-cascaded.png`, `shadows-traced.png`), a close-up of the nearest rocks
side by side, and where rays and the single map differ. Cascades put the detail near the camera: there they come close
to the traced shadows, where one map over the whole area shows texel steps.

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
  windows are left undefined, and its histories keep what the last frame it ran wrote. Shared work that serves several
  views leaves out a skipped one's part by asking `g.running()` at record time: the renderer's culling culls only the
  views that run.
- **Textures:** `graph.texture(name, format, w, h)` is an image the graph keeps from frame to frame, resting readable
  by shaders, with a bindless slot that never changes (`texture.slot()`). A view renders into it with
  `view.output(key, texture)` (a mirror, a security camera's screen), and a frame the view is skipped leaves it as it
  was. A technique of another view reads it with `g.texture(texture)`, which orders its pass after the copy into it,
  and samples it at its slot (`ctx.sampled(image)`). It belongs to the graph, not to the view: rendered once and its
  view turned off or removed, it keeps its image. `graph.removeTexture(texture)` frees it once no frame in flight
  samples it; otherwise it closes with the graph.
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
  key run in their `order()`: at an `Anchor` (`OPAQUE`, `TRANSPARENT`, `POST`, `TONE_MAP`, `UI`), then after or
  before other techniques by `id()` (ids of techniques not in the graph are ignored), else in the order added. So a
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
`invalidate()`. Per-frame data lives in the techniques, and pass bodies read it when recording.

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

`engine-scene` draws scene's `Renderables` (a mesh and per-submesh materials on a node, from `scene-render`) with the
renderers above, following the scene's change logs and asset cache:

- `SceneResources` is the GPU side of the assets, shared by every scene on a device: meshes in a `GeometryPool`,
  materials in `Materials`, textures in `Textures`. It acquires what scenes use in the `AssetCache`, uploads each asset
  once it loads, however many scenes and nodes use it, and removes it from the GPU (once no frame in flight reads it)
  and releases it in the cache when nothing does. Materials draw as the default until they load, untextured where
  their textures have not loaded (or failed to); a renderable's materials take a block of ids, one per submesh, shared
  by every renderable with the same materials and mesh layout.
- `SceneSync` keeps a scene on the GPU: its renderables as instances of a `GpuScene` at their world positions,
  written once and again only where nodes moved or renderables changed. Given a `JobSystem`, many instances at once
  (most nodes moved) are claimed in order and written on its threads: 30,000 moving rocks in 0.5 ms on 4 threads
  against 1.0 ms on one, the same ids and image either way. Drawing is the renderers' business: cameras
  are `SceneView`s (a `RenderView` in a graph view), each at its own world position (moving one rewrites no
  instance), drawn by their views' techniques, so the rendering mode is the graph's: `ClusteredForwardShading`,
  `VisibilityBufferShading`, shadows, swapped with `port.replace`.

```java
FrameSlots slots = new FrameSlots(2);                 // shared by every graph drawing these resources
SceneResources resources = new SceneResources(assets, new GeometryPool(device, capacity, slots),
    new Materials(device, 4096, slots), new Textures(device, slots));

GpuScene worldGpu = new GpuScene(device, resources.pool(), 100_000, slots);
SceneSync world = new SceneSync(resources, worldRenderables, worldGpu);
SceneSync gizmos = new SceneSync(resources, gizmoRenderables, worldGpu); // same table: its nodes on layer 1
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
scene.update(); worldRenderables.update();
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
followed yet: lights and cameras from the scene. Removed meshes are not yet followed by
`ClassicLodGeometry` and ray tracing, so draw views with meshlets.

## Building

Needs Java 27 and a Vulkan 1.3 driver (1.4 is used when present); the validation layers (Vulkan SDK) are
optional locally. Tests open hidden windows. Without a display, set `SDL_VIDEO_DRIVER=offscreen`.

CI does that on Mesa's lavapipe through build-logic's `setup-vulkan` action. The action pins and caches the Vulkan
packages and sets `TRUESIM_REQUIRE_VALIDATION=true`, so the Vulkan tests fail when the validation layer is not active.

Exclusive fullscreen is only tested when `TRUESIM_TEST_FULLSCREEN` is set, because it changes the monitor's mode.

`VulkanInstance.bestGpu()` picks the most capable GPU (discrete first, then the most memory). `TRUESIM_GPU` (or the
system property `truesim.gpu`) chooses another, for the tests too: a type (`discrete`, `integrated`, `virtual`,
`cpu`), an index in `gpus()` or part of a GPU's name, e.g. `TRUESIM_GPU=integrated ./gradlew test`.

A lost device's `DeviceLostException` says what the driver saw, where the GPU has `VK_EXT_device_fault`: the kind of
fault, its address and the buffer that holds it. To have the validation layer check shader reads and writes as they
run, build the instance with `gpuAssistedValidation(true)` (much slower).

Mistakes made here once, each with its evidence and the rule that follows, are in [docs/pitfalls.md](docs/pitfalls.md);
add to it what is verified, nothing else.

```bash
./gradlew build
```

Sibling checkouts (`../build-logic`, `../common-math`, `../common-jobs`, `../scene`) are used automatically when
present (override with `-Ptruesim.<repo>=<path>`); otherwise they come from GitHub Packages.
