package com.truesim.engine.render.shadows;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.render.shadows.ShadowSettings.Updates;

/**
 * Which of a frame's changed atlas tiles it draws, and in which order ({@link ShadowSettings#ATLAS_UPDATES}): from
 * each tile's importance and the frames it has waited. Allocates nothing once grown to the most tiles seen.
 *
 * <pre>{@code
 * int n = schedule.schedule(Updates.IMPORTANCE, count, importance, waited, 64, 256, 2);
 * for (int i = 0; i < n; i++) draw(tiles[schedule.order(i)]);
 * }</pre>
 */
final class TileSchedule {

    private static final int INDEX_BITS = 24;
    private static final long INDEX_MASK = (1L << INDEX_BITS) - 1;

    private long[] keys = new long[64]; // per tile: its rank's bits above, its index below
    private int[] order = new int[64];
    private int forced;

    /**
     * Orders {@code count} changed tiles, those to draw first, and says how many to draw (the frame may have fewer
     * slots: those first in the order are drawn). {@code importance[i]}: tile {@code i}'s importance (not negative);
     * {@code waited[i]}: the frames it has waited (0: changed this frame). {@code draws}: the budget beyond the forced
     * tiles ({@link Updates#IMPORTANCE}) or the most ({@link Updates#CAPPED}); {@code urgent} and {@code lag}: the
     * importance and the wait from which a tile is forced ({@link Updates#IMPORTANCE}).
     */
    int schedule(Updates mode, int count, double[] importance, int[] waited, int draws, double urgent, int lag) {
        if (count > keys.length) {
            grow(count);
        }
        forced = 0;
        if (mode == Updates.IMPORTANCE) {
            // forced tiles first, in their own order; then the rest by importance, more for each frame waited
            int urgentCount = 0;
            for (int i = 0; i < count; i++) {
                double weight = rank(importance[i]);
                boolean urgentNow = weight >= urgent;
                if (urgentNow || waited[i] >= lag) {
                    keys[forced++] = key(weight * (1 + waited[i]), i);
                    urgentCount += urgentNow ? 1 : 0;
                }
            }
            int r = forced;
            for (int i = 0; i < count; i++) {
                double weight = rank(importance[i]);
                if (!(weight >= urgent || waited[i] >= lag)) {
                    keys[r++] = key(weight * (1 + waited[i]), i);
                }
            }
            sortDescending(keys, 0, forced);
            sortDescending(keys, forced, count);
            int rest = count - forced;
            // the budget, or so many that every tile not urgent is drawn in turn within the lag's frames: an even
            // share each frame instead of a burst of late tiles every few frames
            long share = ((long) count - urgentCount + lag) / (lag + 1L) - (forced - urgentCount);
            int budget = (int) Math.min(rest, Math.max(draws, share));
            unpack(count);
            return forced + budget;
        }
        for (int i = 0; i < count; i++) {
            keys[i] = key(rank(importance[i]), i);
        }
        sortDescending(keys, 0, count);
        unpack(count);
        return mode == Updates.ALL ? count : Math.min(Math.max(draws, 0), count);
    }

    /** The {@code i}th tile of the order (an index into the arrays scheduled). */
    int order(int i) {
        return order[i];
    }

    /** Of the tiles to draw, those drawn whatever the budget: urgent, or waited the lag (first in the order). */
    int forced() {
        return forced;
    }

    private void unpack(int count) {
        for (int i = 0; i < count; i++) {
            order[i] = (int) (keys[i] & INDEX_MASK);
        }
    }

    private static double rank(double importance) {
        return importance > 0 ? importance : 0; // NaN, negative: least
    }

    /** A rank and an index as one long ordered as the rank: positive doubles' bits order as they do. */
    private static long key(double rank, int index) {
        double finite = Math.min(rank, Double.MAX_VALUE);
        return (Double.doubleToRawLongBits(finite) & ~INDEX_MASK) | index;
    }

    /** Heap sort of {@code a[from, to)}, largest first: in place, allocating nothing. */
    static void sortDescending(long[] a, int from, int to) {
        int n = to - from;
        for (int i = n / 2 - 1; i >= 0; i--) {
            sift(a, from, i, n);
        }
        for (int end = n - 1; end > 0; end--) {
            long t = a[from];
            a[from] = a[from + end];
            a[from + end] = t;
            sift(a, from, 0, end);
        }
    }

    // a min-heap: the smallest goes last, so the array ends largest first
    private static void sift(long[] a, int from, int i, int n) {
        long v = a[from + i];
        while (true) {
            int c = 2 * i + 1;
            if (c >= n) {
                break;
            }
            if (c + 1 < n && a[from + c + 1] < a[from + c]) {
                c++;
            }
            if (a[from + c] >= v) {
                break;
            }
            a[from + i] = a[from + c];
            i = c;
        }
        a[from + i] = v;
    }

    @AllowAllocation("growing with the tiles")
    private void grow(int count) {
        int n = Math.max(count, 2 * keys.length);
        keys = new long[n];
        order = new int[n];
    }
}
