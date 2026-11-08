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
import com.truesim.engine.settings.EngineSettings;
import com.truesim.engine.vulkan.DisplayEncoding;
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
 * acquired nor presented that frame, and keeps showing its last one. Given an {@link OutputTransform}, each window's
 * result is tone mapped and encoded for the display it is on, SDR or HDR (the {@link PresentSettings#HDR} setting
 * asks for HDR where the display offers it); without one, it is copied as it is, in SDR.
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

    private static final com.truesim.engine.app.profile.Zone ACQUIRE =
        com.truesim.engine.app.profile.Profiler.zone("acquire");
    private static final com.truesim.engine.app.profile.Zone PRESENT =
        com.truesim.engine.app.profile.Profiler.zone("present");

    private final FrameGraph graph;
    private final VulkanInstance instance;
    private final VulkanDevice device;
    private final List<Target> targets = new ArrayList<>();
    // The frame being presented: each window acquired, its image, and the semaphores the graph waits for and signals.
    private Target[] presenting = new Target[0];
    private int[] images = new int[0];
    private long[] acquired = new long[0];
    private long[] finished = new long[0];
    private OutputTransform outputTransform; // or null: results copied as they are, in SDR
    private EngineSettings.Subscription metadataWatch; // the graph's settings, for the transform's HDR metadata
    private boolean closed;

    /** Presents views of {@code graph} through surfaces of {@code instance} (made with surfaces). */
    public Presenter(FrameGraph graph, VulkanInstance instance) {
        this.graph = Objects.requireNonNull(graph, "graph");
        this.instance = Objects.requireNonNull(instance, "instance");
        this.device = graph.device();
    }

    /**
     * Presents {@code view}'s result {@code key} to {@code window} as the graph's settings say
     * ({@link PresentSettings#from}), following them while it runs.
     */
    public Target present(ViewPort view, Key<Image> key, Window window) {
        return present(view, key, window, graph.settings());
    }

    /**
     * Presents {@code view}'s result {@code key} to {@code window} as {@code settings} say (a scoped view for this
     * window, say), following them while it runs.
     */
    @AllowAllocation("adding a window")
    public Target present(ViewPort view, Key<Image> key, Window window, EngineSettings settings) {
        Target target = present(view, key, window, PresentSettings.from(settings));
        java.util.Set<String> names = new java.util.HashSet<>();
        for (com.truesim.engine.settings.Setting<?> setting : PresentSettings.ALL) {
            names.add(settings.nameOf(setting));
        }
        target.watch = settings.onAnyChange(name -> {
            if (names.contains(name)) {
                target.apply(PresentSettings.from(settings));
            }
        });
        return target;
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

    /**
     * Tone maps and encodes every window's result with {@code transform} from now on (null: copied as it is, in SDR),
     * for the display each is on; windows asking for HDR get it where their display offers it.
     */
    @AllowAllocation("set up rarely")
    public Presenter outputTransform(OutputTransform transform) {
        this.outputTransform = transform;
        if (metadataWatch != null) {
            metadataWatch.close();
            metadataWatch = null;
        }
        if (transform != null) {
            // What HDR windows tell their displays follows the settings it is made from.
            metadataWatch = graph.settings().onAnyChange(name -> {
                for (Target target : targets) {
                    target.tellMetadata();
                }
            });
        }
        for (Target target : targets) {
            if (target.transform != null) {
                target.removeTransform(); // the one made by the transform before
            }
            target.swapchain.setHdr(target.wantsHdr());
            target.sync();
        }
        return this;
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
        com.truesim.engine.app.profile.Profiler.waitBegin();
        com.truesim.engine.app.profile.Profiler.begin(PRESENT);
        for (int i = 0; i < count; i++) {
            presenting[i].swapchain.present(device.graphics(), images[i]);
            presenting[i].presented++;
            presenting[i] = null;
        }
        com.truesim.engine.app.profile.Profiler.end(PRESENT);
        com.truesim.engine.app.profile.Profiler.waitEnd();
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
        if (metadataWatch != null) {
            metadataWatch.close();
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
        private EngineSettings display = graph.settings(); // what the output transform reads
        private EngineSettings.Subscription watch; // of the settings it follows, or null
        private final Key<Image> shown; // the transformed result the window shows, with an output transform
        private List<Technique> transform; // the output transform's techniques in the view, or null
        private DisplayEncoding transformed; // the encoding it was made for
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
            shown = Key.image("presenter.window" + window.id());
            swapchain = new Swapchain(device, surface, settings.presentMode(), wantsHdr(), width, height);
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
            if (view.outputCount() == 0) {
                view.size(width, height); // a view of its own: the window's size; else its own, scaled to fit
            }
            sync();
            window.attach(this); // closing the window stops presenting to it first
        }

        /** Tells the window's display what the output transform says of the content now (HDR only). */
        @AllowAllocation("when settings change")
        void tellMetadata() {
            com.truesim.engine.vulkan.HdrMetadata told = outputTransform == null ? null
                : outputTransform.metadata(display);
            if (!java.util.Objects.equals(told, swapchain.metadata())) {
                swapchain.metadata(told);
            }
        }

        /** Whether its swapchain should be HDR: asked for, and an output transform to encode for it. */
        boolean wantsHdr() {
            return settings.hdr() && outputTransform != null;
        }

        /**
         * Matches the view to the swapchain as it is now: the window's image in its format, and the result shown
         * through the output transform made for its encoding (or copied as it is, without one).
         */
        @AllowAllocation("when the swapchain changes")
        void sync() {
            tellMetadata();
            image.reformat(Format.of(swapchain.format()));
            if (transform != null && (outputTransform == null || transformed != swapchain.encoding())) {
                removeTransform();
            }
            if (outputTransform != null && transform == null) {
                transformed = swapchain.encoding();
                transform = List.copyOf(outputTransform.transforms(key, shown, transformed, display));
                view.removeOutput(key, image);
                for (Technique each : transform) {
                    view.add(each);
                }
                view.output(shown, image);
            } else if (outputTransform == null) {
                view.output(key, image);
            }
        }

        /** Takes the output transform's techniques and result out of the view. */
        @AllowAllocation("when the swapchain changes")
        private void removeTransform() {
            for (Technique each : transform) {
                view.remove(each);
            }
            view.removeOutput(shown, image);
            transform = null;
        }

        /**
         * The key of the image the window's swapchain image is copied from this frame: the output transform's result
         * (encoded for the display, the UI composited), or the presented key as it is without a transform. A technique
         * added to the view after presenting can read it (a capture of what the window shows).
         */
        public Key<Image> shownKey() {
            return transform != null ? shown : key;
        }

        /** How the window's images reach its display now: SDR, HDR10 or scRGB. */
        public DisplayEncoding encoding() {
            return swapchain.encoding();
        }

        /**
         * This frame's image of the window, acquired, or -1 if the window shows nothing new this frame: its view does
         * not run, or the window is minimized (the view then runs no frame).
         */
        int acquire(int slot) {
            int width = window.pixelWidth();
            int height = window.pixelHeight();
            if (window.isMinimized() || width < 1 || height < 1) {
                image.hold(true); // nothing to show; the view runs no frame unless it has other outputs
                return -1;
            }
            if (swapchain.needsRecreate() || !swapchain.isUsable() || width != swapchain.width()
                || height != swapchain.height()) {
                swapchain.recreate(width, height);
                image.resize(width, height);
                if (view.outputCount() == 1) {
                    view.size(width, height); // this window its only output: sized to it
                }
                sync(); // another display, maybe another encoding
            }
            if (!view.dueNextFrame()) {
                image.hold(true);
                return -1;
            }
            com.truesim.engine.app.profile.Profiler.waitBegin();
            com.truesim.engine.app.profile.Profiler.begin(ACQUIRE);
            int index = swapchain.acquire(available[slot]);
            com.truesim.engine.app.profile.Profiler.end(ACQUIRE);
            com.truesim.engine.app.profile.Profiler.waitEnd();
            if (index < 0) {
                image.hold(true); // out of date: rebuilt next frame
                return -1;
            }
            image.set(swapchain.image(index), swapchain.view(index)).hold(false);
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

        /**
         * The settings the output transform reads for this window (tone curve, paper white...): the graph's unless
         * {@link #displaySettings(EngineSettings)} gave it others.
         */
        public EngineSettings displaySettings() {
            return display;
        }

        /**
         * Has the output transform read {@code value} for this window from now on: a view of the graph's settings
         * holding the window's own values ({@link EngineSettings#inheriting}, say). Rebuilds its transform.
         */
        @AllowAllocation("changing settings, rarely")
        public Target displaySettings(EngineSettings value) {
            Objects.requireNonNull(value, "value");
            if (value != display) {
                display = value;
                if (transform != null) {
                    removeTransform();
                }
                sync();
            }
            return this;
        }

        /** What the window's display is told about the content while HDR (the output transform's), or null. */
        public com.truesim.engine.vulkan.HdrMetadata metadata() {
            return swapchain.metadata();
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
            swapchain.setHdr(wantsHdr());
            sync();
            return this;
        }

        /** Stops presenting to the window, once the GPU is done: its swapchain goes, and the view's output. */
        @Override
        public void close() {
            if (removed) {
                return;
            }
            removed = true;
            if (watch != null) {
                watch.close();
            }
            device.waitIdle();
            targets.remove(this);
            window.detach(this);
            view.removeOutput(key, image).removeOutput(shown, image);
            if (transform != null) {
                for (Technique each : transform) {
                    view.remove(each);
                }
            }
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
