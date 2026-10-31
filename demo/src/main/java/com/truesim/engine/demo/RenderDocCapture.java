package com.truesim.engine.demo;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;

/**
 * RenderDoc's in-application API, where RenderDoc launched the process (it is then loaded): captures of frames the
 * program chooses, offscreen ones too, which need no window or present. Windows only. Launch with
 * {@code renderdoccmd capture -c <file> java ...}.
 */
final class RenderDocCapture {

    private static final int VERSION_1_1_2 = 10102;
    private static final int START_FRAME_CAPTURE = 19; // the API struct's function pointers, in order
    private static final int END_FRAME_CAPTURE = 21;

    private final MethodHandle start;
    private final MethodHandle end;
    private final MemorySegment device;

    private RenderDocCapture(MethodHandle start, MethodHandle end, MemorySegment device) {
        this.start = start;
        this.end = end;
        this.device = device;
    }

    /**
     * The API where RenderDoc is loaded into the process, captures matching the Vulkan instance at {@code instance}
     * (its handle); null without RenderDoc.
     */
    static RenderDocCapture attach(long instance) {
        Linker linker = Linker.nativeLinker();
        SymbolLookup kernel = SymbolLookup.libraryLookup("kernel32", Arena.global());
        try (Arena arena = Arena.ofConfined()) {
            MethodHandle module = linker.downcallHandle(kernel.find("GetModuleHandleA").orElseThrow(),
                FunctionDescriptor.of(ADDRESS, ADDRESS));
            MethodHandle procedure = linker.downcallHandle(kernel.find("GetProcAddress").orElseThrow(),
                FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
            MemorySegment renderdoc = (MemorySegment) module.invokeExact(arena.allocateFrom("renderdoc.dll"));
            if (renderdoc.address() == 0) {
                return null;
            }
            MemorySegment getApi = (MemorySegment) procedure.invokeExact(renderdoc,
                arena.allocateFrom("RENDERDOC_GetAPI"));
            MethodHandle api = linker.downcallHandle(getApi, FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS));
            MemorySegment out = arena.allocate(ADDRESS);
            if ((int) api.invokeExact(VERSION_1_1_2, out) != 1) {
                return null;
            }
            MemorySegment table = out.get(ADDRESS, 0).reinterpret(8L * (END_FRAME_CAPTURE + 1));
            MethodHandle start = linker.downcallHandle(table.getAtIndex(ADDRESS, START_FRAME_CAPTURE),
                FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));
            MethodHandle end = linker.downcallHandle(table.getAtIndex(ADDRESS, END_FRAME_CAPTURE),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
            // RENDERDOC_DEVICEPOINTER_FROM_VKINSTANCE: the instance's dispatch table pointer.
            MemorySegment device = MemorySegment.ofAddress(instance).reinterpret(8).get(ADDRESS, 0);
            return new RenderDocCapture(start, end, device);
        } catch (Throwable e) {
            throw new IllegalStateException("Could not reach RenderDoc's API", e);
        }
    }

    /** Starts capturing: everything submitted until {@link #end()} is the capture. */
    void start() {
        try {
            start.invokeExact(device, MemorySegment.NULL);
        } catch (Throwable e) {
            throw new IllegalStateException(e);
        }
    }

    /** Ends the capture; whether it was written. */
    boolean end() {
        try {
            return (int) end.invokeExact(device, MemorySegment.NULL) == 1;
        } catch (Throwable e) {
            throw new IllegalStateException(e);
        }
    }
}
