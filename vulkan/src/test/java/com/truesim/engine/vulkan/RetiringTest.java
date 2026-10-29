package com.truesim.engine.vulkan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Retiring frees resources once no frame in flight can read them, oldest first, and the rest when it closes. */
class RetiringTest {

    @Test
    void resourcesGoOnceTheirFramesFinish() {
        FrameSlots slots = new FrameSlots(2);
        List<String> freed = new ArrayList<>();
        try (Retiring retiring = new Retiring(slots)) {
            slots.begin(0);
            retiring.add(() -> freed.add("a")); // frames 1 and 2 may read it
            slots.end();
            slots.begin(1);
            for (int i = 0; i < 9; i++) {
                int n = i;
                retiring.add(() -> freed.add("b" + n)); // grows past its first room
            }
            retiring.release();
            assertThat(freed).isEmpty();
            slots.end();
            slots.begin(0); // frame 3: frame 1 has finished
            retiring.release();
            assertThat(freed).containsExactly("a");
            assertThat(retiring.count()).isEqualTo(9);
            slots.end();
            slots.begin(1);
            retiring.release();
            assertThat(freed).hasSize(10).endsWith("b8");
            assertThat(retiring.count()).isZero();
            retiring.release(); // nothing left
            com.truesim.testing.AllocationAssertions.assertAllocationFree(retiring::release);
            retiring.add(() -> freed.add("c"));
        }
        assertThat(freed).as("the rest when closed").endsWith("c");
    }

    @Test
    void singleSlotsAndClosedRetiringFreeAtOnce() {
        List<String> freed = new ArrayList<>();
        Retiring single = new Retiring(FrameSlots.single());
        single.add(() -> freed.add("a"));
        single.release();
        assertThat(freed).containsExactly("a");
        single.close();
        single.add(() -> freed.add("b"));
        assertThat(freed).containsExactly("a", "b");
    }

    @Test
    void failuresAreReportedAndTheResourceLetGo() {
        Retiring retiring = new Retiring(FrameSlots.single());
        retiring.add(() -> {
            throw new IOException("stuck");
        });
        assertThatIllegalStateException().isThrownBy(retiring::release).withCauseInstanceOf(IOException.class);
        assertThat(retiring.count()).isZero();
        retiring.add(() -> {
            throw new IllegalArgumentException("bad");
        });
        org.assertj.core.api.Assertions.assertThatIllegalArgumentException().isThrownBy(retiring::close);
        assertThatNullPointerException().isThrownBy(() -> retiring.add(null));
        assertThatNullPointerException().isThrownBy(() -> new Retiring(null));
    }
}
