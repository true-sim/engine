package com.truesim.engine.input;

import com.truesim.engine.settings.Setting;
import com.truesim.engine.settings.SettingGroup;

/**
 * Input's own settings, registered by the {@link Input} plugin in {@link #GROUP}, read when they change. The
 * bindings are settings too: one text setting per action, {@code input.<group path>.<action>}, in a subgroup per action
 * group; a player after the first keeps their own under {@code player<n>.} when they rebind (application-api.md D58).
 */
public final class InputSettings {

    /** The options screen's input group. */
    public static final SettingGroup GROUP = SettingGroup.root("input", "settings.input", 40);

    /** How long a tap-and-hold binding waits for the hold, seconds (D70): released sooner, it is a tap. */
    public static final Setting<Float> HOLD_TIME = Setting.decimal("input.holdTime", 0.25f, 0.05f, 2f,
        "how long a tap-and-hold binding waits for the hold, seconds");

    /** Where an analog control driving a binary action turns it on (D77). */
    public static final Setting<Float> PRESS_THRESHOLD = Setting.decimal("input.pressThreshold", 0.6f, 0.05f, 1f,
        "where an analog control driving a binary action turns it on");

    /** Where it turns it off again: below the press threshold, so a value near one does not flicker (D77). */
    public static final Setting<Float> RELEASE_THRESHOLD = Setting.decimal("input.releaseThreshold", 0.4f, 0f, 1f,
        "where an analog control driving a binary action turns it off; at most the press threshold");

    private InputSettings() {
    }
}
