package com.truesim.engine.render;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

class CameraTest {

    /** Where camera-relative point ({@code x}, {@code y}, {@code z}) lands in normalized device coordinates. */
    private static double[] ndc(Camera camera, double x, double y, double z) {
        float[] m = camera.viewProjection();
        double cx = m[0] * x + m[4] * y + m[8] * z + m[12];
        double cy = m[1] * x + m[5] * y + m[9] * z + m[13];
        double cz = m[2] * x + m[6] * y + m[10] * z + m[14];
        double w = m[3] * x + m[7] * y + m[11] * z + m[15];
        return new double[] {cx / w, cy / w, cz / w};
    }

    @Test
    void anEyeLooksLikeACameraWithTheSameView() {
        // A centered field of view and no turn: the camera looking down -Z with the same field of view.
        double fovY = Math.PI / 3;
        int width = 1600;
        int height = 900;
        double half = Math.atan(Math.tan(fovY / 2) * width / height);
        Camera eye = new Camera().setFov(0, 0, 0, 0, 0, 0, 1, -half, half, fovY / 2, -fovY / 2, width, height, 0.1);
        Camera plain = new Camera().set(0, 0, -1, 0, 1, 0, fovY, width, height, 0.1);
        assertThat(eye.viewProjection()).containsExactly(plain.viewProjection(), within(1e-5f));
        assertThat(eye.planes()).containsExactly(plain.planes(), within(1e-5f));
        // Turned a quarter left about +Y (OpenXR's turn): looking down -X.
        double s = Math.sqrt(0.5);
        Camera turned = new Camera().setFov(0, 0, 0, 0, s, 0, s, -half, half, fovY / 2, -fovY / 2, width, height, 0.1);
        Camera left = new Camera().set(-1, 0, 0, 0, 1, 0, fovY, width, height, 0.1);
        assertThat(turned.viewProjection()).containsExactly(left.viewProjection(), within(1e-5f));
    }

    @Test
    void anOffCenterFrustumPutsItsEdgesAtTheScreensEdges() {
        // A headset eye: more to the outside (left) than to the nose, more down than up; from an eye 3 cm left.
        double left = Math.toRadians(-52);
        double right = Math.toRadians(43);
        double up = Math.toRadians(41);
        double down = Math.toRadians(-48);
        Camera eye = new Camera().setFov(-0.03, 0, 0, 0, 0, 0, 1, left, right, up, down, 1000, 1000, 0.05);
        double[] leftEdge = ndc(eye, -0.03 + Math.tan(left) * 2, 0, -2);
        double[] rightEdge = ndc(eye, -0.03 + Math.tan(right) * 2, 0, -2);
        double[] top = ndc(eye, -0.03, Math.tan(up) * 2, -2);
        double[] bottom = ndc(eye, -0.03, Math.tan(down) * 2, -2);
        assertThat(leftEdge[0]).isCloseTo(-1, within(1e-5));
        assertThat(rightEdge[0]).isCloseTo(1, within(1e-5));
        assertThat(top[1]).as("Vulkan's y points down").isCloseTo(-1, within(1e-5));
        assertThat(bottom[1]).isCloseTo(1, within(1e-5));
        assertThat(ndc(eye, -0.03, 0, -0.05)[2]).as("depth 1 at the near plane").isCloseTo(1, within(1e-5));
        assertThat(eye.eye(0)).isEqualTo(-0.03);
    }

    @Test
    void theRotationSetTurnsForwardAndUpAsTheMatricesDo() {
        double s = Math.sqrt(0.5);
        // looking down -X, +Y up: a quarter turn about +Y
        Camera left = new Camera().set(-1, 0, 0, 0, 1, 0, 1.0, 800, 600, 0.25);
        assertThat(new double[] {left.rotation(0), left.rotation(1), left.rotation(2), left.rotation(3)})
            .containsExactly(new double[] {0, s, 0, s}, within(1e-12));
        assertThat(left.fovY()).isEqualTo(1.0);
        assertThat(left.near()).isEqualTo(0.25);
        // any direction: the quaternion turns -Z onto it and +Y into the plane of it and up
        double[][] directions = {{0, 0, 1}, {1, 0, 0}, {0.3, -0.8, 0.52}, {0, -1, 0.001}, {-0.6, 0.1, -0.7}};
        for (double[] f : directions) {
            Camera c = new Camera().set(f[0], f[1], f[2], 0, 1, 0, 1.0, 800, 600, 0.1);
            double[] q = {c.rotation(0), c.rotation(1), c.rotation(2), c.rotation(3)};
            double[] forward = turn(q, 0, 0, -1);
            double length = Math.sqrt(f[0] * f[0] + f[1] * f[1] + f[2] * f[2]);
            for (int a = 0; a < 3; a++) {
                assertThat(forward[a]).isCloseTo(f[a] / length, within(1e-9));
            }
            double[] up = turn(q, 0, 1, 0);
            assertThat(up[0] * f[0] + up[1] * f[1] + up[2] * f[2]).isCloseTo(0, within(1e-9));
        }
        Camera eye = new Camera().setFov(0, 0, 0, 0, s, 0, s, -0.5, 0.5, 0.4, -0.6, 100, 100, 0.05);
        assertThat(eye.rotation(1)).isCloseTo(s, within(1e-12));
        assertThat(eye.fovY()).isCloseTo(1.0, within(1e-12));
        Camera ortho = new Camera().setOrthographic(0, 0, 0, 0, -1, 0, 0, 0, -1, 10, 5, 100, 64, 32);
        assertThat(turn(new double[] {ortho.rotation(0), ortho.rotation(1), ortho.rotation(2), ortho.rotation(3)},
            0, 0, -1)[1]).isCloseTo(-1, within(1e-9));
        assertThat(ortho.halfHeight()).isEqualTo(5);
    }

    private static double[] turn(double[] q, double x, double y, double z) {
        double qx = q[0];
        double qy = q[1];
        double qz = q[2];
        double qw = q[3];
        double tx = 2 * (qy * z - qz * y);
        double ty = 2 * (qz * x - qx * z);
        double tz = 2 * (qx * y - qy * x);
        return new double[] {x + qw * tx + (qy * tz - qz * ty), y + qw * ty + (qz * tx - qx * tz),
            z + qw * tz + (qx * ty - qy * tx)};
    }
}
