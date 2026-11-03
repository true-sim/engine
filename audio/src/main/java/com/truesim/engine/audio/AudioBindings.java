package com.truesim.engine.audio;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.math.TransformArray;
import com.truesim.scene.animation.AnimationCurve;
import com.truesim.scene.animation.AnimationEvent;
import com.truesim.scene.animation.Animator;
import com.truesim.scene.animation.Animators;
import com.truesim.scene.animation.Joint;
import com.truesim.scene.audio.Decibels;
import com.truesim.scene.audio.Sound;
import com.truesim.scene.audio.SoundInstance;
import com.truesim.scene.audio.SoundParameter;
import com.truesim.scene.audio.Sounds;
import com.truesim.scene.audio.SwitchGroup;
import com.truesim.scene.core.NodeHandle;
import com.truesim.scene.effects.EffectData;
import com.truesim.scene.effects.Effects;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Arrays;
import java.util.Objects;

/**
 * Animation and effects driving sounds (§2.19, AU16), next to {@code EffectBindings}: animation events play sounds at
 * joints (a footstep at the foot, its surface switch from the terrain's material under it, louder for heavier events),
 * animation curves drive sound parameters every frame, and effects starting or bursting play sounds where they are.
 *
 * <pre>{@code
 * AudioBindings bindings = new AudioBindings(sounds, animators).effects(effects);
 * bindings.onEvent(LEFT_FOOT_DOWN, FOOTSTEPS, footL).surface(SURFACE, terrain::materialAt);
 * bindings.onEvent(RIGHT_FOOT_DOWN, FOOTSTEPS, footR).surface(SURFACE, terrain::materialAt);
 * bindings.curve(animator, BREATH, breathing, INTENSITY);
 * bindings.onEffect(MUZZLE_FLASH, GUNSHOT);
 * // each frame, after animators.update(...) and scene.update(), before the audio sync:
 * bindings.update();
 * }</pre>
 *
 * <p>Events fire during {@code animators.update} (on the animator's turns: {@code updateEvery(n)} may make far
 * footsteps up to n frames late), so the surface is read where the joint was at the last {@code scene.update()}. The
 * material's number is the switch value's ordinal; {@code NONE} (unloaded terrain) or a number past the enum's values
 * takes the group's fallback. Sounds play by id: binding allocates nothing per event or frame. Scene thread only.
 */
public final class AudioBindings {

    /** The material number at a horizontal position: {@code TerrainHeights::materialAt}; negative for none. */
    @FunctionalInterface
    public interface MaterialAt {
        int materialAt(double x, double z);
    }

    private final Sounds sounds;
    private final Animators animators;
    private Effects effects;
    private EventBinding[] events = new EventBinding[0];
    private AnimationEvent[] listened = new AnimationEvent[0];
    private CurveBinding[] curves = new CurveBinding[0];
    private EffectBinding[] effectBindings = new EffectBinding[0];
    private long mark;
    private int nextBurst;
    private long played;
    private final Animators.Listener listener = this::fired;
    private final Effects.Visitor visitor = this::effectChanged;

    /** Bindings playing into {@code sounds} from {@code animators}' playback (null: effects only). */
    @AllowAllocation("set up once")
    public AudioBindings(Sounds sounds, Animators animators) {
        this.sounds = Objects.requireNonNull(sounds, "sounds");
        this.animators = animators;
    }

    /** Also plays sounds for {@code effects}' starts and bursts ({@link #onEffect}). */
    public AudioBindings effects(Effects effects) {
        this.effects = Objects.requireNonNull(effects, "effects");
        mark = effects.scene().clock().now();
        nextBurst = effects.burstCount() == 0 ? 0 : effects.burstSerial(effects.burstCount() - 1) + 1;
        return this;
    }

    /** Plays {@code sound} at {@code joint} whenever an animator's playback passes {@code event}. */
    @AllowAllocation("setting up a binding")
    public EventBinding onEvent(AnimationEvent event, Sound sound, Joint joint) {
        Objects.requireNonNull(animators, "these bindings have no animators");
        EventBinding binding = new EventBinding(Objects.requireNonNull(event, "event"),
            Objects.requireNonNull(sound, "sound"), Objects.requireNonNull(joint, "joint"));
        events = Arrays.copyOf(events, events.length + 1);
        events[events.length - 1] = binding;
        if (!Arrays.asList(listened).contains(event)) {
            listened = Arrays.copyOf(listened, listened.length + 1);
            listened[listened.length - 1] = event;
            animators.listen(event, listener);
        }
        return binding;
    }

    /** Sets {@code parameter} of {@code sound} to {@code animator}'s curve {@code curve} every {@link #update}. */
    @AllowAllocation("setting up a binding")
    public CurveBinding curve(Animator animator, AnimationCurve curve, SoundInstance sound, SoundParameter parameter) {
        CurveBinding binding = new CurveBinding(Objects.requireNonNull(animator, "animator"),
            Objects.requireNonNull(curve, "curve"), Objects.requireNonNull(sound, "sound"),
            Objects.requireNonNull(parameter, "parameter"));
        curves = Arrays.copyOf(curves, curves.length + 1);
        curves[curves.length - 1] = binding;
        return binding;
    }

    /** Plays {@code sound} once where an instance of {@code effect} starts, or where a burst of it lands. */
    @AllowAllocation("setting up a binding")
    public EffectBinding onEffect(EffectData effect, Sound sound) {
        Objects.requireNonNull(effects, "call effects(...) first");
        EffectBinding binding = new EffectBinding(Objects.requireNonNull(effect, "effect"),
            Objects.requireNonNull(sound, "sound"));
        effectBindings = Arrays.copyOf(effectBindings, effectBindings.length + 1);
        effectBindings[effectBindings.length - 1] = binding;
        return binding;
    }

    /**
     * Sets the curve bindings' parameters, and plays the sounds of effects started or burst since the last update.
     * After {@code animators.update} and the effects' changes, before the audio sync. Allocates nothing.
     */
    public void update() {
        for (CurveBinding c : curves) {
            if (c.sound.alive()) {
                c.sound.set(c.parameter, (float) (c.animator.curve(c.curve) * c.scale + c.bias), 0);
            }
        }
        if (effects == null || effectBindings.length == 0) {
            return;
        }
        long now = effects.scene().clock().now();
        effects.changesSince(mark, visitor); // a log that overflowed misses those starts: sounds are not replayed
        mark = now;
        for (int i = 0; i < effects.burstCount(); i++) {
            int serial = effects.burstSerial(i);
            if (serial < nextBurst) {
                continue;
            }
            nextBurst = serial + 1;
            EffectData data = effects.data(effects.burstEffect(i));
            for (EffectBinding b : effectBindings) {
                if (b.effect.equals(data)) {
                    sounds.fire(b.sound, effects.burstPosition(i, 0), effects.burstPosition(i, 1),
                        effects.burstPosition(i, 2));
                    played++;
                }
            }
        }
    }

    /** How many sounds the bindings played so far. */
    public long played() {
        return played;
    }

    private void effectChanged(long effect, int changes) {
        if ((changes & Effects.ADDED) == 0 || !effects.alive(effect) || effects.shared(effect)) {
            return;
        }
        EffectData data = effects.data(effect);
        for (EffectBinding b : effectBindings) {
            if (b.effect.equals(data)) {
                long node = effects.node(effect);
                if (node != 0) {
                    sounds.fire(b.sound, node);
                } else {
                    sounds.fire(b.sound, effects.position(effect, 0), effects.position(effect, 1),
                        effects.position(effect, 2));
                }
                played++;
            }
        }
    }

    private void fired(long node, AnimationEvent event, float weight) {
        Animator animator = null;
        for (EventBinding b : events) {
            if (b.event != event || weight < b.least) {
                continue;
            }
            if (animator == null) {
                animator = animators.animator(node);
                if (animator == null) {
                    return; // not one of these animators' first nodes
                }
            }
            if (b.joint.index() >= animator.skeleton().size()) {
                continue; // another rig's joint
            }
            long at = animator.node(b.joint);
            long id = sounds.fire(b.sound, at);
            if (b.scaled) {
                sounds.volume(id, Decibels.of(Math.max(1e-4f, Math.min(1, weight))), 0);
            }
            if (b.surface != null) {
                surface(b, id, at);
            }
            played++;
        }
    }

    /** Sets binding {@code b}'s surface switch on {@code id} from the material under joint node {@code node}. */
    private void surface(EventBinding b, long id, long node) {
        TransformArray world = sounds.scene().worldTransforms();
        MemorySegment s = world.segment();
        long base = NodeHandle.index(node) * world.stride();
        int material = b.materialAt.materialAt(s.get(ValueLayout.JAVA_DOUBLE, base),
            s.get(ValueLayout.JAVA_DOUBLE, base + 16));
        set(b.surface, id, material, b.values);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void set(SwitchGroup group, long id, int material, Object[] values) {
        sounds.set(id, group, (Enum) (material >= 0 && material < values.length ? values[material] : group.fallback()));
    }

    /** An animation event playing a sound at a joint; its options are set up front. */
    public static final class EventBinding {
        private final AnimationEvent event;
        private final Sound sound;
        private final Joint joint;
        private SwitchGroup<?> surface;
        private MaterialAt materialAt;
        private Object[] values;
        private float least;
        private boolean scaled = true;

        private EventBinding(AnimationEvent event, Sound sound, Joint joint) {
            this.event = event;
            this.sound = sound;
            this.joint = joint;
        }

        /** Sets switch {@code group} of each sound played from the material under the joint, by ordinal. */
        @AllowAllocation("set up once")
        public EventBinding surface(SwitchGroup<?> group, MaterialAt materialAt) {
            surface = Objects.requireNonNull(group, "group");
            this.materialAt = Objects.requireNonNull(materialAt, "materialAt");
            values = group.type().getEnumConstants(); // once: the JDK hands a copy each call
            return this;
        }

        /** Plays nothing for events weighing less than {@code weight}. 0 by default. */
        public EventBinding minimumWeight(float weight) {
            least = weight;
            return this;
        }

        /** Whether the event's weight scales the volume (as a linear gain). On by default. */
        public EventBinding scaled(boolean scaled) {
            this.scaled = scaled;
            return this;
        }
    }

    /** An animation curve driving a sound's parameter. */
    public static final class CurveBinding {
        private final Animator animator;
        private final AnimationCurve curve;
        private final SoundInstance sound;
        private final SoundParameter parameter;
        private double scale = 1;
        private double bias;

        private CurveBinding(Animator animator, AnimationCurve curve, SoundInstance sound,
            SoundParameter parameter) {
            this.animator = animator;
            this.curve = curve;
            this.sound = sound;
            this.parameter = parameter;
        }

        /** The parameter is the curve's value times {@code scale} plus {@code bias}. 1 and 0 by default. */
        public CurveBinding map(double scale, double bias) {
            this.scale = scale;
            this.bias = bias;
            return this;
        }
    }

    /** An effect whose starts and bursts play a sound. */
    public static final class EffectBinding {
        private final EffectData effect;
        private final Sound sound;

        private EffectBinding(EffectData effect, Sound sound) {
            this.effect = effect;
            this.sound = sound;
        }
    }
}
