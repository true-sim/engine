package com.truesim.engine.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

/** The showcase's camera paths, without a device. */
class CameraPathTest {

    @Test
    void keyframesArePassedThroughAndHeldAtTheEnds() {
        CameraPath path = new CameraPath("keys:0,0,5,0,1,5,0;2,10,g3,0,10,g0,5;4,10,5,10,0,0,0", 0, 0, null);
        double[] eye = new double[3];
        double[] target = new double[3];
        path.at(2, eye, target);
        assertThat(eye[0]).isCloseTo(10, within(1e-9));
        assertThat(eye[1]).as("g3: 3 above the ground").isCloseTo(DemoMeshes.height(10, 0) + 3, within(1e-9));
        assertThat(target[2]).isCloseTo(5, within(1e-9));
        path.at(-1, eye, target);
        assertThat(eye[1]).isCloseTo(5, within(1e-9));
        path.at(9, eye, target);
        assertThat(eye[2]).isCloseTo(10, within(1e-9));
        path.at(1, eye, target);
        assertThat(eye[0]).isBetween(0.0, 10.0);
    }

    @Test
    void anOrbitCirclesTheMiddleLookingAtIt() {
        CameraPath path = new CameraPath("orbit:30,6,90,0", 5, -5, null);
        double[] eye = new double[3];
        double[] target = new double[3];
        for (double t = 0; t < 8; t += 1.5) {
            path.at(t, eye, target);
            double r = Math.sqrt((eye[0] - 5) * (eye[0] - 5) + (eye[2] + 5) * (eye[2] + 5));
            assertThat(r).isBetween(30 * 0.87, 30 * 1.13);
            assertThat(target[0]).isEqualTo(5);
            assertThat(target[2]).isEqualTo(-5);
        }
    }

    @Test
    void unknownPathsAreRefused() {
        assertThatIllegalArgumentException().isThrownBy(() -> new CameraPath("spiral", 0, 0, null));
        assertThatIllegalArgumentException().isThrownBy(() -> new CameraPath("keys:0,1,2", 0, 0, null));
    }
}
