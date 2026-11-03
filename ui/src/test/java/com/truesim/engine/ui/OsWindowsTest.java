package com.truesim.engine.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.KHRSwapchain.VK_KHR_SWAPCHAIN_EXTENSION_NAME;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16B16A16_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_UNDEFINED;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
import static org.lwjgl.vulkan.VK10.vkCmdCopyImageToBuffer;

import com.truesim.engine.graph.ExternalImage;
import com.truesim.engine.graph.FrameGraph;
import com.truesim.engine.graph.PresentSettings;
import com.truesim.engine.graph.Presenter;
import com.truesim.engine.graph.ViewPort;
import com.truesim.engine.platform.Platform;
import com.truesim.engine.platform.PlatformListener;
import com.truesim.engine.platform.Window;
import com.truesim.engine.platform.WindowOptions;
import com.truesim.engine.render.DisplayTransform;
import com.truesim.engine.render.Keys;
import com.truesim.engine.vulkan.Format;
import com.truesim.engine.vulkan.Gpu;
import com.truesim.engine.vulkan.GpuBuffer;
import com.truesim.engine.vulkan.GpuImage;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.engine.vulkan.VulkanException;
import com.truesim.engine.vulkan.VulkanInstance;
import com.truesim.ui.controls.Ui;
import com.truesim.ui.controls.overlay.Overlays;
import com.truesim.ui.controls.windows.DockPanel;
import com.truesim.ui.controls.windows.DockPosition;
import com.truesim.ui.controls.windows.DockSpace;
import com.truesim.ui.controls.windows.OsDockHost;
import com.truesim.ui.controls.windows.OsWindows;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.draw.UiTextures;
import com.truesim.ui.core.input.Pointer;
import com.truesim.ui.core.input.UiInput;
import com.truesim.ui.fonts.cjk.BundledCjkFonts;
import com.truesim.ui.layout.LayoutKind;
import com.truesim.ui.layout.Unit;
import com.truesim.ui.style.tss.Tss;
import com.truesim.ui.text.AtlasSettings;
import com.truesim.ui.text.FontRegistry;
import com.truesim.ui.text.GlyphAtlas;
import com.truesim.ui.text.TextSystem;
import com.truesim.ui.text.natives.NativeTextBackend;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.junit.jupiter.api.Test;

/**
 * Docking into OS windows over the engine: real windows of the platform, each presented by the presenter from a view
 * of its own drawing its region of one UI. A tab dragged beyond the main window opens a second window that follows
 * the cursor; both windows render; the window's close button docks its panel back. With
 * {@code TRUESIM_SCREENSHOTS=true} it writes {@code ui-windows/os-window-*.png} (the UI image of each window over the
 * dark backdrop the gallery screenshots use).
 */
class OsWindowsTest {

    /** A view's UI image read back after a frame. */
    private static final class Readback implements AutoCloseable {
        final int width;
        final int height;
        final GpuImage image;
        final GpuBuffer pixels;
        final ExternalImage out;
        final float[] rgba;

        Readback(VulkanDevice device, ViewPort view) {
            width = view.width();
            height = view.height();
            image = new GpuImage(device, width, height, VK_FORMAT_R16G16B16A16_SFLOAT,
                VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT, VK_IMAGE_ASPECT_COLOR_BIT);
            pixels = GpuBuffer.hostVisible(device, (long) width * height * 8, VK_BUFFER_USAGE_TRANSFER_DST_BIT);
            out = new ExternalImage("read back " + view.name(), width, height, Format.R16G16B16A16_SFLOAT,
                VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT, VK_IMAGE_LAYOUT_UNDEFINED,
                VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL).set(image.handle(), image.view());
            rgba = new float[width * height * 4];
            view.output(Keys.UI, out);
        }

        /** Copies the image last rendered into {@link #rgba} (the GPU idle). */
        void read(VulkanDevice device) {
            device.waitIdle();
            device.submitAndWait(device.graphics(), commands -> {
                try (MemoryStack stack = stackPush()) {
                    VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
                    region.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
                    region.imageExtent().set(width, height, 1);
                    vkCmdCopyImageToBuffer(commands, image.handle(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                        pixels.handle(), region);
                }
            });
            for (int i = 0; i < rgba.length; i++) {
                rgba[i] = Float.float16ToFloat(pixels.memory().getAtIndex(ValueLayout.JAVA_SHORT_UNALIGNED, i));
            }
        }

        float alpha() {
            float sum = 0;
            for (int i = 3; i < rgba.length; i += 4) {
                sum += rgba[i];
            }
            return sum;
        }

        void screenshot(String name) {
            if (!"true".equals(System.getenv("TRUESIM_SCREENSHOTS"))) {
                return;
            }
            BufferedImage png = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int o = (y * width + x) * 4;
                    float a = Math.min(1, Math.max(0, rgba[o + 3]));
                    int rgb = 0;
                    for (int c = 0; c < 3; c++) {
                        float linear = rgba[o + c] + 0.006f * (1 - a);
                        rgb = rgb << 8 | (int) (GpuRig.srgb(Math.min(1, Math.max(0, linear))) * 255 + 0.5f);
                    }
                    png.setRGB(x, y, rgb);
                }
            }
            try {
                Path file = GpuRig.SCREENSHOTS.resolve("ui-windows/" + name + ".png");
                Files.createDirectories(file.getParent());
                ImageIO.write(png, "png", file.toFile());
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public void close() {
            pixels.close();
            image.close();
        }
    }

    /** The platform's windows, but the cursor where the test puts it. */
    private static final class Steered implements OsWindows {
        final UiOsWindows os;
        float x;
        float y;

        Steered(UiOsWindows os) {
            this.os = os;
        }

        @Override
        public List<Monitor> monitors() {
            return os.monitors();
        }

        @Override
        public boolean cursor(float[] out) {
            out[0] = x;
            out[1] = y;
            return true;
        }

        @Override
        public int open(String title, int px, int py, int width, int height) {
            return os.open(title, px, py, width, height);
        }

        @Override
        public void close(int window) {
            os.close(window);
        }

        @Override
        public void move(int window, int px, int py) {
            os.move(window, px, py);
        }

        @Override
        public void title(int window, String title) {
            os.title(window, title);
        }

        @Override
        public void raise(int window) {
            os.raise(window);
        }

        @Override
        public void maximize(int window, boolean on) {
            os.maximize(window, on);
        }

        @Override
        public boolean maximized(int window) {
            return os.maximized(window);
        }

        @Override
        public boolean bounds(int window, int[] out) {
            return os.bounds(window, out);
        }

        @Override
        public float pixelRatio(int window) {
            return os.pixelRatio(window);
        }

        @Override
        public float contentScale(int window) {
            return os.contentScale(window);
        }
    }

    private static TextSystem text(UiTextures textures) {
        NativeTextBackend backend = new NativeTextBackend();
        FontRegistry fonts = BundledCjkFonts.registry(backend);
        GlyphAtlas atlas = new GlyphAtlas(textures, backend.msdfGenerator(), backend.newRasterizer(), null, null,
            new AtlasSettings());
        return new TextSystem(fonts, backend.newShaper(), atlas);
    }

    private static VulkanInstance instance() {
        try {
            return VulkanInstance.builder().applicationName("tests").validation(true).build();
        } catch (VulkanException | UnsatisfiedLinkError e) {
            assumeThat(e).as("no Vulkan available").isNull();
            throw e;
        }
    }

    /** The parts of one run: platform, GPU, presenter, the UI and its docking into OS windows. */
    private Platform platform;
    private VulkanDevice device;
    private Presenter presenter;
    private ViewPort mainView;
    private GpuUiRenderer renderer;
    private Ui ui;
    private UiOsWindows os;
    private Steered steered;
    private OsDockHost host;
    private DockSpace dock;
    private final UiInput input = new UiInput(8);
    private long nanos = 1_000_000_000L;
    private int buttons;
    private final float[] at = new float[2];

    /** One frame: events, the cursor as the UI's mouse, the UI, every window rendered and presented. */
    private int frame() {
        platform.pollEvents(new PlatformListener() {
            @Override
            public void windowCloseRequested(int window) {
                os.windowCloseRequested(window);
            }
        });
        input.beginFrame();
        host.toUi(steered.x, steered.y, at);
        input.pointer(MousePointer.ID, Pointer.Kind.MOUSE).moveTo(at[0], at[1]).buttons(buttons);
        ui.update(mainView.width(), mainView.height(), input, nanos += 16_666_667L);
        renderer.submit(ui.layers(), ui.layerCount(), ui.textures(), mainView.width(), mainView.height());
        os.submit(ui);
        return presenter.render();
    }

    private void cursor(float x, float y, int pressed) {
        steered.x = x;
        steered.y = y;
        buttons = pressed;
        frame();
    }

    @Test
    void aTabDraggedOutOfTheMainWindowOpensASecondWindowBothRenderAndClosingItDocksThePanelBack() {
        platform = Platform.start();
        try (VulkanInstance instance = instance()) {
            assumeThat(instance.gpus()).as("no Vulkan 1.3 GPU").isNotEmpty();
            Gpu gpu = instance.bestGpu();
            assumeThat(gpu.supports(VK_KHR_SWAPCHAIN_EXTENSION_NAME)).as("no presentation").isTrue();
            List<Readback> readbacks = new ArrayList<>();
            try (VulkanDevice d = VulkanDevice.create(gpu);
                 FrameGraph graph = new FrameGraph(d, 2);
                 Presenter p = new Presenter(graph, instance)) {
                device = d;
                presenter = p.outputTransform(new DisplayTransform());
                try {
                    run(graph, readbacks);
                } finally {
                    d.waitIdle();
                    for (Readback r : readbacks) {
                        r.close();
                    }
                }
            }
            assertThat(instance.validationErrors()).as("validation errors").isZero();
        } finally {
            platform.close();
        }
    }

    private void run(FrameGraph graph, List<Readback> readbacks) {
        Window main = platform.createWindow(WindowOptions.of("true-sim docking", 1000, 600), 100, 100);
        renderer = new GpuUiRenderer(graph);
        mainView = graph.addView("main").add(new UiBackdrop()).add(renderer);
        try {
            presenter.present(mainView, Keys.SCENE_COLOR, main);
        } catch (VulkanException e) { // SDL's offscreen driver needs headless surfaces, not every driver has
            assumeThat(e.getMessage()).as("no headless surfaces").doesNotContain("headless_surface");
            throw e;
        }
        UiTextures textures = new UiTextures();
        ui = new Ui(Tss.defaultTheme(), text(textures), textures);
        Overlays overlays = new Overlays(ui);
        dock = new DockSpace(overlays, null);
        int root = ui.store().rootSlot();
        ui.ownLayout(root, Ui.CODE_KIND);
        ui.layout().kind(root, LayoutKind.NONE);
        ui.root().add(dock.root());
        ui.codeLayout(dock.root(), Ui.CODE_WIDTH | Ui.CODE_HEIGHT);
        ui.layout().width(dock.root().slot(), 100, Unit.PERCENT).height(dock.root().slot(), 100, Unit.PERCENT);
        DockPanel scene = panel("scene", "Scene");
        DockPanel hierarchy = panel("hierarchy", "Hierarchy");
        DockPanel inspector = panel("inspector", "Inspector");
        DockPanel console = panel("console", "Console");
        dock.dock(scene, DockPosition.CENTER);
        dock.dock(hierarchy, DockPosition.LEFT);
        dock.dock(inspector, DockPosition.RIGHT);
        dock.dock(console, scene, DockPosition.BOTTOM);
        os = new UiOsWindows(platform, graph, presenter, main, renderer, PresentSettings.defaults());
        steered = new Steered(os);
        host = new OsDockHost(overlays, dock, steered);
        os.host(host);
        assertThat(os.host()).isSameAs(host);
        assertThat(os.main()).isSameAs(main);
        int[] rect = new int[4];
        assertThat(os.bounds(OsWindows.MAIN, rect)).isTrue();
        steered.x = rect[0] + 500;
        steered.y = rect[1] + 300;
        for (int i = 0; i < 40; i++) {
            frame();
        }
        assertThat(presenter.render()).as("the main window alone").isEqualTo(1);

        // the console's tab dragged beyond the main window's right edge: a window opens and follows the cursor
        Element tab = console.tab();
        float[] from = new float[2];
        host.toScreen(ui.layout().x(tab.slot()) + 20, ui.layout().y(tab.slot()) + ui.layout().height(tab.slot()) / 2,
            from);
        cursor(from[0], from[1], Pointer.PRIMARY);
        float endX = rect[0] + rect[2] + 260;
        for (int i = 1; i <= 6; i++) {
            cursor(from[0] + (endX - from[0]) * i / 6, from[1] - 40 * i / 6f, Pointer.PRIMARY);
        }
        assertThat(os.count()).as("an OS window opened").isEqualTo(1);
        OsDockHost.Window w = host.followingWindow();
        assertThat(w).isNotNull();
        assertThat(dock.areaOf(console)).isSameAs(w.area());
        Window second = os.window(w.id());
        assertThat(second.title()).isEqualTo("Console");
        cursor(endX + 40, from[1], Pointer.PRIMARY);
        second.sync();
        int[] placed = new int[4];
        os.bounds(w.id(), placed);
        if (!"wayland".equals(platform.videoDriver())) {
            assertThat(placed[0]).as("the window follows the cursor").isEqualTo(w.x());
        }
        cursor(endX + 40, from[1], 0);
        assertThat(host.followingWindow()).isNull();
        for (int i = 0; i < 30; i++) {
            frame();
        }
        assertThat(presenter.targets()).hasSize(2);
        assertThat(frame()).as("both windows render").isEqualTo(2);
        assertThat(os.target(w.id())).isNotNull();
        assertThat(os.renderer(w.id()).source()).isSameAs(renderer);
        assertThat(os.pixelRatio(w.id())).isPositive();
        assertThat(os.contentScale(w.id())).isPositive();
        assertThat(os.monitors()).isNotEmpty();
        // the mouse of either window maps into the UI; text input finds the window of a field
        assertThat(os.map(second.id(), 5, 5, at)).isTrue();
        float[] toWindow = new float[3];
        assertThat(os.locate(w.originX() + 4, w.originY() + 4, toWindow)).isSameAs(second);
        assertThat(toWindow[0]).isEqualTo(w.originX());
        assertThat(os.locate(4, 4, toWindow)).isNull();

        // both windows' UI images, read back
        Readback mainImage = new Readback(device, mainView);
        Readback windowImage = new Readback(device, os.view(w.id()));
        readbacks.add(mainImage);
        readbacks.add(windowImage);
        frame();
        frame();
        mainImage.read(device);
        windowImage.read(device);
        mainImage.screenshot("os-window-main");
        windowImage.screenshot("os-window-undocked");
        assertThat(windowImage.alpha()).as("the window draws its region of the UI").isPositive();
        assertThat(windowImage.alpha() / (windowImage.width * windowImage.height))
            .as("the dock window's background covers it").isGreaterThan(0.9f);

        // its close button: the panel docks back where it was
        os.windowFocusChanged(second.id(), true);
        os.title(second.id(), "renamed");
        os.raise(second.id());
        os.windowCloseRequested(second.id());
        frame();
        assertThat(os.count()).isZero();
        assertThat(presenter.targets()).hasSize(1);
        assertThat(dock.layout().toText()).contains("tabs(0; scene) tabs(0; console)").doesNotContain("window");
        assertThat(frame()).isEqualTo(1);
        frame();
        mainImage.read(device);
        mainImage.screenshot("os-window-redocked");
        assertThat(os.toString()).contains("0 windows");
        os.closeAll();
        device.waitIdle();
        renderer.close();
    }

    private DockPanel panel(String id, String title) {
        DockPanel p = dock.panel(id, title);
        p.content().add(ui.label(null, title + " content"));
        return p;
    }
}
