package com.truesim.engine.platform;

import static org.lwjgl.sdl.SDLError.SDL_GetError;
import static org.lwjgl.sdl.SDLHaptic.SDL_CloseHaptic;
import static org.lwjgl.sdl.SDLHaptic.SDL_DestroyHapticEffect;
import static org.lwjgl.sdl.SDLHaptic.SDL_GetHapticEffectStatus;
import static org.lwjgl.sdl.SDLHaptic.SDL_GetHapticFeatures;
import static org.lwjgl.sdl.SDLHaptic.SDL_GetHapticID;
import static org.lwjgl.sdl.SDLHaptic.SDL_GetHapticName;
import static org.lwjgl.sdl.SDLHaptic.SDL_GetMaxHapticEffects;
import static org.lwjgl.sdl.SDLHaptic.SDL_GetMaxHapticEffectsPlaying;
import static org.lwjgl.sdl.SDLHaptic.SDL_GetNumHapticAxes;
import static org.lwjgl.sdl.SDLHaptic.SDL_HapticRumbleSupported;
import static org.lwjgl.sdl.SDLHaptic.SDL_InitHapticRumble;
import static org.lwjgl.sdl.SDLHaptic.SDL_PauseHaptic;
import static org.lwjgl.sdl.SDLHaptic.SDL_PlayHapticRumble;
import static org.lwjgl.sdl.SDLHaptic.SDL_ResumeHaptic;
import static org.lwjgl.sdl.SDLHaptic.SDL_RunHapticEffect;
import static org.lwjgl.sdl.SDLHaptic.SDL_SetHapticAutocenter;
import static org.lwjgl.sdl.SDLHaptic.SDL_SetHapticGain;
import static org.lwjgl.sdl.SDLHaptic.SDL_StopHapticEffect;
import static org.lwjgl.sdl.SDLHaptic.SDL_StopHapticEffects;
import static org.lwjgl.sdl.SDLHaptic.SDL_StopHapticRumble;
import static org.lwjgl.sdl.SDLHaptic.nSDL_CreateHapticEffect;
import static org.lwjgl.sdl.SDLHaptic.nSDL_HapticEffectSupported;
import static org.lwjgl.sdl.SDLHaptic.nSDL_UpdateHapticEffect;
import static org.lwjgl.system.MemoryUtil.nmemFree;

import com.truesim.annotations.AllowAllocation;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.lwjgl.sdl.SDL_HapticEffect;
import org.lwjgl.system.MemoryStack;

/**
 * A force feedback device: a wheel's or joystick's ({@link Controller#haptic()}), the mouse's
 * ({@link Platform#mouseHaptic()}) or a standalone one ({@link Platform#openHaptic}). Upload {@link HapticEffect}s
 * and play them by id, or use the simple {@link #rumble}. Use it on the platform's thread.
 *
 * <pre>{@code
 * Haptic wheel = platform.joystick(id).haptic();
 * int centering = wheel.create(new HapticEffect.Condition(ConditionKind.SPRING, Timing.of(HapticEffect.INFINITE),
 *     List.of(new ConditionAxis(1, 1, 0.5f, 0.5f, 0, 0))));
 * wheel.run(centering, 1);
 * }</pre>
 */
public final class Haptic implements AutoCloseable {

    private final Platform platform;
    private final long handle;
    private final Set<HapticFeature> features;
    private final Map<Integer, Long> customData = new HashMap<>();
    private boolean rumbleReady;
    private boolean closed;

    @AllowAllocation("opened once")
    Haptic(Platform platform, long handle) {
        this.platform = platform;
        this.handle = handle;
        this.features = Set.copyOf(Sdl.hapticFeatures(SDL_GetHapticFeatures(handle)));
    }

    public int id() {
        check();
        return SDL_GetHapticID(handle);
    }

    @AllowAllocation("a name is a string")
    public String name() {
        check();
        return Controller.orEmpty(SDL_GetHapticName(handle));
    }

    /** The effects and controls the device supports. */
    public Set<HapticFeature> features() {
        return features;
    }

    public boolean supports(HapticFeature feature) {
        return features.contains(feature);
    }

    /** Whether the device can play {@code effect} as given. */
    public boolean supports(HapticEffect effect) {
        check();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long address = stack.ncalloc(SDL_HapticEffect.ALIGNOF, 1, SDL_HapticEffect.SIZEOF);
            long data = Sdl.writeEffect(effect, address);
            try {
                return nSDL_HapticEffectSupported(handle, address);
            } finally {
                nmemFree(data);
            }
        }
    }

    /** How many effects can be uploaded at once. */
    public int maxEffects() {
        check();
        return SDL_GetMaxHapticEffects(handle);
    }

    /** How many effects can play at once. */
    public int maxPlaying() {
        check();
        return SDL_GetMaxHapticEffectsPlaying(handle);
    }

    /** How many axes effects can act on, e.g. 1 for a wheel. */
    public int axisCount() {
        check();
        return SDL_GetNumHapticAxes(handle);
    }

    // ---------------------------------------------------------------- device controls

    /** Scales all effects, 0 to 1; needs {@link HapticFeature#GAIN}. */
    public boolean setGain(float gain) {
        check();
        return SDL_SetHapticGain(handle, percent(gain));
    }

    /** The device's own centering force, 0 (off) to 1; needs {@link HapticFeature#AUTOCENTER}. */
    public boolean setAutocenter(float strength) {
        check();
        return SDL_SetHapticAutocenter(handle, percent(strength));
    }

    /** Pauses all effects, e.g. while the game is paused; needs {@link HapticFeature#PAUSE}. */
    public boolean pause() {
        check();
        return SDL_PauseHaptic(handle);
    }

    public boolean resume() {
        check();
        return SDL_ResumeHaptic(handle);
    }

    /** Stops every effect. */
    public boolean stopAll() {
        check();
        return SDL_StopHapticEffects(handle);
    }

    // ---------------------------------------------------------------- simple rumble

    /** Whether {@link #rumble} works on this device. */
    public boolean canRumble() {
        check();
        return SDL_HapticRumbleSupported(handle);
    }

    /** Rumbles with {@code strength} (0 to 1) for {@code millis} ms. */
    public boolean rumble(float strength, int millis) {
        check();
        if (!rumbleReady) {
            rumbleReady = SDL_InitHapticRumble(handle);
            if (!rumbleReady) {
                return false;
            }
        }
        return SDL_PlayHapticRumble(handle, Math.clamp(strength, 0f, 1f), Sdl.length(millis));
    }

    public boolean stopRumble() {
        check();
        return rumbleReady && SDL_StopHapticRumble(handle);
    }

    // ---------------------------------------------------------------- effects

    /** Uploads {@code effect} and returns its id; throws when the device cannot play it or is full. */
    @AllowAllocation("uploading an effect")
    public int create(HapticEffect effect) {
        check();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long address = stack.ncalloc(SDL_HapticEffect.ALIGNOF, 1, SDL_HapticEffect.SIZEOF);
            long data = Sdl.writeEffect(effect, address);
            int id = nSDL_CreateHapticEffect(handle, address);
            if (id < 0) {
                nmemFree(data);
                throw new PlatformException("Could not create " + effect.feature() + " effect: " + SDL_GetError());
            }
            keep(id, data);
            return id;
        }
    }

    /**
     * Changes an uploaded effect, e.g. a force that follows the simulation, while it plays; the kind must stay the
     * same. Returns false when the device refused.
     */
    @AllowAllocation("custom effects keep their samples")
    public boolean update(int effect, HapticEffect changed) {
        check();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long address = stack.ncalloc(SDL_HapticEffect.ALIGNOF, 1, SDL_HapticEffect.SIZEOF);
            long data = Sdl.writeEffect(changed, address);
            if (!nSDL_UpdateHapticEffect(handle, effect, address)) {
                nmemFree(data);
                return false;
            }
            keep(effect, data);
            return true;
        }
    }

    @AllowAllocation("custom effects keep their samples")
    private void keep(int effect, long data) {
        Long previous = data == 0 ? customData.remove(effect) : customData.put(effect, data);
        if (previous != null) {
            nmemFree(previous);
        }
    }

    /** Plays an uploaded effect {@code iterations} times ({@link HapticEffect#INFINITE} for ever). */
    public boolean run(int effect, int iterations) {
        check();
        return SDL_RunHapticEffect(handle, effect, iterations == HapticEffect.INFINITE ? -1 : iterations);
    }

    public boolean stop(int effect) {
        check();
        return SDL_StopHapticEffect(handle, effect);
    }

    /** Whether the effect is playing; needs {@link HapticFeature#STATUS}. */
    public boolean isPlaying(int effect) {
        check();
        return SDL_GetHapticEffectStatus(handle, effect);
    }

    /** Removes an uploaded effect, freeing its slot. */
    @AllowAllocation("custom effects keep their samples")
    public void destroy(int effect) {
        check();
        SDL_DestroyHapticEffect(handle, effect);
        keep(effect, 0);
    }

    // ---------------------------------------------------------------- lifecycle

    private static int percent(float value) {
        return Math.round(Math.clamp(value, 0f, 1f) * 100);
    }

    public boolean isClosed() {
        return closed;
    }

    private void check() {
        platform.checkThread();
        if (closed) {
            throw new IllegalStateException("The haptic device is closed");
        }
    }

    /** Stops and closes the device. A controller's haptic device is closed with the controller. */
    @Override
    @AllowAllocation("closing")
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        SDL_CloseHaptic(handle);
        for (long data : customData.values()) {
            nmemFree(data);
        }
        customData.clear();
        platform.closed(this);
    }
}
