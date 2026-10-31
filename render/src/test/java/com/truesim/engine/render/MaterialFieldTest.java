package com.truesim.engine.render;

import static org.assertj.core.api.Assertions.assertThat;

import com.truesim.engine.render.materials.MetallicRoughnessParameters;
import org.junit.jupiter.api.Test;

class MaterialFieldTest {

    @Test
    void textureFieldsAreIntegersMarkedAsTextures() {
        MaterialField texture = MaterialField.texture(MetallicRoughnessParameters.class, "baseColorTexture", 16);
        assertThat(texture.kind()).isEqualTo(MaterialField.Kind.I32);
        assertThat(texture.texture()).isTrue();
        assertThat(texture.offset()).isEqualTo(16);
        assertThat(MaterialField.i32(MetallicRoughnessParameters.class, "flags", 20).texture()).isFalse();
        assertThat(texture).hasToString("MetallicRoughnessParameters.baseColorTexture");
    }
}
