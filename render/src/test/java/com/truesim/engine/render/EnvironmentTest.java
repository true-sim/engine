package com.truesim.engine.render;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

/** An environment's settings and when it is filtered: no GPU needed. */
class EnvironmentTest {

    @Test
    void settingsAreChecked() {
        assertThatIllegalArgumentException().isThrownBy(() -> Environment.uniform(" ", 1, 1, 1))
            .withMessageContaining("has a name");
        assertThatIllegalArgumentException().isThrownBy(() -> Environment.panorama("sky", -1, 64));
        assertThatIllegalArgumentException().isThrownBy(() -> Environment.panorama("sky", 3, 0));
        Environment e = Environment.uniform("grey", 0.5f, 0.5f, 0.5f);
        assertThatIllegalArgumentException().isThrownBy(() -> e.setColor(-1, 0, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> e.setColor(0, Float.NaN, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> e.setColor(0, 0, -1));
        assertThatIllegalArgumentException().isThrownBy(() -> e.intensity(-1));
        assertThatIllegalArgumentException().isThrownBy(() -> e.rotation(0, 0, 0, 0));
        assertThat(e.source()).isEqualTo(Environment.Source.UNIFORM);
        assertThat(e.color(1)).isEqualTo(0.5f);
        assertThat(e.name()).isEqualTo("grey");
        // Turned a quarter about +Y; the lookup takes the inverse.
        e.yaw(Math.PI / 2);
        assertThat(e.toEnvironment(1)).isCloseTo((float) -Math.sqrt(0.5), within(1e-6f));
        assertThat(e.toEnvironment(3)).isCloseTo((float) Math.sqrt(0.5), within(1e-6f));
        e.rotation(0, 0, 0, 2);
        assertThat(e.toEnvironment(3)).as("normalized").isEqualTo(1);
        e.setPanorama(0x12345, 128);
        assertThat(e.source()).isEqualTo(Environment.Source.PANORAMA);
        assertThat(e.panorama()).as("the slot alone, not its sampler").isEqualTo(0x2345);
        assertThat(e.panoramaWidth()).isEqualTo(128);
    }

    @Test
    void filteredOnceAFrameForEveryCubeAndAgainWhenItChanges() {
        Environment e = Environment.uniform("red", 1, 0, 0).intensity(2);
        assertThat(e.intensity()).isEqualTo(2);
        long first = e.version();
        assertThat(e.filters(7, 1, false)).as("a cube seen the first time").isTrue();
        assertThat(e.filters(7, 1, false)).as("the same frame: the same answer").isTrue();
        assertThat(e.filters(7, 2, false)).as("filtered already").isFalse();
        assertThat(e.filters(7, 3, true)).as("a fresh image").isTrue();
        e.intensity(1).yaw(1);
        assertThat(e.version()).as("intensity and rotation do not filter anew").isEqualTo(first);
        assertThat(e.filters(7, 4, false)).isFalse();
        e.setColor(0, 1, 0);
        assertThat(e.filters(7, 5, false)).as("a new color").isTrue();
        for (long cube = 8; cube < 12; cube++) {
            assertThat(e.filters(cube, 5, false)).as("each cube its own").isTrue();
        }
        assertThat(e.filters(11, 6, false)).isFalse();
    }

    @Test
    void qualitiesAreChecked() {
        assertThat(EnvironmentMaps.QUALITY.specularMips()).isEqualTo(6);
        assertThatIllegalArgumentException().isThrownBy(() -> new EnvironmentMaps.Quality(500, 256, 6, 64, 8));
        assertThatIllegalArgumentException().isThrownBy(() -> new EnvironmentMaps.Quality(512, 250, 6, 64, 8));
        assertThatIllegalArgumentException().isThrownBy(() -> new EnvironmentMaps.Quality(512, 256, 6, 60, 8));
        assertThatIllegalArgumentException().isThrownBy(() -> new EnvironmentMaps.Quality(256, 512, 6, 64, 8));
        assertThatIllegalArgumentException().isThrownBy(() -> new EnvironmentMaps.Quality(512, 256, 1, 64, 8));
        assertThatIllegalArgumentException().isThrownBy(() -> new EnvironmentMaps.Quality(512, 256, 10, 64, 8));
        assertThatIllegalArgumentException().isThrownBy(() -> new EnvironmentMaps.Quality(512, 256, 6, 64, 0));
    }
}
