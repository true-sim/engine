package com.truesim.engine.xr;

import com.truesim.engine.app.Engine;

/**
 * Tells the engine when the headset's session state changes (D127): idle or taken off is hidden, a system menu over
 * the app is focus lost; audio's background rule follows them. Only changes are reported, so a desktop window's own
 * focus and visibility still count between them.
 */
final class StateReport {

    private boolean reported;
    private boolean visible = true;
    private boolean focused = true;

    /** Whether it reported once: from then on it follows the headset even when its session stopped. */
    boolean reported() {
        return reported;
    }

    void report(Engine engine, boolean nowVisible, boolean nowFocused) {
        if (!reported || nowVisible != visible) {
            engine.visibilityChanged(nowVisible);
        }
        if (!reported || nowFocused != focused) {
            engine.focusChanged(nowFocused);
        }
        reported = true;
        visible = nowVisible;
        focused = nowFocused;
    }
}
