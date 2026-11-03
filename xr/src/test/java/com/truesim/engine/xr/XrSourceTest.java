package com.truesim.engine.xr;

import static org.assertj.core.api.Assertions.assertThat;

import com.truesim.engine.app.AppConfig;
import com.truesim.engine.app.Application;
import com.truesim.engine.app.Engine;
import com.truesim.engine.input.ActionGroup;
import com.truesim.engine.input.Binding;
import com.truesim.engine.input.BinaryAction;
import com.truesim.engine.input.Axis2Action;
import com.truesim.engine.input.Input;
import com.truesim.engine.input.PoseAction;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A headset's input as the engine's input sees it (D64, D119), from a state a test sets: no headset needed. */
class XrSourceTest {

    static final ActionGroup VR = ActionGroup.root("vr");
    static final BinaryAction GRAB = VR.binary("grab", Binding.of(XrControls.axis(Hand.RIGHT, XrAxis.SQUEEZE)));
    static final BinaryAction MENU = VR.binary("menu", Binding.of(XrControls.button(Hand.LEFT, XrButton.MENU)));
    static final Axis2Action WALK = VR.axis2("walk", Binding.axes2d(XrControls.axis(Hand.LEFT, XrAxis.THUMBSTICK_X),
        XrControls.axis(Hand.LEFT, XrAxis.THUMBSTICK_Y)));
    static final PoseAction AIM = VR.pose("aim", Binding.of(XrControls.pose(Hand.RIGHT, XrPose.AIM)));
    static final PoseAction HEAD = VR.pose("head", Binding.of(XrControls.head()));
    static final BinaryAction RESTING = VR.binary("resting", Binding.of(XrControls.touch(Hand.RIGHT,
        XrTouch.THUMBREST)));

    /** A headset's state, set by the test. */
    static final class State implements XrState {
        float squeeze;
        boolean menu;
        boolean tracked = true;
        final List<String> vibrations = new ArrayList<>();

        @Override
        public boolean pressed(Hand hand, XrButton button) {
            return hand == Hand.LEFT && button == XrButton.MENU && menu;
        }

        @Override
        public boolean touched(Hand hand, XrTouch touch) {
            return hand == Hand.RIGHT && touch == XrTouch.THUMBREST;
        }

        @Override
        public float axis(Hand hand, XrAxis axis) {
            if (hand == Hand.RIGHT && axis == XrAxis.SQUEEZE) {
                return squeeze;
            }
            return hand == Hand.LEFT && axis == XrAxis.THUMBSTICK_Y ? 0.5f : 0;
        }

        @Override
        public boolean pose(Hand hand, XrPose pose, double[] position, float[] orientation) {
            if (!tracked || hand != Hand.RIGHT || pose != XrPose.AIM) {
                return false;
            }
            position[0] = 1;
            position[1] = 2;
            position[2] = 3;
            orientation[3] = 1;
            return true;
        }

        @Override
        public boolean head(double[] position, float[] orientation) {
            position[1] = 1.7;
            orientation[3] = 1;
            return tracked;
        }

        @Override
        public boolean gaze(double[] position, float[] orientation) {
            return false;
        }

        @Override
        public void vibrate(Hand hand, float amplitude, float frequency, long nanos) {
            vibrations.add(hand + " " + amplitude + " " + nanos);
        }
    }

    @Test
    void theHeadsetsControlsDriveActionsLikeAnyDevice() {
        Input input = new Input().add(VR);
        State state = new State();
        try (Engine engine = Engine.create(new Application() {
            @Override
            public void configure(AppConfig app) {
                app.use(input).defaults(Engine.FRAME_RATE, 10_000);
            }
        })) {
            XrSource source = new XrSource(state);
            input.addSource(source);
            state.squeeze = 0.7f;
            engine.stepAt(0);
            assertThat(input.player(0).down(GRAB)).isTrue();
            assertThat(input.player(0).down(MENU)).isFalse();
            assertThat(input.player(0).down(RESTING)).isTrue();
            assertThat(input.player(0).y(WALK)).isEqualTo(0.5f);
            double[] position = new double[3];
            float[] orientation = new float[4];
            assertThat(input.player(0).pose(AIM, position, orientation)).isTrue();
            assertThat(position).containsExactly(1, 2, 3);
            assertThat(input.player(0).pose(HEAD, position, orientation)).isTrue();
            assertThat(position[1]).isEqualTo(1.7);
            // Rebound like any other binding (D64): the menu button grabs.
            input.bind(input.player(0), GRAB, input.parse("xr:LEFT_MENU").getFirst());
            state.menu = true;
            state.tracked = false;
            engine.stepAt(10_000_000L);
            assertThat(input.player(0).down(GRAB)).isTrue();
            assertThat(input.player(0).down(MENU)).isTrue();
            assertThat(input.player(0).pose(AIM, position, orientation)).isFalse();
            assertThat(input.player(0).rumble(0.2f, 0.5f, 30)).isTrue();
            assertThat(state.vibrations).containsExactly("LEFT 0.5 30000000", "RIGHT 0.5 30000000");
            assertThat(input.player(0).glyph(GRAB).toString()).isEqualTo("xr:LEFT_MENU");
            assertThat(source.device().name()).isEqualTo("headset");
            assertThat(XrControls.gaze().toString()).isEqualTo("xr:GAZE_POSE");
            assertThat(XrControls.XR.control("RIGHT_TRIGGER_AXIS")).isSameAs(XrControls.axis(Hand.RIGHT,
                XrAxis.TRIGGER));
        }
    }
}
