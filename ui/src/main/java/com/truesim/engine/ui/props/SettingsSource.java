package com.truesim.engine.ui.props;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.settings.Setting;
import com.truesim.engine.settings.SettingGroup;
import com.truesim.engine.settings.SettingRegistry;
import com.truesim.engine.settings.SettingTier;
import com.truesim.ui.controls.basic.DropDown;
import com.truesim.ui.core.bind.Bindable;
import com.truesim.ui.core.bind.Converter;
import com.truesim.ui.props.EditorFactory;
import com.truesim.ui.props.Hints;
import com.truesim.ui.props.Property;
import com.truesim.ui.props.PropertyBuilder;
import com.truesim.ui.props.PropertyEditor;
import com.truesim.ui.props.PropertyModel;
import com.truesim.ui.props.PropertyRow;
import com.truesim.ui.props.PropertySource;
import com.truesim.ui.props.annotations.EnumStyle;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * The engine's settings as properties (design 5.11), so the settings screen is generated: one {@link Property} of
 * {@link SettingsScreen.Values} per registered {@link Setting} shown to players (it has a group), its value the
 * setting's preferred one (what the player chose; set in the player's layer by the screen). From the setting:
 *
 * <ul>
 *   <li>its label from a translation key, {@code settings.<full name>} ({@link #labels}: the game's translations; by
 *       default the name's last word, {@code render.shadowSize}: "Shadow size"), its description as the tooltip;</li>
 *   <li>numbers' ranges ({@code @Range}, so sliders) and whole numbers' step of 1;</li>
 *   <li>its {@link SettingGroup} as the {@code @Group} (the group's label key translated, nested groups as
 *       {@code Graphics / Shadows}) and the order: the groups' orders, then registration order;</li>
 *   <li>choices as enum styles: an enum's constants in a drop-down ({@code @EnumStyle}), named options in
 *       {@link #CHOICE_EDITOR} (a drop-down of the names);</li>
 *   <li>settings of tiers above the one shown ({@link #upTo}, and tiers the registry hides) {@code @Hidden}.</li>
 * </ul>
 *
 * <pre>{@code
 * SettingsSource source = new SettingsSource(engine.registry()).upTo(SettingTier.ADVANCED);
 * PropertyPanel<SettingsScreen.Values> panel = source.builder()
 *     .build(controls, new SettingsScreen(engine.registry(), engine.settings()));
 * }</pre>
 *
 * <p>As a {@link PropertySource} (priority {@link #PRIORITY}) it answers for {@link SettingsScreen.Values}; the model
 * is made again when settings were registered since ({@link #model()}).
 */
public final class SettingsSource implements PropertySource {

    /** Its priority among property sources: over the annotated classes. */
    public static final int PRIORITY = 100;
    /** The editor of settings with named options: a drop-down of the names. */
    public static final String CHOICE_EDITOR = "setting-choice";

    private final SettingRegistry registry;
    private Function<String, String> labels = SettingsSource::defaultLabel;
    private SettingTier upTo;
    private PropertyModel<SettingsScreen.Values> model;
    private int modelEntries = -1;
    private final Map<String, Setting<?>> byProperty = new java.util.HashMap<>();

    /** The settings of {@code registry}. */
    public SettingsSource(SettingRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    /** Translates label keys ({@code settings.render.bloom}, a group's label key) into text; returns this. */
    public SettingsSource labels(Function<String, String> translate) {
        labels = Objects.requireNonNull(translate, "translate");
        model = null;
        return this;
    }

    /** Shows settings of tiers up to {@code tier} (null: every tier), hides deeper ones; returns this. */
    public SettingsSource upTo(SettingTier tier) {
        upTo = tier;
        model = null;
        return this;
    }

    @Override
    public int priority() {
        return PRIORITY;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> PropertyModel<T> model(Class<T> type) {
        return type == SettingsScreen.Values.class ? (PropertyModel<T>) model() : null;
    }

    /** The settings screen's model, made again when settings were registered since. */
    @AllowAllocation("made when the screen is built")
    public synchronized PropertyModel<SettingsScreen.Values> model() {
        int entries = registry.entries().size();
        if (model == null || entries != modelEntries) {
            model = build();
            modelEntries = entries;
        }
        return model;
    }

    /** A builder of the settings screen over {@link #model()}, with the {@link #CHOICE_EDITOR}. */
    @AllowAllocation("made when the screen is built")
    public PropertyBuilder<SettingsScreen.Values> builder() {
        PropertyBuilder<SettingsScreen.Values> builder = PropertyBuilder.of(model())
            .editor(CHOICE_EDITOR, choiceEditor());
        for (Map.Entry<String, Setting<?>> e : byProperty.entrySet()) {
            if (e.getValue().kind() == Setting.Kind.CHOICE && !e.getValue().defaultValue().getClass().isEnum()) {
                builder.editor(e.getKey(), CHOICE_EDITOR);
            }
        }
        return builder;
    }

    /** The setting a property of the model stands for, or null. */
    public Setting<?> setting(String property) {
        model();
        return byProperty.get(property);
    }

    @AllowAllocation("made when the screen is built")
    private PropertyModel<SettingsScreen.Values> build() {
        byProperty.clear();
        Set<String> shown = new HashSet<>();
        if (upTo != null || !registry.tiers().isEmpty()) {
            SettingTier deepest = upTo;
            if (deepest == null) {
                for (SettingTier t : registry.tiers()) {
                    deepest = deepest == null || t.rank() > deepest.rank() ? t : deepest;
                }
            }
            for (SettingRegistry.Entry entry : registry.entries()) {
                if (entry.group() != null && shown.add(entry.group().id())) {
                    for (SettingRegistry.Entry e : registry.settings(entry.group(), deepest)) {
                        shown.add(e.fullName());
                    }
                }
            }
        }
        PropertyModel.Builder<SettingsScreen.Values> b = PropertyModel.builder(SettingsScreen.Values.class).source("engine settings")
            .factory(() -> {
                throw new UnsupportedOperationException("the settings screen edits the engine's settings");
            });
        // in the groups' order (parents' orders first), then in registration order
        List<SettingRegistry.Entry> visible = new ArrayList<>();
        for (SettingRegistry.Entry entry : registry.entries()) {
            if (entry.visible()) { // not for players: no group
                visible.add(entry);
            }
        }
        List<SettingRegistry.Entry> sorted = new ArrayList<>(visible);
        sorted.sort((x, y) -> {
            int c = compare(x.group(), y.group());
            return c != 0 ? c : Integer.compare(visible.indexOf(x), visible.indexOf(y));
        });
        for (SettingRegistry.Entry entry : visible) {
            boolean hidden = (upTo != null || !registry.tiers().isEmpty()) && !shown.contains(entry.fullName());
            b.property(property(entry, entry.setting(), sorted.indexOf(entry), hidden));
        }
        return b.build();
    }

    @AllowAllocation("made when the screen is built")
    private <V> Property<SettingsScreen.Values, V> property(SettingRegistry.Entry entry, Setting<V> setting, int index,
        boolean hidden) {
        String name = entry.fullName();
        byProperty.put(name, setting);
        List<Annotation> hints = new ArrayList<>();
        hints.add(Hints.label(labels.apply("settings." + name)));
        if (!setting.description().isEmpty()) {
            hints.add(Hints.tooltip(setting.description()));
        }
        if (setting.min() != null && setting.max() != null) {
            hints.add(Hints.range(setting.min().doubleValue(), setting.max().doubleValue()));
        }
        if (setting.kind() == Setting.Kind.INTEGER) {
            hints.add(Hints.step(1));
        }
        if (setting.kind() == Setting.Kind.CHOICE && setting.defaultValue().getClass().isEnum()) {
            hints.add(Hints.enumStyle(EnumStyle.Kind.DROPDOWN));
        }
        hints.add(Hints.group(groupLabel(entry.group())));
        hints.add(Hints.order(index));
        if (hidden) {
            hints.add(Hints.hidden());
        }
        Class<?> type = setting.defaultValue() instanceof Enum<?> e ? e.getDeclaringClass()
            : setting.defaultValue().getClass();
        @SuppressWarnings("unchecked")
        java.util.function.Function<SettingsScreen.Values, V> getter = values -> (V) values.get(name);
        return Property.<SettingsScreen.Values, V>builder(name, type)
            .getter(getter)
            .wither((values, value) -> values.with(name, value))
            .annotations(hints.toArray(new Annotation[0]))
            .index(index)
            .build();
    }

    /** The group's label, its parents' first: "Graphics / Shadows". */
    @AllowAllocation("made when the screen is built")
    private String groupLabel(SettingGroup group) {
        String own = labels.apply(group.label());
        return group.parent() == null ? own : groupLabel(group.parent()) + " / " + own;
    }

    /** Groups by their orders from the root down (a parent before its children). */
    private static int compare(SettingGroup a, SettingGroup b) {
        List<SettingGroup> pa = path(a);
        List<SettingGroup> pb = path(b);
        for (int i = 0; i < Math.min(pa.size(), pb.size()); i++) {
            if (pa.get(i) != pb.get(i)) {
                int c = Integer.compare(pa.get(i).order(), pb.get(i).order());
                return c != 0 ? c : pa.get(i).id().compareTo(pb.get(i).id());
            }
        }
        return Integer.compare(pa.size(), pb.size());
    }

    private static List<SettingGroup> path(SettingGroup group) {
        List<SettingGroup> path = new ArrayList<>();
        for (SettingGroup g = group; g != null; g = g.parent()) {
            path.add(0, g);
        }
        return path;
    }

    /** A label key's text without translations: its last word, split ({@code settings.render.shadowSize}). */
    public static String defaultLabel(String key) {
        String last = key.substring(key.lastIndexOf('.') + 1);
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < last.length(); i++) {
            char c = last.charAt(i);
            if (i == 0) {
                b.append(Character.toUpperCase(c));
            } else if (Character.isUpperCase(c)) {
                b.append(' ').append(Character.toLowerCase(c));
            } else {
                b.append(c);
            }
        }
        return b.toString();
    }

    /** A drop-down of a setting's named options. */
    @AllowAllocation("editors are made when the screen is built")
    private EditorFactory choiceEditor() {
        return row -> {
            Setting<?> setting = byProperty.get(row.property().name());
            List<Object> options = setting == null ? List.of() : List.of(setting.choices());
            DropDown<Object> d = new DropDown<>(row.controls(), row.property().label(), options,
                o -> defaultLabel(String.valueOf(o).toLowerCase(Locale.ROOT)), row.controls().clock().intValue(-1));
            row.bind(Bindable.of(d.model()), Converter.of(options::indexOf,
                i -> i == null || i < 0 || i >= options.size() ? null : options.get(i)), d.element());
            return new PropertyEditor() {
                @Override
                public com.truesim.ui.core.Element element() {
                    return d.element();
                }

                @Override
                public void dispose() {
                    d.dispose();
                }
            };
        };
    }
}
