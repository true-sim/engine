# engine

The true-sim engine: windows, input and Vulkan rendering on [LWJGL](https://www.lwjgl.org/) 3.4 with SDL3.
Multi-window, multi-GPU and multi-threaded from the start; per-frame paths allocate nothing.

Public scene types (meshes, materials, geometries, scene management) live in a separate repository, so mods
and tools can use them without the engine. Shaders are written in [Slang](https://shader-slang.org/).

| Module            | Package                       | What                                                    |
|-------------------|-------------------------------|---------------------------------------------------------|
| `engine-platform` | `com.truesim.engine.platform` | SDL3: displays, windows, runtime mode changes, raw input |
| `engine-vulkan`   | `com.truesim.engine.vulkan`   | Vulkan 1.3, 1.4 where available: instance, GPUs, devices, swapchains, frames |

## Platform

One `Platform` per process, used from the thread that started it. Events go to a `PlatformListener`, which
gets every one as primitives, with nothing allocated. The input module builds on this.

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
        platform.pollEvents(listener);          // windowResized, key, mouseMoved, gamepadAxis, deviceAdded, ...
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

## Building

Needs Java 27 and a Vulkan 1.3 driver (1.4 is used when present); the validation layers (Vulkan SDK) are optional. Tests open hidden
windows. Without a display, set `SDL_VIDEO_DRIVER=offscreen`, as CI does with Mesa's lavapipe. Exclusive
fullscreen is only tested when `TRUESIM_TEST_FULLSCREEN` is set, because it changes the monitor's mode.

```bash
./gradlew build
```

To develop against a local build-logic checkout, add `-Ptruesim.build-logic=../build-logic`.
