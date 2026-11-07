package com.truesim.engine.graph;

import com.truesim.common.graph.Id;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

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
 * <p>A key may have a technique providing it by default ({@link #buffer(String, Supplier)}): every build gives each
 * view, and the frame, where none of their techniques provides the key, a new one from the supplier. It is built only
 * where the key is required, like any technique: a view's own data that techniques add to, such as engine-render's
 * shading hooks.
 *
 * @param <T> what the key names: {@link Image} or {@link Buffer}
 */
public final class Key<T> {

    /** The keys with a default provider, as made. */
    private static final List<Key<?>> DEFAULTED = new CopyOnWriteArrayList<>();

    private final Id<T> id;
    private final Supplier<? extends Technique> byDefault;

    private Key(Id<T> id, Supplier<? extends Technique> byDefault) {
        this.id = id;
        this.byDefault = byDefault;
        if (byDefault != null) {
            DEFAULTED.add(this);
        }
    }

    private Key(Id<T> id) {
        this(id, null);
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

    /**
     * A key naming a buffer, provided by a technique of {@code byDefault} in every view (and the frame) none of whose
     * techniques provides it. The technique must provide the key.
     *
     * @throws IllegalArgumentException unless the name is namespaced, such as {@code acme.fog}
     */
    public static Key<Buffer> buffer(String name, Supplier<? extends Technique> byDefault) {
        return new Key<>(Id.of(name, Buffer.class), Objects.requireNonNull(byDefault, "byDefault"));
    }

    /** The keys with default providers. */
    static List<Key<?>> defaulted() {
        return DEFAULTED;
    }

    /** A new default provider of it, or null: it has none. */
    Technique defaultProvider() {
        return byDefault == null ? null : byDefault.get();
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
