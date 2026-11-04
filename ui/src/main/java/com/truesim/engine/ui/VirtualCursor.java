package com.truesim.engine.ui;

import com.truesim.engine.input.Input;
import com.truesim.engine.input.Player;
import com.truesim.engine.settings.EngineSettings;
import com.truesim.engine.settings.Setting;
import com.truesim.ui.core.input.Pointer;
import com.truesim.ui.core.input.PointerSource;
import com.truesim.ui.core.input.UiInput;
import java.util.List;
import java.util.Objects;
import java.util.function.DoubleSupplier;
import java.util.function.IntSupplier;

/**
 * A gamepad's virtual cursor (design 4.7), off unless {@link #ON} is set: a pointer of
 * {@link Pointer.Kind#VIRTUAL_CURSOR} that {@link UiActions#CURSOR} (the right stick) moves at up to {@link #SPEED}
 * pixels a second and {@link UiActions#CURSOR_CLICK} (south) presses; while it is on, that click gives no accept or
 * cancel ({@link UiCommands#virtualCursor}). It starts in the middle of the UI each time it is switched on and stays
 * within the UI. The first player's. Polling allocates nothing.
 */
public final class VirtualCursor implements PointerSource {

    /** The pointer id the cursor has in the UI's input. */
    public static final int ID = 5;

    /** Whether a gamepad drives a cursor. */
    public static final Setting<Boolean> ON = Setting.bool("ui.virtualCursor", false,
        "a gamepad's right stick moves a cursor over the UI and its south button clicks");
    /** How fast it moves at full tilt. */
    public static final Setting<Float> SPEED = Setting.decimal("ui.virtualCursorSpeed", 1000, 50, 10000,
        "pixels a second the gamepad's cursor moves at full tilt");
    /** Its settings. */
    public static final List<Setting<?>> ALL = List.of(ON, SPEED);

    private final Input input;
    private final EngineSettings settings;
    private final DoubleSupplier realTime;
    private final IntSupplier width;
    private final IntSupplier height;
    private IntSupplier modifiers;
    private boolean shown;
    private double last = Double.NaN;
    private float x;
    private float y;

    /**
     * The cursor of {@code input}'s first player, switched by {@code settings}, timed by {@code realTime} (seconds),
     * within a UI of {@code width} by {@code height} pixels.
     */
    public VirtualCursor(Input input, EngineSettings settings, DoubleSupplier realTime, IntSupplier width,
        IntSupplier height) {
        this.input = Objects.requireNonNull(input, "input");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.realTime = Objects.requireNonNull(realTime, "realTime");
        this.width = Objects.requireNonNull(width, "width");
        this.height = Objects.requireNonNull(height, "height");
    }

    /** The modifiers it carries ({@link UiKeyboard#pointerModifiers}); returns this. */
    public VirtualCursor modifiers(IntSupplier value) {
        modifiers = value;
        return this;
    }

    /** Whether it is on. */
    public boolean on() {
        return settings.get(ON);
    }

    /** Moves and writes the cursor into {@code ui}, or removes it when off; call at the frame's start. */
    @Override
    public void poll(UiInput ui) {
        double now = realTime.getAsDouble();
        if (!settings.get(ON)) {
            if (shown) {
                ui.removePointer(ID);
                shown = false;
            }
            last = now;
            return;
        }
        float w = Math.max(1, width.getAsInt());
        float h = Math.max(1, height.getAsInt());
        if (!shown) {
            shown = true;
            x = w * 0.5f;
            y = h * 0.5f;
        } else {
            float dt = Double.isNaN(last) ? 0 : (float) Math.min(0.1, Math.max(0, now - last)); // a long hitch: no jump
            Player player = input.player(0);
            float speed = settings.get(SPEED);
            x = Math.clamp(x + player.x(UiActions.CURSOR) * speed * dt, 0, w - 1);
            y = Math.clamp(y - player.y(UiActions.CURSOR) * speed * dt, 0, h - 1); // up on the stick is up
        }
        last = now;
        ui.pointer(ID, Pointer.Kind.VIRTUAL_CURSOR).moveTo(x, y)
            .buttons(input.player(0).down(UiActions.CURSOR_CLICK) ? Pointer.PRIMARY : 0)
            .modifiers(modifiers == null ? 0 : modifiers.getAsInt());
    }

    /** Where it is, across, in UI pixels. */
    public float x() {
        return x;
    }

    /** Where it is, down, in UI pixels. */
    public float y() {
        return y;
    }
}
