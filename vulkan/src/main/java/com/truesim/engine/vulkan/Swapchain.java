package com.truesim.engine.vulkan;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.memGetInt;
import static org.lwjgl.system.MemoryUtil.memPutAddress;
import static org.lwjgl.system.MemoryUtil.memPutInt;
import static org.lwjgl.system.MemoryUtil.memPutLong;
import static org.lwjgl.vulkan.EXTSwapchainColorspace.VK_COLOR_SPACE_EXTENDED_SRGB_LINEAR_EXT;
import static org.lwjgl.vulkan.EXTSwapchainColorspace.VK_COLOR_SPACE_HDR10_ST2084_EXT;
import static org.lwjgl.vulkan.KHRSurface.VK_COLOR_SPACE_SRGB_NONLINEAR_KHR;
import static org.lwjgl.vulkan.KHRSurface.VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR;
import static org.lwjgl.vulkan.KHRSurface.vkGetPhysicalDeviceSurfaceCapabilitiesKHR;
import static org.lwjgl.vulkan.KHRSurface.vkGetPhysicalDeviceSurfaceFormatsKHR;
import static org.lwjgl.vulkan.KHRSurface.vkGetPhysicalDeviceSurfacePresentModesKHR;
import static org.lwjgl.vulkan.KHRSwapchain.VK_ERROR_OUT_OF_DATE_KHR;
import static org.lwjgl.vulkan.KHRSwapchain.VK_STRUCTURE_TYPE_PRESENT_INFO_KHR;
import static org.lwjgl.vulkan.KHRSwapchain.VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR;
import static org.lwjgl.vulkan.KHRSwapchain.VK_SUBOPTIMAL_KHR;
import static org.lwjgl.vulkan.EXTHdrMetadata.vkSetHdrMetadataEXT;
import static org.lwjgl.vulkan.KHRSwapchain.nvkAcquireNextImageKHR;
import static org.lwjgl.vulkan.KHRSwapchain.vkCreateSwapchainKHR;
import static org.lwjgl.vulkan.KHRSwapchain.vkDestroySwapchainKHR;
import static org.lwjgl.vulkan.KHRSwapchain.vkGetSwapchainImagesKHR;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_A2B10G10R10_UNORM_PACK32;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_A2R10G10B10_UNORM_PACK32;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_B8G8R8A8_SRGB;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16B16A16_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_SRGB;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
import static org.lwjgl.vulkan.VK10.VK_SHARING_MODE_EXCLUSIVE;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_SUCCESS;
import static org.lwjgl.vulkan.VK10.vkCreateSemaphore;
import static org.lwjgl.vulkan.VK10.vkDestroyImageView;
import static org.lwjgl.vulkan.VK10.vkDestroySemaphore;

import com.truesim.annotations.AllowAllocation;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkHdrMetadataEXT;
import org.lwjgl.vulkan.VkPresentInfoKHR;
import org.lwjgl.vulkan.VkSemaphoreCreateInfo;
import org.lwjgl.vulkan.VkSurfaceCapabilitiesKHR;
import org.lwjgl.vulkan.VkSurfaceFormatKHR;
import org.lwjgl.vulkan.VkSwapchainCreateInfoKHR;

/**
 * The images a window shows, rendered in turn: {@link #acquire} an image, render to it, signal
 * {@link #presentReady(int)} and {@link #present} it. Recreate it at any time with a new size
 * ({@link #recreate(int, int)}, e.g. from {@code windowPixelSizeChanged}) or {@linkplain #setPresentMode present mode};
 * {@link #needsRecreate()} also turns true when the system reports it out of date. While the window has no area
 * (minimized) the swapchain is not {@linkplain #isUsable() usable} and frames should be skipped.
 *
 * <p>Sizes are passed in, so the swapchain can live on a render thread. Acquiring and presenting allocate nothing.
 */
public final class Swapchain implements AutoCloseable {

    private static final long TIMEOUT = 5_000_000_000L;

    private final VulkanDevice device;
    private final Surface surface;
    private PresentMode requested;
    private PresentMode presentMode;
    private long handle;
    private int format;
    private int width;
    private int height;
    private long[] images = new long[0];
    private long[] views = new long[0];
    private long[] presentReady = new long[0];
    private boolean outOfDate;
    private boolean hdr; // HDR output asked for
    private DisplayEncoding encoding = DisplayEncoding.SDR_SRGB;
    private HdrMetadata metadata; // told the display while HDR, or null

    /** A swapchain for {@code surface} of {@code width} x {@code height} pixels (the window's pixel size). */
    @AllowAllocation("set up once")
    public Swapchain(VulkanDevice device, Surface surface, PresentMode mode, int width, int height) {
        this(device, surface, mode, false, width, height);
    }

    /**
     * A swapchain for {@code surface} of {@code width} x {@code height} pixels, with {@code hdr} in HDR where the
     * display offers it ({@link #encoding()} tells which it got).
     */
    @AllowAllocation("set up once")
    public Swapchain(VulkanDevice device, Surface surface, PresentMode mode, boolean hdr, int width, int height) {
        if (!device.canPresent()) {
            throw new VulkanException("The device was created headless");
        }
        if (!device.gpu().canPresent(surface, device.graphics().family())) {
            throw new VulkanException(device.gpu().name() + " cannot present to " + surface.window());
        }
        this.device = device;
        this.surface = surface;
        this.requested = mode;
        this.hdr = hdr;
        create(width, height);
    }

    /** Builds the swapchain for the current surface; the previous one, if any, is replaced. */
    @AllowAllocation("recreating")
    private void create(int requestedWidth, int requestedHeight) {
        try (MemoryStack stack = stackPush()) {
            VkSurfaceCapabilitiesKHR capabilities = VkSurfaceCapabilitiesKHR.malloc(stack);
            Vulkan.check(vkGetPhysicalDeviceSurfaceCapabilitiesKHR(device.gpu().handle(), surface.handle(), capabilities),
                "Could not read the surface capabilities");
            int w = capabilities.currentExtent().width();
            int h = capabilities.currentExtent().height();
            if (w == -1) { // the surface takes the swapchain's size
                w = Math.clamp(requestedWidth, capabilities.minImageExtent().width(), capabilities.maxImageExtent().width());
                h = Math.clamp(requestedHeight, capabilities.minImageExtent().height(),
                    capabilities.maxImageExtent().height());
            }
            long previous = handle;
            destroyImages();
            this.width = w;
            this.height = h;
            this.outOfDate = false;
            if (w == 0 || h == 0) { // minimized
                if (previous != 0) {
                    vkDestroySwapchainKHR(device.handle(), previous, null);
                }
                handle = 0;
                return;
            }
            VkSurfaceFormatKHR chosen = chooseFormat(stack);
            this.format = chosen.format();
            this.encoding = encoding(chosen.colorSpace());
            this.presentMode = supportedPresentModes().contains(requested) ? requested : PresentMode.FIFO;
            int count = Math.max(capabilities.minImageCount() + 1, 3);
            if (capabilities.maxImageCount() > 0) {
                count = Math.min(count, capabilities.maxImageCount());
            }
            VkSwapchainCreateInfoKHR info = VkSwapchainCreateInfoKHR.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR)
                .surface(surface.handle())
                .minImageCount(count)
                .imageFormat(chosen.format())
                .imageColorSpace(chosen.colorSpace())
                .imageArrayLayers(1)
                .imageUsage(VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT
                    | VK_IMAGE_USAGE_TRANSFER_SRC_BIT)
                .imageSharingMode(VK_SHARING_MODE_EXCLUSIVE)
                .preTransform(capabilities.currentTransform())
                .compositeAlpha(VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR)
                .presentMode(presentMode.value)
                .clipped(true)
                .oldSwapchain(previous);
            info.imageExtent().set(w, h);
            LongBuffer swapchain = stack.mallocLong(1);
            Vulkan.check(vkCreateSwapchainKHR(device.handle(), info, null, swapchain),
                "Could not create the swapchain");
            if (previous != 0) {
                vkDestroySwapchainKHR(device.handle(), previous, null);
            }
            this.handle = swapchain.get(0);
            applyMetadata();

            IntBuffer imageCount = stack.mallocInt(1);
            Vulkan.checkList(vkGetSwapchainImagesKHR(device.handle(), handle, imageCount, null),
                "Could not list the swapchain's images");
            LongBuffer imageHandles = stack.mallocLong(imageCount.get(0));
            Vulkan.checkList(vkGetSwapchainImagesKHR(device.handle(), handle, imageCount, imageHandles),
                "Could not list the swapchain's images");
            images = new long[imageCount.get(0)];
            views = new long[images.length];
            presentReady = new long[images.length];
            VkSemaphoreCreateInfo semaphoreInfo = VkSemaphoreCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO);
            LongBuffer semaphore = stack.mallocLong(1);
            for (int i = 0; i < images.length; i++) {
                images[i] = imageHandles.get(i);
                views[i] = Images.createView(device, images[i], format, VK_IMAGE_ASPECT_COLOR_BIT);
                Vulkan.check(vkCreateSemaphore(device.handle(), semaphoreInfo, null, semaphore),
                    "Could not create a semaphore");
                presentReady[i] = semaphore.get(0);
            }
        }
    }

    /** The format {@link #chooseFormat(VkSurfaceFormatKHR.Buffer, boolean)} picks among the surface's. */
    private VkSurfaceFormatKHR chooseFormat(MemoryStack stack) {
        IntBuffer count = stack.mallocInt(1);
        Vulkan.checkList(vkGetPhysicalDeviceSurfaceFormatsKHR(device.gpu().handle(), surface.handle(), count, null),
            "Could not list the surface's formats");
        VkSurfaceFormatKHR.Buffer formats = VkSurfaceFormatKHR.malloc(count.get(0), stack);
        Vulkan.checkList(vkGetPhysicalDeviceSurfaceFormatsKHR(device.gpu().handle(), surface.handle(), count, formats),
            "Could not list the surface's formats");
        return formats.get(chooseFormat(formats, hdr));
    }

    /**
     * The index of the format to present with: with {@code hdr}, HDR10 (10 bits, PQ) or else scRGB (16-bit floats),
     * where offered; otherwise, or where neither is, the first 8-bit sRGB format with the sRGB color space, or 0.
     */
    static int chooseFormat(VkSurfaceFormatKHR.Buffer formats, boolean hdr) {
        if (hdr) {
            for (int i = 0; i < formats.capacity(); i++) {
                VkSurfaceFormatKHR candidate = formats.get(i);
                if ((candidate.format() == VK_FORMAT_A2B10G10R10_UNORM_PACK32
                    || candidate.format() == VK_FORMAT_A2R10G10B10_UNORM_PACK32)
                    && candidate.colorSpace() == VK_COLOR_SPACE_HDR10_ST2084_EXT) {
                    return i;
                }
            }
            for (int i = 0; i < formats.capacity(); i++) {
                VkSurfaceFormatKHR candidate = formats.get(i);
                if (candidate.format() == VK_FORMAT_R16G16B16A16_SFLOAT
                    && candidate.colorSpace() == VK_COLOR_SPACE_EXTENDED_SRGB_LINEAR_EXT) {
                    return i;
                }
            }
        }
        return chooseFormat(formats);
    }

    /** The index of the first sRGB format with the sRGB color space, or 0. */
    static int chooseFormat(VkSurfaceFormatKHR.Buffer formats) {
        for (int i = 0; i < formats.capacity(); i++) {
            VkSurfaceFormatKHR candidate = formats.get(i);
            if ((candidate.format() == VK_FORMAT_B8G8R8A8_SRGB || candidate.format() == VK_FORMAT_R8G8B8A8_SRGB)
                && candidate.colorSpace() == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR) {
                return i;
            }
        }
        return 0;
    }

    /** The present modes the surface supports. */
    @AllowAllocation("a snapshot")
    public List<PresentMode> supportedPresentModes() {
        try (MemoryStack stack = stackPush()) {
            IntBuffer count = stack.mallocInt(1);
            Vulkan.checkList(vkGetPhysicalDeviceSurfacePresentModesKHR(device.gpu().handle(), surface.handle(), count,
                null), "Could not list the surface's present modes");
            IntBuffer modes = stack.mallocInt(count.get(0));
            Vulkan.checkList(vkGetPhysicalDeviceSurfacePresentModesKHR(device.gpu().handle(), surface.handle(), count,
                modes), "Could not list the surface's present modes");
            List<PresentMode> supported = new ArrayList<>();
            for (PresentMode mode : PresentMode.values()) {
                for (int i = 0; i < modes.remaining(); i++) {
                    if (modes.get(i) == mode.value) {
                        supported.add(mode);
                    }
                }
            }
            return List.copyOf(supported);
        }
    }

    // ---------------------------------------------------------------- runtime changes

    /**
     * Rebuilds the swapchain for a new pixel size, e.g. after the window was resized or changed mode. Waits until the
     * device is idle first.
     */
    @AllowAllocation("recreating")
    public void recreate(int width, int height) {
        device.waitIdle();
        create(width, height);
    }

    /** Switches the present mode (falling back to {@link PresentMode#FIFO} if unsupported) and rebuilds. */
    @AllowAllocation("recreating")
    public void setPresentMode(PresentMode mode) {
        this.requested = mode;
        recreate(width, height);
    }

    /**
     * Asks for HDR output where the display offers it, or for SDR, and rebuilds (waiting for the GPU) if that
     * changes what was asked.
     */
    @AllowAllocation("recreating")
    public void setHdr(boolean on) {
        if (on != hdr) {
            hdr = on;
            recreate(width, height);
        }
    }

    /**
     * What to tell the display about the content while the swapchain is HDR ({@link HdrMetadata}; null: nothing),
     * now and whenever it is made again. Ignored where the device has no {@code VK_EXT_hdr_metadata}.
     */
    public void metadata(HdrMetadata told) {
        metadata = told;
        applyMetadata();
    }

    /** The HDR metadata told the display, or null. */
    public HdrMetadata metadata() {
        return metadata;
    }

    /** Tells the display the metadata, where there is some, the swapchain is HDR and the device can. */
    private void applyMetadata() {
        if (metadata == null || handle == 0 || encoding == DisplayEncoding.SDR_SRGB || !device.hdrMetadata()) {
            return;
        }
        try (MemoryStack stack = stackPush()) {
            VkHdrMetadataEXT.Buffer told = VkHdrMetadataEXT.calloc(1, stack);
            metadata.fill(told.get(0), encoding);
            vkSetHdrMetadataEXT(device.handle(), stack.longs(handle), told);
        }
    }

    /** Whether HDR output was asked for; {@link #encoding()} tells whether the display gave it. */
    public boolean hdrRequested() {
        return hdr;
    }

    /** How the images reach the display: SDR, or HDR10 or scRGB where asked for and offered. */
    public DisplayEncoding encoding() {
        return encoding;
    }

    /** The encoding of images in color space {@code colorSpace}. */
    static DisplayEncoding encoding(int colorSpace) {
        return switch (colorSpace) {
            case VK_COLOR_SPACE_HDR10_ST2084_EXT -> DisplayEncoding.HDR10_PQ;
            case VK_COLOR_SPACE_EXTENDED_SRGB_LINEAR_EXT -> DisplayEncoding.SCRGB_LINEAR;
            default -> DisplayEncoding.SDR_SRGB;
        };
    }

    /** The present mode in use. */
    public PresentMode presentMode() {
        return presentMode;
    }

    /** The present mode last asked for. */
    public PresentMode requestedPresentMode() {
        return requested;
    }

    /** Whether there are images to render to; not while the window is minimized. */
    public boolean isUsable() {
        return handle != 0;
    }

    /** Whether the system reported the swapchain out of date or suboptimal: call {@link #recreate}. */
    public boolean needsRecreate() {
        return outOfDate;
    }

    // ---------------------------------------------------------------- frames

    /**
     * Acquires the next image to render to; {@code imageAvailable} is signaled when it may be written. Returns the
     * image index, or -1 if the swapchain is out of date (then {@link #needsRecreate()} is true).
     */
    public int acquire(long imageAvailable) {
        try (MemoryStack stack = stackPush()) {
            long index = stack.nmalloc(Integer.BYTES, Integer.BYTES);
            int result = nvkAcquireNextImageKHR(device.handle(), handle, TIMEOUT, imageAvailable, 0, index);
            if (result == VK_ERROR_OUT_OF_DATE_KHR) {
                outOfDate = true;
                return -1;
            }
            if (result == VK_SUBOPTIMAL_KHR) {
                outOfDate = true; // still usable this time
            } else {
                device.check(result, "Could not acquire a swapchain image"); // a lost device marked and reported
            }
            return memGetInt(index);
        }
    }

    /** The semaphore rendering to {@code image} signals, which presenting it waits for. */
    public long presentReady(int image) {
        return presentReady[image];
    }

    /** Presents {@code image} once {@link #presentReady(int)} is signaled. */
    public void present(Queue queue, int image) {
        try (MemoryStack stack = stackPush()) {
            // Written into stack memory by address: no struct wrappers, so presenting allocates nothing.
            long semaphores = stack.nmalloc(Long.BYTES, Long.BYTES);
            memPutLong(semaphores, presentReady[image]);
            long swapchains = stack.nmalloc(Long.BYTES, Long.BYTES);
            memPutLong(swapchains, handle);
            long indices = stack.nmalloc(Integer.BYTES, Integer.BYTES);
            memPutInt(indices, image);
            long info = stack.ncalloc(VkPresentInfoKHR.ALIGNOF, 1, VkPresentInfoKHR.SIZEOF);
            memPutInt(info + VkPresentInfoKHR.STYPE, VK_STRUCTURE_TYPE_PRESENT_INFO_KHR);
            memPutInt(info + VkPresentInfoKHR.WAITSEMAPHORECOUNT, 1);
            memPutAddress(info + VkPresentInfoKHR.PWAITSEMAPHORES, semaphores);
            memPutInt(info + VkPresentInfoKHR.SWAPCHAINCOUNT, 1);
            memPutAddress(info + VkPresentInfoKHR.PSWAPCHAINS, swapchains);
            memPutAddress(info + VkPresentInfoKHR.PIMAGEINDICES, indices);
            int result = queue.present(info);
            if (result == VK_ERROR_OUT_OF_DATE_KHR || result == VK_SUBOPTIMAL_KHR) {
                outOfDate = true;
            } else if (result != VK_SUCCESS) {
                device.check(result, "Could not present");
            }
        }
    }

    public long handle() {
        return handle;
    }

    /** Width in pixels. */
    public int width() {
        return width;
    }

    /** Height in pixels. */
    public int height() {
        return height;
    }

    /** The image format, e.g. {@code VK_FORMAT_B8G8R8A8_SRGB}. */
    public int format() {
        return format;
    }

    public int imageCount() {
        return images.length;
    }

    public long image(int index) {
        return images[index];
    }

    public long view(int index) {
        return views[index];
    }

    private void destroyImages() {
        for (int i = 0; i < views.length; i++) {
            vkDestroyImageView(device.handle(), views[i], null);
            vkDestroySemaphore(device.handle(), presentReady[i], null);
        }
        views = new long[0];
        presentReady = new long[0];
        images = new long[0];
    }

    @Override
    @AllowAllocation("shutting down")
    public void close() {
        device.waitIdle();
        destroyImages();
        if (handle != 0) {
            vkDestroySwapchainKHR(device.handle(), handle, null);
            handle = 0;
        }
    }
}
