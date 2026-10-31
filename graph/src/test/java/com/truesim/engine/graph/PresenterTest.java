package com.truesim.engine.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.lwjgl.vulkan.KHRSwapchain.VK_KHR_SWAPCHAIN_EXTENSION_NAME;

import com.truesim.engine.platform.Platform;
import com.truesim.engine.platform.Window;
import com.truesim.engine.platform.WindowOptions;
import com.truesim.engine.vulkan.Format;
import com.truesim.engine.vulkan.Gpu;
import com.truesim.engine.vulkan.PresentMode;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.engine.vulkan.VulkanException;
import com.truesim.engine.vulkan.VulkanInstance;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PresenterTest {

    private static final Key<Image> COLOR = Key.image("test.color");

    /** Clears an image of its view's size to {@code rgba}: a view's color. */
    private static Technique fill(float... rgba) {
        return new FrameGraphTest.Lambda(List.of(COLOR), g -> {
            Image image = g.create("color", ImageDesc.of(Format.R8G8B8A8_UNORM, g.view().width(), g.view().height()));
            g.pass("fill").write(image, Access.TRANSFER_DST)
                .record(ctx -> ctx.clear(image.whole(), rgba[0], rgba[1], rgba[2], rgba[3]));
            g.provide(COLOR, image);
        });
    }

    @Test
    void viewsArePresentedToTheirWindowsWhenTheyRun() {
        try (Platform platform = Platform.start(); VulkanInstance instance = instance()) {
            assumeThat(instance.gpus()).as("no Vulkan 1.3 GPU").isNotEmpty();
            Gpu gpu = instance.bestGpu();
            assumeThat(gpu.supports(VK_KHR_SWAPCHAIN_EXTENSION_NAME)).as("no presentation").isTrue();
            Window first = platform.createWindow(WindowOptions.of("first", 320, 240));
            Window second = platform.createWindow(WindowOptions.of("second", 200, 150));
            Window third = platform.createWindow(WindowOptions.of("third", 160, 120));
            try (VulkanDevice device = VulkanDevice.create(gpu);
                 FrameGraph graph = new FrameGraph(device, 2);
                 Presenter presenter = new Presenter(graph, instance)) {
                ViewPort main = graph.addView("main").add(fill(0, 0, 1, 1));
                ViewPort side = graph.addView("side").add(fill(1, 0, 0, 1));
                ViewPort extra = graph.addView("extra").add(fill(0, 1, 0, 1));
                Presenter.Target mainTarget;
                try {
                    mainTarget = presenter.present(main, COLOR, first);
                } catch (VulkanException e) { // SDL's offscreen driver needs headless surfaces, not every driver has
                    assumeThat(e.getMessage()).as("no headless surfaces").doesNotContain("headless_surface");
                    throw e;
                }
                Presenter.Target sideTarget = presenter.present(side, COLOR, second,
                    PresentSettings.defaults().withPresentMode(PresentMode.MAILBOX));
                Presenter.Target extraTarget = presenter.present(extra, COLOR, third);
                assertThat(presenter.targets()).containsExactly(mainTarget, sideTarget, extraTarget);
                assertThat(main.width()).as("the view sized to its window").isEqualTo(first.pixelWidth());
                assertThat(sideTarget.settings().presentMode()).isEqualTo(PresentMode.MAILBOX);
                assertThat(sideTarget.window()).isSameAs(second);
                assertThat(sideTarget.view()).isSameAs(side);
                assertThat(sideTarget).hasToString("presenting view side to Window " + second.id());
                for (int f = 0; f < 4; f++) {
                    assertThat(presenter.render()).as("every window, one frame").isEqualTo(3);
                }
                // A view off: its window is not acquired or presented, and keeps its last frame.
                side.enabled(false);
                assertThat(presenter.render()).isEqualTo(2);
                assertThat(presenter.render()).isEqualTo(2);
                long presented = sideTarget.presented();
                side.enabled(true).rate(2);
                int[] counts = {presenter.render(), presenter.render(), presenter.render(), presenter.render()};
                assertThat(counts).as("every other frame for the side view").containsExactly(3, 2, 3, 2);
                assertThat(sideTarget.presented()).isEqualTo(presented + 2);
                side.rate(1);
                // A window resized: its swapchain and its view follow.
                first.setSize(400, 300);
                first.sync();
                presenter.render();
                assertThat(main.width()).isEqualTo(first.pixelWidth());
                assertThat(main.height()).isEqualTo(first.pixelHeight());
                // Minimized, a window shows nothing new; where the system minimizes it, its view runs no frame.
                third.minimize();
                third.sync();
                assertThat(presenter.render()).isIn(2, 3);
                third.restore();
                third.sync();
                // Present settings changed while running, saved and read back.
                sideTarget.apply(PresentSettings.defaults().withPresentMode(PresentMode.IMMEDIATE));
                sideTarget.apply(sideTarget.settings());
                assertThat(presenter.render()).isEqualTo(3);
                assertThat(PresentSettings.fromMap(sideTarget.settings().toMap())).isEqualTo(sideTarget.settings());
                // A window closed stops being presented to; so does a target closed.
                third.close();
                assertThat(presenter.targets()).containsExactly(mainTarget, sideTarget);
                assertThat(presenter.render()).isEqualTo(2);
                sideTarget.close();
                sideTarget.close();
                assertThat(presenter.render()).isEqualTo(1);
                assertThat(mainTarget.presented()).isPositive();
                // Closed, it presents no more.
                presenter.close();
                assertThat(presenter.targets()).isEmpty();
                assertThatIllegalStateException().isThrownBy(presenter::render);
                assertThatIllegalStateException().isThrownBy(() -> presenter.present(main, COLOR, first));
            }
            assertThat(instance.validationErrors()).as("validation errors").isZero();
        }
    }

    /** A validated instance with surfaces, or the test skipped where there is no Vulkan. */
    private static VulkanInstance instance() {
        try {
            return VulkanInstance.builder().applicationName("tests").validation(true).synchronizationValidation(true)
                .build();
        } catch (VulkanException | UnsatisfiedLinkError e) {
            assumeThat(e).as("no Vulkan available").isNull();
            throw e;
        }
    }

    @Test
    void presentSettingsSaveAndLoad() {
        PresentSettings defaults = PresentSettings.defaults();
        assertThat(defaults.presentMode()).as("vsync").isEqualTo(PresentMode.FIFO);
        PresentSettings mailbox = defaults.withPresentMode(PresentMode.MAILBOX);
        assertThat(mailbox.toMap()).containsExactly(Map.entry("presentMode", "MAILBOX"));
        assertThat(PresentSettings.fromMap(mailbox.toMap())).isEqualTo(mailbox);
        assertThat(PresentSettings.fromMap(Map.of("presentMode", " IMMEDIATE ", "hdr", "true")).presentMode())
            .isEqualTo(PresentMode.IMMEDIATE);
        assertThat(PresentSettings.fromMap(Map.of("presentMode", "SOMETHING_NEW"))).isEqualTo(defaults);
        assertThat(PresentSettings.fromMap(Map.of())).isEqualTo(defaults);
        assertThatNullPointerException().isThrownBy(() -> defaults.withPresentMode(null));
    }
}
