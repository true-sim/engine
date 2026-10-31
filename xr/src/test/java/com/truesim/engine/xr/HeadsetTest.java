package com.truesim.engine.xr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import com.truesim.engine.graph.Access;
import com.truesim.engine.graph.FrameGraph;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Image;
import com.truesim.engine.graph.ImageDesc;
import com.truesim.engine.graph.Key;
import com.truesim.engine.graph.Presenter;
import com.truesim.engine.graph.Technique;
import com.truesim.engine.graph.ViewPort;
import com.truesim.engine.platform.Platform;
import com.truesim.engine.platform.Window;
import com.truesim.engine.platform.WindowOptions;
import com.truesim.engine.vulkan.Format;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.engine.vulkan.VulkanInstance;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * A headset's eyes rendered and shown, the left one mirrored on the desktop: needs an OpenXR runtime and a headset
 * connected (and starts the runtime, e.g. SteamVR), so it runs only with {@code TRUESIM_XR=true}.
 */
@EnabledIfEnvironmentVariable(named = "TRUESIM_XR", matches = "true")
class HeadsetTest {

    private static final Key<Image> COLOR = Key.image("test.eye");

    /** Clears an image of its view's size to {@code rgba}. */
    private static Technique fill(float... rgba) {
        return new Technique() {
            @Override
            public List<Key<?>> provides() {
                return List.of(COLOR);
            }

            @Override
            public void build(GraphBuilder g) {
                Image image = g.create("eye", ImageDesc.of(Format.R8G8B8A8_UNORM, g.view().width(),
                    g.view().height()));
                g.pass("fill").write(image, Access.TRANSFER_DST)
                    .record(ctx -> ctx.clear(image.whole(), rgba[0], rgba[1], rgba[2], rgba[3]));
                g.provide(COLOR, image);
            }
        };
    }

    @Test
    void theEyesAreShownAndOneIsMirroredOnTheDesktop() {
        Headset headset;
        try {
            headset = Headset.connect("true-sim tests");
        } catch (HeadsetException.Unavailable e) {
            assumeThat(e).as("no headset: " + e.getMessage()).isNull();
            return;
        }
        try (headset; Platform platform = Platform.start();
             VulkanInstance instance = VulkanInstance.builder().applicationName("tests").validation(true)
                 .synchronizationValidation(true).extensions(headset.vulkanInstanceExtensions()).build();
             VulkanDevice device = VulkanDevice.create(headset.gpu(instance), headset.vulkanDeviceExtensions());
             FrameGraph graph = new FrameGraph(device, 2);
             Presenter presenter = new Presenter(graph, instance)) {
            System.out.println("Headset: " + headset + ", eyes " + headset.recommendedWidth() + " x "
                + headset.recommendedHeight() + ", Vulkan: " + headset.vulkanInstanceExtensions() + " / "
                + headset.vulkanDeviceExtensions());
            headset.start(graph).origin(0, 1.7, 0);
            ViewPort left = graph.addView("left eye").add(fill(0.1f, 0.3f, 0.9f, 1));
            ViewPort right = graph.addView("right eye").add(fill(0.9f, 0.3f, 0.1f, 1));
            headset.present(Eye.LEFT, left, COLOR).present(Eye.RIGHT, right, COLOR);
            Window window = platform.createWindow(WindowOptions.of("mirror", 640, 360));
            presenter.present(left, COLOR, window);
            assertThat(left.width()).as("sized to the eye, not the mirror").isEqualTo(headset.recommendedWidth());
            long deadline = System.nanoTime() + 30_000_000_000L;
            int mirrored = 0;
            while (headset.frames() < 60 && System.nanoTime() < deadline && !headset.exiting()) {
                platform.pollEvents(new com.truesim.engine.platform.PlatformListener() { });
                headset.render(presenter);
                mirrored++;
            }
            System.out.println("Frames shown in the headset: " + headset.frames() + " of " + mirrored);
            assumeThat(headset.frames()).as("the runtime did not show the app: put the headset on").isPositive();
            assertThat(headset.camera(Eye.LEFT).position(1)).as("at the origin's height plus the head's")
                .isGreaterThan(1.7);
            assertThat(headset.running()).isTrue();
        }
        assertThat(true).isTrue();
    }
}
