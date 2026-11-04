package com.truesim.engine.ui.console;

import com.truesim.annotations.AllowAllocation;
import com.truesim.scene.core.NodeHandle;
import com.truesim.scene.core.SceneGraph;
import com.truesim.ui.console.NodeProvider;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * The scene nodes console commands name ({@code teleport player gate}): the game names the nodes it wants reachable
 * (scene-core keeps no names; a glTF import's node names, say), completion offers the names of nodes still in the
 * scene, by prefix and in order, and a name exists while its node does.
 */
public final class NamedNodes implements NodeProvider {

    private final SceneGraph graph;
    private final TreeMap<String, Long> nodes = new TreeMap<>();

    /** Names of nodes of {@code graph}. */
    public NamedNodes(SceneGraph graph) {
        this.graph = Objects.requireNonNull(graph, "graph");
    }

    /** Names {@code node} {@code name} (another node of the name loses it); returns this. */
    @AllowAllocation("named when a scene loads")
    public NamedNodes name(String name, long node) {
        nodes.put(Objects.requireNonNull(name, "name"), node);
        return this;
    }

    /** The node named {@code name}, or {@link NodeHandle#NONE} (also when it is gone). */
    public long node(String name) {
        Long node = nodes.get(name);
        return node != null && graph.contains(node) ? node : NodeHandle.NONE;
    }

    @Override
    @AllowAllocation("completion, on events")
    public void complete(String prefix, List<String> out) {
        for (Map.Entry<String, Long> e : nodes.tailMap(prefix).entrySet()) {
            if (!e.getKey().startsWith(prefix)) {
                break;
            }
            if (graph.contains(e.getValue())) {
                out.add(e.getKey());
            }
        }
    }

    @Override
    public boolean exists(String name) {
        return node(name) != NodeHandle.NONE;
    }
}
