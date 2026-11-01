package com.truesim.engine.render;

import com.truesim.engine.render.materials.AnisotropyParameters;
import com.truesim.engine.render.materials.ClearcoatParameters;
import com.truesim.engine.render.materials.DiffuseTransmissionParameters;
import com.truesim.engine.render.materials.DispersionParameters;
import com.truesim.engine.render.materials.IridescenceParameters;
import com.truesim.engine.render.materials.IorParameters;
import com.truesim.engine.render.materials.SheenParameters;
import com.truesim.engine.render.materials.SpecularParameters;
import com.truesim.engine.render.materials.TransmissionParameters;
import com.truesim.engine.render.materials.VolumeParameters;
import com.truesim.engine.render.materials.TextureTransformParameters;
import com.truesim.engine.render.materials.UnlitParameters;

/** The engine's features by name, as material data names them: their parameters from data by field name. */
public final class MaterialFeatures {

    private MaterialFeatures() {
    }

    /** Feature {@code name}'s parameters (e.g. {@code clearcoat}) from {@code values}; null for no such feature. */
    public static MaterialFeature fromValues(String name, MaterialValues values) {
        return switch (name) {
            case ClearcoatParameters.NAME -> ClearcoatParameters.fromValues(values);
            case TextureTransformParameters.NAME -> TextureTransformParameters.fromValues(values);
            case UnlitParameters.NAME -> UnlitParameters.fromValues(values);
            case SpecularParameters.NAME -> SpecularParameters.fromValues(values);
            case IorParameters.NAME -> IorParameters.fromValues(values);
            case SheenParameters.NAME -> SheenParameters.fromValues(values);
            case TransmissionParameters.NAME -> TransmissionParameters.fromValues(values);
            case VolumeParameters.NAME -> VolumeParameters.fromValues(values);
            case AnisotropyParameters.NAME -> AnisotropyParameters.fromValues(values);
            case IridescenceParameters.NAME -> IridescenceParameters.fromValues(values);
            case DispersionParameters.NAME -> DispersionParameters.fromValues(values);
            // glTF's extension name, KHR_materials_diffuse_transmission, as the importer passes it
            case DiffuseTransmissionParameters.NAME, "diffuse_transmission" ->
                DiffuseTransmissionParameters.fromValues(values);
            default -> null;
        };
    }
}
