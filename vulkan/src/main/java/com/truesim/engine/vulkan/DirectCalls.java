package com.truesim.engine.vulkan;

import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDevice;

/**
 * Vulkan calls of every frame made straight through the foreign function API. LWJGL wraps each function's address in
 * a {@link MemorySegment} per call, which the JIT removes only where it inlines the whole call: for some calls it does
 * not, and each frame allocates. Here the addresses are wrapped once, so these calls allocate nothing whatever the JIT
 * decides. Arguments are as for LWJGL's {@code n}-prefixed methods: structs and arrays by address.
 */
public final class DirectCalls {

    private static final Linker LINKER = Linker.nativeLinker();
    // Pointers and handles are 64-bit integers here: no segment per argument either.
    private static final MethodHandle WAIT_SEMAPHORES =
        LINKER.downcallHandle(FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_LONG));
    private static final MethodHandle CMD_BLIT_IMAGE = LINKER.downcallHandle(FunctionDescriptor.ofVoid(JAVA_LONG,
        JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_LONG, JAVA_INT), Linker.Option.critical(false));

    private final long device;
    private final MemorySegment waitSemaphores;
    private final MemorySegment cmdBlitImage;

    DirectCalls(VkDevice device) {
        this.device = device.address();
        this.waitSemaphores = MemorySegment.ofAddress(device.getCapabilities().vkWaitSemaphores);
        this.cmdBlitImage = MemorySegment.ofAddress(device.getCapabilities().vkCmdBlitImage);
    }

    /** {@code vkWaitSemaphores}: waits as {@code info} (a {@code VkSemaphoreWaitInfo}) says; returns the result. */
    public int waitSemaphores(long info, long timeout) {
        try {
            return (int) WAIT_SEMAPHORES.invokeExact(waitSemaphores, device, info, timeout);
        } catch (Throwable e) {
            throw failed(e);
        }
    }

    /** {@code vkCmdBlitImage}, recorded into {@code commands}: {@code regions} {@code VkImageBlit}s at an address. */
    public void cmdBlitImage(VkCommandBuffer commands, long source, int sourceLayout, long destination,
        int destinationLayout, int regionCount, long regions, int filter) {
        try {
            CMD_BLIT_IMAGE.invokeExact(cmdBlitImage, commands.address(), source, sourceLayout, destination,
                destinationLayout, regionCount, regions, filter);
        } catch (Throwable e) {
            throw failed(e);
        }
    }

    private static RuntimeException failed(Throwable e) {
        return e instanceof RuntimeException runtime ? runtime : new IllegalStateException(e);
    }
}
