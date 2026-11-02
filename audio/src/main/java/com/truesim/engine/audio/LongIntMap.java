package com.truesim.engine.audio;

import com.truesim.annotations.AllowAllocation;
import java.util.Arrays;

/** A map from longs to ints by open addressing with linear probing and backward-shift removal; allocation-free. */
final class LongIntMap {

    private static final long EMPTY = Long.MIN_VALUE;

    private long[] keys;
    private int[] values;
    private int size;

    @AllowAllocation("made once")
    LongIntMap(int capacity) {
        int n = Integer.highestOneBit(Math.max(4, capacity) * 2 - 1);
        keys = new long[n];
        values = new int[n];
        Arrays.fill(keys, EMPTY);
    }

    int size() {
        return size;
    }

    private int index(long key) {
        long h = key * 0x9E3779B97F4A7C15L;
        return (int) (h ^ (h >>> 32)) & (keys.length - 1);
    }

    /** The value of {@code key}, or -1. */
    int get(long key) {
        int mask = keys.length - 1;
        for (int i = index(key); ; i = (i + 1) & mask) {
            if (keys[i] == key) {
                return values[i];
            }
            if (keys[i] == EMPTY) {
                return -1;
            }
        }
    }

    void put(long key, int value) {
        if (2 * (size + 1) > keys.length) {
            grow();
        }
        int mask = keys.length - 1;
        for (int i = index(key); ; i = (i + 1) & mask) {
            if (keys[i] == EMPTY) {
                keys[i] = key;
                values[i] = value;
                size++;
                return;
            }
            if (keys[i] == key) {
                values[i] = value;
                return;
            }
        }
    }

    void remove(long key) {
        int mask = keys.length - 1;
        int i = index(key);
        while (keys[i] != key) {
            if (keys[i] == EMPTY) {
                return;
            }
            i = (i + 1) & mask;
        }
        keys[i] = EMPTY;
        size--;
        // shift back the entries after it that probed past it
        for (int j = (i + 1) & mask; keys[j] != EMPTY; j = (j + 1) & mask) {
            int home = index(keys[j]);
            if (((j - home) & mask) >= ((j - i) & mask)) {
                keys[i] = keys[j];
                values[i] = values[j];
                keys[j] = EMPTY;
                i = j;
            }
        }
    }

    @AllowAllocation("growing")
    private void grow() {
        long[] oldKeys = keys;
        int[] oldValues = values;
        keys = new long[2 * oldKeys.length];
        values = new int[2 * oldKeys.length];
        Arrays.fill(keys, EMPTY);
        size = 0;
        for (int i = 0; i < oldKeys.length; i++) {
            if (oldKeys[i] != EMPTY) {
                put(oldKeys[i], oldValues[i]);
            }
        }
    }
}
