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
            listeners(s);
            long now = store.clock().now();
            if (!store.changesSince(marks[s], changes)) {
                rebuild(s);
            }
            marks[s] = now;
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
        track.placed = false;
        track.listener = 0;
        track.doppler = 1;
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
        int flags = (store.voiceLoops(track.id, v) ? Voices.LOOP : 0) | (placed(track) ? Voices.SPATIAL : 0)
            | track.sound.priority().ordinal() << Voices.PRIORITY_SHIFT
            | track.sound.virtualization().ordinal() << Voices.VIRTUAL_SHIFT;
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
        if (placed(track)) {
            track.placed = false; // placed at once, the others again with it
            storeNow = track.store;
            place(t);
        }
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

    /** Speed of sound for Doppler, metres a second. */
    static final double SOUND_SPEED = 343;
    /** A jump of the relative position larger than this in a frame is a teleport: no Doppler from it. */
    static final double TELEPORT = 50;

    /** The store's device listeners: id, then position (3) and rotation (4), by store; grown as needed. */
    private long[][] deviceIds = new long[0][];
    private double[][] devices = new double[0][];
    private int[] deviceCounts = new int[0];
    private final Listeners.Visitor collect = this::collect;

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
        Sounds store = stores[track.store];
        double[] d = devices[track.store];
        boolean none = n < 0;
        double dx = store.position(track.id, 0) - (none ? 0 : d[7 * n]);
        double dy = store.position(track.id, 1) - (none ? 0 : d[7 * n + 1]);
        double dz = store.position(track.id, 2) - (none ? 0 : d[7 * n + 2]);
        // by the listener's inverse rotation: v + 2w (q x v) + 2 q x (q x v), q conjugated
        double qx = none ? 0 : -d[7 * n + 3];
        double qy = none ? 0 : -d[7 * n + 4];
        double qz = none ? 0 : -d[7 * n + 5];
        double qw = none ? 1 : d[7 * n + 6];
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
        for (int v = 0; v < track.voices; v++) {
            if (track.state[v] == PLAYING) {
                commands.spatial(track.slots[v], (float) x, (float) y, (float) z, doppler, attenuation, changed);
            }
        }
    }
}
