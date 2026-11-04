package com.truesim.engine.demo;

import com.truesim.engine.input.ActionGroup;
import com.truesim.engine.input.BinaryAction;
import com.truesim.engine.input.Binding;
import com.truesim.engine.platform.Key;

/**
 * The stream window's actions ({@link StreamWindow}): the camera's speed and pausing. Rebound in the settings like
 * any action ({@code input.stream.faster=...}).
 */
final class StreamActions {

    static final ActionGroup STREAM = ActionGroup.root("stream");
    /** A quarter faster: the + key of US (=) and German (]) layouts, the keypad's +. */
    static final BinaryAction FASTER = STREAM.binary("faster", Binding.key(Key.EQUALS), Binding.key(Key.RIGHT_BRACKET),
        Binding.key(Key.NUMPAD_PLUS));
    /** A quarter slower: the - key of US (-) and German (/) layouts, the keypad's -. */
    static final BinaryAction SLOWER = STREAM.binary("slower", Binding.key(Key.MINUS), Binding.key(Key.SLASH),
        Binding.key(Key.NUMPAD_MINUS));
    /** Pausing the flight (streaming and frames go on): Space. */
    static final BinaryAction PAUSE = STREAM.binary("pause", Binding.key(Key.SPACE));

    private StreamActions() {
    }
}
