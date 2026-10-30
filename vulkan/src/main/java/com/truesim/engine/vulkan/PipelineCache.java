package com.truesim.engine.vulkan;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.VK_NULL_HANDLE;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_PIPELINE_CACHE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.vkCreatePipelineCache;
import static org.lwjgl.vulkan.VK10.vkDestroyPipelineCache;
import static org.lwjgl.vulkan.VK10.vkGetPhysicalDeviceProperties;
import static org.lwjgl.vulkan.VK10.vkGetPipelineCacheData;
import static org.lwjgl.vulkan.VK10.vkMergePipelineCaches;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Objects;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.vulkan.VkPipelineCacheCreateInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The device's {@code VkPipelineCache}: every {@link Pipeline} is made through it, so a pipeline the driver compiled once
 * is not compiled again, and with {@link #persist} across runs too. What is loaded from disk is checked against this
 * GPU and driver first (the cache header's vendor, device and cache UUID); a file from another GPU, another driver or
 * damaged is ignored, and replaced when the cache is saved.
 *
 * <pre>{@code
 * device.pipelineCache().persist(Path.of("cache", "pipelines.bin")); // loads it now, saves it when the device closes
 * }</pre>
 */
public final class PipelineCache implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(PipelineCache.class);
    /** {@code VkPipelineCacheHeaderVersionOne}: length, version, vendor, device, then the 16-byte cache UUID. */
    private static final int HEADER_BYTES = 32;
    private static final int HEADER_VERSION_ONE = 1;

    private final VulkanDevice device;
    private final long handle;
    private final int vendor;
    private final int deviceId;
    private final byte[] uuid = new byte[16];
    private Path file;
    private boolean closed;

    PipelineCache(VulkanDevice device) {
        this.device = device;
        try (MemoryStack stack = stackPush()) {
            VkPhysicalDeviceProperties properties = VkPhysicalDeviceProperties.calloc(stack);
            vkGetPhysicalDeviceProperties(device.gpu().handle(), properties);
            this.vendor = properties.vendorID();
            this.deviceId = properties.deviceID();
            properties.pipelineCacheUUID().get(uuid);
            this.handle = create(stack, null);
        }
    }

    private long create(MemoryStack stack, ByteBuffer data) {
        VkPipelineCacheCreateInfo info = VkPipelineCacheCreateInfo.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_PIPELINE_CACHE_CREATE_INFO)
            .pInitialData(data);
        var cache = stack.mallocLong(1);
        Vulkan.check(vkCreatePipelineCache(device.handle(), info, null, cache), "Could not create a pipeline cache");
        return cache.get(0);
    }

    /** The {@code VkPipelineCache}. */
    public long handle() {
        return handle;
    }

    /**
     * Adds what {@code data} holds, if it was saved by this GPU and driver; returns whether it was. Data from elsewhere
     * is ignored: drivers are not required to reject it safely.
     */
    public boolean load(byte[] data) {
        if (!fits(data)) {
            return false;
        }
        ByteBuffer buffer = MemoryUtil.memAlloc(data.length);
        try (MemoryStack stack = stackPush()) {
            buffer.put(0, data);
            long loaded = create(stack, buffer);
            try {
                Vulkan.check(vkMergePipelineCaches(device.handle(), handle, stack.longs(loaded)),
                    "Could not merge pipeline caches");
            } finally {
                vkDestroyPipelineCache(device.handle(), loaded, null);
            }
            return true;
        } finally {
            MemoryUtil.memFree(buffer);
        }
    }

    /** Whether {@code data} starts with the header this GPU and driver write. */
    boolean fits(byte[] data) {
        if (data.length < HEADER_BYTES) {
            return false;
        }
        ByteBuffer header = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        return header.getInt(0) >= HEADER_BYTES && header.getInt(4) == HEADER_VERSION_ONE
            && header.getInt(8) == vendor && header.getInt(12) == deviceId
            && Arrays.equals(data, 16, HEADER_BYTES, uuid, 0, uuid.length);
    }

    /** What the cache holds now, to save. */
    public byte[] data() {
        try (MemoryStack stack = stackPush()) {
            PointerBuffer size = stack.mallocPointer(1);
            Vulkan.check(vkGetPipelineCacheData(device.handle(), handle, size, null),
                "Could not size the pipeline cache");
            ByteBuffer buffer = MemoryUtil.memAlloc((int) size.get(0));
            try {
                Vulkan.check(vkGetPipelineCacheData(device.handle(), handle, size, buffer),
                    "Could not read the pipeline cache");
                byte[] data = new byte[(int) size.get(0)];
                buffer.get(0, data);
                return data;
            } finally {
                MemoryUtil.memFree(buffer);
            }
        }
    }

    /**
     * Keeps the cache in {@code file}: loads it now if it is there (and from this GPU and driver), and saves it there
     * when the device closes. A file that cannot be read is ignored, with a warning: pipelines are then compiled anew.
     */
    public void persist(Path file) {
        this.file = Objects.requireNonNull(file, "file");
        if (Files.isRegularFile(file)) {
            try {
                if (!load(Files.readAllBytes(file))) {
                    LOG.info("The pipeline cache {} is from another GPU or driver: it is rebuilt", file);
                }
            } catch (IOException e) {
                LOG.warn("Could not read the pipeline cache {}: it is rebuilt", file, e);
            }
        }
    }

    /** Saves the cache to {@code file}, replacing it whole, so a crash never leaves half a file. */
    public void save(Path file) {
        try {
            Path parent = file.toAbsolutePath().getParent();
            Files.createDirectories(parent);
            Path temp = Files.createTempFile(parent, file.getFileName().toString(), ".tmp");
            Files.write(temp, data());
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not save the pipeline cache to " + file, e);
        }
    }

    /** Saves to the {@link #persist} file, if any (a failure is logged), and destroys the cache. */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (file != null) {
            try {
                save(file);
            } catch (UncheckedIOException e) {
                LOG.warn("Could not save the pipeline cache", e);
            }
        }
        if (handle != VK_NULL_HANDLE) {
            vkDestroyPipelineCache(device.handle(), handle, null);
        }
    }
}
