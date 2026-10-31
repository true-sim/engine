package com.truesim.engine.xr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

/** The tracking space in the world: moved, turned about the up and scaled, poses and orientations with it. */
class TrackingOriginTest {

    @Test
    void posesFollowTheOriginsPositionTurnAndScale() {
        TrackingOrigin origin = new TrackingOrigin();
        double[] world = new double[3];
        float[] turned = new float[4];
        // At first the tracking space is the world's, moved by nothing.
        origin.toWorld(1, 2, 3, world);
        assertThat(world).containsExactly(1, 2, 3);
        // Moved, then a quarter turn left (+y, counter-clockwise from above) at twice the scale: a step ahead (-z) in
        // the tracking space is two to the world's left (-x), up stays up.
        origin.position(10, 0, -5);
        origin.turn(Math.PI / 2, 2);
        assertThat(origin.yaw()).isEqualTo(Math.PI / 2);
        assertThat(origin.scale()).isEqualTo(2);
        origin.toWorld(0, 1.5, -1, world);
        assertThat(world[0]).isCloseTo(8, within(1e-9));
        assertThat(world[1]).isCloseTo(3, within(1e-9));
        assertThat(world[2]).isCloseTo(-5, within(1e-9));
        // An unturned head is turned with the space: the quarter turn's quaternion (0, sin 45, 0, cos 45).
        origin.toWorld(0, 0, 0, 1, turned, 0);
        assertThat(turned[0]).isCloseTo(0, within(1e-6f));
        assertThat(turned[1]).isCloseTo((float) Math.sqrt(0.5), within(1e-6f));
        assertThat(turned[2]).isCloseTo(0, within(1e-6f));
        assertThat(turned[3]).isCloseTo((float) Math.sqrt(0.5), within(1e-6f));
        // A head already turned a quarter left: half a turn in the world, (0, 1, 0, 0).
        float h = (float) Math.sqrt(0.5);
        origin.toWorld(0, h, 0, h, turned, 0);
        assertThat(turned[1]).isCloseTo(1, within(1e-6f));
        assertThat(turned[3]).isCloseTo(0, within(1e-6f));
        assertThatIllegalArgumentException().isThrownBy(() -> origin.turn(0, 0));
    }
}
