# engine

The true-sim engine: windows, input and Vulkan rendering on [LWJGL](https://www.lwjgl.org/) 3.4 with SDL3.
Multi-window, multi-GPU and multi-threaded from the start; per-frame paths allocate nothing.

Public scene types (meshes, materials, geometries, scene management) live in a separate repository, so mods
and tools can use them without the engine. Shaders are written in [Slang](https://shader-slang.org/).

| Module            | Package                       | What                                                    |
|-------------------|-------------------------------|---------------------------------------------------------|
| `engine-platform` | `com.truesim.engine.platform` | SDL3: displays, windows, runtime mode changes, all input devices |
| `engine-vulkan`   | `com.truesim.engine.vulkan`   | Vulkan 1.3, 1.4 where available: instance, GPUs, devices, swapchains, frames |
| `engine-render`   | `com.truesim.engine.render`   | GPU-driven meshlet rendering of scene's meshes: geometry pool, GPU culling and LOD |

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
a pinned compiler. Data reaches shaders through buffer device addresses passed in push constants: no descriptor
sets, no vertex input; shaders pull what they need.

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
- **Two paths, one set of shaders:** with mesh shaders (`VK_EXT_mesh_shader`, enabled where the GPU has it), a task
  workgroup per chunk culls and launches a mesh workgroup per visible meshlet, which emits its vertices and triangles
  straight from the pool. Without, a compute pass per chunk writes indexed indirect draws, drawn with one
  `vkCmdDrawIndexedIndirectCount`. The meshlet test, vertex decoding and shading are one Slang module
  (`meshlets/common.slang`) both paths import, and they draw the same image (tested). `Path.AUTO` picks mesh shaders
  where available.
- **Instance-level LOD range:** before culling meshlets, the instance pass skips every level finer than the first one
  whose error, seen from the instance's nearest point, is within the allowed pixels: the rule could not pick anything
  there. A far instance costs a chunk or two, however detailed its mesh.

Measured with `MeshletBenchmark` (`TRUESIM_BENCHMARK=true`) on an AMD Radeon RX 9070 XT at 1920 x 1080 with 1 pixel
of error allowed, GPU time of the whole frame (instance pass, culling, drawing):

| Scene | Source triangles | Mesh shaders | Indexed | Meshlets drawn |
|---|---|---|---|---|
| 1,000 rocks (65k-triangle mesh, cluster hierarchy) | 65.5 M | 0.11 ms | 0.75 ms | 19,681 |
| 3,000 rocks | 197 M | 0.26 ms | 2.07 ms | 57,289 |
| 10,000 rocks | 655 M | 0.75 ms | 6.86 ms | 190,813 |
| 30,000 rocks | 1.97 billion | 2.12 ms | 20.6 ms | 576,127 |
| terrain at a grazing angle | 2.1 M | 0.07 ms | 0.10 ms | 723 |

The indexed path issues one draw per visible meshlet, which dominates at these counts; compacting visible meshlets
into one draw is #12.

- **Reverse depth with an infinite far plane,** camera-relative instances: precise near and far, anywhere in a large
  world.
- Tested on the GPU: coverage of a quad, culling (behind, outside, facing away), LOD cuts from full detail to a few
  meshlets that keep the covered area within 2 % (no holes), and both paths drawing the same image.

The frame graph (#8) builds on this.

## Building

Needs Java 27 and a Vulkan 1.3 driver (1.4 is used when present); the validation layers (Vulkan SDK) are
optional locally. Tests open hidden windows. Without a display, set `SDL_VIDEO_DRIVER=offscreen`.

CI does that on Mesa's lavapipe through build-logic's `setup-vulkan` action. The action pins and caches the Vulkan
packages and sets `TRUESIM_REQUIRE_VALIDATION=true`, so the Vulkan tests fail when the validation layer is not active.

Exclusive fullscreen is only tested when `TRUESIM_TEST_FULLSCREEN` is set, because it changes the monitor's mode.

```bash
./gradlew build
```

To develop against a local build-logic checkout, add `-Ptruesim.build-logic=../build-logic`.
