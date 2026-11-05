package com.truesim.engine.platform;

import static java.lang.foreign.ValueLayout.JAVA_BOOLEAN;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import org.lwjgl.sdl.SDLEvents;
import org.lwjgl.sdl.SDLGamepad;
import org.lwjgl.sdl.SDLMouse;
import org.lwjgl.sdl.SDLVideo;

/**
 * The SDL calls made every frame (polling events, a joystick's kind; a window's flags, display, scale, position and
 * size; the global mouse), straight
 * through the foreign function API with each function's address bound once: LWJGL wraps the address in a
 * {@code MemorySegment} on every call, which the JIT removes only where it inlines the whole call (pitfalls.md, "LWJGL
 * Vulkan calls allocate per call"; SDL's are the same). Out-parameters by address, not buffers. Allocates nothing.
 */
final class SdlCalls {

    private static final Linker LINKER = Linker.nativeLinker();
    private static final MethodHandle WINDOW_FLAGS = LINKER.downcallHandle(
        MemorySegment.ofAddress(SDLVideo.Functions.GetWindowFlags), FunctionDescriptor.of(JAVA_LONG, JAVA_LONG));
    private static final MethodHandle DISPLAY_FOR_WINDOW = LINKER.downcallHandle(
        MemorySegment.ofAddress(SDLVideo.Functions.GetDisplayForWindow), FunctionDescriptor.of(JAVA_INT, JAVA_LONG));
    private static final MethodHandle WINDOW_DISPLAY_SCALE = LINKER.downcallHandle(
        MemorySegment.ofAddress(SDLVideo.Functions.GetWindowDisplayScale),
        FunctionDescriptor.of(JAVA_FLOAT, JAVA_LONG));

    private static final FunctionDescriptor TWO_INTS = FunctionDescriptor.of(JAVA_BOOLEAN, JAVA_LONG, JAVA_LONG,
        JAVA_LONG);
    private static final MethodHandle WINDOW_POSITION = LINKER.downcallHandle(
        MemorySegment.ofAddress(SDLVideo.Functions.GetWindowPosition), TWO_INTS);
    private static final MethodHandle WINDOW_SIZE = LINKER.downcallHandle(
        MemorySegment.ofAddress(SDLVideo.Functions.GetWindowSize), TWO_INTS);
    private static final MethodHandle WINDOW_PIXEL_SIZE = LINKER.downcallHandle(
        MemorySegment.ofAddress(SDLVideo.Functions.GetWindowSizeInPixels), TWO_INTS);

    private static final MethodHandle GLOBAL_MOUSE = LINKER.downcallHandle(
        MemorySegment.ofAddress(SDLMouse.Functions.GetGlobalMouseState), FunctionDescriptor.of(JAVA_INT, JAVA_LONG,
            JAVA_LONG));

    private static final MethodHandle POLL_EVENT = LINKER.downcallHandle(
        MemorySegment.ofAddress(SDLEvents.Functions.PollEvent), FunctionDescriptor.of(JAVA_BOOLEAN, JAVA_LONG));
    private static final MethodHandle IS_GAMEPAD = LINKER.downcallHandle(
        MemorySegment.ofAddress(SDLGamepad.Functions.IsGamepad), FunctionDescriptor.of(JAVA_BOOLEAN, JAVA_INT));

    private SdlCalls() {
    }

    /** {@code SDL_PollEvent} into the event at {@code event} (an address): whether there was one. */
    static boolean pollEvent(long event) {
        try {
            return (boolean) POLL_EVENT.invokeExact(event);
        } catch (Throwable e) {
            throw new IllegalStateException(e);
        }
    }

    /** {@code SDL_IsGamepad}: whether joystick {@code id} is a gamepad (on every joystick event). */
    static boolean isGamepad(int id) {
        try {
            return (boolean) IS_GAMEPAD.invokeExact(id);
        } catch (Throwable e) {
            throw new IllegalStateException(e);
        }
    }

    /** {@code SDL_GetGlobalMouseState}, the two floats at {@code x} and {@code y} (addresses); the buttons held. */
    static int globalMouseState(long x, long y) {
        try {
            return (int) GLOBAL_MOUSE.invokeExact(x, y);
        } catch (Throwable e) {
            throw new IllegalStateException(e);
        }
    }

    /** {@code SDL_GetWindowPosition}, the two ints at {@code x} and {@code y} (addresses). */
    static boolean windowPosition(long window, long x, long y) {
        try {
            return (boolean) WINDOW_POSITION.invokeExact(window, x, y);
        } catch (Throwable e) {
            throw new IllegalStateException(e);
        }
    }

    /** {@code SDL_GetWindowSizeInPixels} ({@code pixels}) or {@code SDL_GetWindowSize}, into the ints at w and h. */
    static boolean windowSize(long window, boolean pixels, long w, long h) {
        try {
            return pixels ? (boolean) WINDOW_PIXEL_SIZE.invokeExact(window, w, h)
                : (boolean) WINDOW_SIZE.invokeExact(window, w, h);
        } catch (Throwable e) {
            throw new IllegalStateException(e);
        }
    }

    /** {@code SDL_GetWindowFlags}. */
    static long windowFlags(long window) {
        try {
            return (long) WINDOW_FLAGS.invokeExact(window);
        } catch (Throwable e) {
            throw new IllegalStateException(e);
        }
    }

    /** {@code SDL_GetDisplayForWindow}. */
    static int displayForWindow(long window) {
        try {
            return (int) DISPLAY_FOR_WINDOW.invokeExact(window);
        } catch (Throwable e) {
            throw new IllegalStateException(e);
        }
    }

    /** {@code SDL_GetWindowDisplayScale}. */
    static float windowDisplayScale(long window) {
        try {
            return (float) WINDOW_DISPLAY_SCALE.invokeExact(window);
        } catch (Throwable e) {
            throw new IllegalStateException(e);
        }
    }
}
