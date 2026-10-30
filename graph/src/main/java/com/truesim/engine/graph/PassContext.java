package com.truesim.engine.graph;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.memPutAddress;
import static org.lwjgl.system.MemoryUtil.memPutFloat;
import static org.lwjgl.system.MemoryUtil.memPutInt;
import static org.lwjgl.system.MemoryUtil.memPutLong;
import static org.lwjgl.vulkan.VK10.VK_ATTACHMENT_LOAD_OP_CLEAR;
import static org.lwjgl.vulkan.VK10.VK_ATTACHMENT_LOAD_OP_LOAD;
import static org.lwjgl.vulkan.VK10.VK_ATTACHMENT_STORE_OP_STORE;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_WHOLE_SIZE;
import static org.lwjgl.vulkan.VK12.VK_IMAGE_LAYOUT_DEPTH_ATTACHMENT_OPTIMAL;
import static org.lwjgl.vulkan.VK13.VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO;
import static org.lwjgl.vulkan.VK13.VK_STRUCTURE_TYPE_RENDERING_INFO;

import com.truesim.engine.vulkan.Commands;
import com.truesim.engine.vulkan.Bindless;
import com.truesim.engine.vulkan.Pipeline;
import com.truesim.engine.vulkan.Push;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkRect2D;
import org.lwjgl.vulkan.VkRenderingAttachmentInfo;
import org.lwjgl.vulkan.VkRenderingInfo;
import org.lwjgl.vulkan.VkViewport;

/**
 * What a {@link PassBody} records with: the command buffer, and the Vulkan objects behind its {@link Image}s and
 * {@link Buffer}s. Sampled and storage slots are the image's slots in the device's {@link Bindless} set, for shaders
 * to take in their push constants. Nothing allocates.
 *
 * <p>The usual commands come as helpers, so passes need no Vulkan of their own for them: dispatches and draws with a
 * {@link Push} of their push constants, fills and copies of buffers, and rendering into the pass's attachments. The
 * accesses the pass declared still decide the barriers; the helpers only record.
 *
 * <pre>{@code
 * g.pass("simulate").read(state, BufferAccess.STORAGE_READ).write(next, BufferAccess.STORAGE_WRITE).record(ctx ->
 *     ctx.dispatch(simulate, push.clear().address(ctx.address(state)).address(ctx.address(next)).u32(count),
 *         (count + 63) / 64));
 * g.pass("draw").write(color, Access.COLOR_ATTACHMENT).write(depth, Access.DEPTH_ATTACHMENT).record(ctx -> {
 *     ctx.beginRendering(color, depth, true, 0, 0, 0, 1);
 *     ctx.draw(points, push.clear().address(ctx.address(next)), count, 1);
 *     ctx.endRendering();
 * });
 * }</pre>
 */
public final class PassContext {

    private final FrameGraph graph;
    VkCommandBuffer commands;

    PassContext(FrameGraph graph) {
        this.graph = graph;
    }

    public VkCommandBuffer commands() {
        return commands;
    }

    /** The {@code VkImage}. */
    public long image(Image image) {
        return graph.handleOf(image.id);
    }

    /** A {@code VkImageView} of the whole image. */
    public long view(Image image) {
        return graph.viewOf(image.id);
    }

    /** The image's sampled slot, for images some pass samples. */
    public int sampled(Image image) {
        return graph.sampledSlotOf(image.id);
    }

    /** The image's storage slot, for images some pass uses as a storage image (level 0 of a mipmapped one). */
    public int storage(Image image) {
        return graph.storageSlotOf(image.id, 0);
    }

    /** The {@code VkBuffer} of a buffer of the graph, transient or imported. */
    public long buffer(Buffer buffer) {
        return graph.bufferHandle(buffer.id);
    }

    /** The device address of a buffer of the graph, for shaders to take in their push constants. */
    public long address(Buffer buffer) {
        return graph.bufferAddress(buffer.id);
    }

    /** Whether {@code history}'s last frame image holds what that frame wrote: not on its first frame. */
    public boolean historyValid(History history) {
        return graph.historyValid(history.index);
    }

    /** The storage slot of mip level {@code mip} of the image. */
    public int storage(Image image, int mip) {
        return graph.storageSlotOf(image.id, mip);
    }

    /**
     * The sampled slot of part of an image: all of it, or one mip level (every layer) of a mipmapped image, to read one
     * level while another is written.
     */
    public int sampled(Subresource part) {
        if (part.whole()) {
            return sampled(part.image());
        }
        if (!part.oneMip() || part.baseLayer() != 0 || part.layerCount() != part.image().desc().layers()) {
            throw new IllegalArgumentException("Sampling " + part + ": sample all of it, or one mip level of every layer");
        }
        return graph.sampledSlotOf(part.image().id, part.baseMip());
    }

    /** The storage slot of one mip level (every layer) of an image. */
    public int storage(Subresource part) {
        if (!part.oneMip() || part.baseLayer() != 0 || part.layerCount() != part.image().desc().layers()) {
            throw new IllegalArgumentException("Storing into " + part + ": one mip level of every layer");
        }
        return graph.storageSlotOf(part.image().id, part.baseMip());
    }

    /** A {@code VkImageView} of part of an image: all of it, or one layer's first mip level, to render into. */
    public long view(Subresource part) {
        if (part.whole()) {
            return view(part.image());
        }
        if (!part.oneLayer() || part.baseMip() != 0 || part.mipCount() != part.image().mips()) {
            throw new IllegalArgumentException("A view of " + part + ": all of it, or one layer");
        }
        return graph.layerViewOf(part.image().id, part.baseLayer());
    }

    // ---------------------------------------------------------------- commands

    /** The most workgroups a dispatch has per dimension on every device. */
    public static final int MAX_GROUPS = 65_535;

    /** Binds {@code pipeline} (a compute one), sets {@code push} and dispatches {@code groupsX} workgroups. */
    public void dispatch(Pipeline pipeline, Push push, int groupsX) {
        dispatch(pipeline, push, groupsX, 1, 1);
    }

    /**
     * Binds {@code pipeline} (a compute one), sets {@code push} and dispatches {@code x} by {@code y} by {@code z}
     * workgroups: none if any is 0, at most {@link #MAX_GROUPS} each.
     */
    public void dispatch(Pipeline pipeline, Push push, int x, int y, int z) {
        if (x < 0 || y < 0 || z < 0 || x > MAX_GROUPS || y > MAX_GROUPS || z > MAX_GROUPS) {
            throw new IllegalArgumentException("Workgroups are 0 to 65,535 per dimension: " + x + " x " + y + " x " + z);
        }
        if (x == 0 || y == 0 || z == 0) {
            return;
        }
        pipeline.bind(commands);
        pipeline.push(commands, push);
        Commands.dispatch(commands, x, y, z);
    }

    /**
     * Binds {@code pipeline} (a compute one), sets {@code push} and dispatches as the {@code VkDispatchIndirectCommand}
     * at {@code offset} of {@code buffer} says (read as {@link BufferAccess#INDIRECT}).
     */
    public void dispatchIndirect(Pipeline pipeline, Push push, Buffer buffer, long offset) {
        pipeline.bind(commands);
        pipeline.push(commands, push);
        Commands.dispatchIndirect(commands, buffer(buffer), offset);
    }

    /** Fills {@code buffer} with the 32-bit {@code value} (as {@link BufferAccess#TRANSFER_DST}). */
    public void fill(Buffer buffer, int value) {
        Commands.fillBuffer(commands, buffer(buffer), 0, VK_WHOLE_SIZE, value);
    }

    /** Copies the first {@code bytes} of {@code from} to the start of {@code to} (transfer source, destination). */
    public void copy(Buffer from, Buffer to, long bytes) {
        copy(from, 0, to, 0, bytes);
    }

    /** Copies {@code bytes} at {@code fromOffset} of {@code from} to {@code toOffset} of {@code to}. */
    public void copy(Buffer from, long fromOffset, Buffer to, long toOffset, long bytes) {
        if (fromOffset < 0 || toOffset < 0 || bytes < 0 || fromOffset + bytes > from.size()
            || toOffset + bytes > to.size()) {
            throw new IllegalArgumentException("Copying " + bytes + " bytes from " + fromOffset + " of " + from
                + " to " + toOffset + " of " + to);
        }
        if (bytes == 0) {
            return;
        }
        try (MemoryStack stack = stackPush()) {
            long region = stack.ncalloc(VkBufferCopy.ALIGNOF, 1, VkBufferCopy.SIZEOF);
            memPutLong(region + VkBufferCopy.SRCOFFSET, fromOffset);
            memPutLong(region + VkBufferCopy.DSTOFFSET, toOffset);
            memPutLong(region + VkBufferCopy.SIZE, bytes);
            Commands.copyBuffer(commands, buffer(from), buffer(to), 1, region);
        }
    }

    /**
     * Starts rendering into {@code color} (as {@link Access#COLOR_ATTACHMENT}; or null) and {@code depth} (as
     * {@link Access#DEPTH_ATTACHMENT}; or null), the viewport and scissor all of them. With {@code clear}, color is
     * cleared to ({@code r}, {@code g}, {@code b}, {@code a}) and depth to 0 (reverse depth: the far plane); otherwise
     * both keep what they hold. Draw, then {@link #endRendering}.
     */
    public void beginRendering(Image color, Image depth, boolean clear, float r, float g, float b, float a) {
        Image size = color != null ? color : depth;
        if (size == null || color != null && depth != null
            && (color.width != depth.width || color.height != depth.height)) {
            throw new IllegalArgumentException("Rendering into a color or depth image, or both of one size: " + color
                + ", " + depth);
        }
        try (MemoryStack stack = stackPush()) {
            long colorInfo = stack.ncalloc(VkRenderingAttachmentInfo.ALIGNOF, 1, VkRenderingAttachmentInfo.SIZEOF);
            long depthInfo = stack.ncalloc(VkRenderingAttachmentInfo.ALIGNOF, 1, VkRenderingAttachmentInfo.SIZEOF);
            if (color != null) {
                attachment(colorInfo, view(color), VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, clear);
                long value = colorInfo + VkRenderingAttachmentInfo.CLEARVALUE;
                memPutFloat(value, r);
                memPutFloat(value + 4, g);
                memPutFloat(value + 8, b);
                memPutFloat(value + 12, a);
            }
            if (depth != null) {
                attachment(depthInfo, view(depth), VK_IMAGE_LAYOUT_DEPTH_ATTACHMENT_OPTIMAL, clear);
            }
            long rendering = stack.ncalloc(VkRenderingInfo.ALIGNOF, 1, VkRenderingInfo.SIZEOF);
            memPutInt(rendering + VkRenderingInfo.STYPE, VK_STRUCTURE_TYPE_RENDERING_INFO);
            memPutInt(rendering + VkRenderingInfo.RENDERAREA + VkRect2D.EXTENT, size.width);
            memPutInt(rendering + VkRenderingInfo.RENDERAREA + VkRect2D.EXTENT + 4, size.height);
            memPutInt(rendering + VkRenderingInfo.LAYERCOUNT, 1);
            memPutInt(rendering + VkRenderingInfo.COLORATTACHMENTCOUNT, color == null ? 0 : 1);
            memPutAddress(rendering + VkRenderingInfo.PCOLORATTACHMENTS, colorInfo);
            memPutAddress(rendering + VkRenderingInfo.PDEPTHATTACHMENT, depth == null ? 0 : depthInfo);
            Commands.beginRendering(commands, rendering);
            long viewport = stack.ncalloc(VkViewport.ALIGNOF, 1, VkViewport.SIZEOF);
            memPutFloat(viewport + VkViewport.WIDTH, size.width);
            memPutFloat(viewport + VkViewport.HEIGHT, size.height);
            memPutFloat(viewport + VkViewport.MAXDEPTH, 1);
            Commands.setViewport(commands, 0, 1, viewport);
            long scissor = stack.ncalloc(VkRect2D.ALIGNOF, 1, VkRect2D.SIZEOF);
            memPutInt(scissor + VkRect2D.EXTENT, size.width);
            memPutInt(scissor + VkRect2D.EXTENT + 4, size.height);
            Commands.setScissor(commands, 0, 1, scissor);
        }
    }

    private static void attachment(long info, long view, int layout, boolean clear) {
        memPutInt(info + VkRenderingAttachmentInfo.STYPE, VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO);
        memPutLong(info + VkRenderingAttachmentInfo.IMAGEVIEW, view);
        memPutInt(info + VkRenderingAttachmentInfo.IMAGELAYOUT, layout);
        memPutInt(info + VkRenderingAttachmentInfo.LOADOP, clear ? VK_ATTACHMENT_LOAD_OP_CLEAR
            : VK_ATTACHMENT_LOAD_OP_LOAD);
        memPutInt(info + VkRenderingAttachmentInfo.STOREOP, VK_ATTACHMENT_STORE_OP_STORE);
    }

    /** Ends what {@link #beginRendering} started. */
    public void endRendering() {
        Commands.endRendering(commands);
    }

    /**
     * Binds {@code pipeline} (a graphics one), sets {@code push} and draws {@code vertices} vertices of
     * {@code instances} instances: the shaders pull their data by device address, so there are no vertex buffers.
     */
    public void draw(Pipeline pipeline, Push push, int vertices, int instances) {
        pipeline.bind(commands);
        pipeline.push(commands, push);
        Commands.draw(commands, vertices, instances, 0, 0);
    }

    /**
     * Binds {@code pipeline}, sets {@code push} and draws as the {@code draws} {@code VkDrawIndirectCommand}s from
     * {@code offset} of {@code buffer} say (read as {@link BufferAccess#INDIRECT}).
     */
    public void drawIndirect(Pipeline pipeline, Push push, Buffer buffer, long offset, int draws) {
        pipeline.bind(commands);
        pipeline.push(commands, push);
        Commands.drawIndirect(commands, buffer(buffer), offset, draws, 16);
    }
}
