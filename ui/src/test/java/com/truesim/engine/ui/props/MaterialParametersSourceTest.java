package com.truesim.engine.ui.props;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.truesim.common.state.ChangeClock;
import com.truesim.common.state.VersionedValue;
import com.truesim.engine.render.materials.MetallicRoughnessParameters;
import com.truesim.engine.ui.scene.UiSceneTest;
import com.truesim.ui.controls.Ui;
import com.truesim.ui.controls.basic.Controls;
import com.truesim.ui.core.bind.Binding;
import com.truesim.ui.core.input.UiInput;
import com.truesim.ui.props.Property;
import com.truesim.ui.props.PropertyBuilder;
import com.truesim.ui.props.PropertyModel;
import com.truesim.ui.props.PropertyModels;
import com.truesim.ui.props.PropertyPanel;
import com.truesim.ui.props.annotations.Hidden;
import com.truesim.ui.props.annotations.Range;
import com.truesim.ui.props.annotations.ReadOnly;
import com.truesim.ui.style.tss.Tss;
import org.junit.jupiter.api.Test;

/** The Slang material records as properties: ranges, withers, textures read-only, the defaults as the factory. */
class MaterialParametersSourceTest {

    @Test
    void aMaterialRecordsPropertiesComeFromItsSlangMetadata() {
        MaterialParametersSource source = new MaterialParametersSource();
        assertThat(source.model(String.class)).isNull();
        PropertyModel<MetallicRoughnessParameters> model = source.model(MetallicRoughnessParameters.class);
        assertThat(model.factory().get()).isSameAs(MetallicRoughnessParameters.DEFAULTS);
        @SuppressWarnings("unchecked")
        Property<MetallicRoughnessParameters, Float> roughness =
            (Property<MetallicRoughnessParameters, Float>) model.property("roughness");
        assertThat(roughness.annotation(Range.class).min()).isEqualTo(0);
        assertThat(roughness.annotation(Range.class).max()).isEqualTo(1);
        assertThat(roughness.get(MetallicRoughnessParameters.DEFAULTS)).isEqualTo(0.7f);
        assertThat(roughness.with(MetallicRoughnessParameters.DEFAULTS, 0.25f).roughness()).isEqualTo(0.25f);
        assertThatIllegalArgumentException().as("the record's own range check")
            .isThrownBy(() -> roughness.with(MetallicRoughnessParameters.DEFAULTS, 2f));
        assertThat(model.property("baseColorTexture").annotation(ReadOnly.class)).isNotNull();
        assertThat(model.property("normalScale").annotation(Range.class)).as("no [Range]").isNull();
        assertThat(model.property("emissive").annotation(Hidden.class)).isNull();
        // through the shared models, merged over reflection
        MaterialParametersSource.register();
        MaterialParametersSource.register();
        PropertyModel<MetallicRoughnessParameters> merged =
            PropertyModels.shared().of(MetallicRoughnessParameters.class);
        assertThat(merged.property("metallic").annotation(Range.class)).isNotNull();
    }

    @Test
    void aGeneratedMaterialPanelEditsTheRecordLive() {
        MaterialParametersSource.register();
        Ui ui = new Ui(Tss.defaultTheme(), UiSceneTest.text());
        Controls controls = new Controls(ui);
        VersionedValue<MetallicRoughnessParameters> material = new ChangeClock().value(
            MetallicRoughnessParameters.DEFAULTS);
        PropertyPanel<MetallicRoughnessParameters> panel =
            PropertyBuilder.forType(MetallicRoughnessParameters.class).build(controls, material);
        ui.root().add(panel.element());
        UiInput input = new UiInput(4);
        long now = 1;
        for (int i = 0; i < 5; i++) {
            ui.update(800, 600, input, now += 16_000_000L);
        }
        @SuppressWarnings("unchecked")
        Binding<Object, Double> roughness = (Binding<Object, Double>) panel.row("roughness").bindings().get(0);
        roughness.control().set(0.3);
        ui.update(800, 600, input, now + 16_000_000L);
        assertThat(material.get().roughness()).isEqualTo(0.3f);
        assertThat(panel.row("baseColorTexture").readOnly()).isTrue();
    }
}
