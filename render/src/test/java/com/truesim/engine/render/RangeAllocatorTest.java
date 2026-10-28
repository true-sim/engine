package com.truesim.engine.render;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.truesim.common.math.random.Rng;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RangeAllocatorTest {

    @Test
    void firstFitWithAlignmentAndMerging() {
        RangeAllocator ranges = new RangeAllocator(100);
        assertThat(ranges.capacity()).isEqualTo(100);
        assertThat(ranges.allocate(10, 1)).isZero();
        assertThat(ranges.allocate(6, 4)).isEqualTo(12); // aligned: 10 and 11 stay free
        assertThat(ranges.pieces()).isEqualTo(2);
        assertThat(ranges.allocate(2, 1)).isEqualTo(10); // the gap fits
        assertThat(ranges.allocate(0, 1)).isZero(); // nothing to place
        assertThat(ranges.free()).isEqualTo(82);
        // Freeing the middle, then its neighbors, merges back into one piece.
        ranges.release(10, 2);
        assertThat(ranges.pieces()).isEqualTo(2);
        ranges.release(0, 10);
        ranges.release(12, 6);
        assertThat(ranges.pieces()).isEqualTo(1);
        assertThat(ranges.free()).isEqualTo(100);
        assertThat(ranges.allocate(100, 1)).isZero();
        assertThat(ranges.allocate(1, 1)).isEqualTo(-1);
        ranges.release(0, 100);
        ranges.release(5, 0);
        assertThat(ranges.allocate(101, 1)).isEqualTo(-1);
        // Misuse: outside the range, or freeing what is free.
        assertThatIllegalArgumentException().isThrownBy(() -> ranges.release(95, 10));
        assertThatIllegalStateException().isThrownBy(() -> ranges.release(10, 5));
        assertThat(new RangeAllocator(0).allocate(1, 1)).isEqualTo(-1);
    }

    @Test
    void randomUseKeepsRangesApartAndIsDeterministic() {
        assertThat(run(7)).isEqualTo(run(7));
    }

    /** Random allocations and releases, checked against each other; returns where everything went. */
    private static List<Long> run(long seed) {
        Rng rng = Rng.seeded(seed);
        RangeAllocator ranges = new RangeAllocator(10_000);
        List<long[]> held = new ArrayList<>();
        List<Long> starts = new ArrayList<>();
        for (int i = 0; i < 5_000; i++) {
            if (held.isEmpty() || rng.nextDouble() < 0.55) {
                long length = 1 + rng.nextInt(200);
                long alignment = rng.nextDouble() < 0.5 ? 1 : 4;
                long start = ranges.allocate(length, alignment);
                starts.add(start);
                if (start >= 0) {
                    assertThat(start % alignment).isZero();
                    for (long[] other : held) {
                        assertThat(start + length <= other[0] || other[0] + other[1] <= start).isTrue();
                    }
                    held.add(new long[] {start, length});
                }
            } else {
                long[] range = held.remove(rng.nextInt(held.size()));
                ranges.release(range[0], range[1]);
            }
            long used = held.stream().mapToLong(r -> r[1]).sum();
            assertThat(ranges.free()).isEqualTo(10_000 - used);
        }
        for (long[] range : held) {
            ranges.release(range[0], range[1]);
        }
        assertThat(ranges.pieces()).isEqualTo(1);
        return starts;
    }
}
