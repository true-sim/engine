package com.truesim.engine.demo;

import static org.assertj.core.api.Assertions.assertThat;

import com.truesim.scene.assets.mesh.MeshData;
import com.truesim.scene.assets.mesh.VertexAttribute;
import com.truesim.scene.assets.mesh.VertexFormat;
import com.truesim.scene.meshbuild.MeshBuilder;
import org.junit.jupiter.api.Test;

/** The showcase's generated textures, without a device. */
class EffectTexturesTest {

    @Test
    void aFlameTongueStandsOnItsBaseAndThinsOutLateInItsLife() {
        // dense low in the middle, nothing in the frame's top corners, less of it at the end of its life
        assertThat(EffectTextures.flameDensity(0, 0.2, 0.3)).isGreaterThan(0.5);
        assertThat(EffectTextures.flameDensity(-0.95, 0.95, 0.3)).isZero();
        assertThat(EffectTextures.flameDensity(0.95, 0.95, 0.3)).isZero();
        assertThat(coverage(1)).isLessThan(0.6 * coverage(0.3));
    }

    private static double coverage(double t) {
        double sum = 0;
        for (int y = 0; y < 32; y++) {
            for (int x = 0; x < 32; x++) {
                sum += Math.min(1, EffectTextures.flameDensity((x + 0.5) / 16 - 1, (y + 0.5) / 32, t));
            }
        }
        return sum;
    }

    @Test
    void smokeNormalsAreUnitLengthAndFaceTheCamera() {
        float[] normals = EffectTextures.smoke()[1];
        int size = EffectTextures.SMOKE_FRAMES * 128;
        int middle = 4 * (64 * size + 64); // the first frame's middle
        double x = 2 * normals[middle] - 1;
        double y = 2 * normals[middle + 1] - 1;
        double z = 2 * normals[middle + 2] - 1;
        assertThat(Math.sqrt(x * x + y * y + z * z)).isBetween(0.97, 1.03);
        assertThat(z).isGreaterThan(0.5);
    }

    @Test
    void theUpwardMaskIsDenseWhereTrianglesFaceUp() {
        // Two triangles: one facing up (+Y) on the texture's left half, one facing down on its right half
        float[] positions = {0, 0, 0, 0, 0, 1, 1, 0, 0, 0, -1, 0, 1, -1, 0, 0, -1, 1};
        float[] normals = {0, 1, 0, 0, 1, 0, 0, 1, 0, 0, -1, 0, 0, -1, 0, 0, -1, 0};
        float[] uvs = {0.05f, 0.05f, 0.05f, 0.95f, 0.45f, 0.05f, 0.55f, 0.05f, 0.95f, 0.05f, 0.55f, 0.95f};
        try (MeshData mesh = new MeshBuilder(VertexFormat.of(VertexAttribute.NORMAL, VertexAttribute.UV0), 6)
            .positions(positions).attribute(VertexAttribute.NORMAL, normals).attribute(VertexAttribute.UV0, uvs)
            .submesh(new int[] {0, 1, 2, 3, 4, 5}).buildFlat()) {
            float[] mask = EffectTextures.upward(mesh, 32);
            assertThat(mask[4 * (8 * 32 + 4)]).as("facing up").isGreaterThan(0.9f);
            assertThat(mask[4 * (8 * 32 + 20)]).as("facing down").isLessThan(0.2f);
        }
    }
}
