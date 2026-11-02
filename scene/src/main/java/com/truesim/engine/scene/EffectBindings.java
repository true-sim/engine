package com.truesim.engine.scene;

import com.truesim.annotations.AllowAllocation;
import com.truesim.scene.animation.AnimationCurve;
import com.truesim.scene.animation.AnimationEvent;
import com.truesim.scene.animation.Animator;
import com.truesim.scene.animation.Animators;
import com.truesim.scene.animation.Joint;
import com.truesim.scene.effects.Effect;
import com.truesim.scene.effects.EffectData;
import com.truesim.scene.effects.EffectParameter;
import com.truesim.scene.effects.Effects;
import java.util.Arrays;
import java.util.Objects;

/**
 * Animation driving effects (effects.md §2.6): animation events play effects at joints, and animation curves drive
 * effects' parameters every frame. Events carry no joint, so a binding names it: when an animator's playback passes the
 * event, the effect plays attached to that joint's node of the animator (found from the node the listener hears of,
 * {@link Animators#animator}), the event's weight as a parameter if asked.
 *
 * <pre>{@code
 * EffectBindings fx = new EffectBindings(effects, animators);
 * fx.onEvent(LEFT_FOOT_DOWN, DUST, footL).scale(INTENSITY);   // played at the joint, the event's weight as intensity
 * fx.onEvent(RIGHT_FOOT_DOWN, DUST, footR).scale(INTENSITY);
 * fx.curve(animator, FLICKER, torch, INTENSITY);              // the animator's curve sets the parameter every frame
 * // each frame, after animators.update(...) and before the effect sync:
 * fx.update();
 * }</pre>
 *
 * <p>Events fire on the animator's updates only ({@link Animator#updateEvery}); effects attached to its joints follow
 * the joints every frame. Every animator of the {@link Animators} plays a binding's effect when it passes the event,
 * at the binding's joint of its own rig (a crowd sharing a rig shares the bindings); an animator whose skeleton has no
 * such joint plays nothing. Each effect played allocates its handle, as {@link Effects#play} does; curves allocate
 * nothing. Used from the scene's thread.
 */
public final class EffectBindings {

    private final Effects effects;
    private final Animators animators;
    private EventBinding[] events = new EventBinding[0];
    private AnimationEvent[] listened = new AnimationEvent[0];
    private CurveBinding[] curves = new CurveBinding[0];
    private long played;
    private final Animators.Listener listener = this::fired;

    /** Bindings playing into {@code effects} from {@code animators}' playback. */
    @AllowAllocation("set up once")
    public EffectBindings(Effects effects, Animators animators) {
        this.effects = Objects.requireNonNull(effects, "effects");
        this.animators = Objects.requireNonNull(animators, "animators");
    }

    /** Plays {@code effect} at {@code joint} whenever an animator's playback passes {@code event}. */
    @AllowAllocation("setting up a binding")
    public EventBinding onEvent(AnimationEvent event, EffectData effect, Joint joint) {
        EventBinding binding = new EventBinding(Objects.requireNonNull(event, "event"),
            Objects.requireNonNull(effect, "effect"), Objects.requireNonNull(joint, "joint"));
        events = Arrays.copyOf(events, events.length + 1);
        events[events.length - 1] = binding;
        if (!Arrays.asList(listened).contains(event)) {
            listened = Arrays.copyOf(listened, listened.length + 1);
            listened[listened.length - 1] = event;
            animators.listen(event, listener);
        }
        return binding;
    }

    /**
     * Sets {@code effect}'s float parameter {@code parameter} to {@code animator}'s curve {@code curve} every
     * {@link #update}, while the effect plays.
     */
    @AllowAllocation("setting up a binding")
    public CurveBinding curve(Animator animator, AnimationCurve curve, Effect effect,
        EffectParameter.Float parameter) {
        CurveBinding binding = new CurveBinding(Objects.requireNonNull(animator, "animator"),
            Objects.requireNonNull(curve, "curve"), Objects.requireNonNull(effect, "effect"),
            Objects.requireNonNull(parameter, "parameter"));
        curves = Arrays.copyOf(curves, curves.length + 1);
        curves[curves.length - 1] = binding;
        return binding;
    }

    /**
     * Evaluates the curve bindings: each sets its effect's parameter from its animator's curve as of the animator's last
     * update. After {@code animators.update}, before the effect sync. Allocates nothing.
     */
    public void update() {
        for (CurveBinding c : curves) {
            if (c.effect.alive()) {
                c.effect.set(c.parameter, (float) (c.animator.curve(c.curve) * c.scale + c.bias));
            }
        }
    }

    /** How many effects the event bindings played so far. */
    public long played() {
        return played;
    }

    @AllowAllocation("an effect played: its handle")
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
            Effect effect = effects.play(b.effect, animator.node(b.joint)).offset(b.x, b.y, b.z);
            if (b.weightTo != null) {
                effect.set(b.weightTo, weight * b.weightScale);
            }
            played++;
        }
    }

    /** An animation event playing an effect at a joint; its options are set up front. */
    public static final class EventBinding {
        private final AnimationEvent event;
        private final EffectData effect;
        private final Joint joint;
        private EffectParameter.Float weightTo;
        private float weightScale = 1;
        private float least;
        private double x;
        private double y;
        private double z;

        private EventBinding(AnimationEvent event, EffectData effect, Joint joint) {
            this.event = event;
            this.effect = effect;
            this.joint = joint;
        }

        /** Sets {@code parameter} of each effect played to the event's weight (its motion's weight in the blend). */
        public EventBinding scale(EffectParameter.Float parameter) {
            return scale(parameter, 1);
        }

        /** Sets {@code parameter} of each effect played to the event's weight times {@code factor}. */
        public EventBinding scale(EffectParameter.Float parameter, float factor) {
            weightTo = Objects.requireNonNull(parameter, "parameter");
            weightScale = factor;
            return this;
        }

        /** Plays nothing for events weighing less than {@code weight}: a motion barely faded in. 0 by default. */
        public EventBinding minimumWeight(float weight) {
            least = weight;
            return this;
        }

        /** Plays the effect at ({@code x}, {@code y}, {@code z}) in the joint's frame: a socket's offset. */
        public EventBinding offset(double x, double y, double z) {
            this.x = x;
            this.y = y;
            this.z = z;
            return this;
        }
    }

    /** An animation curve driving an effect's parameter. */
    public static final class CurveBinding {
        private final Animator animator;
        private final AnimationCurve curve;
        private final Effect effect;
        private final EffectParameter.Float parameter;
        private double scale = 1;
        private double bias;

        private CurveBinding(Animator animator, AnimationCurve curve, Effect effect,
            EffectParameter.Float parameter) {
            this.animator = animator;
            this.curve = curve;
            this.effect = effect;
            this.parameter = parameter;
        }

        /** The parameter is the curve's value times {@code scale} plus {@code bias}. 1 and 0 by default. */
        public CurveBinding map(double scale, double bias) {
            this.scale = scale;
            this.bias = bias;
            return this;
        }
    }
}
