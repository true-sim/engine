package com.truesim.engine.audio;

import com.truesim.annotations.AllowAllocation;
import com.truesim.scene.assets.AssetCache;
import com.truesim.scene.assets.AssetRef;
import com.truesim.scene.assets.AssetState;
import com.truesim.scene.audio.AudioClip;
import com.truesim.scene.audio.Attenuation;
import com.truesim.scene.audio.Bus;
import com.truesim.scene.audio.Decibels;
import com.truesim.scene.audio.Listeners;
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
 * <p>Voices take renderer slots first fit, so an offline render of the same calls is the same. Positions: the
 * listener is the store's first device listener (split screen's nearest-listener rule comes with 3D, §6 step 7); its
 * world position and rotation and the instance's are doubles, and only the listener-relative position becomes floats,
 * so a sound 7,000 km out pans as one at the origin. Allocation-free once grown.
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
    private final double[] listener = new double[7];

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
        int acquiredCount;
        long added;
        boolean gone;
        boolean stopped;

        @AllowAllocation("a sound with more voices than any before")
        void grow(int n) {
            int size = Math.max(n, 2 * slots.length);
            slots = Arrays.copyOf(slots, size);
            starts = Arrays.copyOf(starts, size);
            state = Arrays.copyOf(state, size);
            acquired = Arrays.copyOf(acquired, size);
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

    /** Takes the renderer's events: ended voices free their slots; instances whose voices all ended end. */
    public void events() {
        renderer.poll(endings);
    }

    private void ended(int kind, int slot, long tag, long frame) {
        int t = slotTrack[slot];
        if (kind != AudioRenderer.ENDED || t < 0) {
            return;
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
            long now = store.clock().now();
            if (!store.changesSince(marks[s], changes)) {
                rebuild(s);
            }
            marks[s] = now;
            listener(store);
        }
        for (int t = 0; t < tracks.length; t++) {
            Track track = tracks[t];
            if (track != null && track.sound != null && !track.gone) {
                start(t);
                if (track.sound != null && placed(track)) {
                    place(t);
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
        if ((changes & (Sounds.PARAMETERS | Sounds.TIME)) != 0) {
            for (int v = 0; v < track.voices; v++) {
                if (track.state[v] == PLAYING) {
                    commands.gain(track.slots[v], gain(track, v), Math.max(store.volumeRamp(id),
                        store.parameterRamp(id)));
                    commands.pitch(track.slots[v], pitch(track, v));
                    commands.pause(track.slots[v], store.holding(id));
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
        for (int v = 0; v < track.voices; v++) {
            track.slots[v] = -1;
            track.starts[v] = 0;
            AssetRef<AudioClip> clip = store.voiceClip(id, v);
            track.state[v] = clip == null ? DONE : WAITING; // inputs, patches and scatters come with later steps
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
            play(t, v, cache.get(ref), at);
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

    private void play(int t, int v, AudioClip clip, long at) {
        Track track = tracks[t];
        int slot = firstFree();
        if (slot < 0) {
            return; // every slot taken: it waits (logical voices beyond the renderer's are for step 7)
        }
        Sounds store = stores[track.store];
        int flags = (store.voiceLoops(track.id, v) ? Voices.LOOP : 0)
            | (placed(track) ? Voices.SPATIAL : 0);
        Bus bus = store.isUi() ? Bus.UI : track.sound.bus();
        if (!commands.play(slot, clip, at, 0, gain(track, v), pitch(track, v), bus, flags, 0, track.id)) {
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
    }

    private int firstFree() {
        for (int slot = 0; slot < slotTrack.length; slot++) {
            if (slotTrack[slot] < 0) {
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
        for (int v = 0; v < track.voices; v++) {
            if (store.voiceOutro(track.id, v)) {
                AssetRef<AudioClip> ref = store.voiceClip(track.id, v);
                if (cache.state(ref) == AssetState.LOADED) {
                    play(t, v, cache.get(ref), schedulePoint);
                } else {
                    track.state[v] = DONE;
                }
            } else if (track.state[v] == PLAYING) {
                commands.stop(track.slots[v], schedulePoint, outro ? Math.max(fade, 0.03) : fade);
            } else if (track.state[v] == WAITING) {
                track.state[v] = DONE;
            }
        }
        finishIfDone(t);
    }

    /** Voice {@code v}'s gain: drawn and by parameters, times the instance's volume. */
    private float gain(Track track, int v) {
        Sounds store = stores[track.store];
        return store.voiceGain(track.id, v) * Decibels.gain(store.volume(track.id)) * distanceGain(track);
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

    /** The store's listener: its first device listener's position and rotation, or the origin unrotated. */
    private void listener(Sounds store) {
        Arrays.fill(listener, 0);
        listener[6] = 1;
        if (store.isUi()) {
            return;
        }
        Listeners listeners = store.listeners();
        listenerFirst = 0;
        listeners.forEach(firstDevice);
        if (listenerFirst != 0) {
            for (int i = 0; i < 3; i++) {
                listener[i] = listeners.position(listenerFirst, i);
            }
            for (int i = 0; i < 4; i++) {
                listener[3 + i] = listeners.rotation(listenerFirst, i);
            }
        }
    }

    private long listenerFirst;
    private final Listeners.Visitor firstDevice = this::firstDevice;

    private void firstDevice(long listenerId) {
        if (listenerFirst == 0 && !stores[storeNow].listeners().rendering(listenerId)) {
            listenerFirst = listenerId;
        }
    }

    private final double[] relative = new double[3];

    /** Where track {@code t}'s instance is relative to its store's listener, into {@link #relative}: doubles. */
    private void relative(Track track) {
        Sounds store = stores[track.store];
        double dx = store.position(track.id, 0) - listener[0];
        double dy = store.position(track.id, 1) - listener[1];
        double dz = store.position(track.id, 2) - listener[2];
        // by the listener's inverse rotation: v + 2w (q x v) + 2 q x (q x v), q conjugated
        double qx = -listener[3];
        double qy = -listener[4];
        double qz = -listener[5];
        double qw = listener[6];
        double tx = 2 * (qy * dz - qz * dy);
        double ty = 2 * (qz * dx - qx * dz);
        double tz = 2 * (qx * dy - qy * dx);
        relative[0] = dx + qw * tx + qy * tz - qz * ty;
        relative[1] = dy + qw * ty + qz * tx - qx * tz;
        relative[2] = dz + qw * tz + qx * ty - qy * tx;
    }

    /** The distance gain of a placed track now; 1 for 2D. */
    private float distanceGain(Track track) {
        if (!placed(track)) {
            return 1;
        }
        relative(track);
        double d = Math.sqrt(relative[0] * relative[0] + relative[1] * relative[1] + relative[2] * relative[2]);
        Attenuation a = track.sound.attenuation();
        return a.gain((float) d);
    }

    /** Pans and attenuates track {@code t}'s playing voices for where it is now. */
    private void place(int t) {
        Track track = tracks[t];
        relative(track);
        double x = relative[0];
        double z = relative[2];
        double horizontal = Math.sqrt(x * x + z * z);
        float pan = horizontal < 1e-6 ? 0 : (float) (x / horizontal); // right of the listener: -z is ahead
        for (int v = 0; v < track.voices; v++) {
            if (track.state[v] == PLAYING) {
                commands.pan(track.slots[v], pan);
                commands.gain(track.slots[v], gain(track, v), 1.0 / 60);
            }
        }
    }
}
