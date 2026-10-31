package com.truesim.engine.graph;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.KHRSwapchain.VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_UNDEFINED;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.vkCreateSemaphore;
import static org.lwjgl.vulkan.VK10.vkDestroySemaphore;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.platform.Window;
import com.truesim.engine.vulkan.Format;
import com.truesim.engine.vulkan.Surface;
import com.truesim.engine.vulkan.Swapchain;
import com.truesim.engine.vulkan.Vulkan;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.engine.vulkan.VulkanInstance;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkSemaphoreCreateInfo;

/**
 * Presents views of a {@link FrameGraph} to windows: any number of windows, all from one frame of the graph. Each
 * window gets a surface and swapchain; its view is sized to the window's pixels and resized with it. A window whose
 * view does not run in a frame (turned off, its {@link ViewPort#rate rate}, or the window minimized) is neither
 * acquired nor presented that frame, and keeps showing its last one.
 *
 * <pre>{@code
 * Presenter presenter = new Presenter(graph, instance);
 * for (int i = 0; i < screens.size(); i++) {
 *     presenter.present(views.get(i), Keys.SCENE_COLOR, screens.get(i), PresentSettings.fromMap(saved));
 * }
 * while (running) {
 *     platform.pollEvents(listener);
 *     presenter.render();                 // one frame, every window whose view runs
 * }
 * }</pre>
 *
 * <p>Use it on the platform's thread (it reads the windows' sizes). Rendering a frame allocates nothing, but for a
 * window resized (its swapchain and the graph rebuild, after waiting for the GPU).
 */
public final class Presenter implements AutoCloseable {

    private final FrameGraph graph;
    private final VulkanInstance instance;
    private final VulkanDevice device;
    private final List<Target> targets = new ArrayList<>();
    // The frame being presented: each window acquired, its image, and the semaphores the graph waits for and signals.
    private Target[] presenting = new Target[0];
    private int[] images = new int[0];
    private long[] acquired = new long[0];
    private long[] finished = new long[0];
    private boolean closed;

    /** Presents views of {@code graph} through surfaces of {@code instance} (made with surfaces). */
    public Presenter(FrameGraph graph, VulkanInstance instance) {
        this.graph = Objects.requireNonNull(graph, "graph");
        this.instance = Objects.requireNonNull(instance, "instance");
        this.device = graph.device();
    }

    /** Presents {@code view}'s result {@code key} to {@code window} with vsync ({@link PresentSettings#defaults()}). */
    public Target present(ViewPort view, Key<Image> key, Window window) {
        return present(view, key, window, PresentSettings.defaults());
    }

    /**
     * Presents {@code view}'s result {@code key} to {@code window} as {@code settings} say, from the next frame: the
     * view's size becomes the window's in pixels. Closing the window removes it.
     */
    @AllowAllocation("adding a window")
    public Target present(ViewPort view, Key<Image> key, Window window, PresentSettings settings) {
        if (closed) {
            throw new IllegalStateException("The presenter is closed");
        }
        Target target = new Target(view, key, window, settings);
        targets.add(target);
        int n = targets.size();
        presenting = new Target[n];
        images = new int[n];
        acquired = new long[n];
        finished = new long[n];
        return target;
    }

    /** The windows presented to. */
    @AllowAllocation("a snapshot")
    public List<Target> targets() {
        return List.copyOf(targets);
    }

    /**
     * Renders and presents one frame of the graph: every window whose view runs in it. Returns how many windows were
     * presented. Allocates nothing, but for a window resized.
     */
    public int render() {
        if (closed) {
            throw new IllegalStateException("The presenter is closed");
        }
        int slot = graph.beginFrame();
        int count = 0;
        for (int i = 0; i < targets.size(); i++) {
            Target target = targets.get(i);
            int image = target.acquire(slot);
            if (image >= 0) {
                presenting[count] = target;
                images[count] = image;
                acquired[count] = target.available[slot];
                finished[count++] = target.swapchain.presentReady(image);
            }
        }
        graph.submit(acquired, count, finished, count);
        for (int i = 0; i < count; i++) {
            presenting[i].swapchain.present(device.graphics(), images[i]);
            presenting[i].presented++;
            presenting[i] = null;
        }
        return count;
    }

    /** Stops presenting to every window, once the GPU is done: their swapchains go, the views' outputs too. */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        for (Target target : List.copyOf(targets)) {
            target.close();
        }
        closed = true;
    }

    /** A window presented to: its swapchain, and the view whose result it shows. */
    public final class Target implements AutoCloseable {
        private final ViewPort view;
        private final Key<Image> key;
        private final Window window;
        private final Surface surface;
        private final Swapchain swapchain;
        private final ExternalImage image;
        private final long[] available; // per frame slot: signaled when the acquired image may be written
        private PresentSettings settings;
        private long presented;
        private boolean removed;

        @AllowAllocation("adding a window")
        Target(ViewPort view, Key<Image> key, Window window, PresentSettings settings) {
            this.view = Objects.requireNonNull(view, "view");
            this.key = Objects.requireNonNull(key, "key");
            this.window = Objects.requireNonNull(window, "window");
            this.settings = Objects.requireNonNull(settings, "settings");
            int width = Math.max(1, window.pixelWidth());
            int height = Math.max(1, window.pixelHeight());
            surface = instance.createSurface(window);
            swapchain = new Swapchain(device, surface, settings.presentMode(), width, height);
            int usage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT
                | VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
            image = new ExternalImage(window.title(), width, height, Format.of(swapchain.format()), usage,
                VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_PRESENT_SRC_KHR);
            available = new long[graph.framesInFlight()];
            try (MemoryStack stack = stackPush()) {
                LongBuffer semaphore = stack.mallocLong(1);
                VkSemaphoreCreateInfo info = VkSemaphoreCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO);
                for (int s = 0; s < available.length; s++) {
                    Vulkan.check(vkCreateSemaphore(device.handle(), info, null, semaphore), "Could not make a semaphore");
                    available[s] = semaphore.get(0);
                }
            }
            view.size(width, height).output(key, image);
            window.attach(this); // closing the window stops presenting to it first
        }

        /**
         * This frame's image of the window, acquired, or -1 if the window shows nothing new this frame: its view does
         * not run, or the window is minimized (the view then runs no frame).
         */
        int acquire(int slot) {
            int width = window.pixelWidth();
            int height = window.pixelHeight();
            view.suspended = window.isMinimized() || width < 1 || height < 1;
            if (view.suspended) {
                return -1;
            }
            if (swapchain.needsRecreate() || !swapchain.isUsable() || width != swapchain.width()
                || height != swapchain.height()) {
                swapchain.recreate(width, height);
                image.resize(width, height);
                view.size(width, height);
            }
            if (!view.runsNextFrame()) {
                return -1;
            }
            int index = swapchain.acquire(available[slot]);
            if (index < 0) {
                view.suspended = true; // out of date: rebuilt next frame
                return -1;
            }
            image.set(swapchain.image(index), swapchain.view(index));
            return index;
        }

        public Window window() {
            return window;
        }

        public ViewPort view() {
            return view;
        }

        public PresentSettings settings() {
            return settings;
        }

        /** How many frames were presented to the window. */
        public long presented() {
            return presented;
        }

        /** Changes how the window presents, from the next frame (its swapchain rebuilds, after waiting for the GPU). */
        @AllowAllocation("changing settings, rarely")
        public Target apply(PresentSettings changed) {
            Objects.requireNonNull(changed, "changed");
            if (changed.presentMode() != settings.presentMode()) {
                swapchain.setPresentMode(changed.presentMode());
            }
            settings = changed;
            return this;
        }

        /** Stops presenting to the window, once the GPU is done: its swapchain goes, and the view's output. */
        @Override
        public void close() {
            if (removed) {
                return;
            }
            removed = true;
            device.waitIdle();
            targets.remove(this);
            window.detach(this);
            view.removeOutput(key);
            view.suspended = false;
            swapchain.close();
            surface.close();
            for (long semaphore : available) {
                vkDestroySemaphore(device.handle(), semaphore, null);
            }
        }

        @Override
        public String toString() {
            return "presenting " + view + " to " + window;
        }
    }
}
