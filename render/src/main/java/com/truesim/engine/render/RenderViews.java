package com.truesim.engine.render;

import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.graph.ExternalBuffer;
import com.truesim.engine.vulkan.FrameBuffer;
import com.truesim.engine.vulkan.FrameSlots;
import com.truesim.engine.vulkan.GpuBuffer;
import com.truesim.engine.vulkan.Retiring;
import com.truesim.engine.vulkan.VulkanDevice;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.List;

/**
 * A {@link MeshletRenderer}'s open views and what the culling reads of all of them: the view constants array (each
 * view's {@code View} at its index, a copy per frame slot) and the rows the instance pass culls (a view and its phase
 * each). Both grow as views are made; the old ones go once no frame in flight reads them.
 */
final class RenderViews {

    private static final int STORAGE = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;

    private final VulkanDevice device;
    private final FrameSlots slots;
    private final Retiring retiring;
    private final List<RenderView> open = new ArrayList<>();
    private final BitSet indices = new BitSet(); // those of the open views
    private FrameBuffer constants; // the views' Views, one after another, by index
    private GpuBuffer rows;
    private final ExternalBuffer rowsImport; // the rows as an imported buffer of the frame graph

    RenderViews(VulkanDevice device, FrameSlots slots, Retiring retiring) {
        this.device = device;
        this.slots = slots;
        this.retiring = retiring;
        constants = FrameBuffer.create(device, 4L * RenderView.BYTES, STORAGE, slots);
        rows = GpuBuffer.deviceLocal(device, 8L * 4, STORAGE | VK_BUFFER_USAGE_TRANSFER_DST_BIT);
        rowsImport = new ExternalBuffer("culled rows", rows);
    }

    /** A new view of {@code renderer} as {@code options} say, at the lowest index free. */
    @AllowAllocation("making a view")
    RenderView create(MeshletRenderer renderer, ViewOptions options) {
        int index = indices.nextClearBit(0);
        if ((long) RenderView.BYTES * (index + 1) > constants.size()) {
            grow(2 * (index + 1));
        }
        RenderView view = new RenderView(device, renderer, options, index, slots);
        open.add(view);
        indices.set(index);
        return view;
    }

    /** Takes {@code view} (closed) out: its index is free, its buffers go once no frame in flight reads them. */
    @AllowAllocation("closing a view")
    void remove(RenderView view) {
        open.remove(view);
        indices.clear(view.index());
        view.freeBuffers(retiring);
    }

    /** A bigger constants array holding the views' entries, and rows; the old ones kept until no frame reads them. */
    private void grow(int capacity) {
        retiring.add(constants);
        retiring.add(rows);
        FrameBuffer old = constants;
        constants = FrameBuffer.create(device, (long) RenderView.BYTES * capacity, STORAGE, slots);
        constants.copyFrom(old);
        rows = GpuBuffer.deviceLocal(device, 8L * capacity, STORAGE | VK_BUFFER_USAGE_TRANSFER_DST_BIT);
        rowsImport.set(rows); // a new size: the graph rebuilds
    }

    /** The views' entries ({@link RenderView#BYTES} each, by {@link RenderView#index()}). */
    FrameBuffer constants() {
        return constants;
    }

    /** The rows the instance pass culls, a view and its phase each. */
    GpuBuffer rows() {
        return rows;
    }

    ExternalBuffer rowsImport() {
        return rowsImport;
    }

    /** The views open, in the order they were made. */
    List<RenderView> open() {
        return Collections.unmodifiableList(open);
    }

    /** The GPU memory of all views ({@link RenderView#bytes}), in bytes. */
    long bytes() {
        long bytes = 0;
        for (RenderView view : open) {
            bytes += view.bytes();
        }
        return bytes;
    }

    /** Frees every view's buffers and the shared ones: from the renderer's close, once the GPU is done. */
    @AllowAllocation("closing")
    void close() {
        for (RenderView view : open) {
            view.freeBuffers(retiring);
        }
        open.clear();
        constants.close();
        rows.close();
    }
}
