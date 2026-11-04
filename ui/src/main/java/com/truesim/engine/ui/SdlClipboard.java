package com.truesim.engine.ui;

import com.truesim.engine.platform.Platform;
import com.truesim.ui.core.input.Clipboard;

/**
 * ui-core's {@link Clipboard} on the system clipboard through SDL ({@link Platform#clipboardText}): text fields copy
 * to it and paste from it. Read and written when the user copies or pastes, never per frame.
 */
public final class SdlClipboard implements Clipboard {

    private final Platform platform;

    /** The system clipboard of {@code platform}. */
    public SdlClipboard(Platform platform) {
        this.platform = platform;
    }

    @Override
    public String text() {
        return platform.clipboardText();
    }

    @Override
    public void text(String text) {
        platform.clipboardText(text == null ? "" : text);
    }
}
