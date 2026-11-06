package com.truesim.engine.scene;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.within;
import static org.assertj.core.api.Assumptions.assumeThat;

import com.truesim.common.state.ChangeClock;
import com.truesim.engine.render.Camera;
import com.truesim.engine.render.Lights;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.engine.vulkan.VulkanException;
import com.truesim.engine.vulkan.VulkanInstance;
import com.truesim.scene.core.CameraLens;
import com.truesim.scene.core.LightSource;
import com.truesim.scene.core.NodeHandle;
import com.truesim.scene.core.SceneGraph;
import org.junit.jupiter.api.Test;

/** Camera and light components (application-api.md D46, D129) followed by the renderer's camera and lights. */
class CameraAndLightSyncTest {

    @Test
    void aCameraFollowsItsNodeAndLens() {
        SceneGraph scene = new SceneGraph(new ChangeClock());
        long rig = scene.create();
        long eye = scene.create(rig);
        scene.setTranslation(rig, 100, 2, 0);
        scene.setTranslation(eye, 0, 1.5, 0);
        scene.setRotation(eye, 0, Math.sin(Math.PI / 4), 0, Math.cos(Math.PI / 4)); // turned left: looking along -X
        scene.update();
        Camera camera = new Camera();
        CameraSync sync = new CameraSync(scene, eye, camera);
        assertThat(sync.update(800, 600)).as("no lens yet").isFalse();
        scene.components().set(eye, CameraLens.TYPE, CameraLens.perspective(1.2, 0.05));
        assertThat(sync.update(800, 600)).isTrue();
        assertThat(camera.position(0)).isEqualTo(100);
        assertThat(camera.position(1)).isEqualTo(3.5);
        assertThat(camera.fovY()).isEqualTo(1.2);
        assertThat(camera.near()).isEqualTo(0.05);
        assertThat(camera.orthographic()).isFalse();
        // the camera's rotation turns -Z (forward) onto the node's -Z: -X here
        double qx = camera.rotation(0);
        double qy = camera.rotation(1);
        double qz = camera.rotation(2);
        double qw = camera.rotation(3);
        double fx = -2 * (qx * qz + qw * qy);
        assertThat(fx).isCloseTo(-1, within(1e-9));
        scene.components().get(eye, CameraLens.TYPE).orthographicLens(5, 50);
        sync.update(800, 600);
        assertThat(camera.orthographic()).isTrue();
        assertThat(camera.halfHeight()).isEqualTo(5);
        assertThat(sync.node()).isEqualTo(eye);
        assertThat(sync.camera()).isSameAs(camera);
        assertThatIllegalStateException().isThrownBy(sync::update);
        assertThatIllegalArgumentException().isThrownBy(() -> sync.node(NodeHandle.of(77, 1)));
        scene.remove(eye);
        assertThat(sync.update(800, 600)).as("gone").isFalse();
    }

    @Test
    void lightsFollowTheirNodesAndComponents() {
        VulkanInstance instance;
        try {
            instance = VulkanInstance.builder().applicationName("lights").surfaces(false).build();
        } catch (VulkanException | UnsatisfiedLinkError e) {
            assumeThat(e).as("no Vulkan available").isNull();
            throw e;
        }
        try (instance; VulkanDevice device = VulkanDevice.createHeadless(instance.bestGpu());
             Lights lights = new Lights(device, 4, 1)) {
            SceneGraph scene = new SceneGraph(new ChangeClock());
            long lamp = scene.create();
            long sun = scene.create();
            long torch = scene.create();
            scene.setTranslation(lamp, 1, 2, 3);
            scene.setRotation(sun, Math.sin(-Math.PI / 4), 0, 0, Math.cos(-Math.PI / 4)); // -Z turned down: -Y
            scene.update();
            scene.components().set(lamp, LightSource.TYPE, LightSource.point(1, 0.5f, 0.25f, 10).range(8));
            scene.components().set(sun, LightSource.TYPE, LightSource.directional(1, 1, 1, 3));
            scene.components().set(torch, LightSource.TYPE, LightSource.spot(1, 1, 1, 20, 0.1, 0.4));
            LightSync sync = new LightSync(scene, lights);
            sync.update();
            assertThat(sync.count()).isEqualTo(3);
            int id = sync.idOf(lamp);
            assertThat(id).isNotNegative();
            assertThat(lights.position(id, 1)).isEqualTo(2);
            double[] direction = new double[3];
            lights.direction(sync.idOf(sun), direction);
            assertThat(direction[1]).isCloseTo(-1, within(1e-6));
            assertThat(sync.idOf(NodeHandle.of(99, 1))).isEqualTo(-1);
            // shadows: the spec as set on the component, changed in place; none at first
            assertThat(lights.shadow(id)).isSameAs(com.truesim.scene.core.ShadowSpec.NONE);
            com.truesim.scene.core.ShadowSpec soft = com.truesim.scene.core.ShadowSpec.softRayTraced(0.05);
            scene.components().get(lamp, LightSource.TYPE).shadow(soft);
            long specs = lights.shadowVersion();
            sync.update();
            assertThat(lights.shadow(id)).isSameAs(soft);
            assertThat(lights.shadowVersion()).isGreaterThan(specs);
            // the node's mobility as the light's hint: dynamic by default, changed at runtime
            assertThat(lights.mobilityHint(id)).isEqualTo(com.truesim.engine.render.GpuScene.MOVING);
            scene.setMobility(lamp, com.truesim.scene.core.Mobility.STATIC);
            scene.update();
            sync.update();
            assertThat(lights.mobilityHint(id)).isEqualTo(com.truesim.engine.render.GpuScene.STILL);
            // moved, changed in place, hidden, removed
            scene.setTranslation(lamp, 5, 2, 3);
            scene.update();
            scene.components().get(sun, LightSource.TYPE).intensity(0.5);
            scene.setLayers(torch, 0);
            scene.update();
            sync.update();
            assertThat(lights.position(id, 0)).isEqualTo(5);
            int sunId = sync.idOf(sun);
            scene.components().get(sun, LightSource.TYPE).shadow(com.truesim.scene.core.ShadowSpec.rayTraced());
            sync.update();
            assertThat(lights.shadow(sunId).method()).isEqualTo(com.truesim.scene.core.ShadowMethod.RAY_TRACED);
            scene.components().remove(sun, LightSource.TYPE);
            scene.remove(torch);
            sync.update();
            assertThat(sync.count()).isOne();
            assertThat(sync.idOf(sun)).isEqualTo(-1);
            assertThat(lights.shadow(sunId)).as("removed: none").isSameAs(com.truesim.scene.core.ShadowSpec.NONE);
            // many: the ids grow
            for (int i = 0; i < 10; i++) {
                long node = scene.create();
                scene.components().set(node, LightSource.TYPE, LightSource.point(1, 1, 1, 1));
            }
            scene.update();
            sync.update();
            assertThat(sync.count()).isEqualTo(11);
            assertThat(lights.capacity()).isGreaterThanOrEqualTo(11);
        }
    }
}
