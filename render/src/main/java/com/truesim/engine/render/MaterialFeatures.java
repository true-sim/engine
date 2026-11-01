package com.truesim.engine.render;

import com.truesim.engine.render.materials.ClearcoatParameters;
import com.truesim.engine.render.materials.TextureTransformParameters;

/** The engine's features by name, as material data names them: their parameters from data by field name. */
public final class MaterialFeatures {

    private MaterialFeatures() {
    }

    /** Feature {@code name}'s parameters (e.g. {@code clearcoat}) from {@code values}; null for no such feature. */
    public static MaterialFeature fromValues(String name, MaterialValues values) {
        return switch (name) {
            case ClearcoatParameters.NAME -> ClearcoatParameters.fromValues(values);
            case TextureTransformParameters.NAME -> TextureTransformParameters.fromValues(values);
            default -> null;
        };
    }
}
