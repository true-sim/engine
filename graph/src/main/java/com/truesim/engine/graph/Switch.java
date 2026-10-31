package com.truesim.engine.graph;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.graph.Id;
import com.truesim.engine.settings.EngineSettings;
import com.truesim.engine.settings.Setting;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A technique turned on and off by a setting, from one frame to the next, without a rebuild: the graph is built with it
 * on, and while it is off its passes are skipped. An in-place effect (a {@link Technique#modifies modifier}, bloom over
 * the scene) then leaves its image as it was; a producer gives a {@link #fallback} for each image others sample, read
 * instead while it is off (ambient occlusion: 1, nothing occluded). Switched off, a technique keeps its memory.
 *
 * <pre>{@code
 * Setting<Boolean> SSAO = Setting.bool("render.ssao", true, "screen-space ambient occlusion");
 * view.add(new Ssao(view).when(SSAO).fallback(Keys.AMBIENT_OCCLUSION, Fallback.constant(Format.R8_UNORM, 1)));
 * graph.settings().set(SSAO, false);                        // every view, from the next frame
 * view.ownSettings().set(SSAO, true);                       // but this one
 * }</pre>
 *
 * <p>The setting is read every frame: a view's technique from the view's own settings where set there
 * ({@link ViewPort#ownSettings()}), else from the graph's; a frame technique from the graph's. Only sampled reads of a
 * fallen-back image see the fallback: read otherwise (copied, as an attachment), its contents are undefined, as the
 * skipped passes made none (an image the technique modifies in place keeps the producer's). Passes of requests the
 * technique makes run on while it is off: other techniques may share them.
 */
public final class Switch implements Technique {

    private final Setting<Boolean> setting;
    private final Technique technique;
    private final Map<Key<Image>, Fallback> fallbacks = new LinkedHashMap<>();
    private long fallbackVersion; // changes as fallbacks are set: the graph rebuilds
    boolean on = true; // this frame: set when the frame starts

    private Switch(Setting<Boolean> setting, Technique technique) {
        this.setting = Objects.requireNonNull(setting, "setting");
        this.technique = Objects.requireNonNull(technique, "technique");
    }

    /** {@code technique}, on while {@code setting} is. */
    public static Switch of(Setting<Boolean> setting, Technique technique) {
        return new Switch(setting, technique);
    }

    /**
     * While off, passes sampling the image {@code key} names (one the technique provides) read {@code fallback}
     * instead. The graph rebuilds on its next frame.
     */
    @AllowAllocation("set up rarely")
    public Switch fallback(Key<Image> key, Fallback fallback) {
        fallbacks.put(Objects.requireNonNull(key, "key"), Objects.requireNonNull(fallback, "fallback"));
        fallbackVersion++;
        return this;
    }

    /** The setting turning it on and off. */
    public Setting<Boolean> setting() {
        return setting;
    }

    /** The technique switched. */
    public Technique technique() {
        return technique;
    }

    /** Whether it runs in the frame recording (as of when the frame started). */
    public boolean on() {
        return on;
    }

    /** Whether it is on in {@code view}'s settings (null: the frame's), as {@link #on} will be. */
    boolean on(ViewPort view, EngineSettings graph) {
        if (view != null) {
            EngineSettings own = view.ownSettings();
            if (own.isSet(setting)) {
                return own.get(setting);
            }
        }
        return graph.get(setting);
    }

    /** The fallback for {@code key}, or null. */
    Fallback fallbackFor(Key<?> key) {
        return fallbacks.get(key);
    }

    @Override
    public List<Key<?>> provides() {
        return technique.provides();
    }

    @Override
    public List<Key<?>> modifies() {
        return technique.modifies();
    }

    @Override
    public Id<? extends Technique> id() {
        return technique.id();
    }

    @Override
    public Order order() {
        return technique.order();
    }

    /** The technique's, plus the fallbacks set: a new fallback rebuilds the graph as the technique changing does. */
    @Override
    public long buildVersion() {
        return technique.buildVersion() + fallbackVersion;
    }

    @Override
    public void build(GraphBuilder g) {
        technique.build(g);
    }

    @Override
    public String toString() {
        return technique + " when " + setting;
    }
}
