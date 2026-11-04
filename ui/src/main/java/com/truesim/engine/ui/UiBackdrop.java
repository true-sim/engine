package com.truesim.engine.ui;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.graph.Id;
import com.truesim.engine.graph.Access;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Image;
import com.truesim.engine.graph.ImageDesc;
import com.truesim.engine.graph.Key;
import com.truesim.engine.graph.Technique;
import com.truesim.engine.render.Keys;
import com.truesim.engine.vulkan.Format;
import java.util.List;

/**
 * The scene of a view that shows only UI (a docking OS window): {@link Keys#SCENE_COLOR} cleared to opaque black at
 * the view's size, which the view's output transform tone maps and composites its {@link Keys#UI} over, so the UI
 * shows in SDR or HDR as the window's display takes it.
 */
public final class UiBackdrop implements Technique {

    /** Its id. */
    public static final Id<UiBackdrop> ID = Id.of("truesim.ui.backdrop", UiBackdrop.class);

    @Override
    public Id<? extends Technique> id() {
        return ID;
    }

    @Override
    public List<Key<?>> provides() {
        return List.of(Keys.SCENE_COLOR);
    }

    @Override
    @AllowAllocation("building the graph, when it changes")
    public void build(GraphBuilder g) {
        int width = g.view() == null ? 1 : Math.max(1, g.view().width());
        int height = g.view() == null ? 1 : Math.max(1, g.view().height());
        Image image = g.create("ui backdrop", ImageDesc.of(Format.R16G16B16A16_SFLOAT, width, height));
        g.pass("ui backdrop").write(image, Access.TRANSFER_DST)
            .record(ctx -> ctx.clear(image.whole(), 0, 0, 0, 1));
        g.provide(Keys.SCENE_COLOR, image);
    }
}
