package com.truesim.engine.settings;

import com.truesim.annotations.AllowAllocation;
import java.util.Objects;

/**
 * Where a setting shows in options screens: a group in a tree of groups, apart from the setting's saved name, so a menu
 * can be rearranged without breaking saved files. Declared once as constants, like settings:
 *
 * <pre>{@code
 * public static final SettingGroup GRAPHICS = SettingGroup.root("graphics", "settings.graphics", 10);
 * public static final SettingGroup SHADOWS = GRAPHICS.child("shadows", "settings.graphics.shadows", 20);
 * }</pre>
 *
 * <p>The label is a translation key, not the text shown. Siblings are shown by {@link #order()}, then by id. One group
 * per window (or other scope) is a child made for it: {@code DISPLAY.child("window.main", "settings.window.main", 0)}.
 */
@AllowAllocation("declared once")
public final class SettingGroup {

    private final SettingGroup parent;
    private final String id;
    private final String label;
    private final int order;

    private SettingGroup(SettingGroup parent, String id, String label, int order) {
        if (id.isBlank() || !id.equals(id.strip())) {
            throw new IllegalArgumentException("A group's id is a word or dotted words: '" + id + "'");
        }
        this.parent = parent;
        this.id = parent == null ? id : parent.id + "/" + id;
        this.label = Objects.requireNonNull(label, "label");
        this.order = order;
    }

    /** A top-level group. */
    public static SettingGroup root(String id, String label, int order) {
        return new SettingGroup(null, id, label, order);
    }

    /** A subgroup of this one. */
    public SettingGroup child(String id, String label, int order) {
        return new SettingGroup(this, id, label, order);
    }

    /** The group it is in; null for a top-level group. */
    public SettingGroup parent() {
        return parent;
    }

    /** Its id with its parents', {@code "graphics/shadows"}: unique in a registry. */
    public String id() {
        return id;
    }

    /** The translation key of its label. */
    public String label() {
        return label;
    }

    /** Its place among its siblings, lowest first. */
    public int order() {
        return order;
    }

    /** Whether it is {@code group} or inside it, at any depth. */
    public boolean isIn(SettingGroup group) {
        for (SettingGroup g = this; g != null; g = g.parent) {
            if (g == group) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String toString() {
        return id;
    }
}
