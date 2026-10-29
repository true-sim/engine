package com.truesim.engine.vulkan;

import static com.truesim.testing.AllocationAssertions.assertAllocationFree;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.lang.foreign.ValueLayout;
import org.junit.jupiter.api.Test;

/** Push constants laid out as Slang lays out a push struct for Vulkan. */
class PushTest {

    private static final ValueLayout.OfInt INT = ValueLayout.JAVA_INT_UNALIGNED;
    private static final ValueLayout.OfFloat FLOAT = ValueLayout.JAVA_FLOAT_UNALIGNED;
    private static final ValueLayout.OfLong LONG = ValueLayout.JAVA_LONG_UNALIGNED;

    @Test
    void fieldsAlignAsTheShaderReadsThem() {
        // struct { uint a; float4* p; float3 v; float w; float2 t; uint2 u; uint64_t big; float4x4 m; }
        Push push = new Push().u32(7).address(0x1234_5678_9abcL).vec3(1, 2, 3).f32(4).vec2(5, 6).uvec2(8, 9)
            .u64(-1);
        assertThat(push.memory().get(INT, 0)).isEqualTo(7);
        assertThat(push.memory().get(LONG, 8)).as("a pointer on 8").isEqualTo(0x1234_5678_9abcL);
        assertThat(push.memory().get(FLOAT, 16)).as("a float3 on 16").isEqualTo(1);
        assertThat(push.memory().get(FLOAT, 24)).isEqualTo(3);
        assertThat(push.memory().get(FLOAT, 28)).as("a float in the float3's last 4 bytes").isEqualTo(4);
        assertThat(push.memory().get(FLOAT, 32)).as("a float2 on 8").isEqualTo(5);
        assertThat(push.memory().get(INT, 44)).isEqualTo(9);
        assertThat(push.memory().get(LONG, 48)).isEqualTo(-1);
        float[] identity = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
        push.mat4(identity);
        assertThat(push.memory().get(FLOAT, 64)).as("a matrix on 16").isEqualTo(1);
        assertThat(push.memory().get(FLOAT, 64 + 4 * 15)).isEqualTo(1);
        assertThat(push.size()).isEqualTo(128);
        assertThatIllegalStateException().as("past 128 bytes").isThrownBy(() -> push.u32(0));
        assertThat(push.clear().size()).isZero();
        push.u32(1).vec4(1, 2, 3, 4).align(32).f32(9);
        assertThat(push.memory().get(FLOAT, 16)).as("a float4 on 16").isEqualTo(1);
        assertThat(push.memory().get(FLOAT, 32)).as("aligned to 32").isEqualTo(9);
        assertThat(push.address()).isNotZero();
        assertThatIllegalArgumentException().isThrownBy(() -> push.align(3));
        assertThatIllegalArgumentException().isThrownBy(() -> push.mat4(new float[4]));
    }

    @Test
    void refillingAllocatesNothing() {
        Push push = new Push();
        assertAllocationFree(() -> push.clear().address(42).u32(3).vec3(1, 2, 3).f32(0.5f), 10_000, 1_000, 5);
    }
}
