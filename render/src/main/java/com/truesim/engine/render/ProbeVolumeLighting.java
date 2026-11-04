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
import com.truesim.engine.vulkan.Commands;
import java.util.List;
import java.util.Objects;
import org.lwjgl.system.MemoryStack;

/**
 * A view's surfaces lit by global illumination, as {@link #MODE} chooses: with {@link Mode#PROBES}, the
 * {@link ProbeVolume}'s diffuse light replaces the environment's spherical harmonics (every shading path reads the
 * view's indirect-light table: opaque on both shading techniques, blended and transmissive surfaces) and its sky
 * visibility dims the environment's reflections, where the volume reaches; elsewhere the environment lights as before.
 * It modifies the view's {@link Keys#INDIRECT_LIGHT} (an {@link EnvironmentLighting} of the view provides it). Views
 * given equal volumes (a headset's eyes, design D7) share one: the volume is updated once a frame.
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

    /** Global illumination's techniques ({@link #MODE}). */
    public enum Mode {
        /** None: the environment's light alone. */
        OFF,
        /** The ray-traced probe volume (devices with ray tracing; elsewhere {@link #OFF}). */
        PROBES
    }

    /** Global illumination; the graph rebuilds when it changes. */
    public static final Setting<Mode> MODE = Setting.choice("render.gi", Mode.PROBES,
        "global illumination: off, or the ray-traced probe volume");

    private final SceneView view;
    private final ProbeVolume volume;

    /** {@code view} lit by {@code volume}. */
    public ProbeVolumeLighting(SceneView view, ProbeVolume volume) {
        this.view = Objects.requireNonNull(view, "view");
        this.volume = Objects.requireNonNull(volume, "volume");
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

    @Override
    @AllowAllocation("building the graph, when it changes")
    public void build(GraphBuilder g) {
        view.checkBuilding(g, this);
        if (g.setting(MODE) == Mode.OFF || !g.capabilities().rayTracing()) {
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
