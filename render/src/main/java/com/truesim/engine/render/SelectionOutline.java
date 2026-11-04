package com.truesim.engine.render;

import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
import static org.lwjgl.vulkan.VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.graph.Id;
import com.truesim.engine.graph.Access;
import com.truesim.engine.graph.Anchor;
import com.truesim.engine.graph.FrameGraph;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Image;
import com.truesim.engine.graph.ImageDesc;
import com.truesim.engine.graph.Key;
import com.truesim.engine.graph.Order;
import com.truesim.engine.graph.Technique;
import com.truesim.engine.render.layout.OutlinePush;
import com.truesim.engine.settings.Setting;
import com.truesim.engine.settings.TechniqueConfig;
import com.truesim.engine.settings.TechniqueConfig.Tunable;
import com.truesim.engine.settings.TechniqueKind;
import com.truesim.engine.settings.TechniqueSlot;
import com.truesim.engine.vulkan.FrameBuffer;
import com.truesim.engine.vulkan.Format;
import com.truesim.engine.vulkan.Pipeline;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * How a view shows selected and hovered scene nodes (application-api.md, picking and selection), as the kind chosen
 * for {@link #SLOT} ({@code render.selection}) draws it: {@link #OUTLINE} (the default), a band of the selection's
 * colour around what the view shows of each highlighted object, the hovered colour where only hovered, smooth at its
 * edge; or {@link #TINT}, the colour blended over the highlighted objects themselves; or off. What is highlighted is
 * a {@link SelectionHighlights} (instance ids, filled by engine-scene's {@code SceneHighlights} from the scene's
 * {@code Selected} and {@code Hovered} components).
 *
 * <p>It reads the view's IDs: the visibility buffer's ({@link Keys#VISIBILITY_IDS}) where the view is shaded so (nothing
 * drawn twice), else an ID draw of its own through a render view of its own while the slot is not off (about the cost
 * of a depth pre-pass, for forward views; set that view's camera as the main one's). A forward view builds that draw
 * only while something is highlighted, and every view skips its passes' work in frames with nothing highlighted: with
 * nothing selected or hovered (or the slot off) it costs nothing. Two compute passes over the render region: a mask of the highlighted pixels (one byte each), then the
 * outline or tint written into {@link Keys#SCENE_COLOR} in place. It runs at {@link Anchor#TRANSPARENT}, after
 * transparency, so blended surfaces in front of an outline do not hide it, and before post effects. Only what the view
 * shows is outlined: the visible silhouette, its edge against what hides part of it included. Allocates nothing per
 * frame.
 *
 * <pre>{@code
 * port.add(new SelectionOutline(graph, geometry, view.view(), view, depthFormat, highlights));   // visibility buffer
 * SelectionOutline.SLOT.choose(graph.settings(), SelectionOutline.TINT);    // render.selection=tint
 * }</pre>
 */
public final class SelectionOutline implements Technique {

    /** The id of a view's selection outline. */
    public static final Id<SelectionOutline> ID = Id.of("truesim.selectionOutline", SelectionOutline.class);

    /** Where selection highlighting plugs in: {@code render.selection}, its kind chosen by id; the graph rebuilds. */
    public static final TechniqueSlot<SelectionOutline, Technique> SLOT = TechniqueSlot.of("render.selection",
        "outline", true, "how selected and hovered scene nodes show: off, an outline around them, or a tint over them",
        techniques -> techniques.register(SelectionOutline.SLOT, SelectionOutline.OUTLINE)
            .register(SelectionOutline.SLOT, SelectionOutline.TINT));

    /**
     * The tunables of the engine's kinds, read as frames record (no rebuild).
     *
     * @param width the outline's width in pixels (the tint ignores it)
     * @param selectedRed the selected colour, linear scene light
     * @param selectedGreen the selected colour
     * @param selectedBlue the selected colour
     * @param selectedOpacity how much of the selected colour covers the scene
     * @param hoveredRed the hovered colour
     * @param hoveredGreen the hovered colour
     * @param hoveredBlue the hovered colour
     * @param hoveredOpacity how much of the hovered colour covers the scene
     */
    public record Style(
        @Tunable(value = "the selection outline's width in pixels", min = 1, max = 8) int width,
        @Tunable(value = "the selected colour's red, linear", min = 0, max = 100) float selectedRed,
        @Tunable(value = "the selected colour's green, linear", min = 0, max = 100) float selectedGreen,
        @Tunable(value = "the selected colour's blue, linear", min = 0, max = 100) float selectedBlue,
        @Tunable(value = "how much the selected colour covers", min = 0, max = 1) float selectedOpacity,
        @Tunable(value = "the hovered colour's red, linear", min = 0, max = 100) float hoveredRed,
        @Tunable(value = "the hovered colour's green, linear", min = 0, max = 100) float hoveredGreen,
        @Tunable(value = "the hovered colour's blue, linear", min = 0, max = 100) float hoveredBlue,
        @Tunable(value = "how much the hovered colour covers", min = 0, max = 1) float hoveredOpacity)
        implements TechniqueConfig {
    }

    /** An outline: orange for the selection, light blue for hover, two pixels wide. The default. */
    public static final TechniqueKind<Style, SelectionOutline, Technique> OUTLINE = TechniqueKind.of(SLOT, "outline",
        new Style(2, 1.0f, 0.45f, 0.05f, 1, 0.25f, 0.6f, 1.0f, 0.9f), "an outline around selected and hovered objects",
        (outline, config) -> outline.new Pass(0, config));
    /** A tint over the highlighted objects. */
    public static final TechniqueKind<Style, SelectionOutline, Technique> TINT = TechniqueKind.of(SLOT, "tint",
        new Style(2, 1.0f, 0.45f, 0.05f, 0.35f, 0.25f, 0.6f, 1.0f, 0.25f), "a tint over selected and hovered objects",
        (outline, config) -> outline.new Pass(1, config));

    /** Every setting of the selection outline: the choice and the kinds' tunables. */
    public static final List<Setting<?>> ALL = all();

    private final FrameGraph graph;
    private final SceneGeometry geometry;
    private final RenderView ids;
    private final SceneView view;
    private final int depthFormat;
    private final SelectionHighlights highlights;
    private final Map<TechniqueKind<?, ?, ?>, Technique> made = new IdentityHashMap<>();
    private Boolean occlusion;
    private boolean ownIds; // the last build drew IDs of its own: a forward view
    private FrameBuffer flags;
    private final List<FrameBuffer> outgrown = new ArrayList<>(); // freed with it: frames in flight may read them
    private int flagWords;
    private long uploaded = -1;
    private int uploadedWords;

    /**
     * Highlighting of {@code highlights} in {@code view} of {@code graph}: from the visibility buffer's IDs where the
     * view has them, else from {@code geometry}'s ID draw of {@code ids} (a render view of its own looking through the
     * view's camera, with depth of {@code depthFormat}); added to {@code view}'s port.
     */
    public SelectionOutline(FrameGraph graph, SceneGeometry geometry, RenderView ids, SceneView view,
        int depthFormat, SelectionHighlights highlights) {
        this.graph = Objects.requireNonNull(graph, "graph");
        this.geometry = Objects.requireNonNull(geometry, "geometry");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.view = Objects.requireNonNull(view, "view");
        this.depthFormat = depthFormat;
        this.highlights = Objects.requireNonNull(highlights, "highlights");
    }

    private static List<Setting<?>> all() {
        List<Setting<?>> all = new ArrayList<>();
        all.add(SLOT.setting());
        all.addAll(OUTLINE.settings());
        all.addAll(TINT.settings());
        return List.copyOf(all);
    }

    /** The view it works in. */
    public SceneView view() {
        return view;
    }

    /** Its highlights. */
    public SelectionHighlights highlights() {
        return highlights;
    }

    /** Two-phase occlusion culling of its own ID draw (forward views); the graph's setting by default. */
    @AllowAllocation("set up rarely")
    public SelectionOutline occlusion(boolean on) {
        occlusion = on;
        return this;
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
        return List.of(Keys.SCENE_COLOR);
    }

    @Override
    public Order order() {
        return Order.at(Anchor.TRANSPARENT).after(Transparency.ID);
    }

    /**
     * The flags' room (it rebuilds when the marks outgrow it) and, on a forward view, whether anything is highlighted:
     * its own ID draw is built only while something is, so a view with nothing highlighted costs nothing. The graph
     * builds when the frame is submitted, after the highlights of the frame are marked: no frame of delay.
     */
    @Override
    public long buildVersion() {
        long room = Math.max(1, Integer.highestOneBit(Math.max(1, highlights.words())) * 2);
        return ownIds && highlights.any() ? -room : room;
    }

    /** The chosen kind's technique, built in this one's place. */
    @Override
    @AllowAllocation("building the graph, when it changes")
    public void build(GraphBuilder g) {
        view.checkBuilding(g, this);
        TechniqueKind<?, SelectionOutline, Technique> kind = g.technique(SLOT);
        if (kind != null) {
            made.computeIfAbsent(kind, k -> make(kind)).build(g);
        }
    }

    @AllowAllocation("building the graph, when it changes")
    private <C extends Record & TechniqueConfig> Technique make(TechniqueKind<C, SelectionOutline, Technique> kind) {
        return kind.create(this, () -> kind.config(view.port().settings()));
    }

    /** One of the engine's kinds: the mask, then the outline ({@code mode} 0) or the tint (1). */
    private final class Pass implements Technique {

        private final int mode;
        private final java.util.function.Supplier<Style> style;

        Pass(int mode, java.util.function.Supplier<Style> style) {
            this.mode = mode;
            this.style = style;
        }

        @Override
        public List<Key<?>> provides() {
            return List.of();
        }

        @Override
        @AllowAllocation("building the graph, when it changes")
        public void build(GraphBuilder g) {
            buildPasses(g, mode, style);
        }
    }

    @AllowAllocation("building the graph, when it changes")
    private void buildPasses(GraphBuilder g, int mode, java.util.function.Supplier<Style> style) {
        int width = view.width();
        int height = view.height();
        Image shownIds = g.optional(Keys.VISIBILITY_IDS);
        ownIds = shownIds == null;
        if (ownIds && !highlights.any()) {
            return; // a forward view with nothing highlighted: no ID draw, no passes
        }
        Image color = g.modify(Keys.SCENE_COLOR);
        if (shownIds == null) {
            shownIds = g.request(new SceneDraw(geometry, ids, null, SurfaceOutput.ids(), depthFormat, width, height,
                (occlusion != null ? occlusion : g.setting(RenderSettings.OCCLUSION)) && ids.options().occlusion()))
                .color();
        }
        int words = (int) Math.abs(buildVersion());
        if (flags == null || flagWords < words) {
            if (flags != null) {
                outgrown.add(flags);
            }
            flags = FrameBuffer.create(graph.device(), words * 4L,
                VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT, graph.frameSlots());
            flagWords = words;
            uploaded = -1;
            uploadedWords = 0;
        }
        Image mask = g.create("selection mask", ImageDesc.of(Format.R8_UNORM, color.width(), color.height()));
        Pipeline maskProgram = g.computeProgram("shaders/selection/outline.spv", "maskMain", OutlinePush.BYTES);
        Pipeline outlineProgram = g.computeProgram("shaders/selection/outline.spv", "outlineMain", OutlinePush.BYTES);
        OutlinePush maskPush = new OutlinePush();
        OutlinePush outlinePush = new OutlinePush();
        Image shown = shownIds;
        g.pass("selection mask").read(shown, Access.STORAGE_READ).write(mask, Access.STORAGE_WRITE).record(ctx -> {
            upload();
            if (!highlights.any()) {
                return; // nothing highlighted this frame: nothing to mask
            }
            int w = ctx.regionWidth(color);
            int h = ctx.regionHeight(color);
            ctx.dispatch(maskProgram, maskPush.flags(flags.deviceAddress()).ids(ctx.storage(shown))
                .mask(ctx.storage(mask)).size(w | h << 16).instances(flagWords * 16), (w + 7) / 8, (h + 7) / 8,
                1);
        });
        g.pass("selection outline").read(mask, Access.STORAGE_READ).write(color, Access.STORAGE_WRITE).record(ctx -> {
            if (!highlights.any()) {
                return; // the colour stays as it is
            }
            Style s = style.get();
            int w = ctx.regionWidth(color);
            int h = ctx.regionHeight(color);
            ctx.dispatch(outlineProgram, outlinePush.mask(ctx.storage(mask)).color(ctx.storage(color))
                .size(w | h << 16).width(s.width()).mode(mode).selectedR(s.selectedRed())
                .selectedG(s.selectedGreen()).selectedB(s.selectedBlue()).selectedA(s.selectedOpacity())
                .hoveredR(s.hoveredRed()).hoveredG(s.hoveredGreen()).hoveredB(s.hoveredBlue())
                .hoveredA(s.hoveredOpacity()), (w + 7) / 8, (h + 7) / 8, 1);
        });
    }

    /** Hands the highlights to the frame's flags when they changed: up to the words marked now or before. */
    private synchronized void upload() {
        if (highlights.version() == uploaded) {
            return;
        }
        int words = Math.min(flagWords, Math.max(highlights.words(), uploadedWords));
        if (words > 0) {
            flags.put(0, highlights.segment(), 0, words * 4L);
        }
        uploaded = highlights.version();
        uploadedWords = Math.min(flagWords, highlights.words());
    }

    /** Frees its flags: call once the GPU is done with the frames. */
    public void close() {
        if (flags != null) {
            flags.close();
            flags = null;
        }
        for (FrameBuffer old : outgrown) {
            old.close();
        }
        outgrown.clear();
    }
}
