package com.truesim.engine.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.truesim.engine.app.AppConfig;
import com.truesim.engine.app.Application;
import com.truesim.engine.app.Engine;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The desktop as a plugin: the platform started with the engine, its events to the game's listeners. */
class DesktopTest {

    /** A value of {@code type} to call a listener method with. */
    private static Object any(Class<?> type) {
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class) {
            return 7;
        }
        if (type == long.class) {
            return 7L;
        }
        if (type == float.class) {
            return 7f;
        }
        if (type.isEnum()) {
            return type.getEnumConstants()[0];
        }
        return null; // strings, devices: nothing the desktop reads
    }

    @Test
    void everyEventReachesTheGamesListenersAndTheEngine() throws Exception {
        List<String> app = new ArrayList<>();
        Desktop desktop = new Desktop();
        assertThatIllegalStateException().isThrownBy(desktop::platform);
        try (Engine engine = Engine.create(new Application() {
            @Override
            public void configure(AppConfig config) {
                config.use(desktop);
            }

            @Override
            public void onFocusChanged(boolean focused) {
                app.add("focus " + focused);
            }

            @Override
            public void onVisibilityChanged(boolean visible) {
                app.add("visible " + visible);
            }

            @Override
            public QuitResponse onQuitRequested(QuitReason reason) {
                app.add("quit? " + reason);
                return QuitResponse.DEFER;
            }
        })) {
            assertThat(engine.get(Desktop.KEY)).isSameAs(desktop);
            assertThat(desktop.platform()).isNotNull();
            List<String> heard = new ArrayList<>();
            PlatformListener recording = (PlatformListener) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {PlatformListener.class}, (proxy, method, args) -> {
                    heard.add(method.getName());
                    return null;
                });
            PlatformListener other = new PlatformListener() { };
            desktop.listen(recording).listen(other).unlisten(other);
            int methods = 0;
            for (Method method : PlatformListener.class.getMethods()) {
                Object[] args = new Object[method.getParameterCount()];
                for (int i = 0; i < args.length; i++) {
                    args[i] = any(method.getParameterTypes()[i]);
                }
                method.invoke(desktop.events(), args);
                methods++;
            }
            assertThat(heard).as("every event forwarded").hasSize(methods);
            assertThat(app).containsExactly("quit? WINDOW_CLOSED");
            // Focus follows at the end of the frame's events: lost only when no window of the game has it.
            app.clear();
            desktop.events().windowFocusChanged(3, true);
            engine.step();
            desktop.events().windowFocusChanged(4, true);
            desktop.events().windowFocusChanged(3, false); // another window of the game has it
            engine.step();
            assertThat(app).as("focused from the start").isEmpty();
            desktop.events().windowFocusChanged(4, false);
            engine.step();
            desktop.events().windowFocusChanged(5, true);
            engine.step();
            assertThat(app).containsExactly("focus false", "focus true");
            // Visible while a window is not minimised (none at all: nothing hidden).
            desktop.events().windowMinimized(4, true);
            engine.step();
            assertThat(engine.visible()).isTrue();
            Window window = desktop.platform().createWindow(WindowOptions.of("game", 64, 64).withHidden(true)
                .withVulkan(false));
            desktop.events().windowShown(window.id(), true);
            engine.step();
            assertThat(engine.visible()).isTrue();
        }
        assertThatIllegalStateException().isThrownBy(desktop::platform);
    }
}
