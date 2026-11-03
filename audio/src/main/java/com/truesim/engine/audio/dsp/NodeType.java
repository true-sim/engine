package com.truesim.engine.audio.dsp;

import com.truesim.annotations.AllowAllocation;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * A node type registered by name ({@link Nodes}) so patches and data can name it: how to make one and the parameters
 * it reads.
 *
 * <pre>{@code
 * public static final NodeType<BitCrusher> TYPE = NodeType.of("bitCrusher", BitCrusher::new,
 *     NodeParam.of("bits", 8, 1, 16), NodeParam.of("downsample", 1, 1, 64));
 * }</pre>
 *
 * @param name    its registered name
 * @param factory makes a node (on the game thread)
 * @param params  the parameters its nodes read
 * @param <T>     the node class
 */
@AllowAllocation("set up once")
public record NodeType<T extends DspNode>(String name, Supplier<T> factory, List<NodeParam> params) {

    public NodeType {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(factory, "factory");
        params = List.copyOf(params);
    }

    /** The type {@code name}, made by {@code factory}, reading {@code params}. */
    public static <T extends DspNode> NodeType<T> of(String name, Supplier<T> factory, NodeParam... params) {
        return new NodeType<>(name, factory, List.of(params));
    }
}
