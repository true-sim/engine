package com.truesim.engine.ui;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.graph.FrameGraph;
import com.truesim.engine.graph.PresentSettings;
import com.truesim.engine.graph.Presenter;
import com.truesim.engine.graph.ViewPort;
import com.truesim.engine.platform.Display;
import com.truesim.engine.platform.Platform;
import com.truesim.engine.platform.PlatformListener;
import com.truesim.engine.platform.Window;
import com.truesim.engine.platform.WindowOptions;
import com.truesim.engine.render.Keys;
import com.truesim.ui.controls.Ui;
import com.truesim.ui.controls.windows.OsDockHost;
import com.truesim.ui.controls.windows.OsWindows;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Docking's OS windows over the engine (ui-controls' {@link OsWindows}, for an {@link OsDockHost}): SDL windows of
 * the platform, each presenting a view of its own ({@code ui.window<id>}: a {@link UiBackdrop} and a renderer
 * {@linkplain GpuUiRenderer#share() sharing} the main window's uploads) that draws its region of the one UI. So one
 * device, one upload of the draw lists and one atlas serve every window; each window's presenter target encodes for
 * its own display (SDR, HDR10 or scRGB as the display takes it, paper white from the graph's settings), and the
 * UI's scale follows each window's display ({@link #contentScale}).
 *
 * <p>Input: the mouse of every window maps into the UI through the host ({@link #map}: the desktop's global cursor
 * where the system tells it, else the window's position plus the event's), the window's close button closes its
 * area (its panels dock again), and a window taking the OS focus becomes the front-most for the mouse. The keyboard
 * and text go to the UI's focused element whichever window has the keyboard; text input starts on the window
 * holding the focused field ({@link #locate}).
 *
 * <pre>{@code
 * UiOsWindows os = engine.get(UiPlugin.KEY).osWindows();       // with a window and a presenter
 * OsDockHost host = new OsDockHost(overlays, dock, os);
 * os.host(host);                                                // the plugin draws and maps every window from now on
 * }</pre>
 *
 * <p>Use it on the platform's thread. {@link #submit} and the mapping allocate nothing; opening and closing windows
 * do (their views, swapchains and the graph's rebuild).
 */
public final class UiOsWindows implements OsWindows, PlatformListener, MousePointer.Mapper, UiTextInput.Locator {

    /** One OS window: its platform window, view, renderer and presenter target (null without a presenter). */
    private static final class Entry {
        final Window window;
        final ViewPort view;
        final GpuUiRenderer renderer;
        Presenter.Target target;

        Entry(Window window, ViewPort view, GpuUiRenderer renderer) {
            this.window = window;
            this.view = view;
            this.renderer = renderer;
        }
    }

    private final Platform platform;
    private final FrameGraph graph;
    private final Presenter presenter;
    private final Window main;
    private final GpuUiRenderer mainRenderer;
    private final PresentSettings settings;
    private Entry[] entries = new Entry[0];
    private OsDockHost host;
    private final float[] cursor = new float[2];

    /**
     * Windows of {@code platform} next to {@code main}, each a view of {@code graph} presented by {@code presenter}
     * (null: views only, for tests reading them back) as {@code settings} say, drawing what {@code mainRenderer}
     * uploads.
     */
    @AllowAllocation("made once")
    public UiOsWindows(Platform platform, FrameGraph graph, Presenter presenter, Window main,
        GpuUiRenderer mainRenderer, PresentSettings settings) {
        this.platform = Objects.requireNonNull(platform, "platform");
        this.graph = Objects.requireNonNull(graph, "graph");
        this.presenter = presenter;
        this.main = Objects.requireNonNull(main, "main");
        this.mainRenderer = Objects.requireNonNull(mainRenderer, "mainRenderer");
        this.settings = settings == null ? PresentSettings.defaults() : settings;
    }

    /** Draws and maps the windows of {@code value} from now on (null: none). */
    public UiOsWindows host(OsDockHost value) {
        host = value;
        return this;
    }

    /** The docking host whose windows these are, or null. */
    public OsDockHost host() {
        return host;
    }

    /** The main window. */
    public Window main() {
        return main;
    }

    /** The platform window of OS window {@code id} ({@link #MAIN} or its own id: the main one), or null. */
    public Window window(int id) {
        if (id == MAIN || id == main.id()) {
            return main;
        }
        Entry e = entry(id);
        return e == null ? null : e.window;
    }

    /** The view drawing OS window {@code id}, or null. */
    public ViewPort view(int id) {
        Entry e = entry(id);
        return e == null ? null : e.view;
    }

    /** The renderer of OS window {@code id}'s view, or null. */
    public GpuUiRenderer renderer(int id) {
        Entry e = entry(id);
        return e == null ? null : e.renderer;
    }

    /** The presenter target of OS window {@code id}, or null (none, or no presenter). */
    public Presenter.Target target(int id) {
        Entry e = entry(id);
        return e == null ? null : e.target;
    }

    /** How many OS windows are open. */
    public int count() {
        return entries.length;
    }

    private Entry entry(int id) {
        for (Entry e : entries) {
            if (e.window.id() == id) {
                return e;
            }
        }
        return null;
    }

    // ================================================================ each frame

    /**
     * Hands each window's renderer its region of the UI (after the UI updated and the main renderer took the
     * layers): its origin and size in UI pixels. Allocates nothing.
     */
    public void submit(Ui ui) {
        if (host == null) {
            return;
        }
        for (int s = 0; s < host.slotCount(); s++) {
            OsDockHost.Window w = host.windowAt(s);
            Entry e = w == null ? null : entry(w.id());
            if (e != null) {
                e.renderer.origin(w.originX(), w.originY());
                e.renderer.submit(ui.layers(), ui.layerCount(), null, Math.max(1, Math.round(w.width())),
                    Math.max(1, Math.round(w.height())));
            }
        }
    }

    @Override
    public boolean map(int windowId, float x, float y, float[] out) {
        if (host == null) {
            return false;
        }
        Window w = window(windowId);
        if (w == null) {
            return false;
        }
        if (platform.globalMouse(cursor)) {
            host.toUi(cursor[0], cursor[1], out);
        } else {
            host.toUi(w.x() + x, w.y() + y, out);
        }
        return true;
    }

    @Override
    public Window locate(float uiX, float uiY, float[] toWindow) {
        OsDockHost.Window w = host == null ? null : host.regionAt(uiX, uiY);
        Entry e = w == null ? null : entry(w.id());
        if (e == null) {
            return null;
        }
        toWindow[0] = w.originX();
        toWindow[1] = w.originY();
        toWindow[2] = 1 / w.uiPerScreen();
        return e.window;
    }

    // ================================================================ OsWindows

    @Override
    @AllowAllocation("monitors are listed on user action")
    public List<Monitor> monitors() {
        List<Display> displays = platform.displays();
        List<Monitor> out = new ArrayList<>(displays.size());
        for (Display d : displays) {
            out.add(new Monitor(platform.displayKey(d.id()), d.bounds().x(), d.bounds().y(), d.bounds().width(),
                d.bounds().height(), d.usableBounds().x(), d.usableBounds().y(), d.usableBounds().width(),
                d.usableBounds().height(), d.primary()));
        }
        return out;
    }

    @Override
    public boolean cursor(float[] out) {
        return platform.globalMouse(out);
    }

    @Override
    @AllowAllocation("a window opens on user action")
    public int open(String title, int x, int y, int width, int height) {
        Window window = platform.createWindow(WindowOptions.of(title == null || title.isEmpty() ? "true-sim" : title,
            width, height), x, y);
        ViewPort view = graph.addView("ui.window" + window.id()).size(Math.max(1, window.pixelWidth()),
            Math.max(1, window.pixelHeight()));
        GpuUiRenderer renderer = mainRenderer.share();
        view.add(new UiBackdrop()).add(renderer);
        Entry e = new Entry(window, view, renderer);
        if (presenter != null) {
            e.target = presenter.present(view, Keys.SCENE_COLOR, window, settings);
        }
        entries = Arrays.copyOf(entries, entries.length + 1);
        entries[entries.length - 1] = e;
        return window.id();
    }

    @Override
    @AllowAllocation("a window closes on user action")
    public void close(int window) {
        Entry e = entry(window);
        if (e == null) {
            return;
        }
        Entry[] left = new Entry[entries.length - 1];
        int n = 0;
        for (Entry k : entries) {
            if (k != e) {
                left[n++] = k;
            }
        }
        entries = left;
        if (e.target != null) {
            e.target.close(); // waits for the GPU
        }
        graph.removeView(e.view);
        e.renderer.close();
        e.window.close();
    }

    @Override
    public void move(int window, int x, int y) {
        Window w = window(window);
        if (w != null) {
            w.setPosition(x, y);
        }
    }

    @Override
    public void title(int window, String title) {
        Window w = window(window);
        if (w != null && window != MAIN) {
            w.setTitle(title);
        }
    }

    @Override
    public void raise(int window) {
        Window w = window(window);
        if (w != null) {
            w.raise();
        }
    }

    @Override
    public void maximize(int window, boolean on) {
        Window w = window(window);
        if (w != null) {
            if (on) {
                w.maximize();
            } else {
                w.restore();
            }
        }
    }

    @Override
    public boolean maximized(int window) {
        Window w = window(window);
        return w != null && w.isMaximized();
    }

    @Override
    public boolean bounds(int window, int[] out) {
        Window w = window(window);
        if (w == null || w.isClosed()) {
            return false;
        }
        out[0] = w.x();
        out[1] = w.y();
        out[2] = w.width();
        out[3] = w.height();
        return true;
    }

    @Override
    public float pixelRatio(int window) {
        Window w = window(window);
        return w == null || w.width() <= 0 ? 1 : (float) w.pixelWidth() / w.width();
    }

    @Override
    public float contentScale(int window) {
        Window w = window(window);
        float s = w == null ? 1 : w.displayScale();
        return s > 0 ? s : 1;
    }

    /** Closes every OS window (the engine stops). */
    @AllowAllocation("stopping")
    public void closeAll() {
        while (entries.length > 0) {
            close(entries[0].window.id());
        }
    }

    // ================================================================ platform events

    @Override
    @AllowAllocation("closing a window is a user action")
    public void windowCloseRequested(int window) {
        if (host != null && entry(window) != null) {
            host.closeRequested(window);
        }
    }

    @Override
    public void windowFocusChanged(int window, boolean focused) {
        if (host != null && focused) {
            host.focused(window == main.id() ? MAIN : window);
        }
    }

    @Override
    public String toString() {
        return "UiOsWindows[" + entries.length + " windows]";
    }
}
