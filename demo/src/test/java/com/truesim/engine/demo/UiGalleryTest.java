package com.truesim.engine.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.lwjgl.sdl.SDLEvents.SDL_EVENT_KEY_DOWN;
import static org.lwjgl.sdl.SDLEvents.SDL_EVENT_KEY_UP;
import static org.lwjgl.sdl.SDLEvents.SDL_EVENT_MOUSE_BUTTON_DOWN;
import static org.lwjgl.sdl.SDLEvents.SDL_EVENT_MOUSE_BUTTON_UP;
import static org.lwjgl.sdl.SDLEvents.SDL_EVENT_MOUSE_MOTION;
import static org.lwjgl.sdl.SDLEvents.SDL_PushEvent;
import static org.lwjgl.sdl.SDLMouse.SDL_WarpMouseInWindow;
import static org.lwjgl.sdl.SDLVideo.SDL_GetWindowFromID;

import com.truesim.engine.app.Engine;
import com.truesim.engine.platform.Key;
import com.truesim.scene.core.ComponentStore;
import com.truesim.scene.core.PickHits;
import com.truesim.scene.core.Selected;
import com.truesim.scene.core.SceneGraph;
import com.truesim.ui.scene.SceneView;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.lwjgl.sdl.SDL_CommonEvent;
import org.lwjgl.sdl.SDL_Event;
import org.lwjgl.sdl.SDL_KeyboardEvent;
import org.lwjgl.sdl.SDL_MouseButtonEvent;
import org.lwjgl.sdl.SDL_MouseMotionEvent;

/**
 * The UI gallery's 3D page driven as a user drives it: SDL mouse and key events pushed into the platform's queue, read
 * by the engine's input, the UI (its whole gallery on top) and the scene interaction, in the real window. A click
 * selects one rock, Shift+click adds, Ctrl+click toggles, a drag from the ground draws a marquee, Ctrl+A selects all
 * and the gizmo's X arrow then moves every selected rock by the same delta.
 */
class UiGalleryTest {

    private static final int SHIFT = 0x0001;
    private static final int CTRL = 0x0040;

    private final SDL_Event event = SDL_Event.calloc();
    private GalleryShots shots;
    private UiGallery app;
    private Engine engine;
    private int window;
    private int modifiers;
    private float mouseX;
    private float mouseY;

    @Test
    void theThreeDPageSelectsWithTheMouseAndTheGizmoMovesTheWholeSelection() {
        app = new UiGallery(0, false);
        try {
            engine = Engine.create(app);
        } catch (RuntimeException | UnsatisfiedLinkError e) { // StartupFailure, no display or no presentation (CI)
            assumeThat(e).as("no window or GPU: " + e).isNull();
            throw e;
        }
        try (Engine running = engine) {
            window = app.world.window.id();
            // what the window shows at each step, as PNGs: build/gallery-shots
            for (var target : engine.get(com.truesim.engine.graph.Rendering.KEY).presenter().targets()) {
                if (target.view() == app.world.port) {
                    shots = new GalleryShots(target.shownKey(), java.nio.file.Path.of("build", "gallery-shots"));
                    app.world.port.add(shots);
                }
            }
            SceneGraph graph = app.world.graph;
            for (int i = 0; i < 2000 && app.world.assets.get(GalleryWorld.ROCK) == null; i++) {
                engine.step();
            }
            frames(10);
            assertThat(graph.hasBounds(app.world.rocks[0])).as("drawn rocks are bounded: the picker's broad phase finds "
                + "them").isTrue();
            ComponentStore<Selected> selected = graph.components().store(Selected.TYPE);
            List<long[]> near = visibleRocks();
            assertThat(near.size()).as("rocks to click").isGreaterThan(2);
            long first = near.get(0)[0];
            long second = near.get(1)[0];
            // a click selects the rock under the mouse alone
            click(first, 0);
            assertThat(selectedNodes(selected)).as("a click").containsExactly(first);
            assertThat(app.gizmo.count()).as("the gizmo follows").isOne();
            click(second, SHIFT);
            assertThat(selectedNodes(selected)).as("Shift+click adds").containsExactlyInAnyOrder(first, second);
            click(first, CTRL);
            assertThat(selectedNodes(selected)).as("Ctrl+click toggles").containsExactly(second);
            shot("1-clicked");
            // a click on the ground selects nothing
            float[] ground = groundPoint();
            press(ground[0], ground[1]);
            release(ground[0], ground[1]);
            assertThat(selected.size()).as("a click on nothing").isZero();
            // a marquee from the ground over the middle of the view
            float[] at = new float[3];
            SceneView view = app.scene.mainView();
            press(ground[0], ground[1]);
            float toX = ground[0] + 260;
            float toY = ground[1] - 160;
            for (int i = 1; i <= 6; i++) {
                move(ground[0] + (toX - ground[0]) * i / 6, ground[1] + (toY - ground[1]) * i / 6, true);
            }
            release(toX, toY);
            List<Long> inside = new ArrayList<>();
            for (long rock : app.world.rocks) {
                double[] t = translation(graph, rock);
                if (view.project(t[0], t[1], t[2], at) && at[0] > ground[0] + 5 && at[0] < toX - 5
                    && at[1] > toY + 5 && at[1] < ground[1] - 5) {
                    inside.add(rock);
                }
            }
            assertThat(inside).as("rocks in the marquee").isNotEmpty();
            assertThat(selectedNodes(selected)).as("the marquee selects what it covers").containsAll(inside);
            shot("2-marquee");
            // Ctrl+A, then the gizmo's X arrow dragged: every selected rock moves by the same delta
            key(Key.LEFT_CONTROL, true);
            key(Key.A, true);
            key(Key.A, false);
            key(Key.LEFT_CONTROL, false);
            frames(2);
            assertThat(selected.size()).as("Ctrl+A").isEqualTo(GalleryWorld.ROCKS);
            assertThat(app.gizmo.count()).as("the gizmo holds them all").isEqualTo(GalleryWorld.ROCKS);
            double[][] before = new double[GalleryWorld.ROCKS][];
            for (int i = 0; i < GalleryWorld.ROCKS; i++) {
                before[i] = translation(graph, app.world.rocks[i]);
            }
            double ox = app.gizmo.origin(0);
            double oy = app.gizmo.origin(1);
            double oz = app.gizmo.origin(2);
            double s = app.gizmo.size();
            float[] from = new float[3];
            float[] tip = new float[3];
            assertThat(view.project(ox + 0.6 * s, oy, oz, from)).isTrue();
            assertThat(view.project(ox + 1.2 * s, oy, oz, tip)).isTrue();
            move(from[0], from[1], false);
            frames(2);
            press(from[0], from[1]);
            for (int i = 1; i <= 8; i++) {
                move(from[0] + (tip[0] - from[0]) * i, from[1] + (tip[1] - from[1]) * i, true);
            }
            release(from[0] + (tip[0] - from[0]) * 8, from[1] + (tip[1] - from[1]) * 8);
            frames(2);
            shot("3-all-moved");
            double dx = translation(graph, app.world.rocks[0])[0] - before[0][0];
            assertThat(Math.abs(dx)).as("moved along X").isGreaterThan(0.1);
            for (int i = 0; i < GalleryWorld.ROCKS; i++) {
                double[] t = translation(graph, app.world.rocks[i]);
                assertThat(t[0] - before[i][0]).as("rock %d along X", i).isCloseTo(dx, within(1e-6));
                assertThat(t[1] - before[i][1]).as("rock %d along Y", i).isCloseTo(0, within(1e-6));
                assertThat(t[2] - before[i][2]).as("rock %d along Z", i).isCloseTo(0, within(1e-6));
            }
        } finally {
            event.free();
        }
    }

    @Test
    void sweepingTheMouseOverTheRocksKeepsFramesEven() {
        app = new UiGallery(0, false);
        try {
            engine = Engine.create(app);
        } catch (RuntimeException | UnsatisfiedLinkError e) { // StartupFailure, no display or no presentation (CI)
            assumeThat(e).as("no window or GPU: " + e).isNull();
            throw e;
        }
        try (Engine running = engine) {
            window = app.world.window.id();
            for (int i = 0; i < 2000 && app.world.assets.get(GalleryWorld.ROCK) == null; i++) {
                engine.step();
            }
            frames(30);
            var threads = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
            var hovered = app.world.graph.components().store(com.truesim.scene.core.Hovered.TYPE);
            int perPass = 0;
            long[] cpu = null;
            long[] bytes = null;
            int builds = 0;
            int hovering = 0;
            for (int pass = 0; pass < 3; pass++) { // two to warm up (the JIT, each mesh's pick BVH), one measured
                List<float[]> path = sweep();
                perPass = path.size();
                cpu = new long[perPass];
                bytes = new long[perPass];
                int builtBefore = app.world.frameGraph.builds();
                hovering = 0;
                for (int i = 0; i < perPass; i++) {
                    float[] at = path.get(i);
                    SDL_WarpMouseInWindow(SDL_GetWindowFromID(window), at[0], at[1]);
                    push(SDL_EVENT_MOUSE_MOTION);
                    long e = event.address();
                    SDL_MouseMotionEvent.nwindowID(e, window);
                    SDL_MouseMotionEvent.nx(e, at[0]);
                    SDL_MouseMotionEvent.ny(e, at[1]);
                    SDL_PushEvent(event);
                    long a0 = threads.getCurrentThreadAllocatedBytes();
                    long t0 = System.nanoTime();
                    engine.step();
                    cpu[i] = System.nanoTime() - t0;
                    bytes[i] = threads.getCurrentThreadAllocatedBytes() - a0;
                    hovering += hovered.size() > 0 ? 1 : 0;
                }
                builds = app.world.frameGraph.builds() - builtBefore;
            }
            long[] sorted = cpu.clone();
            java.util.Arrays.sort(sorted);
            double median = sorted[perPass / 2] / 1e6;
            double p99 = sorted[perPass * 99 / 100] / 1e6;
            double max = sorted[perPass - 1] / 1e6;
            long[] b = bytes.clone();
            java.util.Arrays.sort(b);
            int spikes = 0;
            for (long c : cpu) {
                spikes += c / 1e6 > 1.5 * median ? 1 : 0; // a frame missing the display's refresh takes twice as long
            }
            int allocating = 0;
            for (long x : bytes) {
                allocating += x > 0 ? 1 : 0;
            }
            System.out.printf(java.util.Locale.ROOT, "Sweep over %d frames (%d over a rock): frame %.2f ms median, "
                + "%.2f p99, %.2f max, %d over 1.5 x the median; graph builds %d; bytes allocated %d median, %d max, "
                + "%d frames allocating%n", perPass, hovering, median, p99, max, spikes, builds, b[perPass / 2],
                b[perPass - 1], allocating);
            assertThat(hovering).as("the sweep crosses rocks").isGreaterThan(perPass / 10);
            assertThat(builds).as("hovering rocks rebuilds no frame graph").isZero();
            assertThat(spikes).as("frames over 1.5 x the median").isZero();
            assertThat(b[perPass / 2]).as("bytes allocated in a frame, median").isZero();
            assertThat(allocating).as("frames allocating (a log line now and then)").isLessThan(perPass / 100);
        } finally {
            event.free();
        }
    }

    @Test
    void theNavBarTakesItsClicksOnEveryPage() {
        app = new UiGallery(0, false);
        try {
            engine = Engine.create(app);
        } catch (RuntimeException | UnsatisfiedLinkError e) { // StartupFailure, no display or no presentation (CI)
            assumeThat(e).as("no window or GPU: " + e).isNull();
            throw e;
        }
        try (Engine running = engine) {
            window = app.world.window.id();
            frames(20);
            var ui = app.scene.ui();
            int pages = app.pages.size();
            for (int from = 0; from < pages; from++) {
                app.show(from);
                frames(20);
                int to = (from + 1) % pages;
                com.truesim.ui.core.Element button = app.pages.get(to).button.element();
                float x = ui.layout().x(button.slot()) + ui.layout().width(button.slot()) / 2;
                float y = ui.layout().y(button.slot()) + ui.layout().height(button.slot()) / 2;
                int top = ui.interaction().picking().pickTop(x, y);
                boolean under = false;
                for (int at = top; at != com.truesim.ui.core.UiStore.NONE && !under;
                    at = ui.store().parent(at)) {
                    under = at == button.slot();
                }
                assertThat(under)
                    .as("page %s: the nav button %s is what the pointer hits at (%s, %s), not %s",
                        app.pages.get(from).name, app.pages.get(to).name, x, y,
                        top == com.truesim.ui.core.UiStore.NONE ? "nothing" : ui.store().element(top))
                    .isTrue();
                move(x, y, false);
                frames(2);
                press(x, y);
                release(x, y);
                frames(2);
                assertThat(app.current).as("page %s: a click on %s", app.pages.get(from).name,
                    app.pages.get(to).name).isEqualTo(to);
            }
        } finally {
            event.free();
        }
    }

    /** The mouse's path: rows across the view, over the rocks, 12 pixels a frame, clear of the UI. */
    private List<float[]> sweep() {
        List<float[]> path = new ArrayList<>();
        float w = app.world.window.width();
        float h = app.world.window.height();
        boolean right = true;
        for (float y = 160; y < h - 90; y += 60) {
            for (float x = 40; x < w - 320; x += 12) {
                path.add(new float[] {right ? x : w - 320 - (x - 40), y});
            }
            right = !right;
        }
        return path;
    }

    /** Rocks whose middle is on screen, clear of the UI, under the pointer first: nearest the camera first. */
    private List<long[]> visibleRocks() {
        SceneView view = app.scene.mainView();
        SceneGraph graph = app.world.graph;
        float[] at = new float[3];
        double[] ray = new double[6];
        PickHits hits = new PickHits(4);
        float w = app.world.window.width();
        float h = app.world.window.height();
        List<long[]> found = new ArrayList<>();
        List<Double> distance = new ArrayList<>();
        for (long rock : app.world.rocks) {
            double[] t = translation(graph, rock);
            if (!view.project(t[0], t[1], t[2], at) || at[0] < 40 || at[0] > w - 320 || at[1] < 60
                || at[1] > h - 80) {
                continue;
            }
            view.ray(at[0], at[1], ray);
            app.scene.picker().beginFrame();
            if (app.scene.picker().pick(ray[0], ray[1], ray[2], ray[3], ray[4], ray[5], 1e6, -1, hits) == 0
                || hits.node(0) != rock) {
                continue;
            }
            double d = view.depth(t[0], t[1], t[2]);
            int i = 0;
            while (i < distance.size() && distance.get(i) < d) {
                i++;
            }
            distance.add(i, d);
            found.add(i, new long[] {rock, Float.floatToIntBits(at[0]), Float.floatToIntBits(at[1])});
        }
        return found;
    }

    /** A point on screen over the ground, clear of rocks and the UI, with room for a marquee up and right of it. */
    private float[] groundPoint() {
        SceneView view = app.scene.mainView();
        double[] ray = new double[6];
        PickHits hits = new PickHits(4);
        float h = app.world.window.height();
        for (float y = h - 120; y > h / 2; y -= 7) {
            for (float x = 60; x < 600; x += 7) {
                view.ray(x, y, ray);
                app.scene.picker().beginFrame();
                if (app.scene.picker().pick(ray[0], ray[1], ray[2], ray[3], ray[4], ray[5], 1e6, -1, hits) == 0) {
                    return new float[] {x, y};
                }
            }
        }
        throw new AssertionError("no bare ground");
    }

    private void click(long rock, int mods) {
        float[] at = null;
        for (long[] r : visibleRocks()) {
            if (r[0] == rock) {
                at = new float[] {Float.intBitsToFloat((int) r[1]), Float.intBitsToFloat((int) r[2])};
            }
        }
        assertThat(at).as("rock on screen").isNotNull();
        if ((mods & SHIFT) != 0) {
            key(Key.LEFT_SHIFT, true);
        }
        if ((mods & CTRL) != 0) {
            key(Key.LEFT_CONTROL, true);
        }
        move(at[0], at[1], false);
        frames(2);
        press(at[0], at[1]);
        release(at[0], at[1]);
        if ((mods & SHIFT) != 0) {
            key(Key.LEFT_SHIFT, false);
        }
        if ((mods & CTRL) != 0) {
            key(Key.LEFT_CONTROL, false);
        }
        frames(2);
    }

    private static List<Long> selectedNodes(ComponentStore<Selected> selected) {
        List<Long> nodes = new ArrayList<>();
        for (int i = 0; i < selected.size(); i++) {
            nodes.add(selected.node(i));
        }
        return nodes;
    }

    private static double[] translation(SceneGraph graph, long node) {
        var world = graph.worldTransforms();
        long o = com.truesim.scene.core.NodeHandle.index(node) * world.stride();
        var s = world.segment();
        return new double[] {s.get(java.lang.foreign.ValueLayout.JAVA_DOUBLE, o),
            s.get(java.lang.foreign.ValueLayout.JAVA_DOUBLE, o + 8),
            s.get(java.lang.foreign.ValueLayout.JAVA_DOUBLE, o + 16)};
    }

    /** Writes what the window shows now (a few frames on) as {@code name}.png. */
    private void shot(String name) {
        if (shots != null) {
            frames(3); // the readback hands over a frame or two late: let the step's own frame be the one shot
            int written = shots.written();
            shots.take(name);
            for (int i = 0; i < 10 && shots.written() == written; i++) {
                engine.step();
            }
        }
    }

    private void frames(int n) {
        for (int i = 0; i < n; i++) {
            engine.step();
        }
    }

    private void push(int type) {
        event.clear();
        SDL_CommonEvent.ntype(event.address(), type);
    }

    private void move(float x, float y, boolean pressed) {
        // the cursor itself too: docking's OS windows map the mouse by the global cursor position
        SDL_WarpMouseInWindow(SDL_GetWindowFromID(window), x, y);
        push(SDL_EVENT_MOUSE_MOTION);
        long e = event.address();
        SDL_MouseMotionEvent.nwindowID(e, window);
        SDL_MouseMotionEvent.nx(e, x);
        SDL_MouseMotionEvent.ny(e, y);
        SDL_MouseMotionEvent.nxrel(e, x - mouseX);
        SDL_MouseMotionEvent.nyrel(e, y - mouseY);
        SDL_MouseMotionEvent.nstate(e, pressed ? 1 : 0);
        assertThat(SDL_PushEvent(event)).isTrue();
        mouseX = x;
        mouseY = y;
        engine.step();
    }

    private void press(float x, float y) {
        button(x, y, true);
    }

    private void release(float x, float y) {
        button(x, y, false);
    }

    private void button(float x, float y, boolean down) {
        SDL_WarpMouseInWindow(SDL_GetWindowFromID(window), x, y);
        push(down ? SDL_EVENT_MOUSE_BUTTON_DOWN : SDL_EVENT_MOUSE_BUTTON_UP);
        long e = event.address();
        SDL_MouseButtonEvent.nwindowID(e, window);
        SDL_MouseButtonEvent.nbutton(e, (byte) 1);
        SDL_MouseButtonEvent.ndown(e, down);
        SDL_MouseButtonEvent.nclicks(e, (byte) 1);
        SDL_MouseButtonEvent.nx(e, x);
        SDL_MouseButtonEvent.ny(e, y);
        assertThat(SDL_PushEvent(event)).isTrue();
        engine.step();
    }

    private void key(Key key, boolean down) {
        int bit = key == Key.LEFT_SHIFT ? SHIFT : key == Key.LEFT_CONTROL ? CTRL : 0;
        modifiers = down ? modifiers | bit : modifiers & ~bit;
        push(down ? SDL_EVENT_KEY_DOWN : SDL_EVENT_KEY_UP);
        long e = event.address();
        SDL_KeyboardEvent.nwindowID(e, window);
        SDL_KeyboardEvent.nscancode(e, key.usage());
        SDL_KeyboardEvent.nmod(e, (short) modifiers);
        SDL_KeyboardEvent.ndown(e, down);
        assertThat(SDL_PushEvent(event)).isTrue();
        engine.step();
    }
}
