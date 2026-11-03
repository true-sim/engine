package com.truesim.engine.input;

import static com.truesim.engine.input.Rig.GAME;
import static com.truesim.engine.input.Rig.JUMP;
import static com.truesim.engine.input.Rig.UI;
import static org.assertj.core.api.Assertions.assertThat;
import static org.lwjgl.sdl.SDLEvents.SDL_EVENT_KEY_DOWN;
import static org.lwjgl.sdl.SDLEvents.SDL_PushEvent;

import com.truesim.engine.app.AppConfig;
import com.truesim.engine.app.Application;
import com.truesim.engine.app.Engine;
import com.truesim.engine.platform.Desktop;
import com.truesim.engine.platform.Window;
import com.truesim.engine.platform.WindowOptions;
import org.junit.jupiter.api.Test;
import org.lwjgl.sdl.SDL_CommonEvent;
import org.lwjgl.sdl.SDL_Event;
import org.lwjgl.sdl.SDL_KeyboardEvent;

/** Input on the real desktop: started after it, its events sampled the same frame, the mouse and text through it. */
class DesktopInputTest {

    @Test
    void theDesktopsEventsAreSampledInTheFrameTheyArriveIn() {
        Input input = new Input().add(GAME, UI);
        Desktop desktop = new Desktop();
        try (Engine engine = Engine.create(new Application() {
            @Override
            public void configure(AppConfig app) {
                app.use(input, desktop).defaults(Engine.FRAME_RATE, 10_000); // installed first, started after
            }
        }); SDL_Event event = SDL_Event.calloc()) {
            Window window = desktop.platform().createWindow(WindowOptions.of("input", 64, 64));
            engine.step();
            SDL_CommonEvent.ntype(event.address(), SDL_EVENT_KEY_DOWN);
            SDL_KeyboardEvent.nscancode(event.address(), 44); // space
            SDL_KeyboardEvent.ndown(event.address(), true);
            assertThat(SDL_PushEvent(event)).isTrue();
            engine.step();
            assertThat(input.player(0).pressed(JUMP)).isTrue(); // the desktop read it, input sampled it: one frame
            input.mouse(MouseMode.CAPTURED);
            input.push(input.context("chat", 1).groups(UI).takesText(text -> { }));
            engine.step();
            assertThat(input.mouseCaptured()).isTrue();
            assertThat(input.player(0).rumble(1, 1, 10)).isFalse(); // no pads
            input.pop(input.textContext());
            input.mouse(MouseMode.RELEASED);
            engine.step();
            assertThat(window.isRelativeMouse()).isFalse();
        }
    }
}
