package com.truesim.engine.ui;

import com.truesim.engine.input.Input;
import com.truesim.engine.input.Player;
import com.truesim.engine.platform.Key;
import com.truesim.engine.platform.PlatformListener;
import com.truesim.ui.core.input.Pointer;
import com.truesim.ui.core.input.UiInput;

/**
 * The keyboard as the UI reads it beside the commands: mnemonics and the modifiers pointers carry. Listen to the desktop
 * with it ({@code desktop.listen(keyboard)}); {@link #poll} writes the frame's part at its start.
 *
 * <ul>
 * <li><b>Mnemonics</b>: a letter or digit key pressed with Alt (not with Ctrl or Meta, so AltGr's characters type, and
 * not repeated) is the frame's {@link UiInput#mnemonic(int) mnemonic}: the character the layout gives the key, lower
 * case; the text it may also type is dropped ({@link UiTextInput}). {@link UiInput#mnemonicsShown(boolean)} is true
 * while Alt is held alone. Not on macOS, where Option types characters and menus have no mnemonics.</li>
 * <li><b>Modifiers</b> ({@link #pointerModifiers()}): Shift, Ctrl, Alt and Meta held, the platform's shortcut
 * modifier, and the actions {@code ui.pointer.dragCopy}, {@code ui.pointer.dragLink} and {@code ui.gizmo.snap} held
 * by the first player, as {@link Pointer}'s modifier bits for the mouse and the other pointers.</li>
 * </ul>
 *
 * <p>Keys held are forgotten when the window loses the focus (an Alt+Tab leaves no Alt held). Use it on the platform's
 * thread. Polling allocates nothing.
 */
public final class UiKeyboard implements PlatformListener {

    private static final int SHIFT = 1;
    private static final int CTRL = 2;
    private static final int ALT = 4;
    private static final int META = 8;

    private final Input input; // whose actions add drag and snap modifiers, or null
    private final boolean mnemonics;
    private int held;          // SHIFT, CTRL, ALT, META: left and right as one
    private int heldLeftRight; // the eight modifier keys, a bit each
    private int mnemonic;      // the code point of this frame's mnemonic, or 0
    private Key mnemonicKey;   // the key that made it
    private Key frameMnemonicKey; // the key of the mnemonic last polled
    private Key lastDown;      // the last key pressed (the text right after it is its)

    /** The keyboard; {@code input} (may be null) adds the drag and snap actions to the modifiers. */
    public UiKeyboard(Input input) {
        this(input, !UiActions.mac());
    }

    /** As {@link #UiKeyboard(Input)}, with mnemonics on or off (off on macOS). */
    UiKeyboard(Input input, boolean mnemonics) {
        this.input = input;
        this.mnemonics = mnemonics;
    }

    @Override
    public void key(long timestamp, int window, int keyboard, Key key, int character, int modifiers, boolean down,
        boolean repeat) {
        int bit = modifierBit(key);
        if (bit != 0) {
            heldLeftRight = down ? heldLeftRight | bit : heldLeftRight & ~bit;
            held = (heldLeftRight & 3) != 0 ? SHIFT : 0;
            held |= (heldLeftRight & 12) != 0 ? CTRL : 0;
            held |= (heldLeftRight & 48) != 0 ? ALT : 0;
            held |= (heldLeftRight & 192) != 0 ? META : 0;
            return;
        }
        if (!down) {
            return;
        }
        lastDown = key;
        if (mnemonics && !repeat && held == ALT && character > 0 && Character.isLetterOrDigit(character)) {
            mnemonic = character;
            mnemonicKey = key;
        }
    }

    private static int modifierBit(Key key) {
        return switch (key) {
            case LEFT_SHIFT -> 1;
            case RIGHT_SHIFT -> 2;
            case LEFT_CONTROL -> 4;
            case RIGHT_CONTROL -> 8;
            case LEFT_ALT -> 16;
            case RIGHT_ALT -> 32;
            case LEFT_META -> 64;
            case RIGHT_META -> 128;
            default -> 0;
        };
    }

    @Override
    public void windowFocusChanged(int window, boolean focused) {
        if (!focused) {
            held = 0;
            heldLeftRight = 0;
        }
    }

    /** The last key pressed: the key the text arriving right after it was typed with. */
    Key lastDown() {
        return lastDown;
    }

    /** The key that made the mnemonic of the frame last polled, or null: its text is not typed. */
    Key mnemonicKey() {
        return frameMnemonicKey;
    }

    /** Writes this frame's mnemonic and whether mnemonics show into {@code ui}; call at the frame's start. */
    public void poll(UiInput ui) {
        if (mnemonic != 0) {
            ui.mnemonic(mnemonic);
        }
        frameMnemonicKey = mnemonicKey;
        mnemonic = 0;
        mnemonicKey = null;
        ui.mnemonicsShown(mnemonics && held == ALT);
    }

    /** The modifiers pointers carry now ({@link Pointer#SHIFT} and the others). */
    public int pointerModifiers() {
        int bits = (held & SHIFT) != 0 ? Pointer.SHIFT : 0;
        bits |= (held & CTRL) != 0 ? Pointer.CTRL : 0;
        bits |= (held & ALT) != 0 ? Pointer.ALT : 0;
        bits |= (held & META) != 0 ? Pointer.META : 0;
        if ((held & (UiActions.mac() ? META : CTRL)) != 0) {
            bits |= Pointer.SHORTCUT;
        }
        if (input != null) {
            Player player = input.player(0);
            bits |= player.down(UiActions.DRAG_COPY) ? Pointer.DRAG_COPY : 0;
            bits |= player.down(UiActions.DRAG_LINK) ? Pointer.DRAG_LINK : 0;
            bits |= player.down(UiActions.SNAP) ? Pointer.GIZMO_SNAP : 0;
        }
        return bits;
    }
}
