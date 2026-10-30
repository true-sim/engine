package com.truesim.engine.platform;

import static org.lwjgl.sdl.SDLError.SDL_GetError;
import static org.lwjgl.sdl.SDLKeyboard.SDL_StartTextInput;
import static org.lwjgl.sdl.SDLKeyboard.SDL_StopTextInput;
import static org.lwjgl.sdl.SDLVideo.SDL_CreateWindow;
import static org.lwjgl.sdl.SDLVideo.SDL_DestroyWindow;
import static org.lwjgl.sdl.SDLVideo.SDL_GetClosestFullscreenDisplayMode;
import static org.lwjgl.sdl.SDLVideo.SDL_GetDisplayForWindow;
import static org.lwjgl.sdl.SDLVideo.SDL_GetWindowDisplayScale;
import static org.lwjgl.sdl.SDLVideo.SDL_GetWindowFlags;
import static org.lwjgl.sdl.SDLVideo.SDL_GetWindowFullscreenMode;
import static org.lwjgl.sdl.SDLVideo.SDL_GetWindowID;
import static org.lwjgl.sdl.SDLVideo.SDL_GetWindowPosition;
import static org.lwjgl.sdl.SDLVideo.SDL_GetWindowSize;
import static org.lwjgl.sdl.SDLVideo.SDL_GetWindowSizeInPixels;
import static org.lwjgl.sdl.SDLVideo.SDL_GetWindowTitle;
import static org.lwjgl.sdl.SDLVideo.SDL_HideWindow;
import static org.lwjgl.sdl.SDLVideo.SDL_RaiseWindow;
import static org.lwjgl.sdl.SDLVideo.SDL_SetWindowFullscreen;
import static org.lwjgl.sdl.SDLVideo.SDL_SetWindowFullscreenMode;
import static org.lwjgl.sdl.SDLVideo.SDL_SetWindowPosition;
import static org.lwjgl.sdl.SDLVideo.SDL_SetWindowResizable;
import static org.lwjgl.sdl.SDLVideo.SDL_SetWindowSize;
import static org.lwjgl.sdl.SDLVideo.SDL_SetWindowTitle;
import static org.lwjgl.sdl.SDLVideo.SDL_ShowWindow;
import static org.lwjgl.sdl.SDLVideo.SDL_SyncWindow;
import static org.lwjgl.sdl.SDLVideo.SDL_WINDOWPOS_CENTERED;
import static org.lwjgl.sdl.SDLVideo.SDL_WINDOW_FULLSCREEN;
import static org.lwjgl.sdl.SDLVideo.SDL_WINDOW_HIDDEN;
import static org.lwjgl.sdl.SDLVideo.SDL_WINDOW_HIGH_PIXEL_DENSITY;
import static org.lwjgl.sdl.SDLVideo.SDL_WINDOW_RESIZABLE;
import static org.lwjgl.sdl.SDLVideo.SDL_WINDOW_VULKAN;

import com.truesim.annotations.AllowAllocation;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;
import org.lwjgl.sdl.SDL_DisplayMode;
import org.lwjgl.system.MemoryStack;

/**
 * A window, changeable at runtime: size, position, title, resizability, visibility, and its mode (windowed, borderless
 * fullscreen, or exclusive fullscreen at a chosen resolution and refresh rate). Changes may take effect asynchronously
 * on some systems; the platform reports the results as events, and {@link #sync()} waits for them.
 *
 * <p>Use it on the platform's thread. Renderers take its {@link #handle()} and {@linkplain #pixelWidth() pixel size}
 * for their surfaces.
 */
public final class Window implements AutoCloseable {

    private final Platform platform;
    private final int id;
    /** What lives on this window, such as Vulkan surfaces, closed before it. */
    private final List<AutoCloseable> dependents = new ArrayList<>();
    private long handle;

    @AllowAllocation("opening a window")
    Window(Platform platform, WindowOptions options) {
        this.platform = platform;
        long flags = (options.resizable() ? SDL_WINDOW_RESIZABLE : 0) | (options.hidden() ? SDL_WINDOW_HIDDEN : 0)
            | (options.highPixelDensity() ? SDL_WINDOW_HIGH_PIXEL_DENSITY : 0) | (options.vulkan() ? SDL_WINDOW_VULKAN : 0);
        this.handle = SDL_CreateWindow(options.title(), options.width(), options.height(), flags);
        if (handle == 0) {
            throw new PlatformException("Could not open window: " + SDL_GetError());
        }
        this.id = SDL_GetWindowID(handle);
    }

    /** The id window events carry. */
    public int id() {
        return id;
    }

    /** The native SDL window, e.g. for creating a Vulkan surface. */
    public long handle() {
        check();
        return handle;
    }

    public boolean isClosed() {
        return handle == 0;
    }

    @AllowAllocation("titles are strings")
    public String title() {
        check();
        return SDL_GetWindowTitle(handle);
    }

    public void setTitle(String title) {
        check();
        SDL_SetWindowTitle(handle, title);
    }

    // ---------------------------------------------------------------- size and position

    /** Width in screen coordinates. */
    public int width() {
        return size(false, true);
    }

    /** Height in screen coordinates. */
    public int height() {
        return size(false, false);
    }

    /** Width in pixels: the size swapchains use. Larger than {@link #width()} on high-DPI displays. */
    public int pixelWidth() {
        return size(true, true);
    }

    /** Height in pixels. */
    public int pixelHeight() {
        return size(true, false);
    }

    private int size(boolean pixels, boolean width) {
        check();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer w = stack.mallocInt(1);
            IntBuffer h = stack.mallocInt(1);
            boolean ok = pixels ? SDL_GetWindowSizeInPixels(handle, w, h) : SDL_GetWindowSize(handle, w, h);
            if (!ok) {
                throw new PlatformException("Could not read the window size: " + SDL_GetError());
            }
            return width ? w.get(0) : h.get(0);
        }
    }

    /** Resizes the window, in screen coordinates. */
    public void setSize(int width, int height) {
        check();
        if (!SDL_SetWindowSize(handle, width, height)) {
            throw new PlatformException("Could not resize the window: " + SDL_GetError());
        }
    }

    public int x() {
        return position(true);
    }

    public int y() {
        return position(false);
    }

    private int position(boolean x) {
        check();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer px = stack.mallocInt(1);
            IntBuffer py = stack.mallocInt(1);
            SDL_GetWindowPosition(handle, px, py);
            return x ? px.get(0) : py.get(0);
        }
    }

    public void setPosition(int x, int y) {
        check();
        SDL_SetWindowPosition(handle, x, y);
    }

    /** Centers the window on its display. */
    public void center() {
        setPosition(SDL_WINDOWPOS_CENTERED, SDL_WINDOWPOS_CENTERED);
    }

    /** The id of the display the window is mostly on. */
    public int display() {
        check();
        return SDL_GetDisplayForWindow(handle);
    }

    /** Pixels per screen coordinate times the display's content scale: how much to scale UI by. */
    public float displayScale() {
        check();
        return SDL_GetWindowDisplayScale(handle);
    }

    // ---------------------------------------------------------------- mode

    public WindowMode mode() {
        check();
        if ((SDL_GetWindowFlags(handle) & SDL_WINDOW_FULLSCREEN) == 0) {
            return WindowMode.WINDOWED;
        }
        return SDL_GetWindowFullscreenMode(handle) == null ? WindowMode.BORDERLESS_FULLSCREEN
            : WindowMode.EXCLUSIVE_FULLSCREEN;
    }

    /** Leaves fullscreen. */
    public void windowed() {
        check();
        SDL_SetWindowFullscreen(handle, false);
    }

    /** Covers the window's display at the desktop resolution. */
    public void borderlessFullscreen() {
        check();
        SDL_SetWindowFullscreenMode(handle, null);
        SDL_SetWindowFullscreen(handle, true);
    }

    /**
     * Switches the display to {@code mode} (one of {@link Display#fullscreenModes()}, or the closest the display
     * supports) and covers it with this window.
     */
    public void exclusiveFullscreen(DisplayMode mode) {
        check();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            SDL_DisplayMode closest = SDL_DisplayMode.malloc(stack);
            if (!SDL_GetClosestFullscreenDisplayMode(mode.displayId(), mode.width(), mode.height(), mode.refreshRate(),
                mode.pixelDensity() > 1, closest)) {
                throw new PlatformException("No fullscreen mode like " + mode + ": " + SDL_GetError());
            }
            SDL_SetWindowFullscreenMode(handle, closest);
            SDL_SetWindowFullscreen(handle, true);
        }
    }

    /** The display mode of exclusive fullscreen, or {@code null} in the other modes. */
    @AllowAllocation("a snapshot")
    public DisplayMode fullscreenMode() {
        check();
        return mode() == WindowMode.EXCLUSIVE_FULLSCREEN ? Platform.mode(SDL_GetWindowFullscreenMode(handle)) : null;
    }

    // ---------------------------------------------------------------- state

    public void setResizable(boolean resizable) {
        check();
        SDL_SetWindowResizable(handle, resizable);
    }

    public boolean isResizable() {
        check();
        return (SDL_GetWindowFlags(handle) & SDL_WINDOW_RESIZABLE) != 0;
    }

    public void show() {
        check();
        SDL_ShowWindow(handle);
    }

    public void hide() {
        check();
        SDL_HideWindow(handle);
    }

    public boolean isVisible() {
        check();
        return (SDL_GetWindowFlags(handle) & SDL_WINDOW_HIDDEN) == 0;
    }

    /** Brings the window to the front and asks for input focus. */
    public void raise() {
        check();
        SDL_RaiseWindow(handle);
    }

    /** Starts sending typed text for this window ({@link PlatformListener#text}), e.g. while a text field is focused. */
    public void startTextInput() {
        check();
        SDL_StartTextInput(handle);
    }

    public void stopTextInput() {
        check();
        SDL_StopTextInput(handle);
    }

    /**
     * Waits until size, position and mode changes have taken effect (some systems apply them asynchronously);
     * returns {@code false} if they did not within SDL's timeout.
     */
    public boolean sync() {
        check();
        return SDL_SyncWindow(handle);
    }

    private void check() {
        platform.checkThread();
        if (handle == 0) {
            throw new IllegalStateException("The window is closed");
        }
    }

    /** Closes the window. */
    @Override
    @AllowAllocation("closing")
    public void close() {
        if (handle == 0) {
            return;
        }
        platform.checkThread();
        RuntimeException failure = null;
        for (AutoCloseable dependent : List.copyOf(dependents).reversed()) {
            try {
                dependent.close();
            } catch (Exception e) {
                if (failure == null) {
                    failure = new PlatformException("Could not close what lived on " + this);
                }
                failure.addSuppressed(e);
            }
        }
        dependents.clear();
        SDL_DestroyWindow(handle);
        handle = 0;
        platform.closed(this);
        if (failure != null) {
            throw failure;
        }
    }

    /**
     * Ties {@code dependent}, such as a Vulkan surface, to this window: closing the window (or the platform) closes it
     * first, newest first. A dependent closed on its own {@linkplain #detach(AutoCloseable) detaches}.
     */
    @AllowAllocation("attaching once")
    public void attach(AutoCloseable dependent) {
        if (handle == 0) {
            throw new IllegalStateException(this + " is closed");
        }
        dependents.add(dependent);
    }

    /** Unties {@code dependent}, closed on its own. */
    public void detach(AutoCloseable dependent) {
        dependents.remove(dependent);
    }

    @Override
    public String toString() {
        return "Window " + id;
    }
}
