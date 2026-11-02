package com.truesim.engine.audio;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.app.Engine;
import com.truesim.engine.app.Frame;
import com.truesim.engine.app.Plugin;
import com.truesim.engine.app.PluginKey;
import com.truesim.engine.app.Stage;
import com.truesim.engine.app.Startup;
import com.truesim.engine.platform.Desktop;
import com.truesim.engine.platform.PlatformListener;
import com.truesim.engine.settings.EngineSettings;
import com.truesim.engine.settings.Setting;
import com.truesim.engine.settings.SettingGroup;
import com.truesim.engine.settings.SettingTier;
import com.truesim.scene.assets.AssetCache;
import com.truesim.scene.audio.Bus;
import com.truesim.scene.audio.Sounds;
import com.truesim.scene.core.SceneGraph;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Audio as a plugin (§2.2, optional by D125: no device or no SDL means silence and a log line): the device output
 * ({@link SdlOutput}, else {@link NullOutput}), the renderer, the sync of the stores it hands out ({@link #ui()},
 * {@link #sounds(SceneGraph)}), the "audio" settings group (player volumes saved with the app's settings, the device,
 * the background rule; block, queue and voices hidden at the developer tier) and the collector check. Each frame it
 * takes the renderer's events before the game's update, and after it advances its stores by the frame's delta and
 * syncs them.
 *
 * <pre>{@code
 * app.use(new Desktop(), new Audio());
 * Audio audio = engine.get(Audio.KEY).assets(cache);
 * Sounds sounds = audio.sounds(scene);
 * sounds.listeners().device(cameraNode);
 * audio.ui().play(CLICK);
 * }</pre>
 *
 * <p>SDL's audio starts on the platform's thread through {@code Platform.startAudio} when the desktop runs, else on
 * the engine's thread. With a collector other than ZGC (generational ZGC is recommended: its pauses stay under a
 * millisecond) the queue starts at 4 blocks, as stop-the-world pauses freeze every Java thread.
 */
public final class Audio implements Plugin {

    private static final Logger LOG = LoggerFactory.getLogger(Audio.class);

    /** Its key. */
    public static final PluginKey<Audio> KEY = PluginKey.of("audio", Audio.class);

    /** The settings' group. */
    public static final SettingGroup GROUP = SettingGroup.root("audio", "settings.audio", 60);

    /** What happens to the sound when the game loses focus or is hidden. */
    public enum Background {
        PLAY,
        /** 12 dB lower. */
        LOWER,
        MUTE
    }

    public static final Setting<Float> MASTER_VOLUME = Setting.decimal("audio.volume.master", 1, 0, 1, "master volume");
    public static final Setting<Float> MUSIC_VOLUME = Setting.decimal("audio.volume.music", 1, 0, 1, "music volume");
    public static final Setting<Float> SFX_VOLUME = Setting.decimal("audio.volume.sfx", 1, 0, 1, "effects volume");
    public static final Setting<Float> VOICE_VOLUME = Setting.decimal("audio.volume.voice", 1, 0, 1, "dialogue volume");
    public static final Setting<Float> UI_VOLUME = Setting.decimal("audio.volume.ui", 1, 0, 1, "interface volume");
    public static final Setting<String> DEVICE =
        Setting.text("audio.device", "", "the playback device by name; empty for the system's default");
    public static final Setting<Background> BACKGROUND =
        Setting.choice("audio.background", Background.PLAY, "the sound when the game is in the background");
    public static final Setting<Integer> BLOCK =
        Setting.integer("audio.block", AudioFormat.DEFAULT_BLOCK, 64, 2048, "frames the mixer renders at once");
    public static final Setting<Integer> LOW_MARK =
        Setting.integer("audio.queue", 0, 0, 8, "blocks queued at least; 0 for automatic (2, or 4 without ZGC)");
    public static final Setting<Integer> VOICES =
        Setting.integer("audio.voices", AudioRenderer.DEFAULT_VOICES, 16, 8192, "logical voices");
    public static final Setting<String> DRIVER =
        Setting.text("audio.driver", "", "SDL's audio driver by name (dummy, disk); empty for SDL's choice");

    private Engine engine;
    private EngineSettings settings;
    private AudioRenderer renderer;
    private AudioOutput output;
    private AudioSync sync;
    private AssetCache cache;
    private Sounds ui;
    private Sounds[] worlds = new Sounds[0];
    private float background = 1;

    @Override
    public PluginKey<Audio> key() {
        return KEY;
    }

    @Override
    public boolean optional() {
        return true;
    }

    @Override
    @AllowAllocation("starting up")
    public void start(Stage stage, Startup startup) {
        if (stage == Stage.BEFORE_INSTANCE) {
            engine = startup.engine();
            settings = startup.settings();
            startup.registry().add(GROUP, MASTER_VOLUME, MUSIC_VOLUME, SFX_VOLUME, VOICE_VOLUME, UI_VOLUME, DEVICE,
                BACKGROUND);
            startup.registry().addHidden(BLOCK, LOW_MARK, VOICES, DRIVER);
        } else if (stage == Stage.INSTANCE) {
            Desktop desktop = startup.find(Desktop.KEY).orElse(null);
            output = open(desktop);
            renderer = new AudioRenderer(format(), settings.get(VOICES));
            output.start(renderer);
            for (Setting<Float> volume : List.of(MASTER_VOLUME, MUSIC_VOLUME, SFX_VOLUME, VOICE_VOLUME, UI_VOLUME)) {
                settings.onChange(volume, this::volumes);
            }
            volumes();
            if (desktop != null) {
                desktop.listen(new Devices());
            }
        }
    }

    private AudioFormat sdlFormat;

    /** The device's output, or none (logged) when SDL's audio or the device cannot start. */
    @AllowAllocation("starting up")
    private AudioOutput open(Desktop desktop) {
        int block = Integer.highestOneBit(settings.get(BLOCK));
        int low = settings.get(LOW_MARK) > 0 ? settings.get(LOW_MARK) : collectorLowMark();
        String driver = settings.get(DRIVER).isEmpty() ? null : settings.get(DRIVER);
        try {
            if (desktop != null) {
                desktop.platform().startAudio(driver);
            } else {
                SdlOutput.startSdlAudio(driver);
            }
            SdlOutput sdl = SdlOutput.open(settings.get(DEVICE), block, low);
            sdlFormat = sdl.format();
            return sdl;
        } catch (RuntimeException | LinkageError e) {
            LOG.warn("No audio output ({}): sounds play silently", e.getMessage());
            sdlFormat = AudioFormat.stereo(48_000).block(block);
            return new NullOutput(true);
        }
    }

    private AudioFormat format() {
        return sdlFormat;
    }

    /** The queue's low mark for the collector running: 2 blocks under ZGC, 4 under others. */
    @AllowAllocation("starting up")
    static int collectorLowMark() {
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            if (gc.getName().contains("ZGC")) {
                return 2;
            }
        }
        LOG.info("Audio: the collector is not ZGC, so the queue starts at 4 blocks (run with -XX:+UseZGC for 2)");
        return 4;
    }

    /** The asset cache the clips of its stores load through; before {@link #ui()} and {@link #sounds}. */
    @AllowAllocation("set up once")
    public Audio assets(AssetCache assets) {
        if (sync != null) {
            throw new IllegalStateException("The audio plugin has its asset cache already");
        }
        cache = assets;
        sync = new AudioSync(renderer(), assets);
        return this;
    }

    /** The renderer: its clock, its commands. */
    public AudioRenderer renderer() {
        if (renderer == null) {
            throw new IllegalStateException("The audio plugin starts in " + Stage.INSTANCE + ": not yet");
        }
        return renderer;
    }

    /** The output: the device's, or a {@link NullOutput}. */
    public AudioOutput output() {
        renderer();
        return output;
    }

    /** The sync of its stores. */
    public AudioSync sync() {
        if (sync == null) {
            throw new IllegalStateException("Give the audio plugin its asset cache first: assets(cache)");
        }
        return sync;
    }

    /** The sounds of the user interface: 2D, the UI bus, never paused; made once. */
    @AllowAllocation("set up once")
    public Sounds ui() {
        if (ui == null) {
            ui = Sounds.ui();
            sync().add(ui);
        }
        return ui;
    }

    /** A store for the sounds of {@code scene}'s world, synced each frame from now on. */
    @AllowAllocation("set up once")
    public Sounds sounds(SceneGraph scene) {
        Sounds sounds = new Sounds(scene);
        sync().add(sounds);
        worlds = Arrays.copyOf(worlds, worlds.length + 1);
        worlds[worlds.length - 1] = sounds;
        return sounds;
    }

    private void volumes() {
        AudioRenderer.Commands c = renderer.commands();
        c.busGain(Bus.MASTER, settings.get(MASTER_VOLUME) * background);
        c.busGain(Bus.MUSIC, settings.get(MUSIC_VOLUME));
        c.busGain(Bus.SFX, settings.get(SFX_VOLUME));
        c.busGain(Bus.VOICE, settings.get(VOICE_VOLUME));
        c.busGain(Bus.UI, settings.get(UI_VOLUME));
    }

    @Override
    public void beforeUpdate(Frame frame) {
        if (renderer == null) {
            return;
        }
        if (sync != null) {
            sync.events();
        }
        boolean away = !engine.focused() || !engine.visible();
        float wanted = !away ? 1 : switch (settings.get(BACKGROUND)) {
            case PLAY -> 1;
            case LOWER -> 0.25f;
            case MUTE -> 0;
        };
        if (wanted != background) {
            background = wanted;
            renderer.commands().busGain(Bus.MASTER, settings.get(MASTER_VOLUME) * background);
        }
    }

    @Override
    public void afterUpdate(Frame frame) {
        if (sync == null) {
            return;
        }
        long ticks = Math.round(frame.delta() * Sounds.TICKS_PER_SECOND);
        for (Sounds world : worlds) {
            world.advance(ticks);
        }
        if (ui != null) {
            ui.advance(ticks);
        }
        sync.update();
    }

    @Override
    public void stop() {
        if (output != null) {
            output.close();
            output = null;
        }
        if (renderer != null) {
            renderer.close();
        }
    }

    /** Follows the devices: a chosen device going away leaves the default playing (SDL follows the default). */
    @AllowAllocation("device changes: rare, logged")
    private final class Devices implements PlatformListener {
        @Override
        public void audioDeviceRemoved(int device) {
            if (output instanceof SdlOutput sdl && sdl.chosen() && sdl.device() == device) {
                LOG.warn("Audio: {} went away; the default device plays", sdl.deviceName());
                sdl.close();
                SdlOutput next = SdlOutput.reopen(renderer.format(), sdl.lowMark());
                output = next;
                next.start(renderer);
            }
        }

        @Override
        public void audioDeviceFormatChanged(int device) {
            LOG.info("Audio: device {} changed its format; SDL converts until the output restarts", device);
        }
    }
}
