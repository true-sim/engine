package com.truesim.engine.scene;

import com.truesim.scene.core.ComponentType;
import com.truesim.scene.render.MaterialData;
import java.util.Objects;

/**
 * A node's values for a shading hook (engine-render's {@code ShadingHooks}; README, "Writing a shading hook"): the
 * hook's name, its parameters by field name as content holds them (the record's defaults where they have none), and
 * whether it is on. Saved with the scene. {@link ShadingHookSync} hands them to the renderer, so a hook whose library
 * has no technique for it runs from content alone; a library's technique merges them with what each frame knows.
 * One node per hook: where several hold values for one hook, the first in the scene's order counts. A hidden node's
 * (on no layer) values are off.
 *
 * <pre>{@code
 * scene.components().set(weather, ShadingHookComponent.TYPE, new ShadingHookComponent("game.fog",
 *     MaterialData.builder("game.fog").set("density", 0.02f).set("color", 0.6f, 0.7f, 0.8f).build().values(), true));
 * }</pre>
 *
 * @param hook   the hook's name, as its {@code [ShadingHook("name")]}
 * @param values its parameters by field name
 * @param on     whether it runs
 */
public record ShadingHookComponent(String hook, MaterialData.Values values, boolean on) {

    /** The component type. */
    public static final ComponentType<ShadingHookComponent> TYPE =
        ComponentType.of("truesim.shading-hook", ShadingHookComponent.class);

    public ShadingHookComponent {
        Objects.requireNonNull(hook, "hook");
        Objects.requireNonNull(values, "values");
    }
}
