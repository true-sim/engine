package com.truesim.engine.ui;

import static com.truesim.testing.AllocationAssertions.assertAllocationFree;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.truesim.engine.graph.Access;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Image;
import com.truesim.engine.graph.ImageDesc;
import com.truesim.engine.graph.Key;
import com.truesim.engine.graph.Technique;
import com.truesim.engine.render.Keys;
import com.truesim.engine.vulkan.Format;
import com.truesim.ui.core.draw.Rgba;
import com.truesim.ui.core.input.ScreenSample;
import java.util.List;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.Test;

/** The eyedropper's read-back of the scene color on the GPU: HDR values kept, the block placed, never waited for. */
class ScreenSamplerTest {

    private static final Offset<Float> CLOSE = within(0.01f);

    /** A scene of one colour, brighter than paper white in red. */
    private static final class Scene implements Technique {
        float r = 2.5f;
        float g = 0.5f;
        float b = 0.25f;

        @Override
        public List<Key<?>> provides() {
            return List.of(Keys.SCENE_COLOR);
        }

        @Override
        public void build(GraphBuilder builder) {
            Image image = builder.create("scene", ImageDesc.of(Format.R16G16B16A16_SFLOAT, 64, 48));
            builder.pass("scene").write(image, Access.TRANSFER_DST).record(ctx -> ctx.clear(image.whole(), r, g, b, 1));
            builder.provide(Keys.SCENE_COLOR, image);
        }
    }

    private static float[] rgb(long packed) {
        return new float[] {Rgba.r(packed), Rgba.g(packed), Rgba.b(packed)};
    }

    @Test
    void aBlockOfTheSceneColorArrivesFramesLaterWithHdrValuesKept() {
        try (GpuRig rig = GpuRig.open(64, 48).show(Keys.SCENE_COLOR)) {
            Scene scene = new Scene();
            GpuScreenSampler sampler = new GpuScreenSampler();
            rig.view.add(scene);
            rig.view.add(sampler);
            assertThat(sampler.request(10, 10, 5)).as("not built yet: nothing would arrive").isFalse();
            rig.frame();
            ScreenSample sample = new ScreenSample();
            assertThat(sampler.poll(sample)).as("nothing asked").isFalse();
            assertThat(sampler.request(-1, 10, 5)).as("outside the view").isFalse();
            assertThat(sampler.request(20, 40, 31)).isTrue();
            assertThat(sampler.sampling()).isTrue();
            int frames = 0;
            while (!sampler.poll(sample) && frames < 8) {
                rig.frame();
                frames++;
            }
            assertThat(frames).as("answered within the frames in flight, never waited for").isBetween(1, 4);
            assertThat(sample.size()).isEqualTo(31);
            assertThat(sample.x()).isEqualTo(20f);
            assertThat(sample.y()).isEqualTo(40f);
            assertThat(rgb(sample.center())).as("linear, above 1 kept").containsExactly(new float[] {2.5f, 0.5f, 0.25f},
                CLOSE);
            assertThat(rgb(sample.color(0, 0))).containsExactly(new float[] {2.5f, 0.5f, 0.25f}, CLOSE);
            // Past the image's bottom (rows 23 and on lie at y 48 and below): black.
            assertThat(rgb(sample.color(15, 23))).containsExactly(new float[] {0, 0, 0}, CLOSE);
            assertThat(rgb(sample.color(15, 22))).containsExactly(new float[] {2.5f, 0.5f, 0.25f}, CLOSE);
            assertThat(sampler.poll(sample)).as("nothing newer yet").isFalse();
            // The scene changes under a still pointer: a new block each frame shows it.
            scene.r = 4;
            for (int i = 0; i < 4; i++) {
                rig.frame();
            }
            assertThat(sampler.poll(sample)).isTrue();
            assertThat(Rgba.r(sample.center())).isEqualTo(4f, CLOSE);
            // In the corner: the texels left of and above the image read black.
            sampler.request(0, 0, 3);
            for (int i = 0; i < 4; i++) {
                rig.frame();
            }
            assertThat(sampler.poll(sample)).isTrue();
            assertThat(sample.size()).isEqualTo(3);
            assertThat(rgb(sample.color(0, 1))).containsExactly(new float[] {0, 0, 0}, CLOSE);
            assertThat(rgb(sample.color(1, 1))).containsExactly(new float[] {4, 0.5f, 0.25f}, CLOSE);
            assertThat(rgb(sample.color(2, 2))).containsExactly(new float[] {4, 0.5f, 0.25f}, CLOSE);
            assertThatThrownBy(() -> sampler.request(5, 5, 4)).isInstanceOf(IllegalArgumentException.class);
            // Stopped: nothing more is copied.
            sampler.stop();
            for (int i = 0; i < 4; i++) {
                rig.frame();
            }
            sampler.poll(sample); // a block copied before the stop may still arrive
            rig.frame();
            assertThat(sampler.poll(sample)).isFalse();
            assertThat(sampler.sampling()).isFalse();
        }
    }

    @Test
    void requestingAndPollingAllocateNothing() {
        try (GpuRig rig = GpuRig.open(64, 48).show(Keys.SCENE_COLOR)) {
            GpuScreenSampler sampler = new GpuScreenSampler();
            rig.view.add(new Scene());
            rig.view.add(sampler);
            rig.frame();
            ScreenSample sample = new ScreenSample();
            float[] x = {5};
            assertAllocationFree(() -> {
                sampler.request(x[0], 7, 9);
                sampler.poll(sample);
                x[0] = x[0] == 5 ? 6 : 5;
            });
        }
    }
}
