package com.truesim.engine.graph;

import java.util.Objects;

/**
 * Names a result a {@link Technique} provides, such as the scene's color or its depth, so others can require it without
 * knowing who makes it. Keys compare by identity: declare each once, as a constant.
 *
 * <pre>{@code
 * public static final Key SCENE_COLOR = Key.of("scene color");
 * }</pre>
 */
public final class Key {

    private final String name;

    private Key(String name) {
        this.name = Objects.requireNonNull(name, "name");
    }

    public static Key of(String name) {
        return new Key(name);
    }

    public String name() {
        return name;
    }

    @Override
    public String toString() {
        return name;
    }
}
