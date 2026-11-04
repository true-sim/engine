package com.truesim.engine.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.truesim.engine.graph.PresentSettings;
import com.truesim.engine.platform.MouseButton;
import com.truesim.engine.platform.Platform;
import com.truesim.engine.platform.Window;
import com.truesim.engine.platform.WindowOptions;
import com.truesim.engine.render.DisplayTransform;
import com.truesim.engine.settings.EngineSettings;
import com.truesim.ui.controls.Ui;
import com.truesim.ui.controls.overlay.Overlays;
import com.truesim.ui.controls.windows.DockSpace;
import com.truesim.ui.controls.windows.OsDockHost;
import com.truesim.ui.controls.windows.OsWindows;
import com.truesim.ui.core.input.Pointer;
import com.truesim.ui.core.input.TextInputHost;
import com.truesim.ui.core.input.UiInput;
import com.truesim.ui.style.tss.Tss;
import org.junit.jupiter.api.Test;

/**
 * The parts of docking's OS windows on the engine side without presenting: the mouse mapped through a mapper, text
 * input moving to the window of its field, and {@link UiOsWindows} over the platform's windows, each a headless view
 * drawing the main renderer's uploads at its region.
 */
class OsWindowsPartsTest {

    @Test
    void aMapperPutsTheMouseOfEveryWindowIntoTheUiAndLeavesOthersToTheOwnWindow() {
        MousePointer mouse = new MousePointer().mapper((window, x, y, out) -> {
            if (window != 7) {
                return false;
            }
            out[0] = 1000 + x;
            out[1] = 2000 + y;
            return true;
        });
        UiInput input = new UiInput(4);
        mouse.mouseMoved(0, 7, 0, 5, 6, 0, 0, MouseButton.LEFT.mask());
        input.beginFrame();
        mouse.poll(input);
        Pointer p = input.pointer(MousePointer.ID, Pointer.Kind.MOUSE);
        assertThat(p.x()).isEqualTo(1005);
        assertThat(p.y()).isEqualTo(2006);
        assertThat(p.buttons()).isEqualTo(Pointer.PRIMARY);
        mouse.mouseButton(0, 7, 0, MouseButton.LEFT, false, 1, 8, 9);
        mouse.mouseWheel(0, 7, 0, 0, 1);
        mouse.mouseWheel(0, 9, 0, 0, 1); // any window, as there is no own one
        input.beginFrame();
        mouse.poll(input);
        assertThat(input.pointer(MousePointer.ID, Pointer.Kind.MOUSE).x()).isEqualTo(1008);
        // another window, not mapped: the own window's (any, here) coordinates as they are
        mouse.mouseMoved(0, 3, 0, 40, 50, 0, 0, 0);
        input.beginFrame();
        mouse.poll(input);
        assertThat(input.pointer(MousePointer.ID, Pointer.Kind.MOUSE).x()).isEqualTo(40);
        MousePointer own = new MousePointer(null, null).mapper(null);
        own.mouseButton(0, 1, 0, MouseButton.RIGHT, true, 1, 2, 3);
        input.beginFrame();
        own.poll(input);
        assertThat(input.pointer(MousePointer.ID, Pointer.Kind.MOUSE).x()).isEqualTo(2);
    }

    @Test
    void textInputStartsOnTheWindowOfItsFieldAndPlacesTheImeThere() {
        Platform platform = Platform.start();
        try (InputRig rig = new InputRig()) {
            Window main = platform.createWindow(WindowOptions.of("main", 200, 100).withHidden(true)
                .withVulkan(false));
            Window other = platform.createWindow(WindowOptions.of("other", 200, 100).withHidden(true)
                .withVulkan(false));
            UiTextInput text = new UiTextInput(rig.commands, main).locator((x, y, out) -> {
                if (x < 1000) {
                    return null;
                }
                out[0] = 1000;
                out[1] = 0;
                out[2] = 0.5f;
                return other;
            });
            text.start(10, 10, 50, 20, TextInputHost.TEXT);
            assertThat(text.window()).isSameAs(main);
            assertThat(main.textInputActive()).isTrue();
            text.start(1010, 10, 50, 20, TextInputHost.TEXT); // focus moved to a field in the other window
            assertThat(text.window()).isSameAs(other);
            text.area(1020, 10, 50, 20);
            text.start(1030, 10, 50, 20, TextInputHost.TEXT);
            assertThat(text.window()).isSameAs(other);
            other.close();
            text.start(10, 10, 50, 20, TextInputHost.TEXT); // back home; the other window is gone
            assertThat(text.window()).isSameAs(main);
            text.stop();
            main.close();
            text.stop(); // its window gone: nothing to stop there
        } finally {
            platform.close();
        }
    }

    @Test
    void osWindowsOpenAViewEachDrawingTheirRegionAndCloseWithIt() {
        Platform platform = Platform.start();
        try (GpuRig rig = GpuRig.open(320, 200); GpuUiRenderer renderer = new GpuUiRenderer(rig.graph)) {
            rig.view.add(renderer);
            Window main = platform.createWindow(WindowOptions.of("main", 320, 200).withVulkan(false), 50, 60);
            UiOsWindows os = new UiOsWindows(platform, rig.graph, null, main, renderer, null);
            assertThat(os.host()).isNull();
            assertThat(os.map(main.id(), 1, 1, new float[2])).isFalse();
            assertThat(os.locate(1, 1, new float[3])).isNull();
            os.windowCloseRequested(main.id());
            os.windowFocusChanged(main.id(), true);
            Ui ui = new Ui(Tss.defaultTheme(), null);
            os.submit(ui); // no host: nothing
            Overlays overlays = new Overlays(ui);
            DockSpace dock = new DockSpace(overlays, null);
            OsDockHost host = new OsDockHost(overlays, dock, os);
            os.host(host);
            int[] rect = new int[4];
            assertThat(os.bounds(OsWindows.MAIN, rect)).isTrue();
            assertThat(os.pixelRatio(OsWindows.MAIN)).isPositive();
            assertThat(os.contentScale(OsWindows.MAIN)).isPositive();
            assertThat(os.pixelRatio(12345)).isEqualTo(1);
            assertThat(os.contentScale(12345)).isEqualTo(1);
            assertThat(os.bounds(12345, rect)).isFalse();
            assertThat(os.maximized(12345)).isFalse();
            assertThat(os.view(12345)).isNull();
            assertThat(os.renderer(12345)).isNull();
            assertThat(os.target(12345)).isNull();
            assertThat(os.window(12345)).isNull();
            os.move(12345, 0, 0);
            os.title(12345, "none");
            os.raise(12345);
            os.maximize(12345, true);
            os.close(12345);
            os.title(OsWindows.MAIN, "the main window keeps its title");
            assertThat(main.title()).isEqualTo("main");
            float[] cursor = new float[2];
            assertThat(os.cursor(cursor)).isEqualTo(platform.globalMouse(cursor));

            int id = os.open("", 400, 300, 160, 120);
            assertThat(os.count()).isEqualTo(1);
            Window w = os.window(id);
            assertThat(w.title()).isEqualTo("true-sim");
            assertThat(os.view(id).name()).isEqualTo("ui.window" + id);
            assertThat(os.renderer(id).source()).isSameAs(renderer);
            assertThat(os.target(id)).as("no presenter").isNull();
            os.title(id, "Panel");
            assertThat(w.title()).isEqualTo("Panel");
            os.move(id, 420, 310);
            os.raise(id);
            os.maximize(id, true);
            os.maximized(id);
            os.maximize(id, false);
            assertThat(os.bounds(id, rect)).isTrue();
            os.windowFocusChanged(id, false);
            os.windowFocusChanged(id, true);
            // a paper white of its own, in a view of the graph's settings following them while unset
            EngineSettings global = rig.graph.settings();
            assertThat(os.displaySettings(id)).isNotSameAs(global);
            assertThat(os.displaySettings(12345)).isNull();
            global.set(DisplayTransform.PAPER_WHITE, 220f);
            assertThat(os.displaySettings(id).get(DisplayTransform.PAPER_WHITE)).isEqualTo(220f);
            assertThat(os.paperWhite(id, 300)).isTrue();
            assertThat(os.paperWhite(12345, 300)).isFalse();
            assertThat(os.displaySettings(id).get(DisplayTransform.PAPER_WHITE)).isEqualTo(300f);
            assertThat(os.paperWhite(OsWindows.MAIN, 10)).as("no presenter: a view of its own all the same").isTrue();
            assertThat(os.displaySettings(OsWindows.MAIN).get(DisplayTransform.PAPER_WHITE)).as("clamped")
                .isEqualTo(40f);
            assertThat(os.displaySettings(main.id())).isSameAs(os.displaySettings(OsWindows.MAIN));
            assertThat(global.get(DisplayTransform.PAPER_WHITE)).isEqualTo(220f);
            os.paperWhite(id, Float.NaN);
            assertThat(os.displaySettings(id).get(DisplayTransform.PAPER_WHITE)).isEqualTo(220f);
            os.paperWhite(id, 300);
            // see-through where the system can: SDL says whether it did (not Wayland, nor the offscreen driver)
            assertThat(os.opacity(12345, 0.5f)).isFalse();
            if (os.opacity(id, 0.5f)) {
                assertThat(w.opacity()).isCloseTo(0.5f, org.assertj.core.api.Assertions.within(0.01f));
                assertThat(os.opacity(id, 1)).isTrue();
            } else {
                assertThat(w.opacity()).as(platform.videoDriver() + " refuses: opaque").isEqualTo(1);
            }
            os.windowCloseRequested(id); // not one of the host's windows: nothing
            assertThat(os.count()).isEqualTo(1);
            os.submit(ui); // the host has no window of this id: nothing drawn
            os.closeAll();
            assertThat(os.count()).isZero();
            assertThat(w.isClosed()).isTrue();
            assertThat(global.inheriting("ui.window" + id).isSet(DisplayTransform.PAPER_WHITE))
                .as("a closed window's value is gone").isFalse();
            assertThat(os.monitors()).isNotEmpty();
            assertThat(os.toString()).contains("0 windows");

            // through the host: a window for an area, drawn at its region by a renderer sharing the main one's
            UiOsWindows drawn = new UiOsWindows(platform, rig.graph, null, main, renderer, PresentSettings.defaults());
            OsDockHost dockHost = new OsDockHost(overlays, dock, drawn);
            drawn.host(dockHost);
            dock.panel("p", "Panel");
            dock.dock(dock.find("p"), com.truesim.ui.controls.windows.DockPosition.CENTER);
            ui.update(320, 200);
            dock.undock(dock.find("p"));
            ui.update(320, 200);
            OsDockHost.Window region = dockHost.followingWindow() != null ? dockHost.followingWindow()
                : dockHost.window(dock.areaOf(dock.find("p")));
            assertThat(region).isNotNull();
            drawn.submit(ui);
            GpuUiRenderer shared = drawn.renderer(region.id());
            assertThat(shared.originX()).isEqualTo(region.originX());
            assertThat(shared.originY()).isEqualTo(region.originY());
            renderer.submit(ui.layers(), ui.layerCount(), ui.textures(), 320, 200);
            shared.submit(ui.layers(), ui.layerCount(), null, 160, 120);
            assertThat(shared.uploadedBytes()).as("its source uploads").isZero();
            assertThat(shared.draws()).isEqualTo(renderer.draws());
            assertThat(shared.slot(1)).isEqualTo(renderer.slot(1));
            assertThat(shared.share().source()).isSameAs(renderer);
            assertThat(drawn.map(main.id(), 3, 4, cursor)).isTrue();
            assertThat(drawn.map(54321, 3, 4, cursor)).isFalse();
            float[] to = new float[3];
            assertThat(drawn.locate(region.originX() + 1, region.originY() + 1, to)).isSameAs(drawn.window(region.id()));
            drawn.windowCloseRequested(region.id());
            assertThat(drawn.count()).isZero();
            assertThat(dock.areaOf(dock.find("p")).isMain()).isTrue();
        } finally {
            platform.close();
        }
    }
}
