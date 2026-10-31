package com.truesim.engine.platform;

import static org.lwjgl.sdl.SDLError.SDL_GetError;
import static org.lwjgl.sdl.SDLMouse.SDL_CursorVisible;
import static org.lwjgl.sdl.SDLMouse.SDL_GetWindowRelativeMouseMode;
import static org.lwjgl.sdl.SDLMouse.SDL_SetWindowRelativeMouseMode;
import static org.lwjgl.sdl.SDLPixels.SDL_PIXELFORMAT_ABGR8888;
import static org.lwjgl.sdl.SDLSurface.SDL_CreateSurfaceFrom;
import static org.lwjgl.sdl.SDLSurface.SDL_DestroySurface;
import static org.lwjgl.sdl.SDLVideo.SDL_FLASH_BRIEFLY;
import static org.lwjgl.sdl.SDLVideo.SDL_FLASH_CANCEL;
import static org.lwjgl.sdl.SDLVideo.SDL_FLASH_UNTIL_FOCUSED;
import static org.lwjgl.sdl.SDLVideo.SDL_FlashWindow;
import static org.lwjgl.sdl.SDLVideo.SDL_GetPrimaryDisplay;
import static org.lwjgl.sdl.SDLVideo.SDL_GetDesktopDisplayMode;
import static org.lwjgl.sdl.SDLVideo.SDL_GetWindowAspectRatio;
import static org.lwjgl.sdl.SDLVideo.SDL_GetWindowKeyboardGrab;
import static org.lwjgl.sdl.SDLVideo.SDL_GetWindowMaximumSize;
import static org.lwjgl.sdl.SDLVideo.SDL_GetWindowMinimumSize;
import static org.lwjgl.sdl.SDLVideo.SDL_GetWindowMouseGrab;
import static org.lwjgl.sdl.SDLVideo.SDL_GetWindowOpacity;
import static org.lwjgl.sdl.SDLVideo.SDL_MaximizeWindow;
import static org.lwjgl.sdl.SDLVideo.SDL_MinimizeWindow;
import static org.lwjgl.sdl.SDLVideo.SDL_PROGRESS_STATE_ERROR;
import static org.lwjgl.sdl.SDLVideo.SDL_PROGRESS_STATE_INDETERMINATE;
import static org.lwjgl.sdl.SDLVideo.SDL_PROGRESS_STATE_NONE;
import static org.lwjgl.sdl.SDLVideo.SDL_PROGRESS_STATE_NORMAL;
import static org.lwjgl.sdl.SDLVideo.SDL_PROGRESS_STATE_PAUSED;
import static org.lwjgl.sdl.SDLVideo.SDL_RestoreWindow;
import static org.lwjgl.sdl.SDLVideo.SDL_SetWindowAlwaysOnTop;
import static org.lwjgl.sdl.SDLVideo.SDL_SetWindowAspectRatio;
import static org.lwjgl.sdl.SDLVideo.SDL_SetWindowBordered;
import static org.lwjgl.sdl.SDLVideo.SDL_SetWindowFocusable;
import static org.lwjgl.sdl.SDLVideo.SDL_SetWindowIcon;
import static org.lwjgl.sdl.SDLVideo.SDL_SetWindowKeyboardGrab;
import static org.lwjgl.sdl.SDLVideo.SDL_SetWindowMaximumSize;
import static org.lwjgl.sdl.SDLVideo.SDL_SetWindowMinimumSize;
import static org.lwjgl.sdl.SDLVideo.SDL_SetWindowModal;
import static org.lwjgl.sdl.SDLVideo.SDL_SetWindowMouseGrab;
import static org.lwjgl.sdl.SDLVideo.SDL_SetWindowOpacity;
import static org.lwjgl.sdl.SDLVideo.SDL_SetWindowParent;
import static org.lwjgl.sdl.SDLVideo.SDL_SetWindowProgressState;
import static org.lwjgl.sdl.SDLVideo.SDL_SetWindowProgressValue;
import static org.lwjgl.sdl.SDLVideo.SDL_WINDOWPOS_CENTERED_DISPLAY;
import static org.lwjgl.sdl.SDLVideo.SDL_WINDOW_ALWAYS_ON_TOP;
import static org.lwjgl.sdl.SDLVideo.SDL_WINDOW_BORDERLESS;
import static org.lwjgl.sdl.SDLVideo.SDL_WINDOW_MAXIMIZED;
import static org.lwjgl.sdl.SDLVideo.SDL_WINDOW_MINIMIZED;
import static org.lwjgl.sdl.SDLVideo.SDL_WINDOW_NOT_FOCUSABLE;
import static org.lwjgl.sdl.SDLVulkan.nSDL_Vulkan_CreateSurface;
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
    private WindowSettings windowed; // as last applied or read: its windowed geometry while fullscreen or maximized

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
        this.windowed = WindowSettings.defaults().withWidth(options.width()).withHeight(options.height())
            .withResizable(options.resizable());
    }

    /** The id window events carry. */
    public int id() {
        return id;
    }

    /** The SDL window, inside the platform. */
    long handle() {
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

    // ---------------------------------------------------------------- more state

    public void maximize() {
        check();
        SDL_MaximizeWindow(handle);
    }

    public void minimize() {
        check();
        SDL_MinimizeWindow(handle);
    }

    /** Back from maximized or minimized to its windowed size and position. */
    public void restore() {
        check();
        SDL_RestoreWindow(handle);
    }

    public boolean isMaximized() {
        return flag(SDL_WINDOW_MAXIMIZED);
    }

    public boolean isMinimized() {
        return flag(SDL_WINDOW_MINIMIZED);
    }

    /** With or without decorations (title bar, borders). */
    public void setBordered(boolean bordered) {
        check();
        SDL_SetWindowBordered(handle, bordered);
    }

    public boolean isBordered() {
        return !flag(SDL_WINDOW_BORDERLESS);
    }

    public void setAlwaysOnTop(boolean onTop) {
        check();
        SDL_SetWindowAlwaysOnTop(handle, onTop);
    }

    public boolean isAlwaysOnTop() {
        return flag(SDL_WINDOW_ALWAYS_ON_TOP);
    }

    /** Whether the window may take input focus (a tool window may not). */
    public void setFocusable(boolean focusable) {
        check();
        SDL_SetWindowFocusable(handle, focusable);
    }

    public boolean isFocusable() {
        return !flag(SDL_WINDOW_NOT_FOCUSABLE);
    }

    /** From 0 (transparent) to 1 (opaque); returns false where the system cannot. */
    public boolean setOpacity(float opacity) {
        check();
        if (!(opacity >= 0 && opacity <= 1)) {
            throw new IllegalArgumentException("Opacity is from 0 to 1: " + opacity);
        }
        return SDL_SetWindowOpacity(handle, opacity);
    }

    public float opacity() {
        check();
        float opacity = SDL_GetWindowOpacity(handle);
        return opacity < 0 ? 1 : opacity;
    }

    /** The smallest size the user may resize the window to, in screen coordinates (0: no limit). */
    public void setMinimumSize(int width, int height) {
        check();
        SDL_SetWindowMinimumSize(handle, width, height);
    }

    /** The largest size the user may resize the window to (0: no limit). */
    public void setMaximumSize(int width, int height) {
        check();
        SDL_SetWindowMaximumSize(handle, width, height);
    }

    /** The minimum size: width at index 0, height at 1 (0: no limit). */
    public int[] minimumSize() {
        return limit(true);
    }

    /** The maximum size: width at index 0, height at 1 (0: no limit). */
    public int[] maximumSize() {
        return limit(false);
    }

    @AllowAllocation("a snapshot")
    private int[] limit(boolean minimum) {
        check();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer w = stack.mallocInt(1);
            IntBuffer h = stack.mallocInt(1);
            if (minimum) {
                SDL_GetWindowMinimumSize(handle, w, h);
            } else {
                SDL_GetWindowMaximumSize(handle, w, h);
            }
            return new int[] {w.get(0), h.get(0)};
        }
    }

    /** Keeps width over height between {@code min} and {@code max} while resizing (0: no limit). */
    public void setAspectRatio(float min, float max) {
        check();
        SDL_SetWindowAspectRatio(handle, min, max);
    }

    /** The aspect ratio limits: minimum at index 0, maximum at 1 (0: no limit). */
    @AllowAllocation("a snapshot")
    public float[] aspectRatio() {
        check();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            java.nio.FloatBuffer min = stack.mallocFloat(1);
            java.nio.FloatBuffer max = stack.mallocFloat(1);
            SDL_GetWindowAspectRatio(handle, min, max);
            return new float[] {min.get(0), max.get(0)};
        }
    }

    /** Confines the mouse to the window while it has focus. */
    public void setMouseGrab(boolean grab) {
        check();
        SDL_SetWindowMouseGrab(handle, grab);
    }

    public boolean isMouseGrabbed() {
        check();
        return SDL_GetWindowMouseGrab(handle);
    }

    /** Takes system shortcuts, such as Alt+Tab, while the window has focus, where the system lets it. */
    public void setKeyboardGrab(boolean grab) {
        check();
        SDL_SetWindowKeyboardGrab(handle, grab);
    }

    public boolean isKeyboardGrabbed() {
        check();
        return SDL_GetWindowKeyboardGrab(handle);
    }

    /** Hides the mouse and reports its motion only, unbounded by the screen: for mouse look. */
    public void setRelativeMouse(boolean relative) {
        check();
        SDL_SetWindowRelativeMouseMode(handle, relative);
    }

    public boolean isRelativeMouse() {
        check();
        return SDL_GetWindowRelativeMouseMode(handle);
    }

    /** Asks for the user's attention: briefly, or until the window has focus; {@code null} stops asking. */
    public void flash(Boolean untilFocused) {
        check();
        SDL_FlashWindow(handle, untilFocused == null ? SDL_FLASH_CANCEL
            : untilFocused ? SDL_FLASH_UNTIL_FOCUSED : SDL_FLASH_BRIEFLY);
    }

    /** Shows a long task on the window's task bar entry: {@code done} from 0 to 1, where the state has a share. */
    public void setProgress(WindowProgress state, float done) {
        check();
        SDL_SetWindowProgressState(handle, switch (state) {
            case NONE -> SDL_PROGRESS_STATE_NONE;
            case INDETERMINATE -> SDL_PROGRESS_STATE_INDETERMINATE;
            case NORMAL -> SDL_PROGRESS_STATE_NORMAL;
            case PAUSED -> SDL_PROGRESS_STATE_PAUSED;
            case ERROR -> SDL_PROGRESS_STATE_ERROR;
        });
        SDL_SetWindowProgressValue(handle, Math.clamp(done, 0, 1));
    }

    /**
     * The window's icon: {@code width} by {@code height} pixels, 4 bytes each (red, green, blue, alpha), row after row
     * in {@code rgba}, a direct buffer.
     */
    public void setIcon(int width, int height, java.nio.ByteBuffer rgba) {
        check();
        if (width < 1 || height < 1 || rgba.remaining() < 4L * width * height || !rgba.isDirect()) {
            throw new IllegalArgumentException("An icon of " + width + " x " + height + " needs " + 4L * width * height
                + " bytes in a direct buffer");
        }
        org.lwjgl.sdl.SDL_Surface surface = SDL_CreateSurfaceFrom(width, height, SDL_PIXELFORMAT_ABGR8888, rgba,
            4 * width);
        if (surface == null) {
            throw new PlatformException("Could not make the icon: " + SDL_GetError());
        }
        try {
            SDL_SetWindowIcon(handle, surface);
        } finally {
            SDL_DestroySurface(surface);
        }
    }

    /** Makes the window a child of {@code parent} (null: none): it stays above it and closes with it. */
    public void setParent(Window parent) {
        check();
        SDL_SetWindowParent(handle, parent == null ? 0 : parent.handle);
    }

    /** As a child window, blocks input to its parent while shown. */
    public void setModal(boolean modal) {
        check();
        SDL_SetWindowModal(handle, modal);
    }

    private boolean flag(long flag) {
        check();
        return (SDL_GetWindowFlags(handle) & flag) != 0;
    }

    // ---------------------------------------------------------------- settings

    /**
     * Changes the window to {@code settings}, as far as the system allows: in its mode on its display (by name; the
     * primary if none has it), its windowed size and position kept for when it is windowed again; exclusive fullscreen
     * where the display has no such mode is borderless. Takes effect at once
     * or, on some systems, shortly after ({@link #sync()}); {@link #settings()} tells what took.
     */
    @AllowAllocation("changing settings, rarely")
    public void apply(WindowSettings settings) {
        check();
        windowed = settings;
        setResizable(settings.resizable());
        setBordered(settings.bordered());
        setAlwaysOnTop(settings.alwaysOnTop());
        setFocusable(settings.focusable());
        SDL_SetWindowOpacity(handle, settings.opacity()); // where the system can
        setMinimumSize(settings.minWidth(), settings.minHeight());
        setMaximumSize(settings.maxWidth(), settings.maxHeight());
        setAspectRatio(settings.minAspect(), settings.maxAspect());
        int display = platform.displayNamed(settings.display());
        switch (settings.mode()) {
            case WINDOWED -> {
                SDL_SetWindowFullscreen(handle, false);
                if (settings.maximized()) {
                    SDL_MaximizeWindow(handle);
                } else {
                    SDL_RestoreWindow(handle);
                    setSize(settings.width(), settings.height());
                    int position = SDL_WINDOWPOS_CENTERED_DISPLAY(display);
                    setPosition(settings.centered() ? position : settings.x(),
                        settings.centered() ? position : settings.y());
                }
            }
            case BORDERLESS_FULLSCREEN -> {
                int position = SDL_WINDOWPOS_CENTERED_DISPLAY(display); // onto its display first
                SDL_SetWindowPosition(handle, position, position);
                borderlessFullscreen();
            }
            case EXCLUSIVE_FULLSCREEN -> {
                DisplayMode desktop = Platform.mode(SDL_GetDesktopDisplayMode(display));
                int width = settings.fullscreenWidth() > 0 ? settings.fullscreenWidth() : desktop.width();
                int height = settings.fullscreenHeight() > 0 ? settings.fullscreenHeight() : desktop.height();
                float rate = settings.refreshRate() > 0 ? settings.refreshRate() : desktop.refreshRate();
                try {
                    exclusiveFullscreen(new DisplayMode(display, width, height, desktop.pixelDensity(), rate, 0, 0,
                        desktop.format()));
                } catch (PlatformException e) { // a display without fullscreen modes: covered at its desktop's
                    borderlessFullscreen();
                }
            }
        }
        setMouseGrab(settings.mouseGrab());
        setKeyboardGrab(settings.keyboardGrab());
        setRelativeMouse(settings.relativeMouse());
        platform.setCursorVisible(settings.cursorVisible());
        platform.setKeepScreenOn(settings.keepScreenOn());
    }

    /**
     * What the window is now, as settings to save: its mode and display, its windowed size and position (the last
     * ones it had while windowed, when fullscreen or maximized), and the rest as the system reports them.
     */
    @AllowAllocation("a snapshot")
    public WindowSettings settings() {
        check();
        WindowMode mode = mode();
        boolean maximized = isMaximized();
        WindowSettings s = windowed;
        if (mode == WindowMode.WINDOWED && !maximized && !isMinimized()) {
            s = s.withWidth(width()).withHeight(height()).withCentered(false).withX(x()).withY(y());
        }
        DisplayMode exclusive = fullscreenMode();
        if (exclusive != null) {
            s = s.withFullscreenWidth(exclusive.width()).withFullscreenHeight(exclusive.height())
                .withRefreshRate(exclusive.refreshRate());
        }
        int display = display();
        String key = display == SDL_GetPrimaryDisplay() ? "" : platform.displayKey(display);
        int[] min = minimumSize();
        int[] max = maximumSize();
        float[] aspect = aspectRatio();
        s = s.withMode(mode).withDisplay(key).withMaximized(maximized)
            .withResizable(isResizable()).withBordered(isBordered()).withAlwaysOnTop(isAlwaysOnTop())
            .withFocusable(isFocusable()).withOpacity(Math.clamp(opacity(), 0, 1)).withMinWidth(min[0])
            .withMinHeight(min[1]).withMaxWidth(max[0]).withMaxHeight(max[1]).withMinAspect(Math.max(0, aspect[0]))
            .withMaxAspect(Math.max(0, aspect[1])).withMouseGrab(isMouseGrabbed()).withKeyboardGrab(isKeyboardGrabbed())
            .withRelativeMouse(isRelativeMouse()).withCursorVisible(SDL_CursorVisible())
            .withKeepScreenOn(platform.keepScreenOn());
        windowed = s;
        return s;
    }

    /**
     * Applies the window settings {@code settings} holds ({@link WindowSettings#from}; a scoped view for this window,
     * say), and applies them again whenever one of them changes, until the window closes. Change them on the
     * platform's thread.
     */
    @AllowAllocation("following settings, once")
    public Window follow(com.truesim.engine.settings.EngineSettings settings) {
        check();
        apply(WindowSettings.from(settings));
        java.util.Set<String> names = new java.util.HashSet<>();
        for (com.truesim.engine.settings.Setting<?> setting : WindowSettings.ALL) {
            names.add(settings.nameOf(setting));
        }
        attach(settings.onAnyChange(name -> {
            if (handle != 0 && names.contains(name)) {
                apply(WindowSettings.from(settings));
            }
        }));
        return this;
    }

    // ---------------------------------------------------------------- rendering

    /**
     * A Vulkan surface on this window for Vulkan instance {@code instance} (a {@code VkInstance} handle), as a
     * {@code VkSurfaceKHR} handle: the window must be made for Vulkan ({@link WindowOptions#vulkan()}). The caller
     * destroys it, before the window ({@link #attach}).
     */
    public long createVulkanSurface(long instance) {
        check();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            java.nio.LongBuffer surface = stack.mallocLong(1);
            if (!nSDL_Vulkan_CreateSurface(handle, instance, 0, org.lwjgl.system.MemoryUtil.memAddress(surface))) {
                throw new PlatformException("Could not create a Vulkan surface: " + SDL_GetError());
            }
            return surface.get(0);
        }
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
