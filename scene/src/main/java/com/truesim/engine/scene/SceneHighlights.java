package com.truesim.engine.scene;

import com.truesim.engine.render.SelectionHighlights;
import com.truesim.scene.core.ComponentStore;
import com.truesim.scene.core.Hovered;
import com.truesim.scene.core.NodeHandle;
import com.truesim.scene.core.SceneGraph;
import com.truesim.scene.core.Selected;
import java.util.Objects;

/**
 * The scene's {@link Selected} and {@link Hovered} components as the instances a {@code SelectionOutline} highlights:
 * each marked node's renderable and those of every node below it (a model's root selected outlines the whole model),
 * by the instance ids its {@link SceneSync} draws them as. Marks again when the selection or hover changed, or when the
 * sync's instances did (a mesh loaded under a selected node: {@link SceneSync#instanceVersion}); otherwise nothing.
 *
 * <pre>{@code
 * SelectionHighlights highlights = new SelectionHighlights();
 * SceneHighlights fill = new SceneHighlights(sync, highlights);
 * port.add(new SelectionOutline(graph, geometry, view, depthFormat, highlights));
 * // each frame, after sync.update():
 * fill.update();
 * }</pre>
 *
 * <p>Allocates nothing per frame once grown; one thread.
 */
public final class SceneHighlights {

    private final SceneSync sync;
    private final SceneGraph scene;
    private final SelectionHighlights out;
    private final ComponentStore<Selected> selected;
    private final ComponentStore<Hovered> hovered;
    private long selectedSeen = -1;
    private long hoveredSeen = -1;
    private long instancesSeen = -1;
    private long marks;

    /** {@code sync}'s scene's selection and hover, marked in {@code highlights}. */
    public SceneHighlights(SceneSync sync, SelectionHighlights highlights) {
        this.sync = Objects.requireNonNull(sync, "sync");
        this.out = Objects.requireNonNull(highlights, "highlights");
        this.scene = sync.scene();
        this.selected = scene.components().store(Selected.TYPE);
        this.hovered = scene.components().store(Hovered.TYPE);
    }

    /** The highlights it fills. */
    public SelectionHighlights highlights() {
        return out;
    }

    /** How many times it marked anew (a test counts them). */
    public long marks() {
        return marks;
    }

    /** Marks anew if the selection, the hover or the drawn renderables changed. */
    public void update() {
        if (selected.version() == selectedSeen && hovered.version() == hoveredSeen
            && sync.instanceVersion() == instancesSeen) {
            return;
        }
        selectedSeen = selected.version();
        hoveredSeen = hovered.version();
        instancesSeen = sync.instanceVersion();
        marks++;
        out.begin();
        for (int i = 0; i < hovered.size(); i++) {
            subtree(hovered.node(i), SelectionHighlights.HOVERED);
        }
        for (int i = 0; i < selected.size(); i++) {
            subtree(selected.node(i), SelectionHighlights.SELECTED);
        }
        out.end();
    }

    /** Marks {@code root}'s renderable and those below it with {@code flags}: a walk by the parent links. */
    private void subtree(long root, int flags) {
        if (!scene.contains(root)) {
            return;
        }
        out.mark(sync.slotOf(root), flags);
        long node = scene.firstChild(root);
        while (node != NodeHandle.NONE) {
            out.mark(sync.slotOf(node), flags);
            long child = scene.firstChild(node);
            if (child != NodeHandle.NONE) {
                node = child;
                continue;
            }
            while (node != root && scene.nextSibling(node) == NodeHandle.NONE) {
                node = scene.parent(node);
            }
            node = node == root ? NodeHandle.NONE : scene.nextSibling(node);
        }
    }
}
