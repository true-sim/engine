package com.truesim.engine.audio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.lwjgl.sdl.SDLInit.SDL_INIT_AUDIO;
import static org.lwjgl.sdl.SDLInit.SDL_QuitSubSystem;

import com.truesim.common.jobs.TaskScheduler;
import com.truesim.common.state.ChangeClock;
import com.truesim.engine.app.AppConfig;
import com.truesim.engine.app.Application;
import com.truesim.engine.app.Engine;
import com.truesim.engine.settings.SettingRegistry;
import com.truesim.scene.assets.AssetCache;
import com.truesim.scene.audio.Sounds;
import com.truesim.scene.core.SceneGraph;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The audio plugin in an engine: SDL's dummy driver, settings, stores synced each frame, the background rule. */
class AudioPluginTest {

    @AfterEach
    void quitAudio() {
        SDL_QuitSubSystem(SDL_INIT_AUDIO);
    }

    private static Engine engine(Audio audio, String driver) {
        return Engine.create(new Application() {
            @Override
            public void configure(AppConfig config) {
                config.use(audio).defaults(Audio.DRIVER, driver).defaults(Audio.LOW_MARK, 2)
                    .defaults(Audio.BACKGROUND, Audio.Background.MUTE);
            }
        });
    }

    @Test
    void thePluginPlaysTheStoresItHandsOut() throws InterruptedException {
        Audio audio = new Audio();
        assertThat(audio.optional()).isTrue();
        assertThatIllegalStateException().isThrownBy(audio::renderer);
        try (Engine engine = engine(audio, "dummy"); TaskScheduler tasks = new TaskScheduler();
             AssetCache cache = new AssetCache(tasks)) {
            assertThat(engine.get(Audio.KEY)).isSameAs(audio);
            assertThat(audio.output()).isInstanceOf(SdlOutput.class);
            assertThatIllegalStateException().isThrownBy(audio::sync);
            SettingRegistry registry = engine.registry();
            assertThat(registry.find("audio.volume.music")).isNotNull();
            audio.assets(cache);
            assertThatIllegalStateException().isThrownBy(() -> audio.assets(cache));
            Sounds ui = audio.ui();
            assertThat(audio.ui()).isSameAs(ui);
            Sounds world = audio.sounds(new SceneGraph(new ChangeClock()));
            ui.play(AudioSyncTest.clip(0.5f, 48_000));
            world.play(AudioSyncTest.clip(0.5f, 48_000));
            for (int i = 0; i < 50 && audio.sync().voices() < 2; i++) {
                tasks.drainCompleted();
                engine.step();
                Thread.sleep(5);
            }
            assertThat(audio.sync().voices()).isEqualTo(2);
            assertThat(world.now()).isPositive();
            engine.settings().set(Audio.MUSIC_VOLUME, 0.5f);
            engine.focusChanged(false);
            engine.step(); // muted in the background
            engine.focusChanged(true);
            engine.step();
            assertThat(audio.renderer().clock().renderedFrame()).isPositive();
        }
    }

    @Test
    void withoutADeviceItPlaysSilently() {
        Audio audio = new Audio();
        try (Engine engine = engine(audio, "no-such-driver")) {
            assertThat(audio.output()).isInstanceOf(NullOutput.class);
            assertThat(((NullOutput) audio.output()).rendering()).isTrue();
            engine.step();
        }
        assertThat(Audio.collectorLowMark()).isBetween(2, 4);
    }
}
