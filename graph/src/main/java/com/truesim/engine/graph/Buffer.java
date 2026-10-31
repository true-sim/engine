package com.truesim.engine.graph;

/**
 * A transient buffer of the graph, made by a technique: device-local, its memory pooled and shared with buffers whose
 * lifetimes do not overlap, its contents undefined until a pass of the frame writes them. A handle is valid for the
 * build that made it; passes resolve it through their {@link PassContext}, usually to a device address.
 */
public final class Buffer {

    final int id;
    final Object build; // the build it is of, as an image's
    final String name;
    final long size;

    Buffer(int id, String name, long size) {
        this(id, name, size, null);
    }

    Buffer(int id, String name, long size, Object build) {
        this.id = id;
        this.build = build;
        this.name = name;
        this.size = size;
    }

    public String name() {
        return name;
    }

    /** The size in bytes. */
    public long size() {
        return size;
    }

    @Override
    public String toString() {
        return name + " (" + size + " bytes)";
    }
}
