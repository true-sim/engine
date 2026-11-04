package com.truesim.engine.ui;

import com.truesim.engine.input.ActionGroup;
import com.truesim.engine.input.BinaryAction;
import com.truesim.engine.input.Binding;
import com.truesim.engine.input.Controls;
import com.truesim.engine.platform.Key;

/**
 * The profiler's input actions (group {@code profiler}), rebindable like every action (their bindings are settings:
 * {@code input.profiler.overlay=key:F3}): the frame graph overlay F3, the detailed view Shift+F3, freezing the
 * recording F4, one frame while frozen Shift+F4, exporting it Ctrl+F4 (Cmd on macOS).
 */
public final class ProfilerActions {

    /** The profiler's actions. */
    public static final ActionGroup PROFILER = ActionGroup.root("profiler");
    /** Shows or hides the frame graph overlay ({@code profiler.overlay}). */
    public static final BinaryAction OVERLAY = PROFILER.binary("overlay", Binding.key(Key.F3));
    /** Shows or hides the detailed profiler ({@code profiler.view}). */
    public static final BinaryAction VIEW = PROFILER.binary("view",
        Binding.chord(Controls.SHIFT, Controls.key(Key.F3)));
    /** Freezes the recording or lets it run again. */
    public static final BinaryAction FREEZE = PROFILER.binary("freeze", Binding.key(Key.F4));
    /** Takes one more frame into a frozen recording. */
    public static final BinaryAction STEP = PROFILER.binary("step",
        Binding.chord(Controls.SHIFT, Controls.key(Key.F4)));
    /** Exports the recording (own format and Chrome trace JSON). */
    public static final BinaryAction EXPORT = PROFILER.binary("export",
        Binding.chord(UiActions.SHORTCUT, Controls.key(Key.F4)));

    private ProfilerActions() {
    }
}
