package com.truesim.engine.graph;

import com.truesim.common.graph.Id;

/**
 * Names a result a {@link Technique} provides, such as the scene's color (an {@link Image}) or its light list (a
 * {@link Buffer}), so others can require it without knowing who makes it. Keys are typed: requiring an image key gives an
 * image, and asking for a buffer from it does not compile. Declare each once, as a constant, with a namespaced name;
 * keys compare by identity.
 *
 * <pre>{@code
 * public static final Key<Image> SCENE_COLOR = Key.image("truesim.sceneColor");
 * public static final Key<Buffer> LIGHT_LIST = Key.buffer("truesim.lightList");
 * }</pre>
 *
 * @param <T> what the key names: {@link Image} or {@link Buffer}
 */
public final class Key<T> {

    private final Id<T> id;

    private Key(Id<T> id) {
        this.id = id;
    }

    /**
     * A key naming an image.
     *
     * @throws IllegalArgumentException unless the name is namespaced, such as {@code acme.fog}
     */
    public static Key<Image> image(String name) {
        return new Key<>(Id.of(name, Image.class));
    }

    /**
     * A key naming a buffer.
     *
     * @throws IllegalArgumentException unless the name is namespaced, such as {@code acme.fog}
     */
    public static Key<Buffer> buffer(String name) {
        return new Key<>(Id.of(name, Buffer.class));
    }

    public String name() {
        return id.name();
    }

    /** What it names: {@code Image.class} or {@code Buffer.class}. */
    public Class<T> kind() {
        return id.kind();
    }

    @Override
    public String toString() {
        return id.name();
    }
}
