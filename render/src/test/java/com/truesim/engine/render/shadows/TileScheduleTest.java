package com.truesim.engine.render.shadows;

import static org.assertj.core.api.Assertions.assertThat;

import com.truesim.engine.render.shadows.ShadowSettings.Updates;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class TileScheduleTest {

    private final TileSchedule schedule = new TileSchedule();

    private int[] order(int n) {
        int[] out = new int[n];
        for (int i = 0; i < n; i++) {
            out[i] = schedule.order(i);
        }
        return out;
    }

    @Test
    void cappedDrawsTheMostImportantUpToTheCap() {
        double[] importance = {5, 50, 1, 20, 10};
        int[] waited = {9, 0, 30, 0, 0};
        int n = schedule.schedule(Updates.CAPPED, 5, importance, waited, 2, 0, 0);
        assertThat(n).isEqualTo(2);
        assertThat(order(5)).containsExactly(1, 3, 4, 0, 2); // waiting changes nothing: as before the modes
        assertThat(schedule.forced()).isZero();
    }

    @Test
    void allDrawsEveryTileMostImportantFirst() {
        double[] importance = {5, 50, 1, 20, 10};
        int n = schedule.schedule(Updates.ALL, 5, importance, new int[5], 2, 0, 0);
        assertThat(n).isEqualTo(5);
        assertThat(order(5)).containsExactly(1, 3, 4, 0, 2);
    }

    @Test
    void importanceDrawsUrgentTilesBeyondTheBudget() {
        double[] importance = {300, 10, 1000, 20, 400, 5};
        int[] waited = new int[6];
        int n = schedule.schedule(Updates.IMPORTANCE, 6, importance, waited, 1, 256, 5);
        assertThat(schedule.forced()).as("three at or over the threshold").isEqualTo(3);
        assertThat(n).as("the three and the budget's one").isEqualTo(4);
        assertThat(order(4)).containsExactly(2, 4, 0, 3);
    }

    @Test
    void theThresholdIsInclusive() {
        double[] importance = {256, 255.9};
        int n = schedule.schedule(Updates.IMPORTANCE, 2, importance, new int[2], 0, 256, 5);
        assertThat(schedule.forced()).isEqualTo(1);
        assertThat(order(1)).containsExactly(0);
        assertThat(n).as("a budget of none: an even share of the lag's frames").isEqualTo(2);
    }

    @Test
    void tilesThatWaitedTheLagAreForced() {
        double[] importance = {100, 1, 40, 2};
        int[] waited = {0, 2, 1, 3};
        int n = schedule.schedule(Updates.IMPORTANCE, 4, importance, waited, 1, 1.0e9, 2);
        assertThat(schedule.forced()).as("waited 2 and 3 frames, the lag 2").isEqualTo(2);
        assertThat(n).isEqualTo(3);
        assertThat(order(3)).containsExactly(3, 1, 0); // 2 x 4 > 1 x 3; then the most important of the rest
    }

    @Test
    void waitingRaisesARank() {
        double[] importance = {10, 4};
        int[] waited = {0, 2}; // 4 x 3 = 12 > 10
        schedule.schedule(Updates.IMPORTANCE, 2, importance, waited, 1, 1.0e9, 100);
        assertThat(order(2)).containsExactly(1, 0);
    }

    @Test
    void noLagDrawsEveryTile() {
        double[] importance = {1, 2, 3};
        int n = schedule.schedule(Updates.IMPORTANCE, 3, importance, new int[3], 1, 1.0e9, 0);
        assertThat(n).isEqualTo(3);
        assertThat(schedule.forced()).isEqualTo(3);
    }

    @Test
    void manyWaitingAreSpreadOverTheLag() {
        int count = 900;
        double[] importance = new double[count];
        Arrays.fill(importance, 1);
        int n = schedule.schedule(Updates.IMPORTANCE, count, importance, new int[count], 64, 1.0e9, 2);
        assertThat(n).as("a third of them: each within 3 frames").isEqualTo(300);
        n = schedule.schedule(Updates.IMPORTANCE, 10, importance, new int[10], 64, 1.0e9, 2);
        assertThat(n).as("few: the budget takes them all").isEqualTo(10);
    }

    @Test
    void steadyChangesWaitNoLongerThanTheLag() {
        // every tile changes every frame: none ever waits past the lag, and the draws stay even
        int count = 1000;
        int lag = 2;
        double[] importance = new double[count];
        int[] waited = new int[count];
        for (int i = 0; i < count; i++) {
            importance[i] = 1 + (i * 7919 % 1000);
        }
        int most = 0;
        int least = Integer.MAX_VALUE;
        for (int frame = 0; frame < 60; frame++) {
            int n = schedule.schedule(Updates.IMPORTANCE, count, importance, waited, 64, 1.0e9, lag);
            boolean[] drawn = new boolean[count];
            for (int i = 0; i < n; i++) {
                drawn[schedule.order(i)] = true;
            }
            for (int i = 0; i < count; i++) {
                assertThat(waited[i]).isLessThanOrEqualTo(lag);
                waited[i] = drawn[i] ? 0 : waited[i] + 1;
            }
            if (frame >= 10) {
                most = Math.max(most, n);
                least = Math.min(least, n);
            }
        }
        assertThat(most - least).as("no burst of late tiles").isLessThan(count / 4);
    }

    @Test
    void noImportanceRanksLast() {
        double[] importance = {Double.NaN, -1, 3, 0};
        schedule.schedule(Updates.CAPPED, 4, importance, new int[4], 4, 0, 0);
        assertThat(schedule.order(0)).isEqualTo(2);
    }

    @Test
    void sortsLargeArraysLargestFirst() {
        long[] a = new long[5000];
        java.util.Random random = new java.util.Random(7);
        for (int i = 0; i < a.length; i++) {
            a[i] = random.nextLong() >>> 1;
        }
        long[] expected = a.clone();
        Arrays.sort(expected);
        TileSchedule.sortDescending(a, 0, a.length);
        for (int i = 0; i < a.length; i++) {
            assertThat(a[i]).isEqualTo(expected[a.length - 1 - i]);
        }
    }
}
