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

    @Test
    void fieldsAreFoundByPathOnTheirRecord() {
        assertThat(MaterialField.find(MetallicRoughnessParameters.class, "baseColor"))
            .isSameAs(MetallicRoughnessParameters.BASE_COLOR);
        assertThat(MaterialField.find(MetallicRoughnessParameters.class, "roughness"))
            .isSameAs(MetallicRoughnessParameters.ROUGHNESS);
        assertThat(MaterialField.find(MetallicRoughnessParameters.class, "flakes")).isNull();
        assertThat(MaterialField.find(String.class, "baseColor")).as("not a record of fields").isNull();
    }

    @Test
    void bufferFieldsAreTheirOwnKind() {
        MaterialField buffer = MaterialField.buffer(MetallicRoughnessParameters.class, "data", 8);
        assertThat(buffer.kind()).isEqualTo(MaterialField.Kind.BUFFER);
        assertThat(buffer.texture()).isFalse();
        assertThat(MaterialValues.NONE.buffer("data", null)).isNull();
        assertThat(MetallicRoughnessParameters.DEFAULTS).satisfies(p -> p.buffers(0, (offset, b) -> {
            throw new AssertionError("no buffer fields");
        }));
    }
}
