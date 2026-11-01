package com.truesim.engine.graph;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.app.Frame;
import com.truesim.engine.app.FrameDriver;
import com.truesim.engine.app.Plugin;
import com.truesim.engine.app.PluginKey;
import com.truesim.engine.app.Stage;
import com.truesim.engine.app.Startup;
import com.truesim.engine.app.StartupFailure;
import com.truesim.engine.vulkan.Gpu;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.engine.vulkan.VulkanInstance;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Vulkan rendering as a plugin: the instance, the device, the frame graph reading the engine's settings, and the
 * presenter showing views in windows, which paces frames unless another plugin drives them. Other plugins shape it
 * while it starts, in the stage before it acts: instance extensions by {@link Stage#BEFORE_INSTANCE} (the instance is
 * made in {@link Stage#INSTANCE}), the GPU and device extensions by {@link Stage#INSTANCE} (the GPU is picked in
 * {@link Stage#GPU}, the device made in {@link Stage#DEVICE}, with the graph and the presenter); views and techniques
 * go in from {@link Stage#GRAPH}.
 *
 * <pre>{@code
 * app.use(new Rendering().validation(true));
 * FrameGraph graph = engine.get(Rendering.KEY).graph();
 * }</pre>
 */
public final class Rendering implements Plugin {

    /** Its key. */
    public static final PluginKey<Rendering> KEY = PluginKey.of("rendering", Rendering.class);

    private final Set<String> instanceExtensions = new LinkedHashSet<>();
    private final Set<String> deviceExtensions = new LinkedHashSet<>();
    private Function<VulkanInstance, Gpu> gpuChoice;
    private boolean validation;
    private boolean headless;
    private int framesInFlight = 2;
    private VulkanInstance instance;
    private Gpu gpu;
    private VulkanDevice device;
    private FrameGraph graph;
    private Presenter presenter;

    @Override
    public PluginKey<Rendering> key() {
        return KEY;
    }

    /** Vulkan's validation layers on or off (off by default): for development. Before startup. */
    public Rendering validation(boolean on) {
        validation = on;
        return this;
    }

    /**
     * Without windows: no surfaces, no swapchains, no platform needed (servers rendering offscreen, tests). Before
     * startup.
     */
    public Rendering headless(boolean on) {
        headless = on;
        return this;
    }

    /** Frames the CPU records ahead of the GPU (2 by default). Before startup. */
    public Rendering framesInFlight(int frames) {
        if (frames < 1) {
            throw new IllegalArgumentException("At least one frame in flight: " + frames);
        }
        framesInFlight = frames;
        return this;
    }

    /** Adds instance extensions a plugin needs; by {@link Stage#BEFORE_INSTANCE}. */
    @AllowAllocation("starting up")
    public Rendering requestInstanceExtensions(Collection<String> names) {
        checkBefore(instance, "the instance is made");
        instanceExtensions.addAll(names);
        return this;
    }

    /** Adds device extensions a plugin needs; by {@link Stage#GPU}. */
    @AllowAllocation("starting up")
    public Rendering requestDeviceExtensions(Collection<String> names) {
        checkBefore(device, "the device is made");
        deviceExtensions.addAll(names);
        return this;
    }

    /**
     * Picks the GPU with {@code choice} instead of the best one (a headset's); one plugin may; by
     * {@link Stage#INSTANCE}.
     */
    public Rendering chooseGpu(Function<VulkanInstance, Gpu> choice) {
        checkBefore(gpu, "the GPU is picked");
        if (gpuChoice != null) {
            throw new IllegalStateException("A plugin chose the GPU already");
        }
        gpuChoice = Objects.requireNonNull(choice, "choice");
        return this;
    }

    private static void checkBefore(Object made, String what) {
        if (made != null) {
            throw new IllegalStateException("Too late: " + what + " already");
        }
    }

    @Override
    @AllowAllocation("starting up")
    public void start(Stage stage, Startup startup) {
        try {
            switch (stage) {
                case INSTANCE -> instance = VulkanInstance.builder().applicationName(startup.engine().name())
                    .validation(validation).surfaces(!headless).extensions(instanceExtensions).build();
                case GPU -> gpu = gpuChoice != null ? gpuChoice.apply(instance) : instance.bestGpu();
                case DEVICE -> {
                    device = headless && deviceExtensions.isEmpty() ? VulkanDevice.createHeadless(gpu)
                        : VulkanDevice.create(gpu, deviceExtensions);
                    graph = new FrameGraph(device, framesInFlight).settings(startup.settings());
                    presenter = new Presenter(graph, instance);
                    startup.defaultFrameDriver(new Presenting());
                }
                default -> {
                    // the other stages are for the plugins building on it
                }
            }
        } catch (StartupFailure e) {
            throw e;
        } catch (RuntimeException e) {
            throw new StartupFailure("Rendering could not start in " + stage + ": " + e.getMessage(), e);
        }
    }

    /** The Vulkan instance; from {@link Stage#INSTANCE}. */
    public VulkanInstance instance() {
        return made(instance, Stage.INSTANCE);
    }

    /** The device; from {@link Stage#DEVICE}. */
    public VulkanDevice device() {
        return made(device, Stage.DEVICE);
    }

    /** The frame graph, reading the engine's settings; from {@link Stage#DEVICE}. */
    public FrameGraph graph() {
        return made(graph, Stage.DEVICE);
    }

    /** The presenter, showing views in windows; from {@link Stage#DEVICE}. */
    public Presenter presenter() {
        return made(presenter, Stage.DEVICE);
    }

    private static <T> T made(T part, Stage stage) {
        if (part == null) {
            throw new IllegalStateException("Rendering makes it in " + stage + ": not yet");
        }
        return part;
    }

    /** Waits for the GPU, then frees the presenter, the graph, the device and the instance. */
    @Override
    public void stop() {
        if (device != null) {
            device.waitIdle();
        }
        if (presenter != null) {
            presenter.close();
            presenter = null;
        }
        if (graph != null) {
            graph.close();
            graph = null;
        }
        if (device != null) {
            device.close();
            device = null;
        }
        if (instance != null) {
            instance.close();
            instance = null;
        }
    }

    /** The default frame driver: renders the graph and presents its windows at the end of the frame. */
    private final class Presenting implements FrameDriver {
        @Override
        public void beginFrame(Frame frame) {
            // presenting waits for the swapchains at the end of the frame
        }

        @Override
        public void endFrame(Frame frame) {
            presenter.render();
        }
    }
}
