package com.truesim.engine.vulkan;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.util.vma.Vma.VMA_MEMORY_USAGE_AUTO;
import static org.lwjgl.util.vma.Vma.vmaCreateImage;
import static org.lwjgl.util.vma.Vma.vmaDestroyImage;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_UNDEFINED;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_TILING_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_CREATE_CUBE_COMPATIBLE_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_TYPE_2D;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_TYPE_3D;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_VIEW_TYPE_2D;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_VIEW_TYPE_2D_ARRAY;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_VIEW_TYPE_3D;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_VIEW_TYPE_CUBE;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_VIEW_TYPE_CUBE_ARRAY;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_INPUT_ATTACHMENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_SAMPLED_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_STORAGE_BIT;
import static org.lwjgl.vulkan.VK10.VK_SHARING_MODE_CONCURRENT;
import static org.lwjgl.vulkan.VK10.VK_SHARING_MODE_EXCLUSIVE;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.vkDestroyImageView;

import com.truesim.annotations.AllowAllocation;
import java.nio.LongBuffer;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.vulkan.VkImageCreateInfo;

/**
 * An image in GPU memory with a view of all of it, e.g. a render target, depth buffer or texture: 2D (with array layers,
 * or cube faces) or 3D, with mip levels and, for render targets, several samples per texel.
 */
@AllowAllocation("created rarely")
public final class GpuImage implements AutoCloseable {

    private static final int VIEW_USAGES = VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_STORAGE_BIT
        | VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT
        | VK_IMAGE_USAGE_INPUT_ATTACHMENT_BIT;

    private final VulkanDevice device;
    private final long handle;
    private final long allocation;
    private final long view;
    private final long[] mipViews;
    private final long[] layerViews; // a 2D view of each layer's first level, for rendering into one layer
    private final int mips;
    private final int width;
    private final int height;
    private final int depth;
    private final int layers;
    private final int samples;
    private final int format;
    private boolean closed;

    public GpuImage(VulkanDevice device, int width, int height, int format, int usage, int aspect) {
        this(device, width, height, 1, 1, false, 1, format, usage, aspect, 1, new int[0]);
    }

    /**
     * An image used by several queue families at once without ownership transfers ({@code VK_SHARING_MODE_CONCURRENT}),
     * e.g. by the graphics and the async compute queue; one family or none is exclusive, as usual.
     */
    public GpuImage(VulkanDevice device, int width, int height, int format, int usage, int aspect,
        int... queueFamilies) {
        this(device, width, height, 1, 1, false, 1, format, usage, aspect, 1, queueFamilies);
    }

    /**
     * An image of {@code mips} mip levels (each half the one before, down to 1 x 1 at most), shared by
     * {@code queueFamilies} as above. Storage images get a view of each level ({@link #view(int)}), as storage
     * descriptors take one level at a time.
     */
    public static GpuImage mipmapped(VulkanDevice device, int width, int height, int format, int usage, int aspect,
        int mips, int... queueFamilies) {
        return new GpuImage(device, width, height, 1, 1, false, 1, format, usage, aspect, mips, queueFamilies);
    }

    /**
     * An image of {@code format}, {@code width} by {@code height} by {@code depth} texels (3D when {@code depth} is
     * more than 1), of {@code layers} array layers ({@code cube}: faces of cube maps, six per cube), {@code samples}
     * samples per texel and {@code mips} mip levels, shared by {@code queueFamilies} as above. Its views have the
     * matching type: 2D, 2D array, cube, cube array or 3D.
     */
    public static GpuImage create(VulkanDevice device, Format format, int width, int height, int depth, int layers,
        boolean cube, int samples, int mips, int usage, int... queueFamilies) {
        return new GpuImage(device, width, height, depth, layers, cube, samples, format.vk(), usage, format.aspect(),
            mips, queueFamilies);
    }

    /** How many mip levels a {@code width} by {@code height} image has, down to 1 x 1. */
    public static int fullMipCount(int width, int height) {
        return 32 - Integer.numberOfLeadingZeros(Math.max(width, height));
    }

    private GpuImage(VulkanDevice device, int width, int height, int depth, int layers, boolean cube, int samples,
        int format, int usage, int aspect, int mips, int[] families) {
        int[] queueFamilies = java.util.Arrays.stream(families).distinct().toArray(); // one family: exclusive
        if (width < 1 || height < 1 || depth < 1 || layers < 1) {
            throw new IllegalArgumentException("Image size and layers must be positive: " + width + " x " + height + " x "
                + depth + ", " + layers + " layers");
        }
        if (depth > 1 && (layers > 1 || cube || samples > 1)) {
            throw new IllegalArgumentException("A 3D image has one layer and one sample");
        }
        if (cube && layers > 6 && !device.cubeArrays()) {
            throw new IllegalArgumentException("This GPU has no cube map arrays (imageCubeArray): " + layers + " layers");
        }
        if (cube && (width != height || layers % 6 != 0)) {
            throw new IllegalArgumentException("Cube maps are square, six layers each: " + width + " x " + height + ", "
                + layers + " layers");
        }
        if (Integer.bitCount(samples) != 1 || samples > 64 || samples > 1 && mips > 1) {
            throw new IllegalArgumentException("Samples are a power of two up to 64, one mip level when more than one: "
                + samples);
        }
        if (mips < 1 || mips > fullMipCount(width, height)) {
            throw new IllegalArgumentException("A " + width + " x " + height + " image has 1 to "
                + fullMipCount(width, height) + " mip levels: " + mips);
        }
        this.mips = mips;
        this.device = device;
        this.width = width;
        this.height = height;
        this.depth = depth;
        this.layers = layers;
        this.samples = samples;
        this.format = format;
        int viewType = depth > 1 ? VK_IMAGE_VIEW_TYPE_3D : cube ? (layers == 6 ? VK_IMAGE_VIEW_TYPE_CUBE
            : VK_IMAGE_VIEW_TYPE_CUBE_ARRAY) : layers > 1 ? VK_IMAGE_VIEW_TYPE_2D_ARRAY : VK_IMAGE_VIEW_TYPE_2D;
        try (MemoryStack stack = stackPush()) {
            VkImageCreateInfo info = VkImageCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO)
                .flags(cube ? VK_IMAGE_CREATE_CUBE_COMPATIBLE_BIT : 0)
                .imageType(depth > 1 ? VK_IMAGE_TYPE_3D : VK_IMAGE_TYPE_2D)
                .format(format)
                .mipLevels(mips)
                .arrayLayers(layers)
                .samples(samples)
                .tiling(VK_IMAGE_TILING_OPTIMAL)
                .usage(usage)
                .sharingMode(queueFamilies.length > 1 ? VK_SHARING_MODE_CONCURRENT : VK_SHARING_MODE_EXCLUSIVE)
                .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            if (queueFamilies.length > 1) {
                info.pQueueFamilyIndices(stack.ints(queueFamilies));
            }
            info.extent().set(width, height, depth);
            LongBuffer image = stack.mallocLong(1);
            PointerBuffer allocated = stack.mallocPointer(1);
            Vulkan.check(vmaCreateImage(device.allocator(), info,
                VmaAllocationCreateInfo.calloc(stack).usage(VMA_MEMORY_USAGE_AUTO), image, allocated, null),
                "Could not allocate a " + width + " x " + height + " image");
            this.handle = image.get(0);
            this.allocation = allocated.get(0);
            // Views are for shaders and attachments; an image only copied to and from has none.
            int levelUsages = VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
            int attachments = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT;
            // A cube's levels are 2D arrays of its faces: shaders write a face through one (storage images are not
            // cubes), and read one level while writing another.
            this.mipViews = new long[(mips > 1 || cube) && (usage & levelUsages) != 0 ? mips : 0];
            this.layerViews = new long[layers > 1 && (usage & attachments) != 0 ? layers : 0];
            long whole = 0;
            try {
                whole = (usage & VIEW_USAGES) != 0
                    ? Images.createView(device, handle, format, aspect, viewType, 0, mips, layers) : 0;
                int levelType = cube ? VK_IMAGE_VIEW_TYPE_2D_ARRAY : viewType;
                for (int mip = 0; mip < mipViews.length; mip++) {
                    mipViews[mip] = Images.createView(device, handle, format, aspect, levelType, mip, 1, layers);
                }
                for (int layer = 0; layer < layerViews.length; layer++) {
                    layerViews[layer] = Images.createLayerView(device, handle, format, aspect, layer);
                }
            } catch (RuntimeException e) {
                destroy(device, whole, mipViews, layerViews, handle, allocation); // nothing made leaks
                throw e;
            }
            this.view = whole;
        }
        device.track(this);
    }

    public long handle() {
        return handle;
    }

    /** A view of the whole image; 0 for images used only for copies. */
    public long view() {
        return view;
    }

    /**
     * A view of mip level {@code mip} alone (of a mipmapped sampled or storage image, every layer; of a cube, a 2D array
     * of its faces), or the view of a one-level image that is no cube.
     */
    public long view(int mip) {
        return mipViews.length == 0 ? view : mipViews[mip];
    }

    /** A 2D view of layer {@code layer}'s first mip level (of a layered attachment), or the view of a one-layer image. */
    public long layerView(int layer) {
        return layers == 1 ? view : layerViews[layer];
    }

    public int mips() {
        return mips;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public int format() {
        return format;
    }

    /** Its depth in texels: more than 1 for a 3D image. */
    public int depth() {
        return depth;
    }

    /** Its array layers (six per cube map). */
    public int layers() {
        return layers;
    }

    /** Its samples per texel. */
    public int samples() {
        return samples;
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            destroy(device, view, mipViews, layerViews, handle, allocation);
            device.untrack(this);
        }
    }

    @Override
    public String toString() {
        return "GpuImage " + width + " x " + height + ", format " + format;
    }

    /** Destroys the views made (0s are skipped) and the image. */
    private static void destroy(VulkanDevice device, long view, long[] mipViews, long[] layerViews, long image,
        long allocation) {
        if (view != 0) {
            vkDestroyImageView(device.handle(), view, null);
        }
        for (long[] views : new long[][] {mipViews, layerViews}) {
            for (long each : views) {
                if (each != 0) {
                    vkDestroyImageView(device.handle(), each, null);
                }
            }
        }
        vmaDestroyImage(device.allocator(), image, allocation);
    }
}
