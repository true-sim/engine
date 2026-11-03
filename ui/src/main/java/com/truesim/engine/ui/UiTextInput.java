package com.truesim.engine.ui;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.input.TextListener;
import com.truesim.engine.platform.Window;
import com.truesim.ui.core.input.TextInputHost;
import com.truesim.ui.core.input.UiInput;

/**
 * The UI's text input through the engine (ui-core's {@link TextInputHost}): when a text field gains focus the UI calls
 * {@link #start}, which pushes the {@code ui.text} context through {@link UiCommands#startText} (keys type and fire no
 * game actions, the text commands flow) and starts SDL's text input on the window with the field's kind (a password
 * starts the hidden password type: no IME composition or prediction; a number field a numeric keyboard) and the IME
 * area at the caret ({@code SDL_SetTextInputArea}, moved by {@link #area} as the caret moves). Typed text and the
 * IME's composition arrive through the input system's {@link TextListener} and are handed to the UI at its frame start
 * by {@link #poll}, into {@link UiInput#text} and {@link UiInput#composition}.
 *
 * <pre>{@code
 * UiTextInput text = new UiTextInput(commands, window);
 * ui.interaction().textInputHost(text);
 * ui.interaction().clipboard(new SdlClipboard(platform));
 * // each frame: input.beginFrame(); commands.poll(input); mouse.poll(input); text.poll(input); ui.update(...)
 * }</pre>
 */
public final class UiTextInput implements TextInputHost, TextListener {

    /**
     * Finds the window a text field is in, for a UI shown in several windows (docking's OS windows, {@link UiOsWindows}).
     */
    @FunctionalInterface
    public interface Locator {
        /**
         * The window showing UI point {@code uiX, uiY}, with its region's origin (UI pixels) and window coordinates per
         * UI pixel in {@code toWindow} (x, y, scale); null: the UI's own window.
         */
        Window locate(float uiX, float uiY, float[] toWindow);
    }

    private final UiCommands commands;
    private final Window home;
    private Window window; // the window text input runs on: the home one, or the one holding the field
    private Locator locator;
    private final float[] located = new float[3];
    private float originX;
    private float originY;
    private float windowScale;
    private float scale = 1;
    private final StringBuilder typed = new StringBuilder();
    private final StringBuilder composition = new StringBuilder();
    private int compositionCursor;
    private boolean compositionChanged;
    private boolean active;
    private int kind = -1;

    /** Text input through {@code commands}' text context, started on {@code window} (null: none, for tests). */
    public UiTextInput(UiCommands commands, Window window) {
        this.commands = commands;
        this.home = window;
        this.window = window;
    }

    /** Starts text input on the window holding the field, as {@code value} finds it (null: always its own). */
    public UiTextInput locator(Locator value) {
        locator = value;
        return this;
    }

    /** The window text input runs on now (its own, or the one holding the focused field), or null. */
    public Window window() {
        return window;
    }

    /** Window coordinates per UI pixel (a high-density display's pixels per window unit inverted). */
    public UiTextInput scale(float windowUnitsPerUiPixel) {
        scale = windowUnitsPerUiPixel;
        return this;
    }

    @Override
    public void start(float x, float y, float width, float height) {
        start(x, y, width, height, TEXT);
    }

    @Override
    @AllowAllocation("a text field gains focus")
    public void start(float x, float y, float width, float height, int textKind) {
        commands.textContext().textKind(windowKind(textKind));
        commands.startText(this);
        Window to = locator == null ? null : locator.locate(x, y, located);
        if (to == null) {
            to = home;
            originX = 0;
            originY = 0;
            windowScale = 0;
        } else {
            originX = located[0];
            originY = located[1];
            windowScale = located[2];
        }
        if (to != window) {
            if (window != null && active && !window.isClosed()) {
                window.stopTextInput(); // the field moved to another window: its input goes there
            }
            window = to;
            active = false;
        }
        if (window != null && (!active || kind != textKind || !window.textInputActive())) {
            window.startTextInput(windowKind(textKind));
        }
        active = true;
        kind = textKind;
        area(x, y, width, height);
    }

    private static int windowKind(int textKind) {
        return switch (textKind) {
            case PASSWORD -> Window.TEXT_INPUT_PASSWORD;
            case NUMBER -> Window.TEXT_INPUT_NUMBER;
            case MULTILINE -> Window.TEXT_INPUT_MULTILINE;
            default -> Window.TEXT_INPUT_TEXT;
        };
    }

    @Override
    public void area(float x, float y, float width, float height) {
        if (window != null) {
            float s = windowScale > 0 ? windowScale : scale;
            window.textInputArea(Math.round((x - originX) * s), Math.round((y - originY) * s),
                Math.max(1, Math.round(width * s)), Math.max(1, Math.round(height * s)), 0);
        }
    }

    @Override
    @AllowAllocation("a text field loses focus")
    public void stop() {
        active = false;
        commands.stopText();
        if (window != null && !window.isClosed()) {
            window.stopTextInput();
        }
        if (!composition.isEmpty()) { // the field went away while composing: the composition ends
            composition.setLength(0);
            compositionChanged = true;
        }
    }

    /** Whether a text field has the keyboard. */
    public boolean active() {
        return active;
    }

    /** The UI's text input kind last started ({@link TextInputHost#TEXT} and the others), -1 before any. */
    public int kind() {
        return kind;
    }

    @Override
    public void text(String text) {
        typed.append(text);
    }

    @Override
    public void editing(String text, int start, int length) {
        composition.setLength(0);
        composition.append(text);
        compositionCursor = start < 0 ? text.length() : start;
        compositionChanged = true;
    }

    /** Hands this frame's typed text and the composition, if it changed, to the UI's input; call at frame start. */
    public void poll(UiInput input) {
        if (!typed.isEmpty()) {
            input.text(typed);
            typed.setLength(0);
        }
        if (compositionChanged) {
            compositionChanged = false;
            input.composition(composition, compositionCursor);
        }
    }
}
