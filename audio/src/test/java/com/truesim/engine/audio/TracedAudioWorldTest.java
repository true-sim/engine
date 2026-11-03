package com.truesim.engine.audio;

import static com.truesim.testing.AllocationAssertions.assertAllocationFree;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.truesim.scene.audio.AudioMaterial;
import com.truesim.scene.audio.Transmission;
import org.junit.jupiter.api.Test;

/** The world over a tracer: crossings' material ids mapped, batches handed through (the GPU's in the demo's tests). */
class TracedAudioWorldTest {

    private static final int WORDS = 5; // two crossings a segment kept

    /** A tracer answering every batch at once: the first segment through ids 1 and 2, the second through 3, the rest none. */
    private static final class Fake {
        double[] ends;
        int count;

        long submit(double[] ends, int count) {
            if (this.ends != null) {
                return 0; // one batch out at a time
            }
            this.ends = ends;
            this.count = count;
            return 7;
        }

        int collect(long ticket, int[] out) {
            if (ticket != 7 || ends == null) {
                return -1;
            }
            java.util.Arrays.fill(out, 0, count * WORDS, 0);
            out[0] = 2;
            out[2] = 1;
            out[4] = 2;
            out[WORDS] = 1;
            out[WORDS + 2] = 3;
            ends = null;
            return count;
        }
    }

    @Test
    void crossingsAreMappedToTheirMaterialsAndBatchesPassThrough() {
        Fake fake = new Fake();
        SegmentTracer tracer = SegmentTracer.of(4, WORDS, fake::submit, fake::collect);
        assertThat(tracer.capacity()).isEqualTo(4);
        assertThat(tracer.words()).isEqualTo(WORDS);
        AudioMaterial[] table = {null, AudioMaterial.CONCRETE, AudioMaterial.GLASS, null};
        TracedAudioWorld world = new TracedAudioWorld(tracer, id -> table[id]);
        assertThat(world.capacity()).isEqualTo(4);
        Transmission[] out = {new Transmission(), new Transmission(), new Transmission()};
        assertThat(world.collect(7, out, 0)).as("nothing submitted").isEqualTo(-1);
        double[] rays = new double[18];
        long ticket = world.submit(rays, 3);
        assertThat(ticket).isEqualTo(7);
        assertThat(fake.ends).isSameAs(rays);
        assertThat(world.submit(rays, 3)).as("a batch already out").isZero();
        assertThat(world.collect(ticket, out, 0)).isEqualTo(3);
        Transmission walled = new Transmission().open().cross(AudioMaterial.CONCRETE).cross(AudioMaterial.GLASS);
        assertThat(out[0].mid()).as("concrete then glass").isEqualTo(walled.mid());
        assertThat(out[0].high()).isEqualTo(walled.high());
        assertThat(out[1].mid()).as("a material mapped to nothing: open").isEqualTo(1);
        assertThat(out[2].mid()).as("no crossings").isEqualTo(1);
        assertAllocationFree(() -> {
            for (int i = 0; i < 100; i++) {
                world.collect(world.submit(rays, 3), out, 0);
            }
        });
    }

    @Test
    void aTracerNeedsRoomAndBothMethods() {
        Fake fake = new Fake();
        assertThatThrownBy(() -> SegmentTracer.of(0, WORDS, fake::submit, fake::collect))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SegmentTracer.of(4, 0, fake::submit, fake::collect))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SegmentTracer.of(4, WORDS, null, fake::collect))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> SegmentTracer.of(4, WORDS, fake::submit, null))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new TracedAudioWorld(null, id -> null)).isInstanceOf(NullPointerException.class);
    }
}
