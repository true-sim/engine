package com.truesim.engine.graph;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.memPutAddress;
import static org.lwjgl.system.MemoryUtil.memPutFloat;
import static org.lwjgl.system.MemoryUtil.memPutInt;
import static org.lwjgl.system.MemoryUtil.memPutLong;
import static org.lwjgl.vulkan.VK10.VK_FILTER_LINEAR;
import static org.lwjgl.vulkan.VK10.VK_FILTER_NEAREST;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_INDEX_TYPE_UINT32;
import static org.lwjgl.vulkan.VK10.VK_WHOLE_SIZE;
import static org.lwjgl.vulkan.VK13.VK_STRUCTURE_TYPE_BLIT_IMAGE_INFO_2;
import static org.lwjgl.vulkan.VK13.VK_STRUCTURE_TYPE_BUFFER_IMAGE_COPY_2;
import static org.lwjgl.vulkan.VK13.VK_STRUCTURE_TYPE_COPY_BUFFER_TO_IMAGE_INFO_2;
import static org.lwjgl.vulkan.VK13.VK_STRUCTURE_TYPE_COPY_IMAGE_TO_BUFFER_INFO_2;
import static org.lwjgl.vulkan.VK13.VK_STRUCTURE_TYPE_IMAGE_BLIT_2;

import com.truesim.engine.vulkan.Bindless;
import com.truesim.engine.vulkan.Commands;
import com.truesim.engine.vulkan.Pipeline;
import com.truesim.engine.vulkan.Push;
import com.truesim.engine.vulkan.PushConstants;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkBlitImageInfo2;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkBufferImageCopy2;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCopyBufferToImageInfo2;
import org.lwjgl.vulkan.VkCopyImageToBufferInfo2;
import org.lwjgl.vulkan.VkImageBlit2;
import org.lwjgl.vulkan.VkImageSubresourceLayers;
import org.lwjgl.vulkan.VkImageSubresourceRange;
import org.lwjgl.vulkan.VkRect2D;
import org.lwjgl.vulkan.VkViewport;

/**
 * What a {@link PassBody} records with: helpers for the usual commands, and the slots and addresses shaders take in
 * their push constants. Sampled and storage slots are the image's slots in the device's {@link Bindless} set. Nothing
 * allocates.
 *
 * <p>The helpers cover dispatches and draws with a {@link Push} of their push constants, fills and copies of buffers,
 * copies between buffers and images, blits and clears, viewports and scissors. The accesses the pass declared still
 * decide the barriers; the helpers only record. A pass with attachments records inside the rendering scope the graph
 * opens for it, the viewport and scissor all of its attachments. Raw Vulkan objects are behind {@link #unsafe()}, for
 * what the helpers do not cover. With validation on, a body that resolves an image or buffer its pass did not declare
 * fails.
 *
 * <pre>{@code
 * g.pass("simulate").read(state, BufferAccess.STORAGE_READ).write(next, BufferAccess.STORAGE_WRITE).record(ctx ->
 *     ctx.dispatch(simulate, push.clear().address(ctx.address(state)).address(ctx.address(next)).u32(count),
 *         (count + 63) / 64));
 * g.pass("draw").read(next, BufferAccess.STORAGE_READ).attach(color, Clear.color(0, 0, 0, 1))
 *     .attachDepth(depth, Clear.FAR)
 *     .record(ctx -> ctx.draw(points, push.clear().address(ctx.address(next)), count, 1));
 * }</pre>
 */
public final class PassContext {

    /** The most workgroups a dispatch has per dimension on every device. */
    public static final int MAX_GROUPS = 65_535;

    private final FrameGraph graph;
    private final UnsafePassContext unsafe;
    VkCommandBuffer commands;
    GraphBuilder.PassInfo pass;

    PassContext(FrameGraph graph) {
        this.graph = graph;
        this.unsafe = new UnsafePassContext(this);
    }

    /** The raw Vulkan objects, for what the helpers do not cover: the command buffer, and the handles behind resources. */
    public UnsafePassContext unsafe() {
        return unsafe;
    }

    /** The image's sampled slot, for images some pass samples. */
    public int sampled(Image image) {
        return graph.sampledSlotOf(declared(image).id);
    }

    /** The image's storage slot, for images some pass uses as a storage image (level 0 of a mipmapped one). */
    public int storage(Image image) {
        return graph.storageSlotOf(declared(image).id, 0);
    }

    /** The device address of a buffer of the graph, for shaders to take in their push constants. */
    public long address(Buffer buffer) {
        return graph.bufferAddress(declared(buffer).id);
    }

    /** Whether {@code history}'s last frame image holds what that frame wrote: not on its first frame. */
    public boolean historyValid(History history) {
        return graph.historyValid(history.index);
    }

    /** Whether {@code history}'s last frame buffer holds what that frame wrote: not on its first frame. */
    public boolean historyValid(BufferHistory history) {
        return graph.historyValid(history.pair);
    }

    /** The frame being recorded: a number one higher each frame of the graph. */
    public long frame() {
        return graph.frame();
    }

    /**
     * Whether {@code image} is a {@link GraphBuilder#persistent persistent image} made for this frame: its first, its
     * contents still zeros. Work kept in it (a filtered environment) is then done anew.
     */
    public boolean fresh(Image image) {
        return graph.freshImage(declared(image).id);
    }

    /** The storage slot of mip level {@code mip} of the image. */
    public int storage(Image image, int mip) {
        return graph.storageSlotOf(declared(image).id, mip);
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
        return graph.sampledSlotOf(declared(part.image()).id, part.baseMip());
    }

    /** The storage slot of one mip level (every layer) of an image. */
    public int storage(Subresource part) {
        if (!part.oneMip() || part.baseLayer() != 0 || part.layerCount() != part.image().desc().layers()) {
            throw new IllegalArgumentException("Storing into " + part + ": one mip level of every layer");
        }
        return graph.storageSlotOf(declared(part.image()).id, part.baseMip());
    }

    /** {@code image}, after checking (with validation on) that the recording pass declared it. */
    Image declared(Image image) {
        if (image.build != null && image.build != graph.currentBuild) {
            throw new IllegalStateException(image + " is of an earlier build: take it again as the graph builds");
        }
        if (pass != null && graph.checkDeclared && !contains(pass.images, image.id)) {
            throw new IllegalStateException(pass.name + " uses " + image + " without declaring it");
        }
        return image;
    }

    /** {@code buffer}, after checking (with validation on) that the recording pass declared it. */
    Buffer declared(Buffer buffer) {
        if (buffer.build != null && buffer.build != graph.currentBuild) {
            throw new IllegalStateException(buffer + " is of an earlier build: take it again as the graph builds");
        }
        if (pass != null && graph.checkDeclared && !contains(pass.buffers, buffer.id)) {
            throw new IllegalStateException(pass.name + " uses " + buffer + " without declaring it");
        }
        return buffer;
    }

    private static boolean contains(int[] ids, int id) {
        for (int i : ids) {
            if (i == id) {
                return true;
            }
        }
        return false;
    }

    long buffer(Buffer buffer) {
        return graph.bufferHandle(declared(buffer).id);
    }

    long image(Image image) {
        return graph.handleOf(declared(image).id);
    }

    long view(Image image) {
        return graph.viewOf(declared(image).id);
    }

    long view(Subresource part) {
        if (part.whole()) {
            return view(part.image());
        }
        if (!part.oneLayer() || part.baseMip() != 0 || part.mipCount() != part.image().mips()) {
            throw new IllegalArgumentException("A view of " + part + ": all of it, or one layer");
        }
        return graph.layerViewOf(declared(part.image()).id, part.baseLayer());
    }

    // ---------------------------------------------------------------- commands

    /** Binds {@code pipeline} (a compute one), sets {@code push} and dispatches {@code groupsX} workgroups. */
    public void dispatch(Pipeline pipeline, PushConstants push, int groupsX) {
        dispatch(pipeline, push, groupsX, 1, 1);
    }

    /**
     * Binds {@code pipeline} (a compute one), sets {@code push} and dispatches {@code x} by {@code y} by {@code z}
     * workgroups: none if any is 0, at most {@link #MAX_GROUPS} each.
     */
    public void dispatch(Pipeline pipeline, PushConstants push, int x, int y, int z) {
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
    public void dispatchIndirect(Pipeline pipeline, PushConstants push, Buffer buffer, long offset) {
        pipeline.bind(commands);
        pipeline.push(commands, push);
        Commands.dispatchIndirect(commands, buffer(buffer), offset);
    }

    /** Fills {@code buffer} with the 32-bit {@code value} (as {@link BufferAccess#TRANSFER_DST}). */
    public void fill(Buffer buffer, int value) {
        Commands.fillBuffer(commands, buffer(buffer), 0, VK_WHOLE_SIZE, value);
    }

    /** Fills {@code bytes} (a multiple of 4) at {@code offset} (a multiple of 4) of {@code buffer} with {@code value}. */
    public void fill(Buffer buffer, long offset, long bytes, int value) {
        if (offset < 0 || bytes < 0 || offset + bytes > buffer.size() || (offset & 3) != 0 || (bytes & 3) != 0) {
            throw new IllegalArgumentException("Filling " + bytes + " bytes at " + offset + " of " + buffer
                + ": multiples of 4 inside it");
        }
        if (bytes > 0) {
            Commands.fillBuffer(commands, buffer(buffer), offset, bytes, value);
        }
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
     * Copies tightly packed texels from {@code offset} of {@code from} into one mip level of {@code to} (its layers),
     * the image as {@link Access#TRANSFER_DST}.
     */
    public void copy(Buffer from, long offset, Subresource to) {
        oneMip("Copying into", to);
        try (MemoryStack stack = stackPush()) {
            long region = bufferImageCopy(stack, offset, to);
            long info = stack.ncalloc(VkCopyBufferToImageInfo2.ALIGNOF, 1, VkCopyBufferToImageInfo2.SIZEOF);
            memPutInt(info + VkCopyBufferToImageInfo2.STYPE, VK_STRUCTURE_TYPE_COPY_BUFFER_TO_IMAGE_INFO_2);
            memPutLong(info + VkCopyBufferToImageInfo2.SRCBUFFER, buffer(from));
            memPutLong(info + VkCopyBufferToImageInfo2.DSTIMAGE, image(to.image()));
            memPutInt(info + VkCopyBufferToImageInfo2.DSTIMAGELAYOUT, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
            memPutInt(info + VkCopyBufferToImageInfo2.REGIONCOUNT, 1);
            memPutAddress(info + VkCopyBufferToImageInfo2.PREGIONS, region);
            Commands.copyBufferToImage2(commands, info);
        }
    }

    /**
     * Copies one mip level of {@code from} (its layers, as {@link Access#TRANSFER_SRC}) to {@code offset} of
     * {@code to}, tightly packed.
     */
    public void copy(Subresource from, Buffer to, long offset) {
        oneMip("Copying from", from);
        copy(from, buffer(to), offset);
    }

    /** Copies one mip level of {@code from} to {@code offset} of the {@code VkBuffer} {@code to}. */
    private void copy(Subresource from, long to, long offset) {
        oneMip("Copying from", from);
        try (MemoryStack stack = stackPush()) {
            long region = bufferImageCopy(stack, offset, from);
            long info = stack.ncalloc(VkCopyImageToBufferInfo2.ALIGNOF, 1, VkCopyImageToBufferInfo2.SIZEOF);
            memPutInt(info + VkCopyImageToBufferInfo2.STYPE, VK_STRUCTURE_TYPE_COPY_IMAGE_TO_BUFFER_INFO_2);
            memPutLong(info + VkCopyImageToBufferInfo2.SRCIMAGE, image(from.image()));
            memPutInt(info + VkCopyImageToBufferInfo2.SRCIMAGELAYOUT, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
            memPutLong(info + VkCopyImageToBufferInfo2.DSTBUFFER, to);
            memPutInt(info + VkCopyImageToBufferInfo2.REGIONCOUNT, 1);
            memPutAddress(info + VkCopyImageToBufferInfo2.PREGIONS, region);
            Commands.copyImageToBuffer2(commands, info);
        }
    }

    /** Copies {@code plan}'s bytes from {@code offset} of {@code from} into its host memory of this frame. */
    void readback(ReadbackPlan plan, Buffer from, long offset) {
        long target = plan.target(graph.recordSlot(), graph.frame());
        try (MemoryStack stack = stackPush()) {
            long region = stack.ncalloc(VkBufferCopy.ALIGNOF, 1, VkBufferCopy.SIZEOF);
            memPutLong(region + VkBufferCopy.SRCOFFSET, offset);
            memPutLong(region + VkBufferCopy.SIZE, plan.bytes);
            Commands.copyBuffer(commands, buffer(from), target, 1, region);
        }
    }

    /** Copies {@code from}, one mip level, into {@code plan}'s host memory of this frame, tightly packed. */
    void readback(ReadbackPlan plan, Subresource from) {
        copy(from, plan.target(graph.recordSlot(), graph.frame()), 0);
    }

    private static long bufferImageCopy(MemoryStack stack, long offset, Subresource part) {
        if (offset < 0) {
            throw new IllegalArgumentException("A buffer offset of " + offset);
        }
        long region = stack.ncalloc(VkBufferImageCopy2.ALIGNOF, 1, VkBufferImageCopy2.SIZEOF);
        memPutInt(region + VkBufferImageCopy2.STYPE, VK_STRUCTURE_TYPE_BUFFER_IMAGE_COPY_2);
        memPutLong(region + VkBufferImageCopy2.BUFFEROFFSET, offset);
        layers(region + VkBufferImageCopy2.IMAGESUBRESOURCE, part);
        corner(region + VkBufferImageCopy2.IMAGEEXTENT, part);
        return region;
    }

    /**
     * Blits one mip level of {@code from} (as {@link Access#TRANSFER_SRC}) over one of {@code to} (as
     * {@link Access#TRANSFER_DST}), scaled to fit, with {@code linear} filtering or the nearest texels.
     */
    public void blit(Subresource from, Subresource to, boolean linear) {
        blit(from, Math.max(1, from.image().width >> from.baseMip()), Math.max(1, from.image().height >> from.baseMip()),
            to, linear);
    }

    /**
     * The width of the pass's render region in {@code image} ({@link GraphBuilder.PassBuilder#region()}): the region's
     * share of an image of its view's size, all of {@code image} in a pass of no one view or at a fixed resolution.
     * Changes every frame under dynamic resolution, so read it while recording, for dispatch sizes and push constants.
     */
    public int regionWidth(Image image) {
        return graph.regionWidth(pass.view, java.util.Objects.requireNonNull(image, "image")); // a size: needs no declaring
    }

    /** The height of the pass's render region in {@code image}. */
    public int regionHeight(Image image) {
        return graph.regionHeight(pass.view, java.util.Objects.requireNonNull(image, "image"));
    }

    /** The pass's view ({@link GraphBuilder#view()} of its technique), or null: the frame's, or shared work. */
    public ViewPort view() {
        return pass.view;
    }

    /**
     * Blits the top left {@code width} x {@code height} texels of {@code from} (a render region) into all of
     * {@code to}, filtered if {@code linear}.
     */
    public void blit(Subresource from, int width, int height, Subresource to, boolean linear) {
        oneMip("Blitting from", from);
        oneMip("Blitting into", to);
        if (from.layerCount() != to.layerCount()) {
            throw new IllegalArgumentException("Blitting " + from + " into " + to + ": as many layers");
        }
        try (MemoryStack stack = stackPush()) {
            long region = stack.ncalloc(VkImageBlit2.ALIGNOF, 1, VkImageBlit2.SIZEOF);
            memPutInt(region + VkImageBlit2.STYPE, VK_STRUCTURE_TYPE_IMAGE_BLIT_2);
            layers(region + VkImageBlit2.SRCSUBRESOURCE, from);
            memPutInt(region + VkImageBlit2.SRCOFFSETS + 12, width); // offsets[1]; offsets[0] stays at the origin
            memPutInt(region + VkImageBlit2.SRCOFFSETS + 16, height);
            memPutInt(region + VkImageBlit2.SRCOFFSETS + 20, 1);
            layers(region + VkImageBlit2.DSTSUBRESOURCE, to);
            corner(region + VkImageBlit2.DSTOFFSETS + 12, to);
            long info = stack.ncalloc(VkBlitImageInfo2.ALIGNOF, 1, VkBlitImageInfo2.SIZEOF);
            memPutInt(info + VkBlitImageInfo2.STYPE, VK_STRUCTURE_TYPE_BLIT_IMAGE_INFO_2);
            memPutLong(info + VkBlitImageInfo2.SRCIMAGE, image(from.image()));
            memPutInt(info + VkBlitImageInfo2.SRCIMAGELAYOUT, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
            memPutLong(info + VkBlitImageInfo2.DSTIMAGE, image(to.image()));
            memPutInt(info + VkBlitImageInfo2.DSTIMAGELAYOUT, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
            memPutInt(info + VkBlitImageInfo2.REGIONCOUNT, 1);
            memPutAddress(info + VkBlitImageInfo2.PREGIONS, region);
            memPutInt(info + VkBlitImageInfo2.FILTER, linear ? VK_FILTER_LINEAR : VK_FILTER_NEAREST);
            Commands.blitImage2(commands, info);
        }
    }

    /**
     * Clears {@code part} of a color image (as {@link Access#TRANSFER_DST}) to ({@code r}, {@code g}, {@code b},
     * {@code a}). Depth images clear as attachments, with {@link Clear}.
     */
    public void clear(Subresource part, float r, float g, float b, float a) {
        if (part.image().format.depth()) {
            throw new IllegalArgumentException("Clearing " + part + ": a color image; depth clears as an attachment");
        }
        try (MemoryStack stack = stackPush()) {
            long color = stack.ncalloc(16, 1, 16);
            memPutFloat(color, r);
            memPutFloat(color + 4, g);
            memPutFloat(color + 8, b);
            memPutFloat(color + 12, a);
            long range = stack.ncalloc(VkImageSubresourceRange.ALIGNOF, 1, VkImageSubresourceRange.SIZEOF);
            memPutInt(range + VkImageSubresourceRange.ASPECTMASK, part.image().format.aspect());
            memPutInt(range + VkImageSubresourceRange.BASEMIPLEVEL, part.baseMip());
            memPutInt(range + VkImageSubresourceRange.LEVELCOUNT, part.mipCount());
            memPutInt(range + VkImageSubresourceRange.BASEARRAYLAYER, part.baseLayer());
            memPutInt(range + VkImageSubresourceRange.LAYERCOUNT, part.layerCount());
            Commands.clearColorImage(commands, image(part.image()), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, color, 1,
                range);
        }
    }

    /** Sets the viewport to {@code width} by {@code height} at ({@code x}, {@code y}), depths 0 to 1. */
    public void viewport(float x, float y, float width, float height) {
        try (MemoryStack stack = stackPush()) {
            long viewport = stack.ncalloc(VkViewport.ALIGNOF, 1, VkViewport.SIZEOF);
            memPutFloat(viewport + VkViewport.X, x);
            memPutFloat(viewport + VkViewport.Y, y);
            memPutFloat(viewport + VkViewport.WIDTH, width);
            memPutFloat(viewport + VkViewport.HEIGHT, height);
            memPutFloat(viewport + VkViewport.MAXDEPTH, 1);
            Commands.setViewport(commands, 0, 1, viewport);
        }
    }

    /** Sets the scissor to {@code width} by {@code height} pixels at ({@code x}, {@code y}). */
    public void scissor(int x, int y, int width, int height) {
        if (x < 0 || y < 0 || width < 0 || height < 0) {
            throw new IllegalArgumentException("A scissor of " + width + " x " + height + " at " + x + ", " + y);
        }
        try (MemoryStack stack = stackPush()) {
            long scissor = stack.ncalloc(VkRect2D.ALIGNOF, 1, VkRect2D.SIZEOF);
            memPutInt(scissor + VkRect2D.OFFSET, x);
            memPutInt(scissor + VkRect2D.OFFSET + 4, y);
            memPutInt(scissor + VkRect2D.EXTENT, width);
            memPutInt(scissor + VkRect2D.EXTENT + 4, height);
            Commands.setScissor(commands, 0, 1, scissor);
        }
    }

    /**
     * Binds {@code pipeline} (a graphics one), sets {@code push} and draws {@code vertices} vertices of
     * {@code instances} instances: the shaders pull their data by device address, so there are no vertex buffers.
     */
    public void draw(Pipeline pipeline, PushConstants push, int vertices, int instances) {
        pipeline.bind(commands);
        pipeline.push(commands, push);
        Commands.draw(commands, vertices, instances, 0, 0);
    }

    /**
     * Binds {@code pipeline}, sets {@code push} and draws as the {@code draws} {@code VkDrawIndirectCommand}s from
     * {@code offset} of {@code buffer} say (read as {@link BufferAccess#INDIRECT}).
     */
    public void drawIndirect(Pipeline pipeline, PushConstants push, Buffer buffer, long offset, int draws) {
        pipeline.bind(commands);
        pipeline.push(commands, push);
        Commands.drawIndirect(commands, buffer(buffer), offset, draws, 16);
    }

    /** Binds 32-bit indices from {@code offset} of {@code buffer} for indexed draws (read as {@link BufferAccess#INDEX}). */
    public void indices(Buffer buffer, long offset) {
        Commands.bindIndexBuffer(commands, buffer(buffer), offset, VK_INDEX_TYPE_UINT32);
    }

    /**
     * Binds {@code pipeline}, sets {@code push} and draws as the {@code draws} {@code VkDrawIndexedIndirectCommand}s
     * from {@code offset} of {@code buffer} say, with the {@link #indices} bound.
     */
    public void drawIndexedIndirect(Pipeline pipeline, PushConstants push, Buffer buffer, long offset, int draws) {
        pipeline.bind(commands);
        pipeline.push(commands, push);
        Commands.drawIndexedIndirect(commands, buffer(buffer), offset, draws, 20);
    }

    /**
     * Binds {@code pipeline}, sets {@code push} and draws as the {@code VkDrawIndexedIndirectCommand}s from
     * {@code offset} of {@code buffer} say: as many as the 32-bit count at {@code countOffset} of {@code count}, at most
     * {@code maxDraws}.
     */
    public void drawIndexedIndirectCount(Pipeline pipeline, PushConstants push, Buffer buffer, long offset, Buffer count,
        long countOffset, int maxDraws) {
        pipeline.bind(commands);
        pipeline.push(commands, push);
        Commands.drawIndexedIndirectCount(commands, buffer(buffer), offset, buffer(count), countOffset, maxDraws, 20);
    }

    /**
     * Binds {@code pipeline} (a mesh one), sets {@code push} and draws as the {@code draws}
     * {@code VkDrawMeshTasksIndirectCommandEXT}s from {@code offset} of {@code buffer} say.
     */
    public void drawMeshTasksIndirect(Pipeline pipeline, PushConstants push, Buffer buffer, long offset, int draws) {
        pipeline.bind(commands);
        pipeline.push(commands, push);
        Commands.drawMeshTasksIndirect(commands, buffer(buffer), offset, draws, 12);
    }

    private static void oneMip(String what, Subresource part) {
        if (!part.oneMip()) {
            throw new IllegalArgumentException(what + " " + part + ": one mip level");
        }
    }

    private static void layers(long layers, Subresource part) {
        memPutInt(layers + VkImageSubresourceLayers.ASPECTMASK, part.image().format.aspect());
        memPutInt(layers + VkImageSubresourceLayers.MIPLEVEL, part.baseMip());
        memPutInt(layers + VkImageSubresourceLayers.BASEARRAYLAYER, part.baseLayer());
        memPutInt(layers + VkImageSubresourceLayers.LAYERCOUNT, part.layerCount());
    }

    /** The size of a part's mip level, as a {@code VkOffset3D} or {@code VkExtent3D}. */
    private static void corner(long at, Subresource part) {
        memPutInt(at, Math.max(1, part.image().width >> part.baseMip()));
        memPutInt(at + 4, Math.max(1, part.image().height >> part.baseMip()));
        memPutInt(at + 8, 1);
    }
}
