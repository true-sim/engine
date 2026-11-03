package com.truesim.engine.audio;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.jobs.JobSystem;
import com.truesim.scene.audio.AudioWorld;
import com.truesim.scene.audio.Occlusion;
import com.truesim.scene.audio.Transmission;
import com.truesim.engine.audio.dsp.PatchVoice;
import com.truesim.engine.audio.dsp.Patches;
import com.truesim.scene.assets.AssetCache;
import com.truesim.scene.assets.AssetRef;
import com.truesim.scene.assets.AssetState;
import com.truesim.scene.audio.AudioClip;
import com.truesim.scene.audio.Attenuation;
import com.truesim.scene.audio.Bus;
import com.truesim.scene.audio.Music;
import com.truesim.scene.audio.Patch;
import com.truesim.scene.audio.Decibels;
import com.truesim.scene.audio.AudioEffect;
import com.truesim.scene.audio.ListenerMix;
import com.truesim.scene.audio.Listeners;
import com.truesim.scene.audio.Play;
import com.truesim.scene.audio.ReverbZones;
import com.truesim.scene.audio.Release;
import com.truesim.scene.audio.Sound;
import com.truesim.scene.audio.Sounds;
import com.truesim.scene.audio.Spatial;
import java.util.Arrays;

/**
 * The game thread's half of audio (§2.4), once a frame after the game has updated the scene and advanced the sounds:
 * it follows each store's change log and writes commands, stamped at the frame's schedule point (the clock's
 * rendered frame plus the scheduling latency) plus each voice's delay, so a frame's sounds keep their relative timing;
 * acquires the clips instances play from the asset cache and starts them when loaded (dropping, and counting, those
 * not loaded within their sound's max start delay rather than playing late); sends gains, pitches, pauses and stops;
 * computes where placed sounds are relative to the listener in doubles; and, through {@link #events()} at the start of
 * the next frame, frees the voices the renderer reports ended and ends the instances whose voices all ended.
 *
 * <pre>{@code
 * AudioSync sync = new AudioSync(renderer, cache).add(sounds).add(ui);
 * // each frame:
 * sync.events();           // before the game's update
 * sync.update();           // after it, and after sounds.advance()
 * }</pre>
 *
 * <p>Voices take renderer slots first fit, so an offline render of the same calls is the same. Positions: each placed
 * instance is heard from its nearest device listener (with 10 % hysteresis; a change cross-fades over 50 ms); its world
 * position and the listener's are doubles, and only the listener-relative position becomes floats, so a sound
 * 7,000 km out pans and Dopplers as one at the origin. The renderer attenuates, filters, pans and chooses the real
 * voices (AU7). Allocation-free once grown.
 */
public final class AudioSync {

    private final AudioRenderer renderer;
    private final AudioRenderer.Commands commands;
    private final AssetCache cache;
    private final RenderClock clock;
    private final int rate;
    private Sounds[] stores = new Sounds[0];
    private long[] marks = new long[0];
    private LongIntMap[] tracked = new LongIntMap[0];

    // Tracks: instances followed, by index; free ones on a stack.
    private Track[] tracks = new Track[0];
    private int[] freeTracks = new int[0];
    private int freeTrackCount;

    // Renderer slots: which track's which voice holds each.
    private final int[] slotTrack;
    private final int[] slotVoice;
    private int slotsUsed;

    private int storeNow;
    private long schedulePoint;
    private long dropped;
    private final Sounds.Visitor changes = this::changed;
    private final AudioRenderer.EventVisitor endings = this::ended;

    /** An instance followed: its voices' slots and state. */
    private static final class Track {
        int store;
        long id;
        Sound sound;
        int voices;
        int[] slots = new int[4];
        long[] starts = new long[4];
        byte[] state = new byte[4];
        Object[] acquired = new Object[4];
        StreamRing[] rings = new StreamRing[4];
        int acquiredCount;
        long added;
        boolean gone;
        boolean stopped;
        boolean placed;
        long listener;
        long placedAt;
        final double[] previous = new double[3];
        double previousDistance;
        float doppler = 1;
        // occlusion (step 16): smoothed transmissions, this frame's sums from its rays, what was sent
        float occMid = 1;
        float occHigh = 1;
        float targetMid = 1;
        float targetHigh = 1;
        float sumMid;
        float sumHigh;
        int rays;
        float sentGain = 1;
        float sentCutoff;
        boolean sharesZone = true;
        boolean occKnown;

        @AllowAllocation("a sound with more voices than any before")
        void grow(int n) {
            int size = Math.max(n, 2 * slots.length);
            slots = Arrays.copyOf(slots, size);
            starts = Arrays.copyOf(starts, size);
            state = Arrays.copyOf(state, size);
            acquired = Arrays.copyOf(acquired, size);
            rings = Arrays.copyOf(rings, size);
        }
    }

    private static final byte WAITING = 0;
    private static final byte PLAYING = 1;
    private static final byte DONE = 2;

    /** The sync of {@code renderer}, loading clips through {@code cache}. */
    @AllowAllocation("made once")
    public AudioSync(AudioRenderer renderer, AssetCache cache) {
        this.renderer = renderer;
        commands = renderer.commands();
        this.cache = cache;
        clock = renderer.clock();
        rate = renderer.format().rate();
        slotTrack = new int[renderer.voices()];
        slotVoice = new int[renderer.voices()];
        slotMusicVoice = new long[renderer.voices()];
        slotPatch = new PatchVoice[renderer.voices()];
        heardSent = new float[Voices.LISTENERS * 2 * renderer.voices()];
        Arrays.fill(renderStores, -1);
        musicGain = new float[renderer.voices()];
        stingerSlot = new boolean[renderer.voices()];
        Arrays.fill(slotTrack, -1);
    }

    /** Follows {@code sounds} from now on: a world's store, or the UI store. */
    @AllowAllocation("set up once")
    public AudioSync add(Sounds sounds) {
        int n = stores.length;
        stores = Arrays.copyOf(stores, n + 1);
        marks = Arrays.copyOf(marks, n + 1);
        tracked = Arrays.copyOf(tracked, n + 1);
        stores[n] = sounds;
        marks[n] = -1;
        tracked[n] = new LongIntMap(64);
        sounds.dialogue().heardByEngine(); // its lines are heard when this says
        return this;
    }

    /** Voices playing in the renderer now, by this sync's count. */
    public int voices() {
        return slotsUsed;
    }

    /** Instances dropped because their clips loaded later than their max start delay. */
    public long dropped() {
        return dropped;
    }

    /** The schedule point of the last update: the output frame a voice started then (without delay) starts at. */
    public long schedulePoint() {
        return schedulePoint;
    }

    // Music (step 11): its voices by slot, and its timed changes waiting for their frame.
    private static final int MUSIC = -2;
    private Music music;
    private final LongIntMap musicSlots = new LongIntMap(64);
    private final long[] slotMusicVoice;
    private final float[] musicGain;
    private final boolean[] stingerSlot;
    private long[] pendingFrames = new long[16];
    private int[] pendingKinds = new int[16];
    private long[] pendingVoices = new long[16];
    private float[] pendingGains = new float[16];
    private float[] pendingSeconds = new float[16];
    private int pendingCount;

    /**
     * Plays {@code music}'s commands (§2.12, step 11): starts on the music bus at their exact frames (streamed clips
     * through rings), stops with their fades, and stem gains, pauses and stinger ducks at their frames, to within the
     * frame of the update that reaches them. The game (or the plugin) calls {@code music.update()} before
     * {@link #update()}.
     */
    public AudioSync music(Music music) {
        this.music = music;
        return this;
    }

    // Rendering listeners (step 13): the renderer's four, by scene listener and store; what each hears of each slot.
    private final long[] renderIds = new long[Voices.LISTENERS];
    private final int[] renderStores = new int[Voices.LISTENERS];
    private final boolean[] renderSeen = new boolean[Voices.LISTENERS];
    private final ListenerMix[] renderMixes = new ListenerMix[Voices.LISTENERS];
    private final double[] renders = new double[7 * Voices.LISTENERS];
    private final ListenerTap[] taps = new ListenerTap[Voices.LISTENERS];
    private final int[] order = new int[Voices.LISTENERS];
    private final int[] sentOrder = new int[Voices.LISTENERS];
    private int sentOrderCount = -1;
    private final float[] heardSent;
    private int listenersDropped;

    /** The tap of rendering listener {@code listener} (its mix made with {@code tap()}); null if none. */
    public ListenerTap tap(long listener) {
        for (int l = 0; l < Voices.LISTENERS; l++) {
            if (renderStores[l] >= 0 && renderIds[l] == listener) {
                return taps[l];
            }
        }
        return null;
    }

    /** Rendering listeners past the renderer's four, not rendered. */
    public int listenersDropped() {
        return listenersDropped;
    }

    /** The renderer's index of rendering listener {@code listener} of store {@code s}; -1 for none. */
    private int renderIndex(int s, long listener) {
        for (int l = 0; l < Voices.LISTENERS; l++) {
            if (renderStores[l] == s && renderIds[l] == listener) {
                return l;
            }
        }
        return -1;
    }

    /** A rendering listener seen this frame: given a renderer index (set up when new or its mix changed), and placed. */
    private void rendering(int s, long listener, Listeners listeners) {
        int l = renderIndex(s, listener);
        if (l < 0) {
            for (int k = 0; k < Voices.LISTENERS && l < 0; k++) {
                l = renderStores[k] < 0 ? k : -1;
            }
            if (l < 0) {
                listenersDropped++;
                return;
            }
            renderStores[l] = s;
            renderIds[l] = listener;
            renderMixes[l] = null;
        }
        renderSeen[l] = true;
        ListenerMix mix = listeners.mix(listener);
        if (mix != renderMixes[l]) {
            setUp(l, mix);
        }
        for (int i = 0; i < 3; i++) {
            renders[7 * l + i] = listeners.position(listener, i);
        }
        for (int i = 0; i < 4; i++) {
            renders[7 * l + 3 + i] = listeners.rotation(listener, i);
        }
    }

    @AllowAllocation("a rendering listener set up: its tap")
    private void setUp(int l, ListenerMix mix) {
        renderMixes[l] = mix;
        if (mix.tapped() && (taps[l] == null || taps[l].channels() != mix.channels())) {
            taps[l] = new ListenerTap(mix.channels(), 1 << 16);
        } else if (!mix.tapped()) {
            taps[l] = null;
        }
        commands.listener(l, true, mix.channels(), Decibels.gain(mix.gain()), mix.deviceBus(), taps[l]);
    }

    /** Listeners not seen this frame are turned off; the order is sent when it changed. */
    private void renderingDone() {
        for (int l = 0; l < Voices.LISTENERS; l++) {
            if (renderStores[l] >= 0 && !renderSeen[l]) {
                commands.listener(l, false, 1, 0, null, null);
                renderStores[l] = -1;
                renderMixes[l] = null;
                taps[l] = null;
                Arrays.fill(heardSent, l * heardSent.length / Voices.LISTENERS,
                    (l + 1) * heardSent.length / Voices.LISTENERS, 0);
            }
            renderSeen[l] = false;
        }
        int count = 0;
        for (int s = 0; s < stores.length; s++) {
            Listeners listeners = stores[s].listeners();
            for (int i = 0; i < listeners.orderCount() && count < order.length; i++) {
                int l = renderIndex(s, listeners.order(i));
                if (l >= 0) {
                    order[count++] = l;
                }
            }
        }
        boolean same = count == sentOrderCount;
        for (int i = 0; same && i < count; i++) {
            same = order[i] == sentOrder[i];
        }
        if (!same && commands.order(order, count)) {
            System.arraycopy(order, 0, sentOrder, 0, count);
            sentOrderCount = count;
        }
    }

    /**
     * What each rendering listener hears of track {@code t}'s voices (§2.10): placed sounds on buses its mix hears,
     * within its max distance, attenuated by the sound's curve and panned for a stereo mix; sent when changed.
     */
    private void hear(int t) {
        Track track = tracks[t];
        Bus bus = track.sound.bus();
        Attenuation attenuation = track.sound.attenuation();
        int voices = slotTrack.length;
        for (int l = 0; l < Voices.LISTENERS; l++) {
            float left = 0;
            float right = 0;
            ListenerMix mix = renderMixes[l];
            if (renderStores[l] == track.store && mix != null && mix.hears(bus)) {
                relativeTo(track, renders, 7 * l);
                double x = relative[0];
                double distance = Math.sqrt(x * x + relative[1] * relative[1] + relative[2] * relative[2]);
                if (distance <= mix.maxDistance()) {
                    float a = attenuation.gain((float) distance);
                    if (mix.channels() == 2) {
                        double pan = distance > 1e-6 ? Math.max(-1, Math.min(1, x / distance)) : 0;
                        left = (float) (a * com.truesim.common.math.Maths.cos((pan + 1) * com.truesim.common.math.Maths.PI / 4));
                        right = (float) (a * com.truesim.common.math.Maths.sin((pan + 1) * com.truesim.common.math.Maths.PI / 4));
                    } else {
                        left = a;
                        right = a;
                    }
                }
            }
            for (int v = 0; v < track.voices; v++) {
                if (track.state[v] != PLAYING) {
                    continue;
                }
                int slot = track.slots[v];
                int i = 2 * (l * voices + slot);
                if (Math.abs(heardSent[i] - left) > 1e-4f || Math.abs(heardSent[i + 1] - right) > 1e-4f) {
                    if (commands.listen(slot, l, left, right)) {
                        heardSent[i] = left;
                        heardSent[i + 1] = right;
                    }
                }
            }
        }
    }

    /** Starts a voice re-emitting the listener its instance is given as input (none yet: silent until one is). */
    private boolean startInput(int t, int v) {
        Track track = tracks[t];
        if (firstFree() < 0) {
            return false;
        }
        int l = renderIndex(track.store, stores[track.store].input(track.id));
        if (!commands.inputNext(l)) {
            return false;
        }
        long at = schedulePoint + Math.round(stores[track.store].voiceDelay(track.id, v) * rate);
        play(t, v, null, at);
        return track.state[v] == PLAYING;
    }

    // Patches (step 12): the voice each slot plays, back to its pool when the slot ends.
    private final PatchVoice[] slotPatch;
    private Patches patches;

    /** The patches sounds name ({@code Patch.named}), with their node types; made on first use at the mix's rate. */
    @AllowAllocation("made once")
    public Patches patches() {
        if (patches == null) {
            patches = new Patches(renderer.format().rate(), renderer.format().block());
        }
        return patches;
    }

    /** Takes the renderer's events: ended voices free their slots; instances whose voices all ended end. */
    public void events() {
        renderer.poll(endings);
    }

    private void ended(int kind, int slot, long tag, long frame) {
        int t = slotTrack[slot];
        if (kind == AudioRenderer.ENDED && t == MUSIC) {
            slotTrack[slot] = -1;
            slotsUsed--;
            musicSlots.remove(slotMusicVoice[slot]);
            return;
        }
        if (kind != AudioRenderer.ENDED || t < 0) {
            return;
        }
        if (slotPatch[slot] != null) {
            patches.give(slotPatch[slot]);
            slotPatch[slot] = null;
        }
        slotTrack[slot] = -1;
        slotsUsed--;
        Track track = tracks[t];
        track.state[slotVoice[slot]] = DONE;
        track.slots[slotVoice[slot]] = -1;
        finishIfDone(t);
    }

    /** Ends track {@code t} once nothing of it plays or will: its instance ends if it still lives. */
    private void finishIfDone(int t) {
        Track track = tracks[t];
        for (int v = 0; v < track.voices; v++) {
            if (track.state[v] == PLAYING || track.state[v] == WAITING && !track.gone) {
                return;
            }
        }
        Sounds store = stores[track.store];
        if (store.alive(track.id)) {
            store.ended(track.id);
        }
        free(t);
    }

    /** Follows every store's changes and writes the frame's commands. Allocates nothing once grown. */
    public void update() {
        schedulePoint = clock.schedulePoint();
        for (int s = 0; s < stores.length; s++) {
            Sounds store = stores[s];
            storeNow = s;
            listeners(s);
            long now = store.clock().now();
            if (!store.changesSince(marks[s], changes)) {
                rebuild(s);
            }
            marks[s] = now;
            listenerZone(s);
        }
        renderingDone();
        if (occluder != null) {
            occlude();
        }
        if (music != null) {
            music();
        }
        long heardFrame = clock.heardFrame();
        for (int t = 0; t < tracks.length; t++) {
            Track track = tracks[t];
            if (track != null && track.sound != null && !track.gone) {
                start(t);
                if (track.sound != null && placed(track)) {
                    place(t);
                    hear(t);
                }
                if (track.sound != null && track.sound.bus() == Bus.VOICE) {
                    line(track, heardFrame);
                }
            }
        }
        for (Sounds store : stores) {
            store.dialogue().update(); // its events at the heard times just told
        }
    }

    // Occlusion (step 16).
    private Occluder occluder;
    private int occlusionCursor;
    private long occlusionPoint = -1;
    private int occlusionSeed;

    /**
     * Occludes sounds whose occlusion is {@code RAYS} (§2.11) by rays through {@code world}, traced on {@code jobs}
     * (null: on the calling thread), 64 rays a frame round-robin; null turns occlusion off.
     */
    @AllowAllocation("set up once")
    public AudioSync occlusion(AudioWorld world, JobSystem jobs) {
        occluder = world == null ? null : new Occluder(world, jobs, occluder == null ? 64 : occluder.budget());
        return this;
    }

    /** Rays a frame, at most. */
    public AudioSync occlusionBudget(int rays) {
        if (occluder != null) {
            occluder.budget(rays);
        }
        return this;
    }

    /** Occlusion rays traced so far. */
    public long occlusionRays() {
        return occluder == null ? 0 : occluder.traced();
    }

    /** The smoothed mid-band transmission of instance {@code id} of store {@code s} (its gain); 1 if not occluded. */
    public float occlusion(long id) {
        for (Track track : tracks) {
            if (track != null && track.sound != null && track.id == id) {
                return track.occMid;
            }
        }
        return 1;
    }

    /** The frame's rays, round-robin over occluding sounds; traced, summed per sound, smoothed, sent. */
    private void occlude() {
        double seconds = occlusionPoint < 0 ? 0 : (double) (schedulePoint - occlusionPoint) / rate;
        occlusionPoint = schedulePoint;
        occluder.begin();
        int n = tracks.length;
        int visited = 0;
        for (; visited < n; visited++) {
            int t = (occlusionCursor + visited) % n;
            Track track = tracks[t];
            if (!occluding(track)) {
                continue;
            }
            float radius = track.sound.attenuation().radius();
            int need = radius > 0 ? 4 : 1;
            if (!occluder.room(need)) {
                break;
            }
            Sounds store = stores[track.store];
            int d = nearest(track);
            double[] p = devices[track.store];
            occluder.add(t, d < 0 ? 0 : p[7 * d], d < 0 ? 0 : p[7 * d + 1], d < 0 ? 0 : p[7 * d + 2],
                store.position(track.id, 0), store.position(track.id, 1), store.position(track.id, 2), radius,
                occlusionSeed++);
            track.rays = 0;
            track.sumMid = 0;
            track.sumHigh = 0;
        }
        occlusionCursor = n == 0 ? 0 : (occlusionCursor + visited) % n;
        occluder.trace();
        for (int i = 0; i < occluder.count(); i++) {
            Track track = tracks[occluder.owner(i)];
            Transmission r = occluder.result(i);
            // the gain: between the lows' and the mids' (bass carries through walls); the cutoff from the highs
            track.sumMid += (float) Math.sqrt(r.low() * r.mid());
            track.sumHigh += r.high();
            track.rays++;
        }
        for (Track track : tracks) {
            if (!occluding(track)) {
                continue;
            }
            if (track.rays > 0) {
                track.targetMid = track.sumMid / track.rays;
                track.targetHigh = track.sumHigh / track.rays;
                track.rays = 0;
                if (!track.occKnown) { // its first rays: taken at once, not faded from open
                    track.occKnown = true;
                    track.occMid = track.targetMid;
                    track.occHigh = track.targetHigh;
                }
            }
            track.occMid = Occluder.smooth(track.occMid, track.targetMid, seconds);
            track.occHigh = Occluder.smooth(track.occHigh, track.targetHigh, seconds);
            float cutoff = Occluder.cutoff(track.occMid, track.occHigh);
            if (Math.abs(track.occMid - track.sentGain) > 0.002f || Math.abs(cutoff - track.sentCutoff) > 20) {
                for (int v = 0; v < track.voices; v++) {
                    if (track.state[v] == PLAYING) {
                        commands.occlude(track.slots[v], track.occMid, cutoff);
                    }
                }
                track.sentGain = track.occMid;
                track.sentCutoff = cutoff;
            }
        }
    }

    private boolean occluding(Track track) {
        return track != null && track.sound != null && !track.gone && track.sound.occlusion() == Occlusion.RAYS
            && playing(track) && placed(track);
    }

    /** The strongest reverb zone at device listener {@code listener} of store {@code s}; 0 for none. */
    private long listenerZoneOf(int s, long listener) {
        for (int n = 0; n < deviceCounts[s]; n++) {
            if (deviceIds[s][n] == listener) {
                double[] d = devices[s];
                return stores[s].reverbZones().strongest(d[7 * n], d[7 * n + 1], d[7 * n + 2]);
            }
        }
        return 0;
    }

    /** Tells a dialogue line where it is heard ({@code heardFrame} against its start) and how loud it is. */
    private void line(Track track, long heardFrame) {
        Sounds store = stores[track.store];
        if (!store.dialogue().saying(track.id)) {
            return;
        }
        if (track.state[0] != PLAYING) {
            store.dialogue().heard(track.id, -1); // loading: not heard yet
            return;
        }
        store.dialogue().heard(track.id, (double) (heardFrame - track.starts[0]) / rate);
        store.dialogue().envelope(track.id, renderer.level(track.slots[0]));
    }

    /**
     * Fills {@code out} with the loudest captioned sounds device listener {@code listener} hears (§2.17, visualized
     * sound for a HUD ring): their caption, direction from the listener in its frame (x right, y up, -z ahead) and
     * loudness (the attenuation at the distance times the voice's gain). Returns how many. Allocates nothing.
     */
    public int indicators(long listener, Indicators out) {
        out.clear();
        for (int s = 0; s < stores.length && s < deviceIds.length; s++) {
            for (int n = 0; n < deviceCounts[s]; n++) {
                if (deviceIds[s][n] == listener) {
                    indicators(s, n, out);
                    return out.count();
                }
            }
        }
        return 0;
    }

    private void indicators(int s, int n, Indicators out) {
        for (Track track : tracks) {
            if (track == null || track.sound == null || track.gone || track.store != s
                || track.sound.caption() == null || !playing(track) || !placed(track)) {
                continue;
            }
            relativeTo(track, devices[s], 7 * n);
            double x = relative[0];
            double y = relative[1];
            double z = relative[2];
            double distance = Math.sqrt(x * x + y * y + z * z);
            int v = 0;
            while (track.state[v] != PLAYING) {
                v++;
            }
            float loudness = track.sound.attenuation().gain((float) distance) * gain(track, v);
            double d = Math.max(distance, 1e-9);
            out.offer(track.sound.caption(), track.id, (float) (x / d), (float) (y / d), (float) (z / d), loudness);
        }
    }

    /** Turns the music's commands into the renderer's; applies the timed ones whose frame has come. */
    private void music() {
        for (int c = 0; c < music.commands(); c++) {
            long frame = music.frame(c);
            long voice = music.voice(c);
            switch (music.kind(c)) {
                case Music.START, Music.STINGER -> startMusic(music.clip(c), frame, voice, music.offset(c),
                    music.kind(c) == Music.START ? music.gain(c) : 1, music.kind(c) == Music.STINGER ? music.gain(c)
                        : 0);
                case Music.STOP -> {
                    int slot = musicSlots.get(voice);
                    if (slot >= 0) {
                        commands.stop(slot, frame, music.seconds(c));
                    }
                }
                default -> later(music.kind(c), frame, voice, music.gain(c), music.seconds(c));
            }
        }
        music.clearCommands();
        // timed changes: applied by the update whose schedule point reaches them
        for (int i = 0; i < pendingCount; ) {
            if (pendingFrames[i] > schedulePoint) {
                i++;
                continue;
            }
            applyLater(pendingKinds[i], pendingVoices[i], pendingGains[i], pendingSeconds[i]);
            pendingCount--;
            pendingFrames[i] = pendingFrames[pendingCount];
            pendingKinds[i] = pendingKinds[pendingCount];
            pendingVoices[i] = pendingVoices[pendingCount];
            pendingGains[i] = pendingGains[pendingCount];
            pendingSeconds[i] = pendingSeconds[pendingCount];
        }
    }

    private void startMusic(AssetRef<AudioClip> ref, long frame, long voice, double offset, float gain, float duck) {
        if (cache.state(ref) != AssetState.LOADED) {
            return; // not primed in time: music primes before it schedules, so this is a late playlist's next
        }
        AudioClip clip = cache.get(ref);
        int slot = firstFree();
        if (slot < 0) {
            return;
        }
        if (clip.streamed()) {
            StreamRing ring = newRing(clip, false);
            streams().add(ring);
            commands.stream(ring);
        }
        int flags = com.truesim.scene.audio.Priority.HIGHEST.ordinal() << Voices.PRIORITY_SHIFT;
        commands.play(slot, clip, frame, offset, gain * clip.gain(), 1, Bus.MUSIC, flags, 0, voice);
        slotTrack[slot] = MUSIC;
        slotMusicVoice[slot] = voice;
        musicGain[slot] = gain * clip.gain();
        stingerSlot[slot] = duck < 0;
        slotsUsed++;
        musicSlots.put(voice, slot);
        if (duck < 0) { // a stinger: the other music ducks while it plays
            later(Music.GAIN, frame, -1, Decibels.gain(duck), 0.05f);
            later(Music.GAIN, frame + Math.round(clip.seconds() * rate), -1, 1, 0.3f);
        }
    }

    private void later(int kind, long frame, long voice, float gain, float seconds) {
        if (pendingCount == pendingFrames.length) {
            growPending();
        }
        pendingFrames[pendingCount] = frame;
        pendingKinds[pendingCount] = kind;
        pendingVoices[pendingCount] = voice;
        pendingGains[pendingCount] = gain;
        pendingSeconds[pendingCount++] = seconds;
    }

    @AllowAllocation("more timed music changes than before")
    private void growPending() {
        int n = 2 * pendingFrames.length;
        pendingFrames = Arrays.copyOf(pendingFrames, n);
        pendingKinds = Arrays.copyOf(pendingKinds, n);
        pendingVoices = Arrays.copyOf(pendingVoices, n);
        pendingGains = Arrays.copyOf(pendingGains, n);
        pendingSeconds = Arrays.copyOf(pendingSeconds, n);
    }

    /** A timed change: a voice's gain (voice -1: every music voice's duck), or a pause or resume of all music. */
    private void applyLater(int kind, long voice, float gain, float seconds) {
        if (kind == Music.GAIN && voice >= 0) {
            int slot = musicSlots.get(voice);
            if (slot >= 0) {
                musicGain[slot] = gain;
                commands.gain(slot, gain, seconds);
            }
            return;
        }
        for (int slot = 0; slot < slotTrack.length; slot++) {
            if (slotTrack[slot] == MUSIC && !stingerSlot[slot]) { // a stinger does not duck itself
                if (kind == Music.GAIN) {
                    commands.gain(slot, musicGain[slot] * gain, seconds);
                } else {
                    commands.pause(slot, kind == Music.PAUSE);
                }
            }
        }
    }

    /** Adds what the log no longer holds: every instance not followed yet. */
    @AllowAllocation("rebuilding after the log overflowed")
    private void rebuild(int s) {
        Sounds store = stores[s];
        for (int t = 0; t < tracks.length; t++) { // what ended meanwhile
            Track track = tracks[t];
            if (track.sound != null && track.store == s && !track.gone && !store.alive(track.id)) {
                changed(track.id, Sounds.REMOVED);
            }
        }
        store.forEach(id -> {
            if (tracked[s].get(id) < 0) {
                changed(id, Sounds.ADDED);
            }
        });
    }

    private void changed(long id, int changes) {
        int s = storeNow;
        Sounds store = stores[s];
        int t = tracked[s].get(id);
        if ((changes & Sounds.ADDED) != 0 && t < 0 && store.alive(id)) {
            t = track(s, id);
        }
        if (t < 0) {
            return;
        }
        Track track = tracks[t];
        if ((changes & Sounds.REMOVED) != 0) {
            track.gone = true;
            for (int v = 0; v < track.voices; v++) {
                if (track.state[v] == PLAYING) {
                    commands.kill(track.slots[v]);
                }
            }
            finishIfDone(t);
            return;
        }
        if (!store.alive(id)) {
            return;
        }
        if ((changes & Sounds.STOPPED) != 0) {
            stop(t);
        }
        if ((changes & Sounds.INPUT) != 0) {
            int l = renderIndex(s, store.input(id));
            for (int v = 0; v < track.voices; v++) {
                if (track.state[v] == PLAYING && store.voiceNode(id, v) instanceof Play play && play.isInput()) {
                    commands.input(track.slots[v], l, schedulePoint);
                }
            }
        }
        if ((changes & (Sounds.PARAMETERS | Sounds.TIME)) != 0) {
            for (int v = 0; v < track.voices; v++) {
                if (track.state[v] == PLAYING) {
                    commands.gain(track.slots[v], gain(track, v), Math.max(store.volumeRamp(id),
                        store.parameterRamp(id)));
                    commands.pitch(track.slots[v], pitch(track, v));
                    commands.pause(track.slots[v], store.holding(id));
                    if (slotPatch[track.slots[v]] != null) {
                        inputs(track, track.slots[v], schedulePoint, store.parameterRamp(id), false);
                    }
                }
            }
        }
    }

    /** Starts following instance {@code id} of store {@code s}: plans its voices and acquires their clips. */
    private int track(int s, long id) {
        int t = newTrack();
        Track track = tracks[t];
        Sounds store = stores[s];
        track.store = s;
        track.id = id;
        track.sound = store.sound(id);
        track.voices = store.voices(id);
        if (track.voices > track.slots.length) {
            track.grow(track.voices);
        }
        track.acquiredCount = 0;
        track.added = store.now();
        track.gone = false;
        track.stopped = false;
        track.placed = false;
        track.listener = 0;
        track.doppler = 1;
        track.occMid = 1;
        track.occHigh = 1;
        track.targetMid = 1;
        track.targetHigh = 1;
        track.sentGain = 1;
        track.sentCutoff = 0;
        track.sharesZone = true;
        track.occKnown = false;
        for (int v = 0; v < track.voices; v++) {
            track.slots[v] = -1;
            track.starts[v] = 0;
            track.rings[v] = null;
            AssetRef<AudioClip> clip = store.voiceClip(id, v);
            boolean procedural = store.voiceNode(id, v) instanceof Patch
                || store.voiceNode(id, v) instanceof Play play && play.isInput();
            track.state[v] = clip == null && !procedural ? DONE : WAITING; // scatters come with a later step
            if (clip != null && !acquired(track, clip)) {
                cache.acquire(clip);
                track.acquired[track.acquiredCount++] = clip;
            }
        }
        tracked[s].put(id, t);
        return t;
    }

    private static boolean acquired(Track track, AssetRef<AudioClip> clip) {
        for (int i = 0; i < track.acquiredCount; i++) {
            if (track.acquired[i].equals(clip)) {
                return true;
            }
        }
        return false;
    }

    @AllowAllocation("growing with the sounds playing")
    private int newTrack() {
        if (freeTrackCount > 0) {
            return freeTracks[--freeTrackCount];
        }
        int n = tracks.length;
        tracks = Arrays.copyOf(tracks, Math.max(16, 2 * n));
        freeTracks = Arrays.copyOf(freeTracks, tracks.length);
        for (int i = tracks.length - 1; i > n; i--) {
            freeTracks[freeTrackCount++] = i;
        }
        for (int i = n; i < tracks.length; i++) {
            tracks[i] = new Track();
        }
        return n;
    }

    @SuppressWarnings("unchecked")
    private void free(int t) {
        Track track = tracks[t];
        for (int i = 0; i < track.acquiredCount; i++) {
            cache.release((AssetRef<AudioClip>) track.acquired[i]);
            track.acquired[i] = null;
        }
        for (int v = 0; v < track.rings.length; v++) {
            if (track.rings[v] != null) {
                track.rings[v].close(); // a ring never played (dropped): the streaming thread lets it go
                track.rings[v] = null;
            }
        }
        tracked[track.store].remove(track.id);
        track.sound = null;
        track.voices = 0;
        freeTracks[freeTrackCount++] = t;
    }

    /**
     * Starts the voices of track {@code t} whose clips are loaded and whose turn it is; drops it if too late; ends it
     * when none of its voices plays or waits (their clips failed to load).
     */
    private void start(int t) {
        Track track = tracks[t];
        Sounds store = stores[track.store];
        long id = track.id;
        boolean waiting = false;
        boolean anyStarted = false;
        for (int v = 0; v < track.voices; v++) {
            anyStarted |= track.starts[v] != 0;
            if (track.state[v] != WAITING || store.voiceOutro(id, v) || track.stopped) {
                continue;
            }
            if (store.voiceNode(id, v) instanceof Patch patch) {
                waiting |= !startPatch(t, v, patch);
                anyStarted |= track.state[v] == PLAYING;
                continue;
            }
            if (store.voiceNode(id, v) instanceof Play play && play.isInput()) {
                waiting |= !startInput(t, v);
                anyStarted |= track.state[v] == PLAYING;
                continue;
            }
            AssetRef<AudioClip> ref = store.voiceClip(id, v);
            AssetState state = cache.state(ref);
            if (state == AssetState.FAILED) {
                track.state[v] = DONE;
                continue;
            }
            if (state != AssetState.LOADED) {
                waiting = true;
                continue;
            }
            int follows = store.voiceFollows(id, v);
            long at;
            if (follows >= 0) {
                if (track.starts[follows] == 0) { // the voice before has not started: this one waits for it
                    waiting = true;
                    continue;
                }
                AudioClip before = cache.get(store.voiceClip(id, follows));
                at = track.starts[follows] + Math.round(before.seconds() * rate / Math.max(1e-3, pitch(track, follows)))
                    + Math.round(store.voiceDelay(id, v) * rate);
            } else {
                at = schedulePoint + Math.round(store.voiceDelay(id, v) * rate);
            }
            AudioClip clip = cache.get(ref);
            if (clip.streamed() && !stream(track, v, clip, store.voiceLoops(id, v))) {
                waiting = true; // priming: its ring fills first
                continue;
            }
            play(t, v, clip, at);
            anyStarted = true;
        }
        if (waiting && !anyStarted
            && store.now() - track.added > Math.round(track.sound.maxStartDelay() * Sounds.TICKS_PER_SECOND)) {
            dropped++;
            store.drop(id); // its REMOVED frees the track next frame
            track.gone = true;
            for (int v = 0; v < track.voices; v++) {
                track.state[v] = DONE;
            }
            finishIfDone(t);
        } else if (!waiting && !playing(track)) {
            finishIfDone(t);
        }
    }

    private static boolean playing(Track track) {
        for (int v = 0; v < track.voices; v++) {
            if (track.state[v] == PLAYING) {
                return true;
            }
        }
        return false;
    }

    /**
     * Readies a streamed voice: its ring made and handed to the streaming thread on first sight; true once primed,
     * after sending it for the next play.
     */
    private boolean stream(Track track, int v, AudioClip clip, boolean loop) {
        if (track.rings[v] == null) {
            track.rings[v] = newRing(clip, loop);
            streams().add(track.rings[v]);
        }
        if (!track.rings[v].primed()) {
            return false;
        }
        return commands.stream(track.rings[v]);
    }

    @AllowAllocation("a ring per streamed voice")
    private static StreamRing newRing(AudioClip clip, boolean loop) {
        return new StreamRing(clip, loop, 1.0);
    }

    private Streams streams;

    /** The streaming thread streamed clips decode on; made on the first. */
    @AllowAllocation("made once")
    public Streams streams() {
        if (streams == null) {
            streams = new Streams();
        }
        return streams;
    }

    /** Starts a patch voice: one from its pool; false while no slot is free (it waits); unregistered, it is done. */
    private boolean startPatch(int t, int v, Patch patch) {
        Track track = tracks[t];
        PatchVoice voice = patches == null ? null : patches.take(patch.name());
        if (voice == null) {
            track.state[v] = DONE; // no such patch registered: silent, as a clip that failed to load
            return true;
        }
        if (firstFree() < 0 || !commands.patch(voice)) {
            patches.give(voice);
            return false;
        }
        long at = schedulePoint + Math.round(stores[track.store].voiceDelay(track.id, v) * rate);
        play(t, v, null, at);
        if (track.state[v] != PLAYING) {
            patches.give(voice); // the ring was full; tried again next frame
            return false;
        }
        int slot = track.slots[v];
        slotPatch[slot] = voice;
        inputs(track, slot, at, 0, true);
        return true;
    }

    /**
     * Sends the patch in {@code slot} the inputs of its instance that changed since last sent, at {@code frame}, over
     * {@code seconds} (at least the patch's 5 ms); a trigger whose count changed fires (at the start only recorded).
     */
    private void inputs(Track track, int slot, long frame, double seconds, boolean starting) {
        PatchVoice voice = slotPatch[slot];
        Sounds store = stores[track.store];
        for (int i = 0; i < voice.patch().inputs().size(); i++) {
            int index = track.sound.parameterIndex(voice.patch().inputs().get(i));
            if (index < 0) {
                continue;
            }
            float value = store.parameter(track.id, index);
            if (value == voice.sent(i)) {
                continue;
            }
            voice.sent(i, value);
            boolean trigger = voice.patch().trigger(i);
            if (!(trigger && starting)) {
                commands.patchInput(slot, i, value, starting ? 0 : Math.max(PatchVoice.DEFAULT_RAMP, seconds), trigger,
                    frame);
            }
        }
    }

    private void play(int t, int v, AudioClip clip, long at) {
        Track track = tracks[t];
        int slot = firstFree();
        if (slot < 0) {
            return; // every slot taken: it waits
        }
        Sounds store = stores[track.store];
        int flags = (store.voiceLoops(track.id, v) ? Voices.LOOP : 0) | (placed(track) ? Voices.SPATIAL : 0)
            | track.sound.priority().ordinal() << Voices.PRIORITY_SHIFT
            | track.sound.virtualization().ordinal() << Voices.VIRTUAL_SHIFT;
        Bus bus = store.isUi() ? Bus.UI : track.sound.bus();
        // the clip's gain to its target loudness (measured at import) rides on the voice's
        float clipGain = clip == null ? 1 : clip.gain();
        for (int l = 0; l < Voices.LISTENERS; l++) {
            heardSent[2 * (l * slotTrack.length + slot)] = 0;
            heardSent[2 * (l * slotTrack.length + slot) + 1] = 0;
        }
        if (!commands.play(slot, clip, at, 0, gain(track, v) * clipGain, pitch(track, v), bus, flags, 0, track.id)) {
            return; // the ring is full: tried again next frame
        }
        if (store.holding(track.id)) {
            commands.pause(slot, true);
        }
        slotTrack[slot] = t;
        slotVoice[slot] = v;
        slotsUsed++;
        track.slots[v] = slot;
        track.starts[v] = at;
        track.state[v] = PLAYING;
        if (placed(track)) {
            track.placed = false; // placed at once, the others again with it
            storeNow = track.store;
            place(t);
        }
    }

    private int firstFree() {
        for (int slot = 0; slot < slotTrack.length; slot++) {
            if (slotTrack[slot] == -1) { // -1 free; music's are -2
                return slot;
            }
        }
        return -1;
    }

    /** Stops track {@code t}: its outro now if it has one (and no fade was asked), else a fade of what plays. */
    private void stop(int t) {
        Track track = tracks[t];
        Sounds store = stores[track.store];
        track.stopped = true;
        float fade = store.stopFade(track.id);
        boolean outro = false;
        for (int v = 0; v < track.voices; v++) {
            outro |= store.voiceOutro(track.id, v);
        }
        // where the outro starts (§2.7): now, cross-fading the loop, or at the loop's next pass end or marker
        long at = schedulePoint;
        float loopFade = Math.max(fade, 0.03f);
        for (int v = 0; v < track.voices && outro; v++) {
            Release release = store.voiceRelease(track.id, v);
            if (release != null && track.state[v] == PLAYING) {
                if (release.kind() == Release.Kind.CROSSFADE) {
                    loopFade = (float) release.seconds();
                } else {
                    at = boundary(track, v, release.kind() == Release.Kind.AT_MARKER);
                    loopFade = 0;
                }
            }
        }
        for (int v = 0; v < track.voices; v++) {
            if (store.voiceOutro(track.id, v)) {
                AssetRef<AudioClip> ref = store.voiceClip(track.id, v);
                if (cache.state(ref) == AssetState.LOADED) {
                    play(t, v, cache.get(ref), at);
                } else {
                    track.state[v] = DONE;
                }
            } else if (track.state[v] == PLAYING) {
                boolean looping = store.voiceRelease(track.id, v) != null;
                commands.stop(track.slots[v], looping ? at : schedulePoint, outro ? loopFade : fade);
            } else if (track.state[v] == WAITING) {
                track.state[v] = DONE;
            }
        }
        finishIfDone(t);
    }

    /**
     * The output frame at or after the schedule point where loop voice {@code v} next ends a pass of its loop region,
     * or with {@code marker} passes the region's next marker (its pass end without one): from its start frame and its
     * rate at the clip's frames, so the outro follows it seamlessly.
     */
    private long boundary(Track track, int v, boolean marker) {
        Sounds store = stores[track.store];
        AudioClip clip = cache.get(store.voiceClip(track.id, v));
        double outPerClip = (double) rate / clip.rate() / pitch(track, v);
        long start = clip.loopStart();
        long end = clip.loopEnd();
        long length = end - start;
        double now = Math.max(0, (schedulePoint - track.starts[v]) / outPerClip); // clip frames played (unwrapped)
        // pass k ends at clip frame end + k * length (unwrapped); a marker m at m + k * length
        long best = Long.MAX_VALUE;
        for (int k = (int) Math.max(0, Math.floor((now - end) / length)); best == Long.MAX_VALUE; k++) {
            long passEnd = end + (long) k * length;
            if (marker) {
                for (int i = 0; i < clip.markers().size() && best == Long.MAX_VALUE; i++) {
                    long m = clip.markers().get(i).frame();
                    long at = m + (long) k * length;
                    if (m >= start && m < end && at >= now) {
                        best = at;
                    }
                }
            }
            if (best == Long.MAX_VALUE && passEnd >= now) {
                best = passEnd;
            }
        }
        return track.starts[v] + Math.round(best * outPerClip);
    }

    /** Voice {@code v}'s gain: drawn and by parameters, times the instance's volume (distance is the renderer's). */
    private float gain(Track track, int v) {
        Sounds store = stores[track.store];
        return store.voiceGain(track.id, v) * Decibels.gain(store.volume(track.id));
    }

    /** Voice {@code v}'s pitch: drawn and by parameters, the instance's, and its time scale (0 while holding: 1). */
    private float pitch(Track track, int v) {
        Sounds store = stores[track.store];
        float rateNow = store.rate(track.id);
        return store.voiceRate(track.id, v) * store.pitch(track.id) * (rateNow > 0 ? rateNow : 1);
    }

    private boolean placed(Track track) {
        return !stores[track.store].isUi() && track.sound.spatial() != Spatial.TWO_D;
    }

    // ---------------------------------------------------------------- positions

    /** The aux bus reverb sends go to, when the layout has it. */
    public static final Bus REVERB = Bus.aux("reverb");

    /** Speed of sound for Doppler, metres a second. */
    static final double SOUND_SPEED = 343;
    /** A jump of the relative position larger than this in a frame is a teleport: no Doppler from it. */
    static final double TELEPORT = 50;

    /** The store's device listeners: id, then position (3) and rotation (4), by store; grown as needed. */
    private long[][] deviceIds = new long[0][];
    private double[][] devices = new double[0][];
    private int[] deviceCounts = new int[0];
    private final Listeners.Visitor collect = this::collect;

    private float sentDecay = Float.NaN;
    private float sentDamping = Float.NaN;

    /**
     * Blends the reverb of the zone the first device listener of store {@code s} is in over the layout's own reverb
     * by the zone's weight there (§2.11), sending it when it moved by more than 1 %.
     */
    private void listenerZone(int s) {
        Sounds store = stores[s];
        if (store.isUi() || deviceCounts[s] == 0 || !commands.layout().contains(REVERB)) {
            return;
        }
        AudioEffect base = null;
        java.util.List<AudioEffect> chain = commands.layout().buses().get(commands.layout().indexOf(REVERB)).effects();
        for (int i = 0; i < chain.size(); i++) {
            if (chain.get(i).name().equals("reverb")) {
                base = chain.get(i);
            }
        }
        if (base == null) {
            return;
        }
        double[] d = devices[s];
        ReverbZones zones = store.reverbZones();
        long zone = zones.size() == 0 ? 0 : zones.strongest(d[0], d[1], d[2]);
        float w = zone == 0 ? 0 : zones.weight(zone, d[0], d[1], d[2]);
        float decay = Reverb.decayOf(base);
        float damping = Reverb.dampingOf(base);
        if (zone != 0) {
            AudioEffect r = zones.reverb(zone);
            decay += (Reverb.decayOf(r) - decay) * w;
            damping += (Reverb.dampingOf(r) - damping) * w;
        }
        if (!(Math.abs(decay - sentDecay) <= 0.01f * decay) || !(Math.abs(damping - sentDamping) <= 0.01f)) {
            sentDecay = decay;
            sentDamping = damping;
            commands.reverb(REVERB, decay, damping);
        }
    }

    /** Collects store {@code s}'s device listeners and where they are, as of its last advance. */
    private void listeners(int s) {
        if (deviceIds.length <= s) {
            growListeners(s + 1);
        }
        deviceCounts[s] = 0;
        if (!stores[s].isUi()) {
            stores[s].listeners().forEach(collect);
        }
    }

    @AllowAllocation("a store or listener more than before")
    private void growListeners(int stores) {
        int n = deviceIds.length;
        deviceIds = Arrays.copyOf(deviceIds, stores);
        devices = Arrays.copyOf(devices, stores);
        deviceCounts = Arrays.copyOf(deviceCounts, stores);
        for (int s = n; s < stores; s++) {
            deviceIds[s] = new long[4];
            devices[s] = new double[28];
        }
    }

    @AllowAllocation("a listener more than before")
    private void growDevices(int s) {
        deviceIds[s] = Arrays.copyOf(deviceIds[s], 2 * deviceIds[s].length);
        devices[s] = Arrays.copyOf(devices[s], 2 * devices[s].length);
    }

    private void collect(long listener) {
        int s = storeNow;
        Listeners listeners = stores[s].listeners();
        if (listeners.rendering(listener)) {
            rendering(s, listener, listeners);
            return;
        }
        int n = deviceCounts[s];
        if (n == deviceIds[s].length) {
            growDevices(s);
        }
        deviceIds[s][n] = listener;
        double[] d = devices[s];
        for (int i = 0; i < 3; i++) {
            d[7 * n + i] = listeners.position(listener, i);
        }
        for (int i = 0; i < 4; i++) {
            d[7 * n + 3 + i] = listeners.rotation(listener, i);
        }
        deviceCounts[s] = n + 1;
    }

    private final double[] relative = new double[3];

    /**
     * Where track {@code track}'s instance is relative to device listener {@code n} of its store, into
     * {@link #relative}: in doubles until here, with the store's listener at the origin unrotated when it has none.
     */
    private void relative(Track track, int n) {
        relativeTo(track, n < 0 ? null : devices[track.store], 7 * n);
    }

    /** As {@link #relative}, for the listener pose at {@code d[o]} (position, then rotation x, y, z, w); null: none. */
    private void relativeTo(Track track, double[] d, int o) {
        Sounds store = stores[track.store];
        boolean none = d == null;
        double dx = store.position(track.id, 0) - (none ? 0 : d[o]);
        double dy = store.position(track.id, 1) - (none ? 0 : d[o + 1]);
        double dz = store.position(track.id, 2) - (none ? 0 : d[o + 2]);
        // by the listener's inverse rotation: v + 2w (q x v) + 2 q x (q x v), q conjugated
        double qx = none ? 0 : -d[o + 3];
        double qy = none ? 0 : -d[o + 4];
        double qz = none ? 0 : -d[o + 5];
        double qw = none ? 1 : d[o + 6];
        double tx = 2 * (qy * dz - qz * dy);
        double ty = 2 * (qz * dx - qx * dz);
        double tz = 2 * (qx * dy - qy * dx);
        relative[0] = dx + qw * tx + qy * tz - qz * ty;
        relative[1] = dy + qw * ty + qz * tx - qx * tz;
        relative[2] = dz + qw * tz + qx * ty - qy * tx;
    }

    /**
     * The device listener track {@code track} is heard from (§2.10, split screen): its nearest, keeping the one it has
     * unless another is more than 10 % nearer. -1 when the store has none.
     */
    private int nearest(Track track) {
        int count = deviceCounts[track.store];
        if (count == 0) {
            return -1;
        }
        Sounds store = stores[track.store];
        double[] d = devices[track.store];
        int best = -1;
        double bestDistance = Double.POSITIVE_INFINITY;
        double current = Double.POSITIVE_INFINITY;
        int at = -1;
        for (int n = 0; n < count; n++) {
            double dx = store.position(track.id, 0) - d[7 * n];
            double dy = store.position(track.id, 1) - d[7 * n + 1];
            double dz = store.position(track.id, 2) - d[7 * n + 2];
            double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (distance < bestDistance) {
                best = n;
                bestDistance = distance;
            }
            if (deviceIds[track.store][n] == track.listener) {
                at = n;
                current = distance;
            }
        }
        return at >= 0 && bestDistance >= current * 0.9 ? at : best;
    }

    /**
     * Places track {@code t}'s playing voices for where its instance is now: listener-relative position (doubles until
     * then), the Doppler ratio from the change of its distance over the frame (relative positions, so float
     * cancellation never happens; none after a teleport or a change of listener), and the attenuation, for the
     * renderer to spatialize. A change of listener cross-fades over 50 ms.
     */
    private void place(int t) {
        Track track = tracks[t];
        Sounds store = stores[track.store];
        int n = nearest(track);
        long listener = n < 0 ? 0 : deviceIds[track.store][n];
        boolean changed = track.placed && listener != track.listener;
        relative(track, n);
        double x = relative[0];
        double y = relative[1];
        double z = relative[2];
        double distance = Math.sqrt(x * x + y * y + z * z);
        double dt = (double) (store.now() - track.placedAt) / Sounds.TICKS_PER_SECOND;
        double jump = Math.sqrt((x - track.previous[0]) * (x - track.previous[0])
            + (y - track.previous[1]) * (y - track.previous[1]) + (z - track.previous[2]) * (z - track.previous[2]));
        float doppler = 1;
        if (track.placed && !changed && dt > 0 && jump < TELEPORT && track.sound.doppler() > 0) {
            double radial = (distance - track.previousDistance) / dt; // receding: positive
            double ratio = Math.max(0.5, Math.min(2, SOUND_SPEED / (SOUND_SPEED + radial)));
            doppler = (float) (1 + (ratio - 1) * track.sound.doppler());
            track.doppler = doppler;
        } else if (track.placed && dt <= 0) {
            doppler = track.doppler; // no time passed: the same as last frame
        }
        if (dt > 0 || !track.placed) {
            track.previous[0] = x;
            track.previous[1] = y;
            track.previous[2] = z;
            track.previousDistance = distance;
            track.placedAt = store.now();
        }
        track.placed = true;
        track.listener = listener;
        Attenuation attenuation = track.sound.attenuation();
        // the reverb send (§2.11): the attenuation's by distance, times how much the emitter is in a zone (all of it
        // where the world has none)
        boolean reverb = commands.layout().contains(REVERB);
        float send = 0;
        if (reverb) {
            ReverbZones zones = store.reverbZones();
            double ex = store.position(track.id, 0);
            double ey = store.position(track.id, 1);
            double ez = store.position(track.id, 2);
            long zone = zones.size() == 0 ? 0 : zones.strongest(ex, ey, ez);
            float inZone = zones.size() == 0 ? 1 : zone == 0 ? 0 : zones.weight(zone, ex, ey, ez);
            send = attenuation.reverbSend((float) distance) * inZone;
            // occlusion lowers the reverb too; obstruction (listener and emitter in one zone) only the direct path
            track.sharesZone = zone == 0 || zone == listenerZoneOf(track.store, track.listener);
            send *= track.sharesZone ? 1 : track.occMid;
        }
        for (int v = 0; v < track.voices; v++) {
            if (track.state[v] == PLAYING) {
                commands.spatial(track.slots[v], (float) x, (float) y, (float) z, doppler, attenuation, changed);
                if (reverb) {
                    commands.send(track.slots[v], REVERB, send);
                }
            }
        }
    }
}
