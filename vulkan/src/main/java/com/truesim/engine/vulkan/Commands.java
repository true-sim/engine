package com.truesim.engine.vulkan;

import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import org.lwjgl.vulkan.VKCapabilitiesDevice;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkQueue;

/**
 * The Vulkan commands of every frame, called straight through the foreign function API so they allocate nothing
 * whatever the JIT decides. LWJGL wraps each function's address in a {@code MemorySegment} per call, which the JIT
 * removes only where it inlines the whole call; how much it inlines changes with the code around it, so a frame that
 * allocated nothing yesterday may allocate today. Here each function's address is wrapped once and kept (again only when
 * a device's address differs). Arguments are as for LWJGL's {@code n}-prefixed methods: structs and arrays by address.
 */
public final class Commands {

    private static final Linker LINKER = Linker.nativeLinker();

    /** A function's address and its segment, made once. */
    private record Fn(long address, MemorySegment segment) {
    }

    private static final Fn NONE = new Fn(0, MemorySegment.NULL);
    private static final int BARRIER = 0;
    private static final int BIND_PIPELINE = 1;
    private static final int PUSH = 2;
    private static final int DISPATCH = 3;
    private static final int DISPATCH_INDIRECT = 4;
    private static final int UPDATE_BUFFER = 5;
    private static final int FILL_BUFFER = 6;
    private static final int COPY_BUFFER = 7;
    private static final int BEGIN_RENDERING = 8;
    private static final int END_RENDERING = 9;
    private static final int VIEWPORT = 10;
    private static final int SCISSOR = 11;
    private static final int BIND_SETS = 12;
    private static final int BIND_INDEX = 13;
    private static final int DRAW = 14;
    private static final int DRAW_INDIRECT = 15;
    private static final int DRAW_INDEXED_INDIRECT = 16;
    private static final int DRAW_INDEXED_INDIRECT_COUNT = 17;
    private static final int DRAW_MESH_TASKS_INDIRECT = 18;
    private static final int SUBMIT = 19;
    private static final int COPY_BUFFER_TO_IMAGE = 20;
    private static final int COPY_IMAGE_TO_BUFFER = 21;
    private static final int BLIT = 22;
    private static final int CLEAR_COLOR = 23;
    private static final int BEGIN_LABEL = 24;
    private static final int END_LABEL = 25;
    private static final int TIMESTAMP = 26;
    private static final int RESET_QUERIES = 27;
    private static final int QUERY_RESULTS = 28;
    // Immutable entries: a thread seeing an older one only wraps the address again.
    private static final Fn[] FUNCTIONS = new Fn[29];

    static {
        java.util.Arrays.fill(FUNCTIONS, NONE);
    }

    private static final Linker.Option CRITICAL = Linker.Option.critical(false);
    private static final MethodHandle H_BARRIER = handle(FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_LONG));
    private static final MethodHandle H_BIND_PIPELINE =
        handle(FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_INT, JAVA_LONG));
    private static final MethodHandle H_PUSH =
        handle(FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_LONG));
    private static final MethodHandle H_DISPATCH =
        handle(FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT));
    private static final MethodHandle H_DISPATCH_INDIRECT =
        handle(FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_LONG, JAVA_LONG));
    private static final MethodHandle H_UPDATE_BUFFER =
        handle(FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG));
    private static final MethodHandle H_FILL_BUFFER =
        handle(FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT));
    private static final MethodHandle H_COPY_BUFFER =
        handle(FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_LONG));
    private static final MethodHandle H_ONE_POINTER = handle(FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_LONG));
    private static final MethodHandle H_NONE = handle(FunctionDescriptor.ofVoid(JAVA_LONG));
    private static final MethodHandle H_RECTS =
        handle(FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_LONG));
    private static final MethodHandle H_BIND_SETS = handle(FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_INT, JAVA_LONG,
        JAVA_INT, JAVA_INT, JAVA_LONG, JAVA_INT, JAVA_LONG));
    private static final MethodHandle H_BIND_INDEX =
        handle(FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT));
    private static final MethodHandle H_DRAW =
        handle(FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT));
    private static final MethodHandle H_DRAW_INDIRECT =
        handle(FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT));
    private static final MethodHandle H_DRAW_INDIRECT_COUNT = handle(FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_LONG,
        JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT));
    private static final MethodHandle H_CLEAR_COLOR = handle(FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_LONG,
        JAVA_INT, JAVA_LONG, JAVA_INT, JAVA_LONG));
    private static final MethodHandle H_TIMESTAMP =
        handle(FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT));
    private static final MethodHandle H_RESET_QUERIES =
        handle(FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT));
    private static final MethodHandle H_QUERY_RESULTS = handle(FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG,
        JAVA_INT, JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT));
    private static final MethodHandle H_SUBMIT = LINKER.downcallHandle(
        FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_LONG));

    private Commands() {
    }

    private static MethodHandle handle(FunctionDescriptor descriptor) {
        return LINKER.downcallHandle(descriptor, CRITICAL);
    }

    /** Function {@code slot} at {@code address}: wrapped the first time, and when a device has it elsewhere. */
    private static MemorySegment function(int slot, long address) {
        Fn fn = FUNCTIONS[slot];
        if (fn.address != address) {
            fn = new Fn(address, MemorySegment.ofAddress(address));
            FUNCTIONS[slot] = fn;
        }
        return fn.segment;
    }

    private static VKCapabilitiesDevice of(VkCommandBuffer commands) {
        return commands.getCapabilities();
    }

    /** {@code vkCmdPipelineBarrier2} with the {@code VkDependencyInfo} at {@code dependency}. */
    public static void pipelineBarrier2(VkCommandBuffer commands, long dependency) {
        try {
            H_BARRIER.invokeExact(function(BARRIER, of(commands).vkCmdPipelineBarrier2), commands.address(),
                dependency);
        } catch (Throwable e) {
            throw failed(e);
        }
    }

    /** {@code vkCmdBindPipeline}. */
    public static void bindPipeline(VkCommandBuffer commands, int bindPoint, long pipeline) {
        try {
            H_BIND_PIPELINE.invokeExact(function(BIND_PIPELINE, of(commands).vkCmdBindPipeline), commands.address(),
                bindPoint, pipeline);
        } catch (Throwable e) {
            throw failed(e);
        }
    }

    /** {@code vkCmdPushConstants} of {@code size} bytes at {@code values}. */
    public static void pushConstants(VkCommandBuffer commands, long layout, int stages, int offset, int size,
        long values) {
        try {
            H_PUSH.invokeExact(function(PUSH, of(commands).vkCmdPushConstants), commands.address(), layout, stages,
                offset, size, values);
        } catch (Throwable e) {
            throw failed(e);
        }
    }

    /** {@code vkCmdDispatch}. */
    public static void dispatch(VkCommandBuffer commands, int x, int y, int z) {
        try {
            H_DISPATCH.invokeExact(function(DISPATCH, of(commands).vkCmdDispatch), commands.address(), x, y, z);
        } catch (Throwable e) {
            throw failed(e);
        }
    }

    /** {@code vkCmdDispatchIndirect}. */
    public static void dispatchIndirect(VkCommandBuffer commands, long buffer, long offset) {
        try {
            H_DISPATCH_INDIRECT.invokeExact(function(DISPATCH_INDIRECT, of(commands).vkCmdDispatchIndirect),
                commands.address(), buffer, offset);
        } catch (Throwable e) {
            throw failed(e);
        }
    }

    /** {@code vkCmdUpdateBuffer} with {@code size} bytes at {@code data}. */
    public static void updateBuffer(VkCommandBuffer commands, long buffer, long offset, long size, long data) {
        try {
            H_UPDATE_BUFFER.invokeExact(function(UPDATE_BUFFER, of(commands).vkCmdUpdateBuffer), commands.address(),
                buffer, offset, size, data);
        } catch (Throwable e) {
            throw failed(e);
        }
    }

    /** {@code vkCmdFillBuffer}. */
    public static void fillBuffer(VkCommandBuffer commands, long buffer, long offset, long size, int data) {
        try {
            H_FILL_BUFFER.invokeExact(function(FILL_BUFFER, of(commands).vkCmdFillBuffer), commands.address(), buffer,
                offset, size, data);
        } catch (Throwable e) {
            throw failed(e);
        }
    }

    /** {@code vkCmdCopyBuffer} with {@code regionCount} {@code VkBufferCopy}s at {@code regions}. */
    public static void copyBuffer(VkCommandBuffer commands, long source, long destination, int regionCount,
        long regions) {
        try {
            H_COPY_BUFFER.invokeExact(function(COPY_BUFFER, of(commands).vkCmdCopyBuffer), commands.address(), source,
                destination, regionCount, regions);
        } catch (Throwable e) {
            throw failed(e);
        }
    }

    /** {@code vkCmdBeginRendering} with the {@code VkRenderingInfo} at {@code info}. */
    public static void beginRendering(VkCommandBuffer commands, long info) {
        try {
            H_ONE_POINTER.invokeExact(function(BEGIN_RENDERING, of(commands).vkCmdBeginRendering), commands.address(),
                info);
        } catch (Throwable e) {
            throw failed(e);
        }
    }

    /** {@code vkCmdEndRendering}. */
    public static void endRendering(VkCommandBuffer commands) {
        try {
            H_NONE.invokeExact(function(END_RENDERING, of(commands).vkCmdEndRendering), commands.address());
        } catch (Throwable e) {
            throw failed(e);
        }
    }

    /** {@code vkCmdSetViewport} with {@code count} {@code VkViewport}s at {@code viewports}. */
    public static void setViewport(VkCommandBuffer commands, int first, int count, long viewports) {
        try {
            H_RECTS.invokeExact(function(VIEWPORT, of(commands).vkCmdSetViewport), commands.address(), first, count,
                viewports);
        } catch (Throwable e) {
            throw failed(e);
        }
    }

    /** {@code vkCmdSetScissor} with {@code count} {@code VkRect2D}s at {@code scissors}. */
    public static void setScissor(VkCommandBuffer commands, int first, int count, long scissors) {
        try {
            H_RECTS.invokeExact(function(SCISSOR, of(commands).vkCmdSetScissor), commands.address(), first, count,
                scissors);
        } catch (Throwable e) {
            throw failed(e);
        }
    }

    /** {@code vkCmdBindDescriptorSets} with {@code count} sets at {@code sets}, no dynamic offsets. */
    public static void bindDescriptorSets(VkCommandBuffer commands, int bindPoint, long layout, int firstSet,
        int count, long sets) {
        try {
            H_BIND_SETS.invokeExact(function(BIND_SETS, of(commands).vkCmdBindDescriptorSets), commands.address(),
                bindPoint, layout, firstSet, count, sets, 0, 0L);
        } catch (Throwable e) {
            throw failed(e);
        }
    }

    /** {@code vkCmdBindIndexBuffer}. */
    public static void bindIndexBuffer(VkCommandBuffer commands, long buffer, long offset, int indexType) {
        try {
            H_BIND_INDEX.invokeExact(function(BIND_INDEX, of(commands).vkCmdBindIndexBuffer), commands.address(),
                buffer, offset, indexType);
        } catch (Throwable e) {
            throw failed(e);
        }
    }

    /** {@code vkCmdDraw}. */
    public static void draw(VkCommandBuffer commands, int vertices, int instances, int firstVertex,
        int firstInstance) {
        try {
            H_DRAW.invokeExact(function(DRAW, of(commands).vkCmdDraw), commands.address(), vertices, instances,
                firstVertex, firstInstance);
        } catch (Throwable e) {
            throw failed(e);
        }
    }

    /** {@code vkCmdDrawIndirect}. */
    public static void drawIndirect(VkCommandBuffer commands, long buffer, long offset, int draws, int stride) {
        try {
            H_DRAW_INDIRECT.invokeExact(function(DRAW_INDIRECT, of(commands).vkCmdDrawIndirect), commands.address(),
                buffer, offset, draws, stride);
        } catch (Throwable e) {
            throw failed(e);
        }
    }

    /** {@code vkCmdDrawIndexedIndirect}. */
    public static void drawIndexedIndirect(VkCommandBuffer commands, long buffer, long offset, int draws,
        int stride) {
        try {
            H_DRAW_INDIRECT.invokeExact(function(DRAW_INDEXED_INDIRECT, of(commands).vkCmdDrawIndexedIndirect),
                commands.address(), buffer, offset, draws, stride);
        } catch (Throwable e) {
            throw failed(e);
        }
    }

    /** {@code vkCmdDrawIndexedIndirectCount}. */
    public static void drawIndexedIndirectCount(VkCommandBuffer commands, long buffer, long offset, long countBuffer,
        long countOffset, int maxDraws, int stride) {
        try {
            H_DRAW_INDIRECT_COUNT.invokeExact(function(DRAW_INDEXED_INDIRECT_COUNT,
                of(commands).vkCmdDrawIndexedIndirectCount), commands.address(), buffer, offset, countBuffer,
                countOffset, maxDraws, stride);
        } catch (Throwable e) {
            throw failed(e);
        }
    }

    /** {@code vkCmdDrawMeshTasksIndirectEXT}. */
    public static void drawMeshTasksIndirect(VkCommandBuffer commands, long buffer, long offset, int draws,
        int stride) {
        try {
            H_DRAW_INDIRECT.invokeExact(function(DRAW_MESH_TASKS_INDIRECT, of(commands).vkCmdDrawMeshTasksIndirectEXT),
                commands.address(), buffer, offset, draws, stride);
        } catch (Throwable e) {
            throw failed(e);
        }
    }

    /** {@code vkCmdCopyBufferToImage2} with the {@code VkCopyBufferToImageInfo2} at {@code info}. */
    public static void copyBufferToImage2(VkCommandBuffer commands, long info) {
        try {
            H_ONE_POINTER.invokeExact(function(COPY_BUFFER_TO_IMAGE, of(commands).vkCmdCopyBufferToImage2),
                commands.address(), info);
        } catch (Throwable e) {
            throw failed(e);
        }
    }

    /** {@code vkCmdCopyImageToBuffer2} with the {@code VkCopyImageToBufferInfo2} at {@code info}. */
    public static void copyImageToBuffer2(VkCommandBuffer commands, long info) {
        try {
            H_ONE_POINTER.invokeExact(function(COPY_IMAGE_TO_BUFFER, of(commands).vkCmdCopyImageToBuffer2),
                commands.address(), info);
        } catch (Throwable e) {
            throw failed(e);
        }
    }

    /** {@code vkCmdBlitImage2} with the {@code VkBlitImageInfo2} at {@code info}. */
    public static void blitImage2(VkCommandBuffer commands, long info) {
        try {
            H_ONE_POINTER.invokeExact(function(BLIT, of(commands).vkCmdBlitImage2), commands.address(), info);
        } catch (Throwable e) {
            throw failed(e);
        }
    }

    /** {@code vkCmdClearColorImage} with the {@code VkClearColorValue} at {@code color}. */
    public static void clearColorImage(VkCommandBuffer commands, long image, int layout, long color, int rangeCount,
        long ranges) {
        try {
            H_CLEAR_COLOR.invokeExact(function(CLEAR_COLOR, of(commands).vkCmdClearColorImage), commands.address(),
                image, layout, color, rangeCount, ranges);
        } catch (Throwable e) {
            throw failed(e);
        }
    }

    /**
     * {@code vkCmdBeginDebugUtilsLabelEXT} with the {@code VkDebugUtilsLabelEXT} at {@code label}, where the instance
     * has debug utils (with validation, say); otherwise nothing. Returns whether it began a label.
     */
    public static boolean beginLabel(VkCommandBuffer commands, long label) {
        long address = commands.getDevice().getPhysicalDevice().getInstance().getCapabilities()
            .vkCmdBeginDebugUtilsLabelEXT;
        if (address == 0) {
            return false;
        }
        try {
            H_ONE_POINTER.invokeExact(function(BEGIN_LABEL, address), commands.address(), label);
        } catch (Throwable e) {
            throw failed(e);
        }
        return true;
    }

    /** {@code vkCmdEndDebugUtilsLabelEXT}, ending what {@link #beginLabel} began. */
    public static void endLabel(VkCommandBuffer commands) {
        long address = commands.getDevice().getPhysicalDevice().getInstance().getCapabilities()
            .vkCmdEndDebugUtilsLabelEXT;
        try {
            H_NONE.invokeExact(function(END_LABEL, address), commands.address());
        } catch (Throwable e) {
            throw failed(e);
        }
    }

    /** {@code vkCmdWriteTimestamp2}. */
    public static void writeTimestamp2(VkCommandBuffer commands, long stage, long pool, int query) {
        try {
            H_TIMESTAMP.invokeExact(function(TIMESTAMP, of(commands).vkCmdWriteTimestamp2), commands.address(), stage,
                pool, query);
        } catch (Throwable e) {
            throw failed(e);
        }
    }

    /** {@code vkResetQueryPool}, from the host. */
    public static void resetQueryPool(VkDevice device, long pool, int first, int count) {
        try {
            H_RESET_QUERIES.invokeExact(function(RESET_QUERIES, device.getCapabilities().vkResetQueryPool),
                device.address(), pool, first, count);
        } catch (Throwable e) {
            throw failed(e);
        }
    }

    /** {@code vkGetQueryPoolResults} into the {@code size} bytes at {@code data}; returns the result. */
    public static int getQueryPoolResults(VkDevice device, long pool, int first, int count, long size, long data,
        long stride, int flags) {
        try {
            return (int) H_QUERY_RESULTS.invokeExact(function(QUERY_RESULTS,
                device.getCapabilities().vkGetQueryPoolResults), device.address(), pool, first, count, size, data,
                stride, flags);
        } catch (Throwable e) {
            throw failed(e);
        }
    }

    /** {@code vkQueueSubmit2} with {@code count} {@code VkSubmitInfo2}s at {@code submits}; returns the result. */
    public static int queueSubmit2(VkQueue queue, int count, long submits, long fence) {
        try {
            return (int) H_SUBMIT.invokeExact(function(SUBMIT, queue.getCapabilities().vkQueueSubmit2),
                queue.address(), count, submits, fence);
        } catch (Throwable e) {
            throw failed(e);
        }
    }

    private static RuntimeException failed(Throwable e) {
        return e instanceof RuntimeException runtime ? runtime : new IllegalStateException(e);
    }
}
