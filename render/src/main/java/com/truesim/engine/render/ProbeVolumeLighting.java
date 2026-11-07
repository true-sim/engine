package com.truesim.engine.render;

import static org.lwjgl.system.MemoryStack.stackPush;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.graph.Id;
import com.truesim.engine.graph.Buffer;
import com.truesim.engine.graph.BufferAccess;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Key;
import com.truesim.engine.graph.PassContext;
import com.truesim.engine.graph.Technique;
import com.truesim.engine.render.layout.HookDataLayout;
import com.truesim.engine.settings.Setting;
import com.truesim.engine.settings.SettingRegistry;
import com.truesim.engine.settings.TechniqueConfig;
import com.truesim.engine.settings.TechniqueConfig.Tunable;
import com.truesim.engine.settings.TechniqueKind;
import com.truesim.engine.settings.TechniqueSlot;
import com.truesim.engine.settings.Techniques;
import com.truesim.engine.vulkan.Commands;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.lwjgl.system.MemoryStack;

/**
 * A view's surfaces lit by global illumination, as the kind chosen for {@link #SLOT} ({@code render.gi}) does it: with
 * {@link #PROBES} (the default), the {@link ProbeVolume}'s (with {@link #CASCADES}, the first of {@link
 * RadianceCascades} made of its scene, lights, environment and anchor, sampled the same way) diffuse light replaces the
 * environment's spherical harmonics and its sky visibility dims the environment's reflections, where the volume
 * reaches; elsewhere the environment lights as before. Shading samples it through the probe volume's shading hook
 * ({@link ProbeVolume#HOOK}, probes/hook.slang), which this technique turns on for the view and whose slot of the
 * view's table it writes: every shading path running the view's hooks (opaque on both shading techniques, blended and
 * transmissive surfaces, AO, reflections) gets it, and the core's shading knows no probe format. It modifies the view's {@link Keys#INDIRECT_LIGHT} (an
 * {@link EnvironmentLighting} of the view provides it). Views given equal volumes (a headset's eyes, design D7) share
 * one: the volume is updated once a frame. Its tunables are the probes kind's ({@link Probes}, {@code
 * render.gi.probes.*}), which the volume reads.
 *
 * <p>A game's own kind ({@link Techniques#register}) makes a {@link Technique} built in this one's place, given this
 * one ({@link #view()}, {@link #volume()}): it modifies {@link Keys#INDIRECT_LIGHT}.
 *
 * <pre>{@code
 * ProbeVolume volume = new ProbeVolume(rayTracing, renderer, lights, environment, leftEye);
 * leftEye.port().add(new ProbeVolumeLighting(leftEye, volume));
 * rightEye.port().add(new ProbeVolumeLighting(rightEye, volume));
 * }</pre>
 */
public final class ProbeVolumeLighting implements Technique {

    /** The id of a view's global illumination. */
    public static final Id<ProbeVolumeLighting> ID = Id.of("truesim.globalIllumination", ProbeVolumeLighting.class);

    /** Where global illumination plugs in: {@code render.gi}, a kind's id; the graph rebuilds when it changes. */
    public static final TechniqueSlot<ProbeVolumeLighting, Technique> SLOT = TechniqueSlot.of("render.gi", "probes",
        true, "global illumination: off, the ray-traced probe volume or radiance cascades",
        techniques -> techniques.register(ProbeVolumeLighting.SLOT, ProbeVolumeLighting.PROBES)
            .register(ProbeVolumeLighting.SLOT, ProbeVolumeLighting.CASCADES));

    /**
     * The probe volume's tunables ({@link ProbeVolume}'s settings, each named there): those of the volume's layout and
     * rays rebuild the graph when they change, the rest are read as frames record.
     */
    public record Probes(
        @Tunable(value = "cascades of the probe volume", min = 1, max = 6,
            formerly = "render.gi.volume.cascades") int cascades,
        @Tunable(value = "probes along each horizontal axis of a probe volume cascade", min = 2, max = 32,
            formerly = "render.gi.volume.probes") int probes,
        @Tunable(value = "probes along the vertical axis of a probe volume cascade", min = 2, max = 32,
            formerly = "render.gi.volume.probesUp") int probesUp,
        @Tunable(value = "the spacing of the probe volume's first cascade, in meters", min = 0.05, max = 1000,
            formerly = "render.gi.volume.spacing") float spacing,
        @Tunable(value = "rays per probe and update", min = 32, max = 256,
            formerly = "render.gi.volume.rays") int rays,
        @Tunable(value = "rays per probe in a fixed set, for relocation and classification", min = 8, max = 128,
            formerly = "render.gi.volume.fixedRays") int fixedRays,
        @Tunable(value = "the share of probes updated each frame, in turns", min = 0.01, max = 1,
            formerly = "render.gi.volume.budget") float budget,
        @Tunable(value = "surfaces a probe ray may pass through, plus one", min = 1, max = 16,
            formerly = "render.gi.volume.layers") int layers,
        @Tunable(value = "how far a shaded point moves along its normal before sampling probes, of their spacing",
            min = 0, max = 2, formerly = "render.gi.volume.normalBias") float normalBias,
        @Tunable(value = "how far a shaded point moves towards the viewer before sampling probes, of their spacing",
            min = 0, max = 2, formerly = "render.gi.volume.viewBias") float viewBias,
        @Tunable(value = "of a probe cascade from its edge, the part over which it blends into the next",
            min = 0.01, max = 0.5, formerly = "render.gi.volume.blend") float blend,
        @Tunable(value = "the longest distance a probe's distance moments hold, of its spacing", min = 0.5, max = 100,
            formerly = "render.gi.volume.depthRange") float depthRange,
        @Tunable(value = "probes move off surfaces near them",
            formerly = "render.gi.volume.relocate") boolean relocate,
        @Tunable(value = "probes inside geometry are turned off",
            formerly = "render.gi.volume.classify") boolean classify,
        @Tunable(value = "updates a probe's history holds at most (the lag of a still scene)", min = 1, max = 1024,
            formerly = "render.gi.response.maxFrames") int maxFrames,
        @Tunable(value = "a probe restarts its history where an update disagrees with it beyond its noise",
            formerly = "render.gi.response.adaptive") boolean adaptive,
        @Tunable(value = "deviations of a probe update's noise beyond which its history restarts", min = 0.5, max = 100,
            formerly = "render.gi.response.sensitivity") float sensitivity,
        @Tunable(value = "how far a probe's updates may drift from its history, relative, before it restarts",
            min = 0.001, max = 1, formerly = "render.gi.response.drift") float drift,
        @Tunable(value = "probes near changed instances and lights restart their histories at once",
            formerly = "render.gi.response.invalidate") boolean invalidate,
        @Tunable(value = "how many times its reach a changed light restarts probes within", min = 1, max = 100,
            formerly = "render.gi.response.lightMargin") float lightMargin,
        @Tunable(value = "updates a frame of the probes whose history restarted (bounces of a change per frame)",
            min = 1, max = 4, formerly = "render.gi.response.iterations") int iterations,
        @Tunable(value = "how many times its bounding sphere a changed instance restarts probes within",
            min = 1, max = 100, formerly = "render.gi.response.instanceMargin") float instanceMargin,
        @Tunable(value = "updates a restarted probe makes every frame before it takes its turns of the budget",
            min = 0, max = 1024) int fresh,
        @Tunable(value = "global illumination's updates run on the async compute queue") boolean async)
        implements TechniqueConfig {
    }

    /** The ray-traced probe volume (devices with ray tracing; elsewhere none). */
    public static final TechniqueKind<Probes, ProbeVolumeLighting, Technique> PROBES = TechniqueKind.of(SLOT, "probes",
        new Probes(3, 16, 8, 1f, 128, 32, 0.5f, 4, 0.25f, 0.25f, 0.2f, 1.5f, true, true, 30, true, 4f, 0.01f, true, 2f,
            2, 3f, 8, false),
        "the ray-traced probe volume", (lighting, config) -> lighting.new Engine(false));

    /**
     * Radiance cascades' tunables ({@link RadianceCascades}' settings, each named there): all but {@code visibility}
     * and {@code classify} rebuild the graph when they change. The update's queue is the probes kind's {@code async}.
     */
    public record Cascades(
        @Tunable(value = "radiance cascades", min = 2, max = 6) int count,
        @Tunable(value = "probes along each horizontal axis of the first radiance cascade", min = 4, max = 32)
        int probes,
        @Tunable(value = "probes along the vertical axis of the first radiance cascade", min = 2, max = 32)
        int probesUp,
        @Tunable(value = "the spacing of the first radiance cascade's probes, in meters", min = 0.05, max = 1000)
        float spacing,
        @Tunable(value = "directions along each side of the first radiance cascade's octahedral tiles", min = 2,
            max = 8) int directions,
        @Tunable(value = "how long the first radiance cascade's intervals are, of its spacing", min = 0.25, max = 64)
        float interval,
        @Tunable(value = "radiance cascades merge only from intervals that continue a probe's") boolean visibility,
        @Tunable(value = "radiance cascade probes inside geometry are not sampled") boolean classify)
        implements TechniqueConfig {
    }

    /**
     * Radiance cascades in world space ({@link RadianceCascades}, made of the probe volume's scene, lights, environment
     * and anchor; no history: every probe's light found anew each frame; devices with ray tracing, elsewhere none).
     */
    public static final TechniqueKind<Cascades, ProbeVolumeLighting, Technique> CASCADES = TechniqueKind.of(SLOT,
        "cascades", new Cascades(4, 32, 16, 0.5f, 8, 2f, true, true), "radiance cascades in world space",
        (lighting, config) -> lighting.new Engine(true));

    private final SceneView view;
    private final ProbeVolume volume;
    private final Map<TechniqueKind<?, ?, ?>, Technique> made = new IdentityHashMap<>(); // per kind, once chosen

    /** {@code view} lit by {@code volume}. */
    public ProbeVolumeLighting(SceneView view, ProbeVolume volume) {
        this.view = Objects.requireNonNull(view, "view");
        this.volume = Objects.requireNonNull(volume, "volume");
    }

    /** The view it lights. */
    public SceneView view() {
        return view;
    }

    /** The probe volume the engine's kind lights it by. */
    public ProbeVolume volume() {
        return volume;
    }

    @Override
    public Id<? extends Technique> id() {
        return ID;
    }

    @Override
    public List<Key<?>> provides() {
        return List.of();
    }

    @Override
    public List<Key<?>> modifies() {
        return List.of(Keys.INDIRECT_LIGHT);
    }

    /** The chosen kind's technique: built in this one's place. */
    @Override
    @AllowAllocation("building the graph, when it changes")
    public void build(GraphBuilder g) {
        view.checkBuilding(g, this);
        TechniqueKind<?, ProbeVolumeLighting, Technique> kind = g.technique(SLOT);
        if (kind != null) {
            made.computeIfAbsent(kind, k -> make(kind)).build(g);
        }
    }

    /** {@code kind}'s technique, its config read from the view's graph's settings as they are when read. */
    @AllowAllocation("building the graph, when it changes")
    private <C extends Record & TechniqueConfig> Technique make(TechniqueKind<C, ProbeVolumeLighting, Technique> kind) {
        return kind.create(this, () -> kind.config(view.port().settings()));
    }

    /** {@link #PROBES}' and {@link #CASCADES}' technique: this one's own pass, lit by the volume or the cascades. */
    private final class Engine implements Technique {

        private final boolean cascades;

        Engine(boolean cascades) {
            this.cascades = cascades;
        }

        @Override
        public List<Key<?>> provides() {
            return List.of();
        }

        @Override
        @AllowAllocation("building the graph, when it changes")
        public void build(GraphBuilder g) {
            buildProbes(g, cascades);
        }
    }

    /** The probe volume's lighting, where the device traces rays. */
    @AllowAllocation("building the graph, when it changes")
    private void buildProbes(GraphBuilder g, boolean cascades) {
        if (!g.capabilities().rayTracing()) {
            return;
        }
        Buffer table = g.modify(Keys.INDIRECT_LIGHT);
        ProbeVolume.Volume probes = cascades ? g.request(RadianceCascades.of(volume)) : g.request(volume);
        int hook = ProbeVolume.hookIndex(volume.renderer());
        g.pass("probe volume lighting").write(table, BufferAccess.TRANSFER_DST)
            .read(probes.data, BufferAccess.STORAGE_READ).record(ctx -> write(ctx, table, probes, hook));
        IndirectInputs.Set inputs = g.request(new IndirectInputs(g.view())).hook(hook);
        probes.readBy(inputs);
    }

    /** The probe volume hook's slot of the table: the volume, and where the view's camera is from its origin. */
    private void write(PassContext ctx, Buffer table, ProbeVolume.Volume probes, int hook) {
        try (MemoryStack stack = stackPush()) {
            long slot = stack.ncalloc(16, 1, HookDataLayout.BYTES);
            ProbeVolume.putHook(slot, probes.address(ctx), view.view().position(0) - probes.origin(0),
                view.view().position(1) - probes.origin(1), view.view().position(2) - probes.origin(2));
            Commands.updateBuffer(ctx.unsafe().commands(), ctx.unsafe().buffer(table), ShadingHooks.slot(hook),
                HookDataLayout.BYTES, slot);
        }
    }

    /**
     * Registers the indirect light's settings in {@code registry} for discovery ({@link SettingRegistry#all(String)}):
     * this slot, the world lights' slot (their kinds' tunables with them), the probe volume's and the radiance
     * cascades' settings, hidden. The plugin of the module holding them calls this.
     */
    public static void register(SettingRegistry registry) {
        registry.addHiddenSlots(SLOT, WorldLights.SLOT).addHidden(ProbeVolume.ALL.toArray(new Setting<?>[0]))
            .addHidden(RadianceCascades.ALL.toArray(new Setting<?>[0]));
    }
}
