package com.truesim.engine.settings;

import static com.truesim.testing.AllocationAssertions.assertAllocationFree;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.truesim.engine.settings.TechniqueConfig.Tunable;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class TechniquesTest {

    /** What the test slot's kinds make: a name and the config they were given. */
    record Made(String what, Supplier<?> config) {
    }

    enum Quality {
        LOW,
        HIGH
    }

    record Horizons(
        @Tunable(value = "how far", min = 0.01, max = 100, formerly = "test.ao.radius") float radius,
        @Tunable(value = "steps", min = 1, max = 64) int steps,
        @Tunable("multiple bounces") boolean multiBounce) implements TechniqueConfig {
    }

    record Mask(
        @Tunable(value = "how far", min = 0.01, max = 100, formerly = "test.ao.radius") float radius,
        @Tunable(value = "thickness", min = 0.001, max = 100) float thickness,
        @Tunable("quality") Quality quality,
        String note) implements TechniqueConfig {

        Mask {
            if (thickness > radius * 10) {
                throw new IllegalArgumentException("thicker than ten radii");
            }
        }
    }

    record Nothing() implements TechniqueConfig {
    }

    static final TechniqueSlot<String, Made> SLOT = TechniqueSlot.of("test.ao", "mask", true, "test occlusion",
        techniques -> techniques.register(TechniquesTest.SLOT, TechniquesTest.HORIZONS)
            .register(TechniquesTest.SLOT, TechniquesTest.MASK));
    static final TechniqueKind<Horizons, String, Made> HORIZONS = TechniqueKind.of(SLOT, "horizons",
        new Horizons(0.5f, 8, false), "horizons searched", (context, config) -> new Made(context + " horizons", config));
    static final TechniqueKind<Mask, String, Made> MASK = TechniqueKind.of(SLOT, "mask",
        new Mask(0.5f, 0.25f, Quality.HIGH, ""), "a mask of sectors", (context, config) -> new Made(context + " mask",
            config));

    /** A game's kind, not the engine's: registered later, through the same call. */
    static final TechniqueKind<Nothing, String, Made> GAME = TechniqueKind.of(SLOT, "acme-ao", new Nothing(),
        "the game's own", (context, config) -> new Made(context + " acme", config));

    static final TechniqueSlot<String, Made> REQUIRED = TechniqueSlot.of("test.blend", "sorted", false, "blending",
        techniques -> techniques.register(TechniquesTest.REQUIRED, TechniquesTest.SORTED));
    static final TechniqueKind<Nothing, String, Made> SORTED = TechniqueKind.of(REQUIRED, "sorted", new Nothing(),
        "sorted", (context, config) -> new Made("sorted", config));

    @Test
    void aSlotDeclaresItsKindsWhenFirstUsedAndChoosesItsDefault() {
        EngineSettings settings = new EngineSettings();
        assertThat(SLOT.chosen(settings)).isSameAs(MASK);
        assertThat(settings.techniques().variants(SLOT)).containsExactly(HORIZONS, MASK);
        assertThat(settings.techniques().slots()).containsExactly(SLOT);
        assertThat(SLOT.setting().kind()).isEqualTo(Setting.Kind.TECHNIQUE);
        assertThat(SLOT.setting().defaultValue()).isEqualTo("mask");
        assertThat(settings.techniques().kind(SLOT, "HORIZONS")).isSameAs(HORIZONS);
        assertThat(settings.techniques().kind(SLOT, "missing")).isNull();
    }

    @Test
    void choosingAKindOrOffChangesTheSettingAndItsListenersHear() {
        EngineSettings settings = new EngineSettings();
        int[] heard = new int[1];
        settings.onChange(SLOT.setting(), () -> heard[0]++);
        SLOT.choose(settings, HORIZONS);
        assertThat(SLOT.chosen(settings)).isSameAs(HORIZONS);
        assertThat(settings.toMap()).containsEntry("test.ao", "horizons");
        SLOT.choose(settings, null);
        assertThat(SLOT.chosen(settings)).isNull();
        assertThat(settings.toMap()).containsEntry("test.ao", "off");
        assertThat(heard[0]).isEqualTo(2);
        assertThatThrownBy(() -> settings.set(SLOT.setting(), "two words")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void eachTunableIsASettingNamedByItsPath() {
        assertThat(MASK.settings()).extracting(Setting::name)
            .containsExactly("test.ao.mask.radius", "test.ao.mask.thickness", "test.ao.mask.quality", "test.ao.mask.note");
        Setting<Float> radius = MASK.setting("radius");
        assertThat(radius.kind()).isEqualTo(Setting.Kind.DECIMAL);
        assertThat(radius.min()).isEqualTo(0.01f);
        assertThat(radius.max()).isEqualTo(100f);
        assertThat(radius.description()).isEqualTo("how far");
        assertThat(MASK.setting("quality").kind()).isEqualTo(Setting.Kind.CHOICE);
        assertThat(MASK.setting("note").description()).isEqualTo("note");
        assertThat(HORIZONS.setting("steps").kind()).isEqualTo(Setting.Kind.INTEGER);
        assertThat(HORIZONS.setting("multiBounce").kind()).isEqualTo(Setting.Kind.BOOLEAN);
        assertThat(MASK.path("radius")).isEqualTo("test.ao.mask.radius");
        assertThat(MASK.type()).isEqualTo(Mask.class);
        assertThat(MASK.defaults().radius()).isEqualTo(0.5f);
        assertThat(MASK.description()).isEqualTo("a mask of sectors");
        assertThat(MASK).hasToString("test.ao=mask");
        assertThatThrownBy(() -> MASK.setting("missing")).isInstanceOf(IllegalArgumentException.class);

        EngineSettings settings = new EngineSettings();
        assertThat(settings.techniques().find("test.ao.mask.radius")).isNull(); // not declared yet
        settings.techniques().slot(SLOT);
        assertThat(settings.techniques().find("test.ao.mask.radius")).isSameAs(radius);
        assertThat(settings.techniques().find("test.ao")).isSameAs(SLOT.setting());
        assertThat(settings.techniques().find("test.ao.mask.missing")).isNull();
        assertThat(settings.techniques().find("other")).isNull();
        assertThat(settings.techniques().settings(SLOT)).hasSize(1 + 3 + 4);
    }

    @Test
    void theConfigIsTheRecordOfItsSettingsMadeAgainOnlyWhenOneChanges() {
        EngineSettings settings = new EngineSettings();
        Mask first = MASK.config(settings);
        assertThat(first).isEqualTo(MASK.defaults());
        settings.set(HORIZONS.setting("steps"), 4); // another kind's: the same record
        assertThat(MASK.config(settings)).isSameAs(first);
        settings.set(MASK.setting("radius"), 2f);
        Mask second = MASK.config(settings);
        assertThat(second.radius()).isEqualTo(2f);
        assertThat(settings.toMap()).containsEntry("test.ao.mask.radius", "2.0");
        assertThat(MASK.config(settings)).isSameAs(second);

        MASK.set(settings, new Mask(3, 1, Quality.LOW, "x"));
        assertThat(MASK.config(settings)).isEqualTo(new Mask(3, 1, Quality.LOW, "x"));
        assertThat(settings.toMap()).containsEntry("test.ao.mask.quality", "LOW");
    }

    @Test
    void valuesTheRecordRefusesTogetherKeepTheRecordBeforeAndAreReported() {
        EngineSettings settings = new EngineSettings();
        settings.set(MASK.setting("radius"), 0.01f).set(MASK.setting("thickness"), 50f);
        assertThat(MASK.config(settings)).isEqualTo(MASK.defaults());
        assertThat(settings.techniques().problems()).singleElement()
            .satisfies(problem -> assertThat(problem.message()).contains("thicker than ten radii"));
    }

    @Test
    void theFactoryMakesTheTechniqueWithItsLiveConfig() {
        EngineSettings settings = new EngineSettings();
        Made made = MASK.create("view", () -> MASK.config(settings));
        assertThat(made.what()).isEqualTo("view mask");
        settings.set(MASK.setting("radius"), 4f);
        assertThat(((Mask) made.config().get()).radius()).isEqualTo(4f);
    }

    @Test
    void enumValuesOlderVersionsSavedStillLoad() {
        EngineSettings settings = new EngineSettings().load(Map.of("test.ao", "HORIZONS"));
        assertThat(SLOT.chosen(settings)).isSameAs(HORIZONS);
        settings.load(Map.of("test.ao", "OFF"));
        assertThat(SLOT.chosen(settings)).isNull();
        settings.load(Map.of("test.ao", "none"));
        assertThat(SLOT.chosen(settings)).isNull();
        assertThat(settings.techniques().problems()).isEmpty();
    }

    @Test
    void aTunableReadsItsFormerNameWhereItsOwnHoldsNothing() {
        EngineSettings settings = new EngineSettings().load(Map.of("test.ao.radius", "2"));
        assertThat(settings.isSet(MASK.setting("radius"))).isTrue();
        assertThat(MASK.config(settings).radius()).isEqualTo(2f);
        assertThat(HORIZONS.config(settings).radius()).isEqualTo(2f); // both kinds had it
        settings.set(MASK.setting("radius"), 3f);
        assertThat(MASK.config(settings).radius()).isEqualTo(3f);
        assertThat(settings.toMap()).containsEntry("test.ao.radius", "2").containsEntry("test.ao.mask.radius", "3.0");

        EngineSettings scoped = new EngineSettings().load(Map.of("view.a.test.ao.radius", "4")).scoped("view.a");
        assertThat(scoped.get(MASK.<Float>setting("radius"))).isEqualTo(4f);
        assertThat(new EngineSettings().load(Map.of("test.ao.radius", "bad")).get(MASK.<Float>setting("radius")))
            .isEqualTo(0.5f);
        EngineSettings set = new EngineSettings();
        set.set(Setting.decimal("test.ao.radius", 0, 0, 100, "old"), 7f);
        assertThat(set.get(MASK.<Float>setting("radius"))).isEqualTo(7f);
    }

    @Test
    void aMissingKindFallsBackToTheDefaultIsReportedAndComesBackWithItsMod() {
        EngineSettings settings = new EngineSettings().load(Map.of("test.ao", "acme-ao"));
        assertThat(SLOT.chosen(settings)).isSameAs(MASK);
        assertThat(SLOT.chosen(settings)).isSameAs(MASK); // reported once
        assertThat(settings.techniques().problems()).singleElement().satisfies(problem -> {
            assertThat(problem.slot()).isEqualTo("test.ao");
            assertThat(problem.value()).isEqualTo("acme-ao");
        });
        assertThat(settings.toMap()).containsEntry("test.ao", "acme-ao"); // kept for when the mod is back

        settings.techniques().register(SLOT, GAME);
        assertThat(settings.techniques().problems()).isEmpty();
        assertThat(SLOT.chosen(settings)).isSameAs(GAME);
        assertThat(GAME.create("view", () -> GAME.config(settings)).what()).isEqualTo("view acme");
        assertThat(settings.techniques().variants(SLOT)).containsExactly(HORIZONS, MASK, GAME);
    }

    @Test
    void aSlotThatCannotBeOffKeepsItsDefault() {
        EngineSettings settings = new EngineSettings().load(Map.of("test.blend", "off"));
        assertThat(REQUIRED.chosen(settings)).isSameAs(SORTED);
        assertThat(settings.techniques().problems()).hasSize(1);
        assertThatThrownBy(() -> TechniqueSlot.of("test.none", "off", false, "x", null))
            .isInstanceOf(IllegalArgumentException.class);
        TechniqueSlot<String, Made> empty = TechniqueSlot.of("test.empty", "off", true, "nothing", null);
        assertThat(empty.chosen(settings)).isNull();
        assertThat(empty.description()).isEqualTo("nothing");
        assertThat(empty.optional()).isTrue();
        assertThat(empty).hasToString("test.empty");
        TechniqueSlot<String, Made> broken = TechniqueSlot.of("test.broken", "gone", true, "nothing", null);
        assertThatThrownBy(() -> broken.chosen(settings)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void registrationIsChecked() {
        Techniques techniques = new EngineSettings().techniques();
        techniques.register(SLOT, MASK); // twice is once
        assertThat(techniques.variants(SLOT)).containsExactly(HORIZONS, MASK);
        TechniqueKind<Nothing, String, Made> twin = TechniqueKind.of(SLOT, "Mask", new Nothing(), "twin",
            (context, config) -> null);
        assertThatThrownBy(() -> techniques.register(SLOT, twin)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> techniques.register(REQUIRED, castTo(REQUIRED, MASK)))
            .isInstanceOf(IllegalArgumentException.class);
        TechniqueSlot<String, Made> sameName = TechniqueSlot.of("test.ao", "x", true, "again", null);
        assertThatThrownBy(() -> techniques.slot(sameName)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TechniqueKind.of(SLOT, "off", new Nothing(), "x", (c, f) -> null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TechniqueKind.of(SLOT, "a.b", new Nothing(), "x", (c, f) -> null))
            .isInstanceOf(IllegalArgumentException.class);
        record Wrong(double value) implements TechniqueConfig {
        }
        assertThatThrownBy(() -> TechniqueKind.of(SLOT, "wrong", new Wrong(1), "x", (c, f) -> null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @SuppressWarnings("unchecked")
    private static <X, T> TechniqueKind<?, X, T> castTo(TechniqueSlot<X, T> slot, TechniqueKind<?, ?, ?> kind) {
        return (TechniqueKind<?, X, T>) kind;
    }

    @Test
    void readingTheChoiceAndTheConfigEachFrameAllocatesNothing() {
        EngineSettings settings = new EngineSettings();
        settings.set(MASK.setting("radius"), 2f);
        float[] sum = new float[1];
        assertAllocationFree(() -> {
            TechniqueKind<?, String, Made> kind = SLOT.chosen(settings);
            sum[0] += kind == MASK ? MASK.config(settings).radius() : 0;
        });
        assertThat(sum[0]).isPositive();
    }
}
