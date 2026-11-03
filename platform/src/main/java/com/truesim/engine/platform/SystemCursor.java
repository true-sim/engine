package com.truesim.engine.platform;

import static org.lwjgl.sdl.SDLMouse.SDL_SYSTEM_CURSOR_CROSSHAIR;
import static org.lwjgl.sdl.SDLMouse.SDL_SYSTEM_CURSOR_DEFAULT;
import static org.lwjgl.sdl.SDLMouse.SDL_SYSTEM_CURSOR_EW_RESIZE;
import static org.lwjgl.sdl.SDLMouse.SDL_SYSTEM_CURSOR_MOVE;
import static org.lwjgl.sdl.SDLMouse.SDL_SYSTEM_CURSOR_NESW_RESIZE;
import static org.lwjgl.sdl.SDLMouse.SDL_SYSTEM_CURSOR_NOT_ALLOWED;
import static org.lwjgl.sdl.SDLMouse.SDL_SYSTEM_CURSOR_NS_RESIZE;
import static org.lwjgl.sdl.SDLMouse.SDL_SYSTEM_CURSOR_NWSE_RESIZE;
import static org.lwjgl.sdl.SDLMouse.SDL_SYSTEM_CURSOR_POINTER;
import static org.lwjgl.sdl.SDLMouse.SDL_SYSTEM_CURSOR_TEXT;
import static org.lwjgl.sdl.SDLMouse.SDL_SYSTEM_CURSOR_WAIT;

/** The operating system's mouse cursors ({@link Platform#setCursor}), and none. */
public enum SystemCursor {
    /** The arrow. */
    DEFAULT(SDL_SYSTEM_CURSOR_DEFAULT),
    /** A hand: something to click. */
    POINTER(SDL_SYSTEM_CURSOR_POINTER),
    /** An I-beam: text. */
    TEXT(SDL_SYSTEM_CURSOR_TEXT),
    MOVE(SDL_SYSTEM_CURSOR_MOVE),
    NOT_ALLOWED(SDL_SYSTEM_CURSOR_NOT_ALLOWED),
    RESIZE_EW(SDL_SYSTEM_CURSOR_EW_RESIZE),
    RESIZE_NS(SDL_SYSTEM_CURSOR_NS_RESIZE),
    RESIZE_NESW(SDL_SYSTEM_CURSOR_NESW_RESIZE),
    RESIZE_NWSE(SDL_SYSTEM_CURSOR_NWSE_RESIZE),
    CROSSHAIR(SDL_SYSTEM_CURSOR_CROSSHAIR),
    WAIT(SDL_SYSTEM_CURSOR_WAIT),
    /** No cursor drawn. */
    HIDDEN(-1);

    final int sdl;

    SystemCursor(int sdl) {
        this.sdl = sdl;
    }
}
