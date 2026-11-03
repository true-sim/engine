package com.truesim.engine.input;

/** Text typed while a context taking text is on top (application-api.md D61): a text field. */
public interface TextListener {

    /** {@code text} was typed (committed, after an input method). */
    void text(String text);

    /** Text being composed by an input method, not yet typed: {@code text}, the cursor at {@code start}. */
    default void editing(String text, int start, int length) {
    }
}
