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
}
