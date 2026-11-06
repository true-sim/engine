package com.truesim.engine.xr;

import static org.assertj.core.api.Assertions.assertThat;

import com.truesim.common.state.ChangeClock;
import com.truesim.engine.app.AppConfig;
import com.truesim.engine.app.Application;
import com.truesim.engine.app.Engine;
import com.truesim.engine.input.Input;
import com.truesim.engine.interaction.PointerKind;
import com.truesim.engine.interaction.SceneInteraction;
import com.truesim.engine.interaction.ScenePointer;
import com.truesim.engine.interaction.ScenePointers;
import com.truesim.scene.core.SceneGraph;
import com.truesim.scene.core.SpatialIndex;
import org.junit.jupiter.api.Test;

/** The controllers' aim rays as the scene interaction's pointers, from a state the test sets: no headset needed. */
class XrSceneRaysTest {

    @Test
    void aTrackedControllersAimIsARayPointerAndItsTriggerItsButton() {
        Input input = new Input();
        SceneInteraction interaction = new SceneInteraction();
        XrSourceTest.State state = new XrSourceTest.State();
        try (Engine engine = Engine.create(new Application() {
            @Override
            public void configure(AppConfig app) {
                app.use(input, interaction).defaults(Engine.FRAME_RATE, 10_000);
            }
        })) {
            XrSceneRays.defaults(input);
            input.addSource(new XrSource(state));
            interaction.pointers(new XrSceneRays(input));
            SceneGraph scene = new SceneGraph(new ChangeClock());
            interaction.world(scene, new SpatialIndex(scene)); // the world the rays pick in
            engine.stepAt(0);
            engine.stepAt(10_000_000L);
            ScenePointer right = interaction.pointerById(ScenePointers.RIGHT_RAY_ID);
            assertThat(right).isNotNull();
            assertThat(right.kind()).isEqualTo(PointerKind.XR_RAY);
            double[] ray = new double[6];
            assertThat(right.ray(ray)).isTrue();
            // from the aim pose's position along its -Z (an identity orientation)
            assertThat(ray).containsExactly(new double[] {1, 2, 3, 0, 0, -1},
                org.assertj.core.data.Offset.offset(1e-9));
            assertThat(right.buttonBits() & ScenePointer.PRIMARY).isZero();
            assertThat(interaction.pointerById(ScenePointers.LEFT_RAY_ID)).as("the left hand untracked").isNull();
            // untracked: the pointer leaves
            state.tracked = false;
            engine.stepAt(20_000_000L);
            engine.stepAt(30_000_000L);
            assertThat(interaction.pointerById(ScenePointers.RIGHT_RAY_ID)).isNull();
        }
    }
}
