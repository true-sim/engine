package com.truesim.engine.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.truesim.engine.vulkan.Format;
import org.junit.jupiter.api.Test;

/** Graphics program descriptions are values: equal ones are one program, and misuse is caught without a device. */
class GraphicsProgramTest {

    @Test
    void equalDescriptionsAreEqual() {
        GraphicsProgram a = GraphicsProgram.of(8).vertex("s.spv", "v").fragment("s.spv", "f")
            .color(Format.R8G8B8A8_UNORM).depth(Format.D32_SFLOAT).drawBackFaces();
        GraphicsProgram b = GraphicsProgram.of(8).vertex("s.spv", "v").fragment("s.spv", "f")
            .color(Format.R8G8B8A8_UNORM).depth(Format.D32_SFLOAT).drawBackFaces();
        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a.doubleSided()).isTrue();
        assertThat(a.stages()).extracting(GraphicsProgram.Stage::kind)
            .containsExactly(GraphicsProgram.Kind.VERTEX, GraphicsProgram.Kind.FRAGMENT);
        assertThat(a).isNotEqualTo(GraphicsProgram.of(8).vertex("s.spv", "v").fragment("s.spv", "f"));
        assertThat(GraphicsProgram.of(0).task("m.spv", "t").mesh("m.spv", "m").stages()).hasSize(2);
    }

    @Test
    void misuseIsCaught() {
        assertThatIllegalArgumentException().isThrownBy(() -> GraphicsProgram.of(6));
        assertThatIllegalArgumentException().isThrownBy(() -> GraphicsProgram.of(-4));
        assertThatIllegalArgumentException().isThrownBy(() -> GraphicsProgram.of(0).vertex("a", "b").vertex("a", "c"))
            .withMessageContaining("VERTEX stage already");
        assertThatIllegalArgumentException().isThrownBy(() -> GraphicsProgram.of(0).color(Format.D32_SFLOAT));
        assertThatIllegalArgumentException().isThrownBy(() -> GraphicsProgram.of(0).depth(Format.R8G8B8A8_UNORM));
    }
}
