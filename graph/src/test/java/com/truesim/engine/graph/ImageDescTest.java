package com.truesim.engine.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.truesim.engine.vulkan.Format;
import org.junit.jupiter.api.Test;

/** Image descriptors check what they describe; ratios scale sizes with ceiling division. */
class ImageDescTest {

    @Test
    void descriptorsDescribeEveryShape() {
        ImageDesc hdr = ImageDesc.of(Format.R16G16B16A16_SFLOAT, 1920, 1080);
        assertThat(hdr.flat()).isTrue();
        assertThat(hdr.fullMips().mips()).isEqualTo(11);
        assertThat(hdr.format(Format.R8G8B8A8_UNORM).format()).isEqualTo(Format.R8G8B8A8_UNORM);
        ImageDesc cascades = ImageDesc.of(Format.D32_SFLOAT, 2048, 2048).layers(4);
        assertThat(cascades.layers()).isEqualTo(4);
        assertThat(cascades.flat()).isFalse();
        ImageDesc sky = ImageDesc.of(Format.R16G16B16A16_SFLOAT, 256, 256).cube();
        assertThat(sky.layers()).isEqualTo(6);
        assertThat(sky.cubeMap()).isTrue();
        assertThat(ImageDesc.of(Format.R16G16B16A16_SFLOAT, 64, 64).cubes(3).layers()).isEqualTo(18);
        ImageDesc fog = ImageDesc.of(Format.R16G16B16A16_SFLOAT, 160, 90).volume(64);
        assertThat(fog.depth()).isEqualTo(64);
        assertThat(fog.flat()).isFalse();
        assertThat(fog.fullMips().mips()).isEqualTo(8); // the widest of width and depth: 160
        ImageDesc msaa = ImageDesc.of(Format.R8G8B8A8_UNORM, 64, 64).samples(4);
        assertThat(msaa.samples()).isEqualTo(4);
        assertThat(msaa.flat()).isFalse();
        // Like another image: its format, layers and samples, scaled, one mip level.
        Image source = new Image(0, "source", cascades.mips(3));
        ImageDesc half = ImageDesc.like(source, Ratio.of(1, 2));
        assertThat(half.width()).isEqualTo(1024);
        assertThat(half.layers()).isEqualTo(4);
        assertThat(half.mips()).isEqualTo(1);
        assertThat(source.desc()).isEqualTo(cascades.mips(3));
        assertThat(source).hasToString("source (2048 x 2048)");
    }

    @Test
    void impossibleImagesAreRejected() {
        ImageDesc plain = ImageDesc.of(Format.R8G8B8A8_UNORM, 8, 8);
        assertThatNullPointerException().isThrownBy(() -> ImageDesc.of(null, 8, 8));
        assertThatIllegalArgumentException().isThrownBy(() -> ImageDesc.of(Format.R8G8B8A8_UNORM, 0, 8));
        assertThatIllegalArgumentException().isThrownBy(() -> ImageDesc.of(Format.R8G8B8A8_UNORM, 8, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> plain.volume(0));
        assertThatIllegalArgumentException().isThrownBy(() -> plain.layers(0));
        assertThatIllegalArgumentException().isThrownBy(() -> plain.volume(4).layers(2))
            .withMessageContaining("3D image");
        assertThatIllegalArgumentException().isThrownBy(() -> plain.cube().volume(4)).withMessageContaining("3D image");
        assertThatIllegalArgumentException().isThrownBy(() -> plain.samples(4).volume(4))
            .withMessageContaining("3D image");
        assertThatIllegalArgumentException().isThrownBy(() -> ImageDesc.of(Format.R8G8B8A8_UNORM, 8, 4).cube())
            .withMessageContaining("Cube maps");
        assertThatIllegalArgumentException().isThrownBy(() -> plain.cube().layers(5))
            .withMessageContaining("Cube maps");
        assertThatIllegalArgumentException().isThrownBy(() -> plain.samples(3)).withMessageContaining("Samples");
        assertThatIllegalArgumentException().isThrownBy(() -> plain.samples(128)).withMessageContaining("Samples");
        assertThatIllegalArgumentException().isThrownBy(() -> plain.mips(2).samples(4))
            .withMessageContaining("Samples");
        assertThatIllegalArgumentException().isThrownBy(() -> plain.mips(0)).withMessageContaining("mip levels");
        assertThatIllegalArgumentException().isThrownBy(() -> plain.mips(5)).withMessageContaining("1 to 4");
    }

    @Test
    void ratiosRoundUp() {
        assertThat(Ratio.of(1, 2).apply(1081)).isEqualTo(541);
        assertThat(Ratio.of(1, 4).apply(1)).isEqualTo(1);
        assertThat(Ratio.ONE.apply(7)).isEqualTo(7);
        assertThat(Ratio.of(3, 2).apply(10)).isEqualTo(15);
        assertThat(Ratio.of(1, 3)).hasToString("1/3");
        assertThatIllegalArgumentException().isThrownBy(() -> Ratio.of(0, 1)).withMessageContaining("positive");
        assertThatIllegalArgumentException().isThrownBy(() -> Ratio.of(1, 0)).withMessageContaining("positive");
    }
}
