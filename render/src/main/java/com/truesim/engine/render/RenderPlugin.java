package com.truesim.engine.render;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.app.Plugin;
import com.truesim.engine.app.PluginKey;
import com.truesim.engine.app.Stage;
import com.truesim.engine.app.Startup;
import com.truesim.engine.settings.SettingRegistry;

/**
 * The renderer's settings in the engine's registry, for discovery: consoles, settings screens and dumps list them from
 * {@link SettingRegistry#all(String)} ({@code "render"}, {@code "display"}, {@code "shadows"}) instead of a hard list.
 * It registers the core renderer's ({@link RenderSettings#register}) and those of the techniques this module still
 * holds; a library's plugin registers its own techniques' the same way, in {@link Stage#BEFORE_INSTANCE}.
 *
 * <pre>{@code
 * app.use(new Desktop(), new Rendering(), new RenderPlugin());
 * List<Setting<?>> render = engine.registry().all("render");   // render.ao, render.ao.bitmask.radius, ...
 * }</pre>
 */
public final class RenderPlugin implements Plugin {

    /** Its key. */
    public static final PluginKey<RenderPlugin> KEY = PluginKey.of("render", RenderPlugin.class);

    @Override
    public PluginKey<?> key() {
        return KEY;
    }

    @Override
    @AllowAllocation("starting up")
    public void start(Stage stage, Startup startup) {
        if (stage == Stage.BEFORE_INSTANCE) {
            SettingRegistry registry = startup.registry();
            RenderSettings.register(registry);
            // Techniques that are optional libraries' (effects-gi) once they move: their plugins call these then
            AmbientOcclusion.register(registry);
            ScreenSpaceReflections.register(registry);
            ProbeVolumeLighting.register(registry);
            ReferencePathTracer.register(registry);
        }
    }
}
