package com.truesim.engine.graph;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_D32_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM;

import com.truesim.engine.vulkan.Format;
import org.junit.jupiter.api.Test;

/** What pass bodies get wrong is caught before anything is recorded: no device needed. */
class PassContextTest {

    private final PassContext ctx = new PassContext(null);

    @Test
    void dispatchesAreBounded() {
        for (int[] groups : new int[][] {{-1, 1, 1}, {1, -1, 1}, {1, 1, -1}, {65_536, 1, 1}, {1, 65_536, 1},
            {1, 1, 65_536}}) {
            assertThatIllegalArgumentException().isThrownBy(() -> ctx.dispatch(null, null, groups[0], groups[1],
                groups[2])).withMessageStartingWith("Workgroups are 0 to 65,535 per dimension");
        }
        // No workgroups in some dimension: nothing is recorded, so nothing is touched.
        assertThatNoException().isThrownBy(() -> ctx.dispatch(null, null, 0));
        assertThatNoException().isThrownBy(() -> ctx.dispatch(null, null, 1, 0, 1));
        assertThatNoException().isThrownBy(() -> ctx.dispatch(null, null, 1, 1, 0));
    }

    @Test
    void copiesStayInsideTheirBuffers() {
        Buffer from = new Buffer(0, "from", 16);
        Buffer to = new Buffer(1, "to", 8);
        for (long[] c : new long[][] {{-1, 0, 4}, {0, -1, 4}, {0, 0, -1}, {14, 0, 4}, {0, 6, 4}}) {
            assertThatIllegalArgumentException().isThrownBy(() -> ctx.copy(from, c[0], to, c[1], c[2]))
                .withMessageStartingWith("Copying");
        }
        assertThatNoException().isThrownBy(() -> ctx.copy(from, to, 0)); // nothing to copy: nothing recorded
    }

    @Test
    void renderingNeedsTargetsOfOneSize() {
        Image color = new Image(0, "color", ImageDesc.of(Format.R8G8B8A8_UNORM, 8, 8));
        Image wide = new Image(1, "depth", ImageDesc.of(Format.D32_SFLOAT, 16, 8));
        Image tall = new Image(2, "depth", ImageDesc.of(Format.D32_SFLOAT, 8, 16));
        assertThatIllegalArgumentException().isThrownBy(() -> ctx.beginRendering(null, null, true, 0, 0, 0, 0))
            .withMessageStartingWith("Rendering into a color or depth image");
        assertThatIllegalArgumentException().isThrownBy(() -> ctx.beginRendering(color, wide, true, 0, 0, 0, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> ctx.beginRendering(color, tall, true, 0, 0, 0, 0));
    }
}
