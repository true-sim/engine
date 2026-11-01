package com.truesim.engine.settings;

import com.truesim.annotations.AllowAllocation;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The settings an app has, registered explicitly by the parts that own them (each plugin its list), with where they
 * show: for options screens and consoles to list, and for startup to check. Values stay in {@link EngineSettings}.
 *
 * <pre>{@code
 * SettingRegistry registry = new SettingRegistry();
 * registry.add(SHADOWS, ShadowSettings.SIZE, ShadowSettings.CASCADES);         // shown under graphics/shadows
 * registry.add(DEBUG, SettingTier.DEVELOPER, GraphSettings.VALIDATION);        // with a tier
 * registry.addScoped("window.main", MAIN_WINDOW, WindowSettings.MODE);         // one window's own, its own group
 * registry.addHidden(Internal.CACHE_SIZE);                                     // not for players: no group
 * registry.tiers(SettingTier.BASIC, SettingTier.ADVANCED, SettingTier.DEVELOPER).hide(SettingTier.DEVELOPER);
 * registry.check();                                                            // at startup
 * for (SettingGroup group : registry.groups(null, SettingTier.ADVANCED)) { ... registry.settings(group, ...) }
 * }</pre>
 *
 * <p>A full name (scope and name) can be registered once; a second registration throws. {@link #check} fails on what
 * cannot be shown: a player-visible setting without a group, a tier not declared, two groups with one id. Tiers are
 * optional: with none declared, no setting may have one, and queries filter nothing.
 */
@AllowAllocation("set up once, queried by options screens")
public final class SettingRegistry {

    /**
     * One registered setting.
     *
     * @param setting the setting
     * @param scope the scope of its values ({@link EngineSettings#scoped}), or null for the unscoped store
     * @param group where it shows; null for a hidden one
     * @param tier its tier, or null
     */
    public record Entry(Setting<?> setting, String scope, SettingGroup group, SettingTier tier) {

        /** Its name with its scope: as saved. */
        public String fullName() {
            return scope == null ? setting.name() : scope + "." + setting.name();
        }

        /** The view of {@code settings} its values are in. */
        public EngineSettings view(EngineSettings settings) {
            return scope == null ? settings : settings.scoped(scope);
        }

        /** Whether players see it: it has a group. */
        public boolean visible() {
            return group != null;
        }
    }

    private final Map<String, Entry> entries = new LinkedHashMap<>(); // by full name, in registration order
    private final Set<SettingTier> tiers = new LinkedHashSet<>();
    private final Set<SettingTier> hidden = new HashSet<>();
    private boolean revealed;

    /** Registers {@code settings}, unscoped, shown in {@code group}. */
    public SettingRegistry add(SettingGroup group, Setting<?>... settings) {
        return add(group, null, settings);
    }

    /** Registers {@code settings}, unscoped, shown in {@code group} at {@code tier} (null: none). */
    public SettingRegistry add(SettingGroup group, SettingTier tier, Setting<?>... settings) {
        Objects.requireNonNull(group, "group");
        for (Setting<?> setting : settings) {
            add(new Entry(setting, null, group, tier));
        }
        return this;
    }

    /** Registers {@code settings} under {@code scope} (a window's, say), shown in {@code group}. */
    public SettingRegistry addScoped(String scope, SettingGroup group, Setting<?>... settings) {
        return addScoped(scope, group, null, settings);
    }

    /** Registers {@code settings} under {@code scope}, shown in {@code group} at {@code tier} (null: none). */
    public SettingRegistry addScoped(String scope, SettingGroup group, SettingTier tier, Setting<?>... settings) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(group, "group");
        for (Setting<?> setting : settings) {
            add(new Entry(setting, scope, group, tier));
        }
        return this;
    }

    /** Registers {@code settings}, unscoped, not shown to players: consoles and the command line still reach them. */
    public SettingRegistry addHidden(Setting<?>... settings) {
        for (Setting<?> setting : settings) {
            add(new Entry(setting, null, null, null));
        }
        return this;
    }

    /** Registers {@code entry}; throws if its full name is registered already. */
    public SettingRegistry add(Entry entry) {
        Objects.requireNonNull(entry.setting(), "setting");
        if (entry.scope() != null && (entry.scope().isBlank() || entry.scope().endsWith("."))) {
            throw new IllegalArgumentException("A scope is a word or dotted words: '" + entry.scope() + "'");
        }
        Entry before = entries.putIfAbsent(entry.fullName(), entry);
        if (before != null) {
            throw new IllegalArgumentException(entry.fullName() + " is registered twice");
        }
        return this;
    }

    /** The tiers this app uses; replaces those declared before. */
    public SettingRegistry tiers(SettingTier... declared) {
        tiers.clear();
        tiers.addAll(List.of(declared));
        return this;
    }

    /** The tiers declared, in the order given. */
    public Set<SettingTier> tiers() {
        return Set.copyOf(tiers);
    }

    /** Keeps {@code tier}'s settings out of queries until {@link #reveal} (a debug flag, a launch option). */
    public SettingRegistry hide(SettingTier tier) {
        hidden.add(Objects.requireNonNull(tier, "tier"));
        return this;
    }

    /** Shows hidden tiers in queries, or hides them again. */
    public SettingRegistry reveal(boolean on) {
        revealed = on;
        return this;
    }

    /**
     * Fails on what options screens cannot show: a player-visible setting without a group (hidden ones are registered
     * with {@link #addHidden}), a setting's tier not declared, two different groups with one id.
     */
    public void check() {
        List<String> problems = new ArrayList<>();
        Map<String, SettingGroup> groups = new HashMap<>();
        for (Entry entry : entries.values()) {
            if (entry.tier() != null && !tiers.contains(entry.tier())) {
                problems.add(entry.fullName() + ": the tier '" + entry.tier() + "' is not declared"
                    + (tiers.isEmpty() ? " (no tiers are)" : ""));
            }
            if (entry.tier() != null && entry.group() == null) {
                problems.add(entry.fullName() + ": a tier, but no group to show it in");
            }
            for (SettingGroup g = entry.group(); g != null; g = g.parent()) {
                SettingGroup other = groups.putIfAbsent(g.id(), g);
                if (other != null && other != g) {
                    problems.add("two groups have the id '" + g.id() + "'");
                }
            }
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Settings that cannot be shown:\n  " + String.join("\n  ", problems));
        }
    }

    /** Every registered setting, in registration order. */
    public List<Entry> entries() {
        return List.copyOf(entries.values());
    }

    /** The setting registered by {@code fullName} (scope and name), or null. */
    public Entry find(String fullName) {
        return entries.get(fullName);
    }

    /**
     * The settings shown directly in {@code group} (not its subgroups), in registration order, with tiers up to
     * {@code upTo} (null: every tier) and no hidden one unless revealed. Settings without a tier always show.
     */
    public List<Entry> settings(SettingGroup group, SettingTier upTo) {
        List<Entry> shown = new ArrayList<>();
        for (Entry entry : entries.values()) {
            if (entry.group() == group && shows(entry, upTo)) {
                shown.add(entry);
            }
        }
        return shown;
    }

    /**
     * The subgroups of {@code parent} (null: the top-level groups) holding at least one setting shown with tiers up to
     * {@code upTo}, at any depth, by order then id: what an options screen lists.
     */
    public List<SettingGroup> groups(SettingGroup parent, SettingTier upTo) {
        Set<SettingGroup> found = new LinkedHashSet<>();
        for (Entry entry : entries.values()) {
            if (entry.group() == null || !shows(entry, upTo)) {
                continue;
            }
            for (SettingGroup g = entry.group(); g != null; g = g.parent()) {
                if (g.parent() == parent) {
                    found.add(g);
                    break;
                }
            }
        }
        List<SettingGroup> sorted = new ArrayList<>(found);
        sorted.sort(Comparator.comparingInt(SettingGroup::order).thenComparing(SettingGroup::id));
        return sorted;
    }

    private boolean shows(Entry entry, SettingTier upTo) {
        SettingTier tier = entry.tier();
        if (entry.group() == null) {
            return false;
        }
        if (tier == null) {
            return true;
        }
        if (hidden.contains(tier) && !revealed) {
            return false;
        }
        return upTo == null || tier.rank() <= upTo.rank();
    }
}
