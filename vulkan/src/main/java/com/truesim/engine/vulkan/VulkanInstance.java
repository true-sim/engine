package com.truesim.engine.vulkan;

import static org.lwjgl.sdl.SDLInit.SDL_INIT_VIDEO;
import static org.lwjgl.sdl.SDLInit.SDL_WasInit;
import static org.lwjgl.sdl.SDLVulkan.SDL_Vulkan_CreateSurface;
import static org.lwjgl.sdl.SDLVulkan.SDL_Vulkan_GetInstanceExtensions;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT;
import static org.lwjgl.vulkan.EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_INFO_BIT_EXT;
import static org.lwjgl.vulkan.EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT;
import static org.lwjgl.vulkan.EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT;
import static org.lwjgl.vulkan.EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_TYPE_PERFORMANCE_BIT_EXT;
import static org.lwjgl.vulkan.EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT;
import static org.lwjgl.vulkan.EXTDebugUtils.VK_EXT_DEBUG_UTILS_EXTENSION_NAME;
import static org.lwjgl.vulkan.EXTDebugUtils.VK_STRUCTURE_TYPE_DEBUG_UTILS_MESSENGER_CREATE_INFO_EXT;
import static org.lwjgl.vulkan.EXTDebugUtils.vkCreateDebugUtilsMessengerEXT;
import static org.lwjgl.vulkan.EXTDebugUtils.vkDestroyDebugUtilsMessengerEXT;
import static org.lwjgl.vulkan.KHRPortabilityEnumeration.VK_INSTANCE_CREATE_ENUMERATE_PORTABILITY_BIT_KHR;
import static org.lwjgl.vulkan.KHRPortabilityEnumeration.VK_KHR_PORTABILITY_ENUMERATION_EXTENSION_NAME;
import static org.lwjgl.vulkan.VK10.VK_FALSE;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_APPLICATION_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.vkCreateInstance;
import static org.lwjgl.vulkan.VK10.vkDestroyInstance;
import static org.lwjgl.vulkan.VK10.vkEnumerateInstanceExtensionProperties;
import static org.lwjgl.vulkan.VK10.vkEnumerateInstanceLayerProperties;
import static org.lwjgl.vulkan.VK10.vkEnumeratePhysicalDevices;
import static org.lwjgl.vulkan.VK11.vkEnumerateInstanceVersion;
import static org.lwjgl.vulkan.VK13.VK_API_VERSION_1_3;
import static org.lwjgl.vulkan.VK14.VK_API_VERSION_1_4;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.platform.Window;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.Platform;
import org.lwjgl.vulkan.VkApplicationInfo;
import org.lwjgl.vulkan.VkDebugUtilsMessengerCallbackDataEXT;
import org.lwjgl.vulkan.VkDebugUtilsMessengerCallbackEXT;
import org.lwjgl.vulkan.VkDebugUtilsMessengerCreateInfoEXT;
import org.lwjgl.vulkan.VkExtensionProperties;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkInstanceCreateInfo;
import org.lwjgl.vulkan.VkLayerProperties;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A Vulkan instance: the entry point that lists {@link Gpu}s and creates {@link Surface}s for windows. With
 * validation on, Khronos' validation layer checks every call (when installed, e.g. with the Vulkan SDK) and its
 * messages go to the log; {@link #validationErrors()} counts the errors, so tests can require none.
 *
 * <p>The engine needs Vulkan 1.3 ({@link #MIN_API_VERSION}) and uses 1.4 ({@link #MAX_API_VERSION}) where the loader
 * and GPU offer it: see {@link VulkanDevice#apiVersion()} and {@link VulkanDevice#features14()}.
 *
 * <pre>{@code
 * try (VulkanInstance vulkan = VulkanInstance.builder().applicationName("true-sim").validation(true).build()) {
 *     Gpu gpu = vulkan.bestGpu();
 *     ...
 * }
 * }</pre>
 */
@AllowAllocation("set up once")
public final class VulkanInstance implements AutoCloseable {

    /** The Vulkan version the engine needs: dynamic rendering and synchronization2 are core. */
    public static final int MIN_API_VERSION = VK_API_VERSION_1_3;
    /** The newest Vulkan version the engine uses when available; its features are enabled where supported. */
    public static final int MAX_API_VERSION = VK_API_VERSION_1_4;

    private static final Logger LOG = LoggerFactory.getLogger(VulkanInstance.class);
    private static final String VALIDATION_LAYER = "VK_LAYER_KHRONOS_validation";

    private final VkInstance handle;
    private final int apiVersion;
    private final boolean validation;
    private final boolean surfaces;
    private final AtomicInteger errors = new AtomicInteger();
    private final AtomicInteger warnings = new AtomicInteger();
    private final VkDebugUtilsMessengerCallbackEXT callback;
    private final long messenger;
    private boolean closed;

    private VulkanInstance(Builder builder) {
        try (MemoryStack stack = stackPush()) {
            IntBuffer version = stack.mallocInt(1);
            Vulkan.check(vkEnumerateInstanceVersion(version), "Could not query the Vulkan version");
            if (Integer.compareUnsigned(version.get(0), MIN_API_VERSION) < 0) {
                throw new VulkanException("Vulkan 1.3 is needed; the loader offers " + Vulkan.version(version.get(0)));
            }
            this.apiVersion = Vulkan.min(Vulkan.withoutPatch(version.get(0)), builder.maxApiVersion);
            Set<String> available = instanceExtensions();
            List<String> extensions = new ArrayList<>();
            if (builder.surfaces) {
                // SDL crashes when asked before its video subsystem runs, so check first.
                PointerBuffer required = SDL_WasInit(SDL_INIT_VIDEO) != 0 ? SDL_Vulkan_GetInstanceExtensions() : null;
                if (required == null) {
                    throw new VulkanException("Surfaces need a running Platform (SDL video)");
                }
                for (int i = 0; i < required.remaining(); i++) {
                    extensions.add(required.getStringUTF8(i));
                }
            }
            boolean layer = builder.validation && layers().contains(VALIDATION_LAYER);
            if (builder.validation && !layer) {
                LOG.warn("Validation requested but {} is not installed; continuing without", VALIDATION_LAYER);
            }
            boolean debug = builder.validation && available.contains(VK_EXT_DEBUG_UTILS_EXTENSION_NAME);
            if (debug) {
                extensions.add(VK_EXT_DEBUG_UTILS_EXTENSION_NAME);
            }
            boolean portability = Platform.get() == Platform.MACOSX
                && available.contains(VK_KHR_PORTABILITY_ENUMERATION_EXTENSION_NAME);
            if (portability) {
                extensions.add(VK_KHR_PORTABILITY_ENUMERATION_EXTENSION_NAME);
            }
            this.validation = layer;
            this.surfaces = builder.surfaces;
            this.callback = debug ? VkDebugUtilsMessengerCallbackEXT.create(this::message) : null;

            VkApplicationInfo application = VkApplicationInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_APPLICATION_INFO)
                .pApplicationName(stack.UTF8(builder.applicationName))
                .pEngineName(stack.UTF8("true-sim"))
                .apiVersion(apiVersion);
            PointerBuffer extensionNames = stack.mallocPointer(extensions.size());
            for (String extension : extensions) {
                extensionNames.put(stack.UTF8(extension));
            }
            VkInstanceCreateInfo info = VkInstanceCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO)
                .flags(portability ? VK_INSTANCE_CREATE_ENUMERATE_PORTABILITY_BIT_KHR : 0)
                .pApplicationInfo(application)
                .ppEnabledExtensionNames(extensionNames.flip())
                .ppEnabledLayerNames(layer ? stack.pointers(stack.UTF8(VALIDATION_LAYER)) : null);
            VkDebugUtilsMessengerCreateInfoEXT messengerInfo = debug ? messengerInfo(stack) : null;
            if (debug) {
                info.pNext(messengerInfo.address()); // also covers instance creation and destruction
            }
            PointerBuffer pointer = stack.mallocPointer(1);
            Vulkan.check(vkCreateInstance(info, null, pointer), "Could not create the Vulkan instance");
            this.handle = new VkInstance(pointer.get(0), info);
            if (debug) {
                LongBuffer messengerHandle = stack.mallocLong(1);
                Vulkan.check(vkCreateDebugUtilsMessengerEXT(handle, messengerInfo, null, messengerHandle),
                    "Could not create the debug messenger");
                this.messenger = messengerHandle.get(0);
            } else {
                this.messenger = 0;
            }
            LOG.info("Vulkan instance {} (validation {})", Vulkan.version(version.get(0)), layer ? "on" : "off");
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Options for {@link VulkanInstance}. */
    public static final class Builder {

        private String applicationName = "true-sim";
        private boolean validation;
        private boolean surfaces = true;
        private int maxApiVersion = MAX_API_VERSION;

        private Builder() {
        }

        public Builder applicationName(String name) {
            this.applicationName = name;
            return this;
        }

        /** Checks every call with the validation layer, if installed, and logs its messages. */
        public Builder validation(boolean validation) {
            this.validation = validation;
            return this;
        }

        /**
         * Whether windows will get surfaces (the default). Surfaces need a running Platform; turn them off for
         * headless use such as offscreen rendering or compute.
         */
        public Builder surfaces(boolean surfaces) {
            this.surfaces = surfaces;
            return this;
        }

        /**
         * The newest Vulkan version to use, from {@link #MIN_API_VERSION} to {@link #MAX_API_VERSION} (the default):
         * e.g. {@code VK_API_VERSION_1_3} to run as on a GPU without 1.4.
         */
        public Builder maxApiVersion(int version) {
            if (Integer.compareUnsigned(version, MIN_API_VERSION) < 0
                || Integer.compareUnsigned(version, MAX_API_VERSION) > 0) {
                throw new IllegalArgumentException("Vulkan " + Vulkan.version(version) + " is not between "
                    + Vulkan.version(MIN_API_VERSION) + " and " + Vulkan.version(MAX_API_VERSION));
            }
            this.maxApiVersion = version;
            return this;
        }

        public VulkanInstance build() {
            return new VulkanInstance(this);
        }
    }

    private static Set<String> instanceExtensions() {
        try (MemoryStack stack = stackPush()) {
            IntBuffer count = stack.mallocInt(1);
            vkEnumerateInstanceExtensionProperties((String) null, count, null);
            VkExtensionProperties.Buffer properties = VkExtensionProperties.malloc(count.get(0), stack);
            vkEnumerateInstanceExtensionProperties((String) null, count, properties);
            Set<String> names = new HashSet<>();
            properties.forEach(property -> names.add(property.extensionNameString()));
            return names;
        }
    }

    private static Set<String> layers() {
        try (MemoryStack stack = stackPush()) {
            IntBuffer count = stack.mallocInt(1);
            vkEnumerateInstanceLayerProperties(count, null);
            VkLayerProperties.Buffer properties = VkLayerProperties.malloc(count.get(0), stack);
            vkEnumerateInstanceLayerProperties(count, properties);
            Set<String> names = new HashSet<>();
            properties.forEach(property -> names.add(property.layerNameString()));
            return names;
        }
    }

    private VkDebugUtilsMessengerCreateInfoEXT messengerInfo(MemoryStack stack) {
        return VkDebugUtilsMessengerCreateInfoEXT.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_DEBUG_UTILS_MESSENGER_CREATE_INFO_EXT)
            .messageSeverity(VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT | VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT
                | VK_DEBUG_UTILS_MESSAGE_SEVERITY_INFO_BIT_EXT)
            .messageType(VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT | VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT
                | VK_DEBUG_UTILS_MESSAGE_TYPE_PERFORMANCE_BIT_EXT)
            .pfnUserCallback(callback);
    }

    private int message(int severity, int types, long data, long user) {
        String text = VkDebugUtilsMessengerCallbackDataEXT.create(data).pMessageString();
        if ((severity & VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT) != 0) {
            errors.incrementAndGet();
            LOG.error("Vulkan: {}", text);
        } else if ((severity & VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT) != 0) {
            warnings.incrementAndGet();
            LOG.warn("Vulkan: {}", text);
        } else {
            LOG.debug("Vulkan: {}", text);
        }
        return VK_FALSE;
    }

    public VkInstance handle() {
        return handle;
    }

    /** The Vulkan version this instance was created for: the loader's, at most the builder's maximum. */
    public int apiVersion() {
        return apiVersion;
    }

    /** Whether the validation layer is active. */
    public boolean validationEnabled() {
        return validation;
    }

    /** Validation errors reported so far. */
    public int validationErrors() {
        return errors.get();
    }

    /** Validation warnings reported so far. */
    public int validationWarnings() {
        return warnings.get();
    }

    // ---------------------------------------------------------------- GPUs

    /** The GPUs that support Vulkan 1.3. */
    public List<Gpu> gpus() {
        try (MemoryStack stack = stackPush()) {
            IntBuffer count = stack.mallocInt(1);
            Vulkan.check(vkEnumeratePhysicalDevices(handle, count, null), "Could not list GPUs");
            PointerBuffer devices = stack.mallocPointer(count.get(0));
            Vulkan.check(vkEnumeratePhysicalDevices(handle, count, devices), "Could not list GPUs");
            List<Gpu> gpus = new ArrayList<>();
            for (int i = 0; i < devices.remaining(); i++) {
                Gpu gpu = new Gpu(this, new VkPhysicalDevice(devices.get(i), handle));
                if (Integer.compareUnsigned(gpu.apiVersion(), MIN_API_VERSION) >= 0) {
                    gpus.add(gpu);
                }
            }
            return List.copyOf(gpus);
        }
    }

    /** The most capable GPU: discrete before integrated before others, then the one with most memory. */
    public Gpu bestGpu() {
        return gpus().stream()
            .min(Comparator.comparing(Gpu::type).thenComparing(Comparator.comparingLong(Gpu::deviceLocalMemory).reversed()))
            .orElseThrow(() -> new VulkanException("No GPU supports Vulkan 1.3"));
    }

    // ---------------------------------------------------------------- surfaces

    /** A surface to present to {@code window}; close it before the window. */
    public Surface createSurface(Window window) {
        if (!surfaces) {
            throw new IllegalStateException("This instance was built without surfaces");
        }
        try (MemoryStack stack = stackPush()) {
            LongBuffer surface = stack.mallocLong(1);
            if (!SDL_Vulkan_CreateSurface(window.handle(), handle, null, surface)) {
                throw new VulkanException("Could not create a surface for " + window + ": "
                    + org.lwjgl.sdl.SDLError.SDL_GetError());
            }
            return new Surface(this, window, surface.get(0));
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (messenger != 0) {
            vkDestroyDebugUtilsMessengerEXT(handle, messenger, null);
        }
        vkDestroyInstance(handle, null);
        if (callback != null) {
            callback.free();
        }
    }
}
