package com.truesim.engine.ui.props;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.render.MaterialField;
import com.truesim.engine.render.MaterialParameters;
import com.truesim.ui.props.Hints;
import com.truesim.ui.props.Property;
import com.truesim.ui.props.PropertyModel;
import com.truesim.ui.props.PropertyModels;
import com.truesim.ui.props.PropertySource;
import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The material records generated from Slang's {@code [MaterialParameters]} structs as properties (design 5.11), so a
 * material inspector is generated: each record component a property, read by its accessor and written by the record's
 * generated wither ({@code withRoughness}), its {@code [Range]} (the record's {@link MaterialField} constants) a
 * {@code @Range} (sliders), {@code [Texture]} fields read-only texture slots ({@code @ReadOnly}, a tooltip saying so),
 * buffer addresses hidden, and the record's {@code DEFAULTS} (the Slang initializers) as its factory. The generator
 * already emits everything this needs, so the Slang generator is unchanged; this source reads it.
 *
 * <pre>{@code
 * MaterialParametersSource.register(); // once: PropertyModels.shared() answers material records
 * PropertyBuilder.forType(MetallicRoughnessParameters.class).build(controls, material);
 * }</pre>
 */
public final class MaterialParametersSource implements PropertySource {

    /** Its priority among property sources: over reflection's view of the same records. */
    public static final int PRIORITY = 100;
    private static final MaterialParametersSource SHARED = new MaterialParametersSource();
    private static boolean registered;

    /** Registers the source with {@link PropertyModels#shared()}, once. */
    public static synchronized void register() {
        if (!registered) {
            PropertyModels.shared().register(SHARED);
            registered = true;
        }
    }

    @Override
    public int priority() {
        return PRIORITY;
    }

    @Override
    @AllowAllocation("made once per type")
    public <T> PropertyModel<T> model(Class<T> type) {
        if (!type.isRecord() || !MaterialParameters.class.isAssignableFrom(type)) {
            return null;
        }
        PropertyModel.Builder<T> b = PropertyModel.builder(type).source("Slang material parameters");
        T defaults = defaults(type);
        if (defaults != null) {
            b.factory(() -> defaults);
        }
        int index = 0;
        for (RecordComponent component : type.getRecordComponents()) {
            b.property(property(type, component, index++));
        }
        return b.build();
    }

    @AllowAllocation("made once per type")
    private static <T, V> Property<T, V> property(Class<T> type, RecordComponent component, int index) {
        String name = component.getName();
        Method accessor = component.getAccessor();
        Method wither = wither(type, component);
        List<Annotation> hints = new ArrayList<>();
        MaterialField field = MaterialField.find(type, name);
        if (field != null) {
            if (field.kind() == MaterialField.Kind.F32 && Float.isFinite(field.low()) && Float.isFinite(field.high())) {
                hints.add(Hints.range(field.low(), field.high()));
            }
            if (field.texture()) {
                hints.add(Hints.readOnly());
                hints.add(Hints.tooltip("a texture slot, -1 for none: set by the material's textures"));
            }
            if (field.kind() == MaterialField.Kind.BUFFER) {
                hints.add(Hints.hidden());
            }
        }
        @SuppressWarnings("unchecked")
        java.util.function.Function<T, V> getter = whole -> (V) invoke(accessor, whole);
        Property.Builder<T, V> b = Property.<T, V>builder(name, component.getType()).getter(getter)
            .annotations(hints.toArray(new Annotation[0])).index(index);
        if (wither != null) {
            @SuppressWarnings("unchecked")
            java.util.function.BiFunction<T, V, T> with = (whole, value) -> (T) invoke(wither, whole, value);
            b.wither(with);
        }
        return b.build();
    }

    /** The record's {@code with<Name>} method, or null. */
    private static Method wither(Class<?> type, RecordComponent component) {
        String name = component.getName();
        try {
            return type.getMethod("with" + name.substring(0, 1).toUpperCase(Locale.ROOT) + name.substring(1),
                component.getType());
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    /** The record's {@code DEFAULTS}, or null. */
    private static <T> T defaults(Class<T> type) {
        try {
            Object value = type.getField("DEFAULTS").get(null);
            return type.isInstance(value) ? type.cast(value) : null;
        } catch (NoSuchFieldException | IllegalAccessException e) {
            return null;
        }
    }

    private static Object invoke(Method method, Object target, Object... arguments) {
        try {
            return method.invoke(target, arguments);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException r) {
                throw r; // a value out of the record's range: the binding shows it as invalid
            }
            throw new IllegalStateException(e.getCause());
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }
}
