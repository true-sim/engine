package com.truesim.engine.audio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.within;
import static org.lwjgl.sdl.SDLInit.SDL_INIT_AUDIO;
import static org.lwjgl.sdl.SDLInit.SDL_QuitSubSystem;

import com.truesim.common.jobs.TaskScheduler;
import com.truesim.common.state.ChangeClock;
import com.truesim.engine.app.AppConfig;
import com.truesim.engine.app.Application;
import com.truesim.engine.app.Engine;
import com.truesim.engine.settings.EngineSettings;
import com.truesim.engine.settings.SettingRegistry;
import com.truesim.engine.settings.TechniqueConfig;
import com.truesim.engine.settings.TechniqueKind;
import com.truesim.scene.assets.AssetCache;
import com.truesim.scene.audio.Sounds;
import com.truesim.scene.core.SceneGraph;
import java.util.Map;
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
            for (int i = 0; i < 200 && audio.sync().voices() < 2; i++) {
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
    void headphonesTurnTheHrtfOnAndOtherOutputsOff() throws InterruptedException {
        Audio audio = new Audio();
        try (Engine engine = engine(audio, "dummy")) {
            assertThat(engine.registry().find("audio.spatializer.hrtf.set")).isNotNull();
            assertThat(audio.spatializer()).isNull();
            engine.settings().set(Audio.HRTF_SET, "no/such/file.hrtf"); // falls back to the built-in set
            engine.settings().set(Audio.OUTPUT, Audio.Output.HEADPHONES);
            for (int i = 0; i < 400 && audio.spatializer() == null; i++) {
                engine.step();
                Thread.sleep(5);
            }
            assertThat(audio.spatializer()).isInstanceOf(HrtfSpatializer.class);
            assertThat(((HrtfSpatializer) audio.spatializer()).hrtf().credit()).contains("SADIE II");
            engine.settings().set(Audio.MONO, true); // mono downmix: no HRTF
            engine.step();
            assertThat(audio.spatializer()).isNull();
            engine.settings().set(Audio.MONO, false);
            engine.settings().set(Audio.OUTPUT, Audio.Output.STEREO_SPEAKERS);
            engine.step();
            assertThat(audio.spatializer()).isNull();
        }
    }

    /** A game's own spatializer: no filters, both ears hear the voice as it is. */
    record Flat(int capacity) implements Spatializer {

        @Override
        public void reset(int channel) {
        }

        @Override
        public void place(int channel, float x, float y, float z) {
        }

        @Override
        public int tailBlocks() {
            return 0;
        }

        @Override
        public void begin() {
        }

        @Override
        public void process(int channel, float[] mono, float[] out, int frames) {
            for (int f = 0; f < frames; f++) {
                out[2 * f] += mono[f];
                out[2 * f + 1] += mono[f];
            }
        }
    }

    /** {@link Flat}'s tunables. */
    record Voices(@TechniqueConfig.Tunable(value = "voices", min = 1, max = 64) int voices)
        implements TechniqueConfig {
    }

    static final TechniqueKind<Voices, AudioFormat, Spatializer> FLAT = TechniqueKind.of(Audio.SPATIALIZER,
        "test-flat", new Voices(8), "both ears alike", (format, config) -> new Flat(config.get().voices()));

    @Test
    void aGamesOwnSpatializerIsChosenThroughTheSettings() throws InterruptedException {
        Audio audio = new Audio();
        try (Engine engine = engine(audio, "dummy")) {
            engine.settings().techniques().register(Audio.SPATIALIZER, FLAT);
            engine.settings().set(Audio.SPATIALIZER.setting(), "test-flat").set(FLAT.setting("voices"), 12)
                .set(Audio.OUTPUT, Audio.Output.HEADPHONES);
            for (int i = 0; i < 400 && audio.spatializer() == null; i++) {
                engine.step();
                Thread.sleep(5);
            }
            assertThat(audio.spatializer()).isEqualTo(new Flat(12));
            Audio.SPATIALIZER.choose(engine.settings(), null); // off: panned
            engine.step();
            assertThat(audio.spatializer()).isNull();
        }
    }

    @Test
    void theHrtfsTunablesReadTheNamesTheyHadBefore() {
        EngineSettings settings = new EngineSettings().load(Map.of("audio.hrtf.voices", "8", "audio.hrtf.set",
            "a.hrtf"));
        assertThat(Audio.HRTF.config(settings).voices()).isEqualTo(8);
        assertThat(settings.get(Audio.HRTF_SET)).isEqualTo("a.hrtf");
        assertThat(Audio.SPATIALIZER.chosen(settings)).isSameAs(Audio.HRTF);
        HrtfSpatializer made = Audio.spatializer(AudioFormat.stereo(48_000).block(256),
            new Audio.Binaural("", 8, 2, 0.08f, 4, 1));
        assertThat(made.capacity()).isEqualTo(12);
        assertThat(made.ambisonics().fields()).isEqualTo(4);
    }

    @Test
    void inVrTheHeadListensAndHeadphonesAreTheDefault() throws InterruptedException {
        Audio audio = new Audio();
        try (Engine engine = engine(audio, "dummy"); TaskScheduler tasks = new TaskScheduler();
             AssetCache cache = new AssetCache(tasks)) {
            audio.assets(cache);
            SceneGraph scene = new SceneGraph(new ChangeClock());
            Sounds sounds = audio.sounds(scene);
            double[] where = {3, 1.7, -2};
            long listener = audio.head(sounds, (position, orientation) -> {
                System.arraycopy(where, 0, position, 0, 3);
                orientation[0] = 0;
                orientation[1] = (float) Math.sin(0.5);
                orientation[2] = 0;
                orientation[3] = (float) Math.cos(0.5);
                return true;
            });
            for (int i = 0; i < 400 && audio.spatializer() == null; i++) {
                engine.step();
                scene.update(); // the game's update places the head's node
                Thread.sleep(5);
            }
            assertThat(audio.spatializer()).as("AUTO with a head: headphones").isNotNull();
            where[0] = 4;
            engine.step();
            scene.update();
            engine.step();
            assertThat(sounds.listeners().position(listener, 0)).isEqualTo(4);
            assertThat(sounds.listeners().rotation(listener, 1)).isCloseTo(Math.sin(0.5), within(1e-6));
            // no headset device to match: the output stays
            assertThat(audio.headsetDevice("")).isFalse();
            assertThat(audio.headsetDevice(null)).isFalse();
            assertThat(audio.headsetDevice("{0.0.0.00000000}.{00000000-0000-0000-0000-000000000000}")).isFalse();
            String first = WindowsEndpointsTest.firstEndpoint();
            if (!first.isEmpty()) { // a real endpoint's name, which SDL's dummy driver does not list
                assertThat(audio.headsetDevice("{0.0.0.00000000}." + first)).isFalse();
            }
            // the headset idle (hidden): the background rule mutes
            engine.visibilityChanged(false);
            engine.step();
            engine.visibilityChanged(true);
            engine.step();
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
