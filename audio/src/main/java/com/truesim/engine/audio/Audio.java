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
import com.truesim.scene.audio.Music;
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
    /** Dynamic range presets (§2.17): the master compressed for quieter rooms. */
    public enum DynamicRange {
        FULL,
        HOME,
        NIGHT
    }

    public static final Setting<Boolean> MONO = Setting.bool("audio.mono", false, "both ears hear everything");
    public static final Setting<Float> BALANCE = Setting.decimal("audio.balance", 0, -1, 1, "left to right");
    public static final Setting<DynamicRange> DYNAMIC_RANGE =
        Setting.choice("audio.dynamicRange", DynamicRange.FULL, "how loud the quiet and loud parts are");
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

    /** What the sound plays on (§2.17): SDL cannot tell headphones from speakers, so the player says. */
    public enum Output {
        /** Stereo, or the device's own layout: panned. */
        AUTO,
        /** Binaural: sounds marked HRTF go through the HRTF (§2.9). */
        HEADPHONES,
        STEREO_SPEAKERS,
        SURROUND
    }

    public static final Setting<Output> OUTPUT =
        Setting.choice("audio.output", Output.AUTO, "headphones play sounds around the head (HRTF)");
    public static final Setting<String> HRTF_SET =
        Setting.text("audio.hrtf.set", "", "the HRTF set, an .hrtf file; empty for the built-in one");
    public static final Setting<Integer> HRTF_VOICES =
        Setting.integer("audio.hrtf.voices", 16, 0, 64, "voices rendered through the HRTF at most");

    private Engine engine;
    private Mixer mixer;
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
                BACKGROUND, MONO, BALANCE, DYNAMIC_RANGE, OUTPUT, HRTF_SET); // no tiers declared by default
            startup.registry().addHidden(BLOCK, LOW_MARK, VOICES, DRIVER, HRTF_VOICES);
        } else if (stage == Stage.INSTANCE) {
            Desktop desktop = startup.find(Desktop.KEY).orElse(null);
            output = open(desktop);
            renderer = new AudioRenderer(format(), settings.get(VOICES));
            mixer = new Mixer(renderer.commands());
            output.start(renderer);
            for (Setting<?> access : List.of(MONO, BALANCE, DYNAMIC_RANGE)) {
                settings.onChange(access, this::accessibility);
            }
            accessibility();
            for (Setting<?> binaural : List.of(OUTPUT, HRTF_SET, HRTF_VOICES, MONO)) {
                settings.onChange(binaural, this::binaural);
            }
            binaural();
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
        mixer.playerVolume(Bus.MASTER, settings.get(MASTER_VOLUME) * background);
        mixer.playerVolume(Bus.MUSIC, settings.get(MUSIC_VOLUME));
        mixer.playerVolume(Bus.SFX, settings.get(SFX_VOLUME));
        mixer.playerVolume(Bus.VOICE, settings.get(VOICE_VOLUME));
        mixer.playerVolume(Bus.UI, settings.get(UI_VOLUME));
        mixer.update(0);
    }

    private void accessibility() {
        renderer.commands().accessibility(settings.get(MONO), settings.get(BALANCE),
            settings.get(DYNAMIC_RANGE).ordinal());
    }

    // Binaural rendering (§2.9): the set prepared off the game thread, handed to the renderer from it.
    private volatile Spatializer preparedSpatializer;
    private volatile boolean binauralChanged;
    private Spatializer spatializer;
    private int binauralWanted;

    /**
     * Turns binaural rendering on with headphones (stereo, mono off) and off otherwise: the set (the built-in one, or
     * {@link #HRTF_SET}'s file) is prepared for the output's rate on a background thread and handed to the renderer at
     * the next frame.
     */
    @AllowAllocation("a setting changed")
    private void binaural() {
        Output output = settings.get(OUTPUT);
        boolean on = (output == Output.HEADPHONES || output == Output.AUTO && headPose != null) // VR: headphones
            && renderer.format().channels() == 2 && !settings.get(MONO) && settings.get(HRTF_VOICES) > 0;
        int wanted = ++binauralWanted;
        if (!on) {
            preparedSpatializer = null;
            binauralChanged = true;
            return;
        }
        String file = settings.get(HRTF_SET);
        int voices = settings.get(HRTF_VOICES);
        AudioFormat format = renderer.format();
        Thread.ofPlatform().daemon().name("audio-hrtf").start(() -> {
            Hrtf hrtf = prepare(file, format.rate());
            HrtfSpatializer next = new HrtfSpatializer(hrtf, format.block(), voices + 4);
            if (wanted == binauralWanted) {
                preparedSpatializer = next;
                binauralChanged = true;
            }
        });
    }

    /** The set in {@code file} (empty: the built-in one) prepared for {@code rate}; the built-in one if it fails. */
    @AllowAllocation("preparing a set")
    static Hrtf prepare(String file, int rate) {
        if (!file.isEmpty()) {
            try (java.io.InputStream in = java.nio.file.Files.newInputStream(java.nio.file.Path.of(file))) {
                return Hrtf.prepare(com.truesim.scene.audio.HrtfSet.read(in), rate);
            } catch (java.io.IOException | RuntimeException e) {
                LOG.warn("Audio: the HRTF set {} does not load ({}); the built-in one plays", file, e.getMessage());
            }
        }
        return Hrtf.builtIn(rate);
    }

    // VR (§2.10, step 18): the head as the listener.
    private HeadPose headPose;
    private SceneGraph headScene;
    private long headNode;
    private final double[] headPosition = new double[3];
    private final float[] headOrientation = new float[4];

    /**
     * Listens from a tracked head ({@code xrInput::head}) in {@code sounds}' world: a node made for it carries a device
     * listener and follows the pose before each frame's update (the same predicted pose the eyes use), so the game's
     * {@code scene.update()} places it. With {@link #OUTPUT} at {@code AUTO} a followed head means headphones: HRTF
     * on (§2.17: in VR the default is headphones). Returns the listener's id.
     */
    @AllowAllocation("set up once")
    public long head(Sounds sounds, HeadPose pose) {
        headScene = sounds.scene();
        headNode = headScene.create();
        headPose = pose;
        follow();
        binaural();
        return sounds.listeners().device(headNode).id();
    }

    private void follow() {
        if (headPose != null && headPose.head(headPosition, headOrientation)) {
            headScene.setTranslation(headNode, headPosition[0], headPosition[1], headPosition[2]);
            headScene.setRotation(headNode, headOrientation[0], headOrientation[1], headOrientation[2],
                headOrientation[3]);
        }
    }

    /**
     * Plays on the headset's own audio device, Windows endpoint {@code endpointId} ({@code headset.audioOutputDevice()}
     * where the runtime offers {@code XR_OCULUS_audio_device_guid}), matched to SDL's devices by name (SDL 3.4 has no
     * endpoint ids): true when it now plays there. Without an id (SteamVR, which sets the system's default device to
     * the headset instead) or a match, the output stays as it is.
     */
    @AllowAllocation("changing device, once")
    public boolean headsetDevice(String endpointId) {
        String name = endpointId == null ? "" : WindowsEndpoints.friendlyName(endpointId);
        if (name.isEmpty() || !(output instanceof SdlOutput sdl)) {
            LOG.info("Audio: no headset device to match ({}); the default device plays", endpointId);
            return false;
        }
        if (!SdlOutput.has(name)) {
            LOG.info("Audio: the headset's device {} is not one of SDL's; the default device plays", name);
            return false;
        }
        sdl.close();
        SdlOutput next = SdlOutput.reopen(name, renderer.format(), sdl.lowMark());
        output = next;
        next.start(renderer);
        LOG.info("Audio: playing on the headset's device {}", name);
        return true;
    }

    /** The spatializer the renderer was last given (null: none); set a frame after the settings ask for it. */
    public Spatializer spatializer() {
        return spatializer;
    }

    private Music music;

    /** The music player (§2.12): music over the renderer's clock, its clips through the asset cache; made once. */
    @AllowAllocation("set up once")
    public Music music() {
        if (music == null) {
            music = new Music(renderer().clock(), new CachedMusicClips(cache));
            sync().music(music);
        }
        return music;
    }

    /** The patches sounds name, and their node types: register a plugin's here (they warm as registered). */
    public com.truesim.engine.audio.dsp.Patches patches() {
        return sync().patches();
    }

    /** Fills {@code out} with the loudest captioned sounds {@code listener} hears; see {@link AudioSync#indicators}. */
    public int indicators(long listener, Indicators out) {
        return sync().indicators(listener, out);
    }

    /** The mixer: the layout, snapshots, player volumes. */
    public Mixer mixer() {
        renderer();
        return mixer;
    }

    @Override
    public void beforeUpdate(Frame frame) {
        if (renderer == null) {
            return;
        }
        if (sync != null) {
            sync.events();
        }
        follow();
        if (binauralChanged) {
            binauralChanged = false;
            spatializer = preparedSpatializer;
            renderer.commands().spatializer(spatializer, settings.get(HRTF_VOICES));
        }
        boolean away = !engine.focused() || !engine.visible();
        float wanted = !away ? 1 : switch (settings.get(BACKGROUND)) {
            case PLAY -> 1;
            case LOWER -> 0.25f;
            case MUTE -> 0;
        };
        if (wanted != background) {
            background = wanted;
            mixer.playerVolume(Bus.MASTER, settings.get(MASTER_VOLUME) * background);
        }
    }

    @Override
    public void afterUpdate(Frame frame) {
        if (sync == null) {
            return;
        }
        if (music != null) {
            music.update();
        }
        long ticks = Math.round(frame.delta() * Sounds.TICKS_PER_SECOND);
        for (Sounds world : worlds) {
            world.advance(ticks);
        }
        if (ui != null) {
            ui.advance(ticks);
        }
        sync.update();
        mixer.update(frame.delta());
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
