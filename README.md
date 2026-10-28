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

### Shaders and pipelines

Shaders are Slang under `src/main/slang`, compiled to SPIR-V resources by build-logic's `truesim.slang` plugin with
a pinned compiler. Buffers reach shaders through device addresses passed in push constants, images through slots of
the device's one bindless descriptor set (`Bindless`: sampled images, samplers, storage images). Every pipeline shares
one layout, so the set is bound once per command buffer. There is no vertex input: shaders pull what they need.

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

Devices require what such shaders use: 64-bit integers for pointers, 8 and 16-bit storage for packed data (meshlet
indices, quantized vertices) and draw parameters, on top of the Vulkan 1.3 baseline.

## GPU-driven rendering

`engine-render` draws scene's meshes (`MeshData`: meshlets over quantized vertices with cluster LOD) GPU-driven: the
CPU writes instances and a view, the GPU decides what to draw.

```java
try (GeometryPool pool = new GeometryPool(device, GeometryPool.Capacity.of(1 << 22, 65_536, 1024));
     MeshletRenderer renderer = new MeshletRenderer(device, pool, new MeshletRenderer.Limits(100_000, 1 << 20, 65_535),
         colorFormat, depthFormat)) {
    int car = pool.add(carMesh);                                   // uploaded once
    renderer.setInstance(0, car, x, y, z, qx, qy, qz, qw, 1, 1, 1); // camera-relative
    renderer.setInstanceCount(1);
    renderer.setView(camera.set(forward, up, fovY, width, height, near), 1.0f, MeshletRenderer.MODE_LIT);
    renderer.record(commands, colorView, depthView, width, height, 0, 0, 0);
}
```

- **Geometry pool:** every mesh's streams in shared device-local buffers, reached by buffer device address, with a
  table entry per mesh; any number of meshes and instances draw in one pass.
- **Culling on the GPU:** an instance pass tests instance bounds and splits each visible instance into chunks of 64
  meshlets; the chunk count is written straight into an indirect command, so only visible work is launched. Each
  meshlet is tested by the cluster LOD rule (its group's error projected on screen at most the allowed pixels, its
  parent's more), the frustum and its normal cone (whole meshlets facing away).
- **Two paths, one set of shaders:** a compute pass culls each chunk and lists the visible meshlets. With mesh shaders
  (`VK_EXT_mesh_shader`, enabled where the GPU has it), a mesh workgroup per listed meshlet emits its vertices and
  triangles straight from the pool. Without, an expansion pass writes their triangles into one index buffer, and one
  `vkCmdDrawIndexedIndirect` draws them. The meshlet test, vertex decoding and shading are one Slang module
  (`meshlets/common.slang`) both paths import, and they draw the same image (tested). `Path.AUTO` picks mesh shaders
  where available. Culling in task shaders instead, a workgroup per chunk, left most lanes idle when instances need
  only a few meshlets: 0.95 ms against 0.59 ms for 30,000 rocks.
- **Instance-level LOD range:** before culling meshlets, the instance pass skips every level finer than the first one
  whose error, seen from the instance's nearest point, is within the allowed pixels: the rule could not pick anything
  there. A far instance costs a chunk or two, however detailed its mesh.
- **Views culled together:** up to four views (the camera, a shadow map's sun, cascades) each have their own lists
  (`Limits.views`). `cullViews` culls several at once: one instance pass with a row per view, and one cull pass over
  all their chunks, each meshlet into its view's list; `drawView` then draws each. Through the frame graph, the
  `MeshletCulling` request gathers every view the techniques draw (the scene's camera, `ShadowMapShadows`' sun) into
  one culling pass.

Measured with `MeshletBenchmark` (`TRUESIM_BENCHMARK=true`) on an AMD Radeon RX 9070 XT at 1920 x 1080 with 1 pixel
of error allowed, GPU time of the whole frame (instance pass, culling, drawing), median per frame of batches of 32
frames recorded back to back (the paths take turns, so they see the same clocks):

| Scene | Source triangles | Mesh shaders | Indexed | No meshlets: full detail | No meshlets: discrete LODs, CPU culling |
|---|---|---|---|---|---|
| 1,000 rocks (82k-triangle mesh) | 81.9 M | 0.065 ms | 0.071 ms | 3.49 ms | 0.11 ms (+0.20 ms CPU) |
| 3,000 rocks | 246 M | 0.076 ms | 0.080 ms | 10.2 ms | 0.30 ms (+0.36 ms CPU) |
| 10,000 rocks | 819 M | 0.19 ms | 0.19 ms | 33.8 ms | 0.94 ms (+0.15 ms CPU) |
| 30,000 rocks | 2.46 billion | 0.59 ms | 0.61 ms | 102 ms | 2.80 ms (+0.47 ms CPU) |
| terrain at a grazing angle | 2.1 M | 0.081 ms | 0.084 ms | 0.17 ms | (one mesh: full detail) |

Small scenes depend on how the GPU clocks for short frames: from one session to the next, the 3,000 rocks and the
terrain measured 0.08 to 0.14 ms on the same code. Comparisons are made within a session.

**Occlusion culling.** Through the frame graph, `SceneDraw` draws the camera in two phases around a `DepthPyramid`. The early
phase draws the instances visible last frame (a bit per instance, kept on the GPU). The late phase tests every
instance in view against the pyramid of that depth, and the meshlets of those newly visible too. It draws what came
into view in the same frame, so nothing pops in. Hidden objects cost only their instance test. The pyramid test is
conservative: a sphere's box is projected, and its nearest depth is compared at the level where the box covers at most
2 x 2 texels, mapped by what each texel really covers. 30,000 rocks, in the same session:

| Scene | Occlusion culling off | On |
|---|---|---|
| in the open (rocks hide rocks) | 0.59 ms, 99,052 meshlets | 0.51 ms, 69,507 meshlets |
| behind a wall (the terrain, upright, 80 units ahead) | 0.64 ms, 99,239 meshlets | 0.17 ms, 2,984 meshlets |

`TRUESIM_SCREENSHOTS=true ./gradlew :engine-render:test --tests '*Screenshots'` renders these scenes to PNGs under
`render/build/screenshots`, shaded and colored per meshlet.

The indexed path draws everything with one draw: culling lists the visible meshlets, an expansion pass writes their
triangles into the frame's index buffer (each index is the meshlet's slot and local vertex), and one
`vkCmdDrawIndexedIndirect` draws them. The baselines without meshlets draw whole meshes, one indirect draw per instance:
at full detail, or as a classic engine does, picking a discrete LOD per instance and culling on the CPU. At the same
error the cluster hierarchy draws fewer triangles than discrete LODs (30,000 rocks: 99,052 meshlets, at most 12.3 M
triangles, against 17.6 M), since each cluster takes the detail its own distance needs. Both builds sum their
simplification errors, which stay close to the real deviation from full detail.

- **Reverse depth with an infinite far plane,** camera-relative instances: precise near and far, anywhere in a large
  world.
- Tested on the GPU: coverage of a quad, culling (behind, outside, facing away), LOD cuts from full detail to a few
  meshlets that keep the covered area within 2 % (no holes), and both paths drawing the same image.


**Memory.** The pool keeps what meshlets need and nothing more: quantized vertices, meshlet descriptors and their
triangles as a byte per local index. An 82k-triangle rock with its whole cluster LOD hierarchy takes 1.50 MiB. A
classic renderer would add a 32-bit index buffer per mesh and per discrete LOD; the pool has none.

### Materials and textures

Surfaces are physically based, metallic-roughness as in glTF: a base color, metalness, roughness, a tangent-space
normal map, occlusion and emission, each a factor times an optional texture.

```java
try (Textures textures = new Textures(device); Materials materials = new Materials(device, 1024)) {
    int albedo = textures.color(512, 512, rgba);       // sRGB, full mip chain made on the GPU
    int normals = textures.data(512, 512, xyz);         // linear
    int rock = materials.add(new Material().baseColorTexture(albedo).normalTexture(normals, 1).roughness(0.9f));
    renderer.setMaterials(materials);
    renderer.setInstance(0, rockMesh, rock, x, y, z, qx, qy, qz, qw, 1, 1, 1); // submesh s takes material rock + s
}
```

- **Textures** live in the bindless set and are sampled by slot, trilinear and anisotropic (`Bindless.MATERIAL`, up to
  16x where the GPU allows).
- **Materials** are a table of 64-byte records the shaders read by id. Material 0 is a light gray default, so meshes
  draw before anything is set up. Instances name a first material and each submesh of the mesh takes the next, so one
  mesh can be drawn with different materials.
- **One shading module** (`shading.slang`): the material evaluation (with explicit texture gradients, so passes
  without hardware derivatives can use it too) and the shading model: Lambert diffuse and a GGX specular lobe with
  height-correlated Smith visibility and Schlick Fresnel. The vertex stage decodes normals, tangents, texture
  coordinates and vertex colors from the pool's attributes.


### Lights and light lists

`Lights` holds point, spot and directional lights, camera-relative like instances, and a shadow table. A **light
binning** technique turns them into the frame's light list, `Keys.LIGHT_LIST` (a buffer). Shading techniques use it
if the graph has one, and otherwise light by the sun; `graph.replace` swaps one binning for another.

`ClusteredLights` is the first binning: the camera's frustum is cut into tiles of 64 pixels and 24 exponential depth
slices, and a compute pass on the async queue gives each cluster the lights whose sphere reaches its box (lights read
in batches of 64 into shared memory; directional lights reach every cluster).

```java
lights.directional(0, -0.4f, -0.8f, -0.3f, 3, 3, 3).shadows(0, 0, 4); // the sun, four cascades: entries 0 to 3
lights.point(1, x, y, z, 10, 20, 15, 10);                              // range 10
lights.setCount(2);
graph.add(new ClusteredLights(lights, camera, width, height));       // provides Keys.LIGHT_LIST
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

- **Geometry providers** draw one kind of geometry: `MeshletGeometry` (a renderer's meshlets) today; classic LOD,
  skinned meshes and particles later. A `SceneGeometry` holds the providers of a frame and sets their views.
- **`SceneDraw`**, a shared request, draws every provider from one view into the targets a `SurfaceOutput` asks for:
  depth only, shaded color, or visibility buffer IDs. It does two-phase occlusion culling for the camera. Shading
  techniques ask for the camera's draw and shadow techniques for the light's; equal requests are drawn once, and the
  renderer culls all its views in one pass.
- **Shading techniques** provide `Keys.SCENE_COLOR` and `Keys.SCENE_DEPTH`; `graph.replace` swaps them:

```java
SceneGeometry geometry = SceneGeometry.meshlets(renderer);
geometry.setView(0, camera, 1, MeshletRenderer.MODE_LIT);
graph.add(new ClusteredForwardShading(geometry, width, height, colorFormat, depthFormat))
    .add(new ClusteredLights(lights, camera, width, height));
graph.replace(new VisibilityBufferShading(geometry, width, height, colorFormat, depthFormat));
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

- **Shadow techniques modify `Keys.LIGHT_LIST`**, so they run whenever shading requires the light list. They put what
  shading must read into the shared `ShadowInputs` (images to sample, buffers to wait for) with a setup that fills
  their lights' entries when the frame records. A new technique is one class; shading needs no change.
- **Ray tracing is a generic parameter** (`IShadowRays`): shading pipelines that cannot trace use `NoRays`, so only the
  visibility buffer's traced variant (`shadeTraced.slang`) needs ray queries. Forward shading counts inline ray
  shadows as lit; a ray-traced mask works with any shading.
- **One top level per frame** (`TopLevel`, a shared request) serves every ray-traced technique.
- A mask needs the camera's depth before shading: for the visibility buffer that is its own IDs' draw (shared); for
  forward shading, a depth-only draw of the camera as a view of its own, for now.

30,000 rocks on the terrain, 1920 x 1080, the sun's shadows over a 600-unit square, GPU time of the whole frame:

| | Forward | Visibility buffer |
|---|---|---|
| the sun, unshadowed | 1.32 ms | 0.89 ms |
| shadow map 2048 x 2048 | 1.76 ms | 1.32 ms |
| shadow map 4096 x 4096 | 2.23 ms | 1.81 ms |
| 3 cascades of 2048 x 2048 to 300 units | 2.14 ms | 1.70 ms |
| ray-traced mask | 2.26 ms (with its own depth draw) | 1.34 ms |
| inline rays | (not traced) | 1.31 ms |

With the visibility buffer, exact ray-traced shadows cost what a 2048 x 2048 shadow map does: about 0.42 ms. Ray
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
- **Keys name buffers too:** `g.provide(key, buffer)` and `g.requireBuffer(key)`, e.g. a light list.
- **Modifiers:** a technique listing a key in `modifies()` takes it after its provider with `g.modify(key)` and
  writes it in place (transparency over the scene, decals) or provides a new version (a post effect). Modifiers of a
  key run in `stage()` order, so a chain such as exposure, bloom, tone mapping needs no rewiring when an effect is
  added or removed; whoever requires the key gets its last version. A modifier nothing needs is never built.
- **History:** `g.history(name, width, height, format)` keeps an image from frame to frame: this frame writes
  `current()`, and `previous()` holds what the frame before wrote (`ctx.historyValid(history)` says whether there
  was one). The graph swaps the two images' roles every frame and carries their state across the frame boundary.

```java
graph.add(new ClusteredForwardShading(geometry, width, height, VK_FORMAT_R16G16B16A16_SFLOAT, VK_FORMAT_D32_SFLOAT))
    .add(new Bloom())                       // requires Keys.SCENE_COLOR, provides BLOOM
    .add(new ToneMap())                     // requires BLOOM, provides TONEMAPPED
    .output(TONEMAPPED, swapchainImage);    // an ExternalImage, set to the acquired image each frame
graph.beginFrame(); graph.submit(acquired, renderFinished);   // per frame: nothing allocated

final class Bloom implements Technique {
    public List<Key> provides() { return List.of(BLOOM); }

    public void build(GraphBuilder g) {
        Image scene = g.require(Keys.SCENE_COLOR);
        Image soft = g.request(new Blur(scene, 8, 0.5));    // shared with any equal request
        Image out = g.createLike("bloom", scene, 1);
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
  threads. Pass bodies then run on worker threads and must not share what they change.
- `record(commands)` still records everything into one command buffer, for simple uses.

The graph's tests run with synchronization validation, which checks every barrier and cross-queue wait. Dropping the
waits makes it report races between the queues.

## Building

Needs Java 27 and a Vulkan 1.3 driver (1.4 is used when present); the validation layers (Vulkan SDK) are
optional locally. Tests open hidden windows. Without a display, set `SDL_VIDEO_DRIVER=offscreen`.

CI does that on Mesa's lavapipe through build-logic's `setup-vulkan` action. The action pins and caches the Vulkan
packages and sets `TRUESIM_REQUIRE_VALIDATION=true`, so the Vulkan tests fail when the validation layer is not active.

Exclusive fullscreen is only tested when `TRUESIM_TEST_FULLSCREEN` is set, because it changes the monitor's mode.

```bash
./gradlew build
```

Sibling checkouts (`../build-logic`, `../common-math`, `../common-jobs`, `../scene`) are used automatically when
present (override with `-Ptruesim.<repo>=<path>`); otherwise they come from GitHub Packages.
