package com.truesim.engine.vulkan;

import static org.lwjgl.vulkan.KHRSurface.vkDestroySurfaceKHR;

import com.truesim.engine.platform.Window;

/** A window's Vulkan surface, which swapchains present to. Close it before its window and instance. */
public final class Surface implements AutoCloseable {

    private final VulkanInstance instance;
    private final Window window;
    private long handle;

    Surface(VulkanInstance instance, Window window, long handle) {
        this.instance = instance;
        this.window = window;
        this.handle = handle;
    }

    public long handle() {
        if (handle == 0) {
            throw new IllegalStateException("The surface is closed");
        }
        return handle;
    }

    /** The window this surface belongs to. */
    public Window window() {
        return window;
    }

    @Override
    public void close() {
        if (handle != 0) {
            vkDestroySurfaceKHR(instance.handle(), handle, null);
            handle = 0;
        }
    }
}
