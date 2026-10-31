package com.truesim.engine.xr;

import static org.lwjgl.openxr.KHRVulkanEnable.XR_KHR_VULKAN_ENABLE_EXTENSION_NAME;
import static org.lwjgl.openxr.KHRVulkanEnable.xrGetVulkanDeviceExtensionsKHR;
import static org.lwjgl.openxr.KHRVulkanEnable.xrGetVulkanGraphicsDeviceKHR;
import static org.lwjgl.openxr.KHRVulkanEnable.xrGetVulkanGraphicsRequirementsKHR;
import static org.lwjgl.openxr.KHRVulkanEnable.xrGetVulkanInstanceExtensionsKHR;
import static org.lwjgl.openxr.XR10.XR_MAKE_VERSION;
import static org.lwjgl.openxr.XR10.XR_ENVIRONMENT_BLEND_MODE_OPAQUE;
import static org.lwjgl.openxr.XR10.XR_ERROR_FORM_FACTOR_UNAVAILABLE;
import static org.lwjgl.openxr.XR10.XR_FORM_FACTOR_HEAD_MOUNTED_DISPLAY;
import static org.lwjgl.openxr.XR10.XR_INFINITE_DURATION;
import static org.lwjgl.openxr.XR10.XR_REFERENCE_SPACE_TYPE_LOCAL;
import static org.lwjgl.openxr.XR10.XR_REFERENCE_SPACE_TYPE_STAGE;
import static org.lwjgl.openxr.XR10.XR_SESSION_STATE_EXITING;
import static org.lwjgl.openxr.XR10.XR_SESSION_STATE_LOSS_PENDING;
import static org.lwjgl.openxr.XR10.XR_SESSION_STATE_READY;
import static org.lwjgl.openxr.XR10.XR_SESSION_STATE_STOPPING;
import static org.lwjgl.openxr.XR10.XR_SUCCESS;
import static org.lwjgl.openxr.XR10.XR_SWAPCHAIN_USAGE_COLOR_ATTACHMENT_BIT;
import static org.lwjgl.openxr.XR10.XR_SWAPCHAIN_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.openxr.XR10.XR_TYPE_EVENT_DATA_SESSION_STATE_CHANGED;
import static org.lwjgl.openxr.XR10.XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO;
import static org.lwjgl.openxr.XR10.XR_VIEW_STATE_ORIENTATION_VALID_BIT;
import static org.lwjgl.openxr.XR10.XR_VIEW_STATE_POSITION_VALID_BIT;
import static org.lwjgl.openxr.XR10.xrAcquireSwapchainImage;
import static org.lwjgl.openxr.XR10.xrBeginFrame;
import static org.lwjgl.openxr.XR10.xrBeginSession;
import static org.lwjgl.openxr.XR10.xrCreateInstance;
import static org.lwjgl.openxr.XR10.xrCreateReferenceSpace;
import static org.lwjgl.openxr.XR10.xrCreateSession;
import static org.lwjgl.openxr.XR10.xrCreateSwapchain;
import static org.lwjgl.openxr.XR10.xrDestroyInstance;
import static org.lwjgl.openxr.XR10.xrDestroySession;
import static org.lwjgl.openxr.XR10.xrDestroySpace;
import static org.lwjgl.openxr.XR10.xrDestroySwapchain;
import static org.lwjgl.openxr.XR10.xrEndFrame;
import static org.lwjgl.openxr.XR10.xrEndSession;
import static org.lwjgl.openxr.XR10.xrEnumerateInstanceExtensionProperties;
import static org.lwjgl.openxr.XR10.xrEnumerateReferenceSpaces;
import static org.lwjgl.openxr.XR10.xrEnumerateSwapchainFormats;
import static org.lwjgl.openxr.XR10.xrEnumerateSwapchainImages;
import static org.lwjgl.openxr.XR10.xrEnumerateViewConfigurationViews;
import static org.lwjgl.openxr.XR10.xrGetInstanceProperties;
import static org.lwjgl.openxr.XR10.xrGetSystem;
import static org.lwjgl.openxr.XR10.xrGetSystemProperties;
import static org.lwjgl.openxr.XR10.xrLocateViews;
import static org.lwjgl.openxr.XR10.xrPollEvent;
import static org.lwjgl.openxr.XR10.xrReleaseSwapchainImage;
import static org.lwjgl.openxr.XR10.xrRequestExitSession;
import static org.lwjgl.openxr.XR10.xrWaitFrame;
import static org.lwjgl.openxr.XR10.xrWaitSwapchainImage;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_B8G8R8A8_SRGB;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_SRGB;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_UNDEFINED;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.vkDestroyImageView;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.graph.ExternalImage;
import com.truesim.engine.graph.FrameGraph;
import com.truesim.engine.graph.Image;
import com.truesim.engine.graph.Key;
import com.truesim.engine.graph.Presenter;
import com.truesim.engine.graph.ViewPort;
import com.truesim.engine.render.Camera;
import com.truesim.engine.settings.EngineSettings;
import com.truesim.engine.vulkan.Format;
import com.truesim.engine.vulkan.Gpu;
import com.truesim.engine.vulkan.Images;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.engine.vulkan.VulkanInstance;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.lwjgl.PointerBuffer;
import org.lwjgl.openxr.XrApplicationInfo;
import org.lwjgl.openxr.XrCompositionLayerBaseHeader;
import org.lwjgl.openxr.XrCompositionLayerProjection;
import org.lwjgl.openxr.XrCompositionLayerProjectionView;
import org.lwjgl.openxr.XrEventDataBuffer;
import org.lwjgl.openxr.XrEventDataSessionStateChanged;
import org.lwjgl.openxr.XrExtensionProperties;
import org.lwjgl.openxr.XrFrameBeginInfo;
import org.lwjgl.openxr.XrFrameEndInfo;
import org.lwjgl.openxr.XrFrameState;
import org.lwjgl.openxr.XrFrameWaitInfo;
import org.lwjgl.openxr.XrGraphicsBindingVulkanKHR;
import org.lwjgl.openxr.XrGraphicsRequirementsVulkanKHR;
import org.lwjgl.openxr.XrInstance;
import org.lwjgl.openxr.XrInstanceCreateInfo;
import org.lwjgl.openxr.XrInstanceProperties;
import org.lwjgl.openxr.XrReferenceSpaceCreateInfo;
import org.lwjgl.openxr.XrSession;
import org.lwjgl.openxr.XrSessionBeginInfo;
import org.lwjgl.openxr.XrSessionCreateInfo;
import org.lwjgl.openxr.XrSpace;
import org.lwjgl.openxr.XrSwapchain;
import org.lwjgl.openxr.XrSwapchainCreateInfo;
import org.lwjgl.openxr.XrSwapchainImageAcquireInfo;
import org.lwjgl.openxr.XrSwapchainImageBaseHeader;
import org.lwjgl.openxr.XrSwapchainImageReleaseInfo;
import org.lwjgl.openxr.XrSwapchainImageVulkanKHR;
import org.lwjgl.openxr.XrSwapchainImageWaitInfo;
import org.lwjgl.openxr.XrSystemGetInfo;
import org.lwjgl.openxr.XrSystemProperties;
import org.lwjgl.openxr.XrView;
import org.lwjgl.openxr.XrViewConfigurationView;
import org.lwjgl.openxr.XrViewLocateInfo;
import org.lwjgl.openxr.XrViewState;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkInstance;

/**
 * A VR headset through its OpenXR runtime, behind the engine's own API: its two eyes are frame graph views, rendered
 * in the same frame as the desktop windows, a mirror of an eye among them.
 *
 * <pre>{@code
 * try (Headset headset = Headset.connect("true-sim")) {               // HeadsetException.Unavailable: no headset
 *     VulkanInstance instance = VulkanInstance.builder().extensions(headset.vulkanInstanceExtensions()).build();
 *     VulkanDevice device = VulkanDevice.create(headset.gpu(instance), headset.vulkanDeviceExtensions());
 *     headset.start(graph);
 *     for (Eye eye : Eye.values()) {
 *         SceneView view = sceneViews.add(eye.name(), headset.camera(eye), options);
 *         headset.present(eye, view.port(), Keys.SCENE_COLOR);
 *     }
 *     presenter.present(leftEye.port(), Keys.SCENE_COLOR, window);       // the left eye on the desktop too
 *     while (running) {
 *         platform.pollEvents(listener);
 *         headset.render(presenter);                                   // paced by the headset
 *     }
 * }
 * }</pre>
 *
 * <p>The eyes' cameras sit at the tracking space's {@link #origin origin} in the world plus the eyes' poses in it,
 * updated each frame for the time the frame will show. While the runtime does not show the app (its menu open, the
 * headset off the head), the eyes' images are held: their views render nothing for the headset, a mirror still does.
 * Use it on one thread.
 */
public final class Headset implements AutoCloseable {

    private final XrInstance instance;
    private final long system;
    private final String runtime;
    private final String name;
    private final int recommendedWidth;
    private final int recommendedHeight;
    private final Camera[] cameras = {new Camera(), new Camera()};
    private final double[] origin = new double[3];
    // The session, once started.
    private FrameGraph graph;
    private VulkanDevice device;
    private XrSession session;
    private XrSpace space;
    private final XrSwapchain[] swapchains = new XrSwapchain[2];
    private final long[][] images = new long[2][];
    private final long[][] views = new long[2][];
    private final ExternalImage[] eyes = new ExternalImage[2];
    private int width;
    private int height;
    private int state;
    private boolean running; // between xrBeginSession and xrEndSession
    private boolean exiting;
    // The eyes' poses and fields of view as last located: position xyz, orientation xyzw; left, right, up, down.
    private final float[][] poses = new float[2][7];
    private final float[][] fovs = new float[2][4];
    private long frames;
    private boolean closed;

    private Headset(XrInstance instance, long system, String runtime, String name, int width, int height) {
        this.instance = instance;
        this.system = system;
        this.runtime = runtime;
        this.name = name;
        this.recommendedWidth = width;
        this.recommendedHeight = height;
        for (float[] pose : poses) {
            pose[6] = 1; // facing -Z until located
        }
    }

    /**
     * Connects to the headset of the OpenXR runtime installed, as application {@code applicationName}. Throws
     * {@link HeadsetException.Unavailable} where there is no runtime or no headset connected.
     */
    @AllowAllocation("connecting, once")
    public static Headset connect(String applicationName) {
        XrInstance instance;
        try (MemoryStack stack = stackPush()) {
            List<String> available = extensions(stack);
            if (!available.contains(XR_KHR_VULKAN_ENABLE_EXTENSION_NAME)) {
                throw new HeadsetException.Unavailable("The OpenXR runtime cannot render with Vulkan");
            }
            PointerBuffer names = stack.pointers(stack.UTF8(XR_KHR_VULKAN_ENABLE_EXTENSION_NAME));
            XrInstanceCreateInfo create = XrInstanceCreateInfo.calloc(stack).type$Default()
                .applicationInfo(XrApplicationInfo.calloc(stack).applicationName(stack.UTF8(applicationName))
                    .applicationVersion(1).engineName(stack.UTF8("true-sim")).engineVersion(1)
                    .apiVersion(XR_MAKE_VERSION(1, 0, 0))) // OpenXR 1.0 and extensions: what runtimes offer
                .enabledExtensionNames(names);
            PointerBuffer handle = stack.mallocPointer(1);
            int result = xrCreateInstance(create, handle);
            if (result < 0) {
                throw new HeadsetException.Unavailable("No OpenXR runtime to use (" + result + ")");
            }
            instance = new XrInstance(handle.get(0), create);
        } catch (UnsatisfiedLinkError | IllegalStateException e) {
            throw new HeadsetException.Unavailable("No OpenXR loader or runtime: " + e.getMessage());
        }
        try (MemoryStack stack = stackPush()) {
            LongBuffer system = stack.mallocLong(1);
            int result = xrGetSystem(instance, XrSystemGetInfo.calloc(stack).type$Default()
                .formFactor(XR_FORM_FACTOR_HEAD_MOUNTED_DISPLAY), system);
            if (result == XR_ERROR_FORM_FACTOR_UNAVAILABLE) {
                xrDestroyInstance(instance);
                throw new HeadsetException.Unavailable("No headset connected");
            }
            check(result, "find the headset");
            XrInstanceProperties instanceProperties = XrInstanceProperties.calloc(stack).type$Default();
            check(xrGetInstanceProperties(instance, instanceProperties), "read the runtime");
            XrSystemProperties systemProperties = XrSystemProperties.calloc(stack).type$Default();
            check(xrGetSystemProperties(instance, system.get(0), systemProperties), "read the headset");
            IntBuffer count = stack.mallocInt(1);
            check(xrEnumerateViewConfigurationViews(instance, system.get(0), XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO,
                count, null), "list the eyes");
            XrViewConfigurationView.Buffer eyes = XrViewConfigurationView.calloc(count.get(0), stack);
            for (int i = 0; i < eyes.capacity(); i++) {
                eyes.get(i).type$Default();
            }
            check(xrEnumerateViewConfigurationViews(instance, system.get(0), XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO,
                count, eyes), "list the eyes");
            // The runtime must be asked for its Vulkan requirements before a session.
            check(xrGetVulkanGraphicsRequirementsKHR(instance, system.get(0),
                XrGraphicsRequirementsVulkanKHR.calloc(stack).type$Default()), "read the Vulkan requirements");
            return new Headset(instance, system.get(0), instanceProperties.runtimeNameString(),
                systemProperties.systemNameString(), eyes.get(0).recommendedImageRectWidth(),
                eyes.get(0).recommendedImageRectHeight());
        } catch (RuntimeException e) {
            if (!(e instanceof HeadsetException.Unavailable)) {
                xrDestroyInstance(instance);
            }
            throw e;
        }
    }

    @AllowAllocation("connecting, once")
    private static List<String> extensions(MemoryStack stack) {
        IntBuffer count = stack.mallocInt(1);
        check(xrEnumerateInstanceExtensionProperties((ByteBuffer) null, count, null), "list the runtime's extensions");
        XrExtensionProperties.Buffer properties = XrExtensionProperties.calloc(count.get(0), stack);
        for (int i = 0; i < properties.capacity(); i++) {
            properties.get(i).type$Default();
        }
        check(xrEnumerateInstanceExtensionProperties((ByteBuffer) null, count, properties),
            "list the runtime's extensions");
        List<String> names = new ArrayList<>();
        for (int i = 0; i < properties.capacity(); i++) {
            names.add(properties.get(i).extensionNameString());
        }
        return names;
    }

    /** The OpenXR runtime's name, e.g. SteamVR/OpenXR. */
    public String runtime() {
        return runtime;
    }

    /** The headset's name, as its runtime reports it. */
    public String name() {
        return name;
    }

    /** The eyes' image width the runtime recommends, in pixels. */
    public int recommendedWidth() {
        return recommendedWidth;
    }

    /** The eyes' image height the runtime recommends, in pixels. */
    public int recommendedHeight() {
        return recommendedHeight;
    }

    /** The Vulkan instance extensions the runtime needs ({@code VulkanInstance.Builder.extensions}). */
    @AllowAllocation("set up once")
    public List<String> vulkanInstanceExtensions() {
        try (MemoryStack stack = stackPush()) {
            IntBuffer length = stack.mallocInt(1);
            check(xrGetVulkanInstanceExtensionsKHR(instance, system, length, null), "read the Vulkan extensions");
            ByteBuffer text = stack.malloc(length.get(0));
            check(xrGetVulkanInstanceExtensionsKHR(instance, system, length, text), "read the Vulkan extensions");
            return words(text);
        }
    }

    /** The Vulkan device extensions the runtime needs ({@code VulkanDevice.create(gpu, extensions)}). */
    @AllowAllocation("set up once")
    public List<String> vulkanDeviceExtensions() {
        try (MemoryStack stack = stackPush()) {
            IntBuffer length = stack.mallocInt(1);
            check(xrGetVulkanDeviceExtensionsKHR(instance, system, length, null), "read the Vulkan extensions");
            ByteBuffer text = stack.malloc(length.get(0));
            check(xrGetVulkanDeviceExtensionsKHR(instance, system, length, text), "read the Vulkan extensions");
            return words(text);
        }
    }

    @AllowAllocation("set up once")
    private static List<String> words(ByteBuffer text) {
        String all = org.lwjgl.system.MemoryUtil.memUTF8(text, Math.max(0, text.remaining() - 1));
        return Arrays.stream(all.trim().split(" ")).filter(w -> !w.isBlank()).toList();
    }

    /** The GPU of {@code instance} the headset is connected to: the device must be made on it. */
    @AllowAllocation("set up once")
    public Gpu gpu(VulkanInstance vulkan) {
        try (MemoryStack stack = stackPush()) {
            PointerBuffer physical = stack.mallocPointer(1);
            VkInstance handle = vulkan.handle();
            check(xrGetVulkanGraphicsDeviceKHR(instance, system, handle, physical), "find the headset's GPU");
            for (Gpu gpu : vulkan.gpus()) {
                if (gpu.handle().address() == physical.get(0)) {
                    return gpu;
                }
            }
        }
        throw new HeadsetException("The headset's GPU is not one this engine can use (Vulkan 1.3 and its features)");
    }

    /**
     * Starts a session rendering with {@code frameGraph}'s device (made on {@link #gpu}): the eyes' images, sized by
     * the runtime's recommendation times {@link XrSettings#RESOLUTION_SCALE}, in the tracking space
     * {@link XrSettings#SPACE} says. The session runs once the runtime is ready ({@link #render}).
     */
    @AllowAllocation("starting, once")
    public Headset start(FrameGraph frameGraph) {
        if (session != null) {
            throw new IllegalStateException("The headset is started already");
        }
        this.graph = Objects.requireNonNull(frameGraph, "frameGraph");
        this.device = frameGraph.device();
        EngineSettings settings = frameGraph.settings();
        float scale = settings.get(XrSettings.RESOLUTION_SCALE);
        width = Math.max(1, Math.round(recommendedWidth * scale));
        height = Math.max(1, Math.round(recommendedHeight * scale));
        try (MemoryStack stack = stackPush()) {
            XrGraphicsBindingVulkanKHR binding = XrGraphicsBindingVulkanKHR.calloc(stack).type$Default()
                .instance(device.gpu().instance().handle()).physicalDevice(device.gpu().handle())
                .device(device.handle()).queueFamilyIndex(device.graphics().family()).queueIndex(0);
            PointerBuffer handle = stack.mallocPointer(1);
            check(xrCreateSession(instance, XrSessionCreateInfo.calloc(stack).type$Default().next(binding.address())
                .systemId(system), handle), "start a session");
            session = new XrSession(handle.get(0), instance);
            int type = settings.get(XrSettings.SPACE) == XrSettings.Space.STAGE && hasStage(stack)
                ? XR_REFERENCE_SPACE_TYPE_STAGE : XR_REFERENCE_SPACE_TYPE_LOCAL;
            XrReferenceSpaceCreateInfo spaceInfo = XrReferenceSpaceCreateInfo.calloc(stack).type$Default()
                .referenceSpaceType(type);
            spaceInfo.poseInReferenceSpace().orientation().w(1);
            check(xrCreateReferenceSpace(session, spaceInfo, handle), "make the tracking space");
            space = new XrSpace(handle.get(0), session);
            long format = swapchainFormat(stack);
            for (Eye eye : Eye.values()) {
                createSwapchain(stack, eye, format);
            }
        }
        return this;
    }

    private boolean hasStage(MemoryStack stack) {
        IntBuffer count = stack.mallocInt(1);
        check(xrEnumerateReferenceSpaces(session, count, null), "list the tracking spaces");
        IntBuffer types = stack.mallocInt(count.get(0));
        check(xrEnumerateReferenceSpaces(session, count, types), "list the tracking spaces");
        for (int i = 0; i < types.capacity(); i++) {
            if (types.get(i) == XR_REFERENCE_SPACE_TYPE_STAGE) {
                return true;
            }
        }
        return false;
    }

    /** An sRGB format the runtime offers (the eyes' linear colors encoded on the copy into it), else its first. */
    private long swapchainFormat(MemoryStack stack) {
        IntBuffer count = stack.mallocInt(1);
        check(xrEnumerateSwapchainFormats(session, count, null), "list the eye formats");
        LongBuffer formats = stack.mallocLong(count.get(0));
        check(xrEnumerateSwapchainFormats(session, count, formats), "list the eye formats");
        for (int i = 0; i < formats.capacity(); i++) {
            if (formats.get(i) == VK_FORMAT_R8G8B8A8_SRGB || formats.get(i) == VK_FORMAT_B8G8R8A8_SRGB) {
                return formats.get(i);
            }
        }
        return formats.get(0);
    }

    @AllowAllocation("starting, once")
    private void createSwapchain(MemoryStack stack, Eye eye, long format) {
        int e = eye.ordinal();
        PointerBuffer handle = stack.mallocPointer(1);
        check(xrCreateSwapchain(session, XrSwapchainCreateInfo.calloc(stack).type$Default()
            .usageFlags(XR_SWAPCHAIN_USAGE_COLOR_ATTACHMENT_BIT | XR_SWAPCHAIN_USAGE_TRANSFER_DST_BIT)
            .format(format).sampleCount(1).width(width).height(height).faceCount(1).arraySize(1).mipCount(1), handle),
            "make the " + eye + " eye's images");
        swapchains[e] = new XrSwapchain(handle.get(0), session);
        IntBuffer count = stack.mallocInt(1);
        check(xrEnumerateSwapchainImages(swapchains[e], count, null), "list the eye's images");
        XrSwapchainImageVulkanKHR.Buffer list = XrSwapchainImageVulkanKHR.calloc(count.get(0), stack);
        for (int i = 0; i < list.capacity(); i++) {
            list.get(i).type$Default();
        }
        check(xrEnumerateSwapchainImages(swapchains[e], count, XrSwapchainImageBaseHeader.create(list.address(),
            list.capacity())), "list the eye's images");
        images[e] = new long[list.capacity()];
        views[e] = new long[list.capacity()];
        for (int i = 0; i < list.capacity(); i++) {
            images[e][i] = list.get(i).image();
            views[e][i] = Images.createView(device, images[e][i], (int) format, VK_IMAGE_ASPECT_COLOR_BIT);
        }
        eyes[e] = new ExternalImage(eye.name().toLowerCase(java.util.Locale.ROOT) + " eye", width, height,
            Format.of((int) format), VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT,
            VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL).hold(true);
    }

    /** The {@code eye}'s camera: at the tracking space's origin plus the eye's pose, updated each frame. */
    public Camera camera(Eye eye) {
        return cameras[eye.ordinal()];
    }

    /**
     * Puts the tracking space's origin (the play area's floor center, or where a seated head started) at world
     * position ({@code x}, {@code y}, {@code z}): the eyes' cameras move with it. Allocates nothing.
     */
    public Headset origin(double x, double y, double z) {
        origin[0] = x;
        origin[1] = y;
        origin[2] = z;
        return this;
    }

    /**
     * Shows {@code view}'s result {@code key} in {@code eye}, sized to the eye's images: one of the view's outputs,
     * besides any others it has (a desktop mirror). The graph rebuilds on its next frame.
     */
    public Headset present(Eye eye, ViewPort view, Key<Image> key) {
        if (session == null) {
            throw new IllegalStateException("Start the headset first: start(graph)");
        }
        view.size(width, height).output(key, eyes[eye.ordinal()]);
        return this;
    }

    /** Whether the runtime shows the app: a session running, frames rendered for the headset. */
    public boolean running() {
        return running;
    }

    /** Whether the runtime asked the app to quit (the user did, from the headset's menu). */
    public boolean exiting() {
        return exiting;
    }

    /** How many frames the headset showed. */
    public long frames() {
        return frames;
    }

    /**
     * Renders a frame: waits for the headset's next one (pacing the loop to its refresh), locates the eyes for the time
     * it will show, and renders the graph through {@code presenter} (its windows in the same frame; null: the graph
     * alone), then hands the eyes' images to the runtime. While the runtime does not show the app, only the windows
     * render. Returns whether the headset showed this frame.
     */
    public boolean render(Presenter presenter) {
        pollEvents();
        if (!running) {
            holdEyes();
            renderGraph(presenter);
            return false;
        }
        try (MemoryStack stack = stackPush()) {
            XrFrameState frame = XrFrameState.calloc(stack).type$Default();
            check(xrWaitFrame(session, XrFrameWaitInfo.calloc(stack).type$Default(), frame), "wait for a frame");
            check(xrBeginFrame(session, XrFrameBeginInfo.calloc(stack).type$Default()), "begin a frame");
            long time = frame.predictedDisplayTime();
            boolean shown = frame.shouldRender() && locate(stack, time);
            if (shown) {
                for (int e = 0; e < 2; e++) {
                    acquire(stack, e);
                }
            } else {
                holdEyes();
            }
            renderGraph(presenter);
            if (shown) {
                for (int e = 0; e < 2; e++) {
                    check(xrReleaseSwapchainImage(swapchains[e], XrSwapchainImageReleaseInfo.calloc(stack)
                        .type$Default()), "hand the eye's image back");
                }
                frames++;
            }
            endFrame(stack, time, shown);
            return shown;
        }
    }

    private void renderGraph(Presenter presenter) {
        if (presenter != null) {
            presenter.render();
        } else if (graph != null) {
            graph.beginFrame();
            graph.submit(0, 0);
        }
    }

    private void holdEyes() {
        for (ExternalImage eye : eyes) {
            if (eye != null) {
                eye.hold(true);
            }
        }
    }

    /** Locates the eyes for {@code time} and sets their cameras; false where tracking is lost. */
    private boolean locate(MemoryStack stack, long time) {
        XrViewState viewState = XrViewState.calloc(stack).type$Default();
        XrView.Buffer located = XrView.calloc(2, stack);
        for (int e = 0; e < 2; e++) {
            located.get(e).type$Default();
        }
        IntBuffer count = stack.mallocInt(1);
        check(xrLocateViews(session, XrViewLocateInfo.calloc(stack).type$Default()
            .viewConfigurationType(XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO).displayTime(time).space(space),
            viewState, count, located), "locate the eyes");
        long valid = XR_VIEW_STATE_ORIENTATION_VALID_BIT | XR_VIEW_STATE_POSITION_VALID_BIT;
        if ((viewState.viewStateFlags() & valid) != valid) {
            return false;
        }
        float near = graph.settings().get(XrSettings.NEAR);
        for (int e = 0; e < 2; e++) {
            XrView view = located.get(e);
            float[] pose = poses[e];
            pose[0] = view.pose().position$().x();
            pose[1] = view.pose().position$().y();
            pose[2] = view.pose().position$().z();
            pose[3] = view.pose().orientation().x();
            pose[4] = view.pose().orientation().y();
            pose[5] = view.pose().orientation().z();
            pose[6] = view.pose().orientation().w();
            float[] fov = fovs[e];
            fov[0] = view.fov().angleLeft();
            fov[1] = view.fov().angleRight();
            fov[2] = view.fov().angleUp();
            fov[3] = view.fov().angleDown();
            cameras[e].position(origin[0] + pose[0], origin[1] + pose[1], origin[2] + pose[2])
                .setFov(0, 0, 0, pose[3], pose[4], pose[5], pose[6], fov[0], fov[1], fov[2], fov[3], width, height,
                    near);
        }
        return true;
    }

    /** Acquires the eye's next image for this frame and sets its external image to it. */
    private void acquire(MemoryStack stack, int e) {
        IntBuffer index = stack.mallocInt(1);
        check(xrAcquireSwapchainImage(swapchains[e], XrSwapchainImageAcquireInfo.calloc(stack).type$Default(), index),
            "acquire the eye's image");
        check(xrWaitSwapchainImage(swapchains[e], XrSwapchainImageWaitInfo.calloc(stack).type$Default()
            .timeout(XR_INFINITE_DURATION)), "wait for the eye's image");
        int i = index.get(0);
        eyes[e].set(images[e][i], views[e][i]).hold(false);
    }

    /** Ends the frame: the eyes' images as a projection layer where {@code shown}, else nothing. */
    private void endFrame(MemoryStack stack, long time, boolean shown) {
        XrFrameEndInfo end = XrFrameEndInfo.calloc(stack).type$Default().displayTime(time)
            .environmentBlendMode(XR_ENVIRONMENT_BLEND_MODE_OPAQUE);
        if (shown) {
            XrCompositionLayerProjectionView.Buffer layerViews = XrCompositionLayerProjectionView.calloc(2, stack);
            for (int e = 0; e < 2; e++) {
                float[] pose = poses[e];
                float[] fov = fovs[e];
                XrCompositionLayerProjectionView view = layerViews.get(e).type$Default();
                view.pose().position$().set(pose[0], pose[1], pose[2]);
                view.pose().orientation().set(pose[3], pose[4], pose[5], pose[6]);
                view.fov().set(fov[0], fov[1], fov[2], fov[3]);
                view.subImage().swapchain(swapchains[e]).imageArrayIndex(0);
                view.subImage().imageRect().extent().set(width, height);
            }
            XrCompositionLayerProjection layer = XrCompositionLayerProjection.calloc(stack).type$Default().space(space)
                .views(layerViews);
            end.layers(stack.pointers(XrCompositionLayerBaseHeader.create(layer.address())));
        }
        check(xrEndFrame(session, end), "end the frame");
    }

    /** Reads the runtime's events: the session starts when it is ready, ends when told to. */
    private void pollEvents() {
        if (session == null) {
            return;
        }
        try (MemoryStack stack = stackPush()) {
            XrEventDataBuffer event = XrEventDataBuffer.calloc(stack).type$Default();
            while (xrPollEvent(instance, event) == XR_SUCCESS) {
                if (event.type() == XR_TYPE_EVENT_DATA_SESSION_STATE_CHANGED) {
                    state = XrEventDataSessionStateChanged.create(event.address()).state();
                    switch (state) {
                        case XR_SESSION_STATE_READY -> {
                            check(xrBeginSession(session, XrSessionBeginInfo.calloc(stack).type$Default()
                                .primaryViewConfigurationType(XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO)),
                                "begin the session");
                            running = true;
                        }
                        case XR_SESSION_STATE_STOPPING -> {
                            check(xrEndSession(session), "end the session");
                            running = false;
                        }
                        case XR_SESSION_STATE_EXITING, XR_SESSION_STATE_LOSS_PENDING -> exiting = true;
                        default -> {
                            // synchronized, visible, focused, idle: frames go on as they are
                        }
                    }
                }
                event.type$Default();
            }
        }
    }

    /** Asks the runtime to end the session: {@link #exiting()} turns true once it has. */
    public void requestExit() {
        if (running) {
            check(xrRequestExitSession(session), "leave the session");
        }
    }

    /** Ends the session and disconnects, once the GPU is done with the eyes' images. */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (device != null) {
            device.waitIdle();
        }
        for (int e = 0; e < 2; e++) {
            if (views[e] != null) {
                for (long view : views[e]) {
                    vkDestroyImageView(device.handle(), view, null);
                }
            }
            if (swapchains[e] != null) {
                xrDestroySwapchain(swapchains[e]);
            }
        }
        if (space != null) {
            xrDestroySpace(space);
        }
        if (session != null) {
            if (running) {
                xrEndSession(session);
            }
            xrDestroySession(session);
        }
        xrDestroyInstance(instance);
    }

    /** Throws unless {@code result} is a success. */
    private static void check(int result, String what) {
        if (result < 0) {
            throw new HeadsetException("Could not " + what + " (OpenXR " + result + ")");
        }
    }

    @Override
    public String toString() {
        return name + " (" + runtime + ")";
    }

}
