package com.truesim.engine.scene;

import com.truesim.scene.core.ComponentType;
import com.truesim.scene.render.MaterialData;
import java.util.Objects;

/**
 * A node's switch and values for a shading hook over the view's, for the node and everything below it (engine-render's
 * {@code ShadingHookOverrides}; README, "Writing a shading hook"): the hook's name, whether it runs there (off, on, or
 * null: as the view runs it; a material refusing the hook wins) and the fields it sets by field name, as content holds
 * them; the fields it does not set stay the view's (the scene's {@link ShadingHookComponent},
 * or what the hook's technique writes). The nearest ancestor (the node itself included) setting a hook counts for it,
 * so a node inside a building can thin the building's fog again, and a node may override one hook while an ancestor
 * overrides another. Renderables below the node are shaded with it (rasterized, ray and probe hits, the media between
 * them and the camera); a library's own drawing (a particle emitter) takes it through
 * {@link ShadingHookOverrideSync#entryOf}. Saved with the scene.
 *
 * <pre>{@code
 * scene.components().set(house, ShadingHookOverride.TYPE, new ShadingHookOverride("game.fog",
 *     MaterialData.builder("game.fog").set("density", 0.001f).build().values()));  // thin fog inside
 * scene.components().set(porch, ShadingHookOverride.TYPE, ShadingHookOverride.off("game.snow")); // no snow there
 * }</pre>
 *
 * @param hook   the hook's name, as its {@code [ShadingHook("name")]}
 * @param values the fields it sets, by field name
 * @param on     whether the hook runs there: false off, true on, null as the view runs it
 */
public record ShadingHookOverride(String hook, MaterialData.Values values, Boolean on) {

    /** The component type. */
    public static final ComponentType<ShadingHookOverride> TYPE =
        ComponentType.of("truesim.shading-hook-override", ShadingHookOverride.class);

    public ShadingHookOverride {
        Objects.requireNonNull(hook, "hook");
        Objects.requireNonNull(values, "values");
    }

    /** Values {@code values} for hook {@code hook}, which runs as the view runs it. */
    public ShadingHookOverride(String hook, MaterialData.Values values) {
        this(hook, values, null);
    }

    /** Hook {@code hook} off for the node and below it. */
    @com.truesim.annotations.AllowAllocation("made when content changes")
    public static ShadingHookOverride off(String hook) {
        return new ShadingHookOverride(hook, MaterialData.builder(hook).build().values(), Boolean.FALSE);
    }
}
