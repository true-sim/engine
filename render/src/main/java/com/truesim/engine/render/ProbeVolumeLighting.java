package com.truesim.engine.render;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.memPutFloat;
import static org.lwjgl.system.MemoryUtil.memPutInt;
import static org.lwjgl.system.MemoryUtil.memPutLong;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.graph.Id;
import com.truesim.engine.graph.Buffer;
import com.truesim.engine.graph.BufferAccess;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Key;
import com.truesim.engine.graph.PassContext;
import com.truesim.engine.graph.Technique;
import com.truesim.engine.render.layout.IndirectConstants;
import com.truesim.engine.render.layout.IndirectLightLayout;
import com.truesim.engine.settings.Setting;
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
 * A view's surfaces lit by global illumination, as the kind chosen for {@link #SLOT} ({@code render.gi}) does it:
 * with {@link #PROBES} (the default), the
 * {@link ProbeVolume}'s diffuse light replaces the environment's spherical harmonics (every shading path reads the
 * view's indirect-light table: opaque on both shading techniques, blended and transmissive surfaces) and its sky
 * visibility dims the environment's reflections, where the volume reaches; elsewhere the environment lights as before.
 * It modifies the view's {@link Keys#INDIRECT_LIGHT} (an {@link EnvironmentLighting} of the view provides it). Views
 * given equal volumes (a headset's eyes, design D7) share one: the volume is updated once a frame. Its tunables are
 * the probes kind's ({@link Probes}, {@code render.gi.probes.*}), which the volume reads.
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
        true, "global illumination: off, or the ray-traced probe volume",
        techniques -> techniques.register(ProbeVolumeLighting.SLOT, ProbeVolumeLighting.PROBES));

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
            min = 1, max = 100, formerly = "render.gi.response.instanceMargin") float instanceMargin)
        implements TechniqueConfig {
    }

    /** The ray-traced probe volume (devices with ray tracing; elsewhere none). */
    public static final TechniqueKind<Probes, ProbeVolumeLighting, Technique> PROBES = TechniqueKind.of(SLOT, "probes",
        new Probes(3, 16, 8, 1f, 128, 32, 1f, 4, 0.25f, 0.25f, 0.2f, 1.5f, true, true, 30, true, 4f, 0.01f, true, 2f, 2,
            3f),
        "the ray-traced probe volume", (lighting, config) -> lighting.new Engine());

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

    /** {@link #PROBES}' technique: this one's own pass. */
    private final class Engine implements Technique {

        @Override
        public List<Key<?>> provides() {
            return List.of();
        }

        @Override
        @AllowAllocation("building the graph, when it changes")
        public void build(GraphBuilder g) {
            buildProbes(g);
        }
    }

    /** The probe volume's lighting, where the device traces rays. */
    @AllowAllocation("building the graph, when it changes")
    private void buildProbes(GraphBuilder g) {
        if (!g.capabilities().rayTracing()) {
            return;
        }
        Buffer table = g.modify(Keys.INDIRECT_LIGHT);
        ProbeVolume.Volume probes = g.request(volume);
        g.pass("probe volume lighting").write(table, BufferAccess.TRANSFER_DST)
            .read(probes.data, BufferAccess.STORAGE_READ).record(ctx -> write(ctx, table, probes));
        probes.readBy(g.request(new IndirectInputs(g.view())));
    }

    /** The table's flags and its probe volume section: the volume, and where the view's camera is from its origin. */
    private void write(PassContext ctx, Buffer table, ProbeVolume.Volume probes) {
        try (MemoryStack stack = stackPush()) {
            int bytes = IndirectLightLayout.BYTES - IndirectLightLayout.RESERVED;
            long section = stack.ncalloc(16, 1, bytes);
            memPutLong(section + IndirectLightLayout.VOLUME - IndirectLightLayout.RESERVED, probes.address(ctx));
            for (int axis = 0; axis < 3; axis++) {
                memPutFloat(section + IndirectLightLayout.VOLUME_OFFSET - IndirectLightLayout.RESERVED + 4L * axis,
                    (float) (view.view().position(axis) - probes.origin(axis)));
            }
            long flags = stack.ncalloc(4, 1, 4);
            memPutInt(flags, IndirectConstants.INDIRECT_ENVIRONMENT | IndirectConstants.INDIRECT_PROBES);
            long handle = ctx.unsafe().buffer(table);
            Commands.updateBuffer(ctx.unsafe().commands(), handle, IndirectLightLayout.FLAGS, 4, flags);
            Commands.updateBuffer(ctx.unsafe().commands(), handle, IndirectLightLayout.RESERVED, bytes, section);
        }
    }
}
