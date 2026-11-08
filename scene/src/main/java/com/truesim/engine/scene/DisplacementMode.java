package com.truesim.engine.scene;

import com.truesim.engine.render.Displacement;
import com.truesim.scene.core.ComponentType;
import java.util.Objects;

/**
 * Where a renderable's displacing materials move its vertices (engine-render's {@link Displacement}; README,
 * "Displacement"): {@link Displacement#RASTER}, in every pass drawing it, or {@link Displacement#COMPUTE}, once a frame
 * into a copy of its mesh that ray tracing sees. On the renderable's node; where it has none, its materials' type's
 * mode ({@code Materials.displacementMode}), else the setting {@code render.displacement.mode}. Saved with the scene.
 *
 * <pre>{@code
 * scene.components().set(snowField, DisplacementMode.TYPE, new DisplacementMode(Displacement.COMPUTE));
 * }</pre>
 *
 * @param mode where its displacement runs
 */
public record DisplacementMode(Displacement mode) {

    /** The component type. */
    public static final ComponentType<DisplacementMode> TYPE =
        ComponentType.of("truesim.displacement-mode", DisplacementMode.class);

    public DisplacementMode {
        Objects.requireNonNull(mode, "mode");
    }
}
