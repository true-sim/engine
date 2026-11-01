package com.truesim.engine.xr;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.app.Application;
import com.truesim.engine.app.Engine;
import com.truesim.engine.app.Frame;
import com.truesim.engine.app.FrameDriver;
import com.truesim.engine.app.Plugin;
import com.truesim.engine.app.PluginKey;
import com.truesim.engine.app.Stage;
import com.truesim.engine.app.Startup;
import com.truesim.engine.app.StartupFailure;
import com.truesim.engine.graph.Rendering;
import java.util.List;

/**
 * A VR headset as a plugin: connects before the GPU instance exists, tells {@link Rendering} the instance and device
 * extensions and the GPU the headset needs, starts its session once the frame graph exists, and drives frames: each
 * frame begins with the headset's wait, input and poses ({@link Headset#beginFrame}), so the game's update reads them,
 * and ends with the graph rendered and the eyes submitted. The runtime asking the app to quit becomes a quit request.
 *
 * <pre>{@code
 * app.use(new Rendering(), Xr.ifAvailable());               // no headset: the desktop only
 * engine.find(Xr.KEY).ifPresent(xr -> xr.headset().present(Eye.LEFT, leftEye, Keys.SCENE_COLOR));
 * }</pre>
 */
public final class Xr implements Plugin {

    /** Its key. */
    public static final PluginKey<Xr> KEY = PluginKey.of("xr", Xr.class);

    private final boolean optional;
    private Engine engine;
    private Rendering rendering;
    private Headset headset;

    private Xr(boolean optional) {
        this.optional = optional;
    }

    /** A headset the game cannot run without. */
    public static Xr required() {
        return new Xr(false);
    }

    /** A headset if there is one: without, the game runs on the desktop. */
    public static Xr ifAvailable() {
        return new Xr(true);
    }

    @Override
    public PluginKey<Xr> key() {
        return KEY;
    }

    @Override
    public List<PluginKey<?>> requires() {
        return List.of(Rendering.KEY);
    }

    @Override
    public boolean optional() {
        return optional;
    }

    @Override
    @AllowAllocation("starting up")
    public void start(Stage stage, Startup startup) {
        switch (stage) {
            case BEFORE_INSTANCE -> {
                engine = startup.engine();
                rendering = startup.get(Rendering.KEY);
                try {
                    headset = Headset.connect(engine.name());
                } catch (HeadsetException e) {
                    throw new StartupFailure("No headset: " + e.getMessage(), e);
                }
                rendering.requestInstanceExtensions(headset.vulkanInstanceExtensions())
                    .requestDeviceExtensions(headset.vulkanDeviceExtensions()).chooseGpu(headset::gpu);
            }
            case GRAPH -> headset.start(rendering.graph());
            case RUN -> startup.driveFrames(new HeadsetFrames());
            default -> {
                // nothing of its own in the other stages
            }
        }
    }

    /** The headset; from {@link Stage#BEFORE_INSTANCE}, its session from {@link Stage#GRAPH}. */
    public Headset headset() {
        if (headset == null) {
            throw new IllegalStateException("The headset connects in BEFORE_INSTANCE: not yet");
        }
        return headset;
    }

    @Override
    public void stop() {
        if (headset != null) {
            headset.close();
            headset = null;
        }
    }

    /** Frames paced by the headset: its wait, input and poses first; the graph rendered and the eyes submitted last. */
    private final class HeadsetFrames implements FrameDriver {
        @Override
        public void beginFrame(Frame frame) {
            headset.beginFrame();
        }

        @Override
        public void endFrame(Frame frame) {
            boolean rendered = false;
            try {
                rendering.presenter().render();
                rendered = true;
            } finally {
                headset.submit(rendered);
            }
            if (headset.exiting()) {
                engine.requestQuit(Application.QuitReason.PLUGIN, false);
            }
        }
    }
}
