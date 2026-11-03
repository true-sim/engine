package com.truesim.engine.xr;

import com.truesim.engine.app.Frame;
import com.truesim.engine.input.Device;
import com.truesim.engine.input.DeviceIdentity;
import com.truesim.engine.input.Input;
import com.truesim.engine.input.InputSource;

/**
 * The headset's input fed to the engine's input (application-api.md D64, D119): each frame, after
 * {@link Headset#beginFrame} synced the OpenXR actions, its buttons, touches, axes and poses are written into one
 * device of {@link XrControls#XR}, which input takes with the other devices (D59). Rumble vibrates both hands (D66).
 * Added by the {@link Xr} plugin when input is installed. Allocates nothing per frame.
 */
final class XrSource implements InputSource {

    private final Headset headset;
    private final XrState fixed; // a test's state, or null: the headset's
    private final double[] position = new double[3];
    private final float[] orientation = new float[4];
    private Device device;

    XrSource(Headset headset) {
        this.headset = headset;
        this.fixed = null;
    }

    XrSource(XrState state) {
        this.headset = null;
        this.fixed = state;
    }

    @Override
    public void start(Input input) {
        input.addDeviceClass(XrControls.XR);
        device = input.connect(XrControls.XR, 0, new DeviceIdentity("openxr", "", "headset"), "headset", this);
    }

    Device device() {
        return device;
    }

    private XrState state() {
        if (fixed != null) {
            return fixed;
        }
        return headset.running() ? headset.input() : null;
    }

    @Override
    public void sample(Frame frame) {
        XrState state = state();
        if (state == null) {
            for (int i = 0; i < XrControls.XR.count(); i++) {
                device.set(i, 0);
                if (XrControls.XR.control(i).kind() == com.truesim.engine.input.ControlKind.POSE) {
                    device.lose(i);
                }
            }
            return;
        }
        for (Hand hand : XrControls.HANDS) {
            int at = hand.ordinal() * XrControls.PER_HAND;
            for (XrButton button : XrControls.BUTTONS) {
                device.set(at++, state.pressed(hand, button) ? 1 : 0);
            }
            for (XrTouch touch : XrControls.TOUCHES) {
                device.set(at++, state.touched(hand, touch) ? 1 : 0);
            }
            for (XrAxis axis : XrControls.AXES) {
                device.set(at++, state.axis(hand, axis));
            }
            for (XrPose pose : XrControls.POSES) {
                if (state.pose(hand, pose, position, orientation)) {
                    pose(at);
                } else {
                    device.lose(at);
                }
                at++;
            }
        }
        if (state.head(position, orientation)) {
            pose(XrControls.HEAD);
        } else {
            device.lose(XrControls.HEAD);
        }
        if (state.gaze(position, orientation)) {
            pose(XrControls.GAZE);
        } else {
            device.lose(XrControls.GAZE);
        }
    }

    private void pose(int control) {
        device.pose(control, position[0], position[1], position[2], orientation[0], orientation[1], orientation[2],
            orientation[3]);
    }

    @Override
    public boolean rumble(Device rumbled, float low, float high, int millis) {
        XrState state = state();
        if (state == null) {
            return false;
        }
        float amplitude = Math.max(low, high);
        for (Hand hand : XrControls.HANDS) {
            state.vibrate(hand, amplitude, 0, millis * 1_000_000L);
        }
        return true;
    }
}
