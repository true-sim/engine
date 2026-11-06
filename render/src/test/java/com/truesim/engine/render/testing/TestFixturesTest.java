package com.truesim.engine.render.testing;

import static org.assertj.core.api.Assertions.assertThat;

import com.truesim.engine.render.Camera;
import com.truesim.engine.render.SceneView;
import com.truesim.engine.vulkan.testing.ReferenceImages;
import com.truesim.engine.vulkan.testing.TestGpu;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * The renderer's published test fixtures as a library's test uses them: a test GPU, the renderer set up with a target
 * read back, meshes built in code, and the frame compared with a reference image kept with the tests.
 */
class TestFixturesTest {

    private static final int SIZE = 64;

    @Test
    void aBoxDrawsAsItsReferenceImage() {
        try (TestGpu gpu = TestGpu.open(); TestRenderer r = TestRenderer.of(gpu.device(), SIZE, SIZE)) {
            int box = r.add(TestMeshes.box());
            int rock = r.add(TestMeshes.rock(2));
            // Turned 30 degrees about y, then tipped toward the camera: three faces in sight, each lit apart
            double turn = Math.toRadians(30);
            double tip = Math.toRadians(20);
            float qx = (float) (Math.sin(tip / 2) * Math.cos(turn / 2));
            float qy = (float) (Math.cos(tip / 2) * Math.sin(turn / 2));
            float qz = (float) (-Math.sin(tip / 2) * Math.sin(turn / 2));
            float qw = (float) (Math.cos(tip / 2) * Math.cos(turn / 2));
            r.scene().set(0, box, -0.8f, 0, -5, qx, qy, qz, qw, 1, 1, 1);
            r.scene().set(1, rock, 1.6f, 0.5f, -6, 0, 0, 0, 1, 0.8f, 0.8f, 0.8f);
            SceneView main = r.toTarget(r.main(new Camera().set(0, 0, -1, 0, 1, 0, Math.PI / 3, SIZE, SIZE, 0.1)));
            main.port().add(r.forward(main));
            r.frames(3);
            int[] background = r.target().rgba(1, 1);
            int[] onTheBox = r.target().rgba(SIZE / 2 - 10, SIZE / 2);
            assertThat(onTheBox).as("the box covers the left of the middle, not the background")
                .isNotEqualTo(background);
            new ReferenceImages(Path.of("src/test/references")).assertMatches("test-renderer-box-and-rock",
                r.target().shot(), 12, 0.05); // GPUs round and rasterize edges a little apart
            gpu.assertNoValidationErrors("drawing the fixtures' scene");
        }
    }
}
