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
    void imageCommandsTakeOneMipLevel() {
        Image chain = new Image(0, "chain", ImageDesc.of(Format.R8G8B8A8_UNORM, 8, 8).mips(3).layers(2));
        Image depth = new Image(1, "depth", ImageDesc.of(Format.D32_SFLOAT, 8, 8));
        Buffer buffer = new Buffer(0, "buffer", 1024);
        assertThatIllegalArgumentException().isThrownBy(() -> ctx.copy(buffer, 0, chain.mips(0, 2)))
            .withMessage("Copying into chain mips 0-1 layers 0-1: one mip level");
        assertThatIllegalArgumentException().isThrownBy(() -> ctx.copy(chain.whole(), buffer, 0))
            .withMessageStartingWith("Copying from");
        assertThatIllegalArgumentException().isThrownBy(() -> ctx.copy(buffer, -1, chain.mip(0)))
            .withMessage("A buffer offset of -1");
        assertThatIllegalArgumentException().isThrownBy(() -> ctx.blit(chain.mips(0, 2), chain.mip(2), true))
            .withMessageStartingWith("Blitting from");
        assertThatIllegalArgumentException().isThrownBy(() -> ctx.blit(chain.mip(0), chain.mips(1, 2), true))
            .withMessageStartingWith("Blitting into");
        assertThatIllegalArgumentException().isThrownBy(() -> ctx.blit(chain.mip(0), new Subresource(chain, 1, 1, 0, 1),
            true)).withMessageEndingWith("as many layers");
        assertThatIllegalArgumentException().isThrownBy(() -> ctx.clear(depth.whole(), 0, 0, 0, 0))
            .withMessageContaining("a color image");
    }

    @Test
    void fillsAndScissorsAreBounded() {
        Buffer buffer = new Buffer(0, "buffer", 16);
        for (long[] f : new long[][] {{-4, 4}, {0, -4}, {2, 4}, {0, 6}, {8, 12}}) {
            assertThatIllegalArgumentException().isThrownBy(() -> ctx.fill(buffer, f[0], f[1], 0))
                .withMessageStartingWith("Filling");
        }
        assertThatNoException().isThrownBy(() -> ctx.fill(buffer, 4, 0, 0)); // nothing to fill: nothing recorded
        for (int[] s : new int[][] {{-1, 0, 1, 1}, {0, -1, 1, 1}, {0, 0, -1, 1}, {0, 0, 1, -1}}) {
            assertThatIllegalArgumentException().isThrownBy(() -> ctx.scissor(s[0], s[1], s[2], s[3]))
                .withMessageStartingWith("A scissor of");
        }
    }
}
