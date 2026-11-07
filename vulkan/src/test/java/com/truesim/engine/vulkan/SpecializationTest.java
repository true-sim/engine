package com.truesim.engine.vulkan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;

/**
 * Specialization constants by id, sparse; the SpecId decorations a module declares; the device's shader variants,
 * closed to replacements once a shader is loaded.
 */
class SpecializationTest {

    @Test
    void constantsAreSortedByIdAndCompareByValue() {
        Specialization own = Specialization.of(7, 9);
        Specialization hooks = Specialization.NONE.with(1025, 1).with(1024, 0);
        Specialization both = own.with(hooks).with(1, 3);
        assertThat(both.size()).isEqualTo(4);
        assertThat(both.id(0)).isZero();
        assertThat(both.value(1)).isEqualTo(3);
        assertThat(both.id(2)).isEqualTo(1024);
        assertThat(both.id(3)).isEqualTo(1025);
        assertThat(both.has(1024)).isTrue();
        assertThat(both.has(2)).isFalse();
        assertThat(both).isEqualTo(Specialization.of(7, 3).with(1025, 1).with(1024, 0))
            .hasSameHashCodeAs(Specialization.of(7, 3).with(1025, 1).with(1024, 0)).isNotEqualTo(own)
            .isNotEqualTo("constants");
        assertThat(both.toString()).isEqualTo("{0=7, 1=3, 1024=0, 1025=1}");
        assertThat(Specialization.NONE.toString()).isEqualTo("{}");
        assertThatThrownBy(() -> own.with(-1, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aModulesSpecIdsAreReadFromItsDecorations() {
        // The header, then OpDecorate %5 SpecId 1024, OpDecorate %6 Location 0, OpDecorate %7 SpecId 3, a nop
        int[] words = {0x07230203, 0x00010600, 0, 10, 0,
            4 << 16 | 71, 5, 1, 1024,
            4 << 16 | 71, 6, 30, 0,
            4 << 16 | 71, 7, 1, 3,
            1 << 16};
        ByteBuffer code = ByteBuffer.allocate(4 * words.length).order(ByteOrder.LITTLE_ENDIAN);
        for (int w : words) {
            code.putInt(w);
        }
        assertThat(ShaderModule.constantIds(code.flip())).containsExactly(3, 1024);
        ByteBuffer cut = ByteBuffer.allocate(4 * 6).order(ByteOrder.LITTLE_ENDIAN);
        cut.putInt(0x07230203).putInt(0).putInt(0).putInt(0).putInt(0).putInt(0); // a word count of 0: stops there
        assertThat(ShaderModule.constantIds(cut.flip())).isEmpty();
    }

    @Test
    void variantsTakeNoReplacementOnceAShaderIsLoaded() {
        ShaderVariants variants = new ShaderVariants();
        assertThat(variants.closed()).isFalse();
        variants.replace("shaders/a.spv", "shaders/b.spv");
        variants.attach(String.class, "installed by");
        assertThat(variants.attached(String.class)).isEqualTo("installed by");
        assertThat(variants.attached(Integer.class)).isNull();
        assertThat(variants.resolve("shaders/a.spv")).isEqualTo("shaders/b.spv");
        assertThat(variants.resolve("shaders/c.spv")).isEqualTo("shaders/c.spv");
        assertThat(variants.closed()).isTrue();
        assertThatThrownBy(() -> variants.replace("shaders/c.spv", "shaders/d.spv"))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("loaded shaders already");
    }
}
