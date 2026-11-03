package com.truesim.engine.input;

import com.truesim.annotations.AllowAllocation;
import java.util.Arrays;

/**
 * A binding made ready for one player: its controls resolved to the kinds of device present, room for its filters'
 * state (D85: per player and binding), and what it was this frame. Made when bindings change, used every frame without
 * allocating.
 */
final class Bound {

    final Binding binding;
    final int action;          // the action's local index
    final boolean binary;      // drives a binary action
    int dims;                  // the components it gives (0: a pose; an action source's, set when made ready)
    final int inputCount;
    final int[] inputClass;    // per input: the kind of device's index in the input
    final int[] inputIndex;
    final int modifierCount;
    final int[] modifierClass;
    final int[] modifierIndex;
    int source = -1;           // ACTION: the source action's local index; -1 for none (or cut for a cycle)
    final FilterType[] filters;
    final float[][] params;
    final int[] stateAt;
    final float[] state;       // every filter's, in a row
    final float holdSeconds;   // 0: the setting

    // This frame.
    long stamp = -1;           // the frame it was read in: -1 never
    final float[] raw = new float[6];
    final float[] value = new float[3];
    boolean modifiersHeld;
    boolean satisfied;         // held and pushed: a chord takes its controls from less specific bindings (D69)
    // Over frames (reset with the filters, D86).
    boolean latched;           // the hysteresis' state (D77)
    boolean under;             // pressed before timing
    double downSince;
    boolean out;               // pressed after timing, before toggling
    boolean toggled;           // the toggle's state (D79): not reset by filters
    boolean result;            // what it gives a binary action this frame

    @AllowAllocation("bindings change")
    Bound(Binding binding, int action, boolean binary, int[] inputClass, int[] inputIndex, int[] modifierClass,
        int[] modifierIndex) {
        this.binding = binding;
        this.action = action;
        this.binary = binary;
        this.dims = binding.dimensions();
        this.inputClass = inputClass;
        this.inputIndex = inputIndex;
        this.inputCount = inputIndex.length;
        this.modifierClass = modifierClass;
        this.modifierIndex = modifierIndex;
        this.modifierCount = modifierIndex.length;
        FilterType.Entry[] entries = binding.filters();
        filters = new FilterType[entries.length];
        params = new float[entries.length][];
        stateAt = new int[entries.length];
        int size = 0;
        for (int i = 0; i < entries.length; i++) {
            filters[i] = entries[i].type();
            params[i] = entries[i].params().clone();
            stateAt[i] = size;
            size += filters[i].stateSize();
        }
        state = new float[size];
        holdSeconds = binding.holdSeconds();
    }

    /** Whether it reads controls (not another action, not inert). */
    boolean readsControls() {
        return binding.shape() != Binding.Shape.ACTION && binding.shape() != Binding.Shape.INERT;
    }

    /** Back to its state before any frame: the filters (D86), the hysteresis, the tap and hold timers. */
    void reset() {
        Arrays.fill(state, 0);
        latched = false;
        under = false;
        out = false;
        downSince = 0;
        result = toggled;
    }

    /** Its value from its raw inputs, by its shape: {@code value}'s first {@code dims} components. */
    void adapt() {
        switch (binding.shape()) {
            case SINGLE -> value[0] = raw[0];
            case AXIS -> value[0] = raw[1] - raw[0];
            case BUTTONS_2D -> {
                value[0] = raw[1] - raw[0];
                value[1] = raw[3] - raw[2];
            }
            case AXES_2D -> {
                value[0] = raw[0];
                value[1] = raw[1];
            }
            case BUTTONS_3D -> {
                value[0] = raw[1] - raw[0];
                value[1] = raw[3] - raw[2];
                value[2] = raw[5] - raw[4];
            }
            case AXES_3D -> {
                value[0] = raw[0];
                value[1] = raw[1];
                value[2] = raw[2];
            }
            default -> {
                // ACTION: written from the source; INERT: nothing
            }
        }
    }

    /** The length of its value's first {@code n} components. */
    float magnitude(int n) {
        return Filters.length(value, n);
    }

    /** Whether it has the same controls as {@code other}: two bindings on one chord. */
    boolean sameControls(Bound other, DeviceClass[] classes) {
        if (!readsControls() || !other.readsControls()
            || other.inputCount + other.modifierCount != inputCount + modifierCount) {
            return false;
        }
        for (int i = 0; i < other.inputCount; i++) {
            if (!has(other.inputClass[i], other.inputIndex[i], classes)) {
                return false;
            }
        }
        for (int i = 0; i < other.modifierCount; i++) {
            if (!has(other.modifierClass[i], other.modifierIndex[i], classes)) {
                return false;
            }
        }
        return true;
    }

    private boolean has(int type, int index, DeviceClass[] classes) {
        for (int i = 0; i < inputCount; i++) {
            if (inputClass[i] == type && classes[type].covers(inputIndex[i], index)) {
                return true;
            }
        }
        for (int i = 0; i < modifierCount; i++) {
            if (modifierClass[i] == type && classes[type].covers(modifierIndex[i], index)) {
                return true;
            }
        }
        return false;
    }
}
